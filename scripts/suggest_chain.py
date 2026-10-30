#!/usr/bin/env python3
"""Python mirror of the production Tatar suggestion chain, for the eval harness.

Mirrors, over the committed assets:

* the NEXT_WORD chain of ``CompositePrefixComputer.predict`` (bigram successors, then the
  top-frequency fallback; learned word pairs stay empty in the harness), with the strip's cells;
* the completion path of ``TdictPrefixIndex.lookup`` with typo recovery off (the eval
  configuration): exact candidates ranked frequency descending, code point ascending, with
  the same-stem boost when the prefix is itself a complete word of at least four code points;
* ``TatarSuffixRules``: the runtime suffix table used by the same-stem boost (ported verbatim,
  NOT the heuristic ``INFLECTIONAL_SUFFIXES`` list of ``suggest_eval.py``);
* the deterministic SplitMix64 resample stream and nearest-rank CI of the bootstrap lines.

``ChainMirror(suffix_rules=False)`` mirrors the Russian engine instead: no suffix rules means no
same-stem boost. NEXT_WORD is bigram successors plus the fallback pool in both languages.

Everything here is BMP-only Tatar, so Python string length is the code-point count and string order
is code point (= UTF-8 byte) order, matching the Kotlin ``Char``/``compareWords`` semantics.
"""
from __future__ import annotations

import bisect
import math
import sys
from pathlib import Path
from typing import Callable, Iterator, Mapping, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

from typo_pack import splitmix64  # noqa: E402

# Production bounds (CompositePrefixComputer / TatBigrPrefixIndex /
# GlobalTopFrequencyFallbackFactory / TdictPrefixIndex).
CELL_COUNT = 4
BIGRAM_MAX_RESULTS = 4
TOP_WORD_POOL = 8
MIN_SAME_STEM_BOOST_PREFIX_CODE_POINTS = 4

# Fixed bootstrap knobs; changing either changes the pinned CI strings in both harnesses.
BOOTSTRAP_SEED = 20261001
BOOTSTRAP_ROUNDS = 2000

# The runtime suffix table of TatarSuffixRules.kt, ported verbatim (review order kept).
RUNTIME_SUFFIXES = frozenset(
    [
        # plural -LAr
        "лар", "ләр", "нар", "нәр",
        # genitive -нIң
        "ның", "нең",
        # dative -GA
        "га", "гә", "ка", "кә",
        # accusative -нI
        "ны", "не",
        # locative -DA
        "да", "дә", "та", "тә",
        # ablative -TAн
        "дан", "дән", "тан", "тән", "нан", "нән",
        # possessives 1sg/2sg/3sg/1pl/2pl/3pl
        "ым", "ем", "м",
        "ың", "ең", "ң",
        "ы", "е", "сы", "се",
        "ыбыз", "ебез", "быз", "без",
        "ыгыз", "егез", "гыз", "гез",
        "лары", "ләре", "нары", "нәре",
        # case forms after the 3sg possessive
        "н",
        "на", "нә",
        "нда", "ндә",
        "ннан", "ннән",
        # present 3sg -A and the person endings riding the present base (bare -сың/-сең
        # also follow an и-final stem directly)
        "а", "ә",
        "ам", "әм", "асың", "әсең", "асыз", "әсез", "алар", "әләр", "сың", "сең",
        # negative present and its persons
        "мый", "ми", "мыйм", "мим", "мыйсың", "мисең", "мыйсыз", "мисез", "мыйлар", "миләр",
        # past -DI and persons
        "ды", "де", "ты", "те",
        "дым", "дем", "тым", "тем", "дың", "дең", "тың", "тең",
        "дык", "дек", "тык", "тек",
        "дыгыз", "дегез", "тыгыз", "тегез", "дылар", "деләр", "тылар", "теләр",
        # negative past
        "мады", "мәде", "мадым", "мәдем", "мадың", "мәдең",
        "мадыгыз", "мәдегез", "мадылар", "мәделәр",
        # -GAn participle, negated included
        "ган", "гән", "кан", "кән", "маган", "мәгән",
        # simple future and negative future
        "ар", "әр", "ыр", "ер", "р", "яр",
        "мас", "мәс",
        # definite future -(A)чAк
        "ачак", "әчәк", "ячак", "ячәк",
        # conditional, negated included
        "са", "сә", "маса", "мәсә",
        # gerunds
        "ып", "еп", "п",
        "гач", "гәч", "кач", "кәч",
        "ганчы", "гәнче", "канчы", "кәнче",
        "мыйча", "мичә",
        # participles, masdar, intention
        "учы", "үче",
        "асы", "әсе",
        "у", "ү", "ю",
        "макчы", "мәкче",
        # derivational suffixes
        "ча", "чә",
        "лык", "лек",
        "лы", "ле",
        "сыз", "сез",
        "чы", "че",
        "даш", "дәш", "таш", "тәш",
        "рак", "рәк",
    ]
)


