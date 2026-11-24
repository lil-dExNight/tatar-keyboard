#!/usr/bin/env python3
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research/corpus"))
import clean_corpora
import evaluation_exclusions
from document_dedup import DocumentDeduplicator


def _near_duplicate_pair() -> tuple[str, str]:
    tokens = [f"token-{index}" for index in range(128)]
    original = " ".join(tokens)
    tokens[64] = "replacement"
    return original, " ".join(tokens)


class CleanCorporaTest(unittest.TestCase):
    def setUp(self):
        self.tatar = frozenset({"рәхмәт", "сәләм", "белән", "һәм"})
        self.russian = frozenset({"это", "наш", "дом"})

    def write_documents(self, directory: Path, source: str, rows: list[dict]) -> Path:
        path = directory / f"{source}.jsonl"
        path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
        return path

    def test_pipeline_separates_eval_chat_language_and_exact_duplicates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            rows = [
                {"id": "1", "text": "Рәхмәт! Сәләм."},
                {"id": "2", "text": "Рэхмэт, белэн"},
                {"id": "3", "text": "Это наш дом."},
                {"id": "4", "text": "Рәхмәт! Сәләм."},
            ]
            documents = self.write_documents(root, "tatoeba", rows)
            output = root / "out"
            with DocumentDeduplicator(output / "dedup.sqlite") as dedup:
                stats = clean_corpora.clean_source(
                    "tatoeba", documents, output, dedup,
                    frozenset({evaluation_exclusions.sentence_key("Рәхмәт!")}),
                    self.tatar, self.russian,
                )
            self.assertEqual((output / "sentences/tatoeba.txt").read_text(), "Сәләм.\n")
            chat = (output / "chat-spelling/tatoeba.jsonl").read_text().splitlines()
            self.assertEqual(len(chat), 1)
            self.assertEqual(json.loads(chat[0])["variants"], {"белэн": "белән", "рэхмэт": "рәхмәт"})
            self.assertEqual(stats["duplicate_documents"]["exact"], 1)
            self.assertEqual(stats["filters"]["evaluation_collision"]["lines"], 1)
            self.assertEqual(stats["filters"]["language_russian"]["lines"], 1)
            self.assertEqual(stats["kept_tokens"], 1)

    def test_near_duplicate_of_evaluation_text_is_excluded_without_inserting_corpus(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original, changed = _near_duplicate_pair()
            documents = self.write_documents(root, "tatoeba", [{"id": "variant", "text": changed}])
            output = root / "out"
            with (
                DocumentDeduplicator(output / "dedup.sqlite") as dedup,
                DocumentDeduplicator(output / "evaluation.sqlite") as evaluation_dedup,
            ):
                evaluation_dedup.check("evaluation", "one", original)
                stats = clean_corpora.clean_source(
                    "tatoeba", documents, output, dedup, frozenset(),
                    self.tatar, self.russian, evaluation_deduplicator=evaluation_dedup,
                )
                self.assertEqual(evaluation_dedup.counts(), {"exact": 0, "near": 0, "kept": 1})
            self.assertEqual(stats["filters"]["evaluation_collision_near"]["lines"], 1)
            self.assertEqual(stats["kept_lines"], 0)

    def test_weighted_nonlexical_duplicates_conserve_occurrences(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            rows = [
                {"id": "1", "text": "Рәхмәт", "frequency": 5},
                {"id": "2", "text": "Рәхмәт", "frequency": 5},
            ]
            documents = self.write_documents(root, "tatoeba", rows)
            output = root / "out"
            with DocumentDeduplicator(output / "dedup.sqlite") as dedup:
                stats = clean_corpora.clean_source(
                    "tatoeba", documents, output, dedup, frozenset(), self.tatar, self.russian,
                )
            self.assertEqual(stats["duplicate_tokens"]["exact"], 5)
            self.assertEqual(stats["raw_token_occurrences"], 10)
            self.assertEqual(stats["kept_token_occurrences"], 5)

    def test_replacement_damage_is_filtered_before_output_streams(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            documents = self.write_documents(root, "tatoeba", [{"id": "bad", "text": "Сәләм\ufffdдөнья"}])
            output = root / "out"
            with DocumentDeduplicator(output / "dedup.sqlite") as dedup:
                stats = clean_corpora.clean_source(
                    "tatoeba", documents, output, dedup, frozenset(), self.tatar, self.russian,
                )
            self.assertEqual(stats["filters"]["encoding"]["lines"], 1)
            self.assertEqual(stats["kept_lines"], 0)
            self.assertEqual(stats["chat_spelling_lines"], 0)
            self.assertEqual((output / "sentences/tatoeba.txt").read_text(), "")
            self.assertEqual((output / "chat-spelling/tatoeba.jsonl").read_text(), "")

    def test_lexical_duplicates_preserve_first_frequency_and_chat_is_excluded(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            documents = self.write_documents(root, "corpus_tatar", [
                {"id": "1", "text": "һәм", "frequency": 5, "lexical": True},
                {"id": "2", "text": "һәм", "frequency": 7, "lexical": True},
                {"id": "3", "text": "хэм", "frequency": 11, "lexical": True},
            ])
            output = root / "out"
            with DocumentDeduplicator(output / "dedup.sqlite") as dedup:
                stats = clean_corpora.clean_source(
                    "corpus_tatar", documents, output, dedup, frozenset(), self.tatar, self.russian,
                )
            self.assertEqual((output / "lexicon-only/corpus_tatar.txt").read_text(), "һәм\n")
            self.assertEqual(stats["kept_token_occurrences"], 5)
            self.assertEqual(stats["filters"]["lexical_duplicate"]["token_occurrences"], 7)
            self.assertEqual(stats["chat_spelling_lines"], 1)
            self.assertEqual(stats["chat_spelling_token_occurrences"], 11)
            self.assertEqual(stats["duplicate_documents"], {"exact": 0, "near": 0})

    def test_retry_requires_fresh_deduplication_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "validation").mkdir()
            clean_corpora.ensure_fresh_output(root)
            (root / "dedup.sqlite").write_bytes(b"partial")
            with self.assertRaisesRegex(FileExistsError, "fresh directory"):
                clean_corpora.ensure_fresh_output(root)

    def test_repeated_header_filter_waits_for_threshold(self):
        tracker = clean_corpora.RepeatedHeaderFilter(threshold=2)
        self.assertFalse(tracker.is_repeated("Искәрмәләр", "1", set()))
        self.assertFalse(tracker.is_repeated("Искәрмәләр", "2", set()))
        self.assertTrue(tracker.is_repeated("Искәрмәләр", "3", set()))
        self.assertFalse(tracker.is_repeated("Рәхмәт.", "4", set()))


if __name__ == "__main__":
    unittest.main()
