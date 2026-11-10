#!/usr/bin/env python3
"""Contract tests for the Russian suggestion eval set and its builder.

The committed file is pinned by bytes and checked field by field. Synthetic streams exercise
all seven exclusion sources without external corpora. Full-stream tests additionally rebuild
twice and compare the committed bytes and measured overlap. Those tests need the pinned raw
corpora under ``research/corpus`` and the six bigram inputs under ``~/corpora-leipzig``; they
skip when those licensed inputs are absent, while the pin, format, and synthetic tests never
skip.
"""
from __future__ import annotations

import hashlib
import importlib.util
import sys
import tempfile
import unittest
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVAL_FILE = ROOT / "app" / "src" / "test" / "resources" / "ru_eval_sentences.txt"
CORPUS_DIR = ROOT / "research" / "corpus"
MAKE_EVAL_SCRIPT = ROOT / "scripts" / "make_ru_eval_set.py"
COVERAGE_SCRIPT = ROOT / "scripts" / "dictionary_coverage.py"

EXPECTED_LINES = 1_000
EXPECTED_SHA256 = "90359e851423dfe7b60a20d6e98ab3209c9c20218208d4e5fccdcf7b8f5b4691"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


make_ru_eval_set = load_module("make_ru_eval_set", MAKE_EVAL_SCRIPT)
coverage = load_module("dictionary_coverage", COVERAGE_SCRIPT)


def read_committed() -> bytes:
    return EVAL_FILE.read_bytes()


def eval_lines(data: bytes) -> list[str]:
    text = data.decode("utf-8")
    return [line for line in text.split("\n") if line and not line.startswith("#")]


def corpus_available() -> bool:
    if not all(
        (CORPUS_DIR / name).is_file()
        for name in (
            make_ru_eval_set.TATOEBA_FILE,
            make_ru_eval_set.OPENSUBTITLES_FILE,
            make_ru_eval_set.CONVERTER,
        )
    ):
        return False
    return all(
        make_ru_eval_set.corpus_path(make_ru_eval_set.DEFAULT_LEIPZIG_DIR, name).is_file()
        for name in make_ru_eval_set.BIGRAM_SENTENCE_FILES
    )


class CommittedEvalSetTest(unittest.TestCase):
    """Pin and format tests; these never skip and hold on every checkout."""

    def test_committed_file_matches_pinned_bytes_and_line_count(self) -> None:
        data = read_committed()
        self.assertEqual(EXPECTED_SHA256, hashlib.sha256(data).hexdigest())
        self.assertEqual(EXPECTED_LINES, len(eval_lines(data)))

    def test_format_contract(self) -> None:
        data = read_committed()
        text = data.decode("utf-8")
        self.assertTrue(text.endswith("\n"), "file must end with a newline")
        self.assertNotIn("\r", text, "LF line endings only")
        raw_lines = text.split("\n")[:-1]
        self.assertTrue(raw_lines[0].startswith("#"))

        lines = eval_lines(data)
        self.assertEqual(EXPECTED_LINES, len(lines))
        self.assertLessEqual(len(lines), 1_000)
        self.assertEqual(len(lines), len(set(lines)))
        self.assertEqual(lines, sorted(lines))
        for line in lines:
            self.assertNotIn("\t", line)
            self.assertEqual(line, unicodedata.normalize("NFC", line))
            self.assertEqual(line, line.lower())
            words = line.split(" ")
            self.assertGreaterEqual(len(words), make_ru_eval_set.DEFAULT_MIN_WORDS)
            self.assertLessEqual(len(words), make_ru_eval_set.DEFAULT_MAX_WORDS)
            for word in words:
                normalized, reason = coverage.normalize_word(
                    word, coverage.RUSSIAN.alphabet
                )
                self.assertIsNone(reason, f"{word!r} must pass the dictionary filter")
                self.assertEqual(word, normalized)
                self.assertFalse(coverage.TATAR_SPECIFIC.intersection(word))

    def test_header_carries_attribution_and_complete_exclusion_list(self) -> None:
        text = read_committed().decode("utf-8")
        header = "\n".join(line for line in text.split("\n") if line.startswith("#"))
        self.assertIn("tatoeba.org", header)
        self.assertIn("CC BY 2.0 FR", header)
        self.assertIn("Tatoeba-v2026-07-08", header)
        self.assertIn("id % 10 == 0", header)
        for name in make_ru_eval_set.BIGRAM_SENTENCE_FILES:
            self.assertIn(name.removesuffix("-sentences.txt"), header)


