#!/usr/bin/env python3
"""Pack the bundled bigram table asset (magic ``TATBIGR\\0``, schema 3 by default).

Input: ``pack`` takes sentence corpora, the linked dictionary asset, head/successor limits and an
optional ``--extra-heads`` list; ``repack`` converts a schema-2 table without a corpus. Shares the
data-layer helpers of ``bigram_pack.py``. ``--language`` picks the alphabet (default Tatar).
Output: raw and zlib-compressed table plus a JSON report; ``--schema 2`` is kept for golden tests.
Exits nonzero without writing output if a cap is exceeded, a word is missing from the dictionary,
or the built image fails its own validator.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import time
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence, TextIO

sys.path.insert(0, str(Path(__file__).resolve().parent))

import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack  # noqa: E402
from bigram_pack import (  # noqa: E402
    MAX_COMPRESSED_BYTES,
    MAX_RAW_BYTES,
    BigramInputError,
    count_pairs,
    peak_rss_bytes,
    read_shipped_vocabulary,
    select_heads,
)

MAGIC = b"TATBIGR\0"
SCHEMA_ID = 2
FORMAT_VERSION = 1
HEADER_SIZE = 96
CHECKSUM_OFFSET = 64
CHECKSUM_SIZE = 32
CHECKSUM_ALGORITHM_SHA256 = 1

# --- schema 2 --------------------------------------------------------------------------------
#
# Header, 96 bytes, unsigned little-endian: 8s (magic) + 4 x u16 (schemaId, formatVersion,
# headerSize, checksumAlgorithm) + 12 x u32 (headCount, pairCount, successVocabularyCount, six
# section offsets, headBlobLength, successBlobLength, fileSize) + 32s SHA-256 at offset 64,
# computed over the whole raw file with the digest bytes zeroed (as in dictionary_pack.py).
#
# Six sections, no padding, no separators: (1) H+1 u32 head-word offsets, (2) UTF-8 head-word
# blob in code-point ascending order (binary search), (3) H+1 u32 success-range boundaries,
# (4) P u32 success ids grouped by head in packing order (count descending, tie code-point
# ascending), (5) V+1 u32 success-word offsets, (6) UTF-8 deduplicated success-word blob in
# code-point ascending order.
#
# Caps for both schemas: compressed <= MAX_COMPRESSED_BYTES and raw <= MAX_RAW_BYTES. The whole
# image is built and validated in memory before any output file is touched.
HEADER_FORMAT = "<8s" + "H" * 4 + "I" * 12 + "32s"
HEADER = struct.Struct(HEADER_FORMAT)
assert HEADER.size == HEADER_SIZE

# --- schema 3 --------------------------------------------------------------------------------
#
# Cross-reference into the dictionary instead of own word blobs: heads are delta-varint indices
# into the bundled TATDICT schema-2 dictionary (both orderings are code-point ascending, so the
# indices of sorted heads strictly increase), successes are plain varint indices into the same
# dictionary, success-range boundaries are u8 counts (1..K), and the header carries the raw
# SHA-256 of the dictionary the table was packed against. The table is valid only together with
# that exact dictionary; both validators (this module's and Android's TatBigrValidator) and the
# runtime reader reject a mismatch.
#
# Header, 128 bytes: magic(8) + 4 x u16 (same four meta fields as schema 2) + 10 x u32
# (headCount, pairCount, headBlockCount, blockIndexOffset, headDeltasOffset, headDeltasSize,
# countsOffset, successIdsOffset, successIdsSize, fileSize) + 32s (dictionary raw SHA-256) +
# 8 reserved zero bytes + 32s checksum at offset 96 (same zero-then-hash trick, new offset).
#
# Four sections, no padding, no separators:
#   (1) headBlockCount records x 12 bytes {u32 firstDictIndex, u32 headDeltaOffset relative to
#       section 2, u32 successOffset relative to section 4} — one record per HEAD_BLOCK_V3 heads,
#       giving binary search over dictionary indices;
#   (2) head dict-index delta stream: per block, (blockHeadCount - 1) varint deltas, each >= 1
#       (the block's first index is absolute in its block-index record);
#   (3) headCount x u8 success counts (the generator never writes 0 — a head without pairs is
#       dropped — and never more than successes_per_head);
#   (4) pairCount varint dictionary indices of successes, grouped by head in packing order
#       (count descending, tie code-point ascending — the order the reader returns them in).

SCHEMA_ID_V3 = 3
FORMAT_VERSION_V3 = 1
HEADER_SIZE_V3 = 128
CHECKSUM_OFFSET_V3 = 96
HEAD_BLOCK_V3 = 64

HEADER_FORMAT_V3 = "<8s" + "H" * 4 + "I" * 10 + "32s" + "8s" + "32s"
HEADER_V3 = struct.Struct(HEADER_FORMAT_V3)
assert HEADER_V3.size == HEADER_SIZE_V3

BLOCK_RECORD_V3 = struct.Struct("<3I")


# zlib settings: the same mode as dictionary_pack.py / bigram_pack.py, so compressed sizes are
# comparable across all bundled assets.
COMPRESSION_LEVEL = 9
COMPRESSION_WBITS = 15
COMPRESSION_MEM_LEVEL = 9


class BigramFormatError(ValueError):
    """The bytes are not a valid TATBIGR schema-2 file."""


class BigramBudgetError(ValueError):
    """A size cap the generator enforces on its own output."""


@dataclass
class ParsedBigramTable:
    """What a validated file actually contains — the round-trip shape used by tests."""

    head_words: list[str]
    success_vocabulary: list[str]
    successes_by_head: dict[str, list[str]]  # packing order preserved, per head


def compress(raw: bytes) -> bytes:
    compressor = zlib.compressobj(
        level=COMPRESSION_LEVEL,
        method=zlib.DEFLATED,
        wbits=COMPRESSION_WBITS,
        memLevel=COMPRESSION_MEM_LEVEL,
        strategy=zlib.Z_DEFAULT_STRATEGY,
    )
    return compressor.compress(raw) + compressor.flush(zlib.Z_FINISH)


def decompress(asset: bytes) -> bytes:
    """Mirrors ``dictionary_pack.decompress_asset`` exactly, with schema 2's own caps."""
    if len(asset) > MAX_COMPRESSED_BYTES:
        raise BigramBudgetError(
            f"compressed asset is {len(asset)} bytes; limit is {MAX_COMPRESSED_BYTES}"
        )
    decompressor = zlib.decompressobj(wbits=COMPRESSION_WBITS)
    try:
        raw = decompressor.decompress(asset, MAX_RAW_BYTES + 1)
        if len(raw) > MAX_RAW_BYTES or decompressor.unconsumed_tail:
            raise BigramBudgetError(f"uncompressed table exceeds {MAX_RAW_BYTES} bytes")
        raw += decompressor.flush(MAX_RAW_BYTES + 1 - len(raw))
    except zlib.error as error:
        raise BigramFormatError(f"invalid zlib stream: {error}") from error
    if len(raw) > MAX_RAW_BYTES:
        raise BigramBudgetError(f"uncompressed table is {len(raw)} bytes; limit is {MAX_RAW_BYTES}")
    if not decompressor.eof:
        raise BigramFormatError("truncated zlib stream")
    if decompressor.unused_data:
        raise BigramFormatError("trailing or concatenated zlib data")
    if decompressor.unconsumed_tail:
        raise BigramFormatError("unconsumed zlib data")
    return raw


