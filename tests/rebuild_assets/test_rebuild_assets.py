#!/usr/bin/env python3
"""Tests for scripts/rebuild_assets.py — the dictionary+bigram rebuild orchestrator.

Everything runs on synthetic fixtures in a temporary directory: a handful of Tatar words
instead of a 100k dictionary, a two-head bigram table instead of the real one. These tests
pin the tool's contract, not the data: it reads pins from the Kotlin files
rather than duplicating them, it rewrites them byte-carefully, and `--check` is green on
a consistent set and red — with the right diagnosis — on a diverged one.
"""

from __future__ import annotations

import io
import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY_ROOT / "scripts"))

import bigram_asset_pack  # noqa: E402
import dict_accept  # noqa: E402
import dictionary_pack  # noqa: E402
import rebuild_assets  # noqa: E402
import wordform_gen  # noqa: E402

# Words of the synthetic dictionary, most frequent first. The alphabet is the Tatar one:
# every letter here is also valid for the shipped assets, so the same validators apply.
WORDS = ["мәхәббәт", "китап", "су", "әни", "әти", "өлкә"]
FREQUENCIES = [900, 800, 700, 600, 500, 400]


def build_dictionary_asset(directory: Path, words=WORDS, frequencies=FREQUENCIES,
                           name="tatar_top100k_v1.tdict.zlib") -> Path:
    words_path = directory / "words.txt"
    words_path.write_text(
        "".join(
            f"{index}\t{word}\t{frequency}\n"
            for index, (word, frequency) in enumerate(zip(words, frequencies), start=1)
        ),
        encoding="utf-8",
    )
    built = dictionary_pack.build_dictionary([words_path], len(words))
    asset_path = directory / name
    asset_path.write_bytes(built.asset)
    return asset_path


def build_bigram_asset(directory: Path, heads, table, dictionary_asset: Path,
                       successes_per_head=2,
                       name="tatar_bigrams_v1.tatbigr.zlib") -> Path:
    """Schema 3: the table refers to the fixture dictionary by indices and by its raw SHA-256."""
    import hashlib

    parsed_dictionary = dictionary_pack.validate_asset(dictionary_asset.read_bytes())
    word_index = {word: index for index, word in enumerate(parsed_dictionary.words)}
    result = bigram_asset_pack.pack_bigram_table_v3(
        heads, table, successes_per_head, word_index,
        hashlib.sha256(parsed_dictionary.raw).digest(),
    )
    asset_path = directory / name
    asset_path.write_bytes(result.compressed)
    return asset_path


SHA0 = "0" * 64

# Dictionary path inside the fixture tree (created in FakeTreeTest.setUp).
DICT_ASSET = Path("app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib")

DICT_BLOCK = """\
        val {spec} = DictionaryArtifactSpec(
            family = "{family}",
            expectedCompressedSize = 1,
            expectedCompressedSha256 =
                "{sha}",
            expectedRawSize = 72,
            expectedRawSha256 =
                "{sha}",
            expectedEntryCount = 1,
        )"""

BIGRAM_BLOCK = """\
        val {spec} = BigramArtifactSpec(
            family = "{family}",
            expectedCompressedSize = 1,
            expectedCompressedSha256 =
                "{sha}",
            expectedRawSize = 96,
            expectedRawSha256 =
                "{sha}",
            expectedDictionaryRawSha256 =
                "{sha}",
            expectedHeadCount = 1,
        )"""


def write_fake_contracts(root: Path) -> None:
    dict_contract = root / rebuild_assets.DICT_CONTRACT
    dict_contract.parent.mkdir(parents=True, exist_ok=True)
    dict_contract.write_text(
        "// шапка, которая не должна пострадать\n"
        + DICT_BLOCK.format(spec="TATAR_TOP100K_V1", family="tatar_top100k", sha=SHA0)
        + "\n\n// комментарий между блоками\n"
        + DICT_BLOCK.format(spec="RUSSIAN_TOP100K_V1", family="russian_top100k", sha=SHA0)
        + "\n",
        encoding="utf-8",
    )
    bigram_contract = root / rebuild_assets.BIGRAM_CONTRACT
    bigram_contract.parent.mkdir(parents=True, exist_ok=True)
    bigram_contract.write_text(
        BIGRAM_BLOCK.format(spec="TATAR_BIGRAMS_V1", family="tatar_bigrams", sha=SHA0)
        + "\n"
        + BIGRAM_BLOCK.format(spec="RUSSIAN_BIGRAMS_V1", family="russian_bigrams", sha=SHA0)
        + "\n",
        encoding="utf-8",
    )


