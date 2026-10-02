#!/usr/bin/env python3
"""Contract tests for the Russian suggestion eval set and its builder.

What is pinned and why:

* The committed eval file (``app/src/test/resources/ru_eval_sentences.txt``) is pinned by
  byte SHA-256 and by line count: any regeneration changes the set, and every suggestion
  quality baseline is measured against it.
* The format contract is checked field by field (comment header, NFC lowercase words that
  pass the dictionary pipeline's own ``normalize_word`` under the Russian language and carry
  no Tatar-specific letters, 3..12 words per line, deduplicated, code-point sorted) so a
  hand edit cannot silently weaken the set.
* The builder is checked for byte-identical determinism: two independent builds must produce
  the same bytes as the committed file.
* The held-out property is checked against all four training corpora of the shipped Russian
  bigram table: no eval line may appear in the normalized thinned conv stream or in any of
  the three normalized Leipzig ru sentence corpora.

The last two groups need the licensed Tatoeba ru dump (``research/corpus/*.txt.gz``) and the
four training corpora (``~/corpora-leipzig``), which are gitignored and therefore absent on a
clean CI checkout. Those tests SKIP with an explicit reason when the inputs are missing; the
pin and format tests above never skip, so a corrupted committed file fails everywhere.
"""
from __future__ import annotations

import hashlib
import importlib.util
import sys
import unittest
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVAL_FILE = ROOT / "app" / "src" / "test" / "resources" / "ru_eval_sentences.txt"
CORPUS_DIR = ROOT / "research" / "corpus"
MAKE_EVAL_SCRIPT = ROOT / "scripts" / "make_ru_eval_set.py"
COVERAGE_SCRIPT = ROOT / "scripts" / "dictionary_coverage.py"

# Changing either means a new eval set, and every baseline number derived from it must be
# re-measured in the same commit.
EXPECTED_LINES = 1_000
EXPECTED_SHA256 = "e5d206e12c27423e1d3acee474cd82a368d0a16da73db8ea097f83368d394fcb"


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
    if not (CORPUS_DIR / make_ru_eval_set.TATOEBA_FILE).is_file():
        return False
    leipzig_dir = make_ru_eval_set.DEFAULT_LEIPZIG_DIR
    return all(
        (leipzig_dir / name).is_file()
        for name in (
            make_ru_eval_set.CONV_SENTENCES_FILE,
            *make_ru_eval_set.LEIPZIG_SENTENCE_FILES,
        )
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
        self.assertTrue(
            raw_lines[0].startswith("#"), "the file must open with a comment header"
        )

        lines = eval_lines(data)
        self.assertEqual(EXPECTED_LINES, len(lines))
        self.assertLessEqual(len(lines), 1_000, "the eval set is capped at 1000 lines")
        self.assertEqual(len(lines), len(set(lines)), "lines must be deduplicated")
        self.assertEqual(lines, sorted(lines), "lines must be code-point sorted")
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
                self.assertEqual(word, normalized, f"{word!r} must already be canonical")
                self.assertFalse(
                    coverage.TATAR_SPECIFIC.intersection(word),
                    f"{word!r} must not carry Tatar-specific letters",
                )

    def test_header_carries_tatoeba_attribution(self) -> None:
        text = read_committed().decode("utf-8")
        header = "\n".join(
            line for line in text.split("\n") if line.startswith("#")
        )
        self.assertIn("tatoeba.org", header)
        self.assertIn("CC BY 2.0 FR", header)
        self.assertIn("Tatoeba-v2026-07-08", header)


class CorpusDependentTest(unittest.TestCase):
    """Determinism and held-out tests; SKIP when the licensed corpus is absent."""

    def setUp(self) -> None:
        if not corpus_available():
            self.skipTest(
                "licensed corpus inputs research/corpus/*.txt.gz and ~/corpora-leipzig "
                "are not on this checkout"
            )

    def test_rebuild_is_byte_identical_to_committed_file(self) -> None:
        build_first = make_ru_eval_set.build_eval_set()
        build_second = make_ru_eval_set.build_eval_set()
        rendered_first = make_ru_eval_set.render_bytes(build_first)
        rendered_second = make_ru_eval_set.render_bytes(build_second)
        self.assertEqual(
            rendered_first,
            rendered_second,
            "two builds over the same inputs must be byte-identical",
        )
        self.assertEqual(
            read_committed(),
            rendered_first,
            "the committed file must be exactly what the builder produces",
        )

    def test_no_eval_line_appears_in_any_training_corpus(self) -> None:
        build = make_ru_eval_set.build_eval_set()
        lines = eval_lines(read_committed())
        self.assertEqual(tuple(lines), build.lines)
        self.assertEqual(4, len(build.training_normalized))
        for label, training in sorted(build.training_normalized.items()):
            overlap = training.intersection(lines)
            self.assertEqual(
                set(),
                overlap,
                f"eval lines must not appear in {label}: {sorted(overlap)[:5]}",
            )
        # Sanity: the held-out pool must be real -- enough candidates to fill the set, and
        # the content exclusion against the conv stream must actually bite.
        self.assertGreaterEqual(build.stats["candidates"], EXPECTED_LINES)
        self.assertGreater(build.stats["dropped_normalized_conv_collision"], 0)


if __name__ == "__main__":
    unittest.main()
