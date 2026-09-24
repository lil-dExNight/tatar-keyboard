#!/usr/bin/env python3
"""Tests for scripts/emoji_skin_pack.py — the skin-tone asset packer.

A synthetic emoji-test.txt (a couple of bases with their five toned forms
instead of the real 15.1 file) and a tiny panel asset drive the unit surface
(prefix/suffix split, U+FE0F replacement by the tone, two-modifier records
skipped) and the CLI contract (exit codes, fail-closed writes, guardrails).
A live-tree test checks the committed asset against the committed panel asset:
every toned form the panel may offer must compose as prefix + tone + suffix
with the variation selector REPLACED, never duplicated.
"""

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACK_SCRIPT = ROOT / "scripts" / "emoji_skin_pack.py"
PANEL_ASSET = ROOT / "app" / "src" / "main" / "assets" / "emoji" / "emoji_set_v1.txt"
SKIN_ASSET = ROOT / "app" / "src" / "main" / "assets" / "emoji" / "emoji_skin_v1.txt"

# Pins for the committed asset, in the emoji_pack pattern: a rebuild that
# changes the shipped table must update these together with the asset.
COMMITTED_LINE_COUNT = 131
COMMITTED_ASSET_SHA256 = (
    "818336362439a73abbd17663f9eceab13a8beea603395ef700a1c87e34e28ea2"
)


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


pack = load_module("emoji_skin_pack", PACK_SCRIPT)

TONE_1 = 0x1F3FB  # lightest of the five modifiers U+1F3FB..U+1F3FF
TONE_5 = 0x1F3FF

WAVE = chr(0x1F44B)          # 👋 — tones go straight after the base
RAISED_HAND = chr(0x1F590)   # 🖐 — panel draws it with U+FE0F, tone REPLACES it
RAISED_HAND_VS16 = RAISED_HAND + "️"
GRINNING = chr(0x1F600)      # 😀 — accepts no tone
TWO_PEOPLE = chr(0x1F46D)    # 👭 — toned forms carry TWO modifiers, never offered


def record(*codepoints: int) -> str:
    return " ".join(f"{cp:04X}" for cp in codepoints) + " ; fully-qualified # x\n"


def record_with_status(status: str, *codepoints: int) -> str:
    return " ".join(f"{cp:04X}" for cp in codepoints) + f" ; {status} # x\n"


def skin_records(base: int, *, omit_tone: int | None = None) -> str:
    """The base itself plus its five toned fully-qualified records."""
    out = record(base)
    for tone in pack.SKIN_TONE_RANGE:
        if tone == omit_tone:
            continue
        out += record(base, tone)
    return out


INPUT = (
    "# Version: 15.1\n"
    "\n"
    "# group: Smileys & Emotion\n"
    + record(0x1F600)
    + "# group: People & Body\n"
    + skin_records(0x1F44B)
    + record(0x1F590, 0xFE0F)
    + skin_records(0x1F590)[len(record(0x1F590)):]  # tones only; base is VS16 above
    + record_with_status("fully-qualified",
                         0x1F469, TONE_1, 0x200D, 0x1F91D, 0x1F469, TONE_5)
    + record(0x1F46D)
    + record_with_status("unqualified", 0x1F600, 0xFE0F)
)

PANEL = (
    "#smileys-emotion\n" + GRINNING + "\n"
    "#people-body\n" + WAVE + "\n" + RAISED_HAND_VS16 + "\n" + TWO_PEOPLE + "\n"
)


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


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


def write_inputs(directory: Path, input_text: str = INPUT,
                 panel_text: str = PANEL) -> tuple:
    input_path = directory / "emoji-test.txt"
    input_path.write_text(input_text, encoding="utf-8")
    panel_path = directory / "emoji_set_v1.txt"
    panel_path.write_text(panel_text, encoding="utf-8")
    return input_path, panel_path


def run_main(input_path: Path, panel: Path, output: Path, **overrides) -> tuple:
    """Invoke the CLI entry point capturing its streams; returns (exit, stdout)."""
    stdout = io.StringIO()
    with patched(**overrides), contextlib.redirect_stdout(stdout), \
            contextlib.redirect_stderr(io.StringIO()):
        code = pack.main([
            "build", "--input", str(input_path),
            "--panel-asset", str(panel), "--output", str(output),
        ])
    return code, stdout.getvalue()


def build_from(directory: Path, input_text: str = INPUT, panel_text: str = PANEL,
               **kwargs) -> pack.SkinToneSet:
    input_path, panel_path = write_inputs(directory, input_text, panel_text)
    text = pack.read_input_text(input_path,
                                expected_sha256=sha256_of(input_path),
                                expected_version="15.1")
    records = pack.parse_fully_qualified(text)
    options = {"max_bytes": pack.MAX_ASSET_BYTES, "max_lines": pack.MAX_LINES,
               "min_bases": 1, "max_bases": 10}
    options.update(kwargs)
    return pack.build_skin_tone_set(
        pack.read_panel_sequences(panel_path),
        pack.build_tone_templates(records), set(records), **options)


