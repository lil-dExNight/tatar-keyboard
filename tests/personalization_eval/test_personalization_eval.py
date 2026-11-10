#!/usr/bin/env python3
"""Contract tests for ``scripts/personalization_eval.py`` (the learn-then-type harness).

What is pinned and why:

* The dictionary-only control of the full eval set must reproduce the pinned chain counters
  of ``TtSuggestEvalTest`` (4335 pairs, 365 top-1, 729 engine top-4), and the repetition
  split's control keystrokes must reproduce the pinned fourth-cell keystroke counters
  (33332/20914/8311): the harness taps every composed cell — the personalization arms are
  compared at the widest strip — and stands on the same mirror as ``suggest_eval.py``.
* Graduation counters per split (hash/chrono/rep) and observation arm (oracle/realistic/
  fixed) are pinned exactly; the JVM mirror ``PersonalizationEvalTest`` pins the same
  constants, which is the Python/JVM parity channel. Re-pin both sides together whenever
  the assets, the eval set or the harness constants change.
* Harness acceptance gates: byte-identical repeat runs; oracle-arm graduations >=
  realistic-arm on every split; the fixed arm equals the oracle arm on clean streams; the
  dictionary-only control never shows a graduated word (graduation implies out-of-vocabulary);
  an empty personal state never changes a strip; the pollution counters (personal cells
  shown and hit) and the multi-session LIFETIME_SERIALS sweep cells are pinned on the
  repetition split; the Russian mirror pins the same counter set over the committed
  Russian assets (its control reproduces the chain counters of RuSuggestEvalTest).
* Unit tests cover the SplitMix64/FNV-1a primitives against the golden vectors shared with
  the JVM tests, the splits, the pending/entries tables (thresholds, evictions, the
  flush-prune lifetime), the coalescing worker on a hand-computed timeline, the merge
  replica (promotion to cell 0 under PROMOTION_MIN_USAGE / PROMOTION_TOP_FREQUENCY_GATE),
  the personalized NEXT_WORD chain and the precedence arm
  (PERSONAL_BIGRAM_PRECEDENCE_MIN_FREQUENCY on frequency, not usage) on synthetic
  fixtures, and the measure walk (with pollution counters) on a hand-computed fixture.
"""
from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "personalization_eval.py"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


harness = load_module("personalization_eval", SCRIPT)
suggest_eval = load_module("suggest_eval", ROOT / "scripts" / "suggest_eval.py")
suggest_chain = load_module("suggest_chain", ROOT / "scripts" / "suggest_chain.py")

# Pins of the committed assets and eval set (see TtSuggestEvalTest).
PIN_CONTROL_PAIRS = 4_473
PIN_CONTROL_TOP1 = 345
PIN_CONTROL_VISIBLE_TOP3 = 652
PIN_CONTROL_ENGINE_TOP4 = 741
PIN_REP_CTRL_KS_BASELINE = 34_196
PIN_REP_CTRL_KS_SIMULATED = 21_095
PIN_REP_CTRL_KS_ORACLE = 8_451

# Graduation-counter pins per split and arm; PersonalizationEvalTest.kt pins the same
# numbers (parity channel). The hash/chrono halves carry no repeated word, so no word ever
# reaches the learn threshold of 3 there.
PIN_GRAD_WORDS = {
    ("hash", "oracle"): 0, ("hash", "realistic"): 0, ("hash", "fixed"): 0,
    ("chrono", "oracle"): 1, ("chrono", "realistic"): 1, ("chrono", "fixed"): 1,
    ("rep", "oracle"): 192, ("rep", "realistic"): 192, ("rep", "fixed"): 192,
}
PIN_GRAD_PAIRS = {
    ("hash", "oracle"): 33, ("hash", "realistic"): 33, ("hash", "fixed"): 33,
    ("chrono", "oracle"): 42, ("chrono", "realistic"): 42, ("chrono", "fixed"): 42,
    ("rep", "oracle"): 81, ("rep", "realistic"): 81, ("rep", "fixed"): 81,
}
PIN_OBSERVED_LOOKUPS = {
    ("hash", "oracle"): 14_740, ("hash", "realistic"): 14_740, ("hash", "fixed"): 14_740,
    ("chrono", "oracle"): 14_586, ("chrono", "realistic"): 14_586, ("chrono", "fixed"): 14_586,
    ("rep", "oracle"): 89_169, ("rep", "realistic"): 89_169, ("rep", "fixed"): 89_169,
}
PIN_EMPTY_OBSERVATIONS = {
    ("hash", "oracle"): 788, ("hash", "realistic"): 788, ("hash", "fixed"): 788,
    ("chrono", "oracle"): 808, ("chrono", "realistic"): 808, ("chrono", "fixed"): 808,
    ("rep", "oracle"): 5_010, ("rep", "realistic"): 5_010, ("rep", "fixed"): 5_010,
}
PIN_WORD_COMPLETIONS = {
    "hash": 93, "chrono": 87, "rep": 591,
}
PIN_PAIR_OBSERVATIONS = {
    "hash": 2_224, "chrono": 2_173, "rep": 13_419,
}
PIN_PAIRS_DROPPED_UNKNOWN_CONTEXT = {"hash": 0, "chrono": 0, "rep": 0}
PIN_WORD_PENDING_EVICTIONS = {"hash": 0, "chrono": 0, "rep": 0}
PIN_PAIR_PENDING_EVICTIONS = {"hash": 1_887, "chrono": 1_798, "rep": 12_400}
PIN_FIX_PROBES = {"hash": 54, "chrono": 38, "rep": 369}
PIN_PRIME_UNSEEN_TOKENS = {"hash": 67, "chrono": 58, "rep": 417}
PIN_UNSEEN_STEM = {"hash": (0, 0, 0), "chrono": (0, 0, 0), "rep": (366, 366, 366)}
# The realistic-arm sweep on the repetition split: the timeline parity channel.
PIN_REP_SWEEP = {
    100: (155, 72_173, 0, 19_578),  # (grad_words, observed, dropped, staled)
    200: (0, 0, 29_009, 73_579),
}
# Measure-side pins of the repetition split (oracle arm). The completion top-1 and
# accept-top-1 pins moved when the harness started modeling the production promotion
# (PROMOTION_MIN_USAGE, PROMOTION_TOP_FREQUENCY_GATE); the disp_* pins moved when the
# precedence arm started mirroring the production frequency-threshold rule.
PIN_REP_COMP_ACCEPT = 196
PIN_REP_COMP_ACCEPT_TOP1 = 54
PIN_REP_COMP_CP1_TOP1 = 0
PIN_REP_COMP_CP2_TOP1 = 7
PIN_REP_COMP_CP2_VISIBLE_TOP3 = 70
PIN_REP_COMP_CP2_ENGINE_TOP4 = 70
PIN_REP_COMP_CP3_TOP1 = 24
PIN_REP_COMP_CP4_TOP1 = 9
PIN_REP_NW_PERS_VISIBLE_TOP3 = 652
PIN_REP_NW_PERS_ENGINE_TOP4 = 741
PIN_REP_NW_DISP_VISIBLE_TOP3 = 698
PIN_REP_NW_DISP_ENGINE_TOP4 = 778
PIN_REP_NW_DISP_GAINED = 47
PIN_REP_KS_PERS_KEYS = 20_179
PIN_REP_REORD_PROMO_1 = 197
# Pollution pins: personal cells shown and hit, per measure walk (the hash/chrono halves
# graduate no word, so only the next-word pair cells appear there).
PIN_REP_COMP_PERSONAL_SHOWN = 8_571
PIN_REP_COMP_PERSONAL_HIT = 197
PIN_REP_NW_PERSONAL_SHOWN = 1_523
PIN_REP_NW_PERSONAL_HIT = 145
PIN_HASH_NW_PERSONAL_SHOWN = 511
PIN_HASH_NW_PERSONAL_HIT = 25
# The multi-session arm on the repetition split: one session per prime pass with a
# pending flush between passes. The production lifetime (500) prunes nothing on this
# stream — the pending tables churn fully within a pass — so all three sweep cells match
# the one-session graduation counters.
PIN_REP_SESSIONS = {
    500: (192, 81, 366, 0, 0),
    2_000: (192, 81, 366, 0, 0),
    None: (192, 81, 366, 0, 0),
}

