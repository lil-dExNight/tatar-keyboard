#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).resolve().parent / "fixtures"
PACK_SCRIPT = ROOT / "scripts" / "emoji_pack.py"
ASSET = ROOT / "app" / "src" / "main" / "assets" / "emoji" / "emoji_set_v1.txt"
NOTICE = ASSET.parent / "NOTICE.txt"

# The Unicode input is deliberately not committed; the orchestrator provides it.
INPUT = Path(os.environ.get("EMOJI_TEST_TXT", "/tmp/emoji-test-15.1.txt"))

SAMPLE = FIXTURES / "emoji_sample.txt"
VERSION_MISMATCH = FIXTURES / "version_mismatch.txt"
UNKNOWN_GROUP = FIXTURES / "unknown_group.txt"
DUPLICATE_SEQUENCE = FIXTURES / "duplicate_sequence.txt"

# Values pinned by docs/DICTIONARY-E2.md for the committed asset.
COMMITTED_ENTRY_COUNT = 1389
COMMITTED_ASSET_BYTES = 7540
EXPECTED_INPUT_SHA256 = (
    "d876ee249aa28eaa76cfa6dfaa702847a8d13b062aa488d465d0395ee8137ed9"
)


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


pack = load_module("emoji_pack", PACK_SCRIPT)


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def seq(*codepoints: int) -> str:
    return "".join(chr(cp) for cp in codepoints)


@contextlib.contextmanager
def patched(**overrides):
    saved = {name: getattr(pack, name) for name in overrides}
    for name, value in overrides.items():
        setattr(pack, name, value)
    try:
        yield
    finally:
        for name, value in saved.items():
            setattr(pack, name, value)


def run_main(input_path: Path, output_path: Path, **overrides) -> int:
    """Invoke the CLI entry point capturing its streams; returns the exit code."""
    with patched(**overrides), contextlib.redirect_stdout(io.StringIO()), \
            contextlib.redirect_stderr(io.StringIO()):
        return pack.main(
            ["build", "--input", str(input_path), "--output", str(output_path)]
        )


# Three concrete examples of each excluded class, as code-point sequences.
SKIN_TONE_EXAMPLES = (
    seq(0x1F44B, 0x1F3FB),
    seq(0x1F44D, 0x1F3FC),
    seq(0x1F64C, 0x1F3FF),
)
ZWJ_EXAMPLES = (
    seq(0x1F468, 0x200D, 0x1F4BB),
    seq(0x1F469, 0x200D, 0x1F692),
    seq(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F466),
)
REGIONAL_EXAMPLES = (
    seq(0x1F1E6, 0x1F1E8),
    seq(0x1F1FA, 0x1F1F8),
    seq(0x1F1EF, 0x1F1F5),
)
TAG_EXAMPLES = (
    seq(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F),
    seq(0x1F3F4, 0xE0067, 0xE0062, 0xE0073, 0xE0063, 0xE0074, 0xE007F),
    seq(0x1F3F4, 0xE0067, 0xE0062, 0xE0077, 0xE006C, 0xE0073, 0xE007F),
)
KEYCAP_KEPT = seq(0x0023, 0xFE0F, 0x20E3)  # #\uFE0F\u20E3
VS16_KEPT = (seq(0x263A, 0xFE0F), seq(0x1F3F3, 0xFE0F))


class SlugRuleTest(unittest.TestCase):
    def test_slug_rule_maps_all_known_groups(self) -> None:
        expected = {
            "Smileys & Emotion": "smileys-emotion",
            "People & Body": "people-body",
            "Component": "component",
            "Animals & Nature": "animals-nature",
            "Food & Drink": "food-drink",
            "Travel & Places": "travel-places",
            "Activities": "activities",
            "Objects": "objects",
            "Symbols": "symbols",
            "Flags": "flags",
        }
        self.assertEqual(set(pack.KNOWN_GROUPS), set(expected))
        for group, slug in expected.items():
            self.assertEqual(pack.slug_for_group(group), slug)

    def test_slugs_are_unique(self) -> None:
        slugs = [pack.slug_for_group(group) for group in pack.KNOWN_GROUPS]
        self.assertEqual(len(slugs), len(set(slugs)))


class FilterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.sections = pack.parse_sections(SAMPLE.read_text(encoding="utf-8"))
        self.all_sequences = [
            sequence for section in self.sections for sequence in section.sequences
        ]

    def test_skin_tone_examples_are_cut(self) -> None:
        for example in SKIN_TONE_EXAMPLES:
            with self.subTest(example=example):
                self.assertNotIn(example, self.all_sequences)

    def test_zwj_examples_are_cut(self) -> None:
        for example in ZWJ_EXAMPLES:
            with self.subTest(example=example):
                self.assertNotIn(example, self.all_sequences)

    def test_regional_indicator_examples_are_cut(self) -> None:
        for example in REGIONAL_EXAMPLES:
            with self.subTest(example=example):
                self.assertNotIn(example, self.all_sequences)

    def test_tag_examples_are_cut(self) -> None:
        for example in TAG_EXAMPLES:
            with self.subTest(example=example):
                self.assertNotIn(example, self.all_sequences)

    def test_keycap_is_kept(self) -> None:
        self.assertIn(KEYCAP_KEPT, self.all_sequences)

    def test_single_vs16_emoji_are_kept(self) -> None:
        for example in VS16_KEPT:
            with self.subTest(example=example):
                self.assertIn(example, self.all_sequences)

    def test_status_filter_drops_non_fully_qualified(self) -> None:
        # 1F603 (minimally-qualified) and lone 263A (unqualified) must be absent.
        self.assertNotIn(seq(0x1F603), self.all_sequences)
        self.assertNotIn(seq(0x263A), self.all_sequences)

    def test_section_order_slugs_and_within_section_order(self) -> None:
        self.assertEqual(
            [section.slug for section in self.sections],
            ["smileys-emotion", "people-body", "symbols", "flags"],
        )
        smileys = self.sections[0]
        self.assertEqual(
            smileys.sequences, (seq(0x1F600), seq(0x1F642), seq(0x263A, 0xFE0F))
        )
        counts = {section.slug: len(section.sequences) for section in self.sections}
        self.assertEqual(counts, {
            "smileys-emotion": 3, "people-body": 1, "symbols": 3, "flags": 2
        })

    def test_component_group_produces_no_section(self) -> None:
        self.assertNotIn("component", [s.slug for s in self.sections])


class FormatTest(unittest.TestCase):
    def test_header_regex_disambiguates_hash_keycap(self) -> None:
        self.assertTrue(pack.SECTION_HEADER_RE.match("#smileys-emotion"))
        self.assertTrue(pack.SECTION_HEADER_RE.match("#flags"))
        # The number-sign keycap starts with '#' but must NOT read as a header.
        self.assertFalse(pack.SECTION_HEADER_RE.match(KEYCAP_KEPT))

    def test_render_is_lf_terminated_one_per_line(self) -> None:
        emoji_set = pack.build_emoji_set(SAMPLE, expected_sha256=sha256_of(SAMPLE))
        text = emoji_set.text
        self.assertTrue(text.endswith("\n"))
        self.assertNotIn("\r", text)
        lines = text.splitlines()
        headers = [line for line in lines if pack.SECTION_HEADER_RE.match(line)]
        self.assertEqual(len(headers), 4)
        # The only non-header line beginning with '#' is the number-sign keycap.
        hash_lines = [line for line in lines if line.startswith("#")]
        non_header_hash = [
            line for line in hash_lines if not pack.SECTION_HEADER_RE.match(line)
        ]
        self.assertEqual(non_header_hash, [KEYCAP_KEPT])

    def test_no_blank_or_stray_lines(self) -> None:
        emoji_set = pack.build_emoji_set(SAMPLE, expected_sha256=sha256_of(SAMPLE))
        for line in emoji_set.text.splitlines():
            self.assertNotEqual(line, "")


