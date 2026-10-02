#!/usr/bin/env python3
"""Contract tests for the Tatar suggestion eval set and its builder.

What is pinned and why:

* The committed eval file (``app/src/test/resources/tt_eval_sentences.txt``) is pinned by
  byte SHA-256 and by line count: any regeneration changes the set, and every suggestion
  quality baseline is measured against it.
* The format contract is checked field by field (comment header, NFC lowercase words that
  pass the dictionary pipeline's own ``normalize_word`` unchanged, 3..12 words per line,
  deduplicated, code-point sorted) so a hand edit cannot silently weaken the set.
* The builder is checked for byte-identical determinism: two independent builds into two
  temp workdirs must produce the same bytes as the committed file.
* The held-out property is checked against the reconstructed train90: no eval line may
  appear in the normalized training mix.

The last two groups need the licensed corpus inputs (``research/corpus/*.txt.gz``) and the
Leipzig tt corpora (``~/corpora-leipzig``), which are gitignored and therefore absent on a
clean CI checkout. Those tests SKIP with an explicit reason when the inputs are missing; the
pin and format tests above never skip, so a corrupted committed file fails everywhere.

A second block covers the pure functions of ``scripts/suggest_chain.py`` (the production-chain
mirror): the runtime suffix table port, the form generator, the chain composition, the
bootstrap sampler against golden SplitMix64 vectors, the lemma-strata predicate and the
keystroke simulator, all on tiny synthetic fixtures that need no assets.
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

# Changing either means a new eval set, and every baseline number derived from it must be
# re-measured in the same commit.
EXPECTED_LINES = 1_000
EXPECTED_SHA256 = "96688b4bb71838f5a99d0e9fbacae27e9b223cd41c8a1b16023220bd86040c35"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


make_eval_set = load_module("make_eval_set", MAKE_EVAL_SCRIPT)
coverage = load_module("dictionary_coverage", COVERAGE_SCRIPT)
suggest_chain = load_module("suggest_chain", ROOT / "scripts" / "suggest_chain.py")


def read_committed() -> bytes:
    return EVAL_FILE.read_bytes()


def eval_lines(data: bytes) -> list[str]:
    text = data.decode("utf-8")
    return [line for line in text.split("\n") if line and not line.startswith("#")]


def corpus_available() -> bool:
    conv_inputs = all(
        (CORPUS_DIR / name).is_file()
        for name in (
            make_eval_set.TATOEBA_FILE,
            make_eval_set.OPENSUBTITLES_FILE,
            make_eval_set.CONVERTER,
        )
    )
    leipzig_inputs = all(
        (make_eval_set.DEFAULT_LEIPZIG_DIR / name).is_file()
        for name in make_eval_set.LEIPZIG_SENTENCE_FILES
    )
    return conv_inputs and leipzig_inputs


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
    """Determinism and held-out tests; SKIP when the licensed corpus is absent."""

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

    def test_no_eval_line_appears_in_any_training_corpus(self) -> None:
        with tempfile.TemporaryDirectory() as workdir:
            build = self.build(Path(workdir))
        lines = eval_lines(read_committed())
        self.assertEqual(tuple(lines), build.lines)
        for label, training in (
            ("train90", build.train90_normalized),
            ("leipzig", build.leipzig_normalized),
        ):
            overlap = training.intersection(lines)
            self.assertEqual(
                set(),
                overlap,
                f"eval lines must not appear in {label}: {sorted(overlap)[:5]}",
            )
        # Sanity: the held-out slice must actually be held out -- a non-trivial number of
        # raw Tatoeba lines went to training and to the eval pool, not all to one side.
        self.assertGreater(build.stats["tatoeba_heldout_rows"], 0)
        self.assertGreater(
            build.stats["tatoeba_unique_rows"], build.stats["tatoeba_heldout_rows"]
        )


class SuffixTableTest(unittest.TestCase):
    """The ported runtime suffix table and the membership predicate."""

    def test_table_matches_the_runtime_size(self) -> None:
        # TatarSuffixRules.suffixCount pins the same number on the JVM side.
        self.assertEqual(167, len(suggest_chain.RUNTIME_SUFFIXES))

    def test_membership(self) -> None:
        self.assertTrue(suggest_chain.is_inflected_continuation("лар"))
        self.assertTrue(suggest_chain.is_inflected_continuation("мыйсыз"))
        self.assertTrue(suggest_chain.is_inflected_continuation("ннан"))
        self.assertFalse(suggest_chain.is_inflected_continuation(""))
        self.assertFalse(suggest_chain.is_inflected_continuation("лара"))
        self.assertFalse(suggest_chain.is_inflected_continuation("xyz"))


class GenerateFormsTest(unittest.TestCase):
    """The form generator port: order, harmony variants, dedup against the stem."""

    def test_back_vowel_stem_full_list(self) -> None:
        self.assertEqual(
            [
                "балалар", "баланың", "балага", "баланы", "балада", "баладан",
                "баласы", "баласын", "баласына", "баласында", "баласыннан",
                "балый", "балады", "балар", "балап", "балаган", "балау", "балача",
            ],
            suggest_chain.generate_forms("бала"),
        )

    def test_vowel_stem_contracts_present_and_drops_the_stem_itself(self) -> None:
        forms = suggest_chain.generate_forms("ди")
        self.assertNotIn("ди", forms)  # the contracted present equals the stem: skipped
        self.assertIn("дияр", forms)  # monosyllabic vowel stem takes -яр
        self.assertIn("дию", forms)  # и-final takes the й-glide masdar
        self.assertIn("диде", forms)
        self.assertEqual(17, len(forms))

    def test_mixed_harmony_stem_generates_both_variants(self) -> None:
        forms = suggest_chain.generate_forms("китап")
        self.assertEqual(36, len(forms))
        self.assertIn("китаплар", forms)
        self.assertIn("китапләр", forms)
        self.assertIn("китапка", forms)  # voiceless-final dative, back
        self.assertIn("китапкә", forms)  # and front
        self.assertIn("китапу", forms)
        self.assertIn("китапү", forms)

    def test_stem_without_harmony_vowel_generates_nothing(self) -> None:
        self.assertEqual([], suggest_chain.generate_forms("ппп"))

    def test_forms_of_filters_and_ranks_by_frequency(self) -> None:
        frequencies = {"балалар": 5, "балага": 9, "баласы": 7}
        forms = suggest_chain.forms_of(
            "бала", ["балага"], 3, lambda word: frequencies.get(word, 0)
        )
        self.assertEqual(["баласы", "балалар"], forms)


class ChainMirrorTest(unittest.TestCase):
    """The chain composition and the completion mirror on a synthetic dictionary."""

    WORDS = ["бала", "балалар", "дие", "дип", "китап", "мин", "син", "сәләм",
             "эшлә", "эшләмәк", "эшләп", "эшләү"]
    FREQUENCIES = [50, 5, 30, 10, 100, 200, 150, 80, 20, 7, 5, 9]
    BIGRAMS = {"мин": ["син", "китап", "дие", "сәләм"], "дие": ["мин"]}

    def mirror(self) -> "suggest_chain.ChainMirror":
        return suggest_chain.ChainMirror(self.WORDS, self.FREQUENCIES, self.BIGRAMS)

    def test_bigram_successes_come_first_and_truncate_to_three(self) -> None:
        self.assertEqual(["син", "китап", "дие"], self.mirror().predict_top3("мин"))

    def test_forms_fill_free_cells_before_the_fallback(self) -> None:
        self.assertEqual(["балалар", "мин", "син"], self.mirror().predict_top3("бала"))

    def test_fallback_excludes_the_committed_word_and_the_shown(self) -> None:
        # No bigrams, no attested forms: the pool fills, minus the head itself.
        self.assertEqual(["мин", "син", "сәләм"], self.mirror().predict_top3("китап"))
        # One bigram successor; the pool fills the rest minus the successor.
        self.assertEqual(["мин", "син", "китап"], self.mirror().predict_top3("дие"))

    def test_unknown_head_still_gets_the_fallback(self) -> None:
        shown = self.mirror().predict_top3("qqq")
        self.assertEqual(["мин", "син", "китап"], shown)

    def test_prefix_top3_plain_ranking(self) -> None:
        mirror = self.mirror()
        self.assertEqual(["бала", "балалар"], mirror.prefix_top3("б"))
        self.assertEqual(["мин"], mirror.prefix_top3("м"))
        # A short complete word does not engage the same-stem boost.
        self.assertEqual([], mirror.prefix_top3("дип"))
        self.assertEqual([], mirror.prefix_top3(""))

    def test_prefix_top3_same_stem_boost(self) -> None:
        mirror = self.mirror()
        # No boost below four code points even for a complete word ("эшлә" continues "эш").
        self.assertEqual(["эшлә", "эшләү", "эшләмәк"], mirror.prefix_top3("эш"))
        # Boosted at the complete-word prefix: suffix continuations first, then the rest.
        self.assertEqual(["эшләү", "эшләп", "эшләмәк"], mirror.prefix_top3("эшлә"))


class BootstrapSamplerTest(unittest.TestCase):
    """The resample stream against golden SplitMix64 vectors, and the CI index math."""

    def test_golden_indices(self) -> None:
        stream = suggest_chain.splitmix64_index_stream(1000, suggest_chain.BOOTSTRAP_SEED)
        self.assertEqual([213, 52, 549, 310, 150, 359, 297, 323],
                         [next(stream) for _ in range(8)])

    def test_nearest_rank_index(self) -> None:
        self.assertEqual(49, suggest_chain.nearest_rank_index(25, 2000))
        self.assertEqual(1949, suggest_chain.nearest_rank_index(975, 2000))
        self.assertEqual(2, suggest_chain.nearest_rank_index(25, 100))
        self.assertEqual(97, suggest_chain.nearest_rank_index(975, 100))

    def test_ci95_on_a_tiny_fixture(self) -> None:
        lo, hi = suggest_chain.bootstrap_rate_ci95([1, 0, 1], [2, 2, 2], rounds=10,
                                                   seed=suggest_chain.BOOTSTRAP_SEED)
        self.assertAlmostEqual(16.666666666666668, lo)
        self.assertAlmostEqual(50.0, hi)
        again = suggest_chain.bootstrap_rate_ci95([1, 0, 1], [2, 2, 2], rounds=10,
                                                  seed=suggest_chain.BOOTSTRAP_SEED)
        self.assertEqual((lo, hi), again)


class LemmaStratificationTest(unittest.TestCase):
    """The three-way stratum predicate over a synthetic frequency map."""

    def frequency_of(self, word: str) -> int:
        return {"бала": 50, "балалар": 5}.get(word, 0)

    def test_strata(self) -> None:
        self.assertEqual(suggest_chain.STRATUM_SEEN_FORM,
                         suggest_chain.lemma_stratum("бала", self.frequency_of))
        self.assertEqual(suggest_chain.STRATUM_NEW_FORM,
                         suggest_chain.lemma_stratum("балам", self.frequency_of))
        self.assertEqual(suggest_chain.STRATUM_UNSEEN_STEM,
                         suggest_chain.lemma_stratum("ббб", self.frequency_of))

    def test_longest_stem_wins(self) -> None:
        self.assertEqual(
            "балалар",
            suggest_chain.longest_stem_with_suffix_remainder("балаларның",
                                                             self.frequency_of),
        )

    def test_no_split_without_a_dictionary_stem(self) -> None:
        self.assertIsNone(
            suggest_chain.longest_stem_with_suffix_remainder(
                "балаларның", lambda word: {"бала": 50}.get(word, 0)
            )
        )


class KeystrokeSimulatorTest(unittest.TestCase):
    """The keystroke simulator on a two-sentence fixture."""

    def test_minimal_distinguishing_prefixes(self) -> None:
        lengths = suggest_chain.minimal_distinguishing_prefix_lengths(["аб", "б"])
        self.assertEqual({"аб": 1, "б": 1}, lengths)
        # A word that prefixes another has no distinguishing prefix.
        lengths = suggest_chain.minimal_distinguishing_prefix_lengths(["аб", "аба"])
        self.assertEqual({"аба": 3}, lengths)

    def test_costs(self) -> None:
        def next_word_top3(head: str) -> list[str]:
            return {"аб": ["б"]}.get(head, [])

        def completion_top3(prefix: str) -> list[str]:
            return {"а": ["аб"]}.get(prefix, [])

        mdp = suggest_chain.minimal_distinguishing_prefix_lengths(["аб", "б"])
        baseline, simulated, oracle = suggest_chain.keystroke_costs(
            ["аб б", "б аб"], next_word_top3, completion_top3, mdp
        )
        # Baseline: (2+1+1) + (1+2+1). Simulated: (tap after 1 key + next-word tap)
        # + (1 key for the one-letter word + completion at 1 key + tap). Oracle:
        # distinguishing prefix + tap for the first word, taps after.
        self.assertEqual(8, baseline)
        self.assertEqual(6, simulated)
        self.assertEqual(5, oracle)


if __name__ == "__main__":
    unittest.main()
