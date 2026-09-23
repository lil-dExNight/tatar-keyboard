#!/usr/bin/env python3
"""Build the deterministic edit-class typo sets used to calibrate recovery@3 (E3a/E3b,
TT-TYPO-NEXT Phase B).

The tool uses only the Python standard library. It emits a reproducible set of edit
class #1 typos -- the contract's "замена буквы на её long-press партнёра" -- as a
deterministic UTF-8/LF text file (data, not code). Given identical inputs the output is
byte-for-byte identical across runs and hosts: there is no time, locale, path or RNG
state in the output, and every random choice is a pure function of a fixed seed and the
word itself.

Two committed inputs, no third:

* the keyboard layout (``res/xml/rowkeys_tatar*.xml`` — and, for the class #2 geometric
  relation, ``res/xml/rows_tatar.xml`` plus the gap/padding fractions of
  ``res/values/config.xml``): the long-press pairs are read from the ``latin:moreKeys``
  attributes and symmetrized + de-duplicated exactly as ``KeyNeighborTable`` does on the
  device, and the geometry reproduces the device layout formula (KeyboardBuilder /
  KeyboardRow / Key, horizontal gap included — see the "Geometric adjacency" section
  below). Not a single pair is hard-coded here -- this is the same principle the engine's
  ``KeyNeighborTableBuilder`` follows. Since TT-TYPO-NEXT Phase B (2026-09-20) the pair
  set this model yields is PROVEN equal to the on-device one (the instrumentation dump on
  a POCO C71, docs/TT-TYPO-NEXT.md).
* the committed dictionary asset
  (``app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib``): the words are its
  110,000-entry vocabulary (110,000 since 2026-09-20, TT-SUGGESTIONS P2), pinned by
  SHA-256 on both the compressed asset and the inflated raw file. The JVM calibration
  test enumerates the very same asset and applies the identical selection rule, so the
  two produce the same reproducible set.

Neither the licensed source corpus nor the emitted typo set is committed to git: the set
is fully reproducible from this generator and the two inputs above (see docs/DICTIONARY-E3.md).

The generator is fail-closed. It exits nonzero and writes no partial output when:

* the compressed asset SHA-256 does not match the pin,
* the inflated raw SHA-256 or entry count does not match the pin,
* the tdict header/section layout is not the canonical schema 1/version 1,
* a word is not strictly valid UTF-8,
* the layout resources yield no long-press pair,
* no dictionary word is eligible for a class #1 typo, or
* a guardrail is breached (more rows than dictionary entries).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import sys
import tempfile
import unicodedata
import xml.etree.ElementTree as ElementTree
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence, TextIO


# --- Pinned input identity: the committed Tatar top-100k dictionary asset. ------------
# These match rkr...storage.DictionaryArtifactSpec.TATAR_TOP100K_V1. The SHA-256 pins are
# the binding gate; the entry count is a readable cross-check.
# 2026-09-01 (SIZE-1): словарь перешёл на schema 2 (front-coding + varint-частоты,
# docs/SIZE-SCHEMA2.md) — состав, порядок и частоты слов побайтно те же, поэтому
# наборы опечаток не пересобираются; поменялись только пины упаковки.
# 2026-09-20 (TT-SUGGESTIONS P2, docs/TT-SUGGESTIONS.md): словарь расширен допущенными
# словоформами до 110 000 записей — наборы опечаток пересобраны с нового состава, пины
# ниже пересчитаны.
EXPECTED_ASSET_SHA256 = (
    "e653ef6ee9d88fd25cd7802e59bb57b954be80d9b7ea897c849be66919fa96ed"
)
EXPECTED_RAW_SHA256 = (
    "3634f021c056b90ab1eb042bf6bccfa1413d31af96993120e77bdcf843152518"
)
EXPECTED_ENTRY_COUNT = 110_000

# --- Deterministic-selection knobs (also documented in docs/DICTIONARY-E3.md). --------
# The seed is fixed for all time; a change is a written decision, not a silent bump.
TYPO_SEED = 20260727
# Prefix length in Unicode code points. Chosen to equal the engine's
# MIN_FUZZY_PREFIX_CODE_POINTS (TdictPrefixIndex): three code points is the shortest
# prefix at which the fuzzy pass fires, so it is the conservative worst case and matches
# the class #1 offline reference "p95 3 variants, максимум 5".
PREFIX_CODE_POINTS = 3

# 64-bit FNV-1a and SplitMix64 constants. The selection is a pure function of (seed,
# word) via these two portable primitives so the JVM test reproduces every choice
# bit-for-bit -- see E3aRecoveryCalibrationTest.kt.
_MASK64 = (1 << 64) - 1
_FNV_OFFSET_BASIS = 0xCBF29CE484222325
_FNV_PRIME = 0x100000001B3
_SPLITMIX_GAMMA = 0x9E3779B97F4A7C15
_SPLITMIX_MIX1 = 0xBF58476D1CE4E5B9
_SPLITMIX_MIX2 = 0x94D049BB133111EB

# --- tdict schema 2/version 1 layout (see docs/SIZE-SCHEMA2.md). -----------------------
_TDICT_MAGIC = b"TATDICT\x00"
_TDICT_HEADER_SIZE = 72
_TDICT_SCHEMA_ID = 2
_TDICT_FORMAT_VERSION = 1
_TDICT_CHECKSUM_ALGORITHM_SHA256 = 1
_TDICT_BLOCK_SIZE = 8

# The Tatar layout row files carry every letter key and its long-press partners.
_TATAR_ROWKEY_FILES = (
    "rowkeys_tatar1.xml",
    "rowkeys_tatar2.xml",
    "rowkeys_tatar3.xml",
    "rowkeys_tatar_extra.xml",
)
_ANDROID_RES_AUTO = "http://schemas.android.com/apk/res-auto"


class TypoPackError(ValueError):
    """A fail-closed generator error (exit 2)."""


class TypoGuardrailError(TypoPackError):
    """A guardrail breach (exit 4)."""


# --------------------------------------------------------------------------------------
# Portable deterministic primitives.
# --------------------------------------------------------------------------------------
def fnv1a64(data: bytes) -> int:
    """64-bit FNV-1a hash of ``data`` (matches the Kotlin implementation)."""
    hash_value = _FNV_OFFSET_BASIS
    for byte in data:
        hash_value ^= byte
        hash_value = (hash_value * _FNV_PRIME) & _MASK64
    return hash_value


def splitmix64(state: int) -> int:
    """One SplitMix64 output for ``state`` (matches the Kotlin implementation)."""
    state = (state + _SPLITMIX_GAMMA) & _MASK64
    z = state
    z = ((z ^ (z >> 30)) * _SPLITMIX_MIX1) & _MASK64
    z = ((z ^ (z >> 27)) * _SPLITMIX_MIX2) & _MASK64
    return (z ^ (z >> 31)) & _MASK64


def selection_index(word: str, choices: int, *, seed: int = TYPO_SEED) -> int:
    """Deterministic index in ``[0, choices)`` for ``word`` under ``seed``."""
    if choices <= 0:
        raise TypoPackError("selection over an empty choice set")
    mixed = splitmix64(seed ^ fnv1a64(word.encode("utf-8")))
    return mixed % choices


# --------------------------------------------------------------------------------------
# Long-press adjacency, read from the layout resources (never hard-coded).
# --------------------------------------------------------------------------------------
def _normalize_letter(code_point: int) -> int | None:
    """Fold a raw key code to a single NFC lower-case letter, mirroring KeyNeighborTable.

    Returns None when the code point is not a Unicode scalar value, expands to more than
    one code point once lower-cased, or is not a letter.
    """
    if code_point < 0 or code_point > 0x10FFFF or 0xD800 <= code_point <= 0xDFFF:
        return None
    folded = unicodedata.normalize("NFC", chr(code_point)).lower()
    if len(folded) != 1:
        return None
    normalized = ord(folded)
    if not chr(normalized).isalpha():
        return None
    return normalized


def _read_directed_pairs(xml_path: Path) -> list[tuple[int, list[int]]]:
    """Read (base, [partner, ...]) pairs from one rowkeys XML by its ``latin:moreKeys``."""
    try:
        root = ElementTree.parse(xml_path).getroot()
    except (ElementTree.ParseError, OSError) as error:
        raise TypoPackError(f"cannot parse layout resource {xml_path}: {error}") from error
    key_spec_attr = f"{{{_ANDROID_RES_AUTO}}}keySpec"
    more_keys_attr = f"{{{_ANDROID_RES_AUTO}}}moreKeys"
    pairs: list[tuple[int, list[int]]] = []
    for key in root.iter("Key"):
        more_keys = key.get(more_keys_attr)
        if more_keys is None:
            continue
        key_spec = key.get(key_spec_attr)
        if key_spec is None or len(key_spec) != 1:
            raise TypoPackError(
                f"{xml_path.name}: a key with moreKeys has no single-character keySpec"
            )
        base = ord(key_spec)
        partners: list[int] = []
        for token in more_keys.split(","):
            token = token.strip()
            if len(token) != 1:
                # A moreKeys entry that is not a single character (e.g. a "!text/..."
                # reference) is not a letter partner; ignore it as KeyNeighborTable would.
                continue
            partners.append(ord(token))
        if partners:
            pairs.append((base, partners))
    return pairs


def build_neighbor_map(directed_pairs: Iterable[tuple[int, Sequence[int]]]) -> dict[int, tuple[int, ...]]:
    """Symmetrize and de-duplicate directed long-press pairs, as KeyNeighborTable does.

    The layout stores base -> partner only; the reverse edge is added explicitly, both
    ends are folded to NFC lower-case letters, self-pairs are dropped, and each node's
    partners are returned sorted ascending by code point.
    """
    adjacency: dict[int, set[int]] = {}
    for base_raw, partners_raw in directed_pairs:
        base = _normalize_letter(base_raw)
        if base is None:
            continue
        for partner_raw in partners_raw:
            partner = _normalize_letter(partner_raw)
            if partner is None or partner == base:
                continue
            adjacency.setdefault(base, set()).add(partner)
            adjacency.setdefault(partner, set()).add(base)
    return {node: tuple(sorted(partners)) for node, partners in adjacency.items()}


def read_layout_neighbor_map(layout_dir: Path) -> dict[int, tuple[int, ...]]:
    directed: list[tuple[int, list[int]]] = []
    for name in _TATAR_ROWKEY_FILES:
        path = layout_dir / name
        if not path.is_file():
            raise TypoPackError(f"layout resource is missing: {path}")
        directed.extend(_read_directed_pairs(path))
    neighbor_map = build_neighbor_map(directed)
    if not neighbor_map:
        raise TypoPackError("layout resources yielded no long-press pair")
    return neighbor_map


# --------------------------------------------------------------------------------------
# Geometric adjacency (edit class #2), reconstructed from the layout geometry (never hard-coded).
# --------------------------------------------------------------------------------------
# The row structure and key widths come from res/xml/rows_tatar.xml; the per-row key order comes
# from the included rowkeys_tatar*.xml. Nothing is hard-coded: the widths, the key order and the
# gap/padding fractions are all read from the committed resources.
#
# TT-TYPO-NEXT Phase B (docs/TT-TYPO-NEXT.md) — the model is now DEVICE-TRUE. The pre-Phase-B
# model packed keys edge-to-edge on a percent grid, which made same-row keys "touch"
# (right == left) and produced 33 same-row pairs the device never has: on a real build
# KeyboardRow subtracts the horizontal gap from every key's width and advances the next key by
# the full PADDED width (keyWidth + horizontalGap), so on device right < left for every same-row
# pair and the "touch" relation never fires. The model below reproduces the device formula
# (KeyboardBuilder/KeyboardRow/Key) on a fixed integer grid — a 100 000-px reference width, one
# grid unit = 0.001 %p — with one rounding per key edge, exactly as Key does (Math.round):
#
#   gap, leftPad, rightPad = fractions of the screen width, from res/values/config.xml;
#   baseWidth = width - leftPad - rightPad + gap
#   x_0 = leftPad;  x_{k+1} = x_k + frac_k * baseWidth        (the gap cancels in the advance)
#   width_k = frac_k * baseWidth - gap, clamped so x_k + width_k <= width - rightPad
#   key.left = round(x_k);  key.right = round(x_k + width_k)
#
# The resulting pair set is identical for all six shipped config variants (values*/config.xml)
# and across a 320..4000 px width sweep; tests/typo_pack asserts that stability, so the reference
# width is a modelling device, not a calibration knob.
_ROWS_TATAR_FILE = "rows_tatar.xml"
_VALUES_CONFIG_FILE = "config.xml"
_GEOMETRY_REFERENCE_WIDTH = 100_000
_GEOMETRIC_OVERLAP_PERCENT = 35


@dataclass(frozen=True)
class _GeoKey:
    code_point: int
    row: int
    left: int
    right: int


def _parse_percent(value: str) -> float:
    """Parse a `NN.NNN%p` (or plain `NN.NNN%`) fraction into percent points (1.739 -> 1.739)."""
    stripped = value.replace("%p", "").replace("%", "").strip()
    return float(stripped)


def _device_round(value: float) -> int:
    """Round half up, matching java.lang.Math.round used by Key for its pixel edges."""
    import math

    return math.floor(value + 0.5)


def _read_row_key_specs(xml_path: Path) -> list[str]:
    """Return the single-character keySpecs of one rowkeys XML, in document order."""
    try:
        root = ElementTree.parse(xml_path).getroot()
    except (ElementTree.ParseError, OSError) as error:
        raise TypoPackError(f"cannot parse layout resource {xml_path}: {error}") from error
    key_spec_attr = f"{{{_ANDROID_RES_AUTO}}}keySpec"
    specs: list[str] = []
    for key in root.iter("Key"):
        spec = key.get(key_spec_attr)
        if spec is None or len(spec) != 1:
            raise TypoPackError(f"{xml_path.name}: a row key has no single-character keySpec")
        specs.append(spec)
    return specs


def read_keyboard_gaps(config_path: Path) -> tuple[float, float, float]:
    """Read (horizontalGap, leftPadding, rightPadding) in percent points from a values config."""
    if not config_path.is_file():
        raise TypoPackError(f"values config is missing: {config_path}")
    try:
        root = ElementTree.parse(config_path).getroot()
    except (ElementTree.ParseError, OSError) as error:
        raise TypoPackError(f"cannot parse values config {config_path}: {error}") from error
    wanted = {
        "config_key_horizontal_gap": None,
        "config_keyboard_left_padding": None,
        "config_keyboard_right_padding": None,
    }
    for fraction in root.iter("fraction"):
        name = fraction.get("name")
        if name in wanted and fraction.text:
            wanted[name] = _parse_percent(fraction.text)
    missing = [name for name, value in wanted.items() if value is None]
    if missing:
        raise TypoPackError(f"{config_path.name}: missing fractions {missing}")
    return (
        wanted["config_key_horizontal_gap"],
        wanted["config_keyboard_left_padding"],
        wanted["config_keyboard_right_padding"],
    )


def _row_width_specs(layout_dir: Path) -> list[list[tuple[str | None, "float | str | None"]]]:
    """Parse rows_tatar.xml into rows of (single-char keySpec or None, width percent or fill).

    A None width is the row's default keyWidth; the string "fill" is fillRight (the trailing
    delete key). Non-letter keys (shift, delete) keep their width entry — they move the x
    advance exactly like on device — but carry no keySpec and produce no geometry record.
    """
    rows_path = layout_dir / _ROWS_TATAR_FILE
    if not rows_path.is_file():
        raise TypoPackError(f"layout resource is missing: {rows_path}")
    try:
        root = ElementTree.parse(rows_path).getroot()
    except (ElementTree.ParseError, OSError) as error:
        raise TypoPackError(f"cannot parse layout resource {rows_path}: {error}") from error
    key_spec_attr = f"{{{_ANDROID_RES_AUTO}}}keySpec"
    key_width_attr = f"{{{_ANDROID_RES_AUTO}}}keyWidth"
    layout_attr = f"{{{_ANDROID_RES_AUTO}}}keyboardLayout"
    rows: list[list[tuple[str | None, float | str | None]]] = []
    for row in root.findall("Row"):
        row_width_attr = row.get(key_width_attr)
        row_width = _parse_percent(row_width_attr) if row_width_attr else 0.0
        entries: list[tuple[str | None, float | str | None]] = []
        for child in row:
            if child.tag == "Key":
                width_attr = child.get(key_width_attr)
                width: float | str | None
                if width_attr == "fillRight":
                    width = "fill"
                elif width_attr is not None:
                    width = _parse_percent(width_attr)
                else:
                    width = None  # the row default applies
                spec = child.get(key_spec_attr)
                entries.append((spec if spec is not None and len(spec) == 1 else None, width))
            elif child.tag == "include":
                include_layout = child.get(layout_attr)
                if include_layout is None:
                    continue
                name = include_layout.split("/")[-1] + ".xml"
                for spec in _read_row_key_specs(layout_dir / name):
                    entries.append((spec, None))
        rows.append(entries)
    return rows


def build_device_geometry(
    rows: Sequence[Sequence[tuple[str | None, "float | str"]]],
    *,
    gap_percent: float,
    left_padding_percent: float,
    right_padding_percent: float,
    width_px: int = _GEOMETRY_REFERENCE_WIDTH,
) -> list[_GeoKey]:
    """Reproduce the device key rectangles (KeyboardBuilder/KeyboardRow/Key) on an integer grid.

    The input rows are (single-char keySpec or None, width in percent points or "fill") with the
    row default already resolved by the caller. Edges are rounded once per key with the same
    half-up rule Key applies (Math.round), so the relation derived from the result is the one a
    device build computes on real pixels.
    """
    gap = gap_percent / 100.0 * width_px
    left_pad = left_padding_percent / 100.0 * width_px
    right_pad = right_padding_percent / 100.0 * width_px
    base_width = width_px - left_pad - right_pad + gap
    right_edge = width_px - right_pad
    geo_keys: list[_GeoKey] = []
    for row_index, entries in enumerate(rows):
        x = left_pad
        for spec, width in entries:
            if width == "fill":
                # fillRight takes the rest of the row; it is the trailing delete key, never a
                # letter, and nothing follows it — so it only bounds the previous key's clamp.
                break
            assert width is not None  # resolved by the caller
            key_width = width / 100.0 * base_width - gap
            if x + key_width > right_edge:
                key_width = right_edge - x
            if spec is not None:
                normalized = _normalize_letter(ord(spec))
                if normalized is not None:
                    geo_keys.append(
                        _GeoKey(normalized, row_index, _device_round(x), _device_round(x + key_width))
                    )
            x += key_width + gap
    if not geo_keys:
        raise TypoPackError("layout resources yielded no letter geometry")
    return geo_keys


def read_layout_geometry(
    layout_dir: Path,
    *,
    gap_percent: float | None = None,
    left_padding_percent: float | None = None,
    right_padding_percent: float | None = None,
    width_px: int = _GEOMETRY_REFERENCE_WIDTH,
) -> list[_GeoKey]:
    """Device-true integer geometry of every letter key of the Tatar alphabet layout.

    The gap/padding fractions default to the base phone config (res/values/config.xml, resolved
    relative to ``layout_dir``); tests pass explicit values to prove the pair set is identical
    under every shipped config variant and screen width.
    """
    raw_rows = _row_width_specs(layout_dir)
    row_widths: list[float] = []
    rows_path = layout_dir / _ROWS_TATAR_FILE
    try:
        root = ElementTree.parse(rows_path).getroot()
    except (ElementTree.ParseError, OSError) as error:
        raise TypoPackError(f"cannot parse layout resource {rows_path}: {error}") from error
    key_width_attr = f"{{{_ANDROID_RES_AUTO}}}keyWidth"
    for row in root.findall("Row"):
        row_width_attr = row.get(key_width_attr)
        row_widths.append(_parse_percent(row_width_attr) if row_width_attr else 0.0)
    if len(row_widths) != len(raw_rows):
        raise TypoPackError("row count mismatch while resolving row default widths")
    resolved: list[list[tuple[str | None, float | str]]] = []
    for entries, row_width in zip(raw_rows, row_widths):
        resolved.append(
            [(spec, row_width if width is None else width) for spec, width in entries]
        )
    if gap_percent is None or left_padding_percent is None or right_padding_percent is None:
        config_path = layout_dir.parent / "values" / _VALUES_CONFIG_FILE
        gap, left_pad, right_pad = read_keyboard_gaps(config_path)
        gap_percent = gap if gap_percent is None else gap_percent
        left_padding_percent = left_pad if left_padding_percent is None else left_padding_percent
        right_padding_percent = (
            right_pad if right_padding_percent is None else right_padding_percent
        )
    return build_device_geometry(
        resolved,
        gap_percent=gap_percent,
        left_padding_percent=left_padding_percent,
        right_padding_percent=right_padding_percent,
        width_px=width_px,
    )


def build_geometric_map(geo_keys: Sequence[_GeoKey]) -> dict[int, tuple[int, ...]]:
    """Edit class #2 relation, verbatim from the contract and identical to KeyNeighborTable.

    Keys of the same row (same top rank) that touch horizontally (shared vertical edge), plus keys
    of an adjacent row (top rank differing by one) whose horizontal overlap is MORE than 35% of the
    width of the narrower of the two. The 35% comparison is exact integer arithmetic.
    """
    distinct_tops = sorted({key.row for key in geo_keys})
    rank = {top: index for index, top in enumerate(distinct_tops)}
    adjacency: dict[int, set[int]] = {}
    count = len(geo_keys)
    for i in range(count):
        first = geo_keys[i]
        for j in range(i + 1, count):
            second = geo_keys[j]
            if first.code_point == second.code_point:
                continue
            rank_first = rank[first.row]
            rank_second = rank[second.row]
            connected = False
            if rank_first == rank_second:
                connected = first.right == second.left or second.right == first.left
            elif abs(rank_first - rank_second) == 1:
                overlap = min(first.right, second.right) - max(first.left, second.left)
                if overlap > 0:
                    min_width = min(first.right - first.left, second.right - second.left)
                    connected = 100 * overlap > _GEOMETRIC_OVERLAP_PERCENT * min_width
            if connected:
                adjacency.setdefault(first.code_point, set()).add(second.code_point)
                adjacency.setdefault(second.code_point, set()).add(first.code_point)
    return {node: tuple(sorted(partners)) for node, partners in adjacency.items()}


def read_layout_geometric_map(layout_dir: Path) -> dict[int, tuple[int, ...]]:
    geometric_map = build_geometric_map(read_layout_geometry(layout_dir))
    if not geometric_map:
        raise TypoPackError("layout geometry yielded no geometric neighbour")
    return geometric_map


# --------------------------------------------------------------------------------------
# The typeable alphabet (edit class #4, TT-TYPO-NEXT Phase C) — read from the layout, never
# hard-coded. Exactly the KeyNeighborTable.nodes set: every letter key of the rowkeys_tatar*.xml
# files plus every letter appearing in their latin:moreKeys (ё and ъ are more-key-only letters).
# --------------------------------------------------------------------------------------
def read_layout_alphabet(layout_dir: Path) -> tuple[int, ...]:
    """The layout's typeable letters as a sorted tuple of code points (== KeyNeighborTable.nodes)."""
    letters: set[int] = set()
    for name in _TATAR_ROWKEY_FILES:
        path = layout_dir / name
        if not path.is_file():
            raise TypoPackError(f"layout resource is missing: {path}")
        try:
            root = ElementTree.parse(path).getroot()
        except (ElementTree.ParseError, OSError) as error:
            raise TypoPackError(f"cannot parse layout resource {path}: {error}") from error
        key_spec_attr = f"{{{_ANDROID_RES_AUTO}}}keySpec"
        more_keys_attr = f"{{{_ANDROID_RES_AUTO}}}moreKeys"
        for key in root.iter("Key"):
            spec = key.get(key_spec_attr)
            if spec is not None and len(spec) == 1:
                normalized = _normalize_letter(ord(spec))
                if normalized is not None:
                    letters.add(normalized)
            more_keys = key.get(more_keys_attr)
            if more_keys:
                for token in more_keys.split(","):
                    token = token.strip()
                    if len(token) != 1:
                        continue
                    normalized = _normalize_letter(ord(token))
                    if normalized is not None:
                        letters.add(normalized)
    if not letters:
        raise TypoPackError("layout resources yielded no alphabet letter")
    return tuple(sorted(letters))


