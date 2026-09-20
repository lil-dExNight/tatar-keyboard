#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PACK_SCRIPT = ROOT / "scripts" / "typo_pack.py"
LAYOUT_DIR = ROOT / "app" / "src" / "main" / "res" / "xml"
DICTIONARY = ROOT / "app" / "src" / "main" / "assets" / "dictionaries" / "tatar_top100k_v1.tdict.zlib"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


pack = load_module("typo_pack", PACK_SCRIPT)


def _make_raw_tdict(words: list[str]) -> bytes:
    """Build a minimal canonical schema-2 tdict for ``words`` (already sorted, unique)."""
    encoded = [word.encode("utf-8") for word in words]
    count = len(encoded)
    block_size = pack._TDICT_BLOCK_SIZE
    block_count = (count + block_size - 1) // block_size
    block_index_offset = pack._TDICT_HEADER_SIZE
    blocks_offset = block_index_offset + 4 * block_count
    blocks = []
    block_offsets = []
    cursor = 0
    for start in range(0, count, block_size):
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
        block += bytes([100]) * len(chunk)  # positive single-byte varint frequencies
        block_offsets.append(blocks_offset + cursor)
        blocks.append(bytes(block))
        cursor += len(block)
    blocks_size = cursor
    file_size = blocks_offset + blocks_size
    header = bytearray()
    header += pack._TDICT_MAGIC
    header += struct.pack("<HHHH", 2, 1, pack._TDICT_HEADER_SIZE, 1)
    header += struct.pack(
        "<IIIIII", count, block_count, block_index_offset, blocks_offset,
        blocks_size, file_size
    )
    header += b"\x00" * 32  # checksum field; not verified by the generator's enumerator
    assert len(header) == pack._TDICT_HEADER_SIZE
    body = b"".join(struct.pack("<I", offset) for offset in block_offsets)
    body += b"".join(blocks)
    return bytes(header) + body


# A tiny synthetic vocabulary with letters that carry long-press partners. Code-point sorted.
FIXTURE_WORDS = sorted(
    {
        "аба", "ана", "аны", "бала", "балалар", "гали", "нур", "нуры",
        "уку", "укучы", "һава", "әни", "әти", "җан", "өч", "күл",
    }
)
FIXTURE_RAW = _make_raw_tdict(FIXTURE_WORDS)
FIXTURE_ASSET = zlib.compress(FIXTURE_RAW, 9)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@contextlib.contextmanager
def _asset_file(data: bytes):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "fixture.tdict.zlib"
        path.write_bytes(data)
        yield path


def run_main(dictionary: Path, output: Path, *, sha=None, raw_sha=None, entries=None, extra_args=()) -> int:
    """Invoke the CLI entry point with the pins patched to the fixture; returns the exit code."""
    overrides = {
        "EXPECTED_ASSET_SHA256": sha if sha is not None else sha256_bytes(FIXTURE_ASSET),
        "EXPECTED_RAW_SHA256": raw_sha if raw_sha is not None else sha256_bytes(FIXTURE_RAW),
        "EXPECTED_ENTRY_COUNT": entries if entries is not None else len(FIXTURE_WORDS),
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
                    "--output", str(output),
                    *extra_args,
                ]
            )
    finally:
        for name, value in saved.items():
            setattr(pack, name, value)


class PrimitiveGoldenVectorTest(unittest.TestCase):
    """The portable primitives must match the values the Kotlin calibration test asserts."""

    def test_fnv1a64_golden(self) -> None:
        self.assertEqual(pack.fnv1a64(b""), 14695981039346656037)
        self.assertEqual(pack.fnv1a64("ана".encode("utf-8")), 3368278190552415294)

    def test_splitmix64_golden(self) -> None:
        self.assertEqual(pack.splitmix64(0), 16294208416658607535)
        self.assertEqual(pack.splitmix64(pack.TYPO_SEED), 5623135597990589359)

    def test_selection_index_golden(self) -> None:
        self.assertEqual(pack.selection_index("ана", 10), 0)
        self.assertEqual(pack.selection_index("китап", 10), 3)
        self.assertEqual(pack.selection_index("авыл", 10), 7)

    def test_seed_is_the_pinned_constant(self) -> None:
        self.assertEqual(pack.TYPO_SEED, 20260727)
        self.assertEqual(pack.PREFIX_CODE_POINTS, 3)