class InputRegistryTest(unittest.TestCase):
    def test_bigram_inputs_match_the_asset_pipeline(self) -> None:
        self.assertEqual(
            {
                "rus_news_2019_1M-sentences.txt",
                "rus_news_2022_1M-sentences.txt",
                "rus_news_2023_1M-sentences.txt",
                "rus_news_2024_1M-sentences.txt",
                "rus_wikipedia_2021_1M-sentences.txt",
                "rus_conv_thinned60-sentences.txt",
            },
            set(make_ru_eval_set.BIGRAM_SENTENCE_FILES),
        )
        russian = next(
            bigram for bigram in make_ru_eval_set.rebuild_assets.BIGRAMS
            if bigram.tag == "rus"
        )
        self.assertEqual(russian.train, make_ru_eval_set.BIGRAM_SENTENCE_FILES)

    def test_conversational_frequency_sources_match_the_committed_input(self) -> None:
        source_lines = [
            line for line in make_ru_eval_set.CONV_FREQUENCY.read_text(
                encoding="utf-8"
            ).splitlines()
            if line.startswith("# Source: ")
        ]
        self.assertEqual(1, len(source_lines))
        self.assertEqual(
            [make_ru_eval_set.TATOEBA_FILE, make_ru_eval_set.OPENSUBTITLES_FILE],
            source_lines[0].removeprefix("# Source: ").split(),
        )


class SyntheticThinSliceTest(unittest.TestCase):
    """The full stream is accepted only when it reproduces the pinned training slice."""

    def test_the_thinned_rows_must_match_their_manifest_pin(self) -> None:
        rows = [
            (1, "обычный разговор"),
            (60, "Боится его, как чёрт ладана."),
            (61, "обычный разговор"),
            (120, "Том любит картошку."),
        ]
        raw = "".join(f"{row_id}\t{content}\n" for row_id, content in rows).encode("utf-8")
        thinned = "".join(
            f"{row_id}\t{content}\n" for row_id, content in rows
            if row_id % make_ru_eval_set.CONV_THIN_MODULUS == 0
        ).encode("utf-8")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / make_ru_eval_set.CONV_ALL_FILE
            path.write_bytes(raw)
            recipe = make_ru_eval_set.ConvRecipe(
                path=path,
                rows=len(rows),
                tatoeba_rows=1,
                output_sha256="synthetic",
                output_bytes=len(raw),
            )
            self.assertEqual(
                2,
                make_ru_eval_set.verify_conv_thin_slice(
                    recipe, len(thinned), hashlib.sha256(thinned).hexdigest()
                ),
            )
            with self.assertRaisesRegex(
                make_ru_eval_set.EvalSetError,
                "does not reproduce the pinned thinned bigram input",
            ):
                make_ru_eval_set.verify_conv_thin_slice(
                    recipe, len(thinned), hashlib.sha256(b"diverged").hexdigest()
                )


