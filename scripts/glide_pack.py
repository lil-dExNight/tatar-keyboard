#!/usr/bin/env python3
"""Generate the deterministic synthetic glide-gesture set that calibrates the glide decoder.

Input: the Tatar layout (``rows_tatar.xml``, ``values/config.xml``; x edges via ``typo_pack.py``),
the bundled Tatar dictionary (pins from ``typo_pack.py``) and ``tt_eval_sentences.txt``.
Output: one ``word<TAB>x0,y0,t0;x1,y1,t1;...`` UTF-8/LF row per selected word, byte-identical
for the same inputs. All arithmetic is integer-exact, so the JVM calibration test regenerates
the same bytes; the SHA-256 pins in ``tests/glide_pack/`` and that test are the contract.
Exits nonzero without writing output on a pin mismatch, missing inputs or an empty/oversized set.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import os
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence, TextIO


def _load_typo_pack():
    scripts_dir = Path(__file__).resolve().parent
    spec = importlib.util.spec_from_file_location("typo_pack", scripts_dir / "typo_pack.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["typo_pack"] = module
    spec.loader.exec_module(module)
    return module


typo_pack = _load_typo_pack()

# Dictionary pins are inherited from typo_pack (the bundled Tatar dictionary).
EXPECTED_ASSET_SHA256 = typo_pack.EXPECTED_ASSET_SHA256
EXPECTED_RAW_SHA256 = typo_pack.EXPECTED_RAW_SHA256
EXPECTED_ENTRY_COUNT = typo_pack.EXPECTED_ENTRY_COUNT

# --- Deterministic knobs. Changing any of them changes the set and its pins. -------------
GLIDE_SEED = 20260924
# Word selection (mirrored bit-for-bit by the JVM calibration test): every eval-file token and
# every dictionary word with ``splitmix64(GLIDE_SEED ^ fnv1a64(word)) % DICT_MODULUS == 0``
# (about 1/40), each of >= 5 code points, all letters on keys or aliases of a key, present in the
# dictionary; the union is sorted by code point, one gesture per word.
MIN_WORD_CODE_POINTS = 5
DICT_MODULUS = 40
# Vertical model (the x model is typo_pack's): the default 5-row Tatar keyboard (kbd_tatar.xml)
# at the default height on a 1080 px, 440 dpi reference screen. Heights are integer pixels with
# one half-up rounding per value, then scaled into the 100 000-unit grid by the same rule. This
# is a model, not a measurement.
_REFERENCE_SCREEN_WIDTH_PX = 1080
_REFERENCE_DENSITY_DPI = 440
_KEYBOARD_HEIGHT_DP_X10 = 2056  # config_default_keyboard_height = 205.6 dp
_ROW_HEIGHT_PERCENT = 20  # kbd_tatar.xml default: 5 rows x 20%p
_VERTICAL_GAP_PERCENT_X1000 = 2814  # config_key_vertical_gap_5row = 2.814%p
_TOP_PADDING_PERCENT_X1000 = 2335  # config_keyboard_top_padding = 2.335%p
_LETTER_ROWS = 4  # the extra Tatar row plus the three qwerty rows
_GRID_WIDTH = typo_pack._GEOMETRY_REFERENCE_WIDTH  # 100 000
# Noise model. Draws come in this order from the SplitMix64 stream seeded by
# ``splitmix64(GLIDE_SEED ^ fnv1a64(word.utf8))``:
#   1. sampling step: step_min + draw % step_min grid units, step_min = narrowest key width /
#      STEP_DIVISOR (sampling density varies per word, like a digitizer's rate with finger speed);
#   2. timestamp step: TSTEP_MIN + draw % TSTEP_VAR ms per sample (the persona scales the range;
#      the decoder's speed-adaptive channel reads only the gesture's total duration);
#   3. corner cutting: each interior non-loop vertex, with probability 1/CUT_MODULUS, moves by
#      (P + Q - 2V) / CUT_DIVISOR (floor division), decided on the original vertices;
#   4. endpoint offsets: the first and last vertex each move by a per-axis uniform draw in
#      +/-ENDPOINT_*_PERCENT % of the key radius, widened by ENDPOINT_WIDE_SCALE with probability
#      1/ENDPOINT_WIDE_MODULUS (real gestures touch down beside the first key's center and lift
#      off further from the last one's, with a heavy tail a single bounded draw cannot express);
#   5. gesture shift: the whole path moves by a constant per-axis draw in
#      +/-GESTURE_OFFSET_PERCENT % of the key radius (a real gesture follows its own shifted
#      route: the per-gesture mean offset from the ideal path has a spread no zero-mean wander
#      expresses);
#   6. the polyline is walked at the step with integer segment lengths round(sqrt(dx^2 + dy^2))
#      and floor-division interpolation;
#   7. smooth wander: the per-point offset is a random walk clamped to +/-jitter (two initial
#      draws, then an x and a y increment in +/-wander per point). Independent per-sample jitter
#      would inflate the path length beyond what the decoder's length channel accepts.
# A doubled letter is not a draw: the set carries both variants (see generate_set). STEP/JITTER
# are in grid units, TSTEP in ms. LOOP_MODULUS is unused here; kept for the golden vectors.
# The percentages and moduli are fitted to the FUTO swipe corpus (the measurements live in
# research/glide-typing.md, the measurement script in research/corpus/futo_glide_analysis.py).
LOOP_MODULUS = 8
CUT_MODULUS = 3  # measured: about a third of real interior vertices are materially cut
CUT_DIVISOR = 20  # cut moves V by (P+Q-2V)/20; measured depth p90 = 0.48..0.71 radii by angle
STEP_DIVISOR = 6  # step range = [narrowestKeyWidth/6, narrowestKeyWidth/3): ~12-25 device px
TSTEP_MIN = 8  # measured inter-point dt p10 = 8 ms
TSTEP_VAR = 11  # tstep in [8, 19) ms; measured dt p90 = 18 ms
JITTER_PERCENT = 22  # local wander; fitted to the measured segment-middle offset p50 = 0.16 radii
WANDER_DIVISOR = 6  # per-sample wander increment = envelope / 6
GESTURE_OFFSET_PERCENT = 30  # per-gesture route shift; measured per-gesture mean offset p50 = 0.20 radii
ENDPOINT_START_PERCENT = 18  # measured touch-down offset from the first key center: p50 = 0.28 radii
ENDPOINT_END_PERCENT = 40  # measured lift-off offset from the last key center: p50 = 0.40 radii
ENDPOINT_WIDE_MODULUS = 7  # measured endpoint offset p95/p50 ratio needs a heavy tail...
ENDPOINT_WIDE_SCALE = 3  # ...start p95 = 0.68, end p95 = 1.14 radii
# Personas (the corpus mixes fast recallers and slow tracers): every word belongs to exactly one
# persona, drawn from a stream INDEPENDENT of the gesture stream (a different seed, so no gesture
# draw moves). The persona scales the noise knobs and the timestamp step, never the draw count.
# Fast: offsets x PERSONA_FAST_NUM/DEN and a shorter tstep; slow: offsets x PERSONA_SLOW_NUM/DEN
# and a longer tstep; normal: the fitted knobs unchanged.
PERSONA_SEED = 0x50EF5A
PERSONA_MODULUS = 3
PERSONA_NORMAL = 0
PERSONA_FAST = 1
PERSONA_SLOW = 2
PERSONA_FAST_NUM = 3
PERSONA_FAST_DEN = 2
PERSONA_SLOW_NUM = 2
PERSONA_SLOW_DEN = 3
TSTEP_FAST_MIN = 5  # fast persona: dt in [5, 12) ms
TSTEP_FAST_VAR = 7
TSTEP_SLOW_MIN = 14  # slow persona: dt in [14, 27) ms
TSTEP_SLOW_VAR = 13
# Context rows (the bigram channel's calibration class): after the word rows, one row per
# selected (previous, word) pair of consecutive eval-sentence tokens. The gesture traces the
# word; the previous token is the committed context. The pair stream (seed below) drives both
# the thinning draw and the gesture's stream seed, so a context row's gesture differs from the
# word's own row even for the same word.
CONTEXT_SEED = 0xC047E5
CONTEXT_MODULUS = 4

_MASK64 = (1 << 64) - 1


class GlidePackError(ValueError):
    """A generator error; nothing is written (exit 2)."""


class GlideGuardrailError(GlidePackError):
    """A guardrail breach (exit 4)."""


def splitmix64(state: int) -> int:
    return typo_pack.splitmix64(state)


def fnv1a64(data: bytes) -> int:
    return typo_pack.fnv1a64(data)


# --------------------------------------------------------------------------------------
# Geometry: typo_pack's device x model plus the vertical model above.
# --------------------------------------------------------------------------------------
@dataclass(frozen=True)
class _Rect:
    code_point: int
    left: int
    top: int
    right: int
    bottom: int

    @property
    def center_x(self) -> int:
        return (self.left + self.right) // 2

    @property
    def center_y(self) -> int:
        return (self.top + self.bottom) // 2


def _round_half_up(value: float) -> int:
    return typo_pack._device_round(value)


def _to_grid(px: int) -> int:
    return _round_half_up(px * _GRID_WIDTH / _REFERENCE_SCREEN_WIDTH_PX)


def vertical_model() -> tuple[int, int, int]:
    """(row_pitch, key_height, top_padding) of the reference keyboard, in grid units."""
    keyboard_px = _round_half_up(_KEYBOARD_HEIGHT_DP_X10 / 10 * _REFERENCE_DENSITY_DPI / 160)
    pitch_px = keyboard_px * _ROW_HEIGHT_PERCENT // 100
    gap_px = _round_half_up(keyboard_px * _VERTICAL_GAP_PERCENT_X1000 / 100_000)
    top_px = _round_half_up(keyboard_px * _TOP_PADDING_PERCENT_X1000 / 100_000)
    return _to_grid(pitch_px), _to_grid(pitch_px - gap_px), _to_grid(top_px)


def read_glide_geometry(layout_dir: Path) -> list[_Rect]:
    """Letter-key rectangles of the Tatar layout, as the device computes them, in grid units."""
    pitch, key_height, top = vertical_model()
    rects: list[_Rect] = []
    for geo in typo_pack.read_layout_geometry(layout_dir):
        row_top = top + geo.row * pitch
        rects.append(
            _Rect(geo.code_point, geo.left, row_top, geo.right, row_top + key_height)
        )
    if not rects:
        raise GlidePackError("layout resources yielded no letter geometry")
    return rects


def alias_bases(
    directed_pairs: Sequence[tuple[int, Sequence[int]]], letters: frozenset[int]
) -> dict[int, int]:
    """Alias letter -> base letter: every long-press letter without a key of its own.

    Base and partners are folded through ``typo_pack._normalize_letter``, so the ``%`` marker of
    ``moreKeys="<letter>,%"`` and any non-letter drop out, as in ``build_neighbor_map``. A letter
    that is the long press of two keys raises, so a layout change cannot pick a base silently.
    """
    bases: dict[int, set[int]] = {}
    for base_raw, partners_raw in directed_pairs:
        base = typo_pack._normalize_letter(base_raw)
        if base is None or base not in letters:
            continue
        for partner_raw in partners_raw:
            partner = typo_pack._normalize_letter(partner_raw)
            if partner is None or partner in letters:
                continue
            bases.setdefault(partner, set()).add(base)
    aliases: dict[int, int] = {}
    for alias, candidates in sorted(bases.items()):
        if len(candidates) != 1:
            names = ", ".join(sorted(chr(base) for base in candidates))
            raise GlidePackError(f"alias {chr(alias)} is the long press of several keys: {names}")
        aliases[alias] = next(iter(candidates))
    return aliases


def read_layout_aliases(layout_dir: Path, rects: Sequence[_Rect]) -> dict[int, int]:
    """Alias letter -> base letter of the Tatar layout, read from its long-press pairs."""
    directed: list[tuple[int, list[int]]] = []
    for name in typo_pack._TATAR_ROWKEY_FILES:
        path = layout_dir / name
        if not path.is_file():
            raise GlidePackError(f"layout resource is missing: {path}")
        directed.extend(typo_pack._read_directed_pairs(path))
    return alias_bases(directed, frozenset(rect.code_point for rect in rects))


def letters_by_code_point(
    rects: Sequence[_Rect], aliases: dict[int, int] | None = None
) -> dict[int, _Rect]:
    """Letter -> key rectangle, with every alias letter on its base key's rectangle."""
    by_letter = {rect.code_point: rect for rect in rects}
    for alias, base in (aliases or {}).items():
        by_letter[alias] = by_letter[base]
    return by_letter


