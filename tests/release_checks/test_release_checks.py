"""Regression fixtures for release provenance and explicit APK path handling."""

from __future__ import annotations

import os
from pathlib import Path
import re
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


class ReleasePackOutputTest(unittest.TestCase):
    """Packing from an existing APK with stub build-tools, to check where the output lands."""

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repo = FixtureRepository(Path(self.temporary_directory.name))
        # zipalign -f -z 4 IN OUT copies; zipalign -c 4 FILE accepts.
        self.repo.tool(
            "zipalign", '#!/bin/sh\nif [ "$1" = "-f" ]; then cp "$4" "$5"; fi\nexit 0\n')
        self.repo.environment["TT_BUILD_TOOLS_VERSION"] = "fixture"
        self.unsigned = self.repo.root.parent / "unsigned.apk"
        with zipfile.ZipFile(self.unsigned, "w") as apk:
            apk.writestr(zipfile.ZipInfo("resources.arsc"), b"arsc")

    def test_an_output_in_a_missing_directory_is_written(self) -> None:
        output = self.repo.root / "build" / "measure" / "out.apk"
        result = self.repo.run(
            "release_pack.sh", "--no-sign", "--from-apk", str(self.unsigned), str(output))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.unsigned.read_bytes(), output.read_bytes())


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


class ReleaseGateTest(unittest.TestCase):
    """The default mode runs every repository gate; the gate commands are stubs here."""

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repo = FixtureRepository(Path(self.temporary_directory.name))
        self.repo.write(
            "gradlew",
            "#!/bin/sh\nmkdir -p app/build/test-results/stub\n"
            "echo '<testsuite tests=\"1\" failures=\"0\" errors=\"0\"/>' "
            "> app/build/test-results/stub/TEST-stub.xml\n",
        )
        (self.repo.root / "gradlew").chmod(0o755)
        self.repo.write("scripts/run_python_tests.sh", "echo 'Ran 1 tests'\n")
        self.repo.write("scripts/check-no-internet.sh", "exit 0\n")
        self.repo.write("scripts/rebuild_assets.py", "")
        self.repo.write("scripts/parity_check.py", "")
        self.repo.write("tests/stub/test_stub.py", "")
        self.apk = self.repo.apk("candidate.apk", None)

    def gate_line(self, hygiene_exit: int) -> str:
        self.repo.write(
            "scripts/text_hygiene_check.py", f"import sys\nsys.exit({hygiene_exit})\n")
        result = self.repo.run("release_check.sh", str(self.apk))
        lines = [line for line in result.stdout.splitlines() if "gates.text_hygiene" in line]
        self.assertEqual(1, len(lines), result.stdout + result.stderr)
        return lines[0].split()[0]

    def test_text_hygiene_findings_fail_the_release_check(self) -> None:
        self.assertEqual("FAIL", self.gate_line(1))

    def test_clean_text_hygiene_passes_its_gate(self) -> None:
        self.assertEqual("PASS", self.gate_line(0))

    def test_quick_mode_skips_the_gate(self) -> None:
        result = self.repo.run("release_check.sh", "--quick", str(self.apk))
        self.assertRegex(result.stdout, r"SKIP\s+gates\.text_hygiene")


