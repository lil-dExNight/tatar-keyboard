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
EMULATOR_RELEASE = ROOT / "scripts" / "emulator-release-validation.sh"
INSTRUMENTATION_VERDICT = ROOT / "scripts" / "instrumentation-verdict.sh"
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
        rf"(?ms)^{re.escape(name)}\(\) \{{[^\n]*\n.*?^\}}$", path.read_text(encoding="utf-8")
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


class EmulatorInstrumentationTest(unittest.TestCase):
    """Release validation reports every instrumentation failure marker as FAIL."""

    SUCCESS_LOG = (
        "INSTRUMENTATION_STATUS: class=fixture.Class\n"
        "INSTRUMENTATION_STATUS: test=fieldSwitch\n"
        "INSTRUMENTATION_STATUS_CODE: 1\n"
        "INSTRUMENTATION_STATUS: class=fixture.Class\n"
        "INSTRUMENTATION_STATUS_CODE: 0\n"
        "OK (1 test)\n"
        "INSTRUMENTATION_CODE: -1\n"
    )

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.directory = Path(self.temporary_directory.name)
        self.fixture = self.directory / "fixture-instrumentation.txt"
        self.instrument = shell_function(EMULATOR_RELEASE, "instrument")

    def run_instrument(
        self, log: str, return_code: int = 0
    ) -> subprocess.CompletedProcess[str]:
        self.fixture.write_text(log, encoding="utf-8")
        harness = textwrap.dedent(
            """
            set -uo pipefail
            source "$INSTRUMENTATION_VERDICT"
            DEBUG_PKG=fixture.package
            RESULTS=$1/result.txt
            : > "$RESULTS"
            FAILURES=0
            result() {
                local line="RESULT|$1|$2|$3"
                echo "$line"
                echo "$line" >> "$RESULTS"
                [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
            }
            SHELL() {
                cat "$INSTRUMENT_FIXTURE"
                return "$INSTRUMENT_RC"
            }
            """
        )
        harness += self.instrument + "\n"
        harness += textwrap.dedent(
            """
            instrument "$1" field-switch.log fixture.Class api.29.field-switch
            [ "$FAILURES" -eq 0 ]
            """
        )
        environment = os.environ.copy()
        environment["INSTRUMENTATION_VERDICT"] = str(INSTRUMENTATION_VERDICT)
        environment["INSTRUMENT_FIXTURE"] = str(self.fixture)
        environment["INSTRUMENT_RC"] = str(return_code)
        return run_bash(harness, str(self.directory), env=environment)

    def assert_failed(self, result: subprocess.CompletedProcess[str]) -> None:
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn("RESULT|FAIL|api.29.field-switch|", output)
        self.assertNotIn("RESULT|PASS|", output)

    def test_a_successful_run_passes(self) -> None:
        result = self.run_instrument(self.SUCCESS_LOG)
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn("RESULT|PASS|api.29.field-switch|", output)
        self.assertNotIn("RESULT|FAIL|", output)

    def test_a_zero_failure_summary_still_passes(self) -> None:
        log = self.SUCCESS_LOG.replace(
            "OK (1 test)", "Tests run: 1, Failures: 0, Errors: 0"
        )
        result = self.run_instrument(log)
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn("RESULT|PASS|api.29.field-switch|", output)

    def test_a_failed_test_is_not_hidden_by_the_final_code(self) -> None:
        log = self.SUCCESS_LOG.replace(
            "INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: -2"
        ).replace(
            "OK (1 test)",
            "FAILURES!!!\nTests run: 1, Failures: 1, Errors: 0",
        )
        self.assert_failed(self.run_instrument(log))

    def test_each_failure_marker_fails(self) -> None:
        cases = {
            "test status code": self.SUCCESS_LOG.replace(
                "INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: -2"
            ),
            "failure banner": self.SUCCESS_LOG.replace(
                "OK (1 test)",
                "FAILURES!!!\nTests run: 1, Failures: 1, Errors: 0",
            ),
            "failure count": self.SUCCESS_LOG.replace(
                "OK (1 test)", "Tests run: 1, Failures: 1, Errors: 0"
            ),
            "missing final code": self.SUCCESS_LOG.replace(
                "INSTRUMENTATION_CODE: -1\n", ""
            ),
        }
        for name, log in cases.items():
            with self.subTest(name=name):
                self.assert_failed(self.run_instrument(log))

    def test_a_nonzero_runner_exit_fails(self) -> None:
        self.assert_failed(self.run_instrument(self.SUCCESS_LOG, return_code=1))


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


