#!/usr/bin/env python3
"""Build the combined Tatar bigram/trigram TATBIGR schema-4 asset.

``pack`` reads the training corpora, a linked dictionary and the raw schema-3 bigram table
produced by ``bigram_asset_pack.py``. It writes deterministic raw and zlib images plus a JSON
report. The production configuration is fixed below; evaluation files are never inputs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import zlib
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence, TextIO

sys.path.insert(0, str(Path(__file__).resolve().parent))

import bigram_asset_pack  # noqa: E402
import bigram_pack  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack  # noqa: E402

SCHEMA_ID_V4 = 4
FORMAT_VERSION_V4 = 1
HEADER_SIZE_V4 = 224
CHECKSUM_OFFSET_V4 = bigram_asset_pack.CHECKSUM_OFFSET_V3
CHECKSUM_SIZE = bigram_asset_pack.CHECKSUM_SIZE
TRIGRAM_BLOCK_SIZE = 64
PACKED_SUCCESSORS = 8
KEPT_TRIGRAM_SUCCESSORS = 16
MAX_CONTEXTS = 50_000
MIN_CONTEXT_TOTAL = 1
BETA = 20.0
BETA_MILLIONTHS = 20_000_000
MAX_U32 = (1 << 32) - 1
MAX_U64 = (1 << 64) - 1

HEADER_FORMAT_V4 = bigram_asset_pack.HEADER_FORMAT_V3 + "12I48s"
HEADER_V4 = struct.Struct(HEADER_FORMAT_V4)
EXTENSION_V4 = struct.Struct("<12I")
BLOCK_RECORD_V4 = struct.Struct("<4I")
assert HEADER_V4.size == HEADER_SIZE_V4
assert EXTENSION_V4.size == 48
assert BLOCK_RECORD_V4.size == 16

MAX_RAW_BYTES = bigram_asset_pack.MAX_RAW_BYTES
MAX_COMPRESSED_BYTES = bigram_asset_pack.MAX_COMPRESSED_BYTES


class TrigramFormatError(ValueError):
    """The bytes are not a valid combined TATBIGR schema-4 file."""


class TrigramInputError(ValueError):
    """An input cannot be represented by the selected model configuration."""


class TrigramBudgetError(ValueError):
    """A combined image exceeds the shared asset safety cap."""


@dataclass
class ParsedTrigramTable:
    """Validated one-word and two-word predictions in their stored ranking order."""

    bigram: bigram_asset_pack.ParsedBigramTable
    successes_by_context: dict[tuple[str, str], list[str]]
    context_count: int
    pair_count: int
    beta_millionths: int
    min_context_total: int


@dataclass
class PackResult:
    raw: bytes
    compressed: bytes
    context_count: int
    pair_count: int


@dataclass
class TrainingSide:
    """The two deterministic corpus passes needed for interpolation."""

    ordered_contexts: list[int]
    context_totals: dict[int, int]
    trigrams: dict[int, list[tuple[int, int]]]
    bigrams: dict[int, dict[int, int]]
    bigram_totals: dict[int, int]
    unique_context_count: int
    eligible_trigram_occurrences: int


def _encode_varint(value: int, maximum: int, label: str) -> bytes:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= maximum:
        raise TrigramInputError(f"{label} is not an unsigned {maximum.bit_length()}-bit integer")
    encoded = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            encoded.append(byte | 0x80)
        else:
            encoded.append(byte)
            return bytes(encoded)


def _encode_varint_u64(value: int) -> bytes:
    return _encode_varint(value, MAX_U64, "context delta")


def _encode_varint_u32(value: int) -> bytes:
    return _encode_varint(value, MAX_U32, "dictionary index")


def _decode_varint(
    raw: bytes, offset: int, limit: int, maximum: int, label: str
) -> tuple[int, int]:
    """Read one minimal unsigned varint without accepting an overflowing final byte."""
    bits = maximum.bit_length()
    max_bytes = (bits + 6) // 7
    value = 0
    cursor = offset
    for byte_index in range(max_bytes):
        if cursor >= limit:
            raise TrigramFormatError(f"{label}: truncated varint")
        byte = raw[cursor]
        cursor += 1
        if byte_index == max_bytes - 1 and byte > (maximum >> (7 * byte_index)):
            raise TrigramFormatError(f"{label}: varint exceeds {bits} bits")
        value |= (byte & 0x7F) << (7 * byte_index)
        if not byte & 0x80:
            if cursor - offset != len(_encode_varint(value, maximum, label)):
                raise TrigramFormatError(f"{label}: non-canonical overlong varint")
            return value, cursor
    raise TrigramFormatError(f"{label}: varint exceeds {bits} bits")


def _decode_varint_u64(raw: bytes, offset: int, limit: int) -> tuple[int, int]:
    return _decode_varint(raw, offset, limit, MAX_U64, "trigram context delta")


def _decode_varint_u32(raw: bytes, offset: int, limit: int) -> tuple[int, int]:
    return _decode_varint(raw, offset, limit, MAX_U32, "trigram successor")


def _validate_source_bigram(
    raw: bytes, dictionary_words: Sequence[str], dictionary_raw_sha256: bytes | None
) -> bigram_asset_pack.ParsedBigramTable:
    try:
        return bigram_asset_pack.validate_raw_v3(
            raw, dictionary_words, dictionary_raw_sha256
        )
    except bigram_asset_pack.BigramFormatError as error:
        raise TrigramInputError(f"source schema-3 table: {error}") from error


def pack_trigram_table_v4(
    source_bigram_raw: bytes,
    dictionary_words: Sequence[str],
    dictionary_raw_sha256: bytes,
    ranked_successes: dict[int, Sequence[int]],
    beta_millionths: int = BETA_MILLIONTHS,
    min_context_total: int = MIN_CONTEXT_TOTAL,
) -> PackResult:
    """Combine a validated schema-3 image with numeric, pre-ranked trigram contexts."""
    source = _validate_source_bigram(
        source_bigram_raw, dictionary_words, dictionary_raw_sha256
    )
    width = len(dictionary_words)
    if not 0 < width <= MAX_U32:
        raise TrigramInputError("dictionary word count does not fit schema 4")
    if not 0 <= beta_millionths <= MAX_U32 or not 0 < min_context_total <= MAX_U32:
        raise TrigramInputError("trigram configuration field does not fit u32")
    if not ranked_successes:
        raise TrigramInputError("the combined table requires at least one trigram context")

    contexts = sorted(ranked_successes)
    if len(contexts) > MAX_U32:
        raise TrigramInputError("trigram context count does not fit u32")
    block_count = (len(contexts) + TRIGRAM_BLOCK_SIZE - 1) // TRIGRAM_BLOCK_SIZE
    block_records: list[bytes] = []
    deltas = bytearray()
    counts = bytearray()
    successors = bytearray()
    previous_context = -1
    for block_start in range(0, len(contexts), TRIGRAM_BLOCK_SIZE):
        block = contexts[block_start : block_start + TRIGRAM_BLOCK_SIZE]
        first_key = block[0]
        if isinstance(first_key, bool) or not isinstance(first_key, int) or first_key < 0:
            raise TrigramInputError("trigram context key must be a nonnegative integer")
        first, second = divmod(first_key, width)
        if first >= width or first > MAX_U32:
            raise TrigramInputError(f"trigram context {first_key} is outside the dictionary")
        block_records.append(
            BLOCK_RECORD_V4.pack(first, second, len(deltas), len(successors))
        )
        previous = first_key
        for context in block:
            if isinstance(context, bool) or not isinstance(context, int) or context < 0:
                raise TrigramInputError("trigram context key must be a nonnegative integer")
            first, second = divmod(context, width)
            if first >= width or first > MAX_U32:
                raise TrigramInputError(f"trigram context {context} is outside the dictionary")
            if context <= previous_context:
                raise TrigramInputError("trigram contexts are not strictly increasing")
            previous_context = context
            if context != first_key:
                delta = context - previous
                if delta > MAX_U64:
                    raise TrigramInputError("trigram context delta exceeds u64")
                deltas.extend(_encode_varint_u64(delta))
                previous = context
            ranked = ranked_successes[context]
            if not 1 <= len(ranked) <= PACKED_SUCCESSORS:
                raise TrigramInputError(
                    f"trigram context {context} has {len(ranked)} successors; expected 1..8"
                )
            seen: set[int] = set()
            counts.append(len(ranked))
            for word_id in ranked:
                if (
                    isinstance(word_id, bool)
                    or not isinstance(word_id, int)
                    or not 0 <= word_id < width
                ):
                    raise TrigramInputError(
                        f"trigram successor {word_id!r} is outside the dictionary"
                    )
                if word_id in seen:
                    raise TrigramInputError(
                        f"trigram context {context} repeats successor {word_id}"
                    )
                seen.add(word_id)
                successors.extend(_encode_varint_u32(word_id))

    bigram_end = len(source_bigram_raw) + HEADER_SIZE_V4 - bigram_asset_pack.HEADER_SIZE_V3
    trigram_block_index_offset = bigram_end
    trigram_deltas_offset = (
        trigram_block_index_offset + BLOCK_RECORD_V4.size * block_count
    )
    trigram_counts_offset = trigram_deltas_offset + len(deltas)
    trigram_success_ids_offset = trigram_counts_offset + len(contexts)
    file_size = trigram_success_ids_offset + len(successors)
    pair_count = sum(counts)
    if file_size > MAX_U32 or pair_count > MAX_U32:
        raise TrigramInputError("combined schema-4 image exceeds u32 arithmetic")

    header = bytearray(source_bigram_raw[: bigram_asset_pack.HEADER_SIZE_V3])
    struct.pack_into("<H", header, 8, SCHEMA_ID_V4)
    struct.pack_into("<H", header, 12, HEADER_SIZE_V4)
    struct.pack_into("<I", header, 52, file_size)
    for offset in (28, 32, 40, 44):
        old_offset = struct.unpack_from("<I", header, offset)[0]
        struct.pack_into(
            "<I", header, offset, old_offset + HEADER_SIZE_V4 - bigram_asset_pack.HEADER_SIZE_V3
        )
    header[CHECKSUM_OFFSET_V4 : CHECKSUM_OFFSET_V4 + CHECKSUM_SIZE] = bytes(CHECKSUM_SIZE)
    extension = EXTENSION_V4.pack(
        len(contexts),
        pair_count,
        block_count,
        trigram_block_index_offset,
        trigram_deltas_offset,
        len(deltas),
        trigram_counts_offset,
        trigram_success_ids_offset,
        len(successors),
        width,
        beta_millionths,
        min_context_total,
    )
    raw = (
        bytes(header)
        + extension
        + bytes(48)
        + source_bigram_raw[bigram_asset_pack.HEADER_SIZE_V3 :]
        + b"".join(block_records)
        + bytes(deltas)
        + bytes(counts)
        + bytes(successors)
    )
    digest = hashlib.sha256(raw).digest()
    raw = raw[:CHECKSUM_OFFSET_V4] + digest + raw[CHECKSUM_OFFSET_V4 + CHECKSUM_SIZE :]

    if len(raw) > MAX_RAW_BYTES:
        raise TrigramBudgetError(
            f"raw combined table is {len(raw)} bytes; limit is {MAX_RAW_BYTES}"
        )
    compressed = bigram_asset_pack.compress(raw)
    if len(compressed) > MAX_COMPRESSED_BYTES:
        raise TrigramBudgetError(
            f"compressed combined table is {len(compressed)} bytes; "
            f"limit is {MAX_COMPRESSED_BYTES}"
        )
    parsed = validate_raw_v4(raw, dictionary_words, dictionary_raw_sha256)
    if parsed.bigram.head_words != source.head_words or (
        parsed.bigram.successes_by_head != source.successes_by_head
    ):
        raise TrigramFormatError("the combined image changed the source bigram content")
    return PackResult(raw, compressed, len(contexts), pair_count)


def validate_raw_v4(
    raw: bytes,
    dictionary_words: Sequence[str],
    dictionary_raw_sha256: bytes | None = None,
) -> ParsedTrigramTable:
    """Strictly validate both parts and every section boundary of a schema-4 image."""
    if len(raw) < HEADER_SIZE_V4:
        raise TrigramFormatError(
            f"file is {len(raw)} bytes, shorter than the {HEADER_SIZE_V4}-byte header"
        )
    (
        magic,
        schema_id,
        format_version,
        header_size,
        checksum_algorithm,
        head_count,
        _bigram_pair_count,
        bigram_block_count,
        bigram_block_index_offset,
        bigram_deltas_offset,
        bigram_deltas_size,
        bigram_counts_offset,
        bigram_success_ids_offset,
        bigram_success_ids_size,
        file_size,
        dictionary_sha,
        bigram_reserved,
        digest,
    ) = bigram_asset_pack.HEADER_V3.unpack_from(raw)
    if magic != bigram_asset_pack.MAGIC:
        raise TrigramFormatError(f"unrecognized magic: {magic!r}")
    if schema_id != SCHEMA_ID_V4:
        raise TrigramFormatError(f"unsupported schema id: {schema_id}")
    if format_version != FORMAT_VERSION_V4:
        raise TrigramFormatError(f"unsupported format version: {format_version}")
    if header_size != HEADER_SIZE_V4:
        raise TrigramFormatError(f"unsupported header size: {header_size}")
    if checksum_algorithm != bigram_asset_pack.CHECKSUM_ALGORITHM_SHA256:
        raise TrigramFormatError(f"unsupported checksum algorithm: {checksum_algorithm}")
    if bigram_reserved != bytes(8) or raw[176:224] != bytes(48):
        raise TrigramFormatError("reserved header bytes are not zero")
    if dictionary_raw_sha256 is not None and dictionary_sha != dictionary_raw_sha256:
        raise TrigramFormatError("the table names a different dictionary than the one supplied")
    zeroed = raw[:CHECKSUM_OFFSET_V4] + bytes(CHECKSUM_SIZE) + raw[CHECKSUM_OFFSET_V4 + CHECKSUM_SIZE :]
    if digest != hashlib.sha256(zeroed).digest():
        raise TrigramFormatError("checksum mismatch")

    (
        context_count,
        pair_count,
        block_count,
        block_index_offset,
        deltas_offset,
        deltas_size,
        counts_offset,
        success_ids_offset,
        success_ids_size,
        dictionary_word_count,
        beta_millionths,
        min_context_total,
    ) = EXTENSION_V4.unpack_from(raw, bigram_asset_pack.HEADER_SIZE_V3)
    width = len(dictionary_words)
    if dictionary_word_count != width or width == 0:
        raise TrigramFormatError("dictionary word count does not match the linked dictionary")
    if context_count == 0:
        raise TrigramFormatError("a trigram table without contexts is not representable")
    if block_count != (context_count + TRIGRAM_BLOCK_SIZE - 1) // TRIGRAM_BLOCK_SIZE:
        raise TrigramFormatError("trigram block count disagrees with context count")

    bigram_shift = HEADER_SIZE_V4 - bigram_asset_pack.HEADER_SIZE_V3
    expected_bigram_blocks = HEADER_SIZE_V4
    expected_bigram_deltas = (
        expected_bigram_blocks + bigram_asset_pack.BLOCK_RECORD_V3.size * bigram_block_count
    )
    expected_bigram_counts = expected_bigram_deltas + bigram_deltas_size
    expected_bigram_successes = expected_bigram_counts + head_count
    bigram_end = expected_bigram_successes + bigram_success_ids_size
    if (
        bigram_block_index_offset,
        bigram_deltas_offset,
        bigram_counts_offset,
        bigram_success_ids_offset,
    ) != (
        expected_bigram_blocks,
        expected_bigram_deltas,
        expected_bigram_counts,
        expected_bigram_successes,
    ):
        raise TrigramFormatError("non-canonical bigram section arithmetic")
    if block_index_offset != bigram_end:
        raise TrigramFormatError("the bigram payload does not end at the trigram block index")
    expected_deltas = block_index_offset + BLOCK_RECORD_V4.size * block_count
    expected_counts = expected_deltas + deltas_size
    expected_successes = expected_counts + context_count
    expected_file_size = expected_successes + success_ids_size
    if (deltas_offset, counts_offset, success_ids_offset, file_size) != (
        expected_deltas,
        expected_counts,
        expected_successes,
        expected_file_size,
    ):
        raise TrigramFormatError("non-canonical trigram section arithmetic")
    if len(raw) != file_size:
        raise TrigramFormatError(
            f"actual length {len(raw)} does not match header file_size {file_size}"
        )

    projected_header = bytearray(raw[: bigram_asset_pack.HEADER_SIZE_V3])
    struct.pack_into("<H", projected_header, 8, bigram_asset_pack.SCHEMA_ID_V3)
    struct.pack_into("<H", projected_header, 12, bigram_asset_pack.HEADER_SIZE_V3)
    struct.pack_into("<I", projected_header, 52, bigram_end - bigram_shift)
    for offset in (28, 32, 40, 44):
        value = struct.unpack_from("<I", projected_header, offset)[0]
        struct.pack_into("<I", projected_header, offset, value - bigram_shift)
    projected_header[
        bigram_asset_pack.CHECKSUM_OFFSET_V3 :
        bigram_asset_pack.CHECKSUM_OFFSET_V3 + CHECKSUM_SIZE
    ] = bytes(CHECKSUM_SIZE)
    projected = bytes(projected_header) + raw[HEADER_SIZE_V4:bigram_end]
    projected_digest = hashlib.sha256(projected).digest()
    projected = (
        projected[: bigram_asset_pack.CHECKSUM_OFFSET_V3]
        + projected_digest
        + projected[bigram_asset_pack.CHECKSUM_OFFSET_V3 + CHECKSUM_SIZE :]
    )
    try:
        parsed_bigram = bigram_asset_pack.validate_raw_v3(
            projected, dictionary_words, dictionary_sha
        )
    except bigram_asset_pack.BigramFormatError as error:
        raise TrigramFormatError(f"bigram part: {error}") from error

    counts = raw[counts_offset : counts_offset + context_count]
    if any(not 1 <= count <= PACKED_SUCCESSORS for count in counts):
        raise TrigramFormatError("a trigram context has a successor count outside 1..8")
    if sum(counts) != pair_count:
        raise TrigramFormatError("trigram successor counts do not add up to pair_count")

    successes_by_context: dict[tuple[str, str], list[str]] = {}
    delta_cursor = deltas_offset
    success_cursor = success_ids_offset
    previous_context = -1
    for block_number in range(block_count):
        record_offset = block_index_offset + block_number * BLOCK_RECORD_V4.size
        first, second, delta_relative, success_relative = BLOCK_RECORD_V4.unpack_from(
            raw, record_offset
        )
        if first >= width or second >= width:
            raise TrigramFormatError("block first context is outside the dictionary")
        if delta_relative != delta_cursor - deltas_offset:
            raise TrigramFormatError("trigram delta stream is not contiguous")
        if success_relative != success_cursor - success_ids_offset:
            raise TrigramFormatError("trigram successor stream is not contiguous")
        delta_end = (
            BLOCK_RECORD_V4.unpack_from(raw, record_offset + BLOCK_RECORD_V4.size)[2]
            if block_number + 1 < block_count
            else deltas_size
        )
        success_end = (
            BLOCK_RECORD_V4.unpack_from(raw, record_offset + BLOCK_RECORD_V4.size)[3]
            if block_number + 1 < block_count
            else success_ids_size
        )
        block_delta_limit = deltas_offset + delta_end
        block_success_limit = success_ids_offset + success_end
        context = first * width + second
        if context <= previous_context:
            raise TrigramFormatError("trigram contexts are not strictly increasing")
        first_position = block_number * TRIGRAM_BLOCK_SIZE
        block_length = min(TRIGRAM_BLOCK_SIZE, context_count - first_position)
        for position in range(block_length):
            if position:
                delta, delta_cursor = _decode_varint_u64(
                    raw, delta_cursor, block_delta_limit
                )
                if delta == 0:
                    raise TrigramFormatError("trigram context delta is zero")
                context += delta
            if context <= previous_context:
                raise TrigramFormatError("trigram contexts are not strictly increasing")
            previous_context = context
            context_first, context_second = divmod(context, width)
            if context_first >= width:
                raise TrigramFormatError("trigram context is outside the dictionary")
            key = (
                dictionary_words[context_first],
                dictionary_words[context_second],
            )
            if key in successes_by_context:
                raise TrigramFormatError("duplicate word-level trigram context")
            context_successes: list[str] = []
            for _ in range(counts[first_position + position]):
                word_id, success_cursor = _decode_varint_u32(
                    raw, success_cursor, block_success_limit
                )
                if word_id >= width:
                    raise TrigramFormatError("trigram successor is outside the dictionary")
                context_successes.append(dictionary_words[word_id])
            successes_by_context[key] = context_successes
        if delta_cursor != block_delta_limit:
            raise TrigramFormatError("trigram block delta stream has trailing bytes")
        if success_cursor != block_success_limit:
            raise TrigramFormatError("trigram block successor stream has trailing bytes")
    if delta_cursor != deltas_offset + deltas_size:
        raise TrigramFormatError("trigram delta stream does not end at its section boundary")
    if success_cursor != success_ids_offset + success_ids_size:
        raise TrigramFormatError("trigram successor stream does not end at the file end")
    if len(successes_by_context) != context_count:
        raise TrigramFormatError("decoded context count disagrees with the header")

    return ParsedTrigramTable(
        bigram=parsed_bigram,
        successes_by_context=successes_by_context,
        context_count=context_count,
        pair_count=pair_count,
        beta_millionths=beta_millionths,
        min_context_total=min_context_total,
    )


def count_training_side(
    train_paths: Sequence[Path],
    words: Sequence[str],
    language: coverage.Language = coverage.DEFAULT_LANGUAGE,
    recover_punctuation: bool = True,
    max_contexts: int = MAX_CONTEXTS,
    kept_trigram_successors: int = KEPT_TRIGRAM_SUCCESSORS,
) -> TrainingSide:
    """Count context totals, selected trigrams and full selected-head bigram distributions."""
    if max_contexts < 1 or kept_trigram_successors < 1:
        raise TrigramInputError("context and successor limits must be positive")
    vocabulary = frozenset(words)
    word_index = {word: position for position, word in enumerate(words)}
    width = len(words)
    totals: Counter[int] = Counter()
    eligible = 0
    for path in train_paths:
        for sentence in bigram_pack.iter_sentences(path):
            tokens = bigram_pack.normalized_tokens(
                sentence, language.alphabet, recover_punctuation=recover_punctuation
            )
            for position in range(len(tokens) - 2):
                first, second, third = tokens[position : position + 3]
                if (
                    first not in vocabulary
                    or second not in vocabulary
                    or third not in vocabulary
                    or second == third
                ):
                    continue
                totals[word_index[first] * width + word_index[second]] += 1
                eligible += 1

    ordered = sorted(totals, key=lambda context: (-totals[context], context))[:max_contexts]
    selected = frozenset(ordered)
    selected_totals = {context: totals[context] for context in ordered}
    required_bigram_heads = frozenset(context % width for context in ordered)
    trigram_counts: dict[int, Counter[int]] = {}
    bigram_counts: dict[int, Counter[int]] = {}
    for path in train_paths:
        for sentence in bigram_pack.iter_sentences(path):
            tokens = bigram_pack.normalized_tokens(
                sentence, language.alphabet, recover_punctuation=recover_punctuation
            )
            for position in range(len(tokens) - 1):
                second, third = tokens[position : position + 2]
                if second in vocabulary and third in vocabulary and second != third:
                    second_id = word_index[second]
                    if second_id in required_bigram_heads:
                        bigram_counts.setdefault(second_id, Counter())[word_index[third]] += 1
                if position + 2 >= len(tokens):
                    continue
                first, second, third = tokens[position : position + 3]
                if (
                    first not in vocabulary
                    or second not in vocabulary
                    or third not in vocabulary
                    or second == third
                ):
                    continue
                context = word_index[first] * width + word_index[second]
                if context in selected:
                    trigram_counts.setdefault(context, Counter())[word_index[third]] += 1

    if set(trigram_counts) != selected:
        raise TrigramInputError("the two corpus passes produced different selected contexts")
    for context, successors in trigram_counts.items():
        if sum(successors.values()) != selected_totals[context]:
            raise TrigramInputError(f"trigram count mismatch for context {context}")
    trimmed = {
        context: sorted(
            successors.items(), key=lambda item: (-item[1], words[item[0]])
        )[:kept_trigram_successors]
        for context, successors in trigram_counts.items()
    }
    return TrainingSide(
        ordered_contexts=ordered,
        context_totals=selected_totals,
        trigrams=trimmed,
        bigrams={head: dict(counts) for head, counts in bigram_counts.items()},
        bigram_totals={head: sum(counts.values()) for head, counts in bigram_counts.items()},
        unique_context_count=len(totals),
        eligible_trigram_occurrences=eligible,
    )


def rank_contexts(
    side: TrainingSide,
    words: Sequence[str],
    bigram_successes: dict[str, list[str]],
    min_context_total: int = MIN_CONTEXT_TOTAL,
    beta: float = BETA,
    packed_successors: int = PACKED_SUCCESSORS,
) -> dict[int, list[int]]:
    """Apply Jelinek-Mercer interpolation and retain the selected number of successors."""
    if min_context_total < 1 or beta <= 0 or not 1 <= packed_successors <= PACKED_SUCCESSORS:
        raise TrigramInputError("invalid interpolation configuration")
    width = len(words)
    word_index = {word: position for position, word in enumerate(words)}
    contexts = sorted(
        context
        for context in side.ordered_contexts
        if side.context_totals[context] >= min_context_total
    )
    ranked_contexts: dict[int, list[int]] = {}
    for context in contexts:
        total = side.context_totals[context]
        second_id = context % width
        counts3 = dict(side.trigrams.get(context, ()))
        counts2 = side.bigrams.get(second_id, {})
        total2 = side.bigram_totals.get(second_id, 0)
        candidate_ids = set(counts3)
        candidate_ids.update(
            word_index[word] for word in bigram_successes.get(words[second_id], ())
        )
        alpha = total / (total + beta)

        def score(word_id: int) -> float:
            probability3 = counts3.get(word_id, 0) / total
            probability2 = counts2.get(word_id, 0) / total2 if total2 else 0.0
            return alpha * probability3 + (1.0 - alpha) * probability2

        ranked = sorted(candidate_ids, key=lambda word_id: (-score(word_id), words[word_id]))
        if ranked:
            ranked_contexts[context] = ranked[:packed_successors]
    return ranked_contexts


def _corpus_report(train_paths: Sequence[Path]) -> list[dict[str, object]]:
    occurrences = Counter(train_paths)
    report: list[dict[str, object]] = []
    for path in occurrences:
        report.append(
            {
                "path": str(path),
                "size": path.stat().st_size,
                "sha256": bigram_pack.sha256_of(path),
                "weight": occurrences[path],
            }
        )
    return report


def run_pack(
    train_paths: Sequence[Path],
    source_bigram_raw_path: Path,
    dictionary_asset_path: Path,
    language: coverage.Language = coverage.DEFAULT_LANGUAGE,
    recover_punctuation: bool = False,
) -> tuple[PackResult, dict[str, object]]:
    """Run the frozen production counting and interpolation configuration in memory."""
    parsed_dictionary = dictionary_pack.validate_asset(
        dictionary_asset_path.read_bytes(), language=language
    )
    words = list(parsed_dictionary.words)
    dictionary_sha = hashlib.sha256(parsed_dictionary.raw).digest()
    source_raw = source_bigram_raw_path.read_bytes()
    source = _validate_source_bigram(source_raw, words, dictionary_sha)
    corpora = _corpus_report(train_paths)
    side = count_training_side(
        train_paths,
        words,
        language,
        recover_punctuation=recover_punctuation,
    )
    ranked = rank_contexts(side, words, source.successes_by_head)
    result = pack_trigram_table_v4(
        source_raw,
        words,
        dictionary_sha,
        ranked,
        BETA_MILLIONTHS,
        MIN_CONTEXT_TOTAL,
    )
    report: dict[str, object] = {
        "language": language.tag,
        "schema": SCHEMA_ID_V4,
        "format_version": FORMAT_VERSION_V4,
        "configuration": {
            "max_contexts": MAX_CONTEXTS,
            "min_context_total": MIN_CONTEXT_TOTAL,
            "beta": BETA,
            "beta_millionths": BETA_MILLIONTHS,
            "kept_trigram_successors": KEPT_TRIGRAM_SUCCESSORS,
            "packed_successors": PACKED_SUCCESSORS,
            "context_block_size": TRIGRAM_BLOCK_SIZE,
            "recover_punctuation": recover_punctuation,
            "self_bigram_pairs_excluded": True,
        },
        "corpora": corpora,
        "dictionary": {
            "path": str(dictionary_asset_path),
            "word_count": len(words),
            "raw_sha256": dictionary_sha.hex(),
        },
        "source_bigram": {
            "path": str(source_bigram_raw_path),
            "raw_bytes": len(source_raw),
            "raw_sha256": hashlib.sha256(source_raw).hexdigest(),
            "head_count": len(source.head_words),
            "pair_count": sum(len(items) for items in source.successes_by_head.values()),
        },
        "counts": {
            "eligible_trigram_occurrences": side.eligible_trigram_occurrences,
            "unique_contexts": side.unique_context_count,
            "selected_contexts": len(side.ordered_contexts),
            "packed_contexts": result.context_count,
            "packed_trigram_pairs": result.pair_count,
            "retained_trigram_successor_types": sum(
                len(successors) for successors in side.trigrams.values()
            ),
            "counted_bigram_heads": len(side.bigrams),
            "counted_bigram_occurrences": sum(side.bigram_totals.values()),
        },
        "raw_bytes": len(result.raw),
        "compressed_bytes": len(result.compressed),
        "raw_sha256": hashlib.sha256(result.raw).hexdigest(),
        "compressed_sha256": hashlib.sha256(result.compressed).hexdigest(),
    }
    return result, report


def _atomic_write(path: Path, data: bytes) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(path)


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    pack = subparsers.add_parser("pack", help="build the combined schema-4 asset")
    pack.add_argument("--train", nargs="+", required=True, type=Path)
    pack.add_argument("--source-bigram", required=True, type=Path, help="raw schema-3 table")
    pack.add_argument("--dictionary", required=True, type=Path, help="linked .tdict.zlib asset")
    pack.add_argument("--out-raw", required=True, type=Path)
    pack.add_argument("--out-compressed", required=True, type=Path)
    pack.add_argument("--report", type=Path)
    pack.add_argument(
        "--recover-punctuation",
        action="store_true",
        help="strip edge punctuation while retaining sentence boundaries",
    )
    pack.add_argument("--language", default="tat", choices=("tat",))
    return parser


def main(argv: Sequence[str] | None = None, stream: TextIO = sys.stdout) -> int:
    arguments = create_argument_parser().parse_args(argv)
    result, report = run_pack(
        arguments.train,
        arguments.source_bigram,
        arguments.dictionary,
        coverage.language_for(arguments.language),
        recover_punctuation=arguments.recover_punctuation,
    )
    text = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True)
    if arguments.report is not None:
        _atomic_write(arguments.report, (text + "\n").encode("utf-8"))
    print(text, file=stream)
    _atomic_write(arguments.out_raw, result.raw)
    _atomic_write(arguments.out_compressed, result.compressed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