# Russian mirror pins (see RuSuggestEvalTest): the same harness over the committed Russian
# eval set and assets, no suffix rules. The hash/chrono halves graduate no word, as in
# Tatar; the repetition split is the only graduation source.
RU_PIN_CONTROL_PAIRS = 4_695
RU_PIN_CONTROL_TOP1 = 391
RU_PIN_CONTROL_VISIBLE_TOP3 = 761
RU_PIN_CONTROL_ENGINE_TOP4 = 851
RU_PIN_GRAD_WORDS = {
    ("hash", "oracle"): 0, ("hash", "realistic"): 0, ("hash", "fixed"): 0,
    ("chrono", "oracle"): 0, ("chrono", "realistic"): 0, ("chrono", "fixed"): 0,
    ("rep", "oracle"): 95, ("rep", "realistic"): 95, ("rep", "fixed"): 95,
}
RU_PIN_GRAD_PAIRS = {
    ("hash", "oracle"): 72, ("hash", "realistic"): 72, ("hash", "fixed"): 72,
    ("chrono", "oracle"): 76, ("chrono", "realistic"): 76, ("chrono", "fixed"): 76,
    ("rep", "oracle"): 167, ("rep", "realistic"): 167, ("rep", "fixed"): 167,
}
RU_PIN_WORD_COMPLETIONS = {"hash": 43, "chrono": 57, "rep": 288}
RU_PIN_PAIR_OBSERVATIONS = {"hash": 2_247, "chrono": 2_301, "rep": 14_085}
RU_PIN_FIX_PROBES = {"hash": 76, "chrono": 75, "rep": 357}
RU_PIN_PRIME_UNSEEN_TOKENS = {"rep": 405}
RU_PIN_UNSEEN_STEM = {"rep": (288, 288, 288)}
RU_PIN_SWEEP = {100: 82, 200: 0}
RU_PIN_REP_KS_BASELINE = 31_780
RU_PIN_REP_KS_SIMULATED = 18_951
RU_PIN_REP_KS_ORACLE = 7_830
RU_PIN_REP_COMP_ACCEPT = 95
RU_PIN_REP_COMP_ACCEPT_TOP1 = 30
RU_PIN_REP_KS_PERS_KEYS = 18_678
RU_PIN_REP_NW_PERS_VISIBLE_TOP3 = 761
RU_PIN_REP_NW_PERS_ENGINE_TOP4 = 851
RU_PIN_REP_NW_DISP_VISIBLE_TOP3 = 845
RU_PIN_REP_NW_DISP_ENGINE_TOP4 = 958
RU_PIN_REP_NW_DISP_GAINED = 115
RU_PIN_REP_COMP_PERSONAL_SHOWN = 6_589
RU_PIN_REP_COMP_PERSONAL_HIT = 96
RU_PIN_REP_NW_PERSONAL_SHOWN = 2_569
RU_PIN_REP_NW_PERSONAL_HIT = 295
# One session per prime pass; the pending tables churn fully within a pass, so the
# production lifetime prunes nothing, as in Tatar.
RU_PIN_REP_SESSIONS = {
    500: (95, 167, 288, 0, 0),
    2_000: (95, 167, 288, 0, 0),
    None: (95, 167, 288, 0, 0),
}

_OUTPUT: dict[str, str] | None = None


def harness_output() -> dict[str, str]:
    """The harness run, once per process: a map of metric name to value."""
    global _OUTPUT
    if _OUTPUT is None:
        alphabet = suggest_eval.coverage.TATAR.alphabet
        lines = suggest_eval.load_eval_lines(suggest_eval.DEFAULT_EVAL, alphabet)
        words, frequencies, raw = suggest_eval.load_dictionary_words(
            suggest_eval.DEFAULT_DICT_ASSET
        )
        successes = suggest_eval.load_bigram_successes(
            suggest_eval.DEFAULT_BIGRAM_ASSET, words, raw
        )
        output_lines = harness.run(words, frequencies, successes, lines, alphabet, True)
        _OUTPUT = dict(line.split("|", 2)[1:] for line in output_lines)
        # Byte-identity gate: a second run over the same assets must match exactly.
        again = harness.run(words, frequencies, successes, lines, alphabet, True)
        assert again == output_lines, "the harness is not byte-identical across runs"
    return _OUTPUT