def _read_u32_array(raw: bytes, offset: int, count: int) -> tuple[int, ...]:
    return struct.unpack_from(f"<{count}I", raw, offset)


def _blob_words(raw: bytes, blob_offset: int, offsets: Sequence[int]) -> list[str]:
    """Decode a length-N+1 offset array over a UTF-8 blob into N strings, strict UTF-8."""
    words: list[str] = []
    for index in range(len(offsets) - 1):
        start, end = offsets[index], offsets[index + 1]
        if end < start:
            raise BigramFormatError(f"offset array is not non-decreasing at index {index}")
        if end == start:
            raise BigramFormatError(f"empty word at index {index}")
        chunk = raw[blob_offset + start : blob_offset + end]
        try:
            words.append(chunk.decode("utf-8", errors="strict"))
        except UnicodeDecodeError as error:
            raise BigramFormatError(f"invalid UTF-8 in blob at index {index}: {error}") from error
    return words


def _check_strictly_ascending(words: Sequence[str], label: str) -> None:
    for index in range(1, len(words)):
        if not words[index - 1] < words[index]:
            raise BigramFormatError(
                f"{label} is not strictly code-point ascending at index {index}: "
                f"{words[index - 1]!r} >= {words[index]!r}"
            )


def validate_raw(raw: bytes) -> ParsedBigramTable:
    """Strict schema-2 validator: rejects any header, offset, ordering or UTF-8 inconsistency.

    Malformed, truncated or concatenated zlib is caught one layer up, by ``decompress``; this
    function only sees inflated bytes, like ``dictionary_pack.validate_raw``.
    """
    if len(raw) < HEADER_SIZE:
        raise BigramFormatError(f"file is {len(raw)} bytes, shorter than the {HEADER_SIZE}-byte header")
    fields = HEADER.unpack_from(raw)
    (
        magic,
        schema_id,
        format_version,
        header_size,
        checksum_algorithm,
        head_count,
        pair_count,
        success_vocabulary_count,
        section1_offset,
        section2_offset,
        section3_offset,
        section4_offset,
        section5_offset,
        section6_offset,
        head_blob_length,
        success_blob_length,
        file_size,
        digest,
    ) = fields

    if magic != MAGIC:
        raise BigramFormatError(f"unrecognized magic: {magic!r}")
    if schema_id != SCHEMA_ID:
        raise BigramFormatError(f"unsupported schema id: {schema_id}")
    if format_version != FORMAT_VERSION:
        raise BigramFormatError(f"unsupported format version: {format_version}")
    if header_size != HEADER_SIZE:
        raise BigramFormatError(f"unsupported header size: {header_size}")
    if checksum_algorithm != CHECKSUM_ALGORITHM_SHA256:
        raise BigramFormatError(f"unsupported checksum algorithm: {checksum_algorithm}")

    zeroed = raw[:CHECKSUM_OFFSET] + bytes(CHECKSUM_SIZE) + raw[CHECKSUM_OFFSET + CHECKSUM_SIZE :]
    expected_digest = hashlib.sha256(zeroed).digest()
    if digest != expected_digest:
        raise BigramFormatError("checksum mismatch")

    # Canonical section arithmetic: every offset and every length is a deterministic function of
    # (head_count, pair_count, success_vocabulary_count) under the "no padding, no separators"
    # rule — a header that disagrees with that arithmetic is corrupt, even if every byte it points
    # at happens to be well-formed.
    expected_section1 = HEADER_SIZE
    expected_section2 = expected_section1 + 4 * (head_count + 1)
    expected_section3 = expected_section2 + head_blob_length
    expected_section4 = expected_section3 + 4 * (head_count + 1)
    expected_section5 = expected_section4 + 4 * pair_count
    expected_section6 = expected_section5 + 4 * (success_vocabulary_count + 1)
    expected_file_size = expected_section6 + success_blob_length
    if (section1_offset, section2_offset, section3_offset, section4_offset, section5_offset, section6_offset) != (
        expected_section1,
        expected_section2,
        expected_section3,
        expected_section4,
        expected_section5,
        expected_section6,
    ):
        raise BigramFormatError("non-canonical section arithmetic")
    if file_size != expected_file_size:
        raise BigramFormatError(
            f"header file_size {file_size} does not match section arithmetic {expected_file_size}"
        )
    if len(raw) != file_size:
        raise BigramFormatError(
            f"actual length {len(raw)} does not match header file_size {file_size} "
            "(truncated or trailing bytes)"
        )

    head_offsets = _read_u32_array(raw, section1_offset, head_count + 1)
    if head_offsets[0] != 0 or head_offsets[-1] != head_blob_length:
        raise BigramFormatError("head offset array does not bound the head blob")
    head_words = _blob_words(raw, section2_offset, head_offsets)
    _check_strictly_ascending(head_words, "head words")

    success_ranges = _read_u32_array(raw, section3_offset, head_count + 1)
    if success_ranges[0] != 0 or success_ranges[-1] != pair_count:
        raise BigramFormatError("success range array does not bound the success id section")
    for index in range(head_count):
        if success_ranges[index] >= success_ranges[index + 1]:
            raise BigramFormatError(f"head {index} ({head_words[index]!r}) has an empty success range")

    success_ids = _read_u32_array(raw, section4_offset, pair_count)
    for position, identifier in enumerate(success_ids):
        if identifier >= success_vocabulary_count:
            raise BigramFormatError(
                f"success id {identifier} at position {position} is >= vocabulary size "
                f"{success_vocabulary_count}"
            )

    success_word_offsets = _read_u32_array(raw, section5_offset, success_vocabulary_count + 1)
    if success_word_offsets[0] != 0 or success_word_offsets[-1] != success_blob_length:
        raise BigramFormatError("success word offset array does not bound the success blob")
    success_vocabulary = _blob_words(raw, section6_offset, success_word_offsets)
    _check_strictly_ascending(success_vocabulary, "success vocabulary")

    successes_by_head: dict[str, list[str]] = {}
    for index, head in enumerate(head_words):
        start, end = success_ranges[index], success_ranges[index + 1]
        successes_by_head[head] = [success_vocabulary[success_ids[position]] for position in range(start, end)]

    return ParsedBigramTable(
        head_words=list(head_words),
        success_vocabulary=list(success_vocabulary),
        successes_by_head=successes_by_head,
    )


