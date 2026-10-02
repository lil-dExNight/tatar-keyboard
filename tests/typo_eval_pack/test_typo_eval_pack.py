#!/usr/bin/env python3

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
LAYOUT_DIR = ROOT / "app" / "src" / "main" / "res" / "xml"
DICTIONARY = ROOT / "app" / "src" / "main" / "assets" / "dictionaries" / "tatar_top100k_v1.tdict.zlib"
EVAL_SET = ROOT / "app" / "src" / "test" / "resources" / "tt_eval_sentences.txt"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


# typo_pack first: typo_eval_pack imports it at module load time.
typo_pack = load_module("typo_pack", ROOT / "scripts" / "typo_pack.py")
pack = load_module("typo_eval_pack", ROOT / "scripts" / "typo_eval_pack.py")


# A tiny synthetic geometry and alphabet for the golden vectors: "а" neighbors "б" and "ә",
# "ә" neighbors "а" (tuples sorted ascending, as the layout readers produce them).
GEO = {ord("а"): (ord("б"), ord("ә")), ord("ә"): (ord("а"),)}
ALPHABET = tuple(sorted(ord(c) for c in "абә"))


def mutate(class_name: str, word: str) -> str | None:
    code_points = tuple(ord(c) for c in word)
    stream = pack.SplitMixStream(pack.stream_seed(word, pack.CLASS_TAGS[class_name]))
    mutated = pack._MUTATORS[class_name](code_points, GEO, ALPHABET, stream)
    return None if mutated is None else "".join(chr(cp) for cp in mutated)


class StreamGoldenVectorTest(unittest.TestCase):
    """The per-(word, class) stream; TypoMutatedEvalTest.kt asserts the same numbers."""

    def test_seed_and_class_tags(self) -> None:
        self.assertEqual(pack.TYPO_EVAL_SEED, 0x54544556)
        self.assertEqual(pack.CLASS_TAGS, {"sub": 1, "del": 2, "ins": 3, "trans": 4})
        self.assertEqual(pack.CLASS_NAMES, ("sub", "del", "ins", "trans"))

    def test_stream_seed_golden(self) -> None:
        self.assertEqual(pack.stream_seed("китап", 1), 11854883575165184619)
        self.assertEqual(pack.stream_seed("сәләм", 3), 666962004492003703)
        self.assertEqual(pack.stream_seed("бала", 2), 926136100999027154)
        self.assertEqual(pack.stream_seed("абә", 1), 11225868616927759322)

    def test_stream_walk_golden(self) -> None:
        stream = pack.SplitMixStream(pack.stream_seed("китап", 1))
        self.assertEqual(stream.next(), 10703956154878274768)
        self.assertEqual(stream.next(), 6640773307694223639)

    def test_index_maps_by_unsigned_modulo(self) -> None:
        # draw1 = 6053160482508434007 for ("бала", tag 2); 6053160482508434007 % 4 == 3.
        stream = pack.SplitMixStream(pack.stream_seed("бала", 2))
        self.assertEqual(stream.next(), 6053160482508434007)
        stream = pack.SplitMixStream(pack.stream_seed("бала", 2))
        self.assertEqual(stream.index(4), 3)


class MutationPrimitiveGoldenTest(unittest.TestCase):
    """Hand-computed tiny cases on the GEO/ALPHABET fixtures (the draw math is in the comments)."""

    def test_sub_picks_position_then_neighbor(self) -> None:
        # "абә": eligible positions [0, 2]; draw1 = 12259115647152501949, % 2 == 1 -> position 2;
        # its only neighbor is "а", so the second draw is forced.
        self.assertEqual(mutate("sub", "абә"), "аба")

    def test_sub_second_draw_selects_the_neighbor(self) -> None:
        # "баба": eligible positions [1, 3]; draw1 = 16122752976182749948, % 2 == 0 -> position 1
        # ("а", neighbors ("б", "ә")); draw2 = 6889992860831780668, % 2 == 0 -> "б".
        self.assertEqual(mutate("sub", "баба"), "ббба")

    def test_sub_without_a_neighbor_anywhere_is_skipped(self) -> None:
        self.assertIsNone(mutate("sub", "бббб"))

    def test_del_removes_the_drawn_position(self) -> None:
        # draw1 = 6053160482508434007, % 4 == 3 -> the trailing "а" is removed.
        self.assertEqual(mutate("del", "бала"), "бал")

    def test_ins_picks_position_then_letter(self) -> None:
        # draw1 = 2621302606675212401, % 5 == 1 -> insert at position 1;
        # draw2 = 583060744491578939, % 3 == 2 -> ALPHABET[2] == "ә".
        self.assertEqual(mutate("ins", "бала"), "бәала")

    def test_trans_swaps_the_drawn_pivot(self) -> None:
        # draw1 = 1816633305461168532, % 3 == 0 -> swap positions (0, 1).
        self.assertEqual(mutate("trans", "бала"), "абла")

    def test_trans_with_a_single_legal_pivot_is_forced(self) -> None:
        # "ааб": the (а, а) pair reproduces the word, so only pivot 1 is eligible.
        self.assertEqual(mutate("trans", "ааб"), "аба")

    def test_trans_without_a_distinct_adjacent_pair_is_skipped(self) -> None:
        self.assertIsNone(mutate("trans", "ааа"))


