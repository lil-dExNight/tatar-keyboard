"""Regression fixtures for release provenance and explicit APK path handling."""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
RELEASE_CHECK = ROOT / "scripts" / "release_check.sh"
NO_INTERNET_CHECK = ROOT / "scripts" / "check-no-internet.sh"
PROVENANCE_ENTRY = "META-INF/version-control-info.textproto"


class ReleaseProvenanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.directory = Path(self.temporary_directory.name)
        tools = self.directory / "sdk" / "build-tools" / "fixture"
        tools.mkdir(parents=True)
        for name in ("aapt2", "apksigner"):
            tool = tools / name
            tool.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            tool.chmod(0o755)
        self.environment = os.environ.copy()
        self.environment["ANDROID_HOME"] = str(self.directory / "sdk")
        self.revision = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def write_apk(self, name: str, provenance: bytes | None) -> Path:
        path = self.directory / name
        with zipfile.ZipFile(path, "w") as apk:
            if provenance is not None:
                apk.writestr(PROVENANCE_ENTRY, provenance)
        return path

    def run_release_check(self, apk: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(RELEASE_CHECK), "--checks", "freshness", str(apk)],
            cwd=ROOT,
            env=self.environment,
            check=False,
            capture_output=True,
            text=True,
        )

    def assert_provenance_fails(self, apk: Path) -> None:
        result = self.run_release_check(apk)
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn("RESULT|FAIL|artifact.freshness|", output)
        self.assertIn("OVERALL|FAIL|", output)

    def test_missing_provenance_fails_the_candidate_check(self) -> None:
        self.assert_provenance_fails(self.write_apk("missing.apk", None))

    def test_malformed_provenance_fails_the_candidate_check(self) -> None:
        malformed = {
            "revision": b'revision: "not-a-commit"\n',
            "duplicate": (
                f'revision: "{self.revision}"\nrevision: "{self.revision}"\n'.encode()
            ),
            "utf8": b'revision: "\xff"\n',
        }
        for name, provenance in malformed.items():
            with self.subTest(name=name):
                self.assert_provenance_fails(self.write_apk(f"{name}.apk", provenance))

    def test_a_well_formed_head_revision_passes(self) -> None:
        apk = self.write_apk("valid.apk", f'revision: "{self.revision}"\n'.encode())
        result = self.run_release_check(apk)
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn("RESULT|PASS|artifact.freshness|", output)

    def test_the_agp_indented_revision_passes(self) -> None:
        provenance = (
            "repositories {\n"
            "    system: GIT\n"
            f'    revision: "{self.revision}"\n'
            "}\n"
        ).encode()
        result = self.run_release_check(self.write_apk("indented.apk", provenance))
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn("RESULT|PASS|artifact.freshness|", output)


class ExplicitApkPathTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        self.script = scripts / NO_INTERNET_CHECK.name
        shutil.copy2(NO_INTERNET_CHECK, self.script)
        manifest = self.root / "app" / "src" / "main" / "AndroidManifest.xml"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <application android:allowBackup="false" />\n'
            "</manifest>\n",
            encoding="utf-8",
        )

    def run_check(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(self.script), *arguments],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
        )

    def assert_hard_error(self, path: Path) -> None:
        result = self.run_check(str(path))
        output = result.stdout + result.stderr
        self.assertEqual(2, result.returncode, output)
        self.assertIn("ERROR: explicit APK is not a readable regular file:", output)

    def test_no_argument_mode_keeps_source_only_checks_working(self) -> None:
        result = self.run_check()
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn("Level 1 OK: no INTERNET in source manifest", output)
        self.assertIn("WARNING: level 2 skipped", output)

    def test_an_explicit_missing_or_non_file_apk_is_a_hard_error(self) -> None:
        self.assert_hard_error(self.root / "missing.apk")
        directory = self.root / "directory.apk"
        directory.mkdir()
        self.assert_hard_error(directory)

    @unittest.skipIf(os.geteuid() == 0, "root can read mode-zero files")
    def test_an_explicit_unreadable_apk_is_a_hard_error(self) -> None:
        apk = self.root / "unreadable.apk"
        apk.write_bytes(b"fixture")
        apk.chmod(0)
        self.addCleanup(apk.chmod, 0o600)
        self.assert_hard_error(apk)


if __name__ == "__main__":
    unittest.main()
