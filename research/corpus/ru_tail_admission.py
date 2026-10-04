#!/usr/bin/env python3
"""Russian dictionary tail admission: candidate pool, candidate asset, paired gate measurement.

The follow-up to the rejected Taiga rerank (research/prediction-engine.md): instead of
re-ranking inside the fixed composition, grow the composition by admitting words that are
attested in the training corpora but absent from the shipped dictionary.

The candidate pool is counted over exactly the four corpora the Russian bigram table trains
on (the manifest-pinned thinned conv stream and the three Leipzig sentence files): the pinned
eval set is decontaminated against precisely these corpora by normalized sentence content, so
a word admitted from them carries no eval leakage. The acceptance discipline is the accept
queue's: shared normalization under the Russian alphabet, the filters.py proper-noun,
profanity and corroboration rules, and the dict_accept fragment rule. Words already in the
composition (the 1.8.4 baseline plus the accepted queue) are not pool words: they enter a
grown composition through the merged ranking with their existing pipeline frequencies.

Subcommands: ``pool`` (count the corpora and write the extra-entries TSV plus a JSON report),
``build`` (pack a candidate dictionary asset OUTSIDE the asset tree through the dict_accept
merge), ``gate`` (the paired before/after measurement over the pinned Russian eval set:
coverage, chain top-1 and chain top-3, each delta with a sentence-level paired bootstrap
CI95). The candidate bigram table is repacked against the candidate dictionary with the
existing CLI: ``python3 scripts/bigram_asset_pack.py pack --train <four corpora> --asset
<candidate.tdict.zlib> --heads 10000 --successes-per-head 4 --schema 3 --language rus
--out-raw ... --out-compressed ... --report ...``. stdlib only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(SCRIPT_DIR))

import corpuslib as CL  # noqa: E402
import dict_accept  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack as dp  # noqa: E402
import filters as F  # noqa: E402
import rebuild_assets  # noqa: E402
import suggest_chain  # noqa: E402
import suggest_eval  # noqa: E402
from tt_gate_check import COVERAGE_GATE_PP  # noqa: E402

RUSSIAN_TRAIN = tuple(
    bigram.train for bigram in rebuild_assets.BIGRAMS if bigram.tag == "rus"
)[0]

POOL_TSV_HEAD = """\
# Russian tail-admission pool: word<TAB>frequency, admitted candidates only.
# One pass over the four Russian bigram-training corpora (data/corpus-manifest.json pins,
# verified before counting) with the shared dict_tokens rule: NFC, lowercase, 33-letter
# Russian alphabet, hugging punctuation stripped. Discipline of the accept queue:
# proper nouns removed by capitalization evidence, profanity roots dropped, a word must
# occur at least three times, and formal fragments (shorter than four letters or without
# a vowel) are rejected. Words already in the composition (the 1.8.4 baseline plus the
# accepted queue) are not listed: a grown composition re-admits them with their pipeline
# frequencies. The frequency is the merged count over the four corpora.
# word<TAB>train_freq
"""


def build_pool(args) -> int:
    """Count the four pinned corpora and write the extra-entries TSV plus a JSON report."""
    manifest = rebuild_assets.load_corpus_manifest(ROOT / rebuild_assets.CORPUS_MANIFEST)
    verdicts = rebuild_assets.verify_corpus(args.corpus_dir, manifest, RUSSIAN_TRAIN)
    bad = {name: verdict for name, verdict in verdicts.items() if verdict != "ok"}
    if bad:
        print(f"error: корпуса не совпали с манифестом: {bad}", file=sys.stderr)
        return 2

    language = coverage.language_for("rus")
    shipped, _asset = dict_accept.load_baseline("rus", args.baseline)
    composition = set(shipped) | set(dict_accept.read_accepted("rus"))

    freq: Counter = Counter()
    evidence = F.CaseEvidence()
    per_source: dict[str, int] = {}
    for name in RUSSIAN_TRAIN:
        path = args.corpus_dir / name
        tokens = 0
        with path.open("r", encoding="utf-8") as handle:
            for line in handle:
                _row_id, tab, content = line.partition("\t")
                if not tab:
                    continue
                first = True
                for chunk in content.split():
                    surface = chunk.strip(CL._EDGE)
                    if not surface:
                        continue
                    word, _reason = coverage.normalize_word(surface, language.alphabet)
                    if word is not None:
                        evidence.observe(surface, word, first)
                        freq[word] += 1
                        tokens += 1
                    first = False
        per_source[name] = tokens

    candidates = {word: count for word, count in freq.items() if word not in composition}
    kept, removed = F.apply_filters(Counter(candidates), evidence, "rus")
    pool = {word: kept[word] for word in kept
            if not dict_accept.fragment_reason(word, "rus")}

    out = args.output
    out.parent.mkdir(parents=True, exist_ok=True)
    data = POOL_TSV_HEAD + "".join(
        f"{word}\t{pool[word]}\n" for word in sorted(pool)
    )
    out.write_text(data, encoding="utf-8", newline="\n")
    report = {
        "corpora": per_source,
        "composition_words": len(composition),
        "candidate_types": len(candidates),
        "removed_proper_noun_types": len(removed["proper_noun"]),
        "removed_profanity_types": len(removed["profanity"]),
        "removed_below_min_freq_types": len(removed["below_min_freq"]),
        "removed_fragment_types": len(kept) - len(pool),
        "pool_words": len(pool),
        "pool_tokens": sum(pool.values()),
        "output": str(out),
        "output_bytes": len(data.encode("utf-8")),
        "output_sha256": hashlib.sha256(data.encode("utf-8")).hexdigest(),
    }
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def build(args) -> int:
    """Pack the candidate dictionary asset outside the asset tree through the dict_accept merge."""
    extra = dict_accept.read_extra_entries(args.pool, "rus")
    shipped, accepted, entries = dict_accept.merged_entries(
        "rus", args.baseline, top=args.top, extra=extra)
    words = {word for word, _f in entries}
    raw = dp.serialize_entries(entries, schema=dp.SCHEMA_ID_V2)
    asset = dp.compress_raw(raw)
    dp.validate_asset(asset, language=coverage.RUSSIAN)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(asset)
    report = {
        "entries": len(entries),
        "accepted_that_entered": len((words & set(accepted)) - set(shipped)),
        "shipped_words_displaced": len(set(shipped) - words),
        "extra_entries_offered": len(extra),
        "extra_entries_that_entered": len(words & set(extra)),
        "asset": str(args.output),
        "asset_bytes": len(asset),
        "asset_sha256": hashlib.sha256(asset).hexdigest(),
        "raw_bytes": len(raw),
        "raw_sha256": hashlib.sha256(raw).hexdigest(),
        "fits_compressed": len(asset) <= dp.MAX_COMPRESSED_BYTES_V2,
        "fits_raw": len(raw) <= dp.MAX_UNCOMPRESSED_BYTES_V2,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if report["fits_compressed"] and report["fits_raw"] else 1


# --- gate -------------------------------------------------------------------------------------


def measure_side(dict_asset: Path, bigram_asset: Path, lines: list[str]) -> dict:
    """Per-sentence coverage and chain counters of one side; the Russian chain has no
    suffix rules."""
    words, frequencies, raw = suggest_eval.load_dictionary_words(dict_asset)
    successes = suggest_eval.load_bigram_successes(bigram_asset, words, raw)
    chain = suggest_chain.ChainMirror(words, frequencies, successes, suffix_rules=False)
    vocabulary = frozenset(words)

    sentence_tokens: list[int] = []
    sentence_covered: list[int] = []
    sentence_hits1: list[int] = []
    sentence_hits3: list[int] = []
    sentence_pairs: list[int] = []
    for line in lines:
        line_words = line.split(" ")
        sentence_tokens.append(len(line_words))
        sentence_covered.append(sum(1 for word in line_words if word in vocabulary))
        hits1 = 0
        hits3 = 0
        for head, successor in zip(line_words, line_words[1:]):
            shown = chain.predict_top3(head)
            if shown and successor == shown[0]:
                hits1 += 1
            if successor in shown:
                hits3 += 1
        sentence_hits1.append(hits1)
        sentence_hits3.append(hits3)
        sentence_pairs.append(len(line_words) - 1)
    return {
        "sentence_tokens": sentence_tokens,
        "sentence_covered": sentence_covered,
        "sentence_hits1": sentence_hits1,
        "sentence_hits3": sentence_hits3,
        "sentence_pairs": sentence_pairs,
        "coverage_pct": sum(sentence_covered) * 100.0 / sum(sentence_tokens),
        "chain_top1_pct": sum(sentence_hits1) * 100.0 / sum(sentence_pairs),
        "chain_top3_pct": sum(sentence_hits3) * 100.0 / sum(sentence_pairs),
    }


def paired_delta_ci95(
    base_hits: list[int], base_weights: list[int],
    cand_hits: list[int], cand_weights: list[int],
) -> tuple[float, float]:
    """Paired sentence-level bootstrap CI95 of a rate difference, candidate minus baseline.

    Both arms draw the same sentence resamples from the shared SplitMix64 stream, like
    tt_gate_check.paired_delta_ci95; the weights of the two arms may differ (the chain
    rate has equal weights by construction, the coverage rate does not).
    """
    size = len(base_hits)
    stream = suggest_chain.splitmix64_index_stream(size)
    deltas: list[float] = []
    for _round in range(suggest_chain.BOOTSTRAP_ROUNDS):
        base_h = base_w = cand_h = cand_w = 0
        for _ in range(size):
            drawn = next(stream)
            base_h += base_hits[drawn]
            base_w += base_weights[drawn]
            cand_h += cand_hits[drawn]
            cand_w += cand_weights[drawn]
        deltas.append((cand_h * 100.0 / cand_w) - (base_h * 100.0 / base_w))
    deltas.sort()
    rounds = suggest_chain.BOOTSTRAP_ROUNDS
    return (
        deltas[suggest_chain.nearest_rank_index(25, rounds)],
        deltas[suggest_chain.nearest_rank_index(975, rounds)],
    )


def gate(args) -> int:
    """The paired before/after measurement over the pinned Russian eval set."""
    lines = suggest_eval.load_eval_lines(args.eval_path, coverage.RUSSIAN.alphabet)
    base = measure_side(args.baseline_dict, args.baseline_bigram, lines)
    cand = measure_side(args.candidate_dict, args.candidate_bigram, lines)

    coverage_ci = paired_delta_ci95(
        base["sentence_covered"], base["sentence_tokens"],
        cand["sentence_covered"], cand["sentence_tokens"])
    top1_ci = paired_delta_ci95(
        base["sentence_hits1"], base["sentence_pairs"],
        cand["sentence_hits1"], cand["sentence_pairs"])
    top3_ci = paired_delta_ci95(
        base["sentence_hits3"], base["sentence_pairs"],
        cand["sentence_hits3"], cand["sentence_pairs"])

    coverage_delta = cand["coverage_pct"] - base["coverage_pct"]
    gate_coverage = coverage_delta >= COVERAGE_GATE_PP and coverage_ci[0] > 0.0
    chain_ok = top3_ci[0] <= 0.0 <= top3_ci[1] or top3_ci[0] > 0.0
    report = {
        "baseline": {
            "coverage_pct": round(base["coverage_pct"], 4),
            "chain_top1_pct": round(base["chain_top1_pct"], 4),
            "chain_top3_pct": round(base["chain_top3_pct"], 4),
        },
        "candidate": {
            "coverage_pct": round(cand["coverage_pct"], 4),
            "chain_top1_pct": round(cand["chain_top1_pct"], 4),
            "chain_top3_pct": round(cand["chain_top3_pct"], 4),
        },
        "coverage_delta_pp": round(coverage_delta, 4),
        "coverage_delta_ci95": [round(coverage_ci[0], 4), round(coverage_ci[1], 4)],
        "coverage_gate_pp": COVERAGE_GATE_PP,
        "coverage_gate_passed": gate_coverage,
        "chain_top1_delta_pp": round(cand["chain_top1_pct"] - base["chain_top1_pct"], 4),
        "chain_top1_delta_ci95": [round(top1_ci[0], 4), round(top1_ci[1], 4)],
        "chain_top3_delta_pp": round(cand["chain_top3_pct"] - base["chain_top3_pct"], 4),
        "chain_top3_delta_ci95": [round(top3_ci[0], 4), round(top3_ci[1], 4)],
        "chain_gate_passed": chain_ok,
        "gate_passed": gate_coverage and chain_ok,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    pool_parser = sub.add_parser("pool", help="count the corpora and write the pool TSV")
    pool_parser.add_argument("--corpus-dir", type=Path,
                             default=rebuild_assets.DEFAULT_CORPUS_DIR)
    pool_parser.add_argument("--baseline", type=Path, required=True,
                             help="directory with the 1.8.4 dictionary assets")
    pool_parser.add_argument("--output", type=Path, required=True)
    pool_parser.add_argument("--report", type=Path, required=True)
    pool_parser.set_defaults(func=build_pool)

    build_parser = sub.add_parser("build", help="pack a candidate dictionary asset")
    build_parser.add_argument("--baseline", type=Path, required=True)
    build_parser.add_argument("--pool", type=Path, required=True)
    build_parser.add_argument("--top", type=int, default=100_000)
    build_parser.add_argument("--output", type=Path, required=True)
    build_parser.set_defaults(func=build)

    gate_parser = sub.add_parser("gate", help="paired before/after measurement")
    gate_parser.add_argument("--baseline-dict", type=Path,
                             default=suggest_eval.RU_DICT_ASSET)
    gate_parser.add_argument("--baseline-bigram", type=Path,
                             default=suggest_eval.RU_BIGRAM_ASSET)
    gate_parser.add_argument("--candidate-dict", type=Path, required=True)
    gate_parser.add_argument("--candidate-bigram", type=Path, required=True)
    gate_parser.add_argument("--eval", dest="eval_path", type=Path,
                             default=suggest_eval.RU_EVAL)
    gate_parser.set_defaults(func=gate)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
