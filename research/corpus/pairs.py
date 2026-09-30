"""Bigram pair counting for corpora too large for an in-memory Counter.

The reference counter (``measure_bigrams.count_pairs``) uses a ``collections.Counter`` keyed
by ``(head, successor)`` string tuples, and finding each head's top successors by re-scanning
that Counter per head is O(heads x pairs). Both are fine at Tatoeba scale and neither works on
the Russian OpenSubtitles file: the Counter alone would need tens of gigabytes.

The rule being counted is unchanged and is the bigram packer's adjacency rule verbatim:

    * tokens come from a plain whitespace split, WITHOUT stripping surrounding punctuation
      (that is ``bigram_tokens``, not ``dict_tokens``);
    * a token rejected by normalization BREAKS adjacency -- it is not transparent;
    * a token that normalizes but is outside the vocabulary does NOT break adjacency; it
      simply cannot form a pair;
    * self-pairs are dropped.

``selftest_pairs.py`` checks this module against the original ``measure_bigrams.count_pairs``
on the Tatar corpora, pair for pair and count for count.
"""
from __future__ import annotations

from bigset import Counter64
from stream import fast_normalizer

BREAK = -1
OUT_OF_VOCAB = 0


class PairCounts:
    """Counts of ``(head, successor)`` pairs, addressed by integer ids."""

    __slots__ = ("ids", "words", "stride", "counts")

    def __init__(self, vocab, capacity_hint: int = 1 << 22) -> None:
        # id 0 is reserved for "normalized but outside the vocabulary", so ids start at 1 and
        # a packed key head*stride + successor is never 0 -- which Counter64 requires.
        self.words = [None] + sorted(vocab)
        self.ids = {word: index for index, word in enumerate(self.words) if index}
        self.stride = len(self.words)
        self.counts = Counter64(capacity_hint)

    def __len__(self) -> int:
        return len(self.counts)

    @property
    def instances(self) -> int:
        return self.counts.total

    def key(self, head: str, successor: str) -> int:
        return self.ids[head] * self.stride + self.ids[successor]

    def items(self):
        """Yield ``(head_id, successor_id, count)`` for every distinct pair."""
        stride = self.stride
        for packed, count in self.counts.items():
            yield packed // stride, packed % stride, count

    def as_word_pairs(self):
        """Yield ``((head, successor), count)`` -- for tests and small corpora only."""
        words = self.words
        for head_id, successor_id, count in self.items():
            yield (words[head_id], words[successor_id]), count


def count_pairs(lines, alphabet, vocab, capacity_hint: int = 1 << 22) -> PairCounts:
    """Count pairs over an iterable of raw lines (or ``(line, source)`` tuples)."""
    result = PairCounts(vocab, capacity_hint)
    ids = result.ids
    stride = result.stride
    bump = result.counts.bump
    norm = fast_normalizer(alphabet)
    for line in lines:
        if type(line) is tuple:
            line = line[0]
        previous = BREAK
        for chunk in line.split():
            word = norm(chunk)
            if word is None:
                previous = BREAK
                continue
            current = ids.get(word, OUT_OF_VOCAB)
            if previous > OUT_OF_VOCAB and current > OUT_OF_VOCAB and previous != current:
                bump(previous * stride + current)
            previous = current
    return result


def top_successors(pairs: PairCounts, heads: set[int], limit: int) -> dict[int, list[int]]:
    """For each head id in [heads], its [limit] best successor ids by (-count, word order).

    One pass over the pair table, keeping at most [limit] candidates per head, instead of one
    pass per head. The tie-break is code-point order of the successor word, which is what
    sorting by ``(-count, word)`` did before -- and because ids were assigned in sorted word
    order, comparing ids compares words.
    """
    best: dict[int, list[tuple[int, int]]] = {}
    for head_id, successor_id, count in pairs.items():
        if head_id not in heads:
            continue
        row = best.get(head_id)
        if row is None:
            best[head_id] = [(count, successor_id)]
            continue
        row.append((count, successor_id))
        if len(row) > limit * 4:
            row.sort(key=lambda item: (-item[0], item[1]))
            del row[limit:]
    out = {}
    for head_id, row in best.items():
        row.sort(key=lambda item: (-item[0], item[1]))
        out[head_id] = [successor_id for _count, successor_id in row[:limit]]
    return out