# --------------------------------------------------------------------------------------
# Dictionary vocabulary, inflated and parsed from the committed asset.
# --------------------------------------------------------------------------------------
def read_dictionary_words(
    asset_path: Path,
    *,
    expected_asset_sha256: str = EXPECTED_ASSET_SHA256,
    expected_raw_sha256: str = EXPECTED_RAW_SHA256,
    expected_entry_count: int = EXPECTED_ENTRY_COUNT,
) -> list[str]:
    """Inflate the pinned dictionary asset and return its words in stored order."""
    compressed = asset_path.read_bytes()
    actual_asset_sha = hashlib.sha256(compressed).hexdigest()
    if actual_asset_sha != expected_asset_sha256:
        raise TypoPackError(
            f"asset SHA-256 {actual_asset_sha} does not match pinned {expected_asset_sha256}"
        )
    try:
        raw = zlib.decompress(compressed)
    except zlib.error as error:
        raise TypoPackError(f"asset is not a valid zlib stream: {error}") from error
    actual_raw_sha = hashlib.sha256(raw).hexdigest()
    if actual_raw_sha != expected_raw_sha256:
        raise TypoPackError(
            f"inflated raw SHA-256 {actual_raw_sha} does not match pinned {expected_raw_sha256}"
        )
    return _parse_tdict_words(raw, expected_entry_count=expected_entry_count)