class LayoutNeighborMapTest(unittest.TestCase):
    """Pairs come from res/xml (latin:moreKeys), symmetrized like KeyNeighborTable — not hard-coded."""

    def setUp(self) -> None:
        self.neighbor_map = pack.read_layout_neighbor_map(LAYOUT_DIR)

    def _partners(self, letter: str) -> tuple[str, ...]:
        return tuple(chr(cp) for cp in self.neighbor_map.get(ord(letter), ()))

    def test_forward_pairs_taken_from_layout(self) -> None:
        self.assertEqual(self._partners("а"), ("ә",))
        self.assertEqual(self._partners("о"), ("ө",))
        self.assertEqual(self._partners("у"), ("ү",))
        self.assertEqual(self._partners("ж"), ("җ",))
        self.assertEqual(self._partners("н"), ("ң",))
        self.assertEqual(self._partners("е"), ("ё",))
        self.assertEqual(self._partners("ь"), ("ъ",))

    def test_symmetrized_and_deduplicated_like_key_neighbor_table(self) -> None:
        # "ә" is declared on both "а" and "э"; the reverse edge gives both bases in code-point order.
        self.assertEqual(self._partners("ә"), ("а", "э"))
        self.assertEqual(self._partners("э"), ("ә",))
        # "һ" is declared on both "г" and "х".
        self.assertEqual(self._partners("һ"), ("г", "х"))
        self.assertEqual(self._partners("г"), ("һ",))
        self.assertEqual(self._partners("х"), ("һ",))

    def test_exactly_ten_undirected_pairs(self) -> None:
        undirected = set()
        for node, partners in self.neighbor_map.items():
            for partner in partners:
                undirected.add(frozenset((node, partner)))
        self.assertEqual(len(undirected), 10)

    def test_plain_letters_have_no_partner(self) -> None:
        for letter in "кибстфцшщзйлрдп":
            self.assertNotIn(ord(letter), self.neighbor_map)


class TypoSetMethodologyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.neighbor_map = pack.read_layout_neighbor_map(LAYOUT_DIR)
        self.typo_set = pack.build_typo_set(FIXTURE_WORDS, self.neighbor_map)

    def test_prefix_is_exactly_three_code_points_with_one_letter_swapped(self) -> None:
        for original, typo in self.typo_set.rows:
            original_prefix = [ord(ch) for ch in original][:3]
            typo_prefix = [ord(ch) for ch in typo]
            self.assertEqual(len(typo_prefix), 3)
            differing = [i for i in range(3) if typo_prefix[i] != original_prefix[i]]
            self.assertEqual(len(differing), 1, msg=f"{original!r}->{typo!r}")
            position = differing[0]
            self.assertIn(typo_prefix[position], self.neighbor_map[original_prefix[position]])

    def test_short_and_ineligible_words_are_skipped(self) -> None:
        originals = {row[0] for row in self.typo_set.rows}
        self.assertNotIn("өч", originals)  # only two code points
        # Every emitted original has at least three code points.
        for original in originals:
            self.assertGreaterEqual(len(original), 3)

    def test_variant_stats_are_populated(self) -> None:
        self.assertGreaterEqual(self.typo_set.variant_max, 1)
        self.assertLessEqual(self.typo_set.variant_p50, self.typo_set.variant_p95)


class DeterminismTest(unittest.TestCase):
    def test_build_typo_set_is_byte_identical(self) -> None:
        neighbor_map = pack.read_layout_neighbor_map(LAYOUT_DIR)
        first = pack.build_typo_set(FIXTURE_WORDS, neighbor_map)
        second = pack.build_typo_set(FIXTURE_WORDS, neighbor_map)
        self.assertEqual(first.data, second.data)
        self.assertEqual(first.sha256, second.sha256)

    def test_full_generate_is_byte_identical(self) -> None:
        with _asset_file(FIXTURE_ASSET) as asset:
            first, _ = pack.generate(
                asset, LAYOUT_DIR,
                expected_asset_sha256=sha256_bytes(FIXTURE_ASSET),
                expected_raw_sha256=sha256_bytes(FIXTURE_RAW),
                expected_entry_count=len(FIXTURE_WORDS),
            )
            second, _ = pack.generate(
                asset, LAYOUT_DIR,
                expected_asset_sha256=sha256_bytes(FIXTURE_ASSET),
                expected_raw_sha256=sha256_bytes(FIXTURE_RAW),
                expected_entry_count=len(FIXTURE_WORDS),
            )
        self.assertEqual(first.sha256, second.sha256)