@dataclass
class PackResult:
    raw: bytes
    compressed: bytes
    head_count: int
    pair_count: int
    success_vocabulary_count: int
    dropped_heads: list[str] = field(default_factory=list)


def pack_bigram_table(
    heads_by_frequency: Sequence[str],
    table: dict[str, list[tuple[str, int]]],
    successes_per_head: int,
) -> PackResult:
    """Build the schema-2 raw+compressed image for one (H, K) configuration.

    ``heads_by_frequency`` is the top-H list in frequency order (as ``select_heads`` returns it)
    and decides set membership only. A head with no successes in training is dropped, not stored
    with an empty range (the validator rejects empty ranges); dropped heads are named in the
    report. Kept heads are re-sorted into code-point order for binary search; each head's success
    list keeps its packing order (count descending, tie code-point ascending).
    """
    kept_successes: dict[str, list[str]] = {}
    dropped_heads: list[str] = []
    for head in heads_by_frequency:
        successes = [word for word, _count in table.get(head, ())[:successes_per_head]]
        if not successes:
            dropped_heads.append(head)
            continue
        kept_successes[head] = successes

    kept_heads = sorted(kept_successes)  # code-point lexical ascending, for binary search

    success_vocabulary: dict[str, int] = {}
    for word in sorted({word for successes in kept_successes.values() for word in successes}):
        success_vocabulary[word] = len(success_vocabulary)

    head_offsets = [0]
    head_blob_parts: list[bytes] = []
    for head in kept_heads:
        encoded = head.encode("utf-8")
        head_blob_parts.append(encoded)
        head_offsets.append(head_offsets[-1] + len(encoded))
    head_blob = b"".join(head_blob_parts)

    success_ranges = [0]
    success_ids: list[int] = []
    for head in kept_heads:
        for word in kept_successes[head]:
            success_ids.append(success_vocabulary[word])
        success_ranges.append(len(success_ids))

    success_word_offsets = [0]
    success_blob_parts: list[bytes] = []
    for word in sorted(success_vocabulary, key=success_vocabulary.get):
        encoded = word.encode("utf-8")
        success_blob_parts.append(encoded)
        success_word_offsets.append(success_word_offsets[-1] + len(encoded))
    success_blob = b"".join(success_blob_parts)

    head_count = len(kept_heads)
    pair_count = len(success_ids)
    success_vocabulary_count = len(success_vocabulary)

    section1_offset = HEADER_SIZE
    section2_offset = section1_offset + 4 * (head_count + 1)
    section3_offset = section2_offset + len(head_blob)
    section4_offset = section3_offset + 4 * (head_count + 1)
    section5_offset = section4_offset + 4 * pair_count
    section6_offset = section5_offset + 4 * (success_vocabulary_count + 1)
    file_size = section6_offset + len(success_blob)

    zero_digest_header = HEADER.pack(
        MAGIC,
        SCHEMA_ID,
        FORMAT_VERSION,
        HEADER_SIZE,
        CHECKSUM_ALGORITHM_SHA256,
        head_count,
        pair_count,
        success_vocabulary_count,
        section1_offset,
        section2_offset,
        section3_offset,
        section4_offset,
        section5_offset,
        section6_offset,
        len(head_blob),
        len(success_blob),
        file_size,
        bytes(CHECKSUM_SIZE),
    )
    raw = (
        zero_digest_header
        + struct.pack(f"<{head_count + 1}I", *head_offsets)
        + head_blob
        + struct.pack(f"<{head_count + 1}I", *success_ranges)
        + struct.pack(f"<{pair_count}I", *success_ids)
        + struct.pack(f"<{success_vocabulary_count + 1}I", *success_word_offsets)
        + success_blob
    )
    digest = hashlib.sha256(raw).digest()
    raw = raw[:CHECKSUM_OFFSET] + digest + raw[CHECKSUM_OFFSET + CHECKSUM_SIZE :]

    if len(raw) > MAX_RAW_BYTES:
        raise BigramBudgetError(f"raw table is {len(raw)} bytes; limit is {MAX_RAW_BYTES}")
    compressed = compress(raw)
    if len(compressed) > MAX_COMPRESSED_BYTES:
        raise BigramBudgetError(f"compressed table is {len(compressed)} bytes; limit is {MAX_COMPRESSED_BYTES}")

    # Self-check: the generator must never emit a file its own validator would reject.
    validate_raw(raw)

    return PackResult(
        raw=raw,
        compressed=compressed,
        head_count=head_count,
        pair_count=pair_count,
        success_vocabulary_count=success_vocabulary_count,
        dropped_heads=dropped_heads,
    )


