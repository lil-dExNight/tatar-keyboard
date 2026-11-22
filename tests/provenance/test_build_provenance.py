#!/usr/bin/env python3
"""Fixtures for the raw-dump acquisition/converted pin cross-check in build_provenance.py."""
from __future__ import annotations

import hashlib
import importlib.util
import json
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


bp = load_module("build_provenance", "research/corpus-sources/build_provenance.py")

DUMP_NAME = "Tatoeba-v2026-10-31.tt.txt.gz"
SHARD_NAME = "corpus-dump-tatoeba-v2026-10-31.tt.json"
CONVERTED_KEY = "tt_conv_train90-sentences.txt"


def spec_for(data: bytes, converted: tuple[str, ...] = (CONVERTED_KEY,)) -> dict[str, object]:
    return {
        "license": "CC-BY-2.0-FR",
        "split_rule": "fixture split",
        "role": "raw_corpus",
        "acquisition_pin": {
            "size_bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        },
        "converted_pins": converted,
    }


class BuildProvenanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "research/corpus").mkdir(parents=True)
        (self.root / "data/provenance.d").mkdir(parents=True)
        self.write_manifest({CONVERTED_KEY: {"size": 1, "sha256": "0" * 64}})

    def write_manifest(self, manifest: dict[str, object]) -> None:
        (self.root / "data/corpus-manifest.json").write_text(
            json.dumps(manifest), encoding="utf-8"
        )

    def write_dump(self, data: bytes) -> None:
        (self.root / "research/corpus" / DUMP_NAME).write_bytes(data)

    def shard_path(self) -> Path:
        return self.root / "data/provenance.d" / SHARD_NAME

    def build(self, spec: dict[str, object]) -> dict[str, object]:
        return bp.build_corpus_dump_shards(self.root, {DUMP_NAME: spec})

    def test_matching_dump_writes_shard(self) -> None:
        data = b"tatar sentences\n"
        self.write_dump(data)
        report = self.build(spec_for(data))
        self.assertEqual(report["written"], [SHARD_NAME])
        self.assertEqual(report["removed"], [])
        shard = json.loads(self.shard_path().read_text(encoding="utf-8"))
        self.assertEqual(shard["size_bytes"], len(data))
        self.assertEqual(shard["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(shard["source_role"], "raw_corpus")
        self.assertEqual(shard["path"], f"research/corpus/{DUMP_NAME}")

    def test_wrong_size_fails_before_shard_rewrite(self) -> None:
        pinned = b"pinned bytes"
        self.write_dump(pinned)
        self.build(spec_for(pinned))
        committed = self.shard_path().read_bytes()
        self.write_dump(pinned + b"drift")
        with self.assertRaisesRegex(bp.ProvenanceBuildError, "size differs"):
            self.build(spec_for(pinned))
        self.assertEqual(self.shard_path().read_bytes(), committed)

    def test_wrong_sha256_fails_before_shard_rewrite(self) -> None:
        pinned = b"pinned bytes"
        self.write_dump(pinned)
        self.build(spec_for(pinned))
        committed = self.shard_path().read_bytes()
        self.write_dump(b"pinned byt3s")  # same size, different digest
        with self.assertRaisesRegex(bp.ProvenanceBuildError, "SHA-256 differs"):
            self.build(spec_for(pinned))
        self.assertEqual(self.shard_path().read_bytes(), committed)

    def test_missing_mapping_fails(self) -> None:
        data = b"dump bytes"
        self.write_dump(data)
        spec = spec_for(data)
        del spec["acquisition_pin"]
        with self.assertRaisesRegex(bp.ProvenanceBuildError, "no acquisition pin"):
            self.build(spec)
        self.assertFalse(self.shard_path().exists())

    def test_dangling_converted_pin_fails(self) -> None:
        data = b"dump bytes"
        self.write_dump(data)
        with self.assertRaisesRegex(bp.ProvenanceBuildError, "converted-corpus pin"):
            self.build(spec_for(data, converted=("absent-converted.txt",)))
        self.assertFalse(self.shard_path().exists())

    def test_raw_name_absent_from_converted_manifest_cannot_disable_the_check(self) -> None:
        # The converted manifest keys name converted streams, never raw dumps; the old
        # manifest.get(raw name) lookup therefore always missed and skipped the check.
        manifest = json.loads((self.root / "data/corpus-manifest.json").read_text())
        self.assertNotIn(DUMP_NAME, manifest)
        pinned = b"pinned bytes"
        self.write_dump(pinned + b"drift")
        with self.assertRaisesRegex(bp.ProvenanceBuildError, "acquisition pin"):
            self.build(spec_for(pinned))
        self.assertFalse(self.shard_path().exists())

    def test_absent_registry_dump_keeps_its_declaration(self) -> None:
        data = b"pinned bytes"
        self.write_dump(data)
        self.build(spec_for(data))
        (self.root / "research/corpus" / DUMP_NAME).unlink()
        committed = self.shard_path().read_bytes()
        report = self.build(spec_for(data))
        # The registry still declares the dump: provenance requires the declaration even
        # without local corpora, so the shard and its pin stay committed untouched.
        self.assertEqual(report, {"written": [], "removed": []})
        self.assertEqual(self.shard_path().read_bytes(), committed)

    def test_shard_for_a_dump_that_left_the_registry_is_deleted(self) -> None:
        data = b"pinned bytes"
        self.write_dump(data)
        self.build(spec_for(data))
        (self.root / "research/corpus" / DUMP_NAME).unlink()
        report = bp.build_corpus_dump_shards(self.root, {})
        self.assertEqual(report["removed"], [SHARD_NAME])
        self.assertEqual(report["written"], [])
        self.assertFalse(self.shard_path().exists())


class LiveDumpPinTest(unittest.TestCase):
    """The production pin mapping must cover the real dumps and manifest, offline."""

    def test_every_production_dump_has_an_explicit_mapping(self) -> None:
        manifest = json.loads((ROOT / "data/corpus-manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(len(bp.CORPUS_DUMPS), 4)
        for name, meta in bp.CORPUS_DUMPS.items():
            with self.subTest(dump=name):
                pin = meta["acquisition_pin"]
                self.assertIsInstance(pin["size_bytes"], int)
                self.assertGreater(pin["size_bytes"], 0)
                self.assertRegex(pin["sha256"], r"^[0-9a-f]{64}$")
                for converted in meta["converted_pins"]:
                    self.assertIn(converted, manifest)

    def test_present_local_dumps_match_their_pins(self) -> None:
        for name, meta in bp.CORPUS_DUMPS.items():
            dump = ROOT / "research/corpus" / name
            if not dump.is_file():
                continue  # clean checkout without local corpora: nothing to measure
            with self.subTest(dump=name):
                size, sha256 = bp._sha256(dump)
                self.assertEqual(size, meta["acquisition_pin"]["size_bytes"])
                self.assertEqual(sha256, meta["acquisition_pin"]["sha256"])

    def test_acquisition_pins_match_committed_provenance_shards(self) -> None:
        """Every duplicated pin must equal the tracked shard a clean checkout carries."""
        for name, meta in bp.CORPUS_DUMPS.items():
            stem = name.replace(".txt.gz", "").lower()
            shard_path = ROOT / "data/provenance.d" / f"corpus-dump-{stem}.json"
            with self.subTest(dump=name):
                self.assertTrue(shard_path.is_file(), str(shard_path))
                shard = json.loads(shard_path.read_text(encoding="utf-8"))
                self.assertEqual(shard["path"], f"research/corpus/{name}")
                self.assertEqual(shard["source_role"], "raw_corpus")
                self.assertEqual(shard["size_bytes"], meta["acquisition_pin"]["size_bytes"])
                self.assertEqual(shard["sha256"], meta["acquisition_pin"]["sha256"])


if __name__ == "__main__":
    unittest.main()
