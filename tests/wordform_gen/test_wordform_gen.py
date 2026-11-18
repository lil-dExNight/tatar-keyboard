#!/usr/bin/env python3
"""Contract tests for the Tatar word-form generator (scripts/wordform_gen.py).

What is pinned and why:

* Hand-written golden paradigms (our own data, no license issues) for the
  harmony/assimilation classes that matter: back/front, vowel/consonant-final,
  voiceless and nasal finals, final-consonant voicing (китап→китабы), the
  3sg possessive override (су→суы), suppletive pronouns, vowel-stem present
  contraction (эшлә→эшли) and the derivational set (дуслык, мөмкинлек).
* The newer label families, cross-checked against the kaikki.org Tatar verb
  tables: person rows for the future/conditional/definite-future/recent-past
  (язармын, язсак, язачакбыз, язганнар), the 1pl/3rd-person imperatives
  (языйк, язсыннар), the nominal predicative persons (татармын, өйбез) and
  the -мы/-ме question-clitic layer (``.q`` labels: язаммы, татармы).
* The committed exceptions table (scripts/wordform_exceptions_tat.tsv) is
  pinned by byte SHA-256 and by row counts: editing the table must re-pin
  the tests, like the dictionary asset pins.
* The exceptions loader is fail-closed: every malformed row shape is a hard
  error, never a skipped line.
* Determinism: candidate and group builders produce byte-identical output on
  repeated runs over the same input.
"""
from __future__ import annotations

import hashlib
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GENERATOR_SCRIPT = ROOT / "scripts" / "wordform_gen.py"
EXCEPTIONS_FILE = ROOT / "scripts" / "wordform_exceptions_tat.tsv"

# Changing the exceptions table changes the generated candidate forms;
# re-pin in the same commit.
EXPECTED_EXCEPTIONS_SHA256 = (
    "9d45e6d6f81367cd7c3372e44b89bec0263d5cbc1c4a61d6b28c45cc67b1c19e"
)
EXPECTED_HARMONY_ROWS = 6
EXPECTED_POSSESSIVE_BASE_ROWS = 1
EXPECTED_PRONOUN_ROWS = 7
EXPECTED_OVERRIDE_ROWS = 20


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


gen = load_module("wordform_gen", GENERATOR_SCRIPT)

EXC = gen.load_exceptions(EXCEPTIONS_FILE)


def noun_forms(stem: str, harmony: str = "back") -> dict[str, str]:
    return dict(gen.generate_noun_forms(stem, harmony, EXC))


def verb_forms(stem: str, harmony: str = "back") -> dict[str, str]:
    return dict(gen.generate_verb_forms(stem, harmony, EXC))


class ExceptionsPinTest(unittest.TestCase):
    def test_exceptions_file_sha256(self):
        digest = hashlib.sha256(EXCEPTIONS_FILE.read_bytes()).hexdigest()
        self.assertEqual(digest, EXPECTED_EXCEPTIONS_SHA256)

    def test_exceptions_row_counts(self):
        self.assertEqual(len(EXC.harmonies), EXPECTED_HARMONY_ROWS)
        self.assertEqual(len(EXC.possessive_bases), EXPECTED_POSSESSIVE_BASE_ROWS)
        self.assertEqual(len(EXC.pronouns), EXPECTED_PRONOUN_ROWS)
        self.assertEqual(len(EXC.overrides), EXPECTED_OVERRIDE_ROWS)


class HarmonyTest(unittest.TestCase):
    def test_last_syllable_decides(self):
        self.assertEqual(gen.harmony_of("татар"), "back")
        self.assertEqual(gen.harmony_of("өй"), "front")
        self.assertEqual(gen.harmony_of("китап"), "back")
        self.assertEqual(gen.harmony_of("су"), "back")
        self.assertEqual(gen.harmony_of("мин"), "front")

    def test_extended_vowels(self):
        # э is front, я/ю are back (й+а/й+у).
        self.assertEqual(gen.harmony_of("элек"), "front")
        self.assertEqual(gen.harmony_of("юл"), "back")
        self.assertEqual(gen.harmony_of("яр"), "back")

    def test_final_glide_does_not_decide(self):
        # A final у/ү after another vowel is a diphthong glide.
        self.assertEqual(gen.harmony_of("эшләү"), "front")
        self.assertEqual(gen.harmony_of("дию"), "front")
        self.assertEqual(gen.harmony_of("барау"), "back")
        self.assertEqual(gen.harmony_of("табу"), "back")  # бу is a real syllable

    def test_no_vowel_no_harmony(self):
        self.assertIsNone(gen.harmony_of("б"))
        self.assertEqual(gen.harmony_variants("б"), ())

    def test_dual_harmony_for_mixed_and_loans(self):
        # совет = о+е: loans take back suffixes (советларга), but a surface
        # rule cannot tell them from native front stems, so both are generated.
        self.assertEqual(gen.harmony_variants("совет"), ("front", "back"))
        self.assertEqual(gen.harmony_variants("цирк"), ("front", "back"))
        self.assertEqual(gen.harmony_variants("татар"), ("back",))
        self.assertEqual(gen.harmony_variants("исем"), ("front",))

    def test_lexical_harmony_override(self):
        for stem in ("министр", "президент", "метр", "шрифт", "ел", "еш"):
            self.assertEqual(
                gen.harmony_variants(stem, EXC.harmonies.get(stem)), ("back",), stem
            )
        self.assertEqual(gen.harmony_variants("исем", EXC.harmonies.get("исем")), ("front",))
        with self.assertRaises(gen.WordformError):
            gen.harmony_variants("исем", "sideways")


class ArchiphonemeTest(unittest.TestCase):
    """One unit test per archiphoneme class, both harmonies."""

    def render(self, pattern: str, stem: str, harmony: str) -> str:
        return gen.render_pattern(pattern, stem, harmony)

    def test_a(self):
        self.assertEqual(self.render("чA", "татар", "back"), "ча")
        self.assertEqual(self.render("чA", "өй", "front"), "чә")

    def test_i(self):
        self.assertEqual(self.render("нIң", "татар", "back"), "ның")
        self.assertEqual(self.render("нIң", "өй", "front"), "нең")

    def test_u(self):
        self.assertEqual(self.render("U", "яз", "back"), "у")
        self.assertEqual(self.render("U", "кит", "front"), "ү")

    def test_y(self):
        self.assertEqual(self.render("мY", "яз", "back"), "мый")
        self.assertEqual(self.render("мY", "эшлә", "front"), "ми")

    def test_g_assimilation(self):
        self.assertEqual(self.render("GA", "татар", "back"), "га")
        self.assertEqual(self.render("GA", "китап", "back"), "ка")  # voiceless п
        self.assertEqual(self.render("GA", "өй", "front"), "гә")  # vowel
        self.assertEqual(self.render("GA", "кит", "front"), "кә")  # voiceless т

    def test_d_assimilation(self):
        self.assertEqual(self.render("DA", "татар", "back"), "да")
        self.assertEqual(self.render("DA", "китап", "back"), "та")
        self.assertEqual(self.render("DI", "су", "back"), "ды")

    def test_soft_sign_does_not_assimilate_suffixes(self):
        self.assertEqual(self.render("GA", "җәмгыять", "front"), "кә")
        self.assertEqual(self.render("DA", "җәмгыять", "front"), "тә")
        self.assertEqual(self.render("TAн", "җәмгыять", "front"), "тән")
        self.assertEqual(self.render("GA", "җәмгыять", "back"), "ка")
        self.assertEqual(self.render("GA", "өй", "front"), "гә")

    def test_l_assimilation(self):
        self.assertEqual(self.render("LAр", "татар", "back"), "лар")
        self.assertEqual(self.render("LAр", "урман", "back"), "нар")  # nasal н
        self.assertEqual(self.render("LAр", "кем", "front"), "нәр")  # nasal м
        self.assertEqual(self.render("LAр", "таң", "back"), "нар")  # nasal ҥ
        self.assertEqual(self.render("LAр", "өй", "front"), "ләр")

    def test_alternation(self):
        self.assertEqual(self.render("(Iм~м)", "татар", "back"), "ым")
        self.assertEqual(self.render("(Iм~м)", "бала", "back"), "м")
        self.assertEqual(self.render("(I~сI)", "ат", "back"), "ы")
        self.assertEqual(self.render("(I~сI)", "бала", "back"), "сы")

    def test_malformed_patterns_fail_closed(self):
        with self.assertRaises(gen.WordformError):
            self.render("(GA", "татар", "back")  # unbalanced
        with self.assertRaises(gen.WordformError):
            self.render("(G~A~L)", "татар", "back")  # not exactly two branches
        with self.assertRaises(gen.WordformError):
            self.render("LAr", "татар", "back")  # ASCII r is not an archiphoneme
        with self.assertRaises(gen.WordformError):
            self.render("GA", "татар", "sideways")


