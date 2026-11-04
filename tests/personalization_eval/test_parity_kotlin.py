#!/usr/bin/env python3
"""Kotlin<->Python parity of the personalization harness constants and pins.

Parses the pinned constants out of the JVM mirror (PersonalizationEvalTest.kt) and
asserts, for every metric the two sides share, that the JVM pins equal the Python pins of
``tests/personalization_eval/test_personalization_eval.py``, and that the shared
thresholds equal the production constants (PendingCounters, CompositePrefixComputer,
PersonalBigramStore). Re-pin both sides together when anything changes.
"""
from __future__ import annotations

import importlib.util
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "personalization_eval.py"
KOTLIN_MIRROR = (
    ROOT
    / "app"
    / "src"
    / "test"
    / "java"
    / "rkr"
    / "simplekeyboard"
    / "inputmethod"
    / "latin"
    / "dictionary"
    / "engine"
    / "PersonalizationEvalTest.kt"
)
COMPOSITE_PREFIX_COMPUTER = (
    ROOT
    / "app"
    / "src"
    / "main"
    / "java"
    / "rkr"
    / "simplekeyboard"
    / "inputmethod"
    / "latin"
    / "dictionary"
    / "engine"
    / "CompositePrefixComputer.kt"
)
PENDING_COUNTERS = (
    ROOT
    / "app"
    / "src"
    / "main"
    / "java"
    / "rkr"
    / "simplekeyboard"
    / "inputmethod"
    / "latin"
    / "dictionary"
    / "personalstore"
    / "PendingCounters.kt"
)
PERSONAL_BIGRAM_STORE = (
    ROOT
    / "app"
    / "src"
    / "main"
    / "java"
    / "rkr"
    / "simplekeyboard"
    / "inputmethod"
    / "latin"
    / "dictionary"
    / "personalstore"
    / "PersonalBigramStore.kt"
)
TPERS_FORMAT = (
    ROOT
    / "app"
    / "src"
    / "main"
    / "java"
    / "rkr"
    / "simplekeyboard"
    / "inputmethod"
    / "latin"
    / "dictionary"
    / "personal"
    / "TpersFormat.kt"
)
TPERSB_FORMAT = (
    ROOT
    / "app"
    / "src"
    / "main"
    / "java"
    / "rkr"
    / "simplekeyboard"
    / "inputmethod"
    / "latin"
    / "dictionary"
    / "personal"
    / "TpersbFormat.kt"
)


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


harness = load_module("personalization_eval", SCRIPT)
pins = load_module(
    "personalization_eval_pins",
    ROOT / "tests" / "personalization_eval" / "test_personalization_eval.py",
)


def kotlin_constants(source: str) -> dict[str, int]:
    """Every `private const val NAME = <int>L?` of the Kotlin mirror, as ints."""
    found: dict[str, int] = {}
    for name, value in re.findall(
        r"private const val ([A-Z_0-9]+) = (\d[\d_]*)L?\b", source
    ):
        found[name] = int(value.replace("_", ""))
    return found


def kotlin_pin_maps(source: str) -> dict[str, dict[str, int]]:
    """Every `private val PIN_NAME = mapOf(...)` of the Kotlin mirror.

    The maps hold `"key" to <int>` rows; the repetition-split sweep and keystroke pins
    live in `const val` declarations instead, which [kotlin_constants] reads.
    """
    maps: dict[str, dict[str, int]] = {}
    for name, body in re.findall(
        r"private val (PIN_[A-Z_0-9]+) = mapOf\((.*?)\)\n", source, re.DOTALL
    ):
        entries = {
            key: int(value.replace("_", ""))
            for key, value in re.findall(r'"([^"]+)" to (\d[\d_]*)L?\b', body)
        }
        maps[name] = entries
    return maps


def production_constant(source: str, name: str) -> int:
    match = re.search(rf"const val {name} = (\d[\d_]*)L?\b", source)
    assert match is not None, f"{name} is not a const val of the production source"
    return int(match.group(1).replace("_", ""))


def split_arm_key(jvm_key: str) -> tuple[str, str]:
    split, arm = jvm_key.split(".")
    return split, arm


