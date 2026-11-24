"""Persistent exact and near-duplicate detection for corpus documents."""

from __future__ import annotations

import hashlib
import sqlite3
import struct
import unicodedata
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from os import PathLike
from typing import Literal

_SIGNATURE_SIZE = 16
_BAND_COUNT = 8
_BAND_SIZE = 2
_SIMILARITY_THRESHOLD = 0.80
_COMMIT_BATCH = 1000
_MODULUS = (1 << 61) - 1


@dataclass(frozen=True)
class DuplicateMatch:
    reason: Literal["exact", "near"]
    original_source: str
    original_document_id: str
    similarity: float


def _permutations() -> tuple[tuple[int, int], ...]:
    permutations = []
    for index in range(_SIGNATURE_SIZE):
        seed = hashlib.blake2b(
            b"document-dedup-permutation-" + bytes([index]), digest_size=16
        ).digest()
        multiplier = int.from_bytes(seed[:8], "big") % _MODULUS
        increment = int.from_bytes(seed[8:], "big") % _MODULUS
        permutations.append((multiplier or 1, increment))
    return tuple(permutations)


_PERMUTATIONS = _permutations()


def _normalize(text: str) -> str:
    normalized = unicodedata.normalize("NFC", text)
    characters = []
    for character in normalized:
        category = unicodedata.category(character)
        if category == "Cf":
            continue
        characters.append(" " if category in {"Cc", "Cs"} else character)
    return " ".join("".join(characters).split())


def _signature(tokens: list[str]) -> tuple[int, ...] | None:
    if len(tokens) < 5:
        return None
    shingles = {
        " ".join(tokens[index:index + 5])
        for index in range(len(tokens) - 4)
    }
    base_hashes = {
        int.from_bytes(
            hashlib.blake2b(shingle.encode("utf-8"), digest_size=8).digest(),
            "big",
        )
        for shingle in shingles
    }
    return tuple(
        min((multiplier * value + increment) % _MODULUS for value in base_hashes)
        for multiplier, increment in _PERMUTATIONS
    )


def _pack_signature(signature: tuple[int, ...]) -> bytes:
    return struct.pack(">16Q", *signature)


def _unpack_signature(signature: bytes) -> tuple[int, ...]:
    return struct.unpack(">16Q", signature)


def _bands(signature: tuple[int, ...]):
    for band in range(_BAND_COUNT):
        offset = band * _BAND_SIZE
        yield band, struct.pack(">QQ", *signature[offset:offset + _BAND_SIZE])


class DocumentDeduplicator:
    """Stores document fingerprints in SQLite and tracks counts per context."""

    def __init__(self, path: str | PathLike[str]):
        self._path = path
        self._connection: sqlite3.Connection | None = None
        self._pending = 0
        self._reset_context()

    def _reset_context(self) -> None:
        self._counts = {"exact": 0, "near": 0, "kept": 0}
        self._matrix = defaultdict(lambda: defaultdict(int))

    def __enter__(self) -> DocumentDeduplicator:
        if self._connection is not None:
            raise RuntimeError("DocumentDeduplicator is already open")
        Path(self._path).parent.mkdir(parents=True, exist_ok=True)
        self._connection = sqlite3.connect(self._path)
        self._connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS representatives (
                row_id INTEGER PRIMARY KEY,
                source TEXT NOT NULL,
                document_id TEXT NOT NULL,
                exact_hash BLOB NOT NULL UNIQUE,
                signature BLOB
            );
            CREATE TABLE IF NOT EXISTS band_rows (
                representative_id INTEGER NOT NULL
                    REFERENCES representatives(row_id),
                band INTEGER NOT NULL,
                value BLOB NOT NULL,
                PRIMARY KEY (band, value, representative_id)
            );
            """
        )
        self._connection.commit()
        self._pending = 0
        self._reset_context()
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        connection = self._connection
        if connection is None:
            return
        try:
            if exc_type is None:
                connection.commit()
            else:
                connection.rollback()
        finally:
            connection.close()
            self._connection = None

    def _db(self) -> sqlite3.Connection:
        if self._connection is None:
            raise RuntimeError("DocumentDeduplicator must be used as a context manager")
        return self._connection

    def find(self, text: str) -> DuplicateMatch | None:
        """Return a lookup-only match; never insert text or alter duplicate counts."""
        normalized = _normalize(text)
        if not normalized:
            return None
        connection = self._db()
        exact_hash = hashlib.sha256(normalized.encode("utf-8")).digest()
        row = connection.execute(
            "SELECT source, document_id FROM representatives WHERE exact_hash = ?",
            (exact_hash,),
        ).fetchone()
        if row is not None:
            return DuplicateMatch("exact", row[0], row[1], 1.0)
        signature = _signature(normalized.split())
        if signature is not None:
            return self._near_match(connection, signature)
        return None

    def check(
        self, source: str, document_id: str, text: str
    ) -> DuplicateMatch | None:
        normalized = _normalize(text)
        if not normalized:
            return None

        connection = self._db()
        exact_hash = hashlib.sha256(normalized.encode("utf-8")).digest()
        row = connection.execute(
            "SELECT source, document_id FROM representatives WHERE exact_hash = ?",
            (exact_hash,),
        ).fetchone()
        if row is not None:
            return self._duplicate(
                source, DuplicateMatch("exact", row[0], row[1], 1.0)
            )

        signature = _signature(normalized.split())
        if signature is not None:
            match = self._near_match(connection, signature)
            if match is not None:
                return self._duplicate(source, match)

        packed = _pack_signature(signature) if signature is not None else None
        cursor = connection.execute(
            "INSERT INTO representatives "
            "(source, document_id, exact_hash, signature) VALUES (?, ?, ?, ?)",
            (source, document_id, exact_hash, packed),
        )
        if signature is not None:
            connection.executemany(
                "INSERT INTO band_rows (representative_id, band, value) VALUES (?, ?, ?)",
                (
                    (cursor.lastrowid, band, value)
                    for band, value in _bands(signature)
                ),
            )
        self._counts["kept"] += 1
        self._pending += 1
        if self._pending == _COMMIT_BATCH:
            connection.commit()
            self._pending = 0
        return None

    def _near_match(
        self, connection: sqlite3.Connection, signature: tuple[int, ...]
    ) -> DuplicateMatch | None:
        bands = tuple(_bands(signature))
        conditions = " OR ".join(
            "(b.band = ? AND b.value = ?)" for _ in bands
        )
        parameters = [item for band, value in bands for item in (band, value)]
        rows = connection.execute(
            "SELECT DISTINCT r.row_id, r.source, r.document_id, r.signature "
            "FROM band_rows AS b "
            "JOIN representatives AS r ON r.row_id = b.representative_id "
            f"WHERE {conditions} ORDER BY r.row_id",
            parameters,
        )
        for _, source, document_id, packed in rows:
            candidate = _unpack_signature(packed)
            matches = sum(a == b for a, b in zip(signature, candidate))
            similarity = matches / _SIGNATURE_SIZE
            if similarity >= _SIMILARITY_THRESHOLD:
                return DuplicateMatch("near", source, document_id, similarity)
        return None

    def _duplicate(self, source: str, match: DuplicateMatch) -> DuplicateMatch:
        self._counts[match.reason] += 1
        if source != match.original_source:
            self._matrix[source][match.original_source] += 1
        return match

    def cross_source_matrix(self) -> dict[str, dict[str, int]]:
        """Return counts indexed by duplicate source, then representative source."""
        return {source: dict(targets) for source, targets in self._matrix.items()}

    def counts(self) -> dict[str, int]:
        return dict(self._counts)
