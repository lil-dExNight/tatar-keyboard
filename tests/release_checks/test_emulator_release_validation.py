"""Host-side contract tests for the headless emulator release matrix."""

from __future__ import annotations

import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "emulator-release-validation.sh"
SMOKE_SCRIPT = ROOT / "scripts" / "emulator-smoke.sh"
DIGEST = "ab" * 32


def sdk_bounds() -> tuple[int, int]:
    text = (ROOT / "app" / "build.gradle").read_text(encoding="utf-8")
    minimum = re.search(r"minSdkVersion\s+(\d+)", text)
    target = re.search(r"targetSdkVersion\s+(\d+)", text)
    assert minimum and target
    return int(minimum.group(1)), int(target.group(1))


def build_tools_pin() -> str:
    text = (ROOT / "scripts" / "build-tools-pin.sh").read_text(encoding="utf-8")
    match = re.search(r'TT_BUILD_TOOLS_PIN="([^"]+)"', text)
    assert match
    return match.group(1)


def normalized(chunk: bytes | str | None) -> str:
    if chunk is None:
        return ""
    return chunk if isinstance(chunk, str) else chunk.decode("utf-8", "replace")


class EmulatorReleaseValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.outdir = Path(self.temporary_directory.name)
        self.minimum, self.target = sdk_bounds()

    def run_plan(self, *mappings: str) -> subprocess.CompletedProcess[str]:
        arguments = ["bash", str(SCRIPT), "--plan", "--outdir", str(self.outdir)]
        for mapping in mappings:
            arguments.extend(("--avd", mapping))
        environment = os.environ.copy()
        environment["ADB"] = "/nonexistent/adb"
        environment["EMULATOR"] = "/nonexistent/emulator"
        return subprocess.run(arguments, capture_output=True, text=True, env=environment, check=False)

    def complete_matrix(self) -> list[str]:
        return [f"{api}=tt_api{api}" for api in range(self.minimum, self.target + 1)]

    def test_a_complete_matrix_is_ready_but_physical_boundaries_block_signoff(self) -> None:
        result = self.run_plan(*self.complete_matrix())
        output = result.stdout + result.stderr
        self.assertEqual(1, result.returncode, output)
        self.assertIn("EMULATOR_PLAN|READY|", output)
        for boundary in ("direct-boot", "lock-screen", "talkback"):
            self.assertIn(f"RESULT|SKIP|physical.{boundary}|", output)
        self.assertIn("OVERALL|INCOMPLETE|physical_only=direct-boot,lock-screen,talkback", output)

    def test_a_gap_is_an_explicit_skip_and_not_a_ready_plan(self) -> None:
        mappings = self.complete_matrix()
        mappings.pop()
        result = self.run_plan(*mappings)
        output = result.stdout + result.stderr
        self.assertEqual(1, result.returncode, output)
        self.assertIn(f"RESULT|SKIP|api.{self.target}|no --avd", output)
        self.assertIn("EMULATOR_PLAN|INCOMPLETE|", output)

    def test_duplicate_api_mappings_are_rejected(self) -> None:
        result = self.run_plan(f"{self.minimum}=first", f"{self.minimum}=second")
        output = result.stdout + result.stderr
        self.assertEqual(2, result.returncode, output)
        self.assertIn(f"duplicate --avd mapping for API {self.minimum}", output)

    def test_run_path_has_signed_upgrade_backup_corruption_and_no_window(self) -> None:
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("-no-window", text)
        # Every install goes through install_apk, whose bounded, reconnecting
        # wrapper is what keeps a wedged adb stream from hanging the matrix.
        self.assertIn('install_apk "$CANDIDATE"', text)
        self.assertIn('install --no-incremental -r "$1"', text)
        self.assertIn("timeout 240", text)
        self.assertIn("wait_package_manager", text)
        self.assertIn("logcat -b all -d", text)
        self.assertIn("CURRENT_EVIDENCE_DIR", text)
        self.assertIn("firstInstallTime=preserved", text)
        self.assertIn("BackupRestoreDeviceTest", text)
        self.assertIn("PasswordFieldSwitchDeviceTest", text)
        self.assertIn('quarantine="$corrupt_file.quarantine"', text)
        self.assertIn("physical.talkback", text)

    def test_instrumentation_apks_are_badged_and_gated_on_the_candidate(self) -> None:
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('ANDROIDTEST_BADGE=$(badge "$ANDROIDTEST_APK" allow-unversioned)', text)
        self.assertIn("artifact.instrumentation-contract", text)
        self.assertIn('"$DEBUG_VN" = "$CANDIDATE_VN"', text)
        self.assertIn('"$TEST_PKG" = "$DEBUG_PKG.test"', text)
        self.assertIn('"$TEST_VC" -eq "$CANDIDATE_VC"', text)

    def test_smoke_leg_rejects_critical_suggestion_skips(self) -> None:
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('critical_smoke_skips "$directory/smoke/result.txt"', text)
        self.assertIn("critical suggestion checks skipped in smoke", text)
        match = re.search(r"(?ms)^critical_smoke_skips\(\) \{.*?^\}$", text)
        self.assertIsNotNone(match)
        self.assertIn("RESULT\\|SKIP\\|suggest-(tt|ru)-", match.group(0))

    def test_corruption_probe_selects_the_fixture_language_and_waits_for_debug_registration(self) -> None:
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('<string name="pref_current_subtype">tt_RU:tatar</string>', text)
        self.assertIn("debug package has no listed IME after 30 seconds", text)
        self.assertIn("debug IME did not show after 15 seconds", text)