class GoldenNounParadigmTest(unittest.TestCase):
    def test_tatar_full_paradigm(self):
        self.assertEqual(
            gen.generate_noun_forms("татар", "back", EXC),
            [
                ("noun.pl", "татарлар"),
                ("noun.gen", "татарның"),
                ("noun.dat", "татарга"),
                ("noun.acc", "татарны"),
                ("noun.loc", "татарда"),
                ("noun.abl", "татардан"),
                ("noun.p1", "татарым"),
                ("noun.p2", "татарың"),
                ("noun.p3", "татары"),
                ("noun.p1pl", "татарыбыз"),
                ("noun.p2pl", "татарыгыз"),
                ("noun.p3pl", "татарлары"),
                ("noun.p3.acc", "татарын"),
                ("noun.p3.dat", "татарына"),
                ("noun.p3.loc", "татарында"),
                ("noun.p3.abl", "татарыннан"),
                ("noun.pl.gen", "татарларның"),
                ("noun.pl.dat", "татарларга"),
                ("noun.pl.acc", "татарларны"),
                ("noun.pl.loc", "татарларда"),
                ("noun.pl.abl", "татарлардан"),
                ("noun.pred.1sg", "татармын"),
                ("noun.pred.1sg.q", "татармынмы"),
                ("noun.pred.2sg", "татарсың"),
                ("noun.pred.2sg.q", "татарсыңмы"),
                ("noun.pred.3sg", "татар"),
                ("noun.pred.3sg.q", "татармы"),
                ("noun.pred.1pl", "татарбыз"),
                ("noun.pred.1pl.q", "татарбызмы"),
                ("noun.pred.2pl", "татарсыз"),
                ("noun.pred.2pl.q", "татарсызмы"),
                ("noun.pred.3pl", "татарлар"),
                ("noun.pred.3pl.q", "татарлармы"),
            ],
        )

    def test_oy_front_full_paradigm(self):
        self.assertEqual(
            gen.generate_noun_forms("өй", "front", EXC),
            [
                ("noun.pl", "өйләр"),
                ("noun.gen", "өйнең"),
                ("noun.dat", "өйгә"),
                ("noun.acc", "өйне"),
                ("noun.loc", "өйдә"),
                ("noun.abl", "өйдән"),
                ("noun.p1", "өйем"),
                ("noun.p2", "өйең"),
                ("noun.p3", "өйе"),
                ("noun.p1pl", "өйебез"),
                ("noun.p2pl", "өйегез"),
                ("noun.p3pl", "өйләре"),
                ("noun.p3.acc", "өйен"),
                ("noun.p3.dat", "өйенә"),
                ("noun.p3.loc", "өйендә"),
                ("noun.p3.abl", "өйеннән"),
                ("noun.pl.gen", "өйләрнең"),
                ("noun.pl.dat", "өйләргә"),
                ("noun.pl.acc", "өйләрне"),
                ("noun.pl.loc", "өйләрдә"),
                ("noun.pl.abl", "өйләрдән"),
                ("noun.pred.1sg", "өймен"),
                ("noun.pred.1sg.q", "өйменме"),
                ("noun.pred.2sg", "өйсең"),
                ("noun.pred.2sg.q", "өйсеңме"),
                ("noun.pred.3sg", "өй"),
                ("noun.pred.3sg.q", "өйме"),
                ("noun.pred.1pl", "өйбез"),
                ("noun.pred.1pl.q", "өйбезме"),
                ("noun.pred.2pl", "өйсез"),
                ("noun.pred.2pl.q", "өйсезме"),
                ("noun.pred.3pl", "өйләр"),
                ("noun.pred.3pl.q", "өйләрме"),
            ],
        )

    def test_urman_nasal_plural(self):
        forms = noun_forms("урман")
        self.assertEqual(forms["noun.pl"], "урманнар")
        self.assertEqual(forms["noun.p3pl"], "урманнары")

    def test_ablative_n_after_nasal(self):
        # The ablative initial is three-way: т after voiceless, н after nasals
        # (kaikki: урманнан, таңнан, моңнан), д elsewhere.
        self.assertEqual(noun_forms("урман")["noun.abl"], "урманнан")
        self.assertEqual(noun_forms("таң")["noun.abl"], "таңнан")
        self.assertEqual(noun_forms("көн", "front")["noun.abl"], "көннән")
        self.assertEqual(noun_forms("татар")["noun.abl"], "татардан")
        self.assertEqual(noun_forms("китап")["noun.abl"], "китаптан")
        self.assertEqual(noun_forms("су")["noun.abl"], "судан")
        # The locative and the -DI past keep д after nasals (урманда, минде).
        self.assertEqual(noun_forms("урман")["noun.loc"], "урманда")
        self.assertEqual(verb_forms("мин", "front")["verb.past.3sg"], "минде")

    def test_kitap_voicing_rule(self):
        # Voiceless G→к in the dative, but п→б voicing in the possessives.
        forms = noun_forms("китап")
        self.assertEqual(forms["noun.pl"], "китаплар")
        self.assertEqual(forms["noun.dat"], "китапка")
        self.assertEqual(forms["noun.loc"], "китапта")
        self.assertEqual(forms["noun.p3"], "китабы")
        self.assertEqual(forms["noun.p1"], "китабым")
        self.assertEqual(forms["noun.p2"], "китабың")
        self.assertEqual(forms["noun.p3pl"], "китаплары")  # no voicing: consonant suffix
        self.assertEqual(forms["noun.p3.acc"], "китабын")
        self.assertEqual(forms["noun.p3.abl"], "китабыннан")
        generated = {form for _, form in gen.generate_all("китап", EXC)}
        self.assertNotIn("китапы", generated)
        self.assertNotIn("китапым", generated)

    def test_su_u_final_hiatus_possessives(self):
        # у-final stems take full vowel-initial possessives (kaikki: суым,
        # суың, суыбыз) and bare -ы for 3sg (суы, never *сусы).
        forms = noun_forms("су")
        self.assertEqual(forms["noun.acc"], "суны")
        self.assertEqual(forms["noun.p1"], "суым")
        self.assertEqual(forms["noun.p2"], "суың")
        self.assertEqual(forms["noun.p3"], "суы")
        self.assertEqual(forms["noun.p1pl"], "суыбыз")
        self.assertEqual(forms["noun.p3.acc"], "суын")  # built on the p3 form
        self.assertEqual(forms["noun.p3.dat"], "суына")

    def test_hiatus_class_vs_plain_vowel_final(self):
        # и-final takes full endings (әбием), а/ы-final bare ones (абам,
        # аракым); и-final 3sg keeps -се (әбисе), у-final drops с (суы).
        self.assertEqual(noun_forms("әби", "front")["noun.p1"], "әбием")
        self.assertEqual(noun_forms("әби", "front")["noun.p3"], "әбисе")
        self.assertEqual(noun_forms("аба")["noun.p1"], "абам")
        self.assertEqual(noun_forms("аракы")["noun.p1"], "аракым")
        self.assertEqual(noun_forms("аракы")["noun.p3"], "аракысы")

    def test_pronoun_suppletive_replaces_paradigm(self):
        forms = gen.generate_noun_forms("мин", "front", EXC)
        self.assertEqual(
            forms,
            [
                ("noun.pron", "минем"),
                ("noun.pron", "миңа"),
                ("noun.pron", "мине"),
                ("noun.pron", "миндә"),
                ("noun.pron", "миннән"),
            ],
        )
        # No regular genitive *мингә or plural *минләр may leak in.
        self.assertNotIn("мингә", {form for _, form in gen.generate_all("мин", EXC)})
        self.assertNotIn("минләр", {form for _, form in gen.generate_all("мин", EXC)})

    def test_pronoun_bu_suppletive(self):
        forms = dict(gen.generate_noun_forms("бу", "back", EXC))
        # All five forms come from the table (labels collapse to noun.pron).
        self.assertEqual(len(forms), 1)
        self.assertEqual(
            [form for _, form in gen.generate_noun_forms("бу", "back", EXC)],
            ["моның", "моңа", "моны", "монда", "моннан"],
        )