class ReadEvalWordsTest(unittest.TestCase):
    def test_header_and_blank_lines_are_skipped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "eval.txt"
            path.write_text(
                "# header\n# second header\n\nбала ешкан\n\n# trailer\nбала укый\n",
                encoding="utf-8",
            )
            self.assertEqual(pack.read_eval_words(path), {"бала", "ешкан", "укый"})

    def test_a_missing_file_raises(self) -> None:
        with self.assertRaises(typo_pack.TypoPackError):
            pack.read_eval_words(ROOT / "does" / "not" / "exist")

    def test_an_empty_file_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "eval.txt"
            path.write_text("# only a header\n", encoding="utf-8")
            with self.assertRaises(typo_pack.TypoPackError):
                pack.read_eval_words(path)


class SubstrateWordsTest(unittest.TestCase):
    def test_only_eval_dictionary_words_of_three_or_more_code_points(self) -> None:
        # "ук" is in the dictionary and the eval set but too short; "уты" is long enough but not
        # a dictionary word; "бала" and "укый" qualify.
        dictionary = ["бала", "ук", "укый"]
        self.assertEqual(
            pack.substrate_words(dictionary, {"бала", "ук", "укый", "уты"}),
            ["бала", "укый"],
        )

    def test_exactly_three_code_points_pass(self) -> None:
        dictionary = ["бал", "бала", "ешкан"]
        self.assertEqual(pack.substrate_words(dictionary, {"бал", "бала"}), ["бал", "бала"])

    def test_stored_order_is_kept(self) -> None:
        dictionary = ["бала", "авыл", "ешкан"]
        self.assertEqual(
            pack.substrate_words(dictionary, {"ешкан", "авыл", "бала"}),
            ["бала", "авыл", "ешкан"],
        )


def assert_class_edit(
    test: unittest.TestCase,
    geometric_map: dict[int, tuple[int, ...]],
    alphabet: tuple[int, ...],
    original: str,
    class_name: str,
    mutated: str,
) -> None:
    """The mutated row must differ from its original in exactly the class's edit."""
    test.assertNotEqual(original, mutated)
    if class_name == "sub":
        test.assertEqual(len(original), len(mutated))
        differing = [i for i, (a, b) in enumerate(zip(original, mutated)) if a != b]
        test.assertEqual(len(differing), 1, msg=f"{original!r}->{mutated!r}")
        test.assertIn(ord(mutated[differing[0]]), geometric_map[ord(original[differing[0]])])
    elif class_name == "del":
        test.assertEqual(len(original) - 1, len(mutated))
        test.assertTrue(
            any(original[:i] + original[i + 1 :] == mutated for i in range(len(original))),
            msg=f"{original!r}->{mutated!r}",
        )
    elif class_name == "ins":
        test.assertEqual(len(original) + 1, len(mutated))
        hits = [
            i for i in range(len(mutated)) if mutated[:i] + mutated[i + 1 :] == original
        ]
        test.assertTrue(hits, msg=f"{original!r}->{mutated!r}")
        test.assertIn(ord(mutated[hits[0]]), alphabet)
    elif class_name == "trans":
        test.assertEqual(len(original), len(mutated))
        test.assertTrue(
            any(
                original[i] == mutated[i + 1]
                and original[i + 1] == mutated[i]
                and original[:i] == mutated[:i]
                and original[i + 2 :] == mutated[i + 2 :]
                for i in range(len(original) - 1)
            ),
            msg=f"{original!r}->{mutated!r}",
        )
    else:  # pragma: no cover
        raise AssertionError(class_name)


