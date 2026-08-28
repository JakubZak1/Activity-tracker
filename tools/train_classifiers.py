#!/usr/bin/env python3
"""Compare Random Forest and RBF SVM using paired-session grouped cross-validation."""

from __future__ import annotations

import argparse
import csv
import json
import os
from collections import Counter
from pathlib import Path
from typing import Any

import joblib
os.environ.setdefault("MPLBACKEND", "Agg")
os.environ.setdefault("MPLCONFIGDIR", str((Path("dataset/results") / ".matplotlib").resolve()))
import matplotlib.pyplot as plt
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import (
    accuracy_score,
    balanced_accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
)
from sklearn.model_selection import StratifiedKFold
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.svm import SVC


LABELS = ["walking", "running", "cycling", "sitting", "lying"]
METADATA_COLUMNS = {
    "file", "relative_path", "device_id", "placement", "label", "paired_session_id", "window_id",
    "start_ms", "end_ms", "session_elapsed_start_s", "session_elapsed_end_s", "sample_count",
}
COLORS = {"random_forest": "#377eb8", "svm_rbf": "#4daf4a"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--processed-dir", type=Path, default=Path("dataset/processed"))
    parser.add_argument("--models-dir", type=Path, default=Path("dataset/models"))
    parser.add_argument("--results-dir", type=Path, default=Path("dataset/results"))
    parser.add_argument("--folds", type=int, default=3)
    parser.add_argument("--seed", type=int, default=42)
    return parser.parse_args()


def load_features(path: Path) -> tuple[np.ndarray, np.ndarray, np.ndarray, list[str], list[dict[str, str]]]:
    with path.open("r", newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
        if not rows:
            raise ValueError(f"empty feature file: {path}")
        fieldnames = list(rows[0])
    feature_names = [name for name in fieldnames if name not in METADATA_COLUMNS]
    x = np.asarray([[float(row[name]) for name in feature_names] for row in rows], dtype=np.float64)
    y = np.asarray([row["label"] for row in rows])
    groups = np.asarray([row["paired_session_id"] for row in rows])
    if not np.all(np.isfinite(x)):
        raise ValueError(f"non-finite feature value in {path}")
    return x, y, groups, feature_names, rows


def make_group_folds(datasets: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray, list[str], list[dict[str, str]]]], folds: int, seed: int) -> dict[str, int]:
    group_labels: dict[str, str] = {}
    group_placements: dict[str, set[str]] = {}
    for placement, (_, labels, groups, _, _) in datasets.items():
        for label, group in zip(labels, groups, strict=True):
            existing = group_labels.setdefault(str(group), str(label))
            if existing != label:
                raise ValueError(f"paired session {group} contains labels {existing!r} and {label!r}")
            group_placements.setdefault(str(group), set()).add(placement)
    missing_pair = {group: places for group, places in group_placements.items() if places != set(datasets)}
    if missing_pair:
        raise ValueError(f"paired sessions absent from one placement: {missing_pair}")

    groups_sorted = np.asarray(sorted(group_labels))
    labels = np.asarray([group_labels[group] for group in groups_sorted])
    counts = Counter(labels)
    if min(counts.values()) < folds:
        raise ValueError(f"at least {folds} paired sessions per class are required, got {dict(counts)}")
    splitter = StratifiedKFold(n_splits=folds, shuffle=True, random_state=seed)
    assignments: dict[str, int] = {}
    for fold, (_, test_indices) in enumerate(splitter.split(groups_sorted, labels)):
        for index in test_indices:
            assignments[str(groups_sorted[index])] = fold
    return assignments


def make_estimator(model_name: str, seed: int):
    if model_name == "random_forest":
        return RandomForestClassifier(
            n_estimators=100,
            max_depth=12,
            min_samples_leaf=2,
            max_features="sqrt",
            class_weight="balanced_subsample",
            random_state=seed,
            n_jobs=-1,
        )
    if model_name == "svm_rbf":
        return Pipeline(
            [
                ("scale", StandardScaler()),
                ("model", SVC(kernel="rbf", C=10.0, gamma="scale", class_weight="balanced", cache_size=1024)),
            ]
        )
    raise ValueError(f"unknown model: {model_name}")


def metric_summary(y_true: np.ndarray, y_pred: np.ndarray) -> dict[str, Any]:
    return {
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "balanced_accuracy": float(balanced_accuracy_score(y_true, y_pred)),
        "macro_f1": float(f1_score(y_true, y_pred, labels=LABELS, average="macro", zero_division=0)),
        "classification_report": classification_report(
            y_true, y_pred, labels=LABELS, output_dict=True, zero_division=0
        ),
        "confusion_matrix": confusion_matrix(y_true, y_pred, labels=LABELS).tolist(),
    }