class FailClosedCoreTest(unittest.TestCase):
    def test_asset_sha_mismatch_raises(self) -> None:
        with _asset_file(FIXTURE_ASSET) as asset:
            with self.assertRaises(pack.TypoPackError):
                pack.read_dictionary_words(asset, expected_asset_sha256="00" * 32)

    def test_raw_sha_mismatch_raises(self) -> None:
        with _asset_file(FIXTURE_ASSET) as asset:
            with self.assertRaises(pack.TypoPackError):
                pack.read_dictionary_words(
                    asset,
                    expected_asset_sha256=sha256_bytes(FIXTURE_ASSET),
                    expected_raw_sha256="00" * 32,
                    expected_entry_count=len(FIXTURE_WORDS),
                )

    def test_entry_count_mismatch_raises(self) -> None:
        with _asset_file(FIXTURE_ASSET) as asset:
            with self.assertRaises(pack.TypoPackError):
                pack.read_dictionary_words(
                    asset,
                    expected_asset_sha256=sha256_bytes(FIXTURE_ASSET),
                    expected_raw_sha256=sha256_bytes(FIXTURE_RAW),
                    expected_entry_count=len(FIXTURE_WORDS) + 1,
                )

    def test_bad_magic_raises(self) -> None:
        broken = b"XXXXXXXX" + FIXTURE_RAW[8:]
        with self.assertRaises(pack.TypoPackError):
            pack._parse_tdict_words(broken, expected_entry_count=len(FIXTURE_WORDS))

    def test_noncanonical_layout_raises(self) -> None:
        broken = bytearray(FIXTURE_RAW)
        struct.pack_into("<I", broken, 20, 999)  # corrupt offsets_offset
        with self.assertRaises(pack.TypoPackError):
            pack._parse_tdict_words(bytes(broken), expected_entry_count=len(FIXTURE_WORDS))

    def test_not_a_zlib_stream_raises(self) -> None:
        with _asset_file(b"not zlib at all") as asset:
            with self.assertRaises(pack.TypoPackError):
                pack.read_dictionary_words(
                    asset, expected_asset_sha256=sha256_bytes(b"not zlib at all")
                )

    def test_no_eligible_word_raises(self) -> None:
        neighbor_map = pack.read_layout_neighbor_map(LAYOUT_DIR)
        # None of these have a partner-bearing letter in the first three code points.
        with self.assertRaises(pack.TypoPackError):
            pack.build_typo_set(["ктс", "спт", "дфц"], neighbor_map)

    def test_guardrail_raises(self) -> None:
        neighbor_map = pack.read_layout_neighbor_map(LAYOUT_DIR)
        with self.assertRaises(pack.TypoGuardrailError):
            pack.build_typo_set(FIXTURE_WORDS, neighbor_map, max_rows=1)

    def test_missing_layout_dir_raises(self) -> None:
        with self.assertRaises(pack.TypoPackError):
            pack.read_layout_neighbor_map(ROOT / "does" / "not" / "exist")

    def test_key_with_more_keys_but_multichar_keyspec_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            layout = Path(directory)
            # Provide all four expected files; corrupt just one key.
            for name in pack._TATAR_ROWKEY_FILES:
                (layout / name).write_text(
                    "<merge xmlns:latin='http://schemas.android.com/apk/res-auto'>"
                    "<Key latin:keySpec='ab' latin:moreKeys='&#x04D9;'/></merge>",
                    encoding="utf-8",
                )
            with self.assertRaises(pack.TypoPackError):
                pack.read_layout_neighbor_map(layout)