def is_inflected_continuation(remainder: str) -> bool:
    """Mirror of ``TatarSuffixRules.isInflectedContinuation``: table membership."""
    return bool(remainder) and remainder in RUNTIME_SUFFIXES











def longest_stem_with_suffix_remainder(
    word: str, frequency_of: Callable[[str], int]
) -> str | None:
    """The longest proper split into a dictionary stem plus a runtime-table suffix, or None."""
    for cut in range(len(word) - 1, 0, -1):
        if is_inflected_continuation(word[cut:]) and frequency_of(word[:cut]) > 0:
            return word[:cut]
    return None


STRATUM_SEEN_FORM = "seen_form"
STRATUM_NEW_FORM = "new_form_of_seen_stem"
STRATUM_UNSEEN_STEM = "unseen_stem"
STRATA = (STRATUM_SEEN_FORM, STRATUM_NEW_FORM, STRATUM_UNSEEN_STEM)


def lemma_stratum(word: str, frequency_of: Callable[[str], int]) -> str:
    """The lemma stratum of one eval word: seen form, new form of a seen stem, unseen stem."""
    if frequency_of(word) > 0:
        return STRATUM_SEEN_FORM
    if longest_stem_with_suffix_remainder(word, frequency_of) is not None:
        return STRATUM_NEW_FORM
    return STRATUM_UNSEEN_STEM