def evaluate_model(
    model_name: str,
    x: np.ndarray,
    y: np.ndarray,
    groups: np.ndarray,
    assignments: dict[str, int],
    folds: int,
    seed: int,
) -> tuple[dict[str, Any], np.ndarray]:
    predictions = np.empty_like(y)
    fold_metrics: list[dict[str, Any]] = []
    for fold in range(folds):
        test_mask = np.asarray([assignments[str(group)] == fold for group in groups])
        train_mask = ~test_mask
        if set(y[test_mask]) != set(LABELS) or set(y[train_mask]) != set(LABELS):
            raise ValueError(f"fold {fold} does not contain all labels in train and test")
        estimator = make_estimator(model_name, seed + fold)
        estimator.fit(x[train_mask], y[train_mask])
        predictions[test_mask] = estimator.predict(x[test_mask])
        metrics = metric_summary(y[test_mask], predictions[test_mask])
        metrics["fold"] = fold
        metrics["train_windows"] = int(np.sum(train_mask))
        metrics["test_windows"] = int(np.sum(test_mask))
        metrics["test_paired_sessions"] = int(len(set(groups[test_mask])))
        fold_metrics.append(metrics)
        print(
            f"fold,{model_name},{fold},macro_f1,{metrics['macro_f1']:.4f},"
            f"balanced_accuracy,{metrics['balanced_accuracy']:.4f}"
        )
    overall = metric_summary(y, predictions)
    overall["folds"] = fold_metrics
    return overall, predictions


def write_fold_assignments(path: Path, assignments: dict[str, int], datasets: dict[str, tuple]) -> None:
    labels: dict[str, str] = {}
    for _, y, groups, _, _ in datasets.values():
        labels.update({str(group): str(label) for group, label in zip(groups, y, strict=True)})
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["paired_session_id", "label", "fold"])
        writer.writeheader()
        for group in sorted(assignments):
            writer.writerow({"paired_session_id": group, "label": labels[group], "fold": assignments[group]})


def write_predictions(path: Path, rows: list[dict[str, str]], predictions: dict[str, np.ndarray], assignments: dict[str, int]) -> None:
    fields = ["placement", "file", "window_id", "paired_session_id", "fold", "label", *[f"pred_{name}" for name in predictions]]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for index, row in enumerate(rows):
            output = {
                "placement": row["placement"],
                "file": row["file"],
                "window_id": row["window_id"],
                "paired_session_id": row["paired_session_id"],
                "fold": assignments[row["paired_session_id"]],
                "label": row["label"],
            }
            output.update({f"pred_{name}": values[index] for name, values in predictions.items()})
            writer.writerow(output)


def plot_confusions(results: dict[str, dict[str, dict[str, Any]]], output: Path) -> None:
    fig, axes = plt.subplots(2, 2, figsize=(13, 11), constrained_layout=True)
    for row_index, placement in enumerate(("wrist", "leg")):
        for column_index, model_name in enumerate(("random_forest", "svm_rbf")):
            matrix = np.asarray(results[placement][model_name]["confusion_matrix"], dtype=float)
            normalized = matrix / np.maximum(matrix.sum(axis=1, keepdims=True), 1.0)
            axis = axes[row_index, column_index]
            image = axis.imshow(normalized, vmin=0, vmax=1, cmap="Blues")
            for i in range(len(LABELS)):
                for j in range(len(LABELS)):
                    axis.text(j, i, f"{int(matrix[i, j])}\n{normalized[i, j]:.1%}", ha="center", va="center", fontsize=8,
                              color="white" if normalized[i, j] > 0.55 else "black")
            axis.set_title(f"{placement.replace('wrist', 'Wrist').replace('leg', 'Leg')} — {model_name.replace('_', ' ').upper()}")
            axis.set_xticks(range(len(LABELS)), LABELS, rotation=35, ha="right")
            axis.set_yticks(range(len(LABELS)), LABELS)
            axis.set_xlabel("Predicted")
            axis.set_ylabel("Actual")
    fig.colorbar(image, ax=axes, shrink=0.72, label="Row-normalized share")
    fig.savefig(output, dpi=180)
    plt.close(fig)


def plot_comparison(results: dict[str, dict[str, dict[str, Any]]], output: Path) -> None:
    metrics = ["macro_f1", "balanced_accuracy", "accuracy"]
    fig, axes = plt.subplots(1, 2, figsize=(12, 5), sharey=True, constrained_layout=True)
    x = np.arange(len(metrics))
    width = 0.34
    for axis, placement in zip(axes, ("wrist", "leg"), strict=True):
        for offset, model_name in zip((-width / 2, width / 2), ("random_forest", "svm_rbf"), strict=True):
            values = [results[placement][model_name][metric] for metric in metrics]
            bars = axis.bar(x + offset, values, width, label=model_name.replace("_", " ").upper(), color=COLORS[model_name])
            axis.bar_label(bars, fmt="%.3f", padding=3, fontsize=8)
        axis.set_title("Wrist" if placement == "wrist" else "Leg")
        axis.set_xticks(x, ["Macro F1", "Balanced acc.", "Accuracy"])
        axis.set_ylim(0, 1.08)
        axis.grid(axis="y", alpha=0.25)
    axes[0].set_ylabel("Out-of-fold score")
    axes[1].legend(loc="lower right")
    fig.suptitle("Activity classification — paired-session 3-fold cross-validation")
    fig.savefig(output, dpi=180)
    plt.close(fig)


