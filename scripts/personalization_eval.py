#!/usr/bin/env python3
"""Learn-then-type personalization harness over the committed eval assets.

The harness splits the committed eval set into a prime stream and a measure stream, replays
the prime stream through a model of the clean-run learning machines (word completions and
word pairs), then replays the measure stream through the production-chain mirror of
``suggest_chain.py`` extended with the personal-dictionary merge of
``CompositePrefixComputer.merge`` and the learned-pairs step of
``CompositePrefixComputer.predict``.

Splits (all deterministic):

* ``hash``: SplitMix64 sentence-hash halves, ``splitmix64(SPLIT_SEED ^ fnv1a64(line)) % 2``;
  the same FNV-1a/SplitMix64 discipline ``suggest_chain.py`` and the typo packs use.
* ``chrono``: first/second half in file order (the eval set is code-point sorted, so this is
  a maximally skewed locality split — a stress arm, not a time model).
* ``rep``: within-stream repetition; the whole stream primes, then the same stream measures.

Observation arms of the priming simulator (clean-run rules of CleanRunMachine.kt: the run
is clean while the word grows one keystroke at a time; a completion is reported only when a
PROPER prefix of the word came back with an empty exact pass during the run; pairs need only
a clean run and a non-empty in-sentence context):

* ``oracle``: every keystroke's prefix is observed.
* ``realistic``: latest-only coalescing (LatestOnlyPrefixEngine) modeled as a single worker
  with a fixed lookup latency and SplitMix64-jittered inter-keystroke intervals; a prefix
  lookup counts only when its answer lands strictly before the next request or text change.
* ``fixed``: realistic plus the deferred empty-prefix observation rule — when the run has
  no qualifying empty-prefix observation, binary-search the shortest proper prefix with an
  empty exact pass (emptiness is monotone in prefix length) and feed it retroactively.

Learning rules mirrored from the stores: a word graduates after 3 clean completions
(PendingCounters.LEARN_THRESHOLD), a pair after 2 clean observations with a context known to
the bundled or the personal dictionary (PersonalBigramStore.LEARN_THRESHOLD); pending tables
cap at 256 with lowest-serial eviction; word entries cap at 2000, pairs at 1000. Words enter
at usage 1, pairs at usage 0 with the frequency at the threshold; taps bump usage, clean
re-observations bump pair frequency. Eligibility: 3..24 code points per word, 1..24 per pair
member, per-language alphabet (the eval sets already satisfy it). Words are keyed directly —
the production salted hash is injective at this scale. One continuous session: pending
flush-pruning (LIFETIME_SERIALS) never fires. Graduations are frozen at measure start; usage
and pair-frequency counters keep tracking during the measure walk.

Measure-half reports, per split x arm: completion top-1/top-3 on graduated-word tokens
against the dictionary-only control, next-word top-1/top-3 with learned pairs filling their
at-most-2 free cells, a chain-order arm where a learned pair with usage >= 3 may displace
bigram successor #3 (quantifying the suppression surface), keystroke-savings deltas against
the per-split control, and strip-reorder counters per graduated-word occurrence.

Output: ``PERS|metric|value`` lines, byte-identical across runs. The Tatar control replay of
the full eval set reproduces the pinned chain counters of ``TtSuggestEvalTest``; the JVM
mirror ``PersonalizationEvalTest`` pins the graduation counters of this harness. Exit 2 on
any missing or invalid input.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Iterator, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

import dictionary_coverage as coverage  # noqa: E402
import suggest_chain  # noqa: E402
import suggest_eval  # noqa: E402
from typo_pack import fnv1a64, splitmix64  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]

# SplitMix64 stream seeds; changing either re-pins every counter of this harness.
SPLIT_SEED = 20261028
INTERVAL_SEED = 20261029

# Realistic-arm latency model, integer milliseconds. The canonical lookup latency is the
# pinned request-to-handoff p95 budget; the sweep brackets it on both sides.
KEY_INTERVAL_BASE_MS = 80
KEY_INTERVAL_JITTER_MS = 120
LOOKUP_LATENCY_MS = 16
LOOKUP_LATENCY_SWEEP_MS = (5, 16, 50, 100, 150, 200)

# The repetition arm primes with REP_PRIME_PASSES passes over the stream (the word learn
# threshold is 3, so fewer passes can graduate nothing) and measures on one more pass.
REP_PRIME_PASSES = 3

# Production learning knobs (PendingCounters, PersonalBigramStore, TpersFormat, TpersbFormat).
WORD_LEARN_THRESHOLD = 3
PAIR_LEARN_THRESHOLD = 2
PENDING_CAP = 256
WORD_CAP = 2_000
PAIR_CAP = 1_000
WORD_MIN_CODE_POINTS = 3
WORD_MAX_CODE_POINTS = 24
PAIR_WORD_MIN_CODE_POINTS = 1
PAIR_WORD_MAX_CODE_POINTS = 24

# Production strip/chain knobs (CompositePrefixComputer).
CELL_COUNT = suggest_chain.CELL_COUNT
BIGRAM_MAX_RESULTS = suggest_chain.BIGRAM_MAX_RESULTS
TOP_WORD_POOL = suggest_chain.TOP_WORD_POOL
MAX_PERSONAL_BIGRAM_CELLS = 2
PAIR_DISPLACE_MIN_USAGE = 3

SPLITS = ("hash", "chrono", "rep")
ARMS = ("oracle", "realistic", "fixed")
COMPLETION_PREFIX_LENGTHS = (1, 2, 3, 4)


# --------------------------------------------------------------------------------------
# Splits.
# --------------------------------------------------------------------------------------
def hash_halves(lines: Sequence[str]) -> tuple[list[str], list[str]]:
    """SplitMix64 sentence-hash halves: ``splitmix64(SPLIT_SEED ^ fnv1a64(line)) % 2``."""
    prime: list[str] = []
    measure: list[str] = []
    for line in lines:
        draw = splitmix64(SPLIT_SEED ^ fnv1a64(line.encode("utf-8"))) % 2
        (prime if draw == 0 else measure).append(line)
    return prime, measure


def chronological_halves(lines: Sequence[str]) -> tuple[list[str], list[str]]:
    """First/second half in file order."""
    middle = len(lines) // 2
    return list(lines[:middle]), list(lines[middle:])


def repetition_halves(lines: Sequence[str]) -> tuple[list[str], list[str]]:
    """Within-stream repetition: REP_PRIME_PASSES prime passes, then one measured pass."""
    return list(lines) * REP_PRIME_PASSES, list(lines)


def split_streams(name: str, lines: Sequence[str]) -> tuple[list[str], list[str]]:
    if name == "hash":
        return hash_halves(lines)
    if name == "chrono":
        return chronological_halves(lines)
    if name == "rep":
        return repetition_halves(lines)
    raise ValueError(f"unknown split: {name}")


def interval_stream_ms(seed: int = INTERVAL_SEED) -> Iterator[int]:
    """Keystroke gaps: a base interval plus a SplitMix64 jitter in [0, KEY_INTERVAL_JITTER_MS]."""
    state = splitmix64(seed)
    while True:
        yield KEY_INTERVAL_BASE_MS + state % (KEY_INTERVAL_JITTER_MS + 1)
        state = splitmix64(state)


# --------------------------------------------------------------------------------------
# Learning-state model (PendingCounters / PersonalEntries / PersonalBigramEntries).
# --------------------------------------------------------------------------------------
class PendingModel:
    """PendingCounters keyed by the word itself; serials and the cap mirror the production."""

    def __init__(self, cap: int = PENDING_CAP) -> None:
        self._counts: dict = {}
        self._serials: dict = {}
        self._next_serial = 1
        self._cap = cap
        self.evictions = 0

    def note(self, key) -> int:
        """One observation; returns the key's count. Evicts the lowest serial past the cap."""
        count = self._counts.get(key, 0) + 1
        self._counts[key] = count
        self._serials[key] = self._next_serial
        self._next_serial += 1
        if len(self._counts) > self._cap:
            victim = min(self._serials, key=self._serials.get)
            del self._counts[victim]
            del self._serials[victim]
            self.evictions += 1
        return count

    def drop(self, key) -> None:
        self._counts.pop(key, None)
        self._serials.pop(key, None)