class GoldenVerbParadigmTest(unittest.TestCase):
    def test_yaz_full_paradigm(self):
        # The finite grid matches the kaikki.org язу table cell for cell,
        # including the negative doublets kaikki lists under the same tags.
        self.assertEqual(
            gen.generate_verb_forms("яз", "back", EXC),
            [
                ("verb.imp.2sg", "яз"),
                ("verb.imp.2pl", "языгыз"),
                ("verb.imp.1pl", "языйк"),
                ("verb.imp.3sg", "язсын"),
                ("verb.imp.3pl", "язсыннар"),
                ("verb.pres.1sg", "язам"),
                ("verb.pres.1sg.q", "язаммы"),
                ("verb.pres.2sg", "язасың"),
                ("verb.pres.2sg.q", "язасыңмы"),
                ("verb.pres.3sg", "яза"),
                ("verb.pres.3sg.q", "язамы"),
                ("verb.pres.1pl", "язабыз"),
                ("verb.pres.1pl.q", "язабызмы"),
                ("verb.pres.2pl", "язасыз"),
                ("verb.pres.2pl.q", "язасызмы"),
                ("verb.pres.3pl", "язалар"),
                ("verb.pres.3pl.q", "язалармы"),
                ("verb.pres.neg.1sg", "язмыйм"),
                ("verb.pres.neg.1sg.q", "язмыйммы"),
                ("verb.pres.neg.2sg", "язмыйсың"),
                ("verb.pres.neg.2sg.q", "язмыйсыңмы"),
                ("verb.pres.neg.3sg", "язмый"),
                ("verb.pres.neg.3sg.q", "язмыймы"),
                ("verb.pres.neg.1pl", "язмыйбыз"),
                ("verb.pres.neg.1pl.q", "язмыйбызмы"),
                ("verb.pres.neg.2pl", "язмыйсыз"),
                ("verb.pres.neg.2pl.q", "язмыйсызмы"),
                ("verb.pres.neg.3pl", "язмыйлар"),
                ("verb.pres.neg.3pl.q", "язмыйлармы"),
                ("verb.past.1sg", "яздым"),
                ("verb.past.1sg.q", "яздыммы"),
                ("verb.past.2sg", "яздың"),
                ("verb.past.2sg.q", "яздыңмы"),
                ("verb.past.3sg", "язды"),
                ("verb.past.3sg.q", "яздымы"),
                ("verb.past.1pl", "яздык"),
                ("verb.past.1pl.q", "яздыкмы"),
                ("verb.past.2pl", "яздыгыз"),
                ("verb.past.2pl.q", "яздыгызмы"),
                ("verb.past.3pl", "яздылар"),
                ("verb.past.3pl.q", "яздылармы"),
                ("verb.past.neg.1sg", "язмадым"),
                ("verb.past.neg.1sg.q", "язмадыммы"),
                ("verb.past.neg.2sg", "язмадың"),
                ("verb.past.neg.2sg.q", "язмадыңмы"),
                ("verb.past.neg.3sg", "язмады"),
                ("verb.past.neg.3sg.q", "язмадымы"),
                ("verb.past.neg.1pl", "язмадык"),
                ("verb.past.neg.1pl.q", "язмадыкмы"),
                ("verb.past.neg.2pl", "язмадыгыз"),
                ("verb.past.neg.2pl.q", "язмадыгызмы"),
                ("verb.past.neg.3pl", "язмадылар"),
                ("verb.past.neg.3pl.q", "язмадылармы"),
                ("verb.rpast.1sg", "язганмын"),
                ("verb.rpast.1sg.q", "язганмынмы"),
                ("verb.rpast.2sg", "язгансың"),
                ("verb.rpast.2sg.q", "язгансыңмы"),
                ("verb.rpast.3sg", "язган"),
                ("verb.rpast.3sg.q", "язганмы"),
                ("verb.rpast.1pl", "язганбыз"),
                ("verb.rpast.1pl.q", "язганбызмы"),
                ("verb.rpast.2pl", "язгансыз"),
                ("verb.rpast.2pl.q", "язгансызмы"),
                ("verb.rpast.3pl", "язганнар"),
                ("verb.rpast.3pl.q", "язганнармы"),
                ("verb.rpast.neg.1sg", "язмаганмын"),
                ("verb.rpast.neg.1sg.q", "язмаганмынмы"),
                ("verb.rpast.neg.2sg", "язмагансың"),
                ("verb.rpast.neg.2sg.q", "язмагансыңмы"),
                ("verb.rpast.neg.3sg", "язмаган"),
                ("verb.rpast.neg.3sg.q", "язмаганмы"),
                ("verb.rpast.neg.1pl", "язмаганбыз"),
                ("verb.rpast.neg.1pl.q", "язмаганбызмы"),
                ("verb.rpast.neg.2pl", "язмагансыз"),
                ("verb.rpast.neg.2pl.q", "язмагансызмы"),
                ("verb.rpast.neg.3pl", "язмаганнар"),
                ("verb.rpast.neg.3pl.q", "язмаганнармы"),
                ("verb.fut.1sg", "язармын"),
                ("verb.fut.1sg.q", "язармынмы"),
                ("verb.fut.2sg", "язарсың"),
                ("verb.fut.2sg.q", "язарсыңмы"),
                ("verb.fut.3sg", "язар"),
                ("verb.fut.3sg.q", "язармы"),
                ("verb.fut.1pl", "язарбыз"),
                ("verb.fut.1pl.q", "язарбызмы"),
                ("verb.fut.2pl", "язарсыз"),
                ("verb.fut.2pl.q", "язарсызмы"),
                ("verb.fut.3pl", "язарлар"),
                ("verb.fut.3pl.q", "язарлармы"),
                ("verb.fut.neg.1sg", "язмасмын"),
                ("verb.fut.neg.1sg.q", "язмасмынмы"),
                ("verb.fut.neg.2sg", "язмассың"),
                ("verb.fut.neg.2sg.q", "язмассыңмы"),
                ("verb.fut.neg.3sg", "язмас"),
                ("verb.fut.neg.3sg.q", "язмасмы"),
                ("verb.fut.neg.1pl", "язмасбыз"),
                ("verb.fut.neg.1pl.q", "язмасбызмы"),
                ("verb.fut.neg.2pl", "язмассыз"),
                ("verb.fut.neg.2pl.q", "язмассызмы"),
                ("verb.fut.neg.3pl", "язмаслар"),
                ("verb.fut.neg.3pl.q", "язмаслармы"),
                ("verb.deffut.1sg", "язачакмын"),
                ("verb.deffut.1sg.q", "язачакмынмы"),
                ("verb.deffut.2sg", "язачаксың"),
                ("verb.deffut.2sg.q", "язачаксыңмы"),
                ("verb.deffut.3sg", "язачак"),
                ("verb.deffut.3sg.q", "язачакмы"),
                ("verb.deffut.1pl", "язачакбыз"),
                ("verb.deffut.1pl.q", "язачакбызмы"),
                ("verb.deffut.2pl", "язачаксыз"),
                ("verb.deffut.2pl.q", "язачаксызмы"),
                ("verb.deffut.3pl", "язачаклар"),
                ("verb.deffut.3pl.q", "язачаклармы"),
                ("verb.cond.1sg", "язсам"),
                ("verb.cond.1sg.q", "язсаммы"),
                ("verb.cond.2sg", "язсаң"),
                ("verb.cond.2sg.q", "язсаңмы"),
                ("verb.cond.3sg", "язса"),
                ("verb.cond.3sg.q", "язсамы"),
                ("verb.cond.1pl", "язсак"),
                ("verb.cond.1pl.q", "язсакмы"),
                ("verb.cond.2pl", "язсагыз"),
                ("verb.cond.2pl.q", "язсагызмы"),
                ("verb.cond.3pl", "язсалар"),
                ("verb.cond.3pl.q", "язсалармы"),
                ("verb.cond.neg.1sg", "язмасам"),
                ("verb.cond.neg.1sg.q", "язмасаммы"),
                ("verb.cond.neg.2sg", "язмасаң"),
                ("verb.cond.neg.2sg.q", "язмасаңмы"),
                ("verb.cond.neg.3sg", "язмаса"),
                ("verb.cond.neg.3sg.q", "язмасамы"),
                ("verb.cond.neg.1pl", "язмасак"),
                ("verb.cond.neg.1pl.q", "язмасакмы"),
                ("verb.cond.neg.2pl", "язмасагыз"),
                ("verb.cond.neg.2pl.q", "язмасагызмы"),
                ("verb.cond.neg.3pl", "язмасалар"),
                ("verb.cond.neg.3pl.q", "язмасалармы"),
                ("verb.ger.ip", "язып"),
                ("verb.ger.gac", "язгач"),
                ("verb.ger.ganci", "язганчы"),
                ("verb.ger.neg", "язмыйча"),
                ("verb.part.gan", "язган"),
                ("verb.part.uci", "язучы"),
                ("verb.part.asi", "язасы"),
                ("verb.masdar", "язу"),
                ("verb.intent", "язмакчы"),
            ],
        )

    def test_kit_voiceless(self):
        forms = verb_forms("кит", "front")
        self.assertEqual(forms["verb.past.3sg"], "китте")  # D→т
        self.assertEqual(forms["verb.rpast.3sg"], "киткән")  # G→к
        self.assertEqual(forms["verb.fut.3sg"], "китәр")
        self.assertEqual(forms["verb.ger.ip"], "китеп")
        self.assertEqual(forms["verb.ger.gac"], "киткәч")
        self.assertEqual(forms["verb.masdar"], "китү")
        self.assertEqual(forms["verb.pres.neg.3sg"], "китми")  # front -ми, not -мәй

    def test_eshla_vowel_stem(self):
        forms = verb_forms("эшлә", "front")
        self.assertEqual(forms["verb.pres.3sg"], "эшли")  # contraction
        self.assertEqual(forms["verb.pres.1sg"], "эшлим")
        self.assertEqual(forms["verb.pres.neg.3sg"], "эшләми")
        self.assertEqual(forms["verb.fut.3sg"], "эшләр")  # -р after vowel
        self.assertEqual(forms["verb.deffut.3sg"], "эшләячәк")  # -ячәк after vowel
        self.assertEqual(forms["verb.ger.ip"], "эшләп")  # -п after vowel
        self.assertEqual(forms["verb.ger.neg"], "эшләмичә")
        self.assertEqual(forms["verb.part.uci"], "эшләүче")
        self.assertEqual(forms["verb.masdar"], "эшләү")
        self.assertNotIn("verb.part.asi", forms)  # consonant stems only

    def test_uku_present_is_regular_negatives_suppletive(self):
        # The contraction rule covers the present (укы→укый); the future and
        # negative forms switch to the suppletive stem ук- (kaikki-attested).
        uku = verb_forms("укы", "back")
        self.assertEqual(uku["verb.pres.3sg"], "укый")
        self.assertEqual(uku["verb.past.3sg"], "укыды")
        self.assertEqual(uku["verb.masdar"], "уку")
        self.assertEqual(uku["verb.fut.3sg"], "укар")
        self.assertEqual(uku["verb.deffut.3sg"], "укачак")
        self.assertEqual(uku["verb.pres.neg.3sg"], "укмый")
        self.assertEqual(uku["verb.pres.neg.1sg"], "укмыйм")  # base re-derived
        self.assertEqual(uku["verb.fut.neg.3sg"], "укмас")
        self.assertEqual(uku["verb.past.neg.3sg"], "укмады")
        self.assertEqual(uku["verb.rpast.neg.3sg"], "укмаган")
        self.assertEqual(uku["verb.ger.neg"], "укмыйча")

    def test_diyu_is_regular(self):
        # дию needs no exception rows: stem ди contracts regularly; "диген" is a
        # misspelling of дигән (generated here), дип is regular too.
        diyu = verb_forms("ди", "front")
        self.assertEqual(diyu["verb.pres.3sg"], "ди")
        self.assertEqual(diyu["verb.rpast.3sg"], "дигән")
        self.assertEqual(diyu["verb.ger.ip"], "дип")
        self.assertEqual(diyu["verb.masdar"], "дию")
        self.assertEqual(diyu["verb.fut.3sg"], "дияр")  # monosyllabic vowel stem

    def test_future_allomorphy(self):
        # Verified against kaikki.org Tatar verb tables: -ар/-әр for
        # monosyllabic consonant stems, -ыр/-ер for longer ones, -р after
        # vowels, -яр for monosyllabic vowel stems.
        self.assertEqual(verb_forms("ат")["verb.fut.3sg"], "атар")
        self.assertEqual(verb_forms("кит", "front")["verb.fut.3sg"], "китәр")
        self.assertEqual(verb_forms("кер", "front")["verb.fut.3sg"], "керәр")
        self.assertEqual(verb_forms("үс", "front")["verb.fut.3sg"], "үсәр")
        self.assertEqual(verb_forms("әлсер", "front")["verb.fut.3sg"], "әлсерер")
        self.assertEqual(verb_forms("саклан")["verb.fut.3sg"], "сакланыр")
        self.assertEqual(verb_forms("сөйлә", "front")["verb.fut.3sg"], "сөйләр")
        self.assertEqual(verb_forms("ю", "back")["verb.fut.3sg"], "юяр")
        # The lexical -ыр/-ер monosyllable class comes from the exceptions table.
        for stem, form, harmony in (
            ("бар", "барыр", "back"), ("бир", "бирер", "front"),
            ("бел", "белер", "front"), ("бул", "булыр", "back"),
            ("ал", "алыр", "back"), ("әйт", "әйтер", "front"),
            ("кал", "калыр", "back"), ("кил", "килер", "front"),
            ("тор", "торыр", "back"), ("тул", "тулыр", "back"),
            ("йөр", "йөрер", "front"),
        ):
            self.assertEqual(verb_forms(stem, harmony)["verb.fut.3sg"], form, stem)
        for stem, wrong, harmony in (
            ("бир", "бирәр", "front"), ("бел", "беләр", "front"),
            ("әйт", "әйтәр", "front"), ("кил", "киләр", "front"),
            ("тор", "торар", "back"),
        ):
            self.assertNotIn(wrong, {form for _, form in gen.generate_all(stem, EXC)})

    def test_masdar_glide_spelling(self):
        # -у/-ү after consonants and а/ә-stems; the -ю glide spelling after
        # ы/и/у/ү (җыю, дию, сию, сую); ю→юу from the exceptions table.
        self.assertEqual(verb_forms("яз")["verb.masdar"], "язу")
        self.assertEqual(verb_forms("эшлә", "front")["verb.masdar"], "эшләү")
        self.assertEqual(verb_forms("җы")["verb.masdar"], "җыю")
        self.assertEqual(verb_forms("ди", "front")["verb.masdar"], "дию")
        self.assertEqual(verb_forms("си", "front")["verb.masdar"], "сию")
        self.assertEqual(verb_forms("су")["verb.masdar"], "сую")
        self.assertEqual(verb_forms("ю", "back")["verb.masdar"], "юу")
        self.assertEqual(verb_forms("укы")["verb.masdar"], "уку")

    def test_bulu_regular_consonant_stem(self):
        forms = verb_forms("бул", "back")
        self.assertEqual(forms["verb.pres.3sg"], "була")
        self.assertEqual(forms["verb.deffut.3sg"], "булачак")
        self.assertEqual(forms["verb.masdar"], "булу")
        self.assertEqual(forms["verb.ger.ip"], "булып")

    def test_kara_present_uses_regular_contraction(self):
        forms = verb_forms("кара", "back")
        self.assertEqual(forms["verb.pres.3sg"], "карый")
        self.assertEqual(forms["verb.pres.1sg"], "карыйм")
        self.assertEqual(forms["verb.pres.2sg"], "карыйсың")
        self.assertEqual(forms["verb.pres.1pl"], "карыйбыз")
        self.assertEqual(forms["verb.pres.2pl"], "карыйсыз")
        self.assertEqual(forms["verb.pres.3pl"], "карыйлар")
        present = {
            form for label, form in gen.generate_verb_forms("кара", "back", EXC)
            if label.startswith("verb.pres.")
        }
        for wrong in ("карам", "карасың", "карасыз", "каралар"):
            self.assertNotIn(wrong, present)
        self.assertEqual(forms["verb.imp.2sg"], "кара")

    def test_stale_override_fails_closed(self):
        # verb.part.asi is never emitted for a vowel-final stem, so an override
        # naming it for эшлә is a stale table row — a hard error.
        bad = gen.Exceptions({}, {}, {}, {("эшлә", "verb.part.asi"): "эшләәсе"})
        with self.assertRaises(gen.WordformError):
            gen.generate_all("эшлә", bad)
        # A pronoun stem emits no regular noun labels, so a noun override on
        # мин is a stale row too.
        bad2 = gen.Exceptions({}, {}, {"мин": ("минем",)}, {("мин", "noun.p3"): "мине"})
        with self.assertRaises(gen.WordformError):
            gen.generate_all("мин", bad2)

    def test_no_harmony_stem_fails_closed(self):
        with self.assertRaises(gen.WordformError):
            gen.generate_all("б")


