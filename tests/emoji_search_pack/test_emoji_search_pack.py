#!/usr/bin/env python3
"""Tests for scripts/emoji_search_pack.py — the CLDR -> emoji-search index packer.

Synthetic CLDR annotation files (a handful of annotations instead of the real
CLDR 44 pair) and a tiny panel asset drive both the unit surface (parsing,
U+FE0F lookup, keyword union order, guardrails) and the CLI contract (exit
codes, fail-closed writes). What is pinned here is the CONTRACT of the tool,
not the data. A live-tree test additionally checks the committed asset against
the committed panel asset: the index must never offer an emoji the panel
cannot draw.
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
PACK_SCRIPT = ROOT / "scripts" / "emoji_search_pack.py"
PANEL_ASSET = ROOT / "app" / "src" / "main" / "assets" / "emoji" / "emoji_set_v1.txt"
SEARCH_ASSET = ROOT / "app" / "src" / "main" / "assets" / "emoji" / "emoji_search_v1.txt"

# Pins for the committed asset, in the emoji_pack pattern: a rebuild that
# changes the shipped index must update these together with the asset.
COMMITTED_LINE_COUNT = 1389
COMMITTED_ASSET_SHA256 = (
    "b16b4a76cdeafdae5aa16a05a47f38a9cc3f91cc616eb4a7ff1f7cba849af1bf"
)


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


pack = load_module("emoji_search_pack", PACK_SCRIPT)


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def seq(*codepoints: int) -> str:
    return "".join(chr(cp) for cp in codepoints)


GRINNING = seq(0x1F600)
SMILING_VS16 = seq(0x263A, 0xFE0F)  # ☺️ — panel keeps U+FE0F, CLDR strips it
UPSIDE_DOWN = seq(0x1F643)

RU_XML = """<?xml version="1.0" encoding="UTF-8"?>
<ldml><annotations>
<annotation cp="😀" type="tts">широко улыбается</annotation>
<annotation cp="😀">радость | улыбка | широко улыбается</annotation>
<annotation cp="☺" type="tts">улыбающееся лицо</annotation>
<annotation cp="☺">лицо | доволен</annotation>
</annotations></ldml>
"""

DERIVED_RU_XML = """<?xml version="1.0" encoding="UTF-8"?>
<ldml><annotations>
<annotation cp="😀" type="tts">ИМЯ ИЗ DERIVED — НЕ ДОЛЖНО ПОБЕДИТЬ</annotation>
<annotation cp="🙃" type="tts">перевёрнутое лицо</annotation>
<annotation cp="🙃">вверх ногами</annotation>
</annotations></ldml>
"""

EN_XML = """<?xml version="1.0" encoding="UTF-8"?>
<ldml><annotations>
<annotation cp="😀" type="tts">Grinning Face</annotation>
<annotation cp="😀">face | grin</annotation>
</annotations></ldml>
"""

DERIVED_EN_XML = """<?xml version="1.0" encoding="UTF-8"?>
<ldml><annotations>
<annotation cp="😀">smile | happy</annotation>
</annotations></ldml>
"""

NOT_CLDR_XML = """<?xml version="1.0" encoding="UTF-8"?>
<ldml><identity><language type="ru"/></identity></ldml>
"""

PANEL = "#smileys-emotion\n" + GRINNING + "\n" + SMILING_VS16 + "\n"

# The English keyword list of the base file replaces the derived list for the
# same cp wholesale (dict.update per key) — "smile | happy" from derived-en is
# shadowed by "face | grin" from the base file; derived only fills absent cps.
EXPECTED_LINE_GRINNING = (
    f"{GRINNING}\tшироко улыбается\t"
    "широко улыбается радость улыбка grinning face face grin"
)
EXPECTED_LINE_SMILING = (
    f"{SMILING_VS16}\tулыбающееся лицо\tулыбающееся лицо лицо доволен"
)


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


def write_cldr_dir(directory: Path, ru=RU_XML, en=EN_XML,
                   derived_ru=DERIVED_RU_XML, derived_en=DERIVED_EN_XML) -> dict:
    """Write the four synthetic CLDR files; return the pin dict matching them."""
    texts = {"ru": ru, "en": en,
             "derived-ru": derived_ru, "derived-en": derived_en}
    pins = {}
    for name, text in texts.items():
        path = directory / f"{name}.xml"
        path.write_text(text, encoding="utf-8")
        pins[name] = sha256_of(path)
    return pins


def write_panel(directory: Path, text: str = PANEL) -> Path:
    path = directory / "emoji_set_v1.txt"
    path.write_text(text, encoding="utf-8")
    return path


def run_main(cldr_dir: Path, panel: Path, output: Path, **overrides) -> tuple:
    """Invoke the CLI entry point capturing its streams; returns (exit, stdout)."""
    stdout = io.StringIO()
    with patched(**overrides), contextlib.redirect_stdout(stdout), \
            contextlib.redirect_stderr(io.StringIO()):
        code = pack.main([
            "build", "--cldr-dir", str(cldr_dir),
            "--panel-asset", str(panel), "--output", str(output),
        ])
    return code, stdout.getvalue()


def build_from(directory: Path, panel_text: str = PANEL, **kwargs):
    """Build an index from the synthetic fixtures via the public builders."""
    pins = write_cldr_dir(directory)
    parsed = {}
    for name in ("ru", "en", "derived-ru", "derived-en"):
        parsed[name] = pack.parse_annotations(
            pack.read_input_text(directory / f"{name}.xml",
                                 expected_sha256=pins[name]))
    merged = {}
    for lang in ("ru", "en"):
        keywords = dict(parsed[f"derived-{lang}"][0])
        keywords.update(parsed[lang][0])
        names = dict(parsed[f"derived-{lang}"][1])
        names.update(parsed[lang][1])
        merged[lang] = (keywords, names)
    panel = write_panel(directory, panel_text)
    options = {"max_bytes": pack.MAX_ASSET_BYTES, "max_lines": pack.MAX_LINES,
               "min_russian_coverage": 0.5}
    options.update(kwargs)
    return pack.build_index(pack.read_panel_sequences(panel),
                            (merged["ru"], merged["en"]), **options)


class ParseAnnotationsTest(unittest.TestCase):
    def test_keywords_and_names_split(self) -> None:
        keywords, names = pack.parse_annotations(RU_XML)
        self.assertEqual(names[GRINNING], "широко улыбается")
        self.assertEqual(keywords[GRINNING],
                         ["радость", "улыбка", "широко улыбается"])
        self.assertEqual(names["☺"], "улыбающееся лицо")

    def test_no_annotations_raises(self) -> None:
        with self.assertRaises(pack.EmojiSearchPackError):
            pack.parse_annotations(NOT_CLDR_XML)

    def test_xml_entities_are_unescaped(self) -> None:
        # CLDR writes cps as literal UTF-8; only the five named entities need
        # undoing, and they appear in the VALUES, not in cp attributes.
        keywords, names = pack.parse_annotations(
            '<annotation cp="😀" type="tts">a &amp; b</annotation>'
            '<annotation cp="😀">x &lt; y | &quot;z&quot;</annotation>'
        )
        self.assertEqual(names[GRINNING], "a & b")
        self.assertEqual(keywords[GRINNING], ["x < y", '"z"'])


class LookupKeyTest(unittest.TestCase):
    def test_fe0f_is_stripped_for_lookup(self) -> None:
        self.assertEqual(pack.lookup_key(SMILING_VS16), "☺")
        self.assertEqual(pack.lookup_key(GRINNING), GRINNING)


class BuildIndexTest(unittest.TestCase):
    def test_format_sequence_tab_name_tab_keywords(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            index = build_from(Path(directory))
        lines = index.text.splitlines()
        self.assertEqual(lines, [EXPECTED_LINE_GRINNING, EXPECTED_LINE_SMILING])
        for line in lines:
            self.assertEqual(line.count("\t"), 2)
        self.assertTrue(index.text.endswith("\n"))
        self.assertNotIn("\r", index.text)

    def test_russian_comes_first_and_is_lowercased(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            index = build_from(Path(directory))
        words = index.text.splitlines()[0].split("\t")[2].split(" ")
        self.assertEqual(words[0], "широко")
        # The English name is lowercased (Grinning Face -> grinning face) and
        # deduplicated against keywords only as whole phrases.
        self.assertIn("grinning face", index.text.splitlines()[0].split("\t")[2])

    def test_fe0f_sequence_found_and_kept_intact_in_field_one(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            index = build_from(Path(directory))
        line = index.text.splitlines()[1]
        self.assertEqual(line.split("\t")[0], SMILING_VS16)

    def test_derived_fills_gaps_but_base_wins(self) -> None:
        # 🙃 is annotated only in derived-ru: it appears with the derived name.
        # 😀 is annotated in both: the base name must win over the derived one.
        with tempfile.TemporaryDirectory() as directory:
            index = build_from(Path(directory),
                               PANEL + UPSIDE_DOWN + "\n",
                               min_russian_coverage=0.0)
        lines = index.text.splitlines()
        self.assertEqual(len(lines), 3)
        self.assertIn("перевёрнутое лицо", lines[2])
        self.assertNotIn("НЕ ДОЛЖНО ПОБЕДИТЬ", index.text)
        self.assertNotIn("ИМЯ ИЗ DERIVED", index.text.lower())

    def test_sequence_without_annotation_is_omitted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            index = build_from(Path(directory),
                               PANEL + seq(0x1F978) + "\n",  # no annotation
                               min_russian_coverage=0.0)
        self.assertEqual(index.line_count, 2)

    def test_coverage_counts_only_russian(self) -> None:
        # ☺️ has no Russian annotation in this variant: coverage is 1/2.
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            pins = write_cldr_dir(directory, ru=DERIVED_RU_XML.replace(
                "🙃", "😀"))  # keep it a valid CLDR file
            parsed = {name: pack.parse_annotations(pack.read_input_text(
                directory / f"{name}.xml", expected_sha256=pins[name]))
                for name in pins}
            index = pack.build_index(
                (GRINNING, SMILING_VS16),
                (parsed["ru"], parsed["en"]),
                max_bytes=pack.MAX_ASSET_BYTES, max_lines=pack.MAX_LINES,
                min_russian_coverage=0.0)
        self.assertAlmostEqual(index.russian_coverage, 0.5)

    def test_russian_coverage_guardrail_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(pack.EmojiSearchGuardrailError):
                build_from(Path(directory), PANEL + seq(0x1F978) + "\n",
                           min_russian_coverage=0.99)

    def test_byte_guardrail_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(pack.EmojiSearchGuardrailError):
                build_from(Path(directory), max_bytes=10)

    def test_line_guardrail_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(pack.EmojiSearchGuardrailError):
                build_from(Path(directory), max_lines=1)

    def test_malformed_sequence_with_tab_raises(self) -> None:
        # A sequence carrying a tab can only reach the line builder if CLDR has
        # a matching cp; then the three-field line shape is enforced.
        tabbed = "😀\tsplit"
        sources = (({tabbed: ["грязный вход"]}, {}), ({}, {}))
        with self.assertRaises(pack.EmojiSearchPackError):
            pack.build_index([tabbed], sources, max_bytes=pack.MAX_ASSET_BYTES,
                             max_lines=pack.MAX_LINES, min_russian_coverage=0.0)

    def test_determinism_repeated_builds_byte_identical(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            first = build_from(Path(directory))
            second = build_from(Path(directory))
        self.assertEqual(first.data, second.data)
        self.assertEqual(first.sha256, second.sha256)


class ReadPanelSequencesTest(unittest.TestCase):
    def test_duplicate_sequence_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            panel = write_panel(Path(directory), PANEL + GRINNING + "\n")
            with self.assertRaises(pack.EmojiSearchPackError):
                pack.read_panel_sequences(panel)

    def test_empty_panel_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            panel = write_panel(Path(directory), "#smileys-emotion\n")
            with self.assertRaises(pack.EmojiSearchPackError):
                pack.read_panel_sequences(panel)


class ReadTtExtraTest(unittest.TestCase):
    """The hand-written Tatar keyword file: fail-closed parsing."""

    def write_extra(self, directory: Path, text: str) -> Path:
        path = directory / "emoji_search_tt_extra.txt"
        path.write_text(text, encoding="utf-8")
        return path

    def test_valid_file_parses_and_skips_comments(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            path = self.write_extra(
                directory,
                "# татарские синонимы, составлены вручную\n"
                f"{GRINNING}\tелмаю,шатлык,көз кысу\n"
                "\n"
                f"{SMILING_VS16}\tбәхет\n",
            )
            extra = pack.read_tt_extra(path, (GRINNING, SMILING_VS16))
        self.assertEqual(extra[GRINNING], ["елмаю", "шатлык", "көз кысу"])
        self.assertEqual(extra[SMILING_VS16], ["бәхет"])

    def test_unknown_sequence_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_extra(Path(directory), f"{UPSIDE_DOWN}\tелмаю\n")
            with self.assertRaises(pack.EmojiSearchPackError):
                pack.read_tt_extra(path, (GRINNING, SMILING_VS16))

    def test_duplicate_sequence_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_extra(
                Path(directory),
                f"{GRINNING}\tелмаю\n{GRINNING}\tбәхет\n",
            )
            with self.assertRaises(pack.EmojiSearchPackError):
                pack.read_tt_extra(path, (GRINNING,))

    def test_malformed_lines_raise(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            for bad in (f"{GRINNING}\n", f"{GRINNING}\t\n",
                        f"{GRINNING}\tелмаю\tтавыш\n"):
                path = self.write_extra(directory, bad)
                with self.assertRaises(pack.EmojiSearchPackError, msg=bad):
                    pack.read_tt_extra(path, (GRINNING,))

    def test_non_canonical_or_foreign_words_raise(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            for bad in ("Елмаю", "hello", "сәләм!"):
                path = self.write_extra(directory, f"{GRINNING}\t{bad}\n")
                with self.assertRaises(pack.EmojiSearchPackError, msg=bad):
                    pack.read_tt_extra(path, (GRINNING,))

    def test_empty_file_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_extra(Path(directory), "# только комментарий\n")
            with self.assertRaises(pack.EmojiSearchPackError):
                pack.read_tt_extra(path, (GRINNING,))


class BuildIndexTtExtraTest(unittest.TestCase):
    """Tatar keywords extend a line after the CLDR words, never before."""

    def test_tt_keywords_appended_after_ru_and_en(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            index = build_from(Path(directory),
                               tt_extra={GRINNING: ["елмаю", "шатлык"]})
        words = index.text.splitlines()[0].split("\t")[2].split(" ")
        self.assertEqual(words[-2:], ["елмаю", "шатлык"])
        self.assertEqual(words[0], "широко")

    def test_tt_keywords_deduplicated_against_cldr(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            index = build_from(Path(directory),
                               tt_extra={GRINNING: ["улыбка", "елмаю"]})
        words = index.text.splitlines()[0].split("\t")[2].split(" ")
        self.assertEqual(words.count("улыбка"), 1)
        self.assertIn("елмаю", words)

    def test_tt_extra_does_not_change_russian_coverage_or_line_count(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            plain = build_from(Path(directory))
            extended = build_from(Path(directory),
                                  tt_extra={GRINNING: ["елмаю"]})
        self.assertEqual(plain.line_count, extended.line_count)
        self.assertEqual(plain.russian_coverage, extended.russian_coverage)


class CliTtExtraTest(unittest.TestCase):
    def test_tt_extra_reaches_the_asset_and_json_reports_it(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            pins = write_cldr_dir(directory)
            panel = write_panel(directory)
            extra = directory / "tt.txt"
            extra.write_text(f"{GRINNING}\tелмаю,шатлык\n", encoding="utf-8")
            out = directory / "out.txt"
            stdout = io.StringIO()
            with patched(EXPECTED_INPUT_SHA256=pins), \
                    contextlib.redirect_stdout(stdout), \
                    contextlib.redirect_stderr(io.StringIO()):
                code = pack.main([
                    "build", "--cldr-dir", str(directory),
                    "--panel-asset", str(panel), "--tt-extra", str(extra),
                    "--output", str(out)])
            self.assertEqual(code, 0)
            payload = json.loads(stdout.getvalue())
            self.assertEqual(payload["tt_extra_sequences"], 1)
            line = out.read_text(encoding="utf-8").splitlines()[0]
            self.assertTrue(line.endswith("елмаю шатлык"))

    def test_bad_tt_extra_exits_2_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            pins = write_cldr_dir(directory)
            panel = write_panel(directory)
            extra = directory / "tt.txt"
            extra.write_text(f"{UPSIDE_DOWN}\tелмаю\n", encoding="utf-8")
            out = directory / "out.txt"
            with patched(EXPECTED_INPUT_SHA256=pins), \
                    contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()):
                code = pack.main([
                    "build", "--cldr-dir", str(directory),
                    "--panel-asset", str(panel), "--tt-extra", str(extra),
                    "--output", str(out)])
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())


class CliContractTest(unittest.TestCase):
    def test_successful_build_exit_zero_writes_asset_and_json(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            pins = write_cldr_dir(directory)
            panel = write_panel(directory)
            out = directory / "emoji_search_v1.txt"
            code, stdout = run_main(directory, panel, out,
                                    EXPECTED_INPUT_SHA256=pins)
            self.assertEqual(code, 0)
            self.assertTrue(out.exists())
            self.assertEqual(out.read_text(encoding="utf-8").splitlines(),
                             [EXPECTED_LINE_GRINNING, EXPECTED_LINE_SMILING])
            payload = json.loads(stdout)
            self.assertEqual(payload["line_count"], 2)
            self.assertEqual(payload["sequence_count"], 2)
            self.assertEqual(payload["russian_coverage"], 1.0)
            self.assertEqual(payload["cldr_version"], pack.CLDR_VERSION)
            self.assertEqual(payload["asset_bytes"], len(out.read_bytes()))

    def test_input_sha_mismatch_exits_2_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            write_cldr_dir(directory)  # real pins left in place -> mismatch
            panel = write_panel(directory)
            out = directory / "emoji_search_v1.txt"
            code, _ = run_main(directory, panel, out)
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_not_a_cldr_file_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            pins = write_cldr_dir(directory, ru=NOT_CLDR_XML)
            panel = write_panel(directory)
            out = directory / "emoji_search_v1.txt"
            code, _ = run_main(directory, panel, out,
                               EXPECTED_INPUT_SHA256=pins)
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_invalid_utf8_input_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            pins = write_cldr_dir(directory)
            bad = directory / "ru.xml"
            bad.write_bytes(b"\xff\xfe not utf8")
            pins["ru"] = sha256_of(bad)
            panel = write_panel(directory)
            code, _ = run_main(directory, panel, directory / "out.txt",
                               EXPECTED_INPUT_SHA256=pins)
            self.assertEqual(code, 2)

    def test_duplicate_panel_sequence_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            pins = write_cldr_dir(directory)
            panel = write_panel(directory, PANEL + GRINNING + "\n")
            code, _ = run_main(directory, panel, directory / "out.txt",
                               EXPECTED_INPUT_SHA256=pins)
            self.assertEqual(code, 2)

    def test_empty_panel_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            pins = write_cldr_dir(directory)
            panel = write_panel(directory, "#smileys-emotion\n")
            code, _ = run_main(directory, panel, directory / "out.txt",
                               EXPECTED_INPUT_SHA256=pins)
            self.assertEqual(code, 2)

    def test_coverage_guardrail_exits_4(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            pins = write_cldr_dir(directory)
            panel = write_panel(directory, PANEL + seq(0x1F978) + "\n")
            out = directory / "out.txt"
            code, _ = run_main(directory, panel, out,
                               EXPECTED_INPUT_SHA256=pins)
            self.assertEqual(code, 4)
            self.assertFalse(out.exists())

    def test_byte_guardrail_exits_4(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            pins = write_cldr_dir(directory)
            panel = write_panel(directory)
            code, _ = run_main(directory, panel, directory / "out.txt",
                               EXPECTED_INPUT_SHA256=pins, MAX_ASSET_BYTES=10)
            self.assertEqual(code, 4)

    def test_failure_does_not_publish_partial_asset(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            write_cldr_dir(directory)
            panel = write_panel(directory)
            out = directory / "out.txt"
            out.write_bytes(b"OLD CONTENT")
            code, _ = run_main(directory, panel, out)  # sha mismatch
            self.assertEqual(code, 2)
            self.assertEqual(out.read_bytes(), b"OLD CONTENT")


class CommittedAssetTest(unittest.TestCase):
    """The committed index against the committed panel asset (live tree)."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.panel_sequences = pack.read_panel_sequences(PANEL_ASSET)
        cls.search_text = SEARCH_ASSET.read_text(encoding="utf-8")
        cls.search_lines = cls.search_text.splitlines()

    def test_shape_and_guardrails(self) -> None:
        self.assertTrue(self.search_text.endswith("\n"))
        self.assertNotIn("\r", self.search_text)
        self.assertLessEqual(len(SEARCH_ASSET.read_bytes()), pack.MAX_ASSET_BYTES)
        self.assertLessEqual(len(self.search_lines), pack.MAX_LINES)
        self.assertGreater(len(self.search_lines), 1000)

    def test_committed_asset_sha256_and_line_count_pins(self) -> None:
        self.assertEqual(len(self.search_lines), COMMITTED_LINE_COUNT)
        self.assertEqual(sha256_of(SEARCH_ASSET), COMMITTED_ASSET_SHA256)

    def test_every_line_is_three_tab_separated_fields(self) -> None:
        for line in self.search_lines:
            fields = line.split("\t")
            self.assertEqual(len(fields), 3, msg=line[:60])
            self.assertTrue(all(fields), msg=line[:60])

    def test_index_never_offers_what_the_panel_cannot_draw(self) -> None:
        panel = set(self.panel_sequences)
        indexed = [line.split("\t")[0] for line in self.search_lines]
        self.assertEqual(len(indexed), len(set(indexed)))  # no duplicates
        for sequence in indexed:
            self.assertIn(sequence, panel)

    def test_russian_coverage_floor_holds_on_committed_assets(self) -> None:
        panel = set(self.panel_sequences)
        indexed = {line.split("\t")[0] for line in self.search_lines}
        coverage = len(panel & indexed) / len(panel)
        self.assertGreaterEqual(coverage, pack.MIN_RUSSIAN_COVERAGE)

    def test_keywords_are_lowercased_and_name_is_russian(self) -> None:
        first = dict((line.split("\t")[0], line.split("\t")[2])
                     for line in self.search_lines)
        for line in self.search_lines:
            name, keywords = line.split("\t")[1], line.split("\t")[2]
            self.assertEqual(keywords, keywords.lower())
            self.assertTrue(keywords.startswith(name),
                            msg=f"keywords must open with the name: {line[:60]}")
        self.assertIn("улыбка", first[GRINNING])

    def test_no_variation_selector_leaks_into_keywords(self) -> None:
        # Sequences keep U+FE0F (field 1 must match the panel), but the lookup
        # stripped it: keywords and names must never carry it.
        for line in self.search_lines:
            fields = line.split("\t")
            self.assertNotIn("️", fields[1] + fields[2])

    def test_tatar_keywords_reached_the_committed_asset(self) -> None:
        # Mission m2 of docs/AUDIT-2026-08-31.md: the hand-written Tatar
        # synonyms of scripts/emoji_search_tt_extra.txt must be in the index.
        tt_words = {
            "❤️": ["йөрәк", "мәхәббәт", "сөю"],
            "😀": ["елмаю", "шатлык"],
            "🐱": ["мәче"],
            "👋": ["сәлам", "исәнмесез"],
            "📖": ["китап"],
            "🕌": ["мәчет"],
            # M4a positive control: the rooster had no Tatar keyword at all.
            "🐓": ["әтәч", "этэч"],
        }
        by_sequence = {}
        for line in self.search_lines:
            fields = line.split("\t")
            by_sequence[fields[0]] = fields[2].split(" ")
        for sequence, expected in tt_words.items():
            self.assertIn(sequence, by_sequence)
            for word in expected:
                self.assertIn(word, by_sequence[sequence],
                              msg=f"{word} missing for {sequence}")

    def test_tatar_keywords_never_lead_a_line(self) -> None:
        # Ranking promise: a Tatar word never stands before the Russian name
        # and the CLDR keywords of the same line.
        for line in self.search_lines:
            fields = line.split("\t")
            words = fields[2].split(" ")
            self.assertFalse(set(words[0]) & set("әөүҗңһ"),
                             msg=f"Tatar word leads the line: {line[:60]}")


if __name__ == "__main__":
    unittest.main()