class DeterminismTest(unittest.TestCase):
    def test_repeated_builds_are_byte_identical(self) -> None:
        sha = sha256_of(SAMPLE)
        first = pack.build_emoji_set(SAMPLE, expected_sha256=sha)
        second = pack.build_emoji_set(SAMPLE, expected_sha256=sha)
        self.assertEqual(first.data, second.data)
        self.assertEqual(first.sha256, second.sha256)


class FailClosedCoreTest(unittest.TestCase):
    def test_sha_mismatch_raises(self) -> None:
        with self.assertRaises(pack.EmojiPackError):
            pack.build_emoji_set(SAMPLE, expected_sha256="00" * 32)

    def test_version_mismatch_raises(self) -> None:
        with self.assertRaises(pack.EmojiPackError):
            pack.build_emoji_set(
                VERSION_MISMATCH, expected_sha256=sha256_of(VERSION_MISMATCH)
            )

    def test_unknown_category_raises(self) -> None:
        with self.assertRaises(pack.EmojiPackError):
            pack.build_emoji_set(
                UNKNOWN_GROUP, expected_sha256=sha256_of(UNKNOWN_GROUP)
            )

    def test_duplicate_sequence_raises(self) -> None:
        with self.assertRaises(pack.EmojiPackError):
            pack.build_emoji_set(
                DUPLICATE_SEQUENCE, expected_sha256=sha256_of(DUPLICATE_SEQUENCE)
            )

    def test_invalid_utf8_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bad = Path(directory) / "invalid.txt"
            bad.write_bytes(b"# Version: 15.1\n# group: Symbols\n\xff\xfe not utf8\n")
            with self.assertRaises(pack.EmojiPackError):
                pack.build_emoji_set(bad, expected_sha256=sha256_of(bad))

    def test_entry_guardrail_raises(self) -> None:
        with self.assertRaises(pack.EmojiGuardrailError):
            pack.build_emoji_set(
                SAMPLE, expected_sha256=sha256_of(SAMPLE), max_entries=8
            )

    def test_byte_guardrail_raises(self) -> None:
        with self.assertRaises(pack.EmojiGuardrailError):
            pack.build_emoji_set(
                SAMPLE, expected_sha256=sha256_of(SAMPLE), max_bytes=10
            )


