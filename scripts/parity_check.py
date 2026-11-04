#!/usr/bin/env python3
"""Cross-language parity gate over the headline suggestion-eval metrics.

Reads the pinned tt and ru values of each headline metric and fails when the absolute
tt - ru gap exceeds the metric's budget. Two pin sets must agree: the JVM eval suites
(TtSuggestEvalTest.kt, RuSuggestEvalTest.kt) pin the chain and keystroke rates their
harness measures, and the suggest_eval mirror pins in tests/suggest_eval/test_suggest_eval.py
cover every metric for both languages, including the dictionary token coverage and the
Russian keystroke arm, which the JVM suites do not pin. A missing pin or a disagreement
between the two sets is an error, not a pass.

Output: one PARITY|<metric>|tt=..|ru=..|delta=..|budget=..|<verdict> line per metric, then
a PARITY|OVERALL|.. line. Exit: 0 within budget, 1 over budget, 2 on a pin problem.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

ENGINE_DIR = "app/src/test/java/rkr/simplekeyboard/inputmethod/latin/dictionary/engine"
PINS_TEST = ROOT / "tests/suggest_eval/test_suggest_eval.py"
TT_SUITE = ROOT / ENGINE_DIR / "TtSuggestEvalTest.kt"
RU_SUITE = ROOT / ENGINE_DIR / "RuSuggestEvalTest.kt"

# Maximum absolute tt - ru gap per headline metric, in percentage points. The budgets cap
# how far the weaker language may trail the stronger one; when an eval re-pin moves a gap
# on purpose, re-seed the budget from the new pinned delta. The chain top-3 budget was
# re-seeded after the Russian bigram table gained the 2023/2024 news corpora and edge-
# punctuation recovery, and again after the Tatar eval-set rotation moved the gap to
# 2.3068pp on purpose.
METRIC_GAP_BUDGET_PP = {
    "dict_word_coverage_tokens_pct": 3.0,
    "ks_pct": 3.5,
    "nextword_chain_visible_top3_pct": 2.4,
}

# The FullEvalRecomputeTest method pinning the suggest_eval table of each language.
PINS_METHOD = {"tat": "test_tatar_pins", "rus": "test_russian_pins"}

# A pin row is a live `self.assertEqual(table[...], "...")` statement; the line anchor keeps
# commented-out rows from matching.
PINS_ROW_RE = re.compile(
    r'^\s*self\.assertEqual\(table\["([a-z0-9_]+)"\],\s*"(\d+(?:\.\d+)?)"\)', re.M)

CHAIN_PCT_RE = re.compile(
    r'assertEquals\("(\d+\.\d{4})", format\(ratePct\(visibleHits, pairs\)\)\)')
KS_PCT_RE = re.compile(r'assertEquals\("(\d+\.\d{4})", format\(savedPct\)\)')

# The rates the JVM suites pin as inline strings; the mirror pins must carry the same value.
KOTLIN_CROSS_CHECKS = (
    ("tt", "nextWordChainHitRatesOnTheEvalSet", CHAIN_PCT_RE, "tat",
     "nextword_chain_visible_top3_pct"),
    ("tt", "keystrokeSavingsOnTheEvalSet", KS_PCT_RE, "tat", "ks_pct"),
    ("ru", "nextWordChainHitRatesOnTheEvalSet", CHAIN_PCT_RE, "rus",
     "nextword_chain_visible_top3_pct"),
)


class PinError(Exception):
    """A pin is missing, malformed or inconsistent between the suites and the mirror pins."""


def python_method_body(text: str, method: str, source: str) -> str:
    """One test method, from its `def` line to the next same-indent def or class."""
    start = text.find(f"    def {method}(")
    if start < 0:
        raise PinError(f"{source}: method {method} not found")
    end = len(text)
    for marker in ("\n    def ", "\nclass "):
        index = text.find(marker, start)
        if 0 <= index < end:
            end = index
    return text[start:end]


def kotlin_method_body(text: str, method: str, source: str) -> str:
    """One test method, from `fun <method>(` to its closing brace at member indent."""
    start = text.find(f"fun {method}(")
    if start < 0:
        raise PinError(f"{source}: method {method} not found")
    end = text.find("\n    }", start)
    if end < 0:
        raise PinError(f"{source}: method {method} has no closing brace")
    return text[start:end]


def mirror_pins(pins_test_text: str) -> dict[str, dict[str, str]]:
    """The pinned suggest_eval metric rows of each language's pin method."""
    pins = {}
    for language, method in PINS_METHOD.items():
        rows = dict(PINS_ROW_RE.findall(python_method_body(pins_test_text, method, "pins test")))
        missing = [metric for metric in METRIC_GAP_BUDGET_PP if metric not in rows]
        if missing:
            raise PinError(f"{method} pins lack: {', '.join(missing)}")
        pins[language] = rows
    return pins


