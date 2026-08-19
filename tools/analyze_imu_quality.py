#!/usr/bin/env python3
"""Analyze timing and sensor quality in Activity Tracker IMU CSV files."""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from collections import deque
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


EXPECTED_COLUMNS = [
    "timestamp_ms",
    "label",
    "acc_x_g",
    "acc_y_g",
    "acc_z_g",
    "gyro_x_dps",
    "gyro_y_dps",
    "gyro_z_dps",
]
AXES = EXPECTED_COLUMNS[2:]
ACCEL_AXES = AXES[:3]
GYRO_AXES = AXES[3:]


@dataclass
class FileSummary:
    file: str
    rows: int
    labels: list[str]
    start_ms: int | None
    end_ms: int | None
    duration_s: float
    effective_hz: float
    issues: list[str]


@dataclass
class NumericSummary:
    mean: float
    stddev: float
    minimum: float
    p01: float
    median: float
    p99: float
    maximum: float


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Analyze Activity Tracker IMU CSV timing, bias, noise, drift, and clipping.",
    )
    parser.add_argument(
        "paths",
        nargs="+",
        type=Path,
        help="CSV files or directories containing CSV files.",
    )
    parser.add_argument(
        "--stationary",
        action="store_true",
        help="Evaluate heuristic stationary-sensor quality checks.",
    )
    parser.add_argument(
        "--window-samples",
        type=int,
        default=3000,
        help="Samples used for the first/last drift windows (default: 3000, about one minute).",
    )
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON.")
    return parser.parse_args()


def resolve_files(paths: Iterable[Path]) -> list[Path]:
    files: set[Path] = set()
    for path in paths:
        if path.is_dir():
            files.update(candidate for candidate in path.glob("*.csv") if candidate.is_file())
        elif path.is_file() and path.suffix.lower() == ".csv":
            files.add(path)
    return sorted(files, key=lambda value: value.name)


