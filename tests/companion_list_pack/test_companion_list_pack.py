#!/usr/bin/env python3
"""Tests for scripts/companion_list_pack.py — the cold-companion word list packer.

A synthetic dictionary asset drives both the unit surface (selection by stored
frequency with dictionary-order ties, the raw format, the parse-back mirror,
the guardrails) and the CLI contract (exit codes, fail-closed writes). The
committed-asset tests pin the shipped lists: the pins in
CompanionListStorageContracts.kt, the word count and the top-of-dictionary
selection rule. No network, no corpus dependency: the only live-tree inputs
are the two committed dictionary assets and the two committed lists.
"""

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACK_SCRIPT = ROOT / "scripts" / "companion_list_pack.py"
SCRIPTS = ROOT / "scripts"

TAT_DICTIONARY_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "dictionaries"
    / "tatar_top100k_v1.tdict.zlib"
)
RU_DICTIONARY_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "dictionaries"
    / "russian_top100k_v1.tdict.zlib"
)
TAT_LIST_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "dictionaries"
    / "tatar_companion_v1.tcomp.zlib"
)
RU_LIST_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "dictionaries"
    / "russian_companion_v1.tcomp.zlib"
)
CONTRACT = (
    ROOT / "app" / "src" / "main" / "java" / "rkr" / "simplekeyboard" / "inputmethod"
    / "latin" / "dictionary" / "storage" / "CompanionListStorageContracts.kt"
)


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


pack = load_module("companion_list_pack", PACK_SCRIPT)
sys.path.insert(0, str(SCRIPTS))
import dictionary_pack  # noqa: E402
import rebuild_assets  # noqa: E402


def write_dictionary(directory: Path, entries: list[tuple[str, int]]) -> Path:
    """A real schema-2 dictionary asset over [entries], built with the pack APIs.

    The serializer requires lexical order, so the entries are sorted here; the
    frequencies stay attached to their words.
    """
    raw = dictionary_pack.serialize_entries(
        sorted(entries), dictionary_pack.coverage.TATAR)
    path = directory / "dict.tdict.zlib"
    path.write_bytes(dictionary_pack.compress_raw(raw))
    return path


def run_main(dictionary: Path, output: Path, top: int = 20_000,
             language: str = "tat") -> tuple:
    """Invoke the CLI entry point capturing its streams; returns (exit, stdout)."""
    stdout = io.StringIO()
    argv = ["build", "--dictionary", str(dictionary), "--output", str(output),
            "--top", str(top), "--language", language]
    with contextlib.redirect_stdout(stdout), \
            contextlib.redirect_stderr(io.StringIO()):
        code = pack.main(argv)
    return code, stdout.getvalue()


class SelectWordsTest(unittest.TestCase):
    """The selection rule: stored frequency, ties by dictionary order."""

    def parsed(self, entries: list[tuple[str, int]]) -> dictionary_pack.ParsedDictionary:
        with tempfile.TemporaryDirectory() as directory:
            asset = write_dictionary(Path(directory), entries)
            return pack.read_dictionary(asset, dictionary_pack.coverage.TATAR)

    def test_top_words_by_frequency(self) -> None:
        # Dictionary order (lexical): алма, анла, әби, дус; frequencies rank дус first.
        parsed = self.parsed([("алма", 30), ("анла", 20), ("әби", 10), ("дус", 40)])
        self.assertEqual(("дус", "алма", "анла"), pack.select_words(parsed, 3))

    def test_ties_keep_dictionary_order(self) -> None:
        parsed = self.parsed([("алма", 10), ("анла", 10), ("әби", 10)])
        # Same frequency everywhere: the dictionary's own order decides.
        self.assertEqual(("алма", "анла"), pack.select_words(parsed, 2))

    def test_top_beyond_the_dictionary_keeps_all_words(self) -> None:
        parsed = self.parsed([("алма", 30), ("анла", 20)])
        self.assertEqual(("алма", "анла"), pack.select_words(parsed, 100))

    def test_a_nonpositive_top_is_an_error(self) -> None:
        parsed = self.parsed([("алма", 30)])
        with self.assertRaises(pack.CompanionListPackError):
            pack.select_words(parsed, 0)

    def test_a_top_over_the_bound_is_a_guardrail(self) -> None:
        parsed = self.parsed([("алма", 30)])
        with self.assertRaises(pack.CompanionListGuardrailError):
            pack.select_words(parsed, pack.MAX_WORDS + 1)