class ExternalMorphologyRegressionTest(unittest.TestCase):
    """Normative surface forms from SART, UD Tatar-NMCTT and the analyzer."""

    def assert_verb(self, stem: str, harmony: str, expected: dict[str, str]):
        forms = verb_forms(stem, harmony)
        for label, form in expected.items():
            self.assertEqual(forms[label], form, f"{stem} {label}")

    def test_final_consonant_voicing(self):
        self.assertEqual(noun_forms("башлык")["noun.p3"], "башлыгы")
        self.assertEqual(noun_forms("тәртип", "front")["noun.p3.acc"], "тәртибен")
        self.assertEqual(noun_forms("көньяк")["noun.p3.loc"], "көньягында")
        self.assert_verb("тап", "back", {
            "verb.pres.3pl": "табалар",
            "verb.ger.ip": "табып",
            "verb.masdar": "табу",
            "verb.deffut.3sg": "табачак",
            "verb.part.asi": "табасы",
        })
        self.assert_verb("күнек", "front", {
            "verb.pres.3sg": "күнегә",
            "verb.fut.3sg": "күнегер",
            "verb.ger.ip": "күнегеп",
            "verb.masdar": "күнегү",
        })
        for stem, harmony, expected in (
            ("нык", "back", "ныграк"), ("озак", "back", "озаграк"),
            ("ерак", "back", "ераграк"), ("күп", "front", "күбрәк"),
            ("сирәк", "front", "сирәгрәк"),
        ):
            self.assertEqual(
                dict(gen.generate_deriv_forms(stem, harmony))["deriv.rak"], expected, stem
            )
        plain = verb_forms("тап")
        self.assertEqual(plain["verb.past.3sg"], "тапты")
        self.assertEqual(plain["verb.imp.1pl"], "тапыйк")
        self.assertEqual(plain["verb.fut.3sg"], "тапар")
        self.assertEqual(noun_forms("тап")["noun.dat"], "тапка")
        self.assertEqual(noun_forms("халк")["noun.p3"], "халкы")
        generated = {form for _, form in gen.generate_all("тап", EXC)}
        for wrong in ("тапып", "тапалар", "тапу", "тапачак"):
            self.assertNotIn(wrong, generated)

    def test_first_person_plural_present_and_past(self):
        self.assert_verb("бар", "back", {
            "verb.pres.1pl": "барабыз",
            "verb.pres.1pl.q": "барабызмы",
            "verb.pres.neg.1pl": "бармыйбыз",
            "verb.past.1pl": "бардык",
            "verb.past.1pl.q": "бардыкмы",
            "verb.past.neg.1pl": "бармадык",
        })
        self.assert_verb("кил", "front", {
            "verb.pres.1pl": "киләбез",
            "verb.past.1pl": "килдек",
        })
        self.assertEqual(verb_forms("өметлән", "front")["verb.pres.1pl"], "өметләнәбез")
        self.assertEqual(verb_forms("исбатла")["verb.past.1pl"], "исбатладык")
        self.assertEqual(verb_forms("үткәр", "front")["verb.past.1pl"], "үткәрдек")
        self.assertIn("verb.pres.1pl", gen.KNOWN_LABELS)
        self.assertIn("verb.past.1pl", gen.KNOWN_LABELS)

    def test_one_syllable_glide_stems(self):
        self.assert_verb("куй", "back", {
            "verb.pres.1sg": "куям",
            "verb.pres.2sg": "куясың",
            "verb.pres.3sg": "куя",
            "verb.pres.1pl": "куябыз",
            "verb.pres.2pl": "куясыз",
            "verb.pres.3pl": "куялар",
            "verb.fut.3sg": "куяр",
            "verb.deffut.3sg": "куячак",
            "verb.ger.ip": "куеп",
            "verb.masdar": "кую",
        })
        self.assert_verb("җый", "back", {
            "verb.pres.3sg": "җыя",
            "verb.pres.1pl": "җыябыз",
            "verb.fut.3sg": "җыяр",
            "verb.deffut.3sg": "җыячак",
            "verb.ger.ip": "җыеп",
            "verb.masdar": "җыю",
        })
        generated = {form for _, form in gen.generate_all("куй", EXC)}
        for wrong in ("куйа", "куйасың", "куйалар", "куйячак", "куйып", "куйу"):
            self.assertNotIn(wrong, generated)
        self.assert_verb("сөй", "front", {
            "verb.pres.3sg": "сөя",
            "verb.ger.ip": "сөеп",
            "verb.masdar": "сөю",
        })

    def test_lexical_back_harmony(self):
        expected = {
            "министр": {"noun.p3": "министры"},
            "президент": {"noun.p3": "президенты", "noun.p3.dat": "президентына"},
            "метр": {"noun.dat": "метрга"},
            "шрифт": {"noun.pl.dat": "шрифтларга"},
            "ел": {
                "noun.gen": "елның", "noun.acc": "елны", "noun.loc": "елда",
                "noun.p3": "елы",
            },
            "еш": {"deriv.rak": "ешрак"},
        }
        for stem, cells in expected.items():
            rows = gen.generate_all(stem, EXC)
            for label, form in cells.items():
                self.assertIn(form, {f for lab, f in rows if lab == label}, f"{stem} {label}")
        generated_el = {form for _, form in gen.generate_all("ел", EXC)}
        for wrong in ("елнең", "елне", "елдә", "еле"):
            self.assertNotIn(wrong, generated_el)
        generated_loans = {form for stem in ("министр", "президент")
                           for _, form in gen.generate_all(stem, EXC)}
        self.assertNotIn("министре", generated_loans)
        self.assertNotIn("президенте", generated_loans)
        self.assertNotIn("ешрәк", {form for _, form in gen.generate_all("еш", EXC)})
        self.assertEqual(noun_forms("исем", "front")["noun.gen"], "исемнең")

    def test_soft_sign_stem_spelling(self):
        expected = {
            "автомобиль": {"noun.p3": "автомобиле"},
            "вазгыять": {"noun.p3": "вазгыяте"},
            "медаль": {"noun.p3.acc": "медален"},
            "мөрәҗәгать": {"noun.p3": "мөрәҗәгате"},
            "сентябрь": {"noun.p3.loc": "сентябрендә"},
        }
        for stem, cells in expected.items():
            rows = gen.generate_all(stem, EXC)
            for label, form in cells.items():
                self.assertIn(form, {f for lab, f in rows if lab == label}, f"{stem} {label}")
        generated = {form for _, form in gen.generate_all("автомобиль", EXC)}
        self.assertNotIn("автомобилье", generated)
        self.assertNotIn("автомобильы", generated)
        self.assertIn("автомобильне", generated)

    def test_soft_sign_assimilation(self):
        forms = noun_forms("җәмгыять", "front")
        self.assertEqual(forms["noun.dat"], "җәмгыятькә")
        self.assertEqual(forms["noun.loc"], "җәмгыятьтә")
        self.assertEqual(forms["noun.abl"], "җәмгыятьтән")
        generated = {form for _, form in gen.generate_all("җәмгыять", EXC)}
        for wrong in ("җәмгыятьгә", "җәмгыятьдә", "җәмгыятьдән"):
            self.assertNotIn(wrong, generated)

    def test_syncopated_possessive_base(self):
        forms = noun_forms("халык")
        self.assertEqual(forms["noun.p1"], "халкым")
        self.assertEqual(forms["noun.p2"], "халкың")
        self.assertEqual(forms["noun.p3"], "халкы")
        self.assertEqual(forms["noun.p3.acc"], "халкын")
        self.assertEqual(forms["noun.pl"], "халыклар")
        self.assertEqual(forms["noun.dat"], "халыкка")
        generated = {form for _, form in gen.generate_all("халык", EXC)}
        for wrong in ("халыкым", "халыкың", "халыкы", "халыкын"):
            self.assertNotIn(wrong, generated)

    def test_ay_3sg_possessive_contraction(self):
        forms = noun_forms("малай")
        self.assertEqual(forms["noun.p1"], "малайым")
        self.assertEqual(forms["noun.p2"], "малайың")
        self.assertEqual(forms["noun.p3"], "малае")
        self.assertEqual(forms["noun.p3.acc"], "малаен")
        generated = {form for _, form in gen.generate_all("малай", EXC)}
        self.assertNotIn("малайы", generated)
        self.assertEqual(noun_forms("су")["noun.p3"], "суы")

    def test_u_final_converb(self):
        self.assertEqual(verb_forms("ту")["verb.ger.ip"], "туып")
        self.assertNotIn("туп", {form for _, form in gen.generate_all("ту", EXC)})
        self.assertEqual(verb_forms("ди", "front")["verb.ger.ip"], "дип")
        self.assertEqual(verb_forms("эшлә", "front")["verb.ger.ip"], "эшләп")
        self.assertEqual(verb_forms("яз")["verb.ger.ip"], "язып")


