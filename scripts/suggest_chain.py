#!/usr/bin/env python3
"""Python mirror of the production Tatar suggestion chain, for the eval harness.

Mirrors, over the committed assets:

* the NEXT_WORD chain of ``CompositePrefixComputer.predict`` (bigram successors, then
  after-word forms, then the top-frequency fallback; learned word pairs stay empty in the
  harness), with the strip's three cells;
* the completion path of ``TdictPrefixIndex.lookup`` with typo recovery off (the eval
  configuration): exact candidates ranked frequency descending, code point ascending, with
  the same-stem boost when the prefix is itself a complete word of at least four code points;
* ``TatarSuffixRules``: the runtime suffix table (ported verbatim, NOT the heuristic
  ``INFLECTIONAL_SUFFIXES`` list of ``suggest_eval.py``) and the bounded harmony-aware form
  generator, including its documented simplifications versus the build-time generator;
* the deterministic SplitMix64 resample stream and nearest-rank CI of the bootstrap lines.

Letter classes and the harmony rules are imported from ``wordform_gen.py``: the runtime
documents them as an exact mirror of it. Everything here is BMP-only Tatar, so Python string
length is the code-point count and string order is code point (= UTF-8 byte) order, matching
the Kotlin ``Char``/``compareWords`` semantics.
"""
from __future__ import annotations

import bisect
import math
import sys
from pathlib import Path
from typing import Callable, Iterator, Mapping, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

from typo_pack import splitmix64  # noqa: E402
from wordform_gen import (  # noqa: E402
    BACK,
    FRONT,
    NASALS,
    VOICELESS,
    VOWELS,
    harmony_of,
    harmony_variants,
    syllable_count,
)

# Production bounds (CompositePrefixComputer / TatBigrPrefixIndex / TatarAfterWordForms /
# GlobalTopFrequencyFallbackFactory / TdictPrefixIndex).
CELL_COUNT = 3
BIGRAM_MAX_RESULTS = 3
GENERATION_CAP = 40
MAX_CONTEXT_BYTES = 128
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


def generate_forms(stem: str, max_out: int = GENERATION_CAP) -> list[str]:
    """Mirror of ``TatarSuffixRules.generateForms``: up to 18 forms per harmony variant.

    Same simplifications as the runtime (no voicing exceptions, no suppletive pronouns, no
    per-stem overrides): the caller's dictionary filter absorbs the overgeneration.
    """
    out: list[str] = []

    def emit(form: str) -> None:
        if len(out) < max_out and form != stem and form not in out:
            out.append(form)

    for harmony in harmony_variants(stem):
        if len(out) >= max_out:
            break
        last = stem[-1]
        vowel_final = last in VOWELS
        a = "а" if harmony == BACK else "ә"
        i = "ы" if harmony == BACK else "е"
        u = "у" if harmony == BACK else "ү"
        g = "к" if last in VOICELESS else "г"
        d = "т" if last in VOICELESS else "д"
        liquid = "н" if last in NASALS else "л"
        abl = "т" if last in VOICELESS else ("н" if last in NASALS else "д")

        emit(stem + liquid + a + "р")  # plural
        emit(stem + "н" + i + "ң")  # genitive
        emit(stem + g + a)  # dative
        emit(stem + "н" + i)  # accusative
        emit(stem + d + a)  # locative
        emit(stem + abl + a + "н")  # ablative

        # 3sg possessive: bare -ы/-е after consonants and у/ү, -сы/-се after other vowels.
        possessive3 = stem + i if (not vowel_final or last in "уү") else stem + "с" + i
        emit(possessive3)
        # The post-3sg cases ride the possessive's own harmony.
        emit(possessive3 + "н")
        emit(possessive3 + "н" + a)
        emit(possessive3 + "нд" + a)
        emit(possessive3 + "нн" + a + "н")

        # Present 3sg: -а/-ә after consonants; vowel-final stems contract to ый/и.
        emit(stem[:-1] + ("ый" if harmony == BACK else "и") if vowel_final else stem + a)
        emit(stem + d + i)  # past 3sg -DI
        emit(_future_form(stem, harmony, vowel_final))
        emit(stem + "п" if vowel_final else stem + i + "п")  # gerund -(I)п
        emit(stem + g + a + "н")  # participle -GAn
        # Masdar: -у/-ү, with the й-glide spelling -ю after ы/и/у/ү-final stems.
        emit(stem + "ю" if (vowel_final and last not in "аәоөэ") else stem + u)
        emit(stem + "ч" + a)  # derivational -чA
    return out


def _future_form(stem: str, harmony: str, vowel_final: bool) -> str:
    """Mirror of the runtime's lexically split simple future."""
    if vowel_final:
        return stem + "яр" if syllable_count(stem) == 1 else stem + "р"
    if syllable_count(stem) == 1:
        vowel = "а" if harmony == BACK else "ә"
    else:
        vowel = "ы" if harmony == BACK else "е"
    return stem + vowel + "р"


