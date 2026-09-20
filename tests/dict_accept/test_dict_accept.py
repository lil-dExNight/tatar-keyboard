#!/usr/bin/env python3
"""Tests for scripts/dict_accept.py and scripts/dict_accept_check.py.

The selection rule (1.9.1: accept everything except formal fragments and
operator-excluded words) is exercised on synthetic Row objects — no corpora,
no queue files. The select/pack contract runs on tiny temporary inputs: a
synthetic baseline dictionary built with dictionary_pack (its SHA-256 pinned
into the module under test), a two-row queue and a two-word conv-freq file.
Fail-closed paths are checked both ways: a wrong baseline SHA-256 must stop
both select and pack. A live-tree test runs dict_accept_check against the
committed assets and the 1.8.4 baseline and asserts the operator-visible
invariants the mission promised: no named garbage, no `можна`, silent words
present.
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
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "research" / "corpus"))

import dictionary_pack  # noqa: E402

# dict_accept.py inserts scripts/ and research/corpus/ into sys.path itself.
da = importlib.import_module("dict_accept")
dac = importlib.import_module("dict_accept_check")

BASELINE_DIR = ROOT / "research" / "corpus" / "out" / "shipped-1.8.4"

RUS_WORDS = ["работаю", "работает", "работаем", "мама", "папа"]
RUS_FREQS = [500, 400, 300, 200, 100]
TAT_WORDS = ["мәхәббәт", "китап", "су", "әни"]
TAT_FREQS = [400, 300, 200, 100]

QUEUE_HEADER = "\t".join(da.HEADER) + "\n"


def make_row(word: str, *, freq: int = 100, heldout: int = 10,
             sources: str = "OpenSubtitles", cap_ratio: float = 0.0,
             top100k: str = "no") -> da.Row:
    return da.Row([word, str(heldout), str(freq), str(freq), sources,
                   "ok", f"{cap_ratio:.2f}", top100k])


def queue_text(rows: list[da.Row]) -> str:
    out = QUEUE_HEADER
    for row in rows:
        out += "\t".join([
            row.word, str(row.heldout), str(row.freq), str(row.freq_clean),
            row.sources, row.license, f"{row.cap_ratio:.2f}",
            "yes" if row.enters_top100k else "no"]) + "\n"
    return out


def build_baseline(directory: Path) -> dict:
    """A two-language synthetic 'shipped 1.8.4' directory; returns its pins."""
    pins = {}
    for tag, words, freqs in (("rus", RUS_WORDS, RUS_FREQS),
                              ("tat", TAT_WORDS, TAT_FREQS)):
        words_path = directory / f"words-{tag}.txt"
        words_path.write_text(
            "".join(f"{i}\t{w}\t{f}\n" for i, (w, f)
                    in enumerate(zip(words, freqs), start=1)),
            encoding="utf-8")
        built = dictionary_pack.build_dictionary([words_path], len(words))
        import corpuslib  # local import: needs the sys.path fix above
        asset_path = directory / corpuslib.SHIPPED[tag].name
        asset_path.write_bytes(built.asset)
        pins[tag] = hashlib.sha256(built.asset).hexdigest()
    return pins


@contextlib.contextmanager
def patched(**overrides):
    saved = {name: getattr(da, name) for name in overrides}
    for name, value in overrides.items():
        setattr(da, name, value)
    try:
        yield
    finally:
        for name, value in saved.items():
            setattr(da, name, value)


def run_main(argv: list[str], **overrides) -> tuple:
    """Invoke dict_accept's CLI capturing stdout; returns (exit-or-None, out)."""
    stdout = io.StringIO()
    code = None
    with patched(**overrides), contextlib.redirect_stdout(stdout):
        try:
            code = da.main(argv)
        except SystemExit as error:
            code = error.code
    return code, stdout.getvalue()