def pin_everything(root: Path, dictionary: rebuild_assets.DictionaryAsset,
                   bigram: rebuild_assets.BigramAsset) -> None:
    """What the rebuild's step 3 does: measure the assets, write the pins."""
    rebuild_assets.write_pins(
        root / rebuild_assets.DICT_CONTRACT,
        {dictionary.spec: rebuild_assets.measure_dictionary(root / dictionary.asset,
                                                            dictionary.tag)},
        "DictionaryArtifactSpec",
        "expectedEntryCount",
    )
    rebuild_assets.write_pins(
        root / rebuild_assets.BIGRAM_CONTRACT,
        {bigram.spec: rebuild_assets.measure_bigram(
            root / bigram.asset, root / dictionary.asset, bigram.dictionary
        )},
        "BigramArtifactSpec",
        "expectedHeadCount",
    )


def write_corpus_manifest(root: Path, contents=None) -> Path:
    """Manifest for the current registry: entries for `contents` ({name: bytes}) pin those
    bytes, every other registry name gets a dummy entry."""
    import hashlib

    contents = contents or {}
    data = {}
    for name in rebuild_assets.corpus_names():
        body = contents.get(name)
        data[name] = {
            "size": len(body) if body is not None else 1,
            "sha256": hashlib.sha256(body).hexdigest() if body is not None else SHA0,
            "source": "тест",
        }
    path = root / rebuild_assets.CORPUS_MANIFEST
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return path


class FakeTreeTest(unittest.TestCase):
    """A fake repository root: one Tatar dictionary, one bigram table, both contracts."""

    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        write_fake_contracts(self.root)
        asset_dir = self.root / "app/src/main/assets/dictionaries"
        asset_dir.mkdir(parents=True)
        build_dictionary_asset(asset_dir)
        bigram_dir = self.root / "app/src/main/assets/bigrams"
        bigram_dir.mkdir(parents=True)
        # Heads = the top-3 by frequency, each with a pair: the consistent baseline.
        self.table = {word: [(WORDS[0], 10)] for word in WORDS[:3]}
        build_bigram_asset(bigram_dir, WORDS[:3], self.table,
                           asset_dir / "tatar_top100k_v1.tdict.zlib")
        self.dictionary = rebuild_assets.DictionaryAsset(
            tag="tat", spec="TATAR_TOP100K_V1",
            asset="app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
        )
        self.bigram = rebuild_assets.BigramAsset(
            tag="tat", spec="TATAR_BIGRAMS_V1",
            asset="app/src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib",
            dictionary="tat", heads=3, successes_per_head=2,
            extra_heads=None, train=(),
        )
        self._saved = (rebuild_assets.DICTIONARIES, rebuild_assets.BIGRAMS)
        rebuild_assets.DICTIONARIES = (self.dictionary,)
        rebuild_assets.BIGRAMS = (self.bigram,)
        self.addCleanup(self._restore)
        write_corpus_manifest(self.root)

    def _restore(self):
        rebuild_assets.DICTIONARIES, rebuild_assets.BIGRAMS = self._saved

    def run_check(self, known_drift=None) -> tuple[int, dict]:
        stream = io.StringIO()
        code = rebuild_assets.run_check(self.root, known_drift, stream)
        return code, json.loads(stream.getvalue())

    def write_known_drift(self, data) -> Path:
        path = self.root / "known.json"
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        return path


class PinsIoTest(FakeTreeTest):
    def test_written_pins_read_back_exactly(self):
        pins = rebuild_assets.measure_dictionary(
            self.root / self.dictionary.asset, "tat")
        pin_everything(self.root, self.dictionary, self.bigram)
        read_back = rebuild_assets.read_pins(
            self.root / rebuild_assets.DICT_CONTRACT,
            "TATAR_TOP100K_V1", "DictionaryArtifactSpec", "expectedEntryCount")
        self.assertEqual(pins, read_back)

    def test_rewrite_touches_only_the_target_block(self):
        pin_everything(self.root, self.dictionary, self.bigram)
        text = (self.root / rebuild_assets.DICT_CONTRACT).read_text(encoding="utf-8")
        self.assertIn("// шапка, которая не должна пострадать", text)
        self.assertIn("// комментарий между блоками", text)
        # The Russian block was not in the update and still holds the dummy pins.
        russian = rebuild_assets.read_pins(
            self.root / rebuild_assets.DICT_CONTRACT,
            "RUSSIAN_TOP100K_V1", "DictionaryArtifactSpec", "expectedEntryCount")
        self.assertEqual(1, russian.count)
        self.assertEqual(SHA0, russian.raw_sha256)

    def test_sizes_are_written_with_underscore_separators(self):
        contract = self.root / rebuild_assets.DICT_CONTRACT
        pins = rebuild_assets.Pins(1_234_567, "a" * 64, 2_345_678, "b" * 64, 100_000)
        rebuild_assets.write_pins(contract, {"TATAR_TOP100K_V1": pins},
                                  "DictionaryArtifactSpec", "expectedEntryCount")
        text = contract.read_text(encoding="utf-8")
        self.assertIn("expectedCompressedSize = 1_234_567,", text)
        self.assertIn("expectedEntryCount = 100_000,", text)

    def test_a_block_that_disappeared_fails_closed(self):
        contract = self.root / rebuild_assets.DICT_CONTRACT
        contract.write_text("// пусто\n", encoding="utf-8")
        with self.assertRaises(rebuild_assets.ContractError):
            rebuild_assets.read_pins(contract, "TATAR_TOP100K_V1",
                                     "DictionaryArtifactSpec", "expectedEntryCount")


