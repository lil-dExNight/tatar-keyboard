#!/usr/bin/env python3
"""Offline simulation of the case-aware variant of the stem-keyed bigram backoff stage.

TEST MEASUREMENT ONLY -- no asset under app/src/main/assets is written or replaced.

The stage is the one simulated by ``sim_stem_backoff.py``: between the form-keyed bigram pass
and the after-word forms pass, when the bigram pass fills fewer cells than the strip has, the
committed word's stem is looked up in a stem-keyed bigram table and each successor stem is
expanded to one surface form. The variant changes only the expansion: a successor stem is
expanded to the successor form most often attested in training pairs whose head maps to the
head's stem (count descending, then code point ascending -- the packer's order), not to the
successor cluster's globally most frequent form. The within-cluster form differences of an
agglutinative language are predominantly case, possessive and tense endings, so conditioning
the form choice on the head stem is what makes the expansion case-aware.

Pipeline of the simulation:

  1. Stem clusters, pair eligibility and the top-4 per-head cut are exactly those of
     ``sim_stem_backoff.py`` (the build-time generator's ``group`` mode, the packer's
     adjacency rule, count descending then code point ascending).
  2. One pass over the training streams counts two counters: (head stem, successor stem)
     pairs for the table cut, and (head stem, successor form) pairs for the expansion. The
     streams mirror the shipped table's training mix: both Leipzig tt sets plus the
     conversational train90 file listed ten times.
  3. Both stem arms and the unchanged baseline chain replay the eval set; one shared
     SplitMix64 resample stream scores every arm and metric per round, so each delta of two
     arms is exactly paired.

The pre-registered gate (fixed before the first measurement run): the case-aware arm passes
only if its composite-chain top-3 gains at least ``GATE_DELTA_PP`` percentage points over the
baseline chain with the paired CI95 lower bound above zero, and neither the chain top-1 delta
nor the keystroke-savings delta regresses (each paired CI95 must reach zero). The case-blind
arm is rerun on the current training mix as a reference; the gate applies to the case-aware
arm alone. The rule has no free parameters beyond the shipped table's per-head cut and
tie-breaks, and nothing is estimated on the held-out set.

Diagnostics mirror the first experiment -- engaged pairs, stem hits, expansion hits and the
expansion fidelity per stem arm -- plus the share of kept stem pairs whose pair-conditioned
form differs from the cluster's global top form (the mechanism the variant changes).

Output: one JSON report on stdout; progress notes on stderr. Exit 2 on any missing or invalid
input, like the eval driver.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

import bigram_pack  # noqa: E402
import sim_stem_backoff  # noqa: E402
import suggest_chain  # noqa: E402
import suggest_eval  # noqa: E402
import wordform_gen  # noqa: E402
from bigset import Counter64  # noqa: E402

CELL_COUNT = suggest_chain.CELL_COUNT

# The pre-registered acceptance bar of the experiment: the case-aware arm's simulated
# composite-chain top-3 delta must reach +1.5 percentage points with the paired CI95 lower
# bound above zero, and the chain top-1 and keystroke-savings paired CI95 intervals must
# reach zero (no regression).
GATE_DELTA_PP = 1.5

# The conversational train90 file's multiplicity in the shipped table's training mix.
CONV_WEIGHT = 10

DEFAULT_CORPORA = (
    Path.home() / "corpora-leipzig/tat_mixed_2015_1M/tat_mixed_2015_1M-sentences.txt",
    Path.home() / "corpora-leipzig/tat_web_2018_1M/tat_web_2018_1M-sentences.txt",
    *([Path.home() / "corpora-leipzig/tt_conv_train90-sentences.txt"] * CONV_WEIGHT),
)


class CaseAwareStemChain(suggest_chain.ChainMirror):
    """The chain mirror with the stem-keyed backoff stage and pair-conditioned expansion.

    Same stage placement and strip budget as ``sim_stem_backoff.StemBackoffChain``; the
    expansion of a successor stem is the form most often attested after the head's stem in
    the training pairs, not the successor cluster's global top form.
    """

    def __init__(
        self,
        words: Sequence[str],
        frequencies: Sequence[int],
        successes_by_head: dict[str, list[str]],
        form_to_stem: dict[str, str],
        stem_table: dict[str, tuple[str, ...]],
        pair_forms: dict[tuple[str, str], str],
    ) -> None:
        super().__init__(words, frequencies, successes_by_head)
        self._form_to_stem = form_to_stem
        self._stem_table = stem_table
        self._pair_forms = pair_forms
        self._trace_cache: dict[str, tuple[list[str], list[str]]] = {}

    def predict_top3(self, head: str) -> list[str]:
        return self._traced(head)[0]

    def stem_additions(self, head: str) -> list[str]:
        """The forms the stem stage placed on the strip for [head] (empty when not engaged)."""
        return self._traced(head)[1]

    def _traced(self, head: str) -> tuple[list[str], list[str]]:
        cached = self._trace_cache.get(head)
        if cached is not None:
            return cached
        shown = list(self._successes.get(head, ()))[: suggest_chain.BIGRAM_MAX_RESULTS]
        added: list[str] = []
        if len(shown) < CELL_COUNT:
            stem = self._form_to_stem.get(head, head)
            for successor_stem in self._stem_table.get(stem, ()):
                form = self._pair_forms.get((stem, successor_stem))
                if form is None or form == head or form in shown:
                    continue
                shown.append(form)
                added.append(form)
                if len(shown) >= CELL_COUNT:
                    break
        if len(shown) < CELL_COUNT:
            shown += suggest_chain.forms_of(
                head, shown, CELL_COUNT - len(shown), self.frequency_of
            )
        if len(shown) < CELL_COUNT:
            for word in self._fallback_pool:
                if len(shown) >= CELL_COUNT:
                    break
                if word == head or word in shown:
                    continue
                shown.append(word)
        self._trace_cache[head] = (shown, added)
        return shown, added


def count_stem_and_form_pairs(
    paths: Sequence[Path],
    word_index: dict[str, int],
    stem_ids: Sequence[int],
) -> tuple[Counter64, Counter64, list[dict[str, object]]]:
    """Count (stem, stem) pairs and (head stem, successor form) pairs in one pass.

    Adjacency and eligibility are the packer's rule exactly as
    ``sim_stem_backoff.count_stem_pairs`` applies it. The second counter keys the head stem id
    with the successor's dictionary id, so aggregating it over one successor stem's forms
    yields that stem pair's successor-form distribution. Returns both counters and one stats
    row per corpus.
    """
    stem_counts = Counter64()
    form_counts = Counter64()
    stride = len(word_index) + 1
    stats: list[dict[str, object]] = []
    for path in paths:
        sentences = 0
        tokens = 0
        tokens_rejected = 0
        form_pairs = 0
        stem_pairs = 0
        started = time.monotonic()
        for sentence in bigram_pack.iter_sentences(path):
            sentences += 1
            previous_form: str | None = None
            previous_sid = 0
            for token in bigram_pack.normalized_tokens(sentence):
                if token is None:
                    tokens_rejected += 1
                    previous_form = None
                    previous_sid = 0
                    continue
                tokens += 1
                index = word_index.get(token)
                if index is None:
                    # Outside the vocabulary: no pair can carry it, on either side.
                    previous_form = None
                    previous_sid = 0
                    continue
                successor_sid = stem_ids[index]
                if previous_form is not None and previous_form != token:
                    form_pairs += 1
                    if previous_sid != successor_sid:
                        stem_counts.bump(previous_sid * stride + successor_sid)
                        form_counts.bump(previous_sid * stride + index + 1)
                        stem_pairs += 1
                previous_form = token
                previous_sid = successor_sid
        stats.append(
            {
                "path": str(path),
                "sentences": sentences,
                "tokens": tokens,
                "tokens_rejected": tokens_rejected,
                "form_pairs": form_pairs,
                "stem_pairs": stem_pairs,
                "seconds": round(time.monotonic() - started, 3),
            }
        )
        print(f"counted {path.name}: {sentences} sentences", file=sys.stderr)
    return stem_counts, form_counts, stats


def build_pair_forms(
    form_counts: Counter64,
    stride: int,
    stem_ids: Sequence[int],
    stem_table: dict[str, tuple[str, ...]],
    word_index: dict[str, int],
    words: Sequence[str],
) -> dict[tuple[str, str], str]:
    """Map each kept (head stem, successor stem) pair to its expansion form.

    The form is the pair's most counted successor form, ties broken code point ascending:
    successor ids enumerate the code-point-sorted dictionary, so the lower id wins the tie.
    """
    kept: set[int] = set()
    for head, successors in stem_table.items():
        head_sid = word_index[head] + 1
        for successor in successors:
            kept.add(head_sid * stride + word_index[successor] + 1)
    best: dict[int, tuple[int, int]] = {}
    for key, count in form_counts.items():
        head_sid = key // stride
        form_index = key % stride - 1
        pair_key = head_sid * stride + stem_ids[form_index]
        if pair_key not in kept:
            continue
        candidate = (count, -form_index)
        if candidate > best.get(pair_key, (0, 0)):
            best[pair_key] = candidate
    pair_forms: dict[tuple[str, str], str] = {}
    for head, successors in stem_table.items():
        head_sid = word_index[head] + 1
        for successor in successors:
            pair_key = head_sid * stride + word_index[successor] + 1
            pair_forms[(head, successor)] = words[-best[pair_key][1]]
    return pair_forms


def completion_costs(
    lines: Sequence[str], chain: suggest_chain.ChainMirror
) -> dict[str, int]:
    """The keystroke simulation's per-word completion-assist cost (arm-independent)."""
    costs: dict[str, int] = {}
    for line in lines:
        for word in line.split(" "):
            if word in costs:
                continue
            cost = len(word)
            for cut in range(1, len(word)):
                if word in chain.prefix_top3(word[:cut]):
                    cost = cut + 1
                    break
            costs[word] = cost
    return costs


