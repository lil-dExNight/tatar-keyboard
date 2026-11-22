#!/usr/bin/env python3
"""Load and verify the shared exclusions for Tatar final-set builders."""
from __future__ import annotations

import bz2
import gzip
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Mapping, Sequence

import make_eval_set

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INVENTORY = ROOT / "data" / "evaluation" / "final-exclusions-tt.json"
CORPUS_DIR_PREFIX = "${CORPUS_DIR}/"
FORMATS = frozenset(("text", "gzip_text", "tatoeba_tsv_bz2", "recipe_provided"))
REQUIRED_FILE_FORMATS = FORMATS - {"recipe_provided"}


class FinalExclusionError(ValueError):
    """A malformed, unpinned, or missing required final-builder input."""


@dataclass(frozen=True)
class Pin:
    size_bytes: int
    sha256: str
    source: str


@dataclass(frozen=True)
class ExclusionSource:
    identifier: str
    format: str
    configured_path: str | None
    sentence_column: int
    declared_pin: Pin | None

    def resolve(self, root: Path, corpus_dir: Path) -> Path:
        assert self.configured_path is not None
        if self.configured_path.startswith(CORPUS_DIR_PREFIX):
            return corpus_dir.expanduser() / self.configured_path[len(CORPUS_DIR_PREFIX):]
        return root / self.configured_path


def _positive_int(value: object) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise FinalExclusionError("pin size must be a positive integer")
    return value


def _lowercase_hash(value: object) -> str:
    if not isinstance(value, str) or len(value) != 64:
        raise FinalExclusionError("pin sha256 must be 64 lowercase hex characters")
    try:
        int(value, 16)
    except ValueError as error:
        raise FinalExclusionError("pin sha256 must be 64 lowercase hex characters") from error
    if value.lower() != value:
        raise FinalExclusionError("pin sha256 must use lowercase hex")
    return value


def _pin(raw: object, source: str) -> Pin:
    if not isinstance(raw, dict):
        raise FinalExclusionError(f"{source}: pin is not an object")
    return Pin(_positive_int(raw.get("size_bytes")), _lowercase_hash(raw.get("sha256")), source)


def load_inventory(path: Path = DEFAULT_INVENTORY) -> tuple[ExclusionSource, ...]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise FinalExclusionError(f"cannot read exclusion inventory {path}: {error}") from error
    if (
        not isinstance(document, dict)
        or document.get("schema") != 1
        or document.get("language") != "tat"
        or not isinstance(document.get("sources"), list)
        or not document["sources"]
    ):
        raise FinalExclusionError(f"{path}: invalid Tatar final-exclusion inventory")
    sources: list[ExclusionSource] = []
    identifiers: set[str] = set()
    paths: set[str] = set()
    for index, raw in enumerate(document["sources"]):
        if not isinstance(raw, dict):
            raise FinalExclusionError(f"{path}: source {index} is not an object")
        identifier = raw.get("id")
        configured_path = raw.get("path")
        source_format = raw.get("format")
        declared_pin = raw.get("pin")
        if (
            not isinstance(identifier, str)
            or not identifier
            or not isinstance(source_format, str)
            or source_format not in FORMATS
        ):
            raise FinalExclusionError(f"{path}: source {index} has an invalid id or format")
        if source_format == "recipe_provided":
            if configured_path is not None:
                raise FinalExclusionError(f"{path}: {identifier} must not declare a path")
        else:
            if not isinstance(configured_path, str) or not configured_path:
                raise FinalExclusionError(f"{path}: {identifier} needs a relative path")
            relative_text = (
                configured_path[len(CORPUS_DIR_PREFIX):]
                if configured_path.startswith(CORPUS_DIR_PREFIX) else configured_path
            )
            relative = Path(relative_text)
            if not relative_text or relative.is_absolute() or ".." in relative.parts:
                raise FinalExclusionError(f"{path}: {identifier} has a non-relative path")
            if configured_path in paths:
                raise FinalExclusionError(f"{path}: duplicate exclusion path {configured_path}")
            paths.add(configured_path)
        column = raw.get("sentence_column", 0)
        if not isinstance(column, int) or isinstance(column, bool) or column < 0:
            raise FinalExclusionError(f"{path}: {identifier} has an invalid sentence column")
        if source_format == "tatoeba_tsv_bz2" and "sentence_column" in raw:
            raise FinalExclusionError(f"{path}: {identifier} uses fixed Tatoeba TSV columns")
        if identifier in identifiers:
            raise FinalExclusionError(f"{path}: duplicate exclusion source id {identifier}")
        identifiers.add(identifier)
        sources.append(
            ExclusionSource(
                identifier,
                source_format,
                configured_path if isinstance(configured_path, str) else None,
                column,
                _pin(declared_pin, identifier) if declared_pin is not None else None,
            )
        )
    return tuple(sources)


