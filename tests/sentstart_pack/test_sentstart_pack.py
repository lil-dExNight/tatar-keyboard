#!/usr/bin/env python3
"""Tests for scripts/sentstart_pack.py — the sentence-start table packer (P4).

Synthetic sentence files and a tiny synthetic dictionary drive both the unit
surface (first-token extraction, normalization, the dictionary-membership
filter, ranking, guardrails) and the CLI contract (exit codes, fail-closed
writes). What is pinned there is the CONTRACT of the tool, not the data. The
committed-asset tests pin the shipped data: SHA-256, record count, the sorting
and normalization contracts, and the membership of every record in the shipped
Tatar dictionary. No network, no corpus dependency: the only live-tree inputs
are the two committed assets.
"""

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACK_SCRIPT = ROOT / "scripts" / "sentstart_pack.py"
DICTIONARY_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "dictionaries"
    / "tatar_top100k_v1.tdict.zlib"
)
SENTSTART_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "dictionaries"
    / "tatar_sentstart_v1.txt"
)


RU_DICTIONARY_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "dictionaries"
    / "russian_top100k_v1.tdict.zlib"
)
RU_SENTSTART_ASSET = (
    ROOT / "app" / "src" / "main" / "assets" / "dictionaries"
    / "russian_sentstart_v1.txt"
)


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


pack = load_module("sentstart_pack", PACK_SCRIPT)
coverage = pack.coverage
dictionary_pack = pack.dictionary_pack


def write_tmp(directory: Path, name: str, text: str) -> Path:
    path = directory / name
    path.write_text(text, encoding="utf-8")
    return path


def write_dictionary(
    directory: Path, words: list[str], language=coverage.TATAR
) -> Path:
    """A real schema-2 dictionary asset over [words], built with the pack APIs."""
    entries = sorted((word, 1000 - rank) for rank, word in enumerate(words))
    raw = dictionary_pack.serialize_entries(entries, language, schema=2)
    path = directory / "dict.tdict.zlib"
    path.write_bytes(dictionary_pack.compress_raw(raw))
    return path


def run_main(sentences: list[Path], dictionary: Path, output: Path,
             top: int = 64, language: str = "tat", **overrides) -> tuple:
    """Invoke the CLI entry point capturing its streams; returns (exit, stdout)."""
    stdout = io.StringIO()
    saved = {name: getattr(pack, name) for name in overrides}
    for name, value in overrides.items():
        setattr(pack, name, value)
    argv = ["build", "--dictionary", str(dictionary), "--output", str(output),
            "--top", str(top), "--language", language]
    for path in sentences:
        argv.extend(["--sentences", str(path)])
    try:
        with contextlib.redirect_stdout(stdout), \
                contextlib.redirect_stderr(io.StringIO()):
            code = pack.main(argv)
    finally:
        for name, value in saved.items():
            setattr(pack, name, value)
    return code, stdout.getvalue()