class ImperativePersonsTest(unittest.TestCase):
    """1pl/3sg/3pl imperatives; kaikki.org: языйк, китик, эшлик, язсыннар."""

    def test_consonant_stems(self):
        self.assertEqual(verb_forms("яз")["verb.imp.1pl"], "языйк")
        self.assertEqual(verb_forms("яз")["verb.imp.3sg"], "язсын")
        self.assertEqual(verb_forms("яз")["verb.imp.3pl"], "язсыннар")
        kit = verb_forms("кит", "front")
        self.assertEqual(kit["verb.imp.1pl"], "китик")
        self.assertEqual(kit["verb.imp.3sg"], "китсен")
        self.assertEqual(kit["verb.imp.3pl"], "китсеннәр")

    def test_vowel_stems_contract(self):
        # Like the present, the final vowel contracts before -ыйк/-ик.
        eshla = verb_forms("эшлә", "front")
        self.assertEqual(eshla["verb.imp.1pl"], "эшлик")
        self.assertEqual(eshla["verb.imp.3sg"], "эшләсен")
        self.assertEqual(eshla["verb.imp.3pl"], "эшләсеннәр")
        self.assertEqual(verb_forms("укы")["verb.imp.1pl"], "укыйк")
        self.assertEqual(verb_forms("кара")["verb.imp.1pl"], "карыйк")
        self.assertEqual(verb_forms("ди", "front")["verb.imp.1pl"], "дик")

    def test_bare_vowel_stem_keeps_glide(self):
        # ю has no consonant to carry the ending: юйык, not *юыйк.
        yu = verb_forms("ю", "back")
        self.assertEqual(yu["verb.imp.1pl"], "юйык")
        self.assertEqual(yu["verb.imp.3sg"], "юсын")
        self.assertEqual(yu["verb.imp.3pl"], "юсыннар")


