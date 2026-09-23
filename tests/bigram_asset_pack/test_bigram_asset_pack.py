#!/usr/bin/env python3
"""Tests for the E5b real TATBIGR schema-2 packer and its validator.

Corpora are 582 MB and downloaded separately (see docs/DICTIONARY-E5A.md); everything here runs
on synthetic fixtures small enough to read, exactly like tests/bigram_pack does for E5a.
"""

from __future__ import annotations

import hashlib
import struct
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY_ROOT / "scripts"))

import bigram_asset_pack as pack  # noqa: E402
import dictionary_pack  # noqa: E402

PACK_SCRIPT = REPOSITORY_ROOT / "scripts" / "bigram_asset_pack.py"


def _write_asset(directory: Path, words: list[str]) -> Path:
    words_path = directory / "words.txt"
    words_path.write_text(
        "".join(f"{index}\t{word}\t{1000 - index}\n" for index, word in enumerate(words, start=1)),
        encoding="utf-8",
    )
    built = dictionary_pack.build_dictionary([words_path], len(words))
    asset_path = directory / "tatar.tdict.z"
    asset_path.write_bytes(built.asset)
    return asset_path


def _manual_raw(
    heads: list[str],
    successes_by_head: dict[str, list[str]],
    vocabulary_words: list[str],
) -> bytes:
    """Build a raw TATBIGR file directly, in caller-specified order — no sorting, no dedup.

    Used only to hand the validator structures ``pack_bigram_table`` would never produce on its
    own (unsorted or duplicated words), the same way dictionary_pack's tests keep an independent
    ``unchecked_raw`` builder next to the real one.
    """
    vocabulary = {word: index for index, word in enumerate(vocabulary_words)}
    head_offsets = [0]
    head_blob_parts = []
    for head in heads:
        encoded = head.encode("utf-8")
        head_blob_parts.append(encoded)
        head_offsets.append(head_offsets[-1] + len(encoded))
    head_blob = b"".join(head_blob_parts)

    success_ranges = [0]
    success_ids: list[int] = []
    for head in heads:
        for word in successes_by_head[head]:
            success_ids.append(vocabulary[word])
        success_ranges.append(len(success_ids))

    success_word_offsets = [0]
    success_blob_parts = []
    for word in vocabulary_words:
        encoded = word.encode("utf-8")
        success_blob_parts.append(encoded)
        success_word_offsets.append(success_word_offsets[-1] + len(encoded))
    success_blob = b"".join(success_blob_parts)

    head_count = len(heads)
    pair_count = len(success_ids)
    success_vocabulary_count = len(vocabulary_words)

    section1_offset = pack.HEADER_SIZE
    section2_offset = section1_offset + 4 * (head_count + 1)
    section3_offset = section2_offset + len(head_blob)
    section4_offset = section3_offset + 4 * (head_count + 1)
    section5_offset = section4_offset + 4 * pair_count
    section6_offset = section5_offset + 4 * (success_vocabulary_count + 1)
    file_size = section6_offset + len(success_blob)

    header = pack.HEADER.pack(
        pack.MAGIC,
        pack.SCHEMA_ID,
        pack.FORMAT_VERSION,
        pack.HEADER_SIZE,
        pack.CHECKSUM_ALGORITHM_SHA256,
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
        bytes(pack.CHECKSUM_SIZE),
    )
    raw = (
        header
        + struct.pack(f"<{head_count + 1}I", *head_offsets)
        + head_blob
        + struct.pack(f"<{head_count + 1}I", *success_ranges)
        + struct.pack(f"<{pair_count}I", *success_ids)
        + struct.pack(f"<{success_vocabulary_count + 1}I", *success_word_offsets)
        + success_blob
    )
    digest = hashlib.sha256(raw).digest()
    return raw[: pack.CHECKSUM_OFFSET] + digest + raw[pack.CHECKSUM_OFFSET + pack.CHECKSUM_SIZE :]


def _parse(raw: bytes, asset_path: Path) -> pack.ParsedBigramTable:
    """Разбор таблицы любой схемы: schema 2 напрямую, schema 3 — через словарь ассета.

    Семантические тесты ниже проверяют ВЫБОР голов, а не кодировку, поэтому им нужен
    единый вид разбора независимо от того, какая схема сегодня поставляется.
    """
    schema_id = struct.unpack_from("<H", raw, 8)[0]
    if schema_id == pack.SCHEMA_ID:
        return pack.validate_raw(raw)
    words = dictionary_pack.validate_asset(asset_path.read_bytes()).words
    return pack.validate_raw_v3(raw, words)


