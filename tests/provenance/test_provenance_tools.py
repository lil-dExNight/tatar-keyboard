#!/usr/bin/env python3
from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))


def load_module(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


provenance = load_module("provenance", "scripts/provenance.py")
metrics = load_module("evaluation_metrics", "scripts/evaluation_metrics.py")
splits = load_module("evaluation_splits", "scripts/evaluation_splits.py")
FIXTURES = ROOT / "tests" / "provenance" / "fixtures"


class ProvenanceTest(unittest.TestCase):
    def make_tree(self, root: Path) -> tuple[Path, dict[str, object]]:
        artifact = root / "app/src/main/assets/example.bin"
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b"immutable\x00payload")
        manifest_dir = root / "data/provenance.d"
        manifest_dir.mkdir(parents=True)
        shard = {
            "id": "example",
            "revision": "example/v1",
            "size_bytes": artifact.stat().st_size,
            "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
            "license": "Apache-2.0",
            "source_role": "generated_test_asset",
            "split_rule": "not-applicable",
            "generation_command": "fixture generator",
            "path": "app/src/main/assets/example.bin",
        }
        (manifest_dir / "example.json").write_text(json.dumps(shard), encoding="utf-8")
        return artifact, shard

    def test_live_shards_cover_every_artifact(self) -> None:
        result = provenance.validate_shards(ROOT)
        self.assertGreaterEqual(result["shards"], 23)
        self.assertEqual(result["shards"], result["artifacts"])

    def test_missing_stale_and_duplicate_provenance_fail(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact, shard = self.make_tree(root)
            self.assertEqual(
                provenance.validate_shards(root, covered_roots=(Path("app/src/main/assets"),))[
                    "artifacts"
                ],
                1,
            )
            artifact.write_bytes(b"changed")
            with self.assertRaisesRegex(provenance.ProvenanceError, "size mismatch"):
                provenance.validate_shards(
                    root, covered_roots=(Path("app/src/main/assets"),)
                )
            artifact.write_bytes(b"immutable\x00payload")
            extra = artifact.parent / "unmanifested.bin"
            extra.write_bytes(b"new")
            with self.assertRaisesRegex(provenance.ProvenanceError, "missing provenance"):
                provenance.validate_shards(
                    root, covered_roots=(Path("app/src/main/assets"),)
                )
            extra.unlink()
            duplicate = dict(shard)
            duplicate["path"] = "app/src/main/assets/other.bin"
            (root / "app/src/main/assets/other.bin").write_bytes(artifact.read_bytes())
            (root / "data/provenance.d/duplicate.json").write_text(
                json.dumps(duplicate), encoding="utf-8"
            )
            with self.assertRaisesRegex(provenance.ProvenanceError, "duplicate id"):
                provenance.validate_shards(
                    root, covered_roots=(Path("app/src/main/assets"),)
                )


class EvaluationMetricTest(unittest.TestCase):
    def valid_report(self) -> dict[str, object]:
        return self.read_report("valid_gate_report.json")

    def read_report(self, name: str) -> dict[str, object]:
        return json.loads((FIXTURES / name).read_text(encoding="utf-8"))

    def test_fourth_cell_is_diagnostic_not_visible(self) -> None:
        candidates = ["бер", "ике", "өч", "target"]
        self.assertNotIn("target", metrics.visible_top3(candidates))
        self.assertIn("target", metrics.engine_top4(candidates))

    def test_valid_report_and_paired_fixture(self) -> None:
        metrics.validate_gate_report(self.valid_report())
        report = metrics._paired_ci_command(FIXTURES / "paired_input.json")
        self.assertEqual(report["baseline"]["hits"], 2)
        self.assertEqual(report["baseline"]["denominator"], 8)
        self.assertEqual(report["candidate"]["hits"], 4)
        self.assertEqual(report["delta"]["paired"], True)
        self.assertEqual(len(report["delta"]["ci95"]), 2)

    def test_rounding_fixture_and_production_report_validate(self) -> None:
        # Baseline 1/3 and candidate 2/3: the pp must come from the rounded pct values
        # (33.333334), not from the raw rate difference (33.333333).
        metrics.validate_gate_report(self.read_report("thirds_gate_report.json"))
        visible = metrics._paired_ci_command(FIXTURES / "paired_input.json")
        engine = metrics.paired_metric_report(
            [0, 1, 1, 0],
            [2, 2, 2, 2],
            [1, 1, 1, 1],
            [2, 2, 2, 2],
            k=metrics.ENGINE_TOP_K,
            role="diagnostic",
            rounds=100,
            seed=20261001,
        )
        report = {
            "language": "tat",
            "measurement": metrics.measurement_report(
                units=4,
                denominator_name="adjacent next-word pairs",
                visible_top3_report=visible,
                engine_top4_report=engine,
            ),
        }
        metrics.validate_gate_report(report)

    def test_stale_pct_and_pp_fixtures_fail(self) -> None:
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "baseline.pct"):
            metrics.validate_gate_report(self.read_report("stale_pct_gate_report.json"))
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "stale_pp|delta.pp"):
            metrics.validate_gate_report(self.read_report("stale_pp_gate_report.json"))

    def test_hand_edited_pct_and_pp_are_rejected(self) -> None:
        edited = self.valid_report()
        edited["measurement"]["metrics"]["visible_top3"]["candidate"]["pct"] = 60.0
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "candidate.pct"):
            metrics.validate_gate_report(edited)
        edited = self.valid_report()
        edited["measurement"]["metrics"]["engine_top4"]["baseline"]["pct"] = 100.0
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "engine_top4.baseline.pct"):
            metrics.validate_gate_report(edited)
        flipped = self.valid_report()
        flipped["measurement"]["metrics"]["visible_top3"]["delta"]["pp"] = -25.0
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "delta.pp"):
            metrics.validate_gate_report(flipped)
        missing = self.valid_report()
        del missing["measurement"]["metrics"]["visible_top3"]["candidate"]["pct"]
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "pct is missing or invalid"):
            metrics.validate_gate_report(missing)
        non_finite = self.valid_report()
        non_finite["measurement"]["metrics"]["visible_top3"]["baseline"]["pct"] = float("nan")
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "pct is missing or invalid"):
            metrics.validate_gate_report(non_finite)
        boolean = self.valid_report()
        boolean["measurement"]["metrics"]["engine_top4"]["delta"]["pp"] = True
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "delta.pp is missing or invalid"):
            metrics.validate_gate_report(boolean)

    def test_wrong_top_k_and_unpaired_report_fail(self) -> None:
        wrong_k = self.valid_report()
        wrong_k["measurement"]["metrics"]["visible_top3"]["k"] = 4
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "wrong top-k"):
            metrics.validate_gate_report(wrong_k)
        unpaired = self.valid_report()
        unpaired["measurement"]["metrics"]["visible_top3"]["delta"]["paired"] = False
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "unpaired comparison"):
            metrics.validate_gate_report(unpaired)
        missing_denominator = self.valid_report()
        del missing_denominator["measurement"]["metrics"]["engine_top4"]["baseline"][
            "denominator"
        ]
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "denominator"):
            metrics.validate_gate_report(missing_denominator)

    def test_unpaired_series_and_impossible_hits_fail(self) -> None:
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "lengths differ"):
            metrics.paired_delta_ci95([0, 1], [2, 2], [1], [2])
        with self.assertRaisesRegex(metrics.EvaluationMetricError, "exceed"):
            metrics.paired_delta_ci95([3], [2], [1], [2])


