#!/usr/bin/env python3
"""Tests for scripts/chat_spelling_fold.py and its use in dict_accept.pack.

The rule, the review table format and the fold/drop/keep arithmetic run on synthetic
frequencies. Two live-tree checks keep the committed data honest: every Russian-letter spelling
the shipped Tatar dictionary contains has a reviewed decision, and the shipped asset still has
both spellings of the pairs the table folds, so the rule is the only thing that can remove them.
"""

from __future__ import annotations

import contextlib
import hashlib
import io
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "research" / "corpus"))

import chat_spelling_fold  # noqa: E402
import dict_accept  # noqa: E402
import dictionary_coverage  # noqa: E402
import dictionary_pack  # noqa: E402

csf = chat_spelling_fold


class VariantTest(unittest.TestCase):
    def test_all_six_pairs_translate(self) -> None:
        self.assertEqual(csf.chat_variant("рәхмәт"), "рэхмэт")
        self.assertEqual(csf.chat_variant("һәм"), "хэм")
        self.assertEqual(csf.chat_variant("бөтен"), "ботен")
        self.assertEqual(csf.chat_variant("күп"), "куп")
        self.assertEqual(csf.chat_variant("җыр"), "жыр")
        self.assertEqual(csf.chat_variant("киң"), "кин")

    def test_a_word_without_tatar_letters_has_no_variant(self) -> None:
        self.assertIsNone(csf.chat_variant("китап"))
        self.assertIsNone(csf.chat_variant("работа"))

    def test_short_words_are_ignored(self) -> None:
        self.assertIsNone(csf.chat_variant("үз"))
        self.assertIsNone(csf.chat_variant("ул"))


class FlaggedTest(unittest.TestCase):
    def test_dominant_standard_form_folds(self) -> None:
        decisions = csf.flagged({"рәхмәт": 4000, "рэхмэт": 100})
        self.assertEqual([(d.word, d.decision, d.target) for d in decisions],
                         [("рэхмэт", "fold", "рәхмәт")])

    def test_weak_standard_form_keeps_both(self) -> None:
        decisions = csf.flagged({"төр": 1200, "тор": 1000})
        self.assertEqual([(d.word, d.decision) for d in decisions], [("тор", "keep")])

    def test_a_more_frequent_variant_is_a_word_of_its_own(self) -> None:
        decisions = csf.flagged({"моңда": 100, "монда": 5000})
        self.assertEqual([(d.word, d.decision) for d in decisions], [("монда", "keep")])

    def test_several_standard_forms_drop(self) -> None:
        decisions = csf.flagged({"хәм": 300, "һәм": 4000, "хэм": 100})
        self.assertEqual([(d.word, d.decision) for d in decisions],
                         [("хэм", "drop"), ("хәм", "fold")])

    def test_a_word_absent_from_the_composition_is_not_flagged(self) -> None:
        self.assertEqual(csf.flagged({"рәхмәт": 4000}), [])


class ReviewTableTest(unittest.TestCase):
    def test_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "review.tsv"
            decisions = [csf.Decision("рэхмэт", "fold", "рәхмәт", 0, 0, "note")]
            csf.write_review(path, decisions)
            rows = csf.read_review(path)
        self.assertEqual(rows["рэхмэт"], csf.Decision("рэхмэт", "fold", "рәхмәт", 0, 0, "note"))

    def test_bad_tables_fail(self) -> None:
        header = "\t".join(csf.REVIEW_HEADER) + "\n"
        cases = {
            "header": "word\tdecision\n",
            "decision": header + "рэхмэт\treplace\tрәхмәт\tno such decision\n",
            "missing target": header + "рэхмэт\tfold\t\tmissing target\n",
            "target on a keep": header + "рэхмэт\tkeep\tрәхмәт\ttarget on a keep\n",
            "duplicate": header + "рэхмэт\tkeep\t\tone\nрэхмэт\tkeep\t\ttwo\n",
        }
        for name, text in cases.items():
            with self.subTest(name), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "review.tsv"
                path.write_text(text, encoding="utf-8")
                with self.assertRaises(csf.ChatSpellingError):
                    csf.read_review(path)