def _parse_tdict_words(raw: bytes, *, expected_entry_count: int) -> list[str]:
    if len(raw) < _TDICT_HEADER_SIZE:
        raise TypoPackError("raw dictionary is shorter than its header")
    if raw[:8] != _TDICT_MAGIC:
        raise TypoPackError("wrong dictionary magic")
    (
        schema_id,
        format_version,
        header_size,
        checksum_algorithm,
    ) = struct.unpack_from("<HHHH", raw, 8)
    (
        entry_count,
        block_count,
        block_index_offset,
        blocks_offset,
        blocks_size,
        file_size,
    ) = struct.unpack_from("<IIIIII", raw, 16)
    if schema_id != _TDICT_SCHEMA_ID or format_version != _TDICT_FORMAT_VERSION:
        raise TypoPackError("unsupported dictionary schema/version")
    if header_size != _TDICT_HEADER_SIZE:
        raise TypoPackError("unexpected header size")
    if checksum_algorithm != _TDICT_CHECKSUM_ALGORITHM_SHA256:
        raise TypoPackError("unsupported checksum algorithm")
    if entry_count != expected_entry_count:
        raise TypoPackError(
            f"entry count {entry_count} does not match pinned {expected_entry_count}"
        )
    expected_block_count = (entry_count + _TDICT_BLOCK_SIZE - 1) // _TDICT_BLOCK_SIZE
    expected_blocks_offset = _TDICT_HEADER_SIZE + 4 * block_count
    if (
        block_count != expected_block_count
        or block_index_offset != _TDICT_HEADER_SIZE
        or blocks_offset != expected_blocks_offset
        or file_size != blocks_offset + blocks_size
        or file_size != len(raw)
    ):
        raise TypoPackError("noncanonical section layout")

    block_offsets = struct.unpack_from(f"<{block_count}I", raw, block_index_offset)
    words: list[str] = []
    for block in range(block_count):
        cursor = block_offsets[block]
        block_end = (
            block_offsets[block + 1] if block + 1 < block_count else file_size
        )
        in_block = min(_TDICT_BLOCK_SIZE, entry_count - block * _TDICT_BLOCK_SIZE)
        if cursor >= block_end:
            raise TypoPackError("truncated block")
        first_length = raw[cursor]
        cursor += 1
        if first_length == 0 or cursor + first_length > block_end:
            raise TypoPackError("invalid first word of a block")
        first = raw[cursor : cursor + first_length]
        cursor += first_length
        block_words = [first]
        for _ in range(in_block - 1):
            prefix_length = 0
            shift = 0
            while True:
                if cursor >= block_end:
                    raise TypoPackError("truncated varint")
                byte = raw[cursor]
                cursor += 1
                prefix_length |= (byte & 0x7F) << shift
                if not byte & 0x80:
                    break
                shift += 7
            if prefix_length > first_length or cursor >= block_end:
                raise TypoPackError("invalid block entry")
            suffix_length = raw[cursor]
            cursor += 1
            if suffix_length == 0 or cursor + suffix_length > block_end:
                raise TypoPackError("invalid block entry suffix")
            block_words.append(first[:prefix_length] + raw[cursor : cursor + suffix_length])
            cursor += suffix_length
        # Частотный хвост блока пропускается: набор опечаток — про слова.
        for _ in range(in_block):
            while True:
                if cursor >= block_end:
                    raise TypoPackError("truncated frequency varint")
                byte = raw[cursor]
                cursor += 1
                if not byte & 0x80:
                    break
        if cursor != block_end:
            raise TypoPackError("trailing bytes in a block")
        for index, chunk in enumerate(block_words):
            try:
                words.append(chunk.decode("utf-8"))
            except UnicodeDecodeError as error:
                raise TypoPackError(
                    f"word #{len(words) + index} is not valid UTF-8: {error}"
                ) from error
    if len(words) != entry_count:
        raise TypoPackError("decoded word count does not match the header")
    return words