class SplitCollisionTest(unittest.TestCase):
    def write_config(self, root: Path, sources: list[dict[str, object]]) -> Path:
        path = root / "splits.json"
        path.write_text(json.dumps({"language": "tat", "sources": sources}), encoding="utf-8")
        return path

    def source(self, identifier: str, kind: str, split: str, path: str) -> dict[str, object]:
        return {"id": identifier, "kind": kind, "split": split, "path": path}

    def write_basic_sentences(self, root: Path) -> list[dict[str, object]]:
        (root / "train.txt").write_text("бер ике өч\nдүрт биш алты\n", encoding="utf-8")
        (root / "dev.txt").write_text("җиде сигез тугыз\n", encoding="utf-8")
        (root / "final.txt").write_text("ун унбер уннике\n", encoding="utf-8")
        return [
            self.source("train", "sentences", "train", "train.txt"),
            self.source("dev", "sentences", "development", "dev.txt"),
            self.source("final", "sentences", "final", "final.txt"),
        ]

    def test_disjoint_train_development_final_pass(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = self.write_basic_sentences(root)
            (root / "frequency.tsv").write_text("бер\t10\nике\t5\n", encoding="utf-8")
            (root / "admissions.tsv").write_text("word\tfrequency\nөч\t2\n", encoding="utf-8")
            sources.extend(
                [
                    self.source("frequency", "frequencies", "train", "frequency.tsv"),
                    self.source(
                        "admissions", "dictionary_admissions", "train", "admissions.tsv"
                    ),
                ]
            )
            report = splits.check_splits(root, self.write_config(root, sources))
            self.assertTrue(report["passed"])
            self.assertEqual(report["collision_count"], 0)

    def test_normalized_sentence_and_bigram_collisions_fail(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = self.write_basic_sentences(root)
            (root / "bigram.tsv").write_text("1\tҖИДЕ сигез тугыз!\n", encoding="utf-8")
            sources.append(
                {
                    **self.source("bigram", "bigram_training", "train", "bigram.tsv"),
                    "sentence_column": 1,
                }
            )
            with self.assertRaises(splits.SplitCollisionError) as caught:
                splits.check_splits(root, self.write_config(root, sources))
            self.assertEqual(caught.exception.report["collision_count"], 1)
            self.assertEqual(
                caught.exception.report["collisions"][0]["normalized"],
                "җиде сигез тугыз",
            )

    def test_normalized_frequency_and_admission_collisions_fail(self) -> None:
        for kind in ("frequencies", "dictionary_admissions"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                sources = self.write_basic_sentences(root)
                (root / "words.tsv").write_text("Бер\t1\nбер\t2\n", encoding="utf-8")
                sources.append(self.source("words", kind, "train", "words.tsv"))
                with self.assertRaises(splits.SplitCollisionError) as caught:
                    splits.check_splits(root, self.write_config(root, sources))
                self.assertEqual(caught.exception.report["collision_count"], 1)


class LiveSplitConfigTest(unittest.TestCase):
    """Run the committed config so the suite covers the real final split, offline."""

    def test_live_split_config_passes_and_covers_evaluation_splits(self) -> None:
        config = ROOT / "data" / "evaluation" / "splits-tt.json"
        report = splits.check_splits(ROOT, config)
        self.assertTrue(report["passed"])
        self.assertEqual(report["collision_count"], 0)
        records = report["records"]
        self.assertGreater(records["final:sentences"], 0)
        self.assertGreater(records["development:sentences"], 0)
        self.assertGreater(records["train:frequencies"], 0)


if __name__ == "__main__":
    unittest.main()