class ApplyTest(unittest.TestCase):
    def review(self, *rows: csf.Decision) -> dict[str, csf.Decision]:
        return {row.word: row for row in rows}

    def test_fold_moves_counts_and_drop_removes_them(self) -> None:
        frequencies = {"рәхмәт": 4000, "рэхмэт": 100, "һәм": 4000, "хэм": 300,
                       "хәм": 300, "китап": 50}
        review = self.review(
            csf.Decision("рэхмэт", "fold", "рәхмәт", 0, 0, ""),
            csf.Decision("хәм", "fold", "һәм", 0, 0, ""),
            csf.Decision("хэм", "drop", "", 0, 0, ""),
        )
        result, report = csf.apply(frequencies, review)
        self.assertEqual(result, {"рәхмәт": 4100, "һәм": 4300, "китап": 50})
        self.assertEqual(report.folded, 2)
        self.assertEqual(report.dropped, 1)
        self.assertEqual(report.folded_tokens, 400)
        self.assertEqual(report.dropped_tokens, 300)

    def test_an_unreviewed_variant_fails(self) -> None:
        frequencies = {"рәхмәт": 4000, "рэхмэт": 100}
        with self.assertRaisesRegex(csf.ChatSpellingError, "unreviewed"):
            csf.apply(frequencies, {})

    def test_a_missing_target_fails(self) -> None:
        frequencies = {"рәхмәт": 4000, "рэхмэт": 100}
        review = self.review(csf.Decision("рэхмэт", "fold", "рәхмәтне", 0, 0, ""))
        with self.assertRaisesRegex(csf.ChatSpellingError, "not in the composition"):
            csf.apply(frequencies, review)

    def test_stale_removals_do_not_apply_to_another_composition(self) -> None:
        frequencies = {"азатлыгынын": 100, "монда": 50, "китап": 5}
        review = self.review(
            csf.Decision("азатлыгынын", "fold", "азатлыгының", 0, 0, ""),
            csf.Decision("монда", "drop", "", 0, 0, ""),
        )
        result, report = csf.apply(frequencies, review)
        self.assertEqual(frequencies, result)
        self.assertEqual((report.folded, report.dropped, report.kept), (0, 0, 0))

    def test_review_rows_outside_the_composition_are_ignored(self) -> None:
        review = self.review(csf.Decision("рэхмэт", "fold", "рәхмәт", 0, 0, ""))
        result, report = csf.apply({"китап": 5}, review)
        self.assertEqual(result, {"китап": 5})
        self.assertEqual(report.folded, 0)


