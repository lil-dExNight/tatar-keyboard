#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import sys
import tempfile
import unittest
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PACK_SCRIPT = ROOT / "scripts" / "glide_pack.py"
LAYOUT_DIR = ROOT / "app" / "src" / "main" / "res" / "xml"
DICTIONARY = ROOT / "app" / "src" / "main" / "assets" / "dictionaries" / "tatar_top100k_v1.tdict.zlib"
EVAL_WORDS = ROOT / "app" / "src" / "test" / "resources" / "tt_eval_sentences.txt"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


pack = load_module("glide_pack", PACK_SCRIPT)
typo_pack = pack.typo_pack


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# A tiny fixture geometry: two rows of three keys, 1000 x 1000 each, no gaps.
FIXTURE_RECTS = [
    pack._Rect(ord("а"), 0, 0, 1000, 1000),
    pack._Rect(ord("б"), 1000, 0, 2000, 1000),
    pack._Rect(ord("в"), 2000, 0, 3000, 1000),
    pack._Rect(ord("г"), 0, 1000, 2000, 2000),
    pack._Rect(ord("д"), 1000, 1000, 3000, 2000),
    pack._Rect(ord("е"), 2000, 1000, 4000, 2000),
]
FIXTURE_BY_LETTER = {rect.code_point: rect for rect in FIXTURE_RECTS}
FIXTURE_LETTERS = frozenset(FIXTURE_BY_LETTER)
FIXTURE_RADIUS = pack.key_radius(FIXTURE_RECTS)


class PrimitiveGoldenVectorTest(unittest.TestCase):
    """The portable primitives must match the values the Kotlin calibration test asserts."""

    def test_fnv1a64_golden(self) -> None:
        self.assertEqual(pack.fnv1a64(b""), 14695981039346656037)
        self.assertEqual(pack.fnv1a64("ана".encode("utf-8")), 3368278190552415294)

    def test_splitmix64_golden(self) -> None:
        self.assertEqual(pack.splitmix64(0), 16294208416658607535)
        self.assertEqual(pack.splitmix64(pack.GLIDE_SEED), 1014119616305702635)

    def test_seed_and_knobs_are_the_pinned_constants(self) -> None:
        self.assertEqual(pack.GLIDE_SEED, 20260924)
        self.assertEqual(pack.MIN_WORD_CODE_POINTS, 5)
        self.assertEqual(pack.DICT_MODULUS, 40)
        self.assertEqual(pack.LOOP_MODULUS, 8)
        self.assertEqual(pack.CUT_MODULUS, 10)
        self.assertEqual(pack.STEP_DIVISOR, 6)
        self.assertEqual(pack.TSTEP_MIN, 8)
        self.assertEqual(pack.TSTEP_VAR, 9)
        self.assertEqual(pack.JITTER_PERCENT, 18)
        self.assertEqual(pack.WANDER_DIVISOR, 6)


class VerticalModelTest(unittest.TestCase):
    """The resource-derived vertical model, pinned as exact grid integers."""

    def test_vertical_model_grid_values(self) -> None:
        pitch, key_height, top = pack.vertical_model()
        self.assertEqual(pitch, 10463)
        self.assertEqual(key_height, 8981)
        self.assertEqual(top, 1204)


class GeometryParityTest(unittest.TestCase):
    """The glide geometry reuses typo_pack's device-true x model, extended with real y."""

    @unittest.skipUnless(DICTIONARY.is_file(), "committed dictionary asset not available")
    def test_x_edges_match_typo_pack_geometry(self) -> None:
        rects = pack.read_glide_geometry(LAYOUT_DIR)
        base = typo_pack.read_layout_geometry(LAYOUT_DIR)
        self.assertEqual(len(rects), 37)
        self.assertEqual(len(rects), len(base))
        for rect, geo in zip(rects, base):
            self.assertEqual(rect.code_point, geo.code_point)
            self.assertEqual(rect.left, geo.left)
            self.assertEqual(rect.right, geo.right)
        # Four letter rows, strictly increasing down the keyboard, full-height rectangles.
        rows = sorted({rect.top for rect in rects})
        self.assertEqual(len(rows), 4)
        for rect in rects:
            self.assertGreater(rect.bottom, rect.top)
            self.assertGreater(rect.right, rect.left)

    @unittest.skipUnless(DICTIONARY.is_file(), "committed dictionary asset not available")
    def test_key_radius_is_the_narrowest_key(self) -> None:
        rects = pack.read_glide_geometry(LAYOUT_DIR)
        self.assertEqual(pack.key_radius(rects), 6971)