class CheckTest(FakeTreeTest):
    def test_green_on_a_consistent_set(self):
        pin_everything(self.root, self.dictionary, self.bigram)
        code, report = self.run_check()
        self.assertEqual(0, code, report)
        self.assertTrue(report["ok"])
        self.assertEqual("ok", report["dictionaries"]["tat"]["verdict"])
        self.assertEqual("ok", report["bigrams"]["tat"]["verdict"])

    def test_red_when_the_asset_moved_but_the_pins_did_not(self):
        pin_everything(self.root, self.dictionary, self.bigram)
        # Rebuild the dictionary with different frequencies: valid asset, stale pins.
        build_dictionary_asset(self.root / "app/src/main/assets/dictionaries",
                               frequencies=[100 + i for i in range(len(WORDS))])
        code, report = self.run_check()
        self.assertEqual(1, code)
        self.assertEqual("mismatch", report["dictionaries"]["tat"]["verdict"])
        self.assertTrue(report["dictionaries"]["tat"]["problems"])

    def test_a_head_outside_the_dictionary_is_unrepresentable(self):
        # Schema 3 stores indices into the dictionary, so a head outside the dictionary
        # cannot be encoded: the packer fails before writing the file.
        table = dict(self.table)
        table["дус"] = [(WORDS[0], 5)]  # "дус" is not in the dictionary
        with self.assertRaises(bigram_asset_pack.BigramInputError):
            build_bigram_asset(self.root / "app/src/main/assets/bigrams",
                               WORDS[:3] + ["дус"], table, self.root / DICT_ASSET)

    def test_a_table_packed_against_another_dictionary_is_red(self):
        # Schema 3 link: the table was packed against one dictionary, then the dictionary
        # and pins changed; the table header names the OLD dictionary, so the check fails.
        pin_everything(self.root, self.dictionary, self.bigram)
        build_dictionary_asset(self.root / "app/src/main/assets/dictionaries",
                               frequencies=[100 + i for i in range(len(WORDS))])
        code, report = self.run_check()
        self.assertEqual(1, code)
        self.assertEqual("mismatch", report["bigrams"]["tat"]["verdict"])

    def test_drift_is_counted_in_both_directions(self):
        # The table is packed with heads in the OLD frequency order: "өлкә" instead of "мәхәббәт".
        build_bigram_asset(self.root / "app/src/main/assets/bigrams",
                           [WORDS[5], WORDS[1], WORDS[2]],
                           {w: [(WORDS[1], 7)] for w in (WORDS[5], WORDS[1], WORDS[2])},
                           self.root / DICT_ASSET)
        pin_everything(self.root, self.dictionary, self.bigram)
        code, report = self.run_check()
        self.assertEqual(1, code)
        drift = report["bigrams"]["tat"]["drift"]
        self.assertEqual(1, drift["missing_top_heads"])  # "мәхәббәт" is missing
        self.assertEqual(1, drift["unexpected_heads"])  # "өлкә" is extra
        self.assertEqual(0, drift["heads_outside_dictionary"])
        self.assertEqual("drift", report["bigrams"]["tat"]["verdict"])

    def test_known_drift_is_accepted_only_on_exact_numbers(self):
        build_bigram_asset(self.root / "app/src/main/assets/bigrams",
                           [WORDS[1], WORDS[2]],  # "мәхәббәт" is not packed
                           {w: [(WORDS[1], 7)] for w in (WORDS[1], WORDS[2])},
                           self.root / DICT_ASSET)
        pin_everything(self.root, self.dictionary, self.bigram)
        key = "bigrams/tatar_bigrams_v1.tatbigr.zlib"

        exact = self.write_known_drift(
            {key: {"missing_top_heads": 1, "unexpected_heads": 0, "reason": "тест"}})
        code, report = self.run_check(exact)
        self.assertEqual(0, code, report)
        self.assertEqual("known-drift", report["bigrams"]["tat"]["verdict"])

        wrong = self.write_known_drift(
            {key: {"missing_top_heads": 2, "unexpected_heads": 0, "reason": "тест"}})
        code, report = self.run_check(wrong)
        self.assertEqual(1, code)
        self.assertEqual("drift", report["bigrams"]["tat"]["verdict"])

    def test_a_stale_known_drift_entry_fails_the_check(self):
        pin_everything(self.root, self.dictionary, self.bigram)  # no mismatch
        stale = self.write_known_drift({
            "bigrams/tatar_bigrams_v1.tatbigr.zlib": {
                "missing_top_heads": 1, "unexpected_heads": 0, "reason": "тест"}})
        code, report = self.run_check(stale)
        self.assertEqual(1, code)
        self.assertEqual("stale-known-drift", report["bigrams"]["tat"]["verdict"])

    def test_a_known_drift_entry_for_an_unknown_asset_fails(self):
        pin_everything(self.root, self.dictionary, self.bigram)
        alien = self.write_known_drift({
            "bigrams/no_such_asset.tatbigr.zlib": {
                "missing_top_heads": 1, "unexpected_heads": 1, "reason": "тест"}})
        code, _report = self.run_check(alien)
        self.assertEqual(1, code)

    def test_missing_contract_is_an_input_error(self):
        (self.root / rebuild_assets.BIGRAM_CONTRACT).unlink()
        stream = io.StringIO()
        code = rebuild_assets.run_check(self.root, None, stream)
        self.assertEqual(2, code)

    def test_unparseable_dict_contract_is_an_input_error(self):
        # ContractError (the contract could not be parsed) gives exit code 2, not an
        # unhandled traceback with code 1.
        contract = self.root / rebuild_assets.DICT_CONTRACT
        text = contract.read_text(encoding="utf-8")
        contract.write_text(text.replace("expectedCompressedSize", "expectedGone"),
                            encoding="utf-8")
        stream = io.StringIO()
        code = rebuild_assets.run_check(self.root, None, stream)
        self.assertEqual(2, code)

    def test_unparseable_bigram_contract_is_an_input_error(self):
        contract = self.root / rebuild_assets.BIGRAM_CONTRACT
        text = contract.read_text(encoding="utf-8")
        contract.write_text(text.replace("expectedHeadCount", "expectedGone"),
                            encoding="utf-8")
        stream = io.StringIO()
        code = rebuild_assets.run_check(self.root, None, stream)
        self.assertEqual(2, code)


