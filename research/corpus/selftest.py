"""Check that the streaming rewrite measures the same thing as the in-memory scripts.

Run it before trusting any number produced with stream.py. It compares, on real corpus files:

  1. ``fast_normalizer(alphabet)`` against ``dictionary_coverage.normalize_word`` -- token by
     token, over every token of the given files, including the rejected ones.
  2. ``Split`` against the original in-memory split (``measure_filtered.collect``) -- the exact
     list of TRAIN lines and the exact list of HELD lines, in order.

Both are exact-equality checks, not spot checks: if the rewrite changed the method rather than
its memory profile, this fails.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import corpuslib as CL  # noqa: E402
import dictionary_coverage as cov  # noqa: E402
from measure_filtered import collect  # noqa: E402
from stream import Split, TRAIN, HELD, fast_normalizer  # noqa: E402


def check_normalizer(paths, tag) -> int:
    alphabet = cov.language_for(tag).alphabet
    fast = fast_normalizer(alphabet)
    checked = 0
    for path in paths:
        with CL.open_text(Path(path)) as handle:
            for line in handle:
                for chunk in line.split():
                    word = chunk.strip(CL._EDGE)
                    reference = cov.normalize_word(word, alphabet)[0] if word else None
                    if fast(word) != reference:
                        raise AssertionError(
                            f"normalizer disagreement on {chunk!r}: "
                            f"fast={fast(word)!r} reference={reference!r}")
                    checked += 1
    return checked


def check_split(paths, tag) -> tuple[int, int]:
    reference_train, reference_held, _freq, _ev = collect(paths, tag)
    split = Split(paths)
    streamed_train = [line for line, _ in split.iter_lines(TRAIN)]
    streamed_held = [line for line, _ in split.iter_lines(HELD)]
    if streamed_train != reference_train:
        raise AssertionError(
            f"train split differs: {len(streamed_train)} streamed vs {len(reference_train)} reference")
    if streamed_held != reference_held:
        raise AssertionError(
            f"held split differs: {len(streamed_held)} streamed vs {len(reference_held)} reference")
    return len(reference_train), len(reference_held)


def main() -> None:
    tag = sys.argv[1]
    paths = sys.argv[2:]
    tokens = check_normalizer(paths, tag)
    train, held = check_split(paths, tag)
    print(f"selftest {tag}: normalizer agrees on {tokens} tokens; "
          f"split identical ({train} train / {held} held lines)")


if __name__ == "__main__":
    main()
