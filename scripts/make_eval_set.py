#!/usr/bin/env python3
"""Build the pinned Tatar suggestion-eval set (TT-SUGGESTIONS phase P0).

The eval set is a held-out slice of Tatoeba Tatar sentences: sentences that did NOT go
into the conversational training mix ``tt_conv_train90`` the shipped Tatar bigram table
was trained on (docs/CORPUS-CONVERSATIONAL-TT.md).

Provenance chain, reproduced here exactly so membership is not a guess:

1. ``research/corpus/make_conv_train.py`` converts Tatoeba tt + OpenSubtitles tt into the
   Leipzig ``id<TAB>sentence`` shape, deduplicating by stripped line content (Tatoeba
   first). The documented output is 218 552 rows, 12 619 164 bytes, SHA-256
   ``8420ec0a…82e2``; this script re-runs the converter into a build directory and
   VERIFIES that digest -- a mismatch means the reconstruction diverged from the
   documented recipe, which is a hard error (fail-closed), never a fallback.
2. The documented split keeps rows with ``id % 10 != 1`` for training (``train90``) and
   holds out ``id % 10 == 1``. Every unique Tatoeba line appears exactly once in the
   converted stream, so a Tatoeba line is in train90 iff its row id is not 1 mod 10.
3. Eval candidates are the Tatoeba-origin rows (the first ``tatoeba_unique_kept`` rows of
   the stream -- Tatoeba is converted first) with ``id % 10 == 1``.

Each candidate is then normalized exactly like the dictionary pipeline normalizes corpus
tokens: surrounding punctuation is stripped (the ``dict_tokens`` rule of
research/corpus/corpuslib.py, which mirrors Leipzig word-list semantics) and every token
goes through ``dictionary_coverage.normalize_word`` (NFC, lowercase, Tatar alphabet
filter). Tokens that fail normalization are DROPPED -- they can never be dictionary
entries, so they are not what the eval measures; the surviving tokens joined by single
spaces are the eval sentence. Sentences with fewer than 3 or more than 12 surviving
tokens are dropped, normalized duplicates collapse (first occurrence wins), and a
sentence whose normalized form also appears in normalized train90 is excluded
(conservative: casing/punctuation variants of a trained sentence are still training
data).

Selection is a deterministic sample with a pinned seed: candidates are ordered by
SHA-256 of ``"<seed>\\t<line>"`` and the first ``--limit`` (default 1000) are taken, then
sorted by Unicode code point for a stable, diff-friendly file. Hash-ordered selection is
used instead of ``random.Random(seed).sample`` because the latter's algorithm is not
guaranteed stable across Python versions; this build must be byte-identical everywhere.

Output: UTF-8, LF line endings, ``#`` comment header (attribution + provenance), one
normalized sentence per line. Written atomically (temp file + rename in the target
directory). No wall-clock field appears anywhere in the output, so a rebuild over the
same inputs reproduces the committed file byte for byte.

License: Tatoeba sentences are CC BY 2.0 FR (attribution required -- the header below IS
the attribution, and app/src/main/assets/dictionaries/NOTICE.txt already covers
Tatoeba). Only Tatoeba lines are written; no OpenSubtitles sentence is reproduced.

Usage:

    python3 scripts/make_eval_set.py [--corpus-dir research/corpus]
        [--workdir build/tt-suggestions]
        [--out app/src/test/resources/tt_eval_sentences.txt]
        [--limit 1000] [--min-words 3] [--max-words 12]

Prints a JSON stats report to stdout. stdlib only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

import dictionary_coverage as coverage  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CORPUS_DIR = ROOT / "research" / "corpus"
DEFAULT_WORKDIR = ROOT / "build" / "tt-suggestions"
DEFAULT_OUT = ROOT / "app" / "src" / "test" / "resources" / "tt_eval_sentences.txt"

TATOEBA_FILE = "Tatoeba-v2026-07-08.tt.txt.gz"
OPENSUBTITLES_FILE = "OpenSubtitles-v2024.tt.txt.gz"
CONVERTER = "make_conv_train.py"

# The documented digest of the converted stream (docs/CORPUS-CONVERSATIONAL-TT.md,
# "Входы": 218 552 rows, 12 619 164 bytes). Verified on every build; a mismatch means
# the local corpus files or the converter no longer reproduce the documented recipe.
CONV_SENTENCES_SHA256 = (
    "8420ec0a3c7f329094989ece7cee396ee0dfe987e5e131f91424d7e2e5826b49"
)
CONV_SENTENCES_BYTES = 12_619_164
CONV_SENTENCES_LINES = 218_552

# Pinned sampling seed: the P0 execution date (2026-09-19). It never changes; changing
# it is a new eval set, not a rebuild.
SAMPLE_SEED = 20260919

DEFAULT_LIMIT = 1_000
DEFAULT_MIN_WORDS = 3
DEFAULT_MAX_WORDS = 12

# Characters that may legitimately hug a word inside a Tatoeba sentence -- the exact
# rule of research/corpus/corpuslib.py (``_EDGE``), kept in sync by value, not by
# import, so scripts/ stays self-contained.
EDGE_CHARS = "\"'«»„“”‘’()[]{}<>.,!?;:…—–-*_/\\|~`^&#№%+=@$"

HEADER_LINES = (
    "# Tatar evaluation sentences for the suggestion engine (TT-SUGGESTIONS, phase P0).",
    "#",
    "# Source: Tatoeba Tatar sentences, dump Tatoeba-v2026-07-08 (https://tatoeba.org/).",
    "# License: Creative Commons Attribution 2.0 France (CC BY 2.0 FR),",
    "# https://creativecommons.org/licenses/by/2.0/fr/ -- sentences are contributed by",
    "# Tatoeba's members; the license permits use, modification and redistribution on the",
    "# single condition that the source is cited. This header is that citation; see also",
    "# app/src/main/assets/dictionaries/NOTICE.txt (Tatoeba section).",
    "#",
    "# Selection: Tatoeba lines NOT present in the tt_conv_train90 training mix of the",
    "# shipped Tatar bigram table (docs/CORPUS-CONVERSATIONAL-TT.md). The mix is",
    "# reconstructed with research/corpus/make_conv_train.py and verified against the",
    "# documented SHA-256 of the converted stream; the held-out slice is rows with",
    "# id % 10 == 1. Tokens are normalized to NFC lowercase Tatar-alphabet words",
    "# (scripts/dictionary_coverage.py normalize_word; hugging punctuation stripped per",
    "# the dict_tokens rule), sentences kept at 3..12 surviving words, deduplicated,",
    "# excluding any sentence whose normalized form appears in normalized train90.",
    "# Sample: deterministic, hash-ordered by SHA-256 of seed 20260919 + line, at most",
    "# 1000 lines, sorted by code point. Lines starting with '#' are comments.",
    "#",
    "# Generator: scripts/make_eval_set.py -- rebuilds byte-identically over the same",
    "# corpus inputs; no wall-clock fields. Do not edit by hand.",
)


class EvalSetError(ValueError):
    """A fail-closed eval-set build error (bad inputs or a diverged reconstruction)."""


@dataclass(frozen=True)
class ConvRecipe:
    """The reconstructed conversational split: rows of the converted stream."""

    rows: tuple[tuple[int, str], ...]  # (id, content) in file order, ids are 1..N
    tatoeba_rows: int  # unique Tatoeba lines kept = rows 1..tatoeba_rows (Tatoeba first)
    output_sha256: str

    @property
    def train90_contents(self) -> frozenset[str]:
        """Raw stripped contents of every training row (``id % 10 != 1``)."""
        return frozenset(content for row_id, content in self.rows if row_id % 10 != 1)

    def tatoeba_heldout(self) -> list[str]:
        """Tatoeba-origin contents held out of training, in Tatoeba file order."""
        return [
            content
            for row_id, content in self.rows
            if row_id <= self.tatoeba_rows and row_id % 10 == 1
        ]


@dataclass(frozen=True)
class EvalBuild:
    """The built eval set plus everything a test needs to check its provenance."""

    lines: tuple[str, ...]
    train90_normalized: frozenset[str]
    stats: dict[str, object] = field(default_factory=dict)


def normalize_sentence(line: str) -> list[str]:
    """Tokenize a raw corpus line into normalized words (dict_tokens semantics).

    Surrounding punctuation is stripped from each whitespace token, then
    ``normalize_word`` (NFC, lowercase, Tatar alphabet filter) decides; tokens it
    rejects are dropped. Returns the surviving words, possibly an empty list.
    """
    words: list[str] = []
    for chunk in line.split():
        word = chunk.strip(EDGE_CHARS)
        if not word:
            continue
        normalized, _reason = coverage.normalize_word(word)
        if normalized is not None:
            words.append(normalized)
    return words


def run_conv_recipe(corpus_dir: Path, workdir: Path) -> ConvRecipe:
    """Re-run the documented train90 recipe and verify it byte-for-byte.

    Raises ``EvalSetError`` when the corpus inputs or the converter are missing, or when
    the converted stream does not match the digest recorded in
    docs/CORPUS-CONVERSATIONAL-TT.md (the reconstruction is then NOT the documented one,
    and guessing at membership is not an option).
    """
    converter = corpus_dir / CONVERTER
    tatoeba = corpus_dir / TATOEBA_FILE
    opensubtitles = corpus_dir / OPENSUBTITLES_FILE
    for path in (converter, tatoeba, opensubtitles):
        if not path.is_file():
            raise EvalSetError(f"required corpus input is missing: {path}")

    workdir.mkdir(parents=True, exist_ok=True)
    out_path = workdir / "tt_conv-sentences.txt"
    result = subprocess.run(
        [sys.executable, str(converter), str(out_path), str(tatoeba), str(opensubtitles)],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise EvalSetError(
            f"make_conv_train.py failed with exit {result.returncode}: {result.stderr}"
        )
    report = json.loads(result.stdout)

    if (
        report["output_sha256"] != CONV_SENTENCES_SHA256
        or report["output_bytes"] != CONV_SENTENCES_BYTES
        or report["output_lines"] != CONV_SENTENCES_LINES
    ):
        raise EvalSetError(
            "the reconstructed conversational stream does not match the documented one "
            f"(docs/CORPUS-CONVERSATIONAL-TT.md): got {report['output_lines']} rows, "
            f"{report['output_bytes']} bytes, SHA-256 {report['output_sha256']}; "
            f"expected {CONV_SENTENCES_LINES} rows, {CONV_SENTENCES_BYTES} bytes, "
            f"SHA-256 {CONV_SENTENCES_SHA256}"
        )

    rows: list[tuple[int, str]] = []
    with out_path.open("r", encoding="utf-8", newline="") as stream:
        for line in stream:
            row_id_text, content = line.rstrip("\n").split("\t", 1)
            rows.append((int(row_id_text), content))
    if len(rows) != report["output_lines"]:
        raise EvalSetError("converted stream row count disagrees with its own report")

    tatoeba_rows = next(
        item["unique_kept"] for item in report["inputs"] if item["file"] == TATOEBA_FILE
    )
    return ConvRecipe(
        rows=tuple(rows),
        tatoeba_rows=tatoeba_rows,
        output_sha256=report["output_sha256"],
    )


def build_eval_set(
    corpus_dir: Path,
    workdir: Path,
    limit: int = DEFAULT_LIMIT,
    min_words: int = DEFAULT_MIN_WORDS,
    max_words: int = DEFAULT_MAX_WORDS,
) -> EvalBuild:
    """Build the eval set deterministically; returns lines and provenance data."""
    if limit <= 0:
        raise EvalSetError("limit must be positive")
    if not 0 < min_words <= max_words:
        raise EvalSetError("word bounds must satisfy 0 < min <= max")

    recipe = run_conv_recipe(corpus_dir, workdir)

    train90_normalized: set[str] = set()
    for content in recipe.train90_contents:
        words = normalize_sentence(content)
        if words:
            train90_normalized.add(" ".join(words))

    seen: set[str] = set()
    candidates: list[str] = []
    dropped_length = 0
    dropped_empty = 0
    dropped_collision = 0
    dropped_duplicate = 0
    for content in recipe.tatoeba_heldout():
        words = normalize_sentence(content)
        if not words:
            dropped_empty += 1
            continue
        if not min_words <= len(words) <= max_words:
            dropped_length += 1
            continue
        normalized = " ".join(words)
        if normalized in train90_normalized:
            dropped_collision += 1
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
        "conv_output_sha256": recipe.output_sha256,
        "conv_rows": len(recipe.rows),
        "tatoeba_unique_rows": recipe.tatoeba_rows,
        "tatoeba_heldout_rows": len(recipe.tatoeba_heldout()),
        "train90_normalized_sentences": len(train90_normalized),
        "dropped_no_surviving_tokens": dropped_empty,
        "dropped_word_count_outside_bounds": dropped_length,
        "dropped_normalized_train90_collision": dropped_collision,
        "dropped_normalized_duplicate": dropped_duplicate,
        "candidates": len(candidates),
        "sample_seed": SAMPLE_SEED,
        "limit": limit,
        "min_words": min_words,
        "max_words": max_words,
        "selected": len(lines),
    }
    return EvalBuild(
        lines=lines, train90_normalized=frozenset(train90_normalized), stats=stats
    )


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
    parser.add_argument("--workdir", type=Path, default=DEFAULT_WORKDIR)
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
            args.workdir,
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