class ArtifactFixture:
    """A fake build-tools tree plus four APK paths, all driven by stub binaries."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.sdk = root / "sdk"
        self.outdir = root / "out"
        self.tools = self.sdk / "build-tools" / build_tools_pin()
        self.tools.mkdir(parents=True)
        self.apks = {
            "candidate": root / "candidate.apk",
            "previous": root / "previous.apk",
            "debug": root / "debug.apk",
            "androidtest": root / "androidtest.apk",
        }
        for apk in self.apks.values():
            apk.write_bytes(b"")
        self.badging = {
            "candidate.apk": ("org.tatarkeyboard.ime", 57, "3.14.2"),
            "previous.apk": ("org.tatarkeyboard.ime", 56, "3.14.1"),
            "debug.apk": ("org.tatarkeyboard.ime.debug", 57, "3.14.2"),
            "androidtest.apk": ("org.tatarkeyboard.ime.debug.test", 57, "3.14.2"),
        }
        self.write_binaries()

    def write_binaries(self) -> None:
        aapt2 = ["#!/bin/bash", 'apk="${!#}"', 'case "$(basename "$apk")" in']
        for name, (package, version_code, version_name) in self.badging.items():
            aapt2.append(
                f"  {name}) echo \"package: name='{package}' versionCode='{version_code}' "
                f"versionName='{version_name}'\" ;;"
            )
        aapt2.extend(("  *) exit 1 ;;", "esac", ""))
        apksigner = """#!/bin/bash
echo "Signer #1 certificate DN: CN=Fixture"
echo "Signer #1 certificate SHA-256 digest: %s"
""" % DIGEST
        adb = """#!/bin/bash
if [ "$1" = "devices" ]; then
    echo "List of devices attached"
