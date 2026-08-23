#!/usr/bin/env python3
"""Compare embedded-friendly RF feature sets against grouped CV and live diagnostics."""

from __future__ import annotations

import csv
import json
from collections import Counter
from pathlib import Path
from typing import Any

import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import balanced_accuracy_score, f1_score


METADATA_COLUMNS = {
    "file", "relative_path", "device_id", "placement", "label", "paired_session_id", "window_id",
    "start_ms", "end_ms", "session_elapsed_start_s", "session_elapsed_end_s", "sample_count",
}
MAGNITUDE_PREFIXES = ("acc_mag_g_", "acc_dynamic_mag_g_", "gyro_mag_dps_")
AXIS_PREFIXES = ("acc_x_g_", "acc_y_g_", "acc_z_g_", "gyro_x_dps_", "gyro_y_dps_", "gyro_z_dps_")


def read_rows(path: Path) -> tuple[list[dict[str, str]], list[str]]:
    with path.open("r", newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        rows = list(reader)
        fields = reader.fieldnames or []
    return rows, [name for name in fields if name not in METADATA_COLUMNS]


def ordered(all_features: list[str], selected: set[str]) -> list[str]:
    return [name for name in all_features if name in selected]


def feature_sets(all_features: list[str]) -> dict[str, list[str]]:
    magnitude_basic = {
        name for name in all_features
        if name.startswith(MAGNITUDE_PREFIXES) and name.rsplit("_", 1)[-1] in {"mean", "std", "min", "max"}
    }
    acc_means = {f"acc_{axis}_g_mean" for axis in "xyz"}
    gyro_means = {f"gyro_{axis}_dps_mean" for axis in "xyz"}
    axis_stds = {
        *(f"acc_{axis}_g_std" for axis in "xyz"),
        *(f"gyro_{axis}_dps_std" for axis in "xyz"),
    }
    dominant = {name for name in all_features if name.startswith(MAGNITUDE_PREFIXES) and name.endswith("_dominant_hz")}
    spectral = {
        name for name in all_features
        if name.startswith(MAGNITUDE_PREFIXES)
        and name.endswith(("_dominant_hz", "_spectral_energy", "_spectral_entropy"))
    }
    magnitude_all = {name for name in all_features if name.startswith(MAGNITUDE_PREFIXES)}
    basic36 = {
        name for name in all_features
        if name.startswith((*AXIS_PREFIXES, *MAGNITUDE_PREFIXES))
        and name.rsplit("_", 1)[-1] in {"mean", "std", "min", "max"}
    }
    definitions = {
        "basic36_current": basic36,
        "robust15": magnitude_basic | acc_means,
        "robust18": magnitude_basic | acc_means | gyro_means,
        "cadence21": magnitude_basic | acc_means | gyro_means | dominant,
        "spectral27": magnitude_basic | acc_means | gyro_means | spectral,
        "axis_std24": magnitude_basic | acc_means | gyro_means | axis_stds,
        "axis_std_cadence27": magnitude_basic | acc_means | gyro_means | axis_stds | dominant,
        "magnitude_full39": magnitude_all | acc_means,
        "magnitude_full42": magnitude_all | acc_means | gyro_means,
    }
    return {name: ordered(all_features, values) for name, values in definitions.items()}


def make_model(trees: int, depth: int, min_leaf: int, seed: int) -> RandomForestClassifier:
    return RandomForestClassifier(
        n_estimators=trees,
        max_depth=depth,
        min_samples_leaf=min_leaf,
        max_features="sqrt",
        class_weight="balanced_subsample",
        random_state=seed,
        n_jobs=-1,
    )


def diagnostic_summary(rows: list[dict[str, str]], predictions: np.ndarray) -> tuple[int, int, float, dict[str, Any]]:
    by_file: dict[str, list[str]] = {}
    truth: dict[str, str] = {}
    for row, predicted in zip(rows, predictions, strict=True):
        by_file.setdefault(row["file"], []).append(str(predicted))
        truth[row["file"]] = row["label"]
    detail: dict[str, Any] = {}
    correct_sessions = 0
    for file_name, values in by_file.items():
        predicted = Counter(values).most_common(1)[0][0]
        correct_sessions += predicted == truth[file_name]
        detail[file_name] = {
            "actual": truth[file_name],
            "majority": predicted,
            "counts": dict(Counter(values)),
        }
    correct_windows = int(np.count_nonzero(predictions == np.asarray([row["label"] for row in rows])))
    return correct_sessions, correct_windows, correct_windows / len(rows), detail


def main() -> int:
    training_path = Path("dataset/processed/features_leg.csv")
    diagnostic_path = Path("dataset/processed/live_diagnostic_2026-08-23/features_leg.csv")
    assignments_path = Path("dataset/processed/fold_assignments.csv")
    output_dir = Path("dataset/results/live_feature_search_2026-08-23")
    output_dir.mkdir(parents=True, exist_ok=True)

    training_rows, all_features = read_rows(training_path)
    diagnostic_rows, diagnostic_features = read_rows(diagnostic_path)
    if diagnostic_features != all_features:
        raise ValueError("training and diagnostic feature schemas differ")
    assignments = {
        row["paired_session_id"]: int(row["fold"])
        for row in csv.DictReader(assignments_path.open("r", newline="", encoding="utf-8"))
    }
    y = np.asarray([row["label"] for row in training_rows])
    groups = np.asarray([row["paired_session_id"] for row in training_rows])
    diagnostic_y = np.asarray([row["label"] for row in diagnostic_rows])
    results: list[dict[str, Any]] = []
    details: dict[str, Any] = {}
    configurations = (
        (20, 8, 2),
        (30, 8, 2),
        (50, 8, 2),
        (30, 10, 2),
        (30, 8, 5),
    )

    for set_name, names in feature_sets(all_features).items():
        x = np.asarray([[float(row[name]) for name in names] for row in training_rows], dtype=np.float64)
        diagnostic_x = np.asarray([[float(row[name]) for name in names] for row in diagnostic_rows], dtype=np.float64)
        for trees, depth, min_leaf in configurations:
            out_of_fold = np.empty_like(y)
            for fold in range(3):
                test = np.asarray([assignments[group] == fold for group in groups])
                model = make_model(trees, depth, min_leaf, 4200 + fold)
                model.fit(x[~test], y[~test])
                out_of_fold[test] = model.predict(x[test])
            final_model = make_model(trees, depth, min_leaf, 42)
            final_model.fit(x, y)
            diagnostic_predictions = final_model.predict(diagnostic_x)
            sessions, windows, diagnostic_accuracy, detail = diagnostic_summary(
                diagnostic_rows, diagnostic_predictions
            )
            nodes = sum(estimator.tree_.node_count for estimator in final_model.estimators_)
            key = f"{set_name}_t{trees}_d{depth}_l{min_leaf}"
            result = {
                "key": key,
                "feature_set": set_name,
                "feature_count": len(names),
                "trees": trees,
                "max_depth": depth,
                "min_samples_leaf": min_leaf,
                "cv_macro_f1": float(f1_score(y, out_of_fold, average="macro", zero_division=0)),
                "cv_balanced_accuracy": float(balanced_accuracy_score(y, out_of_fold)),
                "diagnostic_sessions_correct": sessions,
                "diagnostic_sessions_total": len(detail),
                "diagnostic_windows_correct": windows,
                "diagnostic_windows_total": len(diagnostic_y),
                "diagnostic_window_accuracy": diagnostic_accuracy,
                "nodes": nodes,
            }
            results.append(result)
            details[key] = detail
            print(
                f"candidate,{key},cv_f1,{result['cv_macro_f1']:.4f},"
                f"diagnostic_sessions,{sessions}/{len(detail)},diagnostic_windows,{windows}/{len(diagnostic_y)}"
            )

    eligible = [row for row in results if row["cv_macro_f1"] >= 0.93]
    selected = max(
        eligible,
        key=lambda row: (
            row["diagnostic_sessions_correct"],
            row["diagnostic_window_accuracy"],
            row["cv_macro_f1"],
            -row["feature_count"],
            -row["nodes"],
        ),
    )
    fields = list(results[0])
    with (output_dir / "candidates.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(
            sorted(
                results,
                key=lambda row: (
                    -row["diagnostic_sessions_correct"],
                    -row["diagnostic_window_accuracy"],
                    -row["cv_macro_f1"],
                    row["feature_count"],
                ),
            )
        )
    payload = {
        "method": {
            "training": str(training_path),
            "development_diagnostic": str(diagnostic_path),
            "grouped_cv_minimum_macro_f1": 0.93,
            "selection_order": ["diagnostic session majority", "diagnostic window accuracy", "CV macro F1", "smaller model"],
            "warning": "The live diagnostic is development data after this search; record a fresh untouched repeat for final evaluation.",
        },
        "selected": selected,
        "selected_features": feature_sets(all_features)[selected["feature_set"]],
        "selected_diagnostic_detail": details[selected["key"]],
    }
    (output_dir / "selection.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print("selected," + json.dumps(selected, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