class ExtendedPersonTensesTest(unittest.TestCase):
    """Person rows of fut/cond/deffut/rpast; kaikki.org язу/китү tables."""

    def test_future_persons(self):
        fut = {p: verb_forms("яз")[f"verb.fut.{p}"] for p in
               ("1sg", "2sg", "3sg", "1pl", "2pl", "3pl")}
        self.assertEqual(
            fut,
            {"1sg": "язармын", "2sg": "язарсың", "3sg": "язар",
             "1pl": "язарбыз", "2pl": "язарсыз", "3pl": "язарлар"},
        )
        kit = {p: verb_forms("кит", "front")[f"verb.fut.{p}"] for p in
               ("1sg", "2sg", "3sg", "1pl", "2pl", "3pl")}
        self.assertEqual(
            kit,
            {"1sg": "китәрмен", "2sg": "китәрсең", "3sg": "китәр",
             "1pl": "китәрбез", "2pl": "китәрсез", "3pl": "китәрләр"},
        )

    def test_conditional_persons(self):
        # The conditional has its own set: 1pl -к, 2pl -гыз (язсак, язсагыз).
        cond = {p: verb_forms("яз")[f"verb.cond.{p}"] for p in
                ("1sg", "2sg", "3sg", "1pl", "2pl", "3pl")}
        self.assertEqual(
            cond,
            {"1sg": "язсам", "2sg": "язсаң", "3sg": "язса",
             "1pl": "язсак", "2pl": "язсагыз", "3pl": "язсалар"},
        )
        kit = {p: verb_forms("кит", "front")[f"verb.cond.{p}"] for p in
               ("1sg", "2sg", "3sg", "1pl", "2pl", "3pl")}
        self.assertEqual(
            kit,
            {"1sg": "китсәм", "2sg": "китсәң", "3sg": "китсә",
             "1pl": "китсәк", "2pl": "китсәгез", "3pl": "китсәләр"},
        )

    def test_deffut_and_rpast_persons(self):
        self.assertEqual(verb_forms("яз")["verb.deffut.1sg"], "язачакмын")
        self.assertEqual(verb_forms("яз")["verb.deffut.1pl"], "язачакбыз")
        self.assertEqual(verb_forms("кит", "front")["verb.deffut.2sg"], "китәчәксең")
        rpast = {p: verb_forms("яз")[f"verb.rpast.{p}"] for p in
                 ("1sg", "2sg", "3sg", "1pl", "2pl", "3pl")}
        self.assertEqual(
            rpast,
            {"1sg": "язганмын", "2sg": "язгансың", "3sg": "язган",
             "1pl": "язганбыз", "2pl": "язгансыз", "3pl": "язганнар"},
        )
        # 3pl assimilates after the н-final -ган base (киткәннәр).
        self.assertEqual(verb_forms("кит", "front")["verb.rpast.3pl"], "киткәннәр")
        self.assertEqual(verb_forms("кит", "front")["verb.rpast.1sg"], "киткәнмен")

    def test_negative_persons(self):
        self.assertEqual(verb_forms("яз")["verb.fut.neg.1sg"], "язмасмын")
        self.assertEqual(verb_forms("яз")["verb.fut.neg.3pl"], "язмаслар")
        self.assertEqual(verb_forms("яз")["verb.cond.neg.1pl"], "язмасак")
        self.assertEqual(verb_forms("яз")["verb.rpast.neg.2pl"], "язмагансыз")
        self.assertEqual(verb_forms("кит", "front")["verb.fut.neg.2sg"], "китмәссең")
        self.assertEqual(verb_forms("кит", "front")["verb.cond.neg.3pl"], "китмәсәләр")
        self.assertEqual(verb_forms("эшлә", "front")["verb.fut.1pl"], "эшләрбез")
        self.assertEqual(verb_forms("эшлә", "front")["verb.cond.1sg"], "эшләсәм")

    def test_3sg_override_rederives_persons(self):
        # The lexical future of бар is an exception row; its persons follow.
        bar = verb_forms("бар")
        self.assertEqual(bar["verb.fut.1sg"], "барырмын")
        self.assertEqual(bar["verb.fut.2sg"], "барырсың")
        self.assertEqual(bar["verb.fut.1pl"], "барырбыз")
        self.assertEqual(bar["verb.fut.3pl"], "барырлар")
        # The suppletive укы cluster drives every new family too (kaikki:
        # укармын, укачаксыз, укмасбыз, укмаганнар).
        uku = verb_forms("укы")
        self.assertEqual(uku["verb.fut.1sg"], "укармын")
        self.assertEqual(uku["verb.deffut.2pl"], "укачаксыз")
        self.assertEqual(uku["verb.fut.neg.1pl"], "укмасбыз")
        self.assertEqual(uku["verb.rpast.neg.3pl"], "укмаганнар")


