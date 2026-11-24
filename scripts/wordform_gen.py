#!/usr/bin/env python3
"""Build-time Tatar word-form generator.

Given a stem, generate its inflectional paradigm (noun cases, possessives, verb tenses and
gerunds) plus the frequent derivational suffixes, using Tatar vowel harmony and consonant
assimilation. The output is a CANDIDATE list: forms are emitted without a corpus check, and
rebuild_assets.py admits only forms attested in the corpora. Errors in the exceptions table or
in a pattern raise WordformError (exit 2).

Rules:

* Vowel harmony. The last stem syllable decides: back {а ы о у я ю} vs front
  {ә е и ө ү э}. э is front (эшләр/эшкә); я/ю are й+а/й+у, hence back (юлга,
  ярлар). A final у/ү/ю/я written right after another vowel is a diphthong
  glide and does NOT decide harmony (эшләү is front: эшләүче; дию is front:
  Lexical harmony rows in the exceptions table pin stems whose written vowels
  do not show the suffix harmony (министр→министры, ковид→ковидка,
  ел→елның, еш→ешрак). A terminal soft sign does not create a second harmony
  variant: soft-sign stems stay in the front row (вазгыять→вазгыяте).
* Suffix patterns are strings of Cyrillic literals plus archiphonemes:
  A=а/ә, I=ы/е, U=у/ү, Y=ый/и (the present-tense vowel), G=г/к (к after
  voiceless {п к с т ч ш ф х ц щ}), D=д/т (т after voiceless), L=л/н (н after
  nasals {м н ң}), T=д/т/н (ablative: т after voiceless, н after nasals:
  урманнан). ``(C~V)`` renders branch C after a consonant-final stem and
  branch V after a vowel-final stem (китеп vs эшләп). Noun possessives split
  the V branch by the final vowel: и/у/ү-final stems take full endings
  (әбием, суым, суыбыз), other vowel-final stems bare ones (абам, аракым);
  3sg is bare -ы/-е after у/ү (суы) and -сы/-се elsewhere (абасы, әбисе).
* Final п/к after a vowel voices to б/г on the possessive, present, masdar,
  -Iп converb, categorical-future and -AsI bases and before the comparative
  -рAк (тап→таба/табу/табып/табачак/табасы, нык→ныграк, күп→күбрәк).
  Listed Russian loans keep п/к (парк→паркы, банк→банкы, суп→супы).
  Listed Russian loans in б/г/д/з take voiceless-assimilating suffixes by
  pronunciation (ковид→ковидка/ковидтан, завод→заводка/заводта,
  клуб→клубка); final в and ж do not trigger this rule. The plural, case,
  past, negative and imperative suffixes otherwise attach to the plain stem
  (китапка, тапты, тапмый, тапыйк). A one-syllable й-stem drops й on those
  vowel-initial tense bases (куй→куя/куяр/куячак/кую; җый→җыя/җыю) and forms
  the -еп converb (куеп, җыеп). Stems in у/ү take -ып/-еп in the converb
  (ту→туып); a terminal soft sign drops before a vowel-initial possessive
  (автомобиль→автомобиле).
* The present tense of vowel-final stems contracts: the stem-final vowel is
  replaced by Y (укы→укый, эшлә→эшли, ди→ди, кара→карый).
* The indefinite future is lexically split (verified against kaikki.org tables):
  vowel stems take -р (эшләр; monosyllables -яр: дияр), monosyllabic consonant
  stems take -ар/-әр (язар, китәр, куяр), longer consonant stems take -ыр/-ер
  (әлсерер) with the final-consonant voicing above; the -ыр/-ер monosyllable
  class (барыр, бирер, белер, булыр, алыр, әйтер, калыр, килер, торыр,
  тулыр, йөрер) and the suppletive укы/ук- cluster (укый but укыр, укмый)
  are exception rows. The categorical future is the separate -ачак/-әчәк base
  (куячак). The infinitive appends -GA to the resolved indefinite-future base
  (язар→язарга, килер→килергә, укыр→укырга).
* Person paradigms (kaikki.org tables): the present carries type I endings
  (1pl -бIз: барабыз) and the -DI past type II endings (1pl -к: яздык); the
  indefinite future, the categorical future and the -GAн recent past take the
  predicative endings -мIн/-сIң/∅/-бIз/-сIз/-LAр
  (язармын, язачакбыз, язганнар), the conditional takes -м/-ң/∅/-к/-гIз/-LAр
  (язсам, язсак). The imperative adds 1pl -ыйк/-ик (vowel stems contract as
  in the present: эшлик, укыйк) and 3sg/3pl -сын(нар)/-сен(нәр).
* First/second-person possessives take all five non-nominative cases, on both
  the plain and plural stem (юлыма, адресымны, йолаларыбыз, урыннарыгызны).
  Third-person possessives add genitive and plural-base cases likewise.
* Nominal/adjective predicates take the predicative endings (татармын,
  өйбез; 3sg is the bare stem, 3pl coincides with the plural). The
  question clitic -мы/-ме stacks on every person form and nominal predicate
  under the ``.q`` labels (язаммы, татармы); the joined spelling is informal,
  so the attestation gate decides which of these ship.
* Exceptions live in scripts/wordform_exceptions_tat.tsv (lexical harmony,
  reviewed Russian loans, the possessive base of a syncopated stem, suppletive
  pronouns, explicit per-label overrides).

Usage:

    python3 scripts/wordform_gen.py paradigm STEM [--pos noun|verb|deriv|all]
    python3 scripts/wordform_gen.py candidates --words WORDS.tsv \
        --exceptions scripts/wordform_exceptions_tat.tsv --out OUT.tsv
    python3 scripts/wordform_gen.py group --words WORDS.tsv \
        --exceptions scripts/wordform_exceptions_tat.tsv --out GROUPS.tsv

``paradigm`` prints ``label<TAB>form`` rows in paradigm order. ``candidates`` writes sorted
unique ``form<TAB>stem<TAB>label`` rows for every listed word. ``group`` strips known suffixes
(longest first) to reach another LISTED word and keeps the analysis only when the generator
round-trips the form from that stem; forms reachable from several stems are skipped. Both
writers print a JSON stats report to stdout.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

import dictionary_coverage as coverage  # noqa: E402

DEFAULT_EXCEPTIONS = Path(__file__).resolve().with_name("wordform_exceptions_tat.tsv")

# --- Harmony engine ---------------------------------------------------------

BACK_VOWELS = frozenset("аыоуюя")
FRONT_VOWELS = frozenset("әеиөүэ")
VOWELS = BACK_VOWELS | FRONT_VOWELS
VOICELESS = frozenset("пкстчшфхцщ")
NASALS = frozenset("мнң")
# Final consonants that are pronounced voiceless in reviewed Russian loans.
LOAN_DEVOICING_FINALS = frozenset("бгдз")

BACK = "back"
FRONT = "front"


class WordformError(ValueError):
    """A word-form generation error; the command exits with code 2."""


class ExceptionsError(WordformError):
    """A structurally invalid exceptions table."""


def harmony_of(word: str) -> str | None:
    """Classify [word] by its last syllable vowel; None if it has none.

    A final у/ү/ю/я immediately preceded by another vowel is a diphthong glide
    and is skipped: the syllable nucleus decides (эшләү → ә → front; дию → и →
    front; барау → а → back; аю → а → back). A terminal ь marks the front row.
    """
    if word.endswith("ь"):
        return FRONT
    letters = list(word)
    for index in range(len(letters) - 1, -1, -1):
        char = letters[index]
        if char in "уүюя" and index > 0 and letters[index - 1] in VOWELS:
            continue
        if char in BACK_VOWELS:
            return BACK
        if char in FRONT_VOWELS:
            return FRONT
    return None


def harmony_variants(word: str, forced: str | None = None) -> tuple[str, ...]:
    """Return one lexical harmony variant; empty when no vowel gives one.

    Without an explicit [forced] row, only the last-syllable rule applies.
    Letter-based guesses about Russian loans are deliberately not rules.
    """
    if forced is not None:
        if forced not in (BACK, FRONT):
            raise WordformError(f"unknown forced harmony: {forced!r}")
        return (forced,)
    primary = harmony_of(word)
    return (primary,) if primary is not None else ()


def _voice_final(stem: str, voice: bool = True) -> str:
    """Voice stem-final п/к after a vowel, except for a listed Russian loan."""
    if not voice or len(stem) < 2 or stem[-2] not in VOWELS:
        return stem
    if stem[-1] == "п":
        return stem[:-1] + "б"
    if stem[-1] == "к":
        return stem[:-1] + "г"
    return stem

def _without_final_soft_sign(stem: str) -> str:
    """Drop the spelling-only final ь of a stem before a vocalic suffix."""
    return stem[:-1] if stem.endswith("ь") else stem

def _is_glide_stem(stem: str) -> bool:
    """A one-syllable й-stem (куй, җый, сөй) whose й drops before a vowel."""
    return (
        len(stem) >= 2
        and stem.endswith("й")
        and stem[-2] in VOWELS
        and syllable_count(stem) == 1
    )

def _glide_base(stem: str) -> str:
    return stem[:-1] if _is_glide_stem(stem) else stem

def _vowel_suffix_base(stem: str, voice_final: bool = True) -> str:
    """The stem shape before the vowel-initial verbal allomorphs."""
    base = _without_final_soft_sign(stem)
    if _is_glide_stem(base):
        base = _glide_base(base)
    return _voice_final(base, voice_final)


def render_pattern(
    pattern: str, base: str, harmony: str, devoiced_final: bool = False
) -> str:
    """Render a suffix [pattern] against [base] under [harmony].

    [devoiced_final] is true only for a listed Russian loan in б/г/д/з.
    Raises ``WordformError`` on an unknown archiphoneme or a malformed
    alternation (patterns are compile-time data; a typo must not pass).
    """
    if harmony not in (BACK, FRONT):
        raise WordformError(f"unknown harmony: {harmony!r}")
    if not base:
        raise WordformError("empty base word")
    last = base[-1]
    # A final soft sign is a spelling marker, not a consonant for suffix
    # assimilation: the preceding letter decides (җәмгыять+GA → җәмгыятькә).
    assimilation = base[-2] if last == "ь" and len(base) > 1 else last
    voiceless_assimilation = assimilation in VOICELESS or (
        devoiced_final and assimilation in LOAN_DEVOICING_FINALS
    )
    out: list[str] = []
    index = 0
    while index < len(pattern):
        char = pattern[index]
        if char == "(":
            end = pattern.find(")", index)
            if end < 0:
                raise WordformError(f"unbalanced '(' in pattern: {pattern!r}")
            branches = pattern[index + 1 : end].split("~")
            if len(branches) != 2:
                raise WordformError(
                    f"alternation must have exactly one '~' in pattern: {pattern!r}"
                )
            branch = branches[1] if last in VOWELS else branches[0]
            out.append(render_pattern(branch, base, harmony, devoiced_final))
            index = end + 1
            continue
        if char == "A":
            out.append("а" if harmony == BACK else "ә")
        elif char == "I":
            out.append("ы" if harmony == BACK else "е")
        elif char == "U":
            out.append("у" if harmony == BACK else "ү")
        elif char == "Y":
            out.append("ый" if harmony == BACK else "и")
        elif char == "G":
            out.append("к" if voiceless_assimilation else "г")
        elif char == "D":
            out.append("т" if voiceless_assimilation else "д")
        elif char == "L":
            out.append("н" if assimilation in NASALS else "л")
        elif char == "T":
            # Ablative initial: т after voiceless, н after nasals
            # (урманнан, таңнан, моңнан), д elsewhere.
            out.append(
                "т" if voiceless_assimilation else "н" if assimilation in NASALS else "д"
            )
        elif char.isascii() and char.isalpha():
            raise WordformError(
                f"unknown archiphoneme {char!r} in pattern: {pattern!r}"
            )
        else:
            out.append(char)
        index += 1
    return "".join(out)


def attach(
    base: str,
    pattern: str,
    harmony: str | None = None,
    devoiced_final: bool = False,
) -> str:
    """Append [pattern] to [base]; when [harmony] is omitted, classify [base].

    Derived bases (a plural, a possessive, a tense stem) carry their own last
    syllable, so suffixes stacked on them harmonize by the base itself:
    татарлар+га, баласы+нда, язма+ды.
    """
    resolved = harmony if harmony is not None else harmony_of(base)
    if resolved is None:
        raise WordformError(f"no harmony vowel in base: {base!r}")
    return base + render_pattern(pattern, base, resolved, devoiced_final)


def present_base(stem: str, harmony: str, voice_final: bool = True) -> str:
    """The present-tense stem: +A after consonants (яза), vowel contraction
    to Y after vowels (укы→укый, эшлә→эшли)."""
    if _is_glide_stem(stem):
        return _glide_base(stem) + "я"
    base = _vowel_suffix_base(stem, voice_final)
    if base[-1] in VOWELS:
        return base[:-1] + render_pattern("Y", base, harmony)
    return attach(base, "A", harmony)


def syllable_count(word: str) -> int:
    """Rough syllable count: one per harmony vowel letter."""
    return sum(1 for char in word if char in VOWELS)


def masdar_form(stem: str, harmony: str, voice_final: bool = True) -> str:
    """The masdar (verbal noun): -у/-ү after consonants (язу, китү) and after
    stems in а/ә/о/ө (башлау, эшләү); stems in ы/и/у/ү take the й-glide
    spelling -ю (җыю, дию, сию, сую). укы→уку and ю→юу are exception rows."""
    if _is_glide_stem(stem):
        return _glide_base(stem) + "ю"
    base = _vowel_suffix_base(stem, voice_final)
    if base[-1] in VOWELS and base[-1] not in "аәоөэ":
        return base + "ю"
    return attach(base, "U", harmony)


def future_base(stem: str, harmony: str, voice_final: bool = True) -> str:
    """The indefinite-future stem (verified against kaikki.org Tatar tables).

    Vowel-final stems take -р (эшләр, сөйләр), monosyllabic vowel stems take
    -яр (ди→дияр, ю→юяр). Monosyllabic consonant stems take -ар/-әр (язар,
    китәр, керәр) on the plain stem, longer consonant stems take -ыр/-ер on
    the voiced stem (әлсерер, сакланыр, күнек→күнегер). The -ыр/-ер
    monosyllable class (барыр, бирер, белер, булыр, алыр, әйтер, калыр,
    килер, торыр, тулыр, йөрер) and укы→укыр are lexical exception rows.
    """
    if _is_glide_stem(stem):
        stem = _glide_base(stem)
    if stem[-1] in VOWELS:
        return stem + "яр" if syllable_count(stem) == 1 else stem + "р"
    if syllable_count(stem) == 1:
        return attach(stem, "Aр", harmony)
    return attach(
        _voice_final(_without_final_soft_sign(stem), voice_final), "Iр", harmony
    )


def converb_ip_form(stem: str, harmony: str, voice_final: bool = True) -> str:
    """The -Iп converb: -еп on a one-syllable й-stem (куеп, җыеп), -Iп on
    у/ү-final stems (туып), otherwise the consonant/vowel alternation."""
    if _is_glide_stem(stem):
        return _glide_base(stem) + "еп"
    if stem[-1] in "уү":
        return attach(stem, "Iп", harmony)
    return attach(
        _voice_final(_without_final_soft_sign(stem), voice_final), "(Iп~п)", harmony
    )

# --- Paradigm tables --------------------------------------------------------

# Ordered case suffixes of the noun paradigm (plural first: plural+case forms
# are built on it below).
NOUN_CASE_PATTERNS = (
    ("noun.pl", "LAр"),
    ("noun.gen", "нIң"),
    ("noun.dat", "GA"),
    ("noun.acc", "нI"),
    ("noun.loc", "DA"),
    ("noun.abl", "TAн"),
)

# Possessives attach to the stem's vocalic-suffix shape: п/к voices after a
# vowel (китап→китабы), final ь drops (автомобиль→автомобиле), and a listed
# syncopated stem uses its short base (халык→халкы), and the listed vowel-stem
# alternation of ай gives аенда. 3sg is the base of the
# post-3sg case forms below. The vowel-final
# branch splits by the final vowel (kaikki.org tables): и/у/ү-final stems take
# the full vowel-initial endings (әбием, суым, суыбыз), other vowel-final
# stems the bare endings (абам, кешем, аракым); for 3sg the split differs —
# у/ү-final stems take bare -ы/-е (суы, авыруы), everything else takes
# -сы/-се (абасы, әбисе, аракысы). The -ай/-әй possessive family contracts
# to -ае/-әе plus the bare person ending (малай→малаем/малае).
HIATUS_FINAL = frozenset("иуү")


def _possessive_forms(stem: str, harmony: str) -> list[tuple[str, str]]:
    last = stem[-1]
    full = last not in VOWELS or last in HIATUS_FINAL
    if stem.endswith(("ай", "әй")) or (
        stem.endswith("е") and stem[:-1].endswith(("а", "ә"))
    ):
        # The whole possessive family contracts: малай→малаем/малаең/малае;
        # a lexical base such as ай→ае enters already contracted.
        p3 = stem[:-1] + "е" if stem[-1] == "й" else stem
        return [
            ("noun.p1", attach(p3, "м", harmony)),
            ("noun.p2", attach(p3, "ң", harmony)),
            ("noun.p3", p3),
            ("noun.p1pl", attach(p3, "бIз", harmony)),
            ("noun.p2pl", attach(p3, "гIз", harmony)),
        ]
    if last not in VOWELS or last in "уү":
        p3 = attach(stem, "I", harmony)
    else:
        p3 = attach(stem, "сI", harmony)
    return [
        ("noun.p1", attach(stem, "Iм" if full else "м", harmony)),
        ("noun.p2", attach(stem, "Iң" if full else "ң", harmony)),
        ("noun.p3", p3),
        ("noun.p1pl", attach(stem, "IбIз" if full else "бIз", harmony)),
        ("noun.p2pl", attach(stem, "IгIз" if full else "гIз", harmony)),
    ]

# First/second-person possessives take the regular case endings directly:
# юл+ым+га → юлыма, адрес+ым+ны → адресымны.
FIRST_SECOND_POSSESSIVES = ("noun.p1", "noun.p2", "noun.p1pl", "noun.p2pl")
NOUN_POSSESSIVE_CASE_PATTERNS = (
    ("gen", "нIң"),
    ("dat", "GA"),
    ("acc", "нI"),
    ("loc", "DA"),
    ("abl", "TAн"),
)

# Cases after the third-person possessive are special (баласы+ның/н/на/нда/ыннан);
# they are built on the p3 form, which always ends in a vowel.
NOUN_POST3_PATTERNS = (
    ("noun.p3.gen", "нIң"),
    ("noun.p3.acc", "н"),
    ("noun.p3.dat", "нA"),
    ("noun.p3.loc", "нDA"),
    ("noun.p3.abl", "ннAн"),
)

# Case forms of the plural, built on the plural form (татарлар+ның/га/ны/да/дан).
NOUN_PLURAL_CASE_PATTERNS = (
    ("noun.pl.gen", "нIң"),
    ("noun.pl.dat", "GA"),
    ("noun.pl.acc", "нI"),
    ("noun.pl.loc", "DA"),
    ("noun.pl.abl", "TAн"),
)

# Personal endings. Type I rides the present; its base always ends in a vowel
# or in the glide й (яза, башлый, язмый), so 1sg is always -м and 1pl -бIз
# (башлыйм/башлыйбыз, язмыйм/язмыйбыз — never *-мын). Type II rides the -DI
# past, whose base is likewise always vowel-final (язды+м/ң/к/гыз/лар). 3sg is
# the bare base.
PERSON_TYPE_I = (
    ("1sg", "м"),
    ("2sg", "сIң"),
    ("3sg", None),
    ("1pl", "бIз"),
    ("2pl", "сIз"),
    ("3pl", "LAр"),
)
PERSON_TYPE_II = (
    ("1sg", "м"),
    ("2sg", "ң"),
    ("3sg", None),
    ("1pl", "к"),
    ("2pl", "гIз"),
    ("3pl", "LAр"),
)

# The predicative set (with 1pl -бIз) rides the indefinite future, the
# categorical future and the -GAн recent past (kaikki.org: язармын/язарбыз, язачаксыз,
# язганмын/язганнар); the conditional set (1pl -к, 2pl -гIз) rides the
# conditional (kaikki.org: язсам, язсак, язсагыз). The same predicative set
# builds the nominal/adjective predicates (татармын, өйбез).
PERSON_PREDICATIVE = (
    ("1sg", "мIн"),
    ("2sg", "сIң"),
    ("3sg", None),
    ("1pl", "бIз"),
    ("2pl", "сIз"),
    ("3pl", "LAр"),
)
PERSON_CONDITIONAL = (
    ("1sg", "м"),
    ("2sg", "ң"),
    ("3sg", None),
    ("1pl", "к"),
    ("2pl", "гIз"),
    ("3pl", "LAр"),
)

# Nominal/adjective predicative persons. 3sg is the bare stem (skipped as a
# candidate, but the base of noun.pred.3sg.q: татармы), 3pl coincides with
# noun.pl (татарлар "they are Tatars"). The endings attach to the plain stem,
# never to the voiced possessive stem (китапмын, not *китабымын).
NOUN_PREDICATIVE_PATTERNS = (
    ("noun.pred.1sg", "мIн"),
    ("noun.pred.2sg", "сIң"),
    ("noun.pred.3sg", None),
    ("noun.pred.1pl", "бIз"),
    ("noun.pred.2pl", "сIз"),
    ("noun.pred.3pl", "LAр"),
)

# The question clitic -мы/-ме stacks on person forms and nominal predicates
# (язаммы, татармы); the pattern harmonizes on the person form itself.
QUESTION_PATTERN = "мI"

DERIV_PATTERNS = (
    ("deriv.ca", "чA"),  # language/manner: татарча; only ча/чә exists
    ("deriv.lik", "лIк"),  # дуслык, мөмкинлек; л does not assimilate in Tatar
    ("deriv.li", "лI"),  # көчле
    ("deriv.siz", "сIз"),  # сусыз
    ("deriv.ci", "чI"),  # укытучы (on the stem укыту)
    ("deriv.das", "DAш"),  # юлдаш; т after voiceless
    ("deriv.rak", "рAк"),  # comparative: тизрәк
)

# The negative gerund does not fit the archiphoneme notation (мыйча vs мичә —
# the front variant carries и, not *әй; kaikki.org: язмыйча, китмичә), so it is
# spelled out per harmony.
NEG_GERUND = {BACK: "мыйча", FRONT: "мичә"}

# One layer of productive verb derivation. Vowel/consonant branches follow the
# same alternation as the inflectional patterns; causative -т follows vowels.
VERB_DERIV_PATTERNS = (
    ("verb.deriv.passive", "(Iл~л)"),
    ("verb.deriv.reciprocal", "(Iш~ш)"),
    ("verb.deriv.causative", "(DIр~т)"),
    ("verb.deriv.causative_double", "(DIрт~т)"),
    ("verb.deriv.frequentative", "GAлA"),
)
NOUN_VERB_DERIV_PATTERNS = (
    ("verb.deriv.denominal_n", "лAн"),
    ("verb.deriv.denominal_nsh", "лAш"),
)

NOMINALIZED_VERB_BASES = (
    ("verb.masdar", "verb.masdar"),
    ("verb.masdar.neg", "verb.masdar.neg"),
    ("verb.part.gan", "verb.part.gan"),
    ("verb.part.gan.neg", "verb.rpast.neg.3sg"),
    ("verb.part.uci", "verb.part.uci"),
    ("verb.deffut.3sg", "verb.deffut.3sg"),
)

_VERB_PERSON_TENSES = (
    ("verb.pres", PERSON_TYPE_I),
    ("verb.pres.neg", PERSON_TYPE_I),
    ("verb.past", PERSON_TYPE_II),
    ("verb.past.neg", PERSON_TYPE_II),
    ("verb.rpast", PERSON_PREDICATIVE),
    ("verb.rpast.neg", PERSON_PREDICATIVE),
    ("verb.fut", PERSON_PREDICATIVE),
    ("verb.fut.neg", PERSON_PREDICATIVE),
    ("verb.deffut", PERSON_PREDICATIVE),
    ("verb.cond", PERSON_CONDITIONAL),
    ("verb.cond.neg", PERSON_CONDITIONAL),
)

VERB_LABELS = (
    [
        "verb.imp.2sg", "verb.imp.2pl", "verb.imp.1pl", "verb.imp.3sg", "verb.imp.3pl",
        "verb.imp.neg.2sg", "verb.imp.neg.2pl", "verb.imp.neg.1pl",
        "verb.imp.neg.3sg", "verb.imp.neg.3pl",
    ]
    + [
        f"{tense}.{person}{question}"
        for tense, endings in _VERB_PERSON_TENSES
        for person, _ in endings
        for question in ("", ".q")
    ]
    + [
        "verb.ger.ip",
        "verb.ger.gac",
        "verb.ger.ganci",
        "verb.ger.neg",
        "verb.part.gan",
        "verb.part.uci",
        "verb.part.asi",
        "verb.masdar",
        "verb.masdar.neg",
        "verb.inf",
        "verb.intent",
    ]
)

NOUN_LABELS = (
    [label for label, _ in NOUN_CASE_PATTERNS]
    + ["noun.p1", "noun.p2", "noun.p3", "noun.p1pl", "noun.p2pl"]
    + [
        f"{possessive}.{case}"
        for possessive in FIRST_SECOND_POSSESSIVES
        for case, _ in NOUN_POSSESSIVE_CASE_PATTERNS
    ]
    + ["noun.p3pl"]
    + [label for label, _ in NOUN_POST3_PATTERNS]
    + [
        label.replace("noun.p3.", "noun.p3pl.", 1)
        for label, _ in NOUN_POST3_PATTERNS
    ]
    + [
        f"noun.pl.{possessive.removeprefix('noun.')}{suffix}"
        for possessive in FIRST_SECOND_POSSESSIVES
        for suffix in ("",) + tuple(
            f".{case}" for case, _ in NOUN_POSSESSIVE_CASE_PATTERNS
        )
    ]
    + [label for label, _ in NOUN_PLURAL_CASE_PATTERNS]
    + [
        f"{label}{question}"
        for label, _ in NOUN_PREDICATIVE_PATTERNS
        for question in ("", ".q")
    ]
    + ["noun.pron"]  # suppletive pronoun forms from the exceptions table
)

DERIV_LABELS = [label for label, _ in DERIV_PATTERNS]

KNOWN_LABELS = frozenset(NOUN_LABELS + VERB_LABELS + DERIV_LABELS)


# --- Exceptions table -------------------------------------------------------


@dataclass(frozen=True)
class Exceptions:
    """The manual exception tables of scripts/wordform_exceptions_tat.tsv."""

    harmonies: Mapping[str, str]  # stem -> forced back/front suffix harmony
    possessive_bases: Mapping[str, str]  # stem -> base of possessive-family forms
    pronouns: Mapping[str, tuple[str, ...]]  # stem -> suppletive noun forms
    overrides: Mapping[tuple[str, str], str]  # (stem, label) -> explicit form
    loanwords: frozenset[str] = frozenset()  # reviewed Russian loan stems


EMPTY_EXCEPTIONS = Exceptions({}, {}, {}, {})


def _require_word(value: str, source: str, line_number: int, field: str) -> str:
    """Validate a stem/form field against the dictionary alphabet."""
    normalized, reason = coverage.normalize_word(value)
    if normalized is None or normalized != value:
        raise ExceptionsError(
            f"{source}:{line_number}: {field} {value!r} is not a normalized "
            f"Tatar word ({reason or 'not NFC/lowercase'})"
        )
    return value


def load_exceptions(path: Path) -> Exceptions:
    """Load the exceptions table; any structural error raises ExceptionsError."""
    harmonies: dict[str, str] = {}
    possessive_bases: dict[str, str] = {}
    pronouns: dict[str, tuple[str, ...]] = {}
    overrides: dict[tuple[str, str], str] = {}
    loanwords: dict[str, str] = {}
    try:
        # Python 3.10-compatible: read bytes and decode — read_text(newline=...) needs 3.13.
        text = path.read_bytes().decode("utf-8")
    except OSError as error:
        raise ExceptionsError(f"cannot read exceptions table {path}: {error}") from error
    if "\r" in text:
        raise ExceptionsError(f"{path}: CR characters are not allowed (use LF)")

    for line_number, line in enumerate(text.split("\n"), start=1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) < 3 or any(field == "" for field in fields):
            raise ExceptionsError(
                f"{path}:{line_number}: expected kind<TAB>stem<TAB>payload... "
                f"with no empty fields, got {len(fields)} field(s)"
            )
        kind, stem = fields[0], fields[1]
        _require_word(stem, str(path), line_number, "stem")

        if kind == "harmony":
            if len(fields) != 3 or fields[2] not in (BACK, FRONT):
                raise ExceptionsError(
                    f"{path}:{line_number}: harmony rows carry payload 'back' or 'front'"
                )
            target: dict = harmonies
            value: object = fields[2]
        elif kind == "loan":
            if len(fields) != 3 or fields[2] != "yes":
                raise ExceptionsError(
                    f"{path}:{line_number}: loan rows carry the payload 'yes'"
                )
            target = loanwords
            value = fields[2]
        elif kind == "possessive":
            if len(fields) != 3:
                raise ExceptionsError(
                    f"{path}:{line_number}: possessive rows carry exactly one base field"
                )
            base = _require_word(fields[2], str(path), line_number, "possessive base")
            if base == stem:
                raise ExceptionsError(
                    f"{path}:{line_number}: possessive base must differ from the stem"
                )
            target = possessive_bases
            value = base
        elif kind == "pronoun":
            forms = tuple(
                _require_word(field, str(path), line_number, "pronoun form")
                for field in fields[2:]
            )
            if len(set(forms)) != len(forms):
                raise ExceptionsError(f"{path}:{line_number}: duplicate pronoun forms")
            target = pronouns
            value = forms
        elif kind == "override":
            seen_labels: set[str] = set()
            for field in fields[2:]:
                if field.count("=") != 1:
                    raise ExceptionsError(
                        f"{path}:{line_number}: override payload must be label=form, "
                        f"got {field!r}"
                    )
                label, form = field.split("=")
                if label not in KNOWN_LABELS:
                    raise ExceptionsError(
                        f"{path}:{line_number}: unknown override label {label!r}"
                    )
                if label in seen_labels:
                    raise ExceptionsError(
                        f"{path}:{line_number}: duplicate override label {label!r}"
                    )
                seen_labels.add(label)
                _require_word(form, str(path), line_number, "override form")
                key = (stem, label)
                if key in overrides:
                    raise ExceptionsError(
                        f"{path}:{line_number}: duplicate override for {stem} {label}"
                    )
                overrides[key] = form
            continue
        else:
            raise ExceptionsError(f"{path}:{line_number}: unknown kind {kind!r}")

        if stem in target:
            raise ExceptionsError(
                f"{path}:{line_number}: duplicate {kind} row for stem {stem!r}"
            )
        target[stem] = value

    return Exceptions(
        harmonies=harmonies,
        possessive_bases=possessive_bases,
        pronouns=pronouns,
        overrides=overrides,
        loanwords=frozenset(loanwords),
    )


# --- Paradigm generation ----------------------------------------------------


def _apply_overrides(
    stem: str, forms: list[tuple[str, str]], exceptions: Exceptions
) -> list[tuple[str, str]]:
    """Replace generated forms by explicit overrides for the labels emitted here."""
    if not exceptions.overrides:
        return forms
    return [
        (label, exceptions.overrides.get((stem, label), form)) for label, form in forms
    ]


def generate_noun_forms(
    stem: str, harmony: str, exceptions: Exceptions = EMPTY_EXCEPTIONS
) -> list[tuple[str, str]]:
    """The ordered noun paradigm of [stem] as (label, form) pairs."""
    pronoun = exceptions.pronouns.get(stem)
    if pronoun is not None:
        # Suppletive paradigm: the table forms replace regular noun generation
        # entirely (мин → минем/миңа/мине/миндә/миннән; no минләр).
        return [("noun.pron", form) for form in pronoun]

    forms: list[tuple[str, str]] = []
    voice_final = stem not in exceptions.loanwords
    devoiced_final = stem in exceptions.loanwords and stem[-1] in LOAN_DEVOICING_FINALS
    # An override of the plural re-derives every form built on the plural.
    plural = exceptions.overrides.get((stem, "noun.pl")) or attach(stem, "LAр", harmony)
    for label, pattern in NOUN_CASE_PATTERNS:
        forms.append(
            (
                label,
                plural
                if label == "noun.pl"
                else attach(stem, pattern, harmony, devoiced_final),
            )
        )

    # Possessives use the stem's vocalic-suffix shape; plural-case and
    # predicative forms stay on the plain stem (китапка, китаплары).
    poss_stem = _voice_final(
        _without_final_soft_sign(exceptions.possessive_bases.get(stem, stem)), voice_final
    )
    possessives = _possessive_forms(poss_stem, harmony)
    forms.extend(possessives)
    forms = _apply_overrides(stem, forms, exceptions)
    possessive_map = dict(forms)
    poss3 = possessive_map["noun.p3"]

    for possessive in FIRST_SECOND_POSSESSIVES:
        base = possessive_map[possessive]
        for case, pattern in NOUN_POSSESSIVE_CASE_PATTERNS:
            if case == "dat" and possessive in ("noun.p1", "noun.p2"):
                pattern = "A"  # юлыма, юлыңа; plural persons take -га/-гә.
            forms.append((f"{possessive}.{case}", attach(base, pattern)))

    plural_possessives = dict(_possessive_forms(plural, harmony))
    poss3pl = exceptions.overrides.get((stem, "noun.p3pl"), plural_possessives["noun.p3"])
    forms.append(("noun.p3pl", poss3pl))
    for label, pattern in NOUN_POST3_PATTERNS:
        forms.append((label, attach(poss3, pattern, harmony)))
    for label, pattern in NOUN_POST3_PATTERNS:
        forms.append((label.replace("noun.p3.", "noun.p3pl.", 1), attach(poss3pl, pattern, harmony)))

    for possessive in FIRST_SECOND_POSSESSIVES:
        base = plural_possessives[possessive]
        forms.append((f"noun.pl.{possessive.removeprefix('noun.')}", base))
        for case, pattern in NOUN_POSSESSIVE_CASE_PATTERNS:
            if case == "dat" and possessive in ("noun.p1", "noun.p2"):
                pattern = "A"
            forms.append(
                (
                    f"noun.pl.{possessive.removeprefix('noun.')}.{case}",
                    attach(base, pattern),
                )
            )
    for label, pattern in NOUN_PLURAL_CASE_PATTERNS:
        forms.append((label, attach(plural, pattern)))
    for label, pattern in NOUN_PREDICATIVE_PATTERNS:
        form = stem if pattern is None else attach(stem, pattern, harmony)
        # The predicative rows sit after _apply_overrides ran, so an override
        # (plain or .q) replaces the generated person form here; the question
        # clitic then stacks on the overridden base.
        form = exceptions.overrides.get((stem, label), form)
        forms.append((label, form))
        forms.append(
            (f"{label}.q", exceptions.overrides.get((stem, f"{label}.q"), attach(form, QUESTION_PATTERN)))
        )
    return _apply_overrides(stem, forms, exceptions)


def generate_extended_noun_forms(
    stem: str, harmony: str, exceptions: Exceptions = EMPTY_EXCEPTIONS
) -> list[tuple[str, str]]:
    """Generate the nominal paradigm plus locative-attributive and adverbial cases."""
    forms = generate_noun_forms(stem, harmony, exceptions)
    if stem in exceptions.pronouns:
        return list(forms)
    extended = list(forms)
    for label, form in forms:
        if label == "noun.loc" or label.endswith(".loc"):
            extended.append((f"{label}.attr", attach(form, "гI")))
    devoiced_final = stem in exceptions.loanwords and stem[-1] in LOAN_DEVOICING_FINALS
    for label, pattern in (
        ("noun.sim", "DAй"),
        ("noun.term", "GAчA"),
    ):
        form = exceptions.overrides.get((stem, label)) or attach(
            stem, pattern, harmony, devoiced_final
        )
        extended.append((label, form))
    return extended


def generate_verb_forms(
    stem: str, harmony: str, exceptions: Exceptions = EMPTY_EXCEPTIONS
) -> list[tuple[str, str]]:
    """The ordered verb paradigm of [stem] as (label, form) pairs."""
    voice_final = stem not in exceptions.loanwords
    devoiced_final = stem in exceptions.loanwords and stem[-1] in LOAN_DEVOICING_FINALS
    forms: list[tuple[str, str]] = [("verb.imp.2sg", stem)]
    negated = attach(stem, "мA", harmony)
    forms.append(("verb.imp.2pl", attach(stem, "(IгIз~гIз)", harmony)))
    # 1pl -ыйк/-ик (языйк, китик); vowel stems contract the final vowel as in
    # the present (эшлик, укыйк), a bare vowel stem keeps a й glide (юйык).
    if stem[-1] in VOWELS:
        if len(stem) > 1:
            imp_1pl = stem[:-1] + render_pattern("Yк", stem, harmony)
        else:
            imp_1pl = stem + "й" + render_pattern("Iк", stem, harmony)
        forms.append(("verb.imp.1pl", imp_1pl))
    else:
        forms.append(("verb.imp.1pl", attach(stem, "Yк", harmony)))
    forms.append(("verb.imp.3sg", attach(stem, "сIн", harmony)))
    forms.append(("verb.imp.3pl", attach(stem, "сIннAр", harmony)))
    forms.append(("verb.imp.neg.2sg", negated))
    forms.append(("verb.imp.neg.2pl", attach(negated, "(IгIз~гIз)")))
    if negated[-1] in VOWELS:
        negative_imp_1pl = negated[:-1] + render_pattern("Yк", negated, harmony)
    else:
        negative_imp_1pl = attach(negated, "Yк", harmony)
    forms.append(("verb.imp.neg.1pl", negative_imp_1pl))
    forms.append(("verb.imp.neg.3sg", attach(negated, "сIн")))
    forms.append(("verb.imp.neg.3pl", attach(negated, "сIннAр")))

    # Person-bearing bases; an override of the 3sg form re-derives the persons
    # (override бар verb.fut.3sg=барыр → барырмын/барырсың/барырбыз…).
    bases = {
        "verb.pres": exceptions.overrides.get((stem, "verb.pres.3sg"))
        or present_base(stem, harmony, voice_final),
        "verb.pres.neg": exceptions.overrides.get((stem, "verb.pres.neg.3sg"))
        or attach(stem, "мY", harmony),
        "verb.past": exceptions.overrides.get((stem, "verb.past.3sg"))
        or attach(stem, "DI", harmony, devoiced_final),
        "verb.past.neg": exceptions.overrides.get((stem, "verb.past.neg.3sg"))
        or attach(negated, "DI"),
        "verb.rpast": exceptions.overrides.get((stem, "verb.rpast.3sg"))
        or attach(stem, "GAн", harmony, devoiced_final),
        "verb.rpast.neg": exceptions.overrides.get((stem, "verb.rpast.neg.3sg"))
        or attach(negated, "GAн"),
        "verb.fut": exceptions.overrides.get((stem, "verb.fut.3sg"))
        or future_base(stem, harmony, voice_final),
        "verb.fut.neg": exceptions.overrides.get((stem, "verb.fut.neg.3sg"))
        or attach(stem, "мAс", harmony),
        "verb.deffut": exceptions.overrides.get((stem, "verb.deffut.3sg"))
        or attach(_vowel_suffix_base(stem, voice_final), "(AчAк~ячAк)", harmony),
        "verb.cond": exceptions.overrides.get((stem, "verb.cond.3sg"))
        or attach(stem, "сA", harmony),
        "verb.cond.neg": exceptions.overrides.get((stem, "verb.cond.neg.3sg"))
        or attach(negated, "сA"),
    }
    for tense, endings in _VERB_PERSON_TENSES:
        base = bases[tense]
        for person, pattern in endings:
            form = base if pattern is None else attach(base, pattern)
            forms.append((f"{tense}.{person}", form))
            forms.append((f"{tense}.{person}.q", attach(form, QUESTION_PATTERN)))

    forms.append(("verb.ger.ip", converb_ip_form(stem, harmony, voice_final)))
    forms.append(("verb.ger.gac", attach(stem, "GAч", harmony, devoiced_final)))
    forms.append(("verb.ger.ganci", attach(stem, "GAнчI", harmony, devoiced_final)))
    forms.append(("verb.ger.neg", stem + NEG_GERUND[harmony]))
    forms.append(("verb.part.gan", attach(stem, "GAн", harmony, devoiced_final)))
    forms.append(("verb.part.uci", attach(stem, "UчI", harmony)))
    if stem[-1] not in VOWELS:
        # The -AsI participle is only safe on consonant stems (язасы, киләсе);
        # vowel-stem shapes are not standard, so they are not generated.
        forms.append(
            ("verb.part.asi", attach(_vowel_suffix_base(stem, voice_final), "AсI", harmony))
        )
    forms.append(("verb.masdar", masdar_form(stem, harmony, voice_final)))
    forms.append(("verb.masdar.neg", attach(negated, "U")))
    # The infinitive is exactly the resolved indefinite-future base plus -GA.
    # Lexical future overrides therefore determine it before this attachment.
    forms.append(("verb.inf", attach(bases["verb.fut"], "GA")))
    forms.append(("verb.intent", attach(stem, "мAкчI", harmony)))
    return _apply_overrides(stem, forms, exceptions)


def generate_nominalized_verb_forms(
    stem: str, harmony: str, exceptions: Exceptions = EMPTY_EXCEPTIONS
) -> list[tuple[str, str]]:
    """Generate nominal chains from verbal nouns, participles and nominalized stems."""
    resolved = dict(generate_verb_forms(stem, harmony, exceptions))
    forms: list[tuple[str, str]] = []
    for nominal_label, base_label in NOMINALIZED_VERB_BASES:
        base = resolved[base_label]
        bases_for_nominal_chains = [base]
        if nominal_label.startswith("verb.masdar") and base[-1] in "уү":
            bases_for_nominal_chains.append(base[:-1] + "в")
        for nominal_base in bases_for_nominal_chains:
            for noun_label, form in generate_extended_noun_forms(nominal_base, harmony):
                forms.append((f"{nominal_label}.{noun_label}", form))
    return forms


def generate_derived_verb_forms(
    stem: str,
    harmony: str,
    exceptions: Exceptions = EMPTY_EXCEPTIONS,
    *,
    noun_chain: bool,
    verb_chain: bool,
) -> list[tuple[str, str]]:
    """Generate one verb-derivation layer from an enabled noun or verb stem."""
    devoiced_final = stem in exceptions.loanwords and stem[-1] in LOAN_DEVOICING_FINALS
    patterns = []
    if verb_chain:
        patterns.extend(VERB_DERIV_PATTERNS)
    if noun_chain:
        patterns.extend(NOUN_VERB_DERIV_PATTERNS)
    forms: list[tuple[str, str]] = []
    for label, pattern in patterns:
        derived = attach(stem, pattern, harmony, devoiced_final)
        children = generate_verb_forms(derived, harmony)
        children += generate_nominalized_verb_forms(derived, harmony)
        for child_label, form in children:
            forms.append((f"{label}.{child_label}", form))
    return forms


def generate_deriv_forms(
    stem: str, harmony: str, exceptions: Exceptions = EMPTY_EXCEPTIONS
) -> list[tuple[str, str]]:
    """The frequent derivational suffixes of [stem] as (label, form) pairs."""
    voice_final = stem not in exceptions.loanwords
    devoiced_final = stem in exceptions.loanwords and stem[-1] in LOAN_DEVOICING_FINALS
    forms: list[tuple[str, str]] = []
    for label, pattern in DERIV_PATTERNS:
        base = (
            _voice_final(_without_final_soft_sign(stem), voice_final)
            if label == "deriv.rak"
            else stem
        )
        forms.append((label, attach(base, pattern, harmony, devoiced_final)))
    return forms


def generate_assumptive_forms(
    forms: Sequence[tuple[str, str]]
) -> list[tuple[str, str]]:
    """Add the -дыр/-дер assumptive clitic once to each generated form."""
    return list(forms) + [
        (f"{label}.assumptive", attach(form, "DIр")) for label, form in forms
    ]


def generate_all(
    stem: str, exceptions: Exceptions = EMPTY_EXCEPTIONS
) -> list[tuple[str, str]]:
    """Noun + verb + derivational forms of [stem] over its harmony variants.

    Raises ``WordformError`` when the stem has no harmony vowel, and when an
    override names a label this stem never emits (e.g. a noun override on a
    suppletive pronoun) — a stale table row is an error, not a no-op.
    """
    variants = harmony_variants(stem, exceptions.harmonies.get(stem))
    if not variants:
        raise WordformError(f"no Tatar harmony vowel in stem: {stem!r}")
    forms: list[tuple[str, str]] = []
    for harmony in variants:
        forms.extend(generate_noun_forms(stem, harmony, exceptions))
        forms.extend(generate_verb_forms(stem, harmony, exceptions))
        forms.extend(generate_deriv_forms(stem, harmony, exceptions))
    emitted = {label for label, _ in forms}
    for override_stem, label in exceptions.overrides:
        if override_stem == stem and label not in emitted:
            raise WordformError(
                f"override {label!r} of stem {stem!r} matches no generated form"
            )
    return forms


def generate_acceptor_forms(
    stem: str,
    exceptions: Exceptions = EMPTY_EXCEPTIONS,
    *,
    noun_chain: bool,
    verb_chain: bool,
) -> list[tuple[str, str]]:
    """Generate only the chains licensed by the acceptor's continuation flags."""
    variants = harmony_variants(stem, exceptions.harmonies.get(stem))
    if not variants:
        raise WordformError(f"no Tatar harmony vowel in stem: {stem!r}")
    forms: list[tuple[str, str]] = []
    for harmony in variants:
        if noun_chain:
            forms.extend(generate_extended_noun_forms(stem, harmony, exceptions))
            forms.extend(generate_deriv_forms(stem, harmony, exceptions))
        if verb_chain:
            forms.extend(generate_verb_forms(stem, harmony, exceptions))
            forms.extend(generate_nominalized_verb_forms(stem, harmony, exceptions))
        forms.extend(
            generate_derived_verb_forms(
                stem,
                harmony,
                exceptions,
                noun_chain=noun_chain,
                verb_chain=verb_chain,
            )
        )
    emitted = {label for label, _ in forms}
    for override_stem, label in exceptions.overrides:
        if override_stem == stem and label not in emitted:
            raise WordformError(
                f"override {label!r} of stem {stem!r} matches no generated form"
            )
    return generate_assumptive_forms(forms)


