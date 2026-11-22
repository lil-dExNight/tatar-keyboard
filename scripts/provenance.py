#!/usr/bin/env python3
"""Validate sharded provenance for generated assets and evaluation data.

Each shard is one JSON object with an immutable revision, byte size, SHA-256, license, source
role, split rule, generation command, and repository-relative path. Every file under the
covered roots needs exactly one shard. Missing, duplicate, malformed, or stale entries fail.

Tracked assets and test resources are mandatory in every environment; there is no bypass.
Raw training dumps (source_role ``raw_corpus`` with a path under ``research/corpus/``) are
gitignored local inputs: they are size- and hash-verified whenever present and reported by
name when absent, so a clean ``git archive HEAD`` extraction without corpora or ``.git``
still validates every tracked artifact. The required dump set comes from the acquisition-pin
registry (``research/corpus-sources/build_provenance.py``), so deleting a raw shard cannot
narrow what validation demands. Tracked paths are fully verified before raw corpora,
so a missing or drifted tracked artifact always fails first. ``--require-raw-corpora`` makes
the dumps mandatory for corpus rebuild and corpus-derived evaluation validation;
``validate_raw_corpora`` is the shared strict preflight that asset rebuilds call before
writing any output.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
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
RAW_CORPUS_ROOT = Path("research/corpus")
# Single source of the required raw dumps: the acquisition-pin registry in the shard builder.
RAW_CORPUS_REGISTRY = Path("research/corpus-sources/build_provenance.py")
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


def load_raw_corpus_inventory(root: Path) -> tuple[str, ...]:
    """Repository paths of every required raw dump, read from the acquisition-pin registry.

    The registry module (``build_provenance.py``) is the single source of the dump set, so
    the requirement cannot be narrowed by deleting a shard or a local file.
    """
    path = root / RAW_CORPUS_REGISTRY
    if not path.is_file():
        raise ProvenanceError(f"raw corpus inventory is missing: {RAW_CORPUS_REGISTRY}")
    spec = importlib.util.spec_from_file_location("tt_raw_corpus_registry", path)
    if spec is None or spec.loader is None:
        raise ProvenanceError(f"raw corpus inventory is unparsable: {RAW_CORPUS_REGISTRY}")
    module = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(module)
    except (OSError, ValueError, SyntaxError) as error:
        raise ProvenanceError(f"raw corpus inventory is unparsable: {error}") from error
    dumps = getattr(module, "CORPUS_DUMPS", None)
    if not isinstance(dumps, dict) or not dumps or not all(
        isinstance(name, str) and name for name in dumps
    ):
        raise ProvenanceError(f"raw corpus inventory declares no dumps: {RAW_CORPUS_REGISTRY}")
    return tuple(f"{RAW_CORPUS_ROOT.as_posix()}/{name}" for name in sorted(dumps))


def _raw_corpus_shards(
    shards: Iterable[tuple[Path, dict[str, object]]]
) -> dict[str, tuple[Path, dict[str, object]]]:
    """Raw-corpus-role shards pointing inside the dump directory, keyed by dump path."""
    found: dict[str, tuple[Path, dict[str, object]]] = {}
    for shard_path, shard in shards:
        relative_text = shard.get("path")
        if not isinstance(relative_text, str):
            continue
        if shard.get("source_role") != "raw_corpus":
            continue
        if RAW_CORPUS_ROOT not in Path(relative_text).parents:
            continue
        found[relative_text] = (shard_path, shard)
    return found


def _check_raw_declarations(declared: set[str], inventory: Sequence[str]) -> None:
    """The registry and the raw shards must name the same dumps, before any byte is read."""
    expected = set(inventory)
    for missing in sorted(expected - declared):
        raise ProvenanceError(
            f"no provenance shard declares the required raw corpus dump: {missing}"
        )
    for extra in sorted(declared - expected):
        raise ProvenanceError(
            f"raw corpus shard is absent from the acquisition-pin registry: {extra}"
        )


def _verify_raw_dump(
    root: Path, label: Path, shard: dict[str, object], relative_text: str, required: bool
) -> str:
    """Size- and hash-verify one raw dump; returns ``"verified"`` or ``"missing"``."""
    size = shard.get("size_bytes")
    if not isinstance(size, int) or isinstance(size, bool) or size < 0:
        raise ProvenanceError(f"{label}: size_bytes must be a nonnegative integer")
    expected_sha256 = shard.get("sha256")
    if not isinstance(expected_sha256, str) or not SHA256_RE.fullmatch(expected_sha256):
        raise ProvenanceError(f"{label}: sha256 must be 64 lowercase hexadecimal digits")
    artifact = root / relative_text
    if not artifact.is_file():
        if required:
            raise ProvenanceError(
                f"{label}: raw corpus dump is required but missing: {relative_text}"
            )
        return "missing"
    actual_size = artifact.stat().st_size
    if actual_size != size:
        raise ProvenanceError(
            f"{label}: size mismatch for {relative_text}: {actual_size} != {size}"
        )
    if _sha256(artifact) != expected_sha256:
        raise ProvenanceError(f"{label}: SHA-256 mismatch for {relative_text}")
    return "verified"


def validate_raw_corpora(
    root: Path,
    manifest_dir: Path = DEFAULT_MANIFEST_DIR,
    raw_corpus_inventory: Sequence[str] | None = None,
) -> dict[str, int]:
    """Strict raw-corpus preflight for commands that rebuild or measure on corpus bytes.

    Every registry dump needs a provenance shard and matching bytes on disk: a deleted
    shard, an absent dump, or a drifted dump fails before any output is written.
    """
    shards = load_shards(root, manifest_dir)
    if raw_corpus_inventory is None:
        raw_corpus_inventory = load_raw_corpus_inventory(root)
    raw_by_path = _raw_corpus_shards(shards)
    _check_raw_declarations(set(raw_by_path), raw_corpus_inventory)
    verified = 0
    for relative_text in sorted(raw_by_path):
        shard_path, shard = raw_by_path[relative_text]
        _verify_raw_dump(
            root, shard_path.relative_to(root), shard, relative_text, required=True
        )
        verified += 1
    return {"verified_raw_corpora": verified}


def validate_shards(
    root: Path,
    manifest_dir: Path = DEFAULT_MANIFEST_DIR,
    covered_roots: Sequence[Path] = COVERED_ROOTS,
    require_raw_corpora: bool = False,
    raw_corpus_inventory: Sequence[str] | None = None,
) -> dict[str, int]:
    shards = load_shards(root, manifest_dir)
    if raw_corpus_inventory is None:
        raw_corpus_inventory = load_raw_corpus_inventory(root)
    raw_by_path = _raw_corpus_shards(shards)
    _check_raw_declarations(set(raw_by_path), raw_corpus_inventory)
    by_id: dict[str, Path] = {}
    by_path: dict[str, Path] = {}
    verified_bundles = 0
    unverified_bundles: list[str] = []
    verified_raw_corpora = 0
    missing_raw_corpora: list[str] = []
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
        # Declared raw training dumps are the only optional artifacts. Their verification is
        # deferred until every other path has been fully checked, so a missing or drifted
        # tracked artifact always fails before raw-corpus reporting or --require-raw-corpora
        # errors. The exemption is tied to the raw-corpus role and the gitignored dump
        # directory, and the declaration set was already matched against the registry.
        if relative_text in raw_by_path:
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
        # Artifacts tracked by git must live under the covered roots. Any other untracked
        # input must be gitignored; it is still hash-verified above.
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

    # Raw corpora come last: absent dumps are reported (or fail with --require-raw-corpora),
    # present dumps are always size- and hash-verified.
    for relative_text in sorted(raw_by_path):
        shard_path, shard = raw_by_path[relative_text]
        outcome = _verify_raw_dump(
            root,
            shard_path.relative_to(root),
            shard,
            relative_text,
            require_raw_corpora,
        )
        if outcome == "verified":
            verified_raw_corpora += 1
        else:
            missing_raw_corpora.append(relative_text)
    return {
        "shards": len(shards),
        "artifacts": len(by_path),
        "verified_bundles": verified_bundles,
        "unverified_bundles": len(unverified_bundles),
        "verified_raw_corpora": verified_raw_corpora,
        "missing_raw_corpora": len(missing_raw_corpora),
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--manifest-dir", type=Path, default=DEFAULT_MANIFEST_DIR)
    parser.add_argument(
        "--require-raw-corpora",
        action="store_true",
        help="fail when a raw corpus dump is absent (rebuild and corpus-derived evaluation "
        "validation); by default absent dumps are reported and present dumps hash-verified",
    )
    args = parser.parse_args(argv)
    try:
        result = validate_shards(
            args.root.resolve(), args.manifest_dir, require_raw_corpora=args.require_raw_corpora
        )
    except (ProvenanceError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    notes = []
    if result["verified_bundles"] or result["unverified_bundles"]:
        notes.append(
            f"{result['verified_bundles']} corpus bundles verified,"
            f" {result['unverified_bundles']} not built locally"
        )
    if result["verified_raw_corpora"] or result["missing_raw_corpora"]:
        notes.append(
            f"{result['verified_raw_corpora']} raw corpus dumps verified,"
            f" {result['missing_raw_corpora']} not present"
        )
    extra = f" ({'; '.join(notes)})" if notes else ""
    print(
        f"PASS: {result['shards']} provenance shards cover "
        f"{result['artifacts']} artifacts{extra}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