def _decode_varint_v3(raw: bytes, offset: int, limit: int) -> tuple[int, int]:
    """dictionary_pack's canonical varint, re-raised as a bigram format error."""
    try:
        return dictionary_pack._decode_varint(raw, offset, limit)
    except dictionary_pack.DictionaryFormatError as error:
        raise BigramFormatError(f"schema 3 varint: {error}") from error


def _serialize_v3(
    kept_heads: Sequence[str],
    kept_successes: dict[str, list[str]],
    word_index: dict[str, int],
    dictionary_raw_sha256: bytes,
) -> tuple[bytes, int]:
    """The schema-3 raw image of an already-selected head set; shared by ``pack`` and ``repack``.

    Every head and every success must be present in ``word_index``: schema 3 stores no words of
    its own, so a word the dictionary does not contain cannot be represented.
    """
    head_indices: list[int] = []
    for head in kept_heads:
        if head not in word_index:
            raise BigramInputError(f"head {head!r} is not in the dictionary the table links to")
        head_indices.append(word_index[head])
    for index in range(1, len(head_indices)):
        if head_indices[index] <= head_indices[index - 1]:
            raise BigramFormatError(
                "dictionary indices of sorted heads are not strictly increasing — "
                "the dictionary order and the head order disagree"
            )

    head_count = len(kept_heads)
    block_count = (head_count + HEAD_BLOCK_V3 - 1) // HEAD_BLOCK_V3

    block_records: list[tuple[int, int, int]] = []
    head_delta_parts: list[bytes] = []
    success_parts: list[bytes] = []
    counts = bytearray()
    for block in range(block_count):
        first = block * HEAD_BLOCK_V3
        last = min(first + HEAD_BLOCK_V3, head_count)
        block_records.append(
            (head_indices[first], sum(map(len, head_delta_parts)), sum(map(len, success_parts)))
        )
        for position in range(first + 1, last):
            head_delta_parts.append(
                dictionary_pack._encode_varint(head_indices[position] - head_indices[position - 1])
            )
        for position in range(first, last):
            successes = kept_successes[kept_heads[position]]
            if not 1 <= len(successes) <= 255:
                raise BigramFormatError(f"head {kept_heads[position]!r} has {len(successes)} successes")
            counts.append(len(successes))
            for word in successes:
                if word not in word_index:
                    raise BigramInputError(
                        f"success {word!r} is not in the dictionary the table links to"
                    )
                success_parts.append(dictionary_pack._encode_varint(word_index[word]))
    head_deltas = b"".join(head_delta_parts)
    success_ids = b"".join(success_parts)
    pair_count = sum(counts)

    block_index_offset = HEADER_SIZE_V3
    head_deltas_offset = block_index_offset + BLOCK_RECORD_V3.size * block_count
    counts_offset = head_deltas_offset + len(head_deltas)
    success_ids_offset = counts_offset + head_count
    file_size = success_ids_offset + len(success_ids)

    zero_digest_header = HEADER_V3.pack(
        MAGIC,
        SCHEMA_ID_V3,
        FORMAT_VERSION_V3,
        HEADER_SIZE_V3,
        CHECKSUM_ALGORITHM_SHA256,
        head_count,
        pair_count,
        block_count,
        block_index_offset,
        head_deltas_offset,
        len(head_deltas),
        counts_offset,
        success_ids_offset,
        len(success_ids),
        file_size,
        dictionary_raw_sha256,
        bytes(8),
        bytes(CHECKSUM_SIZE),
    )
    raw = (
        zero_digest_header
        + b"".join(BLOCK_RECORD_V3.pack(*record) for record in block_records)
        + head_deltas
        + bytes(counts)
        + success_ids
    )
    digest = hashlib.sha256(raw).digest()
    return raw[:CHECKSUM_OFFSET_V3] + digest + raw[CHECKSUM_OFFSET_V3 + CHECKSUM_SIZE :], pair_count


