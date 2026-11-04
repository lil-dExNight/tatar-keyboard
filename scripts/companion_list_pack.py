#!/usr/bin/env python3
"""Build a cold-companion autocorrect word list (TATCOMP\0, schema 1).

Input: exactly one committed dictionary asset (.tdict.zlib), read with the pack
reader APIs (decompression + validation). The list is the dictionary's top
`--top` words by stored frequency (ties broken by the dictionary's own lexical
order), so it is a deterministic function of the committed dictionary: no
corpus is read and no baseline is needed. A rebuild of the dictionary followed
by this packer reproduces the list byte for byte.

Why an exact list and not a Bloom filter: the list protects a typed word from
autocorrect, so a false positive suppresses a legitimate correction. The
reader is the same rule: one exact sorted probe, no probabilistic membership.

The raw file: a 56-byte header (magic TATCOMP\\0, schemaId, formatVersion,
headerSize, checksumAlgorithm, wordCount, bodySize, then the SHA-256 of the
whole file with the checksum field itself zeroed), then the body: `wordCount`
words in unsigned UTF-8 byte order, each a u8 byte length followed by the
UTF-8 bytes. Front coding is not worth it at this size. The whole file is
zlib-compressed (level 9, wbits 15, memLevel 9) into the asset. Exit codes:
0 ok, 2 bad input (a self-check mismatch included), 4 guardrail breach;
nothing is written unless every check passes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import tempfile
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack  # noqa: E402

MAGIC = b"TATCOMP\x00"
SCHEMA_ID = 1
FORMAT_VERSION = 1
CHECKSUM_ALGORITHM_SHA256 = 1
HEADER_SIZE = 56
HEADER = struct.Struct("<8sHHHHII32s")
# 20 000 words of at most 64 code points each: a bound on the unpacked list,
# so the reader can size its buffer from the pinned word count. Ties with
# TatCompFormat.MAX_WORDS in the Kotlin contract.
MAX_WORDS = 20_000
MAX_WORD_BYTES = 128
# A bound on the raw file, the Kotlin reader's safety limit (equal to
# TatCompFormat.MAX_RAW_SIZE): a corrupt or hostile zlib stream cannot inflate
# without limit. Not a budget the packer aims for.
MAX_RAW_BYTES = 1_048_576

COMPRESSION_LEVEL = 9
COMPRESSION_WBITS = 15
COMPRESSION_MEM_LEVEL = 9

DEFAULT_TOP = 20_000


class CompanionListPackError(ValueError):
    """A generator error; nothing is written (exit 2)."""


class CompanionListGuardrailError(CompanionListPackError):
    """A guardrail breach: a raw size, word length or word count over its bound
    (exit 4)."""


@dataclass(frozen=True)
class CompanionList:
    words: tuple[str, ...]
    raw: bytes
    compressed: bytes

    @property
    def raw_size(self) -> int:
        return len(self.raw)

    @property
    def compressed_size(self) -> int:
        return len(self.compressed)

    @property
    def raw_sha256(self) -> str:
        return hashlib.sha256(self.raw).hexdigest()

    @property
    def compressed_sha256(self) -> str:
        return hashlib.sha256(self.compressed).hexdigest()


def read_dictionary(path: Path, language: coverage.Language) -> dictionary_pack.ParsedDictionary:
    """The committed dictionary asset, decompressed and fully validated.

    A dictionary that fails validation is a build failure, never a smaller
    list.
    """
    try:
        asset = path.read_bytes()
        raw = dictionary_pack.decompress_asset(asset, language)
        parsed = dictionary_pack.validate_raw(raw, language=language)
    except (OSError, dictionary_pack.DictionaryPackError) as error:
        raise CompanionListPackError(
            f"{path.name}: cannot decode the dictionary: {error}"
        ) from error
    return parsed


def select_words(
    parsed: dictionary_pack.ParsedDictionary, top: int
) -> tuple[str, ...]:
    """The top `top` words by stored frequency, ties by dictionary order.

    Dictionary order is the lexical order of the asset's words, which is the
    byte order of their UTF-8 encoding; using it as the tie-break makes the
    selection independent of the reader's iteration order.
    """
    if top <= 0:
        raise CompanionListPackError(f"top must be positive, got {top}")
    if top > MAX_WORDS:
        raise CompanionListGuardrailError(
            f"top {top} is over the {MAX_WORDS} word bound"
        )
    ranked = sorted(
        range(len(parsed.words)),
        key=lambda index: (-parsed.frequencies[index], index),
    )
    chosen = [parsed.words[index] for index in ranked[:top]]
    return tuple(chosen)


def build_raw(words: Sequence[str]) -> bytes:
    """The deterministic raw file: header plus u8-length word rows.

    Every length must fit a u8; the words are sorted by their UTF-8 bytes, and
    duplicates are refused, so the reader's sorted-probe rule holds.
    """
    encoded = [word.encode("utf-8") for word in words]
    if any(len(word) == 0 for word in encoded):
        raise CompanionListPackError("an empty word cannot be stored")
    for word in encoded:
        if len(word) > MAX_WORD_BYTES:
            raise CompanionListGuardrailError(
                f"a word is {len(word)} bytes, over the {MAX_WORD_BYTES} bound"
            )
    if len(set(encoded)) != len(encoded):
        raise CompanionListPackError("duplicate words cannot be stored")
    encoded.sort()
    if len(encoded) > MAX_WORDS:
        raise CompanionListGuardrailError(
            f"{len(encoded)} words is over the {MAX_WORDS} bound"
        )
    body = bytearray()
    for word in encoded:
        body.append(len(word))
        body.extend(word)
    if HEADER_SIZE + len(body) > MAX_RAW_BYTES:
        raise CompanionListGuardrailError(
            f"raw file is {HEADER_SIZE + len(body)} bytes, over the "
            f"{MAX_RAW_BYTES} bound"
        )
    header = HEADER.pack(
        MAGIC,
        SCHEMA_ID,
        FORMAT_VERSION,
        HEADER_SIZE,
        CHECKSUM_ALGORITHM_SHA256,
        len(encoded),
        len(body),
        b"\x00" * 32,
    )
    raw = bytearray(header)
    raw.extend(body)
    digest = hashlib.sha256(bytes(raw)).digest()
    raw[HEADER_SIZE - 32:HEADER_SIZE] = digest
    return bytes(raw)


def compress_raw(raw: bytes) -> bytes:
    """zlib, the same parameters as the dictionary packer (level 9, wbits 15,
    memLevel 9), so the two shipped zlib assets compress identically."""
    compressor = zlib.compressobj(
        level=COMPRESSION_LEVEL,
        method=zlib.DEFLATED,
        wbits=COMPRESSION_WBITS,
        memLevel=COMPRESSION_MEM_LEVEL,
        strategy=zlib.Z_DEFAULT_STRATEGY,
    )
    return compressor.compress(raw) + compressor.flush(zlib.Z_FINISH)


def build_list(parsed: dictionary_pack.ParsedDictionary, top: int) -> CompanionList:
    words = select_words(parsed, top)
    raw = build_raw(words)
    return CompanionList(words=words, raw=raw, compressed=compress_raw(raw))


def decompress_asset(asset: bytes) -> bytes:
    """The reader's bounded inflation rule: the raw file may never exceed
    MAX_RAW_BYTES and the stream must be exactly one zlib member."""
    decompressor = zlib.decompressobj(wbits=COMPRESSION_WBITS)
    try:
        raw = decompressor.decompress(asset, MAX_RAW_BYTES + 1)
        remaining = MAX_RAW_BYTES + 1 - len(raw)
        raw += decompressor.flush(remaining)
    except zlib.error as error:
        raise CompanionListPackError(f"invalid zlib stream: {error}") from error
    if len(raw) > MAX_RAW_BYTES:
        raise CompanionListPackError(
            f"uncompressed list is {len(raw)} bytes; limit is {MAX_RAW_BYTES}"
        )
    if not decompressor.eof:
        raise CompanionListPackError("truncated zlib stream")
    if decompressor.unused_data:
        raise CompanionListPackError("trailing or concatenated zlib data")
    if decompressor.unconsumed_tail:
        raise CompanionListPackError("unconsumed zlib data")
    return raw


def parse_raw(raw: bytes) -> tuple[tuple[str, ...], dict[str, object]]:
    """The python mirror of the Kotlin reader: header, checksum and body checks.

    Returns (words, header fields). Used by the packer's own self-check and by
    the tests; a mismatch is a pack error (exit 2).
    """
    if len(raw) > MAX_RAW_BYTES:
        raise CompanionListPackError(
            f"uncompressed list is {len(raw)} bytes; limit is {MAX_RAW_BYTES}"
        )
    if len(raw) < HEADER_SIZE:
        raise CompanionListPackError("raw list is shorter than its header")
    (
        magic,
        schema_id,
        format_version,
        header_size,
        checksum_algorithm,
        word_count,
        body_size,
        checksum,
    ) = HEADER.unpack_from(raw)
    if magic != MAGIC:
        raise CompanionListPackError("magic is not TATCOMP\\0")
    if schema_id != SCHEMA_ID:
        raise CompanionListPackError(f"unsupported schema id: {schema_id}")
    if format_version != FORMAT_VERSION:
        raise CompanionListPackError(f"unsupported format version: {format_version}")
    if header_size != HEADER_SIZE:
        raise CompanionListPackError(f"unsupported header size: {header_size}")
    if checksum_algorithm != CHECKSUM_ALGORITHM_SHA256:
        raise CompanionListPackError(
            f"unsupported checksum algorithm: {checksum_algorithm}"
        )
    if word_count > MAX_WORDS:
        raise CompanionListPackError(
            f"word count {word_count} is over the {MAX_WORDS} bound"
        )
    if HEADER_SIZE + body_size != len(raw):
        raise CompanionListPackError(
            f"body size {body_size} does not close the file at {len(raw)}"
        )
    zeroed = bytes(raw[:HEADER_SIZE - 32]) + b"\x00" * 32 + raw[HEADER_SIZE:]
    if hashlib.sha256(zeroed).digest() != checksum:
        raise CompanionListPackError("header checksum mismatch")
    words: list[str] = []
    cursor = HEADER_SIZE
    previous: bytes | None = None
    for _ in range(word_count):
        if cursor >= len(raw):
            raise CompanionListPackError("body ends before the word count is met")
        length = raw[cursor]
        cursor += 1
        if length == 0:
            raise CompanionListPackError("a stored word is empty")
        if cursor + length > len(raw):
            raise CompanionListPackError("a stored word runs past the body")
        word = raw[cursor:cursor + length]
        cursor += length
        if previous is not None and word <= previous:
            raise CompanionListPackError("words are not strictly ascending")
        previous = word
        try:
            words.append(word.decode("utf-8"))
        except UnicodeDecodeError as error:
            raise CompanionListPackError(
                "a stored word is not valid UTF-8"
            ) from error
    if cursor != len(raw):
        raise CompanionListPackError("body holds more than the word count")
    return tuple(words), {
        "schema_id": schema_id,
        "format_version": format_version,
        "header_size": header_size,
        "checksum_algorithm": checksum_algorithm,
        "word_count": word_count,
        "body_size": body_size,
    }


def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = tempfile.NamedTemporaryFile(
        dir=str(path.parent), delete=False, prefix=path.name, suffix=".tmp"
    )
    try:
        with handle:
            handle.write(data)
        Path(handle.name).replace(path)
    except BaseException:
        Path(handle.name).unlink(missing_ok=True)
        raise


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)
    build = subparsers.add_parser("build", help="build one language's list asset")
    build.add_argument("--dictionary", type=Path, required=True,
                       help="the committed dictionary asset (.tdict.zlib)")
    build.add_argument("--language", choices=sorted(coverage.LANGUAGES), default="tat",
                       help="the language of the dictionary (default tat)")
    build.add_argument("--output", type=Path, required=True,
                       help="the zlib asset to write (.tcomp.zlib)")
    build.add_argument("--top", type=int, default=DEFAULT_TOP,
                       help=f"words to keep (default {DEFAULT_TOP})")
    return parser


def _print_json(payload: dict) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        if args.command == "build":
            language = coverage.language_for(args.language)
            parsed = read_dictionary(args.dictionary, language)
            companion = build_list(parsed, args.top)
            # Self-check: the output must parse back to exactly the chosen
            # words (the body stores them sorted, the selection is ranked), or
            # the packer is broken and nothing is written.
            words, fields = parse_raw(companion.raw)
            if (sorted(words) != sorted(companion.words)
                    or fields["word_count"] != len(companion.words)):
                raise CompanionListPackError(
                    "the built raw file does not read back as the chosen words"
                )
            write_atomic(args.output, companion.compressed)
            _print_json(
                {
                    "compressed_sha256": companion.compressed_sha256,
                    "compressed_size": companion.compressed_size,
                    "dictionary_words": len(parsed.words),
                    "language": language.tag,
                    "raw_sha256": companion.raw_sha256,
                    "raw_size": companion.raw_size,
                    "top_word": companion.words[0] if companion.words else None,
                    "word_count": len(companion.words),
                }
            )
        else:  # pragma: no cover
            raise AssertionError(args.command)
    except CompanionListGuardrailError as error:
        print(f"error: {error}", file=sys.stderr)
        return 4
    except CompanionListPackError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
