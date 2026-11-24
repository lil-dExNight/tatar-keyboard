#!/usr/bin/env python3
import bz2
import gzip
import io
import json
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research/corpus"))
import convert_sources


class ConverterTest(unittest.TestCase):
    def test_tatoeba_filters_language_and_preserves_ids(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "tat.tsv.bz2"
            with bz2.open(path, "wt", encoding="utf-8") as stream:
                stream.write("1\ttat\tСәләм!\n2\trus\tПривет!\n3\ttat\t\n")
            self.assertEqual(list(convert_sources.iter_tatoeba(path)), [{"id": "1", "text": "Сәләм!"}])

    def test_leipzig_reads_only_sentence_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "sample.tar.gz"
            with tarfile.open(path, "w:gz") as archive:
                for name, payload in (("sample/sample-sentences.txt", "7\tСәләм дөнья\n8\t\n"), ("sample/sample-words.txt", "сәләм\t2\n")):
                    info = tarfile.TarInfo(name)
                    encoded = payload.encode("utf-8")
                    info.size = len(encoded)
                    archive.addfile(info, io.BytesIO(encoded))
            self.assertEqual(list(convert_sources.iter_leipzig(path)), [{"id": "7", "text": "Сәләм дөнья"}])

    def test_wikipedia_extracts_page_title_and_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "wiki.xml.bz2"
            payload = "<mediawiki xmlns='urn:x'><page><title>Казан</title><revision><text>Казан — шәһәр.</text></revision></page></mediawiki>"
            with bz2.open(path, "wt", encoding="utf-8") as stream:
                stream.write(payload)
            rows = list(convert_sources.iter_wikipedia(path))
            self.assertEqual(rows[0]["id"], "Казан")
            self.assertEqual(rows[0]["text"], "Казан — шәһәр.")

    def test_corpus_tatar_supports_all_three_list_layouts(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with bz2.open(directory / "tatcorpus3.words_frequency_case-sensitive.bz2", "wt", encoding="utf-8") as stream:
                stream.write("12\tһәм\nnot-a-count\tюк\n")
            with zipfile.ZipFile(directory / "tatcorpus2.words_frequency_full.txt.zip", "w") as archive:
                archive.writestr("words.txt", "1\tбелән\t8\n")
            (directory / "tatcorpus3.words.sorted.length.single.txt").write_text("кирәк\n", encoding="utf-8")
            rows = list(convert_sources.iter_corpus_tatar(directory))
            self.assertEqual([(row["text"], row["frequency"]) for row in rows], [("һәм", 12), ("белән", 8), ("кирәк", 1)])

    def test_madlad_literal_newlines_become_line_breaks(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "madlad.jsonl.gz"
            with gzip.open(path, "wt", encoding="utf-8") as stream:
                stream.write(json.dumps({"text": "Башлык\\nСәләм\\nРәхмәт"}) + "\n")
            self.assertEqual(list(convert_sources.iter_madlad(path)), [{"id": "1", "text": "Башлык\nСәләм\nРәхмәт"}])

    def test_jsonl_keeps_redacted_text_and_public_url(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "community.jsonl"
            path.write_text(json.dumps({"article_url": "https://example.invalid/post", "text": "Сәләм [PHONE]"}) + "\n", encoding="utf-8")
            self.assertEqual(list(convert_sources.iter_jsonl(path)), [{"id": "https://example.invalid/post", "text": "Сәләм [PHONE]", "url": "https://example.invalid/post"}])


if __name__ == "__main__":
    unittest.main()