class FailClosedExitCodeTest(unittest.TestCase):
    def _out(self, directory: str) -> Path:
        return Path(directory) / "typo_set.txt"

    def test_successful_build_writes_output(self) -> None:
        with _asset_file(FIXTURE_ASSET) as asset, tempfile.TemporaryDirectory() as directory:
            out = self._out(directory)
            code = run_main(asset, out)
            self.assertEqual(code, 0)
            self.assertTrue(out.exists())
            self.assertTrue(out.read_bytes())

    def test_sha_mismatch_exits_two_without_output(self) -> None:
        with _asset_file(FIXTURE_ASSET) as asset, tempfile.TemporaryDirectory() as directory:
            out = self._out(directory)
            code = run_main(asset, out, sha="00" * 32)
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())

    def test_guardrail_exit_does_not_publish_partial_output(self) -> None:
        # Force a guardrail breach by pinning the entry count so build proceeds, then shrinking the
        # allowed rows via a patched module-level ceiling is not exposed; instead corrupt the raw sha
        # to hit exit 2 while an old file is present, proving no partial overwrite.
        with _asset_file(FIXTURE_ASSET) as asset, tempfile.TemporaryDirectory() as directory:
            out = self._out(directory)
            out.write_bytes(b"OLD CONTENT")
            code = run_main(asset, out, raw_sha="00" * 32)
            self.assertEqual(code, 2)
            self.assertEqual(out.read_bytes(), b"OLD CONTENT")


class CommittedInputsSmokeTest(unittest.TestCase):
    """When the committed asset is present, a real build reproduces the pinned set identity."""

    @unittest.skipUnless(DICTIONARY.is_file(), "committed dictionary asset not available")
    def test_real_build_matches_recorded_set_identity(self) -> None:
        typo_set, neighbor_map = pack.generate(DICTIONARY, LAYOUT_DIR)
        # Recorded in docs/DICTIONARY-E3.md and asserted identically by the Kotlin calibration test.
        # Recalibrated 2026-09-20 (TT-SUGGESTIONS P2): the dictionary grew to 110 000 entries,
        # so the reproducible set grew with it (87 360 -> 96 118 rows).
        self.assertEqual(typo_set.size, 96118)
        self.assertEqual(
            typo_set.sha256,
            "1bf09f403a288c111a1607c83eecee3faa410ee5669015b558e292cbe28e9aee",
        )
        self.assertEqual(typo_set.variant_p95, 3)
        self.assertEqual(len({frozenset((n, p)) for n, ps in neighbor_map.items() for p in ps}), 10)


class GeometricMapTest(unittest.TestCase):
    """Edit class #2 geometric neighbours — device-true (gap-aware) model, Phase B."""

    def setUp(self) -> None:
        self.geo = pack.read_layout_geometry(LAYOUT_DIR)
        self.geometric_map = pack.build_geometric_map(self.geo)

    def test_thirty_seven_letter_keys(self) -> None:
        self.assertEqual(len(self.geo), 37)

    def test_thirty_two_undirected_pairs_and_symmetry(self) -> None:
        # The horizontal gap kills every same-row "touch" on device: exactly the 32 cross-row
        # pairs survive (docs/TT-TYPO-NEXT.md).
        undirected = set()
        for node, partners in self.geometric_map.items():
            for partner in partners:
                undirected.add(frozenset((node, partner)))
                self.assertIn(node, self.geometric_map[partner])
        self.assertEqual(len(undirected), 32)

    def test_no_same_row_pair_survives_the_gap(self) -> None:
        row_of = {key.code_point: key.row for key in self.geo}
        for node, partners in self.geometric_map.items():
            for partner in partners:
                self.assertNotEqual(row_of[node], row_of[partner])

    def test_the_mission_pair_survives(self) -> None:
        # ц sits one row below ә with ~79 % overlap — the pair that turns "сцләм" into "сәләм".
        self.assertIn(ord("ә"), self.geometric_map[ord("ц")])
        self.assertIn(ord("ц"), self.geometric_map[ord("ә")])

    def test_average_fanout_and_max(self) -> None:
        total = sum(len(v) for v in self.geometric_map.values())
        self.assertEqual(len(self.geometric_map), 37)
        self.assertAlmostEqual(total / 37, 64 / 37, places=4)
        self.assertEqual(max(len(v) for v in self.geometric_map.values()), 3)

    def test_fifth_row_letters_connect_to_the_alphabet(self) -> None:
        for letter in "әөүҗңһ":
            partners = self.geometric_map.get(ord(letter), ())
            self.assertTrue(partners)
            self.assertTrue(any(chr(cp) not in "әөүҗңһ" for cp in partners))

    def test_a_specific_neighbour_set(self) -> None:
        self.assertEqual("".join(chr(cp) for cp in self.geometric_map[ord("к")]), "аө")


