#!/usr/bin/env python3
"""Regenerate `scripts/bigram_extra_heads_tat.txt` against the shipped Tatar dictionary.

Two rules, both over the shipped dictionary's frequency ranking (the `select_heads` order:
frequency descending, then code point ascending; ranks are zero-based):

* imperative rule: unigram rank in [max(10 000, H), 40 000), where H is the table's head
  cutoff (the window predates it, and words the cutoff reaches are left out); at least 4 of
  the 6 verb paradigm cells
  are present in the same dictionary (the cells: infinitive, past, participle, negative
  imperative, plural imperative, masdar, each with its spelling variants); the word is not
  the negative form of another verb (`Y` + ма/мә with `Y` in the dictionary); the mixed
  training corpora have at least one pair for it;
* conversational rule: rank >= the bigram table's head cutoff H, at least 10 occurrences in
  the conversational training input, not covered by the cutoff or the imperative rule.

Pairs are not counted for the conversational rule: the packer drops a head without
in-dictionary pairs itself, and the count lands in `scripts/known_asset_drift.json`.

The eval set is never read. Output: the listing file, rewritten atomically.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))

import bigram_pack  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
from bigram_extra_heads_conv import conversational_counts  # noqa: E402
from wordform_gen import write_atomic  # noqa: E402

LISTING = SCRIPT_DIR / "bigram_extra_heads_tat.txt"

IMPERATIVE_RANK_LO = 10_000
IMPERATIVE_RANK_HI = 40_000
CELLS_MIN = 4
CONV_RANK_LO = 10_132  # the bigram table's head cutoff H
CONV_MIN_OCCURRENCES = 10

# Harmony sets of the original rule implementation, ported verbatim (the duplicate front vowel
# and the membership of я are part of the ported rule, not typos to fix silently).
BACK_VOWELS = set("аоуыя")
FRONT_VOWELS = set("әөүеиө")
VOWELS = set("аәеёиоөуүыэюя")


def is_back(stem: str) -> bool:
    """Vowel harmony by the stem's last vowel; the default is the back variant."""
    for char in reversed(stem):
        if char in BACK_VOWELS:
            return True
        if char in FRONT_VOWELS:
            return False
    return True


def paradigm_forms(stem: str) -> dict[str, list[str]]:
    """The six paradigm cells; each value lists the accepted spellings."""
    back = is_back(stem)
    vowel_final = stem[-1] in VOWELS
    a, ae = ("а", "ы") if back else ("ә", "е")
    g, k = ("га", "ка") if back else ("гә", "кә")
    cells: dict[str, list[str]] = {}
    if vowel_final:
        cells["infinitive"] = [stem + ("рга" if back else "ргә")]
    else:
        cells["infinitive"] = [stem + v + ("рга" if back else "ргә") for v in {a, ae}]
    cells["past"] = [stem + d + ("ы" if back else "е") for d in ("д", "т")]
    cells["participle"] = [stem + s for s in (g + "н", k + "н")]
    cells["negative_imperative"] = [stem + ("ма" if back else "мә")]
    if vowel_final:
        cells["plural_imperative"] = [stem + ("гыз" if back else "гез")]
    else:
        cells["plural_imperative"] = [stem + ("ыгыз" if back else "егез")]
    cells["masdar"] = [stem + ("у" if back else "ү")]
    return cells


def is_negative_form(word: str, vocabulary: frozenset[str]) -> bool:
    return any(
        word.endswith(suffix) and word[: -len(suffix)] in vocabulary
        for suffix in ("ма", "мә")
    )


HEADER = """\
# Extra heads for the Tatar bigram table.
#
# A word listed here becomes a head of the bigram table regardless of its unigram rank. This is
# not a dictionary: every word is already in tatar_top100k_v1.tdict.zlib. The list only decides
# which words get stored successors.
#
# Why: heads are chosen by unigram frequency with a cutoff H, and frequent Tatar imperatives (the
# bare verb stem) rank below it, so they had no next-word predictions.
#
# The list is produced by a rule, not by hand; regenerate the whole file:
#   python3 scripts/bigram_extra_heads_regen.py --corpus-dir DIR
# The two rules and their parameters live in scripts/bigram_extra_heads_regen.py (docstring).
# Words already inside the cutoff (H = 10 132) are left out; ShippedExtraHeadListTest checks that.
# Do not add words by hand.
#
# word    rank    freq     cells   training pairs
"""

