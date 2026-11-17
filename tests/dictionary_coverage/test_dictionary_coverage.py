#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import io
import json
import subprocess
import sys
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "dictionary_coverage.py"
FIXTURES = Path(__file__).resolve().parent / "fixtures"
SPEC = importlib.util.spec_from_file_location("dictionary_coverage", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
dictionary_coverage = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = dictionary_coverage
SPEC.loader.exec_module(dictionary_coverage)


class DictionaryCoverageTest(unittest.TestCase):
    def test_multiple_sources_merge_normalize_filter_and_ties(self) -> None:
        frequencies: Counter[str] = Counter()
        stats = []
        for fixture in ("corpus_a-words.txt", "corpus_b-words.txt"):
            path = FIXTURES / fixture
            with path.open(encoding="utf-8") as stream:
                stats.append(
                    dictionary_coverage.read_source(
                        stream, fixture, frequencies, skip_malformed=False
                    )
                )

        report, entries = dictionary_coverage.build_report(frequencies, stats, (1, 3, 20))

        self.assertEqual(frequencies["сәлам"], 13)
        self.assertEqual(frequencies["йорт"], 10)  # NFC merges и + combining breve.
        self.assertNotIn("latin", frequencies)
        self.assertNotIn("бер-ике", frequencies)
        self.assertEqual(
            entries,
            [
                ("сәлам", 13),
                ("йорт", 10),
                ("өй", 5),
                ("агач", 4),
                ("алма", 4),
                ("юл", 4),
                ("үсә", 2),
                ("ә", 2),
            ],
        )
        self.assertEqual(report["totals"]["duplicates_merged"], 2)
        self.assertEqual(report["totals"]["accepted_tokens"], 44)
        self.assertEqual(report["cutoffs"][1]["covered_tokens"], 28)
        self.assertEqual(report["cutoffs"][2]["selected_words"], 8)
        self.assertGreater(report["cutoffs"][2]["serialized_tsv_bytes"], 0)
        self.assertGreater(report["cutoffs"][2]["gzip_tsv_bytes"], 0)
        self.assertGreater(report["cutoffs"][2]["packed_nul_u32_bytes"], 0)
        self.assertEqual(
            report["cutoffs"][2]["packed_nul_u32_plus_offsets_bytes"],
            report["cutoffs"][2]["packed_nul_u32_bytes"] + 4 * 8,
        )

    def test_malformed_row_fails_closed_by_default(self) -> None:
        stream = io.StringIO("1\tсәлам\t2\n2\tхата\tNaN\n")
        with self.assertRaisesRegex(
            dictionary_coverage.MalformedRowError, r"fixture:2: frequency"
        ):
            dictionary_coverage.read_source(
                stream, "fixture", Counter(), skip_malformed=False
            )

    def test_skip_malformed_counts_bad_rows(self) -> None:
        with (FIXTURES / "malformed-words.txt").open(encoding="utf-8") as stream:
            frequencies: Counter[str] = Counter()
            stats = dictionary_coverage.read_source(
                stream, "malformed", frequencies, skip_malformed=True
            )
        self.assertEqual(frequencies, Counter({"дөрес": 3}))
        self.assertEqual(stats.rows_malformed, 2)

    def test_cli_emits_utf8_json_and_default_cutoffs(self) -> None:
        result = subprocess.run(
            [sys.executable, str(SCRIPT), str(FIXTURES / "corpus_a-words.txt")],
            check=True,
            capture_output=True,
            text=True,
        )
        report = json.loads(result.stdout)
        self.assertEqual(
            [cutoff["requested_rank"] for cutoff in report["cutoffs"]],
            [100_000, 150_000, 250_000],
        )
        self.assertEqual(report["totals"]["unique_words"], 5)
        self.assertIn('"ә"', result.stdout)

    def test_cli_rejects_malformed_input(self) -> None:
        result = subprocess.run(
            [sys.executable, str(SCRIPT), str(FIXTURES / "malformed-words.txt")],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("malformed-words.txt:2", result.stderr)

    def test_cli_rejects_input_without_usable_tatar_words(self) -> None:
        result = subprocess.run(
            [sys.executable, str(SCRIPT), str(FIXTURES / "filtered-only-words.txt")],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("no usable Tatar words", result.stderr)


class InternalHyphenGrammarTest(unittest.TestCase):
    """The compound grammar is opt-in; the default corpus rule stays letter-only."""

    def test_internal_hyphen_is_accepted_only_with_the_flag(self) -> None:
        word, reason = dictionary_coverage.normalize_word(
            "Азык-төлек", allow_internal_hyphen=True
        )
        self.assertEqual("азык-төлек", word)
        self.assertIsNone(reason)
        word, reason = dictionary_coverage.normalize_word("Азык-төлек")
        self.assertIsNone(word)
        self.assertEqual("outside_tatar_alphabet", reason)

    def test_hyphens_outside_two_letters_are_rejected(self) -> None:
        for word in ("-азык", "азык-", "азык--төлек", "азык–төлек", "азык—төлек",
                     "азык‐төлек", "азык'төлек", "азык’төлек", "а-", "2-3"):
            normalized, reason = dictionary_coverage.normalize_word(
                word, allow_internal_hyphen=True
            )
            self.assertIsNone(normalized, msg=word)
            self.assertIsNotNone(reason, msg=word)

    def test_multiple_internal_hyphens_are_allowed(self) -> None:
        word, reason = dictionary_coverage.normalize_word(
            "аз-ык-тө-лек", allow_internal_hyphen=True
        )
        self.assertEqual("аз-ык-тө-лек", word)
        self.assertIsNone(reason)


if __name__ == "__main__":
    unittest.main()
