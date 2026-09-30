#!/usr/bin/env python3
"""Print Tatar words to add to the extra-heads list because they are common in conversation.

A word is printed when its frequency rank in the dictionary (ranked as ``bigram_pack.select_heads``
does) is >= ``--heads``, it occurs >= ``--threshold`` times in the conversational training corpus,
and it is not already in ``--existing`` (``bigram_extra_heads_tat.txt``). Pairs are not checked:
the packer drops heads without in-vocabulary pairs. The eval set is never read.
Output: one word per line on stdout, code-point ascending, deterministic for the same inputs.
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