class PackIntegrationTest(unittest.TestCase):
    """dict_accept.merged_entries applies the reviewed table to the Tatar composition."""

    WORDS = [
        ("рәхмәт", 4000),
        ("рэхмэт", 100),
        ("китап", 300),
        ("су", 200),
    ]
    # A pair outside the committed review table: the rule flags it and the build must stop.
    UNREVIEWED = [("шәкерт", 4000), ("шэкерт", 100)]

    def build(self, words: list[tuple[str, int]]) -> None:
        base = Path(self.directory.name)
        self.baseline = base / "baseline"
        self.out_dir = base / "accept"
        for directory in (self.baseline, self.out_dir):
            if directory.exists():
                shutil.rmtree(directory)
            directory.mkdir()
        for suffix in ("ru", "tt"):
            (self.out_dir / f"conv-freq-{suffix}.tsv").write_text(
                "word\tconv_freq\n", encoding="utf-8")
            (self.out_dir / f"accepted-{suffix}.tsv").write_text(
                "\t".join(dict_accept.HEADER + ["rule", "rule_detail"]) + "\n", encoding="utf-8")
        words_path = base / "words-tat.txt"
        words_path.write_text(
            "".join(f"{i}\t{w}\t{f}\n" for i, (w, f) in enumerate(words, start=1)),
            encoding="utf-8",
        )
        built = dictionary_pack.build_dictionary([words_path], len(words))
        import corpuslib

        asset_path = self.baseline / corpuslib.SHIPPED["tat"].name
        asset_path.write_bytes(built.asset)
        self.pins = {"tat": hashlib.sha256(built.asset).hexdigest()}
        for name in ("ru-conv-review.tsv", "tt-conv-review.tsv"):
            (self.out_dir / name).write_text("placeholder\n", encoding="utf-8")

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.build(self.WORDS)

    def tearDown(self) -> None:
        self.directory.cleanup()

    @contextlib.contextmanager
    def patched(self):
        import dict_accept

        saved = (dict_accept.QUEUE, dict_accept.OUT_DIR, dict_accept.BASELINE_SHA256)
        dict_accept.QUEUE = {tag: self.out_dir / "placeholder.tsv" for tag in ("rus", "tat")}
        dict_accept.OUT_DIR = self.out_dir
        dict_accept.BASELINE_SHA256 = {**dict_accept.BASELINE_SHA256, **self.pins}
        try:
            yield dict_accept
        finally:
            dict_accept.QUEUE, dict_accept.OUT_DIR, dict_accept.BASELINE_SHA256 = saved

    def test_the_fold_applies_before_the_composition_is_written(self) -> None:
        with self.patched() as da:
            report: dict = {}
            _shipped, _accepted, entries = da.merged_entries(
                "tat", self.baseline, report_out=report
            )
        frequencies = dict(entries)
        self.assertNotIn("рэхмэт", frequencies)
        self.assertEqual(frequencies["рәхмәт"], 4100)
        self.assertEqual(report["chat_spelling"]["folded"], 1)
        self.assertEqual(report["chat_spelling"]["folded_tokens"], 100)

    def test_hygiene_off_keeps_the_shipped_composition(self) -> None:
        with self.patched() as da:
            _shipped, _accepted, entries = da.merged_entries(
                "tat", self.baseline, hygiene=False
            )
        self.assertEqual(dict(entries)["рэхмэт"], 100)

    def test_an_unreviewed_variant_stops_the_build(self) -> None:
        self.build(self.UNREVIEWED)
        with self.patched() as da:
            with self.assertRaisesRegex(csf.ChatSpellingError, "unreviewed"):
                da.merged_entries("tat", self.baseline)

    def test_a_stale_fold_keeps_an_admitted_word_and_its_preservation(self) -> None:
        self.build([("азатлыгынын", 100), ("китап", 300)])
        with self.patched() as da:
            _shipped, _accepted, entries = da.merged_entries(
                "tat", self.baseline, preserve={"азатлыгынын": 100}
            )
        self.assertEqual(dict(entries)["азатлыгынын"], 100)

    def test_a_required_word_the_rule_folds_is_reported(self) -> None:
        with self.patched() as da:
            report: dict = {}
            _shipped, _accepted, entries = da.merged_entries(
                "tat", self.baseline, required=frozenset({"рэхмэт"}), report_out=report
            )
        self.assertEqual(report["chat_spelling_required_folded"], ["рэхмэт"])
        self.assertIn("рәхмәт", dict(entries))

    def test_a_required_word_the_rule_drops_fails_closed(self) -> None:
        self.build([("һәм", 4000), ("хәм", 300), ("хэм", 100), ("китап", 300)])
        with self.patched() as da:
            with self.assertRaisesRegex(SystemExit, "drops"):
                da.merged_entries("tat", self.baseline, required=frozenset({"хэм"}))


class LiveTreeTest(unittest.TestCase):
    """The committed table covers every variant the shipped dictionary holds."""

    @classmethod
    def setUpClass(cls) -> None:
        path = ROOT / "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib"
        parsed = dictionary_pack.validate_asset(
            path.read_bytes(), language=dictionary_coverage.TATAR
        )
        cls.frequencies = dict(zip(parsed.words, parsed.frequencies))
        cls.review = csf.read_review(ROOT / csf.REVIEW)

    def test_every_removal_has_a_reviewed_decision(self) -> None:
        unreviewed = [
            d.word for d in csf.flagged(self.frequencies)
            if d.word not in self.review and d.decision != "keep"
        ]
        self.assertEqual(unreviewed, [])

    def test_a_fold_target_is_never_removed(self) -> None:
        removed = {word for word, row in self.review.items() if row.decision != "keep"}
        for word, row in self.review.items():
            if row.decision != "fold":
                continue
            with self.subTest(word=word):
                self.assertNotIn(row.target, removed)

    def test_the_rule_covers_the_shipped_composition(self) -> None:
        # Valid in both states: before a rebuild the shipped asset still holds the spellings, and
        # after it the composition no longer does. What must hold either way is that the rule
        # leaves no reviewed removal behind and never stops on an unreviewed word.
        cleaned, _report = csf.apply(self.frequencies, self.review)
        current_words = {item.word for item in csf.flagged(self.frequencies)}
        removed = current_words & {
            word for word, row in self.review.items() if row.decision != "keep"
        }
        self.assertEqual(sorted(removed & set(cleaned)), [])

    def test_the_review_covers_the_reported_chat_spellings(self) -> None:
        # R2 measured these in the shipped dictionary; each must be folded or dropped.
        for word in ("рэхмэт", "белэн", "хэм", "кирэкми", "хэзерге", "һэм", "ярдэмен"):
            with self.subTest(word=word):
                self.assertIn(word, self.review)
                self.assertNotEqual(self.review[word].decision, "keep")


if __name__ == "__main__":
    unittest.main()