def percentile(sorted_values: list[float], fraction: float) -> float:
    if not sorted_values:
        return math.nan
    position = (len(sorted_values) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[lower]
    weight = position - lower
    return sorted_values[lower] * (1.0 - weight) + sorted_values[upper] * weight


def summarize(values: list[float]) -> NumericSummary:
    ordered = sorted(values)
    return NumericSummary(
        mean=statistics.fmean(values),
        stddev=statistics.pstdev(values),
        minimum=ordered[0],
        p01=percentile(ordered, 0.01),
        median=statistics.median(ordered),
        p99=percentile(ordered, 0.99),
        maximum=ordered[-1],
    )


def analyze(files: list[Path], stationary: bool, window_samples: int) -> dict[str, object]:
    axis_values: dict[str, list[float]] = {axis: [] for axis in AXES}
    accel_magnitudes: list[float] = []
    gyro_magnitudes: list[float] = []
    intervals: list[int] = []
    first_window: list[tuple[float, ...]] = []
    last_window: deque[tuple[float, ...]] = deque(maxlen=window_samples)
    file_summaries: list[FileSummary] = []
    identical_vectors = 0
    clipping_accel = 0
    clipping_gyro = 0
    total_rows = 0

    for path in files:
        issues: list[str] = []
        labels: set[str] = set()
        timestamps: list[int] = []
        previous_vector: tuple[float, ...] | None = None
        rows = 0
        with path.open("r", newline="", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames != EXPECTED_COLUMNS:
                file_summaries.append(FileSummary(path.name, 0, [], None, None, 0.0, 0.0, [
                    f"unexpected columns: {reader.fieldnames}",
                ]))
                continue
            for row_number, row in enumerate(reader, start=2):
                try:
                    timestamp = int(row["timestamp_ms"])
                    vector = tuple(float(row[axis]) for axis in AXES)
                except (TypeError, ValueError):
                    issues.append(f"row {row_number}: invalid timestamp or numeric value")
                    continue
                if not all(math.isfinite(value) for value in vector):
                    issues.append(f"row {row_number}: NaN or infinite sensor value")
                    continue
                if timestamps and timestamp <= timestamps[-1]:
                    issues.append(f"row {row_number}: timestamp is not strictly increasing")
                timestamps.append(timestamp)
                labels.add(row["label"])
                rows += 1
                total_rows += 1

                if previous_vector == vector:
                    identical_vectors += 1
                previous_vector = vector
                if len(first_window) < window_samples:
                    first_window.append(vector)
                last_window.append(vector)

                for axis, value in zip(AXES, vector):
                    axis_values[axis].append(value)
                accel_magnitudes.append(math.sqrt(sum(value * value for value in vector[:3])))
                gyro_magnitudes.append(math.sqrt(sum(value * value for value in vector[3:])))
                if any(abs(value) >= 15.9 for value in vector[:3]):
                    clipping_accel += 1
                if any(abs(value) >= 1990.0 for value in vector[3:]):
                    clipping_gyro += 1

        local_intervals = [right - left for left, right in zip(timestamps, timestamps[1:])]
        intervals.extend(local_intervals)
        if len(labels) != 1:
            issues.append(f"expected one label, found {sorted(labels)}")
        duration_ms = timestamps[-1] - timestamps[0] if len(timestamps) >= 2 else 0
        file_summaries.append(FileSummary(
            file=path.name,
            rows=rows,
            labels=sorted(labels),
            start_ms=timestamps[0] if timestamps else None,
            end_ms=timestamps[-1] if timestamps else None,
            duration_s=duration_ms / 1000.0,
            effective_hz=(len(local_intervals) * 1000.0 / duration_ms) if duration_ms > 0 else 0.0,
            issues=issues,
        ))

    if total_rows == 0:
        raise ValueError("No valid IMU rows found")

    axis_summaries = {axis: asdict(summarize(values)) for axis, values in axis_values.items()}
    accel_summary = asdict(summarize(accel_magnitudes))
    gyro_summary = asdict(summarize(gyro_magnitudes))
    interval_summary = asdict(summarize([float(value) for value in intervals])) if intervals else None

    def window_means(samples: Iterable[tuple[float, ...]]) -> dict[str, float]:
        materialized = list(samples)
        return {
            axis: statistics.fmean(sample[index] for sample in materialized)
            for index, axis in enumerate(AXES)
        }

    first_means = window_means(first_window)
    last_means = window_means(last_window)
    drift = {axis: last_means[axis] - first_means[axis] for axis in AXES}

    result: dict[str, object] = {
        "files": [asdict(summary) for summary in file_summaries],
        "total_rows": total_rows,
        "axis": axis_summaries,
        "accel_magnitude_g": accel_summary,
        "gyro_magnitude_dps": gyro_summary,
        "timing_ms": interval_summary,
        "timing_counts": {
            "below_18_ms": sum(value < 18 for value in intervals),
            "between_18_and_22_ms": sum(18 <= value <= 22 for value in intervals),
            "above_100_ms": sum(value > 100 for value in intervals),
        },
        "identical_consecutive_vectors": identical_vectors,
        "identical_consecutive_rate": identical_vectors / max(1, total_rows - len(files)),
        "clipping_rows": {"accelerometer": clipping_accel, "gyroscope": clipping_gyro},
        "drift_last_minus_first_window": drift,
        "window_samples": min(window_samples, total_rows),
    }

    if stationary:
        result["stationary_outlier_counts"] = {
            "accel_magnitude_outside_0_95_to_1_05_g": sum(
                value < 0.95 or value > 1.05 for value in accel_magnitudes
            ),
            "gyro_magnitude_above_5_dps": sum(value > 5.0 for value in gyro_magnitudes),
            "gyro_magnitude_above_10_dps": sum(value > 10.0 for value in gyro_magnitudes),
            "gyro_magnitude_above_15_dps": sum(value > 15.0 for value in gyro_magnitudes),
        }
        result["stationary_checks"] = {
            "gravity_mean_0_95_to_1_05_g": 0.95 <= accel_summary["mean"] <= 1.05,
            "gravity_stddev_at_most_0_03_g": accel_summary["stddev"] <= 0.03,
            "gyro_axis_bias_below_1_dps": all(abs(axis_summaries[axis]["mean"]) < 1.0 for axis in GYRO_AXES),
            "gyro_axis_noise_below_1_dps": all(axis_summaries[axis]["stddev"] < 1.0 for axis in GYRO_AXES),
            "no_clipping": clipping_accel == 0 and clipping_gyro == 0,
            "no_schema_numeric_or_timestamp_issues": not any(summary.issues for summary in file_summaries),
        }
        result["stationary_threshold_note"] = (
            "Engineering smoke-test thresholds, not a calibration certificate or manufacturer specification."
        )
    return result


def print_human(result: dict[str, object]) -> None:
    print(f"files,{len(result['files'])}")
    print(f"total_rows,{result['total_rows']}")
    for item in result["files"]:
        print(
            "file,{file},rows,{rows},labels,{labels},duration_s,{duration_s:.3f},effective_hz,{effective_hz:.3f},issues,{issues}".format(
                file=item["file"],
                rows=item["rows"],
                labels=";".join(item["labels"]),
                duration_s=item["duration_s"],
                effective_hz=item["effective_hz"],
                issues=";".join(item["issues"]) or "none",
            )
        )
    for axis, summary in result["axis"].items():
        print(
            "axis,{axis},mean,{mean:.6f},stddev,{stddev:.6f},min,{minimum:.6f},p01,{p01:.6f},median,{median:.6f},p99,{p99:.6f},max,{maximum:.6f}".format(
                axis=axis,
                **summary,
            )
        )
    for name in ("accel_magnitude_g", "gyro_magnitude_dps", "timing_ms"):
        summary = result[name]
        print(
            "metric,{name},mean,{mean:.6f},stddev,{stddev:.6f},min,{minimum:.6f},p01,{p01:.6f},median,{median:.6f},p99,{p99:.6f},max,{maximum:.6f}".format(
                name=name,
                **summary,
            )
        )
    print("timing_counts," + ",".join(f"{key},{value}" for key, value in result["timing_counts"].items()))
    print(f"identical_consecutive_vectors,{result['identical_consecutive_vectors']}")
    print(f"identical_consecutive_rate,{result['identical_consecutive_rate']:.8f}")
    print("clipping_rows," + ",".join(f"{key},{value}" for key, value in result["clipping_rows"].items()))
    print(
        "drift_last_minus_first_window," +
        ",".join(f"{axis},{value:.6f}" for axis, value in result["drift_last_minus_first_window"].items())
    )
    if "stationary_checks" in result:
        print("stationary_outlier_counts," + ",".join(
            f"{key},{value}" for key, value in result["stationary_outlier_counts"].items()
        ))
        print("stationary_checks," + ",".join(
            f"{key},{'pass' if value else 'fail'}"
            for key, value in result["stationary_checks"].items()
        ))
        print(f"stationary_threshold_note,{result['stationary_threshold_note']}")


def main() -> int:
    args = parse_args()
    if args.window_samples <= 0:
        raise SystemExit("--window-samples must be positive")
    files = resolve_files(args.paths)
    if not files:
        raise SystemExit("No CSV files found")
    result = analyze(files, args.stationary, args.window_samples)
    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    else:
        print_human(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
