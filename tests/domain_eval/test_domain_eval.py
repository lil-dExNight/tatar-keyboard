#!/usr/bin/env python3
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import domain_eval  # noqa: E402
import evaluation_metrics as metrics  # noqa: E402


def series(hits: list[int], denominators: list[int] | None = None) -> dict[str, object]:
    return {
        "hits": hits,
        "denominators": denominators if denominators is not None else [1] * len(hits),
    }


def domain_report(
    identifier: str,
    coverage: list[int],
    completion: list[int],
    next_word: list[int],
    digest: str = "a" * 64,
) -> dict[str, object]:
    return {
        "domains": [
            {
                "id": identifier,
                "input_sha256": digest,
                "series": {
                    "coverage": series(coverage),
                    "completion_cp3": series(completion),
                    "next_word": series(next_word),
                },
            }
        ]
    }


class CanonicalizationTest(unittest.TestCase):
    def test_native_placeholders_split_segments_and_deduplicate(self) -> None:
        raw = [
            "бер ике өч <URL> дүрт биш алты",
            "бер ике өч <NAME> дүрт биш алты",
            "бер ике <NUM> өч дүрт биш",
            "бер ике өч",
        ]
        self.assertEqual(
            ["бер ике өч", "дүрт биш алты", "өч дүрт биш"],
            domain_eval.canonicalize(raw, native=True),
        )

    def test_hash_prefixed_subtitle_is_data_not_a_comment(self) -> None:
        raw = ["#бер ике өч"]
        self.assertEqual(["бер ике өч"], domain_eval.canonicalize(raw, native=False))
        self.assertEqual(
            [], domain_eval.canonicalize(raw, native=False, skip_comments=True)
        )

    def test_prepare_subtitles_samples_before_canonicalization(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "subtitles.txt"
            source.write_text(
                "бер ике өч\n" + "кыска\n" * 9 + "дүрт биш алты\n",
                encoding="utf-8",
            )
            spec = domain_eval.DomainSpec("subtitles", "Subtitles", source, subtitles=True)
            domains, _exclusion = domain_eval.prepare_domains([spec], root / "out")
            self.assertEqual(["бер ике өч", "дүрт биш алты"], domains[0].lines)

    def test_headline_exclusion_is_reproducible(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subtitle_path = root / "subtitles.txt"
            subtitle_path.write_text(
                "ул Локк белән сөйләште\n" * 21,
                encoding="utf-8",
            )
            other_path = root / "other.txt"
            other_path.write_text("бер ике өч\n", encoding="utf-8")
            specs = [
                domain_eval.DomainSpec(
                    "subtitles", "Subtitles", subtitle_path, subtitles=True
                ),
                domain_eval.DomainSpec("tatoeba", "Tatoeba", other_path),
            ]
            domains, exclusion = domain_eval.prepare_domains(specs, root / "out")
            self.assertIn("локк", exclusion["words"])
            self.assertNotIn("сөйләште", exclusion["words"])
            self.assertTrue(domain_eval.is_foreign_name_form("сойерның"))
            self.assertFalse(domain_eval.is_foreign_name_form("өчен"))
            subtitle = domains[0]
            adjusted = domain_eval.adjusted_headline_series(
                subtitle,
                series([4], [4]),
                frozenset({"ул", "локк", "белән", "сөйләште"}),
                frozenset(exclusion["words"]),
            )
            self.assertEqual([3], adjusted["hits"])
            self.assertEqual([3], adjusted["denominators"])


class DecisionRuleTest(unittest.TestCase):
    def test_absent_native_uses_secondary_mde_and_says_so(self) -> None:
        baseline = domain_report("common_voice", ([1, 0] * 50), ([1, 0] * 50), ([1, 0] * 50))
        candidate = domain_report("common_voice", [1] * 49 + [0] * 51, ([1, 0] * 50), ([1, 0] * 50))
        decision = metrics.decide_domain_candidate(baseline, candidate, rounds=100)
        self.assertTrue(decision["acceptable"])
        self.assertEqual("absent", decision["native_chat"])
        self.assertTrue(decision["secondary_domain_fallback"])
        self.assertIn("native chat absent", decision["message"])

    def test_secondary_loss_beyond_mde_is_rejected(self) -> None:
        baseline = domain_report("common_voice", ([1, 0] * 50), ([1, 0] * 50), ([1, 0] * 50))
        candidate = domain_report("common_voice", [1] * 30 + [0] * 70, ([1, 0] * 50), ([1, 0] * 50))
        decision = metrics.decide_domain_candidate(baseline, candidate, rounds=100)
        self.assertFalse(decision["acceptable"])
        self.assertEqual(["common_voice:coverage"], decision["failures"])
        self.assertIn("native chat absent", decision["message"])

    def test_native_negative_point_delta_is_rejected(self) -> None:
        baseline = domain_report("native_chat", [1] + [0] * 99, ([1, 0] * 50), ([1, 0] * 50))
        candidate = domain_report("native_chat", [0] * 100, ([1, 0] * 50), ([1, 0] * 50))
        decision = metrics.decide_domain_candidate(baseline, candidate, rounds=100)
        self.assertFalse(decision["acceptable"])
        self.assertIn("native_chat:coverage", decision["failures"])

    def test_native_paired_ci_must_not_cross_below_zero(self) -> None:
        report = domain_report("native_chat", ([1, 0] * 50), ([1, 0] * 50), ([1, 0] * 50))
        with mock.patch.object(metrics, "paired_delta_ci95", return_value=(-0.1, 0.1)):
            decision = metrics.decide_domain_candidate(report, report, rounds=100)
        self.assertFalse(decision["acceptable"])
        self.assertEqual(3, len(decision["failures"]))

    def test_coverage_only_decision_supports_dictionary_acceptance(self) -> None:
        report = domain_report("common_voice", ([1, 0] * 50), ([1, 0] * 50), ([1, 0] * 50))
        decision = metrics.decide_domain_candidate(
            report, report, rounds=100, metrics=("coverage",)
        )
        self.assertTrue(decision["acceptable"])
        self.assertEqual(["coverage"], decision["metrics"])
        self.assertEqual(["coverage"], list(decision["domains"]["common_voice"]))

    def test_different_evaluation_content_is_not_paired(self) -> None:
        baseline = domain_report("common_voice", [1], [1], [1], digest="a" * 64)
        candidate = domain_report("common_voice", [1], [1], [1], digest="b" * 64)
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "different evaluation inputs"):
            metrics.decide_domain_candidate(baseline, candidate, rounds=100)


class SeriesOutputTest(unittest.TestCase):
    def test_completion_series_matches_aggregate_hits(self) -> None:
        words = {"аб", "абд", "абе", "б"}
        mirror = mock.Mock()
        mirror.prefix_top3.side_effect = lambda prefix: [prefix] if prefix == "абд" else []
        output = {}
        rows = domain_eval.suggest_eval._completion_metrics(words, mirror, output)
        table = dict(rows)
        self.assertEqual(1, int(table["prefix_cp3_visible_top3_hits"]))
        self.assertEqual([1, 0], output["completion_cp3"]["hits"])
        self.assertEqual([1, 1], output["completion_cp3"]["denominators"])


class RegistryTest(unittest.TestCase):
    def test_native_chat_is_first(self) -> None:
        self.assertEqual("native_chat", domain_eval.DOMAIN_ORDER[0][0])


if __name__ == "__main__":
    unittest.main()
