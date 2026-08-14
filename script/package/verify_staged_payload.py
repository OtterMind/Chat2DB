#!/usr/bin/env python3
"""Verify native staging preserves the canonical updater payload exactly."""

from __future__ import annotations

import hashlib
import os
import sys
import zipfile
from pathlib import Path, PurePosixPath


def fail(message: str) -> None:
    raise SystemExit(f"[error] {message}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_regular(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_file():
        fail(f"{label} must be a regular non-symlink file: {path}")


def verify_byte_copy(source: Path, staged: Path, label: str) -> None:
    require_regular(source, f"canonical {label}")
    require_regular(staged, f"staged {label}")
    if sha256_file(source) != sha256_file(staged):
        fail(f"staged {label} SHA-256 mismatch")


def zip_entries(archive: Path) -> dict[str, str]:
    require_regular(archive, "canonical archive")
    entries: dict[str, str] = {}
    with zipfile.ZipFile(archive) as payload:
        for entry in payload.infolist():
            if entry.is_dir():
                continue
            name = PurePosixPath(entry.filename)
            if name.is_absolute() or ".." in name.parts or not name.parts:
                fail(f"unsafe archive entry in {archive.name}: {entry.filename}")
            # POSIX file type bits in the upper external attribute word. A ZIP
            # symlink is never a valid staged updater payload entry.
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                fail(f"symbolic-link archive entry in {archive.name}: {entry.filename}")
            normalized = name.as_posix()
            if normalized in entries:
                fail(f"duplicate archive entry in {archive.name}: {normalized}")
            entries[normalized] = sha256_zip_entry(payload, entry)
    return entries


def sha256_zip_entry(payload: zipfile.ZipFile, entry: zipfile.ZipInfo) -> str:
    digest = hashlib.sha256()
    with payload.open(entry) as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def staged_entries(root: Path) -> dict[str, str]:
    if root.is_symlink() or not root.is_dir():
        fail(f"staged directory must be a non-symlink directory: {root}")
    entries: dict[str, str] = {}
    for current_root, directories, files in os.walk(root, followlinks=False):
        current = Path(current_root)
        for directory in directories:
            candidate = current / directory
            if candidate.is_symlink():
                fail(f"staged directory contains a symbolic link: {candidate}")
        for filename in files:
            candidate = current / filename
            if candidate.is_symlink() or not candidate.is_file():
                fail(f"staged directory contains a non-regular file: {candidate}")
            entries[candidate.relative_to(root).as_posix()] = sha256_file(candidate)
    return entries


def verify_archive_tree(archive: Path, root: Path, label: str) -> None:
    expected = zip_entries(archive)
    actual = staged_entries(root)
    if expected != actual:
        missing = sorted(set(expected) - set(actual))
        unexpected = sorted(set(actual) - set(expected))
        changed = sorted(key for key in set(expected) & set(actual) if expected[key] != actual[key])
        fail(f"staged {label} inventory mismatch"
             f" (missing={missing[:3]}, unexpected={unexpected[:3]}, changed={changed[:3]})")


def main(argv: list[str]) -> None:
    if len(argv) != 8:
        fail("usage: verify_staged_payload.py <canonical-jar> <staged-jar> <canonical-local-manifest> "
             "<staged-local-manifest> <canonical-lib.zip> <staged-lib-dir> <canonical-dist.zip> <staged-dist-dir>")
    canonical_jar, staged_jar, canonical_local, staged_local, lib_zip, staged_lib, dist_zip, staged_dist = map(Path, argv)
    verify_byte_copy(canonical_jar, staged_jar, "chat2db-community.jar")
    verify_byte_copy(canonical_local, staged_local, "local_version.json")
    verify_archive_tree(lib_zip, staged_lib, "lib")
    verify_archive_tree(dist_zip, staged_dist, "dist")


if __name__ == "__main__":
    main(sys.argv[1:])