# --------------------------------------------------------------------------------------
# Typo-set construction.
# --------------------------------------------------------------------------------------
@dataclass(frozen=True)
class TypoSet:
    rows: tuple[tuple[str, str], ...]
    text: str
    data: bytes
    eligible_count: int
    scanned_count: int
    variant_p50: int
    variant_p95: int
    variant_max: int

    @property
    def size(self) -> int:
        return len(self.rows)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()


def _percentile(sorted_values: Sequence[int], fraction: float) -> int:
    if not sorted_values:
        return 0
    import math

    rank = max(1, math.ceil(len(sorted_values) * fraction))
    return sorted_values[rank - 1]


def _variant_count(prefix_code_points: Sequence[int], neighbor_map: dict[int, tuple[int, ...]]) -> int:
    """Number of class #1 variants the engine emits for ``prefix_code_points``.

    Mirrors FuzzyPrefixVariants.generateLongPressVariants: one variant per partner of
    every position that has any.
    """
    total = 0
    for code_point in prefix_code_points:
        total += len(neighbor_map.get(code_point, ()))
    return total


def build_typo_set(
    words: Sequence[str],
    neighbor_map: dict[int, tuple[int, ...]],
    *,
    seed: int = TYPO_SEED,
    prefix_code_points: int = PREFIX_CODE_POINTS,
    max_rows: int | None = None,
) -> TypoSet:
    """Build the deterministic class #1 typo set from ``words`` and ``neighbor_map``.

    For every word of at least ``prefix_code_points`` code points whose prefix window
    holds at least one letter with a long-press partner, one (position, partner) choice
    is picked deterministically and applied inside the prefix. The output is one
    ``original<TAB>typo_prefix`` row per eligible word, in input (code-point-sorted) order.
    """
    if prefix_code_points <= 0:
        raise TypoPackError("prefix length must be positive")
    rows: list[tuple[str, str]] = []
    variant_counts: list[int] = []
    scanned = 0
    for word in words:
        code_points = [ord(character) for character in word]
        if len(code_points) < prefix_code_points:
            continue
        scanned += 1
        eligible: list[tuple[int, int]] = []
        for position in range(prefix_code_points):
            for partner in neighbor_map.get(code_points[position], ()):
                eligible.append((position, partner))
        if not eligible:
            continue
        position, partner = eligible[selection_index(word, len(eligible), seed=seed)]
        typo_code_points = code_points[:prefix_code_points]
        typo_code_points[position] = partner
        typo_prefix = "".join(chr(cp) for cp in typo_code_points)
        rows.append((word, typo_prefix))
        variant_counts.append(_variant_count(typo_code_points, neighbor_map))

    if not rows:
        raise TypoPackError("no dictionary word is eligible for a class #1 typo")
    limit = len(words) if max_rows is None else max_rows
    if len(rows) > limit:
        raise TypoGuardrailError(
            f"typo set has {len(rows)} rows; limit is {limit}"
        )

    text = "".join(f"{original}\t{typo}\n" for original, typo in rows)
    data = text.encode("utf-8")
    variant_counts.sort()
    return TypoSet(
        rows=tuple(rows),
        text=text,
        data=data,
        eligible_count=len(rows),
        scanned_count=scanned,
        variant_p50=_percentile(variant_counts, 0.50),
        variant_p95=_percentile(variant_counts, 0.95),
        variant_max=variant_counts[-1] if variant_counts else 0,
    )


