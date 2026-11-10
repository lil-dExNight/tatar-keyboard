#!/usr/bin/env python3
"""Build the Russian suggestion eval set from fully held-out Tatoeba sentences.

The conversational stream is reconstructed from the pinned Tatoeba and OpenSubtitles dumps
with ``research/corpus/make_conv_train.py``. Candidates are Tatoeba-origin rows in the
``id % 10 == 0`` slice, which the conversational-frequency and acceptance measurements hold
out. Normalized candidates are removed when they occur in conversational frequency or
development training (``id % 10 != 0``) or in any of the six current Russian bigram inputs,
including the 2023 and 2024 news corpora. All corpus bytes are pin-checked. Output is
``app/src/test/resources/ru_eval_sentences.txt`` plus a JSON provenance report on stdout.
Only Tatoeba lines (CC BY 2.0 FR) are written. Errors exit nonzero without writing the eval
set; rebuilding the same pinned inputs is byte-identical.
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
from typing import Iterable, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

import dictionary_coverage as coverage  # noqa: E402
import rebuild_assets  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CORPUS_DIR = ROOT / "research" / "corpus"
DEFAULT_LEIPZIG_DIR = Path.home() / "corpora-leipzig"
DEFAULT_WORKDIR = ROOT / "build" / "ru-suggestions"
DEFAULT_OUT = ROOT / "app" / "src" / "test" / "resources" / "ru_eval_sentences.txt"
CORPUS_MANIFEST = ROOT / "data" / "corpus-manifest.json"
CONV_FREQUENCY = ROOT / "data" / "dictionary" / "dict-accept" / "conv-freq-ru.tsv"

TATOEBA_FILE = "Tatoeba-v2026-07-08.ru.txt.gz"
TATOEBA_SHA256 = "e49b94d540df0b27f0309ea49e25093398c5a4f4e7052945f19de08120f9e499"
TATOEBA_BYTES = 19_194_956
OPENSUBTITLES_FILE = "OpenSubtitles-v2024.ru.txt.gz"
OPENSUBTITLES_SHA256 = "7b403e71d615a248a6871f5836533926cc3988c6bfc2bc99f9e3324a179889a0"
OPENSUBTITLES_BYTES = 1_518_001_327
CONVERTER = "make_conv_train.py"
CONV_ALL_FILE = "rus_conv_all-sentences.txt"
CONV_ALL_SHA256 = "ff8524a8cde9162aa54ba0aa08100d62eb7c690ab66c4af57140f54aa7db60a6"
CONV_ALL_BYTES = 4_587_788_864
CONV_ALL_LINES = 60_036_548
CONV_TATOEBA_ROWS = 1_231_094

# The asset pipeline is the source of truth for bigram training inputs. Keeping a second list
# here previously left the 2023 and 2024 news corpora in the eval set.
BIGRAM_SENTENCE_FILES = tuple(
    bigram.train for bigram in rebuild_assets.BIGRAMS if bigram.tag == "rus"
)[0]
CONV_SENTENCES_FILE = "rus_conv_thinned60-sentences.txt"
CONV_FREQUENCY_LABEL = "conv_frequency_train"
TRAINING_INPUT_LABELS = (CONV_FREQUENCY_LABEL, *BIGRAM_SENTENCE_FILES)

CONV_HELD_MODULUS = 10
CONV_HELD_RESIDUE = 0
CONV_THIN_MODULUS = 60

# Pinned sampling seed, an arbitrary value. Changing it produces a new eval set, not a rebuild.
SAMPLE_SEED = 20261003

DEFAULT_LIMIT = 1_000
DEFAULT_MIN_WORDS = 3
DEFAULT_MAX_WORDS = 12

# Characters that may hug a word inside a corpus sentence: the rule of
# research/corpus/corpuslib.py (``_EDGE``), copied by value so tokenization stays shared.
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
    "# Selection: Tatoeba-origin rows from the deduplicated Tatoeba + OpenSubtitles stream",
    "# whose id % 10 == 0, the slice held out of conversational-frequency and acceptance",
    "# training. Normalized collisions with conversational frequency and development training",
    "# rows (id % 10 != 0) are excluded as well as exact row reuse.",
    "# Normalized sentences from every current Russian bigram input are excluded too:",
    "# rus_conv_thinned60 and Leipzig rus_news_2019_1M, rus_news_2022_1M,",
    "# rus_news_2023_1M, rus_news_2024_1M and rus_wikipedia_2021_1M. The input list comes",
    "# from rebuild_assets.py and every file is checked against data/corpus-manifest.json.",
    "# Tokens are NFC lowercase Russian-alphabet words with hugging punctuation stripped",
    "# per the dict_tokens rule; sentences have 3..12 surviving words and are deduplicated.",
    "# Sample: deterministic, hash-ordered by SHA-256 of seed 20261003 + line, at most",
    "# 1000 lines, sorted by code point. Lines starting with '#' are comments.",
    "#",
    "# Generator: scripts/make_ru_eval_set.py -- rebuilds byte-identically over the same",
    "# corpus inputs; no wall-clock fields. Do not edit by hand.",
)


class EvalSetError(ValueError):
    """An eval-set build error (bad or missing inputs); nothing is written."""


@dataclass(frozen=True)
class ConvRecipe:
    """A verified full conversational stream and its fixed split metadata."""

    path: Path
    rows: int
    tatoeba_rows: int
    output_sha256: str
    output_bytes: int


@dataclass(frozen=True)
class EvalBuild:
    """The built eval set plus measured overlap with each excluded input."""

    lines: tuple[str, ...]
    remaining_overlap: dict[str, frozenset[str]]
    stats: dict[str, object] = field(default_factory=dict)


def normalize_sentence(line: str) -> list[str]:
    """Tokenize a raw corpus line into normalized words (dict_tokens semantics)."""
    words: list[str] = []
    for chunk in line.split():
        word = chunk.strip(EDGE_CHARS)
        if not word:
            continue
        normalized, _reason = coverage.normalize_word(word, coverage.RUSSIAN.alphabet)
        if normalized is not None:
            words.append(normalized)
    return words


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(8 * 1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def verify_pin(path: Path, size: int, sha256: str, description: str) -> None:
    if not path.is_file():
        raise EvalSetError(f"{description} is missing: {path}")
    if path.stat().st_size != size or sha256_file(path) != sha256:
        raise EvalSetError(f"{description} does not match its pin: {path}")


def load_manifest(manifest_path: Path) -> dict[str, tuple[int, str]]:
    if not manifest_path.is_file():
        raise EvalSetError(f"corpus manifest is missing: {manifest_path}")
    try:
        raw = json.loads(manifest_path.read_text(encoding="utf-8"))
        return {
            name: (int(pin["size"]), str(pin["sha256"]))
            for name, pin in raw.items()
        }
    except (json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
        raise EvalSetError(f"corpus manifest does not parse: {error}") from error


def corpus_path(root: Path, name: str) -> Path:
    """Accept both the rebuild's flat layout and Leipzig's release-directory layout."""
    direct = root / name
    nested = root / name.removesuffix("-sentences.txt") / name
    if direct.is_file() or not nested.is_file():
        return direct
    return nested


def verify_conv_frequency_sources(path: Path) -> None:
    """Ensure the committed tuning table comes from the two pinned raw inputs."""
    if not path.is_file():
        raise EvalSetError(f"conversational-frequency input is missing: {path}")
    expected = f"# Source: {TATOEBA_FILE} {OPENSUBTITLES_FILE}"
    sources = [
        line for line in path.read_text(encoding="utf-8").splitlines()
        if line.startswith("# Source: ")
    ]
    if sources != [expected]:
        raise EvalSetError(
            f"conversational-frequency sources diverged: {sources!r}; expected {expected!r}"
        )


def run_conv_recipe(corpus_dir: Path, workdir: Path) -> ConvRecipe:
    """Return the pinned full stream, reconstructing it with the repository converter."""
    verify_conv_frequency_sources(CONV_FREQUENCY)
    tatoeba = corpus_dir / TATOEBA_FILE
    opensubtitles = corpus_dir / OPENSUBTITLES_FILE
    converter = corpus_dir / CONVERTER
    verify_pin(tatoeba, TATOEBA_BYTES, TATOEBA_SHA256, "Tatoeba input")
    verify_pin(
        opensubtitles,
        OPENSUBTITLES_BYTES,
        OPENSUBTITLES_SHA256,
        "OpenSubtitles input",
    )
    if not converter.is_file():
        raise EvalSetError(f"required corpus converter is missing: {converter}")

    workdir.mkdir(parents=True, exist_ok=True)
    out_path = workdir / CONV_ALL_FILE
    if not out_path.exists():
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
        try:
            report = json.loads(result.stdout)
        except json.JSONDecodeError as error:
            raise EvalSetError(f"make_conv_train.py report does not parse: {error}") from error
        if (
            report.get("output_lines") != CONV_ALL_LINES
            or report.get("output_bytes") != CONV_ALL_BYTES
            or report.get("output_sha256") != CONV_ALL_SHA256
            or report.get("inputs", [{}])[0].get("unique_kept") != CONV_TATOEBA_ROWS
        ):
            raise EvalSetError(f"reconstructed conversational stream diverged: {result.stdout}")

    verify_pin(out_path, CONV_ALL_BYTES, CONV_ALL_SHA256, "full conversational stream")
    return ConvRecipe(
        path=out_path,
        rows=CONV_ALL_LINES,
        tatoeba_rows=CONV_TATOEBA_ROWS,
        output_sha256=CONV_ALL_SHA256,
        output_bytes=CONV_ALL_BYTES,
    )


def sentence_rows(path: Path) -> Iterable[tuple[int, str]]:
    with path.open("r", encoding="utf-8", newline="") as stream:
        for line_number, raw in enumerate(stream, 1):
            row_id, tab, content = raw.rstrip("\r\n").partition("\t")
            if not tab:
                raise EvalSetError(f"{path}:{line_number}: expected id<TAB>sentence")
            try:
                parsed_id = int(row_id)
            except ValueError as error:
                raise EvalSetError(
                    f"{path}:{line_number}: sentence id is not an integer"
                ) from error
            yield parsed_id, content


def verify_conv_thin_slice(recipe: ConvRecipe, size: int, sha256: str) -> int:
    """Prove that the full stream reproduces the pinned bigram-training slice."""
    digest = hashlib.sha256()
    thin_size = 0
    thin_rows = 0
    with recipe.path.open("rb") as stream:
        for raw in stream:
            row_id, tab, _content = raw.partition(b"\t")
            if not tab:
                raise EvalSetError(f"{recipe.path}: expected id<TAB>sentence")
            try:
                parsed_id = int(row_id)
            except ValueError as error:
                raise EvalSetError(f"{recipe.path}: sentence id is not an integer") from error
            if parsed_id % CONV_THIN_MODULUS == 0:
                digest.update(raw)
                thin_size += len(raw)
                thin_rows += 1
    if thin_size != size or digest.hexdigest() != sha256:
        raise EvalSetError(
            "full conversational stream does not reproduce the pinned thinned bigram input"
        )
    return thin_rows


def candidate_pool(
    recipe: ConvRecipe, min_words: int, max_words: int
) -> tuple[dict[str, int], dict[str, int]]:
    """Build normalized Tatoeba candidates from the frequency-held-out residue."""
    candidates: dict[str, int] = {}
    stats = {
        "tatoeba_heldout_rows": 0,
        "dropped_no_surviving_tokens": 0,
        "dropped_word_count_outside_bounds": 0,
        "dropped_normalized_duplicate": 0,
    }
    for row_id, content in sentence_rows(recipe.path):
        if row_id > recipe.tatoeba_rows:
            break
        if row_id % CONV_HELD_MODULUS != CONV_HELD_RESIDUE:
            continue
        stats["tatoeba_heldout_rows"] += 1
        words = normalize_sentence(content)
        if not words:
            stats["dropped_no_surviving_tokens"] += 1
            continue
        if not min_words <= len(words) <= max_words:
            stats["dropped_word_count_outside_bounds"] += 1
            continue
        normalized = " ".join(words)
        if normalized in candidates:
            stats["dropped_normalized_duplicate"] += 1
            continue
        candidates[normalized] = 0
    return candidates, stats


def mark_conversational_training(
    candidates: dict[str, int], recipe: ConvRecipe, bit: int
) -> None:
    """Mark candidates occurring in frequency training or any conversational tuning slice."""
    for row_id, content in sentence_rows(recipe.path):
        if row_id % CONV_HELD_MODULUS == CONV_HELD_RESIDUE:
            continue
        words = normalize_sentence(content)
        if words:
            normalized = " ".join(words)
            if normalized in candidates:
                candidates[normalized] |= bit


def mark_sentence_file(candidates: dict[str, int], path: Path, bit: int) -> None:
    for _row_id, content in sentence_rows(path):
        words = normalize_sentence(content)
        if words:
            normalized = " ".join(words)
            if normalized in candidates:
                candidates[normalized] |= bit


def sample_lines(candidates: Iterable[str], limit: int) -> tuple[str, ...]:
    eligible = list(candidates)
    if len(eligible) > limit:
        eligible = sorted(
            eligible,
            key=lambda line: hashlib.sha256(
                f"{SAMPLE_SEED}\t{line}".encode("utf-8")
            ).digest(),
        )[:limit]
    return tuple(sorted(eligible))


def build_from_conv_stream(
    recipe: ConvRecipe,
    training_paths: Sequence[tuple[str, Path]],
    limit: int = DEFAULT_LIMIT,
    min_words: int = DEFAULT_MIN_WORDS,
    max_words: int = DEFAULT_MAX_WORDS,
) -> EvalBuild:
    """Measure every exclusion source and sample only candidates with no observed overlap."""
    if limit <= 0:
        raise EvalSetError("limit must be positive")
    if not 0 < min_words <= max_words:
        raise EvalSetError("word bounds must satisfy 0 < min <= max")
    labels = (CONV_FREQUENCY_LABEL, *(name for name, _path in training_paths))
    if len(set(labels)) != len(labels) or len(labels) > 64:
        raise EvalSetError("training input labels must be unique and fit in 64 bits")

    candidates, stats = candidate_pool(recipe, min_words, max_words)
    initial_candidates = len(candidates)
    mark_conversational_training(candidates, recipe, 1)
    for index, (_name, path) in enumerate(training_paths, 1):
        mark_sentence_file(candidates, path, 1 << index)

    eligible = [line for line, overlap in candidates.items() if overlap == 0]
    if len(eligible) < limit:
        raise EvalSetError(
            f"independent candidate count {len(eligible)} is below required limit {limit}"
        )
    lines = sample_lines(eligible, limit)
    remaining = {
        label: frozenset(
            line for line in lines if candidates[line] & (1 << index)
        )
        for index, label in enumerate(labels)
    }
    stats.update({
        "training_inputs": list(labels),
        "initial_candidates": initial_candidates,
        "dropped_normalized_collision": {
            label: sum(1 for overlap in candidates.values() if overlap & (1 << index))
            for index, label in enumerate(labels)
        },
        "candidates": len(eligible),
        "sample_seed": SAMPLE_SEED,
        "limit": limit,
        "min_words": min_words,
        "max_words": max_words,
        "selected": len(lines),
        "remaining_overlap": {name: len(overlap) for name, overlap in remaining.items()},
    })
    return EvalBuild(lines=lines, remaining_overlap=remaining, stats=stats)


def build_eval_set(
    corpus_dir: Path = DEFAULT_CORPUS_DIR,
    leipzig_dir: Path = DEFAULT_LEIPZIG_DIR,
    limit: int = DEFAULT_LIMIT,
    min_words: int = DEFAULT_MIN_WORDS,
    max_words: int = DEFAULT_MAX_WORDS,
    workdir: Path = DEFAULT_WORKDIR,
) -> EvalBuild:
    """Rebuild and pin-check the full stream and every current bigram training input."""
    recipe = run_conv_recipe(corpus_dir, workdir)
    manifest = load_manifest(CORPUS_MANIFEST)
    training_paths: list[tuple[str, Path]] = []
    for name in BIGRAM_SENTENCE_FILES:
        if name not in manifest:
            raise EvalSetError(f"corpus manifest has no pin for {name}")
        path = corpus_path(leipzig_dir, name)
        size, sha256 = manifest[name]
        verify_pin(path, size, sha256, "training corpus input")
        training_paths.append((name, path))

    thin_size, thin_sha256 = manifest[CONV_SENTENCES_FILE]
    thin_rows = verify_conv_thin_slice(recipe, thin_size, thin_sha256)

    build = build_from_conv_stream(
        recipe,
        training_paths,
        limit=limit,
        min_words=min_words,
        max_words=max_words,
    )
    build.stats.update({
        "conv_output_sha256": recipe.output_sha256,
        "conv_output_bytes": recipe.output_bytes,
        "conv_rows": recipe.rows,
        "conv_thin_rows_verified": thin_rows,
        "conv_thin_sha256": thin_sha256,
        "tatoeba_unique_rows": recipe.tatoeba_rows,
        "conv_frequency_heldout_rule": (
            f"id % {CONV_HELD_MODULUS} == {CONV_HELD_RESIDUE}"
        ),
    })
    return build


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
            corpus_dir=args.corpus_dir,
            leipzig_dir=args.leipzig_dir,
            workdir=args.workdir,
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
