#!/usr/bin/env python3
"""Plot one Activity Tracker CSV session for quick sensor sanity checks."""

from __future__ import annotations

import argparse
import math
import os
from pathlib import Path

from dataset_loader import SensorSample, load_log
from validate_dataset import validate_file

os.environ.setdefault("MPLCONFIGDIR", str(Path("dataset/results/matplotlib_cache")))

try:
    import matplotlib.pyplot as plt
except ModuleNotFoundError:
    plt = None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Plot accelerometer, gyroscope, and magnitude traces for one CSV log.")
    parser.add_argument("csv_file", type=Path)
    parser.add_argument("--output-dir", default=Path("dataset/results/plots"), type=Path)
    parser.add_argument("--output", type=Path, help="Explicit PNG output path.")
    parser.add_argument("--trim-start-s", default=0.0, type=float, help="Seconds ignored at the start of the session.")
    parser.add_argument("--trim-end-s", default=0.0, type=float, help="Seconds ignored at the end of the session.")
    parser.add_argument("--show", action="store_true", help="Open an interactive plot window after saving.")
    return parser.parse_args()


def require_matplotlib() -> None:
    if plt is not None:
        return
    print("error,missing_matplotlib")
    print("Install it with:")
    print("python -m pip install matplotlib")
    raise SystemExit(2)


def relative_time_s(samples: list[SensorSample]) -> list[float]:
    start_ms = samples[0].timestamp_ms
    return [(sample.timestamp_ms - start_ms) / 1000.0 for sample in samples]


def magnitude(x: float, y: float, z: float) -> float:
    return math.sqrt((x * x) + (y * y) + (z * z))


def default_output_path(csv_file: Path, output_dir: Path) -> Path:
    return output_dir / f"{csv_file.stem}.png"


def trimmed_output_path(csv_file: Path, output_dir: Path, trim_start_s: float, trim_end_s: float) -> Path:
    if trim_start_s == 0 and trim_end_s == 0:
        return default_output_path(csv_file, output_dir)
    start_tag = f"{trim_start_s:g}s".replace(".", "p")
    end_tag = f"{trim_end_s:g}s".replace(".", "p")
    return output_dir / f"{csv_file.stem}_trim_{start_tag}_{end_tag}.png"


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


def main() -> int:
    args = parse_args()
    require_matplotlib()

    report = validate_file(args.csv_file)
    if report.issues:
        print(f"error,invalid_csv,{args.csv_file},{'; '.join(report.issues)}")
        return 1

    samples = trim_samples(load_log(args.csv_file), args.trim_start_s, args.trim_end_s)
    if not samples:
        print(f"error,no_samples_after_trim,{args.csv_file}")
        return 1

    time_s = relative_time_s(samples)
    acc_mag = [magnitude(sample.acc_x_g, sample.acc_y_g, sample.acc_z_g) for sample in samples]
    gyro_mag = [magnitude(sample.gyro_x_dps, sample.gyro_y_dps, sample.gyro_z_dps) for sample in samples]

    output_path = args.output or trimmed_output_path(args.csv_file, args.output_dir, args.trim_start_s, args.trim_end_s)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    fig, axes = plt.subplots(4, 1, figsize=(12, 10), sharex=True)
    plotted_duration_s = (samples[-1].timestamp_ms - samples[0].timestamp_ms) / 1000.0
    fig.suptitle(
        f"{args.csv_file.name} | label={report.label} | plotted_rows={len(samples)} | "
        f"plotted_duration={plotted_duration_s:.1f}s | trim={args.trim_start_s:g}s/{args.trim_end_s:g}s"
    )

    axes[0].plot(time_s, [sample.acc_x_g for sample in samples], label="acc_x")
    axes[0].plot(time_s, [sample.acc_y_g for sample in samples], label="acc_y")
    axes[0].plot(time_s, [sample.acc_z_g for sample in samples], label="acc_z")
    axes[0].set_ylabel("acc [g]")
    axes[0].grid(True, alpha=0.3)
    axes[0].legend(loc="upper right")

    axes[1].plot(time_s, [sample.gyro_x_dps for sample in samples], label="gyro_x")
    axes[1].plot(time_s, [sample.gyro_y_dps for sample in samples], label="gyro_y")
    axes[1].plot(time_s, [sample.gyro_z_dps for sample in samples], label="gyro_z")
    axes[1].set_ylabel("gyro [dps]")
    axes[1].grid(True, alpha=0.3)
    axes[1].legend(loc="upper right")

    axes[2].plot(time_s, acc_mag, label="acc_mag")
    axes[2].set_ylabel("acc mag [g]")
    axes[2].set_xlabel("time [s]")
    axes[2].grid(True, alpha=0.3)
    axes[2].legend(loc="upper right")

    axes[3].plot(time_s, gyro_mag, label="gyro_mag")
    axes[3].set_ylabel("gyro mag [dps]")
    axes[3].set_xlabel("time [s]")
    axes[3].grid(True, alpha=0.3)
    axes[3].legend(loc="upper right")

    fig.tight_layout()
    fig.savefig(output_path, dpi=140)
    print(f"ok,plot_saved,{output_path}")

    if args.show:
        plt.show()
    else:
        plt.close(fig)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