class BuildRawTest(unittest.TestCase):
    """The raw file: header fields, checksum, strictly ascending word rows."""

    def test_the_header_matches_the_format(self) -> None:
        raw = pack.build_raw(["алма", "анла"])
        magic, schema_id, version, header_size, algorithm, count, body_size = \
            struct.unpack_from("<8sHHHHII", raw)
        self.assertEqual(b"TATCOMP\x00", magic)
        self.assertEqual(pack.SCHEMA_ID, schema_id)
        self.assertEqual(pack.FORMAT_VERSION, version)
        self.assertEqual(pack.HEADER_SIZE, header_size)
        self.assertEqual(pack.CHECKSUM_ALGORITHM_SHA256, algorithm)
        self.assertEqual(2, count)
        self.assertEqual(len(raw) - pack.HEADER_SIZE, body_size)

    def test_the_checksum_covers_the_file_with_itself_zeroed(self) -> None:
        words = ["алма", "анла"]
        raw = pack.build_raw(words)
        checksum = raw[pack.HEADER_SIZE - 32:pack.HEADER_SIZE]
        zeroed = (raw[:pack.HEADER_SIZE - 32] + b"\x00" * 32
                  + raw[pack.HEADER_SIZE:])
        self.assertEqual(hashlib.sha256(zeroed).digest(), checksum)

    def test_words_are_stored_in_unsigned_byte_order(self) -> None:
        raw = pack.build_raw(["дус", "алма", "анла"])
        words, _fields = pack.parse_raw(raw)
        encoded = [word.encode("utf-8") for word in words]
        self.assertEqual(sorted(encoded), encoded)

    def test_a_duplicate_word_is_an_error(self) -> None:
        with self.assertRaises(pack.CompanionListPackError):
            pack.build_raw(["алма", "алма"])

    def test_an_empty_word_is_an_error(self) -> None:
        with self.assertRaises(pack.CompanionListPackError):
            pack.build_raw(["алма", ""])

    def test_a_word_over_the_length_bound_is_a_guardrail(self) -> None:
        with self.assertRaises(pack.CompanionListGuardrailError):
            pack.build_raw(["а" * (pack.MAX_WORD_BYTES + 1)])


