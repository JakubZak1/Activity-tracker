#!/usr/bin/env python3
"""Train the augmented cadence-aware leg model and run leakage-aware checks."""

from __future__ import annotations

import csv
import json
from collections import Counter
from pathlib import Path
from typing import Any

import joblib
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, balanced_accuracy_score, confusion_matrix, f1_score


LABELS = ["walking", "running", "cycling", "sitting", "lying"]
METADATA_COLUMNS = {
    "file", "relative_path", "device_id", "placement", "label", "paired_session_id", "window_id",
    "start_ms", "end_ms", "session_elapsed_start_s", "session_elapsed_end_s", "sample_count",
}


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open("r", newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def matrix(rows: list[dict[str, str]], names: list[str]) -> tuple[np.ndarray, np.ndarray]:
    return (
        np.asarray([[float(row[name]) for name in names] for row in rows], dtype=np.float64),
        np.asarray([row["label"] for row in rows]),
    )


def make_model(seed: int) -> RandomForestClassifier:
    return RandomForestClassifier(
        n_estimators=20,
        max_depth=8,
        min_samples_leaf=2,
        max_features="sqrt",
        class_weight="balanced_subsample",
        random_state=seed,
        n_jobs=-1,
    )


def metrics(y_true: np.ndarray, y_pred: np.ndarray) -> dict[str, Any]:
    observed = [label for label in LABELS if label in set(y_true)]
    return {
        "windows": int(len(y_true)),
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "balanced_accuracy": float(balanced_accuracy_score(y_true, y_pred)),
        "macro_f1_observed": float(f1_score(y_true, y_pred, labels=observed, average="macro", zero_division=0)),
        "confusion_matrix": confusion_matrix(y_true, y_pred, labels=LABELS).tolist(),
    }


def session_detail(rows: list[dict[str, str]], predictions: np.ndarray) -> dict[str, Any]:
    by_file: dict[str, list[str]] = {}
    truth: dict[str, str] = {}
    for row, predicted in zip(rows, predictions, strict=True):
        by_file.setdefault(row["file"], []).append(str(predicted))
        truth[row["file"]] = row["label"]
    sessions = []
    for file_name in sorted(by_file):
        counts = Counter(by_file[file_name])
        majority = counts.most_common(1)[0][0]
        sessions.append(
            {
                "file": file_name,
                "actual": truth[file_name],
                "predicted": majority,
                "correct": majority == truth[file_name],
                "windows": len(by_file[file_name]),
                "counts": dict(counts),
            }
        )
    return {
        "correct": sum(item["correct"] for item in sessions),
        "total": len(sessions),
        "sessions": sessions,
    }


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    training_rows = read_rows(root / "dataset/processed/features_leg.csv")
    development_rows = read_rows(root / "dataset/processed/live_diagnostic_2026-08-23/features_leg.csv")
    holdout_rows = read_rows(root / "dataset/processed/holdout/features_leg.csv")
    selection = json.loads(
        (root / "dataset/results/live_feature_search_2026-08-23/selection.json").read_text(encoding="utf-8")
    )
    feature_names = list(selection["selected_features"])
    if selection["selected"]["key"] != "axis_std_cadence27_t20_d8_l2":
        raise ValueError("unexpected feature-search selection")

    training_x, training_y = matrix(training_rows, feature_names)
    development_x, development_y = matrix(development_rows, feature_names)
    holdout_x, holdout_y = matrix(holdout_rows, feature_names)

    # Engineering LOSO: each new physical file is evaluated by a model that saw
    # the original dataset and the other five diagnostic files, never itself.
    development_predictions = np.empty_like(development_y)
    development_files = np.asarray([row["file"] for row in development_rows])
    unique_files = sorted(set(development_files))
    for index, file_name in enumerate(unique_files):
        dev_test = development_files == file_name
        model = make_model(7300 + index)
        x_fit = np.concatenate((training_x, development_x[~dev_test]), axis=0)
        y_fit = np.concatenate((training_y, development_y[~dev_test]), axis=0)
        model.fit(x_fit, y_fit)
        development_predictions[dev_test] = model.predict(development_x[dev_test])

    # Original grouped-CV folds remain untouched; augmentation sessions are
    # extra training-only groups in every fold. This is a regression check, not
    # a replacement for the original benchmark.
    assignments = {
        row["paired_session_id"]: int(row["fold"])
        for row in csv.DictReader(
            (root / "dataset/processed/fold_assignments.csv").open("r", newline="", encoding="utf-8")
        )
    }
    groups = np.asarray([row["paired_session_id"] for row in training_rows])
    augmented_cv_predictions = np.empty_like(training_y)
    for fold in range(3):
        test = np.asarray([assignments[group] == fold for group in groups])
        model = make_model(7400 + fold)
        x_fit = np.concatenate((training_x[~test], development_x), axis=0)
        y_fit = np.concatenate((training_y[~test], development_y), axis=0)
        model.fit(x_fit, y_fit)
        augmented_cv_predictions[test] = model.predict(training_x[test])

    final_x = np.concatenate((training_x, development_x), axis=0)
    final_y = np.concatenate((training_y, development_y), axis=0)
    final_model = make_model(42)
    final_model.fit(final_x, final_y)
    holdout_predictions = final_model.predict(holdout_x)
    development_fit_predictions = final_model.predict(development_x)

    nodes = sum(estimator.tree_.node_count for estimator in final_model.estimators_)
    package = {
        "schema_version": 2,
        "purpose": "embedded_leg_random_forest_augmented_v2",
        "selection_rule": "fixed axis_std_cadence27 candidate; original training plus six declared development sessions",
        "placement": "leg",
        "device_id": "18EE26A8",
        "sample_rate_hz": 52.0,
        "window_samples": 260,
        "stride_samples": 130,
        "classes": LABELS,
        "feature_names": feature_names,
        "training_sources": {
            "original_windows": len(training_rows),
            "development_windows": len(development_rows),
            "development_files": unique_files,
        },
        "model_parameters": {
            "trees": 20,
            "max_depth": 8,
            "min_samples_leaf": 2,
            "random_state": 42,
        },
        "estimator": final_model,
    }
    model_path = root / "dataset/models/leg_random_forest_embedded_v2.joblib"
    joblib.dump(package, model_path, compress=3)

    report = {
        "schema_version": 1,
        "method_warning": "The six live diagnostic files are development data. LOSO is an engineering check after feature selection, not a final untouched evaluation.",
        "feature_count": len(feature_names),
        "features": feature_names,
        "model_nodes": nodes,
        "development_loso": {
            **metrics(development_y, development_predictions),
            "session_majority": session_detail(development_rows, development_predictions),
        },
        "original_grouped_cv_with_development_augmentation": metrics(training_y, augmented_cv_predictions),
        "old_holdout_regression": {
            **metrics(holdout_y, holdout_predictions),
            "session_majority": session_detail(holdout_rows, holdout_predictions),
        },
        "development_resubstitution_not_an_evaluation": {
            **metrics(development_y, development_fit_predictions),
            "session_majority": session_detail(development_rows, development_fit_predictions),
        },
    }
    output_dir = root / "dataset/results/embedded_v2"
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "evaluation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# Embedded leg model v2 engineering evaluation",
        "",
        report["method_warning"],
        "",
        "| Check | Windows | Macro F1 | Balanced accuracy | Accuracy | Session majority |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for label, key in (
        ("Development LOSO", "development_loso"),
        ("Original grouped CV with augmentation", "original_grouped_cv_with_development_augmentation"),
        ("Old holdout regression", "old_holdout_regression"),
        ("Development resubstitution (not evaluation)", "development_resubstitution_not_an_evaluation"),
    ):
        value = report[key]
        session = value.get("session_majority")
        shown_session = f"{session['correct']}/{session['total']}" if session else "n/a"
        lines.append(
            f"| {label} | {value['windows']} | {value['macro_f1_observed']:.3f} | "
            f"{value['balanced_accuracy']:.3f} | {value['accuracy']:.3f} | {shown_session} |"
        )
    lines.extend(["", "## Development LOSO sessions", "", "| File | Actual | Predicted | Counts |", "|---|---|---|---|"])
    for item in report["development_loso"]["session_majority"]["sessions"]:
        lines.append(
            f"| `{item['file']}` | {item['actual']} | {item['predicted']} | "
            f"{', '.join(f'{key} {value}' for key, value in item['counts'].items())} |"
        )
    lines.extend(
        [
            "",
            "The model may be deployed for a fresh test only if export parity and native/firmware tests pass. The fresh repeat remains the final decision gate.",
        ]
    )
    (output_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"model": str(model_path), "nodes": nodes, "evaluation": report}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
