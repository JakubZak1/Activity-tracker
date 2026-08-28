#!/usr/bin/env python3
"""Select a deployable leg Random Forest using grouped-CV training data only."""

from __future__ import annotations

import csv
import json
from pathlib import Path
from typing import Any

import joblib
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import balanced_accuracy_score, f1_score


METADATA_COLUMNS = {
    "file", "relative_path", "device_id", "placement", "label", "paired_session_id", "window_id",
    "start_ms", "end_ms", "session_elapsed_start_s", "session_elapsed_end_s", "sample_count",
}
STATS_BASIC = {"mean", "std", "min", "max"}
STATS_RMS = STATS_BASIC | {"rms"}


def statistic_suffix(name: str) -> str:
    return name.rsplit("_", 1)[-1]


def feature_sets(all_features: list[str]) -> dict[str, list[str]]:
    signal_prefixes = (
        "acc_x_g_", "acc_y_g_", "acc_z_g_", "gyro_x_dps_", "gyro_y_dps_", "gyro_z_dps_",
        "acc_mag_g_", "acc_dynamic_mag_g_", "gyro_mag_dps_",
    )
    aggregate = {"acc_sma", "gyro_sma", "acc_corr_xy", "acc_corr_xz", "acc_corr_yz", "gyro_corr_xy", "gyro_corr_xz", "gyro_corr_yz"}

    def choose(stats: set[str], include_aggregate: bool) -> list[str]:
        selected = [
            name for name in all_features
            if (name.startswith(signal_prefixes) and statistic_suffix(name) in stats)
            or (include_aggregate and name in aggregate)
        ]
        return selected

    return {
        "basic36": choose(STATS_BASIC, False),
        "basic44": choose(STATS_BASIC, True),
        "rms53": choose(STATS_RMS, True),
        "no_fft_quantile71": [
            name for name in all_features
            if not name.endswith(("_median", "_iqr", "_dominant_hz", "_spectral_energy", "_spectral_entropy"))
        ],
    }


def load_data(path: Path) -> tuple[list[dict[str, str]], list[str]]:
    with path.open("r", newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        rows = list(reader)
        fields = reader.fieldnames or []
    return rows, [name for name in fields if name not in METADATA_COLUMNS]


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


def estimated_flash_bytes(model: RandomForestClassifier) -> tuple[int, int]:
    nodes = sum(estimator.tree_.node_count for estimator in model.estimators_)
    # Export uses separate uint8 feature/int16 children/float threshold/int8
    # leaf-class arrays plus uint16 tree offsets: 10 bytes per node.
    return nodes, nodes * 10 + (len(model.estimators_) + 1) * 2


def main() -> int:
    processed = Path("dataset/processed")
    results_dir = Path("dataset/results")
    models_dir = Path("dataset/models")
    results_dir.mkdir(parents=True, exist_ok=True)
    models_dir.mkdir(parents=True, exist_ok=True)
    rows, all_features = load_data(processed / "features_leg.csv")
    assignments = {
        row["paired_session_id"]: int(row["fold"])
        for row in csv.DictReader((processed / "fold_assignments.csv").open("r", newline="", encoding="utf-8"))
    }
    labels = np.asarray([row["label"] for row in rows])
    groups = np.asarray([row["paired_session_id"] for row in rows])
    candidates: list[dict[str, Any]] = []

    for set_name, names in feature_sets(all_features).items():
        x = np.asarray([[float(row[name]) for name in names] for row in rows], dtype=np.float64)
        for trees in (10, 20, 30):
            for depth in (6, 8, 10):
                for min_leaf in (2, 5):
                    predicted = np.empty_like(labels)
                    for fold in range(3):
                        test = np.asarray([assignments[group] == fold for group in groups])
                        model = make_model(trees, depth, min_leaf, 4200 + fold)
                        model.fit(x[~test], labels[~test])
                        predicted[test] = model.predict(x[test])
                    final_model = make_model(trees, depth, min_leaf, 42)
                    final_model.fit(x, labels)
                    nodes, estimated_bytes = estimated_flash_bytes(final_model)
                    candidate = {
                        "feature_set": set_name,
                        "feature_count": len(names),
                        "trees": trees,
                        "max_depth": depth,
                        "min_samples_leaf": min_leaf,
                        "macro_f1": float(f1_score(labels, predicted, average="macro", zero_division=0)),
                        "balanced_accuracy": float(balanced_accuracy_score(labels, predicted)),
                        "nodes": nodes,
                        "estimated_model_flash_bytes": estimated_bytes,
                    }
                    candidates.append(candidate)
                    print(
                        f"candidate,{set_name},{len(names)},trees,{trees},depth,{depth},leaf,{min_leaf},"
                        f"macro_f1,{candidate['macro_f1']:.4f},bytes,{estimated_bytes}"
                    )

    # Twenty trees give 5 percentage-point vote resolution and were materially
    # more stable than ten trees across repeated seeds. Depth eight remains
    # inexpensive while preserving the grouped-CV result. The feature set is
    # deliberately restricted to streaming-friendly mean/std/min/max values.
    selected = next(
        candidate
        for candidate in candidates
        if candidate["feature_set"] == "basic36"
        and candidate["trees"] == 20
        and candidate["max_depth"] == 8
        and candidate["min_samples_leaf"] == 2
    )
    selected_names = feature_sets(all_features)[selected["feature_set"]]
    x_selected = np.asarray([[float(row[name]) for name in selected_names] for row in rows], dtype=np.float64)
    selected_model = make_model(selected["trees"], selected["max_depth"], selected["min_samples_leaf"], 42)
    selected_model.fit(x_selected, labels)

    package = {
        "schema_version": 1,
        "purpose": "embedded_leg_random_forest",
        "selection_rule": "streaming basic36 features; 20 trees for stable voting; depth 8; grouped-CV only",
        "placement": "leg",
        "device_id": "18EE26A8",
        "sample_rate_hz": 52.0,
        "window_samples": 260,
        "stride_samples": 130,
        "classes": ["walking", "running", "cycling", "sitting", "lying"],
        "feature_names": selected_names,
        "selection_metrics": selected,
        "estimator": selected_model,
    }
    joblib.dump(package, models_dir / "leg_random_forest_embedded.joblib", compress=3)

    fields = list(candidates[0])
    with (results_dir / "embedded_model_selection.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(sorted(candidates, key=lambda item: (-item["macro_f1"], item["estimated_model_flash_bytes"])))
    metadata = {key: value for key, value in package.items() if key != "estimator"}
    with (results_dir / "embedded_model_selection.json").open("w", encoding="utf-8") as handle:
        json.dump(metadata, handle, indent=2)
        handle.write("\n")
    print("selected," + json.dumps(selected, sort_keys=True))
    print(f"model,{models_dir / 'leg_random_forest_embedded.joblib'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