def build_geometric_typo_set(
    words: Sequence[str],
    geometric_map: dict[int, tuple[int, ...]],
    *,
    seed: int = TYPO_SEED,
    prefix_code_points: int = PREFIX_CODE_POINTS,
    max_rows: int | None = None,
) -> TypoSet:
    """Edit class #2: replace one prefix letter with a geometric keyboard neighbour.

    For every word of at least ``prefix_code_points`` code points whose prefix window holds a letter
    with a geometric neighbour, one ``(position, neighbour)`` choice is picked deterministically —
    the same ``(seed, word)`` primitive as class #1 — and applied inside the prefix. Enumeration
    order is position ascending, then neighbour code point ascending, matching the JVM test.
    """
    if prefix_code_points <= 0:
        raise TypoPackError("prefix length must be positive")
    rows: list[tuple[str, str]] = []
    variant_counts: list[int] = []
    scanned = 0
    for word in words:
        code_points = [ord(character) for character in word]
        if len(code_points) < prefix_code_points:
            continue
        scanned += 1
        eligible: list[tuple[int, int]] = []
        for position in range(prefix_code_points):
            for neighbour in geometric_map.get(code_points[position], ()):
                eligible.append((position, neighbour))
        if not eligible:
            continue
        position, neighbour = eligible[selection_index(word, len(eligible), seed=seed)]
        typo_code_points = code_points[:prefix_code_points]
        typo_code_points[position] = neighbour
        typo_prefix = "".join(chr(cp) for cp in typo_code_points)
        rows.append((word, typo_prefix))
        variant_counts.append(_variant_count(typo_code_points, geometric_map))
    return _finish_typo_set(rows, variant_counts, scanned, words, max_rows, "class #2")


