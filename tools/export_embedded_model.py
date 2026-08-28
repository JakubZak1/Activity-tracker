#!/usr/bin/env python3
"""Export the selected sklearn leg Random Forest to compact C++ arrays."""

from __future__ import annotations

import csv
import json
from pathlib import Path

import joblib
import numpy as np


CANONICAL_CLASSES = ["walking", "running", "cycling", "sitting", "lying"]
SENSOR_COLUMNS = ["acc_x_g", "acc_y_g", "acc_z_g", "gyro_x_dps", "gyro_y_dps", "gyro_z_dps"]


def format_array(values: list[object], formatter, per_line: int = 12) -> str:
    lines = []
    for start in range(0, len(values), per_line):
        lines.append("    " + ", ".join(formatter(value) for value in values[start:start + per_line]))
    return ",\n".join(lines)


def float_literal(value: object) -> str:
    numeric = float(np.float32(value))
    if not np.isfinite(numeric):
        raise ValueError(f"non-finite model value: {numeric}")
    text = f"{numeric:.9g}"
    if "." not in text and "e" not in text.lower():
        text += ".0"
    return f"{text}f"


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    package = joblib.load(root / "dataset/models/leg_random_forest_embedded_v2.joblib")
    model = package["estimator"]
    if list(package["classes"]) != CANONICAL_CLASSES:
        raise ValueError("unexpected canonical class order")
    if len(package["feature_names"]) > 255:
        raise ValueError("feature index does not fit uint8_t")

    features: list[int] = []
    left: list[int] = []
    right: list[int] = []
    thresholds: list[float] = []
    probabilities: list[float] = []
    offsets = [0]
    sklearn_classes = [str(value) for value in model.classes_]
    class_to_canonical = [CANONICAL_CLASSES.index(label) for label in sklearn_classes]

    for estimator in model.estimators_:
        tree = estimator.tree_
        base = offsets[-1]
        if base + tree.node_count > 65535:
            raise ValueError("model node index does not fit uint16_t")
        for node in range(tree.node_count):
            is_leaf = tree.children_left[node] < 0
            features.append(255 if is_leaf else int(tree.feature[node]))
            left.append(65535 if is_leaf else base + int(tree.children_left[node]))
            right.append(65535 if is_leaf else base + int(tree.children_right[node]))
            thresholds.append(0.0 if is_leaf else float(tree.threshold[node]))
            raw = np.asarray(tree.value[node][0], dtype=np.float64)
            canonical = np.zeros(len(CANONICAL_CLASSES), dtype=np.float64)
            if float(np.sum(raw)) > 0.0:
                raw = raw / np.sum(raw)
            for sklearn_index, value in enumerate(raw):
                canonical[class_to_canonical[sklearn_index]] = value
            probabilities.extend(float(value) if is_leaf else 0.0 for value in canonical)
        offsets.append(base + tree.node_count)

    node_count = len(features)

    def exported_predict(vector: list[float]) -> str:
        vector32 = np.asarray(vector, dtype=np.float32)
        accumulated = np.zeros(len(CANONICAL_CLASSES), dtype=np.float32)
        thresholds32 = np.asarray(thresholds, dtype=np.float32)
        probabilities32 = np.asarray(probabilities, dtype=np.float32)
        for tree_index in range(len(model.estimators_)):
            node = offsets[tree_index]
            while features[node] != 255:
                node = left[node] if vector32[features[node]] <= thresholds32[node] else right[node]
            base = node * len(CANONICAL_CLASSES)
            accumulated += probabilities32[base:base + len(CANONICAL_CLASSES)]
        return CANONICAL_CLASSES[int(np.argmax(accumulated))]

    parity: dict[str, dict[str, int]] = {}
    for dataset_name, feature_path in (
        ("training", root / "dataset/processed/features_leg.csv"),
        ("holdout", root / "dataset/processed/holdout/features_leg.csv"),
        ("live_development", root / "dataset/processed/live_diagnostic_2026-08-23/features_leg.csv"),
    ):
        dataset_rows = list(csv.DictReader(feature_path.open("r", newline="", encoding="utf-8")))
        vectors = [[float(row[name]) for name in package["feature_names"]] for row in dataset_rows]
        sklearn_predictions = model.predict(np.asarray(vectors, dtype=np.float64))
        mismatches = 0
        for vector, sklearn_prediction_value in zip(vectors, sklearn_predictions, strict=True):
            sklearn_prediction = str(sklearn_prediction_value)
            if exported_predict(vector) != sklearn_prediction:
                mismatches += 1
        parity[dataset_name] = {"windows": len(dataset_rows), "prediction_mismatches": mismatches}
        if mismatches:
            raise RuntimeError(f"C++ float32 export differs from sklearn on {mismatches} {dataset_name} windows")
    header = f"""#ifndef GENERATED_LEG_MODEL_H
#define GENERATED_LEG_MODEL_H

#include <stdint.h>

namespace generated_leg_model {{
constexpr uint16_t kTreeCount = {len(model.estimators_)};
constexpr uint16_t kNodeCount = {node_count};
constexpr uint8_t kFeatureCount = {len(package['feature_names'])};
constexpr uint8_t kClassCount = {len(CANONICAL_CLASSES)};
constexpr uint8_t kLeafFeature = 255;
constexpr uint16_t kNoChild = 65535;

extern const uint16_t kTreeOffsets[kTreeCount + 1];
extern const uint8_t kFeatures[kNodeCount];
extern const uint16_t kLeftChildren[kNodeCount];
extern const uint16_t kRightChildren[kNodeCount];
extern const float kThresholds[kNodeCount];
extern const float kLeafProbabilities[kNodeCount * kClassCount];
extern const char* const kClassLabels[kClassCount];
}}

#endif
"""
    source = f"""#include "generated_leg_model.h"

namespace generated_leg_model {{
const uint16_t kTreeOffsets[kTreeCount + 1] = {{
{format_array(offsets, str)}
}};

const uint8_t kFeatures[kNodeCount] = {{
{format_array(features, str)}
}};

const uint16_t kLeftChildren[kNodeCount] = {{
{format_array(left, str)}
}};

const uint16_t kRightChildren[kNodeCount] = {{
{format_array(right, str)}
}};

const float kThresholds[kNodeCount] = {{
{format_array(thresholds, float_literal, 8)}
}};

const float kLeafProbabilities[kNodeCount * kClassCount] = {{
{format_array(probabilities, float_literal, 8)}
}};

const char* const kClassLabels[kClassCount] = {{
{format_array(CANONICAL_CLASSES, lambda value: json.dumps(value), 5)}
}};
}}
"""
    (root / "include/generated_leg_model.h").write_text(header, encoding="utf-8")
    (root / "src/generated_leg_model.cpp").write_text(source, encoding="utf-8")

    rows = list(csv.DictReader((root / "dataset/processed/holdout/features_leg.csv").open("r", newline="", encoding="utf-8")))
    golden_rows = []
    for label in CANONICAL_CLASSES:
        row = next(item for item in rows if item["label"] == label)
        vector = [float(row[name]) for name in package["feature_names"]]
        predicted = str(model.predict(np.asarray([vector], dtype=np.float64))[0])
        golden_rows.append((row, label, predicted, vector))
    development_rows = list(
        csv.DictReader(
            (root / "dataset/processed/live_diagnostic_2026-08-23/features_leg.csv").open(
                "r", newline="", encoding="utf-8"
            )
        )
    )
    seen_files: set[str] = set()
    for row in development_rows:
        if row["file"] in seen_files:
            continue
        seen_files.add(row["file"])
        vector = [float(row[name]) for name in package["feature_names"]]
        predicted = str(model.predict(np.asarray([vector], dtype=np.float64))[0])
        golden_rows.append((row, row["label"], predicted, vector))
    golden = f"""#ifndef EMBEDDED_MODEL_GOLDEN_H
#define EMBEDDED_MODEL_GOLDEN_H

#include "generated_leg_model.h"

namespace embedded_model_golden {{
constexpr uint8_t kCount = {len(golden_rows)};
const float kFeatures[kCount][generated_leg_model::kFeatureCount] = {{
{format_array([value for _, _, _, vector in golden_rows for value in vector], float_literal, len(package['feature_names']))}
}};
const uint8_t kExpected[kCount] = {{
{format_array([CANONICAL_CLASSES.index(predicted) for _, _, predicted, _ in golden_rows], str, 10)}
}};
}}

#endif
"""
    (root / "test/test_native_classifier/generated_golden.h").parent.mkdir(parents=True, exist_ok=True)
    (root / "test/test_native_classifier/generated_golden.h").write_text(golden, encoding="utf-8")

    raw_index = {
        path.name: path
        for path in (root / "dataset/raw/holdout").rglob("*.csv")
    }
    raw_windows: list[list[list[float]]] = []
    for feature_row, _, _, _ in golden_rows:
        raw_path = raw_index.get(feature_row["file"])
        if raw_path is None:
            raise ValueError(f"missing raw golden source: {feature_row['file']}")
        raw_rows = list(csv.DictReader(raw_path.open("r", newline="", encoding="utf-8")))
        start_timestamp = int(feature_row["start_ms"])
        start_index = next(
            (index for index, raw_row in enumerate(raw_rows) if int(raw_row["timestamp_ms"]) == start_timestamp),
            None,
        )
        if start_index is None:
            raise ValueError(f"missing raw golden timestamp: {feature_row['file']} / {start_timestamp}")
        selected_raw = raw_rows[start_index:start_index + int(package["window_samples"])]
        if len(selected_raw) != int(package["window_samples"]):
            raise ValueError(f"short raw golden window: {feature_row['file']}")
        raw_windows.append(
            [[float(raw_row[name]) for name in SENSOR_COLUMNS] for raw_row in selected_raw]
        )
    raw_golden = f"""#ifndef EMBEDDED_RAW_GOLDEN_H
#define EMBEDDED_RAW_GOLDEN_H

#include "generated_leg_model.h"

namespace embedded_raw_golden {{
constexpr uint8_t kCount = {len(golden_rows)};
constexpr uint16_t kWindowSamples = {int(package['window_samples'])};
constexpr uint8_t kRawSignalCount = {len(SENSOR_COLUMNS)};
const float kRaw[kCount][kWindowSamples][kRawSignalCount] = {{
{format_array([value for window in raw_windows for sample in window for value in sample], float_literal, len(SENSOR_COLUMNS))}
}};
const float kExpectedFeatures[kCount][generated_leg_model::kFeatureCount] = {{
{format_array([value for _, _, _, vector in golden_rows for value in vector], float_literal, len(package['feature_names']))}
}};
const uint8_t kExpectedClass[kCount] = {{
{format_array([CANONICAL_CLASSES.index(predicted) for _, _, predicted, _ in golden_rows], str, 10)}
}};
}}

#endif
"""
    (root / "test/test_native_classifier/generated_raw_golden.h").write_text(raw_golden, encoding="utf-8")

    metadata = {
        "trees": len(model.estimators_),
        "nodes": node_count,
        "features": package["feature_names"],
        "classes": CANONICAL_CLASSES,
        "array_bytes": node_count * (1 + 2 + 2 + 4 + 4 * len(CANONICAL_CLASSES)) + 2 * len(offsets),
        "parity": parity,
    }
    (root / "dataset/results/embedded_export.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
