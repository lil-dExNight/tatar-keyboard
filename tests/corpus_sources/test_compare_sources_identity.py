#!/usr/bin/env python3
"""compare_sources.py must pin shipped-asset identity (SHA-256), not only compressed size."""
from __future__ import annotations

import hashlib
import importlib.util
import re
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")


def load_module(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


compare = load_module("compare_sources", "research/corpus-sources/compare_sources.py")


class ShippedAssetIdentityTest(unittest.TestCase):
    def test_shipped_asset_fields_record_size_and_digest(self) -> None:
        paths = compare.shipped_asset_paths()
        fields = compare.shipped_asset_fields()
        self.assertEqual(
            fields["shipped_asset_sizes"]["bigram_compressed_bytes"],
            paths["bigram"].stat().st_size,
        )
        self.assertEqual(
            fields["shipped_asset_sizes"]["dictionary_compressed_bytes"],
            paths["dictionary"].stat().st_size,
        )
        self.assertEqual(
            fields["shipped_asset_sha256"]["bigram"],
            hashlib.sha256(paths["bigram"].read_bytes()).hexdigest(),
        )
        self.assertEqual(
            fields["shipped_asset_sha256"]["dictionary"],
            hashlib.sha256(paths["dictionary"].read_bytes()).hexdigest(),
        )
        for digest in fields["shipped_asset_sha256"].values():
            self.assertRegex(digest, SHA256_RE)

    def test_same_size_different_bytes_changes_the_recorded_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            first = Path(temporary) / "asset.zlib"
            first.write_bytes(b"bigramA")
            digest_first = compare.file_sha256(first)
            first.write_bytes(b"bigramB")  # same size, different bytes
            digest_second = compare.file_sha256(first)
            self.assertEqual(digest_first, hashlib.sha256(b"bigramA").hexdigest())
            self.assertNotEqual(digest_first, digest_second)
            self.assertRegex(digest_second, SHA256_RE)

    def test_file_provenance_uses_the_shared_digest_helper(self) -> None:
        # file_provenance reports repository-relative paths, so it runs on a tracked file.
        path = ROOT / "data/corpus-sources/ATTRIBUTION.txt"
        provenance = compare.file_provenance(path)
        self.assertEqual(provenance["path"], "data/corpus-sources/ATTRIBUTION.txt")
        self.assertEqual(provenance["size_bytes"], path.stat().st_size)
        self.assertEqual(provenance["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