def build_two_substitution_typo_set(
    words: Sequence[str],
    alphabet: Sequence[int],
    *,
    seed: int = TYPO_SEED,
    prefix_code_points: int = PREFIX_CODE_POINTS,
    max_rows: int | None = None,
) -> TypoSet:
    """Edit class #5 (ROADMAP-P4 P6): two substitutions at two DISTINCT positions.

    For every word of at least ``prefix_code_points`` code points, exactly one ``(i, j, x, y)``
    choice is picked deterministically: the position pair is the ``selection_index``-th pair of
    the ``(i < j)`` pairs of the window in lexicographic order, and each replacement letter is the
    ``selection_index``-th letter of the alphabet minus the position's own letter. The three picks
    are chained off the one ``(seed, word)`` SplitMix64 stream so the JVM calibration test
    reproduces every choice bit-for-bit -- exactly the same primitive as the other classes, used
    three times rather than materializing the C(n,2) x 37^2 eligible space per word.
    """
    if prefix_code_points <= 0:
        raise TypoPackError("prefix length must be positive")
    rows: list[tuple[str, str]] = []
    variant_counts: list[int] = []
    scanned = 0
    for word in words:
        code_points = [ord(character) for character in word]
        if len(code_points) < prefix_code_points:
            continue
        scanned += 1
        pair_count = prefix_code_points * (prefix_code_points - 1) // 2
        if pair_count == 0:
            continue
        # The chained SplitMix64 stream of this word (JVM: the identical sequence).
        mixed = splitmix64(seed ^ fnv1a64(word.encode("utf-8")))
        pair_index = mixed % pair_count
        mixed = splitmix64(mixed)
        x_index = mixed % (len(alphabet) - 1)
        mixed = splitmix64(mixed)
        y_index = mixed % (len(alphabet) - 1)
        # The pair_index-th (i < j) pair in lexicographic order.
        i = 0
        k = pair_index
        while k >= prefix_code_points - 1 - i:
            k -= prefix_code_points - 1 - i
            i += 1
        j = i + 1 + k
        x_choices = [cp for cp in alphabet if cp != code_points[i]]
        y_choices = [cp for cp in alphabet if cp != code_points[j]]
        typo_code_points = code_points[:prefix_code_points]
        typo_code_points[i] = x_choices[x_index]
        typo_code_points[j] = y_choices[y_index]
        typo_prefix = "".join(chr(cp) for cp in typo_code_points)
        rows.append((word, typo_prefix))
        # The engine's enumeration cost driver: stage A probes one alphabet per position.
        variant_counts.append(prefix_code_points * (len(alphabet) - 1))

    return _finish_typo_set(rows, variant_counts, scanned, words, max_rows, "class #5")


