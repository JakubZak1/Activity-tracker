#!/usr/bin/env python3
"""Small stdlib-only loader for Activity Tracker CSV logs."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class SensorSample:
    timestamp_ms: int
    label: str
    acc_x_g: float
    acc_y_g: float
    acc_z_g: float
    gyro_x_dps: float
    gyro_y_dps: float
    gyro_z_dps: float
    file: str


def load_log(path: Path) -> list[SensorSample]:
    samples: list[SensorSample] = []
    with path.open("r", newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            samples.append(
                SensorSample(
                    timestamp_ms=int(row["timestamp_ms"]),
                    label=row["label"],
                    acc_x_g=float(row["acc_x_g"]),
                    acc_y_g=float(row["acc_y_g"]),
                    acc_z_g=float(row["acc_z_g"]),
                    gyro_x_dps=float(row["gyro_x_dps"]),
                    gyro_y_dps=float(row["gyro_y_dps"]),
                    gyro_z_dps=float(row["gyro_z_dps"]),
                    file=path.name,
                )
            )
    return samples


def load_directory(raw_dir: Path) -> list[SensorSample]:
    samples: list[SensorSample] = []
    for path in sorted(raw_dir.glob("*.csv")):
        samples.extend(load_log(path))
    return samples


def main() -> int:
    parser = argparse.ArgumentParser(description="Load Activity Tracker CSV logs and print a short summary.")
    parser.add_argument("--raw-dir", default="dataset/raw/own", type=Path)
    args = parser.parse_args()

    samples = load_directory(args.raw_dir)
    labels = sorted({sample.label for sample in samples})
    files = sorted({sample.file for sample in samples})

    print(f"raw_dir,{args.raw_dir}")
    print(f"files,{len(files)}")
    print(f"samples,{len(samples)}")
    print(f"labels,{','.join(labels)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