class WordEntries:
    """Graduated personal words: usage counters and LRU serials (PersonalEntries)."""

    def __init__(self, cap: int = WORD_CAP) -> None:
        self.usage: dict[str, int] = {}
        self._serials: dict[str, int] = {}
        self._next_serial = 1
        self._cap = cap

    def __contains__(self, word: str) -> bool:
        return word in self.usage

    def add(self, word: str) -> None:
        """upsert: a new entry starts at usage 1; evicts the lowest serial past the cap."""
        self.usage[word] = self.usage.get(word, 0) + 1
        self._touch(word)
        while len(self.usage) > self._cap:
            self._evict_oldest()

    def note_use(self, word: str) -> None:
        if word not in self.usage:
            return
        self.usage[word] += 1
        self._touch(word)

    def _touch(self, word: str) -> None:
        self._serials[word] = self._next_serial
        self._next_serial += 1

    def _evict_oldest(self) -> None:
        victim = min(self._serials, key=self._serials.get)
        del self.usage[victim]
        del self._serials[victim]


class PairEntries:
    """Graduated pairs: usage and frequency counters plus LRU serials (PersonalBigramEntries)."""

    def __init__(self, cap: int = PAIR_CAP) -> None:
        self.by_context: dict[str, dict[str, list[int]]] = {}
        self._serials: dict[tuple[str, str], int] = {}
        self._next_serial = 1
        self._cap = cap

    def __contains__(self, pair: tuple[str, str]) -> bool:
        context, word = pair
        return context in self.by_context and word in self.by_context[context]

    def upsert(self, context: str, word: str, frequency_delta: int) -> None:
        """A new pair starts at usage 0 with the given frequency; bumps frequency if present."""
        words = self.by_context.setdefault(context, {})
        if word in words:
            words[word][1] += frequency_delta
        else:
            words[word] = [0, frequency_delta]
        self._touch(context, word)
        while len(self._serials) > self._cap:
            self._evict_oldest()

    def note_observation(self, context: str, word: str) -> None:
        """One more clean typed observation of a graduated pair: frequency += 1."""
        if (context, word) not in self:
            return
        self.by_context[context][word][1] += 1
        self._touch(context, word)

    def note_use(self, context: str, word: str) -> None:
        """An accepted prediction that is a graduated pair: usage += 1."""
        if (context, word) not in self:
            return
        self.by_context[context][word][0] += 1
        self._touch(context, word)

    def size(self) -> int:
        return len(self._serials)

    def _touch(self, context: str, word: str) -> None:
        self._serials[(context, word)] = self._next_serial
        self._next_serial += 1

    def _evict_oldest(self) -> None:
        victim = min(self._serials, key=self._serials.get)
        context, word = victim
        del self._serials[victim]
        del self.by_context[context][word]
        if not self.by_context[context]:
            del self.by_context[context]


