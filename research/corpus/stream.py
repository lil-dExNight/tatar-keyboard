"""Streaming replacement for the in-memory line handling of the earlier corpus scripts.

For Tatoeba and the small Tatar OpenSubtitles file, keeping every deduplicated line in a
Python list is fine. The Russian OpenSubtitles file does not fit that shape: the list alone
would need more memory than a workstation has.

The split RULE is unchanged, on purpose, so the numbers stay comparable with the in-memory
scripts:

    * a line is keyed by ``line.strip()``; empty keys are dropped;
    * a key already seen is dropped (deduplication happens BEFORE the split, so one subtitle
      repeated across uploads cannot land in both halves);
    * counting unique lines from 1, every tenth one is HELD OUT and the other nine are TRAIN.

What changed is only WHERE the decision lives. [Split] makes one pass over the files, records
one byte per physical line (0 = dropped, 1 = train, 2 = held), and then replays the files as
often as a measurement needs. One byte per line costs a few hundred megabytes for the largest
corpus here, against tens of gigabytes for the list of lines it replaces.

[fast_normalizer] is a speed-only replacement for ``dictionary_coverage.normalize_word``.
It performs the same three steps in the same order (NFC, lower, length limit, alphabet
membership) and differs only in that membership is tested by a compiled character class
instead of a Python-level loop over characters. ``selftest.py`` asserts the two agree on every
token of the Tatar corpora before any number is reported.
"""
from __future__ import annotations

import re
import unicodedata
from pathlib import Path

import corpuslib as CL
from bigset import HashSet64, line_key

DROP, TRAIN, HELD = 0, 1, 2

MAX_WORD_LENGTH = 64


def fast_normalizer(alphabet):
    """Return ``norm(raw) -> str | None`` equivalent to ``normalize_word(raw, alphabet)[0]``."""
    char_class = "".join(re.escape(character) for character in sorted(alphabet))
    accepted = re.compile(f"[{char_class}]{{1,{MAX_WORD_LENGTH}}}\\Z").match
    normalize = unicodedata.normalize

    def norm(raw_word: str):
        word = normalize("NFC", raw_word.strip()).lower()
        return word if accepted(word) else None

    return norm


def source_name(path: Path) -> str:
    """The corpus name as make_review.py records it: 'OpenSubtitles-v2024.ru.txt.gz' -> 'OpenSubtitles'."""
    return path.name.split("-v")[0].split(".")[0]


class Split:
    """Deduplicated train/held split over gzipped corpora, held as one byte per line."""

    def __init__(self, paths, capacity_hint: int | None = None) -> None:
        self.paths = [Path(p) for p in paths]
        if capacity_hint is None:
            # An estimate of compressed bytes per unique line; the table grows on its own if
            # the guess is low, so this only saves a rehash.
            capacity_hint = max(1 << 16, sum(p.stat().st_size for p in self.paths) // 55)
        seen = HashSet64(capacity_hint)
        self.codes: list[bytearray] = []
        self.train_lines = 0
        self.held_lines = 0
        self.dropped_lines = 0
        unique = 0
        for path in self.paths:
            codes = bytearray()
            append = codes.append
            add = seen.add
            with CL.open_text(path) as handle:
                for line in handle:
                    key = line.strip()
                    if not key or not add(line_key(key)):
                        append(DROP)
                        self.dropped_lines += 1
                        continue
                    unique += 1
                    if unique % 10 == 0:
                        append(HELD)
                        self.held_lines += 1
                    else:
                        append(TRAIN)
                        self.train_lines += 1
            self.codes.append(codes)
        self.unique_lines = unique
        del seen

    def iter_lines(self, want: int):
        """Yield ``(line, source_name)`` for every line whose split code is [want]."""
        for path, codes in zip(self.paths, self.codes):
            name = source_name(path)
            with CL.open_text(path) as handle:
                for line, code in zip(handle, codes):
                    if code == want:
                        yield line, name

    def train(self):
        return self.iter_lines(TRAIN)

    def held(self):
        return self.iter_lines(HELD)