class PackArgvTest(unittest.TestCase):
    """The canned pack commands and their parameters, pinned verbatim."""

    def test_tatar_command(self):
        argv = rebuild_assets.bigram_pack_argv(
            Path("/root"), Path("/corpora"), Path("/work"), rebuild_assets.BIGRAMS[0])
        text = " ".join(argv)
        self.assertIn("--heads 10132", text)
        # Four successors per head (the table's K; unrelated to the number of strip cells).
        self.assertIn("--successes-per-head 4", text)
        self.assertIn("--extra-heads /root/scripts/bigram_extra_heads_tat.txt", text)
        self.assertIn("--language tat", text)
        self.assertIn("/corpora/tat_mixed_2015_1M-sentences.txt", text)
        self.assertIn("/corpora/tat_web_2018_1M-sentences.txt", text)
        # Conversational input.
        self.assertIn("/corpora/tt_conv_train90-sentences.txt", text)

    def test_russian_command(self):
        argv = rebuild_assets.bigram_pack_argv(
            Path("/root"), Path("/corpora"), Path("/work"), rebuild_assets.BIGRAMS[1])
        text = " ".join(argv)
        self.assertIn("--heads 10000", text)
        self.assertIn("--successes-per-head 4", text)
        self.assertNotIn("--extra-heads", text)
        self.assertIn("--language rus", text)
        for name in ("rus_news_2022_1M", "rus_news_2019_1M", "rus_wikipedia_2021_1M"):
            self.assertIn(f"/corpora/{name}-sentences.txt", text)
        # Conversational input.
        self.assertIn("/corpora/rus_conv_thinned60-sentences.txt", text)


class DictAcceptArgvTest(unittest.TestCase):
    """The canned dictionary-rebuild commands, pinned like the bigram ones."""

    def test_tatar_command_carries_top_and_extra_entries(self):
        dictionary = rebuild_assets.DICTIONARIES[0]
        argv = rebuild_assets.dict_accept_argv(
            Path("/root"), Path("/baseline"), Path("/work"), dictionary,
            Path("/work/wordforms-admitted-tt.tsv"))
        text = " ".join(argv)
        self.assertIn("pack", text)
        self.assertIn("--baseline /baseline", text)
        self.assertIn("--write", text)
        self.assertIn("--only tat", text)
        self.assertIn(f"--top {dictionary.top}", text)
        self.assertIn("--extra-entries /work/wordforms-admitted-tt.tsv", text)
        self.assertIn("/work/dict-accept-pack-tat.json", text)

    def test_russian_command_has_no_extra_entries(self):
        dictionary = rebuild_assets.DICTIONARIES[1]
        argv = rebuild_assets.dict_accept_argv(
            Path("/root"), Path("/baseline"), Path("/work"), dictionary, None)
        text = " ".join(argv)
        self.assertIn("--only rus", text)
        self.assertIn("--top 100000", text)
        self.assertNotIn("--extra-entries", text)


