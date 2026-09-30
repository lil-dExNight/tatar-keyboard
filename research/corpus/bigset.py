"""A memory-bounded, near-exact set of 64-bit line hashes.

A Python ``set`` of every deduplicated line of a large corpus (such as the Russian
OpenSubtitles dump) needs tens of gigabytes. Here lines are reduced to a 64-bit BLAKE2b digest
and stored in an open-addressing table backed by ``array('q')``: 8 bytes per slot and no
per-entry Python object.

Correctness cost: two different lines that share a 64-bit digest are treated as duplicates
and the second one is dropped. With n lines the chance that any such pair exists is about
n^2 / 2^65 (about 5e-4 at n = 2e8), and one dropped line changes no reported figure. The
digest is BLAKE2b rather than Python's ``hash()`` because ``hash()`` of a ``str`` is salted
per process, which would make the split, and so the numbers, differ between runs.
"""
from __future__ import annotations

from array import array
from hashlib import blake2b

_MASK64 = (1 << 64) - 1


def line_key(text: str) -> int:
    """A deterministic non-zero signed 64-bit key for [text]; 0 is reserved for EMPTY."""
    value = int.from_bytes(blake2b(text.encode("utf-8", "surrogatepass"),
                                   digest_size=8).digest(), "little")
    if value == 0:
        value = 1
    # array('q') is SIGNED; fold the unsigned digest into that range without losing bits.
    return value - (1 << 64) if value >= (1 << 63) else value


class HashSet64:
    """Open-addressing set of non-zero 64-bit keys with linear probing."""

    __slots__ = ("_table", "_mask", "_size", "_limit")

    def __init__(self, capacity_hint: int = 1 << 20) -> None:
        capacity = 1 << max(16, (max(capacity_hint, 1) * 2 - 1).bit_length())
        self._alloc(capacity)

    def _alloc(self, capacity: int) -> None:
        self._table = array("q", bytes(8 * capacity))
        self._mask = capacity - 1
        self._size = 0
        self._limit = (capacity * 2) // 3

    def __len__(self) -> int:
        return self._size

    def add(self, key: int) -> bool:
        """Insert [key]; return True when it was not present before."""
        table = self._table
        mask = self._mask
        index = key & mask
        while True:
            current = table[index]
            if current == 0:
                table[index] = key
                self._size += 1
                if self._size > self._limit:
                    self._grow()
                return True
            if current == key:
                return False
            index = (index + 1) & mask

    def _grow(self) -> None:
        old = self._table
        self._alloc((self._mask + 1) * 2)
        table = self._table
        mask = self._mask
        size = 0
        for key in old:
            if key == 0:
                continue
            index = key & mask
            while table[index] != 0:
                index = (index + 1) & mask
            table[index] = key
            size += 1
        self._size = size


class Counter64:
    """Open-addressing ``int -> count`` map on two flat arrays, for counting bigram pairs.

    A ``collections.Counter`` keyed by ``(word, word)`` tuples costs roughly 200 bytes per
    distinct pair once the tuple, the two string references and the dict slot are counted. The
    Russian OpenSubtitles corpus produces distinct pairs in the tens of millions, which puts a
    Counter into the tens of gigabytes. Here a pair is encoded as one integer (``head_id *
    stride + successor_id``) and a slot costs 12 bytes: 8 for the key, 4 for the count.

    Keys must be non-zero, which the encoding guarantees by reserving id 0 for no word.
    """

    __slots__ = ("_keys", "_counts", "_mask", "_size", "_limit", "total")

    def __init__(self, capacity_hint: int = 1 << 20) -> None:
        self._alloc(1 << max(16, (max(capacity_hint, 1) * 2 - 1).bit_length()))
        self.total = 0

    def _alloc(self, capacity: int) -> None:
        self._keys = array("q", bytes(8 * capacity))
        self._counts = array("i", bytes(4 * capacity))
        self._mask = capacity - 1
        self._size = 0
        self._limit = (capacity * 2) // 3

    def __len__(self) -> int:
        return self._size

    def bump(self, key: int) -> None:
        keys = self._keys
        mask = self._mask
        index = key & mask
        while True:
            current = keys[index]
            if current == key:
                self._counts[index] += 1
                self.total += 1
                return
            if current == 0:
                keys[index] = key
                self._counts[index] = 1
                self._size += 1
                self.total += 1
                if self._size > self._limit:
                    self._grow()
                return
            index = (index + 1) & mask

    def _grow(self) -> None:
        old_keys, old_counts = self._keys, self._counts
        total = self.total
        self._alloc((self._mask + 1) * 2)
        keys, counts, mask = self._keys, self._counts, self._mask
        size = 0
        for slot, key in enumerate(old_keys):
            if key == 0:
                continue
            index = key & mask
            while keys[index] != 0:
                index = (index + 1) & mask
            keys[index] = key
            counts[index] = old_counts[slot]
            size += 1
        self._size = size
        self.total = total

    def items(self):
        """Yield ``(key, count)`` for every occupied slot."""
        counts = self._counts
        for index, key in enumerate(self._keys):
            if key != 0:
                yield key, counts[index]