def validate_raw_v3(
    raw: bytes,
    dictionary_words: Sequence[str],
    dictionary_raw_sha256: bytes | None = None,
) -> ParsedBigramTable:
    """Strict validator for TATBIGR schema 3 — the cross-referenced layout.

    ``dictionary_words`` is the ordered word list of the dictionary the table links to (indices
    in the file resolve against it); ``dictionary_raw_sha256``, when given, must equal the digest
    the header names — the table is valid only with that exact dictionary.
    """
    if len(raw) < HEADER_SIZE_V3:
        raise BigramFormatError(
            f"file is {len(raw)} bytes, shorter than the {HEADER_SIZE_V3}-byte header"
        )
    (
        magic,
        schema_id,
        format_version,
        header_size,
        checksum_algorithm,
        head_count,
        pair_count,
        block_count,
        block_index_offset,
        head_deltas_offset,
        head_deltas_size,
        counts_offset,
        success_ids_offset,
        success_ids_size,
        file_size,
        dictionary_sha,
        reserved,
        digest,
    ) = HEADER_V3.unpack_from(raw)

    if magic != MAGIC:
        raise BigramFormatError(f"unrecognized magic: {magic!r}")
    if schema_id != SCHEMA_ID_V3:
        raise BigramFormatError(f"unsupported schema id: {schema_id}")
    if format_version != FORMAT_VERSION_V3:
        raise BigramFormatError(f"unsupported format version: {format_version}")
    if header_size != HEADER_SIZE_V3:
        raise BigramFormatError(f"unsupported header size: {header_size}")
    if checksum_algorithm != CHECKSUM_ALGORITHM_SHA256:
        raise BigramFormatError(f"unsupported checksum algorithm: {checksum_algorithm}")
    if reserved != bytes(8):
        raise BigramFormatError("reserved header bytes are not zero")
    if dictionary_raw_sha256 is not None and dictionary_sha != dictionary_raw_sha256:
        raise BigramFormatError("the table names a different dictionary than the one supplied")

    zeroed = raw[:CHECKSUM_OFFSET_V3] + bytes(CHECKSUM_SIZE) + raw[CHECKSUM_OFFSET_V3 + CHECKSUM_SIZE :]
    if digest != hashlib.sha256(zeroed).digest():
        raise BigramFormatError("checksum mismatch")

    expected_block_index = HEADER_SIZE_V3
    expected_head_deltas = expected_block_index + BLOCK_RECORD_V3.size * block_count
    expected_counts = expected_head_deltas + head_deltas_size
    expected_success_ids = expected_counts + head_count
    expected_file_size = expected_success_ids + success_ids_size
    if (block_index_offset, head_deltas_offset, counts_offset, success_ids_offset, file_size) != (
        expected_block_index,
        expected_head_deltas,
        expected_counts,
        expected_success_ids,
        expected_file_size,
    ):
        raise BigramFormatError("non-canonical section arithmetic")
    if len(raw) != file_size:
        raise BigramFormatError(
            f"actual length {len(raw)} does not match header file_size {file_size} "
            "(truncated or trailing bytes)"
        )
    if head_count == 0:
        raise BigramFormatError("a table without heads is not representable")
    if block_count != (head_count + HEAD_BLOCK_V3 - 1) // HEAD_BLOCK_V3:
        raise BigramFormatError("block count disagrees with head count")

    dictionary_size = len(dictionary_words)
    counts = raw[counts_offset : counts_offset + head_count]
    if min(counts) < 1:
        raise BigramFormatError("a head with an empty success range — the packer never emits one")
    if sum(counts) != pair_count:
        raise BigramFormatError("success counts do not add up to pair_count")

    head_words: list[str] = []
    successes_by_head: dict[str, list[str]] = {}
    pair_cursor = success_ids_offset
    previous_index = -1
    for block in range(block_count):
        first_index, delta_offset, success_offset = BLOCK_RECORD_V3.unpack_from(
            raw, block_index_offset + BLOCK_RECORD_V3.size * block
        )
        if first_index >= dictionary_size:
            raise BigramFormatError(f"block {block} first index is >= dictionary size")
        if block == 0 and (delta_offset != 0 or success_offset != 0):
            raise BigramFormatError("the first block's stream offsets must be zero")
        if first_index <= previous_index:
            raise BigramFormatError("block first indices are not strictly increasing")
        first = block * HEAD_BLOCK_V3
        last = min(first + HEAD_BLOCK_V3, head_count)
        delta_end = (
            BLOCK_RECORD_V3.unpack_from(
                raw, block_index_offset + BLOCK_RECORD_V3.size * (block + 1)
            )[1]
            if block + 1 < block_count
            else head_deltas_size
        )
        success_end = (
            BLOCK_RECORD_V3.unpack_from(
                raw, block_index_offset + BLOCK_RECORD_V3.size * (block + 1)
            )[2]
            if block + 1 < block_count
            else success_ids_size
        )
        if pair_cursor != success_ids_offset + success_offset:
            raise BigramFormatError(f"block {block} success stream offset is not where decoding stands")

        index = first_index
        cursor = head_deltas_offset + delta_offset
        delta_limit = head_deltas_offset + delta_end
        block_indices = [index]
        for _ in range(first + 1, last):
            delta, cursor = _decode_varint_v3(raw, cursor, delta_limit)
            if delta < 1:
                raise BigramFormatError("head index delta is not positive")
            index += delta
            if index >= dictionary_size:
                raise BigramFormatError("head index is >= dictionary size")
            block_indices.append(index)
        if cursor != delta_limit:
            raise BigramFormatError(f"block {block} head delta stream does not end on its boundary")

        for position, head_dict_index in enumerate(block_indices):
            if head_dict_index <= previous_index:
                raise BigramFormatError("head indices are not strictly increasing")
            previous_index = head_dict_index
            head = dictionary_words[head_dict_index]
            head_words.append(head)
            successes: list[str] = []
            for _ in range(counts[first + position]):
                success_index, pair_cursor = _decode_varint_v3(
                    raw, pair_cursor, success_ids_offset + success_end
                )
                if success_index >= dictionary_size:
                    raise BigramFormatError("success index is >= dictionary size")
                successes.append(dictionary_words[success_index])
            successes_by_head[head] = successes
    if pair_cursor != success_ids_offset + success_ids_size:
        raise BigramFormatError("success id stream does not end exactly at the file end section")

    return ParsedBigramTable(
        head_words=head_words,
        success_vocabulary=sorted({word for s in successes_by_head.values() for word in s}),
        successes_by_head=successes_by_head,
    )


