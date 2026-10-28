#!/usr/bin/env python3
"""Candidate surgical conversational heads for the Tatar bigram table (A5 rule).

TEST MEASUREMENT ONLY -- the shipped listing is not rewritten here; the output file feeds
packing experiments. The eval set is never read.

A candidate is a shipped-dictionary word below the head cutoff H that is not already a head
(not in the top H, not in the current extra-heads listing) and satisfies one of two rules,
both tightened by the attestation requirement:

* conversational: unigram frequency rank >= H (the ``select_heads`` order), at least
  ``--floor`` token occurrences in the conversational training input (counted with the
  existing rule's tokenization, ``bigram_extra_heads_conv.conversational_counts``);
* imperative: rank in the documented band [H, 40 000), at least 4 of the 6 verb paradigm
  cells present in the dictionary (the regen rule's cells), not the negative form of
  another verb;

and, both rules: at least one pair (h, w) with w in the dictionary attested in the
conversational stream under the packer's pairing rule (``bigram_pack.iter_pairs``) -- a head
whose successors would come only from the written corpora is rejected (the wiki-stub
successor failure mode).

Output: the candidate list (one word per line, evidence in a ``#`` comment), ordered by
conversational occurrences descending then code point ascending, plus a JSON report.
Exit 2 on any missing input.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Sequence

SCRIPT_DIR = Path(__file__).resolve().parents[2] / "scripts"
sys.path.insert(0, str(SCRIPT_DIR))

import bigram_pack  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
from bigram_extra_heads_conv import conversational_counts  # noqa: E402
from bigram_extra_heads_regen import (  # noqa: E402
    CELLS_MIN,
    CONV_RANK_LO,
    IMPERATIVE_RANK_HI,
    is_negative_form,
    paradigm_forms,
)

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DICTIONARY = ROOT / "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib"
DEFAULT_LISTING = SCRIPT_DIR / "bigram_extra_heads_tat.txt"
DEFAULT_CONV = Path.home() / "corpora-leipzig/tt_conv_train90-sentences.txt"


def conv_pairs(path: Path, vocabulary: frozenset[str]) -> dict[str, set[str]]:
    """Successors attested per head in the conversational stream, packer pair rule."""
    pairs: dict[str, set[str]] = {}
    for sentence in bigram_pack.iter_sentences(path):
        tokens = bigram_pack.normalized_tokens(sentence, coverage.TATAR.alphabet)
        for head, success in bigram_pack.iter_pairs(tokens, vocabulary):
            pairs.setdefault(head, set()).add(success)
    return pairs


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dictionary", type=Path, default=DEFAULT_DICTIONARY)
    parser.add_argument("--conversational", type=Path, default=DEFAULT_CONV)
    parser.add_argument("--existing", type=Path, default=DEFAULT_LISTING)
    parser.add_argument("--floor", type=int, default=5,
                        help="minimum conversational token occurrences (default: %(default)s)")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    for path in (args.dictionary, args.conversational, args.existing):
        if not path.is_file():
            print(f"error: missing input {path}", file=sys.stderr)
            return 2

    language = coverage.language_for("tat")
    vocabulary, frequencies = bigram_pack.read_shipped_vocabulary(
        args.dictionary, language
    )
    ordered = sorted(frequencies.items(), key=lambda item: (-item[1], item[0]))
    frequency_rank = {word: rank for rank, (word, _f) in enumerate(ordered)}
    existing = {
        line.split("#", 1)[0].strip()
        for line in args.existing.read_text(encoding="utf-8").splitlines()
        if line.split("#", 1)[0].strip()
    }
    counts = conversational_counts(args.conversational, set(frequencies))
    attested = conv_pairs(args.conversational, frozenset(vocabulary))

    conversational: list[tuple[str, int]] = []
    for word, count in counts.items():
        if count < args.floor:
            continue
        rank = frequency_rank[word]
        if rank < CONV_RANK_LO or word in existing:
            continue
        if not attested.get(word):
            continue
        conversational.append((word, count))

    imperative: list[tuple[str, int]] = []
    for rank in range(CONV_RANK_LO, min(IMPERATIVE_RANK_HI, len(ordered))):
        word, _frequency = ordered[rank]
        if len(word) < 2 or word in existing:
            continue
        cells = paradigm_forms(word)
        hits = sum(
            1 for variants in cells.values()
            if any(variant in vocabulary for variant in variants)
        )
        if hits < CELLS_MIN or is_negative_form(word, vocabulary):
            continue
        if not attested.get(word):
            continue
        imperative.append((word, counts.get(word, 0)))

    seen = set()
    pool: list[tuple[str, int, str]] = []
    for word, count in conversational:
        seen.add(word)
        pool.append((word, count, "conv"))
    for word, count in imperative:
        if word not in seen:
            pool.append((word, count, "imperative"))
    pool.sort(key=lambda item: (-item[1], item[0]))

    lines = [
        f"{word}\t# {rule} rule, {count} conv occurrences, "
        f"rank {frequency_rank[word]}\n"
        for word, count, rule in pool
    ]
    args.out.write_text("".join(lines), encoding="utf-8")
    report = {
        "floor": args.floor,
        "conversational_candidates": len(conversational),
        "imperative_candidates": len(imperative),
        "pool": len(pool),
        "out": str(args.out),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
