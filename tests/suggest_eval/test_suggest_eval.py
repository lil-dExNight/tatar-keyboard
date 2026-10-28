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
keystroke simulator, all on tiny synthetic fixtures that need no assets. A third block covers
the completion-by-prefix-length and strip-empty report rows of ``scripts/suggest_eval.py``
over the same kind of fixture.
"""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
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
EXPECTED_SHA256 = "282f3abe194e85e8c094cdff781d651f8f61b786a5d9d6ee2c7b443babcea234"


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
suggest_eval = load_module("suggest_eval", ROOT / "scripts" / "suggest_eval.py")


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

    def test_bigram_successes_come_first_and_truncate_to_four(self) -> None:
        self.assertEqual(["син", "китап", "дие", "сәләм"], self.mirror().predict_top3("мин"))

    def test_forms_fill_free_cells_before_the_fallback(self) -> None:
        self.assertEqual(["балалар", "мин", "син", "китап"], self.mirror().predict_top3("бала"))

    def test_fallback_excludes_the_committed_word_and_the_shown(self) -> None:
        # No bigrams, no attested forms: the pool fills, minus the head itself.
        self.assertEqual(["мин", "син", "сәләм", "бала"], self.mirror().predict_top3("китап"))
        # One bigram successor; the pool fills the rest minus the successor.
        self.assertEqual(["мин", "син", "китап", "сәләм"], self.mirror().predict_top3("дие"))

    def test_unknown_head_still_gets_the_fallback(self) -> None:
        shown = self.mirror().predict_top3("qqq")
        self.assertEqual(["мин", "син", "китап", "сәләм"], shown)

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
        self.assertEqual(["эшлә", "эшләү", "эшләмәк", "эшләп"], mirror.prefix_top3("эш"))
        # Boosted at the complete-word prefix: suffix continuations first, then the rest.
        self.assertEqual(["эшләү", "эшләп", "эшләмәк"], mirror.prefix_top3("эшлә"))

    def test_prefix_top3_context_boost(self) -> None:
        mirror = self.mirror()
        # The default is off: the context alone changes nothing unless it is passed.
        self.assertEqual(["бала", "балалар"], mirror.prefix_top3("б"))
        # A packed successor leads, then the unchanged track output over the rest.
        self.assertEqual(
            ["балалар", "бала"], mirror.prefix_top3("б", context=("балалар",))
        )
        # Frequency order is kept inside the context track, not the packing order.
        self.assertEqual(
            ["бала", "балалар"],
            mirror.prefix_top3("б", context=("балалар", "бала")),
        )
        # Successors that do not continue the prefix (or equal it) are inert.
        self.assertEqual(
            ["бала", "балалар"], mirror.prefix_top3("б", context=("китап", "б"))
        )
        # An empty context is the pre-boost path, byte-identical.
        self.assertEqual(mirror.prefix_top3("б"), mirror.prefix_top3("б", context=()))
        # The context track leads the same-stem track at a complete-word prefix.
        self.assertEqual(
            ["эшләмәк", "эшләү", "эшләп"],
            mirror.prefix_top3("эшлә", context=("эшләмәк",)),
        )
        # A successor whose remainder is also a suffix takes the context track only.
        self.assertEqual(
            ["эшләп", "эшләү", "эшләмәк"],
            mirror.prefix_top3("эшлә", context=("эшләп",)),
        )

    def test_packed_successors(self) -> None:
        mirror = self.mirror()
        self.assertEqual(("син", "китап", "дие", "сәләм"), mirror.packed_successors("мин"))
        self.assertEqual((), mirror.packed_successors("qqq"))


class CompletionMetricsTest(unittest.TestCase):
    """The per-prefix-length completion rows of ``scripts/suggest_eval.py`` on a fixture."""

    WORDS = ChainMirrorTest.WORDS + ["яя"]

    def test_top3_and_top1_rows_by_prefix_length(self) -> None:
        mirror = suggest_chain.ChainMirror(
            ChainMirrorTest.WORDS, ChainMirrorTest.FREQUENCIES, ChainMirrorTest.BIGRAMS
        )
        metrics = suggest_eval._completion_metrics(set(self.WORDS), mirror)
        self.assertEqual(
            [
                ("prefix_cp1_words", "13"),
                ("prefix_cp1_hits", "12"),
                ("prefix_top3_cp1_pct", "92.3077"),
                ("prefix_cp1_top1_hits", "6"),
                ("prefix_top1_cp1_pct", "46.1538"),
                ("prefix_cp2_words", "13"),
                ("prefix_cp2_hits", "12"),
                ("prefix_top3_cp2_pct", "92.3077"),
                ("prefix_cp2_top1_hits", "7"),
                ("prefix_top1_cp2_pct", "53.8462"),
                # "яя" is shorter than three code points and drops out of the cp3 denominator.
                ("prefix_cp3_words", "12"),
                ("prefix_cp3_hits", "8"),
                ("prefix_top3_cp3_pct", "66.6667"),
                ("prefix_cp3_top1_hits", "4"),
                ("prefix_top1_cp3_pct", "33.3333"),
            ],
            metrics,
        )


class StripEmptyMetricsTest(unittest.TestCase):
    """The strip-empty rows: prefix moments miss, the fallback keeps next-word non-empty."""

    def test_moment_counts_and_rates(self) -> None:
        mirror = suggest_chain.ChainMirror(
            ChainMirrorTest.WORDS, ChainMirrorTest.FREQUENCIES, ChainMirrorTest.BIGRAMS
        )
        lines = ["мин син бала", "китап зыя", "б мин"]
        pairs = [(head, tail) for line in lines
                 for head, tail in zip(line.split(" "), line.split(" ")[1:])]
        metrics = suggest_eval._strip_empty_metrics(lines, pairs, mirror)
        self.assertEqual(
            [
                # "зыя" misses at both its prefixes; the one-letter "б" has no prefix moment.
                ("strip_empty_prefix_moments", "15"),
                ("strip_empty_prefix_empty", "2"),
                ("strip_empty_prefix_pct", "13.3333"),
                ("strip_empty_nextword_moments", "4"),
                ("strip_empty_nextword_empty", "0"),
                ("strip_empty_nextword_pct", "0.0000"),
            ],
            metrics,
        )


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

    def test_paired_delta_ci95_is_deterministic(self) -> None:
        lo, hi = suggest_chain.paired_delta_ci95([0, 1, 0], [1, 1, 0], [2, 2, 2],
                                                 rounds=10, seed=suggest_chain.BOOTSTRAP_SEED)
        again = suggest_chain.paired_delta_ci95([0, 1, 0], [1, 1, 0], [2, 2, 2],
                                                rounds=10, seed=suggest_chain.BOOTSTRAP_SEED)
        self.assertEqual((lo, hi), again)
        # The first unit is the only one that differs between the arms (base 0, boost 1).
        # Ten seeded rounds over three units bracket the delta at [0, 33.33].
        self.assertEqual(0.0, lo)
        self.assertAlmostEqual(33.333333333333336, hi)


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


class FullEvalRecomputeTest(unittest.TestCase):
    """The full eval over the committed assets reproduces the pinned numbers.

    The JVM pins in TtSuggestEvalTest/RuSuggestEvalTest assert the same values on the Kotlin
    side; this class is the python-side recompute (run_python_tests.sh runs it, CI included), so
    a drift between the mirror and the JVM harness fails here. It runs the real pipeline (a few
    seconds per language) and never skips: the assets and the eval sets are committed.
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.tables = {}
        for language, argv in (("tat", []), ("rus", ["--language", "rus"])):
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                result = suggest_eval.main(argv)
            if result != 0:
                raise AssertionError(f"suggest_eval {language} exited {result}")
            table = {}
            for line in buffer.getvalue().splitlines():
                tag, name, value = line.split("|", 2)
                if tag == "EVAL":
                    table[name] = value
            cls.tables[language] = table

    def test_tatar_pins(self) -> None:
        table = self.tables["tat"]
        self.assertEqual(table["dict_word_coverage_tokens_pct"], "95.3327")
        self.assertEqual(table["bigram_head_coverage_pct"], "85.0750")
        self.assertEqual(table["nextword_chain_top1_pct"], "7.1972")
        self.assertEqual(table["nextword_chain_top3_pct"], "14.9712")
        self.assertEqual(table["prefix_top1_cp1_pct"], "0.9782")
        self.assertEqual(table["prefix_top1_cp2_pct"], "5.1581")
        self.assertEqual(table["prefix_top1_cp3_pct"], "15.1875")
        self.assertEqual(table["strip_empty_prefix_pct"], "2.1258")
        self.assertEqual(table["strip_empty_nextword_empty"], "0")
        self.assertEqual(table["ks_pct"], "36.7815")
        # The context boost (the armed exact pass) over the cp>=2 prefix moments; the JVM pins
        # in TtSuggestEvalTest assert the same values on the real index.
        self.assertEqual(table["ctxboost_cp2plus_moments"], "18338")
        self.assertEqual(table["ctxboost_cp2plus_top1_base_pct"], "34.2186")
        self.assertEqual(table["ctxboost_cp2plus_top1_boost_pct"], "36.0508")
        self.assertEqual(table["ctxboost_cp2plus_top1_delta_pp"], "+1.8323")
        self.assertEqual(table["ctxboost_cp2plus_top1_delta_ci95_lo"], "+1.4943")
        self.assertEqual(table["ctxboost_cp2plus_top1_delta_ci95_hi"], "+2.1713")
        self.assertEqual(table["ctxboost_cp2plus_top3_base_pct"], "54.5152")
        self.assertEqual(table["ctxboost_cp2plus_top3_boost_pct"], "55.5459")

    def test_russian_pins(self) -> None:
        table = self.tables["rus"]
        self.assertEqual(table["dict_word_coverage_tokens_pct"], "97.5485")
        self.assertEqual(table["bigram_head_coverage_pct"], "89.7895")
        self.assertEqual(table["nextword_chain_top1_pct"], "7.8594")
        self.assertEqual(table["nextword_chain_top3_pct"], "18.6520")
        self.assertEqual(table["prefix_top1_cp1_pct"], "1.0370")
        self.assertEqual(table["prefix_top1_cp2_pct"], "6.5611")
        self.assertEqual(table["prefix_top1_cp3_pct"], "17.9440")
        self.assertEqual(table["strip_empty_prefix_pct"], "1.3767")
        self.assertEqual(table["strip_empty_nextword_empty"], "0")
        self.assertEqual(table["ks_pct"], "40.3667")
        # The context boost over the cp>=2 prefix moments; RuSuggestEvalTest pins the same values.
        self.assertEqual(table["ctxboost_cp2plus_moments"], "15636")
        self.assertEqual(table["ctxboost_cp2plus_top1_base_pct"], "38.1683")
        self.assertEqual(table["ctxboost_cp2plus_top1_boost_pct"], "40.0998")
        self.assertEqual(table["ctxboost_cp2plus_top1_delta_pp"], "+1.9314")
        self.assertEqual(table["ctxboost_cp2plus_top1_delta_ci95_lo"], "+1.5925")
        self.assertEqual(table["ctxboost_cp2plus_top1_delta_ci95_hi"], "+2.2991")
        self.assertEqual(table["ctxboost_cp2plus_top3_base_pct"], "62.6759")
        self.assertEqual(table["ctxboost_cp2plus_top3_boost_pct"], "63.3538")


if __name__ == "__main__":
    unittest.main()
