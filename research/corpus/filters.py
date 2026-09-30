"""Filtering conversational corpora before their words are proposed for a keyboard dictionary.

Garbage in the data costs more than missing data. Every filter here must be able to
report what it removed, and the report must include examples that PASSED and should not have.

Filters, in the order applied:

1. LINE DEDUP -- subtitle collections repeat the same line across alternative uploads.

2. PROPER-NOUN BY CASE EVIDENCE -- the dominant garbage in subtitles is character names
   (Локк, Десмонд, Сойер, Макфлай, Танос). They are indistinguishable from ordinary words
   once lowercased, so the evidence has to be collected BEFORE lowercasing:
   for each surface token we record whether it was capitalized and whether it stood first in
   its line. A form is judged a proper noun when it has at least MIN_CASE_EVIDENCE
   non-line-initial occurrences and at least PROPER_RATIO of them are capitalized. Line-initial
   occurrences are excluded from the ratio because every sentence capitalizes its first word,
   which would convict every ordinary word that happens to start sentences.

3. CORROBORATION -- a form must reach MIN_FREQ occurrences overall. Single sightings in a
   subtitle corpus are overwhelmingly typos, transliteration and OCR noise.

4. PROFANITY -- an explicit stop list, applied last so its effect is reportable separately.
   The list is deliberately short and covers unambiguous obscene roots; it is not a
   morality filter on ordinary rude words, it exists so the keyboard never SUGGESTS an
   obscenity the user did not already type.
"""
from __future__ import annotations
import re
from collections import Counter, defaultdict

PROPER_RATIO = 0.80
MIN_CASE_EVIDENCE = 3
MIN_FREQ = 3

# Unambiguous obscene roots. Matched as a prefix of the normalized form.
PROFANITY_ROOTS = {
    "rus": ("хуй", "хуе", "хуё", "хуя", "пизд", "еба", "ебу", "ебё", "ебе", "ёб",
            "еби", "ебл", "ебн", "ебт", "заеб", "уеб", "разъеб", "разьеб", "въеб", "выеб",
            "поеб", "перееб", "бляд", "мудак", "пидор", "пидар", "гондон",
            "сука", "суки", "сукин", "херн", "залуп", "дроч", "пиздец"),
    # NOTE: roots "манда" and "муда" are deliberately ABSENT. Measured false positives:
    # "манда" convicts мандарин/мандарины (and would convict мандат), "муда" convicts
    # мудрость-adjacent forms. A root that costs ordinary words is not worth the obscenity
    # it catches, because the obscenity is also caught by the fuller roots kept above.
    # NOTE: "сука" is deliberately ABSENT from the Tatar list. Tatar "сука" is a plough and
    # "сукалар" is an ordinary verb form; the Russian root convicts them wrongly. In the Tatar
    # corpora the root caught only such false positives.
    "tat": ("хуй", "пизд", "еба", "ёб", "бляд", "мудак", "пидор"),
}

class CaseEvidence:
    """Collects capitalization evidence per lowercase form, excluding line-initial tokens."""
    def __init__(self):
        self.cap = Counter()
        self.non_initial = Counter()
        self.total = Counter()

    def observe(self, surface: str, lower: str, line_initial: bool):
        self.total[lower] += 1
        if line_initial:
            return
        self.non_initial[lower] += 1
        if surface[:1].isupper():
            self.cap[lower] += 1

    def is_proper(self, word: str) -> bool:
        n = self.non_initial.get(word, 0)
        if n < MIN_CASE_EVIDENCE:
            return False
        return (self.cap.get(word, 0) / n) >= PROPER_RATIO

    def ratio(self, word: str) -> float:
        n = self.non_initial.get(word, 0)
        return (self.cap.get(word, 0) / n) if n else 0.0

def is_profane(word: str, tag: str) -> bool:
    return any(word.startswith(r) for r in PROFANITY_ROOTS.get(tag, ()))

def apply_filters(freq: Counter, evidence: CaseEvidence, tag: str):
    """Return (kept, removed_by_reason) where removed_by_reason maps reason -> Counter."""
    kept = Counter()
    removed = defaultdict(Counter)
    for w, c in freq.items():
        if is_profane(w, tag):
            removed["profanity"][w] = c
        elif evidence.is_proper(w):
            removed["proper_noun"][w] = c
        elif c < MIN_FREQ:
            removed["below_min_freq"][w] = c
        else:
            kept[w] = c
    return kept, removed