class KotlinPythonPinParityTest(unittest.TestCase):
    """The JVM mirror and the Python harness pin the same numbers."""

    KOTLIN = KOTLIN_MIRROR.read_text(encoding="utf-8")
    KOTLIN_CONSTS = kotlin_constants(KOTLIN)
    KOTLIN_MAPS = kotlin_pin_maps(KOTLIN)

    def test_shared_graduation_pins_are_equal(self):
        jvm_words = {
            split_arm_key(key): value for key, value in self.KOTLIN_MAPS["PIN_GRAD_WORDS"].items()
        }
        jvm_pairs = {
            split_arm_key(key): value for key, value in self.KOTLIN_MAPS["PIN_GRAD_PAIRS"].items()
        }
        jvm_lookups = {
            split_arm_key(key): value
            for key, value in self.KOTLIN_MAPS["PIN_OBSERVED_LOOKUPS"].items()
        }
        jvm_empty = {
            split_arm_key(key): value
            for key, value in self.KOTLIN_MAPS["PIN_EMPTY_OBSERVATIONS"].items()
        }
        self.assertEqual(jvm_words, pins.PIN_GRAD_WORDS)
        self.assertEqual(jvm_pairs, pins.PIN_GRAD_PAIRS)
        self.assertEqual(jvm_lookups, pins.PIN_OBSERVED_LOOKUPS)
        self.assertEqual(jvm_empty, pins.PIN_EMPTY_OBSERVATIONS)

    def test_shared_priming_pins_are_equal(self):
        self.assertEqual(self.KOTLIN_MAPS["PIN_WORD_COMPLETIONS"], pins.PIN_WORD_COMPLETIONS)
        self.assertEqual(self.KOTLIN_MAPS["PIN_PAIR_OBSERVATIONS"], pins.PIN_PAIR_OBSERVATIONS)
        self.assertEqual(
            self.KOTLIN_MAPS["PIN_PAIRS_DROPPED_UNKNOWN_CONTEXT"],
            pins.PIN_PAIRS_DROPPED_UNKNOWN_CONTEXT,
        )
        self.assertEqual(
            self.KOTLIN_MAPS["PIN_WORD_PENDING_EVICTIONS"], pins.PIN_WORD_PENDING_EVICTIONS
        )
        jvm_unseen = {
            split: tuple(int(cell.strip().rstrip("L")) for cell in cells.split(","))
            for split, cells in re.findall(
                r'"(hash|chrono|rep)" to listOf\(([^)]*)\)', self.KOTLIN
            )
        }
        self.assertEqual(jvm_unseen, pins.PIN_UNSEEN_STEM)

    def test_shared_sweep_pins_are_equal(self):
        self.assertEqual(
            self.KOTLIN_CONSTS["PIN_REP_SWEEP_L100_GRAD_WORDS"],
            pins.PIN_REP_SWEEP[100][0],
        )
        self.assertEqual(
            self.KOTLIN_CONSTS["PIN_REP_SWEEP_L100_OBSERVED"],
            pins.PIN_REP_SWEEP[100][1],
        )
        self.assertEqual(
            self.KOTLIN_CONSTS["PIN_REP_SWEEP_L100_DROPPED"],
            pins.PIN_REP_SWEEP[100][2],
        )
        self.assertEqual(
            self.KOTLIN_CONSTS["PIN_REP_SWEEP_L100_STALED"],
            pins.PIN_REP_SWEEP[100][3],
        )
        self.assertEqual(
            self.KOTLIN_CONSTS["PIN_REP_SWEEP_L200_GRAD_WORDS"],
            pins.PIN_REP_SWEEP[200][0],
        )
        self.assertEqual(
            self.KOTLIN_CONSTS["PIN_REP_SWEEP_L200_OBSERVED"],
            pins.PIN_REP_SWEEP[200][1],
        )
        self.assertEqual(
            self.KOTLIN_CONSTS["PIN_REP_SWEEP_L200_DROPPED"],
            pins.PIN_REP_SWEEP[200][2],
        )
        self.assertEqual(
            self.KOTLIN_CONSTS["PIN_REP_SWEEP_L200_STALED"],
            pins.PIN_REP_SWEEP[200][3],
        )

    def test_shared_harness_constants_are_equal(self):
        pairs = {
            "SPLIT_SEED": harness.SPLIT_SEED,
            "INTERVAL_SEED": harness.INTERVAL_SEED,
            "KEY_INTERVAL_BASE_MS": harness.KEY_INTERVAL_BASE_MS,
            "KEY_INTERVAL_JITTER_MS": harness.KEY_INTERVAL_JITTER_MS,
            "LOOKUP_LATENCY_MS": harness.LOOKUP_LATENCY_MS,
            "REP_PRIME_PASSES": harness.REP_PRIME_PASSES,
            "WORD_LEARN_THRESHOLD": harness.WORD_LEARN_THRESHOLD,
            "PAIR_LEARN_THRESHOLD": harness.PAIR_LEARN_THRESHOLD,
            "PENDING_CAP": harness.PENDING_CAP,
            "WORD_CAP": harness.WORD_CAP,
            "PAIR_CAP": harness.PAIR_CAP,
            "WORD_MIN_CODE_POINTS": harness.WORD_MIN_CODE_POINTS,
            "WORD_MAX_CODE_POINTS": harness.WORD_MAX_CODE_POINTS,
            "PAIR_WORD_MIN_CODE_POINTS": harness.PAIR_WORD_MIN_CODE_POINTS,
            "PAIR_WORD_MAX_CODE_POINTS": harness.PAIR_WORD_MAX_CODE_POINTS,
        }
        for jvm_name, python_value in pairs.items():
            self.assertEqual(
                self.KOTLIN_CONSTS[jvm_name], python_value, f"{jvm_name} drifted"
            )

    def test_shared_thresholds_equal_the_production_constants(self):
        pending = PENDING_COUNTERS.read_text(encoding="utf-8")
        composite = COMPOSITE_PREFIX_COMPUTER.read_text(encoding="utf-8")
        bigram_store = PERSONAL_BIGRAM_STORE.read_text(encoding="utf-8")
        tpers = TPERS_FORMAT.read_text(encoding="utf-8")
        tpersb = TPERSB_FORMAT.read_text(encoding="utf-8")
        self.assertEqual(production_constant(pending, "LEARN_THRESHOLD"), harness.WORD_LEARN_THRESHOLD)
        self.assertEqual(production_constant(pending, "MAX_PENDING"), harness.PENDING_CAP)
        self.assertEqual(production_constant(pending, "LIFETIME_SERIALS"), harness.LIFETIME_SERIALS)
        self.assertEqual(production_constant(bigram_store, "LEARN_THRESHOLD"), harness.PAIR_LEARN_THRESHOLD)
        self.assertEqual(production_constant(composite, "PROMOTION_MIN_USAGE"), harness.PROMOTION_MIN_USAGE)
        self.assertEqual(
            production_constant(composite, "PROMOTION_TOP_FREQUENCY_GATE"),
            harness.PROMOTION_TOP_FREQUENCY_GATE,
        )
        self.assertEqual(
            production_constant(composite, "PERSONAL_BIGRAM_PRECEDENCE_MIN_FREQUENCY"),
            harness.PERSONAL_BIGRAM_PRECEDENCE_MIN_FREQUENCY,
        )
        self.assertEqual(
            production_constant(tpers, "MAX_PERSONAL_ENTRIES"), harness.WORD_CAP
        )
        self.assertEqual(production_constant(tpers, "MIN_WORD_CODE_POINTS"), harness.WORD_MIN_CODE_POINTS)
        self.assertEqual(production_constant(tpers, "MAX_WORD_CODE_POINTS"), harness.WORD_MAX_CODE_POINTS)
        self.assertEqual(
            production_constant(tpersb, "MAX_PERSONAL_BIGRAM_PAIRS"), harness.PAIR_CAP
        )
        self.assertEqual(
            production_constant(tpersb, "MIN_WORD_CODE_POINTS"), harness.PAIR_WORD_MIN_CODE_POINTS
        )
        self.assertEqual(
            production_constant(tpersb, "MAX_WORD_CODE_POINTS"), harness.PAIR_WORD_MAX_CODE_POINTS
        )


if __name__ == "__main__":
    unittest.main()
