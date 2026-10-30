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


SCRIPTS_FOR_FIXTURE = (
    "release_check.sh",
    "release_pack.sh",
    "build-tools-pin.sh",
    "release-build-inputs.sh",
)


def git(cwd: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments], cwd=cwd, check=True, capture_output=True, text=True
    ).stdout.strip()


class FixtureRepository:
    """A throwaway git repository holding copies of the release scripts and a fake SDK."""

    def __init__(self, directory: Path) -> None:
        self.root = directory / "repo"
        scripts = self.root / "scripts"
        scripts.mkdir(parents=True)
        for name in SCRIPTS_FOR_FIXTURE:
            shutil.copy2(ROOT / "scripts" / name, scripts / name)
        self.write("app/build.gradle", "versionCode 1\n")
        self.write("docs/notes.md", "notes\n")
        git(self.root, "init", "-q")
        git(self.root, "config", "user.email", "fixture@example.invalid")
        git(self.root, "config", "user.name", "fixture")
        git(self.root, "config", "commit.gpgsign", "false")
        self.commit("initial")
        tools = directory / "sdk" / "build-tools" / "fixture"
        tools.mkdir(parents=True)
        self.tools = tools
        for name in ("aapt2", "apksigner"):
            self.tool(name, "#!/bin/sh\nexit 0\n")
        self.environment = os.environ.copy()
        self.environment["ANDROID_HOME"] = str(directory / "sdk")
        self.environment.pop("ANDROID_SDK_ROOT", None)

    def tool(self, name: str, body: str) -> None:
        tool = self.tools / name
        tool.write_text(body, encoding="utf-8")
        tool.chmod(0o755)

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def commit(self, message: str) -> str:
        git(self.root, "add", "-A")
        git(self.root, "commit", "-q", "-m", message)
        return git(self.root, "rev-parse", "HEAD")

    def head(self) -> str:
        return git(self.root, "rev-parse", "HEAD")

    def apk(self, name: str, provenance: bytes | None) -> Path:
        path = self.root.parent / name
        with zipfile.ZipFile(path, "w") as apk:
            if provenance is not None:
                apk.writestr(PROVENANCE_ENTRY, provenance)
        return path

    def apk_from(self, revision: str) -> Path:
        return self.apk(f"{revision[:12]}.apk", f'revision: "{revision}"\n'.encode())

    def run(self, script: str, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(self.root / "scripts" / script), *arguments],
            cwd=self.root,
            env=self.environment,
            check=False,
            capture_output=True,
            text=True,
        )


class ReleaseProvenanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repo = FixtureRepository(Path(self.temporary_directory.name))

    def run_release_check(self, apk: Path) -> subprocess.CompletedProcess[str]:
        return self.repo.run("release_check.sh", "--checks", "freshness", str(apk))

    def assert_provenance_fails(self, apk: Path) -> str:
        result = self.run_release_check(apk)
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn("RESULT|FAIL|artifact.freshness|", output)
        self.assertIn("OVERALL|FAIL|", output)
        return output

    def assert_provenance_passes(self, apk: Path) -> None:
        result = self.run_release_check(apk)
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn("RESULT|PASS|artifact.freshness|", output)

    def test_missing_provenance_fails_the_candidate_check(self) -> None:
        self.assert_provenance_fails(self.repo.apk("missing.apk", None))

    def test_malformed_provenance_fails_the_candidate_check(self) -> None:
        revision = self.repo.head()
        malformed = {
            "revision": b'revision: "not-a-commit"\n',
            "duplicate": (f'revision: "{revision}"\nrevision: "{revision}"\n'.encode()),
            "utf8": b'revision: "\xff"\n',
        }
        for name, provenance in malformed.items():
            with self.subTest(name=name):
                self.assert_provenance_fails(self.repo.apk(f"{name}.apk", provenance))

    def test_a_well_formed_head_revision_passes(self) -> None:
        self.assert_provenance_passes(self.repo.apk_from(self.repo.head()))

    def test_the_agp_indented_revision_passes(self) -> None:
        provenance = (
            "repositories {\n"
            "    system: GIT\n"
            f'    revision: "{self.repo.head()}"\n'
            "}\n"
        ).encode()
        self.assert_provenance_passes(self.repo.apk("indented.apk", provenance))

    def test_an_ancestor_with_only_document_changes_since_passes(self) -> None:
        packed = self.repo.head()
        self.repo.write("docs/notes.md", "release notes\n")
        self.repo.write("CHANGELOG.md", "changes\n")
        self.repo.commit("documents only")
        self.assert_provenance_passes(self.repo.apk_from(packed))

    def test_an_ancestor_with_a_build_input_change_since_fails(self) -> None:
        packed = self.repo.head()
        self.repo.write("app/src/main/java/Fix.java", "class Fix {}\n")
        self.repo.commit("a code fix after the pack")
        output = self.assert_provenance_fails(self.repo.apk_from(packed))
        self.assertIn("app/src/main/java/Fix.java", output)

    def test_a_revision_outside_the_history_fails(self) -> None:
        self.assert_provenance_fails(self.repo.apk_from("0123456789abcdef" * 2 + "01234567"))

    def test_an_uncommitted_build_input_change_fails_even_at_head(self) -> None:
        self.repo.write("app/build.gradle", "versionCode 2\n")
        output = self.assert_provenance_fails(self.repo.apk_from(self.repo.head()))
        self.assertIn("app/build.gradle", output)

    def test_an_untracked_build_input_fails_even_at_head(self) -> None:
        self.repo.write("app/src/main/res/values/extra.xml", "<resources/>\n")
        self.assert_provenance_fails(self.repo.apk_from(self.repo.head()))

    def test_an_uncommitted_document_change_keeps_head_valid(self) -> None:
        self.repo.write("docs/notes.md", "draft\n")
        self.assert_provenance_passes(self.repo.apk_from(self.repo.head()))


class ReleasePackDirtyTreeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repo = FixtureRepository(Path(self.temporary_directory.name))

    def test_a_dirty_build_input_stops_the_pack_before_any_build(self) -> None:
        self.repo.write("app/build.gradle", "versionCode 2\n")
        result = self.repo.run("release_pack.sh", "--no-sign", "out.apk")
        output = result.stdout + result.stderr
        self.assertEqual(1, result.returncode, output)
        self.assertIn("незакоммиченные изменения", output)
        self.assertIn("app/build.gradle", output)
        self.assertNotIn("build-tools:", output)

    def test_allow_dirty_passes_the_gate_with_a_warning(self) -> None:
        self.repo.write("app/build.gradle", "versionCode 2\n")
        result = self.repo.run("release_pack.sh", "--no-sign", "--allow-dirty", "out.apk")
        output = result.stdout + result.stderr
        self.assertIn("WARNING: --allow-dirty", output)
        self.assertNotIn("незакоммиченные изменения", output)
        # The fixture SDK lacks the pinned build-tools, so the run stops at that step past the gate.
        self.assertIn("build-tools", output)

    def test_a_clean_tree_passes_the_gate(self) -> None:
        self.repo.write("docs/notes.md", "draft\n")
        result = self.repo.run("release_pack.sh", "--no-sign", "out.apk")
        output = result.stdout + result.stderr
        self.assertNotIn("незакоммиченные изменения", output)
        self.assertNotIn("--allow-dirty", output)
        self.assertIn("build-tools", output)


class CriticalResourcesTest(unittest.TestCase):
    """artifact.critical_resources against a fake aapt2 dump of a fixture tree."""

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repo = FixtureRepository(Path(self.temporary_directory.name))
        res = "app/src/main/res/"
        self.repo.write(
            res + "raw/keep.xml",
            '<resources xmlns:tools="http://schemas.android.com/tools"\n'
            '    tools:keep="@xml/keyboard_layout_set_*,@xml/kbd_*,@xml/rows_*,'
            '@xml/rowkeys_*,@xml/row_*,@string/*" />\n',
        )
        self.repo.write(res + "xml/kbd_tatar.xml", "<Keyboard/>\n")
        self.repo.write(
            res + "xml/key_styles_common.xml",
            '<key-style keyLabel="!string/change_keyboard"/>\n',
        )
        self.repo.write(
            res + "values/strings-action-keys.xml",
            '<resources><string name="label_pause_key">P</string>'
            '<string name="label_wait_key">W</string></resources>\n',
        )
        self.repo.write(
            res + "values/strings.xml",
            '<resources><string name="change_keyboard">C</string>'
            '<string name="locale_name_tt">T</string></resources>\n',
        )
        self.repo.write(
            "app/src/main/java/Table.java",
            'class Table { String s = "!string/label_wait_key"; }\n',
        )
        self.apk = self.repo.apk("candidate.apk", None)
        self.dump = Path(self.temporary_directory.name) / "dump.txt"
        self.repo.tool("aapt2", f'#!/bin/sh\ncat "{self.dump}"\n')

    def write_dump(self, names: list[str]) -> None:
        self.dump.write_text(
            "".join(f"    resource 0x7f0{i:05x} {name}\n" for i, name in enumerate(names)),
            encoding="utf-8",
        )

    def run_check(self) -> tuple[int, str]:
        result = self.repo.run(
            "release_check.sh", "--checks", "critical_resources", str(self.apk)
        )
        log = self.repo.root / "build" / "release_check" / "critical-resources.log"
        text = log.read_text(encoding="utf-8") if log.is_file() else ""
        return result.returncode, result.stdout + result.stderr + text

    def test_every_named_string_present_passes(self) -> None:
        self.write_dump([
            "xml/kbd_tatar", "string/label_pause_key", "string/label_wait_key",
            "string/change_keyboard", "string/locale_name_tt",
        ])
        code, output = self.run_check()
        self.assertEqual(0, code, output)
        self.assertIn("RESULT|PASS|artifact.critical_resources|", output)

    def test_a_shrunk_key_label_string_fails(self) -> None:
        self.write_dump([
            "xml/kbd_tatar", "string/label_pause_key", "string/label_wait_key",
            "string/locale_name_tt",
        ])
        code, output = self.run_check()
        self.assertNotEqual(0, code, output)
        self.assertIn("MISSING string/change_keyboard", output)

    def test_a_reference_to_an_undefined_string_fails(self) -> None:
        self.repo.write(
            "app/src/main/res/xml/key_styles_enter.xml",
            '<key-style keyLabel="!string/edit_menu_copy"/>\n',
        )
        self.write_dump([
            "xml/kbd_tatar", "string/label_pause_key", "string/label_wait_key",
            "string/change_keyboard", "string/locale_name_tt",
        ])
        code, output = self.run_check()
        self.assertNotEqual(0, code, output)
        self.assertIn("!string/edit_menu_copy", output)


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