def _valid_raw() -> bytes:
    table = {
        "әни": [("өйгә", 3), ("кайтты", 1)],
        "зур": [("матур", 2)],
    }
    return pack.pack_bigram_table(["әни", "зур"], table, successes_per_head=2).raw


def replace_field(raw: bytes, offset: int, fmt: str, value: int) -> bytes:
    changed = bytearray(raw)
    struct.pack_into(fmt, changed, offset, value)
    return bytes(changed)


def rechecksum(raw: bytes) -> bytes:
    changed = bytearray(raw)
    changed[pack.CHECKSUM_OFFSET : pack.CHECKSUM_OFFSET + pack.CHECKSUM_SIZE] = bytes(
        pack.CHECKSUM_SIZE
    )
    digest = hashlib.sha256(bytes(changed)).digest()
    changed[pack.CHECKSUM_OFFSET : pack.CHECKSUM_OFFSET + pack.CHECKSUM_SIZE] = digest
    return bytes(changed)


class HeaderLayoutTest(unittest.TestCase):
    def test_header_is_96_bytes_with_digest_at_64(self) -> None:
        self.assertEqual(96, pack.HEADER.size)
        self.assertEqual(96, pack.HEADER_SIZE)
        self.assertEqual(64, pack.CHECKSUM_OFFSET)

    def test_caps_are_the_ones_the_gate_names(self) -> None:
        self.assertEqual(250_000, pack.MAX_COMPRESSED_BYTES)
        self.assertEqual(1_048_576, pack.MAX_RAW_BYTES)


class RoundTripTest(unittest.TestCase):
    def test_pack_and_validate_round_trip(self) -> None:
        table = {
            "әни": [("өйгә", 3), ("кайтты", 1)],
            "зур": [("матур", 2)],
        }
        result = pack.pack_bigram_table(["әни", "зур"], table, successes_per_head=2)
        parsed = pack.validate_raw(result.raw)
        # Code-point order, not Tatar alphabetical order: 'з' is U+0437, 'ә' is U+04D9, so "зур"
        # sorts before "әни" even though it would not in a Tatar dictionary.
        self.assertEqual(["зур", "әни"], parsed.head_words)
        self.assertEqual(["өйгә", "кайтты"], parsed.successes_by_head["әни"])
        self.assertEqual(["матур"], parsed.successes_by_head["зур"])
        self.assertEqual([], result.dropped_heads)
        self.assertEqual(2, result.head_count)
        self.assertEqual(3, result.pair_count)
        self.assertEqual(3, result.success_vocabulary_count)

    def test_heads_are_stored_lexically_regardless_of_frequency_order(self) -> None:
        # "әни" is the more frequent head (listed first) but code-point order (U+0437 'з' before
        # U+04D9 'ә') puts "зур" first in storage.
        table = {"әни": [("өйгә", 1)], "зур": [("матур", 5)]}
        result = pack.pack_bigram_table(["әни", "зур"], table, successes_per_head=2)
        parsed = pack.validate_raw(result.raw)
        self.assertEqual(["зур", "әни"], parsed.head_words)

    def test_successes_per_head_truncates_and_preserves_packing_order(self) -> None:
        table = {"әни": [("а", 5), ("б", 4), ("в", 3), ("г", 2)]}
        result = pack.pack_bigram_table(["әни"], table, successes_per_head=2)
        parsed = pack.validate_raw(result.raw)
        self.assertEqual(["а", "б"], parsed.successes_by_head["әни"])

    def test_a_head_with_zero_successes_is_dropped_and_reported(self) -> None:
        table = {"әни": [("өйгә", 1)]}  # "зур" never appears as a head at all
        result = pack.pack_bigram_table(["әни", "зур"], table, successes_per_head=2)
        self.assertEqual(["зур"], result.dropped_heads)
        self.assertEqual(1, result.head_count)
        parsed = pack.validate_raw(result.raw)
        self.assertEqual(["әни"], parsed.head_words)

    def test_success_vocabulary_is_deduplicated_across_heads(self) -> None:
        table = {"әни": [("өй", 2)], "зур": [("өй", 1)]}
        result = pack.pack_bigram_table(["әни", "зур"], table, successes_per_head=2)
        self.assertEqual(1, result.success_vocabulary_count)
        self.assertEqual(2, result.pair_count)


