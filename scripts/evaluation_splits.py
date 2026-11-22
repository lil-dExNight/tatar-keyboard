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
sentences, frequencies, dictionary_admissions, evaluation_words, and bigram_training. Splits
are train, development, and final. Sentence sources may set sentence_column for TSV input;
word tables may set word_column and word_namespace. Paths are repository-relative except the
portable ${CORPUS_DIR}/ prefix used by optional raw-corpus declarations; committed files cannot
be optional. An optional source is skipped only while its file is absent; a present file is
always parsed. Cross-split leak classification is fixed to development/final, while an optional
`evaluation_splits` field only controls which committed sentence roles are required.
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
KINDS = frozenset(
    ("sentences", "frequencies", "dictionary_admissions", "evaluation_words", "bigram_training")
)
WORD_TABLE_KINDS = frozenset(("frequencies", "dictionary_admissions", "evaluation_words"))
UNIQUE_WORD_TABLE_KINDS = frozenset(("frequencies", "dictionary_admissions"))
SPLITS = frozenset(("train", "development", "final"))
EVALUATION_SPLITS = frozenset(("development", "final"))
CORPUS_DIR_PREFIX = "${CORPUS_DIR}/"
DEFAULT_CORPUS_DIR = Path.home() / "corpora-leipzig"


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


def _source_path(root: Path, source: dict[str, object], corpus_dir: Path) -> Path:
    configured = str(source["path"])
    if configured.startswith(CORPUS_DIR_PREFIX):
        return corpus_dir.expanduser() / configured[len(CORPUS_DIR_PREFIX):]
    return root / configured


def _records(
    root: Path,
    source: dict[str, object],
    alphabet: frozenset[str],
    corpus_dir: Path,
) -> Iterable[tuple[str, int, str]]:
    relative = Path(str(source["path"]))
    path = _source_path(root, source, corpus_dir)
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


def _validate_config(
    config: object,
) -> tuple[str, list[dict[str, object]], frozenset[str]]:
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
        configured_path = str(source["path"])
        relative_text = (
            configured_path[len(CORPUS_DIR_PREFIX):]
            if configured_path.startswith(CORPUS_DIR_PREFIX) else configured_path
        )
        relative = Path(relative_text)
        if not relative_text or relative.is_absolute() or ".." in relative.parts:
            raise SplitConfigError(f"source {source['id']} path is not repository-relative")
        if "optional" in source:
            if not isinstance(source["optional"], bool):
                raise SplitConfigError(f"source {source['id']} optional must be a boolean")
            if source["optional"] and not configured_path.startswith(CORPUS_DIR_PREFIX):
                raise SplitConfigError(
                    f"source {source['id']} cannot make a committed artifact optional"
                )
        key = configured_path
        if key in paths:
            raise SplitConfigError(
                f"artifact {key} is assigned to both {paths[key]} and {source['id']}"
            )
        paths[key] = source["id"]
        sources.append(source)
    configured_evaluation_splits = config.get(
        "evaluation_splits", sorted(EVALUATION_SPLITS)
    )
    if (
        not isinstance(configured_evaluation_splits, list)
        or not configured_evaluation_splits
        or any(
            not isinstance(split, str) or split not in EVALUATION_SPLITS
            for split in configured_evaluation_splits
        )
        or len(set(configured_evaluation_splits)) != len(configured_evaluation_splits)
    ):
        raise SplitConfigError("evaluation_splits must contain development and/or final once")
    # Required evaluation splits need committed sentence sources so the collision gate can run
    # offline. Raw bigram inputs are declared as optional: clean archives skip only absent files,
    # while builders require every exclusion input they consume.
    for split in configured_evaluation_splits:
        if not any(
            source["split"] == split
            and source["kind"] == "sentences"
            and not source.get("optional", False)
            for source in sources
        ):
            raise SplitConfigError(f"{split} has no committed sentence source")
    return language, sources, frozenset(configured_evaluation_splits)


def check_splits(
    root: Path, config_path: Path, *, corpus_dir: Path = DEFAULT_CORPUS_DIR
) -> dict[str, object]:
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SplitConfigError(f"cannot read {config_path}: {error}") from error
    language, sources, required_evaluation_splits = _validate_config(config)
    alphabet = coverage.RUSSIAN.alphabet if language == "rus" else coverage.TATAR.alphabet
    sentence_owners: dict[str, tuple[str, str, int, str]] = {}
    word_owners: dict[tuple[str, str, str], tuple[str, int, str]] = {}
    counts: Counter[str] = Counter()
    collisions: list[dict[str, object]] = []
    optional_missing: list[str] = []
    for source in sources:
        kind = str(source["kind"])
        split = str(source["split"])
        identifier = str(source["id"])
        path = _source_path(root, source, corpus_dir)
        if source.get("optional", False) and not path.exists():
            optional_missing.append(identifier)
            continue
        for normalized, line_number, raw in _records(root, source, alphabet, corpus_dir):
            counts[f"{split}:{kind}"] += 1
            if kind in WORD_TABLE_KINDS:
                if kind in UNIQUE_WORD_TABLE_KINDS:
                    namespace = str(source.get("word_namespace", identifier))
                    key = (kind, namespace, normalized)
                    previous = word_owners.get(key)
                    if previous is not None:
                        collisions.append({
                            "kind": kind,
                            "normalized": normalized,
                            "first": {
                                "source": previous[0], "line": previous[1], "raw": previous[2]
                            },
                            "second": {"source": identifier, "line": line_number, "raw": raw},
                        })
                    else:
                        word_owners[key] = (identifier, line_number, raw)
                # Evaluation word targets may repeat with different typo operations; they are
                # measurements, not merge inputs, so they do not enter sentence/word ownership.
                continue
            previous = sentence_owners.get(normalized)
            if previous is None:
                sentence_owners[normalized] = (identifier, split, line_number, raw)
                continue
            first_id, first_split, first_line, first_raw = previous
            cross_split = first_split != split
            evaluation_leak = (
                split in EVALUATION_SPLITS or first_split in EVALUATION_SPLITS
            )
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
        "required_evaluation_splits": sorted(required_evaluation_splits),
        "optional_missing": optional_missing,
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
    parser.add_argument("--corpus-dir", type=Path, default=DEFAULT_CORPUS_DIR)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    config_path = args.config if args.config.is_absolute() else root / args.config
    try:
        report = check_splits(root, config_path, corpus_dir=args.corpus_dir)
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
