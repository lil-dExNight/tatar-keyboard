#!/usr/bin/env python3
"""Paired chain-metric comparison of two bigram tables over the pinned eval set.

TEST MEASUREMENT ONLY -- no asset under app/src/main/assets is written or replaced.

Both tables are decoded against the shipped dictionary of ``--language`` (each must link to
it); two ``suggest_chain.ChainMirror`` instances replay the eval set, and the per-sentence
chain top-3 and top-1 hit counts of the variant arm (``--table-b``) are compared with the
baseline arm (``--table-a``) by the paired bootstrap: one SplitMix64 stream (the harness
seed and round count) resamples sentence indices for both arms on the same draw, so the
per-round delta is exactly paired.

Output: one JSON report on stdout. Exit 2 on any missing or invalid input, like the eval
driver.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

import bigram_asset_pack  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import suggest_chain  # noqa: E402
import suggest_eval  # noqa: E402

DICT_ASSETS = {"tat": suggest_eval.DEFAULT_DICT_ASSET, "rus": suggest_eval.RU_DICT_ASSET}
EVAL_PATHS = {"tat": suggest_eval.DEFAULT_EVAL, "rus": suggest_eval.RU_EVAL}


def load_table(
    path: Path, dictionary_words: list[str], dictionary_raw: bytes
) -> dict[str, list[str]]:
    if not path.is_file():
        raise suggest_eval.SuggestEvalError(f"bigram asset is missing: {path}")
    raw = bigram_asset_pack.decompress(path.read_bytes())
    parsed = bigram_asset_pack.validate_raw_v3(
        raw, dictionary_words, hashlib.sha256(dictionary_raw).digest()
    )
    return parsed.successes_by_head


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


def paired_bootstrap(
    hits_a: Sequence[int],
    hits_b: Sequence[int],
    weights: Sequence[int],
    rounds: int = suggest_chain.BOOTSTRAP_ROUNDS,
    seed: int = suggest_chain.BOOTSTRAP_SEED,
) -> list[float]:
    """CI95 of the per-round rate delta (B − A) over one shared resample stream."""
    size = len(weights)
    stream = suggest_chain.splitmix64_index_stream(size, seed)
    deltas: list[float] = []
    for _ in range(rounds):
        hits_drawn_a = 0
        hits_drawn_b = 0
        weight = 0
        for _ in range(size):
            drawn = next(stream)
            hits_drawn_a += hits_a[drawn]
            hits_drawn_b += hits_b[drawn]
            weight += weights[drawn]
        deltas.append((hits_drawn_b - hits_drawn_a) * 100.0 / weight)
    deltas.sort()
    return [
        deltas[suggest_chain.nearest_rank_index(25, rounds)],
        deltas[suggest_chain.nearest_rank_index(975, rounds)],
    ]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--language", choices=("tat", "rus"), default="tat")
    parser.add_argument("--table-a", type=Path, required=True, help="baseline .tatbigr.zlib")
    parser.add_argument("--table-b", type=Path, required=True, help="variant .tatbigr.zlib")
    parser.add_argument("--dict-asset", type=Path, default=None)
    parser.add_argument("--eval", dest="eval_path", type=Path, default=None)
    args = parser.parse_args(argv)

    dict_asset = args.dict_asset or DICT_ASSETS[args.language]
    eval_path = args.eval_path or EVAL_PATHS[args.language]
    language = coverage.language_for(args.language)
    try:
        words, frequencies, raw = suggest_eval.load_dictionary_words(dict_asset)
        table_a = load_table(args.table_a, words, raw)
        table_b = load_table(args.table_b, words, raw)
        lines = suggest_eval.load_eval_lines(eval_path, language.alphabet)
    except (
        suggest_eval.SuggestEvalError,
        bigram_asset_pack.BigramFormatError,
        bigram_asset_pack.BigramBudgetError,
        OSError,
    ) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    suffix_rules = args.language == "tat"
    chain_a = suggest_chain.ChainMirror(words, frequencies, table_a, suffix_rules=suffix_rules)
    chain_b = suggest_chain.ChainMirror(words, frequencies, table_b, suffix_rules=suffix_rules)
    hits_a3, hits_a1, weights = sentence_hits(lines, chain_a)
    hits_b3, hits_b1, _ = sentence_hits(lines, chain_b)
    pairs = sum(weights)
    top3_a, top3_b = sum(hits_a3), sum(hits_b3)
    top1_a, top1_b = sum(hits_a1), sum(hits_b1)
    report = {
        "language": args.language,
        "pairs": pairs,
        "heads_a": len(table_a),
        "heads_b": len(table_b),
        "top3_hits_a": top3_a,
        "top3_hits_b": top3_b,
        "top3_pct_a": round(top3_a * 100.0 / pairs, 4),
        "top3_pct_b": round(top3_b * 100.0 / pairs, 4),
        "delta_top3_pp": round((top3_b - top3_a) * 100.0 / pairs, 4),
        "top3_delta_ci95": [round(b, 4) for b in paired_bootstrap(hits_a3, hits_b3, weights)],
        "top1_hits_a": top1_a,
        "top1_hits_b": top1_b,
        "delta_top1_pp": round((top1_b - top1_a) * 100.0 / pairs, 4),
        "top1_delta_ci95": [round(b, 4) for b in paired_bootstrap(hits_a1, hits_b1, weights)],
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