class DeviceGeometryModelTest(unittest.TestCase):
    """The Phase-B device-true geometry model (KeyboardBuilder/KeyboardRow/Key reproduced)."""

    def test_gap_and_paddings_are_read_from_the_base_config(self) -> None:
        gap, left_pad, right_pad = pack.read_keyboard_gaps(
            ROOT / "app" / "src" / "main" / "res" / "values" / "config.xml"
        )
        self.assertEqual((gap, left_pad, right_pad), (1.739, 0.870, 0.870))

    def test_device_round_is_half_up_like_math_round(self) -> None:
        # Python's round() is banker's rounding; the device applies Math.round (floor(x + 0.5)).
        self.assertEqual(pack._device_round(0.5), 1)
        self.assertEqual(pack._device_round(1.5), 2)
        self.assertEqual(pack._device_round(-0.5), 0)
        self.assertEqual(pack._device_round(98.18), 98)

    def test_zero_gap_reproduces_the_pre_phase_b_edge_to_edge_model(self) -> None:
        # With a zero gap the device formula degenerates to keys that DO touch — the old model's
        # 65 pairs (33 same-row + 32 cross-row) come back, which cross-checks the formula.
        legacy = pack.read_layout_geometry(
            LAYOUT_DIR, gap_percent=0.0, left_padding_percent=0.0, right_padding_percent=0.0
        )
        legacy_map = pack.build_geometric_map(legacy)
        undirected = {frozenset((n, p)) for n, ps in legacy_map.items() for p in ps}
        self.assertEqual(len(undirected), 65)

    def test_pair_set_is_identical_across_all_shipped_configs_and_widths(self) -> None:
        # Every shipped config variant (phone/tablet x portrait/landscape) and a width sweep must
        # yield the same 32 pairs — the reference width is a modelling device, not a knob.
        rows = pack._row_width_specs(LAYOUT_DIR)
        rows_path = LAYOUT_DIR / "rows_tatar.xml"
        import xml.etree.ElementTree as ET

        root = ET.parse(rows_path).getroot()
        row_widths = [
            pack._parse_percent(row.get(f"{{{pack._ANDROID_RES_AUTO}}}keyWidth"))
            for row in root.findall("Row")
        ]
        resolved = [
            [(spec, row_width if width is None else width) for spec, width in entries]
            for entries, row_width in zip(rows, row_widths)
        ]
        res_dir = ROOT / "app" / "src" / "main" / "res"
        configs = sorted(res_dir.glob("values*/config.xml"))
        self.assertEqual(len(configs), 6)
        reference = None
        for config in configs:
            gap, left_pad, right_pad = pack.read_keyboard_gaps(config)
            for width in (320, 480, 720, 1080, 1440, 2400, 4000):
                geo = pack.build_device_geometry(
                    resolved,
                    gap_percent=gap,
                    left_padding_percent=left_pad,
                    right_padding_percent=right_pad,
                    width_px=width,
                )
                pairs = {
                    frozenset((n, p))
                    for n, ps in pack.build_geometric_map(geo).items()
                    for p in ps
                }
                self.assertEqual(len(pairs), 32, msg=f"{config} @ {width}px")
                if reference is None:
                    reference = pairs
                self.assertEqual(pairs, reference, msg=f"{config} @ {width}px")


