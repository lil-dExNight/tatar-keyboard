#!/usr/bin/env python3
"""Corpus-statistics metrics of Tatar suggestions over the eval set ``tt_eval_sentences.txt``.

Input: the eval set, the bundled Tatar dictionary and the schema-3 bigram table (decoded with
the pipeline readers; the table is checked against the dictionary's raw SHA-256).
Output: ``EVAL|metric|value`` lines: eval sizes, dictionary coverage of tokens and types, a
lower-bound share of inflected tokens, bigram head coverage and next-word top-3 hit rate
(over all pairs and over head-covered pairs). ``TtSuggestEvalTest`` measures the same on the
JVM. Exit 2 on any missing or invalid input.
"""
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

import bigram_asset_pack  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EVAL = ROOT / "app" / "src" / "test" / "resources" / "tt_eval_sentences.txt"
DEFAULT_DICT_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "dictionaries" / "tatar_top100k_v1.tdict.zlib"
)
DEFAULT_BIGRAM_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "bigrams" / "tatar_bigrams_v1.tatbigr.zlib"
)

SHOWN_RESULTS = 3

# Tatar inflectional surface suffixes: plural, six cases, possessives, post-3sg-possessive
# case forms, the main verb forms, and the frequent derivational set. Multi-letter only:
# one-letter endings (-а/-ә present, -р future, -у verbal noun) are too ambiguous, so the
# share is a lower bound for before/after comparison. Matching is longest-first and
# requires a stem of >= 2 code points.
INFLECTIONAL_SUFFIXES = frozenset(
    [
        # plural
        "лар", "ләр", "нар", "нәр",
        # genitive, dative, accusative, locative, ablative
        "ның", "нең",
        "га", "гә", "ка", "кә",
        "ны", "не",
        "да", "дә", "та", "тә",
        "дан", "дән", "тан", "тән",
        # possessives
        "ым", "ем", "ың", "ең", "сы", "се",
        "ыбыз", "ебез", "ыгыз", "егез", "лары", "ләре",
        # case forms after the 3sg possessive
        "на", "нә", "нда", "нде", "ннан", "ннән",
        # past -DI, resultative -GAn, future -Ir, future -AčAk, conditional -sA
        "ды", "де", "ты", "те",
        "ган", "гән", "кан", "кән",
        "ыр", "ер",
        "аҗак", "әҗәк",
        "са", "сә",
        # gerunds and negated forms
        "ып", "еп", "гач", "гәч", "ганча", "гәнчә", "мыйча", "мәйчә",
        # participle -UčI, intention -mAkčI, present negation -mAy
        "учы", "үче", "макчы", "мәкче", "мый", "мәй",
        # frequent derivational suffixes (-čA, -lIk, -lI, -sIz, -čI, -dAş, -rAk)
        "ча", "чә", "лык", "лек", "лы", "ле", "сыз", "сез",
        "чы", "че", "даш", "дәш", "рак", "рәк",
    ]
)
SUFFIXES_BY_LENGTH = sorted(INFLECTIONAL_SUFFIXES, key=len, reverse=True)
MIN_STEM_CODE_POINTS = 2


class SuggestEvalError(ValueError):
    """An eval error: missing or invalid input (exit 2)."""


def load_eval_lines(path: Path) -> list[str]:
    """Read the eval set, skipping ``#`` comment lines; raises on bad content."""
    if not path.is_file():
        raise SuggestEvalError(f"eval set is missing: {path}")
    lines: list[str] = []
    for raw in path.read_text(encoding="utf-8").split("\n"):
        if not raw or raw.startswith("#"):
            continue
        words = raw.split(" ")
        for word in words:
            normalized, reason = coverage.normalize_word(word)
            if reason is not None or normalized != word:
                raise SuggestEvalError(f"eval line carries a non-canonical word: {word!r}")
        lines.append(raw)
    if not lines:
        raise SuggestEvalError(f"eval set has no data lines: {path}")
    return lines