class SyntheticExclusionTest(unittest.TestCase):
    """Every source rejects a collision; only a fully independent sentence survives."""

    def test_all_current_inputs_and_both_conversational_splits(self) -> None:
        rows = {row_id: f"пустая строка номер {row_id}" for row_id in range(1, 121)}
        rows.update({
            10: "Том любит картошку",
            11: "том любит картошку!",
            20: "Свободная строка осталась",
            30: "Разработка использовала фразу",
            31: "разработка использовала фразу.",
            60: "Биграмма забрала фразу",
            70: "Новости забрали фразу",
            80: "Старые новости повторились",
            90: "Википедия повторила фразу",
            100: "Новости двадцать третьего",
            110: "Новости двадцать четвертого",
        })
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            conv = root / "conv.txt"
            conv.write_text(
                "".join(f"{row_id}\t{rows[row_id]}\n" for row_id in sorted(rows)),
                encoding="utf-8",
            )
            names = make_ru_eval_set.BIGRAM_SENTENCE_FILES
            collisions = {
                "rus_conv_thinned60-sentences.txt": rows[60],
                "rus_news_2019_1M-sentences.txt": rows[70],
                "rus_news_2022_1M-sentences.txt": rows[80],
                "rus_wikipedia_2021_1M-sentences.txt": rows[90],
                "rus_news_2023_1M-sentences.txt": rows[100],
                "rus_news_2024_1M-sentences.txt": rows[110],
            }
            training_paths = []
            for index, name in enumerate(names, 1):
                path = root / name
                path.write_text(f"{index}\t{collisions[name]}\n", encoding="utf-8")
                training_paths.append((name, path))
            recipe = make_ru_eval_set.ConvRecipe(
                path=conv,
                rows=len(rows),
                tatoeba_rows=len(rows),
                output_sha256="synthetic",
                output_bytes=conv.stat().st_size,
            )
            first = make_ru_eval_set.build_from_conv_stream(
                recipe, training_paths, limit=1
            )
            second = make_ru_eval_set.build_from_conv_stream(
                recipe, training_paths, limit=1
            )
            with self.assertRaisesRegex(make_ru_eval_set.EvalSetError,
                                        "independent candidate count 1"):
                make_ru_eval_set.build_from_conv_stream(recipe, training_paths, limit=2)

        self.assertEqual(("свободная строка осталась",), first.lines)
        self.assertEqual(first.lines, second.lines)
        self.assertEqual(make_ru_eval_set.render_bytes(first),
                         make_ru_eval_set.render_bytes(second))
        self.assertEqual(set(make_ru_eval_set.TRAINING_INPUT_LABELS),
                         set(first.remaining_overlap))
        for label, overlap in first.remaining_overlap.items():
            self.assertEqual(frozenset(), overlap, label)
        dropped = first.stats["dropped_normalized_collision"]
        for label in make_ru_eval_set.TRAINING_INPUT_LABELS:
            self.assertGreaterEqual(dropped[label], 1, label)
        self.assertEqual(0, first.stats["remaining_overlap"][
            "rus_news_2023_1M-sentences.txt"
        ])
        self.assertEqual(0, first.stats["remaining_overlap"][
            "rus_news_2024_1M-sentences.txt"
        ])


class CorpusDependentTest(unittest.TestCase):
    """Determinism and measured held-out tests over the licensed full inputs."""

    @classmethod
    def setUpClass(cls) -> None:
        if not corpus_available():
            raise unittest.SkipTest("pinned Russian raw corpora and bigram inputs are unavailable")
        cls.build_first = make_ru_eval_set.build_eval_set()

    def test_rebuild_is_byte_identical_to_committed_file(self) -> None:
        build_second = make_ru_eval_set.build_eval_set()
        rendered_first = make_ru_eval_set.render_bytes(self.build_first)
        rendered_second = make_ru_eval_set.render_bytes(build_second)
        self.assertEqual(rendered_first, rendered_second)
        self.assertEqual(read_committed(), rendered_first)

    def test_no_eval_line_appears_in_any_training_or_tuning_input(self) -> None:
        build = self.build_first
        self.assertEqual(tuple(eval_lines(read_committed())), build.lines)
        self.assertEqual(set(make_ru_eval_set.TRAINING_INPUT_LABELS),
                         set(build.remaining_overlap))
        self.assertEqual(7, len(build.remaining_overlap))
        for label, overlap in sorted(build.remaining_overlap.items()):
            self.assertEqual(frozenset(), overlap, f"{label}: {sorted(overlap)[:5]}")
        self.assertGreaterEqual(build.stats["candidates"], EXPECTED_LINES)
        self.assertGreater(
            build.stats["dropped_normalized_collision"][
                make_ru_eval_set.CONV_FREQUENCY_LABEL
            ],
            0,
        )
        self.assertTrue(all(value == 0 for value in
                            build.stats["remaining_overlap"].values()))


if __name__ == "__main__":
    unittest.main()
