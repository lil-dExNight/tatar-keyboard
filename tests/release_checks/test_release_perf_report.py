"""Host-only validation of strict performance evidence."""

from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "scripts" / "release_perf_report.py"
DEVICE_PERF = ROOT / "scripts" / "device-perf-ritual.sh"
LEGS = "cold,pss,frames,warm,touch,suggest,first-suggestion,first-glide"
PSS_SCENARIOS = ("keyboard-idle", "after-50-words", "emoji-panel-open", "emoji-panel-closed", "idle-30s")
FRAME_RUNS = (1, 2, 3)


def report_lines() -> list[str]:
    checks = (
        "cold-start", "warm-show", "touch", "suggest",
        "first-suggestion", "cold-first-glide", "warm-decode",
    )
    lines = [
        (
            "RESULT|INFO|touch|events=64 p50_ms=2.4 p95_ms=5.5 max_ms=5.8 "
            "event_to_handled_p50_ms=2.5 event_to_handled_p95_ms=5.7 "
            "budget_ms=6 over_budget=false build=release script=32-event-tt"
        ) if check == "touch" else f"RESULT|INFO|{check}|runs=5 skips=0 over_budget=false"
        for check in checks
    ]
    lines.append("RESULT|INFO|frames-proof|try-it field holds the tt script tail as typed")
    for run in FRAME_RUNS:
        lines.append(
            f"RESULT|INFO|frames|run={run} n=40 p50_ms=12.1 p90_ms=15.2 p95_ms=16.1 "
            "budget_ms=16.7 over_budget=false build=release script=32-event-tt window=InputMethod"
        )
        lines.append(
            f"RESULT|INFO|jank|run={run} frames=40 janky=0 janky_pct=0.0 budget_pct=1.0 "
            "over_budget=false build=release script=32-event-tt window=InputMethod"
        )
    for scenario in PSS_SCENARIOS:
        lines.append(
            f"RESULT|INFO|pss|scenario={scenario} total_kb=48000 swap_kb=0 anon_kb=18000 "
            "file_kb=30000 budget_kb=69000 over_budget=false build=release"
        )
    lines.append(f"RESULT|SUMMARY|strict=1 fail=0 skip=0 sample_skips=0 legs={LEGS} p0_signoff=ready|outdir=fixture")
    return lines


def spellcheck_report_lines() -> list[str]:
    return [
        "RESULT|INFO|spellcheck-warm|runs=31 words=200 p95_ms=20.0 budget_ms=100.0 over_budget=false",
        "RESULT|INFO|spellcheck-cold|runs=1 words=200 p95_ms=400.0 budget_ms=500.0 over_budget=false",
        "RESULT|INFO|spellcheck-memory|delta_kb=3000 budget_kb=3584 over_budget=false",
        "RESULT|SUMMARY|strict=1 fail=0 skip=0 sample_skips=0 legs=spellcheck p0_signoff=ready|outdir=fixture",
    ]


def line_index(lines: list[str], needle: str) -> int:
    for index, line in enumerate(lines):
        if needle in line:
            return index
    raise AssertionError(f"fixture line not found: {needle}")


def drop(lines: list[str], needle: str) -> list[str]:
    return [line for line in lines if needle not in line]


class StrictPerformanceReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.report = Path(self.temporary_directory.name) / "result.txt"

    def run_validator(
        self, lines: list[str], *, wrapper: bool = False, legs: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        self.report.write_text("\n".join(lines) + "\n", encoding="utf-8")
        if wrapper:
            command = ["bash", str(DEVICE_PERF), "--verify-report", str(self.report)]
        else:
            command = ["python3", str(VALIDATOR), str(self.report)]
        if legs is not None:
            command.extend(["--legs", legs])
        environment = os.environ.copy()
        environment["ADB"] = "/nonexistent/adb"
        return subprocess.run(command, capture_output=True, text=True, env=environment, check=False)

    def test_complete_strict_report_passes_without_adb(self) -> None:
        result = self.run_validator(report_lines())
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RELEASE_PERF|PASS|strict=1 skip=0 sample_skips=0", result.stdout)

    def test_device_script_dispatches_to_the_host_validator(self) -> None:
        result = self.run_validator(report_lines(), wrapper=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RELEASE_PERF|PASS|", result.stdout)

    def test_spellcheck_only_report_passes_through_the_wrapper(self) -> None:
        result = self.run_validator(spellcheck_report_lines(), wrapper=True, legs="spellcheck")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RELEASE_PERF|PASS|strict=1 skip=0 sample_skips=0 legs=spellcheck", result.stdout)

    def test_spellcheck_leg_requires_budgeted_memory_evidence(self) -> None:
        lines = spellcheck_report_lines()
        lines[2] = lines[2].replace(" over_budget=false", "")
        result = self.run_validator(lines, legs="spellcheck")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("required leg spellcheck has no budgeted spellcheck-memory result", result.stdout)

    def test_touch_p95_within_reference_budget_passes(self) -> None:
        lines = report_lines()
        lines[line_index(lines, "|touch|")] = (
            "RESULT|INFO|touch|events=64 p50_ms=2.4 p95_ms=5.5 max_ms=5.8 "
            "event_to_handled_p50_ms=2.5 event_to_handled_p95_ms=5.7 "
            "budget_ms=6 over_budget=false build=release script=32-event-tt"
        )
        result = self.run_validator(lines)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RELEASE_PERF|PASS|", result.stdout)

    def test_touch_p95_over_reference_budget_fails(self) -> None:
        lines = report_lines()
        lines[line_index(lines, "|touch|")] = (
            "RESULT|INFO|touch|events=64 p50_ms=2.4 p95_ms=6.1 max_ms=6.4 "
            "event_to_handled_p50_ms=2.5 event_to_handled_p95_ms=6.2 "
            "budget_ms=6 over_budget=false build=release script=32-event-tt"
        )
        result = self.run_validator(lines)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("touch p95_ms=6.1 exceeds 6", result.stdout)

    def test_touch_budget_pin_is_six_ms(self) -> None:
        body = DEVICE_PERF.read_text(encoding="utf-8")
        match = re.search(r"(?m)^TOUCH_BUDGET_MS=(\d+)$", body)
        self.assertIsNotNone(match)
        self.assertEqual("6", match.group(1))

    def test_spellcheck_pss_delta_budget_pin(self) -> None:
        body = DEVICE_PERF.read_text(encoding="utf-8")
        match = re.search(r"(?m)^SPELLCHECK_PSS_DELTA_BUDGET_KB=(\d+)$", body)
        self.assertIsNotNone(match)
        self.assertEqual("3584", match.group(1))

    def test_explicit_skip_blocks_signoff(self) -> None:
        lines = report_lines()
        lines[line_index(lines, "|touch|")] = "RESULT|SKIP|touch|instrumentation unavailable"
        lines = [*lines[:-1], lines[-1].replace("skip=0", "skip=1").replace(
            "p0_signoff=ready", "p0_signoff=blocked")]
        result = self.run_validator(lines)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("SKIP touch", result.stdout)
        self.assertIn("p0_signoff", result.stdout)

    def test_a_partially_skipped_measurement_blocks_signoff(self) -> None:
        lines = report_lines()
        lines[line_index(lines, "|cold-start|")] = lines[line_index(lines, "|cold-start|")].replace(
            "skips=0", "skips=1")
        lines = [*lines[:-1], lines[-1].replace("sample_skips=0", "sample_skips=1").replace(
            "p0_signoff=ready", "p0_signoff=blocked")]
        result = self.run_validator(lines)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("skipped samples in cold-start", result.stdout)

    def test_a_missing_required_leg_cannot_pass(self) -> None:
        result = self.run_validator(drop(report_lines(), "cold-first-glide"))
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("required leg first-glide has no cold-first-glide result", result.stdout)

    def test_an_advisory_report_is_not_strict_evidence(self) -> None:
        lines = report_lines()
        lines[-1] = lines[-1].replace("strict=1", "strict=0").replace(
            "p0_signoff=ready", "p0_signoff=blocked")
        result = self.run_validator(lines)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("report was not produced by --strict", result.stdout)

    def test_over_budget_info_cannot_be_relabelled_as_ready(self) -> None:
        lines = report_lines()
        index = line_index(lines, "scenario=keyboard-idle")
        lines[index] = lines[index].replace("over_budget=false", "over_budget=true")
        result = self.run_validator(lines)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("over budget pss", result.stdout)

    def test_a_missing_pss_scenario_cannot_pass(self) -> None:
        result = self.run_validator(drop(report_lines(), "scenario=idle-30s"))
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("required pss scenario idle-30s has no result", result.stdout)

    def test_a_pss_scenario_without_a_budget_verdict_cannot_pass(self) -> None:
        lines = report_lines()
        index = line_index(lines, "scenario=after-50-words")
        lines[index] = lines[index].replace(" over_budget=false", "")
        result = self.run_validator(lines)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("required pss scenario after-50-words has no budgeted result", result.stdout)

    def test_missing_jank_evidence_cannot_pass(self) -> None:
        result = self.run_validator(drop(report_lines(), "|jank|"))
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("required leg frames has no jank result", result.stdout)

    def test_jank_evidence_without_any_budget_verdict_cannot_pass(self) -> None:
        lines = report_lines()
        for index, line in enumerate(lines):
            if "|jank|" in line:
                lines[index] = line.replace(" over_budget=false", "")
        result = self.run_validator(lines)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("required leg frames has no budgeted jank result", result.stdout)

    def test_jank_evidence_with_a_partial_budget_verdict_cannot_pass(self) -> None:
        lines = report_lines()
        index = line_index(lines, "|jank|run=2 ")
        lines[index] = lines[index].replace(" over_budget=false", "")
        result = self.run_validator(lines)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("1 jank result(s) without over_budget=", result.stdout)

    def test_incomplete_jank_runs_cannot_pass(self) -> None:
        result = self.run_validator(drop(report_lines(), "|jank|run=3 "))
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("required leg frames is missing jank evidence for run 3", result.stdout)

    def test_over_budget_jank_cannot_be_relabelled_as_ready(self) -> None:
        lines = report_lines()
        index = line_index(lines, "|jank|run=2 ")
        lines[index] = lines[index].replace("over_budget=false", "over_budget=true")
        result = self.run_validator(lines)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("over budget jank", result.stdout)

    def test_a_report_without_a_summary_cannot_pass(self) -> None:
        result = self.run_validator(report_lines()[:-1])
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("expected one RESULT|SUMMARY line", result.stdout)


class DeviceProbeDiscoveryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.instrumentation = Path(self.temporary_directory.name) / "instrumentation.txt"

    def find_runner(self, lines: list[str]) -> subprocess.CompletedProcess[str]:
        self.instrumentation.write_text("\n".join(lines) + "\n", encoding="utf-8")
        body = DEVICE_PERF.read_text(encoding="utf-8")
        match = re.search(r"(?ms)^self_targeted_runner\(\) \{.*?^\}$", body)
        self.assertIsNotNone(match)
        environment = os.environ.copy()
        environment["INSTRUMENTATION_FILE"] = str(self.instrumentation)
        harness = """PKG=org.tatarkeyboard.ime
SHELL() { cat "$INSTRUMENTATION_FILE"; }
""" + match.group(0) + """
self_targeted_runner rkr.simplekeyboard.inputmethod.latin.spellcheck.SpellCheckPerfSelfRunner
"""
        return subprocess.run(["bash", "-c", harness], capture_output=True, text=True,
                              env=environment, check=False)

    def test_matching_test_package_is_preferred(self) -> None:
        debug = (
            "instrumentation:org.tatarkeyboard.ime.debug.test/"
            "rkr.simplekeyboard.inputmethod.latin.spellcheck.SpellCheckPerfSelfRunner "
            "(target=org.tatarkeyboard.ime.debug.test)"
        )
        release = (
            "instrumentation:org.tatarkeyboard.ime.test/"
            "rkr.simplekeyboard.inputmethod.latin.spellcheck.SpellCheckPerfSelfRunner "
            "(target=org.tatarkeyboard.ime.test)"
        )
        result = self.find_runner([debug, release])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(release.split()[0].removeprefix("instrumentation:"), result.stdout.strip())

    def test_other_self_targeted_package_is_accepted_but_foreign_target_is_not(self) -> None:
        self_targeted = (
            "instrumentation:org.tatarkeyboard.ime.debug.test/"
            "rkr.simplekeyboard.inputmethod.latin.spellcheck.SpellCheckPerfSelfRunner "
            "(target=org.tatarkeyboard.ime.debug.test)"
        )
        foreign_target = (
            "instrumentation:org.tatarkeyboard.ime.test/"
            "rkr.simplekeyboard.inputmethod.latin.spellcheck.SpellCheckPerfSelfRunner "
            "(target=org.tatarkeyboard.ime.debug)"
        )
        result = self.find_runner([foreign_target, self_targeted])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self_targeted.split()[0].removeprefix("instrumentation:"), result.stdout.strip())
        result = self.find_runner([foreign_target])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout.strip())