fi
exit 0
"""
        emulator = "#!/bin/bash\nexit 0\n"
        for name, body in (("aapt2", "\n".join(aapt2)), ("apksigner", apksigner)):
            path = self.tools / name
            path.write_text(body, encoding="utf-8")
            path.chmod(path.stat().st_mode | stat.S_IXUSR)
        for name, body in (("adb", adb), ("emulator", emulator)):
            path = self.root / name
            path.write_text(body, encoding="utf-8")
            path.chmod(path.stat().st_mode | stat.S_IXUSR)


class ArtifactContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.fixture = ArtifactFixture(Path(self.temporary_directory.name))
        self.minimum, self.target = sdk_bounds()

    def arguments(self) -> list[str]:
        apks = self.fixture.apks
        arguments = [
            "bash", str(SCRIPT),
            "--candidate", str(apks["candidate"]),
            "--previous", str(apks["previous"]),
            "--debug-apk", str(apks["debug"]),
            "--androidtest-apk", str(apks["androidtest"]),
            "--outdir", str(self.fixture.outdir),
        ]
        for api in range(self.minimum, self.target + 1):
            arguments.extend(("--avd", f"{api}=tt_api{api}"))
        return arguments

    def environment(self) -> dict[str, str]:
        environment = os.environ.copy()
        environment["ADB"] = str(self.fixture.root / "adb")
        environment["EMULATOR"] = str(self.fixture.root / "emulator")
        environment["ANDROID_HOME"] = str(self.fixture.sdk)
        environment["ANDROID_SDK_ROOT"] = str(self.fixture.sdk)
        return environment

    def run_script(self, timeout: int = 15) -> tuple[int | None, str]:
        try:
            completed = subprocess.run(
                self.arguments(), capture_output=True, text=True,
                env=self.environment(), timeout=timeout, check=False,
            )
            return completed.returncode, normalized(completed.stdout) + normalized(completed.stderr)
        except subprocess.TimeoutExpired as error:
            return None, normalized(error.stdout) + normalized(error.stderr)

    def test_compatible_instrumentation_apks_pass_the_artifact_contract(self) -> None:
        returncode, output = self.run_script()
        self.assertIsNone(returncode, output)
        self.assertIn("RESULT|PASS|artifact.upgrade-contract|", output)
        self.assertIn(
            "RESULT|PASS|artifact.instrumentation-contract|"
            "debug=org.tatarkeyboard.ime.debug/3.14.2/57 "
            "androidTest=org.tatarkeyboard.ime.debug.test/3.14.2/57",
            output,
        )

    def test_a_debug_apk_from_another_version_is_rejected(self) -> None:
        self.fixture.badging["debug.apk"] = ("org.tatarkeyboard.ime.debug", 56, "3.14.1")
        self.fixture.write_binaries()
        returncode, output = self.run_script()
        self.assertEqual(2, returncode, output)
        self.assertIn("ERROR: debug APK version", output)
        self.assertNotIn("RESULT|PASS|artifact.instrumentation-contract|", output)

    def test_a_debug_apk_outside_the_candidate_package_is_rejected(self) -> None:
        self.fixture.badging["debug.apk"] = ("org.otherkeyboard.ime.debug", 57, "3.14.2")
        self.fixture.badging["androidtest.apk"] = ("org.otherkeyboard.ime.debug.test", 57, "3.14.2")
        self.fixture.write_binaries()
        returncode, output = self.run_script()
        self.assertEqual(2, returncode, output)
        self.assertIn("ERROR: debug package org.otherkeyboard.ime.debug", output)

    def test_an_androidtest_apk_outside_the_debug_package_is_rejected(self) -> None:
        self.fixture.badging["androidtest.apk"] = ("org.tatarkeyboard.ime.test", 57, "3.14.2")
        self.fixture.write_binaries()
        returncode, output = self.run_script()
        self.assertEqual(2, returncode, output)
        self.assertIn("ERROR: androidTest package org.tatarkeyboard.ime.test", output)

    def test_an_unversioned_agp_androidtest_apk_passes_the_artifact_contract(self) -> None:
        self.fixture.badging["androidtest.apk"] = ("org.tatarkeyboard.ime.debug.test", "", "")
        self.fixture.write_binaries()
        returncode, output = self.run_script()
        self.assertIsNone(returncode, output)
        self.assertIn(
            "RESULT|PASS|artifact.instrumentation-contract|"
            "debug=org.tatarkeyboard.ime.debug/3.14.2/57 "
            "androidTest=org.tatarkeyboard.ime.debug.test/3.14.2/57 "
            "candidate=org.tatarkeyboard.ime/3.14.2/57 "
            "androidTestVersion=unspecified-by-AGP",
            output,
        )

    def test_an_androidtest_apk_from_another_version_is_rejected(self) -> None:
        self.fixture.badging["androidtest.apk"] = ("org.tatarkeyboard.ime.debug.test", 55, "3.14.0")
        self.fixture.write_binaries()
        returncode, output = self.run_script()
        self.assertEqual(2, returncode, output)
        self.assertIn("ERROR: androidTest version", output)

    def test_only_rejects_an_api_without_a_mapping(self) -> None:
        arguments = self.arguments()
        arguments.extend(("--only", "99"))
        environment = self.environment()
        result = subprocess.run(
            arguments, capture_output=True, text=True, env=environment, check=False
        )
        output = result.stdout + result.stderr
        self.assertEqual(2, result.returncode, output)
        self.assertIn("ERROR: --only 99 has no --avd 99=<name> mapping", output)


class LandscapeImeFrameTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.dump = Path(self.temporary_directory.name) / "dumpsys-window.txt"
        script = SMOKE_SCRIPT.read_text(encoding="utf-8")
        marker = 'ls_frame=$(python3 - "$ls_dump" <<\'PY\'\n'
        start = script.index(marker) + len(marker)
        end = script.index("\nPY\n)", start)
        self.parser = script[start:end]

    def frame(self, dump: str) -> str:
        self.dump.write_text(dump, encoding="utf-8")
        result = subprocess.run(
            ["python3", "-c", self.parser, str(self.dump)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result.stdout.strip()

    def test_api_30_uppercase_ime_inset_frame_is_parsed(self) -> None:
        frame = self.frame(
            "InsetsSource type=ITYPE_IME frame=[0,425][2148,1080] "
            "visibleFrame=[0,425][2148,1080] visible=true\n"
        )
        self.assertEqual("0 425 2148 1080", frame)

    def test_api_34_modern_ime_inset_frame_is_parsed(self) -> None:
        frame = self.frame(
            "InsetsSource id=3 type=ime frame=[0,315][2280,1080] "
            "visibleFrame=[0,315][2280,1080] visible=true flags=\n"
        )
        self.assertEqual("0 315 2280 1080", frame)

    def test_legacy_mcurrent_and_mdock_intersection_is_parsed(self) -> None:
        frame = self.frame(
            "mDock=[0,66][2148,1080]\n"
            "mCurrent=[0,66][2148,425]\n"
        )
        self.assertEqual("0 425 2148 1080", frame)

    def test_api_24_parenthesized_mcur_and_mdock_intersection_is_parsed(self) -> None:
        # Verbatim API 24-27 landscape dump lines: these images have no InsetsSource
        # and their InputMethod window block carries no mFrame, so only this fallback
        # can locate the keyboard. The InputMethod window must not be mistaken for a
        # frame either: it fills the display.
        frame = self.frame(
            "  Window #3 Window{3bc2d2 u0 InputMethod}:\n"
            "    mDisplayId=0 stackId=0 mSession=Session{f946d96 3732:u0a10084}\n"
            "    mAttrs=WM.LayoutParams{(0,0)(fillxfill) gr=#50 sim=#20 ty=2011}\n"
            "    Requested w=2148 h=1014 mLayoutSeq=350\n"
            "    mHasSurface=true mShownPosition=[0,66] isReadyForDisplay()=true\n"
            "    mStable=(0,66)-(2148,1080)\n"
            "    mCur=(0,66)-(2148,425)\n"
            "    mContent=(0,66)-(2148,425)\n"
            "    mDock=(0,66)-(2148,1080)\n"
        )
        self.assertEqual("0 425 2148 1080", frame)











class SmokeFollowupRetryTest(unittest.TestCase):
    def test_empty_band_is_retried_without_accepting_a_wrong_commit(self) -> None:
        text = SMOKE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("for followup_attempt in 1 2 3", text)
        unchanged = 'elif [ "$wf_again" = "$wf_after" ]; then\n            continue'
        self.assertIn(unchanged, text)
        self.assertIn('elif [ "$w3" = "башка" ]', text)
        self.assertIn("follow-up cell 1 committed '$w3'", text)
        self.assertIn("strip stayed empty after $followup_attempt attempts", text)


class InstallApkTest(unittest.TestCase):
    """Behavioral fixtures for install_apk and wait_package_manager."""

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.bindir = self.root / "bin"
        self.bindir.mkdir()
        text = SCRIPT.read_text(encoding="utf-8")
        self.wait_function = re.search(
            r"(?ms)^wait_package_manager\(\) \{.*?^\}$", text
        ).group(0)
        self.install_function = re.search(
            r"(?ms)^install_apk\(\) \{.*?^\}$", text
        ).group(0)
        self.apk = self.root / "fixture.apk"
        self.apk.write_bytes(b"fixture")
        self.adb = self.bindir / "adb"
        self.calls = self.root / "adb-calls.txt"

    def write_adb(self, body: str) -> None:
        self.adb.write_text(
            "#!/bin/bash\n"
            'echo "$*" >>"$ADB_CALLS"\n'
            f'CALL_FILE="{self.calls}"\n'
            + body
            + "\n",
            encoding="utf-8",
        )
        self.adb.chmod(0o755)

    def run_install(self, behavior: str) -> subprocess.CompletedProcess[str]:
        self.calls.write_text("", encoding="utf-8")
        harness = textwrap.dedent(
            """
            set -uo pipefail
            log() { :; }
            sleep() { :; }
            SERIAL=emulator-5554
            ADB="$1"
            SHELL() { "$ADB" -s "$SERIAL" shell "$@"; }
            """
        )
        harness += self.wait_function + "\n" + self.install_function + "\n"
        harness += 'install_apk "$2" "$3/install.log"\n'
        environment = os.environ.copy()
        environment["ADB_BEHAVIOR"] = behavior
        environment["ADB_CALLS"] = str(self.calls)
        return subprocess.run(
            ["bash", "-c", harness, "bash", str(self.adb), str(self.apk), str(self.root)],
            capture_output=True,
            text=True,
            env=environment,
            check=False,
        )

    def install_stub(self) -> str:
        return textwrap.dedent(
            """
            pm_ready() { [ "$ADB_BEHAVIOR" != never-ready ]; }
            case "$*" in
                "-s emulator-5554 shell service check package")
                    pm_ready && echo "Service package: found" || return 1 ;;
                "-s emulator-5554 shell pm path android")
                    pm_ready && echo "package:/system/framework/framework-res.apk" || return 1 ;;
                "-s emulator-5554 reconnect") : ;;
                "-s emulator-5554 install"*)
                    case "$ADB_BEHAVIOR" in
                        ok|never-ready) echo "Success" ;;
                        wedge-then-ok)
                            if grep -q wedged "$CALL_FILE" 2>/dev/null; then
                                echo "Success"
                            else
                                echo "Failure [INSTALL_FAILED_FIXTURE]" >&2
                                exit 1
                            fi
                            ;;
                        fail) echo "Failure [INSTALL_FAILED_FIXTURE]" >&2; exit 1 ;;
                    esac
                    ;;
                "-s emulator-5554 push"*) : ;;
                "-s emulator-5554 shell pm install"*) echo "Success" ;;
                "-s emulator-5554 shell rm -f"*) : ;;
            esac
            """
        ).strip()

    def test_a_successful_install_is_non_incremental_without_a_retry(self) -> None:
        self.write_adb(self.install_stub())
        result = self.run_install("ok")
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        calls = self.calls.read_text(encoding="utf-8")
        self.assertIn("install --no-incremental -r", calls)
        self.assertEqual(1, calls.count("install --no-incremental"))
        self.assertNotIn("reconnect", calls)

    def test_a_wedged_install_reconnects_and_retries_to_success(self) -> None:
        self.write_adb(self.install_stub())
        result = self.run_install("wedge-then-ok")
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        calls = self.calls.read_text(encoding="utf-8")
        self.assertIn("reconnect", calls)
        self.assertEqual(2, calls.count("install --no-incremental"))

    def test_a_failing_install_falls_back_to_the_push_and_pm_install_path(self) -> None:
        self.write_adb(self.install_stub())
        result = self.run_install("fail")
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        calls = self.calls.read_text(encoding="utf-8")
        self.assertIn("push", calls)
        self.assertIn("shell pm install -r /data/local/tmp/fixture.apk", calls)

    def test_a_package_manager_that_never_answers_fails_the_install(self) -> None:
        self.write_adb(self.install_stub())
        result = self.run_install("never-ready")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("install --no-incremental", self.calls.read_text(encoding="utf-8"))


class SmokeCriticalSkipTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.result = Path(self.temporary_directory.name) / "result.txt"
        text = SCRIPT.read_text(encoding="utf-8")
        match = re.search(r"(?ms)^critical_smoke_skips\(\) \{.*?^\}$", text)
        assert match
        self.function = match.group(0)

    def evaluate(self, lines: list[str]) -> subprocess.CompletedProcess[str]:
        self.result.write_text("\n".join(lines) + "\n", encoding="utf-8")
        harness = "set -uo pipefail\n" + self.function + \
            f'\ncritical_smoke_skips "{self.result}"\n'
        return subprocess.run(["bash", "-c", harness], capture_output=True, text=True, check=False)

    def test_passing_suggestion_probes_and_documented_skips_are_accepted(self) -> None:
        result = self.evaluate([
            "RESULT|PASS|suggest-tt-мин|полоса подсказок оживилась: 5500 px",
            "RESULT|PASS|suggest-ru-при|полоса подсказок оживилась: 4100 px",
            "RESULT|SKIP|suggest-en-hi|у en нет словаря в ассетах",
            "RESULT|SKIP|suggestions-enabled|package not debuggable",
            "RESULT|SKIP|learned-emoji-tt|prefs cannot be seeded via run-as",
            "RESULT|PASS|crash-log|crash-буфер пуст",
        ])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("", result.stdout.strip())

    def test_a_skipped_tt_suggestion_probe_is_reported(self) -> None:
        result = self.evaluate([
            "RESULT|PASS|boot|serial=emulator-5554",
            "RESULT|SKIP|suggest-tt-мин|нет ImageMagick для пиксельной дельты полосы",
            "RESULT|SKIP|suggest-en-hi|у en нет словаря в ассетах",
        ])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|SKIP|suggest-tt-мин|", result.stdout)
        self.assertNotIn("suggest-en-hi", result.stdout)

    def test_a_skipped_ru_suggestion_probe_is_reported(self) -> None:
        result = self.evaluate([
            "RESULT|SKIP|suggest-ru-при|нет ImageMagick для пиксельной дельты полосы",
        ])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|SKIP|suggest-ru-при|", result.stdout)


if __name__ == "__main__":
    unittest.main()
