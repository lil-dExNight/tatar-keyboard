#!/usr/bin/env python3
"""Corpus-statistics metrics of suggestions over a pinned eval set (Tatar or Russian).

Input: the eval set, the bundled dictionary and the schema-3 bigram table (decoded with
the pipeline readers; the table is checked against the dictionary's raw SHA-256), by default
the Tatar ones (``tt_eval_sentences.txt``); ``--language rus`` switches the defaults to the
Russian assets and ``ru_eval_sentences.txt``.
Output: ``EVAL|metric|value`` lines: eval sizes, dictionary coverage of tokens and types,
bigram head coverage and plain-bigram next-word hit rates; then, through the production-chain
mirror of ``suggest_chain.py``: full-chain and completion top-1, visible top-3 and engine top-4
hit counts and rates, with a sentence-level bootstrap CI95 and minimum detectable effect, the share
of typing moments with an empty strip answer (prefix moments while a word is incomplete,
next-word moments over the adjacent pairs), and the keystroke-savings simulation with its
vocabulary-oracle bound. The Tatar mode additionally
reports a lower-bound share of inflected tokens and the lemma strata (seen form / new form
of a seen stem / unseen stem) with per-stratum cp3 completion and chain hit rates; both are
defined by the Tatar suffix tables and are absent from the Russian report. ``TtSuggestEvalTest``
measures the Tatar side on the JVM. Exit 2 on any missing or invalid input.
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
import suggest_chain  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EVAL = ROOT / "app" / "src" / "test" / "resources" / "tt_eval_sentences.txt"
DEFAULT_DICT_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "dictionaries" / "tatar_top100k_v1.tdict.zlib"
)
DEFAULT_BIGRAM_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "bigrams" / "tatar_bigrams_v1.tatbigr.zlib"
)
RU_EVAL = ROOT / "app" / "src" / "test" / "resources" / "ru_eval_sentences.txt"
RU_DICT_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "dictionaries" / "russian_top100k_v1.tdict.zlib"
)
RU_BIGRAM_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "bigrams" / "russian_bigrams_v1.tatbigr.zlib"
)

# The shipped strip width (SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT): the keystroke
# simulation measures what a default install can tap. The engines compose four candidates
# (suggest_chain.CELL_COUNT); the fourth-cell setting's arm is reported next to the default's.
SHOWN_RESULTS = 3

# The stratum completion metric's prefix length ("cp3"): fixed at three code points by the
# metric's definition, independent of the strip width.
STRATUM_PREFIX_CODE_POINTS = 3

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


def load_eval_lines(
    path: Path, alphabet: frozenset[str] = coverage.TATAR_ALPHABET
) -> list[str]:
    """Read the eval set, skipping ``#`` comment lines; raises on bad content."""
    if not path.is_file():
        raise SuggestEvalError(f"eval set is missing: {path}")
    lines: list[str] = []
    for raw in path.read_text(encoding="utf-8").split("\n"):
        if not raw or raw.startswith("#"):
            continue
        words = raw.split(" ")
        for word in words:
            normalized, reason = coverage.normalize_word(word, alphabet)
            if reason is not None or normalized != word:
                raise SuggestEvalError(f"eval line carries a non-canonical word: {word!r}")
        lines.append(raw)
    if not lines:
        raise SuggestEvalError(f"eval set has no data lines: {path}")
    return lines