class ParseRawTest(unittest.TestCase):
    """The python mirror of the Kotlin reader: every malformed shape is an error."""

    def raw(self, words: list[str]) -> bytes:
        return pack.build_raw(words)

    def test_the_happy_path_reads_back_the_words(self) -> None:
        words = ["алма", "анла", "әби"]
        parsed, fields = pack.parse_raw(self.raw(words))
        self.assertEqual(tuple(words), parsed)
        self.assertEqual(len(words), fields["word_count"])

    def test_a_wrong_magic_is_an_error(self) -> None:
        raw = bytearray(self.raw(["алма"]))
        raw[0] = ord("X")
        with self.assertRaises(pack.CompanionListPackError):
            pack.parse_raw(bytes(raw))

    def test_an_unsupported_schema_is_an_error(self) -> None:
        raw = bytearray(self.raw(["алма"]))
        struct.pack_into("<H", raw, 8, pack.SCHEMA_ID + 1)
        with self.assertRaises(pack.CompanionListPackError):
            pack.parse_raw(bytes(raw))

    def test_an_unsupported_format_version_is_an_error(self) -> None:
        raw = bytearray(self.raw(["алма"]))
        struct.pack_into("<H", raw, 10, pack.FORMAT_VERSION + 1)
        with self.assertRaises(pack.CompanionListPackError):
            pack.parse_raw(bytes(raw))

    def test_a_word_count_over_the_bound_is_an_error(self) -> None:
        raw = bytearray(self.raw(["алма"]))
        struct.pack_into("<I", raw, 16, pack.MAX_WORDS + 1)
        with self.assertRaises(pack.CompanionListPackError):
            pack.parse_raw(bytes(raw))

    def test_a_body_size_not_closing_the_file_is_an_error(self) -> None:
        raw = bytearray(self.raw(["алма"]))
        struct.pack_into("<I", raw, 20, 3)
        with self.assertRaises(pack.CompanionListPackError):
            pack.parse_raw(bytes(raw))

    def test_a_flipped_checksum_byte_is_an_error(self) -> None:
        raw = bytearray(self.raw(["алма"]))
        raw[pack.HEADER_SIZE - 32] ^= 1
        with self.assertRaises(pack.CompanionListPackError):
            pack.parse_raw(bytes(raw))

    def test_a_flipped_body_byte_is_an_error(self) -> None:
        raw = bytearray(self.raw(["алма"]))
        raw[pack.HEADER_SIZE] ^= 1
        with self.assertRaises(pack.CompanionListPackError):
            pack.parse_raw(bytes(raw))

    def test_a_truncated_file_is_an_error(self) -> None:
        raw = self.raw(["алма", "анла"])
        with self.assertRaises(pack.CompanionListPackError):
            pack.parse_raw(raw[:-2])

    def test_descending_words_are_an_error(self) -> None:
        # Hand-built body: the words are not ascending, so the reader refuses.
        body = bytearray()
        for word in ["дус", "алма"]:
            encoded = word.encode("utf-8")
            body.append(len(encoded))
            body.extend(encoded)
        header = pack.HEADER.pack(
            pack.MAGIC, pack.SCHEMA_ID, pack.FORMAT_VERSION, pack.HEADER_SIZE,
            pack.CHECKSUM_ALGORITHM_SHA256, 2, len(body), b"\x00" * 32,
        )
        raw = bytearray(header) + body
        import hashlib as _hashlib
        raw[pack.HEADER_SIZE - 32:pack.HEADER_SIZE] = _hashlib.sha256(bytes(raw)).digest()
        with self.assertRaises(pack.CompanionListPackError):
            pack.parse_raw(bytes(raw))

    def test_more_body_than_the_word_count_is_an_error(self) -> None:
        body = bytearray()
        encoded = "алма".encode("utf-8")
        body.append(len(encoded))
        body.extend(encoded)
        header = pack.HEADER.pack(
            pack.MAGIC, pack.SCHEMA_ID, pack.FORMAT_VERSION, pack.HEADER_SIZE,
            pack.CHECKSUM_ALGORITHM_SHA256, 1, len(body) + 4, b"\x00" * 32,
        )
        raw = bytearray(header) + body + bytearray(b"\x01\x02\x03\x04")
        import hashlib as _hashlib
        raw[pack.HEADER_SIZE - 32:pack.HEADER_SIZE] = _hashlib.sha256(bytes(raw)).digest()
        with self.assertRaises(pack.CompanionListPackError):
            pack.parse_raw(bytes(raw))