class TwoSideTreeTest(unittest.TestCase):
    """A fake tree with both languages: dictionaries, tables, baseline and corpus directory."""

    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        write_fake_contracts(self.root)
        dictionaries = self.root / "app/src/main/assets/dictionaries"
        dictionaries.mkdir(parents=True)
        build_dictionary_asset(dictionaries)
        build_dictionary_asset(dictionaries, words=["мама", "папа", "работа"],
                               frequencies=[30, 20, 10],
                               name="russian_top100k_v1.tdict.zlib")
        bigrams = self.root / "app/src/main/assets/bigrams"
        bigrams.mkdir(parents=True)
        build_bigram_asset(bigrams, WORDS[:3], {word: [(WORDS[0], 10)] for word in WORDS[:3]},
                           dictionaries / "tatar_top100k_v1.tdict.zlib")
        build_bigram_asset(bigrams, ["мама"], {"мама": [("папа", 5)]},
                           dictionaries / "russian_top100k_v1.tdict.zlib",
                           name="russian_bigrams_v1.tatbigr.zlib")
        self._saved = (rebuild_assets.DICTIONARIES, rebuild_assets.BIGRAMS)
        rebuild_assets.DICTIONARIES = (
            rebuild_assets.DictionaryAsset(
                tag="tat", spec="TATAR_TOP100K_V1",
                asset="app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib"),
            rebuild_assets.DictionaryAsset(
                tag="rus", spec="RUSSIAN_TOP100K_V1",
                asset="app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib"),
        )
        rebuild_assets.BIGRAMS = (
            rebuild_assets.BigramAsset(
                tag="tat", spec="TATAR_BIGRAMS_V1",
                asset="app/src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib",
                dictionary="tat", heads=3, successes_per_head=2,
                extra_heads=None, train=("tt_corpus-sentences.txt",)),
            rebuild_assets.BigramAsset(
                tag="rus", spec="RUSSIAN_BIGRAMS_V1",
                asset="app/src/main/assets/bigrams/russian_bigrams_v1.tatbigr.zlib",
                dictionary="rus", heads=1, successes_per_head=2,
                extra_heads=None, train=("rus_corpus-sentences.txt",)),
        )
        self.addCleanup(self._restore)
        self.baseline = self.root / "baseline"
        self.baseline.mkdir()
        (self.baseline / "tatar_top100k_v1.tdict.zlib").write_bytes(b"tt")
        (self.baseline / "russian_top100k_v1.tdict.zlib").write_bytes(b"ru")
        self.corpora = self.root / "corpora"
        self.corpora.mkdir()
        # The tests below write every corpus file empty, so the manifest pins empty files.
        write_corpus_manifest(
            self.root, {name: b"" for name in rebuild_assets.corpus_names()})

    def _restore(self):
        rebuild_assets.DICTIONARIES, rebuild_assets.BIGRAMS = self._saved

    def run_rebuild(self, only):
        return rebuild_assets.run_rebuild(
            self.root, self.baseline, self.corpora, self.root / "work", None, only=only)


class OnlyModeTest(TwoSideTreeTest):
    """`--only`: one side rebuilds, the other must stay byte-identical."""

    def test_only_tatar_needs_no_russian_inputs(self):
        # The tatar inputs are complete (train + words files); the russian corpus is
        # absent and must NOT be required in --only tatar. The exceptions table is not
        # in the fake tree, so the first run stops at the input gate with exit 2…
        for name in rebuild_assets.WORDFORM_FREQUENCY_SOURCES:
            (self.corpora / name).write_text("", encoding="utf-8")
        (self.corpora / "tt_corpus-sentences.txt").write_text("", encoding="utf-8")
        self.assertEqual(2, self.run_rebuild("tatar"))
        # …and with the exceptions table present the gate passes: the run proceeds into
        # the word-form stage and dies on the fake baseline bytes (dict_accept refuses a
        # baseline whose SHA is not the shipped-1.8.4 one) — a SystemExit, not the input-gate
        # return code.
        scripts_dir = self.root / "scripts"
        scripts_dir.mkdir()
        (scripts_dir / "wordform_exceptions_tat.tsv").write_text("# empty\n",
                                                                 encoding="utf-8")
        with self.assertRaises(SystemExit) as caught:
            self.run_rebuild("tatar")
        self.assertIn("не ассет 1.8.4", str(caught.exception))

    def test_only_russian_needs_no_tatar_inputs(self):
        # No tatar corpus, no words files, no exceptions table — the russian side is
        # complete, so the input gate passes and the run dies at the pack step (the
        # fake root has no scripts/dict_accept.py for the subprocess).
        (self.corpora / "rus_corpus-sentences.txt").write_text("", encoding="utf-8")
        with self.assertRaises(SystemExit) as caught:
            self.run_rebuild("russian")
        self.assertIn("шаг пересборки упал", str(caught.exception))

    def test_untouched_side_snapshot_detects_asset_and_pin_moves(self):
        snapshot = rebuild_assets._side_snapshot(self.root, frozenset({"rus"}))
        # Nothing moved: a fresh snapshot equals the taken one.
        self.assertEqual(snapshot, rebuild_assets._side_snapshot(self.root, frozenset({"rus"})))
        # Move the russian asset: the snapshot must catch it.
        asset = self.root / "app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib"
        asset.write_bytes(asset.read_bytes() + b"x")
        moved = rebuild_assets._side_snapshot(self.root, frozenset({"rus"}))
        self.assertNotEqual(snapshot, moved)
        self.assertEqual(snapshot["pins:RUSSIAN_TOP100K_V1"], moved["pins:RUSSIAN_TOP100K_V1"])

    def test_check_rejects_only(self):
        code = rebuild_assets.main(["--check", "--only", "tatar", "--root", str(self.root)])
        self.assertEqual(2, code)


