#!/usr/bin/env python3
"""Tests for scripts/check_profiles.py.

The parser is checked on golden lines of the profgen human-readable format and on malformed
lines that must fail, the split (differ/subset) legs on fixture rule lists, the resolved-ratio
math and the R8 mapping reader on stub data, the DEX reader on synthetic headers, and the
profgen leg on fake binaries. The DEX reader also runs against the real debug APK; that test
SKIPs when the APK was not built (same convention as tests/suggest_eval for absent corpus
inputs).
"""
from __future__ import annotations

import importlib.util
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEBUG_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


cp = load_module("check_profiles", ROOT / "scripts" / "check_profiles.py")

GOLDEN_LINES = [
    # class rules
    ("Lkotlin/Lazy;", ("", "Lkotlin/Lazy;", "", "", "")),
    ("Lkotlin/LazyKt__LazyJVMKt$WhenMappings;", ("", "Lkotlin/LazyKt__LazyJVMKt$WhenMappings;", "", "", "")),
    # method rules in the flag combinations the generator emits
    ("HSPLkotlin/LazyKt;->lazy(Lkotlin/LazyThreadSafetyMode;Lkotlin/jvm/functions/Function0;)Lkotlin/Lazy;",
     ("HSP", "Lkotlin/LazyKt;", "lazy",
      "Lkotlin/LazyThreadSafetyMode;Lkotlin/jvm/functions/Function0;", "Lkotlin/Lazy;")),
    ("HSPLkotlin/LazyThreadSafetyMode;-><clinit>()V",
     ("HSP", "Lkotlin/LazyThreadSafetyMode;", "<clinit>", "", "V")),
    ("HSPLkotlin/LazyThreadSafetyMode;->$values()[Lkotlin/LazyThreadSafetyMode;",
     ("HSP", "Lkotlin/LazyThreadSafetyMode;", "$values", "", "[Lkotlin/LazyThreadSafetyMode;")),
    ("HSPLkotlin/Pair;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V",
     ("HSP", "Lkotlin/Pair;", "<init>", "Ljava/lang/Object;Ljava/lang/Object;", "V")),
    ("PLandroidx/annotation/InspectableProperty$ValueType;->toString()Ljava/lang/String;",
     ("P", "Landroidx/annotation/InspectableProperty$ValueType;", "toString", "",
      "Ljava/lang/String;")),
    ("HPLkotlin/Unit;-><clinit>()V", ("HP", "Lkotlin/Unit;", "<clinit>", "", "V")),
    # array parameters
    ("HSPLfoo/Bar;->baz([I[[Ljava/lang/String;)V",
     ("HSP", "Lfoo/Bar;", "baz", "[I[[Ljava/lang/String;", "V")),
    # trailing whitespace is tolerated
    ("Lkotlin/Lazy;  ", ("", "Lkotlin/Lazy;", "", "", "")),
    # wildcards are legal syntax; the rule is fuzzy and never resolves exactly
    ("HSPLkotlin/*;->m()V", ("HSP", "Lkotlin/*;", "m", "", "V")),
]

SKIPPED_LINES = [
    "",
    "   ",
    "# a comment",
    "[Lfoo/Bar;",  # array class line from Android S+
    "HSPLfoo/Bar;->baz()V+inline-cache-entry",  # inline cache suffix
]

MALFORMED_LINES = [
    "HLfoo/Bar;",  # flags on a class rule
    "Lfoo/Bar;->m()V",  # method rule without flags
    "Lfoo/Bar",  # unterminated class descriptor
    "HSPfoo/Bar;->m()V",  # class descriptor without 'L'
    "XLfoo/Bar;->m()V",  # unknown flag
    "HSPLfoo/Bar;->m(Ljava/lang/String)V",  # unterminated parameter descriptor
    "HSPLfoo/Bar;->m()V junk",  # trailing text
    "HSPLfoo/Bar;->m()Q",  # unknown return type
    "HSPLfoo/Bar;->m(V)V",  # void parameter
    "HSPLfoo/Bar;->m()[V",  # void array return type
    "HSPLfoo/Bar;->()V",  # empty method name
    "HSPLfoo/Bar;->m(",  # missing ')'
    "HSPLfoo/Bar;",  # flags on a class rule, all flags
    "foo",  # garbage
    "Lfoo/Bar; junk",  # text after a class rule
    "HSPL;",  # empty class descriptor
]


