#!/usr/bin/env python3
"""Focused binary and counting tests for the combined TATBIGR schema-4 packer."""

from __future__ import annotations

import hashlib
import io
import json
import struct
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY_ROOT / "scripts"))

import bigram_asset_pack  # noqa: E402
import dictionary_pack  # noqa: E402
import trigram_asset_pack as pack  # noqa: E402

WORDS = sorted(["әни", "өйгә", "кайтты", "зур", "матур"])
DICTIONARY_SHA = hashlib.sha256(b"fixture dictionary").digest()


def _source_raw(words: list[str], dictionary_sha: bytes = DICTIONARY_SHA) -> bytes:
    word_index = {word: index for index, word in enumerate(words)}
    result = bigram_asset_pack.pack_bigram_table_v3(
        [words[0]],
        {words[0]: [(words[1], 3)]},
        8,
        word_index,
        dictionary_sha,
    )
    return result.raw


def _combined(
    words: list[str] | None = None,
    ranked: dict[int, list[int]] | None = None,
) -> tuple[pack.PackResult, list[str], bytes]:
    words = WORDS if words is None else words
    width = len(words)
    if ranked is None:
        ranked = {1: [2], width + 2: [3]}
    result = pack.pack_trigram_table_v4(
        _source_raw(words), words, DICTIONARY_SHA, ranked
    )
    return result, words, _source_raw(words)


def _rechecksum(raw: bytes) -> bytes:
    changed = bytearray(raw)
    changed[pack.CHECKSUM_OFFSET_V4 : pack.CHECKSUM_OFFSET_V4 + pack.CHECKSUM_SIZE] = bytes(
        pack.CHECKSUM_SIZE
    )
    digest = hashlib.sha256(changed).digest()
    changed[pack.CHECKSUM_OFFSET_V4 : pack.CHECKSUM_OFFSET_V4 + pack.CHECKSUM_SIZE] = digest
    return bytes(changed)


class Schema4RoundTripTest(unittest.TestCase):
    def test_binary_round_trip_preserves_both_parts(self) -> None:
        result, words, source_raw = _combined()
        self.assertEqual(224, pack.HEADER_V4.size)
        self.assertEqual(pack.HEADER_SIZE_V4, pack.HEADER_V4.size)
        self.assertEqual(4, struct.unpack_from("<H", result.raw, 8)[0])
        self.assertEqual(224, struct.unpack_from("<H", result.raw, 12)[0])
        self.assertEqual(len(result.raw), struct.unpack_from("<I", result.raw, 52)[0])
        self.assertEqual(bytes(8), result.raw[88:96])
        self.assertEqual(bytes(48), result.raw[176:224])

        parsed = pack.validate_raw_v4(result.raw, words, DICTIONARY_SHA)
        source = bigram_asset_pack.validate_raw_v3(source_raw, words, DICTIONARY_SHA)
        self.assertEqual(source.head_words, parsed.bigram.head_words)
        self.assertEqual(source.successes_by_head, parsed.bigram.successes_by_head)
        self.assertEqual(2, parsed.context_count)
        self.assertEqual(2, parsed.pair_count)
        self.assertEqual([words[2]], parsed.successes_by_context[(words[0], words[1])])
        self.assertEqual([words[3]], parsed.successes_by_context[(words[1], words[2])])
        self.assertEqual(result.raw, bigram_asset_pack.decompress(result.compressed))
        self.assertEqual(result.raw, _combined()[0].raw)

        bigram_end = (
            struct.unpack_from("<I", result.raw, 44)[0]
            + struct.unpack_from("<I", result.raw, 48)[0]
        )
        self.assertEqual(bigram_end, struct.unpack_from("<I", result.raw, 140)[0])
        self.assertEqual(
            len(result.raw),
            struct.unpack_from("<I", result.raw, 156)[0]
            + struct.unpack_from("<I", result.raw, 160)[0],
        )

    def test_multiple_blocks_use_relative_stream_offsets(self) -> None:
        words = sorted(["алма", "бала", "дус", "эш", "китап", "су", "яз", "өй", "әни", "матур"])
        ranked = {context: [(context + 1) % len(words)] for context in range(65)}
        result, _, _ = _combined(words, ranked)
        parsed = pack.validate_raw_v4(result.raw, words, DICTIONARY_SHA)
        self.assertEqual(65, parsed.context_count)
        self.assertEqual(2, struct.unpack_from("<I", result.raw, 136)[0])
        self.assertEqual(65, len(parsed.successes_by_context))
        second_record = struct.unpack_from(
            "<4I", result.raw, struct.unpack_from("<I", result.raw, 140)[0] + 16
        )
        self.assertEqual(divmod(64, len(words)), second_record[:2])
        self.assertGreater(second_record[2], 0)
        self.assertGreater(second_record[3], 0)