class ValidatorRejectsHeaderCorruptionTest(unittest.TestCase):
    def test_rejects_each_header_corruption_class(self) -> None:
        valid = _valid_raw()
        cases = {
            "short": valid[:20],
            "magic": b"BADMAGIC" + valid[8:],
            "schema": replace_field(valid, 8, "<H", 3),
            "version": replace_field(valid, 10, "<H", 2),
            "header_size": replace_field(valid, 12, "<H", 70),
            "algorithm": replace_field(valid, 14, "<H", 2),
        }
        for name, raw in cases.items():
            with self.subTest(name=name), self.assertRaises(pack.BigramFormatError):
                pack.validate_raw(raw)

    def test_rejects_checksum_mismatch(self) -> None:
        corrupted = bytearray(_valid_raw())
        corrupted[-1] ^= 1
        with self.assertRaisesRegex(pack.BigramFormatError, "checksum"):
            pack.validate_raw(bytes(corrupted))

    def test_rejects_non_canonical_section_arithmetic(self) -> None:
        valid = _valid_raw()
        # Offsets, in header order: three counts at 16/20/24, six section offsets at
        # 28/32/36/40/44/48, two blob lengths at 52/56, file size at 60.
        cases = {
            "section1": replace_field(valid, 28, "<I", pack.HEADER_SIZE + 4),
            "section2": replace_field(valid, 32, "<I", 999),
            "section3": replace_field(valid, 36, "<I", 999),
            "section4": replace_field(valid, 40, "<I", 999),
            "section5": replace_field(valid, 44, "<I", 999),
            "section6": replace_field(valid, 48, "<I", 999),
        }
        for name, raw in cases.items():
            with self.subTest(name=name), self.assertRaises(pack.BigramFormatError):
                pack.validate_raw(rechecksum(raw))

    def test_rejects_file_size_disagreeing_with_arithmetic(self) -> None:
        valid = _valid_raw()
        corrupted = rechecksum(replace_field(valid, 60, "<I", len(valid) + 8))
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw(corrupted)

    def test_rejects_truncated_and_trailing_bytes(self) -> None:
        valid = _valid_raw()
        for name, raw in {"truncated": valid[:-1], "trailing": valid + b"x"}.items():
            with self.subTest(name=name), self.assertRaises(pack.BigramFormatError):
                pack.validate_raw(raw)


class ValidatorRejectsSectionContentTest(unittest.TestCase):
    def test_rejects_unsorted_head_words(self) -> None:
        # Code-point order puts "зур" (U+0437) before "әни" (U+04D9); this order is reversed.
        raw = _manual_raw(
            heads=["әни", "зур"],
            successes_by_head={"әни": ["өй"], "зур": ["өй"]},
            vocabulary_words=["өй"],
        )
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw(raw)

    def test_rejects_duplicate_head_words(self) -> None:
        raw = _manual_raw(
            heads=["әни", "әни"],
            successes_by_head={"әни": ["өй"]},
            vocabulary_words=["өй"],
        )
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw(raw)

    def test_rejects_unsorted_success_vocabulary(self) -> None:
        raw = _manual_raw(
            heads=["әни"],
            successes_by_head={"әни": ["юл", "абы"]},
            vocabulary_words=["юл", "абы"],  # code-point descending — wrong
        )
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw(raw)

    def test_rejects_duplicate_success_vocabulary(self) -> None:
        raw = _manual_raw(
            heads=["әни"],
            successes_by_head={"әни": ["өй"]},
            vocabulary_words=["өй", "өй"],
        )
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw(rechecksum(raw))

    def test_rejects_empty_success_range(self) -> None:
        raw = _manual_raw(
            heads=["әни", "зур"],
            successes_by_head={"әни": [], "зур": ["өй"]},
            vocabulary_words=["өй"],
        )
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw(raw)

    def test_rejects_success_id_at_or_beyond_vocabulary_size(self) -> None:
        valid = _manual_raw(
            heads=["әни"],
            successes_by_head={"әни": ["өй"]},
            vocabulary_words=["өй"],
        )
        # The single success id lives right after the two (H+1)=2-entry u32 arrays that follow
        # the head blob: header(96) + head-offsets(2*4=8) + head-blob("әни"=6B) +
        # success-ranges(2*4=8) = 118.
        section4_offset = pack.HEADER_SIZE + 4 * 2 + len("әни".encode("utf-8")) + 4 * 2
        corrupted = rechecksum(replace_field(valid, section4_offset, "<I", 1))
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw(corrupted)

    def test_rejects_invalid_utf8_in_head_blob(self) -> None:
        valid = bytearray(_valid_raw())
        head_blob_offset = pack.HEADER_SIZE + 4 * 3  # 3 = head_count(2) + 1 offsets
        valid[head_blob_offset] = 0xFF
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw(rechecksum(bytes(valid)))