class GoldenLinesTest(unittest.TestCase):
    def test_golden_lines_parse(self) -> None:
        for line, expected in GOLDEN_LINES:
            with self.subTest(line=line):
                rule = cp.parse_rule(line, 1)
                self.assertIsNotNone(rule)
                assert rule is not None
                self.assertEqual(
                    (rule.flags, rule.class_descriptor, rule.method_name,
                     rule.params, rule.return_type), expected)

    def test_wildcard_rule_is_fuzzy(self) -> None:
        rule = cp.parse_rule("HSPLkotlin/*;->m()V", 1)
        assert rule is not None
        self.assertTrue(rule.is_fuzzy)
        exact = cp.parse_rule("Lkotlin/Lazy;", 1)
        assert exact is not None
        self.assertFalse(exact.is_fuzzy)

    def test_skipped_lines_parse_to_none(self) -> None:
        for line in SKIPPED_LINES:
            with self.subTest(line=line):
                self.assertIsNone(cp.parse_rule(line, 1))


class MalformedLinesTest(unittest.TestCase):
    def test_malformed_lines_fail(self) -> None:
        for line in MALFORMED_LINES:
            with self.subTest(line=line):
                with self.assertRaises(cp.ProfileError):
                    cp.parse_rule(line, 1)

    def test_error_carries_coordinates(self) -> None:
        with self.assertRaises(cp.ProfileError) as ctx:
            cp.parse_rule("HSPfoo/Bar;->m()V", 7)
        self.assertEqual(ctx.exception.line_no, 7)
        self.assertIn("4", str(ctx.exception.column))


