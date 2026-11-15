#!/usr/bin/env python3
"""Check train/development/final assignments and normalized evaluation collisions.

Sentence and bigram-training sources share one normalized-sentence namespace. Any
normalized sentence in development or final that also occurs in train, bigram training, or
the other evaluation split is a leak and is an error. Frequency and dictionary-admission
tables are word tables: within one word namespace no two rows may normalize to the same
word (including case or Unicode variants). A source's namespace is its optional
word_namespace, defaulting to the source id; tables that must stay mutually disjoint
(accepted vs rejected admissions) share one namespace, while tables merged downstream by
design (conversational and bonus frequencies) keep separate namespaces.

Config: {"language":"tat", "sources":[{"id", "kind", "split", "path", ...}]}. Kinds are
sentences, frequencies, dictionary_admissions, and bigram_training. Splits are train,
development, and final. Sentence sources may set sentence_column for TSV input; word tables
may set word_column and word_namespace. All paths are repository-relative.
"""
from __future__ import annotations

import argparse
import json
import sys
import unicodedata
from collections import Counter
from pathlib import Path
from typing import Iterable, Iterator, Sequence

import dictionary_coverage as coverage

ROOT = Path(__file__).resolve().parents[1]
EDGE_CHARS = "\"'«»„“”‘’()[]{}<>.,!?;:…—–-*_/\\|~`^&#№%+=@$"
KINDS = frozenset(("sentences", "frequencies", "dictionary_admissions", "bigram_training"))
SPLITS = frozenset(("train", "development", "final"))
EVALUATION_SPLITS = frozenset(("development", "final"))


class SplitConfigError(ValueError):
    """An invalid split configuration or input."""


class SplitCollisionError(ValueError):
    """Normalized records collide across splits or within a word table."""

    def __init__(self, report: dict[str, object]) -> None:
        super().__init__("normalized evaluation collisions detected")
        self.report = report


def normalize_sentence(line: str, alphabet: frozenset[str]) -> str:
    words: list[str] = []
    for chunk in line.split():
        word = chunk.strip(EDGE_CHARS)
        if not word:
            continue
        normalized, _reason = coverage.normalize_word(word, alphabet)
        if normalized is not None:
            words.append(normalized)
    return " ".join(words)


def normalize_word(word: str, alphabet: frozenset[str]) -> str | None:
    normalized, _reason = coverage.normalize_word(
        unicodedata.normalize("NFC", word), alphabet
    )
    return normalized


def _rows(path: Path) -> Iterator[tuple[int, list[str]]]:
    try:
        stream = path.open("r", encoding="utf-8", newline="")
    except OSError as error:
        raise SplitConfigError(f"cannot open {path}: {error}") from error
    with stream:
        skipped_header = False
        for line_number, raw in enumerate(stream, 1):
            line = raw.rstrip("\r\n")
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if not skipped_header:
                skipped_header = True
                if fields[0] in ("word", "case_id"):
                    continue
            yield line_number, fields


def _records(
    root: Path, source: dict[str, object], alphabet: frozenset[str]
) -> Iterable[tuple[str, int, str]]:
    relative = Path(str(source["path"]))
    path = root / relative
    kind = source["kind"]
    if kind in ("sentences", "bigram_training"):
        column = int(source.get("sentence_column", 0))
        for line_number, fields in _rows(path):
            if column >= len(fields):
                raise SplitConfigError(
                    f"{relative}:{line_number}: sentence column {column} is absent"
                )
            normalized = normalize_sentence(fields[column], alphabet)
            if normalized:
                yield normalized, line_number, fields[column]
    else:
        column = int(source.get("word_column", 0))
        for line_number, fields in _rows(path):
            if column >= len(fields):
                raise SplitConfigError(
                    f"{relative}:{line_number}: word column {column} is absent"
                )
            normalized = normalize_word(fields[column], alphabet)
            if normalized is not None:
                yield normalized, line_number, fields[column]


