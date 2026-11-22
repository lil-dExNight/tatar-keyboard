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
                provenance.validate_shards(root, covered_roots=(Path("app/src/main/assets"),), raw_corpus_inventory=())[
                    "artifacts"
                ],
                1,
            )
            artifact.write_bytes(b"changed")
            with self.assertRaisesRegex(provenance.ProvenanceError, "size mismatch"):
                provenance.validate_shards(
                    root, covered_roots=(Path("app/src/main/assets"),), raw_corpus_inventory=()
                )
            artifact.write_bytes(b"immutable\x00payload")
            extra = artifact.parent / "unmanifested.bin"
            extra.write_bytes(b"new")
            with self.assertRaisesRegex(provenance.ProvenanceError, "missing provenance"):
                provenance.validate_shards(
                    root, covered_roots=(Path("app/src/main/assets"),), raw_corpus_inventory=()
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
                    root, covered_roots=(Path("app/src/main/assets"),), raw_corpus_inventory=()
                )


class RawCorpusShardTest(unittest.TestCase):
    """Raw dumps are optional local inputs; the registry lists them, not the shards.

    The temporary roots have no ``.git`` directory, mirroring a clean ``git archive HEAD``
    extraction: validation must not depend on ``git check-ignore`` for raw dump shards, and
    the required dump set must not shrink when a shard or the registry is deleted.
    """

    DUMP = "Example-v1.tt.txt.gz"

    def write_registry(self, root: Path, names: tuple[str, ...]) -> None:
        registry = root / provenance.RAW_CORPUS_REGISTRY
        registry.parent.mkdir(parents=True, exist_ok=True)
        entries = ",\n".join(f'    "{name}": {{"license": "CC0-1.0"}}' for name in names)
        registry.write_text(f"CORPUS_DUMPS = {{\n{entries},\n}}\n", encoding="utf-8")

    def write_shards(self, root: Path, dump_relative: str) -> None:
        pairs = [
            ("example", "app/src/main/assets/example.bin", "generated_test_asset"),
            ("dump", dump_relative, "raw_corpus"),
        ]
        for identifier, path, role in pairs:
            artifact = root / path
            shard = {
                "id": identifier,
                "revision": f"{identifier}/v1",
                "size_bytes": artifact.stat().st_size,
                "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                "license": "Apache-2.0",
                "source_role": role,
                "split_rule": "not-applicable",
                "generation_command": "fixture generator",
                "path": path,
            }
            (root / "data/provenance.d" / f"{identifier}.json").write_text(
                json.dumps(shard), encoding="utf-8"
            )

    def make_root(self, root: Path, registry_names: tuple[str, ...] = ()) -> None:
        (root / "app/src/main/assets").mkdir(parents=True)
        (root / "research/corpus").mkdir(parents=True)
        (root / "data/provenance.d").mkdir(parents=True)
        (root / "app/src/main/assets/example.bin").write_bytes(b"immutable\x00payload")
        (root / "research/corpus" / self.DUMP).write_bytes(b"raw corpus bytes")
        self.write_registry(root, registry_names or (self.DUMP,))
        self.write_shards(root, f"research/corpus/{self.DUMP}")

    def test_absent_raw_dump_is_optional_unless_required(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_root(root)
            (root / "research/corpus" / self.DUMP).unlink()
            result = provenance.validate_shards(root)
            self.assertEqual(result["missing_raw_corpora"], 1)
            self.assertEqual(result["verified_raw_corpora"], 0)
            with self.assertRaisesRegex(provenance.ProvenanceError, "raw corpus dump is required"):
                provenance.validate_shards(root, require_raw_corpora=True)
            self.assertEqual(provenance.main(["--root", str(root)]), 0)
            self.assertEqual(provenance.main(["--root", str(root), "--require-raw-corpora"]), 2)

    def test_present_raw_dump_is_hash_verified_without_git(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_root(root)
            result = provenance.validate_shards(root)
            self.assertEqual(result["verified_raw_corpora"], 1)
            self.assertEqual(result["missing_raw_corpora"], 0)
            dump = root / "research/corpus" / self.DUMP
            dump.write_bytes(b"raw corpus byt3s")  # same size, different bytes
            for required in (False, True):
                with self.assertRaisesRegex(provenance.ProvenanceError, "SHA-256 mismatch"):
                    provenance.validate_shards(root, require_raw_corpora=required)
            dump.write_bytes(b"raw corpus bytes plus drift")
            for required in (False, True):
                with self.assertRaisesRegex(provenance.ProvenanceError, "size mismatch"):
                    provenance.validate_shards(root, require_raw_corpora=required)

    def test_tracked_artifact_is_mandatory_even_when_raw_dumps_are_optional(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_root(root)
            (root / "research/corpus" / self.DUMP).unlink()
            (root / "app/src/main/assets/example.bin").unlink()
            for required in (False, True):
                with self.assertRaisesRegex(provenance.ProvenanceError, "artifact is missing"):
                    provenance.validate_shards(root, require_raw_corpora=required)

    def test_deleted_raw_shard_still_requires_the_registry_dump(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_root(root)
            (root / "data/provenance.d/dump.json").unlink()
            for required in (False, True):
                with self.assertRaisesRegex(
                    provenance.ProvenanceError,
                    "no provenance shard declares the required raw corpus dump",
                ):
                    provenance.validate_shards(root, require_raw_corpora=required)
            # Deleting the dump too does not help: the shard was the only declaration.
            (root / "research/corpus" / self.DUMP).unlink()
            with self.assertRaisesRegex(
                provenance.ProvenanceError, "no provenance shard declares"
            ):
                provenance.validate_shards(root)
            self.assertEqual(provenance.main(["--root", str(root)]), 2)

    def test_unregistered_raw_shard_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_root(root)
            (root / "data/provenance.d/extra.json").write_text(
                json.dumps(
                    {
                        "id": "extra-dump",
                        "revision": "extra/v1",
                        "size_bytes": 4,
                        "sha256": hashlib.sha256(b"bytes").hexdigest(),
                        "license": "CC0-1.0",
                        "source_role": "raw_corpus",
                        "split_rule": "not-applicable",
                        "generation_command": "fixture generator",
                        "path": "research/corpus/Unknown-v1.tt.txt.gz",
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                provenance.ProvenanceError, "absent from the acquisition-pin registry"
            ):
                provenance.validate_shards(root)

    def test_missing_registry_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_root(root)
            (root / provenance.RAW_CORPUS_REGISTRY).unlink()
            for call in (
                lambda: provenance.validate_shards(root),
                lambda: provenance.validate_raw_corpora(root),
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceError, "raw corpus inventory is missing"
                ):
                    call()

    def test_required_dump_cannot_escape_by_renaming_its_role(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_root(root)
            shard_path = root / "data/provenance.d/dump.json"
            shard = json.loads(shard_path.read_text(encoding="utf-8"))
            shard["source_role"] = "generated_training_input"
            shard_path.write_text(json.dumps(shard), encoding="utf-8")
            # The registry requires a raw-corpus declaration for the dump: downgrading the
            # role removes the declaration, not the requirement.
            for dump_present in (True, False):
                if not dump_present:
                    (root / "research/corpus" / self.DUMP).unlink()
                with self.subTest(dump_present=dump_present):
                    with self.assertRaisesRegex(
                        provenance.ProvenanceError, "no provenance shard declares"
                    ):
                        provenance.validate_shards(root)

    def test_raw_role_outside_the_dump_directory_is_not_declared(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_root(root)
            shard_path = root / "data/provenance.d/dump.json"
            shard = json.loads(shard_path.read_text(encoding="utf-8"))
            shard["path"] = f"research/other/{self.DUMP}"
            (root / "research/other").mkdir(parents=True)
            (root / "research/other" / self.DUMP).write_bytes(b"raw corpus bytes")
            shard["size_bytes"] = 16
            shard["sha256"] = hashlib.sha256(b"raw corpus bytes").hexdigest()
            shard_path.write_text(json.dumps(shard), encoding="utf-8")
            # The registry lists dumps under research/corpus only: the re-pointed shard
            # leaves the required dump undeclared instead of becoming an optional entry.
            with self.assertRaisesRegex(
                provenance.ProvenanceError, "no provenance shard declares"
            ):
                provenance.validate_shards(root)

    def test_strict_preflight_requires_declared_verified_dumps(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_root(root)
            self.assertEqual(
                provenance.validate_raw_corpora(root), {"verified_raw_corpora": 1}
            )
            dump = root / "research/corpus" / self.DUMP
            dump.write_bytes(b"raw corpus byt3s")
            with self.assertRaisesRegex(provenance.ProvenanceError, "SHA-256 mismatch"):
                provenance.validate_raw_corpora(root)
            (root / "data/provenance.d/dump.json").unlink()
            with self.assertRaisesRegex(
                provenance.ProvenanceError, "no provenance shard declares"
            ):
                provenance.validate_raw_corpora(root)

    def test_strict_preflight_fails_on_absent_dump(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_root(root)
            (root / "research/corpus" / self.DUMP).unlink()
            with self.assertRaisesRegex(
                provenance.ProvenanceError, "raw corpus dump is required but missing"
            ):
                provenance.validate_raw_corpora(root)


class LiveRawInventoryTest(unittest.TestCase):
    """The committed registry, shards, and dumps form one consistent required set."""

    def test_registry_and_committed_shards_agree(self) -> None:
        inventory = provenance.load_raw_corpus_inventory(ROOT)
        self.assertEqual(len(inventory), 4)
        for relative in inventory:
            self.assertTrue(relative.startswith("research/corpus/"))
            self.assertTrue(relative.endswith(".txt.gz"))
        result = provenance.validate_shards(ROOT)
        self.assertEqual(result["verified_raw_corpora"] + result["missing_raw_corpora"], 4)

    def test_strict_preflight_when_local_dumps_are_present(self) -> None:
        inventory = provenance.load_raw_corpus_inventory(ROOT)
        if not all((ROOT / relative).is_file() for relative in inventory):
            self.skipTest("local raw corpora absent (clean checkout)")
        self.assertEqual(
            provenance.validate_raw_corpora(ROOT)["verified_raw_corpora"], len(inventory)
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