class ParseProfileFileTest(unittest.TestCase):
    def write(self, directory: str, text: str) -> Path:
        path = Path(directory) / "baseline-prof.txt"
        path.write_text(text, encoding="utf-8")
        return path

    def test_file_with_two_errors_reports_both(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write(tmp, "Lkotlin/Lazy;\nfoo\nHSPLbar/Baz;->m()Q\n")
            with self.assertRaises(cp.ProfileErrors) as ctx:
                cp.parse_profile(path)
            self.assertEqual([e.line_no for e in ctx.exception.errors], [2, 3])

    def test_file_with_one_error_raises_single(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write(tmp, "Lkotlin/Lazy;\nfoo\n")
            with self.assertRaises(cp.ProfileError):
                cp.parse_profile(path)


class MappingTest(unittest.TestCase):
    def test_class_lines_only(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mapping.txt"
            path.write_text(
                "# compiler: R8\n"
                "kotlin.Result$Failure -> sa:\n"
                "    private final java.lang.Object value -> a\n"
                "kotlin.ExceptionsKt -> R8$$REMOVED$$CLASS$$0:\n",
                encoding="utf-8")
            mapping = cp.load_r8_mapping(path)
        self.assertEqual(mapping, {
            "Lkotlin/Result$Failure;": "Lsa;",
            "Lkotlin/ExceptionsKt;": "LR8$$REMOVED$$CLASS$$0;",
        })

    def test_malformed_class_line_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mapping.txt"
            path.write_text("kotlin.Result no arrow here\n", encoding="utf-8")
            with self.assertRaises(cp.DexError):
                cp.load_r8_mapping(path)

    def test_empty_mapping_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mapping.txt"
            path.write_text("# only comments\n", encoding="utf-8")
            with self.assertRaises(cp.DexError):
                cp.load_r8_mapping(path)


class RatioTest(unittest.TestCase):
    def test_translation_and_resolution(self) -> None:
        rules = {"La;", "Lb;", "Lc;", "Ld;"}
        mapping = {"Lb;": "Lx;", "Lc;": "LR8$$REMOVED$$CLASS$$0;"}
        resolved, ratio = cp.resolved_ratio(rules, mapping, {"La;", "Lx;"})
        self.assertEqual(resolved, 2)
        self.assertAlmostEqual(ratio, 0.5)

    def test_empty_rules_give_zero(self) -> None:
        self.assertEqual(cp.resolved_ratio(set(), {}, {"La;"}), (0, 0.0))


def rules_of(lines: list[str]) -> list:
    rules = []
    for line in lines:
        rule = cp.parse_rule(line, 1)
        if rule is not None:
            rules.append(rule)
    return rules


BASELINE_FIXTURE = rules_of([
    "Lkotlin/Lazy;",
    "Lfoo/App;",
    "HSPLkotlin/LazyKt;->lazy(Lkotlin/jvm/functions/Function0;)Lkotlin/Lazy;",
    "HPLfoo/App;->onCreate()V",
])
# The startup journey is a prefix of the full one; flags may differ between the passes.
STARTUP_FIXTURE = rules_of([
    "Lkotlin/Lazy;",
    "SPLkotlin/LazyKt;->lazy(Lkotlin/jvm/functions/Function0;)Lkotlin/Lazy;",
    "SPLfoo/App;->onCreate()V",
])


def legs_by_name(legs: list) -> dict:
    return {name: (status, detail) for status, name, detail in legs}


class SplitReportTest(unittest.TestCase):
    def test_strict_subset_passes_both_legs(self) -> None:
        legs = legs_by_name(cp.split_report(BASELINE_FIXTURE, STARTUP_FIXTURE, False))
        status, detail = legs["differ"]
        self.assertEqual(status, "PASS")
        self.assertIn("baseline-only rules: 1", detail)
        self.assertIn("startup-only rules: 0", detail)
        status, detail = legs["subset"]
        self.assertEqual(status, "PASS")
        self.assertIn("all 3 startup rules", detail)

    def test_byte_identical_files_fail_differ(self) -> None:
        legs = legs_by_name(cp.split_report(BASELINE_FIXTURE, BASELINE_FIXTURE, True))
        status, detail = legs["differ"]
        self.assertEqual(status, "FAIL")
        self.assertIn("byte-identical", detail)
        # Equal sets are still a subset: the subset leg stays green, differ is the signal.
        self.assertEqual(legs["subset"][0], "PASS")

    def test_flags_only_difference_still_fails_differ(self) -> None:
        same_rules_other_flags = rules_of([
            "Lkotlin/Lazy;",
            "Lfoo/App;",
            "PLkotlin/LazyKt;->lazy(Lkotlin/jvm/functions/Function0;)Lkotlin/Lazy;",
            "SPLfoo/App;->onCreate()V",
        ])
        legs = legs_by_name(cp.split_report(BASELINE_FIXTURE, same_rules_other_flags, False))
        status, detail = legs["differ"]
        self.assertEqual(status, "FAIL")
        self.assertIn("same rule set", detail)
        self.assertEqual(legs["subset"][0], "PASS")

    def test_startup_only_rule_fails_subset(self) -> None:
        startup_extra = STARTUP_FIXTURE + rules_of(["Lfoo/StartupOnly;"])
        legs = legs_by_name(cp.split_report(BASELINE_FIXTURE, startup_extra, False))
        status, detail = legs["subset"]
        self.assertEqual(status, "FAIL")
        self.assertIn("startup rules missing from baseline-prof.txt: 1", detail)
        self.assertIn("Lfoo/StartupOnly;", detail)
        self.assertEqual(legs["differ"][0], "PASS")

    def test_rule_identity_ignores_flags(self) -> None:
        hot = cp.parse_rule("HSPLfoo/Bar;->m(I)V", 1)
        startup = cp.parse_rule("SLfoo/Bar;->m(I)V", 1)
        assert hot is not None and startup is not None
        self.assertEqual(cp.rule_identity(hot), cp.rule_identity(startup))
        clazz = cp.parse_rule("Lfoo/Bar;", 1)
        assert clazz is not None
        self.assertEqual(cp.rule_identity(clazz), "Lfoo/Bar;")


class ClassRuleSetsTest(unittest.TestCase):
    def test_exact_and_fuzzy_partition(self) -> None:
        rules = rules_of([
            "Lkotlin/Lazy;",
            "Lkotlin/*;",
            "HSPLkotlin/LazyKt;->lazy()V",  # method rule, not a class rule
        ])
        exact, fuzzy = cp.class_rule_sets(rules)
        self.assertEqual(exact, {"Lkotlin/Lazy;"})
        self.assertEqual(fuzzy, {"Lkotlin/*;"})


def minimal_dex(string_ids_size: int = 0, class_defs_size: int = 0, file_size: int = 0x70) -> bytes:
    """A valid dex header with empty tables; sizes land where the reader looks for them."""
    data = bytearray(0x70)
    data[0:8] = b"dex\n035\0"
    data[0x20:0x24] = file_size.to_bytes(4, "little")
    data[0x24:0x28] = (0x70).to_bytes(4, "little")
    data[0x28:0x2C] = (0x12345678).to_bytes(4, "little")
    data[0x38:0x3C] = string_ids_size.to_bytes(4, "little")
    data[0x3C:0x40] = (0x70).to_bytes(4, "little")
    data[0x40:0x44] = (0).to_bytes(4, "little")
    data[0x44:0x48] = (0x70).to_bytes(4, "little")
    data[0x60:0x64] = class_defs_size.to_bytes(4, "little")
    data[0x64:0x68] = (0x70).to_bytes(4, "little")
    return bytes(data)


class DexReaderTest(unittest.TestCase):
    """Synthetic headers; the real-APK test is DexReaderApkTest below."""

    def test_empty_dex_parses_to_no_classes(self) -> None:
        self.assertEqual(cp.dex_class_descriptors(minimal_dex()), set())

    def test_bad_magic_fails(self) -> None:
        with self.assertRaises(cp.DexError):
            cp.dex_class_descriptors(b"dex\n999\0" + bytes(0x68))
        with self.assertRaises(cp.DexError):
            cp.dex_class_descriptors(b"not a dex")

    def test_region_outside_file_fails(self) -> None:
        with self.assertRaises(cp.DexError):
            cp.dex_class_descriptors(minimal_dex(class_defs_size=1))

    def test_wrong_endian_tag_fails(self) -> None:
        data = bytearray(minimal_dex())
        data[0x28:0x2C] = (0x78563412).to_bytes(4, "little")
        with self.assertRaises(cp.DexError):
            cp.dex_class_descriptors(bytes(data))

    def test_truncated_file_fails(self) -> None:
        with self.assertRaises(cp.DexError):
            cp.dex_class_descriptors(minimal_dex(file_size=0x7000))


class DexReaderApkTest(unittest.TestCase):
    """The reader against the real debug APK; SKIPs when the APK was not built."""

    def setUp(self) -> None:
        if not DEBUG_APK.is_file():
            self.skipTest(f"{DEBUG_APK} is not built on this checkout")

    def test_finds_ime_class_in_debug_apk(self) -> None:
        descriptors, dex_count = cp.apk_class_descriptors(DEBUG_APK)
        self.assertGreaterEqual(dex_count, 1)
        self.assertIn("Lrkr/simplekeyboard/inputmethod/latin/LatinIME;", descriptors)

    def test_non_apk_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "fake.apk"
            path.write_bytes(b"not a zip")
            with self.assertRaises(cp.DexError):
                cp.apk_class_descriptors(path)


class ProfgenLegTest(unittest.TestCase):
    def fake_profgen(self, directory: str, stderr: str, code: int) -> Path:
        path = Path(directory) / "profgen"
        path.write_text(f"#!/bin/sh\necho '{stderr}' >&2\nexit {code}\n", encoding="utf-8")
        path.chmod(path.stat().st_mode | stat.S_IXUSR)
        return path

    def test_absent_binary_skips(self) -> None:
        status, _ = cp.profgen_validate(None, [])
        self.assertEqual(status, "SKIP")

    def test_silent_success_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            profile = Path(tmp) / "p.txt"
            profile.write_text("Lkotlin/Lazy;\n", encoding="utf-8")
            status, _ = cp.profgen_validate(self.fake_profgen(tmp, "", 0), [profile])
        self.assertEqual(status, "PASS")

    def test_stderr_is_a_failure_even_on_exit_zero(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            profile = Path(tmp) / "p.txt"
            profile.write_text("Lkotlin/Lazy;\n", encoding="utf-8")
            status, detail = cp.profgen_validate(
                self.fake_profgen(tmp, "p.txt:1:1 error: Illegal token", 0), [profile])
        self.assertEqual(status, "FAIL")
        self.assertIn("Illegal token", detail)

    def test_find_profgen_explicit_missing_disables(self) -> None:
        self.assertIsNone(cp.find_profgen(""))
        self.assertIsNone(cp.find_profgen("/nonexistent/profgen"))


if __name__ == "__main__":
    unittest.main()
