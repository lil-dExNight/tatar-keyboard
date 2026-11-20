#!/usr/bin/env python3
"""Deterministic tests for the fail-closed Glot500 text converter."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research" / "corpus"))

import glot500_dump_to_text as converter  # noqa: E402

REVISION = "f7b3e9d11a7e974ec0d021342cd8b41fd67510c1"
ALLOWED = ["Earthlings", "Flores200", "w2c"]
REJECTED = ["Leipzig_mixed", "Tatoeba"]


class FakeColumn:
    def __init__(self, values):
        self.values = values

    def to_pylist(self):
        return self.values


class FakeBatch:
    def __init__(self, rows, columns):
        self.rows = rows
        self.columns = columns

    def column(self, name):
        return FakeColumn([row.get(name) for row in self.rows])


class FakeParquetFile:
    """Small JSON-backed stand-in; pyarrow is optional in the normal test runtime."""

    def __init__(self, path):
        self.rows = json.loads(Path(path).read_text(encoding="utf-8"))

    def iter_batches(self, batch_size, columns):
        for start in range(0, len(self.rows), 2):
            yield FakeBatch(self.rows[start:start + 2], columns)


def row(label, text="synthetic words", script="Cyrl", lang_script="Cyrl"):
    return {
        "text": text,
        "dataset": label,
        "script": script,
        "lang_script": lang_script,
    }


class ConverterTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        parquet_module = types.ModuleType("pyarrow.parquet")
        parquet_module.ParquetFile = FakeParquetFile
        pyarrow_module = types.ModuleType("pyarrow")
        pyarrow_module.__path__ = []
        pyarrow_module.parquet = parquet_module
        modules = mock.patch.dict(sys.modules, {
            "pyarrow": pyarrow_module,
            "pyarrow.parquet": parquet_module,
        })
        modules.start()
        self.addCleanup(modules.stop)

    def write_shard(self, name, rows):
        path = self.root / name
        path.write_text(json.dumps(rows), encoding="utf-8")
        return path

    def write_policy(self, shards, revision=REVISION):
        pins = []
        for shard in shards:
            with shard.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            pins.append({
                "path": f"tat_Cyrl/train/{shard.name}",
                "bytes": shard.stat().st_size,
                "sha256": digest,
            })
        path = self.root / "allowlist.json"
        path.write_text(json.dumps({
            "schema_version": 1,
            "dataset_revision": revision,
            "default": "deny",
            "label_matching": "exact",
            "allow_labels": ALLOWED,
            "known_rejected_labels": REJECTED,
            "shards": pins,
        }), encoding="utf-8")
        return path

    def convert(self, shards, policy, revision=REVISION):
        out = self.root / "out.txt"
        report = self.root / "report.json"
        stats = converter.convert(shards, out, report, revision, policy)
        return stats, out.read_text(encoding="utf-8"), json.loads(
            report.read_text(encoding="utf-8")
        )

    def assert_no_output(self):
        self.assertFalse((self.root / "out.txt").exists())
        self.assertFalse((self.root / "report.json").exists())

    def test_emits_every_allowed_label_and_normalizes_whitespace(self):
        shard = self.write_shard("allowed.parquet", [
            row("Earthlings", "  earthlings   one\n"),
            row("Flores200", "flores\ntwo"),
            row("w2c", "web   three"),
            row("w2c", "not emitted", script="Latn"),
        ])
        policy = self.write_policy([shard])

        stats, output, report = self.convert([shard], policy)

        self.assertEqual(output, "earthlings one\nflores two\nweb three\n")
        self.assertEqual(stats["rows_kept"], 3)
        self.assertEqual(stats["rows_dropped_script"], 1)
        self.assertEqual(stats["per_label_kept"], {
            "Earthlings": 1,
            "Flores200": 1,
            "w2c": 1,
        })
        self.assertEqual(stats["dataset_revision"], REVISION)
        self.assertEqual(stats["unknown_labels"], [])
        self.assertEqual(report, stats)
        self.assertEqual(
            stats["output_sha256"], hashlib.sha256(output.encode("utf-8")).hexdigest()
        )

    def test_known_non_allowlisted_labels_default_to_deny(self):
        shard = self.write_shard("denied.parquet", [
            row("Earthlings", "allowed only"),
            row("Tatoeba", "evaluation text"),
            row("Leipzig_mixed", "duplicate text"),
        ])
        policy = self.write_policy([shard])

        stats, output, _ = self.convert([shard], policy)

        self.assertEqual(output, "allowed only\n")
        self.assertEqual(stats["rows_dropped_label"], 2)
        self.assertEqual(stats["dropped_labels"], ["Leipzig_mixed", "Tatoeba"])
        self.assertEqual(stats["per_label_kept"], {"Earthlings": 1})

    def test_unknown_label_in_any_shard_refuses_all_output(self):
        allowed = self.write_shard("allowed.parquet", [row("Earthlings")])
        unknown = self.write_shard("unknown.parquet", [
            row("FutureSource", "must never escape"),
        ])
        policy = self.write_policy([allowed, unknown])

        with self.assertRaisesRegex(ValueError, "unknown dataset labels.*FutureSource"):
            converter.convert(
                [allowed, unknown], self.root / "out.txt", self.root / "report.json",
                REVISION, policy,
            )

        self.assert_no_output()

    def test_revision_mismatch_refuses_all_output(self):
        shard = self.write_shard("allowed.parquet", [row("Earthlings")])
        policy = self.write_policy([shard])

        with self.assertRaisesRegex(ValueError, "dataset revision mismatch"):
            converter.convert(
                [shard], self.root / "out.txt", self.root / "report.json",
                "0" * 40, policy,
            )

        self.assert_no_output()

    def test_shard_hash_mismatch_refuses_all_output(self):
        shard = self.write_shard("allowed.parquet", [row("Earthlings", "alpha")])
        policy = self.write_policy([shard])
        shard.write_text(
            shard.read_text(encoding="utf-8").replace("alpha", "omega"),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            converter.convert(
                [shard], self.root / "out.txt", self.root / "report.json",
                REVISION, policy,
            )

        self.assert_no_output()

    def test_tracked_policy_contains_reviewed_revision_labels_and_pins(self):
        policy = converter.load_policy(converter.DEFAULT_ALLOWLIST)

        self.assertEqual(policy["dataset_revision"], REVISION)
        self.assertEqual(policy["allow_labels"], ALLOWED)
        self.assertEqual(policy["default"], "deny")
        self.assertEqual(len(policy["shards"]), 3)
        self.assertTrue(all(pin["sha256"] for pin in policy["shards"]))


if __name__ == "__main__":
    unittest.main()
