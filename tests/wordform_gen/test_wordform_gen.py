#!/usr/bin/env python3
"""Contract tests for the TT-SUGGESTIONS phase-P1 Tatar word-form generator.

What is pinned and why:

* Hand-written golden paradigms (our own data, no license issues) for the
  harmony/assimilation classes that matter: back/front, vowel/consonant-final,
  voiceless and nasal finals, the voicing exception (китап→китабы), the 3sg
  possessive override (су→суы), suppletive pronouns, vowel-stem present
  contraction (эшлә→эшли) and the derivational set (дуслык, мөмкинлек).
* The committed exceptions table (scripts/wordform_exceptions_tat.tsv) is
  pinned by byte SHA-256 and by row counts: editing the tables is a conscious
  act that must re-pin the tests, exactly like the dictionary asset pins.
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

# Pinned at P1 creation (2026-09-19). Changing the exceptions table changes
# the generated candidate forms; re-pin in the same commit.
EXPECTED_EXCEPTIONS_SHA256 = (
    "715020c2a80b3383789e6f74396a3ae36bdfe524cda613dbb84558b8226d663e"
)
EXPECTED_VOICING_ROWS = 3
EXPECTED_PRONOUN_ROWS = 7
EXPECTED_OVERRIDE_ROWS = 16


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
        self.assertEqual(len(EXC.voicing), EXPECTED_VOICING_ROWS)
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

    def test_kitap_voicing_exception(self):
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
        self.assertEqual(
            gen.generate_verb_forms("яз", "back", EXC),
            [
                ("verb.imp.2sg", "яз"),
                ("verb.imp.2pl", "языгыз"),
                ("verb.pres.1sg", "язам"),
                ("verb.pres.2sg", "язасың"),
                ("verb.pres.3sg", "яза"),
                ("verb.pres.2pl", "язасыз"),
                ("verb.pres.3pl", "язалар"),
                ("verb.pres.neg.1sg", "язмыйм"),
                ("verb.pres.neg.2sg", "язмыйсың"),
                ("verb.pres.neg.3sg", "язмый"),
                ("verb.pres.neg.2pl", "язмыйсыз"),
                ("verb.pres.neg.3pl", "язмыйлар"),
                ("verb.past.1sg", "яздым"),
                ("verb.past.2sg", "яздың"),
                ("verb.past.3sg", "язды"),
                ("verb.past.2pl", "яздыгыз"),
                ("verb.past.3pl", "яздылар"),
                ("verb.past.neg.1sg", "язмадым"),
                ("verb.past.neg.2sg", "язмадың"),
                ("verb.past.neg.3sg", "язмады"),
                ("verb.past.neg.2pl", "язмадыгыз"),
                ("verb.past.neg.3pl", "язмадылар"),
                ("verb.rpast.3sg", "язган"),
                ("verb.rpast.neg.3sg", "язмаган"),
                ("verb.fut.3sg", "язар"),
                ("verb.fut.neg.3sg", "язмас"),
                ("verb.deffut.3sg", "язачак"),
                ("verb.cond.3sg", "язса"),
                ("verb.cond.neg.3sg", "язмаса"),
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
        # The contraction rule covers the present (укы→укый); the будущее and
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
        # дию needs no exception rows: stem ди contracts regularly; the plan's
        # "диген" is a typo for дигән (generated here), дип is regular too.
        diyu = verb_forms("ди", "front")
        self.assertEqual(diyu["verb.pres.3sg"], "ди")
        self.assertEqual(diyu["verb.rpast.3sg"], "дигән")
        self.assertEqual(diyu["verb.ger.ip"], "дип")
        self.assertEqual(diyu["verb.masdar"], "дию")
        self.assertEqual(diyu["verb.fut.3sg"], "дияр")  # monosyllabic vowel stem

    def test_future_allomorphy(self):
        # Verified against kaikki.org Tatar verb tables (2026-09): -ар/-әр for
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
        for stem, form in (
            ("бар", "барыр"), ("бул", "булыр"), ("ал", "алыр"),
            ("кал", "калыр"), ("тул", "тулыр"), ("йөр", "йөрер"),
        ):
            harmony = "front" if stem == "йөр" else "back"
            self.assertEqual(verb_forms(stem, harmony)["verb.fut.3sg"], form, stem)

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

    def test_kara_present_override_rederives_persons(self):
        forms = verb_forms("кара", "back")
        self.assertEqual(forms["verb.pres.3sg"], "кара")  # not *карый
        self.assertEqual(forms["verb.pres.1sg"], "карам")
        self.assertEqual(forms["verb.pres.2pl"], "карасыз")

    def test_stale_override_fails_closed(self):
        # verb.part.asi is never emitted for a vowel-final stem, so an override
        # naming it for эшлә is a stale table row — a hard error.
        bad = gen.Exceptions({}, {}, {("эшлә", "verb.part.asi"): "эшләәсе"})
        with self.assertRaises(gen.WordformError):
            gen.generate_all("эшлә", bad)
        # A pronoun stem emits no regular noun labels, so a noun override on
        # мин is a stale row too.
        bad2 = gen.Exceptions({}, {"мин": ("минем",)}, {("мин", "noun.p3"): "мине"})
        with self.assertRaises(gen.WordformError):
            gen.generate_all("мин", bad2)

    def test_no_harmony_stem_fails_closed(self):
        with self.assertRaises(gen.WordformError):
            gen.generate_all("б")


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
        exc = self.load_text("# comment\n\nvoicing\tкитап\tкитаб\n")
        self.assertEqual(exc.voicing, {"китап": "китаб"})

    def test_unknown_kind(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("magic\tкитап\tкитаб\n")

    def test_wrong_field_count(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("voicing\tкитап\n")
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("voicing\tкитап\tкитаб\textra\n")

    def test_bad_voicing_pair(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("voicing\tкитап\tкитас\n")  # п→с is not a voicing pair

    def test_unknown_override_label(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("override\tяз\tnoun.genus=язган\n")

    def test_malformed_override_payload(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("override\tяз\tverb.masdar\n")  # no '='

    def test_word_outside_alphabet(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("voicing\tkitap\tкитаб\n")  # Latin stem
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("override\tяз\tverb.masdar=Язу\n")  # not lowercase

    def test_duplicate_rows(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("voicing\tкитап\tкитаб\nvoicing\tкитап\tкитаб\n")
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("pronoun\tмин\tминем\tмиңа\tминем\n")  # duplicate form

    def test_cr_rejected(self):
        with self.assertRaises(gen.ExceptionsError):
            self.load_text("voicing\tкитап\tкитаб\r\n")

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
            # Dual-harmony loan: both variants are candidates; P2 keeps the
            # attested one (советларга).
            ("советларга", "совет", "noun.pl.dat"),
            ("советләргә", "совет", "noun.pl.dat"),
        }
        self.assertTrue(expected <= row_set, expected - row_set)
        # The bare stem itself is never a candidate row.
        self.assertNotIn(("яз", "яз", "verb.imp.2sg"), row_set)
        self.assertNotIn("минләр", {form for form, _, _ in rows})
        self.assertEqual(stats["stems"], len(WORD_LIST))

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
            "өйләр", "китаплар", "суны",
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
        self.assertEqual(stats["grouped_forms"], len(rows))

    def test_ambiguous_strips_skipped(self):
        # язам analyzes as яза+м (noun.p1 of яза) and as яз+ам
        # (verb.pres.1sg of яз); both stems listed → fail-closed skip.
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
        # Smoke: exit 0 and sane output for the brief's two probe stems.
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