class DecompressTest(unittest.TestCase):
    def test_rejects_bad_zlib_streams(self) -> None:
        valid = pack.compress(_valid_raw())
        cases = {
            "invalid": b"not-zlib",
            "truncated": valid[:-1],
            "trailing": valid + b"trailing",
            "concatenated": valid + valid,
        }
        for name, asset in cases.items():
            with self.subTest(name=name), self.assertRaises(pack.BigramFormatError):
                pack.decompress(asset)

    def test_round_trips_through_compression(self) -> None:
        raw = _valid_raw()
        self.assertEqual(raw, pack.decompress(pack.compress(raw)))

    def test_rejects_compressed_asset_over_cap(self) -> None:
        original = pack.MAX_COMPRESSED_BYTES
        pack.MAX_COMPRESSED_BYTES = 4
        try:
            with self.assertRaises(pack.BigramBudgetError):
                pack.decompress(pack.compress(_valid_raw()))
        finally:
            pack.MAX_COMPRESSED_BYTES = original

    def test_rejects_decompression_bomb_over_raw_cap(self) -> None:
        original = pack.MAX_RAW_BYTES
        pack.MAX_RAW_BYTES = 4
        try:
            with self.assertRaises(pack.BigramBudgetError):
                pack.decompress(pack.compress(_valid_raw()))
        finally:
            pack.MAX_RAW_BYTES = original


class GeneratorGuardrailTest(unittest.TestCase):
    def test_generator_stops_on_raw_cap_before_writing_anything(self) -> None:
        table = {"әни": [("өйгә", 3)]}
        original = pack.MAX_RAW_BYTES
        pack.MAX_RAW_BYTES = 4
        try:
            with self.assertRaises(pack.BigramBudgetError):
                pack.pack_bigram_table(["әни"], table, successes_per_head=2)
        finally:
            pack.MAX_RAW_BYTES = original

    def test_generator_stops_on_compressed_cap(self) -> None:
        table = {"әни": [("өйгә", 3)]}
        original = pack.MAX_COMPRESSED_BYTES
        pack.MAX_COMPRESSED_BYTES = 4
        try:
            with self.assertRaises(pack.BigramBudgetError):
                pack.pack_bigram_table(["әни"], table, successes_per_head=2)
        finally:
            pack.MAX_COMPRESSED_BYTES = original


