#!/usr/bin/env python3
"""Shared, deterministic dataset preparation for the Activity Tracker models."""

from __future__ import annotations

import csv
import json
import math
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

import numpy as np


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
TARGET_LABELS = ("walking", "running", "cycling", "sitting", "lying")
SENSOR_COLUMNS = EXPECTED_COLUMNS[2:]
METADATA_COLUMNS = [
    "file",
    "relative_path",
    "device_id",
    "placement",
    "label",
    "paired_session_id",
    "window_id",
    "start_ms",
    "end_ms",
    "session_elapsed_start_s",
    "session_elapsed_end_s",
    "sample_count",
]


@dataclass
class SessionFile:
    path: Path
    relative_path: str
    file: str
    device_id: str
    device_identity: str
    placement: str
    body_side: str
    label: str
    raw_label: str | None
    paired_session_id: str
    session_id: str
    started_at_epoch_ms: int | None
    rows: int
    first_timestamp_ms: int | None
    last_timestamp_ms: int | None
    duration_s: float
    effective_hz: float
    size_bytes: int
    crc32: str
    sidecar_size_bytes: int | None
    sidecar_crc32: str | None
    included: bool
    exclusion_reason: str
    metadata_status: str
    correction: str
    issues: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class Calibration:
    path: Path
    device_id: str
    acc_offset: np.ndarray
    acc_scale: np.ndarray
    gyro_bias: np.ndarray


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def crc32_file(path: Path) -> str:
    checksum = 0
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            checksum = zlib.crc32(chunk, checksum)
    return f"{checksum & 0xFFFFFFFF:08X}"


