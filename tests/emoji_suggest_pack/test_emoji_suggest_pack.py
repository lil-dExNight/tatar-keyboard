#!/usr/bin/env python3
"""Tests for scripts/emoji_suggest_pack.py — the curated word -> emoji packer.

A synthetic TSV and a tiny panel asset drive both the unit surface (TSV
parsing, denylist, conflicts, guardrails) and the CLI contract (exit codes,
fail-closed writes). These tests pin the tool's contract, not the data.
The committed-asset tests pin the shipped data: SHA-256, record counts,
positive controls, the polysemy denylist and a checklist of frequent Russian
trap words that must never map to an emoji.
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
PACK_SCRIPT = ROOT / "scripts" / "emoji_suggest_pack.py"
DATA_TSV = ROOT / "scripts" / "emoji_suggest_data.tsv"
PANEL_ASSET = ROOT / "app" / "src" / "main" / "assets" / "emoji" / "emoji_set_v1.txt"
SUGGEST_ASSET = ROOT / "app" / "src" / "main" / "assets" / "emoji" / "emoji_suggest_v1.txt"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


pack = load_module("emoji_suggest_pack", PACK_SCRIPT)

PANEL = "#smileys-emotion\n❤️\n✈️\n👋\n💼\n🐱\n"

DATA = (
    "# curated rows\n"
    "❤️\tru\tсердце\n"
    "❤️\tru\tсердца\n"
    "❤️\tru\tсердцу\n"
    "✈️\tru\tсамолёт\n"
    "✈️\tru\tсамолет\n"
    "\n"
    "👋\ttt\tсәлам\n"
    "💼\ttt\tэш\n"
    "🐱\ttt\tмәче\n"
)


def write_tmp(directory: Path, name: str, text: str) -> Path:
    path = directory / name
    path.write_text(text, encoding="utf-8")
    return path


def run_main(data: Path, panel: Path, output: Path, **overrides) -> tuple:
    """Invoke the CLI entry point capturing its streams; returns (exit, stdout)."""
    stdout = io.StringIO()
    saved = {name: getattr(pack, name) for name in overrides}
    for name, value in overrides.items():
        setattr(pack, name, value)
    try:
        with contextlib.redirect_stdout(stdout), \
                contextlib.redirect_stderr(io.StringIO()):
            code = pack.main([
                "build", "--data", str(data),
                "--panel-asset", str(panel), "--output", str(output),
            ])
    finally:
        for name, value in saved.items():
            setattr(pack, name, value)
    return code, stdout.getvalue()


class ReadDataTest(unittest.TestCase):
    """The curated TSV parser: fail-closed on anything unexpected."""

    def parse(self, text: str) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            path = write_tmp(Path(directory), "data.tsv", text)
            return pack.read_data(path)

    def test_valid_file_parses_and_skips_comments(self) -> None:
        mapping = self.parse(DATA)
        self.assertEqual(mapping[("ru", "сердце")], "❤️")
        self.assertEqual(mapping[("tt", "эш")], "💼")
        self.assertEqual(len(mapping), 8)

    def test_malformed_lines_raise(self) -> None:
        for bad in ("❤️\tru\n", "❤️\tru\tсердце\textra\n", "❤️\n",
                    "\tru\tсердце\n", "❤️\tru\t\n"):
            with self.assertRaises(pack.EmojiSuggestPackError, msg=bad):
                self.parse(bad)

    def test_unknown_language_raises(self) -> None:
        with self.assertRaises(pack.EmojiSuggestPackError):
            self.parse("❤️\ten\theart\n")

    def test_non_canonical_words_raise(self) -> None:
        for bad in ("Сердце", "сердце!", "two words", "сердце "):
            with self.assertRaises(pack.EmojiSuggestPackError, msg=bad):
                self.parse(f"❤️\tru\t{bad}\n")

    def test_foreign_word_raises_in_both_languages(self) -> None:
        # Latin is outside both alphabets; a Tatar-specific letter (ә) is
        # outside the Russian one (the Tatar alphabet covers the Russian
        # letters, so the reverse direction cannot be enforced by alphabet).
        with self.assertRaises(pack.EmojiSuggestPackError):
            self.parse("🐱\ttt\tmouse\n")
        with self.assertRaises(pack.EmojiSuggestPackError):
            self.parse("🐱\tru\tмәче\n")

    def test_duplicate_row_raises(self) -> None:
        with self.assertRaises(pack.EmojiSuggestPackError):
            self.parse("❤️\tru\tсердце\n❤️\tru\tсердце\n")

    def test_conflict_raises(self) -> None:
        with self.assertRaises(pack.EmojiSuggestPackError):
            self.parse("❤️\tru\tсердце\n✈️\tru\tсердце\n")

    def test_same_word_in_two_languages_is_not_a_conflict(self) -> None:
        mapping = self.parse("🐱\tru\tмашка\n🐱\ttt\tмашка\n")
        self.assertEqual(len(mapping), 2)

    def test_denylist_raises_with_reason(self) -> None:
        with self.assertRaises(pack.EmojiSuggestPackError) as ctx:
            self.parse("🐋\tru\tкит\n")
        self.assertIn("denylist", str(ctx.exception))
        with self.assertRaises(pack.EmojiSuggestPackError):
            self.parse("🍸\ttt\tбар\n")

    def test_empty_file_raises(self) -> None:
        with self.assertRaises(pack.EmojiSuggestPackError):
            self.parse("# только комментарий\n\n")

    def test_invalid_utf8_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "data.tsv"
            path.write_bytes(b"\xff\xfe not utf8")
            with self.assertRaises(pack.EmojiSuggestPackError):
                pack.read_data(path)

    def test_denylist_has_reasons_and_known_tags(self) -> None:
        for (lang, word), reason in pack.DENYLIST.items():
            self.assertIn(lang, pack.LANG_ALPHABETS)
            self.assertTrue(reason.strip())
            self.assertTrue(word)


class ReadPanelSequencesTest(unittest.TestCase):
    def test_duplicate_sequence_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            panel = write_tmp(Path(directory), "panel.txt", PANEL + "❤️\n")
            with self.assertRaises(pack.EmojiSuggestPackError):
                pack.read_panel_sequences(panel)

    def test_empty_panel_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            panel = write_tmp(Path(directory), "panel.txt", "#smileys-emotion\n")
            with self.assertRaises(pack.EmojiSuggestPackError):
                pack.read_panel_sequences(panel)


class BuildTableTest(unittest.TestCase):
    PANEL_SEQS = ("❤️", "✈️", "👋", "💼", "🐱")

    def build(self, mapping, **kwargs) -> pack.SuggestTable:
        options = {"max_bytes": pack.MAX_ASSET_BYTES,
                   "max_zlib_bytes": pack.MAX_ZLIB_BYTES,
                   "max_lines": pack.MAX_LINES, "min_lines": 1}
        options.update(kwargs)
        return pack.build_table(mapping, self.PANEL_SEQS, **options)

    def test_format_and_sorting_by_language_then_word(self) -> None:
        mapping = {("tt", "эш"): "💼", ("ru", "сердца"): "❤️",
                   ("tt", "сәлам"): "👋", ("ru", "самолет"): "✈️",
                   ("ru", "сердце"): "❤️"}
        table = self.build(mapping)
        self.assertEqual(
            table.text.splitlines(),
            ["ru\tсамолет\t✈️", "ru\tсердца\t❤️", "ru\tсердце\t❤️",
             "tt\tсәлам\t👋", "tt\tэш\t💼"],
        )
        self.assertTrue(table.text.endswith("\n"))
        self.assertNotIn("\r", table.text)
        self.assertEqual(table.line_count, 5)
        self.assertEqual(table.ru_entries, 3)
        self.assertEqual(table.tt_entries, 2)

    def test_emoji_outside_panel_raises(self) -> None:
        with self.assertRaises(pack.EmojiSuggestPackError):
            self.build({("ru", "сердце"): "🫀"})

    def test_determinism_repeated_builds_byte_identical(self) -> None:
        mapping = {("ru", "сердце"): "❤️", ("tt", "эш"): "💼"}
        first = self.build(mapping)
        second = self.build(mapping)
        self.assertEqual(first.data, second.data)
        self.assertEqual(first.sha256, second.sha256)

    def test_byte_guardrail_raises(self) -> None:
        with self.assertRaises(pack.EmojiSuggestGuardrailError):
            self.build({("ru", "сердце"): "❤️"}, max_bytes=10)

    def test_zlib_guardrail_raises(self) -> None:
        with self.assertRaises(pack.EmojiSuggestGuardrailError):
            self.build({("ru", "сердце"): "❤️"}, max_zlib_bytes=5)

    def test_line_guardrails_raise(self) -> None:
        mapping = {("ru", "сердце"): "❤️", ("tt", "эш"): "💼"}
        with self.assertRaises(pack.EmojiSuggestGuardrailError):
            self.build(mapping, max_lines=1)
        with self.assertRaises(pack.EmojiSuggestGuardrailError):
            self.build(mapping, min_lines=100)


class CliContractTest(unittest.TestCase):
    def test_successful_build_exit_zero_writes_asset_and_json(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            data = write_tmp(directory, "data.tsv", DATA)
            panel = write_tmp(directory, "panel.txt", PANEL)
            out = directory / "emoji_suggest_v1.txt"
            code, stdout = run_main(data, panel, out, MIN_LINES=1)
            self.assertEqual(code, 0)
            self.assertTrue(out.exists())
            lines = out.read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(lines), 8)
            self.assertIn("tt\tсәлам\t👋", lines)
            payload = json.loads(stdout)
            self.assertEqual(payload["line_count"], 8)
            self.assertEqual(payload["asset_bytes"], len(out.read_bytes()))
            self.assertEqual(payload["concept_count"], 5)
            self.assertEqual(payload["ru_entries"], 5)
            self.assertEqual(payload["tt_entries"], 3)
            self.assertEqual(len(payload["asset_sha256"]), 64)
            self.assertGreater(payload["zlib_bytes"], 0)

    def test_bad_data_exits_2_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            data = write_tmp(directory, "data.tsv", "🐋\tru\tкит\n")
            panel = write_tmp(directory, "panel.txt", PANEL)
            out = directory / "out.txt"
            code, _ = run_main(data, panel, out)
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_guardrail_exits_4(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            data = write_tmp(directory, "data.tsv", DATA)
            panel = write_tmp(directory, "panel.txt", PANEL)
            out = directory / "out.txt"
            code, _ = run_main(data, panel, out, MAX_LINES=2)
            self.assertEqual(code, 4)
            self.assertFalse(out.exists())

    def test_failure_does_not_publish_partial_asset(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            data = write_tmp(directory, "data.tsv", "❤️\tru\tсердце\nno tabs\n")
            panel = write_tmp(directory, "panel.txt", PANEL)
            out = directory / "out.txt"
            out.write_bytes(b"OLD CONTENT")
            code, _ = run_main(data, panel, out)
            self.assertEqual(code, 2)
            self.assertEqual(out.read_bytes(), b"OLD CONTENT")


# Frequent Russian words that must NEVER map to an emoji: function words,
# auxiliaries and polysemous words. The shipped asset must have zero hits here.
TRAP_WORDS = frozenset("""
можно работа работаю день нет пока здесь очень когда если жизнь рука дело
и в не на я ты он она оно мы вы они что это как так все ещё уже только тоже также
где куда зачем почему сколько который чтобы потому поэтому тогда сегодня завтра вчера
всегда никогда иногда сейчас потом сначала вместе хорошо плохо лучше хуже быстро медленно
правда конечно наверное нужно надо может будет был была было были есть быть стал стала
знать понимать видеть слышать говорить сказать сделать делать работать жить идти ехать
хотеть мочь давать взять дать новый старый большой маленький хороший плохой первый
последний другой свой самый такой какой этот тот весь мой твой его её их наш ваш
кто кого кому чего ничего никто туда сюда там тут везде нигде почти совсем сразу
опять снова вдруг примерно действительно вообще впрочем кстати кажется ладно давай
здравствуйте год неделя лет свет работы мир лук замок ключ кит бар месяц молния
земля время нельзя очки мышь ручка камера труба борьба зарядка приставка карта
кошелек
""".split())

# Positive controls: exact (language, word) -> emoji.
POSITIVE_CONTROLS = {
    ("ru", "сердце"): "❤️",
    ("ru", "сердца"): "❤️",
    ("ru", "сердцу"): "❤️",
    ("ru", "самолет"): "✈️",
    ("ru", "самолета"): "✈️",
    ("tt", "йөрәк"): "❤️",
    ("tt", "йөрәккә"): "❤️",
    ("tt", "сәлам"): "👋",
    ("tt", "эш"): "💼",
    # Concepts that used to have only Russian rows; «йорэк» is йөрәк typed with
    # Russian look-alike letters.
    ("tt", "әтәч"): "🐓",
    ("tt", "кәҗә"): "🐐",
    ("tt", "йорэк"): "❤️",
    ("tt", "жыр"): "🎵",
}

# The shipped asset is pinned: a data change must update these numbers too.
EXPECTED_ASSET_SHA256 = "54171e32bdf3be6883dc6ffcbcf603257753cb4e6eaa5d20b5b38cd0def42c99"
EXPECTED_LINE_COUNT = 3976
EXPECTED_RU_ENTRIES = 2501
EXPECTED_TT_ENTRIES = 1475


class CommittedAssetTest(unittest.TestCase):
    """The shipped asset against the shipped panel asset (live tree)."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.asset_text = SUGGEST_ASSET.read_text(encoding="utf-8")
        cls.lines = cls.asset_text.splitlines()
        cls.mapping = {}
        for line in cls.lines:
            lang, word, emoji = line.split("\t")
            cls.mapping[(lang, word)] = emoji
        cls.panel = set(pack.read_panel_sequences(PANEL_ASSET))

    def test_asset_is_pinned(self) -> None:
        digest = hashlib.sha256(SUGGEST_ASSET.read_bytes()).hexdigest()
        self.assertEqual(digest, EXPECTED_ASSET_SHA256)
        self.assertEqual(len(self.lines), EXPECTED_LINE_COUNT)
        self.assertEqual(
            sum(1 for lang, _ in self.mapping if lang == "ru"), EXPECTED_RU_ENTRIES)
        self.assertEqual(
            sum(1 for lang, _ in self.mapping if lang == "tt"), EXPECTED_TT_ENTRIES)

    def test_shape_and_guardrails(self) -> None:
        self.assertTrue(self.asset_text.endswith("\n"))
        self.assertNotIn("\r", self.asset_text)
        self.assertLessEqual(len(SUGGEST_ASSET.read_bytes()), pack.MAX_ASSET_BYTES)
        self.assertGreaterEqual(len(self.lines), pack.MIN_LINES)
        self.assertLessEqual(len(self.lines), pack.MAX_LINES)

    def test_every_line_is_three_tab_separated_fields(self) -> None:
        for line in self.lines:
            fields = line.split("\t")
            self.assertEqual(len(fields), 3, msg=line[:60])
            self.assertTrue(all(fields), msg=line[:60])
            self.assertIn(fields[0], pack.LANG_ALPHABETS, msg=line[:60])

    def test_lines_are_sorted_by_language_then_word(self) -> None:
        keys = [(line.split("\t")[0], line.split("\t")[1]) for line in self.lines]
        self.assertEqual(keys, sorted(keys))
        self.assertEqual(len(keys), len(set(keys)))

    def test_every_emoji_is_in_the_panel(self) -> None:
        for (lang, word), emoji in self.mapping.items():
            self.assertIn(emoji, self.panel, msg=f"{lang}:{word} -> {emoji!r}")

    def test_every_word_passes_normalize_word(self) -> None:
        for lang, word in self.mapping:
            alphabet = pack.LANG_ALPHABETS[lang]
            normalized, reason = pack.dictionary_coverage.normalize_word(word, alphabet)
            self.assertIsNone(reason, msg=f"{lang}:{word}: {reason}")
            self.assertEqual(normalized, word)

    def test_positive_controls(self) -> None:
        for (lang, word), emoji in POSITIVE_CONTROLS.items():
            self.assertEqual(self.mapping.get((lang, word)), emoji,
                             msg=f"{lang}:{word}")

    def test_trap_words_have_no_mapping(self) -> None:
        self.assertGreaterEqual(len(TRAP_WORDS), 100)
        for word in sorted(TRAP_WORDS):
            self.assertNotIn(("ru", word), self.mapping, msg=word)

    def test_denylisted_words_have_no_mapping(self) -> None:
        for lang, word in pack.DENYLIST:
            self.assertNotIn((lang, word), self.mapping, msg=f"{lang}:{word}")

    def test_data_tsv_rebuilds_the_same_asset(self) -> None:
        """The committed TSV and the committed asset must never drift apart."""
        mapping = pack.read_data(DATA_TSV)
        table = pack.build_table(
            mapping,
            pack.read_panel_sequences(PANEL_ASSET),
            max_bytes=pack.MAX_ASSET_BYTES,
            max_zlib_bytes=pack.MAX_ZLIB_BYTES,
            max_lines=pack.MAX_LINES,
            min_lines=pack.MIN_LINES,
        )
        self.assertEqual(table.data, SUGGEST_ASSET.read_bytes())


if __name__ == "__main__":
    unittest.main()
