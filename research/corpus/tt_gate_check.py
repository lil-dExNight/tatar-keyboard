#!/usr/bin/env python3
"""Pre-registered gate measurement for the extra-corpus ingestion experiment (memo P3).

Compares the committed Tatar assets with a candidate dictionary (built outside the asset
tree by tt_merge_sim.py) plus the bigram table repacked against it from the same training
corpora. Metrics, all from the pinned eval set:

* token coverage (``dict_word_coverage_tokens_pct`` of suggest_eval.py), per side;
* full-chain next-word top-3 hit rate, per side, with the sentence-level bootstrap CI95;
* the paired bootstrap CI95 of the per-round rate difference: both arms draw the same
  sentence resamples from the shared SplitMix64 stream, so the difference CI is paired.

Gate: coverage improves by at least +0.5 percentage points, and the chain top-3 difference
CI95 does not lie entirely below zero.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import suggest_chain  # noqa: E402
import suggest_eval  # noqa: E402

COVERAGE_GATE_PP = 0.5


def measure_side(dict_asset: Path, bigram_asset: Path, lines: list[str]):
    words, frequencies, raw = suggest_eval.load_dictionary_words(dict_asset)
    successes = suggest_eval.load_bigram_successes(bigram_asset, words, raw)
    chain = suggest_chain.ChainMirror(words, frequencies, successes)
    vocabulary = frozenset(words)

    tokens = 0
    covered = 0
    sentence_hits: list[int] = []
    sentence_pairs: list[int] = []
    for line in lines:
        line_words = line.split(" ")
        tokens += len(line_words)
        covered += sum(1 for word in line_words if word in vocabulary)
        hits = 0
        for head, successor in zip(line_words, line_words[1:]):
            if successor in chain.predict_top3(head):
                hits += 1
        sentence_hits.append(hits)
        sentence_pairs.append(len(line_words) - 1)
    return {
        "tokens": tokens,
        "covered_tokens": covered,
        "coverage_pct": covered * 100.0 / tokens,
        "chain_top3_hits": sum(sentence_hits),
        "chain_pairs": sum(sentence_pairs),
        "sentence_hits": sentence_hits,
        "sentence_pairs": sentence_pairs,
    }


def paired_delta_ci95(base: dict, cand: dict) -> tuple[float, float]:
    """CI95 of the chain top-3 rate difference (candidate minus baseline), paired."""
    size = len(base["sentence_hits"])
    stream = suggest_chain.splitmix64_index_stream(size)
    deltas: list[float] = []
    for _round in range(suggest_chain.BOOTSTRAP_ROUNDS):
        base_hits = base_weights = cand_hits = 0
        for _ in range(size):
            drawn = next(stream)
            base_hits += base["sentence_hits"][drawn]
            base_weights += base["sentence_pairs"][drawn]
            cand_hits += cand["sentence_hits"][drawn]
        deltas.append((cand_hits - base_hits) * 100.0 / base_weights)
    deltas.sort()
    rounds = suggest_chain.BOOTSTRAP_ROUNDS
    return (
        deltas[suggest_chain.nearest_rank_index(25, rounds)],
        deltas[suggest_chain.nearest_rank_index(975, rounds)],
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--baseline-dict", type=Path,
                        default=suggest_eval.DEFAULT_DICT_ASSET)
    parser.add_argument("--baseline-bigram", type=Path,
                        default=suggest_eval.DEFAULT_BIGRAM_ASSET)
    parser.add_argument("--candidate-dict", type=Path, required=True)
    parser.add_argument("--candidate-bigram", type=Path, required=True)
    parser.add_argument("--eval", dest="eval_path", type=Path,
                        default=suggest_eval.DEFAULT_EVAL)
    args = parser.parse_args()

    lines = suggest_eval.load_eval_lines(args.eval_path)
    base = measure_side(args.baseline_dict, args.baseline_bigram, lines)
    cand = measure_side(args.candidate_dict, args.candidate_bigram, lines)

    base_rate = base["chain_top3_hits"] * 100.0 / base["chain_pairs"]
    cand_rate = cand["chain_top3_hits"] * 100.0 / cand["chain_pairs"]
    base_ci = suggest_chain.bootstrap_rate_ci95(base["sentence_hits"], base["sentence_pairs"])
    cand_ci = suggest_chain.bootstrap_rate_ci95(cand["sentence_hits"], cand["sentence_pairs"])
    delta_lo, delta_hi = paired_delta_ci95(base, cand)

    coverage_delta = cand["coverage_pct"] - base["coverage_pct"]
    chain_delta = cand_rate - base_rate
    gate_coverage = coverage_delta >= COVERAGE_GATE_PP
    gate_chain = delta_lo <= 0.0 <= delta_hi or delta_lo > 0.0

    report = {
        "baseline": {
            "coverage_pct": round(base["coverage_pct"], 4),
            "chain_top3_pct": round(base_rate, 4),
            "chain_top3_ci95": [round(base_ci[0], 4), round(base_ci[1], 4)],
        },
        "candidate": {
            "coverage_pct": round(cand["coverage_pct"], 4),
            "chain_top3_pct": round(cand_rate, 4),
            "chain_top3_ci95": [round(cand_ci[0], 4), round(cand_ci[1], 4)],
        },
        "coverage_delta_pp": round(coverage_delta, 4),
        "coverage_gate_pp": COVERAGE_GATE_PP,
        "coverage_gate_passed": gate_coverage,
        "chain_top3_delta_pp": round(chain_delta, 4),
        "chain_top3_delta_ci95": [round(delta_lo, 4), round(delta_hi, 4)],
        "chain_gate_passed": gate_chain,
        "gate_passed": gate_coverage and gate_chain,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
