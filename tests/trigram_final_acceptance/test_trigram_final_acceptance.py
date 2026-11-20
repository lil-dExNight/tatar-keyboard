#!/usr/bin/env python3
"""Unit tests for scripts/trigram_final_acceptance.py (no JVM, no final-set access)."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import evaluation_metrics  # noqa: E402
import trigram_final_acceptance as acceptance  # noqa: E402


def synthetic_measurement(sentences: list[dict]) -> dict:
    return {
        "sentences": sentences,
        "moments": sum(record["pairs"] for record in sentences),
        "sentences_total": len(sentences),
        "trigram_covered_moments": 0,
        "latency_ns": {
            "baseline": {"samples": 10, "p50_ns": 100, "p95_ns": 200, "max_ns": 300},
            "candidate": {"samples": 10, "p50_ns": 110, "p95_ns": 210, "max_ns": 310},
        },
        "allocation_bytes_per_round": {
            "baseline": 1000, "candidate": 1100, "rounds": 8, "moments_per_round": 4,
        },
        "sizes": {
            "baseline_raw": 100, "baseline_compressed": 90,
            "candidate_raw": 200, "candidate_compressed": 180,
        },
    }


def record(pairs: int, b1: int, b3: int, b4: int, c1: int, c3: int, c4: int) -> dict:
    return {
        "pairs": pairs,
        "baseline_top1": b1, "baseline_top3": b3, "baseline_top4": b4,
        "candidate_top1": c1, "candidate_top3": c3, "candidate_top4": c4,
    }


class PerRecordArraysTest(unittest.TestCase):
    def test_aligned_arrays(self) -> None:
        measurement = synthetic_measurement(
            [record(3, 1, 2, 3, 2, 3, 3, ), record(2, 0, 1, 1, 1, 2, 2)]
        )
        denominators, arrays = acceptance.per_record_arrays(measurement)
        self.assertEqual(denominators, [3, 2])
        self.assertEqual(arrays["top1"]["baseline"], [1, 0])
        self.assertEqual(arrays["top1"]["candidate"], [2, 1])
        self.assertEqual(arrays["top3"]["baseline"], [2, 1])
        self.assertEqual(arrays["top3"]["candidate"], [3, 2])
        self.assertEqual(arrays["top4"]["candidate"], [3, 2])

    def test_hits_above_pairs_rejected(self) -> None:
        measurement = synthetic_measurement([record(2, 3, 0, 0, 0, 0, 0)])
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.per_record_arrays(measurement)

    def test_nonpositive_pairs_rejected(self) -> None:
        measurement = synthetic_measurement([record(0, 0, 0, 0, 0, 0, 0)])
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.per_record_arrays(measurement)

    def test_empty_measurement_rejected(self) -> None:
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.per_record_arrays(synthetic_measurement([]))


class MeasurementBlockTest(unittest.TestCase):
    def test_block_passes_gate_report_validation(self) -> None:
        sentences = [
            record(4, index % 2, 2, 3, 2, 3 + index % 2, 4)
            for index in range(40)
        ]
        block = acceptance.build_measurement_block(synthetic_measurement(sentences))
        evaluation_metrics.validate_gate_report({"measurement": block})
        visible = block["metrics"]["visible_top3"]
        self.assertEqual(visible["k"], 3)
        self.assertEqual(visible["role"], "user")
        self.assertEqual(block["metrics"]["engine_top4"]["k"], 4)
        self.assertEqual(block["metrics"]["top1"]["k"], 1)
        self.assertEqual(block["units"], 40)
        self.assertEqual(visible["baseline"]["denominator"], 160)
        self.assertGreater(visible["delta"]["pp"], 0)
        self.assertTrue(visible["delta"]["paired"])
        for name in ("visible_top3", "engine_top4", "top1"):
            metric = block["metrics"][name]
            self.assertEqual(
                metric["baseline"]["denominator"], metric["candidate"]["denominator"]
            )
            low, high = metric["delta"]["ci95"]
            self.assertLessEqual(low, high)

    def test_check_report_cli_accepts_block(self) -> None:
        sentences = [record(3, 1, 1, 2, 2, 2, 3) for _ in range(30)]
        block = acceptance.build_measurement_block(synthetic_measurement(sentences))
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            report.write_text(json.dumps({"measurement": block}), encoding="utf-8")
            self.assertEqual(evaluation_metrics.main(["check-report", str(report)]), 0)


class EvaluateGatesTest(unittest.TestCase):
    def _block(self, ci_low: float) -> dict:
        return {
            "metrics": {
                "visible_top3": {
                    "delta": {"pp": 2.0, "ci95": [ci_low, 3.0]},
                }
            }
        }

    def _inputs(self, ci_low: float = 0.5, p95: int = 200, raw: int = 200,
                apk: int = 3_000_000) -> tuple:
        block = self._block(ci_low)
        sizes = {"baseline_raw": 100, "candidate_raw": raw}
        latency = {"baseline": {"p95_ns": 190}, "candidate": {"p95_ns": p95}}
        package = {"candidate_apk_bytes": apk, "delta_bytes": 90}
        return block, sizes, latency, package

    def test_accept_when_all_gates_pass(self) -> None:
        block, sizes, latency, package = self._inputs()
        gates, verdict = acceptance.evaluate_gates(
            block, sizes, latency, package, bigram_part_identical=True
        )
        self.assertEqual(verdict, "ACCEPT")
        self.assertTrue(all(gate["pass"] for gate in gates.values()))

    def test_reject_when_visible_top3_ci_crosses_zero(self) -> None:
        block, sizes, latency, package = self._inputs(ci_low=-0.1)
        gates, verdict = acceptance.evaluate_gates(
            block, sizes, latency, package, bigram_part_identical=True
        )
        self.assertEqual(verdict, "REJECT")
        self.assertFalse(gates["visible_top3_improvement"]["pass"])

    def test_reject_on_latency_budget(self) -> None:
        block, sizes, latency, package = self._inputs(p95=5_000_001)
        gates, verdict = acceptance.evaluate_gates(
            block, sizes, latency, package, bigram_part_identical=True
        )
        self.assertEqual(verdict, "REJECT")
        self.assertFalse(gates["latency"]["pass"])

    def test_reject_on_raw_cap(self) -> None:
        block, sizes, latency, package = self._inputs(raw=10 * 1024 * 1024)
        gates, verdict = acceptance.evaluate_gates(
            block, sizes, latency, package, bigram_part_identical=True
        )
        self.assertEqual(verdict, "REJECT")
        self.assertFalse(gates["memory"]["pass"])

    def test_reject_on_apk_ceiling(self) -> None:
        block, sizes, latency, package = self._inputs(apk=5_242_881)
        gates, verdict = acceptance.evaluate_gates(
            block, sizes, latency, package, bigram_part_identical=True
        )
        self.assertEqual(verdict, "REJECT")
        self.assertFalse(gates["package_size"]["pass"])

    def test_reject_on_bigram_part_divergence(self) -> None:
        block, sizes, latency, package = self._inputs()
        gates, verdict = acceptance.evaluate_gates(
            block, sizes, latency, package, bigram_part_identical=False
        )
        self.assertEqual(verdict, "REJECT")
        self.assertFalse(gates["bigram_part_parity"]["pass"])


class ApkSizeDeltaTest(unittest.TestCase):
    def _apk_with_entry(self, directory: Path, payload: bytes, level: int = 6) -> Path:
        apk = directory / "candidate.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED,
                             compresslevel=level) as archive:
            archive.writestr("classes.dex", b"dex-bytes" * 100)
            archive.writestr(acceptance.APK_ASSET_ENTRY, payload)
        return apk

    def test_delta_from_deflated_entry(self) -> None:
        candidate = acceptance.CANDIDATE_ASSET.read_bytes()
        baseline = candidate[: len(candidate) // 2]
        with tempfile.TemporaryDirectory() as directory:
            apk = self._apk_with_entry(Path(directory), candidate)
            apk_size = apk.stat().st_size
            baseline_path = Path(directory) / "baseline.zlib"
            baseline_path.write_bytes(baseline)
            result = acceptance.apk_size_delta(apk, baseline_path)
        self.assertEqual(result["candidate_apk_bytes"], apk_size)
        self.assertEqual(result["candidate_entry_compressed_bytes"],
                         result["baseline_entry_compressed_bytes"]
                         + result["delta_bytes"])
        self.assertEqual(result["baseline_apk_bytes"],
                         result["candidate_apk_bytes"] - result["delta_bytes"])
        self.assertGreater(result["delta_bytes"], 0)
        self.assertIn("deflate_level", result)

    def test_mismatched_apk_entry_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = self._apk_with_entry(Path(directory), b"not-the-candidate" * 100)
            baseline_path = Path(directory) / "baseline.zlib"
            baseline_path.write_bytes(b"baseline")
            with self.assertRaises(acceptance.AcceptanceError):
                acceptance.apk_size_delta(apk, baseline_path)

    def test_missing_apk_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(acceptance.AcceptanceError):
                acceptance.apk_size_delta(
                    Path(directory) / "absent.apk", Path(directory) / "absent.zlib"
                )


class RunOnceTest(unittest.TestCase):
    def test_measure_refuses_existing_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "measurement.json"
            report.write_text("{}", encoding="utf-8")
            with self.assertRaises(acceptance.AcceptanceError):
                acceptance.run_measurement(Path(directory), report, Path("whatever"))


if __name__ == "__main__":
    unittest.main()