class NominalPredicativeTest(unittest.TestCase):
    """Nominal/adjective predicative persons: татармын, өйбез, сумын."""

    def test_person_grid(self):
        su = noun_forms("су")
        self.assertEqual(
            {p: su[f"noun.pred.{p}"] for p in ("1sg", "2sg", "3sg", "1pl", "2pl", "3pl")},
            {"1sg": "сумын", "2sg": "сусың", "3sg": "су",
             "1pl": "субыз", "2pl": "сусыз", "3pl": "сулар"},
        )
        self.assertEqual(noun_forms("өй", "front")["noun.pred.1pl"], "өйбез")
        self.assertEqual(noun_forms("өй", "front")["noun.pred.2sg"], "өйсең")

    def test_predicative_uses_plain_stem(self):
        # Predicative endings start with a consonant: китапмын, not *китабымын.
        self.assertEqual(noun_forms("китап")["noun.pred.1sg"], "китапмын")

    def test_pronouns_have_no_predicative(self):
        labels = {label for label, _ in gen.generate_noun_forms("мин", "front", EXC)}
        self.assertFalse(any(label.startswith("noun.pred") for label in labels))

    def test_override_replaces_the_generated_person(self):
        # noun.pred rows are emitted after _apply_overrides ran, so an explicit
        # override row replaces the generated form; the question clitic stacks
        # on the overridden base, and a .q-specific override wins outright.
        exc = gen.Exceptions({}, {}, {}, {("татар", "noun.pred.1sg"): "татарымын"})
        forms = dict(gen.generate_noun_forms("татар", "back", exc))
        self.assertEqual(forms["noun.pred.1sg"], "татарымын")
        self.assertEqual(forms["noun.pred.1sg.q"], "татарымынмы")
        self.assertEqual(forms["noun.pred.2sg"], "татарсың")  # untouched row
        exc_q = gen.Exceptions({}, {}, {}, {("татар", "noun.pred.3sg.q"): "татарлармы"})
        forms_q = dict(gen.generate_noun_forms("татар", "back", exc_q))
        self.assertEqual(forms_q["noun.pred.3sg.q"], "татарлармы")
        self.assertEqual(forms_q["noun.pred.3sg"], "татар")

    def test_bare_3sg_is_not_a_candidate_row(self):
        rows, _stats = gen.build_candidates(["татар"], gen.EMPTY_EXCEPTIONS)
        self.assertNotIn(("татар", "татар", "noun.pred.3sg"), set(rows))
        self.assertIn(("татармы", "татар", "noun.pred.3sg.q"), set(rows))


class QuestionCliticTest(unittest.TestCase):
    """The -мы/-ме clitic over person forms and nominal predicates."""

    def test_over_verb_persons(self):
        forms = verb_forms("яз")
        self.assertEqual(forms["verb.pres.1sg.q"], "язаммы")
        self.assertEqual(forms["verb.pres.3sg.q"], "язамы")
        self.assertEqual(forms["verb.past.3pl.q"], "яздылармы")
        self.assertEqual(forms["verb.rpast.3sg.q"], "язганмы")
        self.assertEqual(forms["verb.fut.3sg.q"], "язармы")
        self.assertEqual(forms["verb.cond.1pl.q"], "язсакмы")
        self.assertEqual(forms["verb.deffut.3sg.q"], "язачакмы")

    def test_harmony_follows_the_person_form(self):
        kit = verb_forms("кит", "front")
        self.assertEqual(kit["verb.past.3sg.q"], "киттеме")
        self.assertEqual(kit["verb.fut.2sg.q"], "китәрсеңме")
        self.assertEqual(kit["verb.rpast.3pl.q"], "киткәннәрме")
        self.assertEqual(verb_forms("эшлә", "front")["verb.pres.3sg.q"], "эшлиме")

    def test_over_nominal_predicates(self):
        self.assertEqual(noun_forms("татар")["noun.pred.1sg.q"], "татармынмы")
        self.assertEqual(noun_forms("татар")["noun.pred.3sg.q"], "татармы")
        self.assertEqual(noun_forms("су")["noun.pred.3sg.q"], "сумы")
        self.assertEqual(noun_forms("өй", "front")["noun.pred.1pl.q"], "өйбезме")

    def test_over_overridden_bases(self):
        self.assertEqual(verb_forms("кара")["verb.pres.3sg.q"], "карыймы")
        self.assertEqual(verb_forms("укы")["verb.pres.neg.1sg.q"], "укмыйммы")
        self.assertEqual(verb_forms("бар")["verb.fut.1sg.q"], "барырмынмы")

    def test_no_clitic_on_imperatives_or_nonfinite(self):
        labels = {label for label, _ in gen.generate_verb_forms("яз", "back", EXC)}
        for label in labels:
            if label.endswith(".q"):
                self.assertNotIn("imp", label)
                self.assertNotIn("ger", label)
                self.assertNotIn("part", label)
        self.assertIn("verb.imp.2sg", labels)  # imperative rows exist, minus .q

    def test_question_labels_are_known(self):
        self.assertIn("verb.pres.1sg.q", gen.KNOWN_LABELS)
        self.assertIn("noun.pred.1sg.q", gen.KNOWN_LABELS)
        self.assertEqual(gen.label_family("verb.pres.1sg.q"), "verb.q")
        self.assertEqual(gen.label_family("noun.pred.1sg.q"), "noun.pred.q")
        self.assertEqual(gen.label_family("noun.pl"), "noun")
        self.assertEqual(gen.label_family("deriv.ca"), "deriv")


class GoldenDerivTest(unittest.TestCase):
    def test_derivational_set(self):
        dus = dict(gen.generate_deriv_forms("дус", "back"))
        self.assertEqual(dus["deriv.lik"], "дуслык")
        self.assertEqual(dus["deriv.ca"], "дусча")
        momkin = dict(gen.generate_deriv_forms("мөмкин", "front"))
        self.assertEqual(momkin["deriv.lik"], "мөмкинлек")
        self.assertEqual(dict(gen.generate_deriv_forms("татар", "back"))["deriv.ca"], "татарча")

    def test_das_assimilation(self):
        self.assertEqual(dict(gen.generate_deriv_forms("юл", "back"))["deriv.das"], "юлдаш")
        self.assertEqual(dict(gen.generate_deriv_forms("дус", "back"))["deriv.das"], "дусташ")


