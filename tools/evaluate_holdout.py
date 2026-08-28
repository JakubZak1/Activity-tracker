#!/usr/bin/env python3
"""Evaluate frozen research models on post-training holdout sessions."""

from __future__ import annotations

import argparse
import csv
import json
import os
from collections import Counter
from pathlib import Path
from typing import Any

import joblib
import numpy as np

os.environ.setdefault("MPLBACKEND", "Agg")
os.environ.setdefault("MPLCONFIGDIR", str((Path("dataset/results") / ".matplotlib").resolve()))
import matplotlib.pyplot as plt
from sklearn.metrics import accuracy_score, balanced_accuracy_score, classification_report, confusion_matrix, f1_score


LABELS = ["walking", "running", "cycling", "sitting", "lying"]
METADATA_COLUMNS = {
    "file", "relative_path", "device_id", "placement", "label", "paired_session_id", "window_id",
    "start_ms", "end_ms", "session_elapsed_start_s", "session_elapsed_end_s", "sample_count",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--features-dir", type=Path, default=Path("dataset/processed/holdout"))
    parser.add_argument("--training-features-dir", type=Path, default=Path("dataset/processed"))
    parser.add_argument("--models-dir", type=Path, default=Path("dataset/models"))
    parser.add_argument("--results-dir", type=Path, default=Path("dataset/results/holdout"))
    parser.add_argument("--cv-metrics", type=Path, default=Path("dataset/results/metrics.json"))
    parser.add_argument("--allow-incomplete", action="store_true")
    return parser.parse_args()


def read_feature_rows(path: Path) -> tuple[list[dict[str, str]], list[str]]:
    with path.open("r", newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        rows = list(reader)
        fieldnames = reader.fieldnames or []
    if not rows:
        raise ValueError(f"no windows in {path}")
    return rows, [name for name in fieldnames if name not in METADATA_COLUMNS]


def groups_from_features(path: Path) -> set[str]:
    with path.open("r", newline="", encoding="utf-8") as handle:
        return {row["paired_session_id"] for row in csv.DictReader(handle)}


def summarize(y_true: np.ndarray, y_pred: np.ndarray) -> dict[str, Any]:
    observed_labels = [label for label in LABELS if label in set(y_true)]
    complete = observed_labels == LABELS
    result: dict[str, Any] = {
        "complete_five_class_holdout": complete,
        "observed_labels": observed_labels,
        "windows": int(len(y_true)),
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "balanced_accuracy_observed": float(balanced_accuracy_score(y_true, y_pred)),
        "macro_f1_observed": float(f1_score(y_true, y_pred, labels=observed_labels, average="macro", zero_division=0)),
        "macro_f1_five_class": float(f1_score(y_true, y_pred, labels=LABELS, average="macro", zero_division=0)) if complete else None,
        "classification_report": classification_report(y_true, y_pred, labels=LABELS, output_dict=True, zero_division=0),
        "confusion_matrix": confusion_matrix(y_true, y_pred, labels=LABELS).tolist(),
    }
    return result


def session_majority(rows: list[dict[str, str]], predictions: np.ndarray) -> dict[str, Any]:
    by_group: dict[str, list[str]] = {}
    truth: dict[str, str] = {}
    for row, prediction in zip(rows, predictions, strict=True):
        group = row["paired_session_id"]
        by_group.setdefault(group, []).append(str(prediction))
        truth[group] = row["label"]
    predicted = {group: Counter(values).most_common(1)[0][0] for group, values in by_group.items()}
    correct = sum(predicted[group] == truth[group] for group in truth)
    return {
        "correct": correct,
        "total": len(truth),
        "accuracy": correct / len(truth) if truth else 0.0,
        "sessions": [
            {
                "paired_session_id": group,
                "actual": truth[group],
                "predicted": predicted[group],
                "windows": len(by_group[group]),
            }
            for group in sorted(truth)
        ],
    }


def plot_confusions(results: dict[str, dict[str, dict[str, Any]]], output: Path) -> None:
    fig, axes = plt.subplots(2, 2, figsize=(13, 11), constrained_layout=True)
    image = None
    for row_index, placement in enumerate(("wrist", "leg")):
        for column_index, model_name in enumerate(("random_forest", "svm_rbf")):
            metrics = results[placement][model_name]
            matrix = np.asarray(metrics["confusion_matrix"], dtype=float)
            normalized = matrix / np.maximum(matrix.sum(axis=1, keepdims=True), 1.0)
            axis = axes[row_index, column_index]
            image = axis.imshow(normalized, vmin=0, vmax=1, cmap="Purples")
            for i in range(len(LABELS)):
                for j in range(len(LABELS)):
                    axis.text(
                        j, i, f"{int(matrix[i, j])}\n{normalized[i, j]:.1%}", ha="center", va="center", fontsize=8,
                        color="white" if normalized[i, j] > 0.55 else "black",
                    )
            suffix = "" if metrics["complete_five_class_holdout"] else " (incomplete)"
            axis.set_title(f"{placement.title()} — {model_name.replace('_', ' ').upper()}{suffix}")
            axis.set_xticks(range(len(LABELS)), LABELS, rotation=35, ha="right")
            axis.set_yticks(range(len(LABELS)), LABELS)
            axis.set_xlabel("Predicted")
            axis.set_ylabel("Actual")
    if image is not None:
        fig.colorbar(image, ax=axes, shrink=0.72, label="Row-normalized share")
    fig.savefig(output, dpi=180)
    plt.close(fig)


def write_predictions(path: Path, rows: list[dict[str, str]], predictions: dict[str, np.ndarray]) -> None:
    fields = ["placement", "file", "window_id", "paired_session_id", "label", *[f"pred_{name}" for name in predictions]]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for index, row in enumerate(rows):
            output = {name: row[name] for name in ("placement", "file", "window_id", "paired_session_id", "label")}
            output.update({f"pred_{name}": values[index] for name, values in predictions.items()})
            writer.writerow(output)


def plot_cv_comparison(results: dict[str, dict[str, dict[str, Any]]], cv_results: dict[str, Any], output: Path) -> None:
    configurations = [(placement, model) for placement in ("wrist", "leg") for model in ("random_forest", "svm_rbf")]
    labels = [f"{placement}\n{model.replace('_', ' ').upper()}" for placement, model in configurations]
    cv_values = [cv_results[placement][model]["macro_f1"] for placement, model in configurations]
    holdout_values = [results[placement][model]["macro_f1_five_class"] for placement, model in configurations]
    x = np.arange(len(configurations))
    width = 0.36
    fig, axis = plt.subplots(figsize=(11, 5.5), constrained_layout=True)
    cv_bars = axis.bar(x - width / 2, cv_values, width, label="Grouped CV", color="#377eb8")
    holdout_bars = axis.bar(x + width / 2, holdout_values, width, label="Frozen holdout", color="#984ea3")
    axis.bar_label(cv_bars, fmt="%.3f", padding=3, fontsize=8)
    axis.bar_label(holdout_bars, fmt="%.3f", padding=3, fontsize=8)
    axis.set_xticks(x, labels)
    axis.set_ylim(0, 1.08)
    axis.set_ylabel("Macro F1")
    axis.set_title("Grouped cross-validation vs post-training holdout")
    axis.grid(axis="y", alpha=0.25)
    axis.legend(loc="lower right")
    fig.savefig(output, dpi=180)
    plt.close(fig)


def write_report(path: Path, results: dict[str, dict[str, dict[str, Any]]], cv_results: dict[str, Any], overlap_count: int) -> None:
    lines = [
        "# Frozen-model holdout evaluation",
        "",
        "The four models were loaded from disk and used without fitting or parameter selection on these sessions.",
        f"Training/holdout paired-session overlap: **{overlap_count}**.",
        "",
        "| Placement | Model | Complete | Windows | Macro F1 | Balanced accuracy | Accuracy | Session majority |",
        "|---|---|---:|---:|---:|---:|---:|---:|",
    ]
    for placement in ("wrist", "leg"):
        for model_name in ("random_forest", "svm_rbf"):
            metric = results[placement][model_name]
            macro = metric["macro_f1_five_class"] if metric["complete_five_class_holdout"] else metric["macro_f1_observed"]
            majority = metric["session_majority"]
            lines.append(
                f"| {placement} | {model_name} | {metric['complete_five_class_holdout']} | {metric['windows']} | "
                f"{macro:.3f} | {metric['balanced_accuracy_observed']:.3f} | {metric['accuracy']:.3f} | "
                f"{majority['correct']}/{majority['total']} |"
            )
    lines.extend(
        [
            "",
            "One new session per class is an honest post-training check but still has high uncertainty; it does not establish generalization to other users.",
            "",
            "| Placement | Model | Grouped-CV macro F1 | Holdout macro F1 | Difference |",
            "|---|---|---:|---:|---:|",
        ]
    )
    for placement in ("wrist", "leg"):
        for model_name in ("random_forest", "svm_rbf"):
            cv_score = cv_results[placement][model_name]["macro_f1"]
            holdout_score = results[placement][model_name]["macro_f1_five_class"]
            lines.append(
                f"| {placement} | {model_name} | {cv_score:.3f} | {holdout_score:.3f} | {holdout_score - cv_score:+.3f} |"
            )
    if any(not metric["complete_five_class_holdout"] for placement in results.values() for metric in placement.values()):
        lines.extend(["", "An incomplete row reports macro F1 only across labels present in that placement and is not comparable to a complete five-class result."])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    args.results_dir.mkdir(parents=True, exist_ok=True)
    training_groups = set().union(
        *(groups_from_features(args.training_features_dir / f"features_{placement}.csv") for placement in ("wrist", "leg"))
    )
    results: dict[str, dict[str, dict[str, Any]]] = {"wrist": {}, "leg": {}}
    cv_results = json.loads(args.cv_metrics.read_text(encoding="utf-8"))
    all_holdout_groups: set[str] = set()
    incomplete = False

    for placement in ("wrist", "leg"):
        rows, feature_names = read_feature_rows(args.features_dir / f"features_{placement}.csv")
        y_true = np.asarray([row["label"] for row in rows])
        x = np.asarray([[float(row[name]) for name in feature_names] for row in rows], dtype=np.float64)
        all_holdout_groups.update(row["paired_session_id"] for row in rows)
        placement_predictions: dict[str, np.ndarray] = {}
        for model_name in ("random_forest", "svm_rbf"):
            package = joblib.load(args.models_dir / f"{placement}_{model_name}.joblib")
            if package["feature_names"] != feature_names:
                raise ValueError(f"feature mismatch for {placement}/{model_name}")
            if package["placement"] != placement:
                raise ValueError(f"placement mismatch for {placement}/{model_name}")
            predicted = package["estimator"].predict(x)
            placement_predictions[model_name] = predicted
            metrics = summarize(y_true, predicted)
            metrics["session_majority"] = session_majority(rows, predicted)
            results[placement][model_name] = metrics
            incomplete |= not metrics["complete_five_class_holdout"]
            shown_macro = metrics["macro_f1_five_class"] or metrics["macro_f1_observed"]
            print(
                f"holdout,{placement},{model_name},complete,{metrics['complete_five_class_holdout']},"
                f"macro_f1,{shown_macro:.4f},balanced_accuracy,{metrics['balanced_accuracy_observed']:.4f},"
                f"accuracy,{metrics['accuracy']:.4f}"
            )
        write_predictions(args.results_dir / f"predictions_{placement}.csv", rows, placement_predictions)

    overlap = training_groups & all_holdout_groups
    if overlap:
        raise ValueError(f"holdout group leakage detected: {sorted(overlap)}")
    with (args.results_dir / "metrics.json").open("w", encoding="utf-8") as handle:
        json.dump(results, handle, indent=2)
        handle.write("\n")
    write_report(args.results_dir / "report.md", results, cv_results, len(overlap))
    plot_confusions(results, args.results_dir / "confusion_matrices.png")
    plot_cv_comparison(results, cv_results, args.results_dir / "cv_vs_holdout.png")
    print(f"report,{args.results_dir / 'report.md'}")
    if incomplete and not args.allow_incomplete:
        print("error,incomplete_holdout,use --allow-incomplete only for provisional diagnostics")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
