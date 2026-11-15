#!/usr/bin/env python3
"""Structural and evidence contracts for the reviewable Tatar data sets."""
from __future__ import annotations

import datetime
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORDFORM_REVIEW = ROOT / "data" / "dictionary" / "tatar-wordform-review.tsv"
LANGUAGE_EVAL = ROOT / "data" / "dictionary" / "tatar-language-eval-review.tsv"
WORDFORM_HEADER = [
    "word", "lemma", "paradigm_cell", "corpus_evidence", "attestation_status",
    "admission_decision", "approved", "reviewer", "review_date", "note",
]
LANGUAGE_HEADER = [
    "case_id", "category", "input", "expected_output", "evaluation", "source_context",
    "approved", "reviewer", "review_date", "note",
]


def read_rows(path: Path, header: list[str]) -> list[dict[str, str]]:
    lines = [line for line in path.read_text(encoding="utf-8").splitlines()
             if line and not line.startswith("#")]
    if not lines or lines[0].split("\t") != header:
        raise AssertionError(f"{path}: wrong header")
    rows = []
    for line_number, line in enumerate(lines[1:], start=2):
        fields = line.split("\t")
        if len(fields) != len(header):
            raise AssertionError(f"{path}:{line_number}: wrong column count")
        rows.append(dict(zip(header, fields)))
    return rows


class ReviewMetadataMixin:
    def assert_review_metadata(self, row: dict[str, str]) -> None:
        approved = row["approved"]
        self.assertIn(approved, ("", "yes", "no"))
        if approved == "yes":
            self.assertTrue(row["reviewer"].strip())
            datetime.date.fromisoformat(row["review_date"])
            self.assertTrue(row["note"].strip())


class WordformReviewTest(ReviewMetadataMixin, unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.rows = read_rows(WORDFORM_REVIEW, WORDFORM_HEADER)
        cls.by_word = {row["word"]: row for row in cls.rows}

    def test_words_are_unique_and_review_state_is_pending(self) -> None:
        self.assertEqual(len(self.by_word), len(self.rows))
        for row in self.rows:
            with self.subTest(word=row["word"]):
                self.assert_review_metadata(row)
                # This data set is prepared for review, not presented as completed native review.
                self.assertEqual(row["approved"], "")
                self.assertEqual(
                    (row["reviewer"], row["review_date"]), ("", "")
                )

    def test_target_variants_are_held_on_single_source_evidence(self) -> None:
        for word in ("бүгөн", "йөрөп", "һәрмәлдә"):
            with self.subTest(word=word):
                row = self.by_word[word]
                self.assertEqual(row["attestation_status"], "single-source")
                self.assertEqual(row["admission_decision"], "hold")
                self.assertIn("Tatoeba=1", row["corpus_evidence"])
                self.assertIn("extra=absent", row["corpus_evidence"])

    def test_independently_attested_missing_candidate_is_explicit(self) -> None:
        row = self.by_word["горурланасыз"]
        self.assertEqual(row["paradigm_cell"], "present.2pl")
        self.assertEqual(row["attestation_status"], "independent-attestation")
        self.assertEqual(row["admission_decision"], "candidate-awaiting-review")
        self.assertIn("Tatoeba=1", row["corpus_evidence"])
        self.assertIn("extra=present", row["corpus_evidence"])

    def test_first_plural_rule_has_back_front_and_negative_evidence(self) -> None:
        for word, cell in (
            ("язабыз", "present.1pl"),
            ("китәбез", "present.1pl"),
            ("бармыйбыз", "present.neg.1pl"),
            ("эшләмибез", "present.neg.1pl"),
        ):
            with self.subTest(word=word):
                row = self.by_word[word]
                self.assertEqual(row["paradigm_cell"], cell)
                self.assertEqual(row["attestation_status"], "independent-attestation")
                self.assertEqual(row["admission_decision"], "generated-by-rule")


class LanguageEvaluationReviewTest(ReviewMetadataMixin, unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.rows = read_rows(LANGUAGE_EVAL, LANGUAGE_HEADER)
        cls.by_id = {row["case_id"]: row for row in cls.rows}

    def test_ids_are_unique_and_all_rows_are_reviewable(self) -> None:
        self.assertEqual(len(self.by_id), len(self.rows))
        for row in self.rows:
            with self.subTest(case_id=row["case_id"]):
                self.assert_review_metadata(row)
                self.assertEqual(row["approved"], "")
                self.assertEqual(row["evaluation"], "preserve")
                self.assertEqual(row["input"], row["expected_output"])
                self.assertTrue(row["source_context"])
                self.assertTrue(row["note"])

    def test_required_categories_have_positive_and_safety_cases(self) -> None:
        counts = Counter(row["category"] for row in self.rows)
        for category in (
            "conversational", "proper_name", "casing", "internal_hyphen", "apostrophe",
            "autocorrect_safety",
        ):
            self.assertGreaterEqual(counts[category], 2, category)
        safety = {row["input"] for row in self.rows
                  if row["category"] == "autocorrect_safety"}
        self.assertEqual(safety, {"бүгөн", "горурланасыз", "йөрөп", "һәрмәлдә"})

    def test_script_and_punctuation_cases_exercise_the_intended_boundaries(self) -> None:
        for row in self.rows:
            if row["category"] == "internal_hyphen":
                self.assertIn("-", row["input"])
            if row["category"] == "apostrophe":
                self.assertTrue({"'", "’"}.intersection(row["input"]))
        self.assertIn("БҮГЕН КИТӘБЕЗ.", {
            row["input"] for row in self.rows if row["category"] == "casing"
        })
        self.assertIn("N’yu-Yorkta", {
            row["input"] for row in self.rows if row["category"] == "apostrophe"
        })


if __name__ == "__main__":
    unittest.main()