class PersonalModel:
    """The two learning stores (words and pairs) over one priming or measure pass."""

    def __init__(self, known_word, alphabet: frozenset[str]) -> None:
        self.words = WordEntries()
        self.pairs = PairEntries()
        self._word_pending = PendingModel()
        self._pair_pending = PendingModel()
        self._known_word = known_word
        self._alphabet = alphabet
        self.word_completions_reported = 0
        self.word_completions_eligible = 0
        self.pair_observations = 0
        self.pairs_dropped_unknown_context = 0

    @property
    def word_pending_evictions(self) -> int:
        return self._word_pending.evictions

    @property
    def pair_pending_evictions(self) -> int:
        return self._pair_pending.evictions

    def _eligible(self, word: str, min_code_points: int, max_code_points: int) -> bool:
        if not min_code_points <= len(word) <= max_code_points:
            return False
        return all(character in self._alphabet for character in word)

    def note_completion(self, word: str) -> None:
        """One clean word completion with the empty-exact proof; graduates at the threshold."""
        self.word_completions_reported += 1
        if not self._eligible(word, WORD_MIN_CODE_POINTS, WORD_MAX_CODE_POINTS):
            return
        self.word_completions_eligible += 1
        if word in self.words:
            return
        if self._word_pending.note(word) >= WORD_LEARN_THRESHOLD:
            self.words.add(word)
            self._word_pending.drop(word)

    def note_pair(self, context: str, word: str) -> None:
        """One clean pair completion; graduates at the threshold with a known context."""
        self.pair_observations += 1
        if not self._eligible(context, PAIR_WORD_MIN_CODE_POINTS, PAIR_WORD_MAX_CODE_POINTS):
            return
        if not self._eligible(word, PAIR_WORD_MIN_CODE_POINTS, PAIR_WORD_MAX_CODE_POINTS):
            return
        if (context, word) in self.pairs:
            self.pairs.note_observation(context, word)
            return
        if self._pair_pending.note((context, word)) >= PAIR_LEARN_THRESHOLD:
            if not self._known_word(context) and context not in self.words:
                # The keyboard does not know the context word: the progress is dropped.
                self._pair_pending.drop((context, word))
                self.pairs_dropped_unknown_context += 1
                return
            self.pairs.upsert(context, word, PAIR_LEARN_THRESHOLD)
            self._pair_pending.drop((context, word))


# --------------------------------------------------------------------------------------
# Observation arms of the priming simulator.
# --------------------------------------------------------------------------------------
class CoalescingWorker:
    """Latest-only single-worker request queue with a fixed lookup latency in integer ms.

    Mirrors LatestOnlyPrefixEngine plus the controller's currency check: at most one
    in-flight and one pending request; a newer request replaces the pending one and makes
    the in-flight answer stale, and only an answer landing strictly before the next request
    or text change is applied. A ``cutoff`` event is a text change that issues no request
    (the sentence-final separator): in-flight and pending answers go stale but still occupy
    the worker.
    """

    PREFIX = "prefix"
    NEXT_WORD = "nextword"
    CUTOFF = "cutoff"

    def __init__(self, latency_ms: int) -> None:
        self.latency_ms = latency_ms
        self._busy: list | None = None  # [complete_at, kind, payload, alive]
        self._pending: list | None = None  # [arrival, kind, payload, alive]
        self.issued_requests = 0
        self.observed_lookups = 0
        self.dropped_lookups = 0
        self.staled_lookups = 0

    def arrive(self, time_ms: int, kind: str, payload: str | None = None) -> list[str]:
        """Advance to an event; returns the prefixes whose answers landed since the last one."""
        observed: list[str] = []
        while self._busy is not None and self._busy[0] < time_ms:
            complete_at, busy_kind, busy_payload, busy_alive = self._busy
            if busy_alive and busy_kind == self.PREFIX:
                observed.append(busy_payload)
            self._busy = None
            if self._pending is not None:
                _, pending_kind, pending_payload, pending_alive = self._pending
                self._pending = None
                self._busy = [
                    complete_at + self.latency_ms, pending_kind, pending_payload, pending_alive
                ]
        if kind == self.CUTOFF:
            if self._busy is not None:
                if self._busy[3]:
                    self.staled_lookups += 1
                self._busy[3] = False
            if self._pending is not None:
                if self._pending[3]:
                    self.staled_lookups += 1
                self._pending[3] = False
        else:
            self.issued_requests += 1
            if self._pending is not None:
                if self._pending[3]:
                    self.dropped_lookups += 1  # replaced before it ran
                self._pending = None
            if self._busy is None:
                self._busy = [time_ms + self.latency_ms, kind, payload, True]
            else:
                if self._busy[3]:
                    self.staled_lookups += 1
                self._busy[3] = False
                self._pending = [time_ms, kind, payload, True]
        self.observed_lookups += len(observed)
        return observed


def exact_pass_empty(chain: suggest_chain.ChainMirror, prefix: str) -> bool:
    """True when no dictionary word other than [prefix] itself starts with [prefix].

    ChainMirror.prefix_top3 is the exact pass of TdictPrefixIndex.lookup with typo recovery
    off (the word equal to the prefix is excluded), so its emptiness is the
    ``lastExactCount == 0`` signal CleanRunMachine.observeEmptyResult consumes. Personal
    words never mask it: the production feed reads the primary index only.
    """
    return not chain.prefix_top3(prefix)


def shortest_empty_prefix_probe(
    chain: suggest_chain.ChainMirror, word: str
) -> tuple[int | None, int]:
    """The deferred completion-time probe: binary-search the shortest proper prefix of [word]
    whose exact pass is empty (emptiness is monotone in prefix length). Returns the prefix
    length (or None) and the probe count."""
    low, high = 1, len(word) - 1
    found: int | None = None
    probes = 0
    while low <= high:
        middle = (low + high) // 2
        probes += 1
        if exact_pass_empty(chain, word[:middle]):
            found = middle
            high = middle - 1
        else:
            low = middle + 1
    return found, probes


class PrimeStats:
    def __init__(self) -> None:
        self.observed_lookups = 0
        self.empty_observations = 0
        self.fix_probes = 0
        self.fix_recovered_words = 0