class HeadSelectionIsIndependentOfPairEvidenceTest(unittest.TestCase):
    """The mechanism docs/BIGRAM-ADJACENCY.md measured, pinned so it cannot be re-litigated quietly.

    ``run_pack`` calls ``select_heads(frequencies, heads)`` on the shipped .tdict's UNIGRAM
    frequencies and only then counts pairs, so the head SET is decided before a single sentence is
    tokenized. That is why no change to the adjacency rule — the one
    docs/RUSSIAN-BIGRAMS.md section 12 item 3 proposed — can give a successor to a word that is not
    already a head: "позвони" is absent from russian_top100k_v1 entirely and "приходи" sits at
    unigram rank 88 861, nine times below the shipped H = 10 000 cutoff.
    """

    def test_bigram_evidence_never_promotes_a_word_to_a_head(self) -> None:
        with TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            # _write_asset assigns frequency 1000 - index, so dictionary order IS frequency order:
            # "шалтырат" (Tatar imperative "call!") is the LEAST frequent of the three.
            asset_path = _write_asset(directory, ["алма", "китап", "шалтырат"])
            train = directory / "train-sentences.txt"
            # "шалтырат" heads three clean, bare, punctuation-free pairs; "алма" heads exactly one.
            # No tokenizer rule is in play here at all — the evidence is as good as evidence gets.
            train.write_text(
                "1\tшалтырат алма\n"
                "2\tшалтырат алма\n"
                "3\tшалтырат китап\n"
                "4\tалма китап\n"
                "5\tкитап алма\n",
                encoding="utf-8",
            )

            result, report = pack.run_pack([train], asset_path, 2, 4, 1)

            parsed = _parse(result.raw, asset_path)
            # Abundant pair evidence loses to unigram rank: the head set is exactly the top 2.
            self.assertNotIn("шалтырат", parsed.head_words)
            self.assertEqual(["алма", "китап"], parsed.head_words)
            self.assertEqual(2, report["requested_heads"])
            # And it is reachable as a SUCCESSOR, which is the only role the cutoff leaves it —
            # proving its absence above is the cutoff, not a vocabulary or tokenization failure.
            self.assertIn("шалтырат", pack.read_shipped_vocabulary(asset_path, pack.coverage.language_for("tat"))[0])


class ExtraHeadsTest(unittest.TestCase):
    """The addressable head list: the fix docs/IMPERATIVE-HEADS.md measured, pinned as behaviour.

    ``HeadSelectionIsIndependentOfPairEvidenceTest`` above pins the rule that pair evidence can
    never promote a word to a head. This class pins the ONE deliberate exception: a word named in
    ``--extra-heads`` becomes a head whatever its unigram rank, and nothing else about the file
    changes. Every test here fails against the packer as it stood before that option existed.
    """

    def test_a_word_below_the_cutoff_becomes_a_head_when_named(self) -> None:
        with TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            # Frequency order IS dictionary order here: "шалтырат" ("call!") is the least
            # frequent of the three, exactly like a real imperative sitting below H.
            asset_path = _write_asset(directory, ["алма", "китап", "шалтырат"])
            train = directory / "train-sentences.txt"
            train.write_text(
                "1\tшалтырат алма\n"
                "2\tшалтырат алма\n"
                "3\tшалтырат китап\n"
                "4\tалма китап\n"
                "5\tкитап алма\n",
                encoding="utf-8",
            )

            without, _ = pack.run_pack([train], asset_path, 2, 4, 1)
            self.assertNotIn("шалтырат", _parse(without.raw, asset_path).head_words)

            result, report = pack.run_pack(
                [train], asset_path, 2, 4, 1, extra_heads=["шалтырат"]
            )

            parsed = _parse(result.raw, asset_path)
            self.assertIn("шалтырат", parsed.head_words)
            self.assertEqual(["алма", "китап"], parsed.successes_by_head["шалтырат"])
            # The cutoff itself did not move: the two frequency-chosen heads are still there,
            # with the successors they had before, and the head count grew by exactly one.
            self.assertEqual(["алма", "китап", "шалтырат"], parsed.head_words)
            self.assertEqual(
                _parse(without.raw, asset_path).successes_by_head["алма"],
                parsed.successes_by_head["алма"],
            )
            self.assertEqual(without.head_count + 1, result.head_count)
            self.assertEqual(["шалтырат"], report["extra_heads_promoted"])
            self.assertEqual([], report["extra_heads_dropped_for_no_pairs"])

    def test_a_named_word_the_cutoff_already_reached_is_not_packed_twice(self) -> None:
        with TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            asset_path = _write_asset(directory, ["алма", "китап", "шалтырат"])
            train = directory / "train-sentences.txt"
            train.write_text("1\tалма китап\n2\tкитап алма\n", encoding="utf-8")

            result, report = pack.run_pack([train], asset_path, 2, 4, 1, extra_heads=["алма"])

            parsed = _parse(result.raw, asset_path)
            self.assertEqual(["алма", "китап"], parsed.head_words)
            self.assertEqual([], report["extra_heads_promoted"])

    def test_a_named_word_with_no_pairs_is_dropped_and_named_in_the_report(self) -> None:
        with TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            asset_path = _write_asset(directory, ["алма", "китап", "шалтырат"])
            train = directory / "train-sentences.txt"
            # "шалтырат" appears only as a SUCCESSOR, so as a head it has nothing to store, and
            # the validator forbids an empty range — the generator must drop it, not emit it.
            train.write_text("1\tалма шалтырат\n2\tкитап алма\n", encoding="utf-8")

            result, report = pack.run_pack(
                [train], asset_path, 2, 4, 1, extra_heads=["шалтырат"]
            )

            self.assertNotIn("шалтырат", _parse(result.raw, asset_path).head_words)
            self.assertEqual(["шалтырат"], report["extra_heads_dropped_for_no_pairs"])

    def test_the_list_may_not_add_a_word_the_dictionary_does_not_ship(self) -> None:
        """The border the mission dossier draws: heads are not a dictionary.

        Promoting a shipped word is a decision about suggestions. Adding an unshipped word would
        be a decision about the word list itself, which this file must never be able to make —
        so a word outside the shipped vocabulary stops the generation instead of being skipped.
        """
        with TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            asset_path = _write_asset(directory, ["алма", "китап", "шалтырат"])
            vocabulary, _ = pack.read_shipped_vocabulary(
                asset_path, pack.coverage.language_for("tat")
            )
            listing = directory / "extra.txt"
            listing.write_text("шалтырат\nпозвони\n", encoding="utf-8")

            with self.assertRaises(pack.BigramInputError) as raised:
                pack.read_extra_heads(listing, vocabulary)
            self.assertIn("позвони", str(raised.exception))

    def test_the_list_reader_takes_comments_blanks_and_duplicates(self) -> None:
        with TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            asset_path = _write_asset(directory, ["алма", "китап", "шалтырат"])
            vocabulary, _ = pack.read_shipped_vocabulary(
                asset_path, pack.coverage.language_for("tat")
            )
            listing = directory / "extra.txt"
            listing.write_text(
                "# заголовок\n\nшалтырат   # ранг 34 169\n   \nалма\nшалтырат\n",
                encoding="utf-8",
            )

            self.assertEqual(
                ["шалтырат", "алма"], pack.read_extra_heads(listing, vocabulary)
            )