_RU_OUTPUT: dict[str, str] | None = None


def russian_output() -> dict[str, str]:
    """The Russian mirror of the harness run, once per process (see RuSuggestEvalTest)."""
    global _RU_OUTPUT
    if _RU_OUTPUT is None:
        alphabet = suggest_eval.coverage.RUSSIAN.alphabet
        lines = suggest_eval.load_eval_lines(suggest_eval.RU_EVAL, alphabet)
        words, frequencies, raw = suggest_eval.load_dictionary_words(suggest_eval.RU_DICT_ASSET)
        successes = suggest_eval.load_bigram_successes(
            suggest_eval.RU_BIGRAM_ASSET, words, raw
        )
        output_lines = harness.run(words, frequencies, successes, lines, alphabet, False)
        _RU_OUTPUT = dict(line.split("|", 2)[1:] for line in output_lines)
        assert (
            harness.run(words, frequencies, successes, lines, alphabet, False) == output_lines
        ), "the Russian mirror is not byte-identical across runs"
        return _RU_OUTPUT
    return _RU_OUTPUT


def ru_metric(name: str) -> str:
    return russian_output()[name]


def ru_metric_int(name: str) -> int:
    return int(ru_metric(name))


def metric(name: str) -> str:
    return harness_output()[name]


def metric_int(name: str) -> int:
    return int(metric(name))


class DeterminismTest(unittest.TestCase):
    """Byte-identical repeat runs (the second run happens inside harness_output)."""

    def test_run_is_byte_identical(self):
        self.assertIn("control.eval_pairs", harness_output())

    def test_primitives_match_golden_vectors(self):
        self.assertEqual(14_695_981_039_346_656_037, harness.fnv1a64(b""))
        self.assertEqual(
            3_368_278_190_552_415_294, harness.fnv1a64("ана".encode("utf-8"))
        )
        self.assertEqual(16_294_208_416_658_607_535, harness.splitmix64(0))

    def test_interval_stream_is_deterministic_and_bounded(self):
        stream_a = harness.interval_stream_ms()
        stream_b = harness.interval_stream_ms()
        values_a = [next(stream_a) for _ in range(64)]
        values_b = [next(stream_b) for _ in range(64)]
        self.assertEqual(values_a, values_b)
        for value in values_a:
            self.assertGreaterEqual(value, harness.KEY_INTERVAL_BASE_MS)
            self.assertLessEqual(
                value, harness.KEY_INTERVAL_BASE_MS + harness.KEY_INTERVAL_JITTER_MS
            )


class SplitTest(unittest.TestCase):
    """The three split arms: disjointness, determinism and pinned sizes."""

    LINES = ["а б в", "г д", "е ё ж", "з и к л", "м н о", "п р с т у"]

    def test_hash_halves_partition_and_determinism(self):
        prime_a, measure_a = harness.hash_halves(self.LINES)
        prime_b, measure_b = harness.hash_halves(self.LINES)
        self.assertEqual((prime_a, measure_a), (prime_b, measure_b))
        self.assertEqual(sorted(prime_a + measure_a), sorted(self.LINES))
        self.assertTrue(set(prime_a).isdisjoint(measure_a))

    def test_chronological_halves(self):
        prime, measure = harness.chronological_halves(self.LINES)
        self.assertEqual(self.LINES[:3], prime)
        self.assertEqual(self.LINES[3:], measure)

    def test_repetition_halves_repeat_the_stream(self):
        prime, measure = harness.repetition_halves(self.LINES)
        self.assertEqual(self.LINES * harness.REP_PRIME_PASSES, prime)
        self.assertEqual(self.LINES, measure)

    def test_hash_split_sizes_are_pinned(self):
        self.assertEqual(493, metric_int("hash.prime_lines"))
        self.assertEqual(507, metric_int("hash.measure_lines"))


class StoreModelTest(unittest.TestCase):
    """Pending/entries tables: thresholds, serial eviction, usage and frequency counters."""

    def test_pending_graduation_threshold_and_drop(self):
        pending = harness.PendingModel()
        self.assertEqual(1, pending.note("word"))
        self.assertEqual(2, pending.note("word"))
        pending.drop("word")
        self.assertEqual(1, pending.note("word"))

    def test_pending_evicts_the_lowest_serial_past_the_cap(self):
        pending = harness.PendingModel(cap=2)
        pending.note("a")
        pending.note("b")
        pending.note("a")  # refresh: b is now the oldest
        pending.note("c")  # size 3 over the cap: b is evicted
        self.assertEqual(1, pending.evictions)
        self.assertEqual(3, pending.note("a"))  # a survived and keeps its count
        self.assertEqual(1, pending.note("b"))  # b starts over

    def test_flush_prunes_only_the_stale_pending_entries(self):
        # A cap above the note count keeps this test about the lifetime, not eviction.
        pending = harness.PendingModel(cap=4 * harness.LIFETIME_SERIALS)
        pending.note("стар")
        for index in range(harness.LIFETIME_SERIALS - 1):
            pending.note(f"свеж{index}")
        # "стар" was noted LIFETIME_SERIALS notes ago: its serial lags the current one by
        # exactly the lifetime, so the flush drops it; nothing else is stale.
        self.assertEqual(1, pending.prune_for_flush(harness.LIFETIME_SERIALS))
        self.assertEqual(1, pending.note("стар"))  # the count starts over
        # A fresh-only table survives any lifetime, and None disables the prune entirely.
        fresh = harness.PendingModel(cap=4 * harness.LIFETIME_SERIALS)
        for index in range(16):
            fresh.note(f"свеж{index}")
        self.assertEqual(0, fresh.prune_for_flush(harness.LIFETIME_SERIALS))
        self.assertEqual(0, fresh.prune_for_flush(harness.LIFETIME_SERIALS + 1))
        self.assertEqual(0, fresh.prune_for_flush(None))

    def test_flush_pending_prunes_pending_only_and_keeps_graduations(self):
        model = harness.PersonalModel(lambda word: False, frozenset("абвг"))
        for _ in range(harness.WORD_LEARN_THRESHOLD):
            model.note_completion("абв")
        model.note_pair("абвг", "вба")
        self.assertIn("абв", model.words)
        # Age every pending serial past the lifetime with fresh pair notes; the pair
        # "абвг/вба" is at count 1 and stays pending, so it is pruned while the graduated
        # word survives and the flush reports its drops.
        for index in range(harness.LIFETIME_SERIALS + 5):
            model.note_pair("бваг", "г" + "а" * (index % 5))
        word_prunes, pair_prunes = model.flush_pending(harness.LIFETIME_SERIALS)
        self.assertEqual(0, word_prunes)
        self.assertGreater(pair_prunes, 0)
        self.assertIn("абв", model.words)
        self.assertNotIn(("абвг", "вба"), model.pairs)

    def test_word_learning_rules(self):
        model = harness.PersonalModel(lambda word: False, frozenset("абвг"))
        for _ in range(harness.WORD_LEARN_THRESHOLD - 1):
            model.note_completion("абв")
        model.note_completion("абв")
        self.assertIn("абв", model.words)
        self.assertEqual(1, model.words.usage["абв"])  # entries start at usage 1
        # Ineligible words (outside the 3..24 window) never reach the pending table.
        model.note_completion("аб")
        self.assertNotIn("аб", model.words)
        self.assertEqual(4, model.word_completions_reported)
        self.assertEqual(3, model.word_completions_eligible)

    def test_pair_learning_rules(self):
        model = harness.PersonalModel(lambda word: word == "контекст", frozenset("абвгконтекст"))
        model.note_pair("контекст", "абв")
        self.assertNotIn(("контекст", "абв"), model.pairs)
        model.note_pair("контекст", "абв")
        self.assertIn(("контекст", "абв"), model.pairs)
        # Graduated at the pair threshold: usage 0, frequency at the threshold.
        self.assertEqual([0, harness.PAIR_LEARN_THRESHOLD], model.pairs.by_context["контекст"]["абв"])
        model.note_pair("контекст", "абв")  # a re-observation bumps frequency only
        self.assertEqual([0, harness.PAIR_LEARN_THRESHOLD + 1], model.pairs.by_context["контекст"]["абв"])
        # An unknown context drops the progress instead of graduating.
        model.note_pair("бвг", "абв")
        model.note_pair("бвг", "абв")
        self.assertNotIn(("бвг", "абв"), model.pairs)
        self.assertEqual(1, model.pairs_dropped_unknown_context)
        # ... but a context learned as a personal word in the meantime counts as known.
        for _ in range(harness.WORD_LEARN_THRESHOLD):
            model.note_completion("бвг")
        model.note_pair("бвг", "абв")
        model.note_pair("бвг", "абв")
        self.assertIn(("бвг", "абв"), model.pairs)