class FragmentRuleTest(unittest.TestCase):
    def test_rus_short_word_is_fragment(self) -> None:
        self.assertTrue(da.fragment_reason("ме", "rus"))

    def test_rus_word_without_vowels_is_fragment(self) -> None:
        # Порог длины проверяется первым: «щрн» (3 буквы) — «короче 4 букв»,
        # безгласный диагноз получает слово, прошедшее длину.
        self.assertEqual(da.fragment_reason("щрнб", "rus"), "ни одной гласной")
        self.assertTrue(da.fragment_reason("щрн", "rus").startswith("короче"))

    def test_rus_normal_word_is_not_fragment(self) -> None:
        self.assertEqual(da.fragment_reason("привет", "rus"), "")

    def test_tat_has_no_length_threshold(self) -> None:
        # «док», «ох», «фу» — живые татарские слова короче четырёх букв.
        self.assertEqual(da.fragment_reason("док", "tat"), "")
        self.assertEqual(da.fragment_reason("ох", "tat"), "")

    def test_tat_word_without_vowels_is_fragment(self) -> None:
        self.assertEqual(da.fragment_reason("щгл", "tat"), "ни одной гласной")
        # Татарские гласные ә, ө, ү считаются гласными.
        self.assertEqual(da.fragment_reason("көр", "tat"), "")


class PriorVerdictTest(unittest.TestCase):
    def test_two_corpora_passes(self) -> None:
        row = make_row("давай", sources="OpenSubtitles+Tatoeba")
        passed, rule, _ = da.prior_verdict(row, "rus", frozenset())
        self.assertTrue(passed)
        self.assertEqual(rule, "two-corpora")

    def test_cap_ratio_evidence_blocks_old_rule(self) -> None:
        row = make_row("вася", sources="OpenSubtitles+Tatoeba", cap_ratio=0.9)
        passed, rule, detail = da.prior_verdict(row, "rus", frozenset())
        self.assertFalse(passed)
        self.assertEqual(rule, "proper-noun-evidence")
        self.assertIn("cap_ratio", detail)

    def test_shipped_word_passes(self) -> None:
        row = make_row("мама")
        passed, rule, _ = da.prior_verdict(row, "rus", frozenset({"мама"}))
        self.assertTrue(passed)
        self.assertEqual(rule, "shipped-word")

    def test_shipped_paradigm_needs_three_other_forms(self) -> None:
        row = make_row("работала", sources="OpenSubtitles")
        shipped = frozenset({"работаю", "работает", "работаем"})
        passed, rule, detail = da.prior_verdict(row, "rus", shipped)
        self.assertTrue(passed)
        self.assertEqual(rule, "shipped-paradigm")
        self.assertIn("работа", detail)

    def test_two_paradigm_siblings_are_not_enough(self) -> None:
        row = make_row("работала", sources="OpenSubtitles")
        shipped = frozenset({"работаю", "работает"})
        passed, rule, _ = da.prior_verdict(row, "rus", shipped)
        self.assertFalse(passed)
        self.assertEqual(rule, "single-source")

    def test_paradigm_branch_is_russian_only(self) -> None:
        row = make_row("китаплар", sources="OpenSubtitles")
        shipped = frozenset({"китап", "китабы", "китапка"})
        passed, rule, _ = da.prior_verdict(row, "tat", shipped)
        self.assertFalse(passed)
        self.assertEqual(rule, "single-source")


