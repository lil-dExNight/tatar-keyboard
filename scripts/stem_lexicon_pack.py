#!/usr/bin/env python3
"""Build the incremental Tatar stem lexicon (TATSTEM schema 1).

The Apertium source is a pinned lexc file. Other stems come from unambiguous
word-form groups in the bundled Tatar dictionary and from lemmas in the
word-form review table. Every shipped dictionary form is excluded. Only
Apertium entries receive suffix-chain flags; all retained entries can still be
accepted as bare stems by the runtime reader.

Use ``fetch`` to acquire the lexc file under build/, then ``build`` to write the
zlib-compressed asset. The build validates its raw image before atomically
replacing the output. Only the Python standard library is used.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import struct
import sys
import tarfile
import tempfile
import unicodedata
import urllib.request
import zlib
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Iterable, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack  # noqa: E402
import wordform_gen  # noqa: E402

APERTIUM_COMMIT = "18fe9e45d5672d6f6113291197449e7522df1b3e"
APERTIUM_REPOSITORY = "https://github.com/apertium/apertium-tat"
APERTIUM_ARCHIVE_URL = f"https://codeload.github.com/apertium/apertium-tat/tar.gz/{APERTIUM_COMMIT}"
APERTIUM_LEXC_NAME = "apertium-tat.tat.lexc"
APERTIUM_LEXC_SIZE = 2_938_153
APERTIUM_LEXC_SHA256 = "43438bc2710beae9472e1d5e09a2403793d0d059c48ff9a9e5c9100387716e73"
APERTIUM_RAW_STEMS = 55_110
APERTIUM_RAW_TRIPLES = 56_647
APERTIUM_ACCEPTED_ENTRIES = 59_270
DEFAULT_APERTIUM_LEXC = Path(
    "build/stem-lexicon/apertium-tat/apertium-tat.tat.lexc"
)
DEFAULT_DICTIONARY = Path(
    "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib"
)
DEFAULT_REVIEW = Path("data/dictionary/tatar-wordform-review.tsv")
DEFAULT_EXCEPTIONS = wordform_gen.DEFAULT_EXCEPTIONS
DEFAULT_OUTPUT = Path("app/src/main/assets/dictionaries/tatar_stems_v1.tstem.zlib")

MAGIC = b"TATSTEM\x00"
SCHEMA_ID = 1
FORMAT_VERSION = 1
CHECKSUM_ALGORITHM_SHA256 = 1
HEADER_SIZE = 136
CHECKSUM_OFFSET = 104
CHECKSUM_SIZE = 32
HEADER = struct.Struct("<8sHHHHIIIIII32s32s32s")
BLOCK_SIZE = 8
MAX_STEMS = 100_000
MAX_STEM_BYTES = 128
MAX_STEM_CODE_POINTS = 64
MAX_RAW_BYTES = 2 * 1024 * 1024
MAX_COMPRESSED_BYTES = 600 * 1024

FLAG_NOUN = 1
FLAG_VERB = 2
FLAG_FRONT = 4
FLAG_BACK = 8
FLAG_NOUN_CHAIN = 16
FLAG_VERB_CHAIN = 32
FLAG_FORCE_BACK = 64
FLAG_KNOWN = 127

COMPRESSION_LEVEL = 9
COMPRESSION_WBITS = 15
COMPRESSION_MEM_LEVEL = 9

POS_TAGS = frozenset({
    "n", "np", "adj", "num", "prn", "det", "v", "vaux", "adv", "post",
    "postadv", "cnjcoo", "cnjsub", "cnjadv", "ij", "abbr", "cop", "ideo",
})
EXCLUDED_LEXICONS = frozenset({"Digits", "Guesser"})
LEXICON_RE = re.compile(r"^\s*LEXICON\s+([^\s!;]+)\s*$")


class StemLexiconError(ValueError):
    """An invalid source or asset; no output is written."""


class StemLexiconGuardrailError(StemLexiconError):
    """A format or size bound was exceeded."""


@dataclass(frozen=True)
class StemEntry:
    word: str
    flags: int


@dataclass(frozen=True)
class BuiltStemLexicon:
    entries: tuple[StemEntry, ...]
    raw: bytes
    compressed: bytes
    report: dict[str, object]


@dataclass
class LexcEntry:
    upper: str
    lower: str | None
    continuation: str
    line: int
    is_regex: bool = False

    @property
    def is_pure_reference(self) -> bool:
        return (
            self.upper == "" and self.lower in (None, "")
            and self.continuation != "#" and not self.is_regex
        )


@dataclass(frozen=True)
class LexcSide:
    text: str
    tags: tuple[str, ...]
    archiphonemes: tuple[str, ...]


@dataclass
class LexcDiagnostics:
    entries_parsed: int = 0
    regex_entries: int = 0
    unresolved_pos_entries: int = 0
    unterminated_entries: int = 0
    parse_errors: list[str] = field(default_factory=list)
    undefined_continuations: set[str] = field(default_factory=set)


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _strip_comment(line: str) -> str:
    index = 0
    while index < len(line):
        if line[index] == "%":
            index += 2
            continue
        if line[index] == "!":
            return line[:index]
        index += 1
    return line


def _unescaped_positions(text: str, delimiter: str) -> Iterable[int]:
    index = 0
    while index < len(text):
        char = text[index]
        if char == "%":
            index += 2
            continue
        if char == "<":
            end = text.find(">", index + 1)
            if end < 0:
                return
            index = end + 1
            continue
        if char == delimiter:
            yield index
        index += 1


def _split_lexc_tokens(text: str) -> list[str]:
    parts: list[str] = []
    current: list[str] = []
    in_regex = False
    index = 0
    while index < len(text):
        char = text[index]
        if char == "%" and index + 1 < len(text):
            current.append(text[index:index + 2])
            index += 2
            continue
        if char == "<":
            in_regex = True
        if char.isspace() and not in_regex:
            if current:
                parts.append("".join(current))
                current = []
        else:
            current.append(char)
        if char == ">" and in_regex:
            in_regex = False
        index += 1
    if current:
        parts.append("".join(current))
    return parts


def _parse_side(token: str) -> LexcSide:
    literal: list[str] = []
    tags: list[str] = []
    archiphonemes: list[str] = []
    index = 0
    while index < len(token):
        char = token[index]
        if char != "%":
            if char == "@":
                end = token.find("@", index + 1)
                if end < 0:
                    raise StemLexiconError("unterminated lexc flag diacritic")
                index = end + 1
            else:
                literal.append(char)
                index += 1
            continue
        if index + 1 >= len(token):
            literal.append(char)
            index += 1
            continue
        following = token[index + 1]
        if following == "<":
            end = token.find("%>", index + 2)
            if end < 0:
                raise StemLexiconError("unterminated lexc analysis tag")
            tags.append(token[index + 2:end])
            index = end + 2
        elif following == "{":
            end = token.find("%}", index + 2)
            if end < 0 or "%" in token[index + 2:end]:
                literal.append("{")
                index += 2
            else:
                content = token[index + 2:end]
                archiphonemes.append(content)
                literal.append("{" + content + "}")
                index = end + 2
        elif following == ">":
            index += 2
        else:
            literal.append(following)
            index += 2
    return LexcSide("".join(literal), tuple(tags), tuple(archiphonemes))


class LexcParser:
    """Parse literal entries and continuation references from one lexc file."""

    def __init__(self) -> None:
        self.lexicons: dict[str, list[LexcEntry]] = defaultdict(list)
        self.diagnostics = LexcDiagnostics()

    def parse(self, text: str) -> None:
        current: str | None = None
        in_multichar = False
        pending: tuple[list[str], int] | None = None
        for line_number, raw_line in enumerate(text.splitlines(), 1):
            line = _strip_comment(raw_line).rstrip()
            stripped = line.strip()
            if stripped == "Multichar_Symbols":
                in_multichar = True
                continue
            if in_multichar and stripped.startswith("LEXICON"):
                in_multichar = False
            elif in_multichar:
                continue

            cursor = 0
            while cursor < len(line):
                relative = next(_unescaped_positions(line[cursor:], ";"), None)
                if relative is None:
                    chunk = line[cursor:]
                    terminated = False
                else:
                    chunk = line[cursor:cursor + relative]
                    terminated = True
                header = LEXICON_RE.match(chunk) if pending is None else None
                if header:
                    current = header.group(1)
                    in_multichar = False
                elif chunk.strip() or pending is not None:
                    if pending is None:
                        pending = ([chunk], line_number)
                    else:
                        pending[0].append(chunk)
                if terminated and current is not None:
                    if pending is None:
                        pending = ([""], line_number)
                    self._entry(current, pending[0], pending[1])
                    pending = None
                elif terminated and pending is not None:
                    self.diagnostics.parse_errors.append(
                        f"line {line_number}: entry outside a lexicon"
                    )
                    pending = None
                if not terminated:
                    break
                cursor += relative + 1
        if pending is not None:
            self.diagnostics.unterminated_entries += 1
            self.diagnostics.parse_errors.append(
                f"line {pending[1]}: statement without terminating ';'"
            )

    def _entry(self, lexicon: str, chunks: list[str], line: int) -> None:
        text = "\n".join(chunks).strip()
        self.diagnostics.entries_parsed += 1
        if not text:
            return
        try:
            pieces = _split_lexc_tokens(text)
            if not pieces:
                raise StemLexiconError("empty lexc entry")
            is_regex = any(piece.startswith("<") for piece in pieces[:-1])
            continuation = pieces[-1]
            morph = " ".join(pieces[:-1])
            colons = list(_unescaped_positions(morph, ":"))
            if len(colons) > 1:
                raise StemLexiconError("more than one unescaped ':'")
            upper = morph[:colons[0]] if colons else morph
            lower = morph[colons[0] + 1:] if colons else None
            _parse_side(upper)
            if lower is not None:
                _parse_side(lower)
            self.lexicons[lexicon].append(
                LexcEntry(upper, lower, continuation, line, is_regex)
            )
        except StemLexiconError as error:
            self.diagnostics.parse_errors.append(f"line {line}: {error}")


def _continuation_tags(
    lexicons: dict[str, list[LexcEntry]],
    continuation: str,
    cache: dict[str, tuple[str, ...]],
    visiting: set[str],
) -> tuple[str, ...]:
    if continuation in cache:
        return cache[continuation]
    if continuation in visiting or continuation not in lexicons:
        return ()
    visiting.add(continuation)
    found: set[str] = set()
    for entry in lexicons[continuation]:
        upper = _parse_side(entry.upper)
        if upper.text:
            continue
        found.update(upper.tags)
        if not set(upper.tags) & POS_TAGS:
            found.update(
                _continuation_tags(lexicons, entry.continuation, cache, visiting)
            )
    visiting.discard(continuation)
    result = tuple(sorted(found))
    cache[continuation] = result
    return result


def extract_apertium(
    path: Path, verify_pin: bool = True
) -> tuple[tuple[tuple[str, str, str], ...], dict[str, object]]:
    """Extract (stem, POS, continuation) triples from the stem-bearing lexicons."""
    source = path.read_bytes()
    source_sha = _sha256(source)
    if verify_pin and (
        len(source) != APERTIUM_LEXC_SIZE or source_sha != APERTIUM_LEXC_SHA256
    ):
        raise StemLexiconError(
            f"{path}: expected the pinned {APERTIUM_COMMIT} lexc source"
        )
    parser = LexcParser()
    parser.parse(source.decode("utf-8"))
    diagnostics = parser.diagnostics
    for entries in parser.lexicons.values():
        for entry in entries:
            if entry.continuation != "#" and entry.continuation not in parser.lexicons:
                diagnostics.undefined_continuations.add(entry.continuation)
    if diagnostics.parse_errors or diagnostics.undefined_continuations:
        raise StemLexiconError(
            "lexc parse failed: "
            f"{len(diagnostics.parse_errors)} parse errors, "
            f"{len(diagnostics.undefined_continuations)} undefined continuations"
        )
    if "Root" not in parser.lexicons:
        raise StemLexiconError("lexc source has no LEXICON Root")

    stem_lexicons: set[str] = set()
    root_targets: list[str] = []
    for entry in parser.lexicons["Root"]:
        target = entry.continuation
        root_targets.append(target)
        if target not in EXCLUDED_LEXICONS and target in parser.lexicons:
            stem_lexicons.add(target)
    for target in root_targets:
        if target in EXCLUDED_LEXICONS:
            continue
        for entry in parser.lexicons.get(target, []):
            child = entry.continuation
            if (
                entry.is_pure_reference and child not in EXCLUDED_LEXICONS
                and child in parser.lexicons
            ):
                stem_lexicons.add(child)

    cache: dict[str, tuple[str, ...]] = {}
    triples: set[tuple[str, str, str]] = set()
    accepted_entries = 0
    for lexicon in sorted(stem_lexicons):
        for entry in parser.lexicons[lexicon]:
            if entry.is_pure_reference:
                continue
            if entry.is_regex:
                diagnostics.regex_entries += 1
                continue
            upper = _parse_side(entry.upper)
            if not upper.text:
                continue
            inherited = _continuation_tags(
                parser.lexicons, entry.continuation, cache, set()
            )
            direct = set(upper.tags) & POS_TAGS
            positions = sorted(direct or (set(inherited) & POS_TAGS))
            if not positions:
                diagnostics.unresolved_pos_entries += 1
                continue
            accepted_entries += 1
            for pos in positions:
                triples.add((upper.text, pos, entry.continuation))
    report: dict[str, object] = {
        "source_commit": APERTIUM_COMMIT,
        "source_path": str(path),
        "source_size": len(source),
        "source_sha256": source_sha,
        "unique_stems": len({stem for stem, _pos, _cont in triples}),
        "stem_pos_continuations": len(triples),
        "accepted_source_entries": accepted_entries,
        "parsed_statements": diagnostics.entries_parsed,
        "regex_entries_skipped": diagnostics.regex_entries,
        "unresolved_pos_entries_skipped": diagnostics.unresolved_pos_entries,
        "parse_errors": len(diagnostics.parse_errors),
        "undefined_continuations": len(diagnostics.undefined_continuations),
    }
    if verify_pin and (
        report["unique_stems"] != APERTIUM_RAW_STEMS
        or report["stem_pos_continuations"] != APERTIUM_RAW_TRIPLES
        or accepted_entries != APERTIUM_ACCEPTED_ENTRIES
    ):
        raise StemLexiconError(f"{path}: extraction counts differ from the source pins")
    return tuple(sorted(triples)), report


def valid_stem(word: str) -> bool:
    normalized, reason = coverage.normalize_word(word)
    return (
        normalized == word and reason is None and len(word) >= 2
        and len(word) <= MAX_STEM_CODE_POINTS
        and wordform_gen.harmony_of(word) is not None
    )


def pos_parts(pos: str) -> frozenset[str]:
    value = pos.lower()
    if value in {"v", "verb", "vblex", "iv", "tv"} or "verb" in value:
        return frozenset({"verb"})
    if value in {"n", "noun", "np", "proper_noun"}:
        return frozenset({"noun"})
    # Nominal suffixes also apply to Apertium's other open and closed classes.
    return frozenset({"noun"})


def read_apertium(
    triples: Iterable[tuple[str, str, str]]
) -> dict[str, dict[str, object]]:
    rows: dict[str, dict[str, object]] = {}
    for raw_stem, pos, continuation in triples:
        stem = unicodedata.normalize("NFC", raw_stem.strip()).lower()
        if not valid_stem(stem):
            continue
        entry = rows.setdefault(stem, {"pos": set(), "continuations": set()})
        entry["pos"].add(pos)  # type: ignore[union-attr]
        entry["continuations"].add(continuation)  # type: ignore[union-attr]
    return rows


def review_stems(path: Path) -> dict[str, set[str]]:
    rows: dict[str, set[str]] = defaultdict(set)
    with path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(
            (line for line in stream if not line.startswith("#")), delimiter="\t"
        )
        required = {"lemma", "paradigm_cell"}
        if not required.issubset(reader.fieldnames or []):
            raise StemLexiconError(f"{path}: missing columns {sorted(required)}")
        for row in reader:
            stem = row["lemma"]
            if not valid_stem(stem):
                continue
            cell = row["paradigm_cell"]
            parts = (
                {"verb"}
                if cell.startswith(
                    ("present", "past", "future", "gerund", "masdar", "rpast")
                ) or cell == "citation" and stem.endswith(("у", "ү"))
                else {"noun", "verb"}
            )
            if cell.startswith("noun.") or cell in {
                "locative", "genitive", "dative", "accusative", "ablative",
                "plural", "derived",
            }:
                parts = {"noun"}
            rows[stem].update(parts)
    return dict(rows)


def _record_flags(
    stem: str,
    parts: set[str],
    apertium: dict[str, object] | None,
) -> int:
    flags = (FLAG_NOUN if "noun" in parts else 0) | (
        FLAG_VERB if "verb" in parts else 0
    )
    forced_back = False
    if apertium is not None:
        apertium_parts = pos_parts_for_entries(apertium["pos"])  # type: ignore[arg-type]
        if "noun" in apertium_parts:
            flags |= FLAG_NOUN_CHAIN
        if "verb" in apertium_parts:
            flags |= FLAG_VERB_CHAIN
        forced_back = any(
            str(name).endswith("-RUS")
            for name in apertium["continuations"]  # type: ignore[union-attr]
        )
    harmony = wordform_gen.BACK if forced_back else wordform_gen.harmony_of(stem)
    if harmony == wordform_gen.BACK:
        flags |= FLAG_BACK
    elif harmony == wordform_gen.FRONT:
        flags |= FLAG_FRONT
    else:
        raise StemLexiconError(f"stem has no harmony class: {stem}")
    if forced_back:
        flags |= FLAG_FORCE_BACK
    return flags


def pos_parts_for_entries(values: Iterable[str]) -> set[str]:
    parts: set[str] = set()
    for value in values:
        parts.update(pos_parts(value))
    return parts


def merge_stems(
    dictionary_words: Sequence[str],
    apertium: dict[str, dict[str, object]],
    review: dict[str, set[str]],
    exceptions: wordform_gen.Exceptions,
) -> tuple[tuple[StemEntry, ...], dict[str, object]]:
    """Merge sources and return only entries absent from the shipped dictionary."""
    groups, group_stats = wordform_gen.build_groups(list(dictionary_words), exceptions)
    inferred: dict[str, set[str]] = defaultdict(set)
    for stem, _form, label in groups:
        if valid_stem(stem):
            inferred[stem].add("verb" if label.startswith("verb.") else "noun")

    dictionary_set = set(dictionary_words)
    dictionary_stems = {word for word in dictionary_words if valid_stem(word)}
    source_sets = {
        "apertium": set(apertium),
        "inferred": set(inferred),
        "review": set(review),
    }
    merged = set().union(*source_sets.values())
    incremental = sorted(merged - dictionary_set, key=lambda word: word.encode("utf-8"))
    entries: list[StemEntry] = []
    for stem in incremental:
        parts = pos_parts_for_entries(apertium.get(stem, {}).get("pos", set()))  # type: ignore[arg-type]
        parts.update(review.get(stem, set()))
        parts.update(inferred.get(stem, set()))
        if not parts:
            raise StemLexiconError(f"stem has no part of speech: {stem}")
        entries.append(StemEntry(stem, _record_flags(stem, parts, apertium.get(stem))))

    pos_counts = Counter()
    chain_counts = Counter()
    for entry in entries:
        pos_counts[
            ",".join(
                name for name, flag in (("noun", FLAG_NOUN), ("verb", FLAG_VERB))
                if entry.flags & flag
            )
        ] += 1
        chain_counts[
            "apertium" if entry.flags & (FLAG_NOUN_CHAIN | FLAG_VERB_CHAIN)
            else "bare_only"
        ] += 1
    full_merged = merged | dictionary_stems
    stats: dict[str, object] = {
        "dictionary_form_count": len(dictionary_words),
        "dictionary_valid_stem_count": len(dictionary_stems),
        "source_counts": {
            **{name: len(words) for name, words in source_sets.items()},
            "dictionary_forms_as_stems": len(dictionary_stems),
        },
        "merged_unique_stems": len(full_merged),
        "incremental_stems": len(entries),
        "incremental_pos_counts": dict(sorted(pos_counts.items())),
        "incremental_chain_counts": dict(sorted(chain_counts.items())),
        "forced_back_loans": sum(bool(entry.flags & FLAG_FORCE_BACK) for entry in entries),
        "overlap": {
            "apertium_and_review": len(set(apertium) & set(review)),
            "apertium_and_inferred": len(set(apertium) & set(inferred)),
            "review_and_inferred": len(set(review) & set(inferred)),
            "all_three": len(set(apertium) & set(review) & set(inferred)),
            "apertium_and_dictionary": len(set(apertium) & dictionary_set),
            "review_and_dictionary": len(set(review) & dictionary_set),
            "inferred_and_dictionary": len(set(inferred) & dictionary_set),
        },
        "group_stats": group_stats,
    }
    return tuple(entries), stats


def _varint(value: int) -> bytes:
    if value < 0 or value > 0xFFFF_FFFF:
        raise StemLexiconError("varint value is outside u32")
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def build_raw(
    entries: Sequence[StemEntry], dictionary_raw_sha256: str, apertium_sha256: str
) -> bytes:
    """Serialize sorted front-coded blocks and their absolute-offset index."""
    if not entries or len(entries) > MAX_STEMS:
        raise StemLexiconGuardrailError("stem count is outside the format bound")
    for entry in entries:
        if not valid_stem(entry.word):
            raise StemLexiconError("stem is not canonical Tatar vocabulary")
        _validate_flags(entry.word, entry.flags)
    encoded = [entry.word.encode("utf-8") for entry in entries]
    if any(
        not word or len(word) > MAX_STEM_BYTES for word in encoded
    ):
        raise StemLexiconGuardrailError("stem byte length is outside the format bound")
    if any(encoded[index] <= encoded[index - 1] for index in range(1, len(encoded))):
        raise StemLexiconError("stems must be unique and in unsigned UTF-8 order")
    if len(dictionary_raw_sha256) != 64 or len(apertium_sha256) != 64:
        raise StemLexiconError("source identities must be SHA-256 hex strings")
    try:
        dictionary_sha = bytes.fromhex(dictionary_raw_sha256)
        apertium_sha = bytes.fromhex(apertium_sha256)
    except ValueError as error:
        raise StemLexiconError("source identities must be SHA-256 hex strings") from error

    block_count = (len(entries) + BLOCK_SIZE - 1) // BLOCK_SIZE
    block_index_offset = HEADER_SIZE
    blocks_offset = block_index_offset + 4 * block_count
    blocks: list[bytes] = []
    offsets: list[int] = []
    cursor = blocks_offset
    for start in range(0, len(entries), BLOCK_SIZE):
        chunk = entries[start:start + BLOCK_SIZE]
        first = chunk[0].word.encode("utf-8")
        block = bytearray((len(first),))
        block.extend(first)
        block.append(chunk[0].flags)
        for entry in chunk[1:]:
            word = entry.word.encode("utf-8")
            prefix = 0
            limit = min(len(first), len(word))
            while prefix < limit and first[prefix] == word[prefix]:
                prefix += 1
            suffix_length = len(word) - prefix
            if suffix_length == 0:
                raise StemLexiconError("a later stem cannot be a prefix of its block head")
            block.extend(_varint(prefix))
            block.append(suffix_length)
            block.extend(word[prefix:])
            block.append(entry.flags)
        offsets.append(cursor)
        cursor += len(block)
        blocks.append(bytes(block))
    file_size = cursor
    if file_size > MAX_RAW_BYTES:
        raise StemLexiconGuardrailError(
            f"raw stem lexicon exceeds {MAX_RAW_BYTES} bytes"
        )
    header = HEADER.pack(
        MAGIC, SCHEMA_ID, FORMAT_VERSION, HEADER_SIZE, CHECKSUM_ALGORITHM_SHA256,
        len(entries), block_count, block_index_offset, blocks_offset,
        file_size - blocks_offset, file_size, dictionary_sha, apertium_sha,
        b"\x00" * CHECKSUM_SIZE,
    )
    raw = bytearray(header)
    for offset in offsets:
        raw.extend(struct.pack("<I", offset))
    for block in blocks:
        raw.extend(block)
    raw[CHECKSUM_OFFSET:HEADER_SIZE] = hashlib.sha256(raw).digest()
    return bytes(raw)


def compress_raw(raw: bytes) -> bytes:
    compressor = zlib.compressobj(
        COMPRESSION_LEVEL, zlib.DEFLATED, COMPRESSION_WBITS,
        COMPRESSION_MEM_LEVEL, zlib.Z_DEFAULT_STRATEGY,
    )
    compressed = compressor.compress(raw) + compressor.flush(zlib.Z_FINISH)
    if len(compressed) > MAX_COMPRESSED_BYTES:
        raise StemLexiconGuardrailError(
            f"compressed stem lexicon exceeds {MAX_COMPRESSED_BYTES} bytes"
        )
    return compressed


def decompress_asset(asset: bytes) -> bytes:
    decompressor = zlib.decompressobj(COMPRESSION_WBITS)
    try:
        raw = decompressor.decompress(asset, MAX_RAW_BYTES + 1)
        remaining = MAX_RAW_BYTES + 1 - len(raw)
        if remaining > 0:
            raw += decompressor.flush(remaining)
    except zlib.error as error:
        raise StemLexiconError(f"invalid zlib stream: {error}") from error
    if len(raw) > MAX_RAW_BYTES:
        raise StemLexiconGuardrailError("inflated stem lexicon exceeds the raw bound")
    if not decompressor.eof:
        raise StemLexiconError("truncated or oversized zlib stream")
    if decompressor.unused_data or decompressor.unconsumed_tail:
        raise StemLexiconError("trailing or concatenated zlib data")
    return raw


def _read_varint(raw: bytes, cursor: int, limit: int) -> tuple[int, int]:
    value = 0
    shift = 0
    start = cursor
    while True:
        if cursor >= limit:
            raise StemLexiconError("truncated varint")
        byte = raw[cursor]
        cursor += 1
        if shift == 28 and byte > 0x0F:
            raise StemLexiconError("varint exceeds u32")
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            break
        shift += 7
    if len(_varint(value)) != cursor - start:
        raise StemLexiconError("non-minimal varint")
    return value, cursor


def _validate_flags(stem: str, flags: int) -> None:
    if flags == 0 or flags & ~FLAG_KNOWN:
        raise StemLexiconError("stem flags contain no category or reserved bits")
    if not flags & (FLAG_NOUN | FLAG_VERB):
        raise StemLexiconError("stem has no part of speech")
    if flags & (FLAG_NOUN_CHAIN) and not flags & FLAG_NOUN:
        raise StemLexiconError("noun chain without noun category")
    if flags & (FLAG_VERB_CHAIN) and not flags & FLAG_VERB:
        raise StemLexiconError("verb chain without verb category")
    harmony_flags = flags & (FLAG_FRONT | FLAG_BACK)
    if harmony_flags not in (FLAG_FRONT, FLAG_BACK):
        raise StemLexiconError("stem must have exactly one harmony class")
    forced = bool(flags & FLAG_FORCE_BACK)
    if forced and harmony_flags != FLAG_BACK:
        raise StemLexiconError("forced back harmony conflicts with the harmony class")
    natural = wordform_gen.harmony_of(stem)
    expected = FLAG_BACK if natural == wordform_gen.BACK else FLAG_FRONT
    if not forced and harmony_flags != expected:
        raise StemLexiconError("harmony class differs from the stem spelling")
    if forced and not flags & (FLAG_NOUN_CHAIN | FLAG_VERB_CHAIN):
        raise StemLexiconError("forced back harmony requires an Apertium chain")


def parse_raw(raw: bytes) -> tuple[tuple[StemEntry, ...], dict[str, object]]:
    """Fully validate and decode a raw TATSTEM image."""
    if len(raw) > MAX_RAW_BYTES:
        raise StemLexiconGuardrailError("raw stem lexicon exceeds the size bound")
    if len(raw) < HEADER_SIZE:
        raise StemLexiconError("raw stem lexicon is shorter than its header")
    (
        magic, schema, version, header_size, algorithm, count, block_count,
        index_offset, blocks_offset, blocks_size, file_size, dictionary_sha,
        apertium_sha, stored_checksum,
    ) = HEADER.unpack_from(raw)
    if magic != MAGIC:
        raise StemLexiconError("wrong stem lexicon magic")
    if (schema, version, header_size, algorithm) != (
        SCHEMA_ID, FORMAT_VERSION, HEADER_SIZE, CHECKSUM_ALGORITHM_SHA256
    ):
        raise StemLexiconError("unsupported stem lexicon header")
    if count == 0 or count > MAX_STEMS:
        raise StemLexiconError("stem count is outside the format bound")
    expected_blocks = (count + BLOCK_SIZE - 1) // BLOCK_SIZE
    expected_blocks_offset = HEADER_SIZE + 4 * expected_blocks
    if (
        block_count != expected_blocks or index_offset != HEADER_SIZE
        or blocks_offset != expected_blocks_offset
        or blocks_offset + blocks_size != file_size or file_size != len(raw)
    ):
        raise StemLexiconError("noncanonical stem lexicon sections")
    zeroed = raw[:CHECKSUM_OFFSET] + b"\x00" * CHECKSUM_SIZE + raw[HEADER_SIZE:]
    if hashlib.sha256(zeroed).digest() != stored_checksum:
        raise StemLexiconError("stem lexicon checksum mismatch")
    offsets = list(struct.unpack_from(f"<{block_count}I", raw, index_offset))
    if not offsets or offsets[0] != blocks_offset:
        raise StemLexiconError("first block does not start at the block section")
    if any(
        offsets[index] <= offsets[index - 1] or offsets[index] >= file_size
        for index in range(1, len(offsets))
    ):
        raise StemLexiconError("block offsets are not strictly increasing")

    entries: list[StemEntry] = []
    previous: bytes | None = None
    for block_index, start in enumerate(offsets):
        end = offsets[block_index + 1] if block_index + 1 < len(offsets) else file_size
        in_block = min(BLOCK_SIZE, count - block_index * BLOCK_SIZE)
        cursor = start
        if cursor >= end:
            raise StemLexiconError("empty stem block")
        first_length = raw[cursor]
        cursor += 1
        if first_length == 0 or first_length > MAX_STEM_BYTES or cursor + first_length > end:
            raise StemLexiconError("invalid block-head stem")
        first = raw[cursor:cursor + first_length]
        cursor += first_length
        for item in range(in_block):
            if item == 0:
                word = first
            else:
                prefix, cursor = _read_varint(raw, cursor, end)
                if prefix > first_length or cursor >= end:
                    raise StemLexiconError("invalid front-coded prefix")
                suffix_length = raw[cursor]
                cursor += 1
                if (
                    suffix_length == 0 or prefix + suffix_length > MAX_STEM_BYTES
                    or cursor + suffix_length > end
                ):
                    raise StemLexiconError("invalid front-coded suffix")
                word = first[:prefix] + raw[cursor:cursor + suffix_length]
                cursor += suffix_length
            if cursor >= end:
                raise StemLexiconError("missing stem flags")
            flags = raw[cursor]
            cursor += 1
            if previous is not None and word <= previous:
                raise StemLexiconError("stems are not strictly increasing")
            try:
                stem = word.decode("utf-8")
            except UnicodeDecodeError as error:
                raise StemLexiconError("stem is not strict UTF-8") from error
            if not valid_stem(stem):
                raise StemLexiconError("stem is not canonical Tatar vocabulary")
            _validate_flags(stem, flags)
            entries.append(StemEntry(stem, flags))
            previous = word
        if cursor != end:
            raise StemLexiconError("trailing bytes in a stem block")
    if len(entries) != count:
        raise StemLexiconError("stem count does not match the blocks")
    fields: dict[str, object] = {
        "stem_count": count,
        "block_count": block_count,
        "blocks_offset": blocks_offset,
        "blocks_size": blocks_size,
        "file_size": file_size,
        "dictionary_raw_sha256": dictionary_sha.hex(),
        "apertium_lexc_sha256": apertium_sha.hex(),
    }
    return tuple(entries), fields


def build_lexicon(
    dictionary_path: Path,
    review_path: Path,
    exceptions_path: Path,
    apertium_path: Path,
) -> BuiltStemLexicon:
    """Extract, merge, pack and parse back one pinned stem lexicon."""
    asset = dictionary_path.read_bytes()
    parsed_dictionary = dictionary_pack.validate_asset(
        asset, language=coverage.language_for("tat")
    )
    dictionary_sha = _sha256(parsed_dictionary.raw)
    triples, extraction_report = extract_apertium(apertium_path)
    apertium = read_apertium(triples)
    review = review_stems(review_path)
    exceptions = wordform_gen.load_exceptions(exceptions_path)
    entries, merge_report = merge_stems(
        parsed_dictionary.words, apertium, review, exceptions
    )
    raw = build_raw(entries, dictionary_sha, APERTIUM_LEXC_SHA256)
    parsed_entries, fields = parse_raw(raw)
    if parsed_entries != entries:
        raise StemLexiconError("packed entries differ from the merged entries")
    compressed = compress_raw(raw)
    if decompress_asset(compressed) != raw:
        raise StemLexiconError("compressed asset does not reproduce the raw image")
    report: dict[str, object] = {
        **merge_report,
        "apertium": extraction_report,
        "format": fields,
        "raw_size": len(raw),
        "raw_sha256": _sha256(raw),
        "compressed_size": len(compressed),
        "compressed_sha256": _sha256(compressed),
        "compressed_budget": MAX_COMPRESSED_BYTES,
        "raw_budget": MAX_RAW_BYTES,
        "deterministic": True,
    }
    return BuiltStemLexicon(entries, raw, compressed, report)


def _write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(
        dir=str(path.parent), prefix=path.name + ".", suffix=".tmp"
    )
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def fetch_apertium(destination: Path) -> dict[str, object]:
    """Fetch only the pinned lexc member into destination without extracting a tree."""
    target = destination / APERTIUM_LEXC_NAME
    if target.is_file():
        data = target.read_bytes()
        if len(data) == APERTIUM_LEXC_SIZE and _sha256(data) == APERTIUM_LEXC_SHA256:
            return {
                "path": str(target), "size": len(data), "sha256": _sha256(data),
                "commit": APERTIUM_COMMIT, "downloaded": False,
            }
        raise StemLexiconError(f"{target}: existing file does not match the source pin")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary_dir = Path(tempfile.mkdtemp(prefix=destination.name + ".", dir=destination.parent))
    archive_path = temporary_dir / "source.tar.gz"
    try:
        request = urllib.request.Request(
            APERTIUM_ARCHIVE_URL, headers={"User-Agent": "tatar-keyboard-asset-build"}
        )
        with urllib.request.urlopen(request, timeout=120) as response, archive_path.open("wb") as out:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)
        with tarfile.open(archive_path, "r:gz") as archive:
            members = []
            for member in archive.getmembers():
                parts = PurePosixPath(member.name).parts
                if len(parts) == 2 and parts[1] == APERTIUM_LEXC_NAME and member.isfile():
                    members.append(member)
            if len(members) != 1:
                raise StemLexiconError("Apertium archive does not contain exactly one pinned lexc file")
            extracted = archive.extractfile(members[0])
            if extracted is None:
                raise StemLexiconError("Apertium lexc archive member is unreadable")
            data = extracted.read(APERTIUM_LEXC_SIZE + 1)
            if len(data) != APERTIUM_LEXC_SIZE or extracted.read(1):
                raise StemLexiconError("Apertium lexc size differs from the source pin")
        if _sha256(data) != APERTIUM_LEXC_SHA256:
            raise StemLexiconError("Apertium lexc SHA-256 differs from the source pin")
        _write_atomic(temporary_dir / APERTIUM_LEXC_NAME, data)
        metadata = {
            "repository": APERTIUM_REPOSITORY,
            "commit": APERTIUM_COMMIT,
            "archive_url": APERTIUM_ARCHIVE_URL,
            "path": APERTIUM_LEXC_NAME,
            "size": len(data),
            "sha256": _sha256(data),
        }
        _write_atomic(
            temporary_dir / "source.json",
            (json.dumps(metadata, indent=2, sort_keys=True) + "\n").encode("utf-8"),
        )
        if destination.exists():
            raise StemLexiconError(f"{destination}: destination already exists")
        os.replace(temporary_dir, destination)
        return {
            "path": str(target), "size": len(data), "sha256": _sha256(data),
            "commit": APERTIUM_COMMIT, "downloaded": True,
        }
    finally:
        if temporary_dir.exists():
            import shutil
            shutil.rmtree(temporary_dir)


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subcommands = parser.add_subparsers(dest="command", required=True)
    fetch = subcommands.add_parser("fetch", help="fetch the pinned Apertium lexc source")
    fetch.add_argument("--output-dir", type=Path, default=DEFAULT_APERTIUM_LEXC.parent)
    build = subcommands.add_parser("build", help="build and validate the stem lexicon")
    build.add_argument("--apertium-lexc", type=Path, default=DEFAULT_APERTIUM_LEXC)
    build.add_argument("--dictionary", type=Path, default=DEFAULT_DICTIONARY)
    build.add_argument("--review", type=Path, default=DEFAULT_REVIEW)
    build.add_argument("--exceptions", type=Path, default=DEFAULT_EXCEPTIONS)
    build.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    build.add_argument("--report", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        if args.command == "fetch":
            report = fetch_apertium(args.output_dir)
        else:
            built = build_lexicon(
                args.dictionary, args.review, args.exceptions, args.apertium_lexc
            )
            report_bytes = (json.dumps(
                built.report, ensure_ascii=False, indent=2, sort_keys=True
            ) + "\n").encode("utf-8")
            if args.report is not None:
                _write_atomic(args.report, report_bytes)
            _write_atomic(args.output, built.compressed)
            report = built.report
        print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    except StemLexiconGuardrailError as error:
        print(f"error: {error}", file=sys.stderr)
        return 4
    except (OSError, ValueError, dictionary_pack.DictionaryPackError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