def inspect_csv(path: Path) -> tuple[int, int | None, int | None, str | None, list[str]]:
    issues: list[str] = []
    rows = 0
    first_timestamp: int | None = None
    last_timestamp: int | None = None
    previous_timestamp: int | None = None
    labels: set[str] = set()

    try:
        with path.open("r", newline="", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames != EXPECTED_COLUMNS:
                return 0, None, None, None, [f"unexpected columns: {reader.fieldnames}"]
            for row_number, row in enumerate(reader, start=2):
                rows += 1
                labels.add(row.get("label", ""))
                try:
                    timestamp = int(row["timestamp_ms"])
                    values = [float(row[name]) for name in SENSOR_COLUMNS]
                except (KeyError, TypeError, ValueError) as exc:
                    issues.append(f"row {row_number}: invalid value ({exc})")
                    continue
                if not all(math.isfinite(value) for value in values):
                    issues.append(f"row {row_number}: non-finite sensor value")
                if previous_timestamp is not None and timestamp <= previous_timestamp:
                    issues.append(f"row {row_number}: timestamp not strictly increasing")
                if first_timestamp is None:
                    first_timestamp = timestamp
                last_timestamp = timestamp
                previous_timestamp = timestamp
    except (OSError, UnicodeError) as exc:
        return 0, None, None, None, [f"cannot read CSV: {exc}"]

    if rows == 0:
        issues.append("empty data file")
    if len(labels) != 1:
        issues.append(f"expected one label, found {sorted(labels)}")
    raw_label = next(iter(labels)) if len(labels) == 1 else None
    if raw_label is not None and raw_label not in TARGET_LABELS:
        issues.append(f"unsupported label: {raw_label}")
    return rows, first_timestamp, last_timestamp, raw_label, issues


def load_calibrations(calibration_dir: Path) -> dict[str, Calibration]:
    calibrations: dict[str, Calibration] = {}
    for path in sorted(calibration_dir.glob("xiao_unit_*.json")):
        data = load_json(path)
        device_id = str(data["device"]["short_id"]).upper()
        calibrations[device_id] = Calibration(
            path=path,
            device_id=device_id,
            acc_offset=np.asarray(data["accelerometer"]["offset_g"], dtype=np.float64),
            acc_scale=np.asarray(data["accelerometer"]["scale_multiplier"], dtype=np.float64),
            gyro_bias=np.asarray(data["gyroscope"]["bias_dps"], dtype=np.float64),
        )
    return calibrations


def discover_sessions(raw_dir: Path, curation_path: Path) -> tuple[list[SessionFile], dict[str, Any]]:
    curation = load_json(curation_path)
    corrections = curation.get("label_corrections", {})
    metadata_overrides = curation.get("metadata_overrides", {})
    excluded_files = curation.get("excluded_files", {})
    placements = {key.upper(): value for key, value in curation["placements"].items()}
    sessions: list[SessionFile] = []
    seen_names: set[str] = set()

    for path in sorted(raw_dir.rglob("*.csv")):
        if path.name in seen_names:
            raise ValueError(f"duplicate CSV filename in dataset: {path.name}")
        seen_names.add(path.name)

        rows, first_ts, last_ts, raw_label, issues = inspect_csv(path)
        size_bytes = path.stat().st_size
        checksum = crc32_file(path)
        sidecar_path = Path(f"{path}.session.json")
        sidecar = load_json(sidecar_path) if sidecar_path.exists() else {}
        override = metadata_overrides.get(path.name, {})
        correction = corrections.get(path.name, {})

        device_id = path.name.split("_", 1)[0].upper()
        placement = str(sidecar.get("sensor_placement") or override.get("sensor_placement") or placements.get(device_id, ""))
        device_identity = str(sidecar.get("device_identity") or override.get("device_identity") or device_id)
        label = str(correction.get("corrected_label") or sidecar.get("activity") or override.get("activity") or raw_label or "")
        paired_session_id = str(sidecar.get("paired_session_id") or override.get("paired_session_id") or "")
        session_id = str(sidecar.get("session_id") or override.get("session_id") or f"recovered-{path.stem}")
        body_side = str(sidecar.get("body_side") or override.get("body_side") or "left")
        started_at = sidecar.get("started_at_epoch_ms") or override.get("started_at_epoch_ms")
        sidecar_size = sidecar.get("size_bytes")
        sidecar_crc = str(sidecar.get("crc32", "")).upper() or None
        metadata_status = "verified"

        if not sidecar:
            metadata_status = "recovered_from_override" if override else "missing_sidecar"
            if not override:
                issues.append("missing sidecar and metadata override")
        elif sidecar_size != size_bytes or sidecar_crc != checksum:
            expected_size = correction.get("actual_size_bytes") or override.get("actual_size_bytes")
            expected_crc = str(correction.get("actual_crc32") or override.get("actual_crc32") or "").upper()
            if expected_size == size_bytes and expected_crc == checksum:
                metadata_status = "verified_after_declared_correction"
            else:
                metadata_status = "sidecar_integrity_mismatch"
                issues.append(
                    f"sidecar integrity mismatch (sidecar {sidecar_size}/{sidecar_crc}, actual {size_bytes}/{checksum})"
                )

        if correction:
            if raw_label != label:
                issues.append(f"corrected CSV content label {raw_label!r} != declared {label!r}")
            correction_text = str(correction.get("reason", "declared label correction"))
        else:
            correction_text = ""
            if raw_label and label != raw_label:
                issues.append(f"metadata label {label!r} != CSV label {raw_label!r}")

        if label not in TARGET_LABELS:
            issues.append(f"invalid curated label: {label!r}")
        if placement not in {"wrist", "leg"}:
            issues.append(f"invalid placement: {placement!r}")
        if not paired_session_id:
            issues.append("missing paired_session_id")

        duration_s = ((last_ts - first_ts) / 1000.0) if first_ts is not None and last_ts is not None else 0.0
        effective_hz = ((rows - 1) / duration_s) if rows > 1 and duration_s > 0 else 0.0
        exclusion_reason = str(excluded_files.get(path.name, ""))
        included = not issues and not exclusion_reason
        sessions.append(
            SessionFile(
                path=path,
                relative_path=path.relative_to(raw_dir).as_posix(),
                file=path.name,
                device_id=device_id,
                device_identity=device_identity,
                placement=placement,
                body_side=body_side,
                label=label,
                raw_label=raw_label,
                paired_session_id=paired_session_id,
                session_id=session_id,
                started_at_epoch_ms=int(started_at) if started_at is not None else None,
                rows=rows,
                first_timestamp_ms=first_ts,
                last_timestamp_ms=last_ts,
                duration_s=duration_s,
                effective_hz=effective_hz,
                size_bytes=size_bytes,
                crc32=checksum,
                sidecar_size_bytes=int(sidecar_size) if sidecar_size is not None else None,
                sidecar_crc32=sidecar_crc,
                included=included,
                exclusion_reason=exclusion_reason,
                metadata_status=metadata_status,
                correction=correction_text,
                issues=issues,
            )
        )
    return sessions, curation


def session_reference_timestamps(sessions: Iterable[SessionFile]) -> dict[tuple[str, str], int]:
    references: dict[tuple[str, str], int] = {}
    for session in sessions:
        if session.first_timestamp_ms is None:
            continue
        key = (session.paired_session_id, session.placement)
        references[key] = min(references.get(key, session.first_timestamp_ms), session.first_timestamp_ms)
    return references


def paired_exclusion_intervals(
    sessions: list[SessionFile], curation: dict[str, Any]
) -> dict[str, list[tuple[float, float, str]]]:
    by_file = {session.file: session for session in sessions}
    references = session_reference_timestamps(sessions)
    intervals: dict[str, list[tuple[float, float, str]]] = {}
    for item in curation.get("excluded_intervals", []):
        source = by_file.get(str(item["source_file"]))
        if source is None or source.first_timestamp_ms is None:
            raise ValueError(f"curation interval references missing file: {item['source_file']}")
        source_ref = references[(source.paired_session_id, source.placement)]
        base_s = (source.first_timestamp_ms - source_ref) / 1000.0
        start_s = base_s + float(item["start_s"])
        end_s = base_s + float(item["end_s"])
        reason = str(item.get("reason", "curated exclusion"))
        if end_s <= start_s:
            raise ValueError(f"invalid interval in {item['source_file']}: {start_s}..{end_s}")
        if item.get("propagate_to_pair", False):
            intervals.setdefault(source.paired_session_id, []).append((start_s, end_s, reason))
        else:
            intervals.setdefault(f"file:{source.file}", []).append((start_s, end_s, reason))
    return intervals


def write_manifest(path: Path, sessions: list[SessionFile]) -> None:
    fields = [
        "relative_path", "file", "device_id", "device_identity", "placement", "body_side", "label", "raw_label",
        "paired_session_id", "session_id", "started_at_epoch_ms", "rows", "duration_s", "effective_hz", "size_bytes",
        "crc32", "sidecar_size_bytes", "sidecar_crc32", "metadata_status", "included", "exclusion_reason", "correction", "issues",
    ]
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for session in sessions:
            writer.writerow(
                {
                    "relative_path": session.relative_path,
                    "file": session.file,
                    "device_id": session.device_id,
                    "device_identity": session.device_identity,
                    "placement": session.placement,
                    "body_side": session.body_side,
                    "label": session.label,
                    "raw_label": session.raw_label or "",
                    "paired_session_id": session.paired_session_id,
                    "session_id": session.session_id,
                    "started_at_epoch_ms": session.started_at_epoch_ms or "",
                    "rows": session.rows,
                    "duration_s": f"{session.duration_s:.3f}",
                    "effective_hz": f"{session.effective_hz:.6f}",
                    "size_bytes": session.size_bytes,
                    "crc32": session.crc32,
                    "sidecar_size_bytes": session.sidecar_size_bytes or "",
                    "sidecar_crc32": session.sidecar_crc32 or "",
                    "metadata_status": session.metadata_status,
                    "included": str(session.included).lower(),
                    "exclusion_reason": session.exclusion_reason,
                    "correction": session.correction,
                    "issues": "; ".join(session.issues),
                }
            )


def load_samples(path: Path) -> tuple[np.ndarray, np.ndarray]:
    timestamps: list[int] = []
    values: list[list[float]] = []
    with path.open("r", newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            timestamps.append(int(row["timestamp_ms"]))
            values.append([float(row[name]) for name in SENSOR_COLUMNS])
    return np.asarray(timestamps, dtype=np.int64), np.asarray(values, dtype=np.float64)


def apply_calibration(values: np.ndarray, calibration: Calibration) -> np.ndarray:
    corrected = values.copy()
    corrected[:, :3] = (corrected[:, :3] - calibration.acc_offset) * calibration.acc_scale
    corrected[:, 3:] = corrected[:, 3:] - calibration.gyro_bias
    return corrected


def _stats(prefix: str, values: np.ndarray) -> dict[str, float]:
    q25, median, q75 = np.percentile(values, [25, 50, 75])
    minimum = float(np.min(values))
    maximum = float(np.max(values))
    return {
        f"{prefix}_mean": float(np.mean(values)),
        f"{prefix}_std": float(np.std(values)),
        f"{prefix}_min": minimum,
        f"{prefix}_max": maximum,
        f"{prefix}_range": maximum - minimum,
        f"{prefix}_rms": float(np.sqrt(np.mean(np.square(values)))),
        f"{prefix}_median": float(median),
        f"{prefix}_iqr": float(q75 - q25),
        f"{prefix}_energy": float(np.mean(np.square(values))),
    }


def _spectral(prefix: str, values: np.ndarray, sample_rate_hz: float) -> dict[str, float]:
    centered = values - np.mean(values)
    spectrum = np.abs(np.fft.rfft(centered)) ** 2
    frequencies = np.fft.rfftfreq(len(centered), d=1.0 / sample_rate_hz)
    if len(spectrum) <= 1 or float(np.sum(spectrum[1:])) <= 0.0:
        return {f"{prefix}_dominant_hz": 0.0, f"{prefix}_spectral_energy": 0.0, f"{prefix}_spectral_entropy": 0.0}
    non_dc = spectrum[1:]
    probabilities = non_dc / np.sum(non_dc)
    entropy = -float(np.sum(probabilities * np.log2(probabilities + np.finfo(float).eps)))
    entropy /= math.log2(len(probabilities)) if len(probabilities) > 1 else 1.0
    return {
        f"{prefix}_dominant_hz": float(frequencies[1 + int(np.argmax(non_dc))]),
        f"{prefix}_spectral_energy": float(np.mean(non_dc)),
        f"{prefix}_spectral_entropy": entropy,
    }


def extract_features(values: np.ndarray, sample_rate_hz: float) -> dict[str, float]:
    features: dict[str, float] = {}
    for index, name in enumerate(SENSOR_COLUMNS):
        features.update(_stats(name, values[:, index]))

    acc_mag = np.linalg.norm(values[:, :3], axis=1)
    gyro_mag = np.linalg.norm(values[:, 3:], axis=1)
    acc_dynamic_mag = np.abs(acc_mag - 1.0)
    for name, signal in (
        ("acc_mag_g", acc_mag),
        ("acc_dynamic_mag_g", acc_dynamic_mag),
        ("gyro_mag_dps", gyro_mag),
    ):
        features.update(_stats(name, signal))
        features.update(_spectral(name, signal, sample_rate_hz))

    for prefix, triplet in (("acc", values[:, :3]), ("gyro", values[:, 3:])):
        centered = triplet - np.mean(triplet, axis=0)
        features[f"{prefix}_sma"] = float(np.mean(np.sum(np.abs(centered), axis=1)))
        for left, right in ((0, 1), (0, 2), (1, 2)):
            left_std = float(np.std(triplet[:, left]))
            right_std = float(np.std(triplet[:, right]))
            correlation = 0.0 if left_std == 0.0 or right_std == 0.0 else float(np.corrcoef(triplet[:, left], triplet[:, right])[0, 1])
            features[f"{prefix}_corr_{'xyz'[left]}{'xyz'[right]}"] = correlation
    return features


def intervals_overlap(start_s: float, end_s: float, intervals: Iterable[tuple[float, float, str]]) -> bool:
    return any(start_s < interval_end and end_s > interval_start for interval_start, interval_end, _ in intervals)


def build_feature_rows(
    sessions: list[SessionFile],
    curation: dict[str, Any],
    calibrations: dict[str, Calibration],
    *,
    sample_rate_hz: float = 52.0,
    window_s: float = 5.0,
    overlap: float = 0.5,
    trim_start_s: float = 5.0,
    trim_end_s: float = 5.0,
) -> tuple[dict[str, list[dict[str, Any]]], dict[str, int]]:
    if not 0 <= overlap < 1:
        raise ValueError("overlap must be in [0, 1)")
    window_samples = int(round(window_s * sample_rate_hz))
    stride_samples = int(round(window_samples * (1.0 - overlap)))
    trim_start_samples = int(round(trim_start_s * sample_rate_hz))
    trim_end_samples = int(round(trim_end_s * sample_rate_hz))
    if window_samples < 2 or stride_samples < 1:
        raise ValueError("window and stride must contain samples")

    references = session_reference_timestamps(sessions)
    excluded = paired_exclusion_intervals(sessions, curation)
    rows_by_placement: dict[str, list[dict[str, Any]]] = {"wrist": [], "leg": []}
    counters = {"included_files": 0, "excluded_files": 0, "windows": 0, "curated_windows": 0}

    for session in sessions:
        if not session.included:
            counters["excluded_files"] += 1
            continue
        calibration = calibrations.get(session.device_id)
        if calibration is None:
            raise ValueError(f"missing calibration for device {session.device_id}")
        timestamps, values = load_samples(session.path)
        values = apply_calibration(values, calibration)
        usable_start = trim_start_samples
        usable_stop = len(timestamps) - trim_end_samples
        if usable_stop - usable_start < window_samples:
            counters["excluded_files"] += 1
            continue
        counters["included_files"] += 1
        reference = references[(session.paired_session_id, session.placement)]
        pair_intervals = excluded.get(session.paired_session_id, [])
        file_intervals = excluded.get(f"file:{session.file}", [])
        window_id = 0
        for start in range(usable_start, usable_stop - window_samples + 1, stride_samples):
            stop = start + window_samples
            start_elapsed_s = (int(timestamps[start]) - reference) / 1000.0
            end_elapsed_s = (int(timestamps[stop - 1]) - reference) / 1000.0
            if intervals_overlap(start_elapsed_s, end_elapsed_s, (*pair_intervals, *file_intervals)):
                counters["curated_windows"] += 1
                continue
            row: dict[str, Any] = {
                "file": session.file,
                "relative_path": session.relative_path,
                "device_id": session.device_id,
                "placement": session.placement,
                "label": session.label,
                "paired_session_id": session.paired_session_id,
                "window_id": window_id,
                "start_ms": int(timestamps[start]),
                "end_ms": int(timestamps[stop - 1]),
                "session_elapsed_start_s": round(start_elapsed_s, 3),
                "session_elapsed_end_s": round(end_elapsed_s, 3),
                "sample_count": window_samples,
            }
            row.update(extract_features(values[start:stop], sample_rate_hz))
            rows_by_placement[session.placement].append(row)
            counters["windows"] += 1
            window_id += 1
    return rows_by_placement, counters


def write_feature_csv(path: Path, rows: list[dict[str, Any]]) -> list[str]:
    if not rows:
        raise ValueError(f"no feature rows for {path}")
    feature_names = [name for name in rows[0] if name not in METADATA_COLUMNS]
    fields = METADATA_COLUMNS + feature_names
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    return feature_names

