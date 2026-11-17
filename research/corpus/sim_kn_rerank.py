#!/usr/bin/env python3
"""Research-only simulation of continuation fallback and Kneser-Ney successor re-ranking.

Measurement only: no asset under app/src/main/assets is written or replaced.

The shipped table keeps up to eight successors per head in count order. The simulation starts
from that table and compares four arms on the Tatar development eval set:

  baseline             the production chain mirror over the shipped successors;
  continuation fallback
                       for a head with no shipped successors, the first four words of an
                       eight-word pool ranked by (-N1+(•,w), w); covered heads are unchanged;
  KN re-rank           the first four display successors permuted by interpolated Kneser-Ney;
                       positions four through seven stay byte-identical to the shipped order;
  combined             continuation fallback plus the four-successor KN re-rank.

The discount is chosen on the training side only: leave-one-out log-likelihood over a fixed
grid of the counted training pairs. The eval set is never read until the discount is fixed.

The pair table and continuation counts are computed with edge-punctuation recovery, matching
the shipped Tatar table's pack stream. The cache is keyed by language, dictionary digest,
recovery flag and corpus signatures, and lives under build/p1/bigram.

Output: one JSON report on stdout; progress notes on stderr. Exit 2 on any missing or invalid
input, like the eval driver.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import pickle
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

import bigram_pack  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import evaluation_metrics as metrics  # noqa: E402
import suggest_chain  # noqa: E402
import suggest_eval  # noqa: E402

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
LEIPZIG = Path.home() / "corpora-leipzig"

# The pack stream of each shipped table (the train lists the rebuild passes to the packer).
TRAIN_PATHS = {
    "tat": [
        LEIPZIG / "tat_mixed_2015_1M-sentences.txt",
        LEIPZIG / "tat_web_2018_1M-sentences.txt",
        *([LEIPZIG / "tt_conv_train90-sentences.txt"] * 10),
    ],
    "rus": [
        LEIPZIG / "rus_news_2022_1M-sentences.txt",
        LEIPZIG / "rus_news_2019_1M-sentences.txt",
        LEIPZIG / "rus_wikipedia_2021_1M-sentences.txt",
        LEIPZIG / "rus_conv_thinned60-sentences.txt",
    ],
}

EVAL_PATHS = {"tat": suggest_eval.DEFAULT_EVAL, "rus": suggest_eval.RU_EVAL}
DICT_ASSETS = {"tat": suggest_eval.DEFAULT_DICT_ASSET, "rus": suggest_eval.RU_DICT_ASSET}
BIGRAM_ASSETS = {
    "tat": suggest_eval.DEFAULT_BIGRAM_ASSET,
    "rus": suggest_eval.RU_BIGRAM_ASSET,
}

# Both shipped tables pack with edge-punctuation recovery.
RECOVER_PUNCTUATION = {"tat": True, "rus": True}

# The discount search space of the leave-one-out tuning.
DISCOUNT_GRID = (0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9)

# The conservative acceptance bar: visible top-3 delta in percentage points with the paired
# CI95 lower bound above zero; top-1, both keystroke arms and the uncovered-head top-3 must
# not regress.
GATE_DELTA_PP = 0.3

# The engine composes four candidates; the strip displays the first three.
DISPLAY_SUCCESSORS = suggest_chain.BIGRAM_MAX_RESULTS
FALLBACK_POOL_SIZE = 8
CACHE_VERSION = 3
STRATA = ("all", "covered", "uncovered")
METRIC_NAMES = ("top1", "visible_top3", "engine_top4")


class SimError(ValueError):
    """A missing or invalid input (exit 2)."""


@dataclass
class TrainingSide:
    """Counted training data and the shipped assets used by every arm."""

    language_tag: str
    language: coverage.Language
    recover_punctuation: bool
    table: dict[str, list[tuple[str, int]]]
    continuations: tuple[dict[str, int], dict[str, int], int]
    heads: list[str]
    successes_by_head: dict[str, list[str]]
    dictionary_words: list[str]
    dictionary_frequencies: list[int]
    cache_key: tuple[object, ...]


def make_kn_ranker(
    discount: float,
    head_continuations: dict[str, int],
    successor_continuations: dict[str, int],
    continuation_total: int,
) -> Callable[[str, list[tuple[str, int]]], list[tuple[str, int]]]:
    """Order a head's kept successors by interpolated Kneser-Ney score.

    score(w|h) = max(c(h,w) − d, 0) + d · N₁₊(h,•) · N₁₊(•,w) / N₁₊(•,•)

    The head's total pair count c(h,•) multiplies the whole interpolated expression, so it
    is dropped as a per-head constant; the second term is λ(h)·P_cont(w) with the usual
    λ(h) = d · N₁₊(h,•) / c(h,•). Ties break code-point ascending. At d = 0 the score
    degenerates to the raw count and the order to the packing order.
    """

    def rank(head: str, kept: list[tuple[str, int]]) -> list[tuple[str, int]]:
        lambda_mass = discount * head_continuations.get(head, 0)
        return sorted(
            kept,
            key=lambda item: (
                -(
                    max(item[1] - discount, 0.0)
                    + lambda_mass
                    * successor_continuations.get(item[0], 0)
                    / continuation_total
                ),
                item[0],
            ),
        )

    return rank


def log(message: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {message}", file=sys.stderr, flush=True)


def default_cache_path(language_tag: str) -> Path:
    return REPOSITORY_ROOT / "build" / "p1" / "bigram" / (
        f"kn-{language_tag}-recovered-v{CACHE_VERSION}.pkl"
    )


def corpus_signature(paths: Sequence[Path]) -> tuple[tuple[str, int, str], ...]:
    return tuple((str(path), path.stat().st_size, bigram_pack.sha256_of(path))
                 for path in paths)


def load_training_side(language_tag: str, cache: Path | None) -> TrainingSide:
    """Load the pair table and continuation counts from a fresh cache or corpus passes."""
    language = coverage.language_for(language_tag)
    recover_punctuation = RECOVER_PUNCTUATION[language_tag]
    dict_asset = DICT_ASSETS[language_tag]
    bigram_asset = BIGRAM_ASSETS[language_tag]
    if not dict_asset.is_file() or not bigram_asset.is_file():
        raise SimError(f"missing bundled asset under {dict_asset.parent}")
    dictionary_words, dictionary_frequencies, dictionary_raw = suggest_eval.load_dictionary_words(
        dict_asset
    )
    successes_by_head = suggest_eval.load_bigram_successes(
        bigram_asset, dictionary_words, dictionary_raw
    )
    heads = sorted(successes_by_head)
    paths = TRAIN_PATHS[language_tag]
    for path in paths:
        if not path.is_file():
            raise SimError(f"missing training corpus: {path}")

    cache_key = (
        "bigram-kn-rerank",
        CACHE_VERSION,
        language_tag,
        hashlib.sha256(dictionary_raw).hexdigest(),
        recover_punctuation,
        corpus_signature(paths),
    )
    if cache is not None and cache.is_file():
        with cache.open("rb") as stream:
            cached = pickle.load(stream)
        if cached.get("key") == cache_key:
            log(f"training side loaded from {cache}")
            table = cached["table"]
            continuations = cached["continuations"]
        else:
            log(f"cache {cache} is stale; recomputing")
            table, continuations = _count_training_side(
                paths, heads, dictionary_words, language, recover_punctuation
            )
    else:
        table, continuations = _count_training_side(
            paths, heads, dictionary_words, language, recover_punctuation
        )
    if cache is not None:
        cache.parent.mkdir(parents=True, exist_ok=True)
        with cache.open("wb") as stream:
            pickle.dump(
                {"key": cache_key, "table": table, "continuations": continuations},
                stream,
            )
    return TrainingSide(
        language_tag=language_tag,
        language=language,
        recover_punctuation=recover_punctuation,
        table=table,
        continuations=continuations,
        heads=heads,
        successes_by_head=successes_by_head,
        dictionary_words=dictionary_words,
        dictionary_frequencies=dictionary_frequencies,
        cache_key=cache_key,
    )


def _count_training_side(
    paths: Sequence[Path],
    heads: Sequence[str],
    dictionary_words: Sequence[str],
    language: coverage.Language,
    recover_punctuation: bool,
) -> tuple[
    dict[str, list[tuple[str, int]]],
    tuple[dict[str, int], dict[str, int], int],
]:
    log(f"counting pairs for {len(heads)} shipped heads ({language.tag})")
    started = time.monotonic()
    table = bigram_pack.count_pairs(
        paths,
        frozenset(heads),
        frozenset(dictionary_words),
        1,
        [],
        alphabet=language.alphabet,
        recover_punctuation=recover_punctuation,
    )
    log(f"pairs counted in {time.monotonic() - started:.0f}s; counting continuations")
    started = time.monotonic()
    continuations = bigram_pack.count_continuations(
        list(dict.fromkeys(paths)),
        frozenset(dictionary_words),
        language.alphabet,
        recover_punctuation=recover_punctuation,
    )
    log(f"continuations counted in {time.monotonic() - started:.0f}s")
    return table, continuations


def loo_log_likelihood(
    table: dict[str, list[tuple[str, int]]],
    head_continuations: dict[str, int],
    successor_continuations: dict[str, int],
    continuation_total: int,
    discount: float,
) -> tuple[float, int]:
    """Leave-one-out log-likelihood of the counted training pairs under interpolated KN.

    Each occurrence of a pair (h, w) with count c is scored with that occurrence removed:
    the discounted term uses c-1, and when c == 1 the removal also drops one distinct
    continuation from N₁₊(h,•), N₁₊(•,w) and their total. A head left with no observation
    backs off to the continuation distribution. Pairs whose held-out probability is zero
    under every discount are excluded and counted: they carry no signal for the choice.
    """
    total = 0.0
    excluded = 0
    for head, successors in table.items():
        head_count = sum(count for _word, count in successors)
        head_cont = head_continuations.get(head, 0)
        for word, count in successors:
            removed = count - 1
            singleton = count == 1
            n_head = head_cont - 1 if singleton else head_cont
            n_succ = (
                successor_continuations[word] - 1 if singleton
                else successor_continuations[word]
            )
            n_total = continuation_total - 1 if singleton else continuation_total
            backoff = n_succ / n_total
            if head_count == 1:
                probability = backoff
            else:
                probability = (
                    max(removed - discount, 0.0) / (head_count - 1)
                    + (discount * n_head / (head_count - 1)) * backoff
                )
            if probability <= 0.0:
                excluded += count
                continue
            total += count * math.log(probability)
    return total, excluded


def tune_discount(side: TrainingSide) -> dict[str, object]:
    """Choose the discount on the training side only, before the eval set is loaded."""
    successor_continuations, head_continuations, continuation_total = side.continuations
    grid: list[dict[str, float]] = []
    excluded = 0
    for discount in DISCOUNT_GRID:
        value, excluded = loo_log_likelihood(
            side.table,
            head_continuations,
            successor_continuations,
            continuation_total,
            discount,
        )
        grid.append({"discount": discount, "loo_log_likelihood": round(value, 3)})
        log(f"d={discount}: LOO log-likelihood {value:.1f}")
    best = max(grid, key=lambda entry: entry["loo_log_likelihood"])
    return {
        "selection": "training_leave_one_out_log_likelihood",
        "grid": grid,
        "loo_excluded_occurrences": excluded,
        "argmax_discount": best["discount"],
    }


def rerank_display_successors(
    head: str,
    original: Sequence[str],
    counts: dict[str, int],
    ranker: Callable[[str, list[tuple[str, int]]], list[tuple[str, int]]],
) -> list[str]:
    """Re-rank only the first four successors; keep positions four through seven unchanged."""
    display = list(original[:DISPLAY_SUCCESSORS])
    tail = list(original[DISPLAY_SUCCESSORS:])
    kept: list[tuple[str, int]] = []
    for word in display:
        if word not in counts:
            raise SimError(f"shipped pair {head} → {word} absent from the counted table")
        kept.append((word, counts[word]))
    reranked = [word for word, _count in ranker(head, kept)]
    if len(reranked) != len(display) or set(reranked) != set(display):
        raise SimError(f"re-ranking changed the display membership for head {head!r}")
    if list(original[DISPLAY_SUCCESSORS:]) != tail:
        raise SimError(f"re-ranking changed the untouched tail for head {head!r}")
    return reranked + tail


def build_kn_successes(
    side: TrainingSide,
    ranker: Callable[[str, list[tuple[str, int]]], list[tuple[str, int]]],
) -> tuple[dict[str, list[str]], int]:
    kn_successes: dict[str, list[str]] = {}
    changed = 0
    for head in side.heads:
        original = side.successes_by_head[head]
        counts = dict(side.table.get(head, ()))
        reranked = rerank_display_successors(head, original, counts, ranker)
        changed += reranked != list(original)
        kn_successes[head] = reranked
    return kn_successes, changed


def continuation_fallback_pool(
    words: Sequence[str], successor_continuations: dict[str, int]
) -> list[str]:
    """Eight words ranked by distinct left contexts, ties by code point ascending."""
    return sorted(
        words, key=lambda word: (-successor_continuations.get(word, 0), word)
    )[:FALLBACK_POOL_SIZE]


class ContinuationFallbackChain(suggest_chain.ChainMirror):
    """The chain mirror with a continuation-ranked fallback for completely uncovered heads.

    Covered heads, including partially covered ones, keep the production chain's fallback
    behavior. A head with no packed successors shows the first four eligible words of the
    eight-word pool, skipping the head itself and duplicates.
    """

    def __init__(
        self,
        words: Sequence[str],
        frequencies: Sequence[int],
        successes_by_head: dict[str, list[str]],
        continuation_pool: Sequence[str],
        suffix_rules: bool = True,
    ) -> None:
        super().__init__(words, frequencies, successes_by_head, suffix_rules=suffix_rules)
        self._continuation_pool = tuple(continuation_pool)

    def predict_top3(self, head: str) -> list[str]:
        if self._successes.get(head):
            return super().predict_top3(head)
        cached = self._predict_cache.get(head)
        if cached is not None:
            return cached
        shown: list[str] = []
        for word in self._continuation_pool:
            if len(shown) >= suggest_chain.CELL_COUNT:
                break
            if word == head or word in shown:
                continue
            shown.append(word)
        self._predict_cache[head] = shown
        return shown


def hit_flags(shown: Sequence[str], successor: str) -> tuple[int, int, int]:
    return (
        int(bool(shown) and successor == shown[0]),
        int(successor in metrics.visible_top3(shown)),
        int(successor in metrics.engine_top4(shown)),
    )


def empty_series() -> dict[str, dict[str, list[int]]]:
    return {
        stratum: {**{name: [] for name in METRIC_NAMES}, "weights": []}
        for stratum in STRATA
    }


def evaluate_arms(
    lines: Sequence[str],
    arms: dict[str, suggest_chain.ChainMirror],
    successes_by_head: dict[str, list[str]],
) -> tuple[
    dict[str, dict[str, dict[str, list[int]]]],
    dict[str, dict[str, dict[str, int]]],
    dict[str, dict[str, dict[str, int]]],
]:
    """Per-sentence hit and denominator series, plus gained and lost hits per arm."""
    series = {name: empty_series() for name in arms}
    gained = {
        name: {stratum: dict.fromkeys(METRIC_NAMES, 0) for stratum in STRATA}
        for name in arms
    }
    lost = {
        name: {stratum: dict.fromkeys(METRIC_NAMES, 0) for stratum in STRATA}
        for name in arms
    }
    for line in lines:
        words = line.split(" ")
        per_line = {
            name: {
                stratum: dict.fromkeys(METRIC_NAMES, 0) for stratum in STRATA
            }
            for name in arms
        }
        weights = dict.fromkeys(STRATA, 0)
        for head, successor in zip(words, words[1:]):
            covered = bool(successes_by_head.get(head))
            strata = ("all", "covered") if covered else ("all", "uncovered")
            for stratum in strata:
                weights[stratum] += 1
            baseline_flags = hit_flags(arms["baseline"].predict_top3(head), successor)
            for name, chain in arms.items():
                flags = hit_flags(chain.predict_top3(head), successor)
                for stratum in strata:
                    for position, metric_name in enumerate(METRIC_NAMES):
                        per_line[name][stratum][metric_name] += flags[position]
                        if flags[position] and not baseline_flags[position]:
                            gained[name][stratum][metric_name] += 1
                        elif baseline_flags[position] and not flags[position]:
                            lost[name][stratum][metric_name] += 1
        for name in arms:
            for stratum in STRATA:
                series[name][stratum]["weights"].append(weights[stratum])
                for metric_name in METRIC_NAMES:
                    series[name][stratum][metric_name].append(
                        per_line[name][stratum][metric_name]
                    )
    return series, gained, lost


def _arm_rate(hits: Sequence[int], weights: Sequence[int]) -> dict[str, object]:
    denominator = sum(weights)
    if denominator == 0:
        return {"hits": 0, "denominator": 0, "pct": 0.0, "ci95": [0.0, 0.0]}
    low, high = metrics.rate_ci95(hits, weights)
    return {
        "hits": sum(hits),
        "denominator": denominator,
        "pct": round(sum(hits) * 100.0 / denominator, 6),
        "ci95": [round(low, 6), round(high, 6)],
    }


def _paired_top1(
    baseline_hits: Sequence[int],
    candidate_hits: Sequence[int],
    weights: Sequence[int],
) -> dict[str, object]:
    denominator = sum(weights)
    if denominator == 0:
        return {
            "k": 1,
            "role": "diagnostic",
            "baseline": _arm_rate(baseline_hits, weights),
            "candidate": _arm_rate(candidate_hits, weights),
            "delta": {"pp": 0.0, "ci95": [0.0, 0.0]},
        }
    low, high = metrics.paired_delta_ci95(
        baseline_hits, weights, candidate_hits, weights
    )
    baseline = _arm_rate(baseline_hits, weights)
    candidate = _arm_rate(candidate_hits, weights)
    return {
        "k": 1,
        "role": "diagnostic",
        "baseline": baseline,
        "candidate": candidate,
        "delta": {
            "pp": round(candidate["pct"] - baseline["pct"], 6),
            "ci95": [round(low, 6), round(high, 6)],
            "paired": True,
            "method": metrics.BOOTSTRAP_METHOD,
            "resampling": metrics.SHARED_RESAMPLES,
            "rounds": suggest_chain.BOOTSTRAP_ROUNDS,
        },
    }


def _paired_metric(
    baseline_hits: Sequence[int],
    candidate_hits: Sequence[int],
    weights: Sequence[int],
    *,
    k: int,
    role: str,
) -> dict[str, object]:
    if sum(weights) == 0:
        return {
            "k": k,
            "role": role,
            "baseline": _arm_rate(baseline_hits, weights),
            "candidate": _arm_rate(candidate_hits, weights),
            "delta": {"pp": 0.0, "ci95": [0.0, 0.0]},
        }
    return metrics.paired_metric_report(
        baseline_hits,
        weights,
        candidate_hits,
        weights,
        k=k,
        role=role,
    )


def build_metric_report(
    series: dict[str, dict[str, dict[str, list[int]]]],
    gained: dict[str, dict[str, dict[str, int]]],
    lost: dict[str, dict[str, dict[str, int]]],
) -> dict[str, object]:
    baseline = {
        stratum: {
            "denominator": sum(series["baseline"][stratum]["weights"]),
            **{
                name: _arm_rate(
                    series["baseline"][stratum][name],
                    series["baseline"][stratum]["weights"],
                )
                for name in METRIC_NAMES
            },
        }
        for stratum in STRATA
    }
    arms: dict[str, object] = {}
    for name in series:
        if name == "baseline":
            continue
        arms[name] = {}
        for stratum in STRATA:
            weights = series["baseline"][stratum]["weights"]
            arms[name][stratum] = {
                "denominator": sum(weights),
                "top1": _paired_top1(
                    series["baseline"][stratum]["top1"],
                    series[name][stratum]["top1"],
                    weights,
                ),
                "visible_top3": _paired_metric(
                    series["baseline"][stratum]["visible_top3"],
                    series[name][stratum]["visible_top3"],
                    weights,
                    k=metrics.VISIBLE_TOP_K,
                    role="user",
                ),
                "engine_top4": _paired_metric(
                    series["baseline"][stratum]["engine_top4"],
                    series[name][stratum]["engine_top4"],
                    weights,
                    k=metrics.ENGINE_TOP_K,
                    role="diagnostic",
                ),
                "hits_gained": dict(gained[name][stratum]),
                "hits_lost": dict(lost[name][stratum]),
            }
    return {"baseline": baseline, "arms": arms}


def raw_key_costs(lines: Sequence[str]) -> list[int]:
    costs: list[int] = []
    for line in lines:
        words = line.split(" ")
        costs.append(sum(len(word) for word in words) + len(words) - 1)
    return costs


def simulated_key_costs(
    lines: Sequence[str],
    chain: suggest_chain.ChainMirror,
    cells: int,
) -> list[int]:
    """Per-sentence simulated keystrokes, mirroring suggest_chain.keystroke_costs."""
    completion_costs: dict[tuple[str, str | None], int] = {}

    def completion_cost(word: str, context: str | None) -> int:
        key = (word, context)
        cost = completion_costs.get(key)
        if cost is None:
            cost = len(word)
            packed = chain.packed_successors(context) if context is not None else ()
            for cut in range(1, len(word)):
                if word in chain.prefix_top3(word[:cut], packed)[:cells]:
                    cost = cut + 1
                    break
            completion_costs[key] = cost
        return cost

    costs: list[int] = []
    for line in lines:
        words = line.split(" ")
        simulated = completion_cost(words[0], None)
        for previous, word in zip(words, words[1:]):
            if word in chain.predict_top3(previous)[:cells]:
                simulated += 1
            else:
                simulated += completion_cost(word, previous)
        costs.append(simulated)
    return costs


def paired_keystroke_report(
    baseline_keys: Sequence[int],
    candidate_keys: Sequence[int],
    baseline_arm_keys: Sequence[int],
) -> dict[str, object]:
    """Exact totals plus paired CI95 of savings and of the change against the baseline arm."""
    baseline_total = sum(baseline_keys)
    candidate_total = sum(candidate_keys)
    baseline_arm_total = sum(baseline_arm_keys)
    baseline_savings = (baseline_total - baseline_arm_total) * 100.0 / baseline_total
    candidate_savings = (baseline_total - candidate_total) * 100.0 / baseline_total

    size = len(baseline_keys)
    stream = suggest_chain.splitmix64_index_stream(size, suggest_chain.BOOTSTRAP_SEED)
    savings_samples: list[float] = []
    delta_samples: list[float] = []
    for _ in range(suggest_chain.BOOTSTRAP_ROUNDS):
        raw_total = 0
        arm_total = 0
        baseline_arm = 0
        for _ in range(size):
            drawn = next(stream)
            raw_total += baseline_keys[drawn]
            arm_total += candidate_keys[drawn]
            baseline_arm += baseline_arm_keys[drawn]
        savings = (raw_total - arm_total) * 100.0 / raw_total
        baseline_savings_sample = (raw_total - baseline_arm) * 100.0 / raw_total
        savings_samples.append(savings)
        delta_samples.append(savings - baseline_savings_sample)
    savings_samples.sort()
    delta_samples.sort()
    low = suggest_chain.nearest_rank_index(25, suggest_chain.BOOTSTRAP_ROUNDS)
    high = suggest_chain.nearest_rank_index(975, suggest_chain.BOOTSTRAP_ROUNDS)
    return {
        "simulated_keys": candidate_total,
        "saved_keys": baseline_total - candidate_total,
        "savings_pct": round(candidate_savings, 6),
        "savings_ci95": [
            round(savings_samples[low], 6),
            round(savings_samples[high], 6),
        ],
        "delta_keys_vs_baseline": candidate_total - baseline_arm_total,
        "delta_savings_pp_vs_baseline": round(
            candidate_savings - baseline_savings, 6
        ),
        "delta_savings_pp_ci95": [
            round(delta_samples[low], 6),
            round(delta_samples[high], 6),
        ],
    }


def build_keystroke_report(
    lines: Sequence[str],
    arms: dict[str, suggest_chain.ChainMirror],
) -> dict[str, object]:
    raw_keys = raw_key_costs(lines)
    simulated = {
        name: {
            cells: simulated_key_costs(lines, chain, cells)
            for cells in (suggest_eval.SHOWN_RESULTS, suggest_chain.CELL_COUNT)
        }
        for name, chain in arms.items()
    }
    baseline_arm = simulated["baseline"]
    report = {
        "baseline_keys": sum(raw_keys),
        "arms": {},
    }
    for name in arms:
        report["arms"][name] = {
            "cells3": paired_keystroke_report(
                raw_keys, simulated[name][suggest_eval.SHOWN_RESULTS],
                baseline_arm[suggest_eval.SHOWN_RESULTS],
            ),
            "cells4": paired_keystroke_report(
                raw_keys, simulated[name][suggest_chain.CELL_COUNT],
                baseline_arm[suggest_chain.CELL_COUNT],
            ),
        }
    return report


def acceptance_report(
    metric_report: dict[str, object],
    keystroke_report: dict[str, object],
) -> dict[str, object]:
    arms = metric_report["arms"]
    keystrokes = keystroke_report["arms"]
    results: dict[str, object] = {}
    recommended: str | None = None
    recommended_delta = float("-inf")
    for name, strata in arms.items():
        overall = strata["all"]
        uncovered = strata["uncovered"]
        keys3 = keystrokes[name]["cells3"]
        keys4 = keystrokes[name]["cells4"]
        checks = {
            "top1_not_regressed_overall": overall["top1"]["delta"]["pp"] >= 0.0,
            "visible_top3_meaningful_overall": (
                overall["visible_top3"]["delta"]["pp"] >= GATE_DELTA_PP
                and overall["visible_top3"]["delta"]["ci95"][0] > 0.0
            ),
            "keystroke_cells3_not_regressed": keys3["delta_keys_vs_baseline"] <= 0,
            "keystroke_cells4_not_regressed": keys4["delta_keys_vs_baseline"] <= 0,
            "uncovered_top1_not_regressed": (
                uncovered["top1"]["delta"]["pp"] >= 0.0
            ),
            "uncovered_visible_top3_not_regressed": (
                uncovered["visible_top3"]["delta"]["pp"] >= 0.0
            ),
        }
        passed = all(checks.values())
        results[name] = {
            "checks": checks,
            "passes": passed,
            "overall_top3_delta_pp": overall["visible_top3"]["delta"]["pp"],
            "overall_top3_delta_ci95": overall["visible_top3"]["delta"]["ci95"],
            "uncovered_top3_delta_pp": uncovered["visible_top3"]["delta"]["pp"],
            "uncovered_top3_delta_ci95": uncovered["visible_top3"]["delta"]["ci95"],
        }
        if passed and overall["visible_top3"]["delta"]["pp"] > recommended_delta:
            recommended = name
            recommended_delta = overall["visible_top3"]["delta"]["pp"]
    return {
        "rule": {
            "meaningful_top3_delta_pp": GATE_DELTA_PP,
            "meaningful_top3_ci95_lower_must_exceed_zero": True,
            "top1_must_not_regress_overall": True,
            "keystrokes_must_not_regress_cells3_or_cells4": True,
            "uncovered_top1_and_top3_must_not_regress": True,
        },
        "arms": results,
        "recommended_arm": recommended,
    }


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--language", choices=sorted(TRAIN_PATHS), default="tat")
    parser.add_argument("--cache", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=None)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    cache = args.cache if args.cache is not None else default_cache_path(args.language)
    try:
        side = load_training_side(args.language, cache)
    except SimError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    tuning = tune_discount(side)
    discount = float(tuning["argmax_discount"])
    successor_continuations, head_continuations, continuation_total = side.continuations
    ranker = make_kn_ranker(
        discount, head_continuations, successor_continuations, continuation_total
    )
    kn_successes, reranked_heads = build_kn_successes(side, ranker)
    fallback_pool = continuation_fallback_pool(
        side.dictionary_words, successor_continuations
    )

    eval_path = EVAL_PATHS[args.language]
    if not eval_path.is_file():
        print(f"error: eval set is missing: {eval_path}", file=sys.stderr)
        return 2
    try:
        lines = suggest_eval.load_eval_lines(eval_path, side.language.alphabet)
    except suggest_eval.SuggestEvalError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    suffix_rules = args.language == "tat"
    arms = {
        "baseline": suggest_chain.ChainMirror(
            side.dictionary_words,
            side.dictionary_frequencies,
            side.successes_by_head,
            suffix_rules=suffix_rules,
        ),
        "continuation_fallback": ContinuationFallbackChain(
            side.dictionary_words,
            side.dictionary_frequencies,
            side.successes_by_head,
            fallback_pool,
            suffix_rules=suffix_rules,
        ),
        "kn_rerank": suggest_chain.ChainMirror(
            side.dictionary_words,
            side.dictionary_frequencies,
            kn_successes,
            suffix_rules=suffix_rules,
        ),
        "combined": ContinuationFallbackChain(
            side.dictionary_words,
            side.dictionary_frequencies,
            kn_successes,
            fallback_pool,
            suffix_rules=suffix_rules,
        ),
    }
    series, gained, lost = evaluate_arms(lines, arms, side.successes_by_head)
    metric_report = build_metric_report(series, gained, lost)
    keystroke_report = build_keystroke_report(lines, arms)
    acceptance = acceptance_report(metric_report, keystroke_report)

    report = {
        "language": args.language,
        "eval_path": str(eval_path),
        "eval_lines": len(lines),
        "eval_pairs": sum(series["baseline"]["all"]["weights"]),
        "train_paths": [str(path) for path in TRAIN_PATHS[args.language]],
        "recover_punctuation": side.recover_punctuation,
        "cache_path": str(cache),
        "cache_key": repr(side.cache_key),
        "shipped_heads": len(side.heads),
        "shipped_pairs": sum(len(successors) for successors in side.successes_by_head.values()),
        "counted_heads": len(side.table),
        "counted_pairs_kept": sum(len(successors) for successors in side.table.values()),
        "continuation_total": continuation_total,
        "discount": discount,
        "tuning": tuning,
        "reranked_heads": reranked_heads,
        "fallback_pool": {
            "size": len(fallback_pool),
            "ranking": "(-N1+(•,w), word)",
            "words": fallback_pool,
            "successor_continuations": [
                successor_continuations.get(word, 0) for word in fallback_pool
            ],
        },
        "metrics": metric_report,
        "keystrokes": keystroke_report,
        "acceptance": acceptance,
    }
    serialized = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized, encoding="utf-8")
    else:
        print(serialized, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
