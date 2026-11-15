#!/usr/bin/env python3
"""Gate measurement for the extra-corpus ingestion experiment.

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

import evaluation_metrics as metrics  # noqa: E402
import suggest_chain  # noqa: E402
import suggest_eval  # noqa: E402

COVERAGE_GATE_PP = 0.5


def measure_side(dict_asset: Path, bigram_asset: Path, lines: list[str]):
    words, frequencies, raw = suggest_eval.load_dictionary_words(dict_asset)
    successes = suggest_eval.load_bigram_successes(bigram_asset, words, raw)
    chain = suggest_chain.ChainMirror(words, frequencies, successes)
    vocabulary = frozenset(words)

    sentence_tokens: list[int] = []
    sentence_covered: list[int] = []
    sentence_visible_hits: list[int] = []
    sentence_engine_hits: list[int] = []
    sentence_pairs: list[int] = []
    for line in lines:
        line_words = line.split(" ")
        sentence_tokens.append(len(line_words))
        sentence_covered.append(sum(1 for word in line_words if word in vocabulary))
        visible_hits = 0
        engine_hits = 0
        for head, successor in zip(line_words, line_words[1:]):
            candidates = chain.predict_top3(head)
            visible_hits += successor in metrics.visible_top3(candidates)
            engine_hits += successor in metrics.engine_top4(candidates)
        sentence_visible_hits.append(visible_hits)
        sentence_engine_hits.append(engine_hits)
        sentence_pairs.append(len(line_words) - 1)
    return {
        "tokens": sum(sentence_tokens),
        "covered_tokens": sum(sentence_covered),
        "coverage_pct": sum(sentence_covered) * 100.0 / sum(sentence_tokens),
        "chain_pairs": sum(sentence_pairs),
        "sentence_tokens": sentence_tokens,
        "sentence_covered": sentence_covered,
        "sentence_visible_hits": sentence_visible_hits,
        "sentence_engine_hits": sentence_engine_hits,
        "sentence_pairs": sentence_pairs,
    }


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

    visible = metrics.paired_metric_report(
        base["sentence_visible_hits"],
        base["sentence_pairs"],
        cand["sentence_visible_hits"],
        cand["sentence_pairs"],
        k=metrics.VISIBLE_TOP_K,
        role="user",
    )
    engine = metrics.paired_metric_report(
        base["sentence_engine_hits"],
        base["sentence_pairs"],
        cand["sentence_engine_hits"],
        cand["sentence_pairs"],
        k=metrics.ENGINE_TOP_K,
        role="diagnostic",
    )
    coverage_ci = metrics.paired_delta_ci95(
        base["sentence_covered"],
        base["sentence_tokens"],
        cand["sentence_covered"],
        cand["sentence_tokens"],
    )
    coverage_delta = cand["coverage_pct"] - base["coverage_pct"]
    visible_delta = visible["delta"]["pp"]
    delta_lo, delta_hi = visible["delta"]["ci95"]
    gate_coverage = coverage_delta >= COVERAGE_GATE_PP and coverage_ci[0] > 0.0
    gate_chain = delta_lo <= 0.0 <= delta_hi or delta_lo > 0.0

    report = {
        "baseline": {
            "coverage_hits": base["covered_tokens"],
            "coverage_denominator": base["tokens"],
            "coverage_pct": round(base["coverage_pct"], 4),
            "chain_visible_top3_hits": visible["baseline"]["hits"],
            "chain_visible_top3_pct": round(visible["baseline"]["pct"], 4),
            "chain_visible_top3_ci95": [
                round(bound, 4) for bound in visible["baseline"]["ci95"]
            ],
            "chain_engine_top4_hits": engine["baseline"]["hits"],
            "chain_engine_top4_pct": round(engine["baseline"]["pct"], 4),
            "chain_engine_top4_ci95": [
                round(bound, 4) for bound in engine["baseline"]["ci95"]
            ],
            "chain_pairs": base["chain_pairs"],
        },
        "candidate": {
            "coverage_hits": cand["covered_tokens"],
            "coverage_denominator": cand["tokens"],
            "coverage_pct": round(cand["coverage_pct"], 4),
            "chain_visible_top3_hits": visible["candidate"]["hits"],
            "chain_visible_top3_pct": round(visible["candidate"]["pct"], 4),
            "chain_visible_top3_ci95": [
                round(bound, 4) for bound in visible["candidate"]["ci95"]
            ],
            "chain_engine_top4_hits": engine["candidate"]["hits"],
            "chain_engine_top4_pct": round(engine["candidate"]["pct"], 4),
            "chain_engine_top4_ci95": [
                round(bound, 4) for bound in engine["candidate"]["ci95"]
            ],
            "chain_pairs": cand["chain_pairs"],
        },
        "coverage_delta_pp": round(coverage_delta, 4),
        "coverage_delta_ci95": [round(coverage_ci[0], 4), round(coverage_ci[1], 4)],
        "coverage_gate_pp": COVERAGE_GATE_PP,
        "coverage_gate_passed": gate_coverage,
        "chain_top3_delta_pp": round(visible_delta, 4),
        "chain_top3_delta_ci95": [round(delta_lo, 4), round(delta_hi, 4)],
        "chain_visible_top3_delta_pp": round(visible_delta, 4),
        "chain_visible_top3_delta_ci95": [round(delta_lo, 4), round(delta_hi, 4)],
        "chain_engine_top4_delta_pp": round(engine["delta"]["pp"], 4),
        "chain_engine_top4_delta_ci95": [
            round(bound, 4) for bound in engine["delta"]["ci95"]
        ],
        "chain_gate_passed": gate_chain,
        "gate_passed": gate_coverage and gate_chain,
        "measurement": metrics.measurement_report(
            units=len(lines),
            denominator_name="adjacent next-word pairs",
            visible_top3_report=visible,
            engine_top4_report=engine,
        ),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