class CoalescingWorkerTest(unittest.TestCase):
    """The latest-only timeline on a hand-computed schedule (latency 50 ms)."""

    def test_observation_schedule(self):
        worker = harness.CoalescingWorker(50)
        observed = []
        observed += worker.arrive(100, harness.CoalescingWorker.PREFIX, "а")
        observed += worker.arrive(200, harness.CoalescingWorker.PREFIX, "аб")
        observed += worker.arrive(210, harness.CoalescingWorker.PREFIX, "абв")
        observed += worker.arrive(400, harness.CoalescingWorker.NEXT_WORD)
        observed += worker.arrive(500, harness.CoalescingWorker.CUTOFF)
        # "а" completes at 150 (before the next event), "аб" is staled by "абв" at 210,
        # "абв" starts when the worker frees at 250 and completes at 300, observed at 400.
        self.assertEqual(["а", "абв"], observed)
        self.assertEqual(4, worker.issued_requests)
        self.assertEqual(0, worker.dropped_lookups)
        self.assertEqual(1, worker.staled_lookups)
        self.assertEqual(2, worker.observed_lookups)

    def test_pending_replacement_counts_as_dropped(self):
        worker = harness.CoalescingWorker(1_000)
        worker.arrive(0, harness.CoalescingWorker.PREFIX, "а")
        worker.arrive(10, harness.CoalescingWorker.PREFIX, "аб")
        worker.arrive(20, harness.CoalescingWorker.PREFIX, "абв")
        self.assertEqual(1, worker.dropped_lookups)
        self.assertEqual(1, worker.staled_lookups)
        self.assertEqual(0, worker.observed_lookups)


class FixtureEngine:
    """A tiny engine fixture: seven dictionary words, no bigram table."""

    def __init__(self) -> None:
        self.words = ["app", "apple", "apps", "b", "x", "y", "zr"]
        self.frequencies = [10, 5, 7, 20, 40, 50, 60]
        self.engine = harness.Engine(self.words, self.frequencies, {}, suffix_rules=False)

    def model(self, known=()) -> harness.PersonalModel:
        return harness.PersonalModel(lambda word: word in known, frozenset("abeprsxyz"))


class MergeReplicaTest(unittest.TestCase):
    """The CompositePrefixComputer.merge replica on the fixture engine."""

    def test_empty_state_returns_the_dictionary_list(self):
        fixture = FixtureEngine()
        model = fixture.model()
        # The exact pass excludes the word equal to the prefix.
        self.assertEqual(["app", "apps", "apple"], fixture.engine.chain.prefix_top3("ap"))
        self.assertEqual(
            ["app", "apps", "apple"], harness.merged_strip(fixture.engine, model, "ap")
        )

    def test_personal_word_leads_on_an_empty_exact_pass(self):
        fixture = FixtureEngine()
        model = fixture.model()
        model.words.add("zz")
        self.assertEqual([], fixture.engine.chain.prefix_top3("zz"))
        self.assertEqual(["zz"], harness.merged_strip(fixture.engine, model, "zz"))

    def test_personal_word_is_promoted_below_the_frequency_gate(self):
        fixture = FixtureEngine()
        model = fixture.model()
        model.words.add("appx")
        # app (frequency 10) is below the gate, so the graduated word is promoted to cell 0
        # and the exact cells start from index 0.
        self.assertEqual(
            ["appx", "app", "apps", "apple"], harness.merged_strip(fixture.engine, model, "ap")
        )

    def test_personal_word_yields_to_a_top_exact_candidate_at_the_gate(self):
        fixture = FixtureEngine()
        model = fixture.model()
        model.words.add("appx")
        # A top exact candidate at or above the gate blocks the promotion: give the fixture
        # a dictionary word at the gate that leads the exact pass of "ap".
        gate_engine = harness.Engine(
            ["app", "apple", "apps", "b", "x", "y", "zr"],
            [10_000, 5, 7, 20, 40, 50, 60],
            {},
            suffix_rules=False,
        )
        self.assertEqual(
            ["app", "appx", "apps", "apple"], harness.merged_strip(gate_engine, model, "ap")
        )

    def test_usage_orders_the_personal_candidates(self):
        fixture = FixtureEngine()
        model = fixture.model()
        model.words.add("zz")
        model.words.add("zy")
        model.words.note_use("zy")
        self.assertEqual(["zy", "zz"], harness.personal_candidates(model, "z"))