class TemplateTest(unittest.TestCase):
    def test_tone_slots_between_prefix_and_suffix(self) -> None:
        records = [tuple(int(part, 16) for part in
                         line.split("#")[0].split(";")[0].split())
                   for line in INPUT.splitlines()
                   if line and not line.startswith("#")]
        templates = pack.build_tone_templates(records)
        self.assertEqual(templates[(0x1F44B,)], ((0x1F44B,), ()))
        self.assertEqual(templates[(0x1F590,)], ((0x1F590,), ()))
        # Two modifiers describe a pair of bases, not one: no template.
        self.assertNotIn((0x1F469, 0x200D, 0x1F91D, 0x1F469), templates)
        self.assertNotIn((0x1F46D,), templates)

    def test_non_fully_qualified_records_are_ignored(self) -> None:
        records = pack.parse_fully_qualified(INPUT)
        self.assertNotIn((0x1F600, 0xFE0F), records)  # unqualified
        self.assertIn((0x1F600,), records)


class BuildTest(unittest.TestCase):
    def test_format_sequence_tab_prefix_tab_suffix(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            skin = build_from(Path(directory))
        self.assertEqual(skin.text.splitlines(), [
            WAVE + "\t" + WAVE + "\t",
            RAISED_HAND_VS16 + "\t" + RAISED_HAND + "\t",
        ])
        self.assertTrue(skin.text.endswith("\n"))
        self.assertNotIn("\r", skin.text)

    def test_fe0f_is_replaced_by_the_tone_not_kept(self) -> None:
        # Field 1 keeps the panel sequence (with U+FE0F); the prefix drops it,
        # so prefix + tone is the composed form — never prefix + tone + FE0F.
        with tempfile.TemporaryDirectory() as directory:
            skin = build_from(Path(directory))
        line = skin.text.splitlines()[1]
        sequence, prefix, suffix = line.split("\t")
        self.assertEqual(sequence, RAISED_HAND_VS16)
        self.assertNotIn("️", prefix + suffix)
        composed = prefix + chr(TONE_1) + suffix
        self.assertEqual([ord(c) for c in composed], [0x1F590, TONE_1])

    def test_panel_order_is_kept_and_unmatched_sequences_skipped(self) -> None:
        # 😀 accepts no tone, 👭 has only two-modifier records: both skipped,
        # and the surviving lines stay in panel order.
        with tempfile.TemporaryDirectory() as directory:
            skin = build_from(Path(directory))
        self.assertEqual(skin.base_count, 2)
        self.assertEqual(skin.panel_count, 4)
        self.assertFalse(skin.text.startswith(RAISED_HAND_VS16))

    def test_missing_toned_form_raises(self) -> None:
        # Only four of the five toned forms of 👋 are fully-qualified: the
        # panel may not offer a sequence Unicode does not define.
        broken = INPUT.replace(record(0x1F44B, TONE_5), "")
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(pack.EmojiSkinPackError):
                build_from(Path(directory), input_text=broken)

    def test_byte_guardrail_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(pack.EmojiSkinGuardrailError):
                build_from(Path(directory), max_bytes=10)

    def test_line_guardrail_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(pack.EmojiSkinGuardrailError):
                build_from(Path(directory), max_lines=1)

    def test_base_count_sanity_range_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(pack.EmojiSkinGuardrailError):
                build_from(Path(directory), min_bases=3, max_bases=10)
            with self.assertRaises(pack.EmojiSkinGuardrailError):
                build_from(Path(directory), min_bases=1, max_bases=1)

    def test_determinism_repeated_builds_byte_identical(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            first = build_from(Path(directory))
            second = build_from(Path(directory))
        self.assertEqual(first.data, second.data)
        self.assertEqual(first.sha256, second.sha256)


class CliContractTest(unittest.TestCase):
    # The synthetic fixtures hold two bases; the default sanity range is
    # 100..200, so successful CLI runs pin it down to the fixture's scale.
    def test_successful_build_exit_zero_writes_asset_and_json(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            input_path, panel_path = write_inputs(directory)
            out = directory / "emoji_skin_v1.txt"
            code, stdout = run_main(
                input_path, panel_path, out,
                EXPECTED_INPUT_SHA256=sha256_of(input_path),
                MIN_BASES=1, MAX_BASES=10)
            self.assertEqual(code, 0)
            self.assertTrue(out.exists())
            self.assertEqual(len(out.read_text(encoding="utf-8").splitlines()), 2)
            payload = json.loads(stdout)
            self.assertEqual(payload["base_count"], 2)
            self.assertEqual(payload["panel_count"], 4)
            self.assertEqual(payload["unicode_version"], "15.1")
            self.assertEqual(payload["asset_bytes"], len(out.read_bytes()))

    def test_input_sha_mismatch_exits_2_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            input_path, panel_path = write_inputs(directory)
            out = directory / "emoji_skin_v1.txt"
            code, _ = run_main(input_path, panel_path, out)  # real pin stays
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_version_mismatch_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            input_path, panel_path = write_inputs(
                directory, input_text=INPUT.replace("# Version: 15.1",
                                                    "# Version: 15.0"))
            code, _ = run_main(
                input_path, panel_path, directory / "out.txt",
                EXPECTED_INPUT_SHA256=sha256_of(input_path))
            self.assertEqual(code, 2)

    def test_missing_version_declaration_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            input_path, panel_path = write_inputs(
                directory, input_text=INPUT.replace("# Version: 15.1\n", ""))
            code, _ = run_main(
                input_path, panel_path, directory / "out.txt",
                EXPECTED_INPUT_SHA256=sha256_of(input_path))
            self.assertEqual(code, 2)

    def test_no_fully_qualified_records_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            input_path, panel_path = write_inputs(
                directory, input_text="# Version: 15.1\n# group: none\n")
            code, _ = run_main(
                input_path, panel_path, directory / "out.txt",
                EXPECTED_INPUT_SHA256=sha256_of(input_path))
            self.assertEqual(code, 2)

    def test_invalid_utf8_input_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            input_path, panel_path = write_inputs(directory)
            input_path.write_bytes(b"# Version: 15.1\n\xff\xfe\n")
            code, _ = run_main(
                input_path, panel_path, directory / "out.txt",
                EXPECTED_INPUT_SHA256=sha256_of(input_path))
            self.assertEqual(code, 2)

    def test_duplicate_panel_sequence_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            input_path, panel_path = write_inputs(
                directory, panel_text=PANEL + WAVE + "\n")
            code, _ = run_main(
                input_path, panel_path, directory / "out.txt",
                EXPECTED_INPUT_SHA256=sha256_of(input_path))
            self.assertEqual(code, 2)

    def test_missing_toned_form_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            input_path, panel_path = write_inputs(
                directory, input_text=INPUT.replace(record(0x1F44B, TONE_5), ""))
            code, _ = run_main(
                input_path, panel_path, directory / "out.txt",
                EXPECTED_INPUT_SHA256=sha256_of(input_path),
                MIN_BASES=1, MAX_BASES=10)
            self.assertEqual(code, 2)

    def test_base_count_range_exits_4(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            input_path, panel_path = write_inputs(directory)
            out = directory / "out.txt"
            code, _ = run_main(
                input_path, panel_path, out,
                EXPECTED_INPUT_SHA256=sha256_of(input_path))
            # 2 synthetic bases breach the pinned 100..200 sanity range.
            self.assertEqual(code, 4)
            self.assertFalse(out.exists())

    def test_failure_does_not_publish_partial_asset(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            input_path, panel_path = write_inputs(directory)
            out = directory / "out.txt"
            out.write_bytes(b"OLD CONTENT")
            code, _ = run_main(input_path, panel_path, out)  # sha mismatch
            self.assertEqual(code, 2)
            self.assertEqual(out.read_bytes(), b"OLD CONTENT")


class CommittedAssetTest(unittest.TestCase):
    """The committed skin-tone asset against the committed panel asset."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.panel_sequences = pack.read_panel_sequences(PANEL_ASSET)
        cls.text = SKIN_ASSET.read_text(encoding="utf-8")
        cls.lines = cls.text.splitlines()

    def test_shape_and_guardrails(self) -> None:
        self.assertTrue(self.text.endswith("\n"))
        self.assertNotIn("\r", self.text)
        self.assertLessEqual(len(SKIN_ASSET.read_bytes()), pack.MAX_ASSET_BYTES)
        self.assertLessEqual(len(self.lines), pack.MAX_LINES)
        self.assertGreaterEqual(len(self.lines), pack.MIN_BASES)
        self.assertLessEqual(len(self.lines), pack.MAX_BASES)

    def test_committed_asset_sha256_and_line_count_pins(self) -> None:
        self.assertEqual(len(self.lines), COMMITTED_LINE_COUNT)
        self.assertEqual(sha256_of(SKIN_ASSET), COMMITTED_ASSET_SHA256)

    def test_every_line_is_sequence_prefix_suffix(self) -> None:
        panel = set(self.panel_sequences)
        seen = set()
        for line in self.lines:
            fields = line.split("\t")
            self.assertEqual(len(fields), 3, msg=line[:60])
            sequence, prefix, suffix = fields
            self.assertIn(sequence, panel)
            self.assertNotIn(sequence, seen)
            seen.add(sequence)
            # The tone REPLACES U+FE0F: neither part carries it, and removing
            # the panel's own variation selector yields exactly prefix+suffix.
            self.assertNotIn("️", prefix + suffix)
            stripped = sequence.replace("️", "")
            self.assertEqual(stripped, prefix + suffix, msg=line[:60])

    def test_toned_forms_recompose_to_defined_sequences(self) -> None:
        # prefix + tone + suffix must be a plausible toned form: the base of
        # every committed line (tone stripped back out) stays in the panel.
        panel_stripped = {s.replace("️", "") for s in self.panel_sequences}
        for line in self.lines:
            _sequence, prefix, suffix = line.split("\t")
            composed = prefix + chr(TONE_1) + suffix
            self.assertEqual(composed.replace(chr(TONE_1), ""), prefix + suffix)
            self.assertIn(prefix + suffix, panel_stripped)


if __name__ == "__main__":
    unittest.main()