class ExceptionsLoadingTest(unittest.TestCase):
    """Every malformed row shape is a hard error, never a skipped line."""

    def load_text(self, text: str):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "exc.tsv"
            path.write_text(text, encoding="utf-8")
            return gen.load_exceptions(path)

    def test_valid_minimal_file(self):
        exc = self.load_text(
            "# comment\n\nharmony\tел\tback\npossessive\tхалык\tхалк\n"
        )
        self.assertEqual(exc.harmonies, {"ел": "back"})
        self.assertEqual(exc.possessive_bases, {"халык": "халк"})

    def test_unknown_kind(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("magic\tел\tback\n")

    def test_wrong_field_count(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("harmony\tел\n")
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("possessive\tхалык\tхалк\textra\n")

    def test_bad_harmony_and_possessive_base(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("harmony\tел\tsideways\n")
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("possessive\tхалык\tхалык\n")

    def test_unknown_override_label(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("override\tяз\tnoun.genus=язган\n")

    def test_malformed_override_payload(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("override\tяз\tverb.masdar\n")  # no '='

    def test_word_outside_alphabet(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("harmony\tel\tback\n")  # Latin stem
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("override\tяз\tverb.masdar=Язу\n")  # not lowercase

    def test_duplicate_rows(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("harmony\tел\tback\nharmony\tел\tback\n")
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("pronoun\tмин\tминем\tмиңа\tминем\n")  # duplicate form

    def test_cr_rejected(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("harmony\tел\tback\r\n")

    def test_missing_file(self):
        with self.assertRaises(gen.ExceptionsError):
            gen.load_exceptions(ROOT / "scripts" / "no-such-exceptions.tsv")


WORD_LIST = [
    "татар", "өй", "китап", "су", "яз", "кит", "эшлә", "мин", "дус",
    "мөмкин", "совет", "урман", "бала",
]


class CandidatesTest(unittest.TestCase):
    def test_expected_rows_present(self):
        rows, stats = gen.build_candidates(WORD_LIST, EXC)
        row_set = set(rows)
        expected = {
            ("татарлар", "татар", "noun.pl"),
            ("татарча", "татар", "deriv.ca"),
            ("урманнар", "урман", "noun.pl"),
            ("өйләр", "өй", "noun.pl"),
            ("китапка", "китап", "noun.dat"),
            ("китабы", "китап", "noun.p3"),
            ("суны", "су", "noun.acc"),
            ("суы", "су", "noun.p3"),
            ("язды", "яз", "verb.past.3sg"),
            ("язган", "яз", "verb.rpast.3sg"),
            ("язар", "яз", "verb.fut.3sg"),
            ("язып", "яз", "verb.ger.ip"),
            ("язу", "яз", "verb.masdar"),
            ("китте", "кит", "verb.past.3sg"),
            ("киткән", "кит", "verb.rpast.3sg"),
            ("китәр", "кит", "verb.fut.3sg"),
            ("эшли", "эшлә", "verb.pres.3sg"),
            ("минем", "мин", "noun.pron"),
            ("миңа", "мин", "noun.pron"),
            ("дуслык", "дус", "deriv.lik"),
            ("дусча", "дус", "deriv.ca"),
            ("мөмкинлек", "мөмкин", "deriv.lik"),
            # Dual-harmony loan: both variants are candidates; the attestation
            # stage keeps the attested one (советларга).
            ("советларга", "совет", "noun.pl.dat"),
            ("советләргә", "совет", "noun.pl.dat"),
            # The newer families: fut/cond/deffut/rpast persons, 1pl/3rd-person
            # imperatives, nominal predicates, the question-clitic layer.
            ("язармын", "яз", "verb.fut.1sg"),
            ("язарбыз", "яз", "verb.fut.1pl"),
            ("китәрсез", "кит", "verb.fut.2pl"),
            ("язсак", "яз", "verb.cond.1pl"),
            ("язачаксың", "яз", "verb.deffut.2sg"),
            ("язгансың", "яз", "verb.rpast.2sg"),
            ("язганнар", "яз", "verb.rpast.3pl"),
            ("языйк", "яз", "verb.imp.1pl"),
            ("язсыннар", "яз", "verb.imp.3pl"),
            ("эшлик", "эшлә", "verb.imp.1pl"),
            ("татармын", "татар", "noun.pred.1sg"),
            ("өйбез", "өй", "noun.pred.1pl"),
            ("язаммы", "яз", "verb.pres.1sg.q"),
            ("эшлиме", "эшлә", "verb.pres.3sg.q"),
            ("татармы", "татар", "noun.pred.3sg.q"),
            ("сумы", "су", "noun.pred.3sg.q"),
        }
        self.assertTrue(expected <= row_set, expected - row_set)
        # The bare stem itself is never a candidate row.
        self.assertNotIn(("яз", "яз", "verb.imp.2sg"), row_set)
        self.assertNotIn(("татар", "татар", "noun.pred.3sg"), row_set)
        self.assertNotIn("минләр", {form for form, _, _ in rows})
        self.assertEqual(stats["stems"], len(WORD_LIST))

    def test_family_counts(self):
        _rows, stats = gen.build_candidates(WORD_LIST, EXC)
        families = stats["candidate_rows_by_family"]
        self.assertEqual(sum(families.values()), stats["candidate_rows"])
        self.assertEqual(
            families,
            {
                "deriv": 105,
                "noun": 299,
                "noun.pred": 70,
                "noun.pred.q": 82,
                "verb": 1182,
                "verb.q": 990,
            },
        )

    def test_rows_sorted_and_unique(self):
        rows, _stats = gen.build_candidates(WORD_LIST, EXC)
        self.assertEqual(rows, sorted(rows))
        self.assertEqual(len(rows), len(set(rows)))

    def test_determinism(self):
        first, _ = gen.build_candidates(WORD_LIST, EXC)
        second, _ = gen.build_candidates(WORD_LIST, EXC)
        self.assertEqual(gen.render_rows(first), gen.render_rows(second))


class GroupTest(unittest.TestCase):
    def test_groups(self):
        words = WORD_LIST + [
            "татарлар", "татарның", "татарча", "язды", "язган", "эшләде",
            "өйләр", "китаплар", "суны", "татармын", "языйк", "язарсың",
            "язаммы",
        ]
        rows, stats = gen.build_groups(words, EXC)
        groups = {(stem, form): label for stem, form, label in rows}
        self.assertEqual(groups[("татар", "татарлар")], "noun.pl")
        self.assertEqual(groups[("татар", "татарның")], "noun.gen")
        self.assertEqual(groups[("татар", "татарча")], "deriv.ca")
        self.assertEqual(groups[("яз", "язды")], "verb.past.3sg")
        self.assertEqual(groups[("яз", "язган")], "verb.rpast.3sg")
        self.assertEqual(groups[("эшлә", "эшләде")], "verb.past.3sg")
        self.assertEqual(groups[("өй", "өйләр")], "noun.pl")
        self.assertEqual(groups[("су", "суны")], "noun.acc")
        # The newer families strip and round-trip too.
        self.assertEqual(groups[("татар", "татармын")], "noun.pred.1sg")
        self.assertEqual(groups[("яз", "языйк")], "verb.imp.1pl")
        self.assertEqual(groups[("яз", "язарсың")], "verb.fut.2sg")
        self.assertEqual(groups[("яз", "язаммы")], "verb.pres.1sg.q")
        self.assertEqual(stats["grouped_forms"], len(rows))
        self.assertEqual(sum(stats["grouped_forms_by_family"].values()), len(rows))

    def test_ambiguous_strips_skipped(self):
        # язам analyzes as яза+м (noun.p1 of яза) and as яз+ам
        # (verb.pres.1sg of яз); both stems listed → ambiguous, skipped.
        # яза itself groups unambiguously under яз (verb.pres.3sg).
        words = ["яз", "яза", "язам"]
        rows, stats = gen.build_groups(words, EXC)
        self.assertEqual(rows, [("яз", "яза", "verb.pres.3sg")])
        self.assertEqual(stats["ambiguous_skipped"], 1)
        self.assertNotIn("язам", {form for _, form, _ in rows})

    def test_roundtrip_required(self):
        # китапны strips to китап only because the generator reproduces it;
        # a listed prefix that does not round-trip must not become a stem.
        words = ["китап", "китапны"]
        rows, _stats = gen.build_groups(words, EXC)
        self.assertEqual(rows, [("китап", "китапны", "noun.acc")])

    def test_determinism(self):
        words = WORD_LIST + ["татарлар", "язды", "өйләр"]
        first, _ = gen.build_groups(words, EXC)
        second, _ = gen.build_groups(words, EXC)
        self.assertEqual(gen.render_rows(first), gen.render_rows(second))


class CliTest(unittest.TestCase):
    def run_cli(self, *argv: str) -> int:
        return gen.main(list(argv))

    def test_paradigm_prints_full_paradigm(self):
        # Smoke: exit 0 and sane output for two probe stems.
        import contextlib
        import io

        for stem in ("татар", "китап"):
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                self.assertEqual(self.run_cli("paradigm", stem), 0)
            lines = buffer.getvalue().splitlines()
            forms = {line.split("\t")[1] for line in lines if not line.startswith("#")}
            self.assertIn(stem + "лар", forms)
            self.assertTrue(all("\t" in line for line in lines if not line.startswith("#")))

    def test_paradigm_rejects_bad_stem(self):
        import contextlib
        import io

        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(self.run_cli("paradigm", "kitap"), 2)  # Latin
            self.assertEqual(self.run_cli("paradigm", "б"), 2)  # no harmony vowel

    def test_candidates_writes_atomically_and_deterministically(self):
        with tempfile.TemporaryDirectory() as tmp:
            words = Path(tmp) / "words.tsv"
            words.write_text("\n".join(WORD_LIST) + "\n", encoding="utf-8")
            out1, out2 = Path(tmp) / "out1.tsv", Path(tmp) / "out2.tsv"
            import contextlib
            import io

            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(
                    self.run_cli(
                        "candidates", "--words", str(words),
                        "--exceptions", str(EXCEPTIONS_FILE), "--out", str(out1),
                    ),
                    0,
                )
                self.assertEqual(
                    self.run_cli(
                        "candidates", "--words", str(words),
                        "--exceptions", str(EXCEPTIONS_FILE), "--out", str(out2),
                    ),
                    0,
                )
            self.assertEqual(out1.read_bytes(), out2.read_bytes())
            lines = out1.read_text(encoding="utf-8").splitlines()
            self.assertIn("татарлар\tтатар\tnoun.pl", lines)
            self.assertEqual(lines, sorted(lines))


if __name__ == "__main__":
    unittest.main()