class FailClosedExitCodeTest(unittest.TestCase):
    """Every fail-closed path returns a nonzero exit code and writes no asset."""

    def _out(self, directory: str) -> Path:
        return Path(directory) / "emoji_set_v1.txt"

    def test_sha_mismatch_exits_nonzero_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out = self._out(directory)
            code = run_main(SAMPLE, out)  # sample sha != pinned default
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_version_mismatch_exits_nonzero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out = self._out(directory)
            code = run_main(
                VERSION_MISMATCH, out,
                EXPECTED_INPUT_SHA256=sha256_of(VERSION_MISMATCH),
            )
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_unknown_category_exits_nonzero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out = self._out(directory)
            code = run_main(
                UNKNOWN_GROUP, out, EXPECTED_INPUT_SHA256=sha256_of(UNKNOWN_GROUP)
            )
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_duplicate_sequence_exits_nonzero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out = self._out(directory)
            code = run_main(
                DUPLICATE_SEQUENCE, out,
                EXPECTED_INPUT_SHA256=sha256_of(DUPLICATE_SEQUENCE),
            )
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_invalid_utf8_exits_nonzero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bad = Path(directory) / "invalid.txt"
            bad.write_bytes(b"# Version: 15.1\n# group: Symbols\n\xff\xfe\n")
            out = self._out(directory)
            code = run_main(bad, out, EXPECTED_INPUT_SHA256=sha256_of(bad))
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_entry_guardrail_exits_nonzero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out = self._out(directory)
            code = run_main(
                SAMPLE, out, EXPECTED_INPUT_SHA256=sha256_of(SAMPLE), MAX_ENTRIES=8
            )
            self.assertEqual(code, 4)
            self.assertFalse(out.exists())

    def test_byte_guardrail_exits_nonzero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out = self._out(directory)
            code = run_main(
                SAMPLE, out, EXPECTED_INPUT_SHA256=sha256_of(SAMPLE), MAX_ASSET_BYTES=10
            )
            self.assertEqual(code, 4)
            self.assertFalse(out.exists())

    def test_failure_does_not_publish_partial_asset(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out = self._out(directory)
            out.write_bytes(b"OLD CONTENT")
            code = run_main(SAMPLE, out)  # sha mismatch, fails closed
            self.assertEqual(code, 2)
            self.assertEqual(out.read_bytes(), b"OLD CONTENT")

    def test_successful_build_writes_asset(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out = self._out(directory)
            code = run_main(SAMPLE, out, EXPECTED_INPUT_SHA256=sha256_of(SAMPLE))
            self.assertEqual(code, 0)
            self.assertTrue(out.exists())
            expected = pack.build_emoji_set(
                SAMPLE, expected_sha256=sha256_of(SAMPLE)
            )
            self.assertEqual(out.read_bytes(), expected.data)


class CommittedAssetTest(unittest.TestCase):
    def test_asset_shape_headers_and_entry_count(self) -> None:
        text = ASSET.read_text(encoding="utf-8")
        self.assertTrue(text.endswith("\n"))
        self.assertNotIn("\r", text)
        lines = text.splitlines()
        headers = [line for line in lines if pack.SECTION_HEADER_RE.match(line)]
        self.assertEqual(len(headers), 9)
        entries = [line for line in lines if not pack.SECTION_HEADER_RE.match(line)]
        self.assertEqual(len(entries), COMMITTED_ENTRY_COUNT)
        # Exactly one entry line begins with '#': the number-sign keycap.
        hash_entries = [line for line in entries if line.startswith("#")]
        self.assertEqual(hash_entries, [KEYCAP_KEPT])
        self.assertEqual(len(ASSET.read_bytes()), COMMITTED_ASSET_BYTES)
        self.assertLessEqual(len(ASSET.read_bytes()), pack.MAX_ASSET_BYTES)
        self.assertLessEqual(COMMITTED_ENTRY_COUNT, pack.MAX_ENTRIES)

    def test_no_excluded_class_survives_in_committed_asset(self) -> None:
        text = ASSET.read_text(encoding="utf-8")
        for sequence in text.splitlines():
            if pack.SECTION_HEADER_RE.match(sequence):
                continue
            codepoints = [ord(ch) for ch in sequence]
            self.assertFalse(
                pack._is_excluded(codepoints),
                msg=f"excluded class survived: {sequence!r}",
            )

    @unittest.skipUnless(INPUT.exists(), "Unicode emoji-test.txt input not available")
    def test_committed_asset_matches_regeneration(self) -> None:
        emoji_set = pack.build_emoji_set(INPUT)  # pinned defaults
        self.assertEqual(emoji_set.data, ASSET.read_bytes())
        self.assertEqual(emoji_set.entry_count, COMMITTED_ENTRY_COUNT)
        self.assertEqual(emoji_set.byte_size, COMMITTED_ASSET_BYTES)

    def test_provenance_notice(self) -> None:
        notice = NOTICE.read_text(encoding="utf-8")
        # NOTICE carries the Unicode attribution and version taken from the input header.
        self.assertIn("Unicode", notice)
        self.assertIn("15.1", notice)
        self.assertIn("https://www.unicode.org/terms_of_use.html", notice)


if __name__ == "__main__":
    unittest.main()
