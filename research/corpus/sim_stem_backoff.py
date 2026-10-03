#!/usr/bin/env python3
"""Offline simulation of a stem-keyed bigram backoff stage for the next-word chain.

TEST MEASUREMENT ONLY -- no asset under app/src/main/assets is written or replaced.

The simulated runtime rule inserts one stage into the NEXT_WORD chain mirrored by
``suggest_chain.ChainMirror``: when the form-keyed bigram pass fills fewer than the strip's
three cells, the stem of the committed word is looked up in a stem-keyed bigram table and each
stem successor is expanded to its most frequent attested form (the dictionary frequency list
is the attestation source). The stage sits between the bigram pass and the after-word forms
pass; everything else in the chain is unchanged.

Pipeline of the simulation:

  1. The shipped dictionary is lemmatized into stem clusters with the build-time form
     generator's ``group`` mode (``wordform_gen.build_groups``): a form maps to a stem only
     when stripping a generated suffix reaches another listed word and the generator
     round-trips the form; ambiguous forms stay ungrouped and map to themselves. The mapping
     is one level, not transitive-closed: a stem that is itself an inflected form of another
     listed word keeps its own cluster.
  2. A stem-keyed bigram table is counted over the same three training corpora as the
     shipped table. Pair eligibility mirrors the bigram packer verbatim (whitespace split,
     a token rejected by normalization breaks adjacency, both ends must be dictionary words,
     form self-pairs dropped); each end is then mapped to its stem, counts are summed over
     forms, and stem self-pairs are dropped (their expansion could only propose another form
     of the head's own cluster, which is the after-word forms pass's job). Each stem head
     keeps its top-4 successors, count descending then code point ascending, like the
     shipped table.
  3. Both chains replay the eval set; hits are tallied per sentence so the paired bootstrap
     resamples identical sentence indices for both arms (one SplitMix64 stream, the harness
     seed and round count) and reports the CI95 of the delta, alongside per-arm rates.

The known weakness of the rule is that a stem successor carries no case: the expansion always
proposes the cluster's most frequent form. Three diagnostics separate the effect: the share of
engaged pairs whose successor stem appears in the table (stem hit rate), the share where the
expanded form equals the actual next word and lands on the strip (expansion hit rate), and
their ratio (expansion fidelity).

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
import suggest_chain  # noqa: E402
import suggest_eval  # noqa: E402
import wordform_gen  # noqa: E402
from bigset import Counter64  # noqa: E402

# The shipped Tatar table's per-head successor count: the strip shows three, the table keeps
# four.
SUCCESSES_PER_HEAD = 4
CELL_COUNT = suggest_chain.CELL_COUNT

# The pre-registered acceptance bar of the experiment: the simulated composite-chain top-3
# delta must reach +1.5 percentage points with the paired CI95 lower bound above zero.
GATE_DELTA_PP = 1.5

DEFAULT_CORPORA = (
    Path.home() / "corpora-leipzig/tat_mixed_2015_1M/tat_mixed_2015_1M-sentences.txt",
    Path.home() / "corpora-leipzig/tat_web_2018_1M/tat_web_2018_1M-sentences.txt",
    Path.home() / "corpora-leipzig/tt_conv_train90-sentences.txt",
)


class StemBackoffChain(suggest_chain.ChainMirror):
    """The chain mirror with the simulated stem-keyed backoff stage added.

    Between the form-keyed bigram pass and the after-word forms pass: when the bigram pass
    fills fewer cells than the strip has, the successors of the head's stem are expanded to
    their clusters' most frequent attested forms and appended, skipping the head itself and
    anything already shown, until the strip is full or the successors run out.
    """

    def __init__(
        self,
        words: Sequence[str],
        frequencies: Sequence[int],
        successes_by_head: dict[str, list[str]],
        form_to_stem: dict[str, str],
        stem_table: dict[str, tuple[str, ...]],
        expansion: dict[str, str],
    ) -> None:
        super().__init__(words, frequencies, successes_by_head)
        self._form_to_stem = form_to_stem
        self._stem_table = stem_table
        self._expansion = expansion
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
                form = self._expansion[successor_stem]
                if form == head or form in shown:
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


def count_stem_pairs(
    paths: Sequence[Path],
    word_index: dict[str, int],
    stem_ids: Sequence[int],
) -> tuple[Counter64, list[dict[str, object]]]:
    """Count (stem, stem) pairs over the corpora under the packer's adjacency rule.

    ``stem_ids`` maps a dictionary word index to its stem's id (stem dictionary index + 1; id
    0 is reserved so a packed key is never zero). Returns the packed counts and one stats row
    per corpus.
    """
    counts = Counter64()
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
                        counts.bump(previous_sid * stride + successor_sid)
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
    return counts, stats


def build_stem_table(
    counts: Counter64, stride: int, words: Sequence[str]
) -> tuple[dict[str, tuple[str, ...]], int]:
    """Cut the packed counts to the top successors per stem head, in packing order.

    Successor ids enumerate the code-point-sorted dictionary, so ordering by
    (-count, successor id) is the packer's count-descending, code-point-ascending order.
    """
    best: dict[int, list[tuple[int, int]]] = {}
    for key, count in counts.items():
        head = key // stride
        successor = key % stride
        row = best.get(head)
        if row is None:
            best[head] = [(count, successor)]
            continue
        row.append((count, successor))
        if len(row) > 4 * SUCCESSES_PER_HEAD:
            row.sort(key=lambda item: (-item[0], item[1]))
            del row[SUCCESSES_PER_HEAD:]
    table: dict[str, tuple[str, ...]] = {}
    kept_pairs = 0
    for head, row in best.items():
        row.sort(key=lambda item: (-item[0], item[1]))
        kept = row[:SUCCESSES_PER_HEAD]
        table[words[head - 1]] = tuple(words[successor - 1] for _, successor in kept)
        kept_pairs += len(kept)
    return table, kept_pairs


def build_expansion(
    rows: Sequence[tuple[str, str, str]],
    frequencies: dict[str, int],
    needed_stems: set[str],
) -> dict[str, str]:
    """Map each stem to its cluster's most frequent form (ties: code point ascending).

    A cluster is the stem itself plus the forms grouped under it; every member is a
    dictionary word, so the frequency list attests all of them.
    """
    forms_by_stem: dict[str, list[str]] = {}
    for stem, form, _label in rows:
        if stem in needed_stems:
            forms_by_stem.setdefault(stem, []).append(form)
    expansion: dict[str, str] = {}
    for stem in needed_stems:
        candidates = [stem] + forms_by_stem.get(stem, [])
        expansion[stem] = min(candidates, key=lambda word: (-frequencies[word], word))
    return expansion


def paired_bootstrap(
    hits_baseline: Sequence[int],
    hits_stem: Sequence[int],
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
    rates_stem: list[float] = []
    deltas: list[float] = []
    for _ in range(rounds):
        hits_a = 0
        hits_b = 0
        weight = 0
        for _ in range(size):
            drawn = next(stream)
            hits_a += hits_baseline[drawn]
            hits_b += hits_stem[drawn]
            weight += weights[drawn]
        rate_a = hits_a * 100.0 / weight
        rate_b = hits_b * 100.0 / weight
        rates_baseline.append(rate_a)
        rates_stem.append(rate_b)
        deltas.append(rate_b - rate_a)
    for rates in (rates_baseline, rates_stem, deltas):
        rates.sort()
    low = suggest_chain.nearest_rank_index(25, rounds)
    high = suggest_chain.nearest_rank_index(975, rounds)
    return {
        "baseline_ci95": [rates_baseline[low], rates_baseline[high]],
        "stem_ci95": [rates_stem[low], rates_stem[high]],
        "delta_ci95": [deltas[low], deltas[high]],
    }


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
        help="Leipzig id<TAB>sentence training corpora (the shipped table's three by default)",
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
    counts, corpus_stats = count_stem_pairs(args.corpora, word_index, stem_ids)
    counting_seconds = time.monotonic() - stage

    stage = time.monotonic()
    stem_table, kept_pairs = build_stem_table(counts, len(words) + 1, words)
    successor_stems = {successor for successors in stem_table.values() for successor in successors}
    expansion = build_expansion(rows, frequency_by_word, successor_stems)

    baseline = suggest_chain.ChainMirror(words, frequencies, successes_by_head)
    stem_chain = StemBackoffChain(
        words, frequencies, successes_by_head, form_to_stem, stem_table, expansion
    )
    table_seconds = time.monotonic() - stage

    stage = time.monotonic()
    sentence_hits_baseline: list[int] = []
    sentence_hits_stem: list[int] = []
    sentence_pairs: list[int] = []
    diagnostics = {
        "pairs": 0,
        "engaged_pairs": 0,
        "engaged_with_table": 0,
        "stem_hits": 0,
        "expansion_hits": 0,
        "hits_gained": 0,
        "hits_lost": 0,
    }
    for line in lines:
        line_words = line.split(" ")
        hits_baseline = 0
        hits_stem = 0
        for head, successor in zip(line_words, line_words[1:]):
            hit_baseline = successor in baseline.predict_top3(head)
            hit_stem = successor in stem_chain.predict_top3(head)
            hits_baseline += hit_baseline
            hits_stem += hit_stem
            diagnostics["pairs"] += 1
            diagnostics["hits_gained"] += hit_stem and not hit_baseline
            diagnostics["hits_lost"] += hit_baseline and not hit_stem
            bigram_cells = len(
                successes_by_head.get(head, ())[: suggest_chain.BIGRAM_MAX_RESULTS]
            )
            if bigram_cells >= CELL_COUNT:
                continue
            diagnostics["engaged_pairs"] += 1
            successor_stems_of_head = stem_table.get(form_to_stem.get(head, head))
            if successor_stems_of_head:
                diagnostics["engaged_with_table"] += 1
                if form_to_stem.get(successor, successor) in successor_stems_of_head:
                    diagnostics["stem_hits"] += 1
            if successor in stem_chain.stem_additions(head):
                diagnostics["expansion_hits"] += 1
        sentence_hits_baseline.append(hits_baseline)
        sentence_hits_stem.append(hits_stem)
        sentence_pairs.append(len(line_words) - 1)

    total_pairs = diagnostics["pairs"]
    engaged = diagnostics["engaged_pairs"]
    baseline_pct = suggest_eval.percent(sum(sentence_hits_baseline), total_pairs)
    stem_pct = suggest_eval.percent(sum(sentence_hits_stem), total_pairs)
    intervals = paired_bootstrap(sentence_hits_baseline, sentence_hits_stem, sentence_pairs)
    delta_pp = stem_pct - baseline_pct
    eval_seconds = time.monotonic() - stage

    stem_hits = diagnostics["stem_hits"]
    expansion_hits = diagnostics["expansion_hits"]
    report = {
        "clusters": {
            "dictionary_words": len(words),
            "grouped_forms": group_stats["grouped_forms"],
            "distinct_stems": group_stats["distinct_stems"],
            "ambiguous_forms_skipped": group_stats["ambiguous_skipped"],
            "stems_that_are_grouped_forms": len(
                {stem for stem, _form, _label in rows} & set(form_to_stem)
            ),
            "distinct_stem_values": len(
                set(words) - set(form_to_stem) | {stem for stem, _form, _label in rows}
            ),
        },
        "corpora": corpus_stats,
        "stem_table": {
            "heads": len(stem_table),
            "kept_pairs": kept_pairs,
            "successors_per_head": SUCCESSES_PER_HEAD,
            "distinct_stem_pairs": len(counts),
            "distinct_successor_stems": len(successor_stems),
        },
        "eval": {
            "lines": len(lines),
            "pairs": total_pairs,
            "engaged_pairs": engaged,
            "engaged_share_pct": round(suggest_eval.percent(engaged, total_pairs), 4),
            "engaged_with_table": diagnostics["engaged_with_table"],
            "stem_hits": stem_hits,
            "stem_hit_pct_of_engaged": round(suggest_eval.percent(stem_hits, engaged), 4),
            "expansion_hits": expansion_hits,
            "expansion_hit_pct_of_engaged": round(
                suggest_eval.percent(expansion_hits, engaged), 4
            ),
            "expansion_fidelity_pct_of_stem_hits": round(
                suggest_eval.percent(expansion_hits, stem_hits), 4
            ),
            "hits_gained": diagnostics["hits_gained"],
            "hits_lost": diagnostics["hits_lost"],
        },
        "chain_top3": {
            "baseline_pct": round(baseline_pct, 4),
            "baseline_ci95": [round(bound, 4) for bound in intervals["baseline_ci95"]],
            "stem_pct": round(stem_pct, 4),
            "stem_ci95": [round(bound, 4) for bound in intervals["stem_ci95"]],
            "delta_pp": round(delta_pp, 4),
            "delta_ci95": [round(bound, 4) for bound in intervals["delta_ci95"]],
        },
        "gate": {
            "delta_threshold_pp": GATE_DELTA_PP,
            "ci_lower_must_exceed": 0.0,
            "passed": bool(delta_pp >= GATE_DELTA_PP and intervals["delta_ci95"][0] > 0.0),
        },
        "runtime_seconds": {
            "grouping": round(grouping_seconds, 3),
            "counting": round(counting_seconds, 3),
            "table_and_expansion": round(table_seconds, 3),
            "eval_and_bootstrap": round(eval_seconds, 3),
            "total": round(time.monotonic() - started, 3),
        },
    }
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
