#!/usr/bin/env python3
"""Tests for scripts/accept_wordset.py.

The runner orchestrates the acceptance evidence steps as subprocesses, so the tests patch the
script-path constants with the fixture scripts of tests/accept_wordset/fixtures/: each fixture
logs the argv it was called with to ACCEPT_WORDSET_TEST_ARGV_LOG and prints a canned report.
The tests assert the step order and arguments, the relayed numbers, the gate-rejection stop,
the input gate and the re-pin checklist, without touching the real scripts or the tree.
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import accept_wordset  # noqa: E402

FIXTURES = ROOT / "tests" / "accept_wordset" / "fixtures"
LOG_ENV = "ACCEPT_WORDSET_TEST_ARGV_LOG"


class AcceptWordsetTest(unittest.TestCase):
    def setUp(self) -> None:
        self._saved = (accept_wordset.SELECT_SCRIPT, accept_wordset.MEASURE_SCRIPT,
                       dict(accept_wordset.GATE_SCRIPTS), accept_wordset.AUDIT_SCRIPT)
        self.addCleanup(self._restore)
        accept_wordset.SELECT_SCRIPT = FIXTURES / "fixture_select.py"
        accept_wordset.MEASURE_SCRIPT = FIXTURES / "fixture_measure_accept.py"
        accept_wordset.GATE_SCRIPTS = {"tat": FIXTURES / "fixture_gate_pass.py",
                                       "rus": FIXTURES / "fixture_gate_pass.py"}
        accept_wordset.AUDIT_SCRIPT = FIXTURES / "fixture_query_audit.py"

        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        root = Path(self._tmp.name)
        self.baseline = root / "baseline"
        self.baseline.mkdir()
        self.corpus = root / "conv-sentences.txt"
        self.corpus.write_text("1\tсәләм дөнья\n", encoding="utf-8")
        self.candidate_dict = root / "candidate.tdict.zlib"
        self.candidate_dict.write_bytes(b"dict")
        self.candidate_bigram = root / "candidate.tatbigr.zlib"
        self.candidate_bigram.write_bytes(b"bigram")
        self.queries = root / "queries.txt"
        self.queries.write_text("мәх\n", encoding="utf-8")
        self.work_dir = root / "work"
        self.log = root / "argv.log"
        self._saved_log = os.environ.get(LOG_ENV)
        os.environ[LOG_ENV] = str(self.log)

    def _restore(self) -> None:
        (accept_wordset.SELECT_SCRIPT, accept_wordset.MEASURE_SCRIPT,
         gates, accept_wordset.AUDIT_SCRIPT) = self._saved
        accept_wordset.GATE_SCRIPTS.clear()
        accept_wordset.GATE_SCRIPTS.update(gates)
        if self._saved_log is None:
            os.environ.pop(LOG_ENV, None)
        else:
            os.environ[LOG_ENV] = self._saved_log

    def run_runner(self, language: str = "tat"):
        """Run main() with the temp-tree inputs; return (exit code, stdout, stderr)."""
        argv = ["--source", "test-source", "--language", language,
                "--baseline", str(self.baseline), "--corpus", str(self.corpus),
                "--candidate-dict", str(self.candidate_dict),
                "--candidate-bigram", str(self.candidate_bigram),
                "--queries", str(self.queries), "--work-dir", str(self.work_dir)]
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = accept_wordset.main(argv)
        return code, stdout.getvalue(), stderr.getvalue()

    def read_log(self) -> list[list[str]]:
        """The fixture invocations, each as [script path, *args], in call order."""
        return [json.loads(line)
                for line in self.log.read_text(encoding="utf-8").splitlines()]

    def test_happy_path_tatar(self) -> None:
        code, out, _err = self.run_runner()
        self.assertEqual(0, code, out)
        calls = self.read_log()
        self.assertEqual(4, len(calls))
        self.assertEqual("fixture_select.py", Path(calls[0][0]).name)
        self.assertEqual(["select", "--baseline", str(self.baseline)], calls[0][1:])
        self.assertEqual("fixture_measure_accept.py", Path(calls[1][0]).name)
        self.assertEqual(["tat", str(self.baseline), str(self.corpus)], calls[1][1:])
        self.assertEqual("fixture_gate_pass.py", Path(calls[2][0]).name)
        self.assertEqual(["--candidate-dict", str(self.candidate_dict),
                          "--candidate-bigram", str(self.candidate_bigram)], calls[2][1:])
        self.assertEqual("fixture_query_audit.py", Path(calls[3][0]).name)
        self.assertEqual(["query-audit", "--asset", str(self.candidate_dict),
                          "--queries", str(self.queries)], calls[3][1:])

        select_report = json.loads((self.work_dir / "select.json").read_text("utf-8"))
        self.assertEqual(2, select_report["languages"]["tat"]["accepted"])
        gate_report = json.loads((self.work_dir / "gate.json").read_text("utf-8"))
        self.assertTrue(gate_report["gate_passed"])
        audit = (self.work_dir / "query-audit.tsv").read_text("utf-8")
        self.assertIn("мәхәббәт", audit)

        self.assertIn("accepted: 2, rejected: 1", out)
        self.assertIn("delta +0.5000 pp", out)
        self.assertIn("PASSED", out)
        for needle in ("DictionaryStorageContracts.kt", "BigramStorageContracts.kt",
                       "CompanionListStorageContracts.kt", "sentstart_pack.py",
                       "tt-query-review.tsv", "TATAR_REVIEW_DATE",
                       "bigram_extra_heads_regen.py", "typo_eval_pack.py",
                       "AutocorrectAdvice.kt", "MIN_CANDIDATE_FREQUENCY_TT",
                       "known_asset_drift.json", "TtSuggestEvalTest"):
            self.assertIn(needle, out)

    def test_happy_path_russian(self) -> None:
        code, out, _err = self.run_runner(language="rus")
        self.assertEqual(0, code, out)
        calls = self.read_log()
        self.assertEqual(4, len(calls))
        self.assertEqual(["rus", str(self.baseline), str(self.corpus)], calls[1][1:])
        self.assertEqual(["query-audit", "--asset", str(self.candidate_dict),
                          "--queries", str(self.queries), "--language", "rus"],
                         calls[3][1:])
        self.assertIn("accepted: 3, rejected: 4", out)
        self.assertIn("ru-query-review.tsv", out)
        self.assertIn("RUSSIAN_REVIEW_DATE", out)

    def test_gate_rejection_stops_before_the_audit(self) -> None:
        accept_wordset.GATE_SCRIPTS.update(
            {"tat": FIXTURES / "fixture_gate_fail.py", "rus": FIXTURES / "fixture_gate_fail.py"})
        code, out, _err = self.run_runner()
        self.assertEqual(3, code, out)
        self.assertEqual(3, len(self.read_log()))
        self.assertFalse((self.work_dir / "query-audit.tsv").exists())
        self.assertIn("REJECTED", out)
        self.assertNotIn("Contract pins", out)

    def test_a_failed_step_stops_the_run(self) -> None:
        accept_wordset.SELECT_SCRIPT = FIXTURES / "fixture_fail.py"
        code, _out, err = self.run_runner()
        self.assertEqual(1, code)
        self.assertIn("step 'select' failed with exit code 1", err)
        self.assertEqual(1, len(self.read_log()))
        self.assertFalse((self.work_dir / "coverage.json").exists())

    def test_a_broken_report_stops_the_run(self) -> None:
        accept_wordset.SELECT_SCRIPT = FIXTURES / "fixture_broken.py"
        code, _out, err = self.run_runner()
        self.assertEqual(1, code)
        self.assertIn("step 'select' printed no JSON report", err)
        self.assertEqual(1, len(self.read_log()))

    def test_missing_inputs_are_listed_before_anything_runs(self) -> None:
        gone = Path(self._tmp.name) / "gone"
        code, _out, err = self._run_missing(gone)
        self.assertEqual(2, code)
        for label in ("baseline directory", "corpus file", "candidate dictionary",
                      "candidate bigram table", "query list"):
            self.assertIn(label, err)
        self.assertFalse(self.log.exists())

    def _run_missing(self, gone: Path):
        argv = ["--source", "test-source", "--language", "tat",
                "--baseline", str(gone), "--corpus", str(gone),
                "--candidate-dict", str(gone), "--candidate-bigram", str(gone),
                "--queries", str(gone), "--work-dir", str(self.work_dir)]
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = accept_wordset.main(argv)
        return code, stdout.getvalue(), stderr.getvalue()

    def test_source_must_be_a_slug(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), \
                self.assertRaises(SystemExit) as caught:
            accept_wordset.parse_args(
                ["--source", "Bad Name", "--language", "tat", "--baseline", "b",
                 "--corpus", "c", "--candidate-dict", "d", "--candidate-bigram", "e"])
        self.assertEqual(2, caught.exception.code)

    def test_help_documents_the_flow(self) -> None:
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout), \
                self.assertRaises(SystemExit) as caught:
            accept_wordset.parse_args(["--help"])
        self.assertEqual(0, caught.exception.code)
        help_text = stdout.getvalue()
        for needle in ("select", "measure_accept.py", "tt_gate_check.py", "ru_gate_check.py",
                       "query-audit", "checklist", "--candidate-dict", "--candidate-bigram",
                       "--baseline", "--corpus", "--source"):
            self.assertIn(needle, help_text)

    def test_default_paths_point_at_the_real_scripts(self) -> None:
        accept_wordset.GATE_SCRIPTS.clear()
        accept_wordset.GATE_SCRIPTS.update(self._saved[2])
        self.assertEqual("dict_accept.py", self._saved[0].name)
        self.assertEqual("measure_accept.py", self._saved[1].name)
        self.assertEqual("tt_gate_check.py", accept_wordset.GATE_SCRIPTS["tat"].name)
        self.assertEqual("ru_gate_check.py", accept_wordset.GATE_SCRIPTS["rus"].name)
        self.assertEqual("dictionary_pack.py", self._saved[3].name)
        for script in (self._saved[0], self._saved[1], self._saved[3],
                       *accept_wordset.GATE_SCRIPTS.values()):
            self.assertTrue(script.is_file(), script)
        for queries in accept_wordset.QUERIES.values():
            self.assertTrue(queries.is_file(), queries)


if __name__ == "__main__":
    unittest.main()