def prime_pass(
    chain: suggest_chain.ChainMirror,
    lines: Sequence[str],
    arm: str,
    alphabet: frozenset[str],
    latency_ms: int = LOOKUP_LATENCY_MS,
) -> tuple[PersonalModel, PrimeStats, CoalescingWorker | None]:
    """Replay the prime stream through the clean-run model under one observation arm.

    Every word is typed letter by letter and committed with a separator (no taps while
    priming), so every run is clean; a word completion is reported when a proper prefix of
    the word was observed with an empty exact pass. Pairs are reported at every clean
    in-line completion with the previous word as the context.
    """
    model = PersonalModel(lambda word: chain.frequency_of(word) > 0, alphabet)
    stats = PrimeStats()
    worker = None if arm == "oracle" else CoalescingWorker(latency_ms)
    intervals = interval_stream_ms() if worker is not None else None
    clock = 0

    def observe(prefix: str, shortest_empty: int | None) -> int | None:
        if exact_pass_empty(chain, prefix):
            stats.empty_observations += 1
            if shortest_empty is None or len(prefix) < shortest_empty:
                return len(prefix)
        return shortest_empty

    for line in lines:
        words = line.split(" ")
        for position, word in enumerate(words):
            length = len(word)
            shortest_empty: int | None = None
            if worker is None:
                for k in range(1, length + 1):
                    stats.observed_lookups += 1
                    shortest_empty = observe(word[:k], shortest_empty)
            else:
                for k in range(1, length + 1):
                    clock += next(intervals)
                    for prefix in worker.arrive(clock, CoalescingWorker.PREFIX, word[:k]):
                        shortest_empty = observe(prefix, shortest_empty)
                clock += next(intervals)
                mid_line = position + 1 < len(words)
                kind = CoalescingWorker.NEXT_WORD if mid_line else CoalescingWorker.CUTOFF
                for prefix in worker.arrive(clock, kind):
                    shortest_empty = observe(prefix, shortest_empty)
            qualified = shortest_empty is not None and 1 <= shortest_empty < length
            if not qualified and arm == "fixed" and chain.frequency_of(word) == 0:
                # The deferred fix fires on unknown words only: a dictionary word's proper prefixes
                # are all continued by the word itself, so the probe cannot qualify there.
                found, probes = shortest_empty_prefix_probe(chain, word)
                stats.fix_probes += probes
                if found is not None:
                    qualified = True
                    stats.fix_recovered_words += 1
            if qualified:
                model.note_completion(word)
            if position > 0:
                model.note_pair(words[position - 1], word)
    return model, stats, worker


# --------------------------------------------------------------------------------------
# The merged strip and the personalized NEXT_WORD chain.
# --------------------------------------------------------------------------------------
class Engine:
    """The chain mirror plus the raw table views the personalization steps need."""

    def __init__(
        self,
        words: Sequence[str],
        frequencies: Sequence[int],
        successes_by_head: dict[str, list[str]],
        suffix_rules: bool,
    ) -> None:
        self.chain = suggest_chain.ChainMirror(
            words, frequencies, successes_by_head, suffix_rules=suffix_rules
        )
        self.successes = successes_by_head
        self.suffix_rules = suffix_rules
        # GlobalTopFrequencyFallbackFactory: the dictionary's top words, frequency
        # descending then code point ascending.
        self.fallback_pool = [
            word
            for word, _ in sorted(
                zip(words, frequencies), key=lambda pair: (-pair[1], pair[0])
            )[:TOP_WORD_POOL]
        ]


def personal_candidates(model: PersonalModel, prefix: str) -> list[str]:
    """PersonalDictionary.lookupCandidates: saved words starting with [prefix], usage
    descending, then normalized ascending (the array-order tiebreak)."""
    matches = [word for word in model.words.usage if word.startswith(prefix)]
    matches.sort(key=lambda word: (-model.words.usage[word], word))
    return matches


def merged_strip(engine: Engine, model: PersonalModel, prefix: str) -> list[str]:
    """CompositePrefixComputer.merge over the eval-configuration lookup (typo recovery off,
    so the dictionary list is all-exact) and the personal candidates of [prefix].

    A personal word that duplicates a dictionary candidate only restyles the cell; the
    harness stores normalized == raw forms, so that case changes nothing and returns the
    dictionary list, as production does.
    """
    dictionary = engine.chain.prefix_top3(prefix)
    personal_only = None
    for match in personal_candidates(model, prefix):
        if match not in dictionary:
            personal_only = match
            break
    if personal_only is None:
        return dictionary
    cells: list[str] = []
    if dictionary:
        cells.append(dictionary[0])
    cells.append(personal_only)
    for index in range(1 if dictionary else 0, len(dictionary)):
        if len(cells) >= CELL_COUNT:
            return cells
        cells.append(dictionary[index])
    return cells


def pair_successors(model: PersonalModel, context: str) -> list[tuple[str, int, int]]:
    """PersonalBigramDictionary.successorsFor: (word, usage, frequency), usage descending,
    frequency descending, then normalized ascending."""
    words = model.pairs.by_context.get(context)
    if not words:
        return []
    ranked = [(word, counters[0], counters[1]) for word, counters in words.items()]
    ranked.sort(key=lambda entry: (-entry[1], -entry[2], entry[0]))
    return ranked


def personalized_predict(engine: Engine, model: PersonalModel, head: str) -> list[str]:
    """CompositePrefixComputer.predict: bigram successors, then learned pairs in their
    at-most-2 free cells (a pair already shown is skipped), then word forms, then fallback."""
    result = list(engine.successes.get(head, ()))[:BIGRAM_MAX_RESULTS]
    if len(result) < CELL_COUNT:
        room = min(MAX_PERSONAL_BIGRAM_CELLS, CELL_COUNT - len(result))
        extras: list[str] = []
        for word, _, _ in pair_successors(model, head):
            if len(extras) >= room:
                break
            if word in result or word in extras:
                continue
            extras.append(word)
        result = result + extras
    if len(result) < CELL_COUNT and engine.suffix_rules:
        result = result + suggest_chain.forms_of(
            head, result, CELL_COUNT - len(result), engine.chain.frequency_of
        )
    if len(result) < CELL_COUNT:
        for word in engine.fallback_pool:
            if len(result) >= CELL_COUNT:
                break
            if word == head or word in result:
                continue
            result.append(word)
    return result


def displace_predict(engine: Engine, model: PersonalModel, head: str) -> list[str]:
    """The chain-order arm: the top learned pair with usage >= PAIR_DISPLACE_MIN_USAGE may
    displace bigram successor #3 when the successors fill all three cells."""
    base = personalized_predict(engine, model, head)
    if len(list(engine.successes.get(head, ()))[:BIGRAM_MAX_RESULTS]) < CELL_COUNT:
        return base
    learned = pair_successors(model, head)
    if not learned:
        return base
    top_word, top_usage, _ = learned[0]
    if top_usage < PAIR_DISPLACE_MIN_USAGE or top_word in base:
        return base
    return base[: CELL_COUNT - 1] + [top_word]