# --- Candidate and group builders -------------------------------------------


def read_word_list(path: Path) -> tuple[list[str], int]:
    """Read normalized unique words from a word list; returns (words, skipped).

    Accepts one word per line, ``word<TAB>frequency`` or Leipzig
    ``id<TAB>word<TAB>frequency``; blank lines and ``#`` comments are skipped.
    Words rejected by the dictionary pipeline's normalization are skipped and
    counted (they can never be dictionary stems).
    """
    words: list[str] = []
    skipped = 0
    with path.open("r", encoding="utf-8", newline="") as stream:
        for line in stream:
            line = line.rstrip("\r\n")
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split("\t")
            raw = fields[1] if len(fields) >= 3 else fields[0]
            word, _reason = coverage.normalize_word(raw)
            if word is None:
                skipped += 1
                continue
            words.append(word)
    return sorted(set(words)), skipped


def label_family(label: str) -> str:
    """The report family of a label: the question-clitic layer (``.q``) and the
    nominal predicative persons count separately from the plain rows, so the
    report shows how much volume the new families add."""
    if label.endswith(".q"):
        return label_family(label[:-2]) + ".q"
    if label.startswith("noun.pred."):
        return "noun.pred"
    return label.split(".", 1)[0]


def _family_counts(rows: Sequence[tuple[str, str, str]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for _a, _b, label in rows:
        family = label_family(label)
        counts[family] = counts.get(family, 0) + 1
    return dict(sorted(counts.items()))


def build_candidates(
    words: Sequence[str], exceptions: Exceptions
) -> tuple[list[tuple[str, str, str]], dict[str, object]]:
    """Generate (form, stem, label) candidate rows for every listed word."""
    rows: set[tuple[str, str, str]] = set()
    stats: dict[str, object] = {
        "stems": 0,
        "stems_skipped_no_harmony": 0,
        "dual_harmony_stems": 0,
    }
    for word in words:
        variants = harmony_variants(word, exceptions.harmonies.get(word))
        if not variants:
            stats["stems_skipped_no_harmony"] += 1
            continue
        stats["stems"] += 1
        if len(variants) > 1:
            stats["dual_harmony_stems"] += 1
        for label, form in generate_all(word, exceptions):
            if form == word:
                continue  # the bare stem (verb.imp.2sg) is already a listed word
            rows.add((form, word, label))
    sorted_rows = sorted(rows)
    stats["candidate_rows"] = len(sorted_rows)
    stats["candidate_rows_by_family"] = _family_counts(sorted_rows)
    return sorted_rows, stats


# Probe stems covering back/front × vowel/voiced/voiceless/nasal finals plus
# the hiatus possessive class (и/у-final) and the monosyllabic back consonant
# class (сал: the simple future splits by syllable count, so only a
# monosyllabic back probe yields the -ар person suffixes: саларсың); the
# suffix inventory for grouping is read off their generated forms, so every
# surface suffix the generator can produce is strippable — and nothing else.
PROBE_STEMS = ("сабыр", "азап", "аман", "ала", "ел", "кит", "кем", "еле", "аби", "басу", "сал")


def suffix_inventory() -> tuple[str, ...]:
    """All surface suffixes the generator produces, longest first."""
    suffixes: set[str] = set()
    for probe in PROBE_STEMS:
        for _label, form in generate_all(probe, EMPTY_EXCEPTIONS):
            if form.startswith(probe) and len(form) > len(probe):
                suffixes.add(form[len(probe) :])
    return tuple(sorted(suffixes, key=lambda suffix: (-len(suffix), suffix)))


def build_groups(
    words: Sequence[str], exceptions: Exceptions
) -> tuple[list[tuple[str, str, str]], dict[str, object]]:
    """Group listed forms under listed stems: (stem, form, label) rows.

    A form is grouped under a stem only when stripping a known suffix reaches
    another LISTED word and the generator round-trips the form from that stem.
    Forms reachable from more than one listed stem are ambiguous and skipped.
    """
    word_set = set(words)
    inventory = suffix_inventory()
    cache: dict[str, list[tuple[str, str]] | None] = {}

    def forms_of(stem: str) -> list[tuple[str, str]] | None:
        if stem not in cache:
            try:
                cache[stem] = generate_all(stem, exceptions)
            except WordformError:
                cache[stem] = None  # no harmony: cannot validate, never groups
        return cache[stem]

    rows: list[tuple[str, str, str]] = []
    stats: dict[str, object] = {
        "words": len(word_set),
        "ambiguous_skipped": 0,
        "too_short_skipped": 0,
    }
    for form in sorted(word_set):
        if len(form) < 3:
            stats["too_short_skipped"] += 1
            continue
        hits: dict[str, str] = {}
        for suffix in inventory:
            if len(form) - len(suffix) < 2 or not form.endswith(suffix):
                continue
            stem = form[: -len(suffix)]
            if stem in hits or stem not in word_set:
                continue
            generated = forms_of(stem)
            if generated is None:
                continue
            for label, candidate in generated:
                if candidate == form:
                    hits[stem] = label
                    break
        if len(hits) == 1:
            stem, label = next(iter(hits.items()))
            rows.append((stem, form, label))
        elif hits:
            stats["ambiguous_skipped"] += 1
    rows.sort()
    stats["grouped_forms"] = len(rows)
    stats["grouped_forms_by_family"] = _family_counts(rows)
    stats["distinct_stems"] = len({stem for stem, _, _ in rows})
    return rows, stats


# --- IO and CLI --------------------------------------------------------------


def write_atomic(path: Path, data: bytes) -> None:
    """Write [data] to [path] atomically: temp file in the same directory, then rename."""
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temp_name = tempfile.mkstemp(
        dir=str(path.parent), prefix=path.name + ".", suffix=".tmp"
    )
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_name, path)
    except BaseException:
        try:
            os.unlink(temp_name)
        except OSError:
            pass
        raise


def render_rows(rows: Sequence[tuple[str, str, str]]) -> bytes:
    """Serialize (form, stem, label) rows as UTF-8 TSV with LF endings."""
    return "".join("\t".join(row) + "\n" for row in rows).encode("utf-8")


def _report(stats: Mapping[str, object], out: Path, data: bytes) -> None:
    report = {
        **stats,
        "output": str(out),
        "output_bytes": len(data),
        "output_sha256": hashlib.sha256(data).hexdigest(),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))