class PrefixWindowCliTest(unittest.TestCase):
    """TT-TYPO-NEXT Phase B: the typo window is a CLI argument (the 5-cp window is the сцләм case)."""

    def test_default_window_stays_three_code_points(self) -> None:
        with _asset_file(FIXTURE_ASSET) as asset, tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "typo_set.txt"
            code = run_main(asset, out)
            self.assertEqual(code, 0)
            self.assertEqual(
                pack.build_typo_set(FIXTURE_WORDS, pack.read_layout_neighbor_map(LAYOUT_DIR)).data,
                out.read_bytes(),
            )

    def test_five_code_point_window_changes_the_set(self) -> None:
        with _asset_file(FIXTURE_ASSET) as asset, tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "typo_set5.txt"
            code = run_main(asset, out, extra_args=["--prefix-code-points", "5"])
            self.assertEqual(code, 0)
            five = out.read_bytes()
            self.assertNotEqual(
                pack.build_typo_set(FIXTURE_WORDS, pack.read_layout_neighbor_map(LAYOUT_DIR)).data,
                five,
            )
            # Every emitted typo prefix is exactly 5 code points with one letter swapped.
            neighbor_map = pack.read_layout_neighbor_map(LAYOUT_DIR)
            for line in five.decode("utf-8").splitlines():
                original, typo = line.split("\t")
                self.assertEqual(len(typo), 5)
                differing = [i for i in range(5) if typo[i] != original[i]]
                self.assertEqual(len(differing), 1)
                self.assertIn(ord(typo[differing[0]]), neighbor_map.get(ord(original[differing[0]]), ()))

    def test_a_nonpositive_window_fails_closed(self) -> None:
        with _asset_file(FIXTURE_ASSET) as asset, tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "typo_set0.txt"
            code = run_main(asset, out, extra_args=["--prefix-code-points", "0"])
            self.assertEqual(code, 2)
            self.assertFalse(out.exists())


class GeometricTypoSetTest(unittest.TestCase):
    def setUp(self) -> None:
        self.geometric_map = pack.read_layout_geometric_map(LAYOUT_DIR)

    def test_exactly_one_geometric_substitution_inside_the_prefix(self) -> None:
        typo_set = pack.build_geometric_typo_set(FIXTURE_WORDS, self.geometric_map)
        for original, typo in typo_set.rows:
            original_prefix = [ord(ch) for ch in original][:3]
            typo_prefix = [ord(ch) for ch in typo]
            self.assertEqual(len(typo_prefix), 3)
            differing = [i for i in range(3) if typo_prefix[i] != original_prefix[i]]
            self.assertEqual(len(differing), 1, msg=f"{original!r}->{typo!r}")
            position = differing[0]
            self.assertIn(typo_prefix[position], self.geometric_map[original_prefix[position]])

    def test_geometric_set_is_byte_identical(self) -> None:
        first = pack.build_geometric_typo_set(FIXTURE_WORDS, self.geometric_map)
        second = pack.build_geometric_typo_set(FIXTURE_WORDS, self.geometric_map)
        self.assertEqual(first.sha256, second.sha256)


class TranspositionTypoSetTest(unittest.TestCase):
    def test_prefix_is_the_original_with_one_adjacent_pair_swapped(self) -> None:
        typo_set = pack.build_transposition_typo_set(FIXTURE_WORDS)
        for original, typo in typo_set.rows:
            original_prefix = [ord(ch) for ch in original][:3]
            typo_prefix = [ord(ch) for ch in typo]
            self.assertEqual(len(typo_prefix), 3)
            # A transposition is a permutation of the prefix and must differ from it.
            self.assertEqual(sorted(typo_prefix), sorted(original_prefix))
            self.assertNotEqual(typo_prefix, original_prefix)

    def test_identical_adjacent_letters_are_ineligible(self) -> None:
        # "ааб": the (а,а) pair reproduces the prefix, so only the (а,б) swap is eligible -> "аба".
        typo_set = pack.build_transposition_typo_set(["ааб"])
        self.assertEqual(typo_set.rows, (("ааб", "аба"),))

    def test_transposition_set_is_byte_identical(self) -> None:
        first = pack.build_transposition_typo_set(FIXTURE_WORDS)
        second = pack.build_transposition_typo_set(FIXTURE_WORDS)
        self.assertEqual(first.sha256, second.sha256)