def build_transposition_typo_set(
    words: Sequence[str],
    *,
    seed: int = TYPO_SEED,
    prefix_code_points: int = PREFIX_CODE_POINTS,
    max_rows: int | None = None,
) -> TypoSet:
    """Edit class #3: swap two adjacent prefix letters.

    For every word of at least ``prefix_code_points`` code points whose prefix window holds at least
    one distinct adjacent pair, one pair ``(i, i+1)`` is picked deterministically and swapped. Swaps
    of two identical code points are ineligible (they reproduce the prefix). Enumeration order is the
    left index ascending, matching the JVM test.
    """
    if prefix_code_points <= 0:
        raise TypoPackError("prefix length must be positive")
    rows: list[tuple[str, str]] = []
    variant_counts: list[int] = []
    scanned = 0
    for word in words:
        code_points = [ord(character) for character in word]
        if len(code_points) < prefix_code_points:
            continue
        scanned += 1
        eligible = [
            i
            for i in range(prefix_code_points - 1)
            if code_points[i] != code_points[i + 1]
        ]
        if not eligible:
            continue
        pivot = eligible[selection_index(word, len(eligible), seed=seed)]
        typo_code_points = code_points[:prefix_code_points]
        typo_code_points[pivot], typo_code_points[pivot + 1] = (
            typo_code_points[pivot + 1],
            typo_code_points[pivot],
        )
        typo_prefix = "".join(chr(cp) for cp in typo_code_points)
        rows.append((word, typo_prefix))
        # A transposition prefix emits one distinct variant per distinct adjacent pair.
        variant_counts.append(
            sum(
                1
                for i in range(len(typo_code_points) - 1)
                if typo_code_points[i] != typo_code_points[i + 1]
            )
        )
    return _finish_typo_set(rows, variant_counts, scanned, words, max_rows, "class #3")


def build_substitution_typo_set(
    words: Sequence[str],
    alphabet: Sequence[int],
    *,
    seed: int = TYPO_SEED,
    prefix_code_points: int = PREFIX_CODE_POINTS,
    max_rows: int | None = None,
) -> TypoSet:
    """Edit class #4 (Phase C): replace one prefix letter with an arbitrary alphabet letter.

    For every word of at least ``prefix_code_points`` code points, the eligible choices are
    ``(position, letter)`` for every position in the window and every alphabet letter other than
    the position's own — the full single-substitution class the engine generates probe-first.
    One choice is picked deterministically (the same ``(seed, word)`` primitive as the other
    classes). Enumeration order is position ascending, then letter code point ascending, matching
    the JVM test.
    """
    if prefix_code_points <= 0:
        raise TypoPackError("prefix length must be positive")
    rows: list[tuple[str, str]] = []
    variant_counts: list[int] = []
    scanned = 0
    for word in words:
        code_points = [ord(character) for character in word]
        if len(code_points) < prefix_code_points:
            continue
        scanned += 1
        eligible: list[tuple[int, int]] = []
        for position in range(prefix_code_points):
            for letter in alphabet:
                if letter != code_points[position]:
                    eligible.append((position, letter))
        if not eligible:
            continue
        position, letter = eligible[selection_index(word, len(eligible), seed=seed)]
        typo_code_points = code_points[:prefix_code_points]
        typo_code_points[position] = letter
        typo_prefix = "".join(chr(cp) for cp in typo_code_points)
        rows.append((word, typo_prefix))
        # The engine emits one probe per (position, other letter).
        variant_counts.append(prefix_code_points * (len(alphabet) - 1))
    return _finish_typo_set(rows, variant_counts, scanned, words, max_rows, "class #4")


