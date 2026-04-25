#!/usr/bin/env python3
"""Validate Activity Tracker CSV logs and the local session manifest."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
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

TARGET_LABELS = {"walking", "running", "sitting", "lying", "cycling"}


@dataclass
class FileReport:
    path: Path
    label: str | None
    rows: int
    duration_s: float
    issues: list[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate raw Activity Tracker dataset files.")
    parser.add_argument(
        "--raw-dir",
        default="dataset/raw/own",
        type=Path,
        help="Directory containing raw CSV logs copied from the device.",
    )
    parser.add_argument(
        "--manifest",
        default="dataset/sessions.csv",
        type=Path,
        help="Session manifest CSV path.",
    )
    return parser.parse_args()


def read_manifest(manifest_path: Path) -> dict[str, dict[str, str]]:
    if not manifest_path.exists():
        return {}

    with manifest_path.open("r", newline="", encoding="utf-8") as handle:
        return {row["file"]: row for row in csv.DictReader(handle)}


def iter_csv_files(raw_dir: Path) -> Iterable[Path]:
    if not raw_dir.exists():
        return []
    return sorted(path for path in raw_dir.glob("*.csv") if path.is_file())


def validate_numeric(row: dict[str, str], column: str, issues: list[str], row_number: int) -> None:
    try:
        float(row[column])
    except (KeyError, ValueError):
        issues.append(f"row {row_number}: invalid numeric value in {column}")


def validate_file(path: Path) -> FileReport:
    issues: list[str] = []
    rows = 0
    first_timestamp: int | None = None
    last_timestamp: int | None = None
    labels: set[str] = set()
    previous_timestamp: int | None = None

    with path.open("r", newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != EXPECTED_COLUMNS:
            issues.append(f"unexpected columns: {reader.fieldnames}")
            return FileReport(path, None, 0, 0.0, issues)

        for row_number, row in enumerate(reader, start=2):
            rows += 1
            label = row.get("label", "")
            labels.add(label)

            if label not in TARGET_LABELS:
                issues.append(f"row {row_number}: unexpected label {label!r}")

            try:
                timestamp = int(row["timestamp_ms"])
            except ValueError:
                issues.append(f"row {row_number}: invalid timestamp_ms")
                continue

            if first_timestamp is None:
                first_timestamp = timestamp
            last_timestamp = timestamp

            if previous_timestamp is not None and timestamp <= previous_timestamp:
                issues.append(f"row {row_number}: timestamp is not increasing")
            previous_timestamp = timestamp

            for column in EXPECTED_COLUMNS[2:]:
                validate_numeric(row, column, issues, row_number)

    if rows == 0:
        issues.append("empty data file")

    if len(labels) > 1:
        issues.append(f"mixed labels in one file: {sorted(labels)}")

    duration_s = 0.0
    if first_timestamp is not None and last_timestamp is not None:
        duration_s = max(0.0, (last_timestamp - first_timestamp) / 1000.0)

    label = next(iter(labels)) if len(labels) == 1 else None
    return FileReport(path, label, rows, duration_s, issues)


def validate_manifest(manifest: dict[str, dict[str, str]], reports: list[FileReport]) -> list[str]:
    issues: list[str] = []
    files_on_disk = {report.path.name for report in reports}
    files_in_manifest = set(manifest.keys())

    for missing in sorted(files_on_disk - files_in_manifest):
        issues.append(f"{missing}: missing from manifest")

    for missing in sorted(files_in_manifest - files_on_disk):
        issues.append(f"{missing}: listed in manifest but not found on disk")

    for report in reports:
        row = manifest.get(report.path.name)
        if not row or not report.label:
            continue
        if row.get("label") != report.label:
            issues.append(f"{report.path.name}: manifest label {row.get('label')!r} != file label {report.label!r}")

    return issues


def main() -> int:
    args = parse_args()
    manifest = read_manifest(args.manifest)
    reports = [validate_file(path) for path in iter_csv_files(args.raw_dir)]
    manifest_issues = validate_manifest(manifest, reports)

    print(f"raw_dir,{args.raw_dir}")
    print(f"files,{len(reports)}")

    total_rows = 0
    for report in reports:
        total_rows += report.rows
        status = "ok" if not report.issues else "issue"
        label = report.label or "unknown"
        print(f"{status},{report.path.name},label,{label},rows,{report.rows},duration_s,{report.duration_s:.1f}")
        for issue in report.issues:
            print(f"issue,{report.path.name},{issue}")

    for issue in manifest_issues:
        print(f"issue,manifest,{issue}")

    print(f"total_rows,{total_rows}")
    return 1 if any(report.issues for report in reports) or manifest_issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