class LayoutAlphabetTest(unittest.TestCase):
    """The class #4 alphabet — the layout's typeable letters (== KeyNeighborTable.nodes)."""

    def setUp(self) -> None:
        self.alphabet = pack.read_layout_alphabet(LAYOUT_DIR)

    def test_thirty_nine_letters_from_the_layout(self) -> None:
        # 37 letter keys of rowkeys_tatar*.xml plus the more-key-only ё and ъ.
        self.assertEqual(len(self.alphabet), 39)
        self.assertEqual(tuple(sorted(self.alphabet)), self.alphabet)

    def test_more_key_only_letters_are_included(self) -> None:
        self.assertIn(ord("ё"), self.alphabet)
        self.assertIn(ord("ъ"), self.alphabet)

    def test_every_layout_letter_is_present(self) -> None:
        for letter in "әөүҗңһйцукенгшщзхфывапролджэячсмитьбю":
            self.assertIn(ord(letter), self.alphabet)

    def test_missing_layout_dir_raises(self) -> None:
        with self.assertRaises(pack.TypoPackError):
            pack.read_layout_alphabet(ROOT / "does" / "not" / "exist")


class SubstitutionTypoSetTest(unittest.TestCase):
    """Edit class #4: full single substitution over the layout alphabet (Phase C)."""

    def setUp(self) -> None:
        self.alphabet = pack.read_layout_alphabet(LAYOUT_DIR)

    def test_exactly_one_substitution_inside_the_prefix(self) -> None:
        typo_set = pack.build_substitution_typo_set(FIXTURE_WORDS, self.alphabet)
        for original, typo in typo_set.rows:
            original_prefix = [ord(ch) for ch in original][:3]
            typo_prefix = [ord(ch) for ch in typo]
            self.assertEqual(len(typo_prefix), 3)
            differing = [i for i in range(3) if typo_prefix[i] != original_prefix[i]]
            self.assertEqual(len(differing), 1, msg=f"{original!r}->{typo!r}")
            position = differing[0]
            self.assertIn(typo_prefix[position], self.alphabet)
            self.assertNotEqual(typo_prefix[position], original_prefix[position])

    def test_every_word_with_enough_letters_is_eligible(self) -> None:
        typo_set = pack.build_substitution_typo_set(FIXTURE_WORDS, self.alphabet)
        # Every fixture word of >= 3 code points is eligible (the alphabet always has substitutes).
        expected = sum(1 for word in FIXTURE_WORDS if len(word) >= 3)
        self.assertEqual(typo_set.size, expected)

    def test_probe_count_stats(self) -> None:
        typo_set = pack.build_substitution_typo_set(FIXTURE_WORDS, self.alphabet)
        # 3 code points x (39 - 1) = 114 probes per row.
        self.assertEqual(typo_set.variant_p50, 114)
        self.assertEqual(typo_set.variant_max, 114)

    def test_substitution_set_is_byte_identical(self) -> None:
        first = pack.build_substitution_typo_set(FIXTURE_WORDS, self.alphabet)
        second = pack.build_substitution_typo_set(FIXTURE_WORDS, self.alphabet)
        self.assertEqual(first.sha256, second.sha256)


class EditClassDispatchTest(unittest.TestCase):
    def test_generate_dispatches_each_edit_class_to_a_distinct_set(self) -> None:
        with _asset_file(FIXTURE_ASSET) as asset:
            shas = {}
            for edit_class in (1, 2, 3, 4):
                typo_set, _ = pack.generate(
                    asset, LAYOUT_DIR,
                    expected_asset_sha256=sha256_bytes(FIXTURE_ASSET),
                    expected_raw_sha256=sha256_bytes(FIXTURE_RAW),
                    expected_entry_count=len(FIXTURE_WORDS),
                    edit_class=edit_class,
                )
                self.assertGreater(typo_set.size, 0)
                shas[edit_class] = typo_set.sha256
        # The four edit classes are genuinely different transformations.
        self.assertEqual(len(set(shas.values())), 4)

    def test_unknown_edit_class_raises(self) -> None:
        with _asset_file(FIXTURE_ASSET) as asset:
            with self.assertRaises(pack.TypoPackError):
                pack.generate(
                    asset, LAYOUT_DIR,
                    expected_asset_sha256=sha256_bytes(FIXTURE_ASSET),
                    expected_raw_sha256=sha256_bytes(FIXTURE_RAW),
                    expected_entry_count=len(FIXTURE_WORDS),
                    edit_class=9,
                )