def write_reports(results_dir: Path, results: dict[str, dict[str, dict[str, Any]]], model_sizes: dict[str, dict[str, int]]) -> None:
    with (results_dir / "metrics.json").open("w", encoding="utf-8") as handle:
        json.dump(results, handle, indent=2)
        handle.write("\n")

    with (results_dir / "metrics_summary.csv").open("w", newline="", encoding="utf-8") as handle:
        fields = ["placement", "model", "accuracy", "balanced_accuracy", "macro_f1", "model_size_bytes"]
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for placement in ("wrist", "leg"):
            for model_name in ("random_forest", "svm_rbf"):
                metrics = results[placement][model_name]
                writer.writerow(
                    {
                        "placement": placement,
                        "model": model_name,
                        "accuracy": f"{metrics['accuracy']:.6f}",
                        "balanced_accuracy": f"{metrics['balanced_accuracy']:.6f}",
                        "macro_f1": f"{metrics['macro_f1']:.6f}",
                        "model_size_bytes": model_sizes[placement][model_name],
                    }
                )

    lines = [
        "# Model comparison",
        "",
        "Scores are out-of-fold predictions from deterministic 3-fold cross-validation. All overlapping windows from one `paired_session_id` stay in one fold, and both placements use the same fold assignment.",
        "",
        "| Placement | Model | Macro F1 | Balanced accuracy | Accuracy | Serialized size |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for placement in ("wrist", "leg"):
        for model_name in ("random_forest", "svm_rbf"):
            metrics = results[placement][model_name]
            size_mib = model_sizes[placement][model_name] / (1024 * 1024)
            lines.append(
                f"| {placement} | {model_name} | {metrics['macro_f1']:.3f} | {metrics['balanced_accuracy']:.3f} | "
                f"{metrics['accuracy']:.3f} | {size_mib:.2f} MiB |"
            )
    lines.extend(
        [
            "",
            "The saved joblib files are reproducible desktop research models, not firmware binaries. Embedded deployment requires selecting one model, reproducing the identical calibration/window/features on-device, and exporting or pruning it to fit XIAO nRF52840 memory.",
            "",
            "See `confusion_matrices.png`, `model_comparison.png`, `metrics.json`, `metrics_summary.csv`, and `oof_predictions_*.csv` in this directory.",
        ]
    )
    (results_dir / "model_report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    args.models_dir.mkdir(parents=True, exist_ok=True)
    args.results_dir.mkdir(parents=True, exist_ok=True)
    datasets = {
        placement: load_features(args.processed_dir / f"features_{placement}.csv")
        for placement in ("wrist", "leg")
    }
    if datasets["wrist"][3] != datasets["leg"][3]:
        raise ValueError("feature columns differ between wrist and leg")
    assignments = make_group_folds(datasets, args.folds, args.seed)
    write_fold_assignments(args.processed_dir / "fold_assignments.csv", assignments, datasets)

    results: dict[str, dict[str, dict[str, Any]]] = {"wrist": {}, "leg": {}}
    model_sizes: dict[str, dict[str, int]] = {"wrist": {}, "leg": {}}
    for placement, (x, y, groups, feature_names, rows) in datasets.items():
        predictions: dict[str, np.ndarray] = {}
        for model_name in ("random_forest", "svm_rbf"):
            metrics, predicted = evaluate_model(model_name, x, y, groups, assignments, args.folds, args.seed)
            results[placement][model_name] = metrics
            predictions[model_name] = predicted

            final_estimator = make_estimator(model_name, args.seed)
            final_estimator.fit(x, y)
            package = {
                "schema_version": 1,
                "placement": placement,
                "model_name": model_name,
                "classes": LABELS,
                "feature_names": feature_names,
                "sample_rate_hz": 52.0,
                "window_samples": 260,
                "window_s": 5.0,
                "overlap": 0.5,
                "calibration_device": "872F1832" if placement == "wrist" else "18EE26A8",
                "estimator": final_estimator,
            }
            model_path = args.models_dir / f"{placement}_{model_name}.joblib"
            joblib.dump(package, model_path, compress=3)
            model_sizes[placement][model_name] = model_path.stat().st_size
            print(
                f"result,{placement},{model_name},macro_f1,{metrics['macro_f1']:.4f},"
                f"balanced_accuracy,{metrics['balanced_accuracy']:.4f},model_bytes,{model_path.stat().st_size}"
            )
        write_predictions(args.results_dir / f"oof_predictions_{placement}.csv", rows, predictions, assignments)

    write_reports(args.results_dir, results, model_sizes)
    plot_confusions(results, args.results_dir / "confusion_matrices.png")
    plot_comparison(results, args.results_dir / "model_comparison.png")
    print(f"report,{args.results_dir / 'model_report.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