class DictionaryLinkTest(unittest.TestCase):
    def test_wrong_dictionary_digest_or_width_is_rejected(self) -> None:
        result, words, _ = _combined()
        with self.assertRaisesRegex(pack.TrigramFormatError, "different dictionary"):
            pack.validate_raw_v4(result.raw, words, bytes(32))
        with self.assertRaisesRegex(pack.TrigramFormatError, "word count"):
            pack.validate_raw_v4(result.raw, words[:-1], DICTIONARY_SHA)

    def test_successor_outside_dictionary_is_rejected(self) -> None:
        result, words, _ = _combined()
        changed = bytearray(result.raw)
        success_offset = struct.unpack_from("<I", changed, 156)[0]
        changed[success_offset] = 127
        with self.assertRaisesRegex(pack.TrigramFormatError, "outside the dictionary"):
            pack.validate_raw_v4(_rechecksum(bytes(changed)), words, DICTIONARY_SHA)

    def test_reserved_bytes_and_section_arithmetic_are_checked(self) -> None:
        result, words, _ = _combined()
        reserved = bytearray(result.raw)
        reserved[176] = 1
        with self.assertRaisesRegex(pack.TrigramFormatError, "reserved"):
            pack.validate_raw_v4(_rechecksum(bytes(reserved)), words, DICTIONARY_SHA)
        arithmetic = bytearray(result.raw)
        struct.pack_into("<I", arithmetic, 156, struct.unpack_from("<I", arithmetic, 156)[0] + 1)
        with self.assertRaisesRegex(pack.TrigramFormatError, "arithmetic"):
            pack.validate_raw_v4(_rechecksum(bytes(arithmetic)), words, DICTIONARY_SHA)


class WideContextDeltaTest(unittest.TestCase):
    def setUp(self) -> None:
        self.width = 65_537
        self.words = [f"w{index}" for index in range(self.width)]
        self.second_context = 65_536 * self.width
        self.assertGreater(self.second_context, pack.MAX_U32)
        self.result = pack.pack_trigram_table_v4(
            _source_raw(self.words),
            self.words,
            DICTIONARY_SHA,
            {0: [1], self.second_context: [1]},
        )

    def test_delta_wider_than_u32_round_trips(self) -> None:
        parsed = pack.validate_raw_v4(self.result.raw, self.words, DICTIONARY_SHA)
        self.assertIn(("w65536", "w0"), parsed.successes_by_context)
        deltas_offset = struct.unpack_from("<I", self.result.raw, 144)[0]
        deltas_size = struct.unpack_from("<I", self.result.raw, 148)[0]
        encoded = self.result.raw[deltas_offset : deltas_offset + deltas_size]
        self.assertEqual(encoded, pack._encode_varint_u64(self.second_context))

    def _replace_delta(self, replacement: bytes) -> bytes:
        raw = self.result.raw
        deltas_offset = struct.unpack_from("<I", raw, 144)[0]
        old_size = struct.unpack_from("<I", raw, 148)[0]
        counts_offset = struct.unpack_from("<I", raw, 152)[0]
        changed = bytearray(raw[:deltas_offset] + replacement + raw[counts_offset:])
        difference = len(replacement) - old_size
        struct.pack_into("<I", changed, 148, len(replacement))
        for offset in (52, 152, 156):
            struct.pack_into(
                "<I", changed, offset, struct.unpack_from("<I", raw, offset)[0] + difference
            )
        return _rechecksum(bytes(changed))

    def test_malformed_u64_deltas_are_rejected(self) -> None:
        cases = {
            "zero": b"\x00",
            "truncated": b"\x80",
            "overlong": b"\x81\x00",
            "overflow": b"\x80" * 9 + b"\x02",
        }
        for name, encoded in cases.items():
            with self.subTest(name=name):
                changed = self._replace_delta(encoded)
                with self.assertRaisesRegex(pack.TrigramFormatError, "delta|varint"):
                    pack.validate_raw_v4(changed, self.words, DICTIONARY_SHA)