class WordSelectionTest(unittest.TestCase):
    def _eval_file(self, directory: Path, lines: list[str]) -> Path:
        path = directory / "eval.txt"
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return path

    def test_selection_rules(self) -> None:
        # "абагá" is not mappable (á has no key), "аб" is too short, "вгд" is eligible only
        # via the dictionary-thinning draw; the eval token must be a dictionary word.
        words = ["абваг", "абвагд", "бвгда", "вгдае", "гдаеб", "аб", "вгд"]
        with tempfile.TemporaryDirectory() as directory:
            eval_path = self._eval_file(
                Path(directory), ["абваг бвгда жэюя", "# comment", ""]
            )
            selected = pack.select_words(
                words, eval_path, FIXTURE_LETTERS, seed=pack.GLIDE_SEED, dict_modulus=1
            )
        # modulus=1 takes every mappable >= 5-cp dictionary word; the eval adds nothing new
        # (its tokens are either already selected or absent from the dictionary).
        self.assertEqual(selected, ["абваг", "абвагд", "бвгда", "вгдае", "гдаеб"])

    def test_selection_is_sorted_and_unique(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            eval_path = self._eval_file(Path(directory), ["гдаеб абваг гдаеб"])
            selected = pack.select_words(
                ["гдаеб", "абваг"], eval_path, FIXTURE_LETTERS, dict_modulus=40
            )
        self.assertEqual(selected, sorted(set(selected)))

    def test_missing_eval_file_fails_closed(self) -> None:
        with self.assertRaises(pack.GlidePackError):
            pack.select_words(["абваг"], Path("/nonexistent/eval.txt"), FIXTURE_LETTERS)

    def test_empty_selection_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            eval_path = self._eval_file(Path(directory), ["жэюя"])
            with self.assertRaises(pack.GlidePackError):
                # Nothing is >= 5 code points and mappable.
                pack.select_words(["жэюя"], eval_path, FIXTURE_LETTERS)

    def test_guardrail_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            eval_path = self._eval_file(Path(directory), [])
            with self.assertRaises(pack.GlideGuardrailError):
                pack.select_words(
                    ["абваг"], eval_path, FIXTURE_LETTERS, dict_modulus=1, max_rows=0
                )


class NoiseModelTest(unittest.TestCase):
    def test_gesture_is_deterministic(self) -> None:
        first = pack.generate_gesture("абвагд", FIXTURE_BY_LETTER, FIXTURE_RADIUS)
        second = pack.generate_gesture("абвагд", FIXTURE_BY_LETTER, FIXTURE_RADIUS)
        self.assertEqual(first, second)

    def test_coordinates_are_integers_with_monotone_time(self) -> None:
        points = pack.generate_gesture("абвагд", FIXTURE_BY_LETTER, FIXTURE_RADIUS)
        self.assertGreater(len(points), 2)
        for x, y, t in points:
            self.assertIsInstance(x, int)
            self.assertIsInstance(y, int)
            self.assertIsInstance(t, int)
        for previous, current in zip(points, points[1:]):
            self.assertGreater(current[2], previous[2])

    def test_jitter_bounds_around_the_walked_path(self) -> None:
        # With jitter zeroed the samples walk the (cut) polyline exactly; with the pinned
        # jitter every emitted point stays within JITTER of some walked point. The simplest
        # exact pin: endpoints stay within jitter of the first/last vertex.
        word = "агдабва"
        points = pack.generate_gesture(word, FIXTURE_BY_LETTER, FIXTURE_RADIUS)
        jitter = FIXTURE_RADIUS * pack.JITTER_PERCENT // 100
        first_rect = FIXTURE_BY_LETTER[ord(word[0])]
        last_rect = FIXTURE_BY_LETTER[ord(word[-1])]
        self.assertLessEqual(abs(points[0][0] - first_rect.center_x), jitter)
        self.assertLessEqual(abs(points[0][1] - first_rect.center_y), jitter)
        self.assertLessEqual(abs(points[-1][0] - last_rect.center_x), jitter)
        self.assertLessEqual(abs(points[-1][1] - last_rect.center_y), jitter)

    def test_doubled_letter_can_draw_the_loop(self) -> None:
        # The loop machinery: a doubled letter with draw_loop=True contributes the 4 corners
        # (3 extra vertices), and at least one fixture word's pinned stream draws it.
        plain = pack._ideal_vertices("абба", FIXTURE_BY_LETTER, False)[0]
        looped = pack._ideal_vertices("абба", FIXTURE_BY_LETTER, True)[0]
        self.assertEqual(len(plain), 4)
        self.assertEqual(len(looped), 7)
        self.assertEqual(looped[0], plain[0])
        drawn = []
        for word in ("ббага", "ббдга", "ввбга", "еебга", "абба", "агга", "адда"):
            stream = pack.splitmix64(pack.GLIDE_SEED ^ pack.fnv1a64(word.encode("utf-8")))
            if stream % pack.LOOP_MODULUS == 0:
                drawn.append(word)
        self.assertTrue(drawn, "no fixture word's stream draws the doubled-letter loop")

    def test_render_format_is_pinned(self) -> None:
        points = [(1, 2, 0), (30, 40, 8)]
        self.assertEqual(pack.render_gesture("аб", points), "аб\t1,2,0;30,40,8\n")


class DeterminismTest(unittest.TestCase):
    def test_generate_set_is_byte_identical(self) -> None:
        words = ["абваг", "бвгда", "абба"]
        first = pack.generate_set(words, FIXTURE_RECTS)
        second = pack.generate_set(words, FIXTURE_RECTS)
        self.assertEqual(first, second)

    def test_fixture_gesture_golden(self) -> None:
        # Pinned point-for-point: a generator regression is caught here before the JVM side
        # reports a whole-set SHA mismatch with no locality.
        points = pack.generate_gesture("абвагд", FIXTURE_BY_LETTER, FIXTURE_RADIUS)
        rendered = pack.render_gesture("абвагд", points)
        self.assertEqual(
            sha256_bytes(rendered.encode("utf-8")),
            "a41c706ae8162136bceb2a9b99f0323292c7de861cb52225fe41eb7ccd85e5a0",
        )


class CommittedInputsSmokeTest(unittest.TestCase):
    """When the committed asset is present, a real build reproduces the pinned set identity."""

    @unittest.skipUnless(DICTIONARY.is_file(), "committed dictionary asset not available")
    def test_real_build_matches_recorded_set_identity(self) -> None:
        words = typo_pack.read_dictionary_words(DICTIONARY)
        rects = pack.read_glide_geometry(LAYOUT_DIR)
        letters = frozenset(rect.code_point for rect in rects)
        selected = pack.select_words(words, EVAL_WORDS, letters)
        _, data = pack.generate_set(selected, rects)
        # Recorded in docs/ROADMAP-P7.md and asserted identically by the Kotlin calibration
        # test (GlideRecoveryCalibrationTest) -- the cross-language mirror pin.
        self.assertEqual(len(selected), 4498)
        self.assertEqual(len(data), 9181165)
        self.assertEqual(
            sha256_bytes(data),
            "ea7a58fa090906fd4f4af66da9ba9e928d1d8e405882adc9b9ebb390c4e59549",
        )


class CliTest(unittest.TestCase):
    def _fixture_dictionary(self, directory: Path) -> Path:
        # A tiny canonical schema-2 dictionary over fixture letters (words code-point sorted).
        words = ["абваг", "абвагд", "бвгда", "вгдае", "гдаеб"]
        encoded = [word.encode("utf-8") for word in words]
        block_size = typo_pack._TDICT_BLOCK_SIZE
        block_count = (len(encoded) + block_size - 1) // block_size
        blocks_offset = typo_pack._TDICT_HEADER_SIZE + 4 * block_count
        import struct

        blocks = []
        block_offsets = []
        cursor = 0
        for start in range(0, len(encoded), block_size):
            chunk = encoded[start : start + block_size]
            first = chunk[0]
            block = bytearray([len(first)]) + first
            for word in chunk[1:]:
                prefix = 0
                limit = min(len(first), len(word))
                while prefix < limit and first[prefix] == word[prefix]:
                    prefix += 1
                block.append(prefix)
                block.append(len(word) - prefix)
                block += word[prefix:]
            block += bytes([100]) * len(chunk)
            block_offsets.append(blocks_offset + cursor)
            blocks.append(bytes(block))
            cursor += len(block)
        file_size = blocks_offset + cursor
        header = bytearray()
        header += typo_pack._TDICT_MAGIC
        header += struct.pack("<HHHH", 2, 1, typo_pack._TDICT_HEADER_SIZE, 1)
        header += struct.pack(
            "<IIIIII", len(encoded), block_count, typo_pack._TDICT_HEADER_SIZE,
            blocks_offset, cursor, file_size,
        )
        header += b"\x00" * 32
        body = b"".join(struct.pack("<I", offset) for offset in block_offsets)
        raw = bytes(header) + body + b"".join(blocks)
        path = Path(directory) / "fixture.tdict.zlib"
        path.write_bytes(zlib.compress(raw, 9))
        return path

    def _run_main(self, dictionary: Path, output: Path, eval_path: Path, *, sha=None, raw=None, entries=None) -> int:
        asset = dictionary.read_bytes()
        import zlib as zlib_

        raw_bytes = zlib_.decompress(asset)
        overrides = {
            "EXPECTED_ASSET_SHA256": sha if sha is not None else sha256_bytes(asset),
            "EXPECTED_RAW_SHA256": raw if raw is not None else sha256_bytes(raw_bytes),
            "EXPECTED_ENTRY_COUNT": entries if entries is not None else 5,
        }
        saved = {name: getattr(pack, name) for name in overrides}
        for name, value in overrides.items():
            setattr(pack, name, value)
        try:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return pack.main(
                    [
                        "build",
                        "--dictionary", str(dictionary),
                        "--layout-dir", str(LAYOUT_DIR),
                        "--eval-words", str(eval_path),
                        "--output", str(output),
                    ]
                )
        finally:
            for name, value in saved.items():
                setattr(pack, name, value)

    def test_successful_build_writes_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            dictionary = self._fixture_dictionary(directory)
            eval_path = directory / "eval.txt"
            eval_path.write_text("абваг\n", encoding="utf-8")
            output = directory / "out" / "set.txt"
            self.assertEqual(self._run_main(dictionary, output, eval_path), 0)
            content = output.read_text(encoding="utf-8")
            self.assertIn("абваг\t", content)
            # Deterministic rebuild of the same output.
            again = directory / "out2" / "set.txt"
            self.assertEqual(self._run_main(dictionary, again, eval_path), 0)
            self.assertEqual(content, again.read_text(encoding="utf-8"))

    def test_sha_mismatch_exits_two_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            dictionary = self._fixture_dictionary(directory)
            eval_path = directory / "eval.txt"
            eval_path.write_text("абваг\n", encoding="utf-8")
            output = directory / "set.txt"
            self.assertEqual(
                self._run_main(dictionary, output, eval_path, sha="00" * 32), 2
            )
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