def pack_bigram_table_v3(
    heads_by_frequency: Sequence[str],
    table: dict[str, list[tuple[str, int]]],
    successes_per_head: int,
    word_index: dict[str, int],
    dictionary_raw_sha256: bytes,
) -> PackResult:
    """Schema-3 sibling of [pack_bigram_table]: same head selection and drop rules, indices into
    the linked dictionary instead of word blobs."""
    kept_successes: dict[str, list[str]] = {}
    dropped_heads: list[str] = []
    for head in heads_by_frequency:
        successes = [word for word, _count in table.get(head, ())[:successes_per_head]]
        if not successes:
            dropped_heads.append(head)
            continue
        kept_successes[head] = successes

    kept_heads = sorted(kept_successes)  # code-point lexical ascending, as schema 2
    raw, pair_count = _serialize_v3(kept_heads, kept_successes, word_index, dictionary_raw_sha256)

    if len(raw) > MAX_RAW_BYTES:
        raise BigramBudgetError(f"raw table is {len(raw)} bytes; limit is {MAX_RAW_BYTES}")
    compressed = compress(raw)
    if len(compressed) > MAX_COMPRESSED_BYTES:
        raise BigramBudgetError(
            f"compressed table is {len(compressed)} bytes; limit is {MAX_COMPRESSED_BYTES}"
        )

    # Self-check against the real dictionary, exactly like schema 2's self-check.
    ordered_words = sorted(word_index, key=word_index.get)
    validate_raw_v3(raw, ordered_words, dictionary_raw_sha256)

    return PackResult(
        raw=raw,
        compressed=compressed,
        head_count=len(kept_heads),
        pair_count=pair_count,
        success_vocabulary_count=len({w for s in kept_successes.values() for w in s}),
        dropped_heads=dropped_heads,
    )