class ReleaseGlobeSwitchTest(unittest.TestCase):
    def run_switch(self, order: str, target: str) -> subprocess.CompletedProcess[str]:
        body = DEVICE_PERF.read_text(encoding="utf-8")
        match = re.search(r"(?ms)^globe_to\(\) \{.*?^\}$", body)
        self.assertIsNotNone(match)
        harness = """RUN_AS_OK=0
ORDER=$1
TARGET=$2
STATE=$3
INDEX=0
TAPS=0
VISITS=
load_state() { [ ! -f "$STATE" ] || source "$STATE"; }
save_state() { printf 'ORDER=%q\nINDEX=%q\nTAPS=%q\nVISITS=%q\n' "$ORDER" "$INDEX" "$TAPS" "$VISITS" > "$STATE"; }
save_state
sleep() { :; }
log() { :; }
SHELL() {
    [ "$1" = input ] && [ "$2" = tap ] && [ "$3" = 216 ] || return 0
    load_state
    local items=($ORDER)
    INDEX=$((INDEX + 1))
    if [ "$INDEX" -ge "${#items[@]}" ]; then INDEX=0; fi
    TAPS=$((TAPS + 1))
    save_state
}
probe_layout() {
    load_state
    local items=($ORDER) current=${ORDER%% *} rotated=() k
    current=${items[$INDEX]}
    VISITS=$VISITS${VISITS:+,}$current
    if [ "$INDEX" -gt 0 ]; then
        rotated=("${items[$INDEX]}")
        for ((k = 0; k < INDEX; k++)); do rotated+=("${items[$k]}"); done
        for ((k = INDEX + 1; k < ${#items[@]}; k++)); do rotated+=("${items[$k]}"); done
        ORDER=${rotated[*]}
    fi
    INDEX=0
    save_state
    echo "$current"
}
""" + match.group(0) + """
if globe_to "$TARGET"; then rc=0; else rc=$?; fi
load_state
printf 'current=%s visits=%s taps=%s\\n' "${ORDER%% *}" "$VISITS" "$TAPS"
exit "$rc"
"""
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state"
            return subprocess.run(["bash", "-c", harness, "bash", order, target, str(state)],
                                  capture_output=True, text=True, check=False)

    def test_every_enabled_subtype_is_reachable_despite_probe_cycle_resets(self) -> None:
        for order in ("tt en ru", "tt en ru qwerty qwertz"):
            languages = order.split()
            for index, target in enumerate(languages):
                with self.subTest(order=order, target=target):
                    result = self.run_switch(order, target)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertIn(f"current={target} ", result.stdout)
                    self.assertIn(f"visits={','.join(languages[:index + 1])} ", result.stdout)
                    self.assertIn(f"taps={index * (index + 1) // 2}", result.stdout)