class ReadFirstTokensTest(unittest.TestCase):
    """The first-token counter: Leipzig row shape, dict_tokens strip, normalization."""

    def parse(self, files: dict[str, str]) -> pack.FirstTokenCounts:
        with tempfile.TemporaryDirectory() as directory:
            paths = [
                write_tmp(Path(directory), name, text) for name, text in files.items()
            ]
            return pack.read_first_tokens(paths)

    def test_first_token_of_every_sentence_is_counted(self) -> None:
        counts = self.parse({
            "a-sentences.txt": "1\tбу беренче сүз.\n2\tул икенче.\n3\tбу tagыр.\n",
        })
        self.assertEqual(counts.counts, Counter({"бу": 2, "ул": 1}))
        self.assertEqual(counts.rows_read, 3)
        self.assertEqual(counts.tokens_accepted, 3)
        self.assertEqual(counts.tokens_dropped, 0)

    def test_counts_merge_across_files(self) -> None:
        counts = self.parse({
            "a-sentences.txt": "1\tбу бер.\n",
            "b-sentences.txt": "1\tбу ике.\n1\tул өч.\n",
        })
        self.assertEqual(counts.counts, Counter({"бу": 2, "ул": 1}))
        self.assertEqual(counts.rows_read, 3)

    def test_hugging_punctuation_is_stripped(self) -> None:
        counts = self.parse({
            "a-sentences.txt": "1\t«Бу» — сүз.\n2\t(ул) килде.\n3\t…әмма соңгы.\n",
        })
        self.assertEqual(counts.counts, Counter({"бу": 1, "ул": 1, "әмма": 1}))

    def test_capitalization_and_nfd_fold_to_the_dictionary_form(self) -> None:
        counts = self.parse({
            # "Сәи\u0306" is "сәй" in NFD: й = и + U+0306.
            "a-sentences.txt": "1\tБу сүз.\n2\tСәи\u0306 хәл.\n",
        })
        self.assertEqual(counts.counts, Counter({"бу": 1, "сәй": 1}))

    def test_tokens_outside_the_alphabet_are_dropped(self) -> None:
        counts = self.parse({
            # a digit token, a Latin token, an empty-after-strip token, an empty sentence
            "a-sentences.txt":
                "1\t00 сәгатьтә.\n2\tHello world.\n3\t— төшерелде.\n4\t\n5\tбу.\n",
        })
        self.assertEqual(counts.counts, Counter({"бу": 1}))
        self.assertEqual(counts.tokens_accepted, 1)
        self.assertEqual(counts.tokens_dropped, 4)

    def test_only_the_first_token_is_counted(self) -> None:
        counts = self.parse({"a-sentences.txt": "1\tбу ул бу.\n"})
        self.assertEqual(counts.counts, Counter({"бу": 1}))

    def test_a_row_without_the_id_tab_shape_fails_closed(self) -> None:
        with self.assertRaises(pack.SentStartPackError):
            self.parse({"a-sentences.txt": "бу беренче сүз.\n"})
        with self.assertRaises(pack.SentStartPackError):
            self.parse({"a-sentences.txt": "1\tбу\tsүз.\n"})

    def test_no_rows_fails_closed(self) -> None:
        with self.assertRaises(pack.SentStartPackError):
            self.parse({"a-sentences.txt": "\n\n"})

    def test_nothing_surviving_normalization_fails_closed(self) -> None:
        with self.assertRaises(pack.SentStartPackError):
            self.parse({"a-sentences.txt": "1\t123 456.\n"})

    def test_invalid_utf8_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad-sentences.txt"
            path.write_bytes(b"\xff\xfe not utf8")
            with self.assertRaises(pack.SentStartPackError):
                pack.read_first_tokens([path])

    def test_missing_file_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(OSError):
                pack.read_first_tokens([Path(directory) / "absent.txt"])


