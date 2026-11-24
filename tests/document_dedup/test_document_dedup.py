"""Tests for research/corpus/document_dedup.py."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research" / "corpus"))

from document_dedup import DocumentDeduplicator, DuplicateMatch  # noqa: E402


def near_duplicate_pair() -> tuple[str, str]:
    tokens = [f"token-{index}" for index in range(128)]
    original = " ".join(tokens)
    tokens[64] = "replacement"
    return original, " ".join(tokens)


class DocumentDeduplicatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "dedup.sqlite3"

    def test_exact_match_after_nfc_and_control_normalization(self) -> None:
        original = " Cafe\u0301\u200dalpha\x00beta\ud800gamma "
        duplicate = "Caféalpha  beta\ngamma"
        with DocumentDeduplicator(self.path) as dedup:
            self.assertIsNone(dedup.check("source-a", "original", original))
            match = dedup.check("source-a", "duplicate", duplicate)
            self.assertEqual(
                DuplicateMatch("exact", "source-a", "original", 1.0), match
            )
            self.assertEqual({"exact": 1, "near": 0, "kept": 1}, dedup.counts())

    def test_exact_match_crosses_sources(self) -> None:
        text = "Бер генә җөмлә дә югалмасын"
        with DocumentDeduplicator(self.path) as dedup:
            dedup.check("source-a", "document-a", text)
            match = dedup.check("source-b", "document-b", text)
            self.assertEqual("exact", match.reason)
            self.assertEqual("source-a", match.original_source)
            self.assertEqual("document-a", match.original_document_id)
            self.assertEqual(1.0, match.similarity)

    def test_one_token_change_is_a_near_duplicate(self) -> None:
        original, changed = near_duplicate_pair()
        with DocumentDeduplicator(self.path) as dedup:
            dedup.check("source-a", "original", original)
            match = dedup.check("source-b", "changed", changed)
            self.assertEqual("near", match.reason)
            self.assertEqual("source-a", match.original_source)
            self.assertEqual("original", match.original_document_id)
            self.assertGreaterEqual(match.similarity, 0.80)
            self.assertLess(match.similarity, 1.0)
            self.assertEqual({"exact": 0, "near": 1, "kept": 1}, dedup.counts())

    def test_distinct_documents_are_kept(self) -> None:
        first = " ".join(f"alpha-{index}" for index in range(40))
        second = " ".join(f"beta-{index}" for index in range(40))
        with DocumentDeduplicator(self.path) as dedup:
            self.assertIsNone(dedup.check("source-a", "first", first))
            self.assertIsNone(dedup.check("source-a", "second", second))
            self.assertEqual({"exact": 0, "near": 0, "kept": 2}, dedup.counts())

    def test_short_documents_are_exact_only_and_empty_documents_are_ignored(self) -> None:
        with DocumentDeduplicator(self.path) as dedup:
            self.assertIsNone(dedup.check("source-a", "empty", " \u200d\t\n"))
            self.assertEqual({"exact": 0, "near": 0, "kept": 0}, dedup.counts())
            self.assertIsNone(dedup.check("source-a", "short", "one two three four"))
            self.assertIsNone(dedup.check("source-a", "reordered", "four three two one"))
            match = dedup.check("source-a", "short-copy", "one two three four")
            self.assertEqual("exact", match.reason)
            self.assertEqual(
                {"exact": 1, "near": 0, "kept": 2}, dedup.counts()
            )

    def test_find_matches_evaluation_without_inserting_or_counting(self) -> None:
        original, changed = near_duplicate_pair()
        with DocumentDeduplicator(self.path) as dedup:
            dedup.check("evaluation", "one", original)
            match = dedup.find(changed)
            self.assertIsNotNone(match)
            assert match is not None
            self.assertEqual(("near", "evaluation", "one"), (match.reason, match.original_source, match.original_document_id))
            self.assertEqual({"exact": 0, "near": 0, "kept": 1}, dedup.counts())
            self.assertIsNone(dedup.find(" ".join(f"other-{index}" for index in range(128))))
            self.assertEqual({"exact": 0, "near": 0, "kept": 1}, dedup.counts())

    def test_matrix_is_duplicate_source_then_representative_source(self) -> None:
        with DocumentDeduplicator(self.path) as dedup:
            dedup.check("representative-source", "original", "same text")
            dedup.check("duplicate-source", "copy", "same text")
            self.assertEqual(
                {"duplicate-source": {"representative-source": 1}},
                dedup.cross_source_matrix(),
            )

    def test_reopen_uses_persisted_signatures_and_resets_context_counts(self) -> None:
        original, changed = near_duplicate_pair()
        with DocumentDeduplicator(self.path) as dedup:
            dedup.check("source-a", "original", original)
        with DocumentDeduplicator(self.path) as dedup:
            match = dedup.check("source-b", "changed", changed)
            self.assertEqual("near", match.reason)
            self.assertEqual("source-a", match.original_source)
            self.assertEqual("original", match.original_document_id)
            self.assertEqual({"exact": 0, "near": 1, "kept": 0}, dedup.counts())
        self.assertNotIn(original.encode("utf-8"), self.path.read_bytes())


if __name__ == "__main__":
    unittest.main()