class ChainMirror:
    """The production NEXT_WORD chain and completion path over the decoded assets.

    ``words`` is the dictionary in code-point order with parallel ``frequencies``;
    ``successes_by_head`` is the schema-3 bigram table in packing order. With
    ``suffix_rules=False`` the mirror matches an engine without suffix rules (the Russian
    engine): no same-stem boost in the prefix pass.
    """

    def __init__(
        self,
        words: Sequence[str],
        frequencies: Sequence[int],
        successes_by_head: Mapping[str, Sequence[str]],
        suffix_rules: bool = True,
    ) -> None:
        self._suffix_rules = suffix_rules
        self._words = list(words)
        self._frequencies = list(frequencies)
        self._successes = successes_by_head
        self._frequency_by_word = dict(zip(words, frequencies))
        # The fallback pool of GlobalTopFrequencyFallbackFactory: the dictionary's top words,
        # frequency descending then code point ascending.
        self._fallback_pool = [
            word
            for word, _ in sorted(
                zip(words, frequencies), key=lambda pair: (-pair[1], pair[0])
            )[:TOP_WORD_POOL]
        ]
        self._prefix_cache: dict[str, list[str]] = {}
        self._prefix_context_cache: dict[tuple[str, tuple[str, ...]], list[str]] = {}
        self._predict_cache: dict[str, list[str]] = {}

    def frequency_of(self, word: str) -> int:
        return self._frequency_by_word.get(word, 0)

    def packed_successors(self, head: str) -> tuple[str, ...]:
        """The packed bigram successors of [head]: the raw table answer, at most
        BIGRAM_MAX_RESULTS, in packing order. This is the context the prefix boost reads."""
        return tuple(self._successes.get(head, ()))[:BIGRAM_MAX_RESULTS]

    def predict_top3(self, head: str) -> list[str]:
        """Mirror of the NEXT_WORD chain for a committed word: bigrams, then fallback."""
        cached = self._predict_cache.get(head)
        if cached is not None:
            return cached
        shown = list(self._successes.get(head, ()))[:BIGRAM_MAX_RESULTS]
        if len(shown) < CELL_COUNT:
            for word in self._fallback_pool:
                if len(shown) >= CELL_COUNT:
                    break
                if word == head or word in shown:
                    continue
                shown.append(word)
        self._predict_cache[head] = shown
        return shown

    def prefix_top3(self, prefix: str, context: Sequence[str] = ()) -> list[str]:
        """Mirror of the exact pass of ``TdictPrefixIndex.lookup``, typo recovery off.

        Candidates are the dictionary words continuing ``prefix`` (the word equal to the
        prefix is excluded), ranked frequency descending then code point ascending, at most
        four. With a complete-word prefix of at least four code points the same-stem boost
        applies: candidates whose remainder is a runtime suffix form rank first, frequency
        order kept within each track.

        With a non-empty [context] — the previous committed word's packed bigram successors,
        see [packed_successors] — the context boost applies (the runtime's context-armed
        lookup): candidates among the successors rank first in one context track, frequency
        order kept, then the unchanged same-stem and other tracks over the rest. An empty
        context is the pre-boost path, byte-identical.
        """
        if not prefix:
            return []
        if not context:
            cached = self._prefix_cache.get(prefix)
            if cached is not None:
                return cached
            result = self._exact_pass(prefix, ())
            self._prefix_cache[prefix] = result
            return result
        key = (prefix, tuple(context))
        cached = self._prefix_context_cache.get(key)
        if cached is not None:
            return cached
        result = self._exact_pass(prefix, tuple(context))
        self._prefix_context_cache[key] = result
        return result

    def _exact_pass(self, prefix: str, context: tuple[str, ...]) -> list[str]:
        """The track composition of [prefix_top3]: context track first, then same-stem, then
        the rest; every track keeps (frequency descending, code point ascending) order."""
        words = self._words
        low = bisect.bisect_left(words, prefix)
        high = bisect.bisect_left(words, prefix + "\U0010ffff", low)
        boosted = (
            self._suffix_rules
            and len(prefix) >= MIN_SAME_STEM_BOOST_PREFIX_CODE_POINTS
            and self.frequency_of(prefix) > 0
        )
        context_set = set(context)
        context_track: list[tuple[int, str]] = []
        stem_track: list[tuple[int, str]] = []
        other_track: list[tuple[int, str]] = []
        for index in range(low, high):
            word = words[index]
            if word == prefix:
                continue
            entry = (-self._frequencies[index], word)
            if word in context_set:
                target = context_track
            elif boosted and is_inflected_continuation(word[len(prefix):]):
                target = stem_track
            else:
                target = other_track
            bisect.insort(target, entry)
            del target[CELL_COUNT:]
        tracks = context_track + stem_track + other_track
        return [word for _, word in tracks[:CELL_COUNT]]


def splitmix64_index_stream(count: int, seed: int = BOOTSTRAP_SEED) -> Iterator[int]:
    """Resample indices in ``[0, count)``: splitmix64(seed), then splitmix64 of each output.

    One endless stream; a future paired (two-arm) comparison must draw both arms from the
    same stream so the resamples stay paired index-by-index.
    """
    state = splitmix64(seed)
    while True:
        yield state % count
        state = splitmix64(state)


def nearest_rank_index(per_mille: int, size: int) -> int:
    """Zero-based index of the ``per_mille``/1000 percentile, nearest-rank: ceil(p*N/1000)-1."""
    return (per_mille * size + 999) // 1000 - 1


def bootstrap_rate_ci95(
    hits_per_unit: Sequence[int],
    weights_per_unit: Sequence[int],
    rounds: int = BOOTSTRAP_ROUNDS,
    seed: int = BOOTSTRAP_SEED,
) -> tuple[float, float]:
    """CI95 of the rate sum(hits)/sum(weights) over unit resamples with replacement.

    Each round draws ``len(hits_per_unit)`` unit indices from the SplitMix64 stream and
    scores 100*hits/weights; the interval is the 25/975 per-mille nearest ranks of the
    sorted per-round rates.
    """
    size = len(hits_per_unit)
    stream = splitmix64_index_stream(size, seed)
    rates: list[float] = []
    for _ in range(rounds):
        hits = 0
        weights = 0
        for _ in range(size):
            drawn = next(stream)
            hits += hits_per_unit[drawn]
            weights += weights_per_unit[drawn]
        rates.append(hits * 100.0 / weights)
    rates.sort()
    return rates[nearest_rank_index(25, rounds)], rates[nearest_rank_index(975, rounds)]


