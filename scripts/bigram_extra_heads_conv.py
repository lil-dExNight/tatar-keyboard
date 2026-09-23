#!/usr/bin/env python3
"""Expand the Tatar extra-heads list with conversationally established words (P5a option b).

Rule EXPAND-1 (written 2026-09-23 in docs/ROADMAP-P4.md; corpus-only, the eval set is never
consulted): a word joins the list when ALL of these hold:

  1. it is in the shipped dictionary (`tatar_top100k_v1.tdict.zlib`) with FREQUENCY rank
     >= --heads — below the frequency cutoff, so the H mechanism cannot reach it. The frequency
     rank mirrors `bigram_pack.select_heads` exactly: descending frequency, ties broken by
     code point ascending, read from the same dictionary asset (an earlier draft of this script
     ranked by the dictionary's alphabetical storage order — that was a bug; the packer only
     deduplicated it into a subset of the intended set);
  2. its token count in the conversational training input (`tt_conv_train90-sentences.txt`,
     the held-out-free 90% of Tatoeba + OpenSubtitles) is >= --threshold (default 10);
  3. it is not already promoted by the H cutoff or by the existing address list
     (`scripts/bigram_extra_heads_tat.txt`).

Pair evidence is deliberately NOT pre-computed here: the packer's own rule drops a head with no
in-vocabulary pair (recorded into `scripts/known_asset_drift.json`), so the committed list stays
the honest statement of the rule and the survival check lives in exactly one place.

Deterministic: same inputs -> same list, one word per line, code-point ascending. Usage:

    python3 scripts/bigram_extra_heads_conv.py \
        --dictionary app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib \
        --conversational ~/corpora-leipzig/tt_conv_train90-sentences.txt \
        --existing scripts/bigram_extra_heads_tat.txt \
        --heads 10132 --threshold 10
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

_PUNCT = ".,!?;:()[]\"'«»—–-…"


def load_frequencies(dictionary_path: Path) -> dict[str, int]:
    scripts_dir = Path(__file__).resolve().parent
    spec = importlib.util.spec_from_file_location(
        "bigram_asset_pack", scripts_dir / "bigram_asset_pack.py"
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules["bigram_asset_pack"] = module
    spec.loader.exec_module(module)
    _vocabulary, frequencies = module.read_shipped_vocabulary(dictionary_path)
    return frequencies


def conversational_counts(conversational_path: Path, known: set[str]) -> dict[str, int]:
    counts: dict[str, int] = {}
    with conversational_path.open(encoding="utf-8") as stream:
        for line in stream:
            for token in line.split():
                word = token.strip(_PUNCT).lower()
                if word in known:
                    counts[word] = counts.get(word, 0) + 1
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", type=Path, required=True)
    parser.add_argument("--conversational", type=Path, required=True)
    parser.add_argument("--existing", type=Path, required=True)
    parser.add_argument("--heads", type=int, required=True)
    parser.add_argument("--threshold", type=int, default=10)
    args = parser.parse_args()

    frequencies = load_frequencies(args.dictionary)
    # The frequency rank of every word, exactly as select_heads ranks them.
    frequency_rank = {
        word: index + 1
        for index, (word, _frequency) in enumerate(
            sorted(frequencies.items(), key=lambda item: (-item[1], item[0]))
        )
    }
    existing = {
        line.split("#", 1)[0].strip()
        for line in args.existing.read_text(encoding="utf-8").splitlines()
        if line.split("#", 1)[0].strip()
    }
    counts = conversational_counts(args.conversational, set(frequencies))
    selected = sorted(
        word
        for word, count in counts.items()
        if count >= args.threshold
        and frequency_rank[word] >= args.heads
        and word not in existing
    )
    for word in selected:
        print(word)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
