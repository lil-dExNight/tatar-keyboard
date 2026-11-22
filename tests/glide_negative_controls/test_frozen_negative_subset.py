#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/androidTest/assets/glide-negative-controls"
SOURCES = (
    ROOT / "research/glide-evaluation/datasets/session-2-tt-portrait.jsonl",
    ROOT / "research/glide-evaluation/datasets/session-2-tt-landscape.jsonl",
)


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class FrozenNegativeSubsetTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source_lines: dict[str, str] = {}
        self.source_records: dict[str, dict[str, object]] = {}
        for source in SOURCES:
            for line in source.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                record = json.loads(line)
                identifier = str(record["record_id"])
                self.source_lines[identifier] = line
                self.source_records[identifier] = record
        self.manifest = json.loads((ASSETS / "manifest.json").read_text(encoding="utf-8"))
        self.negative_lines = [
            line
            for line in (ASSETS / "frozen-negatives.jsonl").read_text(encoding="utf-8").splitlines()
            if line
        ]
        self.positive_lines = [
            line
            for line in (
                ASSETS / "frozen-positive-pairs.jsonl"
            ).read_text(encoding="utf-8").splitlines()
            if line
        ]

    def test_pinned_lines_are_exact_records_from_the_tracked_dataset(self) -> None:
        self.assertEqual(29, len(self.negative_lines))
        negative_by_id: dict[str, str] = {}
        for index, line in enumerate(self.negative_lines):
            record = json.loads(line)
            identifier = str(record["record_id"])
            negative_by_id[identifier] = line
            pin = self.manifest["records"][index]
            self.assertEqual(identifier, pin["record_id"])
            self.assertEqual(sha256(self.source_lines[identifier]), pin["sha256"])
            self.assertEqual(self.source_lines[identifier], line)
            self.assertNotEqual("ordinary", record["trial_kind"])

        positives = {str(json.loads(line)["record_id"]) for line in self.positive_lines}
        self.assertEqual(26, len(positives))
        for index, line in enumerate(self.positive_lines):
            record = json.loads(line)
            identifier = str(record["record_id"])
            pin = self.manifest["positive_records"][index]
            self.assertEqual(identifier, pin["record_id"])
            self.assertEqual(sha256(self.source_lines[identifier]), pin["sha256"])
            self.assertEqual(self.source_lines[identifier], line)
            self.assertEqual("ordinary", record["trial_kind"])

    def test_manifest_pairs_follow_the_recorded_selection_rule(self) -> None:
        expected: list[dict[str, str]] = []
        negative_records = [json.loads(line) for line in self.negative_lines]
        for negative in sorted(negative_records, key=lambda item: item["record_id"]):
            candidates = [
                record
                for record in self.source_records.values()
                if record["trial_kind"] == "ordinary"
                and record["session_id"] == negative["session_id"]
                and record["orientation"] == negative["orientation"]
                and record["speed_condition"] == negative["speed_condition"]
                and record["layout_id"] == negative["layout_id"]
            ]
            exact = sorted(
                [record for record in candidates if record["prompt"] == negative["prompt"]],
                key=lambda record: str(record["record_id"]),
            )
            ordered_candidates = sorted(
                candidates, key=lambda record: str(record["record_id"])
            )
            positive = (exact or ordered_candidates)[0]
            expected.append(
                {
                    "negative_record_id": str(negative["record_id"]),
                    "positive_record_id": str(positive["record_id"]),
                }
            )
        self.assertEqual(expected, self.manifest["pairs"])

    def test_all_required_negative_classes_are_replayed(self) -> None:
        kinds = {json.loads(line)["trial_kind"] for line in self.negative_lines}
        self.assertEqual(
            {"tap", "tremor", "canceled", "short_drag", "unintended_start"},
            kinds,
        )
        self.assertEqual("glide-negative-control-subset-v1", self.manifest["schema"])


if __name__ == "__main__":
    unittest.main()
