#!/usr/bin/env python3
"""Tests for the training-only proper-name review generator."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research" / "corpus"))

import make_proper_name_review as review  # noqa: E402


def make_source(root: Path, manifest_key: str, text: str) -> review.Source:
    path = root / manifest_key
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    definition = review.SOURCE_DEFINITIONS[manifest_key]
    return review.Source(
        path=path, manifest_key=manifest_key, name=definition["name"],
        sha256="", size=path.stat().st_size, manifest_source="fixture",
        revision=definition["revision"], license=definition["license"],
        risk=definition["risk"],
    )


def synthetic_evidence(
    total: int, noninitial: int, capitalized: int, display_count: int, sources: int,
) -> review.Evidence:
    return review.Evidence(
        total=total, noninitial=noninitial, capitalized=capitalized,
        capitalized_sources=sources, displays=Counter({"Алмаз": display_count}),
    )


class ProperNameEvidenceTest(unittest.TestCase):
    def test_case_floor_display_and_exclusions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = make_source(
                Path(directory), "tt_conv_train90-sentences.txt",
                "Алмаз китап\n"
                "китап Алмаз Алмаз.\n"
                "АЛМАЗ Алмаз алмаз\n"
                "китап Алмаз\n"
                "китап БӘЛКИ БӘЛКИ БӘЛКИ\n"
                "китап МаРия МаРия МаРия\n"
                "китап Дәрес ДӘРЕС ДӘРЕС\n"
                "китап Гади Гади гади\n"
                "китап Мәр’ям Мәр’ям Мәр’ям\n"
                "китап Maria Maria Maria\n"
                "китап Әхмәт-улы Әхмәт-улы Әхмәт-улы\n",
            )
            evidence, token_counts = review.observe_sources([source])
            item = evidence["алмаз"]
            self.assertEqual(item.total, 7)
            self.assertEqual(item.noninitial, 5)
            self.assertEqual(item.capitalized, 4)
            self.assertEqual(item.displays, Counter({"Алмаз": 4}))
            self.assertIsNone(review.rejection_reason(item))
            self.assertEqual(review.rejection_reason(evidence["бәлки"]), "all_caps_only")
            self.assertEqual(review.rejection_reason(evidence["мария"]), "no_titlecase_display")
            self.assertEqual(review.rejection_reason(evidence["дәрес"]), "below_display_evidence")
            self.assertEqual(
                review.rejection_reason(evidence["гади"]), "below_capitalization_ratio"
            )
            rejected = token_counts[0]["rejected_tokens"]
            self.assertEqual(rejected["hyphen_or_apostrophe"], 6)
            self.assertEqual(rejected["latin"], 3)
            self.assertNotIn("мәр’ям", evidence)
            self.assertNotIn("әхмәт-улы", evidence)

            candidates, qualifying, rejected_types = review.select_candidates(evidence, 10)
            self.assertEqual(qualifying, 1)
            self.assertEqual([candidate.normalized for candidate in candidates], ["алмаз"])
            review.attach_source_counts([source], candidates)
            counts = candidates[0].source_counts[0]
            self.assertEqual(
                (counts.total, counts.noninitial, counts.capitalized, counts.display, counts.lines),
                (7, 5, 4, 4, 4),
            )
            rows = review.build_rows(candidates, [source], {"алмаз": 42})
            row = rows[0]
            self.assertEqual(row["observed_display_form"], "Алмаз")
            self.assertEqual(row["uncapitalized_noninitial"], 1)
            self.assertEqual(row["cap_ratio"], "0.800000")
            self.assertEqual(row["license_risk"], "risk")
            self.assertEqual(row["suggested_handling"], "review-homograph")
            self.assertIn("already-shipped-lowercase", row["risk_flags"])
            self.assertTrue(all(not row[name] for name in review.NATIVE_REVIEW_FIELDS))
            table = review.render_tsv(rows, [source], 10, qualifying)
            self.assertNotIn("китап Алмаз", table)
            self.assertIn("below_display_evidence", rejected_types)

    def test_multi_source_rank_precedes_display_frequency(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sources = [
                make_source(
                    root, "tt_conv_train90-sentences.txt", "китап Алмаз Алмаз Алмаз\n"
                ),
                make_source(
                    root, "tat_mixed_2015_1M-sentences.txt",
                    "китап Алмаз Алмаз Алмаз Алмаз\n",
                ),
                make_source(
                    root, "tat_web_2018_1M-sentences.txt",
                    "китап " + " ".join(["Бакы"] * 10) + "\n",
                ),
            ]
            evidence, _ = review.observe_sources(sources)
            candidates, qualifying, _ = review.select_candidates(evidence, 10)
            self.assertEqual(qualifying, 2)
            self.assertEqual(
                [candidate.normalized for candidate in candidates], ["алмаз", "бакы"]
            )
            review.attach_source_counts(sources, candidates)
            rows = review.build_rows(candidates, sources, {})
            self.assertEqual(rows[0]["source_count"], 2)
            self.assertEqual(rows[0]["license_risk"], "mixed")
            self.assertEqual(rows[0]["suggested_handling"], "review-multi-source")
            self.assertEqual(rows[1]["license_risk"], "clean")
            self.assertEqual(rows[1]["suggested_handling"], "review-single-source")
            self.assertTrue(rows[0]["source_evidence"].endswith("tat_web_2018=0/0/0/0/0"))

    def test_display_tie_and_limit_do_not_relax_eligibility(self) -> None:
        displays = Counter({"Әли": 2, "Али": 2})
        self.assertEqual(review.chosen_display(review.Evidence(displays=displays)), "Али")
        evidence = {
            "алмаз": synthetic_evidence(6, 5, 4, 4, 0b11),
            "бакы": synthetic_evidence(10, 10, 10, 10, 0b01),
            "гади": synthetic_evidence(2, 2, 2, 2, 0b01),
        }
        candidates, qualifying, rejected = review.select_candidates(evidence, 1)
        self.assertEqual(qualifying, 2)
        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0].normalized, "алмаз")
        self.assertEqual(rejected["below_noninitial_evidence"], 1)
        self.assertEqual(set(rejected), set(review.REJECTION_REASONS))
        self.assertEqual(sum(rejected.values()), 1)


class LeipzigTokenPositionTest(unittest.TestCase):
    def test_numeric_sentence_id_does_not_make_first_word_noninitial(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = make_source(
                Path(directory), "tt_conv_train90-sentences.txt",
                "1\tАлмаз Алмаз\n"
                "2\tАлмаз китап\n"
                "3\tкитап Алмаз\n"
                "4\tкитап Алмаз\n",
            )
            evidence, _ = review.observe_sources([source])
            item = evidence["алмаз"]
            self.assertEqual(
                (item.total, item.noninitial, item.capitalized, item.displays),
                (5, 3, 3, Counter({"Алмаз": 3})),
            )
            self.assertIsNone(review.rejection_reason(item))
            candidates, _, _ = review.select_candidates(evidence, 1)
            review.attach_source_counts([source], candidates)
            counts = candidates[0].source_counts[0]
            self.assertEqual(
                (counts.total, counts.noninitial, counts.capitalized, counts.display),
                (5, 3, 3, 3),
            )


class SourceSafetyTest(unittest.TestCase):
    def make_manifest(self, root: Path) -> Path:
        manifest = {}
        for name in review.SOURCE_DEFINITIONS:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("китап\n", encoding="utf-8")
            raw = path.read_bytes()
            manifest[name] = {
                "sha256": hashlib.sha256(raw).hexdigest(), "size": len(raw),
                "source": "focused fixture",
            }
        path = root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return path

    def test_manifest_mismatch_writes_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = self.make_manifest(root)
            paths = [root / name for name in review.SOURCE_DEFINITIONS]
            loaded = review.load_sources(manifest_path, paths)
            self.assertEqual([source.name for source in loaded], [
                "tt_conv_train90", "tat_mixed_2015", "tat_web_2018",
            ])
            paths[0].write_text("тамыр\n", encoding="utf-8")
            out = root / "output/names.tsv"
            report = root / "output/report.json"
            argv = [
                "--manifest", str(manifest_path), "--shipped", str(review.DEFAULT_SHIPPED),
                "--out", str(out), "--report", str(report), *map(str, paths),
            ]
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
                review.main(argv)
            self.assertEqual(raised.exception.code, 2)
            self.assertFalse(out.exists())
            self.assertFalse(report.exists())

    def test_forbidden_evaluation_paths(self) -> None:
        for value in (
            "/tmp/tt_conv_heldout10-sentences.txt", "/tmp/tt_conv_all-sentences.txt",
            "/tmp/tt_eval_sentences.txt", "/tmp/tt_common_voice_final.txt",
            "/tmp/development/corpus.txt", "/tmp/common-voice-dev.txt",
            "/tmp/TatSC-test.tsv",
        ):
            with self.subTest(value=value), self.assertRaises(review.ReviewError):
                review.reject_forbidden_path(Path(value))
        review.reject_forbidden_path(Path("/tmp/tt_conv_train90-sentences.txt"))


if __name__ == "__main__":
    unittest.main()
