#!/usr/bin/env python3
"""Validate, curate, calibrate and window the final two-placement dataset."""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

from ml_dataset import (
    build_feature_rows,
    discover_sessions,
    load_calibrations,
    write_feature_csv,
    write_manifest,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-dir", type=Path, default=Path("dataset/raw/own"))
    parser.add_argument("--curation", type=Path, default=Path("dataset/curation/curation.json"))
    parser.add_argument("--calibration-dir", type=Path, default=Path("calibration"))
    parser.add_argument("--output-dir", type=Path, default=Path("dataset/processed"))
    parser.add_argument("--sample-rate-hz", type=float, default=52.0)
    parser.add_argument("--window-s", type=float, default=5.0)
    parser.add_argument("--overlap", type=float, default=0.5)
    parser.add_argument("--trim-start-s", type=float, default=5.0)
    parser.add_argument("--trim-end-s", type=float, default=5.0)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    sessions, curation = discover_sessions(args.raw_dir, args.curation)
    calibrations = load_calibrations(args.calibration_dir)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_manifest(args.output_dir / "session_manifest.csv", sessions)

    invalid = [session for session in sessions if session.issues]
    if invalid:
        for session in invalid:
            print(f"error,{session.relative_path},{'; '.join(session.issues)}")
        print(f"manifest,{args.output_dir / 'session_manifest.csv'}")
        return 1

    rows_by_placement, counters = build_feature_rows(
        sessions,
        curation,
        calibrations,
        sample_rate_hz=args.sample_rate_hz,
        window_s=args.window_s,
        overlap=args.overlap,
        trim_start_s=args.trim_start_s,
        trim_end_s=args.trim_end_s,
    )

    summary: dict[str, object] = {
        "configuration": {
            "sample_rate_hz": args.sample_rate_hz,
            "window_s": args.window_s,
            "overlap": args.overlap,
            "trim_start_s": args.trim_start_s,
            "trim_end_s": args.trim_end_s,
            "calibration": "per-device six-position profiles",
        },
        "files": {
            "total": len(sessions),
            "included": sum(session.included for session in sessions),
            "excluded": sum(not session.included for session in sessions),
        },
        "window_counters": counters,
        "placements": {},
    }
    feature_names: list[str] | None = None
    for placement, rows in rows_by_placement.items():
        # Diagnostic and deployment-validation datasets may intentionally
        # contain only one sensor placement.
        if not rows:
            continue
        current_names = write_feature_csv(args.output_dir / f"features_{placement}.csv", rows)
        if feature_names is None:
            feature_names = current_names
        elif current_names != feature_names:
            raise RuntimeError("feature columns differ between placements")
        labels = Counter(str(row["label"]) for row in rows)
        groups: dict[str, set[str]] = defaultdict(set)
        for row in rows:
            groups[str(row["label"])].add(str(row["paired_session_id"]))
        summary["placements"][placement] = {
            "windows": len(rows),
            "windows_by_label": dict(sorted(labels.items())),
            "paired_sessions_by_label": {label: len(values) for label, values in sorted(groups.items())},
        }
        print(f"features,{placement},{len(rows)},{args.output_dir / f'features_{placement}.csv'}")

    summary["feature_count"] = len(feature_names or [])
    with (args.output_dir / "dataset_summary.json").open("w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2)
        handle.write("\n")
    print(f"manifest,{args.output_dir / 'session_manifest.csv'}")
    print(f"summary,{args.output_dir / 'dataset_summary.json'}")
    print(f"feature_count,{summary['feature_count']}")
    print(f"excluded_windows_by_curation,{counters['curated_windows']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
