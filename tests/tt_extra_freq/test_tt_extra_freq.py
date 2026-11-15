#!/usr/bin/env python3
"""Tests for Tatar attestation in the Russian-bleed filters."""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research" / "corpus"))

import tt_extra_freq as extra  # noqa: E402
import tt_extra_sentences as sentences  # noqa: E402


class BleedClassTest(unittest.TestCase):
    def test_tatar_attestation_precedes_russian_marker_rejection(self) -> None:
        for word in ("игътибар", "актёрлык"):
            with self.subTest(word=word):
                self.assertEqual(
                    extra.bleed_class(
                        word,
                        frozenset({word}),
                        frozenset({word}),
                        independent_tatar_refs=frozenset({word}),
                    ),
                    "tatar_attested",
                )

    def test_russian_only_marker_forms_are_rejected(self) -> None:
        for word in ("съешь", "ёлка"):
            with self.subTest(word=word):
                self.assertEqual(
                    extra.bleed_class(
                        word,
                        frozenset(),
                        frozenset({word}),
                        independent_tatar_refs=frozenset(),
                    ),
                    "russian_letter",
                )

    def test_unattested_marker_form_is_rejected(self) -> None:
        self.assertEqual(
            extra.bleed_class(
                "биврёст",
                frozenset(),
                frozenset(),
                independent_tatar_refs=frozenset(),
            ),
            "russian_letter",
        )

    def test_shipped_dictionary_alone_does_not_save_a_marker_form(self) -> None:
        self.assertEqual(
            extra.bleed_class(
                "актёрлык",
                frozenset({"актёрлык"}),
                frozenset(),
                independent_tatar_refs=frozenset(),
            ),
            "russian_letter",
        )

    def test_shipped_dictionary_alone_does_not_override_russian_reference(self) -> None:
        self.assertEqual(
            extra.bleed_class(
                "привет",
                frozenset({"привет"}),
                frozenset({"привет"}),
                independent_tatar_refs=frozenset(),
            ),
            "russian_only",
        )


class FilterSourceTest(unittest.TestCase):
    def test_filter_preserves_attested_letters_and_drops_russian_only_forms(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tatar_ref = root / "tatar.txt"
            tatar_ref.write_text(
                "1\tигътибар\t7\n2\tактёрлык\t4\n3\tтатар\t2\n",
                encoding="utf-8",
            )
            russian_ref = root / "russian.txt"
            russian_ref.write_text(
                "1\tсъешь\t9\n2\tпривет\t3\n3\tпельмень\t5\n4\tактёрлык\t4\n",
                encoding="utf-8",
            )
            source = root / "input.tsv"
            source.write_text(
                "игътибар\t7\nактёрлык\t4\nтатар\t2\n"
                "съешь\t9\nпривет\t3\nпельмень\t5\n",
                encoding="utf-8",
            )
            output = root / "output.tsv"
            report = root / "report.json"
            args = SimpleNamespace(
                input=source,
                output=output,
                report=report,
                tatar_refs=[tatar_ref],
                tatar_dict_asset=None,
                russian_refs=[russian_ref],
            )
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(extra.filter_source(args), 0)
            kept = dict(
                line.split("\t") for line in output.read_text(encoding="utf-8").splitlines()
            )
            self.assertEqual(kept, {"игътибар": "7", "актёрлык": "4", "татар": "2"})
            stats = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(stats["tatar_independent_ref_words"], 3)
            self.assertEqual(stats["by_class"]["tatar_attested"]["words"], 3)
            self.assertEqual(stats["by_class"]["russian_letter"]["words"], 1)
            self.assertEqual(stats["by_class"]["russian_only"]["words"], 2)

    def test_dictionary_cannot_be_the_only_tatar_attestation_source(self) -> None:
        args = SimpleNamespace(tatar_refs=[])
        with self.assertRaisesRegex(SystemExit, "--tatar-refs"):
            extra.filter_source(args)
        with self.assertRaisesRegex(SystemExit, "--tatar-refs"):
            sentences.build(args)


if __name__ == "__main__":
    unittest.main()
