#!/usr/bin/env python3
"""Tests for scripts/wordform_kaikki_check.py.

A synthetic kaikki JSONL covers the tag vocabulary of the real extract:
person/tense cells with affirmative+negative doublets under one tag set, the
tagless imperative rows (error-unrecognized-form) that carry the stem, the
non-finite bucket, the noun-from-verb masdar, and the entries the loader must
skip (Latin headwords, multiword verbs, fragments without an imperative row,
non-noun/non-verb entries). The comparison itself runs the real generator
with the committed exceptions table.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GENERATOR_SCRIPT = ROOT / "scripts" / "wordform_gen.py"
CHECK_SCRIPT = ROOT / "scripts" / "wordform_kaikki_check.py"
EXCEPTIONS_FILE = ROOT / "scripts" / "wordform_exceptions_tat.tsv"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


gen = load_module("wordform_gen", GENERATOR_SCRIPT)
check = load_module("wordform_kaikki_check", CHECK_SCRIPT)
EXC = gen.load_exceptions(EXCEPTIONS_FILE)


def form(form_text: str, *tags: str) -> dict:
    return {"form": form_text, "tags": list(tags)}


def entry(word: str, pos: str, forms: list[dict]) -> str:
    return json.dumps({"word": word, "pos": pos, "forms": forms}, ensure_ascii=False)


FIXTURE_LINES = [
    entry("татар", "noun", [
        form("татарлар", "nominative", "plural"),
        form("татарның", "genitive"),
        form("татарым", "first-person", "nominative", "possessive", "singular"),
        # Plural-possessed (татарларым): kaikki lists it in the p1 cell, the
        # generator does not emit such combos, so it is filtered out.
        form("татарларым", "first-person", "nominative", "possessive", "singular"),
        form("tatar", "romanization"),
    ]),
    entry("язу", "verb", [
        form("yazu", "romanization"),
        # The negative imperative is listed before the affirmative one; the
        # stem recovery picks the shorter form.
        form("язма", "error-unrecognized-form", "informal", "second-person", "singular"),
        form("яз", "error-unrecognized-form", "informal", "second-person", "singular"),
        form("языйк", "error-unrecognized-form", "first-person", "plural"),
        form("язмыйк", "error-unrecognized-form", "first-person", "plural"),
        form("язсыннар", "error-unrecognized-form", "plural", "third-person"),
        form("язам", "first-person", "indicative", "present", "singular"),
        form("язмыйм", "first-person", "indicative", "present", "singular"),
        form("язарсың", "future", "indefinite", "indicative", "informal", "second-person", "singular"),
        # A second variant in the same cell, which the generator misses.
        form("язырсың", "future", "indefinite", "indicative", "informal", "second-person", "singular"),
        form("язмассың", "future", "indefinite", "indicative", "informal", "second-person", "singular"),
        form("язсак", "conditional", "first-person", "plural"),
        form("язганбыз", "first-person", "indefinite", "indicative", "past", "plural"),
        # kaikki's definite-past 1pl: a cell the generator does not emit.
        form("яздык", "definite", "first-person", "indicative", "past", "plural"),
        form("язып", "error-unrecognized-form"),
        # The -рга infinitive: no generator label, out of scope.
        form("язарга", "error-unrecognized-form"),
        form("язу", "noun-from-verb"),
    ]),
    # Latin headword: the imperative form is not a Tatar word, skipped.
    entry("abaylaw", "verb", [
        form("abayla", "error-unrecognized-form", "informal", "second-person", "singular"),
    ]),
    # Multiword verb: the imperative form has a space, skipped.
    entry("искә алу", "verb", [
        form("искә ал", "error-unrecognized-form", "informal", "second-person", "singular"),
    ]),
    # Fragment entry: no imperative row, no paradigm.
    entry("ит", "verb", [form("itu", "romanization")]),
    # Not a noun or verb: skipped.
    entry("көчле", "adj", [form("küçle", "romanization")]),
]


def load_fixture() -> tuple:
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "kaikki.jsonl"
        path.write_text("\n".join(FIXTURE_LINES) + "\n", encoding="utf-8")
        return check.load_paradigms(path)


class MapTagsTest(unittest.TestCase):
    def test_noun_tags(self):
        self.assertEqual(check.map_noun_tags(["nominative", "plural"]), "noun.pl")
        self.assertEqual(check.map_noun_tags(["genitive"]), "noun.gen")
        self.assertEqual(check.map_noun_tags(["ablative", "plural"]), "noun.pl.abl")
        self.assertEqual(
            check.map_noun_tags(["first-person", "nominative", "possessive", "singular"]),
            "noun.p1",
        )
        self.assertEqual(
            check.map_noun_tags(["dative", "possessive", "singular", "third-person"]),
            "noun.p3.dat",
        )
        self.assertIsNone(check.map_noun_tags(["romanization"]))
        self.assertIsNone(check.map_noun_tags(["nominative", "singular"]))

    def test_verb_tense_cells(self):
        # kaikki's "indefinite past" is our -GAн recent past, its "definite
        # past" our -DI past.
        self.assertEqual(
            check.map_verb_tags(["first-person", "indicative", "present", "singular"]),
            ("verb.pres.1sg", "verb.pres.neg.1sg"),
        )
        self.assertEqual(
            check.map_verb_tags(["indefinite", "indicative", "past", "plural", "third-person"]),
            ("verb.rpast.3pl", "verb.rpast.neg.3pl"),
        )
        self.assertEqual(
            check.map_verb_tags(["definite", "indicative", "past", "singular", "third-person"]),
            ("verb.past.3sg", "verb.past.neg.3sg"),
        )
        self.assertEqual(
            check.map_verb_tags(["future", "indefinite", "indicative", "second-person", "singular"]),
            ("verb.fut.2sg", "verb.fut.neg.2sg"),
        )
        self.assertEqual(
            check.map_verb_tags(["definite", "future", "indicative", "first-person", "plural"]),
            ("verb.deffut.1pl", "verb.deffut.neg.1pl"),
        )
        self.assertEqual(
            check.map_verb_tags(["conditional", "first-person", "plural"]),
            ("verb.cond.1pl", "verb.cond.neg.1pl"),
        )

    def test_verb_imperative_and_nonfinite(self):
        self.assertEqual(
            check.map_verb_tags(["error-unrecognized-form", "informal", "second-person", "singular"]),
            ("verb.imp.2sg", None),
        )
        self.assertEqual(
            check.map_verb_tags(["error-unrecognized-form", "plural", "third-person"]),
            ("verb.imp.3pl", None),
        )
        self.assertEqual(
            check.map_verb_tags(["error-unrecognized-form"]),
            (check.NONFINITE_SENTINEL, None),
        )
        self.assertEqual(check.map_verb_tags(["noun-from-verb"]), ("verb.masdar", None))
        self.assertIsNone(check.map_verb_tags(["romanization"]))
        self.assertIsNone(check.map_verb_tags(["table-tags"]))
        self.assertIsNone(check.map_verb_tags(["inflection-template"]))
        self.assertIsNone(check.map_verb_tags(["infinitive"]))
        self.assertIsNone(check.map_verb_tags([]))


class LoadParadigmsTest(unittest.TestCase):
    def test_noun_cells(self):
        nouns, _verbs = load_fixture()
        self.assertEqual(sorted(nouns), ["татар"])
        self.assertEqual(nouns["татар"]["noun.pl"], {"татарлар"})
        self.assertEqual(nouns["татар"]["noun.p1"], {"татарым", "татарларым"})

    def test_verb_stem_from_shortest_imperative(self):
        _nouns, verbs = load_fixture()
        # язма (negative imperative) is listed first; the stem is still яз.
        self.assertEqual(sorted(verbs), ["яз"])
        cells = verbs["яз"]
        self.assertEqual(cells[("verb.imp.2sg", None)], {"яз", "язма"})
        self.assertEqual(
            cells[("verb.pres.1sg", "verb.pres.neg.1sg")], {"язам", "язмыйм"}
        )
        self.assertEqual(cells[("verb.masdar", None)], {"язу"})
        self.assertEqual(cells[(check.NONFINITE_SENTINEL, None)], {"язып", "язарга"})


class CompareTest(unittest.TestCase):
    def setUp(self):
        nouns, verbs = load_fixture()
        self.report = check.compare(nouns, verbs, EXC)
        self.per_label = self.report["per_label"]

    def test_stem_counts(self):
        self.assertEqual(self.report["stems_in_kaikki_with_forms"], 2)
        self.assertEqual(self.report["stems_compared"], 2)
        self.assertEqual(self.report["stems_compared_noun"], 1)
        self.assertEqual(self.report["stems_compared_verb"], 1)

    def test_recall_counts(self):
        # 15 kaikki forms in shared cells; only язырсың is missed.
        self.assertEqual(self.report["kaikki_in_scope_forms"], 15)
        self.assertEqual(self.report["kaikki_covered_forms"], 14)
        self.assertEqual(self.report["recall_pct"], 93.33)
        self.assertEqual(self.per_label["verb.fut.2sg"], {"kaikki": 2, "covered": 1, "extra": 0})
        self.assertEqual(self.report["missed_rows"], [("яз", "verb.fut.2sg", "язырсың")])

    def test_negative_doublets_split(self):
        # Both forms of a kaikki cell land on our affirmative/negative labels.
        self.assertEqual(self.per_label["verb.pres.1sg"]["covered"], 1)
        self.assertEqual(self.per_label["verb.pres.neg.1sg"], {"kaikki": 1, "covered": 1, "extra": 0})
        self.assertEqual(self.per_label["verb.fut.neg.2sg"]["covered"], 1)

    def test_out_of_scope(self):
        # татарларым (plural-possessed), язма/язмыйк (negative imperatives),
        # яздык (definite-past 1pl), язарга (the -рга infinitive).
        self.assertEqual(self.report["kaikki_out_of_scope_forms"], 5)

    def test_nonfinite_attribution(self):
        self.assertEqual(self.per_label["verb.ger.ip"], {"kaikki": 1, "covered": 1, "extra": 0})
        self.assertEqual(self.per_label["verb.masdar"], {"kaikki": 1, "covered": 1, "extra": 0})

    def test_no_extra_on_covered_stems(self):
        self.assertEqual(self.report["our_extra_forms"], 0)


class CliTest(unittest.TestCase):
    def run_cli(self, *argv: str) -> int:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return check.main(list(argv))

    def fixture_file(self, directory: str) -> Path:
        path = Path(directory) / "kaikki.jsonl"
        path.write_text("\n".join(FIXTURE_LINES) + "\n", encoding="utf-8")
        return path

    def test_happy_path_prints_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            jsonl = self.fixture_file(tmp)
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                self.assertEqual(check.main(["--jsonl", str(jsonl)]), 0)
            out = buffer.getvalue()
            self.assertIn("stems_compared: 2", out)
            self.assertIn("verb.fut.2sg: 1/2", out)

    def test_out_rows_load_as_exceptions(self):
        with tempfile.TemporaryDirectory() as tmp:
            jsonl = self.fixture_file(tmp)
            out = Path(tmp) / "candidates.tsv"
            self.assertEqual(self.run_cli("--jsonl", str(jsonl), "--out", str(out)), 0)
            text = out.read_text(encoding="utf-8")
            self.assertIn("override\tяз\tverb.fut.2sg=язырсың", text)
            # The candidate file is structurally valid for the generator.
            candidates = gen.load_exceptions(out)
            self.assertEqual(candidates.overrides, {("яз", "verb.fut.2sg"): "язырсың"})

    def test_missing_input_and_bad_rows_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = Path(tmp) / "no-such.jsonl"
            self.assertEqual(self.run_cli("--jsonl", str(missing)), 2)
            bad = Path(tmp) / "bad.jsonl"
            bad.write_text("not json\n", encoding="utf-8")
            self.assertEqual(self.run_cli("--jsonl", str(bad)), 2)


if __name__ == "__main__":
    unittest.main()