def _has_double_key(word: str, by_letter: dict[int, _Rect]) -> bool:
    """True when two adjacent letters sit on one key (a doubled letter, or a letter and its alias)."""
    keys = [by_letter[ord(char.lower())].code_point for char in word]
    return any(keys[i] == keys[i - 1] for i in range(1, len(keys)))


def key_radius(rects: Sequence[_Rect]) -> int:
    """min over keys of min(width, height) -- the location channel's scale reference."""
    return min(min(rect.right - rect.left, rect.bottom - rect.top) for rect in rects)


# --------------------------------------------------------------------------------------
# Word selection (mirrored bit-for-bit by the JVM calibration test).
# --------------------------------------------------------------------------------------
def _letters_mappable(word: str, letters: frozenset[int]) -> bool:
    for char in word:
        lowered = char.lower()
        if len(lowered) != 1 or ord(lowered) not in letters:
            return False
    return True


def select_words(
    words: Sequence[str],
    eval_path: Path,
    letters: frozenset[int],
    *,
    seed: int = GLIDE_SEED,
    min_code_points: int = MIN_WORD_CODE_POINTS,
    dict_modulus: int = DICT_MODULUS,
    max_rows: int | None = None,
) -> list[str]:
    """The union of the eval-derived and dictionary-thinned word sets, code-point sorted."""
    dictionary = set(words)
    selected: set[str] = set()
    for word in words:
        if len(word) < min_code_points or not _letters_mappable(word, letters):
            continue
        if splitmix64(seed ^ fnv1a64(word.encode("utf-8"))) % dict_modulus == 0:
            selected.add(word)
    if not eval_path.is_file():
        raise GlidePackError(f"eval word file is missing: {eval_path}")
    for line in eval_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        for token in line.split(" "):
            if (
                len(token) >= min_code_points
                and token in dictionary
                and _letters_mappable(token, letters)
            ):
                selected.add(token)
    result = sorted(selected)
    if not result:
        raise GlidePackError("no word is eligible for the synthetic gesture set")
    limit = len(words) if max_rows is None else max_rows
    if len(result) > limit:
        raise GlideGuardrailError(f"gesture set has {len(result)} rows; limit is {limit}")
    return result


