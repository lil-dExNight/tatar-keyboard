#!/usr/bin/env python3
"""Contract tests for the Tatar morphology golden-vector exporter."""

from __future__ import annotations

import contextlib
import io
import sys
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import stem_lexicon_pack as pack  # noqa: E402
import tatar_morphology_vectors as vectors  # noqa: E402
import wordform_gen as gen  # noqa: E402

RESOURCE = ROOT / "app/src/test/resources/tatar_morphology_vectors_v1.tsv"


def parse_rows(data: bytes) -> list[tuple[str, str, str, str]]:
    rows = []
    for line in data.decode("utf-8").splitlines():
        if line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 4 or fields[0] not in {"A", "R"}:
            raise AssertionError(f"invalid vector row: {line!r}")
        rows.append((fields[0], fields[1], fields[2], fields[3]))
    return rows


def synthetic_entry(
    stem: str, *, noun: bool = False, verb: bool = False,
    chain: bool = True, force_back: bool = False,
) -> pack.StemEntry:
    flags = (pack.FLAG_NOUN if noun else 0) | (pack.FLAG_VERB if verb else 0)
    if chain:
        flags |= (pack.FLAG_NOUN_CHAIN if noun else 0) | (
            pack.FLAG_VERB_CHAIN if verb else 0
        )
    harmony = gen.BACK if force_back else gen.harmony_of(stem)
    flags |= pack.FLAG_BACK if harmony == gen.BACK else pack.FLAG_FRONT
    if force_back:
        flags |= pack.FLAG_FORCE_BACK
    return pack.StemEntry(stem, flags)


class GoldenVectorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.data, cls.stats = vectors.generate()
        cls.entries = vectors.load_stem_entries(vectors.DEFAULT_STEM_LEXICON)
        cls.exceptions = gen.load_exceptions(vectors.DEFAULT_EXCEPTIONS)
        cls.rows = parse_rows(cls.data)

    def test_regeneration_is_deterministic_and_resource_is_fresh(self) -> None:
        regenerated, stats = vectors.generate()
        self.assertEqual(self.data, regenerated)
        self.assertEqual(self.stats, stats)
        self.assertEqual(regenerated, RESOURCE.read_bytes())

    def test_check_mode_does_not_write(self) -> None:
        with mock.patch.object(
            vectors.gen, "write_atomic", side_effect=AssertionError("write attempted")
        ), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            result = vectors.main(["--check"])
        self.assertEqual(0, result)

    def test_every_row_matches_the_full_reference(self) -> None:
        acceptor = vectors.ReferenceAcceptor(self.entries, self.exceptions)
        for decision, form, _stem, _label in self.rows:
            accepted = acceptor.accepts(form)
            if decision == "A":
                self.assertTrue(accepted, (decision, form, _stem, _label))
            else:
                self.assertFalse(accepted, (decision, form, _stem, _label))

    def test_sample_covers_flags_and_available_exceptions(self) -> None:
        sample = vectors.select_sample(self.entries, self.exceptions)
        by_word = {entry.word: entry for entry in self.entries}
        sampled_words = {entry.word for entry in sample}
        required_exceptions = vectors._exception_stems(self.exceptions) & set(by_word)
        self.assertLessEqual(required_exceptions, sampled_words)
        self.assertEqual(
            {entry.flags for entry in self.entries},
            {entry.flags for entry in sample},
        )
        self.assertTrue(any(entry.flags & pack.FLAG_NOUN_CHAIN for entry in sample))
        self.assertTrue(any(entry.flags & pack.FLAG_VERB_CHAIN for entry in sample))
        forced = [entry for entry in sample if entry.flags & pack.FLAG_FORCE_BACK]
        self.assertEqual(
            any(entry.flags & pack.FLAG_FORCE_BACK for entry in self.entries),
            bool(forced),
        )
        for entry in forced:
            self.assertEqual(gen.BACK, vectors.resolve_harmony(entry, self.exceptions))

        actual = {(form, stem, label) for decision, form, stem, label in self.rows if decision == "A"}
        for word in sorted(required_exceptions):
            entry = by_word[word]
            expected = set(vectors.build_accepted_rows((entry,), self.exceptions))
            self.assertLessEqual(expected, actual, word)

    def test_rule_families_are_covered(self) -> None:
        labels = {label for decision, _form, _stem, label in self.rows if decision == "A"}
        self.assertLessEqual(set(gen.DERIV_LABELS), labels)
        self.assertLessEqual(
            {
                "bare",
                "noun.pl",
                "noun.p3.abl",
                "noun.pl.p1.dat",
                "noun.pred.3sg.q",
                "verb.pres.1sg.q",
                "verb.fut.3sg",
                "verb.part.asi",
                "verb.inf",
            },
            labels,
        )
        reasons = {label for decision, _form, _stem, label in self.rows if decision == "R"}
        self.assertLessEqual(
            {
                "suffix_harmony",
                "consonant_assimilation",
                "illegal_continuation_order",
                "extra_clitic",
                "missing_or_unknown_stem",
                "empty_input",
                "outside_alphabet",
            },
            reasons,
        )

    def test_reference_finds_exception_forms_without_literal_stem_prefix(self) -> None:
        acceptor = vectors.ReferenceAcceptor(
            (
                synthetic_entry("ул", noun=True),
                synthetic_entry("бу", noun=True),
                synthetic_entry("халык", noun=True),
                synthetic_entry("бар", verb=True),
                synthetic_entry("укы", verb=True),
            ),
            self.exceptions,
        )
        for form in (
            "аның",
            "моның",
            "халкы",
            "халкында",
            "барырмын",
            "барырмы",
            "уку",
            "укмыйм",
            "укмадым",
            "укырга",
        ):
            self.assertTrue(acceptor.accepts(form), form)
        for form in ("уллар", "булар", "халыгы"):
            self.assertFalse(acceptor.accepts(form), form)

    def test_bare_only_entries_do_not_accept_override_chains(self) -> None:
        acceptor = vectors.ReferenceAcceptor(
            (synthetic_entry("бар", verb=True, chain=False),), self.exceptions
        )
        self.assertTrue(acceptor.accepts("бар"))
        self.assertFalse(acceptor.accepts("барыр"))
        self.assertFalse(acceptor.accepts("барырмын"))

    def test_nonthird_person_verb_override_does_not_rederive_question(self) -> None:
        exceptions = gen.Exceptions(
            {}, {}, {}, {("яз", "verb.pres.1sg"): "язым"}
        )
        acceptor = vectors.ReferenceAcceptor(
            (synthetic_entry("яз", verb=True),), exceptions
        )
        self.assertTrue(acceptor.accepts("язым"))
        self.assertTrue(acceptor.accepts("язаммы"))
        self.assertFalse(acceptor.accepts("языммы"))

        exceptions = gen.Exceptions(
            {}, {}, {},
            {
                ("яз", "verb.pres.1sg"): "язым",
                ("яз", "verb.pres.1sg.q"): "языммы",
            },
        )
        acceptor = vectors.ReferenceAcceptor(
            (synthetic_entry("яз", verb=True),), exceptions
        )
        self.assertTrue(acceptor.accepts("языммы"))

    def test_overridden_bases_rederive_dependent_forms(self) -> None:
        possessive = gen.Exceptions(
            {}, {}, {}, {("су", "noun.p3"): "суын"}
        )
        acceptor = vectors.ReferenceAcceptor(
            (synthetic_entry("су", noun=True),), possessive
        )
        generated = dict(gen.generate_noun_forms("су", gen.BACK, possessive))
        for label in ("noun.p3", "noun.p3.dat", "noun.p3.loc", "noun.p3.abl"):
            self.assertTrue(acceptor.accepts(generated[label]), label)
        self.assertFalse(acceptor.accepts("суы"))

        predicate = gen.Exceptions(
            {}, {}, {}, {("яз", "noun.pred.1sg"): "язым"}
        )
        acceptor = vectors.ReferenceAcceptor(
            (synthetic_entry("яз", noun=True),), predicate
        )
        self.assertTrue(acceptor.accepts("языммы"))
        self.assertFalse(acceptor.accepts("язмынмы"))

    def test_forced_back_precedes_exception_harmony(self) -> None:
        entry = synthetic_entry("исем", noun=True, force_back=True)
        exceptions = gen.Exceptions({"исем": gen.FRONT}, {}, {}, {})
        self.assertEqual(gen.BACK, vectors.resolve_harmony(entry, exceptions))
        acceptor = vectors.ReferenceAcceptor((entry,), exceptions)
        self.assertTrue(acceptor.accepts("исемның"))
        self.assertFalse(acceptor.accepts("исемнең"))


if __name__ == "__main__":
    unittest.main()