class PredictReplicaTest(unittest.TestCase):
    """The personalized NEXT_WORD chain and the chain-order arm on the fixture engine."""

    def make_engine(self, successes) -> harness.Engine:
        fixture = FixtureEngine()
        return harness.Engine(fixture.words, fixture.frequencies, successes, suffix_rules=False)

    def test_pairs_fill_free_cells_only(self):
        engine = self.make_engine({"b": ["x", "y", "zr", "w"]})
        model = harness.PersonalModel(lambda word: True, frozenset("abeprsxyzw"))
        model.pairs.upsert("b", "app", 2)
        self.assertEqual(["x", "y", "zr", "w"], harness.personalized_predict(engine, model, "b"))
        # With one free cell the pair fills it, behind the successors.
        engine = self.make_engine({"b": ["x", "y", "zr"]})
        self.assertEqual(["x", "y", "zr", "app"], harness.personalized_predict(engine, model, "b"))

    def test_pair_already_shown_is_not_duplicated(self):
        engine = self.make_engine({"b": ["x"]})
        model = harness.PersonalModel(lambda word: True, frozenset("abeprsxyz"))
        model.pairs.upsert("b", "x", 2)
        self.assertEqual(["x", "zr", "y", "app"], harness.personalized_predict(engine, model, "b"))

    def test_precedence_arm_moves_a_frequent_pair_to_index_2(self):
        engine = self.make_engine({"b": ["x", "y", "zr", "w"]})
        model = harness.PersonalModel(lambda word: True, frozenset("abeprsxyzw"))
        model.pairs.upsert("b", "app", 2)
        # Frequency 2 is below the precedence threshold: the merged answer is unchanged.
        self.assertEqual(["x", "y", "zr", "w"], harness.displace_predict(engine, model, "b"))
        # Clean typed observations (frequency, not usage) drive the precedence: bump the
        # pair to the threshold and it takes composed index 2, dropping the fourth successor.
        for _ in range(harness.PERSONAL_BIGRAM_PRECEDENCE_MIN_FREQUENCY - 2):
            model.pairs.note_observation("b", "app")
        self.assertEqual(["x", "y", "app", "zr"], harness.displace_predict(engine, model, "b"))
        # Usage bumps (accepted predictions) do not drive the rule.
        model2 = harness.PersonalModel(lambda word: True, frozenset("abeprsxyzw"))
        model2.pairs.upsert("b", "app", 2)
        for _ in range(5):
            model2.pairs.note_use("b", "app")
        self.assertEqual(["x", "y", "zr", "w"], harness.displace_predict(engine, model2, "b"))

    def test_precedence_arm_skips_an_already_visible_pair(self):
        engine = self.make_engine({"b": ["x", "y", "app"]})
        model = harness.PersonalModel(lambda word: True, frozenset("abeprsxyz"))
        model.pairs.upsert("b", "app", 2)
        for _ in range(harness.PERSONAL_BIGRAM_PRECEDENCE_MIN_FREQUENCY - 2):
            model.pairs.note_observation("b", "app")
        # The pair already occupies one of the first three composed cells: not moved.
        self.assertEqual(
            ["x", "y", "app", "zr"], harness.displace_predict(engine, model, "b")
        )

    def test_precedence_arm_holds_below_three_raw_successors(self):
        # Production gates the move on the RAW bigram-successor count: with two bundled
        # successors there is no move, even though the learned pairs push the merged
        # answer past the gate — the frequent pair keeps its merged cell.
        engine = self.make_engine({"b": ["x", "y"]})
        model = harness.PersonalModel(lambda word: True, frozenset("abeprsxyz"))
        model.pairs.upsert("b", "app", 2)
        model.pairs.note_use("b", "app")
        model.pairs.upsert("b", "apps", 2)
        for _ in range(harness.PERSONAL_BIGRAM_PRECEDENCE_MIN_FREQUENCY - 2):
            model.pairs.note_observation("b", "apps")
        self.assertEqual(
            ["x", "y", "app", "apps"], harness.displace_predict(engine, model, "b")
        )
        # One raw successor: the same gate holds with three learned pairs in the free cells.
        engine = self.make_engine({"b": ["x"]})
        model = harness.PersonalModel(lambda word: True, frozenset("abeprsxyz"))
        model.pairs.upsert("b", "app", 2)
        model.pairs.note_use("b", "app")
        model.pairs.note_use("b", "app")
        model.pairs.upsert("b", "apps", 2)
        model.pairs.note_use("b", "apps")
        model.pairs.upsert("b", "apx", 2)
        for _ in range(harness.PERSONAL_BIGRAM_PRECEDENCE_MIN_FREQUENCY - 2):
            model.pairs.note_observation("b", "apx")
        self.assertEqual(
            ["x", "app", "apps", "apx"], harness.displace_predict(engine, model, "b")
        )