# --------------------------------------------------------------------------------------
# Gesture generation (mirrored bit-for-bit by the JVM calibration test).
# --------------------------------------------------------------------------------------
def _ideal_vertices(
    word: str, by_letter: dict[int, _Rect], draw_loop: bool
) -> tuple[list[tuple[int, int]], list[bool]]:
    """Key centers in letter order; a doubled letter contributes the 4 loop corners (or the
    repeated center) exactly as the decoder's ideal-path writer produces them."""
    vertices: list[tuple[int, int]] = []
    loop_flags: list[bool] = []
    previous = -1
    for char in word:
        rect = by_letter[ord(char.lower())]
        cx, cy = rect.center_x, rect.center_y
        if draw_loop and rect.code_point == previous:
            dx = (rect.right - rect.left) // 4
            dy = (rect.bottom - rect.top) // 4
            vertices.extend(
                [(cx + dx, cy + dy), (cx + dx, cy - dy), (cx - dx, cy - dy), (cx - dx, cy + dy)]
            )
            loop_flags.extend([True, True, True, True])
        else:
            vertices.append((cx, cy))
            loop_flags.append(False)
        previous = rect.code_point
    return vertices, loop_flags


def generate_gesture(
    word: str,
    by_letter: dict[int, _Rect],
    radius: int,
    *,
    seed: int = GLIDE_SEED,
    draw_loop: bool = False,
    cut_modulus: int = CUT_MODULUS,
    cut_divisor: int = CUT_DIVISOR,
    jitter_percent: int = JITTER_PERCENT,
    gesture_offset_percent: int = GESTURE_OFFSET_PERCENT,
    endpoint_start_percent: int = ENDPOINT_START_PERCENT,
    endpoint_end_percent: int = ENDPOINT_END_PERCENT,
    tstep_min: int = TSTEP_MIN,
    tstep_var: int = TSTEP_VAR,
) -> list[tuple[int, int, int]]:
    """One synthetic gesture for ``word`` as (x, y, t) integer samples; see the noise model.

    ``draw_loop`` is the caller's choice, not a draw: the set carries both variants of a doubled
    word, and the stream feeds the step draw first.
    """
    has_double = _has_double_key(word, by_letter)
    stream = splitmix64(seed ^ fnv1a64(word.encode("utf-8")))
    draw_loop = draw_loop and has_double
    # The sampling step references the narrowest letter-key width (the bottom row's 8.711%p
    # keys set it on the Tatar layout).
    standard_width = min(rect.right - rect.left for rect in by_letter.values())
    step_min = standard_width // STEP_DIVISOR
    step = step_min + stream % step_min
    stream = splitmix64(stream)
    tstep = tstep_min + stream % tstep_var
    stream = splitmix64(stream)
    vertices, loop_flags = _ideal_vertices(word, by_letter, draw_loop)

    # Corner cutting, decided on the original vertices, applied in parallel.
    count = len(vertices)
    cut: list[bool] = [False] * count
    for v in range(1, count - 1):
        if loop_flags[v]:
            continue
        cut[v] = stream % cut_modulus == 0
        stream = splitmix64(stream)
    cut_vertices = list(vertices)
    for v in range(1, count - 1):
        if not cut[v]:
            continue
        px, py = vertices[v - 1]
        vx, vy = vertices[v]
        qx, qy = vertices[v + 1]
        cut_vertices[v] = (
            vx + (px + qx - 2 * vx) // cut_divisor,
            vy + (py + qy - 2 * vy) // cut_divisor,
        )
    vertices = cut_vertices

    # Endpoint offsets (draw group 4 of the noise model): a per-axis uniform draw in a half-range
    # of ENDPOINT_*_PERCENT % of the key radius, the half-range scaled by ENDPOINT_WIDE_SCALE when
    # the branch draw fires (one branch draw per endpoint, then the x and y draws).
    start_half = radius * endpoint_start_percent // 100
    if stream % ENDPOINT_WIDE_MODULUS == 0:
        start_half *= ENDPOINT_WIDE_SCALE
    stream = splitmix64(stream)
    start_dx = stream % (2 * start_half + 1) - start_half
    stream = splitmix64(stream)
    start_dy = stream % (2 * start_half + 1) - start_half
    stream = splitmix64(stream)
    end_half = radius * endpoint_end_percent // 100
    if stream % ENDPOINT_WIDE_MODULUS == 0:
        end_half *= ENDPOINT_WIDE_SCALE
    stream = splitmix64(stream)
    end_dx = stream % (2 * end_half + 1) - end_half
    stream = splitmix64(stream)
    end_dy = stream % (2 * end_half + 1) - end_half
    stream = splitmix64(stream)
    vertices[0] = (vertices[0][0] + start_dx, vertices[0][1] + start_dy)
    vertices[-1] = (vertices[-1][0] + end_dx, vertices[-1][1] + end_dy)

    # The integer segment walk at the word's sampling step.
    seg_lengths: list[int] = []
    total = 0
    for i in range(count - 1):
        dx = vertices[i + 1][0] - vertices[i][0]
        dy = vertices[i + 1][1] - vertices[i][1]
        length = _round_half_up(math.sqrt(dx * dx + dy * dy))
        seg_lengths.append(length)
        total += length
    points: list[tuple[int, int]] = [vertices[0]]
    if total > 0:
        i = 0
        base = 0
        target = step
        while target < total:
            while base + seg_lengths[i] < target:
                base += seg_lengths[i]
                i += 1
            seg = seg_lengths[i]
            num = target - base
            x1, y1 = vertices[i]
            dx = vertices[i + 1][0] - x1
            dy = vertices[i + 1][1] - y1
            points.append(((x1 * seg + dx * num) // seg, (y1 * seg + dy * num) // seg))
            target += step
        points.append(vertices[-1])

    # Smooth wander: a clamped random walk of the per-point offset (see the noise model).
    jitter = radius * jitter_percent // 100
    wander = jitter // WANDER_DIVISOR
    span = 2 * jitter + 1
    step_span = 2 * wander + 1
    # The gesture shift (draw group 5): one constant offset for the whole path.
    shift = radius * gesture_offset_percent // 100
    shift_span = 2 * shift + 1
    shift_x = stream % shift_span - shift
    stream = splitmix64(stream)
    shift_y = stream % shift_span - shift
    stream = splitmix64(stream)
    offset_x = stream % span - jitter
    stream = splitmix64(stream)
    offset_y = stream % span - jitter
    stream = splitmix64(stream)
    sampled: list[tuple[int, int, int]] = []
    for index, (x, y) in enumerate(points):
        if index > 0:
            offset_x = _clamp(offset_x + stream % step_span - wander, jitter)
            stream = splitmix64(stream)
            offset_y = _clamp(offset_y + stream % step_span - wander, jitter)
            stream = splitmix64(stream)
        sampled.append((x + shift_x + offset_x, y + shift_y + offset_y, index * tstep))
    return sampled


def _clamp(value: int, bound: int) -> int:
    return max(-bound, min(bound, value))


def persona_of(word: str, *, seed: int = PERSONA_SEED) -> int:
    """The word's persona: an integer in [0, PERSONA_MODULUS), from a stream independent of the
    gesture stream (PERSONA_SEED, not GLIDE_SEED), so no gesture draw moves."""
    return splitmix64(seed ^ fnv1a64(word.encode("utf-8"))) % PERSONA_MODULUS


def persona_knobs(persona: int) -> dict[str, int]:
    """The generate_gesture overrides of one persona (empty for the normal one)."""
    if persona == PERSONA_FAST:
        return {
            "jitter_percent": JITTER_PERCENT * PERSONA_FAST_NUM // PERSONA_FAST_DEN,
            "gesture_offset_percent": GESTURE_OFFSET_PERCENT * PERSONA_FAST_NUM // PERSONA_FAST_DEN,
            "endpoint_start_percent": ENDPOINT_START_PERCENT * PERSONA_FAST_NUM // PERSONA_FAST_DEN,
            "endpoint_end_percent": ENDPOINT_END_PERCENT * PERSONA_FAST_NUM // PERSONA_FAST_DEN,
            "tstep_min": TSTEP_FAST_MIN,
            "tstep_var": TSTEP_FAST_VAR,
        }
    if persona == PERSONA_SLOW:
        return {
            "jitter_percent": JITTER_PERCENT * PERSONA_SLOW_NUM // PERSONA_SLOW_DEN,
            "gesture_offset_percent": GESTURE_OFFSET_PERCENT * PERSONA_SLOW_NUM // PERSONA_SLOW_DEN,
            "endpoint_start_percent": ENDPOINT_START_PERCENT * PERSONA_SLOW_NUM // PERSONA_SLOW_DEN,
            "endpoint_end_percent": ENDPOINT_END_PERCENT * PERSONA_SLOW_NUM // PERSONA_SLOW_DEN,
            "tstep_min": TSTEP_SLOW_MIN,
            "tstep_var": TSTEP_SLOW_VAR,
        }
    return {}


def render_gesture(word: str, points: Sequence[tuple[int, int, int]]) -> str:
    coords = ";".join(f"{x},{y},{t}" for x, y, t in points)
    return f"{word}\t{coords}\n"


def render_context_row(previous: str, word: str, points: Sequence[tuple[int, int, int]]) -> str:
    coords = ";".join(f"{x},{y},{t}" for x, y, t in points)
    return f"{word}\t{previous}\t{coords}\n"


def context_pair_seed(previous: str, word: str) -> int:
    """The pair's stream value: the thinning draw and the gesture's stream seed."""
    return splitmix64(CONTEXT_SEED ^ fnv1a64(f"{previous} {word}".encode("utf-8")))


def select_context_pairs(
    words: Sequence[str], eval_path: Path | None, letters: frozenset[int]
) -> list[tuple[str, str]]:
    """(previous, word) pairs of consecutive eval tokens: both dictionary words, the word
    mappable and at least MIN_WORD_CODE_POINTS long, thinned by the pair draw."""
    if eval_path is None:
        return []
    dictionary = set(words)
    pairs: set[tuple[str, str]] = set()
    for line in eval_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        tokens = line.split(" ")
        for previous, word in zip(tokens, tokens[1:]):
            if (
                previous in dictionary
                and word in dictionary
                and len(word) >= MIN_WORD_CODE_POINTS
                and _letters_mappable(word, letters)
            ):
                pairs.add((previous, word))
    return [
        pair
        for pair in sorted(pairs)
        if context_pair_seed(pair[0], pair[1]) % CONTEXT_MODULUS == 0
    ]


def generate_set(
    words: Sequence[str],
    rects: Sequence[_Rect],
    *,
    seed: int = GLIDE_SEED,
    aliases: dict[int, int] | None = None,
    context_pairs: Sequence[tuple[str, str]] = (),
) -> tuple[str, bytes]:
    by_letter = letters_by_code_point(rects, aliases)
    radius = key_radius(rects)
    rows: list[str] = []
    for word in words:
        # A doubled word contributes both variants (the decoder scores it only against its
        # looped path): the no-jog row first, then the jog row, from the same word stream, so
        # the pair differs only in the detour. Both variants carry the word's persona.
        knobs = persona_knobs(persona_of(word))
        rows.append(
            render_gesture(word, generate_gesture(word, by_letter, radius, seed=seed, **knobs))
        )
        if _has_double_key(word, by_letter):
            rows.append(
                render_gesture(
                    word,
                    generate_gesture(word, by_letter, radius, seed=seed, draw_loop=True, **knobs),
                )
            )
    # Context rows: one no-jog row per selected pair, after the word rows.
    for previous, word in context_pairs:
        rows.append(
            render_context_row(
                previous,
                word,
                generate_gesture(
                    word,
                    by_letter,
                    radius,
                    seed=context_pair_seed(previous, word),
                    **persona_knobs(persona_of(word)),
                ),
            )
        )
    rendered = "".join(rows)
    return rendered, rendered.encode("utf-8")


# --------------------------------------------------------------------------------------
# Atomic write and CLI, patterned on scripts/typo_pack.py.
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


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build", help="generate the synthetic glide gesture set")
    build.add_argument("--dictionary", type=Path, required=True)
    build.add_argument("--layout-dir", type=Path, required=True)
    build.add_argument("--eval-words", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
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
            words = typo_pack.read_dictionary_words(
                args.dictionary,
                expected_asset_sha256=EXPECTED_ASSET_SHA256,
                expected_raw_sha256=EXPECTED_RAW_SHA256,
                expected_entry_count=EXPECTED_ENTRY_COUNT,
            )
            rects = read_glide_geometry(args.layout_dir)
            aliases = read_layout_aliases(args.layout_dir, rects)
            letters = frozenset(letters_by_code_point(rects, aliases))
            selected = select_words(words, args.eval_words, letters)
            context_pairs = select_context_pairs(words, args.eval_words, letters)
            _, data = generate_set(
                selected, rects, aliases=aliases, context_pairs=context_pairs
            )
            write_atomic(args.output, data)
            _print_json(
                {
                    "aliases": "".join(chr(alias) for alias in sorted(aliases)),
                    "context_rows": len(context_pairs),
                    "dict_modulus": DICT_MODULUS,
                    "key_radius": key_radius(rects),
                    "letter_keys": len(rects),
                    "min_word_code_points": MIN_WORD_CODE_POINTS,
                    "seed": GLIDE_SEED,
                    "set_bytes": len(data),
                    "set_sha256": hashlib.sha256(data).hexdigest(),
                    "set_size": len(selected),
                }
            )
        else:  # pragma: no cover
            raise AssertionError(args.command)
    except GlideGuardrailError as error:
        print(f"error: {error}", file=sys.stderr)
        return 4
    except (GlidePackError, typo_pack.TypoPackError, OSError, UnicodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