class CountingTest(unittest.TestCase):
    def test_fixed_window_excludes_only_second_third_self_pairs(self) -> None:
        with TemporaryDirectory() as directory:
            train = Path(directory) / "train-sentences.txt"
            train.write_text(
                "1\tәни өйгә кайтты\n"
                "2\tәни өйгә зур\n"
                "3\tзур матур зур\n"
                "4\tәни өйгә өйгә кайтты\n",
                encoding="utf-8",
            )
            side = pack.count_training_side(
                [train], WORDS, recover_punctuation=False
            )
        index = {word: position for position, word in enumerate(WORDS)}
        width = len(WORDS)
        context = index["әни"] * width + index["өйгә"]
        self.assertEqual(2, side.context_totals[context])
        self.assertEqual({index["кайтты"], index["зур"]}, set(dict(side.trigrams[context])))
        self.assertEqual(3, side.bigram_totals[index["өйгә"]])
        self.assertNotIn(index["өйгә"], side.bigrams[index["өйгә"]])
        repeated_first = index["өйгә"] * width + index["өйгә"]
        self.assertEqual(1, side.context_totals[repeated_first])
        self.assertEqual(4, side.eligible_trigram_occurrences)

        source = {"өйгә": ["матур"]}
        ranked = pack.rank_contexts(side, WORDS, source)
        self.assertIn(index["матур"], ranked[context])


class CliTest(unittest.TestCase):
    def test_pack_writes_deterministic_outputs_and_provenance(self) -> None:
        with TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            words_path = directory / "words.txt"
            words_path.write_text(
                "".join(
                    f"{index}\t{word}\t{1000 - index}\n"
                    for index, word in enumerate(WORDS, start=1)
                ),
                encoding="utf-8",
            )
            built = dictionary_pack.build_dictionary([words_path], len(WORDS))
            dictionary_path = directory / "tatar.tdict.zlib"
            dictionary_path.write_bytes(built.asset)
            words = list(built.parsed.words)
            dictionary_sha = hashlib.sha256(built.raw).digest()
            source_path = directory / "source.tatbigr"
            source_path.write_bytes(_source_raw(words, dictionary_sha))
            train = directory / "train-sentences.txt"
            train.write_text(
                "1\tәни өйгә кайтты\n2\tәни өйгә зур\n3\tзур матур кайтты\n",
                encoding="utf-8",
            )
            outputs = []
            for run in (1, 2):
                raw_path = directory / f"out-{run}.tatbigr"
                compressed_path = directory / f"out-{run}.tatbigr.zlib"
                report_path = directory / f"report-{run}.json"
                code = pack.main(
                    [
                        "pack",
                        "--train",
                        str(train),
                        str(train),
                        "--source-bigram",
                        str(source_path),
                        "--dictionary",
                        str(dictionary_path),
                        "--out-raw",
                        str(raw_path),
                        "--out-compressed",
                        str(compressed_path),
                        "--report",
                        str(report_path),
                        "--recover-punctuation",
                    ],
                    stream=io.StringIO(),
                )
                self.assertEqual(0, code)
                outputs.append((raw_path.read_bytes(), compressed_path.read_bytes()))
                report = json.loads(report_path.read_text(encoding="utf-8"))
                self.assertEqual(50_000, report["configuration"]["max_contexts"])
                self.assertEqual(16, report["configuration"]["kept_trigram_successors"])
                self.assertEqual(8, report["configuration"]["packed_successors"])
                self.assertEqual(20_000_000, report["configuration"]["beta_millionths"])
                self.assertTrue(report["configuration"]["recover_punctuation"])
                self.assertEqual(2, report["corpora"][0]["weight"])
                self.assertEqual(train.stat().st_size, report["corpora"][0]["size"])
                self.assertEqual(
                    hashlib.sha256(train.read_bytes()).hexdigest(),
                    report["corpora"][0]["sha256"],
                )
                self.assertEqual(
                    hashlib.sha256(raw_path.read_bytes()).hexdigest(), report["raw_sha256"]
                )
                parsed = pack.validate_raw_v4(
                    raw_path.read_bytes(), words, dictionary_sha
                )
                self.assertGreater(parsed.context_count, 0)
                self.assertEqual(
                    report["counts"]["packed_trigram_pairs"], parsed.pair_count
                )
            self.assertEqual(outputs[0], outputs[1])


if __name__ == "__main__":
    unittest.main()