class NightModeSetterTest(unittest.TestCase):
    def test_legacy_night_mode_uses_the_binder_transaction(self) -> None:
        script = EMULATOR_SMOKE.read_text(encoding="utf-8")
        match = re.search(
            r"(?ms)^\s{4}set_night_mode\(\) \{\n.*?^\s{4}\}$", script
        )
        self.assertIsNotNone(match)
        setter = match.group(0)
        harness = textwrap.dedent(
            """
            set -uo pipefail
            nm_root=0
            nm_legacy=1
            calls=()
            A() { calls+=("A:$*"); }
            SHELL() { calls+=("SHELL:$*"); }
            sleep() { calls+=("sleep:$1"); }
            """
        )
        harness += setter + "\nset_night_mode 2\n"
        harness += "printf '%s\\n' \"${calls[@]}\"\n"
        result = run_bash(harness)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        calls = result.stdout.splitlines()
        self.assertIn("SHELL:service call uimode 4 i32 2", calls)
        self.assertNotIn("SHELL:cmd uimode night 2", calls)

    def test_locked_night_mode_uses_a_rooted_cmd_fallback(self) -> None:
        script = EMULATOR_SMOKE.read_text(encoding="utf-8")
        match = re.search(
            r"(?ms)^\s{4}set_night_mode\(\) \{\n.*?^\s{4}\}$", script
        )
        self.assertIsNotNone(match)
        setter = match.group(0)
        harness = textwrap.dedent(
            """
            set -uo pipefail
            nm_root=1
            nm_legacy=0
            calls=()
            A() { calls+=("A:$*"); }
            SHELL() { calls+=("SHELL:$*"); }
            sleep() { calls+=("sleep:$1"); }
            """
        )
        harness += setter + "\nset_night_mode 2\n"
        harness += "printf '%s\\n' \"${calls[@]}\"\n"
        result = run_bash(harness)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        calls = result.stdout.splitlines()
        self.assertIn("A:root", calls)
        self.assertIn("A:wait-for-device", calls)
        self.assertIn("SHELL:cmd uimode night 2", calls)
        self.assertNotIn("SHELL:service call uimode 4 i32 2", calls)

    def test_locked_night_mode_is_unrooted_after_restore(self) -> None:
        script = EMULATOR_SMOKE.read_text(encoding="utf-8")
        self.assertIn("A unroot", script)


class SmokeSectionTest(unittest.TestCase):
    """Run one real top-level smoke-script block inside an adb fixture."""

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.outdir = Path(self.temporary_directory.name)
        self.script = EMULATOR_SMOKE.read_text(encoding="utf-8")

    def section(self, start: str, end: str) -> str:
        self.assertIn(start, self.script)
        return self.script.split(start, 1)[1].split(end, 1)[0]

    def run_section(self, source: str, environment: dict[str, str]) -> subprocess.CompletedProcess[str]:
        harness = textwrap.dedent(
            """
            set -euo pipefail
            OUTDIR=$1
            RESULTS=$OUTDIR/result.txt
            APK=$OUTDIR/fixture.apk
            PKG=fixture.package
            SETUP_ACTIVITY=rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity
            SMOKE_SDK=${ADB_API:-36}
            : > "$RESULTS"
            : > "$APK"
            FAILURES=0
            calls=()
            result() {
                local line="RESULT|$1|$2|$3"
                echo "$line"
                echo "$line" >> "$RESULTS"
                [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
            }
            sleep() { calls+=("sleep:$1"); }
            trap 'printf "%s\n" "${calls[@]}" >&2' EXIT
            A() {
                calls+=("A:$*")
                case "$*" in
                    "install"*)
                        case "${ADB_INSTALL_BEHAVIOR:-}" in
                            ok) return 0 ;;
                            fail) echo "Failure [fixture]" >&2; return 1 ;;
                            wedge-then-ok)
                                if [ -f "$OUTDIR/install-wedged" ]; then
                                    return 0
                                fi
                                : > "$OUTDIR/install-wedged"
                                echo "Failure [fixture]" >&2
                                return 1
                                ;;
                        esac
                        ;;
                    "push"*)
                        [ "${ADB_PUSH_BEHAVIOR:-ok}" = ok ] || return 1
                        ;;
                    shell*) fixture_shell "$@" ;;
                esac
            }
            SHELL() { A shell "$@"; }
            current_focus() { fixture_focus; }
            fixture_focus() {
                case "${ADB_FOCUS:-}" in
                    ours) printf 'fixture.package/%s\n' "$SETUP_ACTIVITY" ;;
                    *) printf 'com.android.settings/.Settings\n' ;;
                esac
            }
            fixture_shell() {
                # "$1" is the literal word shell; the real command starts at "$2".
                shift
                case "$*" in
                    "service check package"|"service check activity")
                        [ "${ADB_PM_READY:-0}" = 1 ] && echo "Service package: found" || return 1
                        ;;
                    "pm path android")
                        [ "${ADB_PM_READY:-0}" = 1 ] && echo "package:/system/framework/framework-res.apk" || return 1
                        ;;
                    "pm install"*)
                        [ "${ADB_PM_INSTALL_BEHAVIOR:-ok}" = ok ] || return 1
                        ;;
                    "rm -f"*) : ;;
                    "cmd package resolve-activity"*)
                        case "${ADB_RESOLVE:-}" in
                            1) printf 'fixture.package/%s\n' "$SETUP_ACTIVITY" ;;
                            late)
                                if [ -f "$OUTDIR/resolve-missed" ]; then
                                    printf 'fixture.package/%s\n' "$SETUP_ACTIVITY"
                                else
                                    : > "$OUTDIR/resolve-missed"
                                    printf 'No activity found\n'
                                fi
                                ;;
                            *) printf 'No activity found\n' ;;
                        esac
                        ;;
                esac
            }
            """
        )
        harness += "\n" + source + "\n"
        env = os.environ.copy()
        env.update(environment)
        return run_bash(harness, str(self.outdir), env=env)


