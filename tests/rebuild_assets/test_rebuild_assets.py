#!/usr/bin/env python3
"""Tests for scripts/rebuild_assets.py — the dictionary+bigram rebuild orchestrator.

Everything runs on synthetic fixtures in a temporary directory: a handful of Tatar words
instead of a 100k dictionary, a two-head bigram table instead of 518 KB. What is pinned
here is not the data but the CONTRACT of the tool: it reads pins from the Kotlin files
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
    """Schema 3: таблица кросс-референсит словарь фикстуры — индексы и его raw SHA-256."""
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

# Путь словаря внутри фикстурного дерева (создаётся в FakeTreeTest.setUp).
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
        # Schema 3 хранит индексы в словарь: голова вне словаря НЕВЫРАЗИМА — упаковщик
        # падает fail-closed ещё до записи файла (у schema 2 это был дрейф-вердикт
        # «heads_outside_dictionary», который больше не нужен: класс закрыт конструкцией).
        table = dict(self.table)
        table["дус"] = [(WORDS[0], 5)]  # «дус» в словаре нет
        with self.assertRaises(bigram_asset_pack.BigramInputError):
            build_bigram_asset(self.root / "app/src/main/assets/bigrams",
                               WORDS[:3] + ["дус"], table, self.root / DICT_ASSET)

    def test_a_table_packed_against_another_dictionary_is_red(self):
        # Связка schema 3: таблица упакована от одного словаря, пины и словарь сменились —
        # заголовок таблицы называет СТАРЫЙ словарь, и проверка красная по связке.
        pin_everything(self.root, self.dictionary, self.bigram)
        build_dictionary_asset(self.root / "app/src/main/assets/dictionaries",
                               frequencies=[100 + i for i in range(len(WORDS))])
        code, report = self.run_check()
        self.assertEqual(1, code)
        self.assertEqual("mismatch", report["bigrams"]["tat"]["verdict"])

    def test_drift_is_counted_in_both_directions(self):
        # Таблица упакована с головами по СТАРОМУ порядку частот: «өлкә» вместо «мәхәббәт».
        build_bigram_asset(self.root / "app/src/main/assets/bigrams",
                           [WORDS[5], WORDS[1], WORDS[2]],
                           {w: [(WORDS[1], 7)] for w in (WORDS[5], WORDS[1], WORDS[2])},
                           self.root / DICT_ASSET)
        pin_everything(self.root, self.dictionary, self.bigram)
        code, report = self.run_check()
        self.assertEqual(1, code)
        drift = report["bigrams"]["tat"]["drift"]
        self.assertEqual(1, drift["missing_top_heads"])  # нет «мәхәббәт»
        self.assertEqual(1, drift["unexpected_heads"])  # лишняя «өлкә»
        self.assertEqual(0, drift["heads_outside_dictionary"])
        self.assertEqual("drift", report["bigrams"]["tat"]["verdict"])

    def test_known_drift_is_accepted_only_on_exact_numbers(self):
        build_bigram_asset(self.root / "app/src/main/assets/bigrams",
                           [WORDS[1], WORDS[2]],  # «мәхәббәт» не упакована
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
        pin_everything(self.root, self.dictionary, self.bigram)  # расхождения нет
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
        # C3 аудита 2026-09-02: ContractError («контракт не разобрался») — код 2,
        # а не необработанный traceback с кодом 1.
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
    """The canned pack commands are the mission's parameters, pinned verbatim."""

    def test_tatar_command(self):
        argv = rebuild_assets.bigram_pack_argv(
            Path("/root"), Path("/corpora"), Path("/work"), rebuild_assets.BIGRAMS[0])
        text = " ".join(argv)
        self.assertIn("--heads 10132", text)
        # K: 4 -> 3 (2026-09-23, ROADMAP-P4 T7, docs/ROADMAP-P4.md).
        self.assertIn("--successes-per-head 3", text)
        self.assertIn("--extra-heads /root/scripts/bigram_extra_heads_tat.txt", text)
        self.assertIn("--language tat", text)
        self.assertIn("/corpora/tat_mixed_2015_1M-sentences.txt", text)
        self.assertIn("/corpora/tat_web_2018_1M-sentences.txt", text)
        # С части B (2026-08-31) — разговорный вход, docs/CORPUS-CONVERSATIONAL-TT.md.
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
        # С части A (2026-08-31) — разговорный вход, docs/CORPUS-CONVERSATIONAL-RU.md.
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


class OnlyModeTest(unittest.TestCase):
    """`--only`: one side rebuilds, the other must stay byte-identical."""

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

    def _restore(self):
        rebuild_assets.DICTIONARIES, rebuild_assets.BIGRAMS = self._saved

    def run_rebuild(self, only):
        return rebuild_assets.run_rebuild(
            self.root, self.baseline, self.corpora, self.root / "work", None, only=only)

    def test_only_tatar_needs_no_russian_inputs(self):
        # The tatar inputs are complete (train + words files); the russian corpus is
        # absent and must NOT be required in --only tatar. The exceptions table is not
        # in the fake tree, so the first run stops at the input gate with exit 2…
        for name in rebuild_assets.WORDFORM_FREQUENCY_SOURCES:
            (self.corpora / name).write_text("", encoding="utf-8")
        (self.corpora / "tt_corpus-sentences.txt").write_text("", encoding="utf-8")
        self.assertEqual(2, self.run_rebuild("tatar"))
        # …and with the exceptions table present the gate passes: the run proceeds into
        # the wordform stage and dies on the fake baseline bytes (dict_accept refuses a
        # non-1.8.4 SHA) — a SystemExit, not the input-gate return code.
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

    The known-drift file makes this green today AND red the day the drift changes in
    either direction without a conscious edit of that file.
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
        # Since corpus-conversational part A (2026-08-31, docs/CORPUS-CONVERSATIONAL-RU.md)
        # only 2 remain, and they are the generator rule, not a drift: «окей» and «берегись»
        # have no in-vocabulary pair in the thinned conversational input and are dropped,
        # not stored empty.
        self.assertEqual(2, report["bigrams"]["rus"]["drift"]["missing_top_heads"])
        self.assertEqual(0, report["bigrams"]["rus"]["drift"]["unexpected_heads"])


if __name__ == "__main__":
    unittest.main()
