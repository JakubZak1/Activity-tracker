#!/usr/bin/env python3
"""Evaluate frozen and invariant-feature candidates on live diagnostic CSVs."""

from __future__ import annotations

import argparse
import csv
import json
import statistics
from collections import Counter
from pathlib import Path
from typing import Any

import joblib
import numpy as np
from sklearn.ensemble import RandomForestClassifier

from ml_dataset import (
    SENSOR_COLUMNS,
    apply_calibration,
    crc32_file,
    extract_features,
    inspect_csv,
    load_calibrations,
    load_samples,
)


WINDOW_SAMPLES = 260
STRIDE_SAMPLES = 130
SAMPLE_RATE_HZ = 52.0
MAGNITUDE_PREFIXES = ("acc_mag_g", "acc_dynamic_mag_g", "gyro_mag_dps")
STATS = ("mean", "std", "min", "max")
ROBUST_FEATURES = [f"{prefix}_{stat}" for prefix in MAGNITUDE_PREFIXES for stat in STATS] + [
    "acc_x_g_mean",
    "acc_y_g_mean",
    "acc_z_g_mean",
    "gyro_x_dps_mean",
    "gyro_y_dps_mean",
    "gyro_z_dps_mean",
]
METADATA_COLUMNS = {
    "file", "relative_path", "device_id", "placement", "label", "paired_session_id", "window_id",
    "start_ms", "end_ms", "session_elapsed_start_s", "session_elapsed_end_s", "sample_count",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-dir", type=Path, default=Path("dataset/raw/holdout/test"))
    parser.add_argument("--models-dir", type=Path, default=Path("dataset/models"))
    parser.add_argument("--training-features", type=Path, default=Path("dataset/processed/features_leg.csv"))
    parser.add_argument("--calibration-dir", type=Path, default=Path("calibration"))
    parser.add_argument("--output-dir", type=Path, default=Path("dataset/results/live_diagnostic_2026-08-23"))
    return parser.parse_args()


def load_descriptions(path: Path) -> dict[str, str]:
    descriptions: dict[str, str] = {}
    if not path.exists():
        return descriptions
    for line in path.read_text(encoding="utf-8").splitlines():
        if " - " not in line:
            continue
        key, description = line.split(" - ", 1)
        descriptions[key.strip()] = description.strip()
    return descriptions


def description_for(file_name: str, descriptions: dict[str, str]) -> str:
    short = file_name.removeprefix("18ee26a8_").removesuffix(".csv")
    return descriptions.get(short, short)


def read_training(path: Path) -> list[dict[str, str]]:
    with path.open("r", newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def fit_robust_candidate(rows: list[dict[str, str]]) -> RandomForestClassifier:
    x = np.asarray([[float(row[name]) for name in ROBUST_FEATURES] for row in rows], dtype=np.float64)
    y = np.asarray([row["label"] for row in rows])
    model = RandomForestClassifier(
        n_estimators=30,
        max_depth=8,
        min_samples_leaf=2,
        max_features="sqrt",
        class_weight="balanced_subsample",
        random_state=42,
        n_jobs=-1,
    )
    model.fit(x, y)
    return model


def prediction(model: Any, features: dict[str, float], names: list[str]) -> tuple[str, int]:
    vector = np.asarray([[features[name] for name in names]], dtype=np.float64)
    predicted = str(model.predict(vector)[0])
    if not hasattr(model, "predict_proba"):
        return predicted, -1
    probabilities = model.predict_proba(vector)[0]
    class_index = list(model.classes_).index(predicted)
    return predicted, int(round(float(probabilities[class_index]) * 100.0))


def smooth_current(rows: list[dict[str, Any]]) -> None:
    published: str | None = None
    published_confidence = 0
    candidate: str | None = None
    candidate_count = 0
    for row in rows:
        raw = str(row["embedded36_prediction"])
        confidence = int(row["embedded36_confidence_percent"])
        if published is None:
            published = raw
            published_confidence = confidence
        elif raw == published:
            published_confidence = confidence
            candidate = None
            candidate_count = 0
        elif raw == candidate:
            candidate_count += 1
            if candidate_count >= 2:
                published = raw
                published_confidence = confidence
                candidate = None
                candidate_count = 0
        else:
            candidate = raw
            candidate_count = 1
        row["embedded36_published"] = published
        row["embedded36_published_confidence_percent"] = published_confidence


def counts_text(rows: list[dict[str, Any]], column: str) -> str:
    counts = Counter(str(row[column]) for row in rows)
    return ", ".join(f"{label} {count}/{len(rows)}" for label, count in counts.most_common())


def majority(rows: list[dict[str, Any]], column: str) -> str:
    return Counter(str(row[column]) for row in rows).most_common(1)[0][0] if rows else "none"


def main() -> int:
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    descriptions = load_descriptions(args.raw_dir / "opis.txt")
    calibrations = load_calibrations(args.calibration_dir)
    calibration = calibrations["18EE26A8"]
    embedded_package = joblib.load(args.models_dir / "leg_random_forest_embedded.joblib")
    desktop_rf_package = joblib.load(args.models_dir / "leg_random_forest.joblib")
    desktop_svm_package = joblib.load(args.models_dir / "leg_svm_rbf.joblib")
    training_rows = read_training(args.training_features)
    robust_model = fit_robust_candidate(training_rows)

    integrity: list[dict[str, Any]] = []
    window_rows: list[dict[str, Any]] = []
    for path in sorted(args.raw_dir.glob("*.csv")):
        sidecar_path = Path(f"{path}.session.json")
        sidecar = json.loads(sidecar_path.read_text(encoding="utf-8"))
        rows, first_ts, last_ts, raw_label, issues = inspect_csv(path)
        actual_size = path.stat().st_size
        actual_crc = crc32_file(path)
        if actual_size != int(sidecar["size_bytes"]):
            issues.append("sidecar size mismatch")
        if actual_crc != str(sidecar["crc32"]).upper():
            issues.append("sidecar CRC32 mismatch")
        if sidecar.get("device_identity") != "95D112A518EE26A8":
            issues.append("unexpected device identity")
        if sidecar.get("activity") != raw_label:
            issues.append("sidecar/CSV label mismatch")
        timestamps, values = load_samples(path)
        corrected = apply_calibration(values, calibration)
        duration_s = ((last_ts or 0) - (first_ts or 0)) / 1000.0 if rows else 0.0
        effective_hz = (rows - 1) / duration_s if duration_s > 0 else 0.0
        intervals = np.diff(timestamps)
        integrity.append(
            {
                "file": path.name,
                "description": description_for(path.name, descriptions),
                "label": raw_label,
                "rows": rows,
                "duration_s": duration_s,
                "effective_hz": effective_hz,
                "interval_19_ms": int(np.count_nonzero(intervals == 19)),
                "interval_20_ms": int(np.count_nonzero(intervals == 20)),
                "other_intervals": int(np.count_nonzero((intervals != 19) & (intervals != 20))),
                "size_match": actual_size == int(sidecar["size_bytes"]),
                "crc_match": actual_crc == str(sidecar["crc32"]).upper(),
                "issues": "; ".join(issues),
            }
        )
        file_windows: list[dict[str, Any]] = []
        for window_id, start in enumerate(range(0, len(timestamps) - WINDOW_SAMPLES + 1, STRIDE_SAMPLES)):
            stop = start + WINDOW_SAMPLES
            features = extract_features(corrected[start:stop], SAMPLE_RATE_HZ)
            embedded_label, embedded_confidence = prediction(
                embedded_package["estimator"], features, embedded_package["feature_names"]
            )
            robust_label, robust_confidence = prediction(robust_model, features, ROBUST_FEATURES)
            desktop_rf_label, desktop_rf_confidence = prediction(
                desktop_rf_package["estimator"], features, desktop_rf_package["feature_names"]
            )
            desktop_svm_label, desktop_svm_confidence = prediction(
                desktop_svm_package["estimator"], features, desktop_svm_package["feature_names"]
            )
            file_windows.append(
                {
                    "file": path.name,
                    "description": description_for(path.name, descriptions),
                    "actual_label": raw_label,
                    "window_id": window_id,
                    "start_s": (int(timestamps[start]) - int(timestamps[0])) / 1000.0,
                    "end_s": (int(timestamps[stop - 1]) - int(timestamps[0])) / 1000.0,
                    "embedded36_prediction": embedded_label,
                    "embedded36_confidence_percent": embedded_confidence,
                    "robust18_prediction": robust_label,
                    "robust18_confidence_percent": robust_confidence,
                    "desktop_rf_prediction": desktop_rf_label,
                    "desktop_rf_confidence_percent": desktop_rf_confidence,
                    "desktop_svm_prediction": desktop_svm_label,
                    "desktop_svm_confidence_percent": desktop_svm_confidence,
                    "acc_dynamic_mag_g_mean": features["acc_dynamic_mag_g_mean"],
                    "gyro_mag_dps_mean": features["gyro_mag_dps_mean"],
                }
            )
        smooth_current(file_windows)
        window_rows.extend(file_windows)

    integrity_fields = list(integrity[0])
    with (args.output_dir / "integrity.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=integrity_fields)
        writer.writeheader()
        writer.writerows(integrity)
    window_fields = list(window_rows[0])
    with (args.output_dir / "window_predictions.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=window_fields)
        writer.writeheader()
        writer.writerows(window_rows)

    report = [
        "# Live corridor diagnostic",
        "",
        "These post-deployment files are evaluated read-only and are not used to fit any reported model. The robust18 candidate is fitted only on the original training feature table.",
        "",
        "## Integrity",
        "",
        "| File | Maneuver | Rows | Duration | Rate | 19/20 ms | Other | Size | CRC | Issues |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---|",
    ]
    for item in integrity:
        report.append(
            f"| `{item['file']}` | {item['description']} | {item['rows']} | {item['duration_s']:.1f} s | "
            f"{item['effective_hz']:.3f} Hz | {item['interval_19_ms'] + item['interval_20_ms']} | "
            f"{item['other_intervals']} | {'OK' if item['size_match'] else 'FAIL'} | "
            f"{'OK' if item['crc_match'] else 'FAIL'} | {item['issues'] or 'none'} |"
        )
    report.extend(
        [
            "",
            "## Predictions by complete file",
            "",
            "`embedded36 raw` reproduces each 5-second forest prediction. `embedded36 published` additionally reproduces the two-window class-change smoothing used by firmware.",
            "",
            "| Maneuver | Windows | Actual | Embedded36 raw | Embedded36 published | Robust18 | Desktop RF | Desktop SVM |",
            "|---|---:|---|---|---|---|---|---|",
        ]
    )
    for item in integrity:
        selected = [row for row in window_rows if row["file"] == item["file"]]
        report.append(
            f"| {item['description']} | {len(selected)} | {item['label']} | "
            f"{counts_text(selected, 'embedded36_prediction')} | "
            f"{counts_text(selected, 'embedded36_published')} | "
            f"{counts_text(selected, 'robust18_prediction')} | "
            f"{counts_text(selected, 'desktop_rf_prediction')} | "
            f"{counts_text(selected, 'desktop_svm_prediction')} |"
        )
    report.extend(
        [
            "",
            "## Session-majority summary",
            "",
            "| Maneuver | Actual | Embedded36 | Robust18 | Desktop RF | Desktop SVM |",
            "|---|---|---|---|---|---|",
        ]
    )
    for item in integrity:
        selected = [row for row in window_rows if row["file"] == item["file"]]
        report.append(
            f"| {item['description']} | {item['label']} | {majority(selected, 'embedded36_prediction')} | "
            f"{majority(selected, 'robust18_prediction')} | {majority(selected, 'desktop_rf_prediction')} | "
            f"{majority(selected, 'desktop_svm_prediction')} |"
        )
    report.extend(
        [
            "",
            "## Motion-amplitude comparison",
            "",
            "The table uses medians across 5-second windows. It explains similarity in the current feature space; it does not prove that intensity alone causes every error.",
            "",
            "| Source | Dynamic acceleration mean | Gyroscope magnitude mean |",
            "|---|---:|---:|",
        ]
    )
    for label in ("walking", "running", "cycling", "sitting", "lying"):
        selected_training = [row for row in training_rows if row["label"] == label]
        report.append(
            f"| training: {label} | "
            f"{statistics.median(float(row['acc_dynamic_mag_g_mean']) for row in selected_training):.3f} g | "
            f"{statistics.median(float(row['gyro_mag_dps_mean']) for row in selected_training):.1f} dps |"
        )
    for item in integrity:
        selected = [row for row in window_rows if row["file"] == item["file"]]
        report.append(
            f"| diagnostic: {item['description']} | "
            f"{statistics.median(float(row['acc_dynamic_mag_g_mean']) for row in selected):.3f} g | "
            f"{statistics.median(float(row['gyro_mag_dps_mean']) for row in selected):.1f} dps |"
        )
    report.extend(
        [
            "",
            "## Interpretation",
            "",
            "- The current embedded model reproduces the user's live observations; this is not a display-label mapping fault.",
            "- Slow straight walking lies much closer to the training cycling amplitude than to ordinary training walking. Slow running lies near training walking. This supports a pace/domain-coverage explanation.",
            "- The robust18 candidate substantially reduces the left/right turning asymmetry, but it still maps slow walking to cycling and slow running to walking. Removing axis extrema alone therefore does not solve pace generalization.",
            "- Desktop SVM recognizes all slow-straight walking windows, but maps every circular-walking window in both directions to cycling. No evaluated existing model solves all six maneuvers.",
            "- These sessions should remain a development diagnostic. If they guide feature/model changes, a separately recorded repeat must be retained as the final untouched test.",
            "",
            "Window counts are descriptive diagnostics, not independent-trial accuracy: neighboring windows overlap by 50% and all six files come from one participant and one short test period.",
        ]
    )
    (args.output_dir / "report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(json.dumps({"files": len(integrity), "windows": len(window_rows), "output": str(args.output_dir)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