def read_extra_heads(path: Path, vocabulary: frozenset[str]) -> list[str]:
    """Words named explicitly as heads, whatever their frequency rank (``--extra-heads``).

    One word per line; ``#`` starts a comment (the evidence for the word may sit next to it);
    blank lines are ignored. A word missing from the bundled vocabulary stops the generation:
    the list grants successors, it does not add words to the dictionary. Duplicates are dropped.
    """
    words: list[str] = []
    seen: set[str] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        word = line.split("#", 1)[0].strip()
        if not word:
            continue
        if word not in vocabulary:
            raise BigramInputError(
                f"{path.name}:{line_number}: {word!r} is not in the shipped vocabulary; "
                "the extra-head list promotes shipped words, it does not add new ones"
            )
        if word in seen:
            continue
        seen.add(word)
        words.append(word)
    return words


def run_pack(
    train_paths: Sequence[Path],
    asset_path: Path,
    heads: int,
    successes_per_head: int,
    shards: int,
    language: coverage.Language = coverage.DEFAULT_LANGUAGE,
    extra_heads: Sequence[str] = (),
    schema: int = SCHEMA_ID_V3,
) -> tuple[PackResult, dict[str, object]]:
    started = time.monotonic()
    vocabulary, frequencies = read_shipped_vocabulary(asset_path, language)
    ordered_heads = select_heads(frequencies, heads)
    # Appended, not merged by frequency: this list decides SET membership only, and
    # ``pack_bigram_table`` re-sorts the kept heads into code-point order for the file. Words the
    # cutoff already reached are not repeated — a head named twice would be packed twice and the
    # validator would reject the file for a non-ascending head blob.
    already_heads = set(ordered_heads)
    promoted = [word for word in extra_heads if word not in already_heads]
    ordered_heads = ordered_heads + promoted
    table = count_pairs(
        train_paths,
        frozenset(ordered_heads),
        vocabulary,
        shards,
        stats=[],
        alphabet=language.alphabet,
    )
    if schema == SCHEMA_ID:
        result = pack_bigram_table(ordered_heads, table, successes_per_head)
    elif schema == SCHEMA_ID_V3:
        # Schema 3 stores dictionary indices, so the packer reads the linked dictionary's ordered
        # word list and raw digest, and stops on the first head or success the dictionary does
        # not contain.
        parsed_dictionary = dictionary_pack.validate_asset(
            asset_path.read_bytes(), language=language
        )
        word_index = {word: index for index, word in enumerate(parsed_dictionary.words)}
        result = pack_bigram_table_v3(
            ordered_heads,
            table,
            successes_per_head,
            word_index,
            hashlib.sha256(parsed_dictionary.raw).digest(),
        )
    else:
        raise SystemExit(f"unsupported schema: {schema}")
    report = {
        "language": language.tag,
        "schema": schema,
        "requested_heads": heads,
        "successes_per_head": successes_per_head,
        "extra_heads_requested": list(extra_heads),
        "extra_heads_promoted": promoted,
        "extra_heads_dropped_for_no_pairs": [
            word for word in promoted if word in set(result.dropped_heads)
        ],
        "actual_head_count": result.head_count,
        "dropped_heads": result.dropped_heads,
        "pair_count": result.pair_count,
        "success_vocabulary_count": result.success_vocabulary_count,
        "raw_bytes": len(result.raw),
        "compressed_bytes": len(result.compressed),
        "raw_sha256": hashlib.sha256(result.raw).hexdigest(),
        "compressed_sha256": hashlib.sha256(result.compressed).hexdigest(),
        "peak_rss_bytes": peak_rss_bytes(),
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "shards": shards,
    }
    return result, report


