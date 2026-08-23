#!/usr/bin/env python3
"""Evaluate the frozen embedded v2 model on a declared fresh holdout."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any

import joblib
import numpy as np
from sklearn.metrics import accuracy_score, balanced_accuracy_score, confusion_matrix, f1_score


LABELS = ["walking", "running", "cycling", "sitting", "lying"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--features",
        type=Path,
        default=Path("dataset/processed/embedded_v2_fresh_2026-08-23/features_leg.csv"),
    )
    parser.add_argument(
        "--model",
        type=Path,
        default=Path("dataset/models/leg_random_forest_embedded_v2.joblib"),
    )
    parser.add_argument(
        "--curation",
        type=Path,
        default=Path("dataset/curation/embedded_v2_fresh_holdout_2026-08-23.json"),
    )
    parser.add_argument(
        "--hashes",
        type=Path,
        default=Path("dataset/curation/embedded_v2_fresh_holdout_2026-08-23.sha256"),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("dataset/results/embedded_v2_fresh_holdout_2026-08-23"),
    )
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_frozen_raw(root: Path, hashes_path: Path) -> dict[str, str]:
    expected: dict[str, str] = {}
    for line in hashes_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        digest, name = line.split(maxsplit=1)
        expected[name.strip()] = digest.lower()
    actual_files = {path.name for path in root.glob("*.csv")}
    if actual_files != set(expected):
        raise ValueError(f"raw CSV set differs from frozen hash manifest: {sorted(actual_files ^ set(expected))}")
    for name, expected_digest in expected.items():
        actual_digest = sha256(root / name)
        if actual_digest != expected_digest:
            raise ValueError(f"SHA-256 mismatch for {name}")
    return expected


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open("r", newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def metric_block(y_true: np.ndarray, y_pred: np.ndarray) -> dict[str, Any]:
    observed = [label for label in LABELS if label in set(y_true)]
    return {
        "windows": int(len(y_true)),
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "balanced_accuracy": float(balanced_accuracy_score(y_true, y_pred)),
        "macro_f1": float(f1_score(y_true, y_pred, labels=observed, average="macro", zero_division=0)),
        "confusion_matrix_labels": LABELS,
        "confusion_matrix": confusion_matrix(y_true, y_pred, labels=LABELS).tolist(),
    }


def smooth(predictions: list[str]) -> list[str]:
    published: str | None = None
    candidate: str | None = None
    candidate_count = 0
    result: list[str] = []
    for raw in predictions:
        if published is None:
            published = raw
        elif raw == published:
            candidate = None
            candidate_count = 0
        elif raw == candidate:
            candidate_count += 1
            if candidate_count >= 2:
                published = raw
                candidate = None
                candidate_count = 0
        else:
            candidate = raw
            candidate_count = 1
        result.append(published)
    return result


def count_text(values: list[str]) -> str:
    counts = Counter(values)
    return ", ".join(f"{label} {counts[label]}" for label in LABELS if counts[label])


def main() -> int:
    args = parse_args()
    root = Path(__file__).resolve().parents[1]
    features_path = root / args.features
    model_path = root / args.model
    curation_path = root / args.curation
    hashes_path = root / args.hashes
    output_dir = root / args.output_dir

    curation = json.loads(curation_path.read_text(encoding="utf-8"))
    if curation.get("purpose") != "embedded_v2_fresh_generalization_holdout":
        raise ValueError("curation does not declare the fresh embedded-v2 holdout")
    raw_root = root / str(curation["dataset_root"])
    raw_hashes = verify_frozen_raw(raw_root, hashes_path)

    package = joblib.load(model_path)
    if package.get("schema_version") != 2 or package.get("purpose") != "embedded_leg_random_forest_augmented_v2":
        raise ValueError("unexpected model package")
    if package.get("device_id") != "18EE26A8":
        raise ValueError("model is not the Green leg classifier")

    rows = read_rows(features_path)
    feature_names = list(package["feature_names"])
    missing = [name for name in feature_names if name not in rows[0]]
    if missing:
        raise ValueError(f"missing model features: {missing}")
    matrix = np.asarray([[float(row[name]) for name in feature_names] for row in rows], dtype=np.float64)
    truth = np.asarray([row["label"] for row in rows])
    predictions = package["estimator"].predict(matrix)
    probabilities = package["estimator"].predict_proba(matrix)
    classes = list(package["estimator"].classes_)

    output_rows: list[dict[str, Any]] = []
    grouped_indices: dict[str, list[int]] = {}
    for index, row in enumerate(rows):
        grouped_indices.setdefault(row["file"], []).append(index)
    for file_name, indices in grouped_indices.items():
        ordered = sorted(indices, key=lambda index: int(rows[index]["window_id"]))
        raw_values = [str(predictions[index]) for index in ordered]
        published_values = smooth(raw_values)
        for index, published in zip(ordered, published_values, strict=True):
            predicted = str(predictions[index])
            confidence = float(probabilities[index, classes.index(predicted)])
            output_rows.append(
                {
                    "file": file_name,
                    "maneuver": curation.get("sessions", {}).get(file_name, ""),
                    "window_id": int(rows[index]["window_id"]),
                    "start_s": float(rows[index]["session_elapsed_start_s"]),
                    "end_s": float(rows[index]["session_elapsed_end_s"]),
                    "actual": rows[index]["label"],
                    "prediction": predicted,
                    "confidence": confidence,
                    "published_after_smoothing": published,
                }
            )

    output_rows.sort(key=lambda item: (item["file"], item["window_id"]))
    sessions: list[dict[str, Any]] = []
    for file_name in sorted(grouped_indices):
        selected = [row for row in output_rows if row["file"] == file_name]
        actual = str(selected[0]["actual"])
        raw_counts = Counter(str(row["prediction"]) for row in selected)
        max_count = max(raw_counts.values())
        winners = sorted(label for label, count in raw_counts.items() if count == max_count)
        majority = winners[0] if len(winners) == 1 else "tie:" + "/".join(winners)
        sessions.append(
            {
                "file": file_name,
                "maneuver": curation.get("sessions", {}).get(file_name, ""),
                "actual": actual,
                "windows": len(selected),
                "prediction_counts": {label: raw_counts[label] for label in LABELS if raw_counts[label]},
                "majority_prediction": majority,
                "majority_correct": majority == actual,
                "smoothed_counts": dict(Counter(str(row["published_after_smoothing"]) for row in selected)),
                "mean_confidence": float(np.mean([float(row["confidence"]) for row in selected])),
            }
        )

    smoothed_predictions = np.asarray([row["published_after_smoothing"] for row in output_rows])
    ordered_truth = np.asarray([row["actual"] for row in output_rows])
    report = {
        "schema_version": 1,
        "evaluation_type": "frozen_model_on_fresh_session_holdout",
        "warning": "Windows overlap by 50%; nine complete sessions from one participant are the independent experimental units.",
        "model": {
            "path": str(args.model).replace("\\", "/"),
            "sha256": sha256(model_path),
            "purpose": package["purpose"],
            "features": len(feature_names),
            "trees": int(package["model_parameters"]["trees"]),
        },
        "features_sha256": sha256(features_path),
        "raw_sha256": raw_hashes,
        "raw_window_metrics": metric_block(truth, predictions),
        "smoothed_window_metrics": metric_block(ordered_truth, smoothed_predictions),
        "session_majority": {
            "correct": sum(bool(session["majority_correct"]) for session in sessions),
            "total": len(sessions),
            "sessions": sessions,
        },
    }

    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "evaluation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    with (output_dir / "window_predictions.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(output_rows[0]))
        writer.writeheader()
        writer.writerows(output_rows)

    metrics = report["raw_window_metrics"]
    lines = [
        "# Embedded v2 fresh-session holdout",
        "",
        "The model was frozen before these files were copied or evaluated. This evaluator loads the saved v2 model and never fits or tunes an estimator.",
        "",
        "## Overall result",
        "",
        "| Windows | Accuracy | Balanced accuracy | Macro F1 | Session majority |",
        "|---:|---:|---:|---:|---:|",
        f"| {metrics['windows']} | {metrics['accuracy']:.3f} | {metrics['balanced_accuracy']:.3f} | {metrics['macro_f1']:.3f} | {report['session_majority']['correct']}/{report['session_majority']['total']} |",
        "",
        "## Complete sessions",
        "",
        "| File | Maneuver | Actual | Windows | Raw predictions | Majority | Mean confidence |",
        "|---|---|---|---:|---|---|---:|",
    ]
    for session in sessions:
        counts = ", ".join(f"{label} {count}" for label, count in session["prediction_counts"].items())
        lines.append(
            f"| `{session['file']}` | {session['maneuver']} | {session['actual']} | {session['windows']} | "
            f"{counts} | {session['majority_prediction']} | {session['mean_confidence']:.1%} |"
        )
    lines.extend(
        [
            "",
            "## Confusion matrix",
            "",
            "Rows are actual classes and columns are predicted classes.",
            "",
            "| Actual \\ Predicted | walking | running | cycling | sitting | lying |",
            "|---|---:|---:|---:|---:|---:|",
        ]
    )
    for label, values in zip(LABELS, metrics["confusion_matrix"], strict=True):
        lines.append(f"| {label} | " + " | ".join(str(value) for value in values) + " |")
    lines.extend(
        [
            "",
            report["warning"],
            "Window-level smoothing is saved for implementation analysis but raw forest predictions are the primary model metric.",
        ]
    )
    (output_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output_dir), "metrics": metrics, "sessions": report["session_majority"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
