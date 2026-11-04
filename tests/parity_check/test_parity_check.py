#!/usr/bin/env python3
"""Fixture tests for scripts/parity_check.py: pass, over-budget and drifted pin sets.

The fixtures are synthetic miniatures of the three pin files (the suggest_eval mirror pins
in tests/suggest_eval/test_suggest_eval.py and the two JVM eval suites), so a re-pin of the
real suites does not touch this module. The real tree is covered once, end to end, by
RealTreeTest: the current pins must sit within the budgets.
"""

from __future__ import annotations

import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "parity_check.py"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


parity_check = load_module("parity_check", SCRIPT)

# The current pinned values, used as the neutral fixture baseline.
TAT_PINS = {
    "dict_word_coverage_tokens_pct": "95.6697",
    "ks_pct": "35.9516",
    "nextword_chain_visible_top3_pct": "14.5763",
}
RUS_PINS = {
    "dict_word_coverage_tokens_pct": "97.5485",
    "ks_pct": "37.7427",
    "nextword_chain_visible_top3_pct": "15.7412",
}


def pins_text(tat_rows: dict[str, str], rus_rows: dict[str, str]) -> str:
    """A miniature of the mirror pin file: two methods, assertEqual rows only."""
    def section(method: str, rows: dict[str, str]) -> str:
        lines = "\n".join(f'        self.assertEqual(table["{name}"], "{value}")'
                          for name, value in rows.items())
        return f"    def {method}(self) -> None:\n{lines}\n"

    return (section("test_tatar_pins", tat_rows) + section("test_russian_pins", rus_rows))


def tt_suite_text(ks_pct: str, chain_pct: str) -> str:
    """A miniature of the Tatar suite: the two methods carrying inline rate pins."""
    return (
        "    @Test\n"
        "    fun nextWordChainHitRatesOnTheEvalSet() {\n"
        f'        assertEquals("{chain_pct}", format(ratePct(visibleHits, pairs)))\n'
        "    }\n"
        "    @Test\n"
        "    fun keystrokeSavingsOnTheEvalSet() {\n"
        f'        assertEquals("{ks_pct}", format(savedPct))\n'
        "    }\n"
    )


def ru_suite_text(chain_pct: str) -> str:
    """A miniature of the Russian suite: only the chain rate is pinned inline."""
    return (
        "    @Test\n"
        "    fun nextWordChainHitRatesOnTheEvalSet() {\n"
        f'        assertEquals("{chain_pct}", format(ratePct(visibleHits, pairs)))\n'
        "    }\n"
    )


def run_gate(tat_rows: dict[str, str], rus_rows: dict[str, str],
             tt_text: str | None = None, ru_text: str | None = None,
             pins_bytes: bytes | None = None) -> tuple[int, str, str]:
    """run() over fixture files in a temp dir; returns (exit, stdout, stderr)."""
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        pins = root / "pins.py"
        if pins_bytes is not None:
            pins.write_bytes(pins_bytes)
        else:
            pins.write_text(pins_text(tat_rows, rus_rows), encoding="utf-8")
        tt = root / "TtSuggestEvalTest.kt"
        tt.write_text(
            tt_text if tt_text is not None
            else tt_suite_text(tat_rows["ks_pct"], tat_rows["nextword_chain_visible_top3_pct"]),
            encoding="utf-8")
        ru = root / "RuSuggestEvalTest.kt"
        ru.write_text(
            ru_text if ru_text is not None
            else ru_suite_text(rus_rows["nextword_chain_visible_top3_pct"]),
            encoding="utf-8")
        out = io.StringIO()
        stderr = io.StringIO()
        hold = sys.stderr
        sys.stderr = stderr
        try:
            code = parity_check.run(pins, tt, ru, out)
        finally:
            sys.stderr = hold
        return code, out.getvalue(), stderr.getvalue()


class BudgetTest(unittest.TestCase):
    """The gate verdict over fixture pin sets."""

    def test_within_budget_passes(self) -> None:
        code, out, _ = run_gate(TAT_PINS, RUS_PINS)
        self.assertEqual(0, code, out)
        self.assertEqual(3, out.count("|PASS\n"))
        self.assertIn("PARITY|OVERALL|PASS|", out)

    def test_gap_over_budget_fails(self) -> None:
        rus = dict(RUS_PINS, ks_pct="39.9516")  # tt - ru = -4.0000pp, budget 3.5000pp
        code, out, _ = run_gate(TAT_PINS, rus)
        self.assertIn("PARITY|ks_pct|tt=35.9516|ru=39.9516|delta=-4.0000|budget=3.5000|FAIL", out)
        self.assertIn("PARITY|dict_word_coverage_tokens_pct|", out)
        self.assertIn("|PASS\n", out)
        self.assertIn("PARITY|OVERALL|FAIL|ks_pct", out)

    def test_parity_can_flip_direction_without_failing(self) -> None:
        # tt ahead of ru by less than the budget is inside the gate.
        tat = dict(TAT_PINS, dict_word_coverage_tokens_pct="98.0485")
        code, out, _ = run_gate(tat, RUS_PINS)
        self.assertEqual(0, code, out)
        self.assertIn("delta=+0.5000", out)