class ReadDictionaryWordsTest(unittest.TestCase):
    def test_roundtrip_through_a_real_schema2_asset(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = write_dictionary(Path(directory), ["бу", "ул", "әмма"])
            self.assertEqual(pack.read_dictionary_words(path), {"бу", "ул", "әмма"})

    def test_a_broken_asset_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "dict.tdict.zlib"
            path.write_bytes(b"not a zlib stream")
            with self.assertRaises(pack.SentStartPackError):
                pack.read_dictionary_words(path)


class BuildTableTest(unittest.TestCase):
    WORDS = frozenset({"бу", "ул", "әмма", "шулай", "бүген", "хәзер"})

    def build(self, counts, **kwargs) -> pack.SentStartTable:
        options = {"top": 64, "max_bytes": pack.MAX_ASSET_BYTES,
                   "min_records": 1, "max_records": pack.MAX_RECORDS}
        options.update(kwargs)
        return pack.build_table(counts, self.WORDS, **options)

    def test_rows_are_sorted_by_count_desc_then_word_asc(self) -> None:
        table = self.build(Counter({"ул": 5, "бу": 9, "әмма": 5, "шулай": 1}))
        rows = [line for line in table.text.splitlines() if not line.startswith("#")]
        self.assertEqual(rows, ["бу\t9", "ул\t5", "әмма\t5", "шулай\t1"])
        self.assertEqual(table.record_count, 4)
        self.assertEqual(table.top_frequency, 9)
        self.assertEqual(table.bottom_frequency, 1)

    def test_the_header_carries_attribution_and_no_data(self) -> None:
        table = self.build(Counter({"бу": 1}))
        header = [line for line in table.text.splitlines() if line.startswith("#")]
        self.assertTrue(header)
        self.assertTrue(all(line.startswith("#") for line in header))
        joined = "\n".join(header)
        self.assertIn("Leipzig", joined)
        self.assertIn("CC BY 4.0", joined)

    def test_words_outside_the_dictionary_never_enter(self) -> None:
        table = self.build(Counter({"бу": 3, "читсүз": 100, "ул": 2}))
        rows = [line for line in table.text.splitlines() if not line.startswith("#")]
        self.assertEqual(rows, ["бу\t3", "ул\t2"])

    def test_top_cut(self) -> None:
        table = self.build(
            Counter({"бу": 9, "ул": 5, "әмма": 3, "шулай": 1}), top=2)
        rows = [line for line in table.text.splitlines() if not line.startswith("#")]
        self.assertEqual(rows, ["бу\t9", "ул\t5"])

    def test_determinism_repeated_builds_byte_identical(self) -> None:
        counts = Counter({"ул": 5, "бу": 9, "әмма": 5})
        first = self.build(counts)
        second = self.build(counts)
        self.assertEqual(first.data, second.data)
        self.assertEqual(first.sha256, second.sha256)

    def test_no_dictionary_word_surviving_fails_closed(self) -> None:
        with self.assertRaises(pack.SentStartPackError):
            self.build(Counter({"читсүз": 100}))

    def test_a_nonpositive_top_fails_closed(self) -> None:
        with self.assertRaises(pack.SentStartPackError):
            self.build(Counter({"бу": 1}), top=0)

    def test_byte_guardrail_raises(self) -> None:
        with self.assertRaises(pack.SentStartGuardrailError):
            self.build(Counter({"бу": 1}), max_bytes=10)

    def test_record_guardrails_raise(self) -> None:
        counts = Counter({"бу": 4, "ул": 3, "әмма": 2})
        with self.assertRaises(pack.SentStartGuardrailError):
            self.build(counts, max_records=2)
        with self.assertRaises(pack.SentStartGuardrailError):
            self.build(counts, min_records=10)


class CliContractTest(unittest.TestCase):
    WORDS = ["бүген", "бу", "ул", "әмма", "шулай", "хәзер"]
    SENTENCES = (
        "1\tбу беренче.\n2\tул икенче.\n3\tбу tagыр.\n4\tәмма соңгы.\n"
        "5\tчитсүз калды.\n6\tбүген яңа.\n7\tбу өченче.\n"
    )

    def test_successful_build_exit_zero_writes_asset_and_json(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            sentences = write_tmp(directory, "a-sentences.txt", self.SENTENCES)
            dictionary = write_dictionary(directory, self.WORDS)
            out = directory / "tatar_sentstart_v1.txt"
            code, stdout = run_main([sentences], dictionary, out, MIN_RECORDS=1)
            self.assertEqual(code, 0)
            self.assertTrue(out.exists())
            rows = [line for line in out.read_text(encoding="utf-8").splitlines()
                    if not line.startswith("#")]
            self.assertEqual(rows, ["бу\t3", "бүген\t1", "ул\t1", "әмма\t1"])
            payload = json.loads(stdout)
            self.assertEqual(payload["record_count"], 4)
            self.assertEqual(payload["asset_bytes"], len(out.read_bytes()))
            self.assertEqual(len(payload["asset_sha256"]), 64)
            self.assertEqual(payload["rows_read"], 7)
            self.assertEqual(payload["tokens_accepted"], 7)
            self.assertEqual(payload["tokens_dropped"], 0)
            self.assertEqual(payload["dictionary_words"], len(self.WORDS))
            self.assertEqual(payload["top_frequency"], 3)
            self.assertEqual(payload["bottom_frequency"], 1)

    def test_bad_sentences_exit_2_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            sentences = write_tmp(directory, "a-sentences.txt", "no tab here\n")
            dictionary = write_dictionary(directory, self.WORDS)
            out = directory / "out.txt"
            code, _ = run_main([sentences], dictionary, out)
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_a_missing_dictionary_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            sentences = write_tmp(directory, "a-sentences.txt", self.SENTENCES)
            out = directory / "out.txt"
            code, _ = run_main([sentences], directory / "absent.tdict.zlib", out)
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_guardrail_exits_4(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            sentences = write_tmp(directory, "a-sentences.txt", self.SENTENCES)
            dictionary = write_dictionary(directory, self.WORDS)
            out = directory / "out.txt"
            code, _ = run_main([sentences], dictionary, out, MAX_RECORDS=2)
            self.assertEqual(code, 4)
            self.assertFalse(out.exists())

    def test_failure_does_not_publish_a_partial_asset(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            sentences = write_tmp(directory, "a-sentences.txt", "no tab\n")
            dictionary = write_dictionary(directory, self.WORDS)
            out = directory / "out.txt"
            out.write_bytes(b"OLD CONTENT")
            code, _ = run_main([sentences], dictionary, out)
            self.assertEqual(code, 2)
            self.assertEqual(out.read_bytes(), b"OLD CONTENT")


# The shipped asset is pinned: a data change is a written decision that also
# updates these numbers (rebuild recipe in docs/TT-SUGGESTIONS.md, P4 section).
EXPECTED_ASSET_SHA256 = "f84e82074fa5a5cdca91c41184e8486a0c0afac951c4e2e1cbcc123afbc22216"
EXPECTED_RECORD_COUNT = 64


class CommittedAssetTest(unittest.TestCase):
    """The shipped asset against the shipped dictionary (live tree, no corpus)."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.asset_bytes = SENTSTART_ASSET.read_bytes()
        cls.asset_text = cls.asset_bytes.decode("utf-8")
        cls.lines = cls.asset_text.splitlines()
        cls.rows = [line for line in cls.lines if not line.startswith("#")]
        cls.records = []
        for line in cls.rows:
            word, freq = line.split("\t")
            cls.records.append((word, int(freq)))

    def test_asset_is_pinned(self) -> None:
        digest = hashlib.sha256(self.asset_bytes).hexdigest()
        self.assertEqual(digest, EXPECTED_ASSET_SHA256)
        self.assertEqual(len(self.records), EXPECTED_RECORD_COUNT)

    def test_shape_and_guardrails(self) -> None:
        self.assertTrue(self.asset_text.endswith("\n"))
        self.assertNotIn("\r", self.asset_text)
        self.assertLessEqual(len(self.asset_bytes), pack.MAX_ASSET_BYTES)
        self.assertGreaterEqual(len(self.records), pack.MIN_RECORDS)
        self.assertLessEqual(len(self.records), pack.MAX_RECORDS)
        self.assertGreater(len(self.lines), len(self.records))  # a header exists

    def test_every_row_is_a_word_and_a_positive_count(self) -> None:
        for line in self.rows:
            fields = line.split("\t")
            self.assertEqual(len(fields), 2, msg=line[:60])
            self.assertTrue(all(fields), msg=line[:60])
            self.assertGreater(int(fields[1]), 0, msg=line[:60])

    def test_rows_are_sorted_by_count_desc_then_word_asc(self) -> None:
        keys = [(-freq, word) for word, freq in self.records]
        self.assertEqual(keys, sorted(keys))
        words = [word for word, _freq in self.records]
        self.assertEqual(len(words), len(set(words)))

    def test_every_word_passes_normalize_word(self) -> None:
        for word, _freq in self.records:
            normalized, reason = coverage.normalize_word(word)
            self.assertIsNone(reason, msg=f"{word}: {reason}")
            self.assertEqual(normalized, word)

    def test_every_word_is_in_the_shipped_dictionary(self) -> None:
        dictionary_words = pack.read_dictionary_words(DICTIONARY_ASSET)
        for word, _freq in self.records:
            self.assertIn(word, dictionary_words)

    def test_header_carries_the_attribution(self) -> None:
        header = "\n".join(line for line in self.lines if line.startswith("#"))
        self.assertIn("Leipzig", header)
        self.assertIn("CC BY 4.0", header)
        self.assertIn("tat_mixed_2015_1M", header)
        self.assertIn("tat_web_2018_1M", header)


class RussianLanguageTest(unittest.TestCase):
    """The P3b --language rus path: ru normalization, ru header, ru dictionary filter."""

    WORDS = ["в", "по", "он", "это", "ещё", "как"]
    SENTENCES = (
        "1\tВ комнате.\n2\tПо дороге.\n3\tв саду.\n4\tОн сказал.\n5\tЕщё раз.\n"
        "6\tәти мимо.\n7\t123 цифры.\n"
    )

    def test_ru_table_normalizes_with_the_russian_alphabet(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sentences = write_tmp(Path(directory), "ru-sentences.txt", self.SENTENCES)
            counts = pack.read_first_tokens([sentences], coverage.RUSSIAN)
        # В folds to в; Әти is dropped (ә is outside the Russian alphabet), 123 too.
        self.assertEqual(
            counts.counts, Counter({"в": 2, "по": 1, "он": 1, "ещё": 1}))
        self.assertEqual(counts.tokens_dropped, 2)

    def test_ru_cli_build_writes_the_ru_header_and_filter(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            sentences = write_tmp(directory, "ru-sentences.txt", self.SENTENCES)
            dictionary = write_dictionary(
                directory, self.WORDS, coverage.RUSSIAN)
            out = directory / "russian_sentstart_v1.txt"
            code, stdout = run_main(
                [sentences], dictionary, out, language="rus", MIN_RECORDS=1)
            self.assertEqual(code, 0)
            text = out.read_text(encoding="utf-8")
            rows = [line for line in text.splitlines() if not line.startswith("#")]
            self.assertEqual(rows, ["в\t2", "ещё\t1", "он\t1", "по\t1"])
            header = "\n".join(line for line in text.splitlines()
                               if line.startswith("#"))
            self.assertIn("Russian sentence-start suggestions", header)
            self.assertIn("rus_news_2022_1M", header)
            self.assertIn("rus_news_2019_1M", header)
            self.assertIn("rus_wikipedia_2021_1M", header)
            payload = json.loads(stdout)
            self.assertEqual(payload["language"], "rus")
            self.assertEqual(payload["record_count"], 4)

    def test_the_tat_default_keeps_the_tat_header(self) -> None:
        table = pack.build_table(
            Counter({"бу": 2, "ул": 1}), frozenset({"бу", "ул"}),
            top=64, max_bytes=pack.MAX_ASSET_BYTES, min_records=1,
            max_records=pack.MAX_RECORDS)
        header = "\n".join(line for line in table.text.splitlines()
                           if line.startswith("#"))
        self.assertIn("Tatar sentence-start suggestions", header)
        self.assertIn("tat_mixed_2015_1M", header)


# The shipped Russian asset (P3b) is pinned the same way: a data change is a
# written decision that also updates these numbers (rebuild recipe in
# docs/ROADMAP-P1.md).
EXPECTED_RU_ASSET_SHA256 = "ffab114daf924d8f23f295d833a0d90df7298b16fb139b3ba1bdb87a7783f86d"
EXPECTED_RU_RECORD_COUNT = 64


class CommittedRussianAssetTest(unittest.TestCase):
    """The shipped Russian asset against the shipped Russian dictionary (live tree)."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.asset_bytes = RU_SENTSTART_ASSET.read_bytes()
        cls.asset_text = cls.asset_bytes.decode("utf-8")
        cls.lines = cls.asset_text.splitlines()
        cls.rows = [line for line in cls.lines if not line.startswith("#")]
        cls.records = []
        for line in cls.rows:
            word, freq = line.split("\t")
            cls.records.append((word, int(freq)))

    def test_asset_is_pinned(self) -> None:
        digest = hashlib.sha256(self.asset_bytes).hexdigest()
        self.assertEqual(digest, EXPECTED_RU_ASSET_SHA256)
        self.assertEqual(len(self.records), EXPECTED_RU_RECORD_COUNT)

    def test_shape_and_guardrails(self) -> None:
        self.assertTrue(self.asset_text.endswith("\n"))
        self.assertNotIn("\r", self.asset_text)
        self.assertLessEqual(len(self.asset_bytes), pack.MAX_ASSET_BYTES)
        self.assertGreaterEqual(len(self.records), pack.MIN_RECORDS)
        self.assertLessEqual(len(self.records), pack.MAX_RECORDS)

    def test_every_row_is_a_word_and_a_positive_count(self) -> None:
        for line in self.rows:
            fields = line.split("\t")
            self.assertEqual(len(fields), 2, msg=line[:60])
            self.assertTrue(all(fields), msg=line[:60])
            self.assertGreater(int(fields[1]), 0, msg=line[:60])

    def test_rows_are_sorted_by_count_desc_then_word_asc(self) -> None:
        keys = [(-freq, word) for word, freq in self.records]
        self.assertEqual(keys, sorted(keys))
        words = [word for word, _freq in self.records]
        self.assertEqual(len(words), len(set(words)))

    def test_every_word_passes_normalize_word_with_the_russian_alphabet(self) -> None:
        for word, _freq in self.records:
            normalized, reason = coverage.normalize_word(
                word, coverage.RUSSIAN_ALPHABET)
            self.assertIsNone(reason, msg=f"{word}: {reason}")
            self.assertEqual(normalized, word)

    def test_every_word_is_in_the_shipped_russian_dictionary(self) -> None:
        dictionary_words = pack.read_dictionary_words(
            RU_DICTIONARY_ASSET, coverage.RUSSIAN)
        for word, _freq in self.records:
            self.assertIn(word, dictionary_words)

    def test_header_carries_the_attribution(self) -> None:
        header = "\n".join(line for line in self.lines if line.startswith("#"))
        self.assertIn("Leipzig", header)
        self.assertIn("CC BY 4.0", header)
        self.assertIn("rus_news_2022_1M", header)
        self.assertIn("rus_news_2019_1M", header)
        self.assertIn("rus_wikipedia_2021_1M", header)


if __name__ == "__main__":
    unittest.main()