# --------------------------------------------------------------------------------------
# The measure-half walk.
# --------------------------------------------------------------------------------------
class MeasureStats:
    def __init__(self) -> None:
        self.keystrokes = 0
        # Next-word counters.
        self.nw_pairs = 0
        self.nw_pers_top1 = 0
        self.nw_pers_top3 = 0
        self.nw_disp_top1 = 0
        self.nw_disp_top3 = 0
        self.nw_learned_head_pairs = 0
        self.nw_true_learned = 0
        self.nw_true_learned_shown = 0
        self.nw_true_learned_suppressed = 0
        self.nw_suppressed_full_strip = 0
        self.nw_disp_gained = 0
        # Completion counters on graduated-word tokens (prime-end attribution).
        self.gwt_tokens = 0
        self.gwt_typed = 0
        self.gwt_nw_tapped = 0
        self.comp_typed = dict.fromkeys(COMPLETION_PREFIX_LENGTHS, 0)
        self.comp_top1 = dict.fromkeys(COMPLETION_PREFIX_LENGTHS, 0)
        self.comp_top3 = dict.fromkeys(COMPLETION_PREFIX_LENGTHS, 0)
        self.comp_ctrl_top1_hits = 0
        self.comp_ctrl_top3_hits = 0
        self.comp_accept = 0
        self.comp_accept_top1 = 0
        self.comp_never = 0
        # Strip reorders per graduated-word occurrence.
        self.reord_changes_pers = 0
        self.reord_changes_ctrl = 0
        self.reord_max_changes = 0
        self.reord_promo_buckets = [0, 0, 0]  # 0, 1, 2+ promotions per occurrence
        self.reord_max_promo = 0


def _strip_changes(strips: Sequence[Sequence[str]]) -> int:
    return sum(1 for first, second in zip(strips, strips[1:]) if first != second)


def _promotions(strips: Sequence[Sequence[str]], word: str) -> int:
    """Times [word]'s cell improves over consecutive prefixes (enters or moves up)."""
    count = 0
    previous_rank: int | None = None
    for strip in strips:
        rank = strip.index(word) if word in strip else None
        if rank is not None and (previous_rank is None or rank < previous_rank):
            count += 1
        previous_rank = rank
    return count


def measure_walk(
    engine: Engine,
    model: PersonalModel,
    lines: Sequence[str],
    graduated_words: frozenset[str],
) -> MeasureStats:
    """Replay the measure stream with the prime-end personal state.

    Tap semantics mirror keystroke_costs: a NEXT_WORD cell costs one tap (a graduated pair
    behind the tapped cell bumps its usage), a completion costs the typed prefix plus the
    tap (a saved personal word bumps its usage), an unshown word is typed in full (a clean
    typed completion re-observes the pair, bumping its frequency). New graduations stay
    frozen: pending counters do not run during the measure pass.
    """
    stats = MeasureStats()
    for line in lines:
        words = line.split(" ")
        previous: str | None = None
        for word in words:
            if word in graduated_words:
                stats.gwt_tokens += 1
            tapped_next = False
            if previous is not None:
                stats.nw_pairs += 1
                pers = personalized_predict(engine, model, previous)
                disp = displace_predict(engine, model, previous)
                if pers and word == pers[0]:
                    stats.nw_pers_top1 += 1
                if word in pers:
                    stats.nw_pers_top3 += 1
                if disp and word == disp[0]:
                    stats.nw_disp_top1 += 1
                if word in disp:
                    stats.nw_disp_top3 += 1
                if pair_successors(model, previous):
                    stats.nw_learned_head_pairs += 1
                if (previous, word) in model.pairs:
                    stats.nw_true_learned += 1
                    if word in pers:
                        stats.nw_true_learned_shown += 1
                    else:
                        stats.nw_true_learned_suppressed += 1
                        successors = list(engine.successes.get(previous, ()))
                        if len(successors[:BIGRAM_MAX_RESULTS]) >= CELL_COUNT:
                            stats.nw_suppressed_full_strip += 1
                    if word in disp and word not in pers:
                        stats.nw_disp_gained += 1
                if word in pers:
                    tapped_next = True
                    stats.keystrokes += 1
                    model.pairs.note_use(previous, word)
                    if word in graduated_words:
                        stats.gwt_nw_tapped += 1
            if not tapped_next:
                is_graduated = word in graduated_words
                if is_graduated:
                    stats.gwt_typed += 1
                strips_pers: list[list[str]] = []
                strips_ctrl: list[list[str]] = []
                hit_prefix: int | None = None
                for k in range(1, len(word)):
                    strip_pers = merged_strip(engine, model, word[:k])
                    if is_graduated:
                        strip_ctrl = engine.chain.prefix_top3(word[:k])
                        if k in stats.comp_typed:
                            stats.comp_typed[k] += 1
                            if strip_pers and strip_pers[0] == word:
                                stats.comp_top1[k] += 1
                            if word in strip_pers:
                                stats.comp_top3[k] += 1
                        if strip_ctrl and strip_ctrl[0] == word:
                            stats.comp_ctrl_top1_hits += 1
                        if word in strip_ctrl:
                            stats.comp_ctrl_top3_hits += 1
                        strips_pers.append(strip_pers)
                        strips_ctrl.append(strip_ctrl)
                    if word in strip_pers:
                        hit_prefix = k
                        break
                if is_graduated:
                    if hit_prefix is None and len(word) > 1:
                        # The full-word strip is still painted before the separator.
                        strips_pers.append(merged_strip(engine, model, word))
                        strips_ctrl.append(engine.chain.prefix_top3(word))
                    changes_pers = _strip_changes(strips_pers)
                    stats.reord_changes_pers += changes_pers
                    stats.reord_changes_ctrl += _strip_changes(strips_ctrl)
                    stats.reord_max_changes = max(stats.reord_max_changes, changes_pers)
                    promotions = _promotions(strips_pers, word)
                    stats.reord_promo_buckets[min(promotions, 2)] += 1
                    stats.reord_max_promo = max(stats.reord_max_promo, promotions)
                    if hit_prefix is not None:
                        stats.comp_accept += 1
                        if strips_pers[-1] and strips_pers[-1][0] == word:
                            stats.comp_accept_top1 += 1
                    else:
                        stats.comp_never += 1
                if hit_prefix is not None:
                    stats.keystrokes += hit_prefix + 1
                    model.words.note_use(word)
                else:
                    stats.keystrokes += len(word)
                    if previous is not None:
                        model.pairs.note_observation(previous, word)
            previous = word
    return stats