def canonical(raw: str) -> str:
    return " ".join(make_eval_set.normalize_sentence(raw))


def _digest(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
            size += len(block)
    return size, digest.hexdigest()


def _pin_index(corpus_manifest: Path, provenance_dir: Path) -> dict[str, Pin]:
    pins: dict[str, Pin] = {}
    try:
        manifest = json.loads(corpus_manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise FinalExclusionError(f"cannot read corpus manifest {corpus_manifest}: {error}") from error
    if not isinstance(manifest, dict):
        raise FinalExclusionError(f"{corpus_manifest}: corpus manifest is not an object")
    for name, raw in manifest.items():
        if not isinstance(name, str) or not isinstance(raw, dict):
            continue
        if "size" not in raw or "sha256" not in raw:
            continue
        pins[f"{CORPUS_DIR_PREFIX}{name}"] = Pin(
            _positive_int(raw["size"]), _lowercase_hash(raw["sha256"]), corpus_manifest.name
        )
    trigram_shard = provenance_dir / "eval-trigram-common-voice-final.json"
    try:
        shard = json.loads(trigram_shard.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise FinalExclusionError(f"cannot read historical-news pin {trigram_shard}: {error}") from error
    historical = shard.get("historical_development_exclusion") if isinstance(shard, dict) else None
    if isinstance(historical, dict):
        name = historical.get("path")
        if isinstance(name, str):
            pins[f"{CORPUS_DIR_PREFIX}{name}"] = Pin(
                _positive_int(historical["size_bytes"]),
                _lowercase_hash(historical["sha256"]),
                trigram_shard.name,
            )
    try:
        shard_paths = sorted(provenance_dir.glob("*.json"))
    except OSError as error:
        raise FinalExclusionError(f"cannot list provenance directory {provenance_dir}: {error}") from error
    for shard_path in shard_paths:
        try:
            value = json.loads(shard_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(value, dict):
            continue
        files = value.get("input_files")
        if isinstance(files, list):
            for item in files:
                if not isinstance(item, dict):
                    continue
                text_path = item.get("text_path")
                if not isinstance(text_path, str):
                    continue
                pins[text_path] = Pin(
                    _positive_int(item.get("text_size_bytes")),
                    _lowercase_hash(item.get("text_sha256")),
                    shard_path.name,
                )
        artifact_path = value.get("path")
        if isinstance(artifact_path, str) and "size_bytes" in value and "sha256" in value:
            pins[artifact_path] = Pin(
                _positive_int(value["size_bytes"]),
                _lowercase_hash(value["sha256"]),
                shard_path.name,
            )
    return pins


def _verify(path: Path, pin: Pin) -> None:
    if not path.is_file():
        raise FinalExclusionError(f"required final-set exclusion is missing: {path}")
    size, digest = _digest(path)
    if size != pin.size_bytes or digest != pin.sha256:
        raise FinalExclusionError(
            f"{path.name} differs from its {pin.source} pin "
            f"(expected {pin.size_bytes}/{pin.sha256[:12]}…, "
            f"got {size}/{digest[:12]}…)"
        )


def _normalized_rows(stream, path: Path, column: int) -> Iterator[str]:
    for line_number, raw in enumerate(stream, 1):
        line = raw.rstrip("\r\n")
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if column >= len(fields):
            raise FinalExclusionError(
                f"{path}:{line_number}: sentence column {column} is absent"
            )
        normalized = canonical(fields[column])
        if normalized:
            yield normalized


def _text_sentences(
    path: Path, column: int, *, gzip_compressed: bool = False
) -> Iterator[str]:
    try:
        stream = (
            gzip.open(path, "rt", encoding="utf-8", newline="")
            if gzip_compressed else path.open("r", encoding="utf-8", newline="")
        )
    except OSError as error:
        raise FinalExclusionError(f"cannot open required exclusion {path}: {error}") from error
    with stream:
        yield from _normalized_rows(stream, path, column)


def _tatoeba_sentences(path: Path) -> Iterator[str]:
    try:
        stream = bz2.open(path, "rt", encoding="utf-8", newline="")
    except OSError as error:
        raise FinalExclusionError(f"cannot open required exclusion {path}: {error}") from error
    with stream:
        for raw in stream:
            fields = raw.rstrip("\r\n").split("\t")
            if len(fields) < 3 or fields[1] != "tat":
                continue
            normalized = canonical(fields[2])
            if normalized:
                yield normalized


def normalized_recipe_sentences(rows: Sequence[tuple[int, str]]) -> frozenset[str]:
    """Normalize every row from a recipe already verified by make_eval_set.run_conv_recipe."""
    return frozenset(
        normalized
        for normalized in (canonical(content) for _id, content in rows)
        if normalized
    )


def load_exclusion_namespaces(
    root: Path = ROOT,
    corpus_dir: Path = Path.home() / "corpora-leipzig",
    *,
    inventory_path: Path = DEFAULT_INVENTORY,
    skip_ids: frozenset[str] = frozenset(),
    provided_namespaces: Mapping[str, frozenset[str]] | None = None,
    corpus_manifest_path: Path | None = None,
    provenance_dir: Path | None = None,
) -> dict[str, frozenset[str]]:
    """Load every selected source, requiring its pin before parsing it."""
    sources = load_inventory(inventory_path)
    known = {source.identifier for source in sources}
    unknown = skip_ids - known
    if unknown:
        raise FinalExclusionError(f"unknown exclusion skip ids: {sorted(unknown)}")
    provided = dict(provided_namespaces or {})
    unknown_provided = set(provided) - known
    if unknown_provided:
        raise FinalExclusionError(f"provided namespaces for unknown ids: {sorted(unknown_provided)}")
    pins = _pin_index(
        corpus_manifest_path
        if corpus_manifest_path is not None
        else root / "data/corpus-manifest.json",
        provenance_dir if provenance_dir is not None else root / "data/provenance.d",
    )
    namespaces: dict[str, frozenset[str]] = {}
    for source in sources:
        if source.identifier in skip_ids:
            continue
        if source.format == "recipe_provided":
            if source.identifier not in provided:
                raise FinalExclusionError(
                    f"{source.identifier} must come from a verified conversational recipe"
                )
            namespaces[source.identifier] = provided[source.identifier]
            continue
        assert source.configured_path is not None
        path = source.resolve(root, corpus_dir)
        key = source.configured_path
        pin = source.declared_pin or pins.get(key)
        if pin is None:
            raise FinalExclusionError(
                f"{path}: no provenance pin is available for required final-builder input"
            )
        _verify(path, pin)
        if source.format == "tatoeba_tsv_bz2":
            rows = _tatoeba_sentences(path)
        else:
            rows = _text_sentences(
                path, source.sentence_column, gzip_compressed=source.format == "gzip_text"
            )
        namespaces[source.identifier] = frozenset(rows)
    return namespaces