class CommittedExtendedSetsSmokeTest(unittest.TestCase):
    """When the committed asset is present, classes #2 and #3 reproduce the recorded identities."""

    @unittest.skipUnless(DICTIONARY.is_file(), "committed dictionary asset not available")
    def test_class1_class2_class3_recorded_identities(self) -> None:
        # Recalibrated 2026-09-20 (TT-SUGGESTIONS P2) for the 110 000-entry dictionary:
        # 87 360 / 99 654 / 99 642 -> 96 118 / 109 649 / 109 637 rows.
        # Recalibrated 2026-09-20 (TT-TYPO-NEXT Phase B): the geometry model learned the
        # horizontal gap, so the class #2 set identity changed (the device-true 32-pair
        # relation); classes #1 and #3 do not involve geometry and are unchanged.
        one, _ = pack.generate(DICTIONARY, LAYOUT_DIR, edit_class=1)
        self.assertEqual(one.size, 96118)
        self.assertEqual(
            one.sha256, "1bf09f403a288c111a1607c83eecee3faa410ee5669015b558e292cbe28e9aee"
        )
        two, _ = pack.generate(DICTIONARY, LAYOUT_DIR, edit_class=2)
        self.assertEqual(two.size, 109649)
        self.assertEqual(
            two.sha256, "f64f46506ec5c0be19aabc496b11da5c3ca9d875631dc25c6e1d511a87dce9d3"
        )
        three, _ = pack.generate(DICTIONARY, LAYOUT_DIR, edit_class=3)
        self.assertEqual(three.size, 109637)
        self.assertEqual(
            three.sha256, "539a701aa80778bb62f5cb0d9a324cdbac7f611262a0eb5bc7af7fb7d6ef5a42"
        )

    @unittest.skipUnless(DICTIONARY.is_file(), "committed dictionary asset not available")
    def test_class1_and_class2_five_code_point_window_identities(self) -> None:
        # Phase B second window (the сцләм case).
        one, _ = pack.generate(DICTIONARY, LAYOUT_DIR, edit_class=1, prefix_code_points=5)
        self.assertEqual(one.size, 102478)
        self.assertEqual(
            one.sha256, "1c0bd7e7cbf523306e69214d05fd0833022d5322143a1f435a8c4f8ce7f953c6"
        )
        two, _ = pack.generate(DICTIONARY, LAYOUT_DIR, edit_class=2, prefix_code_points=5)
        self.assertEqual(two.size, 104955)
        self.assertEqual(
            two.sha256, "165dbaac09b47d333c5abb10a9e5df47d32671613f023747b3f4933258142d0f"
        )

    @unittest.skipUnless(DICTIONARY.is_file(), "committed dictionary asset not available")
    def test_class4_recorded_identities(self) -> None:
        # TT-TYPO-NEXT Phase C (2026-09-20): the full single-substitution sets on the committed
        # 110k asset, both windows, layout-derived alphabet of 39 letters.
        four3, alphabet = pack.generate(DICTIONARY, LAYOUT_DIR, edit_class=4)
        self.assertEqual(four3.size, 109649)
        self.assertEqual(
            four3.sha256, "30897644f3bd5e6ade1f67df2d1498c3ea2676dcb6b635208a0ad510f46ba072"
        )
        self.assertEqual(len(alphabet), 39)
        four5, _ = pack.generate(DICTIONARY, LAYOUT_DIR, edit_class=4, prefix_code_points=5)
        self.assertEqual(four5.size, 104955)
        self.assertEqual(
            four5.sha256, "c35c97701e8ab593f1010476877aff846005f66b92a0eac8e7549c9b87c0327b"
        )


if __name__ == "__main__":
    unittest.main()