def load_dictionary_words(asset_path: Path) -> tuple[list[str], list[int], bytes]:
    """Decode the bundled dictionary in memory; returns (words, frequencies, raw bytes)."""
    if not asset_path.is_file():
        raise SuggestEvalError(f"dictionary asset is missing: {asset_path}")
    raw = dictionary_pack.decompress_asset(asset_path.read_bytes())
    parsed = dictionary_pack.validate_raw(raw)
    return list(parsed.words), list(parsed.frequencies), raw


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
    parser.add_argument(
        "--language", choices=("tat", "rus"), default="tat",
        help="eval language: switches the default eval set and assets (default: %(default)s)",
    )
    parser.add_argument("--eval", dest="eval_path", type=Path, default=None)
    parser.add_argument("--dict-asset", type=Path, default=None)
    parser.add_argument("--bigram-asset", type=Path, default=None)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    russian = args.language == "rus"
    eval_path = args.eval_path or (RU_EVAL if russian else DEFAULT_EVAL)
    dict_asset = args.dict_asset or (RU_DICT_ASSET if russian else DEFAULT_DICT_ASSET)
    bigram_asset = args.bigram_asset or (
        RU_BIGRAM_ASSET if russian else DEFAULT_BIGRAM_ASSET
    )
    try:
        lines = load_eval_lines(
            eval_path,
            coverage.RUSSIAN.alphabet if russian else coverage.TATAR.alphabet,
        )
        dictionary_words, dictionary_frequencies, dictionary_raw = load_dictionary_words(
            dict_asset
        )
        successes_by_head = load_bigram_successes(
            bigram_asset, dictionary_words, dictionary_raw
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

    pairs: list[tuple[str, str]] = []
    for line in lines:
        words = line.split(" ")
        pairs.extend(zip(words, words[1:]))
    head_covered = 0
    top1_hits = 0
    visible_top3_hits = 0
    engine_top4_hits = 0
    for head, successor in pairs:
        shown = successes_by_head.get(head)
        if not shown:
            continue
        head_covered += 1
        if successor == shown[0]:
            top1_hits += 1
        if successor in shown[:SHOWN_RESULTS]:
            visible_top3_hits += 1
        if successor in shown[:suggest_chain.CELL_COUNT]:
            engine_top4_hits += 1

    metrics: list[tuple[str, str]] = [
        ("eval_lines", str(len(lines))),
        ("eval_tokens", str(len(tokens))),
        ("eval_unique_words", str(len(unique_words))),
        ("dict_word_coverage_tokens_pct", f"{percent(covered_tokens, len(tokens)):.4f}"),
        ("dict_word_coverage_types_pct", f"{percent(covered_types, len(unique_words)):.4f}"),
    ]
    if not russian:
        # The suffix heuristic is Tatar-only; a Russian number would be meaningless.
        inflected = sum(1 for word in tokens if has_inflectional_suffix(word))
        metrics.append(
            ("inflected_token_share_pct", f"{percent(inflected, len(tokens)):.4f}")
        )
    metrics.extend(
        [
            ("bigram_pairs_total", str(len(pairs))),
            ("bigram_head_coverage_pct", f"{percent(head_covered, len(pairs)):.4f}"),
            ("nextword_top1_hits", str(top1_hits)),
            ("nextword_top1_hit_pct", f"{percent(top1_hits, len(pairs)):.4f}"),
            ("nextword_visible_top3_hits", str(visible_top3_hits)),
            ("nextword_visible_top3_hit_pct", f"{percent(visible_top3_hits, len(pairs)):.4f}"),
            ("nextword_visible_top3_hit_covered_pct",
             f"{percent(visible_top3_hits, head_covered):.4f}"),
            ("nextword_engine_top4_hits", str(engine_top4_hits)),
            ("nextword_engine_top4_hit_pct", f"{percent(engine_top4_hits, len(pairs)):.4f}"),
            ("nextword_engine_top4_hit_covered_pct",
             f"{percent(engine_top4_hits, head_covered):.4f}"),
        ]
    )
    metrics.extend(_chain_metrics(lines, pairs, dictionary_words, dictionary_frequencies,
                                  successes_by_head, unique_words, not russian))
    for name, value in metrics:
        print(f"EVAL|{name}|{value}")
    return 0


def _chain_metrics(
    lines: list[str],
    pairs: list[tuple[str, str]],
    dictionary_words: list[str],
    dictionary_frequencies: list[int],
    successes_by_head: dict[str, list[str]],
    unique_words: set[str],
    suffix_rules: bool,
) -> list[tuple[str, str]]:
    """The chain-hit, bootstrap, MDE, completion, strip-empty, strata and keystroke lines."""
    chain = suggest_chain.ChainMirror(
        dictionary_words, dictionary_frequencies, successes_by_head,
        suffix_rules=suffix_rules,
    )

    chain_top1 = 0
    chain_visible_top3 = 0
    chain_engine_top4 = 0
    # Per-sentence counters: the bootstrap resamples whole sentences, pairs included.
    sentence_visible_hits: list[int] = []
    sentence_engine_hits: list[int] = []
    sentence_pairs: list[int] = []
    for line in lines:
        words = line.split(" ")
        visible_hits = 0
        engine_hits = 0
        for head, successor in zip(words, words[1:]):
            shown = chain.predict_top3(head)
            if shown and successor == shown[0]:
                chain_top1 += 1
            if successor in shown[:SHOWN_RESULTS]:
                chain_visible_top3 += 1
                visible_hits += 1
            if successor in shown[:suggest_chain.CELL_COUNT]:
                chain_engine_top4 += 1
                engine_hits += 1
        sentence_visible_hits.append(visible_hits)
        sentence_engine_hits.append(engine_hits)
        sentence_pairs.append(len(words) - 1)

    visible_ci = suggest_chain.bootstrap_rate_ci95(sentence_visible_hits, sentence_pairs)
    engine_ci = suggest_chain.bootstrap_rate_ci95(sentence_engine_hits, sentence_pairs)
    visible_mde = suggest_chain.minimum_detectable_effect_pp(
        chain_visible_top3 / len(pairs), len(pairs)
    )
    engine_mde = suggest_chain.minimum_detectable_effect_pp(
        chain_engine_top4 / len(pairs), len(pairs)
    )

    metrics: list[tuple[str, str]] = [
        ("nextword_chain_pairs", str(len(pairs))),
        ("nextword_chain_top1_hits", str(chain_top1)),
        ("nextword_chain_top1_pct", f"{percent(chain_top1, len(pairs)):.4f}"),
        ("nextword_chain_visible_top3_hits", str(chain_visible_top3)),
        ("nextword_chain_visible_top3_pct", f"{percent(chain_visible_top3, len(pairs)):.4f}"),
        ("nextword_chain_visible_top3_ci95_lo", f"{visible_ci[0]:.4f}"),
        ("nextword_chain_visible_top3_ci95_hi", f"{visible_ci[1]:.4f}"),
        ("nextword_chain_visible_top3_mde_pp", f"{visible_mde:.4f}"),
        ("nextword_chain_engine_top4_hits", str(chain_engine_top4)),
        ("nextword_chain_engine_top4_pct", f"{percent(chain_engine_top4, len(pairs)):.4f}"),
        ("nextword_chain_engine_top4_ci95_lo", f"{engine_ci[0]:.4f}"),
        ("nextword_chain_engine_top4_ci95_hi", f"{engine_ci[1]:.4f}"),
        ("nextword_chain_engine_top4_mde_pp", f"{engine_mde:.4f}"),
    ]
    metrics.extend(_completion_metrics(unique_words, chain))
    metrics.extend(_context_boost_metrics(lines, chain))
    metrics.extend(_strip_empty_metrics(lines, pairs, chain))
    if suffix_rules:
        # The strata are defined by the Tatar suffix table; Russian has no suffix rules.
        metrics.extend(_strata_metrics(lines, unique_words, chain))
    metrics.extend(_keystroke_metrics(lines, unique_words, chain))
    return metrics


def _context_boost_metrics(
    lines: list[str], chain: suggest_chain.ChainMirror
) -> list[tuple[str, str]]:
    """Measure top-1, visible top-3 and engine top-4 with and without completion context."""
    n_lines = len(lines)
    moments = [0] * n_lines
    base_top1 = [0] * n_lines
    boost_top1 = [0] * n_lines
    base_visible_top3 = [0] * n_lines
    boost_visible_top3 = [0] * n_lines
    base_engine_top4 = [0] * n_lines
    boost_engine_top4 = [0] * n_lines
    for line_index, line in enumerate(lines):
        words = line.split(" ")
        for position, word in enumerate(words):
            context = chain.packed_successors(words[position - 1]) if position > 0 else ()
            for length in range(2, len(word)):
                prefix = word[:length]
                moments[line_index] += 1
                base = chain.prefix_top3(prefix)
                boost = chain.prefix_top3(prefix, context)
                if base and base[0] == word:
                    base_top1[line_index] += 1
                if boost and boost[0] == word:
                    boost_top1[line_index] += 1
                if word in base[:SHOWN_RESULTS]:
                    base_visible_top3[line_index] += 1
                if word in boost[:SHOWN_RESULTS]:
                    boost_visible_top3[line_index] += 1
                if word in base[:suggest_chain.CELL_COUNT]:
                    base_engine_top4[line_index] += 1
                if word in boost[:suggest_chain.CELL_COUNT]:
                    boost_engine_top4[line_index] += 1
    total = sum(moments)
    base1 = sum(base_top1)
    boost1 = sum(boost_top1)
    base_visible = sum(base_visible_top3)
    boost_visible = sum(boost_visible_top3)
    base_engine = sum(base_engine_top4)
    boost_engine = sum(boost_engine_top4)
    ci_lo, ci_hi = suggest_chain.paired_delta_ci95(base_top1, boost_top1, moments)
    return [
        ("ctxboost_cp2plus_moments", str(total)),
        ("ctxboost_cp2plus_top1_base_hits", str(base1)),
        ("ctxboost_cp2plus_top1_base_pct", f"{percent(base1, total):.4f}"),
        ("ctxboost_cp2plus_top1_boost_hits", str(boost1)),
        ("ctxboost_cp2plus_top1_boost_pct", f"{percent(boost1, total):.4f}"),
        ("ctxboost_cp2plus_top1_delta_pp", f"{percent(boost1 - base1, total):+.4f}"),
        ("ctxboost_cp2plus_top1_delta_ci95_lo", f"{ci_lo:+.4f}"),
        ("ctxboost_cp2plus_top1_delta_ci95_hi", f"{ci_hi:+.4f}"),
        ("ctxboost_cp2plus_visible_top3_base_hits", str(base_visible)),
        ("ctxboost_cp2plus_visible_top3_base_pct", f"{percent(base_visible, total):.4f}"),
        ("ctxboost_cp2plus_visible_top3_boost_hits", str(boost_visible)),
        ("ctxboost_cp2plus_visible_top3_boost_pct", f"{percent(boost_visible, total):.4f}"),
        ("ctxboost_cp2plus_visible_top3_delta_pp",
         f"{percent(boost_visible - base_visible, total):+.4f}"),
        ("ctxboost_cp2plus_engine_top4_base_hits", str(base_engine)),
        ("ctxboost_cp2plus_engine_top4_base_pct", f"{percent(base_engine, total):.4f}"),
        ("ctxboost_cp2plus_engine_top4_boost_hits", str(boost_engine)),
        ("ctxboost_cp2plus_engine_top4_boost_pct", f"{percent(boost_engine, total):.4f}"),
        ("ctxboost_cp2plus_engine_top4_delta_pp",
         f"{percent(boost_engine - base_engine, total):+.4f}"),
    ]


def _completion_metrics(
    unique_words: set[str], chain: suggest_chain.ChainMirror
) -> list[tuple[str, str]]:
    """Measure top-1, visible top-3 and engine top-4 at one-to-three-code-point prefixes."""
    metrics: list[tuple[str, str]] = []
    for length in (1, 2, 3):
        words = 0
        visible_top3_hits = 0
        engine_top4_hits = 0
        top1_hits = 0
        for word in unique_words:
            if len(word) < length:
                continue
            words += 1
            shown = chain.prefix_top3(word[:length])
            if word in shown[:SHOWN_RESULTS]:
                visible_top3_hits += 1
            if word in shown[:suggest_chain.CELL_COUNT]:
                engine_top4_hits += 1
            if shown and shown[0] == word:
                top1_hits += 1
        metrics.extend(
            [
                (f"prefix_cp{length}_words", str(words)),
                (f"prefix_cp{length}_top1_hits", str(top1_hits)),
                (f"prefix_top1_cp{length}_pct", f"{percent(top1_hits, words):.4f}"),
                (f"prefix_cp{length}_visible_top3_hits", str(visible_top3_hits)),
                (f"prefix_visible_top3_cp{length}_pct",
                 f"{percent(visible_top3_hits, words):.4f}"),
                (f"prefix_cp{length}_engine_top4_hits", str(engine_top4_hits)),
                (f"prefix_engine_top4_cp{length}_pct", f"{percent(engine_top4_hits, words):.4f}"),
            ]
        )
    return metrics


def _strip_empty_metrics(
    lines: list[str], pairs: list[tuple[str, str]], chain: suggest_chain.ChainMirror
) -> list[tuple[str, str]]:
    """The share of typing moments whose strip answer is empty, per answer kind.

    A prefix moment is one keystroke into a token, up to the completing one; the answer is
    the exact completion pass of the typed prefix. A next-word moment is one committed-word
    context (an adjacent pair, the ``nextword_chain_pairs`` denominator); the answer is the
    NEXT_WORD chain. The chain ends in the top-frequency fallback, so a next-word answer is
    empty only when the fallback itself is empty; that side is the fallback invariant.
    """
    prefix_moments = 0
    prefix_empty = 0
    for line in lines:
        for word in line.split(" "):
            for length in range(1, len(word)):
                prefix_moments += 1
                if not chain.prefix_top3(word[:length]):
                    prefix_empty += 1
    nextword_empty = sum(1 for head, _ in pairs if not chain.predict_top3(head))
    return [
        ("strip_empty_prefix_moments", str(prefix_moments)),
        ("strip_empty_prefix_empty", str(prefix_empty)),
        ("strip_empty_prefix_pct", f"{percent(prefix_empty, prefix_moments):.4f}"),
        ("strip_empty_nextword_moments", str(len(pairs))),
        ("strip_empty_nextword_empty", str(nextword_empty)),
        ("strip_empty_nextword_pct", f"{percent(nextword_empty, len(pairs)):.4f}"),
    ]


def _strata_metrics(
    lines: list[str], unique_words: set[str], chain: suggest_chain.ChainMirror
) -> list[tuple[str, str]]:
    """Measure visible top-3 and engine top-4 completion and next-word rates per stratum."""
    stratum_by_word = {
        word: suggest_chain.lemma_stratum(word, chain.frequency_of)
        for word in sorted(unique_words)
    }
    metrics: list[tuple[str, str]] = []
    for stratum in suggest_chain.STRATA:
        words = [word for word, label in stratum_by_word.items() if label == stratum]
        cp3_words = 0
        cp3_visible_hits = 0
        cp3_engine_hits = 0
        for word in words:
            if len(word) < STRATUM_PREFIX_CODE_POINTS:
                continue
            cp3_words += 1
            shown = chain.prefix_top3(word[:STRATUM_PREFIX_CODE_POINTS])
            if word in shown[:SHOWN_RESULTS]:
                cp3_visible_hits += 1
            if word in shown[:suggest_chain.CELL_COUNT]:
                cp3_engine_hits += 1
        stratum_pairs = 0
        stratum_visible_hits = 0
        stratum_engine_hits = 0
        for line in lines:
            line_words = line.split(" ")
            for head, successor in zip(line_words, line_words[1:]):
                if stratum_by_word[head] != stratum:
                    continue
                stratum_pairs += 1
                shown = chain.predict_top3(head)
                if successor in shown[:SHOWN_RESULTS]:
                    stratum_visible_hits += 1
                if successor in shown[:suggest_chain.CELL_COUNT]:
                    stratum_engine_hits += 1
        metrics.extend(
            [
                (f"stratum_{stratum}_words", str(len(words))),
                (f"stratum_{stratum}_cp3_words", str(cp3_words)),
                (f"stratum_{stratum}_cp3_visible_top3_hits", str(cp3_visible_hits)),
                (f"stratum_{stratum}_cp3_visible_top3_pct",
                 f"{percent(cp3_visible_hits, cp3_words):.4f}"),
                (f"stratum_{stratum}_cp3_engine_top4_hits", str(cp3_engine_hits)),
                (f"stratum_{stratum}_cp3_engine_top4_pct",
                 f"{percent(cp3_engine_hits, cp3_words):.4f}"),
                (f"stratum_{stratum}_pairs", str(stratum_pairs)),
                (f"stratum_{stratum}_visible_top3_hits", str(stratum_visible_hits)),
                (f"stratum_{stratum}_visible_top3_pct",
                 f"{percent(stratum_visible_hits, stratum_pairs):.4f}"),
                (f"stratum_{stratum}_engine_top4_hits", str(stratum_engine_hits)),
                (f"stratum_{stratum}_engine_top4_pct",
                 f"{percent(stratum_engine_hits, stratum_pairs):.4f}"),
            ]
        )
    return metrics


def _keystroke_metrics(
    lines: list[str], unique_words: set[str], chain: suggest_chain.ChainMirror
) -> list[tuple[str, str]]:
    """The keystroke-savings simulation and its vocabulary-oracle bound.

    Two arms over the same chain answers: the shipped default strip taps only the first
    SHOWN_RESULTS cells, the fourth-cell arm taps the full composed answer. The baseline and
    the oracle do not depend on the strip width.
    """
    mdp_lengths = suggest_chain.minimal_distinguishing_prefix_lengths(sorted(unique_words))

    def completion(prefix: str, context: str | None, cells: int) -> list[str]:
        successors = chain.packed_successors(context) if context is not None else ()
        return chain.prefix_top3(prefix, successors)[:cells]

    baseline, simulated, oracle = suggest_chain.keystroke_costs(
        lines,
        lambda head: chain.predict_top3(head)[:SHOWN_RESULTS],
        lambda prefix, context: completion(prefix, context, SHOWN_RESULTS),
        mdp_lengths,
    )
    _, simulated4, _ = suggest_chain.keystroke_costs(
        lines,
        lambda head: chain.predict_top3(head)[:suggest_chain.CELL_COUNT],
        lambda prefix, context: completion(prefix, context, suggest_chain.CELL_COUNT),
        mdp_lengths,
    )
    return [
        ("ks_baseline_keys", str(baseline)),
        ("ks_simulated_keys", str(simulated)),
        ("ks_pct", f"{percent(baseline - simulated, baseline):.4f}"),
        ("ks_cells4_simulated_keys", str(simulated4)),
        ("ks_cells4_pct", f"{percent(baseline - simulated4, baseline):.4f}"),
        ("ks_oracle_keys", str(oracle)),
        ("ks_oracle_pct", f"{percent(baseline - oracle, baseline):.4f}"),
    ]


if __name__ == "__main__":
    raise SystemExit(main())
