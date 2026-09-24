#!/usr/bin/env python3
"""Build-time Tatar word-form generator (TT-SUGGESTIONS phase P1).

Given a stem, generate its inflectional paradigm (noun cases, possessives,
verb tenses and gerunds) plus the frequent derivational suffixes, using Tatar
vowel harmony and consonant assimilation. The output is a CANDIDATE list for
phase P2: forms are emitted without any corpus check; P2 admits only forms
attested in the Leipzig/conversational corpora (frequency = corpus count).

Architecture:

* Vowel harmony. The last stem syllable decides: back {а ы о у я ю} vs front
  {ә е и ө ү э}. (The plan's core sets are {а ы о у}/{ә е и ө ү}; э is front
  — эшләр/эшкә — and я/ю are й+а/й+у, hence back — юлга, ярлар.) A final
  у/ү/ю/я written right after another vowel is a diphthong glide and does NOT
  decide harmony (эшләү is front: эшләүче; дию is front: диде). Stems mixing
  both harmony classes (most Russian loans: совет = о+е) or carrying a Russian
  marker letter {ё ъ ь ж ц щ} are generated in BOTH harmony variants — loans
  take back suffixes in practice (советларга) but a surface rule cannot
  separate them from native front stems (исемгә); overgeneration is safe
  because P2 keeps only attested forms.
* Suffix patterns are strings of Cyrillic literals plus archiphonemes:
  A=а/ә, I=ы/е, U=у/ү, Y=ый/и (the present-tense vowel), G=г/к (к after
  voiceless {п к с т ч ш ф х ц щ}), D=д/т (т after voiceless), L=л/н (н after
  nasals {м н ҥ}), T=д/т/н (ablative: т after voiceless, н after nasals —
  урманнан). ``(C~V)`` renders branch C after a consonant-final stem and
  branch V after a vowel-final stem (китеп vs эшләп). Noun possessives split
  the V branch by the final vowel: и/у/ү-final stems take full endings
  (әбием, суым, суыбыз), other vowel-final stems bare ones (абам, аракым);
  3sg is bare -ы/-е after у/ү (суы) and -сы/-се elsewhere (абасы, әбисе).
* The present tense of vowel-final stems contracts: the stem-final vowel is
  replaced by Y (укы→укый, эшлә→эшли, ди→ди); only кара→кара is an override.
* The simple future is lexically split (verified against kaikki.org tables):
  vowel stems take -р (эшләр; monosyllables -яр: дияр), monosyllabic consonant
  stems take -ар/-әр (язар, китәр), longer consonant stems -ыр/-ер
  (әлсерер); the -ыр/-ер monosyllable class (барыр, булыр, алыр, калыр,
  тулыр, йөрер) and the suppletive укы/ук- cluster (укый but укар, укмый)
  are exception rows.
* Exceptions live in scripts/wordform_exceptions_tat.tsv (п→б/к→г voicing
  before vocalic possessives, suppletive pronouns, explicit per-label
  overrides) and are loaded fail-closed.
* Everything is deterministic: fixed paradigm order, sorted candidate rows,
  no wall-clock fields, atomic writes (temp file + rename in place).

Usage:

    python3 scripts/wordform_gen.py paradigm STEM [--pos noun|verb|deriv|all]
    python3 scripts/wordform_gen.py candidates --words WORDS.tsv \
        --exceptions scripts/wordform_exceptions_tat.tsv --out OUT.tsv
    python3 scripts/wordform_gen.py group --words WORDS.tsv \
        --exceptions scripts/wordform_exceptions_tat.tsv --out GROUPS.tsv

``paradigm`` prints ``label<TAB>form`` rows in canonical paradigm order.
``candidates`` writes sorted unique ``form<TAB>stem<TAB>label`` rows for every
listed word (noun + verb + derivational generation). ``group`` analyses the
word list itself: it strips known suffixes (longest first) to reach another
LISTED word and keeps the analysis only when the generator round-trips the
form from that stem; forms reachable from more than one listed stem are
ambiguous and skipped (fail-closed). Word lists accept one word per line,
``word<TAB>frequency`` or Leipzig ``id<TAB>word<TAB>frequency``; words that
fail the dictionary pipeline's own normalization are skipped and counted.
Both writers print a JSON stats report to stdout. stdlib only.
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
# Letters that mark an unassimilated Russian loan; such stems are generated in
# both harmony variants (see the module docstring).
RUSSIAN_MARKERS = frozenset("ёъьжцщ")

BACK = "back"
FRONT = "front"


class WordformError(ValueError):
    """A fail-closed word-form generation error."""


class ExceptionsError(WordformError):
    """A structurally invalid exceptions table."""


def harmony_of(word: str) -> str | None:
    """Classify [word] by its last syllable vowel; None if it has none.

    A final у/ү/ю/я immediately preceded by another vowel is a diphthong glide
    and is skipped: the syllable nucleus decides (эшләү → ә → front; дию → и →
    front; барау → а → back; аю → а → back).
    """
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


def harmony_variants(word: str) -> tuple[str, ...]:
    """The harmony variants to generate for [word], primary first.

    One variant for unambiguous stems; two for mixed-harmony stems and for
    stems with a Russian marker letter. Empty when the word carries no harmony
    vowel at all (caller skips it; such a word can take no suffix).
    """
    primary = harmony_of(word)
    if primary is None:
        return ()
    letters = set(word)
    mixed = bool(letters & BACK_VOWELS) and bool(letters & FRONT_VOWELS)
    if mixed or letters & RUSSIAN_MARKERS:
        return (primary, FRONT if primary == BACK else BACK)
    return (primary,)


def render_pattern(pattern: str, base: str, harmony: str) -> str:
    """Render a suffix [pattern] against [base] under [harmony].

    Raises ``WordformError`` on an unknown archiphoneme or a malformed
    alternation (patterns are compile-time data; a typo must not pass).
    """
    if harmony not in (BACK, FRONT):
        raise WordformError(f"unknown harmony: {harmony!r}")
    if not base:
        raise WordformError("empty base word")
    last = base[-1]
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
            out.append(render_pattern(branch, base, harmony))
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
            out.append("к" if last in VOICELESS else "г")
        elif char == "D":
            out.append("т" if last in VOICELESS else "д")
        elif char == "L":
            out.append("н" if last in NASALS else "л")
        elif char == "T":
            # Ablative initial: т after voiceless, н after nasals
            # (урманнан, таңнан, моңнан), д elsewhere.
            out.append("т" if last in VOICELESS else "н" if last in NASALS else "д")
        elif char.isascii() and char.isalpha():
            raise WordformError(
                f"unknown archiphoneme {char!r} in pattern: {pattern!r}"
            )
        else:
            out.append(char)
        index += 1
    return "".join(out)


def attach(base: str, pattern: str, harmony: str | None = None) -> str:
    """Append [pattern] to [base]; when [harmony] is omitted, classify [base].

    Derived bases (a plural, a possessive, a tense stem) carry their own last
    syllable, so suffixes stacked on them harmonize by the base itself:
    татарлар+га, баласы+нда, язма+ды.
    """
    resolved = harmony if harmony is not None else harmony_of(base)
    if resolved is None:
        raise WordformError(f"no harmony vowel in base: {base!r}")
    return base + render_pattern(pattern, base, resolved)


def present_base(stem: str, harmony: str) -> str:
    """The present-tense stem: +A after consonants (яза), vowel contraction
    to Y after vowels (укы→укый, эшлә→эшли)."""
    if stem[-1] in VOWELS:
        return stem[:-1] + render_pattern("Y", stem, harmony)
    return attach(stem, "A", harmony)


def syllable_count(word: str) -> int:
    """Rough syllable count: one per harmony vowel letter."""
    return sum(1 for char in word if char in VOWELS)


def masdar_form(stem: str, harmony: str) -> str:
    """The masdar (verbal noun): -у/-ү after consonants (язу, китү) and after
    stems in а/ә/о/ө (башлау, эшләү); stems in ы/и/у/ү take the й-glide
    spelling -ю (җыю, дию, сию, сую). укы→уку and ю→юу are exception rows."""
    if stem[-1] in VOWELS and stem[-1] not in "аәоөэ":
        return stem + "ю"
    return attach(stem, "U", harmony)


def future_base(stem: str, harmony: str) -> str:
    """The simple-future stem (verified against kaikki.org Tatar tables).

    Vowel-final stems take -р (эшләр, сөйләр), monosyllabic vowel stems take
    -яр (ди→дияр, ю→юяр). Monosyllabic consonant stems take -ар/-әр (язар,
    китәр, керәр), longer consonant stems take -ыр/-ер (әлсерер, сакланыр).
    The -ыр/-ер monosyllable class (барыр, булыр, алыр, калыр, тулыр, йөрер)
    and укы→укар are lexical and sit in the exceptions table.
    """
    if stem[-1] in VOWELS:
        return stem + "яр" if syllable_count(stem) == 1 else stem + "р"
    if syllable_count(stem) == 1:
        return attach(stem, "Aр", harmony)
    return attach(stem, "Iр", harmony)


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

# Possessives; they attach to the (possibly voiced, see the exceptions table)
# stem. 3sg is the base of the post-3sg case forms below. The vowel-final
# branch splits by the final vowel (kaikki.org tables): и/у/ү-final stems take
# the full vowel-initial endings (әбием, суым, суыбыз), other vowel-final
# stems the bare endings (абам, кешем, аракым); for 3sg the split differs —
# у/ү-final stems take bare -ы/-е (суы, авыруы), everything else takes
# -сы/-се (абасы, әбисе, аракысы).
HIATUS_FINAL = frozenset("иуү")


def _possessive_forms(stem: str, harmony: str) -> list[tuple[str, str]]:
    last = stem[-1]
    full = last not in VOWELS or last in HIATUS_FINAL
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

# Cases after the 3sg possessive are special (баласы+н/на/нда/ыннан); they are
# built on the p3 form, which always ends in a vowel.
NOUN_POST3_PATTERNS = (
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
# or in the glide й (яза, башлый, язмый), so 1sg is always -м (башлыйм,
# язмыйм — never *-мын). Type II rides the -DI past, whose base is likewise
# always vowel-final (язды+м/ң/гыз/лар). 3sg is the bare base.
PERSON_TYPE_I = (
    ("1sg", "м"),
    ("2sg", "сIң"),
    ("3sg", None),
    ("2pl", "сIз"),
    ("3pl", "LAр"),
)
PERSON_TYPE_II = (
    ("1sg", "м"),
    ("2sg", "ң"),
    ("3sg", None),
    ("2pl", "гIз"),
    ("3pl", "LAр"),
)

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

_VERB_PERSON_TENSES = (
    ("verb.pres", PERSON_TYPE_I),
    ("verb.pres.neg", PERSON_TYPE_I),
    ("verb.past", PERSON_TYPE_II),
    ("verb.past.neg", PERSON_TYPE_II),
)

VERB_LABELS = (
    ["verb.imp.2sg", "verb.imp.2pl"]
    + [f"{tense}.{person}" for tense, endings in _VERB_PERSON_TENSES for person, _ in endings]
    + [
        "verb.rpast.3sg",
        "verb.rpast.neg.3sg",
        "verb.fut.3sg",
        "verb.fut.neg.3sg",
        "verb.deffut.3sg",
        "verb.cond.3sg",
        "verb.cond.neg.3sg",
        "verb.ger.ip",
        "verb.ger.gac",
        "verb.ger.ganci",
        "verb.ger.neg",
        "verb.part.gan",
        "verb.part.uci",
        "verb.part.asi",
        "verb.masdar",
        "verb.intent",
    ]
)

NOUN_LABELS = (
    [label for label, _ in NOUN_CASE_PATTERNS]
    + ["noun.p1", "noun.p2", "noun.p3", "noun.p1pl", "noun.p2pl"]
    + ["noun.p3pl"]
    + [label for label, _ in NOUN_POST3_PATTERNS]
    + [label for label, _ in NOUN_PLURAL_CASE_PATTERNS]
    + ["noun.pron"]  # suppletive pronoun forms from the exceptions table
)

DERIV_LABELS = [label for label, _ in DERIV_PATTERNS]

KNOWN_LABELS = frozenset(NOUN_LABELS + VERB_LABELS + DERIV_LABELS)


# --- Exceptions table -------------------------------------------------------


@dataclass(frozen=True)
class Exceptions:
    """The manual exception tables of scripts/wordform_exceptions_tat.tsv."""

    voicing: Mapping[str, str]  # stem -> voiced stem for possessive-family forms
    pronouns: Mapping[str, tuple[str, ...]]  # stem -> suppletive noun forms
    overrides: Mapping[tuple[str, str], str]  # (stem, label) -> explicit form


EMPTY_EXCEPTIONS = Exceptions({}, {}, {})

# Stem-final voicing before vocalic possessive suffixes: п→б, к→г.
VOICING_PAIRS = {("п", "б"), ("к", "г")}


def _require_word(value: str, source: str, line_number: int, field: str) -> str:
    """Validate a stem/form field against the dictionary alphabet (fail-closed)."""
    normalized, reason = coverage.normalize_word(value)
    if normalized is None or normalized != value:
        raise ExceptionsError(
            f"{source}:{line_number}: {field} {value!r} is not a normalized "
            f"Tatar word ({reason or 'not NFC/lowercase'})"
        )
    return value


def load_exceptions(path: Path) -> Exceptions:
    """Load the exceptions table; any structural error aborts (fail-closed)."""
    voicing: dict[str, str] = {}
    pronouns: dict[str, tuple[str, ...]] = {}
    overrides: dict[tuple[str, str], str] = {}
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

        if kind == "voicing":
            if len(fields) != 3:
                raise ExceptionsError(
                    f"{path}:{line_number}: voicing rows carry exactly one payload field"
                )
            voiced = _require_word(fields[2], str(path), line_number, "voiced stem")
            pair = (stem[-1], voiced[-1])
            if (
                len(stem) != len(voiced)
                or stem[:-1] != voiced[:-1]
                or pair not in VOICING_PAIRS
            ):
                raise ExceptionsError(
                    f"{path}:{line_number}: voicing must change only the final "
                    f"letter п→б or к→г, got {stem} → {voiced}"
                )
            target: dict = voicing
            value: object = voiced
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

    return Exceptions(voicing=voicing, pronouns=pronouns, overrides=overrides)


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
    # An override of the plural re-derives the plural+case forms built on it.
    plural = exceptions.overrides.get((stem, "noun.pl")) or attach(stem, "LAр", harmony)
    for label, pattern in NOUN_CASE_PATTERNS:
        forms.append((label, plural if label == "noun.pl" else attach(stem, pattern, harmony)))

    # Possessives attach to the voiced stem for voicing-exception stems
    # (китабым/китабы), everything else to the plain stem (китапка, китаплары).
    poss_stem = exceptions.voicing.get(stem, stem)
    possessives = _possessive_forms(poss_stem, harmony)
    poss3 = dict(possessives)["noun.p3"]
    forms.extend(possessives)
    forms = _apply_overrides(stem, forms, exceptions)
    poss3 = exceptions.overrides.get((stem, "noun.p3"), poss3)

    forms.append(("noun.p3pl", attach(stem, "LAрI", harmony)))
    for label, pattern in NOUN_POST3_PATTERNS:
        forms.append((label, attach(poss3, pattern)))
    for label, pattern in NOUN_PLURAL_CASE_PATTERNS:
        forms.append((label, attach(plural, pattern)))
    return forms


def generate_verb_forms(
    stem: str, harmony: str, exceptions: Exceptions = EMPTY_EXCEPTIONS
) -> list[tuple[str, str]]:
    """The ordered verb paradigm of [stem] as (label, form) pairs."""
    forms: list[tuple[str, str]] = [("verb.imp.2sg", stem)]
    forms.append(("verb.imp.2pl", attach(stem, "(IгIз~гIз)", harmony)))

    # Person-bearing bases; an override of the 3sg form re-derives the persons
    # (override кара verb.pres.3sg=кара → карам/карасың/карасыз/каралар).
    negated = attach(stem, "мA", harmony)
    bases = {
        "verb.pres": exceptions.overrides.get((stem, "verb.pres.3sg"))
        or present_base(stem, harmony),
        "verb.pres.neg": exceptions.overrides.get((stem, "verb.pres.neg.3sg"))
        or attach(stem, "мY", harmony),
        "verb.past": exceptions.overrides.get((stem, "verb.past.3sg"))
        or attach(stem, "DI", harmony),
        "verb.past.neg": exceptions.overrides.get((stem, "verb.past.neg.3sg"))
        or attach(negated, "DI"),
    }
    for tense, endings in _VERB_PERSON_TENSES:
        base = bases[tense]
        for person, pattern in endings:
            forms.append(
                (f"{tense}.{person}", base if pattern is None else attach(base, pattern))
            )

    forms.append(("verb.rpast.3sg", attach(stem, "GAн", harmony)))
    forms.append(("verb.rpast.neg.3sg", attach(negated, "GAн")))
    forms.append(
        (
            "verb.fut.3sg",
            exceptions.overrides.get((stem, "verb.fut.3sg"))
            or future_base(stem, harmony),
        )
    )
    forms.append(("verb.fut.neg.3sg", attach(stem, "мAс", harmony)))
    forms.append(("verb.deffut.3sg", attach(stem, "(AчAк~ячAк)", harmony)))
    forms.append(("verb.cond.3sg", attach(stem, "сA", harmony)))
    forms.append(("verb.cond.neg.3sg", attach(negated, "сA")))
    forms.append(("verb.ger.ip", attach(stem, "(Iп~п)", harmony)))
    forms.append(("verb.ger.gac", attach(stem, "GAч", harmony)))
    forms.append(("verb.ger.ganci", attach(stem, "GAнчI", harmony)))
    forms.append(("verb.ger.neg", stem + NEG_GERUND[harmony]))
    forms.append(("verb.part.gan", attach(stem, "GAн", harmony)))
    forms.append(("verb.part.uci", attach(stem, "UчI", harmony)))
    if stem[-1] not in VOWELS:
        # The -AsI participle is only safe on consonant stems (язасы, киләсе);
        # vowel-stem shapes are not standard, so they are not generated.
        forms.append(("verb.part.asi", attach(stem, "AсI", harmony)))
    forms.append(("verb.masdar", masdar_form(stem, harmony)))
    forms.append(("verb.intent", attach(stem, "мAкчI", harmony)))
    return _apply_overrides(stem, forms, exceptions)


def generate_deriv_forms(stem: str, harmony: str) -> list[tuple[str, str]]:
    """The frequent derivational suffixes of [stem] as (label, form) pairs."""
    return [(label, attach(stem, pattern, harmony)) for label, pattern in DERIV_PATTERNS]


def generate_all(
    stem: str, exceptions: Exceptions = EMPTY_EXCEPTIONS
) -> list[tuple[str, str]]:
    """Noun + verb + derivational forms of [stem] over its harmony variants.

    Raises ``WordformError`` when the stem has no harmony vowel, and when an
    override names a label this stem never emits (e.g. a noun override on a
    suppletive pronoun) — a stale table row is an error, not a no-op.
    """
    variants = harmony_variants(stem)
    if not variants:
        raise WordformError(f"no Tatar harmony vowel in stem: {stem!r}")
    forms: list[tuple[str, str]] = []
    for harmony in variants:
        forms.extend(generate_noun_forms(stem, harmony, exceptions))
        forms.extend(generate_verb_forms(stem, harmony, exceptions))
        forms.extend(generate_deriv_forms(stem, harmony))
    emitted = {label for label, _ in forms}
    for override_stem, label in exceptions.overrides:
        if override_stem == stem and label not in emitted:
            raise WordformError(
                f"override {label!r} of stem {stem!r} matches no generated form"
            )
    return forms


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


def build_candidates(
    words: Sequence[str], exceptions: Exceptions
) -> tuple[list[tuple[str, str, str]], dict[str, int]]:
    """Generate (form, stem, label) candidate rows for every listed word."""
    rows: set[tuple[str, str, str]] = set()
    stats = {"stems": 0, "stems_skipped_no_harmony": 0, "dual_harmony_stems": 0}
    for word in words:
        variants = harmony_variants(word)
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
    stats["candidate_rows"] = len(rows)
    return sorted(rows), stats


# Probe stems covering back/front × vowel/voiced/voiceless/nasal finals plus
# the hiatus possessive class (и/у-final); the suffix inventory for grouping is
# read off their generated forms, so every surface suffix the generator can
# produce is strippable — and nothing else.
PROBE_STEMS = ("сабыр", "азап", "аман", "ала", "ел", "кит", "кем", "еле", "аби", "басу")


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
) -> tuple[list[tuple[str, str, str]], dict[str, int]]:
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
    stats = {"words": len(word_set), "ambiguous_skipped": 0, "too_short_skipped": 0}
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
    variants = harmony_variants(stem)
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
        generate_all(stem, exceptions)  # override-consumption check, fail-closed
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
