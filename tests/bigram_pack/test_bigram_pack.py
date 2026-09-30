#!/usr/bin/env python3
"""Fixture tests for the bigram prototype (scripts/bigram_pack.py).

The corpora are downloaded separately, so what is tested here are the
RULES — parsing, tokenization, pairing, the matrix, the size formula, the caps and the frozen
held-out denominator — on synthetic input small enough to read.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY_ROOT / "scripts"))

import bigram_pack  # noqa: E402
import dictionary_pack  # noqa: E402


class SentenceParsingTest(unittest.TestCase):
    def test_accepts_exactly_two_tab_fields_with_a_positive_id(self) -> None:
        self.assertEqual(
            "әни өйгә кайтты",
            bigram_pack.parse_sentence_row("17\tәни өйгә кайтты\n", "src", 1),
        )

    def test_a_malformed_row_stops_generation_instead_of_being_skipped(self) -> None:
        for line in (
            "17\tәни\tөй\n",  # three fields
            "әни өйгә кайтты\n",  # one field
            "0\tәни өйгә\n",  # id not positive
            "-3\tәни өйгә\n",  # id not decimal
            "17a\tәни өйгә\n",  # id not decimal
        ):
            with self.subTest(line=line):
                with self.assertRaises(bigram_pack.BigramInputError):
                    bigram_pack.parse_sentence_row(line, "src", 1)


class TokenizationAndPairingTest(unittest.TestCase):
    VOCABULARY = frozenset({"әни", "өйгә", "кайтты", "сүз", "китап"})

    def test_a_rejected_token_breaks_adjacency_rather_than_being_transparent(self) -> None:
        # "сүз," is rejected WHOLE by normalize_word (the comma is outside the alphabet), so it
        # must not become a transparent bridge between its neighbours.
        tokens = bigram_pack.normalized_tokens("әни сүз, китап")
        self.assertEqual(["әни", None, "китап"], tokens)
        self.assertEqual([], list(bigram_pack.iter_pairs(tokens, self.VOCABULARY)))

    def test_an_imperative_with_a_trailing_mark_yields_no_pair(self) -> None:
        # "позвони!" is rejected WHOLE, so the imperative gets no successor from this sentence.
        # This is the intended rule, not a defect: missing predictions after imperatives come from
        # the head cutoff, which this rule cannot influence (see tests/bigram_asset_pack
        # test_bigram_evidence_never_promotes_a_word_to_a_head), and relaxing the rule lowered the
        # held-out hit rate in both languages.
        tokens = bigram_pack.normalized_tokens("позвони! мин")
        self.assertEqual([None, "мин"], tokens)
        self.assertEqual([], list(bigram_pack.iter_pairs(tokens, self.VOCABULARY | {"позвони", "мин"})))

    def test_punctuation_alone_between_two_words_produces_no_pair(self) -> None:
        tokens = bigram_pack.normalized_tokens("әни , китап")
        self.assertEqual([], list(bigram_pack.iter_pairs(tokens, self.VOCABULARY)))

    def test_adjacent_in_vocabulary_tokens_make_a_pair(self) -> None:
        tokens = bigram_pack.normalized_tokens("әни өйгә кайтты")
        self.assertEqual(
            [("әни", "өйгә"), ("өйгә", "кайтты")],
            list(bigram_pack.iter_pairs(tokens, self.VOCABULARY)),
        )

    def test_a_word_outside_the_shipped_vocabulary_drops_its_pairs(self) -> None:
        tokens = bigram_pack.normalized_tokens("әни мәктәпкә китап")
        self.assertEqual(
            [], list(bigram_pack.iter_pairs(tokens, self.VOCABULARY))
        )

    def test_a_self_pair_is_dropped(self) -> None:
        tokens = bigram_pack.normalized_tokens("сүз сүз китап")
        self.assertEqual(
            [("сүз", "китап")], list(bigram_pack.iter_pairs(tokens, self.VOCABULARY))
        )

    def test_tokens_are_split_on_runs_of_whitespace(self) -> None:
        tokens = bigram_pack.normalized_tokens("әни \t  өйгә\n")
        self.assertEqual(["әни", "өйгә"], tokens)


class MatrixShapeTest(unittest.TestCase):
    def test_seven_configurations_without_the_top_corner(self) -> None:
        configurations = bigram_pack.matrix_configurations()
        self.assertEqual(7, len(configurations))
        pairs = {(entry.heads, entry.successes_per_head) for entry in configurations}
        self.assertNotIn((10_000, 10), pairs)
        self.assertEqual({8_000, 10_000}, {entry.heads for entry in configurations})
        self.assertEqual({4, 6, 8, 10}, {entry.successes_per_head for entry in configurations})

    def test_the_raw_size_follows_the_documented_section_formula(self) -> None:
        heads = ["әни", "өй"]
        successes = ["кайтты", "зур"]
        expected = (
            96
            + 8 * (len(heads) + 1)
            + sum(len(word.encode("utf-8")) for word in heads)
            + 4 * 5
            + 4 * (len(successes) + 1)
            + sum(len(word.encode("utf-8")) for word in successes)
        )
        self.assertEqual(expected, bigram_pack.raw_size(heads, 5, successes))

    def test_the_caps_are_the_ones_the_gate_names(self) -> None:
        self.assertEqual(250_000, bigram_pack.MAX_COMPRESSED_BYTES)
        self.assertEqual(1_048_576, bigram_pack.MAX_RAW_BYTES)


class HeldOutDenominatorTest(unittest.TestCase):
    """The denominator was frozen BEFORE the run; these tests pin it, not the hit rate."""

    def setUp(self) -> None:
        self.vocabulary = frozenset({"әни", "өйгә", "кайтты", "китап"})
        self.table = {"әни": [("өйгә", 5)]}
        self.head_rank = {"әни": 0, "өйгә": 1, "кайтты": 2, "китап": 3}

    def _evaluate(self, sentences: list[str], tmp: Path) -> bigram_pack.Configuration:
        path = tmp / "holdout-sentences.txt"
        path.write_text(
            "".join(f"{index}\t{text}\n" for index, text in enumerate(sentences, start=1)),
            encoding="utf-8",
        )
        configuration = bigram_pack.Configuration(heads=8_000, successes_per_head=4)
        bigram_pack.evaluate(
            [path], self.vocabulary, self.table, self.head_rank, [configuration],
            [bigram_pack.CorpusStats(path=str(path), sha256="")],
        )
        return configuration

    def test_a_sentence_start_is_not_an_event(self) -> None:
        with TemporaryDirectory() as directory:
            configuration = self._evaluate(["әни өйгә"], Path(directory))
        # Two tokens, one position with a previous token: exactly one event.
        self.assertEqual(1, configuration.events)
        self.assertEqual(1.0, configuration.unconditional_hit_rate)

    def test_a_position_after_a_rejected_token_is_not_an_event(self) -> None:
        with TemporaryDirectory() as directory:
            configuration = self._evaluate(["әни сүз, өйгә"], Path(directory))
        # "әни" -> rejected is an event (the head passed normalize_word); rejected -> "өйгә" is
        # not, because its previous token did not. One event, and it is a miss.
        self.assertEqual(1, configuration.events)
        self.assertEqual(0.0, configuration.unconditional_hit_rate)

    def test_an_event_without_any_prediction_counts_as_a_miss(self) -> None:
        with TemporaryDirectory() as directory:
            configuration = self._evaluate(["китап өйгә"], Path(directory))
        self.assertEqual(1, configuration.events)
        self.assertEqual(0, configuration.events_with_prediction_share)
        self.assertEqual(0.0, configuration.unconditional_hit_rate)
        # Conditional says nothing about it: no event had a prediction available at all.
        self.assertEqual(0.0, configuration.conditional_hit_rate)

    def test_unconditional_is_never_above_conditional(self) -> None:
        with TemporaryDirectory() as directory:
            configuration = self._evaluate(
                ["әни өйгә", "китап өйгә", "кайтты китап"], Path(directory)
            )
        self.assertLessEqual(
            configuration.unconditional_hit_rate, configuration.conditional_hit_rate
        )
        self.assertEqual(3, configuration.events)


class EndToEndMatrixTest(unittest.TestCase):
    """A whole run on a corpus of a few sentences, through the real shipped-asset reader."""

    WORDS = ["әни", "өйгә", "кайтты", "китап", "зур", "матур"]

    def _write_asset(self, directory: Path) -> Path:
        words_path = directory / "words.txt"
        words_path.write_text(
            "".join(
                f"{index}\t{word}\t{1000 - index}\n"
                for index, word in enumerate(self.WORDS, start=1)
            ),
            encoding="utf-8",
        )
        built = dictionary_pack.build_dictionary([words_path], len(self.WORDS))
        asset_path = directory / "tatar.tdict.z"
        asset_path.write_bytes(built.asset)
        return asset_path

    def test_the_matrix_runs_and_every_row_carries_its_numbers(self) -> None:
        with TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            asset_path = self._write_asset(directory)
            train = directory / "train-sentences.txt"
            train.write_text(
                "1\tәни өйгә кайтты\n"
                "2\tәни өйгә китап\n"
                "3\tзур китап матур\n",
                encoding="utf-8",
            )
            holdout = directory / "holdout-sentences.txt"
            holdout.write_text("1\tәни өйгә зур\n", encoding="utf-8")

            report = bigram_pack.run_matrix([train], [holdout], asset_path, shards=2)

        self.assertEqual(7, len(report["configurations"]))
        for row in report["configurations"]:
            with self.subTest(row=row):
                self.assertGreater(row["pairs"], 0)
                self.assertTrue(row["passes_raw_cap"])
                self.assertTrue(row["passes_compressed_cap"])
                self.assertGreaterEqual(row["raw_bytes"], bigram_pack.HEADER_BYTES)
                self.assertGreater(row["compressed_bytes"], 0)
                self.assertGreaterEqual(row["events"], 1)
        # Every corpus is pinned by SHA-256, including the held-out one, so the run is
        # reproducible.
        self.assertEqual(2, len(report["corpora"]))
        for corpus in report["corpora"]:
            self.assertEqual(64, len(corpus["sha256"]))
        # The share of tokens the tokenizer rule throws away is reported, not hidden.
        self.assertIn("rejected_share", report["corpora"][0])
        self.assertIn("peak_rss_bytes", report)

    def test_sharding_does_not_change_the_result(self) -> None:
        with TemporaryDirectory() as raw_directory:
            directory = Path(raw_directory)
            asset_path = self._write_asset(directory)
            train = directory / "train-sentences.txt"
            train.write_text(
                "1\tәни өйгә кайтты\n2\tзур китап матур\n3\tәни өйгә китап\n",
                encoding="utf-8",
            )
            holdout = directory / "holdout-sentences.txt"
            holdout.write_text("1\tәни өйгә\n", encoding="utf-8")

            one = bigram_pack.run_matrix([train], [holdout], asset_path, shards=1)
            many = bigram_pack.run_matrix([train], [holdout], asset_path, shards=4)

        self.assertEqual(one["configurations"], many["configurations"])


if __name__ == "__main__":
    unittest.main()