def paired_delta_ci95(
    hits_base: Sequence[int],
    hits_boost: Sequence[int],
    weights: Sequence[int],
    rounds: int = BOOTSTRAP_ROUNDS,
    seed: int = BOOTSTRAP_SEED,
) -> tuple[float, float]:
    """CI95 of the paired two-arm delta sum(boost-hits)/sum(weights) - sum(base-hits)/sum(weights),
    in pp: one SplitMix64 stream, both arms scored on the same drawn unit indices, nearest-rank
    25/975 per-mille of the per-round deltas."""
    size = len(weights)
    stream = splitmix64_index_stream(size, seed)
    deltas: list[float] = []
    for _ in range(rounds):
        base = 0
        boost = 0
        weight = 0
        for _ in range(size):
            drawn = next(stream)
            base += hits_base[drawn]
            boost += hits_boost[drawn]
            weight += weights[drawn]
        deltas.append((boost - base) * 100.0 / weight)
    deltas.sort()
    return (deltas[nearest_rank_index(25, rounds)],
            deltas[nearest_rank_index(975, rounds)])


def minimum_detectable_effect_pp(p: float, n: int) -> float:
    """MDE in percentage points, two-sided alpha 0.05, power 0.8, normal approximation.

    This is the unpaired conservative bound; the paired bootstrap is tighter.
    """
    z_alpha = 1.959963984540054  # 0.975 quantile of the standard normal
    z_beta = 0.8416212335729143  # 0.8 quantile of the standard normal
    return (z_alpha + z_beta) * math.sqrt(2 * p * (1 - p) / n) * 100.0


def minimal_distinguishing_prefix_lengths(words: Sequence[str]) -> dict[str, int]:
    """For each word, the length of the shortest prefix no other word shares, or absent.

    The distinguishing prefix is computed over the given set (the eval vocabulary), in code
    points; a word that is a proper prefix of another word has none and is left out. In a
    sorted set only the immediate neighbors can share the longest prefix.
    """
    ordered = sorted(words)
    lengths: dict[str, int] = {}
    for index, word in enumerate(ordered):
        need = 1
        if index > 0:
            need = max(need, _common_prefix_length(ordered[index - 1], word) + 1)
        if index + 1 < len(ordered):
            need = max(need, _common_prefix_length(word, ordered[index + 1]) + 1)
        if need <= len(word):
            lengths[word] = need
    return lengths


def _common_prefix_length(first: str, second: str) -> int:
    limit = min(len(first), len(second))
    at = 0
    while at < limit and first[at] == second[at]:
        at += 1
    return at


def keystroke_costs(
    lines: Sequence[str],
    next_word_top3: Callable[[str], Sequence[str]],
    completion_top3: Callable[[str, str | None], Sequence[str]],
    mdp_lengths: Mapping[str, int],
) -> tuple[int, int, int]:
    """Baseline, simulated and oracle keystroke counts over the eval lines.

    Baseline: every code point typed, plus one space between words. Simulated: the first word
    uses completion assist; a later word costs 1 (a strip tap) when the chain shows it for
    the previous word, else completion assist with that previous word as context. Completion
    assist types code points until the word enters the shown cells (cost k+1 with the tap)
    or, when it never does, the full length. Oracle: the first word costs its minimal
    distinguishing prefix plus the tap (never more than its full length; a word without one
    costs the full length), and every later word is a tap.
    """
    completion_costs: dict[tuple[str, str | None], int] = {}

    def completion_cost(word: str, context: str | None) -> int:
        key = (word, context)
        cost = completion_costs.get(key)
        if cost is None:
            cost = len(word)
            for k in range(1, len(word)):
                if word in completion_top3(word[:k], context):
                    cost = k + 1
                    break
            completion_costs[key] = cost
        return cost

    baseline = 0
    simulated = 0
    oracle = 0
    for line in lines:
        words = line.split(" ")
        baseline += sum(len(word) for word in words) + len(words) - 1
        simulated += completion_cost(words[0], None)
        for previous, word in zip(words, words[1:]):
            simulated += (
                1 if word in next_word_top3(previous) else completion_cost(word, previous)
            )
        mdp = mdp_lengths.get(words[0])
        oracle += min(len(words[0]), mdp + 1) if mdp is not None else len(words[0])
        oracle += len(words) - 1
    return baseline, simulated, oracle
