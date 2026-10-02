#!/usr/bin/env python3
"""Measure real glide-gesture statistics of the FUTO swipe corpus against the synthetic set.

Reads the swipe-1 validation slice (``dev.jsonl``) and the QWERTY layout definition of the FUTO
swipe corpus (MIT, https://huggingface.co/datasets/futo-org/swipe.futo.org), reconstructs each
target word's ideal key-center path, and reports the distributions that pin down the noise model
of ``scripts/glide_pack.py``: perpendicular offset from the ideal path, corner-cutting depth by
turn angle, path-length ratio, sampling density, inter-point dt and endpoint offsets. The same
measurements run on gestures the generator produces for the same words over the same layout, so
both columns of the report come from one measurement procedure.

Geometry source: ``swipe-5/layouts/qwerty.json`` of the dataset (per-key letter/cx/cy/rx/ry,
normalized to the keyboard canvas) — the dataset card names it THE QWERTY layout definition.
Compatibility with swipe-1 coordinates is not assumed but measured: the report's
``first_key_hit`` rate (the nearest key center to the first sample is the word's first letter)
is above 90 %, which a mismatched geometry cannot reach.

All real-gesture distances are normalized by the record's own key radius (the decoder's
definition: min over letter keys of min(key width, key height)) computed in the record's pixel
space (``x * canvas_width``, ``y * canvas_height``); the corpus carries one canvas size per
record. The synthetic side uses one reference canvas (the rounded medians), which is exact for a
scale-free generator.

Also writes the QWERTY key rectangles as a keys TSV (code point in hex, left/top/right/bottom in
reference-canvas units, the format the JVM glide tests read) for the real-gesture diagnostic
harness; the reference canvas is recoverable from the TSV as the maximum key right/bottom.

The data is downloaded once by hand and never enters git:

    mkdir -p ~/corpora-futo
    curl -sS -L -o ~/corpora-futo/dev.jsonl \
        https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/main/dev.jsonl
    curl -sS -L -o ~/corpora-futo/qwerty.json \
        https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/main/swipe-5/layouts/qwerty.json
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from array import array
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

import glide_pack  # noqa: E402

# Row filters (the caps of the analysis). The ``distance`` field is the collector's validity
# metric; rows beyond MAX_DISTANCE are its unmatched sentinel cluster. MIN/MAX_POINTS drop empty
# and pathological digitizer runs; words outside MIN/MAX_WORD_LETTERS or with a letter outside
# a-z are skipped (one-letter targets have a degenerate ideal path; the corpus max is well
# below the cap).
MAX_DISTANCE = 1000.0
MIN_POINTS = 2
MAX_POINTS = 2048
MIN_WORD_LETTERS = 2
MAX_WORD_LETTERS = 32

# A sample contributes to the offset (wander) statistic only when its projection parameter on
# the nearest ideal segment lies in [MIDDLE, 1 - MIDDLE]: the zone corner cutting cannot reach.
MIDDLE = 0.25
# A sample contributes to an interior vertex's closest approach when its projection lies on the
# adjacent half-segments ([0.5, 1] of the incoming, [0, 0.5] of the outgoing segment).
CORNER_HALF = 0.5

ANGLE_BUCKETS = ((0, 30), (30, 60), (60, 90), (90, 120), (120, 150), (150, 181))


def percentile(sorted_values: Sequence[float], fraction: float) -> float:
    rank = max(1, math.ceil(len(sorted_values) * fraction))
    return sorted_values[rank - 1]


def summarize(samples: array, percentiles: Sequence[float] = (0.5, 0.9, 0.95)) -> dict:
    if not samples:
        return {"n": 0}
    ordered = sorted(samples)
    result = {"n": len(ordered), "mean": round(statistics.fmean(ordered), 4)}
    for p in percentiles:
        result[f"p{int(p * 100)}"] = round(percentile(ordered, p), 4)
    result["max"] = round(ordered[-1], 4)
    return result


class GestureStats:
    """Accumulators of one measurement side (real or synthetic), filled by measure_gesture."""

    def __init__(self) -> None:
        self.gestures = 0
        self.offsets = array("f")  # perpendicular offset / radius, segment-middle samples
        self.start_offsets = array("f")  # first point to first key center / radius
        self.end_offsets = array("f")
        self.length_ratios = array("f")  # real polyline length / ideal length
        self.densities = array("f")  # points per radius of traveled path
        self.dts = array("f")  # inter-point dt, ms
        self.corner_depths = [array("f") for _ in ANGLE_BUCKETS]  # closest approach / radius
        self.first_key_hits = 0  # nearest key to the first sample is the word's first letter

    def report(self) -> dict:
        return {
            "gestures": self.gestures,
            "offset_radii": summarize(self.offsets),
            "endpoint_start_radii": summarize(self.start_offsets),
            "endpoint_end_radii": summarize(self.end_offsets),
            "length_ratio": summarize(self.length_ratios, (0.5, 0.9)),
            "points_per_radius": summarize(self.densities, (0.1, 0.5, 0.9)),
            "dt_ms": summarize(self.dts, (0.1, 0.5, 0.9)),
            "first_key_hit": round(self.first_key_hits / max(1, self.gestures), 4),
        }


def measure_gesture(
    points: Sequence[tuple[float, float]],
    timestamps: Sequence[float],
    vertices: Sequence[tuple[float, float]],
    radius: float,
    stats: GestureStats,
) -> None:
    """Fold one gesture's samples into stats. Vertices are the key centers in letter order."""
    count = len(vertices)
    seg_len = [0.0] * (count - 1)
    for i in range(count - 1):
        seg_len[i] = math.hypot(
            vertices[i + 1][0] - vertices[i][0], vertices[i + 1][1] - vertices[i][1]
        )
    ideal_length = sum(seg_len)

    # Per interior vertex: the closest approach of the samples assigned to its corner window.
    approach = [math.inf] * count

    real_length = 0.0
    previous_x, previous_y = points[0]
    current = 0  # nearest-segment cursor; gestures follow the path, so a small window suffices
    for px, py in points:
        real_length += math.hypot(px - previous_x, py - previous_y)
        previous_x, previous_y = px, py
        # Nearest segment within a window around the cursor (exact for monotone travel; the
        # window recenters on the argmin, so backtracking within 3 segments is still exact).
        lo = max(0, current - 3)
        hi = min(count - 1, current + 4)
        best_seg = current
        best_dist = math.inf
        best_param = 0.0
        for seg in range(lo, hi):
            x1, y1 = vertices[seg]
            dx = vertices[seg + 1][0] - x1
            dy = vertices[seg + 1][1] - y1
            length_sq = dx * dx + dy * dy
            if length_sq == 0.0:  # a doubled letter's degenerate segment
                dist = math.hypot(px - x1, py - y1)
                param = 0.0
            else:
                param = ((px - x1) * dx + (py - y1) * dy) / length_sq
                if param <= 0.0:
                    dist = math.hypot(px - x1, py - y1)
                elif param >= 1.0:
                    dist = math.hypot(px - x1 - dx, py - y1 - dy)
                else:
                    dist = abs(dx * (py - y1) - dy * (px - x1)) / seg_len[seg]
            if dist < best_dist:
                best_dist = dist
                best_seg = seg
                best_param = min(1.0, max(0.0, param))
        current = best_seg
        if seg_len[best_seg] > 0.0 and MIDDLE <= best_param <= 1.0 - MIDDLE:
            stats.offsets.append(best_dist / radius)
        if best_param >= CORNER_HALF and best_seg + 1 < count:
            vertex = best_seg + 1
            dist = math.hypot(px - vertices[vertex][0], py - vertices[vertex][1])
            if dist < approach[vertex]:
                approach[vertex] = dist
        if best_param <= 1.0 - CORNER_HALF and 0 < best_seg:
            dist = math.hypot(px - vertices[best_seg][0], py - vertices[best_seg][1])
            if dist < approach[best_seg]:
                approach[best_seg] = dist

    stats.gestures += 1
    stats.start_offsets.append(
        math.hypot(points[0][0] - vertices[0][0], points[0][1] - vertices[0][1]) / radius
    )
    stats.end_offsets.append(
        math.hypot(points[-1][0] - vertices[-1][0], points[-1][1] - vertices[-1][1]) / radius
    )
    if ideal_length > 0.0:
        stats.length_ratios.append(real_length / ideal_length)
    if real_length > 0.0:
        stats.densities.append(len(points) * radius / real_length)
    for earlier, later in zip(timestamps, timestamps[1:]):
        stats.dts.append(later - earlier)

    for vertex in range(1, count - 1):
        if math.isinf(approach[vertex]):
            continue
        px, py = vertices[vertex - 1]
        vx, vy = vertices[vertex]
        qx, qy = vertices[vertex + 1]
        ux, uy = vx - px, vy - py
        wx, wy = qx - vx, qy - vy
        norm = math.hypot(ux, uy) * math.hypot(wx, wy)
        if norm == 0.0:  # a doubled letter carries no turn angle
            continue
        cosine = max(-1.0, min(1.0, (ux * wx + uy * wy) / norm))
        degrees = math.degrees(math.acos(cosine))
        for bucket, (low, high) in enumerate(ANGLE_BUCKETS):
            if low <= degrees < high:
                stats.corner_depths[bucket].append(approach[vertex] / radius)
                break