class DecideTest(unittest.TestCase):
    def test_operator_excluded_wins_over_everything(self) -> None:
        # `можна` проходит и две-корпусную планку, и порог длины — и всё равно
        # отклоняется: оператор назвал её поимённо.
        row = make_row("можна", sources="OpenSubtitles+Tatoeba", freq=99999)
        accepted, rejected = da.decide([row], "rus", frozenset())
        self.assertEqual(accepted, [])
        self.assertEqual(rejected[0][1], "operator-excluded")

    def test_fragment_rejected_even_with_high_frequency(self) -> None:
        accepted, rejected = da.decide(
            [make_row("ме", freq=19092)], "rus", frozenset())
        self.assertEqual(accepted, [])
        self.assertEqual(rejected[0][1], "fragment")

    def test_old_rule_pass_keeps_its_label(self) -> None:
        accepted, rejected = da.decide(
            [make_row("давай", sources="OpenSubtitles+Tatoeba")],
            "rus", frozenset())
        self.assertEqual(rejected, [])
        self.assertEqual(accepted[0][1], "two-corpora")

    def test_widened_word_carries_the_old_rejection_reason(self) -> None:
        accepted, _ = da.decide(
            [make_row("приветик", sources="OpenSubtitles")], "rus", frozenset())
        self.assertEqual(accepted[0][1], "operator-widened")
        self.assertIn("single-source", accepted[0][2])

    def test_cap_ratio_no_longer_decides(self) -> None:
        # 1.9.1: регистровая улика — метка в деталях, а не отказ.
        accepted, _ = da.decide(
            [make_row("вася", cap_ratio=0.9)], "rus", frozenset())
        self.assertEqual(accepted[0][1], "operator-widened")
        self.assertIn("proper-noun-evidence", accepted[0][2])

    def test_tatar_short_word_accepted_no_vowel_rejected(self) -> None:
        accepted, rejected = da.decide(
            [make_row("док"), make_row("щгл")], "tat", frozenset())
        self.assertEqual([r.word for r, _, _ in accepted], ["док"])
        self.assertEqual(rejected[0][0].word, "щгл")
        self.assertEqual(rejected[0][1], "fragment")


