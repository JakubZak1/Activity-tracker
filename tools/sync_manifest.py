#!/usr/bin/env python3
"""Add missing valid raw CSV logs to dataset/sessions.csv."""

from __future__ import annotations

import argparse
import csv
from datetime import date
from pathlib import Path

from validate_dataset import validate_file


MANIFEST_FIELDNAMES = ["file", "label", "date", "subject_id", "duration_s", "placement", "orientation", "source", "notes"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Append missing valid raw CSV logs to the session manifest.")
    parser.add_argument("--raw-dir", default=Path("dataset/raw/own"), type=Path)
    parser.add_argument("--manifest", default=Path("dataset/sessions.csv"), type=Path)
    parser.add_argument("--date", default=date.today().isoformat())
    parser.add_argument("--subject-id", default="S01")
    parser.add_argument("--placement", default="wrist")
    parser.add_argument("--orientation", required=True, help="Consistent device orientation, for example usb_toward_hand.")
    parser.add_argument("--source", default="own")
    parser.add_argument("--notes", default="")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def read_manifest(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []

    with path.open("r", newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def write_manifest(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=MANIFEST_FIELDNAMES)
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    args = parse_args()
    rows = read_manifest(args.manifest)
    existing_files = {row["file"] for row in rows if row.get("file")}
    added = 0
    skipped_existing = 0
    skipped_invalid = 0

    for path in sorted(args.raw_dir.glob("*.csv")):
        if path.name in existing_files:
            skipped_existing += 1
            continue

        report = validate_file(path)
        if report.issues or not report.label:
            skipped_invalid += 1
            print(f"skip,invalid,{path.name},{'; '.join(report.issues)}")
            continue

        row = {
            "file": path.name,
            "label": report.label,
            "date": args.date,
            "subject_id": args.subject_id,
            "duration_s": f"{report.duration_s:.1f}",
            "placement": args.placement,
            "orientation": args.orientation,
            "source": args.source,
            "notes": args.notes,
        }
        rows.append(row)
        existing_files.add(path.name)
        added += 1
        print(f"add,{path.name},label,{report.label},duration_s,{report.duration_s:.1f}")

    if not args.dry_run:
        write_manifest(args.manifest, rows)

    print(f"summary,added,{added},skipped_existing,{skipped_existing},skipped_invalid,{skipped_invalid},dry_run,{args.dry_run}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