class MeasureWalkTest(unittest.TestCase):
    """The measure walk on a hand-computed fixture."""

    def test_taps_keystrokes_and_hit_counters(self):
        fixture = FixtureEngine()
        model = fixture.model()
        model.words.add("zz")
        model.pairs.upsert("app", "x", 2)
        lines = ["app x", "app zz", "b zz"]
        walk = harness.measure_walk(fixture.engine, model, lines, frozenset({"zz"}))
        # app: in the strip at prefix 1, 2 keys; x: the learned pair cell, a 1-key tap.
        # app: 2; zz: not predicted, a completion tap at prefix 1, 2 keys — the graduated
        # word is promoted to cell 0 (zr's frequency 60 is below the gate), so the tap is
        # also a comp_top1 hit.
        # b: typed in full, 1 key; zz: a completion tap at prefix 1, 2 keys.
        self.assertEqual(2 + 1 + 2 + 2 + 1 + 2, walk.keystrokes)
        self.assertEqual(2, walk.gwt_tokens)
        self.assertEqual(2, walk.gwt_typed)
        self.assertEqual(0, walk.gwt_nw_tapped)
        self.assertEqual(3, walk.nw_pairs)
        self.assertEqual(1, walk.nw_pers_visible_top3)  # the pair cell x after app
        self.assertEqual(1, walk.nw_pers_engine_top4)
        self.assertEqual(1, walk.nw_true_learned)
        self.assertEqual(1, walk.nw_true_learned_shown)
        # Pollution: two strips paint the saved word zz (prefix 1 of each typed zz) and
        # both hit it; the pair cell x after app is painted twice (target x: a hit;
        # target zz: a miss), and nothing personal follows b or zz.
        self.assertEqual(2, walk.comp_personal_shown)
        self.assertEqual(2, walk.comp_personal_hit)
        self.assertEqual(2, walk.nw_personal_shown)
        self.assertEqual(1, walk.nw_personal_hit)
        self.assertEqual(2, walk.comp_typed[1])
        self.assertEqual(2, walk.comp_visible_top3[1])
        self.assertEqual(2, walk.comp_engine_top4[1])
        self.assertEqual(2, walk.comp_top1[1])  # the promotion leads the strip
        self.assertEqual(2, walk.comp_accept)
        self.assertEqual(2, walk.comp_accept_top1)
        self.assertEqual(2, walk.reord_promo_buckets[1])
        self.assertEqual(3, model.words.usage["zz"])  # two taps bumped the usage
        self.assertEqual([1, 2], model.pairs.by_context["app"]["x"])  # one tap-use