def _cmd_paradigm(args: argparse.Namespace) -> int:
    exceptions = load_exceptions(args.exceptions)
    stem, reason = coverage.normalize_word(args.stem)
    if stem is None or stem != args.stem:
        raise WordformError(
            f"stem {args.stem!r} is not a normalized Tatar word "
            f"({reason or 'not NFC/lowercase'})"
        )
    variants = harmony_variants(stem, exceptions.harmonies.get(stem))
    if not variants:
        raise WordformError(f"no Tatar harmony vowel in stem: {stem!r}")
    generators = {
        "noun": generate_noun_forms,
        "verb": generate_verb_forms,
        "deriv": lambda s, h, e: generate_deriv_forms(s, h),
    }
    selected = generators if args.pos == "all" else {args.pos: generators[args.pos]}
    rows: list[str] = []
    for harmony in variants:
        if len(variants) > 1:
            rows.append(f"# harmony={harmony}")
        for name in ("noun", "verb", "deriv"):
            if name not in selected:
                continue
            for label, form in generators[name](stem, harmony, exceptions):
                rows.append(f"{label}\t{form}")
        generate_all(stem, exceptions)  # raises if an override matches no generated form
    sys.stdout.write("\n".join(rows) + "\n")
    return 0


def _cmd_candidates(args: argparse.Namespace) -> int:
    exceptions = load_exceptions(args.exceptions)
    words, skipped = read_word_list(args.words)
    rows, stats = build_candidates(words, exceptions)
    stats["words_read"] = len(words)
    stats["words_skipped_normalization"] = skipped
    data = render_rows(rows)
    write_atomic(args.out, data)
    _report(stats, args.out, data)
    return 0


