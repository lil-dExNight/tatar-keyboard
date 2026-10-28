#!/usr/bin/env python3
"""Pack-time Kneser-Ney successor re-ranking: discount tuning and offline gate measurement.

TEST MEASUREMENT ONLY -- no asset under app/src/main/assets is written or replaced.

The shipped schema-3 table keeps each head's top-4 successors in count order. The re-rank
permutes those kept successors by interpolated Kneser-Ney score (``make_kn_ranker`` below);
the successor sets stay exactly as packed.

``tune`` fixes the discount on the training side only: leave-one-out log-likelihood of the
counted training pairs over a fixed grid, one number per grid point. The argmax is the
pre-registered discount; record it before running ``eval``.

``eval --discount D`` replays the pinned eval set through two chains -- the shipped table as
packed (baseline arm) and the same successor sets re-ranked with discount D (KN arm) -- and
reports chain top-3 and top-1 rates per arm plus the paired bootstrap CI95 of the deltas
(one SplitMix64 stream, the harness seed and round count). The pre-registered gate: chain
top-3 delta >= +0.3 pp with the CI95 lower bound above zero, chain top-1 not regressed.

The training stream mirrors the shipped table's pack stream (corpus list and conversational
weight); the pair counts come from ``bigram_pack.count_pairs`` and the continuation counts
from ``bigram_pack.count_continuations`` over the deduplicated stream. ``--cache`` stores the
pair table and the continuation counts so ``tune`` and ``eval`` pay the corpus passes once;
the cache is keyed by language and the linked dictionary's raw SHA-256.

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
from pathlib import Path
from typing import Callable, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

import bigram_pack  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import suggest_chain  # noqa: E402
import suggest_eval  # noqa: E402

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

# The discount search space of the leave-one-out tuning.
DISCOUNT_GRID = (0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9)

# The acceptance bar: chain top-3 delta in percentage points with the paired CI95 lower
# bound above zero; chain top-1 must not regress.
GATE_DELTA_PP = 0.3


class SimError(ValueError):
    """A missing or invalid input (exit 2)."""


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


def load_training_side(
    language_tag: str, cache: Path | None
) -> tuple[
    dict[str, list[tuple[str, int]]],
    tuple[dict[str, int], dict[str, int], int],
    list[str],
    dict[str, list[str]],
    list[str],
]:
    """The pair table and continuation counts of the shipped table's heads, from cache or
    corpus passes, plus the shipped head list, its successor lists and the dictionary."""
    language = coverage.language_for(language_tag)
    dict_asset = DICT_ASSETS[language_tag]
    bigram_asset = BIGRAM_ASSETS[language_tag]
    if not dict_asset.is_file() or not bigram_asset.is_file():
        raise SimError(f"missing bundled asset under {dict_asset.parent}")
    dictionary_words, _frequencies, dictionary_raw = suggest_eval.load_dictionary_words(
        dict_asset
    )
    successes_by_head = suggest_eval.load_bigram_successes(
        bigram_asset, dictionary_words, dictionary_raw
    )
    heads = sorted(successes_by_head)
    dictionary_key = hashlib.sha256(dictionary_raw).hexdigest()
    paths = TRAIN_PATHS[language_tag]
    for path in paths:
        if not path.is_file():
            raise SimError(f"missing training corpus: {path}")

    if cache is not None and cache.is_file():
        with cache.open("rb") as stream:
            cached = pickle.load(stream)
        if cached.get("key") == (language_tag, dictionary_key):
            log(f"training side loaded from {cache}")
            return (
                cached["table"],
                cached["continuations"],
                heads,
                successes_by_head,
                dictionary_words,
            )
        log(f"cache {cache} is stale; recomputing")

    log(f"counting pairs for {len(heads)} shipped heads ({language_tag})")
    started = time.monotonic()
    table = bigram_pack.count_pairs(
        paths,
        frozenset(heads),
        frozenset(dictionary_words),
        1,
        [],
        alphabet=language.alphabet,
    )
    log(f"pairs counted in {time.monotonic() - started:.0f}s; counting continuations")
    started = time.monotonic()
    continuations = bigram_pack.count_continuations(
        list(dict.fromkeys(paths)), frozenset(dictionary_words), language.alphabet
    )
    log(f"continuations counted in {time.monotonic() - started:.0f}s")
    if cache is not None:
        cache.parent.mkdir(parents=True, exist_ok=True)
        with cache.open("wb") as stream:
            pickle.dump(
                {
                    "key": (language_tag, dictionary_key),
                    "table": table,
                    "continuations": continuations,
                },
                stream,
            )
    return table, continuations, heads, successes_by_head, dictionary_words


def loo_log_likelihood(
    table: dict[str, list[tuple[str, int]]],
    head_continuations: dict[str, int],
    successor_continuations: dict[str, int],
    continuation_total: int,
    discount: float,
) -> float:
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
                # A singleton whose removal leaves the head without successors or the
                # successor without heads scores zero under every discount: it carries no
                # signal for the choice and is excluded.
                excluded += count
                continue
            total += count * math.log(probability)
    return total, excluded


def paired_bootstrap(
    hits_baseline: Sequence[int],
    hits_variant: Sequence[int],
    weights: Sequence[int],
    rounds: int = suggest_chain.BOOTSTRAP_ROUNDS,
    seed: int = suggest_chain.BOOTSTRAP_SEED,
) -> dict[str, list[float]]:
    """CI95 of both arms' rates and of their delta, over one shared resample stream.

    Each round draws ``len(weights)`` sentence indices from the harness's SplitMix64 stream
    and scores both arms on the same draw, so the per-round delta is exactly paired.
    """
    size = len(weights)
    stream = suggest_chain.splitmix64_index_stream(size, seed)
    rates_baseline: list[float] = []
    rates_variant: list[float] = []
    deltas: list[float] = []
    for _ in range(rounds):
        hits_a = 0
        hits_b = 0
        weight = 0
        for _ in range(size):
            drawn = next(stream)
            hits_a += hits_baseline[drawn]
            hits_b += hits_variant[drawn]
            weight += weights[drawn]
        rate_a = hits_a * 100.0 / weight
        rate_b = hits_b * 100.0 / weight
        rates_baseline.append(rate_a)
        rates_variant.append(rate_b)
        deltas.append(rate_b - rate_a)
    for rates in (rates_baseline, rates_variant, deltas):
        rates.sort()
    low = suggest_chain.nearest_rank_index(25, rounds)
    high = suggest_chain.nearest_rank_index(975, rounds)
    return {
        "baseline_ci95": [rates_baseline[low], rates_baseline[high]],
        "variant_ci95": [rates_variant[low], rates_variant[high]],
        "delta_ci95": [deltas[low], deltas[high]],
    }


def sentence_hits(
    lines: Sequence[str], chain: suggest_chain.ChainMirror
) -> tuple[list[int], list[int], list[int]]:
    """Per-sentence chain top-3 hits, top-1 hits and pair counts over the eval lines."""
    hits_top3: list[int] = []
    hits_top1: list[int] = []
    weights: list[int] = []
    for line in lines:
        words = line.split(" ")
        top3 = 0
        top1 = 0
        for head, successor in zip(words, words[1:]):
            shown = chain.predict_top3(head)
            if successor in shown:
                top3 += 1
                if successor == shown[0]:
                    top1 += 1
        hits_top3.append(top3)
        hits_top1.append(top1)
        weights.append(len(words) - 1)
    return hits_top3, hits_top1, weights


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)
    for name in ("tune", "eval"):
        sub = subparsers.add_parser(name)
        sub.add_argument("--language", choices=sorted(TRAIN_PATHS), default="tat")
        sub.add_argument("--cache", type=Path, default=None)
    subparsers.choices["eval"].add_argument("--discount", type=float, required=True)
    args = parser.parse_args(argv)

    try:
        table, continuations, heads, successes_by_head, dictionary_words = (
            load_training_side(args.language, args.cache)
        )
    except SimError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    successor_continuations, head_continuations, continuation_total = continuations

    if args.command == "tune":
        grid = []
        excluded = 0
        for discount in DISCOUNT_GRID:
            value, excluded = loo_log_likelihood(
                table, head_continuations, successor_continuations,
                continuation_total, discount,
            )
            grid.append({"discount": discount, "loo_log_likelihood": round(value, 3)})
            log(f"d={discount}: LOO log-likelihood {value:.1f}")
        best = max(grid, key=lambda entry: entry["loo_log_likelihood"])
        print(
            json.dumps(
                {"language": args.language, "grid": grid,
                 "loo_excluded_occurrences": excluded,
                 "argmax_discount": best["discount"]},
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    # eval: rebuild each shipped head's successor order under the KN score.
    ranker = make_kn_ranker(
        args.discount, head_continuations, successor_continuations, continuation_total
    )
    kn_successes: dict[str, list[str]] = {}
    for head in heads:
        counts = dict(table.get(head, ()))
        kept = []
        for word in successes_by_head[head]:
            if word not in counts:
                raise SimError(f"shipped pair {head} → {word} absent from the counted table")
            kept.append((word, counts[word]))
        kn_successes[head] = [word for word, _count in ranker(head, kept)]
    reranked_heads = sum(
        1 for head in heads if kn_successes[head] != successes_by_head[head]
    )

    language = coverage.language_for(args.language)
    eval_path = EVAL_PATHS[args.language]
    if not eval_path.is_file():
        print(f"error: eval set is missing: {eval_path}", file=sys.stderr)
        return 2
    try:
        lines = suggest_eval.load_eval_lines(eval_path, language.alphabet)
    except suggest_eval.SuggestEvalError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    dict_asset = DICT_ASSETS[args.language]
    _words, frequencies, _raw = suggest_eval.load_dictionary_words(dict_asset)
    suffix_rules = args.language == "tat"
    baseline_chain = suggest_chain.ChainMirror(
        dictionary_words, frequencies, successes_by_head, suffix_rules=suffix_rules
    )
    kn_chain = suggest_chain.ChainMirror(
        dictionary_words, frequencies, kn_successes, suffix_rules=suffix_rules
    )
    hits_a3, hits_a1, weights = sentence_hits(lines, baseline_chain)
    hits_b3, hits_b1, _ = sentence_hits(lines, kn_chain)
    pairs = sum(weights)
    baseline_top3 = sum(hits_a3)
    kn_top3 = sum(hits_b3)
    baseline_top1 = sum(hits_a1)
    kn_top1 = sum(hits_b1)
    top3 = paired_bootstrap(hits_a3, hits_b3, weights)
    top1 = paired_bootstrap(hits_a1, hits_b1, weights)
    delta_top3 = (kn_top3 - baseline_top3) * 100.0 / pairs
    delta_top1 = (kn_top1 - baseline_top1) * 100.0 / pairs
    passed = delta_top3 >= GATE_DELTA_PP and top3["delta_ci95"][0] > 0.0 and delta_top1 >= 0.0
    report = {
        "language": args.language,
        "discount": args.discount,
        "pairs": pairs,
        "reranked_heads": reranked_heads,
        "baseline_top3_hits": baseline_top3,
        "kn_top3_hits": kn_top3,
        "baseline_top3_pct": round(baseline_top3 * 100.0 / pairs, 4),
        "kn_top3_pct": round(kn_top3 * 100.0 / pairs, 4),
        "delta_top3_pp": round(delta_top3, 4),
        "top3_delta_ci95": [round(bound, 4) for bound in top3["delta_ci95"]],
        "baseline_top1_hits": baseline_top1,
        "kn_top1_hits": kn_top1,
        "delta_top1_pp": round(delta_top1, 4),
        "top1_delta_ci95": [round(bound, 4) for bound in top1["delta_ci95"]],
        "gate_delta_pp": GATE_DELTA_PP,
        "passed": bool(passed),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