def load_dictionary_words(asset_path: Path) -> tuple[list[str], bytes]:
    """Decode the bundled dictionary in memory; returns (words, raw bytes)."""
    if not asset_path.is_file():
        raise SuggestEvalError(f"dictionary asset is missing: {asset_path}")
    raw = dictionary_pack.decompress_asset(asset_path.read_bytes())
    parsed = dictionary_pack.validate_raw(raw)
    return list(parsed.words), raw


def load_bigram_successes(
    asset_path: Path, dictionary_words: list[str], dictionary_raw: bytes
) -> dict[str, list[str]]:
    """Decode the bundled schema-3 bigram table against its linked dictionary."""
    if not asset_path.is_file():
        raise SuggestEvalError(f"bigram asset is missing: {asset_path}")
    raw = bigram_asset_pack.decompress(asset_path.read_bytes())
    parsed = bigram_asset_pack.validate_raw_v3(
        raw, dictionary_words, hashlib.sha256(dictionary_raw).digest()
    )
    return parsed.successes_by_head


def has_inflectional_suffix(word: str) -> bool:
    """Heuristic: [word] ends in a known inflectional suffix, stem >= 2 code points."""
    for suffix in SUFFIXES_BY_LENGTH:
        if len(word) - len(suffix) >= MIN_STEM_CODE_POINTS and word.endswith(suffix):
            return True
    return False


def percent(part: int, whole: int) -> float:
    return part * 100.0 / whole if whole else 0.0


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--eval", dest="eval_path", type=Path, default=DEFAULT_EVAL)
    parser.add_argument("--dict-asset", type=Path, default=DEFAULT_DICT_ASSET)
    parser.add_argument("--bigram-asset", type=Path, default=DEFAULT_BIGRAM_ASSET)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        lines = load_eval_lines(args.eval_path)
        dictionary_words, dictionary_raw = load_dictionary_words(args.dict_asset)
        successes_by_head = load_bigram_successes(
            args.bigram_asset, dictionary_words, dictionary_raw
        )
    except (
        SuggestEvalError,
        dictionary_pack.DictionaryPackError,
        bigram_asset_pack.BigramFormatError,
        bigram_asset_pack.BigramBudgetError,
        OSError,
    ) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    vocabulary = frozenset(dictionary_words)
    tokens = [word for line in lines for word in line.split(" ")]
    unique_words = set(tokens)

    covered_tokens = sum(1 for word in tokens if word in vocabulary)
    covered_types = sum(1 for word in unique_words if word in vocabulary)
    inflected = sum(1 for word in tokens if has_inflectional_suffix(word))

    pairs: list[tuple[str, str]] = []
    for line in lines:
        words = line.split(" ")
        pairs.extend(zip(words, words[1:]))
    head_covered = 0
    top3_hits = 0
    for head, successor in pairs:
        shown = successes_by_head.get(head)
        if not shown:
            continue
        head_covered += 1
        if successor in shown[:SHOWN_RESULTS]:
            top3_hits += 1

    metrics: list[tuple[str, str]] = [
        ("eval_lines", str(len(lines))),
        ("eval_tokens", str(len(tokens))),
        ("eval_unique_words", str(len(unique_words))),
        ("dict_word_coverage_tokens_pct", f"{percent(covered_tokens, len(tokens)):.4f}"),
        ("dict_word_coverage_types_pct", f"{percent(covered_types, len(unique_words)):.4f}"),
        ("inflected_token_share_pct", f"{percent(inflected, len(tokens)):.4f}"),
        ("bigram_pairs_total", str(len(pairs))),
        ("bigram_head_coverage_pct", f"{percent(head_covered, len(pairs)):.4f}"),
        ("nextword_top3_hit_pct", f"{percent(top3_hits, len(pairs)):.4f}"),
        ("nextword_top3_hit_covered_pct", f"{percent(top3_hits, head_covered):.4f}"),
    ]
    for name, value in metrics:
        print(f"EVAL|{name}|{value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