class StrictResultFunctionTest(unittest.TestCase):
    def run_result(self, status: str, check: str, detail: str) -> subprocess.CompletedProcess[str]:
        script = DEVICE_PERF.read_text(encoding="utf-8")
        match = re.search(r"(?ms)^result\(\) \{.*?^\}$", script)
        self.assertIsNotNone(match)
        harness = """set -uo pipefail
STRICT=1
FAILURES=0
SKIPS=0
SAMPLE_SKIPS=0
RESULTS=/dev/null
""" + match.group(0) + f"""
result {status} {check} {detail!r}
printf 'fail=%s skip=%s sample_skips=%s\\n' "$FAILURES" "$SKIPS" "$SAMPLE_SKIPS"
"""
        return subprocess.run(["bash", "-c", harness], capture_output=True, text=True, check=False)

    def test_strict_turns_a_missing_leg_skip_into_failure(self) -> None:
        result = self.run_result("SKIP", "first-glide", "runner unavailable")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|FAIL|first-glide|runner unavailable", result.stdout)
        self.assertIn("fail=1 skip=1 sample_skips=0", result.stdout)

    def test_strict_turns_a_partially_skipped_measurement_into_failure(self) -> None:
        result = self.run_result("INFO", "cold-start", "runs=4 skips=1 over_budget=false")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT|FAIL|cold-start|", result.stdout)
        self.assertIn("fail=1 skip=0 sample_skips=1", result.stdout)


if __name__ == "__main__":
    unittest.main()
