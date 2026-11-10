"""Behavioral fixtures for device-state restoration and script safety checks."""

from __future__ import annotations

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
DEVICE_PERF = ROOT / "scripts" / "device-perf-ritual.sh"
EMULATOR_SMOKE = ROOT / "scripts" / "emulator-smoke.sh"
NO_INTERNET = ROOT / "scripts" / "check-no-internet.sh"
BACKUP_DOMAINS = (
    "file",
    "database",
    "sharedpref",
    "external",
    "device_file",
    "device_database",
    "device_sharedpref",
)


def shell_function(path: Path, name: str) -> str:
    """Return one shell function so its real body can run inside a fixture."""
    match = re.search(
        rf"(?ms)^{re.escape(name)}\(\) \{{\n.*?^\}}$", path.read_text(encoding="utf-8")
    )
    if match is None:
        raise AssertionError(f"shell function {name} not found in {path}")
    return match.group(0)


def run_bash(source: str, *arguments: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["bash", "-c", source, "bash", *arguments],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )


class DeviceStateRestoreTest(unittest.TestCase):
    def restore_state(self, stay_on: str, night: str) -> tuple[str, str]:
        script = DEVICE_PERF.read_text(encoding="utf-8")
        capture_start = script.index("PREV_IME=")
        capture_end = script.index("\nWOKEN=0", capture_start)
        capture = script[capture_start:capture_end]
        harness = textwrap.dedent(
            """
            set -uo pipefail
            stayon=$INITIAL_STAYON
            night=$INITIAL_NIGHT
            log() { :; }
            SHELL() {
                case "$*" in
                    "settings get global stay_on_while_plugged_in") printf '%s\n' "$stayon" ;;
                    "settings get secure default_input_method") printf '\n' ;;
                    "settings get secure enabled_input_methods") printf '\n' ;;
                    "settings get system font_scale") printf 'null\n' ;;
                    "dumpsys power") printf 'mWakefulness=Awake\n' ;;
                    "cmd uimode night") printf 'Night mode: %s\n' "$night" ;;
                    "settings put global stay_on_while_plugged_in "*) stayon=$5 ;;
                    "settings delete global stay_on_while_plugged_in") stayon=null ;;
                    "svc power stayon true") stayon=7 ;;
                    "cmd uimode night "*) night=$4 ;;
                esac
            }
            """
        )
        harness += capture + "\n"
        harness += textwrap.dedent(
            """
            WOKEN=0
            IME_ID=""
            IME_WAS_ENABLED=1
            SUGG_UI_RESTORE=""
            ATRACE_ON=0
            BATTERY_ON=0
            UIMODE_ON=1
            FONTSCALE_ON=0
            SCREENOFF=0
            """
        )
        harness += shell_function(DEVICE_PERF, "restore_night_mode") + "\n"
        harness += shell_function(DEVICE_PERF, "restore") + "\n"
        harness += textwrap.dedent(
            """
            SHELL svc power stayon true
            if [ "$night" = yes ]; then
                SHELL cmd uimode night no
            else
                SHELL cmd uimode night yes
            fi
            restore
            printf 'stayon=%s\nnight=%s\n' "$stayon" "$night"
            """
        )
        environment = os.environ.copy()
        environment["INITIAL_STAYON"] = stay_on
        environment["INITIAL_NIGHT"] = night
        result = run_bash(harness, env=environment)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        values = dict(line.split("=", 1) for line in result.stdout.splitlines())
        return values["stayon"], values["night"]

    def test_stay_on_restore_preserves_off_and_numeric_values(self) -> None:
        for original in ("0", "3", "null"):
            with self.subTest(original=original):
                restored, _ = self.restore_state(original, "no")
                self.assertEqual(original, restored)

    def test_night_mode_restore_preserves_yes_no_and_auto(self) -> None:
        for original in ("yes", "no", "auto"):
            with self.subTest(original=original):
                _, restored = self.restore_state("0", original)
                self.assertEqual(original, restored)


class SmokeCrashBufferTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.outdir = Path(self.temporary_directory.name)
        script = EMULATOR_SMOKE.read_text(encoding="utf-8")
        self.crash_check = script.split("# ── crash buffer ──", 1)[1].split(
            "# ── summary ──", 1
        )[0]

    def run_crash_check(self, logcat_status: int, logcat_content: str = "") -> subprocess.CompletedProcess[str]:
        harness = textwrap.dedent(
            """
            set -euo pipefail
            OUTDIR=$1
            RESULTS=$OUTDIR/result.txt
            : > "$RESULTS"
            FAILURES=0
            result() {
                local line="RESULT|$1|$2|$3"
                echo "$line"
                echo "$line" >> "$RESULTS"
                [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
            }
            A() {
                if [ "$LOGCAT_STATUS" -ne 0 ]; then
                    echo "logcat fixture read failure" >&2
                    return "$LOGCAT_STATUS"
                fi
                printf '%s' "$LOGCAT_CONTENT"
            }
            """
        )
        harness += self.crash_check
        harness += '\n[ "$FAILURES" -eq 0 ]\n'
        environment = os.environ.copy()
        environment["LOGCAT_STATUS"] = str(logcat_status)
        environment["LOGCAT_CONTENT"] = logcat_content
        return run_bash(harness, str(self.outdir), env=environment)

    def test_logcat_read_failure_fails_instead_of_passing_an_empty_buffer(self) -> None:
        result = self.run_crash_check(1)
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn("RESULT|FAIL|crash-log|", output)
        self.assertIn("logcat fixture read failure", output)
        self.assertNotIn("RESULT|PASS|crash-log|", output)

    def test_a_readable_empty_crash_buffer_passes(self) -> None:
        result = self.run_crash_check(0)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|PASS|crash-log|", result.stdout)

    def test_a_reported_fatal_exception_still_fails(self) -> None:
        result = self.run_crash_check(0, "FATAL EXCEPTION: main\n")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|FAIL|crash-log|1 FATAL", result.stdout)


class BackupRuleSectionsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        self.script = scripts / NO_INTERNET.name
        shutil.copy2(NO_INTERNET, self.script)
        shutil.copy2(ROOT / "scripts" / "build-tools-pin.sh", scripts / "build-tools-pin.sh")
        manifest = self.root / "app" / "src" / "main" / "AndroidManifest.xml"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(
            '<manifest><application android:allowBackup="false" /></manifest>\n',
            encoding="utf-8",
        )
        self.apk = self.root / "fixture.apk"
        self.apk.write_bytes(b"fixture")
        self.rules = self.root / "rules-tree.txt"
        tools = self.root / "sdk" / "build-tools" / "fixture"
        tools.mkdir(parents=True)
        aapt2 = tools / "aapt2"
        aapt2.write_text(
            textwrap.dedent(
                """\
                #!/usr/bin/env python3
                import os
                from pathlib import Path
                import sys

                args = sys.argv[1:]
                if args[:2] == ["dump", "permissions"]:
                    raise SystemExit(0)
                if args[:2] == ["dump", "resources"]:
                    print("resource 0x7f120000 xml/data_extraction_rules")
                    print("    (file) res/xml/data_extraction_rules.xml")
                    raise SystemExit(0)
                if args[:2] == ["dump", "xmltree"]:
                    filename = args[args.index("--file") + 1]
                    if filename == "AndroidManifest.xml":
                        print("E: manifest (line=1)")
                        print("  E: application (line=2)")
                        print("    A: android:allowBackup(0x01010280)=false")
                        print("    A: android:dataExtractionRules(0x010105a0)=@0x7f120000")
                    else:
                        print(Path(os.environ["BACKUP_RULES_TREE"]).read_text(encoding="utf-8"), end="")
                    raise SystemExit(0)
                raise SystemExit(2)
                """
            ),
            encoding="utf-8",
        )
        aapt2.chmod(0o755)
        self.environment = os.environ.copy()
        self.environment["ANDROID_HOME"] = str(self.root / "sdk")
        self.environment["BACKUP_RULES_TREE"] = str(self.rules)

    def write_rules(self, cloud: list[str], transfer: list[str]) -> None:
        lines = ["E: data-extraction-rules (line=1)"]
        for section, domains in (("cloud-backup", cloud), ("device-transfer", transfer)):
            lines.append(f"  E: {section} (line=2)")
            for domain in domains:
                lines.append("    E: exclude (line=3)")
                lines.append(f'      A: domain="{domain}"')
        self.rules.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def run_check(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(self.script), str(self.apk)],
            cwd=self.root,
            env=self.environment,
            check=False,
            capture_output=True,
            text=True,
        )

    def test_complete_exclusions_in_each_section_pass(self) -> None:
        self.write_rules(list(BACKUP_DOMAINS), list(BACKUP_DOMAINS))
        result = self.run_check()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("backup closed as a whitelist", result.stdout)

    def test_an_incomplete_device_transfer_section_fails(self) -> None:
        transfer = list(BACKUP_DOMAINS)
        transfer.remove("sharedpref")
        self.write_rules(list(BACKUP_DOMAINS), transfer)
        result = self.run_check()
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn(
            "domain 'sharedpref' in <device-transfer> 0 time(s), expected 1", output
        )

    def test_exclusions_misassigned_between_sections_fail(self) -> None:
        cases = []
        cloud = list(BACKUP_DOMAINS)
        transfer = list(BACKUP_DOMAINS)
        transfer.remove("sharedpref")
        cloud.append("sharedpref")
        cases.append(("duplicated in cloud", cloud, transfer, "cloud-backup", "sharedpref", "2"))
        cloud = list(BACKUP_DOMAINS)
        transfer = list(BACKUP_DOMAINS)
        cloud.remove("database")
        transfer.append("database")
        cases.append(("missing from cloud", cloud, transfer, "cloud-backup", "database", "0"))
        for name, cloud, transfer, section, domain, count in cases:
            with self.subTest(name=name):
                self.write_rules(cloud, transfer)
                result = self.run_check()
                output = result.stdout + result.stderr
                self.assertNotEqual(0, result.returncode, output)
                self.assertIn(
                    f"domain '{domain}' in <{section}> {count} time(s), expected 1",
                    output,
                )


if __name__ == "__main__":
    unittest.main()
