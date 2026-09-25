#!/usr/bin/env python3
"""Build the deterministic synthetic glide-gesture set used to calibrate the glide decoder
(P7-1 of docs/GLIDE-PLAN.md; mission report docs/ROADMAP-P7.md).

The tool uses only the Python standard library, reuses ``scripts/typo_pack.py`` for the
device-true layout geometry (x edges) and the pinned dictionary reader, and emits a
reproducible gesture set as a UTF-8/LF text file: one ``word<TAB>x0,y0,t0;x1,y1,t1;...`` row
per selected word. Given identical inputs the output is byte-for-byte identical across runs
and hosts: there is no time, locale, path or RNG state in the output, and every random choice
is a pure function of a fixed seed and the word itself (the same FNV-1a + SplitMix64 primitive
as ``typo_pack.py``). All coordinate arithmetic is INTEGER-EXACT (the single square root of
the segment walk is an IEEE-correctly-rounded double on both sides), so the JVM calibration
test regenerates the identical bytes -- the set file is a build artifact, the SHA-256 pins in
``tests/glide_pack/`` and in the JVM calibration test are the contract.

Three committed inputs, no fourth:

* the keyboard layout (``res/xml/rows_tatar.xml`` + ``res/values/config.xml`` via typo_pack's
  device-true x model, plus the vertical model documented below);
* the committed Tatar dictionary asset (pins inherited from typo_pack);
* the eval words ``app/src/test/resources/tt_eval_sentences.txt``.

Word selection (mirrored bit-for-bit by the JVM calibration test):

* every eval-file token of >= 5 code points whose letters all have keys and which the
  dictionary carries;
* every dictionary word of >= 5 code points whose letters all have keys and whose
  ``splitmix64(GLIDE_SEED ^ fnv1a64(word)) % DICT_MODULUS == 0`` -- a deterministic ~1/40
  corpus-side thinning;
* the union, sorted by code point, one gesture per word.

Vertical model (the x model is typo_pack's): the default 5-row Tatar keyboard
(``kbd_tatar.xml``: rowHeight 20%p, verticalGap ``config_key_vertical_gap_5row`` 2.814%p,
top padding ``config_keyboard_top_padding`` 2.335%p) at the default height
(``config_default_keyboard_height`` 205.6 dp) on the reference screen (1080 px wide,
440 dpi -- the emulator smoke AVD class). Heights are computed in integer pixels with one
round-half-up per value (typo_pack's ``_device_round``) and then scaled into the 100 000-unit
reference grid by the same rule. This is a documented model, not a measurement: the P7-4
device tuning re-derives the constants from live geometry.

Noise model (per word, draws consumed in this exact order from the SplitMix64 stream seeded
by ``splitmix64(GLIDE_SEED ^ fnv1a64(word.utf8))``):

1. doubled-letter loop (P7-8): the loop is no longer a draw — the SET carries both variants
   of a doubled word (``generate_set`` emits the no-jog row first, then the jog row, the two
   drawn from the same word stream so the pair differs exactly in the detour), because the
   decoder's P7-8 rule scores a doubled word only against its looped ideal path and the
   calibration must measure both classes. ``generate_gesture`` takes ``draw_loop`` from the
   caller;
2. sampling step: ``STEP_MIN + draw % STEP_VAR`` grid units -- the raw-path sampling density
   varies per word like a real digitizer's event rate does with finger speed;
3. timestamp step: ``TSTEP_MIN + draw % TSTEP_VAR`` milliseconds per sample (carried for the
   touch-side recorder; the scoring channels ignore t);
4. corner cutting: every interior non-loop vertex is independently (probability
   1/CUT_MODULUS) pulled halfway toward its chord: ``V' = (P + 2V + Q) / 4`` per coordinate
   (integer floor division), all decisions taken on the ORIGINAL vertices;
5. the polyline is walked at the word's sampling step with integer segment lengths
   (``round(sqrt(dx^2+dy^2))``) and rational (floor-division) interpolation;
6. smooth wander: the per-point offset from the walked path is a clamped random walk, not
   independent noise -- real finger paths deviate smoothly, and independent per-sample jitter
   would inflate the path length far beyond what the decoder's length channel accepts. The
   initial x/y offsets are two draws in [-JITTER, +JITTER]; every subsequent point first draws
   an x increment then a y increment, each uniform in [-WANDER, +WANDER], applied and clamped
   to [-JITTER, +JITTER]. All integer, added after the (already integer) interpolation.

Historical note (2026-09-25): the pre-P7-8 model decided the loop by ``stream %
LOOP_MODULUS == 0`` with LOOP_MODULUS = 8 -- nominally 12.5 %, but 75.1 % of the selection's
doubled words drew it (the low bits of the seeded SplitMix64 stream are not uniform enough
for that modulus; measured, not chased -- the two-row model makes the quirk moot).

The generator is fail-closed exactly like typo_pack: wrong dictionary pins, missing layout or
eval inputs, an unmappable-alphabet or an empty/over-large set all exit nonzero with no
partial output.
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

# Dictionary pins are inherited from typo_pack (the committed Tatar top-110k asset).
EXPECTED_ASSET_SHA256 = typo_pack.EXPECTED_ASSET_SHA256
EXPECTED_RAW_SHA256 = typo_pack.EXPECTED_RAW_SHA256
EXPECTED_ENTRY_COUNT = typo_pack.EXPECTED_ENTRY_COUNT

# --- Deterministic knobs (a change is a written decision, not a silent bump). -------------
GLIDE_SEED = 20260924
# Word-selection knobs: >= 5 code points (the G1 gate's word class), ~1/40 dictionary thinning.
MIN_WORD_CODE_POINTS = 5
DICT_MODULUS = 40
# Vertical model (see the module docstring).
_REFERENCE_SCREEN_WIDTH_PX = 1080
_REFERENCE_DENSITY_DPI = 440
_KEYBOARD_HEIGHT_DP_X10 = 2056  # config_default_keyboard_height = 205.6 dp
_ROW_HEIGHT_PERCENT = 20  # kbd_tatar.xml default: 5 rows x 20%p
_VERTICAL_GAP_PERCENT_X1000 = 2814  # config_key_vertical_gap_5row = 2.814%p
_TOP_PADDING_PERCENT_X1000 = 2335  # config_keyboard_top_padding = 2.335%p
_LETTER_ROWS = 4  # the extra Tatar row plus the three qwerty rows
_GRID_WIDTH = typo_pack._GEOMETRY_REFERENCE_WIDTH  # 100 000
# Noise model (see the module docstring). STEP/JITTER are in grid units; TSTEP in ms.
# LOOP_MODULUS is historical (pre-P7-8 the loop was a 1/LOOP_MODULUS draw; the set now carries
# both variants of a doubled word) — the constant stays pinned for the golden vectors.
LOOP_MODULUS = 8
CUT_MODULUS = 10
STEP_DIVISOR = 6  # step range = [narrowestKeyWidth/6, narrowestKeyWidth/3): ~12-25 device px
TSTEP_MIN = 8
TSTEP_VAR = 9
JITTER_PERCENT = 18  # wander envelope = 18% of the key radius (a typical-glider deviation)
WANDER_DIVISOR = 6  # per-sample wander increment = envelope / 6

_MASK64 = (1 << 64) - 1


class GlidePackError(ValueError):
    """A fail-closed generator error (exit 2)."""


class GlideGuardrailError(GlidePackError):
    """A guardrail breach (exit 4)."""


def splitmix64(state: int) -> int:
    return typo_pack.splitmix64(state)


def fnv1a64(data: bytes) -> int:
    return typo_pack.fnv1a64(data)


# --------------------------------------------------------------------------------------
# Geometry: typo_pack's device-true x model plus the documented vertical model.
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
    """Device-true letter-key rectangles of the Tatar layout in the 100 000-unit grid."""
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
    jitter_percent: int = JITTER_PERCENT,
) -> list[tuple[int, int, int]]:
    """One synthetic gesture for ``word`` as (x, y, t) integer samples; see the noise model.

    P7-8: the loop is the CALLER's decision, not a draw — the calibration set carries both
    variants of a doubled word (the loop-decision draw of the pre-P7-8 model is gone, and the
    stream starts feeding the step draw immediately).
    """
    has_double = any(word[i] == word[i - 1] for i in range(1, len(word)))
    stream = splitmix64(seed ^ fnv1a64(word.encode("utf-8")))
    draw_loop = draw_loop and has_double
    # The sampling step references the narrowest letter-key width (the bottom row's 8.711%p
    # keys set it on the Tatar layout).
    standard_width = min(rect.right - rect.left for rect in by_letter.values())
    step_min = standard_width // STEP_DIVISOR
    step = step_min + stream % step_min
    stream = splitmix64(stream)
    tstep = TSTEP_MIN + stream % TSTEP_VAR
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
        cut_vertices[v] = ((px + 2 * vx + qx) // 4, (py + 2 * vy + qy) // 4)
    vertices = cut_vertices

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
        sampled.append((x + offset_x, y + offset_y, index * tstep))
    return sampled


def _clamp(value: int, bound: int) -> int:
    return max(-bound, min(bound, value))


def render_gesture(word: str, points: Sequence[tuple[int, int, int]]) -> str:
    coords = ";".join(f"{x},{y},{t}" for x, y, t in points)
    return f"{word}\t{coords}\n"


def generate_set(
    words: Sequence[str],
    rects: Sequence[_Rect],
    *,
    seed: int = GLIDE_SEED,
) -> tuple[str, bytes]:
    by_letter = {rect.code_point: rect for rect in rects}
    radius = key_radius(rects)
    rows: list[str] = []
    for word in words:
        # P7-8: a doubled word contributes BOTH variants — the no-jog row first, then the jog
        # row — drawn from the same word stream, so the pair differs exactly in the detour.
        rows.append(render_gesture(word, generate_gesture(word, by_letter, radius, seed=seed)))
        if any(word[i] == word[i - 1] for i in range(1, len(word))):
            rows.append(
                render_gesture(
                    word, generate_gesture(word, by_letter, radius, seed=seed, draw_loop=True),
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
            letters = frozenset(rect.code_point for rect in rects)
            selected = select_words(words, args.eval_words, letters)
            _, data = generate_set(selected, rects)
            write_atomic(args.output, data)
            _print_json(
                {
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