def run_repack(
    v2_asset_path: Path,
    dictionary_asset_path: Path,
    language: coverage.Language,
) -> tuple[PackResult, dict[str, object]]:
    """Byte-stable schema 2 → schema 3 repack: no corpus needed, content carried over verbatim.

    The v2 table is strict-validated first; the v3 image is built from its parsed content and
    then strict-validated back against the dictionary, and the two parses are compared head by
    head — a repack whose output reads differently from its input is never written.
    """
    started = time.monotonic()
    parsed_v2 = validate_raw(decompress(v2_asset_path.read_bytes()))
    parsed_dictionary = dictionary_pack.validate_asset(
        dictionary_asset_path.read_bytes(), language=language
    )
    word_index = {word: index for index, word in enumerate(parsed_dictionary.words)}
    dictionary_sha = hashlib.sha256(parsed_dictionary.raw).digest()

    kept_heads = list(parsed_v2.head_words)  # already code-point ascending
    raw, pair_count = _serialize_v3(
        kept_heads, parsed_v2.successes_by_head, word_index, dictionary_sha
    )
    if len(raw) > MAX_RAW_BYTES:
        raise BigramBudgetError(f"raw table is {len(raw)} bytes; limit is {MAX_RAW_BYTES}")
    compressed = compress(raw)
    if len(compressed) > MAX_COMPRESSED_BYTES:
        raise BigramBudgetError(
            f"compressed table is {len(compressed)} bytes; limit is {MAX_COMPRESSED_BYTES}"
        )

    parsed_v3 = validate_raw_v3(raw, parsed_dictionary.words, dictionary_sha)
    if parsed_v3.head_words != parsed_v2.head_words:
        raise BigramFormatError("repack round-trip: head words differ")
    if parsed_v3.successes_by_head != parsed_v2.successes_by_head:
        raise BigramFormatError("repack round-trip: successes differ")

    result = PackResult(
        raw=raw,
        compressed=compressed,
        head_count=len(parsed_v2.head_words),
        pair_count=pair_count,
        success_vocabulary_count=len(parsed_v2.success_vocabulary),
    )
    report = {
        "language": language.tag,
        "schema": SCHEMA_ID_V3,
        "repacked_from": str(v2_asset_path),
        "dictionary_raw_sha256": dictionary_sha.hex(),
        "actual_head_count": result.head_count,
        "pair_count": result.pair_count,
        "success_vocabulary_count": result.success_vocabulary_count,
        "raw_bytes": len(result.raw),
        "compressed_bytes": len(result.compressed),
        "raw_sha256": hashlib.sha256(result.raw).hexdigest(),
        "compressed_sha256": hashlib.sha256(result.compressed).hexdigest(),
        "elapsed_seconds": round(time.monotonic() - started, 3),
    }
    return result, report


def _atomic_write(path: Path, data: bytes) -> None:
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_bytes(data)
    temp.replace(path)


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    pack = subparsers.add_parser("pack", help="build the real TATBIGR asset from a corpus")
    pack.add_argument("--train", nargs="+", required=True, type=Path)
    pack.add_argument("--asset", required=True, type=Path)
    pack.add_argument("--heads", type=int, required=True)
    pack.add_argument("--successes-per-head", type=int, required=True)
    pack.add_argument("--shards", type=int, default=8)
    pack.add_argument(
        "--schema",
        type=int,
        choices=(SCHEMA_ID, SCHEMA_ID_V3),
        default=SCHEMA_ID_V3,
        help="2 is kept for golden/history (SIZE-1 style); the shipped format is 3",
    )
    pack.add_argument(
        "--extra-heads",
        type=Path,
        help="list of words to make heads whatever their unigram rank (one per line, # comments)",
    )
    pack.add_argument("--out-raw", required=True, type=Path)
    pack.add_argument("--out-compressed", required=True, type=Path)
    pack.add_argument("--report", type=Path)
    pack.add_argument(
        "--language", default=coverage.DEFAULT_LANGUAGE.tag, choices=sorted(coverage.LANGUAGES)
    )
    repack = subparsers.add_parser(
        "repack", help="schema 2 → schema 3, corpus-free, content verified identical"
    )
    repack.add_argument("--v2", required=True, type=Path, help="the schema-2 .tatbigr.zlib asset")
    repack.add_argument(
        "--dictionary", required=True, type=Path, help="the linked TATDICT .tdict.zlib asset"
    )
    repack.add_argument("--out-raw", required=True, type=Path)
    repack.add_argument("--out-compressed", required=True, type=Path)
    repack.add_argument("--report", type=Path)
    repack.add_argument(
        "--language", default=coverage.DEFAULT_LANGUAGE.tag, choices=sorted(coverage.LANGUAGES)
    )
    return parser


def main(argv: Sequence[str] | None = None, stream: TextIO = sys.stdout) -> int:
    arguments = create_argument_parser().parse_args(argv)
    language = coverage.language_for(arguments.language)
    if arguments.command == "repack":
        result, report = run_repack(arguments.v2, arguments.dictionary, language)
    else:
        if arguments.shards < 1:
            raise SystemExit("shards must be positive")
        extra_heads: list[str] = []
        if arguments.extra_heads is not None:
            vocabulary, _frequencies = read_shipped_vocabulary(arguments.asset, language)
            extra_heads = read_extra_heads(arguments.extra_heads, vocabulary)
        result, report = run_pack(
            arguments.train,
            arguments.asset,
            arguments.heads,
            arguments.successes_per_head,
            arguments.shards,
            language,
            extra_heads,
            arguments.schema,
        )
    text = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True)
    if arguments.report is not None:
        arguments.report.write_text(text + "\n", encoding="utf-8")
    print(text, file=stream)
    # Written only after both the raw image and its compression have been validated above, so
    # a partial or over-cap asset never lands on disk.
    _atomic_write(arguments.out_raw, result.raw)
    _atomic_write(arguments.out_compressed, result.compressed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