def paired_rate_samples(
    series: dict[str, tuple[Sequence[int], Sequence[int]]],
    rounds: int = suggest_chain.BOOTSTRAP_ROUNDS,
    seed: int = suggest_chain.BOOTSTRAP_SEED,
) -> dict[str, list[float]]:
    """Per-round rates of every series over one shared SplitMix64 resample stream.

    ``series`` maps a label to per-sentence (numerator, weight) lists; each round draws
    ``size`` sentence indices and scores every series on the same draw, so the per-round
    rates of any two series stay paired index-by-index. The returned per-label rate lists are
    in round order (unsorted); the caller sorts for nearest ranks or subtracts for deltas.
    """
    labels = list(series)
    arrays = [series[label] for label in labels]
    size = len(arrays[0][0])
    stream = suggest_chain.splitmix64_index_stream(size, seed)
    rates = {label: [] for label in labels}
    for _ in range(rounds):
        numerators = [0] * len(labels)
        weights = [0] * len(labels)
        for _ in range(size):
            drawn = next(stream)
            for at, (nums, ws) in enumerate(arrays):
                numerators[at] += nums[drawn]
                weights[at] += ws[drawn]
        for at, label in enumerate(labels):
            rates[label].append(numerators[at] * 100.0 / weights[at])
    return rates