def _cmd_group(args: argparse.Namespace) -> int:
    exceptions = load_exceptions(args.exceptions)
    words, skipped = read_word_list(args.words)
    rows, stats = build_groups(words, exceptions)
    stats["words_read"] = len(words)
    stats["words_skipped_normalization"] = skipped
    # Group rows are (stem, form, label); render as stem<TAB>form<TAB>label.
    data = render_rows(rows)
    write_atomic(args.out, data)
    _report(stats, args.out, data)
    return 0


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)

    paradigm = subparsers.add_parser(
        "paradigm", help="print the full paradigm of a stem (label<TAB>form rows)"
    )
    paradigm.add_argument("stem")
    paradigm.add_argument(
        "--pos", choices=("noun", "verb", "deriv", "all"), default="all"
    )
    paradigm.add_argument("--exceptions", type=Path, default=DEFAULT_EXCEPTIONS)
    paradigm.set_defaults(handler=_cmd_paradigm)

    for command, help_text, handler in (
        (
            "candidates",
            "write sorted form<TAB>stem<TAB>label candidates for a word list",
            _cmd_candidates,
        ),
        (
            "group",
            "group listed forms under listed stems (stem<TAB>form<TAB>label)",
            _cmd_group,
        ),
    ):
        sub = subparsers.add_parser(command, help=help_text)
        sub.add_argument("--words", type=Path, required=True, help="word list TSV")
        sub.add_argument(
            "--exceptions", type=Path, required=True, help="exceptions table TSV"
        )
        sub.add_argument("--out", type=Path, required=True, help="output TSV")
        sub.set_defaults(handler=handler)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        return args.handler(args)
    except (WordformError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