class HarnessAcceptanceTest(unittest.TestCase):
    """The pre-registered harness gates over the committed Tatar assets."""

    def test_dictionary_only_control_reproduces_the_pinned_chain_counters(self):
        self.assertEqual(PIN_CONTROL_PAIRS, metric_int("control.eval_pairs"))
        self.assertEqual(PIN_CONTROL_TOP1, metric_int("control.chain_top1_hits"))
        self.assertEqual(
            PIN_CONTROL_VISIBLE_TOP3, metric_int("control.chain_visible_top3_hits")
        )
        self.assertEqual(
            PIN_CONTROL_ENGINE_TOP4, metric_int("control.chain_engine_top4_hits")
        )
        self.assertEqual(PIN_REP_CTRL_KS_BASELINE, metric_int("rep.ctrl.ks_baseline_keys"))
        self.assertEqual(PIN_REP_CTRL_KS_SIMULATED, metric_int("rep.ctrl.ks_simulated_keys"))
        self.assertEqual(PIN_REP_CTRL_KS_ORACLE, metric_int("rep.ctrl.ks_oracle_keys"))

    def test_graduation_counters_are_pinned(self):
        for (split, arm), words in PIN_GRAD_WORDS.items():
            self.assertEqual(words, metric_int(f"{split}.{arm}.grad_words"), f"{split}.{arm}")
            self.assertEqual(
                PIN_GRAD_PAIRS[(split, arm)], metric_int(f"{split}.{arm}.grad_pairs"),
                f"{split}.{arm}",
            )
            self.assertEqual(
                PIN_OBSERVED_LOOKUPS[(split, arm)],
                metric_int(f"{split}.{arm}.observed_lookups"),
                f"{split}.{arm}",
            )
            self.assertEqual(
                PIN_EMPTY_OBSERVATIONS[(split, arm)],
                metric_int(f"{split}.{arm}.empty_observations"),
                f"{split}.{arm}",
            )
        for split, completions in PIN_WORD_COMPLETIONS.items():
            for arm in ("oracle", "realistic", "fixed"):
                self.assertEqual(
                    completions,
                    metric_int(f"{split}.{arm}.word_completions_reported"),
                    f"{split}.{arm}",
                )
                self.assertEqual(
                    PIN_PAIR_OBSERVATIONS[split],
                    metric_int(f"{split}.{arm}.pair_observations"),
                    f"{split}.{arm}",
                )
                self.assertEqual(
                    PIN_PAIRS_DROPPED_UNKNOWN_CONTEXT[split],
                    metric_int(f"{split}.{arm}.pairs_dropped_unknown_context"),
                    f"{split}.{arm}",
                )
                self.assertEqual(
                    PIN_WORD_PENDING_EVICTIONS[split],
                    metric_int(f"{split}.{arm}.word_pending_evictions"),
                    f"{split}.{arm}",
                )
                self.assertEqual(
                    PIN_PAIR_PENDING_EVICTIONS[split],
                    metric_int(f"{split}.{arm}.pair_pending_evictions"),
                    f"{split}.{arm}",
                )
            self.assertEqual(PIN_FIX_PROBES[split], metric_int(f"{split}.fixed.fix_probes"))

    def test_arm_ordering_gates(self):
        for split in ("hash", "chrono", "rep"):
            oracle = metric_int(f"{split}.oracle.grad_words")
            realistic = metric_int(f"{split}.realistic.grad_words")
            fixed = metric_int(f"{split}.fixed.grad_words")
            self.assertGreaterEqual(oracle, realistic, split)
            # On clean streams the completion-time probe sees the same monotone predicate,
            # so the fixed arm matches the oracle arm exactly.
            self.assertEqual(oracle, fixed, split)
            self.assertEqual(1, metric_int(f"{split}.unseen_stem.gate_pass"), split)
            oracle_unseen, realistic_unseen, fixed_unseen = PIN_UNSEEN_STEM[split]
            self.assertEqual(
                (oracle_unseen, realistic_unseen, fixed_unseen),
                (
                    metric_int(f"{split}.unseen_stem.oracle_unseen_tokens"),
                    metric_int(f"{split}.unseen_stem.realistic_unseen_tokens"),
                    metric_int(f"{split}.unseen_stem.fixed_unseen_tokens"),
                ),
                split,
            )
            self.assertEqual(
                PIN_PRIME_UNSEEN_TOKENS[split], metric_int(f"{split}.prime_unseen_tokens")
            )

    def test_realistic_sweep_cells_are_pinned(self):
        for latency, (graduated, observed, dropped, staled) in PIN_REP_SWEEP.items():
            cell = f"rep.sweep_l{latency}"
            self.assertEqual(graduated, metric_int(f"{cell}.grad_words"), cell)
            self.assertEqual(observed, metric_int(f"{cell}.observed_lookups"), cell)
            self.assertEqual(dropped, metric_int(f"{cell}.dropped_lookups"), cell)
            self.assertEqual(staled, metric_int(f"{cell}.staled_lookups"), cell)
            self.assertEqual(1, metric_int(f"{cell}.unseen_stem_pass"), cell)

    def test_structural_zero_churn_gates(self):
        self.assertEqual(0, metric_int("hash.empty_store.strip_mismatches"))
        for split in ("hash", "chrono", "rep"):
            for arm in ("oracle", "realistic", "fixed"):
                # Graduation implies out-of-vocabulary: the dictionary-only control can
                # never show a graduated word.
                self.assertEqual(
                    0,
                    metric_int(f"{split}.{arm}.comp_ctrl_visible_top3_hits"),
                    f"{split}.{arm}",
                )
                self.assertEqual(
                    0,
                    metric_int(f"{split}.{arm}.comp_ctrl_engine_top4_hits"),
                    f"{split}.{arm}",
                )

    def test_measure_side_pins_of_the_repetition_split(self):
        self.assertEqual(PIN_REP_COMP_ACCEPT, metric_int("rep.oracle.comp_accept"))
        self.assertEqual(PIN_REP_COMP_ACCEPT_TOP1, metric_int("rep.oracle.comp_accept_top1"))
        self.assertEqual(PIN_REP_COMP_CP1_TOP1, metric_int("rep.oracle.comp_cp1_top1"))
        self.assertEqual(PIN_REP_COMP_CP2_TOP1, metric_int("rep.oracle.comp_cp2_top1"))
        self.assertEqual(
            PIN_REP_COMP_CP2_VISIBLE_TOP3, metric_int("rep.oracle.comp_cp2_visible_top3")
        )
        self.assertEqual(
            PIN_REP_COMP_CP2_ENGINE_TOP4, metric_int("rep.oracle.comp_cp2_engine_top4")
        )
        self.assertEqual(PIN_REP_COMP_CP3_TOP1, metric_int("rep.oracle.comp_cp3_top1"))
        self.assertEqual(PIN_REP_COMP_CP4_TOP1, metric_int("rep.oracle.comp_cp4_top1"))
        self.assertEqual(
            PIN_REP_NW_PERS_VISIBLE_TOP3, metric_int("rep.oracle.nw_pers_visible_top3")
        )
        self.assertEqual(
            PIN_REP_NW_PERS_ENGINE_TOP4, metric_int("rep.oracle.nw_pers_engine_top4")
        )
        self.assertEqual(
            PIN_REP_NW_DISP_VISIBLE_TOP3, metric_int("rep.oracle.nw_disp_visible_top3")
        )
        self.assertEqual(
            PIN_REP_NW_DISP_ENGINE_TOP4, metric_int("rep.oracle.nw_disp_engine_top4")
        )
        self.assertEqual(PIN_REP_NW_DISP_GAINED, metric_int("rep.oracle.nw_disp_gained"))
        self.assertEqual(PIN_REP_KS_PERS_KEYS, metric_int("rep.oracle.ks_pers_keys"))
        self.assertEqual(PIN_REP_REORD_PROMO_1, metric_int("rep.oracle.reord_promo_1"))

    def test_pollution_pins_of_the_repetition_split(self):
        for arm in ("oracle", "realistic", "fixed"):
            self.assertEqual(
                PIN_REP_COMP_PERSONAL_SHOWN,
                metric_int(f"rep.{arm}.comp_personal_shown"),
                f"rep.{arm}",
            )
            self.assertEqual(
                PIN_REP_COMP_PERSONAL_HIT, metric_int(f"rep.{arm}.comp_personal_hit"),
                f"rep.{arm}",
            )
            self.assertEqual(
                PIN_REP_NW_PERSONAL_SHOWN, metric_int(f"rep.{arm}.nw_personal_shown"),
                f"rep.{arm}",
            )
            self.assertEqual(
                PIN_REP_NW_PERSONAL_HIT, metric_int(f"rep.{arm}.nw_personal_hit"),
                f"rep.{arm}",
            )
        # The hash half graduates no word, so no completion cell is personal; the chrono
        # half graduates exactly one repeated word, so its personal cells stay tiny.
        self.assertEqual(0, metric_int("hash.oracle.comp_personal_shown"))
        self.assertEqual(285, metric_int("chrono.oracle.comp_personal_shown"))
        self.assertEqual(
            PIN_HASH_NW_PERSONAL_SHOWN, metric_int("hash.oracle.nw_personal_shown")
        )
        self.assertEqual(PIN_HASH_NW_PERSONAL_HIT, metric_int("hash.oracle.nw_personal_hit"))

    def test_multi_session_sweep_cells_are_pinned(self):
        for lifetime, (words, pairs, unseen, word_prunes, pair_prunes) in PIN_REP_SESSIONS.items():
            cell = f"rep.sessions_l{'inf' if lifetime is None else lifetime}"
            self.assertEqual(words, metric_int(f"{cell}.grad_words"), cell)
            self.assertEqual(pairs, metric_int(f"{cell}.grad_pairs"), cell)
            self.assertEqual(unseen, metric_int(f"{cell}.grad_unseen_tokens"), cell)
            self.assertEqual(word_prunes, metric_int(f"{cell}.word_flush_prunes"), cell)
            self.assertEqual(pair_prunes, metric_int(f"{cell}.pair_flush_prunes"), cell)
        # The production lifetime must not lose a single graduation against no pruning.
        self.assertEqual(
            metric_int("rep.sessions_linf.grad_words"), metric_int("rep.sessions_l500.grad_words")
        )
        self.assertEqual(
            metric_int("rep.sessions_linf.grad_pairs"), metric_int("rep.sessions_l500.grad_pairs")
        )

