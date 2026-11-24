#!/usr/bin/env python3
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research/corpus"))
import evaluation_exclusions as exclusions


class EvaluationExclusionTest(unittest.TestCase):
    def test_split_sources_keep_only_development_and_final_sentences(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            declaration = root / "data/evaluation/splits-test.json"
            declaration.parent.mkdir(parents=True)
            declaration.write_text(json.dumps({"sources": [
                {"id": "development", "kind": "sentences", "split": "development", "path": "app/src/test/resources/tt_eval_sentences.txt"},
                {"id": "train", "kind": "sentences", "split": "train", "path": "app/src/test/resources/train.txt"},
                {"id": "words", "kind": "frequencies", "split": "development", "path": "app/src/test/resources/words.tsv"},
            ]}), encoding="utf-8")
            sentences = root / "app/src/test/resources/tt_eval_sentences.txt"
            sentences.parent.mkdir(parents=True)
            sentences.write_text("# comment\n«Сәләм», диде!\n", encoding="utf-8")
            (sentences.parent / "train.txt").write_text("Бу кермәсен\n", encoding="utf-8")
            (sentences.parent / "words.tsv").write_text("сәләм\t1\n", encoding="utf-8")
            keys, records = exclusions.load_split_evaluation_keys(root, ("splits-test.json",))
            self.assertIn(exclusions.sentence_key("Сәләм диде."), keys)
            self.assertNotIn(exclusions.sentence_key("Бу кермәсен"), keys)
            self.assertEqual(records[0]["rows"], 1)

    def test_malformed_committed_and_speech_rows_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            declaration = root / "data/evaluation/splits-bad.json"
            declaration.parent.mkdir(parents=True)
            declaration.write_text(json.dumps({"sources": [
                {"id": "development", "kind": "sentences", "split": "development", "path": "app/src/test/resources/mix.txt", "sentence_column": 1},
            ]}), encoding="utf-8")
            source = root / "app/src/test/resources/mix.txt"
            source.parent.mkdir(parents=True)
            source.write_text("lead\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "sentence column 1 is absent"):
                exclusions.load_split_evaluation_keys(root, ("splits-bad.json",))
            inventory = root / "data/evaluation/inventory.json"
            inventory.write_text(json.dumps({"sources": [
                {"id": "common-voice-test", "format": "text", "path": "build/corpus-sources/bad.txt", "sentence_column": 1},
            ]}), encoding="utf-8")
            bad = root / "build/corpus-sources/bad.txt"
            bad.parent.mkdir(parents=True)
            bad.write_text("already-thin\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "sentence column 1 is absent"):
                exclusions.load_speech_evaluation_keys(root, inventory, root / "build/corpus-sources")

    def test_shipped_tatar_russian_mix_uses_sentence_column(self):
        _keys, records = exclusions.load_split_evaluation_keys(ROOT, ("splits-tt.json",))
        record = next(item for item in records if item["id"] == "tt-ru-mix-sentences")
        self.assertGreater(record["rows"], 0)
        self.assertGreater(record["new_keys"], 0)

    def test_speech_inventory_reads_test_and_ignores_train(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            speech = root / "shared"
            text = speech / "common-voice/tt-test.txt"
            text.parent.mkdir(parents=True)
            text.write_text("Сәләм исәнмесез\n", encoding="utf-8")
            inventory = root / "data/evaluation/inventory.json"
            inventory.parent.mkdir(parents=True)
            inventory.write_text(json.dumps({"sources": [
                {"id": "common-voice-test", "format": "text", "path": "build/corpus-sources/common-voice/tt-test.txt"},
                {"id": "common-voice-train", "format": "text", "path": "build/corpus-sources/common-voice/tt-train.txt"},
                {"id": "extra-tatar-qed", "format": "gzip_text", "path": "unused.gz"},
            ]}), encoding="utf-8")
            keys, records = exclusions.load_speech_evaluation_keys(root, inventory, speech)
            self.assertEqual(keys, frozenset({exclusions.sentence_key("Сәләм исәнмесез")}))
            self.assertEqual(records[0]["rows"], 1)
            text.unlink()
            with self.assertRaises(FileNotFoundError):
                exclusions.load_speech_evaluation_keys(root, inventory, speech)


if __name__ == "__main__":
    unittest.main()
