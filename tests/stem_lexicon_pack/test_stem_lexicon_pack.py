#!/usr/bin/env python3
"""Tests for the deterministic Tatar stem lexicon builder and binary format."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import struct
import sys
import tempfile
import unittest
import zlib
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


pack = load_module("stem_lexicon_pack", ROOT / "scripts" / "stem_lexicon_pack.py")
import dictionary_pack  # noqa: E402
import rebuild_assets  # noqa: E402
import wordform_gen  # noqa: E402

ASSET = ROOT / "app/src/main/assets/dictionaries/tatar_stems_v1.tstem.zlib"
DICTIONARY = ROOT / "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib"
CONTRACT = (
    ROOT / "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage"
    / "StemLexiconStorageContracts.kt"
)

SYNTHETIC_LEXC = """\
Multichar_Symbols
%<n%>
%<v%>
%<prn%>
LEXICON Root
Nouns;
Verbs;
Pronouns;
Digits;
Guesser;
LEXICON Digits
бер N1;
LEXICON Guesser
<.*> N1;
LEXICON Nouns
алма N1;
кондиционер N1-RUS;
<.*> N1;
LEXICON N1
%<n%>: #;
LEXICON N1-RUS
%<n%>: #;
LEXICON Verbs
абайла:абайла V1;
LEXICON V1
%<v%>: #;
LEXICON Pronouns
бер%-бере:бер%-бер PRN;
LEXICON PRN
%<prn%>: #;
"""


def flags_for(
    word: str, noun: bool = True, verb: bool = False,
    chains: bool = True, force_back: bool = False,
) -> int:
    flags = (pack.FLAG_NOUN if noun else 0) | (pack.FLAG_VERB if verb else 0)
    if chains:
        flags |= (pack.FLAG_NOUN_CHAIN if noun else 0) | (
            pack.FLAG_VERB_CHAIN if verb else 0
        )
    harmony = wordform_gen.BACK if force_back else wordform_gen.harmony_of(word)
    flags |= pack.FLAG_BACK if harmony == wordform_gen.BACK else pack.FLAG_FRONT
    if force_back:
        flags |= pack.FLAG_FORCE_BACK
    return flags


def entries(*words: str) -> list[pack.StemEntry]:
    return [pack.StemEntry(word, flags_for(word)) for word in words]


def rehash(raw: bytes) -> bytes:
    result = bytearray(raw)
    result[pack.CHECKSUM_OFFSET:pack.HEADER_SIZE] = b"\x00" * pack.CHECKSUM_SIZE
    result[pack.CHECKSUM_OFFSET:pack.HEADER_SIZE] = hashlib.sha256(result).digest()
    return bytes(result)


class ApertiumExtractTest(unittest.TestCase):
    def extract(self, source: str = SYNTHETIC_LEXC):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.lexc"
            path.write_text(source, encoding="utf-8")
            return pack.extract_apertium(path, verify_pin=False)

    def test_root_scope_pos_inheritance_and_escapes(self) -> None:
        triples, report = self.extract()
        self.assertIn(("алма", "n", "N1"), triples)
        self.assertIn(("кондиционер", "n", "N1-RUS"), triples)
        self.assertIn(("абайла", "v", "V1"), triples)
        self.assertIn(("бер-бере", "prn", "PRN"), triples)
        self.assertEqual(4, report["unique_stems"])
        self.assertEqual(1, report["regex_entries_skipped"])
        self.assertEqual(0, report["parse_errors"])

    def test_undefined_continuation_is_an_error(self) -> None:
        with self.assertRaises(pack.StemLexiconError):
            self.extract(SYNTHETIC_LEXC + "LEXICON Broken\nсүз MISSING;\n")

    def test_unterminated_entry_is_an_error(self) -> None:
        with self.assertRaises(pack.StemLexiconError):
            self.extract(SYNTHETIC_LEXC + "LEXICON Broken\nсүз N1\n")

    def test_source_pin_is_checked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.lexc"
            path.write_text(SYNTHETIC_LEXC, encoding="utf-8")
            with self.assertRaises(pack.StemLexiconError):
                pack.extract_apertium(path)


class MergePolicyTest(unittest.TestCase):
    def test_incremental_sources_and_conservative_chain_flags(self) -> None:
        apertium = {
            "алма": {"pos": {"n"}, "continuations": {"N1"}},
            "бала": {"pos": {"n"}, "continuations": {"N1"}},
            "кондиционер": {"pos": {"n"}, "continuations": {"N1-RUS"}},
        }
        review = {"башла": {"verb"}}
        entries_out, report = pack.merge_stems(
            ("алма", "алмалар"), apertium, review, wordform_gen.EMPTY_EXCEPTIONS
        )
        by_word = {entry.word: entry.flags for entry in entries_out}
        self.assertEqual({"бала", "башла", "кондиционер"}, set(by_word))
        self.assertTrue(by_word["бала"] & pack.FLAG_NOUN_CHAIN)
        self.assertFalse(by_word["башла"] & (pack.FLAG_NOUN_CHAIN | pack.FLAG_VERB_CHAIN))
        self.assertTrue(by_word["башла"] & pack.FLAG_VERB)
        self.assertTrue(by_word["кондиционер"] & pack.FLAG_FORCE_BACK)
        self.assertTrue(by_word["кондиционер"] & pack.FLAG_BACK)
        self.assertEqual(1, report["source_counts"]["inferred"])
        self.assertEqual(3, report["incremental_stems"])

    def test_dictionary_forms_never_enter_the_incremental_set(self) -> None:
        apertium = {"алма": {"pos": {"n"}, "continuations": {"N1"}}}
        entries_out, _report = pack.merge_stems(
            ("алма",), apertium, {}, wordform_gen.EMPTY_EXCEPTIONS
        )
        self.assertEqual((), entries_out)


class FormatTest(unittest.TestCase):
    def raw(self) -> bytes:
        return pack.build_raw(
            entries("алма", "алмалык", "бала", "әни"), "01" * 32, "23" * 32
        )

    def test_round_trip_and_header(self) -> None:
        raw = self.raw()
        parsed, fields = pack.parse_raw(raw)
        self.assertEqual(tuple(entries("алма", "алмалык", "бала", "әни")), parsed)
        self.assertEqual(4, fields["stem_count"])
        self.assertEqual("01" * 32, fields["dictionary_raw_sha256"])
        self.assertEqual("23" * 32, fields["apertium_lexc_sha256"])
        self.assertEqual(len(raw), fields["file_size"])

    def test_front_coding_spans_blocks(self) -> None:
        source = entries(
            "алма", "алмаб", "алмав", "алмаг", "алмад", "алмае", "алмаж", "алмаз",
            "әби",
        )
        raw = pack.build_raw(source, "01" * 32, "23" * 32)
        parsed, fields = pack.parse_raw(raw)
        self.assertEqual(tuple(source), parsed)
        self.assertEqual(2, fields["block_count"])

    def test_deterministic_compression(self) -> None:
        raw = self.raw()
        self.assertEqual(pack.compress_raw(raw), pack.compress_raw(raw))
        self.assertEqual(raw, pack.decompress_asset(pack.compress_raw(raw)))

    def test_truncation_and_corruption_are_rejected(self) -> None:
        raw = self.raw()
        for bad in (raw[:-1], raw[:pack.HEADER_SIZE - 1], raw + b"x"):
            with self.subTest(size=len(bad)), self.assertRaises(pack.StemLexiconError):
                pack.parse_raw(bad)
        corrupt = bytearray(raw)
        corrupt[-1] ^= 1
        with self.assertRaises(pack.StemLexiconError):
            pack.parse_raw(bytes(corrupt))

    def test_checksum_does_not_make_bad_flags_valid(self) -> None:
        raw = bytearray(self.raw())
        blocks_offset = struct.unpack_from("<I", raw, 28)[0]
        first_length = raw[blocks_offset]
        flags_offset = blocks_offset + 1 + first_length
        raw[flags_offset] = 0x80
        with self.assertRaises(pack.StemLexiconError):
            pack.parse_raw(rehash(bytes(raw)))

    def test_invalid_utf8_and_noncanonical_stems_are_rejected(self) -> None:
        raw = bytearray(self.raw())
        blocks_offset = struct.unpack_from("<I", raw, 28)[0]
        raw[blocks_offset + 1] = 0xFF
        with self.assertRaises(pack.StemLexiconError):
            pack.parse_raw(rehash(bytes(raw)))
        with self.assertRaises(pack.StemLexiconError):
            pack.build_raw(
                [pack.StemEntry("Алма", flags_for("алма"))], "01" * 32, "23" * 32
            )

    def test_unsorted_and_duplicate_stems_are_rejected(self) -> None:
        for source in (entries("әни", "алма"), entries("алма", "алма")):
            with self.assertRaises(pack.StemLexiconError):
                pack.build_raw(source, "01" * 32, "23" * 32)

    def test_raw_and_compressed_bounds(self) -> None:
        with self.assertRaises(pack.StemLexiconGuardrailError):
            pack.parse_raw(b"\x00" * (pack.MAX_RAW_BYTES + 1))
        oversized = zlib.compress(b"\x00" * (pack.MAX_RAW_BYTES + 1))
        with self.assertRaises(pack.StemLexiconGuardrailError):
            pack.decompress_asset(oversized)

    def test_zlib_requires_one_complete_member(self) -> None:
        compressed = pack.compress_raw(self.raw())
        for bad in (compressed[:-2], compressed + b"junk", compressed + compressed, b"bad"):
            with self.subTest(size=len(bad)), self.assertRaises(pack.StemLexiconError):
                pack.decompress_asset(bad)


class CliTest(unittest.TestCase):
    def test_bad_source_does_not_replace_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "bad.lexc"
            source.write_text(SYNTHETIC_LEXC, encoding="utf-8")
            output = root / "asset.zlib"
            output.write_bytes(b"old")
            argv = [
                "build", "--apertium-lexc", str(source),
                "--dictionary", str(DICTIONARY),
                "--review", str(ROOT / pack.DEFAULT_REVIEW),
                "--exceptions", str(ROOT / pack.DEFAULT_EXCEPTIONS),
                "--output", str(output),
            ]
            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                code = pack.main(argv)
            self.assertEqual(2, code)
            self.assertEqual(b"old", output.read_bytes())


class ShippedAssetTest(unittest.TestCase):
    def test_shipped_asset_matches_contract_and_incremental_policy(self) -> None:
        compressed = ASSET.read_bytes()
        raw = pack.decompress_asset(compressed)
        entries_out, fields = pack.parse_raw(raw)
        pins = rebuild_assets.read_pins(
            CONTRACT, "TATAR_STEMS_V1", "StemLexiconArtifactSpec",
            "expectedStemCount", linked=True,
        )
        self.assertEqual(pins.compressed_size, len(compressed))
        self.assertEqual(pins.compressed_sha256, hashlib.sha256(compressed).hexdigest())
        self.assertEqual(pins.raw_size, len(raw))
        self.assertEqual(pins.raw_sha256, hashlib.sha256(raw).hexdigest())
        self.assertEqual(pins.count, len(entries_out))
        self.assertEqual(pins.dictionary_raw_sha256, fields["dictionary_raw_sha256"])
        self.assertEqual(pack.APERTIUM_LEXC_SHA256, fields["apertium_lexc_sha256"])
        dictionary = dictionary_pack.validate_asset(DICTIONARY.read_bytes())
        self.assertEqual(pins.dictionary_raw_sha256, hashlib.sha256(dictionary.raw).hexdigest())
        self.assertTrue(set(dictionary.words).isdisjoint(entry.word for entry in entries_out))
        self.assertLessEqual(len(compressed), pack.MAX_COMPRESSED_BYTES)
        self.assertLessEqual(len(raw), pack.MAX_RAW_BYTES)

    def test_shipped_counts_and_flag_invariants(self) -> None:
        entries_out, _fields = pack.parse_raw(pack.decompress_asset(ASSET.read_bytes()))
        self.assertEqual(34_286, len(entries_out))
        pos_counts = {
            name: sum(
                (bool(entry.flags & pack.FLAG_NOUN) == noun)
                and (bool(entry.flags & pack.FLAG_VERB) == verb)
                for entry in entries_out
            )
            for name, noun, verb in (
                ("noun", True, False), ("verb", False, True), ("noun,verb", True, True)
            )
        }
        self.assertEqual({"noun": 32_939, "verb": 1_185, "noun,verb": 162}, pos_counts)
        self.assertEqual(34_179, sum(
            bool(entry.flags & (pack.FLAG_NOUN_CHAIN | pack.FLAG_VERB_CHAIN))
            for entry in entries_out
        ))
        self.assertEqual(5_364, sum(bool(entry.flags & pack.FLAG_FORCE_BACK) for entry in entries_out))


if __name__ == "__main__":
    unittest.main()