class BuildSetTest(unittest.TestCase):
    """The built set over a tiny fixture: sort order, coverage, determinism, per-row edit class."""

    FIXTURE_WORDS = ["ааа", "абә", "бала", "бббб"]

    def setUp(self) -> None:
        self.result = pack.build_typo_eval_set(self.FIXTURE_WORDS, GEO, ALPHABET)

    def test_rows_are_sorted_by_original_then_class(self) -> None:
        keys = [(original, class_name) for original, class_name, _ in self.result.rows]
        self.assertEqual(keys, sorted(keys))
        # "ааа" has no distinct adjacent pair, so no transposition; "бббб" has no neighbor and
        # no distinct adjacent pair, so neither a substitution nor a transposition.
        self.assertEqual(
            keys,
            [
                ("ааа", "del"), ("ааа", "ins"), ("ааа", "sub"),
                ("абә", "del"), ("абә", "ins"), ("абә", "sub"), ("абә", "trans"),
                ("бала", "del"), ("бала", "ins"), ("бала", "sub"), ("бала", "trans"),
                ("бббб", "del"), ("бббб", "ins"),
            ],
        )
        self.assertEqual(self.result.class_counts, {"sub": 3, "del": 4, "ins": 4, "trans": 2})
        self.assertEqual(self.result.substrate_count, 4)

    def test_row_format_is_original_tab_class_tab_mutated(self) -> None:
        for line in self.result.text.splitlines():
            original, class_name, mutated = line.split("\t")
            self.assertIn(class_name, pack.CLASS_TAGS)
        self.assertTrue(self.result.text.endswith("\n"))
        self.assertEqual(self.result.data, self.result.text.encode("utf-8"))

    def test_every_row_differs_by_exactly_the_class_edit(self) -> None:
        for original, class_name, mutated in self.result.rows:
            assert_class_edit(self, GEO, ALPHABET, original, class_name, mutated)

    def test_two_builds_are_byte_identical(self) -> None:
        again = pack.build_typo_eval_set(self.FIXTURE_WORDS, GEO, ALPHABET)
        self.assertEqual(self.result.data, again.data)

    def test_the_fixture_set_identity(self) -> None:
        self.assertEqual(self.result.size, 13)
        self.assertEqual(
            self.result.sha256,
            "c138969b3ddccd5847fdba9ce57d2d8a30c0e9e53aa2de5d73eef58671396153",
        )

    def test_an_empty_substrate_raises(self) -> None:
        with self.assertRaises(typo_pack.TypoPackError):
            pack.build_typo_eval_set([], GEO, ALPHABET)


class CommittedSetIdentityTest(unittest.TestCase):
    """On the committed inputs the set identity is pinned; the JVM test asserts the same values."""

    @classmethod
    def setUpClass(cls) -> None:
        if not (DICTIONARY.is_file() and EVAL_SET.is_file()):
            raise unittest.SkipTest("committed dictionary or eval set not available")
        cls.result = pack.generate(EVAL_SET, DICTIONARY, LAYOUT_DIR)

    def test_set_identity(self) -> None:
        result = self.result
        self.assertEqual(result.substrate_count, 2320)
        self.assertEqual(result.size, 9280)
        self.assertEqual(
            result.class_counts, {"sub": 2320, "del": 2320, "ins": 2320, "trans": 2320}
        )
        self.assertEqual(len(result.data), 300800)
        self.assertEqual(
            result.sha256, "eeca46f81817cb908727eef1fca3d9a280f237209358291aeab0119ca6ea5688"
        )

    def test_every_class_is_present(self) -> None:
        for count in self.result.class_counts.values():
            self.assertGreater(count, 0)

    def test_every_row_differs_by_exactly_the_class_edit(self) -> None:
        geometric_map = typo_pack.read_layout_geometric_map(LAYOUT_DIR)
        alphabet = typo_pack.read_layout_alphabet(LAYOUT_DIR)
        for original, class_name, mutated in self.result.rows:
            assert_class_edit(self, geometric_map, alphabet, original, class_name, mutated)

    def test_two_full_builds_are_byte_identical(self) -> None:
        again = pack.generate(EVAL_SET, DICTIONARY, LAYOUT_DIR)
        self.assertEqual(self.result.data, again.data)


class CliTest(unittest.TestCase):
    def setUp(self) -> None:
        if not (DICTIONARY.is_file() and EVAL_SET.is_file()):
            self.skipTest("committed dictionary or eval set not available")

    def run_cli(self, output: Path, **overrides) -> tuple[int, str]:
        args = {
            "eval_set": EVAL_SET,
            "dictionary": DICTIONARY,
            "layout_dir": LAYOUT_DIR,
            "output": output,
        }
        args.update(overrides)
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(io.StringIO()):
            code = pack.main(
                [
                    "build",
                    "--eval-set", str(args["eval_set"]),
                    "--dictionary", str(args["dictionary"]),
                    "--layout-dir", str(args["layout_dir"]),
                    "--output", str(args["output"]),
                ]
            )
        return code, stdout.getvalue()

    def test_a_successful_build_writes_the_set_and_prints_the_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "typo_eval.tsv"
            code, stdout = self.run_cli(out)
            self.assertEqual(code, 0)
            report = json.loads(stdout)
            self.assertEqual(report["rows"], 9280)
            self.assertEqual(
                report["set_sha256"],
                "eeca46f81817cb908727eef1fca3d9a280f237209358291aeab0119ca6ea5688",
            )
            self.assertEqual(report["set_bytes"], 300800)
            self.assertEqual(report["substrate_words"], 2320)
            self.assertEqual(hashlib.sha256(out.read_bytes()).hexdigest(), report["set_sha256"])

    def test_a_missing_eval_set_exits_two_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "typo_eval.tsv"
            code, _ = self.run_cli(out, eval_set=Path(directory) / "missing.txt")
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_a_missing_dictionary_exits_two_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "typo_eval.tsv"
            code, _ = self.run_cli(out, dictionary=Path(directory) / "missing.tdict.zlib")
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_a_dictionary_pin_mismatch_exits_two_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            broken = Path(directory) / "broken.tdict.zlib"
            broken.write_bytes(b"not the pinned dictionary")
            out = Path(directory) / "typo_eval.tsv"
            code, _ = self.run_cli(out, dictionary=broken)
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