def forms_of(
    stem: str,
    already_shown: Sequence[str],
    max_out: int,
    frequency_of: Callable[[str], int],
) -> list[str]:
    """Mirror of ``TatarAfterWordForms.formsOf``: attested forms, frequency-ranked.

    Keeps generated forms that are dictionary words (frequency > 0), drops the stem, the
    already-shown words and duplicates, ranks frequency descending then code point ascending,
    truncated to ``max_out``.
    """
    if max_out <= 0:
        return []
    encoded = stem.encode("utf-8")
    if not encoded or len(encoded) > MAX_CONTEXT_BYTES:
        return []
    generated = generate_forms(stem, GENERATION_CAP)
    if not generated:
        return []
    shown = set(already_shown)
    ranked: list[tuple[int, str]] = []
    for form in generated:
        if form == stem or form in shown:
            continue
        frequency = frequency_of(form)
        if frequency <= 0:
            continue
        bisect.insort(ranked, (-frequency, form))
    return [form for _, form in ranked[:max_out]]


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
    ``successes_by_head`` is the schema-3 bigram table in packing order.
    """

    def __init__(
        self,
        words: Sequence[str],
        frequencies: Sequence[int],
        successes_by_head: Mapping[str, Sequence[str]],
    ) -> None:
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
        self._predict_cache: dict[str, list[str]] = {}

    def frequency_of(self, word: str) -> int:
        return self._frequency_by_word.get(word, 0)

    def predict_top3(self, head: str) -> list[str]:
        """Mirror of the NEXT_WORD chain for a committed word: bigrams, forms, fallback."""
        cached = self._predict_cache.get(head)
        if cached is not None:
            return cached
        shown = list(self._successes.get(head, ()))[:BIGRAM_MAX_RESULTS]
        if len(shown) < CELL_COUNT:
            shown += forms_of(head, shown, CELL_COUNT - len(shown), self.frequency_of)
        if len(shown) < CELL_COUNT:
            for word in self._fallback_pool:
                if len(shown) >= CELL_COUNT:
                    break
                if word == head or word in shown:
                    continue
                shown.append(word)
        self._predict_cache[head] = shown
        return shown

    def prefix_top3(self, prefix: str) -> list[str]:
        """Mirror of the exact pass of ``TdictPrefixIndex.lookup``, typo recovery off.

        Candidates are the dictionary words continuing ``prefix`` (the word equal to the
        prefix is excluded), ranked frequency descending then code point ascending, at most
        three. With a complete-word prefix of at least four code points the same-stem boost
        applies: candidates whose remainder is a runtime suffix form rank first, frequency
        order kept within each track.
        """
        if not prefix:
            return []
        cached = self._prefix_cache.get(prefix)
        if cached is not None:
            return cached
        words = self._words
        low = bisect.bisect_left(words, prefix)
        high = bisect.bisect_left(words, prefix + "\U0010ffff", low)
        boosted = (
            len(prefix) >= MIN_SAME_STEM_BOOST_PREFIX_CODE_POINTS
            and self.frequency_of(prefix) > 0
        )
        stem_track: list[tuple[int, str]] = []
        other_track: list[tuple[int, str]] = []
        for index in range(low, high):
            word = words[index]
            if word == prefix:
                continue
            entry = (-self._frequencies[index], word)
            target = (
                stem_track
                if boosted and is_inflected_continuation(word[len(prefix):])
                else other_track
            )
            bisect.insort(target, entry)
            del target[CELL_COUNT:]
        result = [word for _, word in (stem_track + other_track)[:CELL_COUNT]]
        self._prefix_cache[prefix] = result
        return result


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
    completion_top3: Callable[[str], Sequence[str]],
    mdp_lengths: Mapping[str, int],
) -> tuple[int, int, int]:
    """Baseline, simulated and oracle keystroke counts over the eval lines.

    Baseline: every code point typed, plus one space between words. Simulated: the first word
    uses completion assist; a later word costs 1 (a strip tap) when the chain shows it for
    the previous word, else completion assist. Completion assist types code points until the
    word enters the prefix top-3 (cost k+1 with the tap) or, when it never does, the full
    length. Oracle: the first word costs its minimal distinguishing prefix plus the tap
    (never more than its full length; a word without one costs the full length), and every
    later word is a tap.
    """
    completion_costs: dict[str, int] = {}

    def completion_cost(word: str) -> int:
        cost = completion_costs.get(word)
        if cost is None:
            cost = len(word)
            for k in range(1, len(word)):
                if word in completion_top3(word[:k]):
                    cost = k + 1
                    break
            completion_costs[word] = cost
        return cost

    baseline = 0
    simulated = 0
    oracle = 0
    for line in lines:
        words = line.split(" ")
        baseline += sum(len(word) for word in words) + len(words) - 1
        simulated += completion_cost(words[0])
        for previous, word in zip(words, words[1:]):
            simulated += 1 if word in next_word_top3(previous) else completion_cost(word)
        mdp = mdp_lengths.get(words[0])
        oracle += min(len(words[0]), mdp + 1) if mdp is not None else len(words[0])
        oracle += len(words) - 1
    return baseline, simulated, oracle
