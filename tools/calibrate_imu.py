#!/usr/bin/env python3
"""Build a device-specific six-position IMU calibration profile."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import statistics
import zlib
from pathlib import Path


ACCEL_KEYS = ["acc_x_g", "acc_y_g", "acc_z_g"]
GYRO_KEYS = ["gyro_x_dps", "gyro_y_dps", "gyro_z_dps"]
EXPECTED_COLUMNS = ["timestamp_ms", "label", *ACCEL_KEYS, *GYRO_KEYS]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_dir", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--alias", required=True)
    parser.add_argument("--device-id", required=True)
    parser.add_argument("--date", required=True)
    parser.add_argument("--trim-seconds", type=float, default=5.0)
    parser.add_argument(
        "--fixtures",
        default="large_face_a,large_face_b,usb_down,usb_up,usb_right,usb_left",
        help="Comma-separated fixture names in numeric filename order.",
    )
    return parser.parse_args()


def numeric_file_key(path: Path) -> tuple[int, str]:
    try:
        return int(path.stem.rsplit("_", 1)[1]), path.name
    except (IndexError, ValueError):
        raise ValueError(f"Filename lacks a numeric session suffix: {path.name}") from None


def load_recording(path: Path, expected_device_id: str, trim_ms: int) -> dict:
    metadata_path = path.with_name(f"{path.name}.session.json")
    if not metadata_path.is_file():
        raise ValueError(f"Missing sidecar: {metadata_path.name}")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata.get("device_identity") != expected_device_id:
        raise ValueError(f"Device ID mismatch in {metadata_path.name}")
    if metadata.get("file_name") != path.name:
        raise ValueError(f"Filename mismatch in {metadata_path.name}")

    raw_bytes = path.read_bytes()
    actual_crc = f"{zlib.crc32(raw_bytes) & 0xFFFF_FFFF:08X}"
    if len(raw_bytes) != metadata.get("size_bytes") or actual_crc != metadata.get("crc32"):
        raise ValueError(f"Size or CRC32 mismatch: {path.name}")

    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != EXPECTED_COLUMNS:
            raise ValueError(f"Unexpected CSV schema: {path.name}")
        rows = list(reader)
    if len(rows) < 3:
        raise ValueError(f"Too few rows: {path.name}")

    timestamps = [int(row["timestamp_ms"]) for row in rows]
    deltas = [current - previous for previous, current in zip(timestamps, timestamps[1:])]
    if min(deltas) < 18 or max(deltas) > 22:
        raise ValueError(f"Timestamp interval outside 18-22 ms: {path.name}")
    trimmed_rows = [
        row for row, timestamp in zip(rows, timestamps)
        if timestamps[0] + trim_ms <= timestamp <= timestamps[-1] - trim_ms
    ]
    if not trimmed_rows:
        raise ValueError(f"Trim removed all samples: {path.name}")

    accel_mean = [statistics.fmean(float(row[key]) for row in trimmed_rows) for key in ACCEL_KEYS]
    gyro_mean = [statistics.fmean(float(row[key]) for row in trimmed_rows) for key in GYRO_KEYS]
    dominant_axis = max(range(3), key=lambda index: abs(accel_mean[index]))
    dominant_sign = "+" if accel_mean[dominant_axis] > 0 else "-"
    duration_s = (timestamps[-1] - timestamps[0]) / 1000.0
    return {
        "path": path,
        "rows_data": trimmed_rows,
        "file": path.name,
        "bytes": len(raw_bytes),
        "rows": len(rows),
        "trimmed_rows": len(trimmed_rows),
        "duration_s": duration_s,
        "effective_hz": (len(rows) - 1) / duration_s,
        "crc32": actual_crc,
        "sha256": hashlib.sha256(raw_bytes).hexdigest().upper(),
        "paired_session_id": metadata.get("paired_session_id"),
        "accel_mean_g": accel_mean,
        "gyro_mean_dps": gyro_mean,
        "detected_orientation": f"{dominant_sign}{'xyz'[dominant_axis]}",
        "orientation_key": (dominant_axis, 1 if dominant_sign == "+" else -1),
    }


def vector_norm(values: list[float]) -> float:
    return math.sqrt(sum(value * value for value in values))


def main() -> int:
    args = parse_args()
    device_id = args.device_id.upper()
    if len(device_id) != 16 or any(character not in "0123456789ABCDEF" for character in device_id):
        raise ValueError("--device-id must contain 16 hexadecimal characters")
    fixture_names = [value.strip() for value in args.fixtures.split(",") if value.strip()]
    paths = sorted(args.input_dir.glob("*.csv"), key=numeric_file_key)
    if len(paths) != 6 or len(fixture_names) != 6:
        raise ValueError("Exactly six CSV files and six fixture names are required")

    recordings = [load_recording(path, device_id, round(args.trim_seconds * 1000)) for path in paths]
    orientation_map: dict[tuple[int, int], dict] = {}
    for recording, fixture in zip(recordings, fixture_names):
        key = recording["orientation_key"]
        if key in orientation_map:
            raise ValueError(f"Duplicate detected orientation: {recording['detected_orientation']}")
        recording["fixture_position"] = fixture
        orientation_map[key] = recording
    expected_orientations = {(axis, sign) for axis in range(3) for sign in (-1, 1)}
    if set(orientation_map) != expected_orientations:
        raise ValueError("Recordings do not cover +X/-X/+Y/-Y/+Z/-Z")

    offsets: list[float] = []
    scales: list[float] = []
    for axis in range(3):
        positive = orientation_map[(axis, 1)]["accel_mean_g"][axis]
        negative = orientation_map[(axis, -1)]["accel_mean_g"][axis]
        offset = (positive + negative) / 2.0
        half_span = (positive - negative) / 2.0
        if half_span <= 0.0:
            raise ValueError(f"Invalid half-span for axis {'xyz'[axis]}")
        offsets.append(offset)
        scales.append(1.0 / half_span)

    all_rows = [row for recording in recordings for row in recording["rows_data"]]
    gyro_bias = [statistics.fmean(float(row[key]) for row in all_rows) for key in GYRO_KEYS]
    gyro_between_stddev = [
        statistics.pstdev(recording["gyro_mean_dps"][axis] for recording in recordings)
        for axis in range(3)
    ]
    raw_norms: list[float] = []
    corrected_norms: list[float] = []
    for row in all_rows:
        raw_accel = [float(row[key]) for key in ACCEL_KEYS]
        corrected = [(raw_accel[index] - offsets[index]) * scales[index] for index in range(3)]
        raw_norms.append(vector_norm(raw_accel))
        corrected_norms.append(vector_norm(corrected))

    orientation_entries = []
    for recording in recordings:
        orientation_entries.append({
            key: recording[key]
            for key in (
                "file", "fixture_position", "detected_orientation", "bytes", "rows",
                "trimmed_rows", "duration_s", "effective_hz", "crc32", "sha256",
                "paired_session_id", "accel_mean_g", "gyro_mean_dps",
            )
        })
    profile = {
        "schema_version": 2,
        "device": {
            "alias": args.alias,
            "board": "Seeed Studio XIAO nRF52840 Sense",
            "hardware_id": device_id,
            "short_id": device_id[-8:],
        },
        "captured_on": args.date,
        "acquisition": {
            "protocol_version": 6,
            "capability": "imu_drdy104_mean2_52_deadline_guard",
            "input_rate_hz": 104,
            "output_rate_hz": 52,
            "accelerometer_range_g": 16,
            "gyroscope_range_dps": 2000,
        },
        "method": {
            "type": "six_position_static",
            "trim_seconds_at_start": args.trim_seconds,
            "trim_seconds_at_end": args.trim_seconds,
            "raw_directory": args.input_dir.as_posix(),
            "orientation_detection": "largest absolute trimmed acceleration mean",
            "correction_order": "corrected_acc=(raw_acc-offset_g)*scale_multiplier; corrected_gyro=raw_gyro-bias_dps",
        },
        "orientations": orientation_entries,
        "accelerometer": {
            "axis_order": ["x", "y", "z"],
            "offset_g": offsets,
            "scale_multiplier": scales,
            "raw_norm_mean_g": statistics.fmean(raw_norms),
            "raw_norm_stddev_g": statistics.pstdev(raw_norms),
            "corrected_norm_mean_g": statistics.fmean(corrected_norms),
            "corrected_norm_stddev_g": statistics.pstdev(corrected_norms),
            "corrected_norm_min_g": min(corrected_norms),
            "corrected_norm_max_g": max(corrected_norms),
        },
        "gyroscope": {
            "axis_order": ["x", "y", "z"],
            "bias_dps": gyro_bias,
            "bias_between_orientation_stddev_dps": gyro_between_stddev,
        },
        "status": "engineering_calibration_candidate",
        "notes": [
            "Raw calibration CSV files are not activity-class dataset sessions.",
            "Apply this exact correction in both model training and embedded inference preprocessing.",
            "Repeat after changing IMU range, ODR, averaging, enclosure, or board mounting.",
            "Temperature was not controlled, so temperature-dependent bias remains uncharacterized.",
            "Validate the profile on a stationary oblique holdout after rigidly fixing the board in its final enclosure.",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(args.output),
        "device_id": device_id,
        "offset_g": offsets,
        "scale_multiplier": scales,
        "gyro_bias_dps": gyro_bias,
        "corrected_norm_mean_g": statistics.fmean(corrected_norms),
        "corrected_norm_stddev_g": statistics.pstdev(corrected_norms),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
