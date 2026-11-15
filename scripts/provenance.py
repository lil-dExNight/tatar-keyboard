#!/usr/bin/env python3
"""Validate sharded provenance for generated assets and evaluation data.

Each shard is one JSON object with an immutable revision, byte size, SHA-256, license, source
role, split rule, generation command, and repository-relative path. Every file under the
covered roots needs exactly one shard. Missing, duplicate, malformed, or stale entries fail.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Iterable, Sequence

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST_DIR = Path("data/provenance.d")
COVERED_ROOTS = (Path("app/src/main/assets"), Path("app/src/test/resources"))
REQUIRED_FIELDS = (
    "id",
    "revision",
    "size_bytes",
    "sha256",
    "license",
    "source_role",
    "split_rule",
    "generation_command",
    "path",
)
CORPUS_BUNDLE_ROOT = Path("build")
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")


class ProvenanceError(ValueError):
    """A missing, malformed, duplicate, or stale provenance shard."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _files_under(root: Path, relative: Path) -> Iterable[Path]:
    directory = root / relative
    if not directory.is_dir():
        return
    for path in sorted(directory.rglob("*")):
        if path.is_file():
            yield path


def _gitignored(root: Path, relative: str) -> bool:
    import subprocess

    try:
        result = subprocess.run(
            ["git", "check-ignore", "-q", "--", relative],
            cwd=root,
            capture_output=True,
            check=False,
        )
    except OSError:
        return False
    return result.returncode == 0


def load_shards(root: Path, manifest_dir: Path) -> list[tuple[Path, dict[str, object]]]:
    directory = root / manifest_dir
    if not directory.is_dir():
        raise ProvenanceError(f"provenance directory is missing: {manifest_dir}")
    paths = sorted(directory.glob("*.json"))
    if not paths:
        raise ProvenanceError(f"no provenance shards in {manifest_dir}")
    shards: list[tuple[Path, dict[str, object]]] = []
    for path in paths:
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ProvenanceError(f"cannot read {path.relative_to(root)}: {error}") from error
        if not isinstance(value, dict):
            raise ProvenanceError(f"{path.relative_to(root)} is not a JSON object")
        shards.append((path, value))
    return shards


def validate_shards(
    root: Path,
    manifest_dir: Path = DEFAULT_MANIFEST_DIR,
    covered_roots: Sequence[Path] = COVERED_ROOTS,
) -> dict[str, int]:
    shards = load_shards(root, manifest_dir)
    by_id: dict[str, Path] = {}
    by_path: dict[str, Path] = {}
    verified_bundles = 0
    unverified_bundles: list[str] = []
    for shard_path, shard in shards:
        label = shard_path.relative_to(root)
        # Two shard shapes share one directory: committed artifacts carry "path" and are
        # hash-verified unconditionally; corpus shards (the research/corpus-sources pipeline)
        # carry "bundle", a regenerable build output that is hash-verified whenever it has
        # been built locally and is otherwise reported by name as unverified.
        is_bundle = "bundle" in shard
        for field in REQUIRED_FIELDS:
            if field == "path" and is_bundle:
                continue
            if field not in shard:
                raise ProvenanceError(f"{label}: required field {field!r} is missing")
        if is_bundle == ("path" in shard):
            raise ProvenanceError(f"{label}: exactly one of 'path' or 'bundle' is required")
        for field in (
            "id",
            "revision",
            "license",
            "source_role",
            "split_rule",
            "generation_command",
            "bundle" if is_bundle else "path",
        ):
            if not isinstance(shard[field], str) or not shard[field].strip():
                raise ProvenanceError(f"{label}: {field} must be a nonempty string")
        identifier = shard["id"]
        if identifier in by_id:
            raise ProvenanceError(
                f"{label}: duplicate id {identifier!r} also used by {by_id[identifier]}"
            )
        by_id[identifier] = label
        relative_text = shard["bundle"] if is_bundle else shard["path"]
        relative = Path(relative_text)
        if relative.is_absolute() or ".." in relative.parts or relative_text != relative.as_posix():
            raise ProvenanceError(f"{label}: path must be a normalized repository-relative path")
        if relative_text in by_path:
            raise ProvenanceError(
                f"{label}: duplicate path {relative_text!r} also used by {by_path[relative_text]}"
            )
        by_path[relative_text] = label
        size = shard["size_bytes"]
        if not isinstance(size, int) or isinstance(size, bool) or size < 0:
            raise ProvenanceError(f"{label}: size_bytes must be a nonnegative integer")
        if not isinstance(shard["sha256"], str) or not SHA256_RE.fullmatch(shard["sha256"]):
            raise ProvenanceError(f"{label}: sha256 must be 64 lowercase hexadecimal digits")
        artifact = root / relative
        if is_bundle:
            if CORPUS_BUNDLE_ROOT not in relative.parents:
                raise ProvenanceError(f"{label}: bundle must live under {CORPUS_BUNDLE_ROOT}/")
            if artifact.is_file():
                actual_size = artifact.stat().st_size
                if actual_size != size:
                    raise ProvenanceError(
                        f"{label}: size mismatch for {relative_text}: {actual_size} != {size}"
                    )
                if _sha256(artifact) != shard["sha256"]:
                    raise ProvenanceError(f"{label}: SHA-256 mismatch for {relative_text}")
                verified_bundles += 1
            else:
                unverified_bundles.append(relative_text)
            continue
        if not artifact.is_file():
            raise ProvenanceError(f"{label}: artifact is missing: {relative_text}")
        actual_size = artifact.stat().st_size
        if actual_size != size:
            raise ProvenanceError(
                f"{label}: size mismatch for {relative_text}: {actual_size} != {size}"
            )
        actual_sha256 = _sha256(artifact)
        if actual_sha256 != shard["sha256"]:
            raise ProvenanceError(f"{label}: SHA-256 mismatch for {relative_text}")
        # Artifacts tracked by git must live under the covered roots. Untracked inputs
        # (gitignored training dumps) may live elsewhere; they are still hash-verified.
        in_covered = any(
            relative == covered or covered in relative.parents for covered in covered_roots
        )
        if not in_covered and not _gitignored(root, relative_text):
            raise ProvenanceError(f"{label}: artifact is outside the covered roots")

    for covered in covered_roots:
        for artifact in _files_under(root, covered):
            relative = artifact.relative_to(root).as_posix()
            if relative not in by_path and not _gitignored(root, relative):
                raise ProvenanceError(f"missing provenance for {relative}")
    return {
        "shards": len(shards),
        "artifacts": len(by_path),
        "verified_bundles": verified_bundles,
        "unverified_bundles": len(unverified_bundles),
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--manifest-dir", type=Path, default=DEFAULT_MANIFEST_DIR)
    args = parser.parse_args(argv)
    try:
        result = validate_shards(args.root.resolve(), args.manifest_dir)
    except (ProvenanceError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    extra = ""
    if result["verified_bundles"] or result["unverified_bundles"]:
        extra = (
            f" ({result['verified_bundles']} corpus bundles verified,"
            f" {result['unverified_bundles']} not built locally)"
        )
    print(
        f"PASS: {result['shards']} provenance shards cover "
        f"{result['artifacts']} artifacts{extra}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