def ci95(per_round_rates: Sequence[float]) -> list[float]:
    """The harness's nearest-rank CI95 of per-round rates; the input is not mutated."""
    ordered = sorted(per_round_rates)
    low = suggest_chain.nearest_rank_index(25, len(ordered))
    high = suggest_chain.nearest_rank_index(975, len(ordered))
    return [ordered[low], ordered[high]]


def delta_samples(
    rates_a: Sequence[float], rates_b: Sequence[float]
) -> list[float]:
    """Per-round deltas of two paired rate series (b minus a), in round order."""
    return [b - a for a, b in zip(rates_a, rates_b)]


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--eval", dest="eval_path", type=Path, default=suggest_eval.DEFAULT_EVAL)
    parser.add_argument("--dict-asset", type=Path, default=suggest_eval.DEFAULT_DICT_ASSET)
    parser.add_argument("--bigram-asset", type=Path, default=suggest_eval.DEFAULT_BIGRAM_ASSET)
    parser.add_argument(
        "--corpora",
        type=Path,
        nargs="+",
        default=list(DEFAULT_CORPORA),
        help="Leipzig id<TAB>sentence training corpora (the shipped table's mix by default)",
    )
    parser.add_argument("--exceptions", type=Path, default=wordform_gen.DEFAULT_EXCEPTIONS)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    started = time.monotonic()
    try:
        lines = suggest_eval.load_eval_lines(args.eval_path)
        words, frequencies, dictionary_raw = suggest_eval.load_dictionary_words(args.dict_asset)
        successes_by_head = suggest_eval.load_bigram_successes(
            args.bigram_asset, words, dictionary_raw
        )
        exceptions = wordform_gen.load_exceptions(args.exceptions)
        for path in args.corpora:
            if not path.is_file():
                raise suggest_eval.SuggestEvalError(f"corpus is missing: {path}")
    except (
        suggest_eval.SuggestEvalError,
        wordform_gen.WordformError,
        OSError,
    ) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    stage = time.monotonic()
    rows, group_stats = wordform_gen.build_groups(words, exceptions)
    form_to_stem = {form: stem for stem, form, _label in rows}
    print(
        f"grouped {group_stats['grouped_forms']} forms under "
        f"{group_stats['distinct_stems']} stems",
        file=sys.stderr,
    )

    frequency_by_word = dict(zip(words, frequencies))
    word_index = {word: index for index, word in enumerate(words)}
    stem_ids = [word_index[form_to_stem.get(word, word)] + 1 for word in words]
    grouping_seconds = time.monotonic() - stage

    stage = time.monotonic()
    stem_counts, form_counts, corpus_stats = count_stem_and_form_pairs(
        args.corpora, word_index, stem_ids
    )
    counting_seconds = time.monotonic() - stage

    stage = time.monotonic()
    stride = len(words) + 1
    stem_table, kept_pairs = sim_stem_backoff.build_stem_table(stem_counts, stride, words)
    successor_stems = {successor for successors in stem_table.values() for successor in successors}
    cluster_top = sim_stem_backoff.build_expansion(rows, frequency_by_word, successor_stems)
    pair_forms = build_pair_forms(form_counts, stride, stem_ids, stem_table, word_index, words)
    overrides = sum(
        1 for (_head, successor), form in pair_forms.items() if form != cluster_top[successor]
    )

    baseline = suggest_chain.ChainMirror(words, frequencies, successes_by_head)
    arms: dict[str, suggest_chain.ChainMirror] = {
        "baseline": baseline,
        "case_blind": sim_stem_backoff.StemBackoffChain(
            words, frequencies, successes_by_head, form_to_stem, stem_table, cluster_top
        ),
        "case_aware": CaseAwareStemChain(
            words, frequencies, successes_by_head, form_to_stem, stem_table, pair_forms
        ),
    }
    table_seconds = time.monotonic() - stage

    stage = time.monotonic()
    completion_cost = completion_costs(lines, baseline)
    sentence_pairs: list[int] = []
    sentence_base_keys: list[int] = []
    sentence_top3: dict[str, list[int]] = {name: [] for name in arms}
    sentence_top1: dict[str, list[int]] = {name: [] for name in arms}
    sentence_sim_keys: dict[str, list[int]] = {name: [] for name in arms}
    diagnostics: dict[str, dict[str, int]] = {
        name: {
            "engaged_pairs": 0,
            "engaged_with_table": 0,
            "stem_hits": 0,
            "expansion_hits": 0,
            "hits_gained": 0,
            "hits_lost": 0,
        }
        for name in ("case_blind", "case_aware")
    }
    stem_arms = tuple(
        (name, chain) for name, chain in arms.items() if name != "baseline"
    )
    total_pairs = 0
    for line in lines:
        line_words = line.split(" ")
        hits3 = dict.fromkeys(arms, 0)
        hits1 = dict.fromkeys(arms, 0)
        for head, successor in zip(line_words, line_words[1:]):
            total_pairs += 1
            shown_baseline = baseline.predict_top3(head)
            hit_baseline = successor in shown_baseline
            hits3["baseline"] += hit_baseline
            hits1["baseline"] += bool(shown_baseline) and successor == shown_baseline[0]
            for name, chain in stem_arms:
                shown = chain.predict_top3(head)
                hit = successor in shown
                hits3[name] += hit
                hits1[name] += bool(shown) and successor == shown[0]
                diag = diagnostics[name]
                diag["hits_gained"] += hit and not hit_baseline
                diag["hits_lost"] += hit_baseline and not hit
                bigram_cells = len(
                    successes_by_head.get(head, ())[: suggest_chain.BIGRAM_MAX_RESULTS]
                )
                if bigram_cells >= CELL_COUNT:
                    continue
                diag["engaged_pairs"] += 1
                successor_stems_of_head = stem_table.get(form_to_stem.get(head, head))
                if successor_stems_of_head:
                    diag["engaged_with_table"] += 1
                    if form_to_stem.get(successor, successor) in successor_stems_of_head:
                        diag["stem_hits"] += 1
                if successor in chain.stem_additions(head):
                    diag["expansion_hits"] += 1
        sentence_pairs.append(len(line_words) - 1)
        sentence_base_keys.append(sum(len(word) for word in line_words) + len(line_words) - 1)
        for name, chain in arms.items():
            sentence_top3[name].append(hits3[name])
            sentence_top1[name].append(hits1[name])
            simulated = completion_cost[line_words[0]]
            for head, successor in zip(line_words, line_words[1:]):
                simulated += (
                    1 if successor in chain.predict_top3(head) else completion_cost[successor]
                )
            sentence_sim_keys[name].append(simulated)

    series: dict[str, tuple[Sequence[int], Sequence[int]]] = {}
    for name in arms:
        series[f"top3_{name}"] = (sentence_top3[name], sentence_pairs)
        series[f"top1_{name}"] = (sentence_top1[name], sentence_pairs)
        series[f"ks_{name}"] = (
            [base - sim for base, sim in zip(sentence_base_keys, sentence_sim_keys[name])],
            sentence_base_keys,
        )
    rates = paired_rate_samples(series)
    eval_seconds = time.monotonic() - stage

    def rate_pct(name: str) -> float:
        numerators, weights = series[name]
        return suggest_eval.percent(sum(numerators), sum(weights))

    def delta_block(arm: str, metric: str) -> dict[str, object]:
        samples = delta_samples(rates[f"{metric}_baseline"], rates[f"{metric}_{arm}"])
        return {
            "delta_pp": round(rate_pct(f"{metric}_{arm}") - rate_pct(f"{metric}_baseline"), 4),
            "delta_ci95": [round(bound, 4) for bound in ci95(samples)],
        }

    def diag_block(name: str) -> dict[str, object]:
        diag = diagnostics[name]
        engaged = diag["engaged_pairs"]
        stem_hits = diag["stem_hits"]
        expansion_hits = diag["expansion_hits"]
        return {
            "engaged_pairs": engaged,
            "engaged_share_pct": round(suggest_eval.percent(engaged, total_pairs), 4),
            "engaged_with_table": diag["engaged_with_table"],
            "stem_hits": stem_hits,
            "stem_hit_pct_of_engaged": round(suggest_eval.percent(stem_hits, engaged), 4),
            "expansion_hits": expansion_hits,
            "expansion_hit_pct_of_engaged": round(
                suggest_eval.percent(expansion_hits, engaged), 4
            ),
            "expansion_fidelity_pct_of_stem_hits": round(
                suggest_eval.percent(expansion_hits, stem_hits), 4
            ),
            "hits_gained": diag["hits_gained"],
            "hits_lost": diag["hits_lost"],
        }

    aware_top3 = delta_block("case_aware", "top3")
    aware_top1 = delta_block("case_aware", "top1")
    aware_ks = delta_block("case_aware", "ks")
    gate_passed = bool(
        aware_top3["delta_pp"] >= GATE_DELTA_PP
        and aware_top3["delta_ci95"][0] > 0.0
        and aware_top1["delta_ci95"][1] >= 0.0
        and aware_ks["delta_ci95"][1] >= 0.0
    )
    report = {
        "clusters": {
            "dictionary_words": len(words),
            "grouped_forms": group_stats["grouped_forms"],
            "distinct_stems": group_stats["distinct_stems"],
            "ambiguous_forms_skipped": group_stats["ambiguous_skipped"],
        },
        "corpora": corpus_stats,
        "stem_table": {
            "heads": len(stem_table),
            "kept_pairs": kept_pairs,
            "successors_per_head": sim_stem_backoff.SUCCESSES_PER_HEAD,
            "distinct_stem_pairs": len(stem_counts),
            "distinct_successor_stems": len(successor_stems),
            "distinct_head_stem_form_pairs": len(form_counts),
        },
        "pair_forms": {
            "kept_pairs": len(pair_forms),
            "overrides_cluster_top": overrides,
            "overrides_share_pct": round(suggest_eval.percent(overrides, len(pair_forms)), 4),
        },
        "eval": {
            "lines": len(lines),
            "pairs": total_pairs,
            "case_blind": diag_block("case_blind"),
            "case_aware": diag_block("case_aware"),
        },
        "chain": {
            "top3_pct": {name: round(rate_pct(f"top3_{name}"), 4) for name in arms},
            "top3_ci95": {
                name: [round(bound, 4) for bound in ci95(rates[f"top3_{name}"])]
                for name in arms
            },
            "top1_pct": {name: round(rate_pct(f"top1_{name}"), 4) for name in arms},
            "ks_pct": {name: round(rate_pct(f"ks_{name}"), 4) for name in arms},
            "delta_case_blind_vs_baseline": {
                "top3": delta_block("case_blind", "top3"),
                "top1": delta_block("case_blind", "top1"),
                "ks": delta_block("case_blind", "ks"),
            },
            "delta_case_aware_vs_baseline": {
                "top3": aware_top3,
                "top1": aware_top1,
                "ks": aware_ks,
            },
            "delta_case_aware_vs_case_blind": {
                "top3": {
                    "delta_pp": round(
                        rate_pct("top3_case_aware") - rate_pct("top3_case_blind"), 4
                    ),
                    "delta_ci95": [
                        round(bound, 4)
                        for bound in ci95(
                            delta_samples(rates["top3_case_blind"], rates["top3_case_aware"])
                        )
                    ],
                },
            },
        },
        "gate": {
            "arm": "case_aware",
            "delta_threshold_pp": GATE_DELTA_PP,
            "ci_lower_must_exceed": 0.0,
            "no_regression_ci95_upper_bound_at_least": 0.0,
            "no_regression_metrics": ["top1", "ks"],
            "passed": gate_passed,
        },
        "runtime_seconds": {
            "grouping": round(grouping_seconds, 3),
            "counting": round(counting_seconds, 3),
            "table_and_expansions": round(table_seconds, 3),
            "eval_and_bootstrap": round(eval_seconds, 3),
            "total": round(time.monotonic() - started, 3),
        },
    }
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
