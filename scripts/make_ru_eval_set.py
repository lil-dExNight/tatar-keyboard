#!/usr/bin/env python3
"""Build the Russian suggestion eval set: Tatoeba sentences held out of bigram training.

Input: the Tatoeba ru dump in ``--corpus-dir`` (checked against TATOEBA_SHA256/TATOEBA_BYTES)
and the four training corpora of the shipped Russian bigram table: the thinned conv stream and
the three Leipzig ru sentence corpora in ``--leipzig-dir``, each checked against
``data/corpus-manifest.json`` (size and SHA-256) before reading. A Tatoeba line is dropped when
its normalized form appears in any normalized training corpus, so no eval sentence appears in
any training corpus; the exclusion is by content, not row id, and needs no conv stream rebuild.
Output: ``app/src/test/resources/ru_eval_sentences.txt`` (UTF-8/LF, ``#`` attribution header,
one normalized sentence per line, byte-identical on rebuild) and a JSON report on stdout.
Only Tatoeba lines (CC BY 2.0 FR) are written. Exits nonzero without writing output if an
input is missing or does not match its pin.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

import dictionary_coverage as coverage  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CORPUS_DIR = ROOT / "research" / "corpus"
DEFAULT_LEIPZIG_DIR = Path.home() / "corpora-leipzig"
DEFAULT_OUT = ROOT / "app" / "src" / "test" / "resources" / "ru_eval_sentences.txt"
CORPUS_MANIFEST = ROOT / "data" / "corpus-manifest.json"

TATOEBA_FILE = "Tatoeba-v2026-07-08.ru.txt.gz"
TATOEBA_SHA256 = "e49b94d540df0b27f0309ea49e25093398c5a4f4e7052945f19de08120f9e499"
TATOEBA_BYTES = 19_194_956

# The thinned conv stream lives directly in the Leipzig corpus dir; each Leipzig corpus lives
# in its own subdirectory, as the release tarballs unpack. All four are the bigram table's
# training inputs (rebuild_assets.py), and every one is pinned in data/corpus-manifest.json.
CONV_SENTENCES_FILE = "rus_conv_thinned60-sentences.txt"
LEIPZIG_SENTENCE_FILES = (
    "rus_news_2019_1M/rus_news_2019_1M-sentences.txt",
    "rus_news_2022_1M/rus_news_2022_1M-sentences.txt",
    "rus_wikipedia_2021_1M/rus_wikipedia_2021_1M-sentences.txt",
)

# Pinned sampling seed, an arbitrary value. Changing it produces a new eval set, not a rebuild.
SAMPLE_SEED = 20261003

DEFAULT_LIMIT = 1_000
DEFAULT_MIN_WORDS = 3
DEFAULT_MAX_WORDS = 12

# Characters that may hug a word inside a Tatoeba sentence: the rule of
# research/corpus/corpuslib.py (``_EDGE``), copied by value so scripts/ stays
# self-contained.
EDGE_CHARS = "\"'«»„“”‘’()[]{}<>.,!?;:…—–-*_/\\|~`^&#№%+=@$"

HEADER_LINES = (
    "# Russian evaluation sentences for the suggestion engine.",
    "#",
    "# Source: Tatoeba Russian sentences, dump Tatoeba-v2026-07-08 (https://tatoeba.org/).",
    "# License: Creative Commons Attribution 2.0 France (CC BY 2.0 FR),",
    "# https://creativecommons.org/licenses/by/2.0/fr/ -- sentences are contributed by",
    "# Tatoeba's members; the license permits use, modification and redistribution on the",
    "# single condition that the source is cited. This header is that citation; see also",
    "# app/src/main/assets/dictionaries/NOTICE.txt (Tatoeba section).",
    "#",
    "# Selection: Tatoeba lines NOT present in any training corpus of the shipped Russian",
    "# bigram table: rus_conv_thinned60 (the id % 60 == 0 rows of the Tatoeba-ru and",
    "# OpenSubtitles-ru conv stream) and the Leipzig rus_news_2019_1M, rus_news_2022_1M",
    "# and rus_wikipedia_2021_1M sentence corpora. All four training corpora are verified",
    "# against data/corpus-manifest.json (size and SHA-256) before reading, and the",
    "# exclusion is by normalized content, not row id. Tokens are normalized to NFC",
    "# lowercase Russian-alphabet words (scripts/dictionary_coverage.py normalize_word",
    "# with the Russian language; the Tatar alphabet is a superset and would let Tatar",
    "# letters through; hugging punctuation stripped per the dict_tokens rule),",
    "# sentences kept at 3..12 surviving words, deduplicated.",
    "# Sample: deterministic, hash-ordered by SHA-256 of seed 20261003 + line, at most",
    "# 1000 lines, sorted by code point. Lines starting with '#' are comments.",
    "#",
    "# Generator: scripts/make_ru_eval_set.py -- rebuilds byte-identically over the same",
    "# corpus inputs; no wall-clock fields. Do not edit by hand.",
)


class EvalSetError(ValueError):
    """An eval-set build error (bad or missing inputs); nothing is written."""


@dataclass(frozen=True)
class EvalBuild:
    """The built eval set plus everything a test needs to check its provenance."""

    lines: tuple[str, ...]
    # Normalized training sentences per corpus file name: the thinned conv stream plus the
    # three Leipzig ru sentence files.
    training_normalized: dict[str, frozenset[str]]
    stats: dict[str, object] = field(default_factory=dict)


def normalize_sentence(line: str) -> list[str]:
    """Tokenize a raw corpus line into normalized words (dict_tokens semantics).

    Surrounding punctuation is stripped from each whitespace token, then
    ``normalize_word`` (NFC, lowercase, Russian alphabet filter) decides; tokens it
    rejects are dropped. The Russian alphabet is passed explicitly: the Tatar default is
    a superset and would let a stray Tatar letter through. Returns the surviving words,
    possibly an empty list.
    """
    words: list[str] = []
    for chunk in line.split():
        word = chunk.strip(EDGE_CHARS)
        if not word:
            continue
        normalized, _reason = coverage.normalize_word(word, coverage.RUSSIAN.alphabet)
        if normalized is not None:
            words.append(normalized)
    return words


def read_tatoeba_lines(corpus_dir: Path) -> list[str]:
    """The pinned Tatoeba ru dump as plain lines; the gz is verified before decompressing."""
    path = corpus_dir / TATOEBA_FILE
    if not path.is_file():
        raise EvalSetError(f"required corpus input is missing: {path}")
    raw = path.read_bytes()
    if len(raw) != TATOEBA_BYTES or hashlib.sha256(raw).hexdigest() != TATOEBA_SHA256:
        raise EvalSetError(f"Tatoeba input does not match its pin: {path}")
    return gzip.decompress(raw).decode("utf-8").split("\n")


def load_training_normalized(leipzig_dir: Path, manifest_path: Path) -> dict[str, frozenset[str]]:
    """Normalized sentence forms of the four training corpora, pin-verified.

    Each file is checked against ``data/corpus-manifest.json`` (size and SHA-256) before it
    is read, like every corpus file the asset rebuild reads. All four are Leipzig
    ``id<TAB>sentence`` rows; the conv stream uses the same layout.
    """
    if not manifest_path.is_file():
        raise EvalSetError(f"corpus manifest is missing: {manifest_path}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise EvalSetError(f"corpus manifest does not parse: {error}") from error
    sets: dict[str, frozenset[str]] = {}
    for relative in (CONV_SENTENCES_FILE, *LEIPZIG_SENTENCE_FILES):
        path = leipzig_dir / relative
        name = path.name
        pin = manifest.get(name)
        if pin is None:
            raise EvalSetError(f"corpus manifest has no pin for {name}")
        if not path.is_file():
            raise EvalSetError(f"training corpus input is missing: {path}")
        raw = path.read_bytes()
        if len(raw) != pin["size"] or hashlib.sha256(raw).hexdigest() != pin["sha256"]:
            raise EvalSetError(
                f"training corpus input does not match its manifest pin: {path}"
            )
        normalized: set[str] = set()
        for line in raw.decode("utf-8").split("\n"):
            content = line.split("\t", 1)
            if len(content) != 2:
                continue
            words = normalize_sentence(content[1])
            if words:
                normalized.add(" ".join(words))
        sets[name] = frozenset(normalized)
    return sets


def build_eval_set(
    corpus_dir: Path = DEFAULT_CORPUS_DIR,
    leipzig_dir: Path = DEFAULT_LEIPZIG_DIR,
    limit: int = DEFAULT_LIMIT,
    min_words: int = DEFAULT_MIN_WORDS,
    max_words: int = DEFAULT_MAX_WORDS,
) -> EvalBuild:
    """Build the eval set deterministically; returns lines and provenance data.

    Candidates are all Tatoeba ru lines. Each is tokenized by ``normalize_sentence``;
    sentences outside [min_words, max_words] are dropped, normalized duplicates collapse
    (first wins), and any sentence whose normalized form occurs in the normalized thinned
    conv stream or in any of the normalized Leipzig training corpora is excluded. Candidates
    are ordered by SHA-256 of ``"<seed>\\t<line>"`` (stable across Python versions, unlike
    ``random.sample``), the first ``limit`` are kept and sorted by code point.
    """
    if limit <= 0:
        raise EvalSetError("limit must be positive")
    if not 0 < min_words <= max_words:
        raise EvalSetError("word bounds must satisfy 0 < min <= max")

    tatoeba_lines = read_tatoeba_lines(corpus_dir)
    training = load_training_normalized(leipzig_dir, CORPUS_MANIFEST)
    conv_normalized = training[CONV_SENTENCES_FILE]
    leipzig_names = tuple(Path(relative).name for relative in LEIPZIG_SENTENCE_FILES)

    seen: set[str] = set()
    candidates: list[str] = []
    dropped_length = 0
    dropped_empty = 0
    dropped_conv = 0
    dropped_leipzig = 0
    dropped_duplicate = 0
    for content in tatoeba_lines:
        if not content:
            continue
        words = normalize_sentence(content)
        if not words:
            dropped_empty += 1
            continue
        if not min_words <= len(words) <= max_words:
            dropped_length += 1
            continue
        normalized = " ".join(words)
        if normalized in conv_normalized:
            dropped_conv += 1
            continue
        if any(normalized in training[name] for name in leipzig_names):
            dropped_leipzig += 1
            continue
        if normalized in seen:
            dropped_duplicate += 1
            continue
        seen.add(normalized)
        candidates.append(normalized)

    if len(candidates) > limit:
        keyed = sorted(
            candidates,
            key=lambda line: hashlib.sha256(
                f"{SAMPLE_SEED}\t{line}".encode("utf-8")
            ).digest(),
        )
        selected = keyed[:limit]
    else:
        selected = candidates
    lines = tuple(sorted(selected))

    stats: dict[str, object] = {
        "tatoeba_lines": len(tatoeba_lines),
        "training_normalized_sentences": {
            name: len(sentences) for name, sentences in sorted(training.items())
        },
        "dropped_no_surviving_tokens": dropped_empty,
        "dropped_word_count_outside_bounds": dropped_length,
        "dropped_normalized_conv_collision": dropped_conv,
        "dropped_normalized_leipzig_collision": dropped_leipzig,
        "dropped_normalized_duplicate": dropped_duplicate,
        "candidates": len(candidates),
        "sample_seed": SAMPLE_SEED,
        "limit": limit,
        "min_words": min_words,
        "max_words": max_words,
        "selected": len(lines),
    }
    return EvalBuild(lines=lines, training_normalized=training, stats=stats)


def render_bytes(build: EvalBuild) -> bytes:
    """Serialize header + eval lines as UTF-8 with LF endings."""
    text = "\n".join([*HEADER_LINES, *build.lines]) + "\n"
    return text.encode("utf-8")


def write_atomic(path: Path, data: bytes) -> None:
    """Write [data] to [path] atomically: temp file in the same directory, then rename."""
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temp_name = tempfile.mkstemp(
        dir=str(path.parent), prefix=path.name + ".", suffix=".tmp"
    )
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_name, path)
    except BaseException:
        try:
            os.unlink(temp_name)
        except OSError:
            pass
        raise


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--corpus-dir", type=Path, default=DEFAULT_CORPUS_DIR)
    parser.add_argument("--leipzig-dir", type=Path, default=DEFAULT_LEIPZIG_DIR)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    parser.add_argument("--min-words", type=int, default=DEFAULT_MIN_WORDS)
    parser.add_argument("--max-words", type=int, default=DEFAULT_MAX_WORDS)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        build = build_eval_set(
            args.corpus_dir,
            leipzig_dir=args.leipzig_dir,
            limit=args.limit,
            min_words=args.min_words,
            max_words=args.max_words,
        )
        data = render_bytes(build)
        write_atomic(args.out, data)
    except (EvalSetError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    report = {
        **build.stats,
        "output": str(args.out),
        "output_bytes": len(data),
        "output_sha256": hashlib.sha256(data).hexdigest(),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