class CorpusManifestRebuildTest(TwoSideTreeTest):
    """The rebuild verifies the corpus bytes against the manifest before writing anything."""

    def write_corpora(self, contents):
        for name in rebuild_assets.corpus_names():
            (self.corpora / name).write_bytes(contents.get(name, b""))

    def tree_state(self):
        return {
            str(path.relative_to(self.root)): path.read_bytes()
            for path in sorted(self.root.rglob("*"))
            if path.is_file()
        }

    def assert_stops_without_writing(self, only, expected_code, message):
        before = self.tree_state()
        stderr = io.StringIO()
        saved, sys.stderr = sys.stderr, stderr
        try:
            code = self.run_rebuild(only)
        finally:
            sys.stderr = saved
        self.assertEqual(expected_code, code, stderr.getvalue())
        self.assertIn(message, stderr.getvalue())
        self.assertFalse((self.root / "work").exists())
        self.assertEqual(before, self.tree_state())

    def test_a_size_change_stops_the_rebuild(self):
        self.write_corpora({"rus_corpus-sentences.txt": "1\tновая строка\n".encode("utf-8")})
        self.assert_stops_without_writing("russian", 1, "rus_corpus-sentences.txt: size")

    def test_a_same_size_content_change_stops_the_rebuild(self):
        write_corpus_manifest(
            self.root,
            {**{name: b"" for name in rebuild_assets.corpus_names()},
             "rus_corpus-sentences.txt": b"1\ta\n"})
        self.write_corpora({"rus_corpus-sentences.txt": b"1\tb\n"})
        self.assert_stops_without_writing("russian", 1, "rus_corpus-sentences.txt: sha256")

    def test_a_full_rebuild_checks_both_languages(self):
        (self.root / "scripts").mkdir()
        (self.root / rebuild_assets.WORDFORM_EXCEPTIONS).write_text("# empty\n", encoding="utf-8")
        self.write_corpora({rebuild_assets.WORDFORM_FREQUENCY_SOURCES[0]: "1\tсу\t1\n".encode("utf-8")})
        self.assert_stops_without_writing(None, 1, rebuild_assets.WORDFORM_FREQUENCY_SOURCES[0])

    def test_only_checks_the_selected_language(self):
        # A changed Tatar corpus does not block a Russian rebuild: the run passes the corpus
        # check and dies at the pack step (the fake root has no scripts/dict_accept.py).
        self.write_corpora({"tt_corpus-sentences.txt": "1\tсу\n".encode("utf-8")})
        with self.assertRaises(SystemExit) as caught:
            self.run_rebuild("russian")
        self.assertIn("шаг пересборки упал", str(caught.exception))

    def test_a_missing_manifest_stops_the_rebuild(self):
        self.write_corpora({})
        (self.root / rebuild_assets.CORPUS_MANIFEST).unlink()
        self.assert_stops_without_writing("russian", 2, "манифест корпусов")


