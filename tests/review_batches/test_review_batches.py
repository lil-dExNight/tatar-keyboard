#!/usr/bin/env python3
"""Tests for scripts/review_batches.py.

The two most important invariants:

    * ``approved`` is never written, under any command, on any path;
    * an unmarked word counts as accepted ONLY inside a portion the curator declared read,
      because otherwise "nobody marked it" and "nobody looked at it" are the same bytes.
"""

from __future__ import annotations

import importlib.util
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "review_batches.py"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


RB = load_module("review_batches", SCRIPT)

QUEUE_HEADER = ["word", "heldout_hits", "train_freq", "train_freq_clean", "sources",
                "license_status", "cap_ratio", "enters_top100k", "approved", "reviewer",
                "review_date", "note"]


ALPHABET = "абвгдежзийклмн"


def name(index: int) -> str:
    """A queue word without digits: the mechanical filter rejects digits, and rightly."""
    return "слово" + ALPHABET[index]


def queue_row(word, train_freq=100, cap="0.00", sources="OpenSubtitles+Tatoeba", note=""):
    values = dict.fromkeys(QUEUE_HEADER, "")
    values.update(word=word, heldout_hits=str(train_freq // 10), train_freq=str(train_freq),
                  train_freq_clean="1", sources=sources,
                  license_status="clean:CC-BY-2.0-FR", cap_ratio=cap,
                  enters_top100k="yes", note=note)
    return values


def write_queue_file(path: Path, rows) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("# тестовая очередь\n")
        handle.write("\t".join(QUEUE_HEADER) + "\n")
        for row in rows:
            handle.write("\t".join(row[name] for name in QUEUE_HEADER) + "\n")


class ReviewBatchesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        self.queue = self.root / "queue.tsv"
        self._root_before = RB.ROOT
        self._queues_before = RB.QUEUES
        RB.ROOT = self.root
        RB.QUEUES = {"ru": ("queue.tsv", "rus", "русская")}
        self.addCleanup(self._restore)

    def _restore(self):
        RB.ROOT = self._root_before
        RB.QUEUES = self._queues_before

    def run_cli(self, *argv):
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = RB.main(list(argv))
        return code, buffer.getvalue()

    def batch(self, name="ru-001"):
        return self.root / "batches" / "ru" / f"{name}.tsv"

    def rows_of(self, path: Path):
        _declared, marks = RB.read_batch(path)
        return marks

    def queue_rows(self):
        return RB.read_queue(self.queue)[2]

    # --- slicing ---------------------------------------------------------------------------

    def test_slice_cuts_in_queue_order_and_keeps_the_remainder(self):
        write_queue_file(self.queue, [queue_row(name(i), 1000 - i) for i in range(7)])
        self.run_cli("slice", "--out", "batches", "--size", "3")
        self.assertEqual([word for word, _ in self.rows_of(self.batch("ru-001"))],
                         [name(0), name(1), name(2)])
        self.assertEqual([word for word, _ in self.rows_of(self.batch("ru-003"))], [name(6)])
        self.assertFalse(self.batch("ru-004").exists())

    def test_slice_never_emits_an_approved_column(self):
        write_queue_file(self.queue, [queue_row("слово")])
        self.run_cli("slice", "--out", "batches", "--size", "3")
        text = self.batch().read_text(encoding="utf-8")
        self.assertNotIn("approved", text)
        self.assertIn("\t".join(RB.BATCH_HEADER), text)

    def test_mechanical_garbage_is_set_aside_with_its_reason(self):
        write_queue_file(self.queue, [
            queue_row("хороший"), queue_row("word"), queue_row("сло8во"),
            queue_row("ш"), queue_row("о" * 31),
        ])
        self.run_cli("slice", "--out", "batches", "--size", "10")
        self.assertEqual([word for word, _ in self.rows_of(self.batch())], ["хороший"])
        dropped = (self.root / "batches" / "ru" / "ru-dropped.tsv").read_text(encoding="utf-8")
        self.assertIn("латиница", dropped)
        self.assertIn("цифры", dropped)
        self.assertIn("одна буква", dropped)
        self.assertIn("длиннее 30", dropped)

    def test_dropped_words_stay_in_the_queue_itself(self):
        write_queue_file(self.queue, [queue_row("хороший"), queue_row("word")])
        self.run_cli("slice", "--out", "batches", "--size", "10")
        self.assertIn("word", [row["word"] for row in self.queue_rows()])

    def test_hint_is_filled_only_where_the_word_alone_cannot_settle_it(self):
        write_queue_file(self.queue, [
            queue_row("обычное"), queue_row("краглин", cap="1.00"), queue_row("оһ"),
        ])
        hints = self.root / "hints"
        hints.mkdir()
        (hints / "hints_rus.json").write_text('{"краглин": "рядом: питер 4", "обычное": "нет"}',
                                              encoding="utf-8")
        self.run_cli("slice", "--out", "batches", "--size", "10", "--hints-dir", str(hints))
        cells = {line.split("\t")[1]: line.split("\t")[4]
                 for line in self.batch().read_text(encoding="utf-8").splitlines()
                 if line.startswith(".")}
        self.assertEqual(cells["обычное"], "")
        self.assertEqual(cells["краглин"], "с заглавной 100% · рядом: питер 4")
        self.assertEqual(cells["оһ"], "")

    # --- collecting ------------------------------------------------------------------------

    def mark(self, path: Path, word: str, mark: str) -> None:
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            fields = line.split("\t")
            if len(fields) == len(RB.BATCH_HEADER) and fields[1] == word:
                fields[0] = mark
                lines[index] = "\t".join(fields)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def declare_read(self, path: Path, text: str = "да") -> None:
        content = path.read_text(encoding="utf-8")
        content = content.replace(RB.DONE_PREFIX + "\n", f"{RB.DONE_PREFIX} {text}\n")
        path.write_text(content, encoding="utf-8")

    def prepared(self, count=4):
        write_queue_file(self.queue, [queue_row(name(i), 100 - i) for i in range(count)])
        self.run_cli("slice", "--out", "batches", "--size", "10")
        return self.batch()

    def test_an_undeclared_portion_accepts_nothing(self):
        batch = self.prepared()
        self.mark(batch, name(1), "x")
        _code, output = self.run_cli("collect", "--out", "batches")
        self.assertIn("вычитано 0", output)
        self.assertIn("принято 0, отвергнуто 0", output)
        self.assertTrue(all("вычитка" not in row["note"] for row in self.queue_rows()))

    def test_a_declared_portion_accepts_everything_unmarked(self):
        batch = self.prepared()
        self.mark(batch, name(1), "x имя")
        self.declare_read(batch)
        _code, output = self.run_cli("collect", "--out", "batches")
        self.assertIn("принято 3, отвергнуто 1", output)
        notes = {row["word"]: row["note"] for row in self.queue_rows()}
        self.assertEqual(notes[name(1)], "[вычитка: отказ, имя]")
        self.assertEqual(notes[name(0)], "[вычитка: принято]")

    def test_the_dot_placeholder_is_not_a_refusal(self):
        batch = self.prepared()
        self.declare_read(batch)
        _code, output = self.run_cli("collect", "--out", "batches")
        self.assertIn("принято 4, отвергнуто 0", output)

    def test_collect_is_idempotent(self):
        batch = self.prepared()
        self.mark(batch, name(2), "x")
        self.declare_read(batch)
        self.run_cli("collect", "--out", "batches")
        first = self.queue.read_text(encoding="utf-8")
        self.run_cli("collect", "--out", "batches")
        self.assertEqual(first, self.queue.read_text(encoding="utf-8"))

    def test_collect_preserves_a_note_the_operator_wrote_himself(self):
        write_queue_file(self.queue, [queue_row(name(0), note="его собственная заметка")])
        self.run_cli("slice", "--out", "batches", "--size", "10")
        self.declare_read(self.batch())
        self.run_cli("collect", "--out", "batches")
        self.assertEqual(self.queue_rows()[0]["note"],
                         "его собственная заметка [вычитка: принято]")

    def test_a_refusal_keeps_the_reason_written_next_to_it(self):
        batch = self.prepared()
        self.mark(batch, name(1), "x имя, не слово")
        self.declare_read(batch)
        self.run_cli("collect", "--out", "batches")
        marks = (self.root / "batches" / "marks-ru.tsv").read_text(encoding="utf-8")
        self.assertIn(name(1) + "\tотказ\tимя, не слово", marks)
        notes = {row["word"]: row["note"] for row in self.queue_rows()}
        self.assertEqual(notes[name(1)], "[вычитка: отказ, имя, не слово]")

    def test_a_bare_cyrillic_x_is_a_refusal_too(self):
        batch = self.prepared()
        self.mark(batch, name(2), "х")
        self.declare_read(batch)
        _code, output = self.run_cli("collect", "--out", "batches")
        self.assertIn("отвергнуто 1", output)

    def test_marks_file_survives_a_regenerated_queue(self):
        batch = self.prepared()
        self.mark(batch, name(3), "x")
        self.declare_read(batch)
        self.run_cli("collect", "--out", "batches")
        marks = (self.root / "batches" / "marks-ru.tsv").read_text(encoding="utf-8")
        self.assertIn(name(3) + "\tотказ", marks)
        self.assertIn(name(0) + "\tпринято", marks)
        self.assertIn("слово\tрешение\tпричина", marks)
        write_queue_file(self.queue, [queue_row(name(i), 100 - i) for i in range(4)])
        self.run_cli("collect", "--out", "batches")
        notes = {row["word"]: row["note"] for row in self.queue_rows()}
        self.assertEqual(notes[name(3)], "[вычитка: отказ]")

    def test_collect_reports_portions_that_no_longer_match_the_queue(self):
        batch = self.prepared()
        self.declare_read(batch)
        write_queue_file(self.queue, [queue_row("совсем_другое")])
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = RB.main(["collect", "--out", "batches"])
        self.assertEqual(code, 1)

    def test_progress_file_names_every_portion_and_its_state(self):
        batch = self.prepared()
        self.declare_read(batch)
        self.run_cli("collect", "--out", "batches")
        progress = (self.root / "batches" / "PROGRESS.tsv").read_text(encoding="utf-8")
        self.assertIn("ru-001\tрусская\t4\tвычитано", progress)

    # --- the approved column ---------------------------------------------------------------

    def test_collect_leaves_approved_exactly_as_it_found_it(self):
        rows = [queue_row(name(0)), queue_row(name(1))]
        rows[0]["approved"] = "yes"
        write_queue_file(self.queue, rows)
        self.run_cli("slice", "--out", "batches", "--size", "10")
        batch = self.batch()
        self.mark(batch, name(1), "x")
        self.declare_read(batch)
        self.run_cli("collect", "--out", "batches")
        approved = {row["word"]: row["approved"] for row in self.queue_rows()}
        self.assertEqual(approved, {name(0): "yes", name(1): ""})

    def test_writing_the_queue_refuses_if_approved_would_change(self):
        write_queue_file(self.queue, [queue_row(name(0))])
        preamble, header, rows = RB.read_queue(self.queue)
        rows[0]["approved"] = "yes"
        with self.assertRaises(SystemExit):
            RB.write_queue(self.queue, preamble, header, rows, [""])


if __name__ == "__main__":
    unittest.main()
