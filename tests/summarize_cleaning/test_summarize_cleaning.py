#!/usr/bin/env python3
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research/corpus"))
import summarize_cleaning as summary


class SummaryTest(unittest.TestCase):
    def test_filter_groups_keep_reason_tokens_separate(self):
        stats = {"filters": {
            "boilerplate_share": {"token_occurrences": 3},
            "language_russian": {"token_occurrences": 5},
            "language_other": {"token_occurrences": 7},
            "machine_translation": {"token_occurrences": 11},
            "unknown": {"token_occurrences": 13},
        }}
        groups = summary.filter_occurrences(stats)
        self.assertEqual((groups["boilerplate"], groups["language"], groups["machine_translation"], groups["other"]), (3, 12, 11, 13))

    def test_chat_occurrences_include_frequency(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "chat.jsonl"
            path.write_text(json.dumps({"text": "хэм", "frequency": 11}) + "\n")
            stats = {"source": "fixture", "chat_spelling_lines": 1, "chat_spelling_tokens": 1, "chat_spelling_token_occurrences": 11, "raw_token_occurrences": 20, "kept_token_occurrences": 9}
            summary.augment_occurrence_stats(stats, path)
            self.assertEqual(stats["chat_spelling_token_occurrence_share"], 0.55)
            self.assertEqual(stats["kept_token_occurrence_share"], 0.45)
            stats["chat_spelling_token_occurrences"] = 10
            with self.assertRaisesRegex(ValueError, "token count"):
                summary.augment_occurrence_stats(stats, path)

    def test_token_conservation_rejects_unaccounted_tokens(self):
        stats = {"source": "fixture", "raw_token_occurrences": 10, "kept_token_occurrences": 3,
                 "chat_spelling_token_occurrences": 2, "duplicate_tokens": {"exact": 1, "near": 1},
                 "filters": {"language_other": {"token_occurrences": 3}}}
        summary.validate_conservation(stats)
        stats["raw_token_occurrences"] = 11
        with self.assertRaisesRegex(ValueError, "conservation"):
            summary.validate_conservation(stats)

    def test_finalize_validates_and_writes_source_tables(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / "stats").mkdir()
            (output / "chat-spelling").mkdir()
            stats = {"source": "fixture", "raw_token_occurrences": 10, "kept_token_occurrences": 5,
                     "chat_spelling_lines": 1, "chat_spelling_tokens": 1,
                     "duplicate_tokens": {"exact": 1, "near": 0},
                     "filters": {"language_other": {"token_occurrences": 2}},
                     "stream": "sentences", "output": {"path": "old"}, "chat_output": {"path": "old-chat"}}
            (output / "stats/fixture.json").write_text(json.dumps(stats))
            (output / "chat-spelling/fixture.jsonl").write_text(json.dumps({"text": "хэм", "frequency": 2}) + "\n")
            (output / "summary.json").write_text(json.dumps({
                "sources": [stats], "cross_source_duplicate_matrix": {},
                "document_duplicates": {"exact": 1, "near": 0, "kept": 1},
            }))
            result = summary.finalize(output)
            self.assertEqual(result["sources"][0]["chat_spelling_token_occurrences"], 2)
            self.assertEqual(result["sources"][0]["output"]["path"], str(output / "sentences/fixture.txt"))
            rendered = (output / "report-tables.md").read_text()
            self.assertIn("| fixture | 10 | 5", rendered)
            self.assertIn("| 20.0000 | 0.0000 |", rendered)

    def test_sparse_matrix_omits_self_pairs(self):
        document = {"sources": [], "cross_source_duplicate_matrix": {"first": {"first": 4}, "second": {"first": 3, "second": 2}}}
        rendered = summary.render_tables(document)
        self.assertIn("| second | first | 3 |", rendered)
        self.assertNotIn("| first | first | 4 |", rendered)
        self.assertNotIn("| second | second | 2 |", rendered)


if __name__ == "__main__":
    unittest.main()