def _finish_typo_set(
    rows: list[tuple[str, str]],
    variant_counts: list[int],
    scanned: int,
    words: Sequence[str],
    max_rows: int | None,
    label: str,
) -> TypoSet:
    if not rows:
        raise TypoPackError(f"no dictionary word is eligible for a {label} typo")
    limit = len(words) if max_rows is None else max_rows
    if len(rows) > limit:
        raise TypoGuardrailError(f"typo set has {len(rows)} rows; limit is {limit}")
    text = "".join(f"{original}\t{typo}\n" for original, typo in rows)
    data = text.encode("utf-8")
    sorted_counts = sorted(variant_counts)
    return TypoSet(
        rows=tuple(rows),
        text=text,
        data=data,
        eligible_count=len(rows),
        scanned_count=scanned,
        variant_p50=_percentile(sorted_counts, 0.50),
        variant_p95=_percentile(sorted_counts, 0.95),
        variant_max=sorted_counts[-1] if sorted_counts else 0,
    )


def generate(
    dictionary_path: Path,
    layout_dir: Path,
    *,
    expected_asset_sha256: str = EXPECTED_ASSET_SHA256,
    expected_raw_sha256: str = EXPECTED_RAW_SHA256,
    expected_entry_count: int = EXPECTED_ENTRY_COUNT,
    seed: int = TYPO_SEED,
    prefix_code_points: int = PREFIX_CODE_POINTS,
    edit_class: int = 1,
) -> tuple[TypoSet, dict[int, tuple[int, ...]]]:
    words = read_dictionary_words(
        dictionary_path,
        expected_asset_sha256=expected_asset_sha256,
        expected_raw_sha256=expected_raw_sha256,
        expected_entry_count=expected_entry_count,
    )
    if edit_class == 1:
        neighbor_map = read_layout_neighbor_map(layout_dir)
        typo_set = build_typo_set(
            words, neighbor_map, seed=seed, prefix_code_points=prefix_code_points
        )
        return typo_set, neighbor_map
    if edit_class == 2:
        geometric_map = read_layout_geometric_map(layout_dir)
        typo_set = build_geometric_typo_set(
            words, geometric_map, seed=seed, prefix_code_points=prefix_code_points
        )
        return typo_set, geometric_map
    if edit_class == 3:
        typo_set = build_transposition_typo_set(
            words, seed=seed, prefix_code_points=prefix_code_points
        )
        return typo_set, {}
    if edit_class == 4:
        alphabet = read_layout_alphabet(layout_dir)
        typo_set = build_substitution_typo_set(
            words, alphabet, seed=seed, prefix_code_points=prefix_code_points
        )
        return typo_set, {cp: () for cp in alphabet}
    if edit_class == 5:
        alphabet = read_layout_alphabet(layout_dir)
        typo_set = build_two_substitution_typo_set(
            words, alphabet, seed=seed, prefix_code_points=prefix_code_points
        )
        return typo_set, {cp: () for cp in alphabet}
    raise TypoPackError(f"unknown edit class {edit_class}")


# --------------------------------------------------------------------------------------
# Atomic write and CLI, patterned on scripts/emoji_pack.py.
# --------------------------------------------------------------------------------------
def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=str(path.parent), prefix=f".{path.name}.")
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def _pairs_json(neighbor_map: dict[int, tuple[int, ...]]) -> list[dict[str, object]]:
    pairs: list[dict[str, str]] = []
    seen: set[tuple[int, int]] = set()
    for node in sorted(neighbor_map):
        for partner in neighbor_map[node]:
            edge = (min(node, partner), max(node, partner))
            if edge in seen:
                continue
            seen.add(edge)
            pairs.append({"a": chr(edge[0]), "b": chr(edge[1])})
    return pairs


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build", help="generate the class #1/#2/#3/#4/#5 typo set")
    build.add_argument("--dictionary", type=Path, required=True)
    build.add_argument("--layout-dir", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    build.add_argument(
        "--edit-class",
        type=int,
        choices=(1, 2, 3, 4, 5),
        default=1,
        help="1 = long-press partner (default), 2 = geometric neighbour, 3 = adjacent "
        "transposition, 4 = full single substitution over the layout alphabet (Phase C), "
        "5 = two substitutions at distinct positions (ROADMAP-P4 P6)",
    )
    build.add_argument(
        "--prefix-code-points",
        type=int,
        default=PREFIX_CODE_POINTS,
        help="typo window in Unicode code points (default %(default)s = the engine's "
        "MIN_FUZZY_PREFIX_CODE_POINTS; TT-TYPO-NEXT Phase B also calibrates a 5-cp window)",
    )
    return parser


def _print_json(value: object, stream: TextIO | None = None) -> None:
    if stream is None:
        stream = sys.stdout
    json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
    stream.write("\n")


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        if args.command == "build":
            typo_set, neighbor_map = generate(
                args.dictionary,
                args.layout_dir,
                expected_asset_sha256=EXPECTED_ASSET_SHA256,
                expected_raw_sha256=EXPECTED_RAW_SHA256,
                expected_entry_count=EXPECTED_ENTRY_COUNT,
                seed=TYPO_SEED,
                prefix_code_points=args.prefix_code_points,
                edit_class=args.edit_class,
            )
            write_atomic(args.output, typo_set.data)
            _print_json(
                {
                    "edit_class": args.edit_class,
                    "eligible_count": typo_set.eligible_count,
                    # Class #4: the map carries one (empty) entry per alphabet letter.
                    "alphabet_size": len(neighbor_map) if args.edit_class == 4 else None,
                    "long_press_pairs": _pairs_json(neighbor_map) if args.edit_class != 4 else [],
                    "prefix_code_points": args.prefix_code_points,
                    "scanned_count": typo_set.scanned_count,
                    "seed": TYPO_SEED,
                    "set_bytes": len(typo_set.data),
                    "set_sha256": typo_set.sha256,
                    "set_size": typo_set.size,
                    "variant_max": typo_set.variant_max,
                    "variant_p50": typo_set.variant_p50,
                    "variant_p95": typo_set.variant_p95,
                }
            )
        else:  # pragma: no cover
            raise AssertionError(args.command)
    except TypoGuardrailError as error:
        print(f"error: {error}", file=sys.stderr)
        return 4
    except (TypoPackError, OSError, UnicodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
