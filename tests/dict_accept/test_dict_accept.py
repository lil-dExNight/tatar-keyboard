#!/usr/bin/env python3
"""Tests for scripts/dict_accept.py and scripts/dict_accept_check.py.

The automatic corpus rules, explicit manual reason codes, written-evidence thresholds and review
metadata are exercised on synthetic Row objects — no corpora or queue files. Legacy accepted
rows are checked separately from rows that opt into the strict manual-evidence columns.
The select/pack contract runs on tiny temporary inputs: a
synthetic baseline dictionary built with dictionary_pack (its SHA-256 pinned
into the module under test), a two-row queue and a two-word conv-freq file.
Fail-closed paths are checked both ways: a wrong baseline SHA-256 must stop
both select and pack. A live-tree test runs dict_accept_check against the
committed assets and the shipped-1.8.4 baseline and asserts the invariants the
curator relies on: no named garbage, no `можна`, silent words present.
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
             top100k: str = "no", approved: str = "", reviewer: str = "",
             review_date: str = "", note: str = "", manual_rules: str = "",
             written_evidence: str = "", written_norm: str = "") -> da.Row:
    fields = [word, str(heldout), str(freq), str(freq), sources,
              "ok", f"{cap_ratio:.2f}", top100k, approved, reviewer,
              review_date, note]
    if manual_rules or written_evidence or written_norm:
        fields += [manual_rules, written_evidence, written_norm]
    return da.Row(fields)


def reviewed_row(word: str, **kwargs) -> da.Row:
    kwargs.setdefault("reviewer", "reviewer-a")
    kwargs.setdefault("review_date", "2026-11-14")
    kwargs.setdefault("note", "checked inflection, spelling, and source context")
    kwargs.setdefault("approved", "yes")
    return make_row(word, **kwargs)


def queue_text(rows: list[da.Row]) -> str:
    structured = any(row.manual_rules or row.written_evidence or row.written_norm for row in rows)
    out = "\t".join(da.FULL_HEADER if structured else da.HEADER) + "\n"
    for row in rows:
        fields = [
            row.word, str(row.heldout), str(row.freq), str(row.freq_clean),
            row.sources, row.license, f"{row.cap_ratio:.2f}",
            "yes" if row.enters_top100k else "no", row.approved, row.reviewer,
            row.review_date, row.note]
        if structured:
            fields += [row.manual_rules, row.written_evidence, row.written_norm]
        out += "\t".join(fields) + "\n"
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
        # The length threshold is checked first: "щрн" (3 letters) is "shorter than 4
        # letters"; the no-vowel diagnosis goes to a word that passed the length check.
        self.assertEqual(da.fragment_reason("щрнб", "rus"), "no vowel")
        self.assertTrue(da.fragment_reason("щрн", "rus").startswith("shorter"))

    def test_rus_normal_word_is_not_fragment(self) -> None:
        self.assertEqual(da.fragment_reason("привет", "rus"), "")

    def test_tat_has_no_length_threshold(self) -> None:
        # "док", "ох", "фу" are real Tatar words shorter than four letters.
        self.assertEqual(da.fragment_reason("док", "tat"), "")
        self.assertEqual(da.fragment_reason("ох", "tat"), "")

    def test_tat_word_without_vowels_is_fragment(self) -> None:
        self.assertEqual(da.fragment_reason("щгл", "tat"), "no vowel")
        # The Tatar vowels ә, ө, ү count as vowels.
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

    def test_shipped_paradigm_needs_independent_attestation(self) -> None:
        row = make_row("работала", sources="Tatoeba")
        shipped = frozenset({"работаю", "работает", "работаем"})
        passed, rule, detail = da.prior_verdict(row, "rus", shipped)
        self.assertTrue(passed)
        self.assertEqual(rule, "shipped-paradigm")
        self.assertIn("работа", detail)

    def test_subtitle_only_paradigm_is_not_automatic(self) -> None:
        # A shipped stem does not make an OpenSubtitles-only form an automatic admission.
        row = make_row("работала", sources="OpenSubtitles")
        shipped = frozenset({"работаю", "работает", "работаем"})
        passed, rule, _ = da.prior_verdict(row, "rus", shipped)
        self.assertFalse(passed)
        self.assertEqual(rule, "single-source")

    def test_two_paradigm_siblings_are_not_enough(self) -> None:
        row = make_row("работала", sources="Tatoeba")
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


class StrictReviewRuleTest(unittest.TestCase):
    def test_written_corpus_threshold_is_exact(self) -> None:
        passing = reviewed_row(
            "приветик", manual_rules="written-corpus=accept",
            written_evidence="tatoeba=2;leipzig_web=3")
        accepted, rejected = da.decide([passing], "rus", frozenset())
        self.assertEqual(rejected, [])
        self.assertEqual(accepted[0][1], "manual-written-corpus")

        for evidence, detail in (
            ("tatoeba=2;leipzig_web=2", "got 4 in 2"),
            ("leipzig_web=5", "got 5 in 1"),
        ):
            with self.subTest(evidence=evidence):
                row = reviewed_row(
                    "приветик", manual_rules="written-corpus=accept",
                    written_evidence=evidence)
                accepted, rejected = da.decide([row], "rus", frozenset())
                self.assertEqual(accepted, [])
                self.assertEqual(rejected[0][1], "review-required")
                self.assertIn("written-corpus", rejected[0][2])
                self.assertIn(detail, rejected[0][2])

    def test_subtitles_and_extra_are_not_written_evidence(self) -> None:
        for source in ("opensubtitles", "extra"):
            with self.subTest(source=source):
                with self.assertRaisesRegex(ValueError, "subtitles and extra"):
                    make_row("оһ", written_evidence=f"{source}=99")

    def test_interjection_threshold_is_exact(self) -> None:
        passing = reviewed_row(
            "оһ", manual_rules="interjection=accept", written_evidence="leipzig_web=3")
        accepted, rejected = da.decide([passing], "tat", frozenset())
        self.assertEqual(rejected, [])
        self.assertEqual(accepted[0][1], "manual-interjection")

        failing = reviewed_row(
            "оһ", manual_rules="interjection=accept",
            written_evidence="leipzig_mixed=1;leipzig_web=1")
        accepted, rejected = da.decide([failing], "tat", frozenset())
        self.assertEqual(accepted, [])
        self.assertEqual(rejected[0][1], "review-required")
        self.assertIn("interjection", rejected[0][2])
        self.assertIn("got 2", rejected[0][2])

    def test_manual_acceptance_requires_every_metadata_field(self) -> None:
        for field in ("approved", "reviewer", "review_date", "note"):
            with self.subTest(field=field):
                row = reviewed_row(
                    "оһ", manual_rules="interjection=accept",
                    written_evidence="leipzig_web=3")
                setattr(row, field, "")
                accepted, rejected = da.decide([row], "tat", frozenset())
                self.assertEqual(accepted, [])
                self.assertEqual(rejected[0][1], "review-required")
                self.assertIn(field, rejected[0][2])

    def test_frequent_neighbor_requires_explicit_rejection_and_note(self) -> None:
        row = reviewed_row(
            "базык", sources="OpenSubtitles+Tatoeba",
            manual_rules="frequent-word-neighbor=reject", note="would mask бозык")
        accepted, rejected = da.decide([row], "tat", frozenset())
        self.assertEqual(accepted, [])
        self.assertEqual(rejected[0][1], "frequent-word-neighbor")
        self.assertIn("бозык", rejected[0][2])

        row.manual_rules = "frequent-word-neighbor=accept"
        accepted, rejected = da.decide([row], "tat", frozenset())
        self.assertEqual(accepted, [])
        self.assertIn("requires manual rejection", rejected[0][2])

        row.manual_rules = "frequent-word-neighbor=reject"
        row.note = ""
        accepted, rejected = da.decide([row], "tat", frozenset())
        self.assertEqual(accepted, [])
        self.assertIn("note", rejected[0][2])

    def test_fused_colloquial_requires_rejection_and_written_norm(self) -> None:
        row = reviewed_row(
            "бернишли", sources="OpenSubtitles+Tatoeba",
            manual_rules="fused-colloquial=reject", written_norm="берни эшли",
            note="fused colloquial spelling")
        accepted, rejected = da.decide([row], "tat", frozenset())
        self.assertEqual(accepted, [])
        self.assertEqual(rejected[0][1], "fused-colloquial")
        self.assertIn("берни эшли", rejected[0][2])

        row.written_norm = ""
        accepted, rejected = da.decide([row], "tat", frozenset())
        self.assertEqual(accepted, [])
        self.assertIn("separately written norm", rejected[0][2])

        row.written_norm = "берни эшли"
        row.manual_rules = "fused-colloquial=accept"
        accepted, rejected = da.decide([row], "tat", frozenset())
        self.assertEqual(accepted, [])
        self.assertIn("requires manual rejection", rejected[0][2])

    def test_manual_rejections_keep_their_reason_codes(self) -> None:
        for word, rule in (("тәмек", "written-corpus"), ("оһ", "interjection")):
            with self.subTest(rule=rule):
                row = reviewed_row(
                    word, approved="no", manual_rules=f"{rule}=reject",
                    note="reviewed rejection with its reason")
                accepted, rejected = da.decide([row], "tat", frozenset())
                self.assertEqual(accepted, [])
                self.assertEqual(rejected[0][1], rule)
                self.assertIn("reviewed rejection", rejected[0][2])

    def test_no_word_shape_detector_claims_a_manual_rule(self) -> None:
        rows = [make_row("базык", sources="OpenSubtitles+Tatoeba"),
                make_row("бернишли", sources="OpenSubtitles+Tatoeba")]
        accepted, rejected = da.decide(rows, "tat", frozenset())
        self.assertEqual(rejected, [])
        self.assertEqual([rule for _row, rule, _detail in accepted],
                         ["two-corpora", "two-corpora"])
        self.assertTrue(da.AUTOMATIC_ADMISSION_RULES.isdisjoint(da.MANUAL_RULES))
        self.assertTrue(da.AUTOMATIC_ADMISSION_RULES.isdisjoint(da.MANUAL_ADMISSION_RULES))

    def test_orphaned_evidence_and_unknown_reason_codes_fail_closed(self) -> None:
        row = reviewed_row("оһ", written_evidence="leipzig_web=3")
        accepted, rejected = da.decide([row], "tat", frozenset())
        self.assertEqual(accepted, [])
        self.assertIn("requires a written-corpus or interjection decision", rejected[0][2])
        with self.assertRaisesRegex(ValueError, "unknown manual rule"):
            make_row("оһ", manual_rules="shape-guess=accept")

    def test_legacy_accepted_interjection_is_preserved(self) -> None:
        row = reviewed_row("оһ", freq=179, note="interjection under prior review")
        fields = [
            row.word, str(row.heldout), str(row.freq), str(row.freq_clean), row.sources,
            row.license, f"{row.cap_ratio:.2f}", "yes", row.approved, row.reviewer,
            row.review_date, row.note, "operator-widened", "prior single-source review",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp)
            (out_dir / "accepted-tt.tsv").write_text(
                "\t".join(da.HEADER + ["rule", "rule_detail"]) + "\n"
                + "\t".join(fields) + "\n", encoding="utf-8")
            with patched(OUT_DIR=out_dir):
                self.assertEqual(da.read_accepted("tat"), {"оһ": 179})

    def test_manual_rejection_date_must_be_iso(self) -> None:
        row = reviewed_row(
            "базык", review_date="18.11.2026",
            manual_rules="frequent-word-neighbor=reject", note="would mask бозык")
        accepted, rejected = da.decide([row], "tat", frozenset())
        self.assertEqual(accepted, [])
        self.assertEqual(rejected[0][1], "review-required")
        self.assertIn("YYYY-MM-DD", rejected[0][2])

    def test_accepted_reader_rechecks_written_evidence(self) -> None:
        row = reviewed_row(
            "приветик", manual_rules="written-corpus=accept",
            written_evidence="tatoeba=2;leipzig_web=2")
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp)
            da.write_rows(out_dir / "accepted-ru.tsv", "ACCEPTED",
                          [(row, "manual-written-corpus", "tampered evidence")],
                          da.fragment_rule_text("rus"))
            with patched(OUT_DIR=out_dir):
                with self.assertRaisesRegex(SystemExit, "got 4 in 2"):
                    da.read_accepted("rus")


class DecideTest(unittest.TestCase):
    def test_operator_excluded_wins_over_everything(self) -> None:
        # `можна` passes both the two-corpora bar and the length threshold and is still
        # rejected: the curator excluded it by name.
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

    def test_reviewed_widened_word_carries_automatic_rejection_reason(self) -> None:
        accepted, rejected = da.decide(
            [reviewed_row("приветик", sources="OpenSubtitles")], "rus", frozenset())
        self.assertEqual(rejected, [])
        self.assertEqual(accepted[0][1], "operator-widened")
        self.assertIn("single-source", accepted[0][2])

    def test_unreviewed_subtitle_noise_is_not_admitted(self) -> None:
        accepted, rejected = da.decide(
            [make_row("приветик", sources="OpenSubtitles")], "rus", frozenset())
        self.assertEqual(accepted, [])
        self.assertEqual(rejected[0][1], "review-required")
        self.assertIn("approved=yes", rejected[0][2])

    def test_widened_review_requires_every_metadata_field(self) -> None:
        for field in ("approved", "reviewer", "review_date", "note"):
            with self.subTest(field=field):
                row = reviewed_row("приветик")
                setattr(row, field, "")
                accepted, rejected = da.decide([row], "rus", frozenset())
                self.assertEqual(accepted, [])
                self.assertEqual(rejected[0][1], "review-required")
                self.assertIn(field, rejected[0][2])

    def test_widened_review_rejects_invalid_date(self) -> None:
        row = reviewed_row("приветик", review_date="14.11.2026")
        accepted, rejected = da.decide([row], "rus", frozenset())
        self.assertEqual(accepted, [])
        self.assertIn("YYYY-MM-DD", rejected[0][2])

    def test_explicit_rejection_wins_over_automatic_evidence(self) -> None:
        row = make_row("давай", sources="OpenSubtitles+Tatoeba", approved="no",
                       note="not an ordinary dictionary word")
        accepted, rejected = da.decide([row], "rus", frozenset())
        self.assertEqual(accepted, [])
        self.assertEqual(rejected[0][1], "review-rejected")

    def test_proper_name_evidence_requires_complete_review(self) -> None:
        accepted, rejected = da.decide(
            [make_row("вася", sources="OpenSubtitles+Tatoeba", cap_ratio=0.9)],
            "rus", frozenset())
        self.assertEqual(accepted, [])
        self.assertEqual(rejected[0][1], "review-required")
        accepted, rejected = da.decide(
            [reviewed_row("вася", sources="OpenSubtitles+Tatoeba", cap_ratio=0.9)],
            "rus", frozenset())
        self.assertEqual(rejected, [])
        self.assertEqual(accepted[0][1], "operator-widened")
        self.assertIn("proper-noun-evidence", accepted[0][2])

    def test_tatar_short_word_needs_review_no_vowel_is_fragment(self) -> None:
        accepted, rejected = da.decide(
            [reviewed_row("док"), make_row("щгл")], "tat", frozenset())
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
            make_row("работала", freq=500, sources="Tatoeba"),
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
        self.assertEqual(report["rule"]["min_written_occurrences"], 5)
        self.assertEqual(report["rule"]["min_written_corpora"], 2)
        self.assertEqual(report["rule"]["min_interjection_written_occurrences"], 3)
        self.assertEqual(report["rule"]["excluded_written_sources"],
                         ["extra", "opensubtitles"])
        rus = report["languages"]["rus"]
        self.assertEqual(rus["queue_rows"], 4)
        self.assertEqual(rus["accepted"], 2)
        self.assertEqual(rus["rejected"], 2)
        self.assertEqual(rus["accepted_by_rule"],
                         {"two-corpora": 1, "shipped-paradigm": 1})
        self.assertEqual(rus["rejected_by_rule"],
                         {"fragment": 1, "operator-excluded": 1})
        tat = report["languages"]["tat"]
        self.assertEqual(tat["accepted"], 1)  # сәлам has two independent sources
        self.assertEqual(tat["rejected"], 2)  # док needs review; щгл has no vowel
        self.assertEqual(tat["rejected_by_rule"], {"fragment": 1, "review-required": 1})

    def test_accepted_tsv_rows_carry_rule_and_detail(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        text = (self.out_dir / "accepted-ru.tsv").read_text(encoding="utf-8")
        rows = [line.split("\t") for line in text.splitlines()
                if line and not line.startswith("#")
                and not line.startswith("word\t")]
        by_word = {row[0]: row for row in rows}
        self.assertEqual(by_word["давай"][15], "two-corpora")
        self.assertEqual(by_word["работала"][15], "shipped-paradigm")
        self.assertIn("работа", by_word["работала"][16])
        self.assertEqual(by_word["давай"][12:15], ["", "", ""])
        self.assertEqual(by_word["работала"][8:12], ["", "", "", ""])
        rejected = (self.out_dir / "rejected-ru.tsv").read_text(encoding="utf-8")
        self.assertIn("можна", rejected)
        self.assertIn("operator-excluded", rejected)

    def test_widened_review_round_trips_and_tampering_stops_pack_input(self) -> None:
        self.queues["tat"].write_text(queue_text([
            reviewed_row("док", freq=50, note="ordinary interjection; checked context"),
        ]), encoding="utf-8")
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        accepted_path = self.out_dir / "accepted-tt.tsv"
        text = accepted_path.read_text(encoding="utf-8")
        self.assertIn("док\t10\t50\t50\tOpenSubtitles", text)
        self.assertIn("reviewer-a\t2026-11-14\tordinary interjection", text)
        with patched(OUT_DIR=self.out_dir):
            self.assertEqual(da.read_accepted("tat"), {"док": 50})
        lines = text.splitlines()
        for index, line in enumerate(lines):
            if line.startswith("док\t"):
                fields = line.split("\t")
                fields[9] = ""
                lines[index] = "\t".join(fields)
        accepted_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        with patched(OUT_DIR=self.out_dir):
            with self.assertRaisesRegex(SystemExit, "reviewer"):
                da.read_accepted("tat")

    def test_strict_interjection_round_trips_and_missing_metadata_stops(self) -> None:
        self.queues["tat"].write_text(queue_text([
            reviewed_row("оһ", freq=50, manual_rules="interjection=accept",
                         written_evidence="leipzig_mixed=1;leipzig_web=2",
                         note="reviewed interjection with written evidence"),
        ]), encoding="utf-8")
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        accepted_path = self.out_dir / "accepted-tt.tsv"
        with patched(OUT_DIR=self.out_dir):
            self.assertEqual(da.read_accepted("tat"), {"оһ": 50})
        lines = accepted_path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            if line.startswith("оһ\t"):
                fields = line.split("\t")
                self.assertEqual(fields[12:15], [
                    "interjection=accept", "leipzig_mixed=1;leipzig_web=2", ""])
                self.assertEqual(fields[15], "manual-interjection")
                fields[9] = ""
                lines[index] = "\t".join(fields)
        accepted_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        with patched(OUT_DIR=self.out_dir):
            with self.assertRaisesRegex(SystemExit, "reviewer"):
                da.read_accepted("tat")

    def test_wrong_baseline_sha_stops_select(self) -> None:
        bad_pins = {tag: "0" * 64 for tag in self.pins}
        code, _ = run_main(["select", "--baseline", str(self.baseline)],
                           QUEUE=self.queues, OUT_DIR=self.out_dir,
                           BASELINE_SHA256=bad_pins)
        self.assertNotEqual(code, 0)
        self.assertIsInstance(code, str)  # SystemExit carries the diagnosis
        self.assertIn("is not the 1.8.4 asset", code)

    def test_wrong_baseline_sha_stops_pack(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        self._write_conv_freq()
        bad_pins = {tag: "0" * 64 for tag in self.pins}
        code, _ = run_main(["pack", "--baseline", str(self.baseline)],
                           QUEUE=self.queues, OUT_DIR=self.out_dir,
                           BASELINE_SHA256=bad_pins)
        self.assertNotEqual(code, 0)
        self.assertIn("is not the 1.8.4 asset", code)

    def test_queue_with_unexpected_columns_stops(self) -> None:
        self.queues["rus"].write_text("word\tbogus\n", encoding="utf-8")
        code, _ = self._run_select()
        self.assertNotEqual(code, 0)
        self.assertIn("unexpected columns", code)

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
            self.assertNotIn("fits_compressed", result[tag])
            self.assertTrue(result[tag]["fits_raw"])
            self.assertEqual(len(result[tag]["sha256_after"]), 64)

    def test_merged_entries_composition_and_frequencies(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        self._write_conv_freq()
        with patched(QUEUE=self.queues, OUT_DIR=self.out_dir,
                     BASELINE_SHA256=self.pins):
            shipped, accepted, entries = da.merged_entries("rus", self.baseline)
        # Contents: shipped plus accepted words (давай via two-corpora, работала via
        # shipped-paradigm); rejected words (ме, можна) are left out.
        words = {w for w, _ in entries}
        self.assertEqual(words, set(RUS_WORDS) | {"давай", "работала"})
        freqs = dict(entries)
        # Frequency is written plus conversational for every word; the queue's train_freq
        # does NOT go into the asset ("давай" has 1000 there, 100 went in).
        self.assertEqual(freqs["мама"], 200 + 5)
        self.assertEqual(freqs["давай"], 100)
        self.assertEqual(freqs["папа"], 100 + 1)
        self.assertEqual(freqs["работала"], 60)
        self.assertNotIn("ме", words)
        self.assertNotIn("можна", words)
        self.assertEqual(set(shipped), set(RUS_WORDS))
        self.assertEqual(set(accepted), {"давай", "работала"})

    def test_tatar_cutoff_weight_does_not_change_stored_frequency(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        self._write_conv_freq()
        (self.out_dir / "conv-freq-tt.tsv").write_text(
            "# comment\nword\tconv_freq\nсу\t50\n", encoding="utf-8")
        with patched(QUEUE=self.queues, OUT_DIR=self.out_dir,
                     BASELINE_SHA256=self.pins):
            _shipped, _accepted, entries = da.merged_entries(
                "tat", self.baseline, top=1)
        # су stores 200 + 50 but wins the cutoff with 200 + 50 + 9 * 50 = 700.
        self.assertEqual(entries, [("су", 250)])

    def test_required_tatar_form_ships_below_the_cutoff(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        self._write_conv_freq()
        with patched(QUEUE=self.queues, OUT_DIR=self.out_dir,
                     BASELINE_SHA256=self.pins):
            _shipped, _accepted, entries = da.merged_entries(
                "tat", self.baseline, top=1, extra={"йөрөп": 1},
                required=frozenset({"йөрөп"}))
        self.assertEqual(dict(entries), {"сәлам": 42, "йөрөп": 1})

    def test_preserved_word_ships_below_the_cutoff_with_its_frequency(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        self._write_conv_freq()
        with patched(QUEUE=self.queues, OUT_DIR=self.out_dir,
                     BASELINE_SHA256=self.pins):
            _shipped, _accepted, entries = da.merged_entries(
                "tat", self.baseline, top=1, preserve={"кит": 7})
        self.assertEqual(dict(entries), {"сәлам": 42, "кит": 7})

    def test_explicit_rejection_removes_baseline_and_extra_words(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        self._write_conv_freq()
        with patched(QUEUE=self.queues, OUT_DIR=self.out_dir,
                     BASELINE_SHA256=self.pins):
            _shipped, _accepted, entries = da.merged_entries(
                "tat", self.baseline, extra={"йөрөп": 1},
                excluded=frozenset({"су", "йөрөп"}))
        self.assertEqual(
            {word for word, _frequency in entries},
            (set(TAT_WORDS) - {"су"}) | {"сәлам"},
        )

    def test_explicit_rejection_conflicts_fail_closed(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        self._write_conv_freq()
        with patched(QUEUE=self.queues, OUT_DIR=self.out_dir,
                     BASELINE_SHA256=self.pins):
            for kwargs in (
                {"extra": {"йөрөп": 1}, "required": frozenset({"йөрөп"})},
                {"preserve": {"кит": 7}},
            ):
                with self.subTest(kwargs=kwargs):
                    with self.assertRaisesRegex(SystemExit, "explicitly excluded"):
                        da.merged_entries(
                            "tat", self.baseline, excluded=frozenset({"йөрөп", "кит"}),
                            **kwargs)

    def test_russian_cutoff_still_uses_stored_frequency(self) -> None:
        code, _ = self._run_select()
        self.assertEqual(code, 0)
        self._write_conv_freq()
        with patched(QUEUE=self.queues, OUT_DIR=self.out_dir,
                     BASELINE_SHA256=self.pins):
            _shipped, _accepted, entries = da.merged_entries(
                "rus", self.baseline, top=1)
        self.assertEqual(entries, [("работаю", 500)])


class DictAcceptCheckLiveTreeTest(unittest.TestCase):
    """dict_accept_check against the committed assets and the shipped-1.8.4 baseline."""

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
        # `ме` has been in the Tatar dictionary since it was built from Leipzig alone
        # (see the header of dict_accept_check.py); it is not a failure of the acceptance rule.
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
        # rus: cutoff 100 000; conversational words displace the same number of words
        # from the Leipzig tail.
        self.assertEqual(self.report["rus"]["entries_after"], 100_000)
        self.assertGreater(self.report["rus"]["words_added"], 0)
        self.assertEqual(self.report["rus"]["words_added"],
                         self.report["rus"]["words_displaced"])
        # tat: conversational cutoff weighting, the bonus-frequency rerank and word forms
        # promote entries above the old cutoff and displace entries from the tail. Explicitly
        # approved forms then ship even when they remain below the new cutoff.
        self.assertEqual(self.report["tat"]["entries_after"], 112_916)
        self.assertEqual(self.report["tat"]["words_added"], 24_029)
        self.assertEqual(self.report["tat"]["words_displaced"], 11_113)

    def test_mozhna_pair_shows_single_word_on_prefix(self) -> None:
        # On the prefix `можн` there is no `можно | можна` pair: the curator excluded
        # `можна` by name.
        top3 = self.report["rus"]["prefix_top3"]["можн"]["after"]
        self.assertNotIn("можна", top3)
        self.assertIn("можно", top3)


class RepeatedEntriesTest(unittest.TestCase):
    """Repeated --extra-entries/--require-entries files merge and reject duplicates."""

    def test_extra_files_merge(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            first = Path(tmp) / "first.tsv"
            second = Path(tmp) / "second.tsv"
            first.write_text("азык-төлек\t10\n", encoding="utf-8")
            second.write_text("санкт-петербург\t5\n", encoding="utf-8")
            self.assertEqual(
                da.read_extra_entries_many([first, second], "tat"),
                {"азык-төлек": 10, "санкт-петербург": 5},
            )

    def test_a_word_repeated_across_files_stops_the_build(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            first = Path(tmp) / "first.tsv"
            second = Path(tmp) / "second.tsv"
            first.write_text("азык-төлек\t10\n", encoding="utf-8")
            second.write_text("азык-төлек\t5\n", encoding="utf-8")
            with self.assertRaises(SystemExit):
                da.read_extra_entries_many([first, second], "tat")

    def test_required_files_merge(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            first = Path(tmp) / "first.txt"
            second = Path(tmp) / "second.txt"
            first.write_text("азык-төлек\n", encoding="utf-8")
            second.write_text("санкт-петербург\n", encoding="utf-8")
            self.assertEqual(
                da.read_required_words_many([first, second], "tat"),
                frozenset({"азык-төлек", "санкт-петербург"}),
            )

if __name__ == "__main__":
    unittest.main()