class SelectPackContractTest(unittest.TestCase):
    """select/pack on tiny temporary inputs with a pinned synthetic baseline."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        directory = Path(self._tmp.name)
        self.baseline = directory / "baseline"
        self.baseline.mkdir()
        self.pins = build_baseline(self.baseline)
        self.queue_dir = directory / "queue"
        self.queue_dir.mkdir()
        self.out_dir = directory / "dict-accept"
        self.queues = {
            "rus": self.queue_dir / "rus.tsv",
            "tat": self.queue_dir / "tat.tsv",
        }
        self.queues["rus"].write_text(queue_text([
            make_row("давай", freq=1000, sources="OpenSubtitles+Tatoeba"),
            make_row("работала", freq=500),
            make_row("ме", freq=19092),
            make_row("можна", freq=777, sources="OpenSubtitles+Tatoeba"),
        ]), encoding="utf-8")
        self.queues["tat"].write_text(queue_text([
            make_row("сәлам", freq=900, sources="OpenSubtitles+Tatoeba"),
            make_row("док", freq=50),
            make_row("щгл", freq=40),
        ]), encoding="utf-8")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _run_select(self):
        return run_main(["select", "--baseline", str(self.baseline)],
                        QUEUE=self.queues, OUT_DIR=self.out_dir,
                        BASELINE_SHA256=self.pins)

    def test_select_writes_all_six_files_and_report(self) -> None:
        code, stdout = self._run_select()
        self.assertEqual(code, 0)
        for suffix in ("ru", "tt"):
            for stem in ("accepted-", "rejected-"):
                self.assertTrue((self.out_dir / f"{stem}{suffix}.tsv").is_file())
            for stem in ("sample-accepted-", "sample-rejected-"):
                self.assertTrue((self.out_dir / f"{stem}{suffix}.txt").is_file())
        report = json.loads(stdout)
        rus = report["languages"]["rus"]
        self.assertEqual(rus["queue_rows"], 4)
        self.assertEqual(rus["accepted"], 2)
        self.assertEqual(rus["rejected"], 2)
        self.assertEqual(rus["accepted_by_rule"],
                         {"two-corpora": 1, "shipped-paradigm": 1})
        self.assertEqual(rus["rejected_by_rule"],
                         {"fragment": 1, "operator-excluded": 1})
        tat = report["languages"]["tat"]
        self.assertEqual(tat["accepted"], 2)  # сәлам + док (без порога длины)
        self.assertEqual(tat["rejected"], 1)  # щгл — ни одной гласной

    def test_accepted_tsv_rows_carry_rule_and_detail(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        text = (self.out_dir / "accepted-ru.tsv").read_text(encoding="utf-8")
        rows = [line.split("\t") for line in text.splitlines()
                if line and not line.startswith("#")
                and not line.startswith("word\t")]
        by_word = {row[0]: row for row in rows}
        self.assertEqual(by_word["давай"][8], "two-corpora")
        self.assertEqual(by_word["работала"][8], "shipped-paradigm")
        self.assertIn("работа", by_word["работала"][9])
        rejected = (self.out_dir / "rejected-ru.tsv").read_text(encoding="utf-8")
        self.assertIn("можна", rejected)
        self.assertIn("operator-excluded", rejected)

    def test_wrong_baseline_sha_stops_select(self) -> None:
        bad_pins = {tag: "0" * 64 for tag in self.pins}
        code, _ = run_main(["select", "--baseline", str(self.baseline)],
                           QUEUE=self.queues, OUT_DIR=self.out_dir,
                           BASELINE_SHA256=bad_pins)
        self.assertNotEqual(code, 0)
        self.assertIsInstance(code, str)  # SystemExit carries the diagnosis
        self.assertIn("не ассет 1.8.4", code)

    def test_wrong_baseline_sha_stops_pack(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        self._write_conv_freq()
        bad_pins = {tag: "0" * 64 for tag in self.pins}
        code, _ = run_main(["pack", "--baseline", str(self.baseline)],
                           QUEUE=self.queues, OUT_DIR=self.out_dir,
                           BASELINE_SHA256=bad_pins)
        self.assertNotEqual(code, 0)
        self.assertIn("не ассет 1.8.4", code)

    def test_queue_with_unexpected_columns_stops(self) -> None:
        self.queues["rus"].write_text("word\tbogus\n", encoding="utf-8")
        code, _ = self._run_select()
        self.assertNotEqual(code, 0)
        self.assertIn("неожиданные колонки", code)

    def _write_conv_freq(self) -> None:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        for suffix, rows in (("ru", [("мама", 5), ("давай", 100),
                                     ("работала", 60), ("папа", 1)]),
                             ("tt", [("су", 7), ("сәлам", 42), ("док", 3)])):
            (self.out_dir / f"conv-freq-{suffix}.tsv").write_text(
                "# comment\nword\tconv_freq\n"
                + "".join(f"{w}\t{f}\n" for w, f in rows), encoding="utf-8")

    def test_pack_measure_mode_reports_and_writes_nothing(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        self._write_conv_freq()
        code, stdout = run_main(["pack", "--baseline", str(self.baseline)],
                                QUEUE=self.queues, OUT_DIR=self.out_dir,
                                BASELINE_SHA256=self.pins)
        self.assertEqual(code, 0)
        result = json.loads(stdout)
        for tag in ("rus", "tat"):
            self.assertFalse(result[tag]["written"])
            self.assertTrue(result[tag]["fits_compressed"])
            self.assertTrue(result[tag]["fits_raw"])
            self.assertEqual(len(result[tag]["sha256_after"]), 64)

    def test_merged_entries_composition_and_frequencies(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        self._write_conv_freq()
        with patched(QUEUE=self.queues, OUT_DIR=self.out_dir,
                     BASELINE_SHA256=self.pins):
            shipped, accepted, entries = da.merged_entries("rus", self.baseline)
        # Состав — поставляемое плюс принятое (давай через two-corpora,
        # работала через shipped-paradigm); отклонённое (ме, можна) не входит.
        words = {w for w, _ in entries}
        self.assertEqual(words, set(RUS_WORDS) | {"давай", "работала"})
        freqs = dict(entries)
        # Частота — письменная плюс разговорная, у каждого слова состава;
        # train_freq очереди в ассет НЕ идёт (у «давай» её 1000, вошло 100).
        self.assertEqual(freqs["мама"], 200 + 5)
        self.assertEqual(freqs["давай"], 100)
        self.assertEqual(freqs["папа"], 100 + 1)
        self.assertEqual(freqs["работала"], 60)
        self.assertNotIn("ме", words)
        self.assertNotIn("можна", words)
        self.assertEqual(set(shipped), set(RUS_WORDS))
        self.assertEqual(set(accepted), {"давай", "работала"})


class DictAcceptCheckLiveTreeTest(unittest.TestCase):
    """dict_accept_check against the committed assets and the 1.8.4 baseline."""

    @classmethod
    def setUpClass(cls) -> None:
        if not BASELINE_DIR.is_dir():
            raise unittest.SkipTest("no 1.8.4 baseline directory in the tree")
        stdout = io.StringIO()
        argv = sys.argv
        sys.argv = ["dict_accept_check.py", str(BASELINE_DIR)]
        try:
            with contextlib.redirect_stdout(stdout):
                dac.main()
        finally:
            sys.argv = argv
        cls.report = json.loads(stdout.getvalue())

    def test_operator_garbage_not_added_by_acceptance(self) -> None:
        for tag in ("rus", "tat"):
            self.assertEqual(
                self.report[tag]["operator_garbage_added_by_acceptance"], [])
            self.assertEqual(
                self.report[tag]["operator_excluded_in_dictionary"], [])

    def test_tatar_me_was_shipped_not_accepted(self) -> None:
        # `ме` в татарском словаре стоит с Leipzig-времён — это измеренный факт
        # из шапки dict_accept_check.py, а не провал правила приёмки.
        self.assertEqual(
            self.report["tat"]["operator_garbage_already_shipped"], ["ме"])
        self.assertEqual(self.report["rus"]["operator_garbage_already_shipped"],
                         [])

    def test_silent_words_are_all_in_the_dictionary(self) -> None:
        for tag in ("rus", "tat"):
            for word, entry in self.report[tag]["silent_words"].items():
                self.assertIsNotNone(entry["after_rank"],
                                     msg=f"{tag}: {word} не попал в словарь")

    def test_added_and_displaced_counts_match_the_documented_rules(self) -> None:
        # rus, как в 1.9.1: отсечка 100 000, разговорные слова вытесняют хвост Leipzig
        # ровно на своё число.
        self.assertEqual(self.report["rus"]["entries_after"], 100_000)
        self.assertGreater(self.report["rus"]["words_added"], 0)
        self.assertEqual(self.report["rus"]["words_added"],
                         self.report["rus"]["words_displaced"])
        # tat, с 2026-09-20 (TT-SUGGESTIONS P2, docs/TT-SUGGESTIONS.md): отсечка поднята
        # до 110 000 именно чтобы словоформы никого не вытесняли — добавлено ровно
        # 10 000 слов к составу 1.8.4 (948 разговорных против 303 при старой отсечке,
        # плюс 9 052 допущенных словоформы), вытеснено 0.
        self.assertEqual(self.report["tat"]["entries_after"], 110_000)
        self.assertEqual(self.report["tat"]["words_added"], 10_000)
        self.assertEqual(self.report["tat"]["words_displaced"], 0)

    def test_mozhna_pair_shows_single_word_on_prefix(self) -> None:
        # Условие готовности tt-dict-widen: на префиксе `можн` нет пары
        # `можно | можна` — `можна` исключена оператором поимённо.
        top3 = self.report["rus"]["prefix_top3"]["можн"]["after"]
        self.assertNotIn("можна", top3)
        self.assertIn("можно", top3)


if __name__ == "__main__":
    unittest.main()