class DecompressTest(unittest.TestCase):
    """The bounded-inflation rule: one zlib member, never over the raw bound."""

    def test_trailing_data_is_an_error(self) -> None:
        compressed = pack.compress_raw(pack.build_raw(["алма"])) + b"junk"
        with self.assertRaises(pack.CompanionListPackError):
            pack.decompress_asset(compressed)

    def test_a_truncated_stream_is_an_error(self) -> None:
        compressed = pack.compress_raw(pack.build_raw(["алма"]))
        with self.assertRaises(pack.CompanionListPackError):
            pack.decompress_asset(compressed[: len(compressed) // 2])

    def test_an_empty_stream_is_an_error(self) -> None:
        # Zero bytes inflate to nothing: the reader refuses the empty raw file.
        with self.assertRaises(pack.CompanionListPackError):
            pack.decompress_asset(b"")

    def test_a_nonzlib_stream_is_an_error(self) -> None:
        with self.assertRaises(pack.CompanionListPackError):
            pack.decompress_asset(b"plainly not a zlib member")



class CliTest(unittest.TestCase):
    """The command line: exit codes, atomic write, the JSON report."""

    def test_build_writes_the_asset_and_reports_the_pins(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entries = [("алма", 30), ("анла", 20), ("әби", 10), ("дус", 40)]
            dictionary = write_dictionary(root, entries)
            output = root / "list.tcomp.zlib"
            code, stdout = run_main(dictionary, output, top=3)
            self.assertEqual(0, code)
            report = json.loads(stdout)
            self.assertEqual(3, report["word_count"])
            self.assertEqual(len(output.read_bytes()), report["compressed_size"])
            self.assertEqual(
                hashlib.sha256(output.read_bytes()).hexdigest(),
                report["compressed_sha256"],
            )
            words, _fields = pack.parse_raw(
                pack.decompress_asset(output.read_bytes()))
            self.assertEqual(sorted(("дус", "алма", "анла")), list(words))

    def test_the_same_inputs_give_the_same_bytes(self) -> None:
        entries = [("алма", 30), ("анла", 20), ("әби", 10)]
        with tempfile.TemporaryDirectory() as first, \
                tempfile.TemporaryDirectory() as second:
            one = write_dictionary(Path(first), entries)
            two = write_dictionary(Path(second), entries)
            out_one = Path(first) / "list.tcomp.zlib"
            out_two = Path(second) / "list.tcomp.zlib"
            self.assertEqual(0, run_main(one, out_one)[0])
            self.assertEqual(0, run_main(two, out_two)[0])
            self.assertEqual(out_one.read_bytes(), out_two.read_bytes())

    def test_a_broken_dictionary_writes_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "list.tcomp.zlib"
            (root / "dict.tdict.zlib").write_bytes(b"not a zlib stream")
            code, _stdout = run_main(root / "dict.tdict.zlib", output)
            self.assertEqual(2, code)
            self.assertFalse(output.exists())

    def test_a_guardrail_breach_writes_nothing_and_exits_4(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dictionary = write_dictionary(root, [("алма", 30)])
            output = root / "list.tcomp.zlib"
            code, _stdout = run_main(dictionary, output, top=pack.MAX_WORDS + 1)
            self.assertEqual(4, code)
            self.assertFalse(output.exists())


class CommittedAssetsTest(unittest.TestCase):
    """The two shipped lists against the committed dictionary pins and contracts."""

    def read_list(self, asset: Path) -> tuple:
        return pack.parse_raw(pack.decompress_asset(asset.read_bytes()))

    def test_the_tatar_list_matches_its_contract_pins(self) -> None:
        asset = TAT_LIST_ASSET.read_bytes()
        words, _fields = self.read_list(TAT_LIST_ASSET)
        self.assertEqual(rebuild_assets.measure_companion(TAT_LIST_ASSET),
                         rebuild_assets.read_pins(
                             CONTRACT, "TATAR_COMPANION_V1",
                             "CompanionListArtifactSpec", "expectedWordCount"))
        self.assertEqual(20_000, len(words))

    def test_the_russian_list_matches_its_contract_pins(self) -> None:
        words, _fields = self.read_list(RU_LIST_ASSET)
        self.assertEqual(rebuild_assets.measure_companion(RU_LIST_ASSET),
                         rebuild_assets.read_pins(
                             CONTRACT, "RUSSIAN_COMPANION_V1",
                             "CompanionListArtifactSpec", "expectedWordCount"))
        self.assertEqual(20_000, len(words))

    def test_each_list_is_the_top_of_its_dictionary(self) -> None:
        for list_asset, dictionary_asset, tag in (
                (TAT_LIST_ASSET, TAT_DICTIONARY_ASSET, "tat"),
                (RU_LIST_ASSET, RU_DICTIONARY_ASSET, "rus")):
            parsed = dictionary_pack.validate_asset(
                dictionary_asset.read_bytes(),
                language=dictionary_pack.coverage.language_for(tag))
            words, _fields = self.read_list(list_asset)
            expected = pack.select_words(parsed, 20_000)
            self.assertEqual(sorted(expected), list(words), tag)

    def test_rebuilding_the_committed_dictionary_reproduces_the_bytes(self) -> None:
        for list_asset, dictionary_asset, tag in (
                (TAT_LIST_ASSET, TAT_DICTIONARY_ASSET, "tat"),
                (RU_LIST_ASSET, RU_DICTIONARY_ASSET, "rus")):
            parsed = dictionary_pack.validate_asset(
                dictionary_asset.read_bytes(),
                language=dictionary_pack.coverage.language_for(tag))
            rebuilt = pack.build_list(parsed, 20_000)
            self.assertEqual(list_asset.read_bytes(), rebuilt.compressed, tag)


if __name__ == "__main__":
    unittest.main()