class ShippedExtraHeadListTest(unittest.TestCase):
    """The list that actually ships is data, and data can rot — so it is checked, not trusted."""

    LISTING = REPOSITORY_ROOT / "scripts" / "bigram_extra_heads_tat.txt"
    DICTIONARY = (
        REPOSITORY_ROOT / "app" / "src" / "main" / "assets" / "dictionaries"
        / "tatar_top100k_v1.tdict.zlib"
    )

    def test_every_named_word_is_in_the_shipped_tatar_dictionary(self) -> None:
        vocabulary, _ = pack.read_shipped_vocabulary(
            self.DICTIONARY, pack.coverage.language_for("tat")
        )
        words = pack.read_extra_heads(self.LISTING, vocabulary)
        # 3 177 = 75 (13 первого отбора IMPERATIVE-HEADS, ранги [10 000, 15 000) + 62 слова
        # расширенного правила части B CORPUS-CONVERSATIONAL-TT, ранги [15 000, 40 000)) +
        # 3 102 слова правила EXPAND-1 (2026-09-23, ROADMAP-P4 P5a: частотный ранг >= 10 132,
        # >= 10 вхождений в разговорном обучении tt_conv_train90 — правило воспроизводится
        # scripts/bigram_extra_heads_conv.py).
        self.assertEqual(3_177, len(words))
        for expected in ("кил", "кит", "шалтырат", "сөйлә", "утыр", "җибәр", "эшлә", "укы"):
            self.assertIn(expected, words)
        # Образцы правила EXPAND-1 (первые строки его секции файла).
        for expected in ("абага", "абау", "абзар"):
            self.assertIn(expected, words)

    def test_no_named_word_is_reachable_by_the_cutoff_the_asset_was_packed_with(self) -> None:
        """If the cutoff ever grows past one of these, the line is dead weight in the file.

        H = 10 132 is the number docs/IMPERATIVE-HEADS.md derives and the shipped asset was
        packed with; a word inside it would be promoted twice over and its line here would say
        something untrue about why it is a head.
        """
        vocabulary, frequencies = pack.read_shipped_vocabulary(
            self.DICTIONARY, pack.coverage.language_for("tat")
        )
        by_cutoff = set(pack.select_heads(frequencies, 10_132))
        for word in pack.read_extra_heads(self.LISTING, vocabulary):
            self.assertNotIn(word, by_cutoff, f"{word} is already reached by the cutoff")