class SmokeInstallTest(SmokeSectionTest):
    """Package-service readiness plus the non-incremental install retry."""

    def setUp(self) -> None:
        super().setUp()
        self.install = self.section(
            "# ── install and select the IME",
            "# On slow or old images (API 30)",
        ).split("\n", 1)[1]

    def test_a_ready_package_manager_installs_non_incrementally(self) -> None:
        result = self.run_section(
            self.install,
            {"ADB_INSTALL_BEHAVIOR": "ok", "ADB_PM_READY": "1", "ADB_RESOLVE": "1"},
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|PASS|install|", result.stdout)
        self.assertIn("--no-incremental", result.stderr)
        self.assertNotIn("A:reconnect", result.stderr)
        self.assertNotIn("package manager not ready", result.stdout)

    def test_a_wedged_install_reconnects_and_retries_until_it_succeeds(self) -> None:
        result = self.run_section(
            self.install,
            {"ADB_INSTALL_BEHAVIOR": "wedge-then-ok", "ADB_PM_READY": "1", "ADB_RESOLVE": "1"},
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|PASS|install|", result.stdout)
        self.assertIn("A:reconnect", result.stderr)

    def test_an_install_that_never_succeeds_fails_the_leg(self) -> None:
        result = self.run_section(
            self.install,
            {"ADB_INSTALL_BEHAVIOR": "fail", "ADB_PM_READY": "1", "ADB_RESOLVE": "1"},
        )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|FAIL|install|", result.stdout)
        self.assertNotIn("RESULT|PASS|install|", result.stdout)

    def test_an_unanswered_package_manager_fails_before_any_install(self) -> None:
        result = self.run_section(
            self.install,
            {"ADB_INSTALL_BEHAVIOR": "ok", "ADB_PM_READY": "0", "ADB_RESOLVE": "1"},
        )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("package manager not ready after 120 seconds", result.stdout)
        self.assertNotIn("A:install", result.stderr)

    def test_a_transiently_missing_activity_is_re_resolved_without_reinstalling(self) -> None:
        result = self.run_section(
            self.install,
            {"ADB_INSTALL_BEHAVIOR": "ok", "ADB_PM_READY": "1", "ADB_RESOLVE": "late"},
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|PASS|install|", result.stdout)
        self.assertNotIn("package half-registered", result.stdout)
        self.assertEqual(1, result.stderr.count("A:install"))

    def test_an_unresolvable_activity_reinstalls_once_and_fails_the_leg(self) -> None:
        result = self.run_section(
            self.install,
            {"ADB_INSTALL_BEHAVIOR": "ok", "ADB_PM_READY": "1", "ADB_RESOLVE": "never"},
        )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("package half-registered after PM restart", result.stdout)
        # The last-ditch reinstall must fire once on top of the initial install.
        self.assertEqual(2, result.stderr.count("A:install"))
        self.assertIn("--no-incremental", result.stderr)

    def test_api37_uses_push_and_pm_install_without_the_stream(self) -> None:
        result = self.run_section(
            self.install,
            {"ADB_API": "37", "ADB_PM_READY": "1", "ADB_RESOLVE": "1"},
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|PASS|install|", result.stdout)
        self.assertIn("A:push", result.stderr)
        self.assertIn("A:shell pm install -r /data/local/tmp/fixture.apk", result.stderr)
        self.assertNotIn("A:install", result.stderr)

    def test_api37_install_failures_are_not_suppressed(self) -> None:
        result = self.run_section(
            self.install,
            {
                "ADB_API": "37",
                "ADB_PM_READY": "1",
                "ADB_PM_INSTALL_BEHAVIOR": "fail",
                "ADB_RESOLVE": "1",
            },
        )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|FAIL|install|", result.stdout)
        self.assertNotIn("RESULT|PASS|install|", result.stdout)
        self.assertNotIn("A:install", result.stderr)

    def test_api37_install_retry_is_bounded(self) -> None:
        result = self.run_section(
            self.install,
            {
                "ADB_API": "37",
                "ADB_PM_READY": "1",
                "ADB_PUSH_BEHAVIOR": "fail",
                "ADB_RESOLVE": "1",
            },
        )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|FAIL|install|", result.stdout)
        self.assertEqual(3, result.stderr.count("A:push"))
        self.assertNotIn("A:install", result.stderr)


class SmokeScreenshotTest(unittest.TestCase):
    """The API37 screenshot workaround keeps the other APIs on screencap."""

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.outdir = Path(self.temporary_directory.name)
        self.shot = shell_function(EMULATOR_SMOKE, "SHOT")

    def run_shot(self, api: str, host_capture: str = "ok") -> subprocess.CompletedProcess[str]:
        harness = textwrap.dedent(
            """
            set -euo pipefail
            OUTDIR=$1
            SMOKE_SDK=$2
            HOST_CAPTURE=$3
            calls=()
            trap 'printf "%s\n" "${calls[@]}" >&2' EXIT
            log() { echo "smoke: $*"; }
            A() {
                calls+=("A:$*")
                case "$*" in
                    "exec-out screencap -p") printf 'guest-png' ;;
                    "emu screenrecord screenshot "*)
                        [ "$HOST_CAPTURE" = ok ] || return 1
                        printf 'host-png' > "$4/screenshot.png"
                        ;;
                esac
            }
            """
        )
        harness += "\n" + self.shot + "\nSHOT fixture.png\n"
        return run_bash(harness, str(self.outdir), api, host_capture)

    def test_a_non37_api_still_uses_guest_screencap(self) -> None:
        result = self.run_shot("36")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("guest-png", (self.outdir / "fixture.png").read_text(encoding="utf-8"))
        self.assertIn("A:exec-out screencap -p", result.stderr)
        self.assertNotIn("screenrecord screenshot", result.stderr)

    def test_api37_uses_the_emulator_host_screenshot(self) -> None:
        result = self.run_shot("37")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("host-png", (self.outdir / "fixture.png").read_text(encoding="utf-8"))
        self.assertIn("A:emu screenrecord screenshot", result.stderr)
        self.assertNotIn("exec-out screencap", result.stderr)

    def test_api37_a_failed_host_capture_stays_an_empty_image(self) -> None:
        result = self.run_shot("37", host_capture="fail")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(b"", (self.outdir / "fixture.png").read_bytes())
        self.assertIn("screenshot fixture.png failed", result.stdout)
        self.assertNotIn("exec-out screencap", result.stderr)


class SmokeFocusRetryTest(SmokeSectionTest):
    """The setup-activity start is retried and bounded by the focus verdict."""

    def setUp(self) -> None:
        super().setUp()
        self.focus = self.section(
            "# SetupActivity must get focus;",
            "# Tap the center of the try-it field",
        )
        self.focus = self.focus[self.focus.index("for _ in $(seq 1 30)") :]

    def test_a_focused_setup_activity_passes(self) -> None:
        result = self.run_section(self.focus, {"ADB_FOCUS": "ours"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|PASS|setup-activity|", result.stdout)
        self.assertEqual(1, result.stderr.count("am start"))

    def test_an_unfocused_setup_activity_fails_after_a_bounded_retry(self) -> None:
        result = self.run_section(self.focus, {"ADB_FOCUS": "other"})
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|FAIL|setup-activity|", result.stdout)
        # Exactly three `am start` attempts: one failing start must not end the
        # run, but the loop must stay bounded instead of polling forever.
        self.assertEqual(3, result.stderr.count("am start"))


class SmokeProbeCleanupTest(unittest.TestCase):
    """A layout probe is not reported until its character is erased."""

    def run_probe(self, reads: list[str]) -> subprocess.CompletedProcess[str]:
        harness = textwrap.dedent(
            """
            set -euo pipefail
            PROBE_KEY="0.045,0.665"
            queue_file=$(mktemp)
            printf '%s\n' "$@" > "$queue_file"
            calls=()
            trap 'printf "%s\\n" "${calls[@]}" >&2; rm -f "$queue_file"' EXIT
            sleep() { :; }
            TAPF() { :; }
            SHELL() { calls+=("$*"); }
            clear_tryit() { calls+=("clear_tryit:$1"); }
            field_text() {
                if [ "$(wc -l < "$queue_file")" -gt 1 ]; then
                    head -1 "$queue_file"
                    tail -n +2 "$queue_file" > "$queue_file.tmp"
                    mv "$queue_file.tmp" "$queue_file"
                else
                    cat "$queue_file"
                fi
            }
            """
        )
        harness += shell_function(EMULATOR_SMOKE, "probe_layout")
        harness += "\nprobe_layout\n"
        return run_bash(harness, *reads)

    def test_a_late_delete_is_confirmed_without_a_resent_keyevent(self) -> None:
        # The first DEL lands one poll late: no re-send is needed.
        result = self.run_probe(["abc", "abcq", "abcq", "abc"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("qwerty", result.stdout.strip())
        self.assertEqual(1, result.stderr.count("input keyevent KEYCODE_DEL"))

    def test_a_swallowed_delete_is_resent_only_while_the_field_is_unchanged(self) -> None:
        # The field shows the post-probe text for four polls, so the DEL is
        # provably swallowed and is re-sent once; the next read is clean.
        result = self.run_probe(["abc", "abcq", "abcq", "abcq", "abcq", "abcq", "abc"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("qwerty", result.stdout.strip())
        self.assertEqual(2, result.stderr.count("input keyevent KEYCODE_DEL"))

    def test_an_uncleaned_probe_is_never_reported_as_the_layout(self) -> None:
        result = self.run_probe(["abc", "abcq"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("", result.stdout.strip())
        self.assertEqual(4, result.stderr.count("input keyevent KEYCODE_DEL"))
        # The residue is not left as the next probe's baseline: the probe
        # restores the field to a known byte-empty state instead.
        self.assertIn("clear_tryit:probe-qwerty-restore", result.stderr)

    def test_an_over_deleted_probe_reports_no_layout_and_stops(self) -> None:
        # The DEL over-deleted (the field is shorter than the pre-probe text):
        # the probe must not report a layout and must not send more DELs.
        result = self.run_probe(["abc", "abcq", "ab"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("", result.stdout.strip())
        self.assertEqual(1, result.stderr.count("input keyevent KEYCODE_DEL"))
        self.assertIn("clear_tryit:probe-qwerty-restore", result.stderr)


class SmokeLayoutPollTest(unittest.TestCase):
    """The release layout probe waits for two observations of the target."""

    def run_poll(self, sequence: str) -> list[str]:
        harness = textwrap.dedent(
            """
            set -euo pipefail
            probe_index_file=$(mktemp)
            echo 0 > "$probe_index_file"
            probe_layout() {
                probe_index=$(( $(cat "$probe_index_file") + 1 ))
                echo "$probe_index" > "$probe_index_file"
                case "$PROBE_SEQUENCE:$probe_index" in
                    late:1|late:2) echo qwerty ;;
                    last:1|last:2|last:3|last:4|last:5) echo qwerty ;;
                    never:*) echo qwerty ;;
                    *) echo tatar ;;
                esac
            }
            sleep() { :; }
            """
        )
        harness += shell_function(EMULATOR_SMOKE, "wait_for_layout")
        harness += '\nwait_for_layout tatar\nprintf \'probes=%s\\n\' "$(cat "$probe_index_file")"\nrm -f "$probe_index_file"\n'
        environment = os.environ.copy()
        environment["PROBE_SEQUENCE"] = sequence
        result = run_bash(harness, env=environment)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result.stdout.splitlines()

    def test_a_stale_outgoing_layout_is_polled_until_the_target_is_confirmed(self) -> None:
        self.assertEqual(["tatar", "probes=4"], self.run_poll("late")[-2:])

    def test_a_never_arriving_layout_has_a_bounded_poll(self) -> None:
        self.assertEqual(["qwerty", "probes=6"], self.run_poll("never")[-2:])

    def test_one_target_answer_on_the_last_poll_is_not_a_confirmation(self) -> None:
        self.assertEqual(["", "probes=6"], self.run_poll("last")[-2:])


class QueueHarnessTest(unittest.TestCase):
    """A field_text stub that pops scripted reads (the last read repeats)."""

    def queue_harness(self, reads: list[str], body: str, extra: str = "", env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        harness = textwrap.dedent(
            """
            set -euo pipefail
            OUTDIR=$1
            shift
            queue_file=$OUTDIR/queue
            printf '%s\n' "$@" > "$queue_file"
            sleep() { :; }
            field_text() {
                if [ "$(wc -l < "$queue_file")" -gt 1 ]; then
                    head -1 "$queue_file"
                    tail -n +2 "$queue_file" > "$queue_file.tmp"
                    mv "$queue_file.tmp" "$queue_file"
                else
                    cat "$queue_file"
                fi
            }
            """
        )
        harness += textwrap.dedent(extra)
        harness += "\n" + body
        return run_bash(harness, str(self.outdir), *reads, env=env)

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.outdir = Path(self.temporary_directory.name)


class SmokeNextWordRetryTest(QueueHarnessTest):
    """The NEXT_WORD tap waits for the pinned band and never hides a wrong word."""

    HARNESS = """
        SPACE="0.55,0.9075"
        STRIP_CELL1="0.5000,0.5954"
        HAVE_MAGICK=1
        BAND_DIFF_MAX=200
        BAND_REFS=$OUTDIR/refs
        LAST_FIELD_TEXT=""
        cell_taps=0
        tt_word_coords() { echo coords; }
        type_word() { :; }
        SHOT() { :; }
        SHELL() { echo 28; }
        TAPF() {
            [ "$1,$2" = "$STRIP_CELL1" ] && cell_taps=$((cell_taps + 1))
            return 0
        }
        band_diff() {
            case "$BAND_MODE" in
                match) echo 0 ;;
                *) echo 5000 ;;
            esac
        }
        """

    def run_probe(self, mode: str, reads: list[str]) -> subprocess.CompletedProcess[str]:
        body = "".join(
            [
                shell_function(EMULATOR_SMOKE, "confirm_space") + "\n",
                shell_function(EMULATOR_SMOKE, "band_ref") + "\n",
                shell_function(EMULATOR_SMOKE, "wait_band") + "\n",
                shell_function(EMULATOR_SMOKE, "type_tt_and_tap_cell2") + "\n",
                'type_tt_and_tap_cell2 татар tatar "$STRIP_CELL1"\n',
                "printf 'taps=%s\\n' \"$cell_taps\"\n",
            ]
        )
        if mode != "missing":
            ref = self.outdir / "refs" / "28" / "nextword-tatar.png"
            ref.parent.mkdir(parents=True, exist_ok=True)
            ref.write_bytes(b"reference")
        environment = os.environ.copy()
        environment["BAND_MODE"] = mode
        return self.queue_harness(reads, body, self.HARNESS, env=environment)

    def test_the_pinned_band_is_awaited_before_the_tap(self) -> None:
        result = self.run_probe("match", ["татар ", "татар ", "татар телен "])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("татар телен \tband=matched\ntaps=1", result.stdout)

    def test_a_band_that_never_matches_the_reference_is_never_tapped(self) -> None:
        # The fallback band stays up for the whole bounded wait: tapping it would
        # commit the fallback word, so the probe reports the timeout instead.
        result = self.run_probe("never", ["татар ", "татар "])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("татар \tтатар \tband=timeout\ntaps=0", result.stdout)

    def test_no_reference_for_the_image_keeps_the_bounded_tap_retry(self) -> None:
        result = self.run_probe(
            "missing",
            ["татар ", "татар ", "татар ", "татар ", "татар ", "татар ", "татар телен "],
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("татар телен \tband=unverified\ntaps=2", result.stdout)

    def test_a_late_space_commit_is_confirmed_before_the_band_wait(self) -> None:
        result = self.run_probe("match", ["татар", "татар", "татар ", "татар телен "])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("татар телен \tband=matched\ntaps=1", result.stdout)

    def test_a_space_that_never_commits_reports_no_band_and_no_tap(self) -> None:
        result = self.run_probe("match", ["татар"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("татар\tтатар\tband=space\ntaps=0", result.stdout)

    def test_a_wrong_committed_word_is_returned_without_retrying(self) -> None:
        result = self.run_probe("match", ["татар ", "татар ", "татар белән "])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("татар белән \tband=matched\ntaps=1", result.stdout)

    def test_a_band_that_never_commits_has_a_bounded_retry(self) -> None:
        result = self.run_probe("match", ["татар ", "татар "])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(result.stdout.endswith("taps=3\n"), result.stdout)


class SmokeSpaceConfirmTest(QueueHarnessTest):
    """A space tap is retried only while the field is unchanged."""

    HARNESS = """
        SPACE="0.55,0.9075"
        RESULTS=$OUTDIR/result.txt
        : > "$RESULTS"
        FAILURES=0
        space_taps=0
        LAST_FIELD_TEXT=""
        result() {
            local line="RESULT|$1|$2|$3"
            echo "$line"
            echo "$line" >> "$RESULTS"
            [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
        }
        TAPF() {
            [ "$1,$2" = "$SPACE" ] && space_taps=$((space_taps + 1))
            return 0
        }
        """

    def run_space(self, reads: list[str]) -> subprocess.CompletedProcess[str]:
        body = "".join(
            [
                shell_function(EMULATOR_SMOKE, "confirm_space") + "\n",
                shell_function(EMULATOR_SMOKE, "space_and_confirm") + "\n",
                "space_and_confirm tt-мин '^мин $'\n",
                "printf 'taps=%s\\n' \"$space_taps\"\n",
            ]
        )
        return self.queue_harness(reads, body, self.HARNESS)

    def test_an_immediate_commit_passes_with_one_tap(self) -> None:
        result = self.run_space(["мин", "мин "])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|PASS|space-tt-мин|", result.stdout)
        self.assertIn("taps=1", result.stdout)

    def test_a_dropped_tap_is_retried_until_the_space_commits(self) -> None:
        result = self.run_space(["мин", "мин", "мин", "мин", "мин", "мин", "мин "])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|PASS|space-tt-мин|", result.stdout)
        self.assertIn("taps=2", result.stdout)

    def test_a_wrong_commit_is_a_failure_and_is_not_tapped_over(self) -> None:
        result = self.run_space(["мин", "минX"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|FAIL|space-tt-мин|", result.stdout)
        self.assertIn("taps=1", result.stdout)

    def test_a_space_that_never_commits_fails_after_a_bounded_retry(self) -> None:
        result = self.run_space(["мин"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|FAIL|space-tt-мин|", result.stdout)
        self.assertIn("taps=3", result.stdout)


class SmokeTypedConfirmTest(QueueHarnessTest):
    """A typed check waits for its tail without re-typing over the field."""

    HARNESS = """
        RESULTS=$OUTDIR/result.txt
        : > "$RESULTS"
        FAILURES=0
        HAVE_MAGICK=0
        STRIP_DIFF_MIN=2000
        LAST_FIELD_TEXT=""
        typed_words=0
        result() {
            local line="RESULT|$1|$2|$3"
            echo "$line"
            echo "$line" >> "$RESULTS"
            [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
        }
        SHOT() { :; }
        type_word() { typed_words=$((typed_words + 1)); }
        strip_diff() { echo 0; }
        """

    def run_typed(self, reads: list[str]) -> subprocess.CompletedProcess[str]:
        body = "".join(
            [
                shell_function(EMULATOR_SMOKE, "field_tail_later") + "\n",
                shell_function(EMULATOR_SMOKE, "typed_checks") + "\n",
                "typed_checks tt \"мин\" coords '^мин$'\n",
                "printf 'typed=%s\\n' \"$typed_words\"\n",
            ]
        )
        return self.queue_harness(reads, body, self.HARNESS)

    def test_a_late_commit_is_waited_for_without_re_typing(self) -> None:
        result = self.run_typed(["ми", "мин"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|PASS|type-tt-мин|", result.stdout)
        self.assertIn("typed=1", result.stdout)

    def test_a_wrong_tail_is_a_hard_failure_after_the_bounded_wait(self) -> None:
        result = self.run_typed(["минпри"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|FAIL|type-tt-мин|", result.stdout)
        self.assertNotIn("RESULT|PASS|type-tt-мин|", result.stdout)
        self.assertIn("typed=1", result.stdout)


class SmokeClearTryitTest(QueueHarnessTest):
    """A standalone typed phase starts only after the field is byte-empty."""

    HARNESS = """
        PKG=fixture.package
        SETUP_ACTIVITY=rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity
        RESULTS=$OUTDIR/result.txt
        : > "$RESULTS"
        FAILURES=0
        calls=()
        result() {
            local line="RESULT|$1|$2|$3"
            echo "$line"
            echo "$line" >> "$RESULTS"
            [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
        }
        DUMP_UI() {
            printf '%s\\n' '<node resource-id="fixture.package:id/setup_test_field" bounds="[0,0][1080,100]"/>'
        }
        keyboard_shown() { [ "${KEYBOARD_SHOWN:-1}" = 1 ]; }
        SHELL() { calls+=("$*"); }
        TAPF() { :; }
        trap 'printf "call=%s\\n" "${calls[@]}" >&2' EXIT
        """

    def run_clear(
        self, reads: list[str], *, keyboard_shown: bool = True
    ) -> subprocess.CompletedProcess[str]:
        body = "".join(
            [
                shell_function(EMULATOR_SMOKE, "refocus_tryit") + "\n",
                shell_function(EMULATOR_SMOKE, "clear_tryit") + "\n",
                "clear_tryit fixture\n",
            ]
        )
        env = os.environ.copy()
        env["KEYBOARD_SHOWN"] = "1" if keyboard_shown else "0"
        return self.queue_harness(reads, body, self.HARNESS, env=env)

    def test_clear_refocuses_moves_to_end_and_deletes_until_empty(self) -> None:
        result = self.run_clear(["abc", "ab", "", ""])
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn("call=am start --activity-clear-task", result.stderr)
        self.assertIn("call=input tap 540 50", result.stderr)
        self.assertIn("call=input keyevent KEYCODE_MOVE_END", result.stderr)
        self.assertEqual(2, result.stderr.count("input keyevent KEYCODE_DEL"))
        self.assertIn("RESULT|PASS|clear-tryit|fixture: try-it field byte-empty", result.stdout)

    def test_a_swallowed_delete_is_retried_with_a_bounded_budget(self) -> None:
        result = self.run_clear(["abc", "abc", "ab", "", ""])
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertEqual(3, result.stderr.count("input keyevent KEYCODE_DEL"))

    def test_a_field_that_never_empties_fails_the_smoke_after_the_timeout(self) -> None:
        result = self.run_clear(["abc"])
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertEqual(30, result.stderr.count("input keyevent KEYCODE_DEL"))
        self.assertIn("RESULT|FAIL|clear-tryit|fixture: try-it field did not empty within 15 seconds", result.stdout)

    def test_a_failed_refocus_is_a_hard_failure_before_any_delete(self) -> None:
        result = self.run_clear(["abc"], keyboard_shown=False)
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertNotIn("input keyevent KEYCODE_DEL", result.stderr)
        self.assertIn("RESULT|FAIL|clear-tryit|fixture: keyboard did not come up for the field refocus", result.stdout)


    def test_clears_precede_the_probe_cycle_and_each_standalone_typed_phase(self) -> None:
        script = EMULATOR_SMOKE.read_text(encoding="utf-8")
        # The cycle itself starts from a known empty field.
        self.assertLess(script.index('clear_tryit "language-cycle"'), script.index("switch_and_check ru russian"))
        # Every phase whose assertion is anchored on a standalone word clears
        # first, so a dropped probe DEL cannot become the typed prefix.
        for tag, phase in (("ru", 'typed_checks ru "при"'), ("en", 'typed_checks en "hi"')):
            with self.subTest(tag=tag):
                self.assertLess(script.index(f'clear_tryit "{tag}-typed"'), script.index(phase))
        self.assertLess(
            script.index('clear_tryit "nextword-татар"'),
            script.index('type_tt_and_tap_cell2 "татар"'),
        )
        self.assertLess(
            script.index('clear_tryit "nextword-сәләм"'),
            script.index('type_tt_and_tap_cell2 "сәләм"'),
        )
        # The ru leg types onto a clean field, exactly like en: its assertions
        # stay fully anchored instead of inheriting a probe-contaminated prefix.
        self.assertIn("typed_checks ru \"при\" \"$RU_PRI\" '^при$'", script)
        self.assertIn("space_and_confirm ru-при '^при $'", script)


class SmokePanelBackReopenTest(SmokeSectionTest):
    """The post-BACK field re-tap waits for the IME window before the probe tap."""

    def run_block(
        self, reads: list[str], *, keyboard_shown_after: int
    ) -> subprocess.CompletedProcess[str]:
        harness = textwrap.dedent(
            """
            set -euo pipefail
            OUTDIR=$1
            shift
            RESULTS=$OUTDIR/result.txt
            : > "$RESULTS"
            FAILURES=0
            queue_file=$OUTDIR/queue
            printf '%s\\n' "$@" > "$queue_file"
            calls=()
            kb_calls=0
            GRID_CELL0_Y=0.6877
            COMMA="0.2009,0.9075"
            result() {
                local line="RESULT|$1|$2|$3"
                echo "$line"
                echo "$line" >> "$RESULTS"
                [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
            }
            sleep() { :; }
            field_text() {
                if [ "$(wc -l < "$queue_file")" -gt 1 ]; then
                    head -1 "$queue_file"
                    tail -n +2 "$queue_file" > "$queue_file.tmp"
                    mv "$queue_file.tmp" "$queue_file"
                else
                    cat "$queue_file"
                fi
            }
            DUMP_UI() {
                printf '%s\\n' '<node resource-id="fixture.package:id/setup_test_field" bounds="[0,0][1080,100]"/>'
            }
            keyboard_shown() {
                kb_calls=$((kb_calls + 1))
                [ "$kb_calls" -gt "$KB_SHOWN_AFTER" ]
            }
            SHELL() {
                calls+=("SHELL:$*")
                case "$*" in
                    "dumpsys input_method") printf 'mInputShown=false\\n' ;;
                esac
            }
            TAPF() {
                calls+=("TAPF:$1,$2")
            }
            LONGPRESSF() { calls+=("LONGPRESSF:$1,$2"); }
            SHOT() { :; }
            refocus_tryit() { return 0; }
            trap 'printf "%s\\n" "${calls[@]}" >&2' EXIT
            """
        )
        harness += self.section("# ── panel-back-reopen ──", "# ── learned-emoji-tt")
        harness += '\n[ "$FAILURES" -eq 0 ]\n'
        env = os.environ.copy()
        env["KB_SHOWN_AFTER"] = str(keyboard_shown_after)
        return run_bash(harness, str(self.outdir), *reads, env=env)

    def test_a_late_window_re_show_is_awaited_and_the_late_commit_confirmed(self) -> None:
        # The keyboard answers on the third poll of the first re-tap round, and
        # the м commit lands one poll late: PASS without a second field re-tap.
        result = self.run_block(["😀", "😀", "😀", "😀", "😀м"], keyboard_shown_after=2)
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn("RESULT|PASS|panel-back-reopen|", result.stdout)
        self.assertEqual(1, result.stderr.count("SHELL:input tap 540 50"))
        self.assertEqual(1, result.stderr.count("TAPF:0.4231,0.8474"))
        self.assertEqual(1, result.stderr.count("SHELL:input keyevent KEYCODE_DEL"))

    def test_a_window_that_never_comes_back_fails_without_the_probe_tap(self) -> None:
        result = self.run_block(["😀"], keyboard_shown_after=99)
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn(
            "the IME window did not come back after the panel BACK within the bounded field re-tap wait",
            result.stdout,
        )
        self.assertEqual(3, result.stderr.count("SHELL:input tap 540 50"))
        self.assertNotIn("TAPF:0.4231,0.8474", result.stderr)
        self.assertNotIn("input keyevent KEYCODE_DEL", result.stderr)

    def test_a_probe_tap_that_never_commits_fails_after_a_bounded_poll(self) -> None:
        result = self.run_block(["😀"] * 8, keyboard_shown_after=0)
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn("committed no м tail after the keyboard came back", result.stdout)
        self.assertEqual(1, result.stderr.count("TAPF:0.4231,0.8474"))
        self.assertNotIn("input keyevent KEYCODE_DEL", result.stderr)

    def test_a_wrong_commit_is_a_failure_and_is_not_deleted_over(self) -> None:
        result = self.run_block(["😀", "😀", "😀c"], keyboard_shown_after=0)
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn("committed no м tail after the keyboard came back", result.stdout)
        self.assertEqual(1, result.stderr.count("TAPF:0.4231,0.8474"))
        self.assertNotIn("input keyevent KEYCODE_DEL", result.stderr)


class SmokeNightModeBaselineTest(SmokeSectionTest):
    """The night-mode leg normalizes to day before its reference shot."""

    def setUp(self) -> None:
        super().setUp()
        self.leg = self.section("# ── night mode ──", "# ── landscape rotate + one typed check")

    def run_leg(self, initial: str, honor: str) -> subprocess.CompletedProcess[str]:
        harness = textwrap.dedent(
            """
            set -euo pipefail
            OUTDIR=$1
            RESULTS=$OUTDIR/result.txt
            : > "$RESULTS"
            FAILURES=0
            HAVE_MAGICK=1
            calls=()
            result() {
                local line="RESULT|$1|$2|$3"
                echo "$line"
                echo "$line" >> "$RESULTS"
                [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
            }
            sleep() { calls+=("sleep:$1"); }
            trap 'printf "%s\\n" "${calls[@]}" >&2' EXIT
            nm_state=$NM_INITIAL
            A() { calls+=("A:$*"); }
            SHELL() {
                case "$*" in
                    "cmd uimode night")
                        case "$nm_state" in
                            1) echo "Night mode: no" ;;
                            2) echo "Night mode: yes" ;;
                            *) echo "Night mode: auto" ;;
                        esac
                        ;;
                    "cmd uimode night yes") [ "$NM_HONOR" = yes ] && nm_state=2 ;;
                    "cmd uimode night no") [ "$NM_HONOR" = yes ] && nm_state=1 ;;
                    "cmd uimode night auto") [ "$NM_HONOR" = yes ] && nm_state=0 ;;
                    "dumpsys uimode") printf 'mNightMode=%s \\n' "$nm_state" ;;
                esac
            }
            SHOT() { : > "$OUTDIR/$1"; }
            refocus_tryit() { return 0; }
            compare() { printf '123456\\n' >&2; }
            convert() { :; }
            """
        )
        harness += "\n" + self.leg + "\n"
        env = os.environ.copy()
        env["NM_INITIAL"] = initial
        env["NM_HONOR"] = honor
        return run_bash(harness, str(self.outdir), env=env)

    def test_a_day_mode_baseline_is_enforced_before_the_reference_shot(self) -> None:
        # A reused AVD that booted in night mode must be flipped to day first:
        # both screenshots would otherwise show the same theme and a correct
        # keyboard would be scored as a failure.
        result = self.run_leg("2", "yes")
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn("RESULT|PASS|night-mode|", result.stdout)
        self.assertIn("mNightMode=1 ", (self.outdir / "uimode-day.txt").read_text(encoding="utf-8"))
        self.assertIn("mNightMode=2 ", (self.outdir / "uimode-night.txt").read_text(encoding="utf-8"))
        self.assertNotIn("A:root", result.stderr)
        self.assertNotIn("A:unroot", result.stderr)

    def test_an_image_refusing_day_mode_fails_instead_of_diffing_two_night_shots(self) -> None:
        result = self.run_leg("2", "no")
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn("image did not enter day mode", result.stdout)
        self.assertIn("RESULT|FAIL|night-mode|", result.stdout)
        self.assertNotIn("RESULT|PASS|night-mode|", result.stdout)
        # The mode change is written through the same root fallback as the night
        # flip, and adbd is unrooted again before the leg ends.
        self.assertIn("A:root", result.stderr)
        self.assertIn("A:unroot", result.stderr)


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
            PKG=fixture.package
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
        result = self.run_crash_check(
            0,
            "FATAL EXCEPTION: main\nProcess: fixture.package\njava.lang.RuntimeException: fixture\n",
        )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|FAIL|crash-log|crash of fixture.package in the buffer", result.stdout)

    def test_another_packages_fatal_exception_does_not_fail_the_leg(self) -> None:
        result = self.run_crash_check(
            0,
            "FATAL EXCEPTION: main\nProcess: com.android.systemui\njava.lang.RuntimeException: fixture\n",
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|PASS|crash-log|", result.stdout)
        self.assertNotIn("RESULT|FAIL|crash-log|", result.stdout)


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
