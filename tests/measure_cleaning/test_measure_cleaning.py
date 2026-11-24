#!/usr/bin/env python3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research/corpus"))
import measure_cleaning


class MeasurementTest(unittest.TestCase):
    def test_binary_metrics_has_explicit_denominators(self):
        result = measure_cleaning.binary_metrics([(True, True), (True, False), (False, True), (False, False)])
        self.assertEqual((result["tp"], result["fp"], result["fn"], result["tn"]), (1, 1, 1, 1))
        self.assertEqual(result["precision"], 0.5)
        self.assertEqual(result["recall"], 0.5)
        self.assertEqual(result["false_positive_rate"], 0.5)

    def test_false_reject_report_aggregates_known_tatar_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "known.txt"
            path.write_text("# header\nһәм\nЭто наш дом\n", encoding="utf-8")
            report = measure_cleaning.false_reject_report([path], frozenset({"һәм"}), frozenset({"это", "наш", "дом"}))
            self.assertEqual(report["lines"], 2)
            self.assertEqual(report["false_rejects"], 1)
            self.assertEqual(report["false_reject_rate"], 0.5)
            self.assertEqual(report["sources"][0]["labels"]["russian"], 1)


if __name__ == "__main__":
    unittest.main()