def load_layout(path: Path) -> dict[str, tuple[float, float, float, float]]:
    """letter -> (cx, cy, rx, ry) in the corpus's canvas-normalized coordinates."""
    layout = json.loads(path.read_text(encoding="utf-8"))
    return {key["letter"]: (key["cx"], key["cy"], key["rx"], key["ry"]) for key in layout["keys"]}


def ideal_vertices(
    word: str, layout: dict[str, tuple[float, float, float, float]], width: float, height: float
) -> list[tuple[float, float]]:
    return [
        (layout[char][0] * width, layout[char][1] * height) for char in word
    ]


def key_radius(
    layout: dict[str, tuple[float, float, float, float]], width: float, height: float
) -> float:
    """The decoder's key radius: min over letter keys of min(key width, key height)."""
    return min(
        min(2 * rx * width, 2 * ry * height) for _, _, rx, ry in layout.values()
    )


def measure_real(dev_path: Path, layout: dict[str, tuple[float, float, float, float]]) -> tuple[GestureStats, dict, list[str], list[float], list[float]]:
    stats = GestureStats()
    skipped = {"distance": 0, "points": 0, "letters": 0, "length": 0, "radius": 0}
    words: set[str] = set()
    canvas_widths: list[float] = []
    canvas_heights: list[float] = []
    with dev_path.open(encoding="utf-8") as stream:
        for line in stream:
            record = json.loads(line)
            if record.get("distance", 0.0) >= MAX_DISTANCE:
                skipped["distance"] += 1
                continue
            data = record["data"]
            if not MIN_POINTS <= len(data) <= MAX_POINTS:
                skipped["points"] += 1
                continue
            word = record["word"].lower()
            if not word.isalpha() or not all(char in layout for char in word):
                skipped["letters"] += 1
                continue
            if not MIN_WORD_LETTERS <= len(word) <= MAX_WORD_LETTERS:
                skipped["length"] += 1
                continue
            width, height = float(record["canvas_width"]), float(record["canvas_height"])
            radius = key_radius(layout, width, height)
            if radius <= 0.0:
                skipped["radius"] += 1
                continue
            points = [(p["x"] * width, p["y"] * height) for p in data]
            timestamps = [p["t"] for p in data]
            vertices = ideal_vertices(word, layout, width, height)
            measure_gesture(points, timestamps, vertices, radius, stats)
            # Geometry sanity: the nearest key center to the first sample is the first letter.
            fx, fy = points[0]
            nearest = min(
                layout_centers(layout, width, height),
                key=lambda center: (center[0] - fx) ** 2 + (center[1] - fy) ** 2,
            )
            if nearest == vertices[0]:
                stats.first_key_hits += 1
            words.add(word)
            canvas_widths.append(width)
            canvas_heights.append(height)
    return stats, skipped, sorted(words), canvas_widths, canvas_heights