class RussianMirrorAcceptanceTest(unittest.TestCase):
    """The Russian mirror of the harness over the committed Russian assets."""

    def test_dictionary_only_control_reproduces_the_pinned_chain_counters(self):
        self.assertEqual(RU_PIN_CONTROL_PAIRS, ru_metric_int("control.eval_pairs"))
        self.assertEqual(RU_PIN_CONTROL_TOP1, ru_metric_int("control.chain_top1_hits"))
        self.assertEqual(
            RU_PIN_CONTROL_VISIBLE_TOP3, ru_metric_int("control.chain_visible_top3_hits")
        )
        self.assertEqual(
            RU_PIN_CONTROL_ENGINE_TOP4, ru_metric_int("control.chain_engine_top4_hits")
        )

    def test_graduation_counters_are_pinned(self):
        for (split, arm), words in RU_PIN_GRAD_WORDS.items():
            self.assertEqual(words, ru_metric_int(f"{split}.{arm}.grad_words"), f"{split}.{arm}")
            self.assertEqual(
                RU_PIN_GRAD_PAIRS[(split, arm)], ru_metric_int(f"{split}.{arm}.grad_pairs"),
                f"{split}.{arm}",
            )
        for split, completions in RU_PIN_WORD_COMPLETIONS.items():
            for arm in ("oracle", "realistic", "fixed"):
                self.assertEqual(
                    completions,
                    ru_metric_int(f"{split}.{arm}.word_completions_reported"),
                    f"{split}.{arm}",
                )
                self.assertEqual(
                    RU_PIN_PAIR_OBSERVATIONS[split],
                    ru_metric_int(f"{split}.{arm}.pair_observations"),
                    f"{split}.{arm}",
                )
            self.assertEqual(
                RU_PIN_FIX_PROBES[split], ru_metric_int(f"{split}.fixed.fix_probes"), split
            )

    def test_arm_ordering_and_unseen_stem_gate(self):
        for split in ("hash", "chrono", "rep"):
            oracle = ru_metric_int(f"{split}.oracle.grad_words")
            realistic = ru_metric_int(f"{split}.realistic.grad_words")
            fixed = ru_metric_int(f"{split}.fixed.grad_words")
            self.assertGreaterEqual(oracle, realistic, split)
            self.assertEqual(oracle, fixed, split)
            self.assertEqual(1, ru_metric_int(f"{split}.unseen_stem.gate_pass"), split)
        self.assertEqual(
            RU_PIN_UNSEEN_STEM["rep"],
            (
                ru_metric_int("rep.unseen_stem.oracle_unseen_tokens"),
                ru_metric_int("rep.unseen_stem.realistic_unseen_tokens"),
                ru_metric_int("rep.unseen_stem.fixed_unseen_tokens"),
            ),
        )
        self.assertEqual(
            RU_PIN_PRIME_UNSEEN_TOKENS["rep"], ru_metric_int("rep.prime_unseen_tokens")
        )

    def test_latency_sweep_and_multi_session_cells_are_pinned(self):
        for latency, graduated in RU_PIN_SWEEP.items():
            self.assertEqual(
                graduated, ru_metric_int(f"rep.sweep_l{latency}.grad_words"), f"l{latency}"
            )
        for lifetime, (words, pairs, unseen, word_prunes, pair_prunes) in (
            RU_PIN_REP_SESSIONS.items()
        ):
            cell = f"rep.sessions_l{'inf' if lifetime is None else lifetime}"
            self.assertEqual(words, ru_metric_int(f"{cell}.grad_words"), cell)
            self.assertEqual(pairs, ru_metric_int(f"{cell}.grad_pairs"), cell)
            self.assertEqual(unseen, ru_metric_int(f"{cell}.grad_unseen_tokens"), cell)
            self.assertEqual(word_prunes, ru_metric_int(f"{cell}.word_flush_prunes"), cell)
            self.assertEqual(pair_prunes, ru_metric_int(f"{cell}.pair_flush_prunes"), cell)
        self.assertEqual(
            ru_metric_int("rep.sessions_linf.grad_words"),
            ru_metric_int("rep.sessions_l500.grad_words"),
        )

    def test_measure_side_and_pollution_pins_of_the_repetition_split(self):
        self.assertEqual(RU_PIN_REP_KS_BASELINE, ru_metric_int("rep.ctrl.ks_baseline_keys"))
        self.assertEqual(RU_PIN_REP_KS_SIMULATED, ru_metric_int("rep.ctrl.ks_simulated_keys"))
        self.assertEqual(RU_PIN_REP_KS_ORACLE, ru_metric_int("rep.ctrl.ks_oracle_keys"))
        self.assertEqual(RU_PIN_REP_COMP_ACCEPT, ru_metric_int("rep.oracle.comp_accept"))
        self.assertEqual(
            RU_PIN_REP_COMP_ACCEPT_TOP1, ru_metric_int("rep.oracle.comp_accept_top1")
        )
        self.assertEqual(RU_PIN_REP_KS_PERS_KEYS, ru_metric_int("rep.oracle.ks_pers_keys"))
        self.assertEqual(
            RU_PIN_REP_NW_PERS_VISIBLE_TOP3, ru_metric_int("rep.oracle.nw_pers_visible_top3")
        )
        self.assertEqual(
            RU_PIN_REP_NW_PERS_ENGINE_TOP4, ru_metric_int("rep.oracle.nw_pers_engine_top4")
        )
        self.assertEqual(
            RU_PIN_REP_NW_DISP_VISIBLE_TOP3, ru_metric_int("rep.oracle.nw_disp_visible_top3")
        )
        self.assertEqual(
            RU_PIN_REP_NW_DISP_ENGINE_TOP4, ru_metric_int("rep.oracle.nw_disp_engine_top4")
        )
        self.assertEqual(RU_PIN_REP_NW_DISP_GAINED, ru_metric_int("rep.oracle.nw_disp_gained"))
        for arm in ("oracle", "realistic", "fixed"):
            self.assertEqual(
                RU_PIN_REP_COMP_PERSONAL_SHOWN,
                ru_metric_int(f"rep.{arm}.comp_personal_shown"),
                f"rep.{arm}",
            )
            self.assertEqual(
                RU_PIN_REP_COMP_PERSONAL_HIT, ru_metric_int(f"rep.{arm}.comp_personal_hit"),
                f"rep.{arm}",
            )
            self.assertEqual(
                RU_PIN_REP_NW_PERSONAL_SHOWN, ru_metric_int(f"rep.{arm}.nw_personal_shown"),
                f"rep.{arm}",
            )
            self.assertEqual(
                RU_PIN_REP_NW_PERSONAL_HIT, ru_metric_int(f"rep.{arm}.nw_personal_hit"),
                f"rep.{arm}",
            )

if __name__ == "__main__":
    unittest.main()
