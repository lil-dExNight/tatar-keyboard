#!/usr/bin/env python3
"""Unit tests for scripts/dl2_acceptance.py (record selection, reports and gates; no bridge run)."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import dl2_acceptance as acceptance  # noqa: E402

WORKSHEET_HEADER = (
    "record_id\tsource_file\tsource_line\tclass\trecord_kind\ttyped_form\tproposed_correction\t"
    "preserve_word\texpected_unchanged\tsource_class\tevidence_type\tsource_frequency\t"
    "independent_examples\tsource_refs\tcontext\tcorrected_context\tedit_comment\tedit_link\t"
    "source_status\tsource_approved\tsource_reviewer\tsource_review_date\tsource_note\t"
    "collision_group\tsplit\tsplit_reason\tbaseline_overlap\tbaseline_refs\tpair_collision_key\t"
    "risk_mass_edit\trisk_proper_name\trisk_duplicate\trisk_corpus_overlap\trisk_flags\t"
    "risk_details\tapproved\treviewer\treview_date\tnote"
)


def worksheet_row(**fields: str) -> str:
    values = {key: "" for key in WORKSHEET_HEADER.split("\t")}
    values.update(fields)
    return "\t".join(values[key] for key in WORKSHEET_HEADER.split("\t"))


def write_worksheet(directory: Path, rows: list[str]) -> Path:
    path = directory / "worksheet-fixture.tsv"
    path.write_text("\n".join([WORKSHEET_HEADER] + rows) + "\n", encoding="utf-8")
    return path


# Text and advice columns are empty by default: a non-empty advice string is a fire, so
# initializing them to "0" would fabricate replacements in every fixture row.
BRIDGE_TEXT_COLUMNS = {
    "id", "kind", "predecessor",
    "baseline_advice", "candidate_advice", "advice_source",
    "armed_baseline_advice", "armed_candidate_advice",
    "class_variants", "baseline_candidates", "candidate_candidates",
}


def bridge_result(**fields: str) -> dict[str, str]:
    row = {
        key: "" if key in BRIDGE_TEXT_COLUMNS else "0"
        for key in acceptance.RESULT_COLUMNS
    }
    row.update(fields)
    return row


class ExtractPredecessorTest(unittest.TestCase):
    def test_predecessor_strips_punctuation_and_uses_the_first_match(self):
        context = "Беренче сүз, икенче китем һәм китем тагын."
        self.assertEqual(acceptance.extract_predecessor(context, "китем"), "икенче")

    def test_predecessor_is_empty_at_sentence_start_and_for_missing_words(self):
        self.assertEqual(acceptance.extract_predecessor("Китем башта тора.", "китем"), "")
        self.assertEqual(acceptance.extract_predecessor("Бөтенләй башка сүз.", "китем"), "")
        self.assertEqual(acceptance.extract_predecessor("", "китем"), "")


class SelectWorksheetTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = write_worksheet(
            Path(self.directory.name),
            [
                # Two final approved pairs sharing one collision group.
                worksheet_row(
                    record_id="C0000001", record_kind="pair", split="final", approved="yes",
                    typed_form="китем", proposed_correction="китәм",
                    collision_group="G1", risk_corpus_overlap="yes",
                ),
                worksheet_row(
                    record_id="C0000002", record_kind="pair", split="final", approved="yes",
                    typed_form="ташкент", proposed_correction="ташкәнт",
                    collision_group="G1", risk_mass_edit="yes",
                ),
                # A rejected final pair: excluded from recovery entirely.
                worksheet_row(
                    record_id="C0000003", record_kind="pair", split="final", approved="no",
                    typed_form="бермә", proposed_correction="бәрмә", collision_group="G2",
                ),
                # Development approved pair: positive control only.
                worksheet_row(
                    record_id="C0000004", record_kind="pair", split="development", approved="yes",
                    typed_form="хай", proposed_correction="һай", collision_group="G3",
                ),
                # Degenerate final pair (identical forms): counted, not measured.
                worksheet_row(
                    record_id="C0000005", record_kind="pair", split="final", approved="yes",
                    typed_form="игә", proposed_correction="игә", collision_group="G4",
                ),
                # A final approved safety row with a resolvable predecessor.
                worksheet_row(
                    record_id="S0000001", record_kind="safety", split="final", approved="yes",
                    typed_form="все", preserve_word="все", expected_unchanged="все",
                    context="Скажите все слова.", collision_group="G5",
                ),
                # A safety row violating the written-form contract: counted, not measured.
                worksheet_row(
                    record_id="S0000002", record_kind="safety", split="final", approved="yes",
                    typed_form="лет", preserve_word="лет", expected_unchanged="лето",
                    collision_group="G6",
                ),
                # A safety row with insufficient evidence: neither protected nor a correction.
                worksheet_row(
                    record_id="S0000003", record_kind="safety", split="final", approved="no",
                    typed_form="чем", preserve_word="чем", expected_unchanged="чем",
                    collision_group="G7",
                ),
            ],
        )

    def test_selection_covers_exactly_the_approved_final_rows(self):
        selection = acceptance.select_worksheet("fixture", self.path)
        self.assertEqual(
            ["C0000001", "C0000002"],
            [row["record_id"] for row in selection["recovery"]],
        )
        self.assertEqual("китем", selection["recovery"][0]["word"])
        self.assertEqual("китәм", selection["recovery"][0]["correction"])
        self.assertEqual(["C0000004"], [row["record_id"] for row in selection["control"]])
        self.assertEqual(["S0000001"], [row["record_id"] for row in selection["safety"]])
        self.assertEqual("скажите", selection["safety"][0]["predecessor"])
        self.assertEqual(1, selection["degenerate_recovery_rows"])
        self.assertEqual(1, selection["safety_contract_violations"])
        self.assertEqual(
            {
                "risk_mass_edit": 1,
                "risk_proper_name": 0,
                "risk_duplicate": 0,
                "risk_corpus_overlap": 1,
            },
            selection["risk_exposure"],
        )

    def test_a_missing_reviewed_worksheet_fails_with_the_option_hint(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "worksheet-ae.tsv"
            with self.assertRaises(acceptance.AcceptanceError) as caught:
                acceptance.select_worksheet("ae", missing)
            self.assertIn("--worksheet-dir", str(caught.exception))


class DisplayPathTest(unittest.TestCase):
    def test_repo_files_are_relative_and_external_paths_are_absolute(self):
        inside = acceptance.ROOT / "data/dictionary/tatar-autocorrect-review.tsv"
        self.assertEqual(
            "data/dictionary/tatar-autocorrect-review.tsv", acceptance.display_path(inside)
        )
        external_relative = Path("..") / "outside-repo" / "worksheet-ae.tsv"
        displayed = acceptance.display_path(external_relative)
        self.assertTrue(Path(displayed).is_absolute())
        self.assertEqual(str(external_relative.resolve()), displayed)


class ExactPairsTest(unittest.TestCase):
    REAL_HEADER = "typed\treplacement\tclass\tapproved\treviewer\treview_date\tnote"
    REAL_COMMENTS = (
        "# Reviewed exact Tatar autocorrections; no productive edit class or dictionary admission.\n"
        "# Within ң/н, only the reviewed genitive-suffix pairs are active.\n"
    )

    def test_comment_lines_before_the_header_are_skipped(self):
        text = (
            self.REAL_COMMENTS
            + self.REAL_HEADER + "\n"
            + "мәгълумат\tмәгълүмат\tү/у\tyes\tclaude\t2026-11-18\tnote\n"
            + "авылнын\tавылның\tң/н\tyes\tclaude\t2026-11-18\tnote\n"
            + "китем\tкитәм\tә/е\tno\tclaude\t2026-11-18\tnote\n"
        )
        pairs = acceptance.parse_exact_pairs(text)
        self.assertEqual(["ү/у", "ң/н"], [pair["record_class"] for pair in pairs])
        self.assertEqual("мәгълумат", pairs[0]["word"])
        self.assertEqual("авылның", pairs[1]["correction"])

    def test_the_committed_pair_file_parses_to_its_declared_counts(self):
        pairs = acceptance.read_exact_pairs(acceptance.EXACT_PAIRS_PATH)
        self.assertEqual(219, len(pairs))
        self.assertEqual(184, sum(1 for pair in pairs if pair["record_class"] == "ү/у"))
        self.assertEqual(35, sum(1 for pair in pairs if pair["record_class"] == "ң/н"))


class ExactSafetyTest(unittest.TestCase):
    def test_declared_row_count_is_preserved_and_duplicates_disclosed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "safety.tsv"
            path.write_text("# comment line\nwordа\nwordб\nwordа\n", encoding="utf-8")
            safety = acceptance.read_exact_safety(path)
            self.assertEqual(3, safety["row_count"])
            self.assertEqual(2, safety["unique_words"])
            self.assertEqual(["wordа"], safety["duplicated"])


class BuildBatchTest(unittest.TestCase):
    def test_batch_order_is_recovery_control_safety_oov_exact_pairs(self):
        selection = {
            "recovery": [
                {"record_id": "C1", "word": "китем", "correction": "китәм", "collision_group": "G1"},
            ],
            "control": [{"record_id": "C2", "word": "хай", "correction": "һай"}],
            "safety": [{"record_id": "S1", "word": "бар", "predecessor": "бер"}],
        }
        exact_pairs = [
            {"word": "мәгълумат", "correction": "мәгълүмат", "record_class": "ү/у"},
        ]
        exact_safety = {"rows": ["тоз", "кич"]}
        valid_oov = {"tat": {"rows": ["сүзташ"]}, "rus": {"rows": ["лет"]}}
        batch = acceptance.build_batch("ae", selection, exact_pairs, exact_safety, valid_oov)
        self.assertEqual(
            ["recovery", "control", "safety", "safety", "safety", "safety", "baseline_pair"],
            [record["kind"] for record in batch],
        )
        self.assertEqual("C1", batch[0]["id"])
        self.assertEqual("тоз", batch[3]["word"])
        self.assertEqual("сүзташ", batch[5]["word"])
        self.assertEqual("мәгълумат", batch[6]["word"])

    def test_russian_class_skips_the_tatar_exact_safety_set(self):
        selection = {"recovery": [], "control": [], "safety": []}
        valid_oov = {"tat": {"rows": ["а"]}, "rus": {"rows": ["лет", "все"]}}
        batch = acceptance.build_batch("ye-yo", selection, [], {"rows": ["тоз"]}, valid_oov)
        self.assertEqual(["safety", "safety"], [record["kind"] for record in batch])
        self.assertEqual("rus", batch[0]["language"])


class PairedMeasurementTest(unittest.TestCase):
    def test_collision_groups_become_single_bootstrap_units(self):
        records = [
            {"record_id": "C1", "collision_group": "G1", "baseline_hit": 0, "candidate_hit": 1,
             "baseline_hit4": 0, "candidate_hit4": 1},
            {"record_id": "C2", "collision_group": "G1", "baseline_hit": 1, "candidate_hit": 1,
             "baseline_hit4": 1, "candidate_hit4": 1},
            {"record_id": "C3", "collision_group": "G2", "baseline_hit": 0, "candidate_hit": 0,
             "baseline_hit4": 0, "candidate_hit4": 0},
        ]
        measurement, arrays = acceptance.paired_measurement(records)
        self.assertEqual("worksheet_row", measurement["unit"])
        self.assertEqual(3, measurement["units"])
        self.assertEqual(2, measurement["collision_groups"])
        visible = measurement["metrics"]["visible_top3"]
        self.assertEqual(1, visible["baseline"]["hits"])
        self.assertEqual(2, visible["candidate"]["hits"])
        engine = measurement["metrics"]["engine_top4"]
        self.assertEqual("diagnostic", engine["role"])
        self.assertEqual(4, engine["k"])
        self.assertTrue(visible["delta"]["paired"])
        self.assertEqual(3, len(arrays["record_ids"]))


def base_report() -> dict:
    def arm(hits: int) -> dict:
        return {"hits": hits, "denominator": 2, "pct": hits * 50.0, "ci95": [0.0, 0.0]}

    def metric(k: int, role: str) -> dict:
        return {
            "k": k,
            "role": role,
            "baseline": arm(0),
            "candidate": arm(2),
            "delta": {
                "pp": 100.0, "ci95": [10.0, 190.0], "paired": True,
                "method": "paired-sentence-bootstrap",
                "resampling": "shared-record-indices", "rounds": 2000,
            },
        }

    return {
        "schema": acceptance.REPORT_SCHEMA,
        "class_slug": "ae",
        "record_class": "ә/е",
        "language": "tat",
        "eligible": True,
        "ineligible_reason": None,
        "measurement": {
            "unit": "worksheet_row",
            "units": 2,
            "denominator_name": "approved_final_pair_rows",
            "gate_metric": "visible_top3",
            "metrics": {
                "visible_top3": metric(3, "user"),
                "engine_top4": metric(4, "diagnostic"),
            },
        },
        "recovery": {
            "record_ids": ["C1", "C2"],
            "collision_groups": ["G1", "G2"],
            "baseline_hits": [0, 0],
            "candidate_hits": [1, 1],
            "baseline_hit4": [0, 0],
            "candidate_hit4": [1, 1],
        },
        "safety": {
            "sources": [
                {"source": "final_safety_worksheet", "role": "final", "rows": 2,
                 "candidate_false_replacements": 0},
                {"source": "valid_oov_tat", "role": "development_regression_evaluation",
                 "rows": 1, "candidate_false_replacements": 0},
            ],
            "combined": {
                "rows": 3, "baseline_false_replacements": 0,
                "candidate_false_replacements": 0, "armed_rows": 1,
                "armed_candidate_fires": 0, "unarmed_candidate_fires": 0,
                "guard_blocked_fires": 0,
            },
        },
        "probes": {"candidate_fuzzy_over_budget_lookups": 0},
        "timing": {"candidate": {"p95_ms": 1.0}, "baseline": {"p95_ms": 1.0}},
        "gates": {
            "exact_reviewed_pairs_preserved": {
                "rows": 219, "baseline_fired": 219, "candidate_fired": 219,
            },
        },
    }


class ValidateReportTest(unittest.TestCase):
    def test_a_consistent_report_passes(self):
        acceptance.validate_report(base_report())

    def test_non_binary_hits_are_rejected(self):
        report = base_report()
        report["recovery"]["candidate_hits"] = [1, 2]
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.validate_report(report)

    def test_array_length_mismatch_is_rejected(self):
        report = base_report()
        report["recovery"]["record_ids"] = ["C1"]
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.validate_report(report)

    def test_stale_hit_totals_are_rejected(self):
        report = base_report()
        report["measurement"]["metrics"]["visible_top3"]["candidate"]["hits"] = 1
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.validate_report(report)

    def test_an_ineligible_report_must_not_carry_a_measurement(self):
        report = base_report()
        report["eligible"] = False
        report["ineligible_reason"] = "no approved final pair rows in either split"
        report["measurement"] = None
        report["recovery"] = None
        acceptance.validate_report(report)

    def test_an_ineligible_report_without_a_reason_is_rejected(self):
        report = base_report()
        report["eligible"] = False
        report["measurement"] = None
        report["recovery"] = None
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.validate_report(report)

    def test_unequal_arm_denominators_are_rejected(self):
        report = base_report()
        candidate = report["measurement"]["metrics"]["visible_top3"]["candidate"]
        candidate["denominator"] = 3
        candidate["pct"] = 66.666667
        report["measurement"]["metrics"]["visible_top3"]["delta"]["pp"] = 66.666667
        with self.assertRaises(acceptance.AcceptanceError) as caught:
            acceptance.validate_report(report)
        self.assertIn("denominators differ", str(caught.exception))

    def test_safety_sources_must_cover_the_combined_denominator(self):
        report = base_report()
        report["safety"]["sources"] = report["safety"]["sources"][:1]
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.validate_report(report)


class SafetySummaryTest(unittest.TestCase):
    def test_unarmed_fires_are_counted_even_when_successors_are_armed(self):
        results = [
            # An armed row whose unarmed class advice fires but whose armed advice is blocked.
            bridge_result(
                record_index="0", id="S1", kind="safety", armed_successors="2",
                candidate_advice="диген", armed_candidate_advice="", guard_blocked="1",
            ),
            # An unarmed row with a baseline fire.
            bridge_result(record_index="1", id="S2", kind="safety", baseline_advice="диген"),
        ]
        bounds = [{"source": "final_safety_worksheet", "role": "final", "start": 0, "end": 2}]
        combined = acceptance.safety_summary(results, bounds)["combined"]
        self.assertEqual(1, combined["unarmed_candidate_fires"])
        self.assertEqual(0, combined["armed_candidate_fires"])
        self.assertEqual(1, combined["guard_blocked_fires"])
        self.assertEqual(1, combined["unarmed_baseline_fires"])
        self.assertEqual(0, combined["armed_baseline_fires"])
        self.assertEqual(1, combined["candidate_false_replacements"])
        self.assertEqual(1, combined["baseline_false_replacements"])


class EvaluateGatesTest(unittest.TestCase):
    def test_every_hard_gate_passes_on_a_healthy_report(self):
        self.assertTrue(all(acceptance.evaluate_gates(base_report()).values()))

    def test_a_zero_lower_bound_fails_the_recovery_gate(self):
        report = base_report()
        report["measurement"]["metrics"]["visible_top3"]["delta"]["ci95"] = [0.0, 100.0]
        self.assertFalse(acceptance.evaluate_gates(report)["recovery_delta_ci95_above_zero"])

    def test_a_false_replacement_fails_the_safety_gate(self):
        report = base_report()
        report["safety"]["combined"]["candidate_false_replacements"] = 1
        self.assertFalse(
            acceptance.evaluate_gates(report)["zero_candidate_false_replacements"]
        )

    def test_the_timing_gate_is_an_absolute_budget(self):
        report = base_report()
        report["timing"]["candidate"]["p95_ms"] = 5.001
        self.assertFalse(
            acceptance.evaluate_gates(report)["host_autocorrect_p95_within_budget"]
        )

    def test_an_unfired_exact_pair_fails_preservation(self):
        report = base_report()
        report["gates"]["exact_reviewed_pairs_preserved"]["candidate_fired"] = 218
        self.assertFalse(
            acceptance.evaluate_gates(report)["exact_reviewed_pairs_preserved"]
        )


class BuildReportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.worksheet = write_worksheet(
            Path(self.directory.name),
            [
                worksheet_row(
                    record_id="C1", record_kind="pair", split="final",
                    approved="yes", typed_form="китем", proposed_correction="китәм",
                    collision_group="G1",
                ),
            ],
        )
        self.selection = {
            "recovery": [
                {"record_id": "C1", "word": "китем", "correction": "китәм",
                 "collision_group": "G1"},
                {"record_id": "C2", "word": "ташкент", "correction": "ташкәнт",
                 "collision_group": "G1"},
            ],
            "control": [{"record_id": "C3", "word": "хай", "correction": "һай"}],
            "safety": [
                {"record_id": "S1", "word": "бар", "predecessor": "бер"},
                {"record_id": "S2", "word": "тоз", "predecessor": ""},
            ],
            "degenerate_recovery_rows": 0,
            "safety_contract_violations": 0,
            "risk_exposure": {
                "risk_mass_edit": 0, "risk_proper_name": 0, "risk_duplicate": 0,
                "risk_corpus_overlap": 1,
            },
            "final_pair_rows": 5,
        }
        self.exact_pairs = [
            {"word": "мәгълумат", "correction": "мәгълүмат", "record_class": "ү/у"},
        ] * 219
        self.exact_safety = {"rows": ["тоз", "кич"], "row_count": 2, "unique_words": 2,
                             "duplicated": []}
        self.valid_oov = {
            "tat": {"path": "o.tsv", "row_count": 1, "unique_words": 1, "sha256": "x",
                    "role": "development_regression_evaluation"},
            "rus": {"path": "r.tsv", "row_count": 0, "unique_words": 0, "sha256": "x",
                    "role": "development_regression_evaluation"},
        }
        self.metadata = {
            "candidate_semantics": "dl2-candidate-v1",
            "advice_semantics": "dl2-advice-v1",
            "dictionaries": [],
        }

    @staticmethod
    def result_row(index: int, record_id: str, kind: str, **overrides: str) -> dict[str, str]:
        row = bridge_result(record_index=str(index), id=record_id, kind=kind)
        row.update(overrides)
        return row

    def test_an_eligible_report_aggregates_and_flags_limitations(self):
        results = [
            self.result_row(0, "C1", "recovery", baseline_hit="0", candidate_hit="1"),
            self.result_row(1, "C2", "recovery", baseline_hit="1", candidate_hit="1"),
            self.result_row(2, "C3", "control", advice_source="class", class_variants="һай:9"),
            self.result_row(3, "S1", "safety", armed_successors="2"),
            self.result_row(4, "S2", "safety", word_in_dictionary="1"),
            self.result_row(5, "safety:тоз", "safety"),
            self.result_row(6, "safety:кич", "safety"),
            self.result_row(7, "safety:сүзташ", "safety"),
            self.result_row(8, "P1", "baseline_pair", baseline_fires="1", candidate_fires="1"),
        ]
        # The visible-top-3 metric runs a real paired bootstrap over the collision groups.
        report = acceptance.build_report(
            "ae", self.worksheet, self.selection, self.exact_pairs, self.exact_safety,
            self.valid_oov, results, self.metadata,
        )
        acceptance.validate_report(report)
        self.assertTrue(report["eligible"])
        visible = report["measurement"]["metrics"]["visible_top3"]
        self.assertEqual(1, visible["baseline"]["hits"])
        self.assertEqual(2, visible["candidate"]["hits"])
        self.assertEqual(2, report["measurement"]["units"])
        self.assertEqual(1, report["measurement"]["collision_groups"])
        self.assertEqual(1, report["control"]["class_engaged_records"])
        self.assertEqual(
            [2, 2, 1],
            [source["rows"] for source in report["safety"]["sources"]],
        )
        self.assertEqual(
            "operator_required", report["gates"]["reviewed_risk_resolution"]["status"],
        )
        self.assertEqual("limitation", report["gates"]["final_safety_sample_adequacy"]["status"])
        self.assertFalse(report["accepted_candidate"])
        self.assertTrue(any(
            "too small" in limitation for limitation in report["limitations"]
        ))

    def test_a_class_without_approved_final_pairs_is_ineligible(self):
        selection = dict(self.selection)
        selection["recovery"] = []
        results = [
            self.result_row(0, "C3", "control"),
            self.result_row(1, "S1", "safety"),
            self.result_row(2, "safety:лет", "safety"),
        ]
        report = acceptance.build_report(
            "ye-yo", self.worksheet, selection, [], self.exact_safety, self.valid_oov,
            results, self.metadata,
        )
        acceptance.validate_report(report)
        self.assertFalse(report["eligible"])
        self.assertIsNone(report["measurement"])
        self.assertIsNone(report["recovery"])
        self.assertIn("no approved final pair rows", report["ineligible_reason"])
        self.assertEqual(
            "not_applicable", report["gates"]["recovery_delta_ci95_above_zero"]["status"],
        )
        self.assertFalse(report["accepted_candidate"])


if __name__ == "__main__":
    unittest.main()