class CorpusManifestFormatTest(unittest.TestCase):
    """The manifest entry set must equal the corpus registry, and every entry is well formed."""

    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.path = write_corpus_manifest(self.root)

    def rewrite(self, change):
        data = json.loads(self.path.read_text(encoding="utf-8"))
        change(data)
        self.path.write_text(json.dumps(data), encoding="utf-8")

    def test_the_generated_manifest_loads(self):
        manifest = rebuild_assets.load_corpus_manifest(self.path)
        self.assertEqual(rebuild_assets.corpus_names(), sorted(manifest))

    def test_a_missing_entry_is_rejected(self):
        self.rewrite(lambda data: data.pop(rebuild_assets.corpus_names()[0]))
        with self.assertRaises(rebuild_assets.CorpusManifestError):
            rebuild_assets.load_corpus_manifest(self.path)

    def test_a_stale_entry_is_rejected(self):
        self.rewrite(lambda data: data.update(
            {"old-sentences.txt": {"size": 1, "sha256": SHA0, "source": "тест"}}))
        with self.assertRaises(rebuild_assets.CorpusManifestError):
            rebuild_assets.load_corpus_manifest(self.path)

    def test_malformed_entries_are_rejected(self):
        name = rebuild_assets.corpus_names()[0]
        for entry in (
            {"size": 1, "sha256": "0" * 63, "source": "тест"},
            {"size": 1, "sha256": "A" * 64, "source": "тест"},
            {"size": -1, "sha256": SHA0, "source": "тест"},
            {"size": True, "sha256": SHA0, "source": "тест"},
            {"size": "1", "sha256": SHA0, "source": "тест"},
            {"size": 1, "sha256": SHA0},
        ):
            with self.subTest(entry=entry):
                self.rewrite(lambda data: data.update({name: entry}))
                with self.assertRaises(rebuild_assets.CorpusManifestError):
                    rebuild_assets.load_corpus_manifest(self.path)

    def test_the_registry_lists_every_corpus_the_rebuild_reads(self):
        names = set(rebuild_assets.corpus_names())
        for bigram in rebuild_assets.BIGRAMS:
            self.assertLessEqual(set(bigram.train), names)
        self.assertLessEqual(set(rebuild_assets.WORDFORM_FREQUENCY_SOURCES), names)
        self.assertEqual(
            set(rebuild_assets.BIGRAMS[1].train), set(rebuild_assets.corpus_names({"rus"})))


class CorpusManifestCheckTest(FakeTreeTest):
    """--check always validates the manifest and verifies the files only with a corpus dir."""

    def setUp(self):
        super().setUp()
        pin_everything(self.root, self.dictionary, self.bigram)
        self.corpora = self.root / "corpora"
        self.corpora.mkdir()
        self.contents = {name: f"1\t{name}\t1\n".encode("utf-8")
                         for name in rebuild_assets.corpus_names()}
        for name, body in self.contents.items():
            (self.corpora / name).write_bytes(body)
        write_corpus_manifest(self.root, self.contents)

    def run_check(self, corpus_dir=None) -> tuple[int, dict]:
        stream = io.StringIO()
        code = rebuild_assets.run_check(self.root, None, stream, corpus_dir=corpus_dir)
        return code, json.loads(stream.getvalue())

    def test_without_a_corpus_dir_the_files_are_not_checked(self):
        code, report = self.run_check()
        self.assertEqual(0, code, report)
        self.assertEqual("not-checked", report["corpus"]["verdict"])

    def test_matching_files_pass(self):
        code, report = self.run_check(self.corpora)
        self.assertEqual(0, code, report)
        self.assertEqual("ok", report["corpus"]["verdict"])
        self.assertEqual({"ok"}, set(report["corpus"]["files"].values()))

    def test_a_changed_file_fails(self):
        name = rebuild_assets.corpus_names()[0]
        (self.corpora / name).write_bytes(self.contents[name].upper())
        code, report = self.run_check(self.corpora)
        self.assertEqual(1, code)
        self.assertEqual("mismatch", report["corpus"]["verdict"])
        self.assertTrue(report["corpus"]["files"][name].startswith("sha256:"))

    def test_a_missing_file_fails(self):
        name = rebuild_assets.corpus_names()[0]
        (self.corpora / name).unlink()
        code, report = self.run_check(self.corpora)
        self.assertEqual(1, code)
        self.assertEqual("missing", report["corpus"]["verdict"])
        self.assertEqual("missing", report["corpus"]["files"][name])

    def test_a_broken_manifest_is_an_input_error(self):
        (self.root / rebuild_assets.CORPUS_MANIFEST).write_text("{", encoding="utf-8")
        stream = io.StringIO()
        self.assertEqual(2, rebuild_assets.run_check(self.root, None, stream))