# --------------------------------------------------------------------------------------
# Controls and the report.
# --------------------------------------------------------------------------------------
def chain_counters(
    chain: suggest_chain.ChainMirror, lines: Sequence[str]
) -> tuple[int, int, int]:
    """(pairs, top-1 hits, top-3 hits) of the production NEXT_WORD chain over [lines]."""
    pairs = 0
    top1 = 0
    top3 = 0
    for line in lines:
        words = line.split(" ")
        for head, successor in zip(words, words[1:]):
            shown = chain.predict_top3(head)
            pairs += 1
            if shown and successor == shown[0]:
                top1 += 1
            if successor in shown:
                top3 += 1
    return pairs, top1, top3


def split_control(
    engine: Engine, lines: Sequence[str]
) -> tuple[int, int, int, int, int, int]:
    """The dictionary-only control of one measure stream: chain counters and keystrokes."""
    pairs, top1, top3 = chain_counters(engine.chain, lines)
    unique_words = sorted({word for line in lines for word in line.split(" ")})
    mdp_lengths = suggest_chain.minimal_distinguishing_prefix_lengths(unique_words)
    baseline, simulated, oracle = suggest_chain.keystroke_costs(
        lines, engine.chain.predict_top3, engine.chain.prefix_top3, mdp_lengths
    )
    return pairs, top1, top3, baseline, simulated, oracle


def empty_store_churn_check(engine: Engine, lines: Sequence[str]) -> tuple[int, int]:
    """With an empty personal state the merged strip and the personalized chain must equal
    the dictionary-only ones everywhere (the zero-churn bar for an empty store). Returns (checks, misses)."""
    model = PersonalModel(lambda word: False, frozenset())
    checks = 0
    mismatches = 0
    for line in lines:
        words = line.split(" ")
        for position, word in enumerate(words):
            for k in range(1, len(word)):
                checks += 1
                if merged_strip(engine, model, word[:k]) != engine.chain.prefix_top3(word[:k]):
                    mismatches += 1
            if position > 0:
                checks += 1
                previous = words[position - 1]
                if personalized_predict(engine, model, previous) != engine.chain.predict_top3(
                    previous
                ):
                    mismatches += 1
    return checks, mismatches


def _pct(part: int, whole: int) -> str:
    return f"{suggest_eval.percent(part, whole):.4f}"


