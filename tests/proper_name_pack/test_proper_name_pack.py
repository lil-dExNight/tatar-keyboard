"""Tests for the reviewed proper-name display asset builder."""
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import dictionary_pack  # noqa: E402
import proper_name_pack  # noqa: E402

DATE = "2026-11-18"


def proper_row(key: str, display: str, approved: str = "yes") -> str:
    return "\t".join(
        [key, display, "10", "9", "1", "0.9", "test=10", approved, "operator", DATE, "note"]
    )


def compound_row(word: str, display: str = "", approved: str = "yes") -> str:
    return "\t".join(
        [word, "10", "coordinated", "test=10", display, approved, "operator", DATE, "note"]
    )


def write_table(path: Path, header, rows) -> None:
    path.write_text(
        "\t".join(header) + "\n" + "".join(row + "\n" for row in rows),
        encoding="utf-8",
    )


class DisplayFormTest(unittest.TestCase):
    def test_accepts_a_simple_name(self):
        self.assertEqual(proper_name_pack.display_form_reason("татарстан", "Татарстан"), "")

    def test_accepts_a_proper_compound(self):
        self.assertEqual(
            proper_name_pack.display_form_reason("санкт-петербург", "Санкт-Петербург"), ""
        )

    def test_rejects_display_that_does_not_fold_to_the_key(self):
        self.assertIn("does not lowercase", proper_name_pack.display_form_reason("татарстан", "Казан"))

    def test_rejects_apostrophe(self):
        self.assertTrue(proper_name_pack.display_form_reason("мас-ала", "Мас’ала"))

    def test_rejects_en_dash(self):
        self.assertTrue(proper_name_pack.display_form_reason("азык-төлек", "Азык–төлек"))

    def test_rejects_noncanonical_key(self):
        self.assertTrue(proper_name_pack.display_form_reason("Татарстан", "Татарстан"))

    def test_rejects_doubled_hyphen(self):
        self.assertTrue(proper_name_pack.display_form_reason("азык--төлек", "Азык--төлек"))


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.names = self.root / "names.tsv"
        self.compounds = self.root / "compounds.tsv"
        write_table(self.names, proper_name_pack.PROPER_NAME_REVIEW_HEADER,
                    [proper_row("казан", "Казан"), proper_row("татарстан", "Татарстан")])
        write_table(self.compounds, proper_name_pack.COMPOUND_REVIEW_HEADER,
                    [compound_row("азык-төлек"), compound_row("санкт-петербург", "Санкт-Петербург")])

    def test_asset_is_sorted_and_carries_both_tables(self):
        data = proper_name_pack.build_asset(self.names, self.compounds)
        forms = proper_name_pack.parse_asset(data, "asset")
        self.assertEqual(
            sorted(forms),
            ["казан", "санкт-петербург", "татарстан"],
        )
        self.assertEqual(forms["санкт-петербург"], "Санкт-Петербург")

    def test_check_detects_a_changed_row(self):
        data = proper_name_pack.build_asset(self.names, self.compounds)
        changed = data.replace("Казан".encode(), "Каазан".encode())
        with self.assertRaises(proper_name_pack.ProperNameError):
            proper_name_pack.parse_asset(changed, "asset")
        self.assertNotEqual(changed, data)

    def test_duplicate_key_is_rejected(self):
        write_table(self.names, proper_name_pack.PROPER_NAME_REVIEW_HEADER,
                    [proper_row("казан", "Казан"), proper_row("казан", "Казан")])
        with self.assertRaises(proper_name_pack.ProperNameError):
            proper_name_pack.build_asset(self.names, self.compounds)

    def test_unapproved_row_is_rejected(self):
        write_table(self.names, proper_name_pack.PROPER_NAME_REVIEW_HEADER,
                    [proper_row("казан", "Казан", approved="no")])
        with self.assertRaises(proper_name_pack.ProperNameError):
            proper_name_pack.build_asset(self.names, self.compounds)

    def test_missing_dictionary_key_is_reported(self):
        data = proper_name_pack.build_asset(self.names, self.compounds)
        raw = dictionary_pack.serialize_entries([("казан", 10)], schema=dictionary_pack.SCHEMA_ID_V2)
        dictionary = self.root / "dictionary.tdict"
        dictionary.write_bytes(dictionary_pack.compress_raw(raw))
        forms = proper_name_pack.parse_asset(data, "asset")
        self.assertEqual(
            proper_name_pack.missing_dictionary_keys(forms, dictionary),
            ["санкт-петербург", "татарстан"],
        )


class CompoundReviewTest(unittest.TestCase):
    def test_requires_review_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "compounds.tsv"
            write_table(path, proper_name_pack.COMPOUND_REVIEW_HEADER,
                        [compound_row("азык-төлек", approved="")])
            with self.assertRaises(proper_name_pack.ProperNameError):
                proper_name_pack.read_compound_review(path)


if __name__ == "__main__":
    unittest.main()
