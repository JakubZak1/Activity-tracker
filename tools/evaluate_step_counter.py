#!/usr/bin/env python3
"""Reproduce the firmware step-counter candidate flow on raw Green CSV files."""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from pathlib import Path
from typing import Any

import joblib
import numpy as np

from ml_dataset import apply_calibration, extract_features, load_calibrations, load_samples


WINDOW_SAMPLES = 260
STRIDE_SAMPLES = 130
SAMPLE_RATE_HZ = 52.0
EMA_ALPHA = 0.35
PEAK_THRESHOLD_DPS = 80.0
REFRACTORY_MS = 400
CLASSIFICATION_LAG_MS = 2500
GYROSCOPE_BIAS = (0.8512324497700314, -3.5223251028806586, -0.07260530137981118)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw_dir", type=Path)
    parser.add_argument(
        "--model",
        type=Path,
        default=Path("dataset/models/leg_random_forest_embedded_v2.joblib"),
    )
    parser.add_argument("--calibration-dir", type=Path, default=Path("calibration"))
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("dataset/results/step_counter_candidate/preflight.json"),
    )
    return parser.parse_args()


def detect_candidates(timestamps: np.ndarray, raw_values: np.ndarray) -> list[int]:
    corrected_gyro = raw_values[:, 3:6].astype(np.float64, copy=True)
    corrected_gyro -= np.asarray(GYROSCOPE_BIAS, dtype=np.float64)
    magnitudes = np.linalg.norm(corrected_gyro, axis=1)
    if len(magnitudes) < 3:
        return []
    filtered = np.empty_like(magnitudes)
    filtered[0] = magnitudes[0]
    for index in range(1, len(magnitudes)):
        filtered[index] = filtered[index - 1] + EMA_ALPHA * (magnitudes[index] - filtered[index - 1])
    candidates: list[int] = []
    last_peak_ms: int | None = None
    for index in range(2, len(filtered)):
        timestamp_ms = int(timestamps[index - 1])
        if (
            filtered[index - 2] < filtered[index - 1]
            and filtered[index - 1] >= filtered[index]
            and filtered[index - 1] >= PEAK_THRESHOLD_DPS
            and (last_peak_ms is None or timestamp_ms - last_peak_ms >= REFRACTORY_MS)
        ):
            candidates.append(timestamp_ms)
            last_peak_ms = timestamp_ms
    return candidates


def evaluate_file(
    path: Path,
    package: dict[str, Any],
    calibration: dict[str, Any],
) -> dict[str, Any]:
    timestamps, raw_values = load_samples(path)
    corrected = apply_calibration(raw_values, calibration)
    candidates = detect_candidates(timestamps, raw_values)
    pending = list(candidates)
    counted: list[int] = []
    predictions: list[str] = []
    last_step_activity = False
    for start in range(0, len(timestamps) - WINDOW_SAMPLES + 1, STRIDE_SAMPLES):
        stop = start + WINDOW_SAMPLES
        features = extract_features(corrected[start:stop], SAMPLE_RATE_HZ)
        vector = np.asarray([[features[name] for name in package["feature_names"]]], dtype=np.float64)
        predicted = str(package["estimator"].predict(vector)[0])
        predictions.append(predicted)
        last_step_activity = predicted in {"walking", "running"}
        cutoff = int(timestamps[stop - 1]) - CLASSIFICATION_LAG_MS
        decided = [timestamp for timestamp in pending if timestamp <= cutoff]
        pending = [timestamp for timestamp in pending if timestamp > cutoff]
        if last_step_activity:
            counted.extend(decided)
    if last_step_activity:
        counted.extend(pending)
    duration_s = (int(timestamps[-1]) - int(timestamps[0])) / 1000.0
    labels = set()
    with path.open("r", encoding="utf-8-sig") as handle:
        header = handle.readline().strip().split(",")
        label_index = header.index("label")
        for line in handle:
            if line.strip():
                labels.add(line.split(",")[label_index].strip())
    if len(labels) != 1:
        raise ValueError(f"expected one label in {path}: {sorted(labels)}")
    return {
        "file": path.name,
        "label": next(iter(labels)),
        "duration_s": duration_s,
        "candidate_peaks": len(candidates),
        "counted_steps": len(counted),
        "counted_steps_per_min": len(counted) * 60.0 / duration_s if duration_s else 0.0,
        "model_windows": len(predictions),
        "model_prediction_counts": dict(Counter(predictions)),
    }


def main() -> int:
    args = parse_args()
    root = Path(__file__).resolve().parents[1]
    raw_dir = root / args.raw_dir
    package = joblib.load(root / args.model)
    if package.get("purpose") != "embedded_leg_random_forest_augmented_v2":
        raise ValueError("unexpected model package")
    calibration = load_calibrations(root / args.calibration_dir)["18EE26A8"]
    sessions = [evaluate_file(path, package, calibration) for path in sorted(raw_dir.glob("*.csv"))]
    report = {
        "schema_version": 1,
        "purpose": "step_counter_preflight_without_manual_ground_truth",
        "warning": "Counts are plausibility estimates only; accuracy requires sessions with manually counted reference steps.",
        "configuration": {
            "ema_alpha": EMA_ALPHA,
            "peak_threshold_dps": PEAK_THRESHOLD_DPS,
            "refractory_ms": REFRACTORY_MS,
            "classification_lag_ms": CLASSIFICATION_LAG_MS,
            "activity_gate": ["walking", "running"],
        },
        "sessions": sessions,
    }
    output = root / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
