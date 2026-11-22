#!/usr/bin/env python3
"""The integration recommendation must be derived from measured, finite, current evidence."""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import math
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load_module(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


recommend = load_module(
    "recommend_integration", "research/corpus-sources/recommend_integration.py"
)
CANDIDATE = recommend.CANDIDATE
DOMAINS = recommend.DOMAINS
LIMITS = {
    "compressed": recommend.bigram_pack.MAX_COMPRESSED_BYTES,
    "raw": recommend.bigram_pack.MAX_RAW_BYTES,
}
# Placeholder digests: staleness compares recorded and current identities, not real assets.
BIGRAM_SHA = "a" * 64
DICTIONARY_SHA = "b" * 64
OTHER_SHA = "c" * 64
NON_FINITE = (float("nan"), float("inf"), -float("inf"))


def delta_entry(delta: float, denominator: int) -> dict[str, object]:
    return {
        "denominator": denominator,
        "delta_pp": delta,
        "paired_normal_95ci_low_pp": delta - 0.1,
        "paired_normal_95ci_high_pp": delta + 0.1,
    }


def make_comparison(
    bigram_deltas: tuple[float, float, float] = (0.5, 0.25, 0.0),
    dictionary_deltas: tuple[float, float, float] = (0.1, 0.0, 0.2),
    compressed_bytes: int = 182462,
    raw_bytes: int = 241217,
    compressed_limit: int = LIMITS["compressed"],
    raw_limit: int = LIMITS["raw"],
    shipped_bigram_bytes: int = 212223,
    shipped_dictionary_bytes: int = 597694,
    shipped_bigram_sha: str = BIGRAM_SHA,
    shipped_dictionary_sha: str = DICTIONARY_SHA,
    record_hashes: bool = True,
) -> dict[str, object]:
    comparison: dict[str, object] = {
        "candidates": {
            CANDIDATE: {
                "bigram_with_shipped_dictionary": {
                    "compressed_bytes": compressed_bytes,
                    "compressed_limit_bytes": compressed_limit,
                    "raw_bytes": raw_bytes,
                    "raw_limit_bytes": raw_limit,
                    "heads": 12506,
                    "pairs": 80611,
                    "distinct_pairs": 388101,
                    "pair_instances": 748342,
                }
            },
            "common_voice": {"training_lines_before_eval_exclusion": 9315},
        },
        "held_out_evaluations": {},
        "shipped_asset_sizes": {
            "bigram_compressed_bytes": shipped_bigram_bytes,
            "dictionary_compressed_bytes": shipped_dictionary_bytes,
        },
        "official_split_normalized_collisions": {"tatsc": {}, "common_voice": {}},
        "profiles": {
            "tatsc": {
                "duplicate_exact_pct": 65.6479,
                "selected_pronouns_per_1000_tokens": 12.0,
            },
            "opensubtitles_only": {"selected_pronouns_per_1000_tokens": 78.2925},
        },
    }
    if record_hashes:
        comparison["shipped_asset_sha256"] = {
            "bigram": shipped_bigram_sha,
            "dictionary": shipped_dictionary_sha,
        }
    evaluations = {}
    for index, domain in enumerate(DOMAINS):
        candidates = {}
        for name in (CANDIDATE, "common_voice", "tatsc", "tatsc_plus_common_voice"):
            candidates[name] = {
                "bigram_with_shipped_dictionary": {"top3_hitrate_pct": 15.0},
                "bigram_with_shipped_dictionary_minus_shipped": delta_entry(
                    bigram_deltas[index] if name == CANDIDATE else 0.0, 100
                ),
                "dictionary": {
                    "merged_minus_shipped": delta_entry(dictionary_deltas[index], 200)
                },
            }
        evaluations[domain] = {"shipped": {"top3_hitrate_pct": 14.0}, "candidates": candidates}
    comparison["held_out_evaluations"] = evaluations
    return comparison


def current_assets(
    bigram_bytes: int = 212223,
    bigram_sha: str = BIGRAM_SHA,
    dictionary_bytes: int = 597694,
    dictionary_sha: str = DICTIONARY_SHA,
) -> dict[str, dict[str, object]]:
    return {
        "bigram": {"size_bytes": bigram_bytes, "sha256": bigram_sha},
        "dictionary": {"size_bytes": dictionary_bytes, "sha256": dictionary_sha},
    }


def make_run_root(root: Path) -> tuple[Path, Path]:
    """A temp repo whose comparison matches its tiny shipped assets (sizes and digests)."""
    (root / "data/corpus-sources").mkdir(parents=True)
    bigram = root / recommend.SHIPPED_BIGRAM
    bigram.parent.mkdir(parents=True)
    bigram.write_bytes(b"bigram")
    dictionary = root / recommend.SHIPPED_DICTIONARY
    dictionary.parent.mkdir(parents=True)
    dictionary.write_bytes(b"dictionary")
    comparison = make_comparison(
        shipped_bigram_bytes=bigram.stat().st_size,
        shipped_dictionary_bytes=dictionary.stat().st_size,
        shipped_bigram_sha=hashlib.sha256(bigram.read_bytes()).hexdigest(),
        shipped_dictionary_sha=hashlib.sha256(dictionary.read_bytes()).hexdigest(),
    )
    (root / "data/corpus-sources/comparison.json").write_text(
        json.dumps(comparison), encoding="utf-8"
    )
    return bigram, dictionary


def domain_result(comparison: dict, domain: str) -> dict:
    return comparison["held_out_evaluations"][domain]["candidates"][CANDIDATE]


class DerivedDecisionTest(unittest.TestCase):
    def test_accepting_measurements_integrate_bigram_and_dictionary(self) -> None:
        report = recommend.build_report(make_comparison())
        self.assertEqual(report["decision"], "integrate_bigram_only")
        self.assertEqual(report["defer_reasons"], [])
        self.assertEqual(report["dictionary_decision"], "integrate")
        self.assertIn("no worse", report["dictionary_reason"])
        for domain in DOMAINS:
            self.assertIn(domain, report["dictionary_reason"])
        self.assertEqual(report["shipped_packed_bigram_bytes"], 212223)

    def test_rejecting_measurements_defer_and_name_the_regressing_domains(self) -> None:
        comparison = make_comparison(
            bigram_deltas=(0.402846, -0.529529, 0.050095),
            dictionary_deltas=(-0.716151, -0.323636, -0.237424),
        )
        report = recommend.build_report(comparison)
        self.assertEqual(report["decision"], "defer")
        self.assertEqual(report["dictionary_decision"], "do_not_integrate")
        for domain in DOMAINS:
            self.assertIn(domain, report["dictionary_reason"])
        self.assertIn("-0.323636", report["dictionary_reason"])
        self.assertIn("Keep dictionary contents", report["dictionary_reason"])
        measurement_reasons = [
            reason for reason in report["defer_reasons"] if "negative visible top-3" in reason
        ]
        self.assertEqual(len(measurement_reasons), 1)
        self.assertIn("common_voice_test", measurement_reasons[0])
        self.assertIn("-0.529529", measurement_reasons[0])
        self.assertNotIn("current_native_held", measurement_reasons[0])

    def test_dictionary_rejection_is_independent_of_bigram_acceptance(self) -> None:
        report = recommend.build_report(
            make_comparison(dictionary_deltas=(0.1, -0.05, 0.2))
        )
        self.assertEqual(report["decision"], "integrate_bigram_only")
        self.assertEqual(report["defer_reasons"], [])
        self.assertEqual(report["dictionary_decision"], "do_not_integrate")
        self.assertIn("common_voice_test", report["dictionary_reason"])
        self.assertNotIn("current_native_held", report["dictionary_reason"])
        self.assertNotIn("tatsc_test", report["dictionary_reason"])

    def test_size_limit_failure_defers_even_with_non_regressing_measurements(self) -> None:
        report = recommend.build_report(
            make_comparison(compressed_bytes=LIMITS["compressed"] + 1)
        )
        self.assertEqual(report["decision"], "defer")
        size_reasons = [r for r in report["defer_reasons"] if "size limits" in r]
        self.assertEqual(len(size_reasons), 1)
        self.assertIn(str(LIMITS["compressed"] + 1), size_reasons[0])
        self.assertEqual(report["dictionary_decision"], "integrate")

    def test_verdicts_agree_with_metrics_across_combinations(self) -> None:
        combinations = (
            ((0.5, 0.25, 0.0), (0.1, 0.0, 0.2)),
            ((0.5, -0.25, 0.0), (0.1, 0.0, 0.2)),
            ((0.5, 0.25, 0.0), (0.1, -0.1, 0.2)),
            ((-0.5, -0.25, -0.1), (-0.1, -0.2, -0.3)),
        )
        for bigram_deltas, dictionary_deltas in combinations:
            with self.subTest(bigram=bigram_deltas, dictionary=dictionary_deltas):
                report = recommend.build_report(
                    make_comparison(bigram_deltas, dictionary_deltas)
                )
                expect_defer = any(delta < 0 for delta in bigram_deltas)
                self.assertEqual(report["decision"] == "defer", expect_defer)
                self.assertEqual(bool(report["defer_reasons"]), expect_defer)
                expect_dictionary_reject = any(delta < 0 for delta in dictionary_deltas)
                self.assertEqual(
                    report["dictionary_decision"] == "do_not_integrate",
                    expect_dictionary_reject,
                )


class NonFiniteEvidenceTest(unittest.TestCase):
    """NaN or infinite decision input must abort instead of reading as non-regression."""

    def reject(self, comparison: dict) -> None:
        with self.assertRaises(recommend.RecommendationError):
            recommend.build_report(comparison)

    def test_non_finite_bigram_and_dictionary_deltas_are_rejected(self) -> None:
        for value in NON_FINITE:
            for field, path in (
                ("bigram", ("bigram_with_shipped_dictionary_minus_shipped", "delta_pp")),
                ("dictionary", ("dictionary", "merged_minus_shipped", "delta_pp")),
            ):
                with self.subTest(field=field, value=value):
                    comparison = make_comparison()
                    result = domain_result(comparison, DOMAINS[1])
                    target = result
                    for part in path[:-1]:
                        target = target[part]
                    target[path[-1]] = value
                    self.reject(comparison)

    def test_non_finite_interval_bounds_are_rejected(self) -> None:
        for value in NON_FINITE:
            with self.subTest(value=value):
                for key in ("paired_normal_95ci_low_pp", "paired_normal_95ci_high_pp"):
                    comparison = make_comparison()
                    domain_result(comparison, DOMAINS[0])[
                        "bigram_with_shipped_dictionary_minus_shipped"
                    ][key] = value
                    self.reject(comparison)
                for key in ("paired_normal_95ci_low_pp", "paired_normal_95ci_high_pp"):
                    comparison = make_comparison()
                    domain_result(comparison, DOMAINS[2])["dictionary"]["merged_minus_shipped"][
                        key
                    ] = value
                    self.reject(comparison)

    def test_non_finite_hit_rates_denominators_and_sizes_are_rejected(self) -> None:
        for value in NON_FINITE:
            with self.subTest(value=value):
                comparison = make_comparison()
                domain_result(comparison, DOMAINS[0])[
                    "bigram_with_shipped_dictionary"
                ]["top3_hitrate_pct"] = value
                self.reject(comparison)

                comparison = make_comparison()
                comparison["held_out_evaluations"][DOMAINS[0]]["shipped"][
                    "top3_hitrate_pct"
                ] = value
                self.reject(comparison)

                comparison = make_comparison()
                domain_result(comparison, DOMAINS[0])[
                    "bigram_with_shipped_dictionary_minus_shipped"
                ]["denominator"] = value
                self.reject(comparison)

                comparison = make_comparison()
                comparison["candidates"][CANDIDATE]["bigram_with_shipped_dictionary"][
                    "compressed_bytes"
                ] = value
                self.reject(comparison)

    def test_zero_denominator_and_boolean_metric_are_rejected(self) -> None:
        comparison = make_comparison()
        domain_result(comparison, DOMAINS[1])["dictionary"]["merged_minus_shipped"][
            "denominator"
        ] = 0
        self.reject(comparison)
        comparison = make_comparison()
        domain_result(comparison, DOMAINS[1])[
            "bigram_with_shipped_dictionary_minus_shipped"
        ]["delta_pp"] = True
        self.reject(comparison)

    def test_finite_zero_and_negative_measurements_still_build(self) -> None:
        report = recommend.build_report(
            make_comparison(
                bigram_deltas=(0.0, -0.5, 1.0),
                dictionary_deltas=(0.0, -0.25, 3.0),
            )
        )
        self.assertEqual(report["decision"], "defer")
        self.assertEqual(report["dictionary_decision"], "do_not_integrate")
        self.assertTrue(math.isfinite(report["candidate_measurements"][DOMAINS[1]]["paired_normal_95ci_pp"][0]))

    def test_run_writes_nothing_when_measurements_are_non_finite(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            make_run_root(root)  # shipped identities are current; only metrics are broken
            out = root / "data/corpus-sources/integration-recommendation.json"
            sentinel = {"sentinel": True}
            out.write_text(json.dumps(sentinel), encoding="utf-8")
            comparison = json.loads(
                (root / "data/corpus-sources/comparison.json").read_text(encoding="utf-8")
            )
            domain_result(comparison, DOMAINS[0])[
                "bigram_with_shipped_dictionary_minus_shipped"
            ]["delta_pp"] = float("nan")
            (root / "data/corpus-sources/comparison.json").write_text(
                json.dumps(comparison), encoding="utf-8"
            )
            with contextlib.redirect_stderr(io.StringIO()) as stderr:
                self.assertEqual(recommend.run(root), 2)
            self.assertIn("finite", stderr.getvalue().lower())
            self.assertEqual(json.loads(out.read_text(encoding="utf-8")), sentinel)


class StalenessGuardTest(unittest.TestCase):
    def test_current_comparison_with_matching_identities_has_no_problems(self) -> None:
        problems = recommend.staleness_problems(
            make_comparison(), current_assets(), LIMITS["compressed"], LIMITS["raw"]
        )
        self.assertEqual(problems, [])

    def test_same_size_different_bigram_bytes_is_stale(self) -> None:
        problems = recommend.staleness_problems(
            make_comparison(),
            current_assets(bigram_sha=OTHER_SHA),
            LIMITS["compressed"],
            LIMITS["raw"],
        )
        self.assertEqual(len(problems), 1)
        self.assertIn("shipped bigram bytes changed since the comparison", problems[0])

    def test_same_size_different_dictionary_bytes_is_stale(self) -> None:
        problems = recommend.staleness_problems(
            make_comparison(),
            current_assets(dictionary_sha=OTHER_SHA),
            LIMITS["compressed"],
            LIMITS["raw"],
        )
        self.assertEqual(len(problems), 1)
        self.assertIn("shipped dictionary bytes changed since the comparison", problems[0])

    def test_asset_size_and_digest_drift_is_reported(self) -> None:
        problems = recommend.staleness_problems(
            make_comparison(),
            current_assets(
                bigram_bytes=1124062,
                bigram_sha=OTHER_SHA,
                dictionary_bytes=610928,
                dictionary_sha=OTHER_SHA,
            ),
            LIMITS["compressed"],
            LIMITS["raw"],
        )
        self.assertEqual(len(problems), 2)
        self.assertIn("shipped bigram", problems[0])
        self.assertIn("1124062", problems[0])
        self.assertIn("shipped dictionary", problems[1])

    def test_legacy_comparison_without_hashes_is_not_current(self) -> None:
        problems = recommend.staleness_problems(
            make_comparison(record_hashes=False),
            current_assets(),
            LIMITS["compressed"],
            LIMITS["raw"],
        )
        self.assertEqual(len(problems), 2)
        self.assertIn("shipped bigram identity is unverifiable", problems[0])
        self.assertIn("shipped dictionary identity is unverifiable", problems[1])
        malformed = make_comparison(record_hashes=False)
        malformed["shipped_asset_sizes"] = {
            "bigram_compressed_bytes": 1,
            "dictionary_compressed_bytes": 2,
        }
        problems = recommend.staleness_problems(
            malformed, current_assets(bigram_bytes=1, dictionary_bytes=2),
            LIMITS["compressed"], LIMITS["raw"],
        )
        self.assertEqual(len(problems), 2)  # same size, unverifiable identity
        self.assertTrue(all("unverifiable" in problem for problem in problems))

    def test_truncated_hash_is_not_current(self) -> None:
        problems = recommend.staleness_problems(
            make_comparison(shipped_bigram_sha="abc123"),
            current_assets(),
            LIMITS["compressed"],
            LIMITS["raw"],
        )
        self.assertEqual(len(problems), 1)
        self.assertIn("identity is unverifiable", problems[0])

    def test_packer_limit_drift_is_reported(self) -> None:
        comparison = make_comparison(compressed_limit=200000, raw_limit=1000)
        problems = recommend.staleness_problems(
            comparison, current_assets(), LIMITS["compressed"], LIMITS["raw"]
        )
        self.assertEqual(len(problems), 2)
        self.assertIn("compressed packed-size limit", problems[0])
        self.assertIn("raw packed-size limit", problems[1])

    def test_run_refuses_to_write_a_stale_record(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bigram, _dictionary = make_run_root(root)
            out = root / "data/corpus-sources/integration-recommendation.json"
            self.assertEqual(recommend.run(root), 0)
            record = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(record["decision"], "integrate_bigram_only")
            self.assertEqual(record["dictionary_decision"], "integrate")
            self.assertEqual(record["evidence_status"], "current")
            bigram.write_bytes(b"bigrak")  # same size, different bytes
            with contextlib.redirect_stderr(io.StringIO()) as stderr:
                self.assertEqual(recommend.run(root), 2)
            self.assertIn("shipped bigram", stderr.getvalue())
            self.assertEqual(json.loads(out.read_text(encoding="utf-8")), record)

    def test_run_mark_stale_annotates_the_record_and_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bigram, _dictionary = make_run_root(root)
            out = root / "data/corpus-sources/integration-recommendation.json"
            self.assertEqual(recommend.run(root), 0)
            fresh = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(fresh["evidence_status"], "current")
            bigram.write_bytes(b"bigrak")  # same size, different bytes
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(recommend.run(root, mark_stale=True), 2)
            marked_bytes = out.read_bytes()
            marked = json.loads(marked_bytes)
            self.assertEqual(marked["evidence_status"], "stale")
            self.assertEqual(len(marked["evidence_blockers"]), 1)
            self.assertIn("shipped bigram bytes changed", marked["evidence_blockers"][0])
            self.assertEqual(
                marked["candidate_measurements"], fresh["candidate_measurements"]
            )
            self.assertEqual(marked["decision"], fresh["decision"])
            self.assertFalse(recommend.is_current_evidence(marked))
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(recommend.run(root, mark_stale=True), 2)
            self.assertEqual(out.read_bytes(), marked_bytes)


class EvidenceStatusTest(unittest.TestCase):
    def test_fresh_report_is_current_and_stale_marker_preserves_measurements(self) -> None:
        report = recommend.build_report(make_comparison())
        self.assertEqual(report["evidence_status"], "current")
        self.assertTrue(recommend.is_current_evidence(report))
        problems = ["shipped bigram identity is unverifiable: fixture"]
        marked = recommend.mark_record_stale(report, problems)
        self.assertEqual(marked["evidence_status"], "stale")
        self.assertEqual(marked["evidence_blockers"], problems)
        self.assertEqual(marked["stale_reason"], recommend.STALE_REASON)
        self.assertFalse(recommend.is_current_evidence(marked))
        unmarked = {
            key: value
            for key, value in marked.items()
            if key not in recommend.MARKER_FIELDS
        }
        expected = {
            key: value
            for key, value in report.items()
            if key not in recommend.MARKER_FIELDS
        }
        self.assertEqual(unmarked, expected)

    def test_unmarked_legacy_record_is_not_evidence(self) -> None:
        self.assertFalse(recommend.is_current_evidence({}))
        self.assertFalse(recommend.is_current_evidence({"decision": "defer"}))

    def test_shipped_asset_identity_reads_size_and_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bigram, dictionary = make_run_root(root)
            identity = recommend.shipped_asset_identity(root)
            self.assertEqual(
                identity["bigram"],
                {
                    "size_bytes": bigram.stat().st_size,
                    "sha256": hashlib.sha256(bigram.read_bytes()).hexdigest(),
                },
            )
            self.assertEqual(
                identity["dictionary"]["sha256"],
                hashlib.sha256(dictionary.read_bytes()).hexdigest(),
            )


class LiveRecordTest(unittest.TestCase):
    """The committed record must agree with the committed comparison's measurements."""

    def test_committed_record_matches_derived_verdicts_and_measurements(self) -> None:
        comparison = json.loads(
            (ROOT / "data/corpus-sources/comparison.json").read_text(encoding="utf-8")
        )
        record = json.loads(
            (ROOT / "data/corpus-sources/integration-recommendation.json").read_text(
                encoding="utf-8"
            )
        )
        report = recommend.build_report(comparison)
        self.assertEqual(report["decision"], record["decision"])
        self.assertEqual(report["dictionary_decision"], record["dictionary_decision"])
        self.assertEqual(report["candidate_measurements"], record["candidate_measurements"])
        self.assertEqual(report["candidate_packed_bigram"], record["candidate_packed_bigram"])
        self.assertEqual(
            report["single_source_replacement_top3_delta_pp"],
            record["single_source_replacement_top3_delta_pp"],
        )
        # The committed record agrees with its own metrics: a defer carries reasons, and
        # the dictionary verdict follows the recorded per-domain coverage deltas.
        self.assertEqual(bool(record["defer_reasons"]), record["decision"] == "defer")
        dictionary_regressed = any(
            domain["dictionary_merged_minus_shipped_pp"] < 0
            for domain in record["candidate_measurements"].values()
        )
        self.assertEqual(
            record["dictionary_decision"] == "do_not_integrate", dictionary_regressed
        )
        for domain in record["candidate_measurements"].values():
            for value in (
                domain["candidate_minus_shipped_pp"],
                domain["dictionary_merged_minus_shipped_pp"],
            ):
                self.assertTrue(math.isfinite(value))

    def test_committed_record_marker_matches_current_inputs(self) -> None:
        comparison = json.loads(
            (ROOT / "data/corpus-sources/comparison.json").read_text(encoding="utf-8")
        )
        record = json.loads(
            (ROOT / "data/corpus-sources/integration-recommendation.json").read_text(
                encoding="utf-8"
            )
        )
        problems = recommend.staleness_problems(
            comparison,
            recommend.shipped_asset_identity(ROOT),
            LIMITS["compressed"],
            LIMITS["raw"],
        )
        # Current tooling treats the record as evidence only when no staleness problem exists.
        self.assertEqual(recommend.is_current_evidence(record), not problems)
        if not problems:
            self.assertEqual(record["evidence_status"], "current")
            return
        # While the comparison is stale, the committed record must equal the exact
        # tool-generated non-evidence marker over its unmarked measurements.
        self.assertEqual(record["evidence_status"], "stale")
        self.assertTrue(record["stale_reason"].strip())
        base = {
            key: value
            for key, value in record.items()
            if key not in recommend.MARKER_FIELDS
        }
        self.assertEqual(recommend.mark_record_stale(base, problems), record)


if __name__ == "__main__":
    unittest.main()