class WordformStageTest(unittest.TestCase):
    """build_admitted_wordforms on a tiny synthetic tree: attestation, merge, fail-closed."""

    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.baseline = self.root / "baseline"
        self.baseline.mkdir()
        self.corpora = self.root / "corpora"
        self.corpora.mkdir()
        self.work = self.root / "work"
        # Baseline tatar dictionary: китап (voicing exception), су, яз.
        asset = build_dictionary_asset(
            self.baseline, words=["китап", "су", "яз"], frequencies=[300, 200, 400])
        import hashlib
        self.baseline_sha = hashlib.sha256(asset.read_bytes()).hexdigest()
        # Committed-side dict_accept inputs: accepted queue + conversational counts.
        self.dict_accept_out = self.root / "dict-accept"
        self.dict_accept_out.mkdir()
        (self.dict_accept_out / "accepted-tt.tsv").write_text(
            "# head\nword\theldout_hits\ttrain_freq\ttrain_freq_clean\tsources\t"
            "license_status\tcap_ratio\tenters_top100k\trule\trule_detail\n"
            "эшләп\t1\t9\t9\tTatoeba\tok\t0.00\tyes\ttwo-corpora\tx\n",
            encoding="utf-8")
        (self.dict_accept_out / "conv-freq-tt.tsv").write_text(
            "# head\nword\tconv_freq\nкитаплар\t3\n", encoding="utf-8")
        # Leipzig words files: китаплар attested 10 (so 10+3 after the conv sum),
        # китабы 5 (the voicing exception form), суга 4, язар 7.
        for name in rebuild_assets.WORDFORM_FREQUENCY_SOURCES:
            (self.corpora / name).write_text(
                "1\tкитаплар\t5\n2\tкитабы\t2\n3\tсуга\t4\n", encoding="utf-8")
        # Second source adds the rest, so the merge across files is exercised too.
        second = self.corpora / rebuild_assets.WORDFORM_FREQUENCY_SOURCES[1]
        second.write_text("1\tкитаплар\t5\n2\tкитабы\t3\n3\tязар\t7\n", encoding="utf-8")
        self._saved = (dict_accept.BASELINE_SHA256, dict_accept.OUT_DIR)
        dict_accept.BASELINE_SHA256 = {**dict_accept.BASELINE_SHA256,
                                       "tat": self.baseline_sha}
        dict_accept.OUT_DIR = self.dict_accept_out
        self.addCleanup(self._restore)

    def _restore(self):
        dict_accept.BASELINE_SHA256, dict_accept.OUT_DIR = self._saved

    def test_admission_merge_and_sum(self):
        # root = the real repository: the exceptions table (китап → китаб voicing) is
        # committed there; the fake tree carries no scripts/.
        out = rebuild_assets.build_admitted_wordforms(
            REPOSITORY_ROOT, self.baseline, self.corpora, self.work)
        rows = dict(
            line.split("\t")
            for line in out.read_text(encoding="utf-8").splitlines()
        )
        # Attested in both Leipzig files: 5 + 5, plus 3 conversational.
        self.assertEqual("13", rows["китаплар"])
        # The voicing exception form (китап → китабы): 2 + 3.
        self.assertEqual("5", rows["китабы"])
        self.assertEqual("4", rows["суга"])
        self.assertEqual("7", rows["язар"])
        # Nothing unattested, nothing already in the composition (китап/су/яз/эшләп
        # itself), nothing doubled.
        self.assertEqual(4, len(rows))
        report = json.loads((self.work / "wordforms-admitted-tt.json").read_text(
            encoding="utf-8"))
        self.assertEqual(4, report["admitted_forms"])
        self.assertEqual(4, report["stems"])  # китап, су, яз + accepted эшләп
        self.assertGreater(report["generated_rows"], 100)
        self.assertGreater(report["rows_unattested"], 0)

    def test_stage_is_deterministic(self):
        first = rebuild_assets.build_admitted_wordforms(
            REPOSITORY_ROOT, self.baseline, self.corpora, self.work)
        second = rebuild_assets.build_admitted_wordforms(
            REPOSITORY_ROOT, self.baseline, self.corpora, self.work)
        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_missing_exceptions_table_fails_closed(self):
        with self.assertRaises(wordform_gen.ExceptionsError):
            rebuild_assets.build_admitted_wordforms(
                self.root / "no-such-root", self.baseline, self.corpora, self.work)


class RealTreeTest(unittest.TestCase):
    """The committed assets against the committed contracts — the CI gate, as a test.

    The known-drift file keeps this green, and it turns red as soon as the drift changes in
    either direction without a matching edit of that file.
    """

    def test_committed_assets_match_their_pins(self):
        stream = io.StringIO()
        code = rebuild_assets.run_check(
            REPOSITORY_ROOT, REPOSITORY_ROOT / "scripts/known_asset_drift.json", stream)
        report = json.loads(stream.getvalue())
        self.assertEqual(0, code, json.dumps(report, ensure_ascii=False, indent=2))

    def test_strict_check_shows_the_known_russian_drift(self):
        stream = io.StringIO()
        code = rebuild_assets.run_check(REPOSITORY_ROOT, None, stream)
        report = json.loads(stream.getvalue())
        self.assertEqual(1, code)
        self.assertEqual("drift", report["bigrams"]["rus"]["verdict"])
        # The remaining two come from the generator rule, not from a drift: «окей» and «берегись»
        # have no in-vocabulary pair in the thinned conversational input and are dropped,
        # not stored empty.
        self.assertEqual(2, report["bigrams"]["rus"]["drift"]["missing_top_heads"])
        self.assertEqual(0, report["bigrams"]["rus"]["drift"]["unexpected_heads"])


if __name__ == "__main__":
    unittest.main()
