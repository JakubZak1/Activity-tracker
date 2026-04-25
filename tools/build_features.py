#!/usr/bin/env python3
"""Build fixed-window features from Activity Tracker raw CSV logs."""

from __future__ import annotations

import argparse
import csv
import math
from pathlib import Path

from dataset_loader import SensorSample, load_log
from validate_dataset import validate_file


AXES = [
    "acc_x_g",
    "acc_y_g",
    "acc_z_g",
    "gyro_x_dps",
    "gyro_y_dps",
    "gyro_z_dps",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Convert raw IMU CSV logs into window-level feature rows.")
    parser.add_argument("--raw-dir", default="dataset/raw/own", type=Path)
    parser.add_argument("--output", default="dataset/processed/features.csv", type=Path)
    parser.add_argument("--sample-rate-hz", default=50.0, type=float)
    parser.add_argument("--window-s", default=2.0, type=float)
    parser.add_argument("--overlap", default=0.5, type=float)
    parser.add_argument("--trim-start-s", default=5.0, type=float, help="Seconds ignored at the start of each session.")
    parser.add_argument("--trim-end-s", default=5.0, type=float, help="Seconds ignored at the end of each session.")
    parser.add_argument(
        "--min-samples",
        type=int,
        default=None,
        help="Minimum samples required per window. Default is 80 percent of the expected window sample count.",
    )
    return parser.parse_args()


def trim_samples(samples: list[SensorSample], trim_start_s: float, trim_end_s: float) -> list[SensorSample]:
    if not samples:
        return []

    first_ms = samples[0].timestamp_ms
    last_ms = samples[-1].timestamp_ms
    start_ms = first_ms + int(trim_start_s * 1000.0)
    end_ms = last_ms - int(trim_end_s * 1000.0)
    if start_ms >= end_ms:
        return []

    return [sample for sample in samples if start_ms <= sample.timestamp_ms <= end_ms]


def sample_value(sample: SensorSample, axis: str) -> float:
    return getattr(sample, axis)


def mean(values: list[float]) -> float:
    return sum(values) / len(values)


def stddev(values: list[float]) -> float:
    avg = mean(values)
    return math.sqrt(sum((value - avg) ** 2 for value in values) / len(values))


def energy(values: list[float]) -> float:
    return sum(value * value for value in values) / len(values)


def magnitude(x: float, y: float, z: float) -> float:
    return math.sqrt((x * x) + (y * y) + (z * z))


def feature_fieldnames() -> list[str]:
    fields = ["file", "window_id", "label", "start_ms", "end_ms", "sample_count", "duration_s"]
    for axis in AXES:
        fields.extend(
            [
                f"{axis}_mean",
                f"{axis}_std",
                f"{axis}_min",
                f"{axis}_max",
                f"{axis}_energy",
            ]
        )

    for prefix in ("acc_mag_g", "gyro_mag_dps"):
        fields.extend([f"{prefix}_mean", f"{prefix}_std", f"{prefix}_min", f"{prefix}_max", f"{prefix}_energy"])

    return fields


def build_feature_row(path: Path, window_id: int, label: str, samples: list[SensorSample]) -> dict[str, str]:
    start_ms = samples[0].timestamp_ms
    end_ms = samples[-1].timestamp_ms
    row: dict[str, str] = {
        "file": path.name,
        "window_id": str(window_id),
        "label": label,
        "start_ms": str(start_ms),
        "end_ms": str(end_ms),
        "sample_count": str(len(samples)),
        "duration_s": f"{max(0.0, (end_ms - start_ms) / 1000.0):.3f}",
    }

    for axis in AXES:
        values = [sample_value(sample, axis) for sample in samples]
        row[f"{axis}_mean"] = f"{mean(values):.6f}"
        row[f"{axis}_std"] = f"{stddev(values):.6f}"
        row[f"{axis}_min"] = f"{min(values):.6f}"
        row[f"{axis}_max"] = f"{max(values):.6f}"
        row[f"{axis}_energy"] = f"{energy(values):.6f}"

    acc_magnitudes = [magnitude(sample.acc_x_g, sample.acc_y_g, sample.acc_z_g) for sample in samples]
    gyro_magnitudes = [magnitude(sample.gyro_x_dps, sample.gyro_y_dps, sample.gyro_z_dps) for sample in samples]
    for prefix, values in (("acc_mag_g", acc_magnitudes), ("gyro_mag_dps", gyro_magnitudes)):
        row[f"{prefix}_mean"] = f"{mean(values):.6f}"
        row[f"{prefix}_std"] = f"{stddev(values):.6f}"
        row[f"{prefix}_min"] = f"{min(values):.6f}"
        row[f"{prefix}_max"] = f"{max(values):.6f}"
        row[f"{prefix}_energy"] = f"{energy(values):.6f}"

    return row


def build_windows(
    path: Path,
    samples: list[SensorSample],
    window_ms: int,
    step_ms: int,
    min_samples: int,
    sample_period_ms: int,
) -> list[dict[str, str]]:
    if not samples:
        return []

    rows: list[dict[str, str]] = []
    label = samples[0].label
    first_ms = samples[0].timestamp_ms
    last_ms = samples[-1].timestamp_ms
    start_ms = first_ms
    window_id = 0

    while start_ms + window_ms <= last_ms + sample_period_ms:
        end_ms = start_ms + window_ms
        window_samples = [sample for sample in samples if start_ms <= sample.timestamp_ms < end_ms]
        if len(window_samples) >= min_samples:
            rows.append(build_feature_row(path, window_id, label, window_samples))
            window_id += 1
        start_ms += step_ms

    return rows


def main() -> int:
    args = parse_args()
    if not 0.0 <= args.overlap < 1.0:
        raise SystemExit("--overlap must be >= 0.0 and < 1.0")

    window_ms = int(args.window_s * 1000.0)
    step_ms = max(1, int(window_ms * (1.0 - args.overlap)))
    sample_period_ms = max(1, int(1000.0 / args.sample_rate_hz))
    expected_samples = max(1, int(args.window_s * args.sample_rate_hz))
    min_samples = args.min_samples if args.min_samples is not None else max(1, int(expected_samples * 0.8))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    feature_rows: list[dict[str, str]] = []
    skipped_files = 0

    for path in sorted(args.raw_dir.glob("*.csv")):
        report = validate_file(path)
        if report.issues:
            skipped_files += 1
            print(f"skip,{path.name},{'; '.join(report.issues)}")
            continue

        samples = trim_samples(load_log(path), args.trim_start_s, args.trim_end_s)
        rows = build_windows(path, samples, window_ms, step_ms, min_samples, sample_period_ms)
        feature_rows.extend(rows)
        print(f"ok,{path.name},windows,{len(rows)},trimmed_samples,{len(samples)}")

    fieldnames = feature_fieldnames()
    with args.output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(feature_rows)

    print(f"output,{args.output}")
    print(f"feature_rows,{len(feature_rows)}")
    print(f"skipped_files,{skipped_files}")
    return 0 if feature_rows else 1


if __name__ == "__main__":
    raise SystemExit(main())