def layout_centers(
    layout: dict[str, tuple[float, float, float, float]], width: float, height: float
) -> list[tuple[float, float]]:
    return [(cx * width, cy * height) for cx, cy, _, _ in layout.values()]


def qwerty_rects(
    layout: dict[str, tuple[float, float, float, float]], width: int, height: int
) -> list[glide_pack._Rect]:
    """Integer key rectangles of the layout on the reference canvas, glide_pack's _Rect."""
    return [
        glide_pack._Rect(
            ord(letter),
            round((cx - rx) * width),
            round((cy - ry) * height),
            round((cx + rx) * width),
            round((cy + ry) * height),
        )
        for letter, (cx, cy, rx, ry) in sorted(layout.items())
    ]


def measure_synthetic(
    words: Sequence[str],
    rects: list[glide_pack._Rect],
    radius: int,
    overrides: dict[str, int],
) -> GestureStats:
    stats = GestureStats()
    by_letter = glide_pack.letters_by_code_point(rects)
    centers = [(rect.center_x, rect.center_y) for rect in rects]
    for word in words:
        gesture = glide_pack.generate_gesture(word, by_letter, radius, **overrides)
        points = [(float(x), float(y)) for x, y, _ in gesture]
        timestamps = [float(t) for _, _, t in gesture]
        vertices = [(float(by_letter[ord(c)].center_x), float(by_letter[ord(c)].center_y)) for c in word]
        measure_gesture(points, timestamps, vertices, float(radius), stats)
        fx, fy = points[0]
        nearest = min(centers, key=lambda center: (center[0] - fx) ** 2 + (center[1] - fy) ** 2)
        if nearest == vertices[0]:
            stats.first_key_hits += 1
    return stats


