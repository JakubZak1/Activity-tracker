#!/usr/bin/env python3
"""Write a deterministic SHA-256 manifest for an immutable directory tree."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path, help="directory whose files are hashed")
    parser.add_argument("output", type=Path, help="manifest to create")
    parser.add_argument(
        "--exclude-name",
        action="append",
        default=[],
        help="file name to omit; may be repeated",
    )
    args = parser.parse_args()

    root = args.root.resolve()
    if not root.is_dir():
        raise SystemExit(f"Not a directory: {root}")

    excluded = set(args.exclude_name)
    files = sorted(
        (path for path in root.rglob("*") if path.is_file() and path.name not in excluded),
        key=lambda path: path.relative_to(root).as_posix().casefold(),
    )
    lines = [f"{sha256(path)}  {path.relative_to(root).as_posix()}" for path in files]

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"ok,manifest,{args.output},files,{len(files)}")


if __name__ == "__main__":
    main()
