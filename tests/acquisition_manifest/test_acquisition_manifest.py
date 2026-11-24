#!/usr/bin/env python3
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from contextlib import redirect_stdout, redirect_stderr

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research/corpus"))
import acquisition_manifest as acquisition


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.raw = self.root / "raw"
        self.path = self.raw / "curated/fixture.bin"
        self.path.parent.mkdir(parents=True)
        self.path.write_bytes(b"abc")
        self.manifest = self._manifest()

    def _record(self):
        return {
            "source_id": "fixture", "source_role": "test_text", "local_path": "curated/fixture.bin",
            "source_url": "https://example.invalid/fixture.bin", "retained": True, "bytes": 3,
            "sha256": hashlib.sha256(b"abc").hexdigest(), "hf_revision": None, "dump_release": "fixture-v1",
            "last_modified": None, "retrieved_utc": "2026-01-02T03:04:05+00:00",
            "metadata_unavailable": {
                "hf_revision": "The fixture is not a Hugging Face dataset.",
                "last_modified": "The fixture evidence has no modification timestamp.",
            },
        }

    def _manifest(self):
        return {
            "schema_version": 1, "expected_source_ids": ["fixture"],
            "sources": [{
                "source_id": "fixture", "role": "test_text", "retained_files": [self._record()],
                "non_retained_downloads": [],
            }],
            "totals": {"source_count": 1, "retained_file_count": 1, "retained_bytes": 3,
                       "non_retained_download_count": 0, "non_retained_bytes": 0, "failed_download_count": 0},
        }

    def _write(self):
        path = self.root / "manifest.json"
        path.write_text(json.dumps(self.manifest), encoding="utf-8")
        return path

    def test_valid_manifest_and_cli_check(self):
        acquisition.validate_manifest(self.manifest, self.raw, ("fixture",))
        output = io.StringIO()
        with redirect_stdout(output):
            result = acquisition.main(["check", "--manifest", str(self._write()), "--raw-root", str(self.raw)], required_sources=("fixture",))
        self.assertEqual(result, 0)
        self.assertEqual(json.loads(output.getvalue())["status"], "valid")

    def test_missing_hash_and_size_mismatches_fail(self):
        self.path.unlink()
        with self.assertRaisesRegex(acquisition.ManifestValidationError, "missing"):
            acquisition.validate_manifest(self.manifest, self.raw, ("fixture",))
        self.path.write_bytes(b"xyz")
        with self.assertRaisesRegex(acquisition.ManifestValidationError, "SHA-256 mismatch"):
            acquisition.validate_manifest(self.manifest, self.raw, ("fixture",))
        self.path.write_bytes(b"abcd")
        with self.assertRaisesRegex(acquisition.ManifestValidationError, "byte size mismatch"):
            acquisition.validate_manifest(self.manifest, self.raw, ("fixture",))

    def test_cli_reports_error_and_unsafe_paths_fail_before_reading(self):
        self.path.write_bytes(b"xyz")
        errors = io.StringIO()
        with redirect_stderr(errors):
            result = acquisition.main(["check", "--manifest", str(self._write()), "--raw-root", str(self.raw)], required_sources=("fixture",))
        self.assertEqual(result, 1)
        self.assertIn("SHA-256 mismatch", errors.getvalue())
        self.manifest["sources"][0]["retained_files"][0]["local_path"] = "../outside.bin"
        with self.assertRaisesRegex(acquisition.ManifestValidationError, "unsafe relative path"):
            acquisition.validate_manifest(self.manifest, self.raw, ("fixture",))

    def test_required_sources_cannot_be_self_declared_or_omitted(self):
        self.manifest["expected_source_ids"].append("missing")
        with self.assertRaisesRegex(acquisition.ManifestValidationError, "exactly match required"):
            acquisition.validate_manifest(self.manifest, self.raw, ("fixture",))
        self.manifest["expected_source_ids"] = []
        self.manifest["sources"] = []
        with self.assertRaises(acquisition.ManifestValidationError):
            acquisition.validate_manifest(self.manifest, self.raw, ("fixture",))

    def test_retained_record_cannot_claim_another_source(self):
        record = self.manifest["sources"][0]["retained_files"][0]
        record["source_id"] = "other"
        with self.assertRaisesRegex(acquisition.ManifestValidationError, "ownership mismatch"):
            acquisition.validate_manifest(self.manifest, self.raw, ("fixture",))

    def test_non_retained_download_needs_no_local_file(self):
        source = self.manifest["sources"][0]
        source["non_retained_downloads"].append({
            "url": "https://example.invalid/page", "retrieved_utc": "2026-01-02T03:04:05+00:00",
            "kind": "content_html", "retained": False, "local_path": None, "bytes": 4,
            "sha256": hashlib.sha256(b"html").hexdigest(), "download_status": "downloaded",
        })
        self.manifest["totals"]["non_retained_download_count"] = 1
        self.manifest["totals"]["non_retained_bytes"] = 4
        acquisition.validate_manifest(self.manifest, self.raw, ("fixture",))

    def test_null_metadata_requires_evidence_reason(self):
        self.manifest["sources"][0]["retained_files"][0]["metadata_unavailable"].pop("last_modified")
        with self.assertRaisesRegex(acquisition.ManifestValidationError, "evidence reason"):
            acquisition.validate_manifest(self.manifest, self.raw, ("fixture",))

    def test_streaming_digest_covers_every_byte(self):
        payload = b"\x00first\n" + "сәләм".encode("utf-8") + b"\xfflast"
        path = self.raw / "digest.bin"
        path.write_bytes(payload)
        self.assertEqual(acquisition.file_digest(path), (len(payload), hashlib.sha256(payload).hexdigest()))


if __name__ == "__main__":
    unittest.main()
