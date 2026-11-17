#!/usr/bin/env python3
"""Contracts for the compound and apostrophe native-review artifacts."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "research/corpus/make_compound_apostrophe_review.py"
DICTIONARY = ROOT / "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib"


def load_review_module():
    spec = importlib.util.spec_from_file_location("compound_apostrophe_review", SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


review = load_review_module()

LINES = {
    "tt_conv_train90-sentences.txt": [
        "1\tазык-төлек азык-төлек",
        "2\tазык-төлек хатын-кыз",
        "3\tхатын-кыз хатын-кыз",
        "4\tхатын-кызлар хатын-кызлар",
        "5\tхатын-кызлар хатын-кыз",
        "6\tхатын-кыз хатын-кыз",
        "7\tхатын-кыз хатын-кыз",
        "8\tхатын-кыз хатын-кыз",
        "9\tхатын-кыз хатын-кыз",
        "10\tхатын-кыз хатын-кыз",
        "11\tхатын-кыз хатын-кыз",
        "12\tбер-бер бер-бер",
        "13\t2-3 2-3",
        "14\tWi-Fi Wi-Fi",
        "15\tIT-парк IT-парк",
        "16\tђйлђнђ-тирђ ђйлђнђ-тирђ",
        "17\tг-м г-м",
        "18\tmäs'älä mäs’älä",
        "19\tfil'm fil’m",
        "20\tБулмы'й Булмый Булмый",
        "21\tБулмый Булмый Булмый",
        "22\tБулмый Булмый Булмый",
    ],
    "tat_mixed_2015_1M-sentences.txt": [
        "1\tmäs'älä mäs’älä",
        "2\tfil'm fil’m",
        "3\tБулмы'й Булмый",
    ],
    "tat_web_2018_1M-sentences.txt": [
        "1\tmäs'älä mäs’älä",
        "2\tfil'm fil’m",
    ],
}


def write_fixture(root: Path) -> tuple[Path, dict[str, Path]]:
    sources: dict[str, Path] = {}
    manifest: dict[str, dict[str, object]] = {}
    for filename, lines in LINES.items():
        path = root / filename
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        sources[filename] = path
        manifest[filename] = {
            "size": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "source": "test fixture",
        }
    manifest_path = root / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return manifest_path, sources


def source_map(sources: dict[str, Path]) -> dict[str, Path]:
    return {
        name: sources[filename]
        for name, filename in review.SOURCE_FILES.items()
    }


class ReviewGenerationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.manifest, self.sources = write_fixture(self.root)
        self.output = self.root / "out"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_generation_preserves_written_forms_and_review_fields(self) -> None:
        reports = review.generate(
            source_map(self.sources),
            self.manifest,
            DICTIONARY,
            self.output,
        )
        self.assertEqual(reports["compounds"]["selection"]["selected"],
                         len(reports["compounds"]["rows"]))
        self.assertEqual(reports["apostrophes"]["selection"]["selected"],
                         len(reports["apostrophes"]["rows"]))
        self.assertTrue(reports["compounds"]["selection"]["shortfall_reason"])
        self.assertTrue(reports["apostrophes"]["selection"]["shortfall_reason"])

        compounds = review._read_tsv(
            self.output / "tt-compounds.tsv", review.COMPOUND_HEADER
        )
        apostrophes = review._read_tsv(
            self.output / "tt-apostrophes.tsv", review.APOSTROPHE_HEADER
        )
        self.assertTrue(compounds)
        self.assertTrue(apostrophes)
        self.assertEqual(
            {row["category"] for row in compounds},
            {
                "coordinated", "reduplicated", "inflected_whole",
                "single_letter_prefix", "numeric", "latin", "mixed_script", "debris",
            },
        )
        self.assertEqual(
            {row["apostrophe_class"] for row in apostrophes}, {"straight", "curly"}
        )
        for row in compounds + apostrophes:
            for field in review.REVIEW_FIELDS:
                self.assertEqual(row[field], "")
            self.assertEqual(
                int(row["total_occurrences"]),
                sum(int(row[f"{name}_occurrences"]) for name in review.SOURCE_ORDER),
            )

        straight = next(row for row in apostrophes if row["form"] == "mäs'älä")
        curly = next(row for row in apostrophes if row["form"] == "mäs’älä")
        self.assertEqual(straight["apostrophe_removed_key"], curly["apostrophe_removed_key"])
        self.assertIn("mäs’älä", straight["collision_attested_forms"])
        self.assertIn("mäs'älä", curly["collision_attested_forms"])
        self.assertNotEqual(straight["nfc_lower_key"], curly["nfc_lower_key"])

        review.validate_outputs(
            source_map(self.sources), self.manifest, DICTIONARY, self.output
        )

    def test_validation_rejects_tampered_count(self) -> None:
        review.generate(
            source_map(self.sources),
            self.manifest,
            DICTIONARY,
            self.output,
        )
        path = self.output / "tt-compounds.tsv"
        lines = path.read_text(encoding="utf-8").splitlines()
        fields = lines[1].split("\t")
        fields[3] = str(int(fields[3]) + 1)
        lines[1] = "\t".join(fields)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        with self.assertRaises(review.ReviewError):
            review.validate_outputs(
                source_map(self.sources), self.manifest, DICTIONARY, self.output
            )

    def test_manifest_hash_mismatch_fails_before_processing(self) -> None:
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        first = review.SOURCE_FILES["tt_conv_train90"]
        manifest[first]["sha256"] = "0" * 64
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(review.ReviewError):
            review.verify_corpora(source_map(self.sources), self.manifest)

    def test_apostrophe_removal_collision_is_explicit(self) -> None:
        self.assertEqual(
            review._apostrophe_folded_key("mäs’älä"),
            review._apostrophe_folded_key("mäs'älä"),
        )
        self.assertNotEqual(
            review.nfc_lower("mäs’älä"), review.nfc_lower("mäs'älä")
        )
        self.assertEqual(review.classify_apostrophe("mäs’älä")[0], "latin")
        self.assertEqual(review.classify_apostrophe("mäs'älä")[0], "latin")
        self.assertEqual(review.classify_apostrophe("Булмы'й")[0], "cyrillic")


if __name__ == "__main__":
    unittest.main()