class EndToEndCliTest(unittest.TestCase):
    def test_cli_pack_writes_matching_atomic_outputs_and_report(self) -> None:
        with TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            asset_path = _write_asset(directory, ["әни", "өйгә", "кайтты", "зур", "матур"])
            train = directory / "train-sentences.txt"
            train.write_text(
                "1\tәни өйгә кайтты\n2\tзур матур\n3\tәни өйгә зур\n",
                encoding="utf-8",
            )
            out_raw = directory / "out.tatbigr"
            out_compressed = directory / "out.tatbigr.zlib"
            report_path = directory / "report.json"

            completed = subprocess.run(
                [
                    sys.executable,
                    str(PACK_SCRIPT),
                    "pack",
                    "--train",
                    str(train),
                    "--asset",
                    str(asset_path),
                    "--heads",
                    "5",
                    "--successes-per-head",
                    "3",
                    "--out-raw",
                    str(out_raw),
                    "--out-compressed",
                    str(out_compressed),
                    "--report",
                    str(report_path),
                ],
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertTrue(out_raw.exists())
            self.assertTrue(out_compressed.exists())
            parsed = _parse(out_raw.read_bytes(), asset_path)
            self.assertGreater(len(parsed.head_words), 0)
            self.assertEqual(
                pack.decompress(out_compressed.read_bytes()), out_raw.read_bytes()
            )
            import json

            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(
                hashlib.sha256(out_raw.read_bytes()).hexdigest(), report["raw_sha256"]
            )
            self.assertEqual(
                hashlib.sha256(out_compressed.read_bytes()).hexdigest(),
                report["compressed_sha256"],
            )


V3_WORDS = ["әни", "өйгә", "кайтты", "зур", "матур"]


def _v3_table() -> tuple[bytes, list[str], bytes]:
    """Корректная schema-3 таблица над маленьким словарём: (raw, слова, sha256 словаря-raw)."""
    with TemporaryDirectory() as raw_directory:
        asset_path = _write_asset(Path(raw_directory), V3_WORDS)
        parsed_dictionary = dictionary_pack.validate_asset(asset_path.read_bytes())
        word_index = {word: index for index, word in enumerate(parsed_dictionary.words)}
        dictionary_sha = hashlib.sha256(parsed_dictionary.raw).digest()
        table = {
            "әни": [("өйгә", 3), ("кайтты", 1)],
            "зур": [("матур", 2)],
        }
        result = pack.pack_bigram_table_v3(["әни", "зур"], table, 2, word_index, dictionary_sha)
        return result.raw, list(parsed_dictionary.words), dictionary_sha


def _rechecksum_v3(raw: bytes) -> bytes:
    changed = bytearray(raw)
    changed[pack.CHECKSUM_OFFSET_V3 : pack.CHECKSUM_OFFSET_V3 + pack.CHECKSUM_SIZE] = bytes(
        pack.CHECKSUM_SIZE
    )
    digest = hashlib.sha256(bytes(changed)).digest()
    changed[pack.CHECKSUM_OFFSET_V3 : pack.CHECKSUM_OFFSET_V3 + pack.CHECKSUM_SIZE] = digest
    return bytes(changed)


class Schema3HeaderLayoutTest(unittest.TestCase):
    def test_header_is_128_bytes_with_digest_at_96(self) -> None:
        self.assertEqual(128, pack.HEADER_V3.size)
        self.assertEqual(128, pack.HEADER_SIZE_V3)
        self.assertEqual(96, pack.CHECKSUM_OFFSET_V3)
        self.assertEqual(12, pack.BLOCK_RECORD_V3.size)

    def test_block_size_is_64(self) -> None:
        self.assertEqual(64, pack.HEAD_BLOCK_V3)


class Schema3RoundTripTest(unittest.TestCase):
    def test_pack_and_validate_round_trip(self) -> None:
        raw, words, dictionary_sha = _v3_table()
        parsed = pack.validate_raw_v3(raw, words, dictionary_sha)
        self.assertEqual(["зур", "әни"], parsed.head_words)  # кодпоинтный порядок
        self.assertEqual(["матур"], parsed.successes_by_head["зур"])
        self.assertEqual(["өйгә", "кайтты"], parsed.successes_by_head["әни"])

    def test_repack_from_schema2_is_content_identical(self) -> None:
        with TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            asset_path = _write_asset(directory, V3_WORDS)
            table = {
                "әни": [("өйгә", 3), ("кайтты", 1)],
                "зур": [("матур", 2)],
            }
            v2 = pack.pack_bigram_table(["әни", "зур"], table, successes_per_head=2)
            v2_asset = directory / "v2.tatbigr.zlib"
            v2_asset.write_bytes(v2.compressed)

            result, report = pack.run_repack(
                v2_asset, asset_path, pack.coverage.language_for("tat")
            )

            self.assertEqual(3, report["schema"])
            parsed = pack.validate_raw_v3(
                result.raw,
                list(dictionary_pack.validate_asset(asset_path.read_bytes()).words),
            )
            self.assertEqual(
                pack.validate_raw(v2.raw).successes_by_head, parsed.successes_by_head
            )

    def test_a_word_outside_the_dictionary_stops_the_pack(self) -> None:
        with TemporaryDirectory() as raw_directory:
            asset_path = _write_asset(Path(raw_directory), V3_WORDS)
            parsed_dictionary = dictionary_pack.validate_asset(asset_path.read_bytes())
            word_index = {word: index for index, word in enumerate(parsed_dictionary.words)}
            dictionary_sha = hashlib.sha256(parsed_dictionary.raw).digest()
            with self.assertRaises(pack.BigramInputError):
                pack.pack_bigram_table_v3(
                    ["дус"], {"дус": [("өйгә", 1)]}, 2, word_index, dictionary_sha
                )
            with self.assertRaises(pack.BigramInputError):
                pack.pack_bigram_table_v3(
                    ["әни"], {"әни": [("дус", 1)]}, 2, word_index, dictionary_sha
                )


class Schema3ValidatorRejectsTest(unittest.TestCase):
    def test_rejects_wrong_dictionary_link(self) -> None:
        raw, words, _dictionary_sha = _v3_table()
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw_v3(raw, words, bytes(32))

    def test_rejects_non_zero_reserved_bytes(self) -> None:
        raw, words, _ = _v3_table()
        changed = bytearray(raw)
        changed[88] = 1
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw_v3(bytes(_rechecksum_v3(bytes(changed))), words)

    def test_rejects_zero_success_count(self) -> None:
        raw, words, _ = _v3_table()
        counts_offset = struct.unpack_from("<I", raw, 40)[0]
        changed = bytearray(raw)
        changed[counts_offset] = 0
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw_v3(bytes(_rechecksum_v3(bytes(changed))), words)

    def test_rejects_zero_head_delta(self) -> None:
        raw, words, _ = _v3_table()
        deltas_offset = struct.unpack_from("<I", raw, 32)[0]
        changed = bytearray(raw)
        changed[deltas_offset] = 0  # дельта второй головы: 1 → 0
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw_v3(bytes(_rechecksum_v3(bytes(changed))), words)

    def test_rejects_counts_not_adding_up(self) -> None:
        raw, words, _ = _v3_table()
        counts_offset = struct.unpack_from("<I", raw, 40)[0]
        changed = bytearray(raw)
        changed[counts_offset] = 3  # было 2; сумма 4 ≠ pairCount 3
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw_v3(bytes(_rechecksum_v3(bytes(changed))), words)

    def test_rejects_success_index_beyond_dictionary(self) -> None:
        raw, words, _ = _v3_table()
        success_offset = struct.unpack_from("<I", raw, 44)[0]
        changed = bytearray(raw)
        changed[success_offset] = 90  # varint 90 при словаре из 5 слов
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw_v3(bytes(_rechecksum_v3(bytes(changed))), words)

    def test_rejects_checksum_mismatch_and_truncation(self) -> None:
        raw, words, _ = _v3_table()
        changed = bytearray(raw)
        changed[96] ^= 1
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw_v3(bytes(changed), words)
        with self.assertRaises(pack.BigramFormatError):
            pack.validate_raw_v3(raw[:-1], words)



if __name__ == "__main__":
    unittest.main()