def collect_metrics(
    pins_test_text: str,
    tt_suite_text: str,
    ru_suite_text: str,
) -> dict[str, dict[str, float]]:
    """The pinned headline metrics per language; the suite pins must match the mirror pins."""
    pins = mirror_pins(pins_test_text)
    suites = {"tt": ("TtSuggestEvalTest.kt", tt_suite_text),
              "ru": ("RuSuggestEvalTest.kt", ru_suite_text)}
    for side, method, pattern, language, metric in KOTLIN_CROSS_CHECKS:
        suite_name, suite_text = suites[side]
        match = pattern.search(kotlin_method_body(suite_text, method, suite_name))
        if match is None:
            raise PinError(f"{suite_name}: {method} pins no {metric} rate")
        if match.group(1) != pins[language][metric]:
            raise PinError(
                f"{metric}: {suite_name} pins {match.group(1)} but the mirror pins "
                f"{pins[language][metric]}")
    return {
        metric: {language: float(pins[language][metric]) for language in PINS_METHOD}
        for metric in METRIC_GAP_BUDGET_PP
    }


def evaluate(metrics: dict[str, dict[str, float]]) -> list[tuple[str, float, float, float, float, bool]]:
    """One report row per metric: (metric, tt, ru, tt - ru, budget, within budget)."""
    rows = []
    for metric, budget in METRIC_GAP_BUDGET_PP.items():
        tt_value = metrics[metric]["tat"]
        ru_value = metrics[metric]["rus"]
        delta = tt_value - ru_value
        rows.append((metric, tt_value, ru_value, delta, budget, abs(delta) <= budget))
    return rows


def run(pins_test: Path, tt_suite: Path, ru_suite: Path, out) -> int:
    """The gate itself: parse, cross-check, evaluate, report. See the module docstring."""
    try:
        metrics = collect_metrics(
            pins_test.read_text(encoding="utf-8"),
            tt_suite.read_text(encoding="utf-8"),
            ru_suite.read_text(encoding="utf-8"),
        )
    except (OSError, UnicodeError, PinError) as error:
        print(f"PARITY|ERROR|{error}", file=sys.stderr)
        return 2
    rows = evaluate(metrics)
    for metric, tt_value, ru_value, delta, budget, within in rows:
        print(f"PARITY|{metric}|tt={tt_value:.4f}|ru={ru_value:.4f}"
              f"|delta={delta:+.4f}|budget={budget:.4f}|{'PASS' if within else 'FAIL'}",
              file=out)
    exceeded = [row for row in rows if not row[5]]
    if exceeded:
        detail = ", ".join(f"{metric} {delta:+.4f}pp over {budget:.4f}pp"
                           for metric, _, _, delta, budget, _ in exceeded)
        print(f"PARITY|OVERALL|FAIL|{detail}", file=out)
        return 1
    print(f"PARITY|OVERALL|PASS|{len(rows)} metric gaps within budget", file=out)
    return 0


def main() -> int:
    return run(PINS_TEST, TT_SUITE, RU_SUITE, sys.stdout)


if __name__ == "__main__":
    sys.exit(main())