class ContinuousIntegrationChecksTest(unittest.TestCase):
    """CI runs the keystore-free artifact checks on the unsigned release APK."""

    # The selectable checks that need the release key or a committed tree with the APK's
    # recorded commit; every other selectable check runs in CI.
    LOCAL_ONLY = {"signature", "freshness"}

    def setUp(self) -> None:
        self.workflow = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        script = RELEASE_CHECK.read_text(encoding="utf-8")
        selectable = re.search(r'^SELECTABLE_CHECKS="([^"]+)"$', script, re.MULTILINE)
        self.assertIsNotNone(selectable)
        self.selectable = set(selectable.group(1).split())
        self.size_limit = re.search(r"^APK_SIZE_LIMIT=(\d+)$", script, re.MULTILINE)
        self.assertIsNotNone(self.size_limit)

    def ci_checks(self) -> list[str]:
        lists = re.findall(r"release_check\.sh --checks (\S+) ", self.workflow)
        self.assertEqual(1, len(lists), lists)
        return lists[0].split(",")

    def test_ci_runs_every_keystore_free_check(self) -> None:
        checks = self.ci_checks()
        self.assertEqual(len(checks), len(set(checks)), checks)
        self.assertLessEqual(self.LOCAL_ONLY, self.selectable)
        self.assertEqual(self.selectable - self.LOCAL_ONLY, set(checks))
        self.assertIn("python3 scripts/rebuild_assets.py --check --allow-known-drift", self.workflow)

    def test_the_size_limit_lives_only_in_the_release_checker(self) -> None:
        self.assertIn("size", self.ci_checks())
        self.assertNotIn(self.size_limit.group(1), self.workflow)

    def test_ci_compiles_the_android_test_and_profile_modules(self) -> None:
        # The androidTest sources and the profile generator are never on the paths of the
        # other CI builds, so their compilation needs its own gradle invocations.
        self.assertIn("./gradlew :app:assembleDebugAndroidTest", self.workflow)
        self.assertIn("./gradlew :baselineprofile:assemble", self.workflow)

    def test_the_scheduled_workflow_gates_key_expiry_and_cold_cache(self) -> None:
        nightly = (ROOT / ".github" / "workflows" / "nightly.yml").read_text(encoding="utf-8")
        self.assertRegex(nightly, r"(?m)^\s*- cron: ")
        self.assertIn("python3 scripts/key_expiry_check.py", nightly)
        self.assertIn("clean assembleRelease -PskipReleaseSigning", nightly)
        # A cache-priming action would defeat the cold-cache build.
        self.assertNotIn("gradle/actions/setup-gradle", nightly)
        # Every action reference is pinned to a full-length commit SHA.
        for ref in re.findall(r"uses:\s*\S+@(\S+)", nightly):
            self.assertRegex(ref, r"^[0-9a-f]{40}$")


class SuggestionsDefaultInScriptsTest(unittest.TestCase):
    """The device scripts agree with the word-suggestions default in Settings.java."""

    SETTINGS = ROOT / "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/Settings.java"
    SMOKE = ROOT / "scripts" / "emulator-smoke.sh"
    GENERATOR = (ROOT / "baselineprofile/src/main/java/org/tatarkeyboard/baselineprofile"
                 / "ImeBaselineProfileGenerator.java")

    def test_word_suggestions_default_on(self) -> None:
        settings = self.SETTINGS.read_text(encoding="utf-8")
        self.assertRegex(
            settings, r"readBooleanTolerant\(prefs, PREF_TATAR_SUGGESTIONS, true\)")

    def test_no_script_says_they_are_off_by_default(self) -> None:
        for path in (self.SMOKE, self.GENERATOR):
            text = " ".join(path.read_text(encoding="utf-8").split())
            self.assertNotRegex(text, r"(?i)suggestions are off by default", path.name)
            self.assertIn("on by default", text, path.name)

    def test_smoke_probes_do_not_skip_a_non_debuggable_package(self) -> None:
        smoke = self.SMOKE.read_text(encoding="utf-8")
        self.assertNotIn("suggestions not enabled (non-debuggable package)", smoke)
        self.assertNotIn("SUGGESTIONS=off", smoke)
        # The emoji grid y assumes the visible strip in every mode.
        self.assertRegex(smoke, r"(?m)^GRID_CELL0_Y=0\.6877$")


class DictionaryNoticeTest(unittest.TestCase):
    """NOTICE.txt beside the bundled dictionaries describes the conversational stream exactly."""

    NOTICE = ROOT / "app/src/main/assets/dictionaries/NOTICE.txt"

    def test_the_left_out_tatar_lines_are_not_called_held_out(self) -> None:
        notice = " ".join(self.NOTICE.read_text(encoding="utf-8").split())
        self.assertNotIn("held-out", notice)
        self.assertIn("without the lines whose id % 10 == 1", notice)

    def test_the_acceptance_rule_is_stated_without_release_numbers(self) -> None:
        notice = self.NOTICE.read_text(encoding="utf-8")
        self.assertNotIn("WIDENED", notice)
        self.assertNotRegex(notice, r"(?m)^1\.9\.[01] ")


if __name__ == "__main__":
    unittest.main()