CONV_HEADER = """
# Conversational rule: conversationally established words below the frequency cutoff. The rule
# (reproducible, the eval set is never read): frequency rank in the shipped dictionary >= H,
# >= 10 token occurrences in the tt_conv_train90 training input, not covered by the cutoff or
# the imperative rule above. Pairs are not counted here: the packer drops a head without an
# in-dictionary pair itself (scripts/known_asset_drift.json).
"""


def regenerate(dictionary: Path, corpus_dir: Path, out: Path) -> dict:
    language = coverage.language_for("tat")
    vocabulary, frequencies = bigram_pack.read_shipped_vocabulary(dictionary, language)
    ordered = sorted(frequencies.items(), key=lambda kv: (-kv[1], kv[0]))

    # Imperative rule: first the cheap cell/negative filters, then pair evidence for the rest.
    candidates: list[tuple[str, int, int, int]] = []  # word, rank, frequency, hit cells
    for rank in range(IMPERATIVE_RANK_LO, min(IMPERATIVE_RANK_HI, len(ordered))):
        if rank < CONV_RANK_LO:
            # The header's "words already inside the cutoff are left out" applies to both
            # rules: the window predates the current cutoff H.
            continue
        word, frequency = ordered[rank]
        if len(word) < 2:
            continue
        cells = paradigm_forms(word)
        hits = sum(1 for variants in cells.values()
                   if any(variant in vocabulary for variant in variants))
        if hits < CELLS_MIN or is_negative_form(word, vocabulary):
            continue
        candidates.append((word, rank, frequency, hits))

    train = [
        corpus_dir / name
        for name in (
            "tat_mixed_2015_1M-sentences.txt",
            "tat_web_2018_1M-sentences.txt",
            "tt_conv_train90-sentences.txt",
        )
    ]
    pairs = bigram_pack.count_pairs(
        train, frozenset(word for word, _r, _f, _c in candidates),
        frozenset(vocabulary), 1, [],
    )
    imperative = [
        (word, rank, frequency, hits, sum(c for _s, c in pairs.get(word, [])))
        for word, rank, frequency, hits in candidates
        if pairs.get(word)
    ]
    imperative.sort(key=lambda row: row[1])

    # Conversational rule.
    frequency_rank = {word: rank for rank, (word, _f) in enumerate(ordered)}
    conv_counts = conversational_counts(corpus_dir / "tt_conv_train90-sentences.txt",
                                        set(frequencies))
    imperative_words = {word for word, _r, _f, _c, _p in imperative}
    conversational = sorted(
        word
        for word, count in conv_counts.items()
        if count >= CONV_MIN_OCCURRENCES
        and frequency_rank[word] >= CONV_RANK_LO
        and word not in imperative_words
    )

    lines = [HEADER]
    for word, rank, frequency, hits, pair_count in imperative:
        lines.append(f"{word:<10} #  {rank}   {frequency}   {hits}/6    {pair_count}\n")
    lines.append(CONV_HEADER)
    for word in conversational:
        lines.append(word + "\n")
    write_atomic(out, "".join(lines).encode("utf-8"))

    report = {
        "dictionary": str(dictionary),
        "imperative_candidates_with_cells": len(candidates),
        "imperative_kept": len(imperative),
        "conversational_kept": len(conversational),
        "total": len(imperative) + len(conversational),
        "output": str(out),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--dictionary", type=Path,
        default=ROOT / "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
    )
    parser.add_argument("--corpus-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=LISTING)
    args = parser.parse_args()
    for name in ("tat_mixed_2015_1M-sentences.txt", "tat_web_2018_1M-sentences.txt",
                 "tt_conv_train90-sentences.txt"):
        if not (args.corpus_dir / name).is_file():
            print(f"error: missing training corpus {args.corpus_dir / name}", file=sys.stderr)
            return 2
    regenerate(args.dictionary, args.corpus_dir, args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
