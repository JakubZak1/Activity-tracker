#!/usr/bin/env python3
"""Explore a firmware-friendly lower-leg step-event detector on raw CSV files."""

from __future__ import annotations

import argparse
import csv
import math
from pathlib import Path


SAMPLE_RATE_HZ = 52.0
EMA_ALPHA = 0.35


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    return parser.parse_args()


def load(path: Path) -> tuple[str, list[int], list[float]]:
    timestamps: list[int] = []
    magnitude: list[float] = []
    labels: set[str] = set()
    with path.open("r", newline="", encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            timestamps.append(int(row["timestamp_ms"]))
            labels.add(row["label"].strip())
            gx = float(row["gyro_x_dps"]) - 0.8512324497700314
            gy = float(row["gyro_y_dps"]) - (-3.5223251028806586)
            gz = float(row["gyro_z_dps"]) - (-0.07260530137981118)
            magnitude.append(math.sqrt(gx * gx + gy * gy + gz * gz))
    if len(labels) != 1:
        raise ValueError(f"expected one label in {path}: {sorted(labels)}")
    return next(iter(labels)), timestamps, magnitude


def filtered(values: list[float]) -> list[float]:
    if not values:
        return []
    output = [values[0]]
    for value in values[1:]:
        output.append(output[-1] + EMA_ALPHA * (value - output[-1]))
    return output


def count_peaks(timestamps: list[int], values: list[float], threshold: float, refractory_ms: int) -> int:
    if len(values) < 3:
        return 0
    count = 0
    last_peak_ms = -1_000_000
    for index in range(2, len(values)):
        peak = values[index - 1]
        if (
            peak >= threshold
            and values[index - 2] < peak
            and peak >= values[index]
            and timestamps[index - 1] - last_peak_ms >= refractory_ms
        ):
            count += 1
            last_peak_ms = timestamps[index - 1]
    return count


def main() -> int:
    args = parse_args()
    files = sorted(args.path.rglob("*.csv")) if args.path.is_dir() else [args.path]
    configurations = [(threshold, refractory) for threshold in (40, 60, 80, 100) for refractory in (300, 400, 500)]
    print("file,label,duration_s,threshold_dps,refractory_ms,detected_steps,estimated_steps_per_min")
    for path in files:
        label, timestamps, magnitude = load(path)
        values = filtered(magnitude)
        duration_s = (timestamps[-1] - timestamps[0]) / 1000.0
        for threshold, refractory in configurations:
            events = count_peaks(timestamps, values, threshold, refractory)
            # Gyroscope magnitude has two swing-related peaks per complete
            # cycle of the instrumented leg. Each accepted peak therefore
            # corresponds to one whole-body step; do not multiply by two.
            steps_per_min = events * 60.0 / duration_s if duration_s else 0.0
            print(
                f"{path.name},{label},{duration_s:.3f},{threshold},{refractory},"
                f"{events},{steps_per_min:.1f}"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
