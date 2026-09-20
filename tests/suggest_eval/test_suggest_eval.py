#!/usr/bin/env python3
"""Contract tests for the TT-SUGGESTIONS phase-P0 eval set and its builder.

What is pinned and why:

* The committed eval file (``app/src/test/resources/tt_eval_sentences.txt``) is pinned by
  byte SHA-256 and by line count: any regeneration changes the set, and the set is what
  every baseline number in docs/TT-SUGGESTIONS.md is measured against.
* The format contract is checked field by field (comment header, NFC lowercase words that
  pass the dictionary pipeline's own ``normalize_word`` unchanged, 3..12 words per line,
  deduplicated, code-point sorted) so a hand edit cannot silently weaken the set.
* The builder is checked for byte-identical determinism: two independent builds into two
  temp workdirs must produce the same bytes as the committed file.
* The held-out property is checked against the reconstructed train90: no eval line may
  appear in the normalized training mix.

The last two groups need the licensed corpus inputs (``research/corpus/*.txt.gz``), which
are gitignored and therefore absent on a clean CI checkout. Those tests SKIP with an
explicit reason when the inputs are missing -- skipping is stated loudly by unittest; the
pin and format tests above never skip, so a corrupted committed file fails everywhere.
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
EVAL_FILE = ROOT / "app" / "src" / "test" / "resources" / "tt_eval_sentences.txt"
CORPUS_DIR = ROOT / "research" / "corpus"
MAKE_EVAL_SCRIPT = ROOT / "scripts" / "make_eval_set.py"
COVERAGE_SCRIPT = ROOT / "scripts" / "dictionary_coverage.py"

# Pinned at P0 creation (2026-09-19). Changing either means a new eval set, and every
# baseline number derived from it must be re-measured in the same commit.
EXPECTED_LINES = 1_000
EXPECTED_SHA256 = "d2ff0db52983028d352bbad464006f8c9552fc16b95d4cb5bdc2f724c8c0f619"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


make_eval_set = load_module("make_eval_set", MAKE_EVAL_SCRIPT)
coverage = load_module("dictionary_coverage", COVERAGE_SCRIPT)


def read_committed() -> bytes:
    return EVAL_FILE.read_bytes()


def eval_lines(data: bytes) -> list[str]:
    text = data.decode("utf-8")
    return [line for line in text.split("\n") if line and not line.startswith("#")]


def corpus_available() -> bool:
    return all(
        (CORPUS_DIR / name).is_file()
        for name in (
            make_eval_set.TATOEBA_FILE,
            make_eval_set.OPENSUBTITLES_FILE,
            make_eval_set.CONVERTER,
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
            self.assertGreaterEqual(len(words), make_eval_set.DEFAULT_MIN_WORDS)
            self.assertLessEqual(len(words), make_eval_set.DEFAULT_MAX_WORDS)
            for word in words:
                normalized, reason = coverage.normalize_word(word)
                self.assertIsNone(reason, f"{word!r} must pass the dictionary filter")
                self.assertEqual(word, normalized, f"{word!r} must already be canonical")

    def test_header_carries_tatoeba_attribution(self) -> None:
        text = read_committed().decode("utf-8")
        header = "\n".join(
            line for line in text.split("\n") if line.startswith("#")
        )
        self.assertIn("tatoeba.org", header)
        self.assertIn("CC BY 2.0 FR", header)
        self.assertIn("Tatoeba-v2026-07-08", header)


class CorpusDependentTest(unittest.TestCase):
    """Determinism and held-out tests; SKIP loudly when the licensed corpus is absent."""

    def build(self, workdir: Path):
        return make_eval_set.build_eval_set(CORPUS_DIR, workdir)

    def setUp(self) -> None:
        if not corpus_available():
            self.skipTest(
                "licensed corpus inputs research/corpus/*.txt.gz are not on this checkout"
            )

    def test_rebuild_is_byte_identical_to_committed_file(self) -> None:
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            build_first = self.build(Path(first))
            build_second = self.build(Path(second))
        rendered_first = make_eval_set.render_bytes(build_first)
        rendered_second = make_eval_set.render_bytes(build_second)
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

    def test_reconstruction_matches_documented_recipe(self) -> None:
        with tempfile.TemporaryDirectory() as workdir:
            build = self.build(Path(workdir))
        self.assertEqual(
            make_eval_set.CONV_SENTENCES_SHA256, build.stats["conv_output_sha256"]
        )
        self.assertEqual(make_eval_set.CONV_SENTENCES_LINES, build.stats["conv_rows"])

    def test_no_eval_line_appears_in_reconstructed_train90(self) -> None:
        with tempfile.TemporaryDirectory() as workdir:
            build = self.build(Path(workdir))
        lines = eval_lines(read_committed())
        self.assertEqual(tuple(lines), build.lines)
        overlap = build.train90_normalized.intersection(lines)
        self.assertEqual(
            set(),
            overlap,
            f"eval lines must not appear in the training mix: {sorted(overlap)[:5]}",
        )
        # Sanity: the held-out slice must actually be held out -- a non-trivial number of
        # raw Tatoeba lines went to training and to the eval pool, not all to one side.
        self.assertGreater(build.stats["tatoeba_heldout_rows"], 0)
        self.assertGreater(
            build.stats["tatoeba_unique_rows"], build.stats["tatoeba_heldout_rows"]
        )


if __name__ == "__main__":
    unittest.main()