def _validate_config(config: object) -> tuple[str, list[dict[str, object]]]:
    if not isinstance(config, dict) or not isinstance(config.get("sources"), list):
        raise SplitConfigError("config must be an object with a sources array")
    language = config.get("language", "tat")
    if language not in ("tat", "rus"):
        raise SplitConfigError("language must be tat or rus")
    sources: list[dict[str, object]] = []
    ids: set[str] = set()
    paths: dict[str, str] = {}
    for index, source in enumerate(config["sources"]):
        if not isinstance(source, dict):
            raise SplitConfigError(f"source {index} is not an object")
        for field in ("id", "kind", "split", "path"):
            if not isinstance(source.get(field), str) or not source[field]:
                raise SplitConfigError(f"source {index} has no valid {field}")
        if source["id"] in ids:
            raise SplitConfigError(f"duplicate source id {source['id']}")
        ids.add(source["id"])
        if source["kind"] not in KINDS:
            raise SplitConfigError(f"source {source['id']} has unknown kind {source['kind']}")
        if source["split"] not in SPLITS:
            raise SplitConfigError(f"source {source['id']} has unknown split {source['split']}")
        relative = Path(source["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise SplitConfigError(f"source {source['id']} path is not repository-relative")
        key = relative.as_posix()
        if key in paths:
            raise SplitConfigError(
                f"artifact {key} is assigned to both {paths[key]} and {source['id']}"
            )
        paths[key] = source["id"]
        sources.append(source)
    # Development and final need committed sentence sources so the collision gate can run
    # offline. Train sentences (Leipzig, conversational streams) are not committed to the
    # repository and are excluded from development/final by the evaluation builders themselves
    # (make_eval_set.py verifies the conversational digest; build_provenance.py re-checks
    # the pinned bundle), so a train sentence source is optional here.
    for split in ("development", "final"):
        if not any(
            source["split"] == split and source["kind"] == "sentences" for source in sources
        ):
            raise SplitConfigError(f"{split} has no sentence source")
    return language, sources


def check_splits(root: Path, config_path: Path) -> dict[str, object]:
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SplitConfigError(f"cannot read {config_path}: {error}") from error
    language, sources = _validate_config(config)
    alphabet = coverage.RUSSIAN.alphabet if language == "rus" else coverage.TATAR.alphabet
    sentence_owners: dict[str, tuple[str, str, int, str]] = {}
    word_owners: dict[tuple[str, str, str], tuple[str, int, str]] = {}
    counts: Counter[str] = Counter()
    collisions: list[dict[str, object]] = []
    for source in sources:
        kind = str(source["kind"])
        split = str(source["split"])
        identifier = str(source["id"])
        for normalized, line_number, raw in _records(root, source, alphabet):
            counts[f"{split}:{kind}"] += 1
            if kind in ("frequencies", "dictionary_admissions"):
                namespace = str(source.get("word_namespace", identifier))
                key = (kind, namespace, normalized)
                previous = word_owners.get(key)
                if previous is not None:
                    collisions.append({
                        "kind": kind,
                        "normalized": normalized,
                        "first": {"source": previous[0], "line": previous[1], "raw": previous[2]},
                        "second": {"source": identifier, "line": line_number, "raw": raw},
                    })
                else:
                    word_owners[key] = (identifier, line_number, raw)
                continue
            previous = sentence_owners.get(normalized)
            if previous is None:
                sentence_owners[normalized] = (identifier, split, line_number, raw)
                continue
            first_id, first_split, first_line, first_raw = previous
            cross_split = first_split != split
            evaluation_leak = split in EVALUATION_SPLITS or first_split in EVALUATION_SPLITS
            if cross_split and evaluation_leak:
                collisions.append({
                    "kind": "normalized_sentence",
                    "normalized": normalized,
                    "first": {
                        "source": first_id,
                        "split": first_split,
                        "line": first_line,
                        "raw": first_raw,
                    },
                    "second": {
                        "source": identifier,
                        "split": split,
                        "line": line_number,
                        "raw": raw,
                    },
                })
    report = {
        "language": language,
        "records": dict(sorted(counts.items())),
        "unique_normalized_sentences": len(sentence_owners),
        "unique_normalized_words": len(word_owners),
        "collisions": collisions,
        "collision_count": len(collisions),
        "passed": not collisions,
    }
    if collisions:
        raise SplitCollisionError(report)
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("config", type=Path)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    config_path = args.config if args.config.is_absolute() else root / args.config
    try:
        report = check_splits(root, config_path)
    except SplitCollisionError as error:
        print(json.dumps(error.report, ensure_ascii=False, indent=2, sort_keys=True))
        return 1
    except SplitConfigError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
