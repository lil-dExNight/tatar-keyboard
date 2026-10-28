#!/usr/bin/env python3
"""Contract tests for ``scripts/personalization_eval.py`` (the learn-then-type harness).

What is pinned and why:

* The dictionary-only control of the full eval set must reproduce the pinned chain counters
  of ``TtSuggestEvalTest`` (4335 pairs, 312 top-1, 552 top-3), and the repetition split's
  control keystrokes must reproduce the pinned keystroke counters (33332/21913/8311): the
  harness stands on the same mirror as ``suggest_eval.py``.
* Graduation counters per split (hash/chrono/rep) and observation arm (oracle/realistic/
  fixed) are pinned exactly; the JVM mirror ``PersonalizationEvalTest`` pins the same
  constants, which is the Python/JVM parity channel. Re-pin both sides together whenever
  the assets, the eval set or the harness constants change.
* Harness acceptance gates: byte-identical repeat runs; oracle-arm graduations >=
  realistic-arm on every split; the fixed arm equals the oracle arm on clean streams; the
  dictionary-only control never shows a graduated word (graduation implies out-of-vocabulary);
  an empty personal state never changes a strip.
* Unit tests cover the SplitMix64/FNV-1a primitives against the golden vectors shared with
  the JVM tests, the splits, the pending/entries tables (thresholds, evictions), the
  coalescing worker on a hand-computed timeline, the merge replica and the personalized
  NEXT_WORD chain on synthetic fixtures, and the measure walk on a hand-computed fixture.
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
PIN_CONTROL_PAIRS = 4_335
PIN_CONTROL_TOP1 = 312
PIN_CONTROL_TOP3 = 552
PIN_REP_CTRL_KS_BASELINE = 33_332
PIN_REP_CTRL_KS_SIMULATED = 21_913
PIN_REP_CTRL_KS_ORACLE = 8_311

# Graduation-counter pins per split and arm; PersonalizationEvalTest.kt pins the same
# numbers (parity channel). The hash/chrono halves carry no repeated word, so no word ever
# reaches the learn threshold of 3 there.
PIN_GRAD_WORDS = {
    ("hash", "oracle"): 0, ("hash", "realistic"): 0, ("hash", "fixed"): 0,
    ("chrono", "oracle"): 0, ("chrono", "realistic"): 0, ("chrono", "fixed"): 0,
    ("rep", "oracle"): 208, ("rep", "realistic"): 208, ("rep", "fixed"): 208,
}
PIN_GRAD_PAIRS = {
    ("hash", "oracle"): 41, ("hash", "realistic"): 41, ("hash", "fixed"): 41,
    ("chrono", "oracle"): 42, ("chrono", "realistic"): 42, ("chrono", "fixed"): 42,
    ("rep", "oracle"): 82, ("rep", "realistic"): 82, ("rep", "fixed"): 82,
}
PIN_OBSERVED_LOOKUPS = {
    ("hash", "oracle"): 14_783, ("hash", "realistic"): 14_783, ("hash", "fixed"): 14_783,
    ("chrono", "oracle"): 14_541, ("chrono", "realistic"): 14_541, ("chrono", "fixed"): 14_541,
    ("rep", "oracle"): 86_991, ("rep", "realistic"): 86_991, ("rep", "fixed"): 86_991,
}
PIN_EMPTY_OBSERVATIONS = {
    ("hash", "oracle"): 899, ("hash", "realistic"): 899, ("hash", "fixed"): 899,
    ("chrono", "oracle"): 853, ("chrono", "realistic"): 853, ("chrono", "fixed"): 853,
    ("rep", "oracle"): 5_097, ("rep", "realistic"): 5_097, ("rep", "fixed"): 5_097,
}
PIN_WORD_COMPLETIONS = {
    "hash": 116, "chrono": 97, "rep": 636,
}
PIN_PAIR_OBSERVATIONS = {
    "hash": 2_209, "chrono": 2_173, "rep": 13_005,
}
PIN_FIX_PROBES = {"hash": 58, "chrono": 43, "rep": 348}
PIN_PRIME_UNSEEN_TOKENS = {"hash": 86, "chrono": 73, "rep": 462}
PIN_A7_UNSEEN = {"hash": (0, 0, 0), "chrono": (0, 0, 0), "rep": (420, 420, 420)}
# The realistic-arm sweep on the repetition split: the timeline parity channel.
PIN_REP_SWEEP = {
    100: (162, 70_377, 0, 19_105),  # (grad_words, observed, dropped, staled)
    200: (0, 0, 28_247, 71_749),
}
# Measure-side pins of the repetition split (oracle arm).
PIN_REP_COMP_ACCEPT = 210
PIN_REP_COMP_CP2_TOP3 = 82
PIN_REP_NW_PERS_TOP3 = 554
PIN_REP_KS_PERS_KEYS = 21_065
PIN_REP_REORD_PROMO_1 = 212

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
        self.assertEqual(508, metric_int("hash.prime_lines"))
        self.assertEqual(492, metric_int("hash.measure_lines"))


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

    def test_word_learning_rules(self):
        model = harness.PersonalModel(lambda word: False, frozenset("абвг"))
        for _ in range(harness.WORD_LEARN_THRESHOLD - 1):
            model.note_completion("абв")
        self.assertNotIn("абв", model.words)
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

    def test_personal_word_takes_the_second_cell_and_pushes_the_third_out(self):
        fixture = FixtureEngine()
        model = fixture.model()
        model.words.add("appx")
        self.assertEqual(
            ["app", "appx", "apps"], harness.merged_strip(fixture.engine, model, "ap")
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
        engine = self.make_engine({"b": ["x", "y", "zr"]})
        model = harness.PersonalModel(lambda word: True, frozenset("abeprsxyz"))
        model.pairs.upsert("b", "app", 2)
        self.assertEqual(["x", "y", "zr"], harness.personalized_predict(engine, model, "b"))
        # With one free cell the pair fills it, behind the successors.
        engine = self.make_engine({"b": ["x", "y"]})
        self.assertEqual(["x", "y", "app"], harness.personalized_predict(engine, model, "b"))

    def test_pair_already_shown_is_not_duplicated(self):
        engine = self.make_engine({"b": ["x"]})
        model = harness.PersonalModel(lambda word: True, frozenset("abeprsxyz"))
        model.pairs.upsert("b", "x", 2)
        self.assertEqual(["x", "zr", "y"], harness.personalized_predict(engine, model, "b"))

    def test_displace_arm_requires_full_successors_and_usage(self):
        engine = self.make_engine({"b": ["x", "y", "zr"]})
        model = harness.PersonalModel(lambda word: True, frozenset("abeprsxyz"))
        model.pairs.upsert("b", "app", 2)
        # usage 0: no displacement.
        self.assertEqual(["x", "y", "zr"], harness.displace_predict(engine, model, "b"))
        for _ in range(harness.PAIR_DISPLACE_MIN_USAGE):
            model.pairs.note_use("b", "app")
        self.assertEqual(["x", "y", "app"], harness.displace_predict(engine, model, "b"))
        # With a free cell the pair is already shown; nothing to displace.
        engine = self.make_engine({"b": ["x", "y"]})
        self.assertEqual(["x", "y", "app"], harness.displace_predict(engine, model, "b"))


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
        # app: 2; zz: not predicted, a completion tap at prefix 1, 2 keys.
        # b: typed in full, 1 key; zz: a completion tap at prefix 1, 2 keys.
        self.assertEqual(2 + 1 + 2 + 2 + 1 + 2, walk.keystrokes)
        self.assertEqual(2, walk.gwt_tokens)
        self.assertEqual(2, walk.gwt_typed)
        self.assertEqual(0, walk.gwt_nw_tapped)
        self.assertEqual(3, walk.nw_pairs)
        self.assertEqual(1, walk.nw_pers_top3)  # the pair cell x after app
        self.assertEqual(1, walk.nw_true_learned)
        self.assertEqual(1, walk.nw_true_learned_shown)
        self.assertEqual(0, walk.nw_true_learned_suppressed)
        self.assertEqual(2, walk.comp_typed[1])
        self.assertEqual(2, walk.comp_top3[1])
        self.assertEqual(0, walk.comp_top1[1])  # zr leads the exact pass
        self.assertEqual(2, walk.comp_accept)
        self.assertEqual(0, walk.comp_accept_top1)
        self.assertEqual(2, walk.reord_promo_buckets[1])
        self.assertEqual(3, model.words.usage["zz"])  # two taps bumped the usage
        self.assertEqual([1, 2], model.pairs.by_context["app"]["x"])  # one tap-use


class HarnessAcceptanceTest(unittest.TestCase):
    """The pre-registered harness gates over the committed Tatar assets."""

    def test_dictionary_only_control_reproduces_the_pinned_chain_counters(self):
        self.assertEqual(PIN_CONTROL_PAIRS, metric_int("control.eval_pairs"))
        self.assertEqual(PIN_CONTROL_TOP1, metric_int("control.chain_top1_hits"))
        self.assertEqual(PIN_CONTROL_TOP3, metric_int("control.chain_top3_hits"))
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
            self.assertEqual(1, metric_int(f"{split}.a7.gate_pass"), split)
            oracle_unseen, realistic_unseen, fixed_unseen = PIN_A7_UNSEEN[split]
            self.assertEqual(
                (oracle_unseen, realistic_unseen, fixed_unseen),
                (
                    metric_int(f"{split}.a7.oracle_unseen_tokens"),
                    metric_int(f"{split}.a7.realistic_unseen_tokens"),
                    metric_int(f"{split}.a7.fixed_unseen_tokens"),
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
            self.assertEqual(1, metric_int(f"{cell}.a7_pass"), cell)

    def test_structural_zero_churn_gates(self):
        self.assertEqual(0, metric_int("hash.empty_store.strip_mismatches"))
        for split in ("hash", "chrono", "rep"):
            for arm in ("oracle", "realistic", "fixed"):
                # Graduation implies out-of-vocabulary: the dictionary-only control can
                # never show a graduated word.
                self.assertEqual(
                    0, metric_int(f"{split}.{arm}.comp_ctrl_top3_hits"), f"{split}.{arm}"
                )

    def test_measure_side_pins_of_the_repetition_split(self):
        self.assertEqual(PIN_REP_COMP_ACCEPT, metric_int("rep.oracle.comp_accept"))
        self.assertEqual(PIN_REP_COMP_CP2_TOP3, metric_int("rep.oracle.comp_cp2_top3"))
        self.assertEqual(PIN_REP_NW_PERS_TOP3, metric_int("rep.oracle.nw_pers_top3"))
        self.assertEqual(PIN_REP_KS_PERS_KEYS, metric_int("rep.oracle.ks_pers_keys"))
        self.assertEqual(PIN_REP_REORD_PROMO_1, metric_int("rep.oracle.reord_promo_1"))


if __name__ == "__main__":
    unittest.main()