def run(
    words: Sequence[str],
    frequencies: Sequence[int],
    successes_by_head: dict[str, list[str]],
    lines: Sequence[str],
    alphabet: frozenset[str],
    suffix_rules: bool,
) -> list[str]:
    """The full harness: returns the PERS metric lines (name, value pairs flattened)."""
    engine = Engine(words, frequencies, successes_by_head, suffix_rules)
    out: list[tuple[str, str]] = []

    def emit(name: str, value) -> None:
        out.append((name, str(value)))

    # Dictionary-only control over the whole eval set: the pinned chain counters.
    control_pairs, control_top1, control_top3 = chain_counters(engine.chain, lines)
    emit("control.eval_pairs", control_pairs)
    emit("control.chain_top1_hits", control_top1)
    emit("control.chain_top3_hits", control_top3)
    emit("control.chain_top1_pct", _pct(control_top1, control_pairs))
    emit("control.chain_top3_pct", _pct(control_top3, control_pairs))

    for split in SPLITS:
        prime_lines, measure_lines = split_streams(split, lines)
        prime_tokens = [word for line in prime_lines for word in line.split(" ")]
        prime_unique = sorted(set(prime_tokens))
        if suffix_rules:
            unseen = {
                word
                for word in prime_unique
                if suggest_chain.lemma_stratum(word, engine.chain.frequency_of)
                == suggest_chain.STRATUM_UNSEEN_STEM
            }
        else:
            # No suffix table: the unseen-stem stratum degenerates to plain OOV.
            unseen = {word for word in prime_unique if engine.chain.frequency_of(word) == 0}
        oov = {word for word in prime_unique if engine.chain.frequency_of(word) == 0}
        prime_oov_tokens = sum(1 for word in prime_tokens if word in oov)
        prime_unseen_tokens = sum(1 for word in prime_tokens if word in unseen)

        emit(f"{split}.prime_lines", len(prime_lines))
        emit(f"{split}.measure_lines", len(measure_lines))
        emit(f"{split}.prime_tokens", len(prime_tokens))
        emit(f"{split}.measure_tokens", sum(len(line.split(" ")) for line in measure_lines))
        emit(f"{split}.prime_pairs", sum(len(line.split(" ")) - 1 for line in prime_lines))
        emit(f"{split}.measure_pairs", sum(len(line.split(" ")) - 1 for line in measure_lines))
        emit(f"{split}.prime_unique_words", len(prime_unique))
        emit(f"{split}.prime_oov_words", len(oov))
        emit(f"{split}.prime_oov_tokens", prime_oov_tokens)
        emit(f"{split}.prime_unseen_words", len(unseen))
        emit(f"{split}.prime_unseen_tokens", prime_unseen_tokens)

        ctrl_pairs, ctrl_top1, ctrl_top3, ks_base, ks_sim, ks_oracle = split_control(
            engine, measure_lines
        )
        emit(f"{split}.ctrl.nw_pairs", ctrl_pairs)
        emit(f"{split}.ctrl.nw_top1_hits", ctrl_top1)
        emit(f"{split}.ctrl.nw_top3_hits", ctrl_top3)
        emit(f"{split}.ctrl.nw_top1_pct", _pct(ctrl_top1, ctrl_pairs))
        emit(f"{split}.ctrl.nw_top3_pct", _pct(ctrl_top3, ctrl_pairs))
        emit(f"{split}.ctrl.ks_baseline_keys", ks_base)
        emit(f"{split}.ctrl.ks_simulated_keys", ks_sim)
        emit(f"{split}.ctrl.ks_oracle_keys", ks_oracle)
        emit(f"{split}.ctrl.ks_pct", _pct(ks_base - ks_sim, ks_base))
        emit(f"{split}.ctrl.ks_oracle_pct", _pct(ks_base - ks_oracle, ks_base))

        arm_grad_words: dict[str, frozenset[str]] = {}
        for arm in ARMS:
            model, stats, worker = prime_pass(
                engine.chain, prime_lines, arm, alphabet, LOOKUP_LATENCY_MS
            )
            graduated = frozenset(model.words.usage)
            grad_pairs = model.pairs.size()
            arm_grad_words[arm] = graduated
            prefix = f"{split}.{arm}"
            if worker is None:
                issued, observed, dropped, staled = (
                    stats.observed_lookups, stats.observed_lookups, 0, 0,
                )
            else:
                issued = worker.issued_requests
                observed = worker.observed_lookups
                dropped = worker.dropped_lookups
                staled = worker.staled_lookups
            emit(f"{prefix}.issued_requests", issued)
            emit(f"{prefix}.observed_lookups", observed)
            emit(f"{prefix}.dropped_lookups", dropped)
            emit(f"{prefix}.staled_lookups", staled)
            emit(f"{prefix}.empty_observations", stats.empty_observations)
            emit(f"{prefix}.word_completions_reported", model.word_completions_reported)
            emit(f"{prefix}.word_completions_eligible", model.word_completions_eligible)
            emit(f"{prefix}.pair_observations", model.pair_observations)
            emit(f"{prefix}.pairs_dropped_unknown_context", model.pairs_dropped_unknown_context)
            emit(f"{prefix}.word_pending_evictions", model.word_pending_evictions)
            emit(f"{prefix}.pair_pending_evictions", model.pair_pending_evictions)
            emit(f"{prefix}.grad_words", len(graduated))
            emit(f"{prefix}.grad_pairs", grad_pairs)
            grad_oov_tokens = sum(1 for word in prime_tokens if word in oov and word in graduated)
            grad_unseen_tokens = sum(
                1 for word in prime_tokens if word in unseen and word in graduated
            )
            emit(f"{prefix}.grad_oov_tokens", grad_oov_tokens)
            emit(f"{prefix}.grad_oov_coverage_pct", _pct(grad_oov_tokens, prime_oov_tokens))
            emit(f"{prefix}.grad_unseen_tokens", grad_unseen_tokens)
            emit(
                f"{prefix}.grad_unseen_coverage_pct",
                _pct(grad_unseen_tokens, prime_unseen_tokens),
            )
            if arm == "fixed":
                emit(f"{prefix}.fix_probes", stats.fix_probes)
                emit(f"{prefix}.fix_recovered_words", stats.fix_recovered_words)

            walk = measure_walk(engine, model, measure_lines, graduated)
            emit(f"{prefix}.gwt_tokens", walk.gwt_tokens)
            emit(f"{prefix}.gwt_typed", walk.gwt_typed)
            emit(f"{prefix}.gwt_nw_tapped", walk.gwt_nw_tapped)
            for k in COMPLETION_PREFIX_LENGTHS:
                emit(f"{prefix}.comp_cp{k}_typed", walk.comp_typed[k])
                emit(f"{prefix}.comp_cp{k}_top1", walk.comp_top1[k])
                emit(f"{prefix}.comp_cp{k}_top3", walk.comp_top3[k])
                emit(f"{prefix}.comp_cp{k}_top1_pct", _pct(walk.comp_top1[k], walk.comp_typed[k]))
                emit(f"{prefix}.comp_cp{k}_top3_pct", _pct(walk.comp_top3[k], walk.comp_typed[k]))
            emit(f"{prefix}.comp_ctrl_top1_hits", walk.comp_ctrl_top1_hits)
            emit(f"{prefix}.comp_ctrl_top3_hits", walk.comp_ctrl_top3_hits)
            emit(f"{prefix}.comp_accept", walk.comp_accept)
            emit(f"{prefix}.comp_accept_pct", _pct(walk.comp_accept, walk.gwt_typed))
            emit(
                f"{prefix}.comp_accept_top1_pct", _pct(walk.comp_accept_top1, walk.comp_accept)
            )
            emit(f"{prefix}.comp_never", walk.comp_never)
            emit(f"{prefix}.nw_pairs", walk.nw_pairs)
            emit(f"{prefix}.nw_pers_top1", walk.nw_pers_top1)
            emit(f"{prefix}.nw_pers_top3", walk.nw_pers_top3)
            emit(f"{prefix}.nw_pers_top1_pct", _pct(walk.nw_pers_top1, walk.nw_pairs))
            emit(f"{prefix}.nw_pers_top3_pct", _pct(walk.nw_pers_top3, walk.nw_pairs))
            emit(f"{prefix}.nw_disp_top1", walk.nw_disp_top1)
            emit(f"{prefix}.nw_disp_top3", walk.nw_disp_top3)
            emit(f"{prefix}.nw_disp_top3_pct", _pct(walk.nw_disp_top3, walk.nw_pairs))
            emit(f"{prefix}.nw_learned_head_pairs", walk.nw_learned_head_pairs)
            emit(f"{prefix}.nw_true_learned", walk.nw_true_learned)
            emit(f"{prefix}.nw_true_learned_shown", walk.nw_true_learned_shown)
            emit(f"{prefix}.nw_true_learned_suppressed", walk.nw_true_learned_suppressed)
            emit(f"{prefix}.nw_suppressed_full_strip", walk.nw_suppressed_full_strip)
            emit(f"{prefix}.nw_disp_gained", walk.nw_disp_gained)
            emit(f"{prefix}.ks_pers_keys", walk.keystrokes)
            emit(f"{prefix}.ks_pers_pct", _pct(ks_base - walk.keystrokes, ks_base))
            pers_pct = suggest_eval.percent(ks_base - walk.keystrokes, ks_base)
            ctrl_pct = suggest_eval.percent(ks_base - ks_sim, ks_base)
            emit(f"{prefix}.ks_delta_pp", f"{pers_pct - ctrl_pct:.4f}")
            emit(f"{prefix}.reord_changes_pers", walk.reord_changes_pers)
            emit(f"{prefix}.reord_changes_ctrl", walk.reord_changes_ctrl)
            emit(f"{prefix}.reord_max_changes", walk.reord_max_changes)
            emit(f"{prefix}.reord_promo_0", walk.reord_promo_buckets[0])
            emit(f"{prefix}.reord_promo_1", walk.reord_promo_buckets[1])
            emit(f"{prefix}.reord_promo_2plus", walk.reord_promo_buckets[2])
            emit(f"{prefix}.reord_max_promo", walk.reord_max_promo)

        # The graduation-coverage gate on unseen-stem tokens: F >= R + 0.9 * (O - R).
        coverage_by_arm = {
            arm: sum(
                1
                for word in prime_tokens
                if word in unseen and word in arm_grad_words[arm]
            )
            for arm in ARMS
        }
        oracle_cov = coverage_by_arm["oracle"]
        realistic_cov = coverage_by_arm["realistic"]
        fixed_cov = coverage_by_arm["fixed"]
        rhs_x1000 = realistic_cov * 1000 + 900 * (oracle_cov - realistic_cov)
        emit(f"{split}.a7.oracle_unseen_tokens", oracle_cov)
        emit(f"{split}.a7.realistic_unseen_tokens", realistic_cov)
        emit(f"{split}.a7.fixed_unseen_tokens", fixed_cov)
        emit(f"{split}.a7.rhs_x1000", rhs_x1000)
        emit(f"{split}.a7.gate_pass", 1 if fixed_cov * 1000 >= rhs_x1000 else 0)

        if split == "hash":
            checks, mismatches = empty_store_churn_check(engine, measure_lines)
            emit(f"{split}.empty_store.strip_checks", checks)
            emit(f"{split}.empty_store.strip_mismatches", mismatches)
        if split in ("hash", "rep"):
            # The latency sweep of the realistic arm. The rep half is where graduations
            # exist, so only it shows the graduation-coverage loss; the hash half shows the
            # raw coalescing counters on the primary split.
            for latency in LOOKUP_LATENCY_SWEEP_MS:
                sweep_model, _, sweep_worker = prime_pass(
                    engine.chain, prime_lines, "realistic", alphabet, latency
                )
                sweep_graduated = frozenset(sweep_model.words.usage)
                cell = f"{split}.sweep_l{latency}"
                emit(f"{cell}.grad_words", len(sweep_graduated))
                emit(
                    f"{cell}.grad_unseen_tokens",
                    sum(
                        1
                        for word in prime_tokens
                        if word in unseen and word in sweep_graduated
                    ),
                )
                emit(
                    f"{cell}.grad_oov_tokens",
                    sum(1 for word in prime_tokens if word in oov and word in sweep_graduated),
                )
                emit(f"{cell}.observed_lookups", sweep_worker.observed_lookups)
                emit(f"{cell}.dropped_lookups", sweep_worker.dropped_lookups)
                emit(f"{cell}.staled_lookups", sweep_worker.staled_lookups)
                if split == "rep":
                    # The fixed arm at the same latency: the deferred probe
                    # recovers the coalescing loss; the gate is F >= R + 0.9 * (O - R).
                    fixed_model, _, _ = prime_pass(
                        engine.chain, prime_lines, "fixed", alphabet, latency
                    )
                    fixed_graduated = frozenset(fixed_model.words.usage)
                    realistic_cov = sum(
                        1
                        for word in prime_tokens
                        if word in unseen and word in sweep_graduated
                    )
                    fixed_cov = sum(
                        1
                        for word in prime_tokens
                        if word in unseen and word in fixed_graduated
                    )
                    rhs_x1000 = realistic_cov * 1000 + 900 * (oracle_cov - realistic_cov)
                    emit(f"{cell}.fixed_grad_words", len(fixed_graduated))
                    emit(f"{cell}.fixed_grad_unseen_tokens", fixed_cov)
                    emit(f"{cell}.a7_rhs_x1000", rhs_x1000)
                    emit(f"{cell}.a7_pass", 1 if fixed_cov * 1000 >= rhs_x1000 else 0)

    return [f"PERS|{name}|{value}" for name, value in out]


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--language", choices=("tat", "rus"), default="tat",
        help="eval language: switches the default eval set and assets (default: %(default)s)",
    )
    parser.add_argument("--eval", dest="eval_path", type=Path, default=None)
    parser.add_argument("--dict-asset", type=Path, default=None)
    parser.add_argument("--bigram-asset", type=Path, default=None)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    russian = args.language == "rus"
    eval_path = args.eval_path or (suggest_eval.RU_EVAL if russian else suggest_eval.DEFAULT_EVAL)
    dict_asset = args.dict_asset or (
        suggest_eval.RU_DICT_ASSET if russian else suggest_eval.DEFAULT_DICT_ASSET
    )
    bigram_asset = args.bigram_asset or (
        suggest_eval.RU_BIGRAM_ASSET if russian else suggest_eval.DEFAULT_BIGRAM_ASSET
    )
    alphabet = coverage.RUSSIAN.alphabet if russian else coverage.TATAR.alphabet
    try:
        lines = suggest_eval.load_eval_lines(eval_path, alphabet)
        dictionary_words, dictionary_frequencies, dictionary_raw = suggest_eval.load_dictionary_words(
            dict_asset
        )
        successes_by_head = suggest_eval.load_bigram_successes(
            bigram_asset, dictionary_words, dictionary_raw
        )
    except (
        suggest_eval.SuggestEvalError,
        suggest_eval.dictionary_pack.DictionaryPackError,
        suggest_eval.bigram_asset_pack.BigramFormatError,
        suggest_eval.bigram_asset_pack.BigramBudgetError,
        OSError,
    ) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    for line in run(
        dictionary_words,
        dictionary_frequencies,
        successes_by_head,
        lines,
        alphabet,
        suffix_rules=not russian,
    ):
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
