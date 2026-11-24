#!/usr/bin/env python3
"""Tests for research/corpus/candidate_inputs.py.

Synthetic streams cover the tokenizer, the deterministic thinning, the word and lexicon counts
and the community document rules. One live-tree test checks that every candidate file the
rebuild reads is declared, with the same size and SHA-256, in the acquisition pin file.
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research" / "corpus"))
sys.path.insert(0, str(ROOT / "scripts"))

import candidate_inputs as ci  # noqa: E402

PINS = ROOT / "data/corpus-sources/acquisition-pins.json"
MANIFEST = ROOT / "data/corpus-manifest.json"
CANDIDATE_PREFIX = "cand-"


def write(directory: Path, name: str, text: str) -> Path:
    path = directory / name
    path.write_text(text, encoding="utf-8")
    return path


def documents(*texts: str) -> str:
    return "".join(json.dumps({"text": text}, ensure_ascii=False) + "\n" for text in texts)


def community(directory: Path, *texts: str) -> tuple[dict[str, int], dict]:
    """Write the documents and the cleaned stream that survived the cleaning run."""
    raw = write(directory, "in.jsonl", documents(*texts))
    cleaned = write(directory, "cleaned.txt", "\n".join(texts) + "\n")
    return ci.community_counts(raw, cleaned)


class TokenTest(unittest.TestCase):
    def test_words_are_lowercased_and_restricted_to_the_alphabet(self) -> None:
        self.assertEqual(
            list(ci.tokens("Бүген КОЯШ чыкты, 2 кеше — әни")),
            ["бүген", "кояш", "чыкты", "кеше", "әни"],
        )

    def test_phone_like_tokens_never_come_through(self) -> None:
        self.assertEqual(list(ci.tokens("шалтыратыгыз 89171234567")), ["шалтыратыгыз"])


class ThinningTest(unittest.TestCase):
    def test_thinning_is_deterministic_and_ordered(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = write(Path(temporary), "in.txt", "\n".join(
                f"Җөмлә {number} бүген язылды." for number in range(300)) + "\n")
            first, report = ci.sentence_file(path, 3000, 7)
            second, _ = ci.sentence_file(path, 3000, 7)
        self.assertEqual(first, second)
        self.assertEqual(first, sorted(first, key=lambda row: row[0]))
        self.assertLess(len(first), 300)
        self.assertEqual(report["stride"], 7)
        self.assertTrue(all(isinstance(identifier, int) and identifier > 0
                            for identifier, _text in first))

    def test_a_stride_wider_than_the_source_keeps_nothing_and_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = write(Path(temporary), "in.txt", "Бер генә юл.\n")
            with self.assertRaisesRegex(ci.CandidateInputError, "kept no line"):
                ci.sentence_file(path, 1, 10**9)


class CountTest(unittest.TestCase):
    def test_word_counts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = write(Path(temporary), "in.txt", "Әни килде.\nӘни китте.\n")
            counts, report = ci.word_counts(path)
        self.assertEqual(counts, {"әни": 2, "килде": 1, "китте": 1})
        self.assertEqual(report["types"], 3)

    def test_an_empty_stream_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = write(Path(temporary), "in.txt", "hello world\n")
            with self.assertRaisesRegex(ci.CandidateInputError, "no token"):
                ci.word_counts(path)

    def test_a_lexicon_counts_each_type_once(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = write(Path(temporary), "in.txt", "китап\nсу\nеке юл\n\nмәктәп\n")
            counts, report = ci.lexicon_counts(path)
        self.assertEqual(counts, {"китап": 1, "су": 1, "мәктәп": 1})
        self.assertEqual(report["types"], 3)


class CommunityRuleTest(unittest.TestCase):
    def test_a_word_needs_three_documents(self) -> None:
        body_texts = (
            "Китап бик кызык.",
            "Китап укыйм.",
            "Китап сатыла.",
            "Бер китап юк.",
        )
        with tempfile.TemporaryDirectory() as temporary:
            counts, report = community(Path(temporary), *body_texts)
        self.assertEqual(counts["китап"], 4)
        self.assertEqual(report["documents"], 4)

    def test_two_documents_are_not_enough(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ci.CandidateInputError, "no word passed"):
                community(Path(temporary), "Китап бар.", "Китап юк.")

    def test_a_capitalized_word_is_a_name(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ci.CandidateInputError, "no word passed"):
                community(Path(temporary), "Рөстәм килде, Рөстәм укыды.", "Рөстәм яза.",
                          "Рөстәм йөгерә.")

    def test_one_lowercase_occurrence_is_enough(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            counts, _ = community(Path(temporary), "Китап бар.", "Китап юк.",
                                  "Китап укыйм, китап язам.")
        self.assertIn("китап", counts)

    def test_the_count_floor_applies(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            counts, _ = community(Path(temporary), "Бер сүз.", "Ике сүз.", "Өч сүз.")
        self.assertEqual(counts, {"сүз": 3})

    def test_a_sentence_the_cleaning_run_dropped_is_not_counted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            raw = write(base, "in.jsonl", documents(
                "Бар китап.", "Юк китап.", "Сатыла китап.", "Бушлай китап."))
            cleaned = write(base, "cleaned.txt", "Бар китап.\nЮк китап.\nСатыла китап.\n")
            counts, report = ci.community_counts(raw, cleaned)
        self.assertEqual(counts, {"китап": 3})
        self.assertEqual(report["documents"], 3)


class PinsTest(unittest.TestCase):
    def test_candidate_inputs_are_declared_with_their_manifest_pins(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        pins = json.loads(PINS.read_text(encoding="utf-8"))
        streams = pins["streams"]
        candidates = {
            name: entry for name, entry in manifest.items()
            if name.startswith(CANDIDATE_PREFIX)
        }
        self.assertTrue(candidates, "the manifest declares no candidate input")
        self.assertEqual(sorted(candidates), sorted(streams))
        for name, entry in candidates.items():
            with self.subTest(name=name):
                self.assertEqual(entry["size"], streams[name]["size_bytes"])
                self.assertEqual(entry["sha256"], streams[name]["sha256"])
                self.assertTrue(streams[name]["generation_command"])


if __name__ == "__main__":
    unittest.main()