class DriftTest(unittest.TestCase):
    """Re-pinned values: the gap, not the level, is gated."""

    def test_joint_repin_keeps_the_gap_and_passes(self) -> None:
        tat = {name: f"{float(value) - 0.5:.4f}" for name, value in TAT_PINS.items()}
        rus = {name: f"{float(value) - 0.5:.4f}" for name, value in RUS_PINS.items()}
        code, out, _ = run_gate(tat, rus)
        self.assertEqual(0, code, out)
        self.assertIn("PARITY|OVERALL|PASS|", out)

    def test_one_sided_drift_widening_the_gap_fails(self) -> None:
        tat = dict(TAT_PINS, nextword_chain_visible_top3_pct="13.2763")  # gap -3.4649pp
        code, out, _ = run_gate(tat, RUS_PINS)
        self.assertEqual(1, code, out)
        self.assertIn("PARITY|nextword_chain_visible_top3_pct|", out)
        self.assertIn("|FAIL", out)

    def test_suite_mirror_desync_is_an_error(self) -> None:
        # The Tatar suite moved but the mirror pins did not: the inputs cannot be trusted.
        code, _, err = run_gate(TAT_PINS, RUS_PINS,
                                tt_text=tt_suite_text("36.3914", "14.5763"))
        self.assertEqual(2, code)
        self.assertIn("ks_pct", err)
        self.assertIn("TtSuggestEvalTest.kt", err)


class PinParsingTest(unittest.TestCase):
    """The slicers and row parsers over fixture text."""

    def test_mirror_pins_reads_both_languages(self) -> None:
        pins = parity_check.mirror_pins(pins_text(TAT_PINS, RUS_PINS))
        self.assertEqual("95.6697", pins["tat"]["dict_word_coverage_tokens_pct"])
        self.assertEqual("15.7412", pins["rus"]["nextword_chain_visible_top3_pct"])

    def test_missing_metric_row_is_an_error(self) -> None:
        tat = {name: value for name, value in TAT_PINS.items() if name != "ks_pct"}
        with self.assertRaisesRegex(parity_check.PinError, "ks_pct"):
            parity_check.mirror_pins(pins_text(tat, RUS_PINS))

    def test_missing_suite_method_is_an_error(self) -> None:
        code, _, err = run_gate(TAT_PINS, RUS_PINS, tt_text="class TtSuggestEvalTest {\n}\n")
        self.assertEqual(2, code)
        self.assertIn("nextWordChainHitRatesOnTheEvalSet", err)

    def test_missing_suite_pin_is_an_error(self) -> None:
        tt = tt_suite_text("34.8914", "14.8328").replace(
            'assertEquals("14.8328", format(ratePct(visibleHits, pairs)))', "")
        code, _, err = run_gate(TAT_PINS, RUS_PINS, tt_text=tt)
        self.assertEqual(2, code)
        self.assertIn("pins no nextword_chain_visible_top3_pct rate", err)

    def test_commented_out_pin_row_is_ignored(self) -> None:
        # A stale row left in a comment must not shadow the live pin.
        live = f'        self.assertEqual(table["ks_pct"], "{TAT_PINS["ks_pct"]}")'
        commented = f'{live}\n        # self.assertEqual(table["ks_pct"], "99.0000")'
        pins = parity_check.mirror_pins(pins_text(TAT_PINS, RUS_PINS).replace(live, commented))
        self.assertEqual(TAT_PINS["ks_pct"], pins["tat"]["ks_pct"])

    def test_non_utf8_pin_file_is_an_error(self) -> None:
        # An undecodable pin file is a pin problem (exit 2), not a crash (exit 1).
        code, _, err = run_gate(TAT_PINS, RUS_PINS, pins_bytes=b"\xff\xfe")
        self.assertEqual(2, code)
        self.assertIn("PARITY|ERROR|", err)


class RealTreeTest(unittest.TestCase):
    """The committed pins pass the budgets; this is the gate itself, run once end to end."""

    def test_committed_pins_within_budget(self) -> None:
        result = subprocess.run(
            [sys.executable, str(SCRIPT)], cwd=ROOT, check=False,
            capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PARITY|OVERALL|PASS|", result.stdout)


if __name__ == "__main__":
    unittest.main()