def write_keys_tsv(path: Path, rects: Sequence[glide_pack._Rect]) -> None:
    lines = [
        f"{rect.code_point:x}\t{rect.left}\t{rect.top}\t{rect.right}\t{rect.bottom}"
        for rect in rects
    ]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--dev", type=Path, default=Path.home() / "corpora-futo" / "dev.jsonl")
    parser.add_argument(
        "--layout", type=Path, default=Path.home() / "corpora-futo" / "qwerty.json"
    )
    parser.add_argument(
        "--keys-out",
        type=Path,
        default=Path.home() / "corpora-futo" / "keys-qwerty.tsv",
        help="QWERTY key rectangles on the reference canvas, for the JVM diagnostic",
    )
    # Synthetic-side overrides for recalibration experiments; unset means the generator's
    # shipped constants.
    parser.add_argument("--synth-jitter-percent", type=int, default=None)
    parser.add_argument("--synth-gesture-offset-percent", type=int, default=None)
    parser.add_argument("--synth-cut-modulus", type=int, default=None)
    parser.add_argument("--synth-cut-divisor", type=int, default=None)
    parser.add_argument("--synth-endpoint-start-percent", type=int, default=None)
    parser.add_argument("--synth-endpoint-end-percent", type=int, default=None)
    args = parser.parse_args(argv)

    layout = load_layout(args.layout)
    real, skipped, words, widths, heights = measure_real(args.dev, layout)
    if not words:
        print("error: no usable records", file=sys.stderr)
        return 2
    reference_width = round(statistics.median(widths))
    reference_height = round(statistics.median(heights))
    rects = qwerty_rects(layout, reference_width, reference_height)
    radius = glide_pack.key_radius(rects)
    overrides = {
        key: value
        for key, value in {
            "jitter_percent": args.synth_jitter_percent,
            "gesture_offset_percent": args.synth_gesture_offset_percent,
            "cut_modulus": args.synth_cut_modulus,
            "cut_divisor": args.synth_cut_divisor,
            "endpoint_start_percent": args.synth_endpoint_start_percent,
            "endpoint_end_percent": args.synth_endpoint_end_percent,
        }.items()
        if value is not None
    }
    synthetic = measure_synthetic(words, rects, radius, overrides)
    write_keys_tsv(args.keys_out, rects)

    report = {
        "meta": {
            "records_kept": real.gestures,
            "skipped": skipped,
            "unique_words": len(words),
            "reference_canvas": [reference_width, reference_height],
            "reference_key_radius": radius,
            "caps": {
                "max_distance": MAX_DISTANCE,
                "min_points": MIN_POINTS,
                "max_points": MAX_POINTS,
                "min_word_letters": MIN_WORD_LETTERS,
                "max_word_letters": MAX_WORD_LETTERS,
            },
            "keys_tsv": str(args.keys_out),
        },
        "real": real.report(),
        "synthetic": synthetic.report(),
        "corner_depth_radii": [
            {
                "turn_angle_degrees": [low, high if high <= 180 else 180],
                "real": summarize(real.corner_depths[bucket]),
                "synthetic": summarize(synthetic.corner_depths[bucket]),
            }
            for bucket, (low, high) in enumerate(ANGLE_BUCKETS)
        ],
    }
    json.dump(report, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
