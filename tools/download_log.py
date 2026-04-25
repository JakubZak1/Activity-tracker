#!/usr/bin/env python3
"""Download CSV logs from the device over the serial command interface."""

from __future__ import annotations

import argparse
import csv
import time
from datetime import datetime
from pathlib import Path

from validate_dataset import validate_file

try:
    import serial
    from serial.tools import list_ports
except ModuleNotFoundError:
    serial = None
    list_ports = None


CSV_HEADER = "timestamp_ms,label,acc_x_g,acc_y_g,acc_z_g,gyro_x_dps,gyro_y_dps,gyro_z_dps"
DOWNLOADS_FIELDNAMES = ["device_file", "size_bytes", "downloaded_as", "downloaded_at"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download CSV logs from Activity Tracker flash over USB serial.")
    parser.add_argument("--port", help="Serial port, for example COM5. If omitted, the script tries to auto-detect one port.")
    parser.add_argument("--baud", default=115200, type=int)
    parser.add_argument("--file", help="Device file path, for example /walking_0002.csv")
    parser.add_argument("--all", action="store_true", help="Download all CSV files reported by the device list command.")
    parser.add_argument("--output", type=Path, help="Output CSV path. Defaults to dataset/raw/own/<file name>.")
    parser.add_argument("--output-dir", default=Path("dataset/raw/own"), type=Path)
    parser.add_argument("--registry", default=Path("dataset/downloads.csv"), type=Path)
    parser.add_argument("--ignore-registry", action="store_true", help="Do not skip files already listed in the download registry.")
    parser.add_argument("--prefix", help="Prefix added to output file names, for example 20260425_2130.")
    parser.add_argument(
        "--timestamp-prefix",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="Add a YYYYMMDD_HHMMSS prefix to downloaded file names.",
    )
    parser.add_argument("--timeout", default=30.0, type=float, help="Seconds without data before failing.")
    parser.add_argument("--stop-first", action="store_true", help="Send stop before reading the file.")
    parser.add_argument("--include-current", action="store_true", help="With --all, also download the file currently being logged.")
    parser.add_argument("--overwrite", action="store_true", help="Overwrite the output file if it already exists.")
    args = parser.parse_args()

    if args.all and args.file:
        parser.error("Use either --all or --file, not both.")
    if not args.all and not args.file:
        parser.error("Provide --file /name.csv or use --all.")
    if args.all and args.output:
        parser.error("--output is only valid with --file. Use --output-dir with --all.")
    if args.output and (args.prefix or args.timestamp_prefix):
        parser.error("--prefix and --timestamp-prefix are not used with explicit --output.")

    return args


def require_pyserial() -> None:
    if serial is not None:
        return
    print("error,missing_pyserial")
    print("Install it with:")
    print("python -m pip install pyserial")
    raise SystemExit(2)


def detect_port() -> str:
    ports = list(list_ports.comports())
    if len(ports) == 1:
        return ports[0].device

    if not ports:
        raise SystemExit("error,no_serial_ports_found,use --port COMx")

    print("error,multiple_serial_ports_found")
    for port in ports:
        print(f"port,{port.device},{port.description}")
    raise SystemExit("Use --port COMx to choose the board.")


def normalize_device_path(path: str) -> str:
    return path if path.startswith("/") else f"/{path}"


def default_output_path(device_path: str) -> Path:
    return Path("dataset/raw/own") / Path(device_path).name


def make_output_name(device_path: str, prefix: str | None) -> str:
    name = Path(device_path).name
    return f"{prefix}_{name}" if prefix else name


def choose_prefix(args: argparse.Namespace) -> str | None:
    if args.prefix:
        return args.prefix
    if args.timestamp_prefix is True:
        return datetime.now().strftime("%Y%m%d_%H%M%S")
    if args.timestamp_prefix is None and args.all:
        return datetime.now().strftime("%Y%m%d_%H%M%S")
    return None


def open_serial(port: str, baud: int, timeout: float):
    connection = serial.Serial(port=port, baudrate=baud, timeout=0.2, write_timeout=2.0)
    time.sleep(1.2)
    connection.reset_input_buffer()
    return connection


def send_command(connection, command: str) -> None:
    connection.write((command + "\n").encode("utf-8"))
    connection.flush()


def read_until_quiet(connection, quiet_s: float = 0.5, max_s: float = 5.0) -> list[str]:
    lines: list[str] = []
    start = time.monotonic()
    last_data = start
    while time.monotonic() - start < max_s:
        raw = connection.readline()
        if raw:
            last_data = time.monotonic()
            lines.append(raw.decode("utf-8", errors="replace").rstrip("\r\n"))
            continue
        if time.monotonic() - last_data >= quiet_s:
            break
    return lines


def get_current_file(connection, timeout: float) -> str | None:
    send_command(connection, "status")

    last_data = time.monotonic()
    while True:
        raw = connection.readline()
        if not raw:
            if time.monotonic() - last_data > timeout:
                raise TimeoutError("Timed out while waiting for status")
            continue

        last_data = time.monotonic()
        line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
        if line.startswith("error,"):
            raise RuntimeError(line)
        if not line.startswith("status,"):
            continue

        parts = line.split(",")
        for index, part in enumerate(parts):
            if part == "current_file" and index + 1 < len(parts):
                current_file = parts[index + 1]
                return None if current_file == "none" else normalize_device_path(current_file)


def list_device_files(connection, timeout: float) -> list[tuple[str, int]]:
    send_command(connection, "list")

    files: list[tuple[str, int]] = []
    saw_begin = False
    last_data = time.monotonic()
    while True:
        raw = connection.readline()
        if not raw:
            if time.monotonic() - last_data > timeout:
                raise TimeoutError("Timed out while waiting for file list")
            continue

        last_data = time.monotonic()
        line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
        if line == "files_begin":
            saw_begin = True
            continue
        if line == "files_end":
            break
        if line.startswith("error,"):
            raise RuntimeError(line)
        if not saw_begin or not line:
            continue

        name, _, _size = line.partition(",")
        if name.endswith(".csv"):
            try:
                size_bytes = int(_size)
            except ValueError:
                size_bytes = -1
            files.append((normalize_device_path(name), size_bytes))

    return files


def load_download_registry(path: Path) -> set[tuple[str, int]]:
    if not path.exists():
        return set()

    keys: set[tuple[str, int]] = set()
    with path.open("r", newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            try:
                keys.add((row["device_file"], int(row["size_bytes"])))
            except (KeyError, ValueError):
                continue
    return keys


def append_download_registry(path: Path, device_path: str, size_bytes: int, output_path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    file_exists = path.exists()
    with path.open("a", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=DOWNLOADS_FIELDNAMES)
        if not file_exists:
            writer.writeheader()
        writer.writerow(
            {
                "device_file": device_path,
                "size_bytes": size_bytes,
                "downloaded_as": str(output_path),
                "downloaded_at": datetime.now().isoformat(timespec="seconds"),
            }
        )


def is_protocol_line(line: str) -> bool:
    prefixes = (
        "commands:",
        "info,",
        "ok,",
        "status,",
        "space,",
        "battery,",
        "files_begin",
        "files_end",
        "read_end,",
        "error,",
        "warn,",
    )
    return line.startswith(prefixes)


def temp_output_path(output_path: Path) -> Path:
    return output_path.with_name(f"{output_path.name}.part")


def download_file(connection, device_path: str, output_path: Path, timeout: float) -> int:
    send_command(connection, f"read {device_path}")

    rows = 0
    saw_header = False
    last_data = time.monotonic()
    partial_path = temp_output_path(output_path)
    if partial_path.exists():
        partial_path.unlink()

    try:
        with partial_path.open("w", newline="", encoding="utf-8") as handle:
            while True:
                raw = connection.readline()
                if not raw:
                    if time.monotonic() - last_data > timeout:
                        raise TimeoutError(f"Timed out while waiting for {device_path}")
                    continue

                last_data = time.monotonic()
                line = raw.decode("utf-8", errors="replace").rstrip("\r\n")

                if line == f"read_end,{device_path}":
                    if not saw_header:
                        raise RuntimeError("csv_header_not_found")
                    break

                if line.startswith("error,"):
                    raise RuntimeError(line)

                if not saw_header:
                    if line == CSV_HEADER:
                        saw_header = True
                        handle.write(line + "\n")
                    elif is_protocol_line(line) or line == "":
                        continue
                    else:
                        print(f"skip,before_header,{line}")
                    continue

                if is_protocol_line(line) or line == "":
                    print(f"skip,protocol,{line}")
                    continue

                handle.write(line + "\n")
                rows += 1

        partial_path.replace(output_path)
        return rows
    except Exception:
        if partial_path.exists():
            partial_path.unlink()
            print(f"cleanup,removed_partial,{partial_path}")
        raise

    raise RuntimeError("download_ended_unexpectedly")


def validate_downloaded_file(output_path: Path) -> None:
    report = validate_file(output_path)
    if not report.issues:
        print(f"ok,validated,{output_path},rows,{report.rows},duration_s,{report.duration_s:.1f}")
        return

    output_path.unlink(missing_ok=True)
    joined = "; ".join(report.issues)
    raise RuntimeError(f"download_validation_failed,{output_path},{joined}")


def download_one(connection, device_path: str, output_path: Path, timeout: float, overwrite: bool) -> bool:
    if output_path.exists() and not overwrite:
        print(f"skip,exists,{output_path}")
        return False

    output_path.parent.mkdir(parents=True, exist_ok=True)
    rows = download_file(connection, device_path, output_path, timeout)
    validate_downloaded_file(output_path)
    print(f"ok,downloaded,{output_path},rows,{rows}")
    return True


def main() -> int:
    args = parse_args()
    require_pyserial()

    port = args.port or detect_port()
    output_prefix = choose_prefix(args)

    print(f"port,{port}")
    if output_prefix:
        print(f"output_prefix,{output_prefix}")

    with open_serial(port, args.baud, args.timeout) as connection:
        startup_lines = read_until_quiet(connection)
        for line in startup_lines:
            if line:
                print(f"device,{line}")

        if args.stop_first:
            send_command(connection, "stop")
            for line in read_until_quiet(connection, quiet_s=0.2, max_s=2.0):
                if line:
                    print(f"device,{line}")

        if args.all:
            current_file = get_current_file(connection, args.timeout)
            registry_keys = set() if args.ignore_registry else load_download_registry(args.registry)
            device_entries = list_device_files(connection, args.timeout)
            if current_file and not args.include_current:
                before_count = len(device_entries)
                device_entries = [entry for entry in device_entries if entry[0] != current_file]
                if len(device_entries) != before_count:
                    print(f"skip,current_file,{current_file}")
            print(f"device_csv_files,{len(device_entries)}")
            downloaded = 0
            skipped = 0
            failed = 0
            for device_path, size_bytes in device_entries:
                if not args.ignore_registry and (device_path, size_bytes) in registry_keys:
                    print(f"skip,registry,{device_path},size_bytes,{size_bytes}")
                    skipped += 1
                    continue

                output_path = args.output_dir / make_output_name(device_path, output_prefix)
                try:
                    did_download = download_one(connection, device_path, output_path, args.timeout, args.overwrite)
                except Exception as exc:
                    failed += 1
                    print(f"error,download_failed,{device_path},{exc}")
                    continue

                if did_download:
                    append_download_registry(args.registry, device_path, size_bytes, output_path)
                    registry_keys.add((device_path, size_bytes))
                    downloaded += 1
                else:
                    skipped += 1
            print(f"summary,downloaded,{downloaded},skipped,{skipped},failed,{failed}")
            return 0 if failed == 0 else 1

        device_path = normalize_device_path(args.file)
        output_path = args.output or (args.output_dir / make_output_name(device_path, output_prefix))
        print(f"device_file,{device_path}")
        print(f"output,{output_path}")
        download_one(connection, device_path, output_path, args.timeout, args.overwrite)
        append_download_registry(args.registry, device_path, -1, output_path)

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("error,interrupted")
        raise SystemExit(130)
