#!/usr/bin/env python3
"""Development metrics for reviewed proper names and compound words.

Reads only development and training inputs: the held-out Tatar eval set
``app/src/test/resources/tt_eval_sentences.txt`` (development split), the committed dictionary
and bigram assets, the proper-name display asset and the two committed review tables. The final
Common Voice split is never read.

Prints ``EVAL|metric|value`` lines and per-name ``NAME|...`` lines:

* next-word coverage of reviewed proper-name keys: how often a development pair whose actual
  successor is a name key gets that key in the production chain's top-1, visible top-3 and
  engine top-4;
* display effect: before the map every predicted key is shown lowercase; after it every
  predicted key is shown with the reviewed display form, so the number of cells whose casing
  changes is the number of predicted name cells;
* homograph cost: development occurrences and adjacent pairs where the actual written form is
  the lowercase homograph, the exact cells a user would see capitalized, and the training
  evidence (non-initial occurrences, uncapitalized ones, cap ratio) from the review table;
* compound admission: dictionary coverage, development occurrences and adjacent pairs of the
  reviewed compound keys, and prefix coverage at three and five code points.
"""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import dictionary_coverage as coverage  # noqa: E402
import suggest_chain  # noqa: E402
import suggest_eval  # noqa: E402

DEFAULT_EVAL = ROOT / "app" / "src" / "test" / "resources" / "tt_eval_sentences.txt"
DEFAULT_DICT = ROOT / "app" / "src" / "main" / "assets" / "dictionaries" / "tatar_top100k_v1.tdict.zlib"
DEFAULT_BIGRAM = ROOT / "app" / "src" / "main" / "assets" / "bigrams" / "tatar_bigrams_v2.tatbigr.zlib"
DEFAULT_NAMES = ROOT / "app" / "src" / "main" / "assets" / "dictionaries" / "tatar_proper_names_v1.txt"
DEFAULT_NAME_REVIEW = ROOT / "data" / "dictionary" / "tatar-proper-name-review.tsv"
DEFAULT_COMPOUND_REVIEW = ROOT / "data" / "dictionary" / "tatar-compound-review.tsv"

VISIBLE_CELLS = suggest_eval.SHOWN_RESULTS


class MeasurementError(ValueError):
    """A missing or invalid input (exit 2)."""


def read_rows(path: Path) -> list[dict[str, str]]:
    try:
        with path.open(encoding="utf-8") as handle:
            lines = [line for line in handle if not line.startswith("#")]
    except OSError as error:
        raise MeasurementError(f"cannot read {path}: {error}") from error
    rows = list(csv.DictReader(lines, delimiter="\t"))
    if not rows:
        raise MeasurementError(f"{path}: no data rows")
    return rows


def read_displays(path: Path) -> dict[str, str]:
    displays: dict[str, str] = {}
    try:
        with path.open(encoding="utf-8") as handle:
            for line_number, raw in enumerate(handle, start=1):
                line = raw.rstrip("\n")
                if not line or line.startswith("#"):
                    continue
                fields = line.split("\t")
                if len(fields) != 2:
                    raise MeasurementError(
                        f"{path}:{line_number}: expected key<TAB>display"
                    )
                key, display = fields
                normalized, reason = coverage.normalize_word(
                    key, allow_internal_hyphen=True
                )
                if reason is not None or normalized != key:
                    raise MeasurementError(
                        f"{path}:{line_number}: key {key!r} is not canonical ({reason})"
                    )
                if display.lower() != key:
                    raise MeasurementError(
                        f"{path}:{line_number}: display {display!r} does not fold to {key!r}"
                    )
                displays[key] = display
    except OSError as error:
        raise MeasurementError(f"cannot read {path}: {error}") from error
    if not displays:
        raise MeasurementError(f"{path}: no display rows")
    return displays


def rate(part: int, whole: int) -> float:
    return part * 100.0 / whole if whole else 0.0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--eval", type=Path, default=DEFAULT_EVAL)
    parser.add_argument("--dict-asset", type=Path, default=DEFAULT_DICT)
    parser.add_argument("--bigram-asset", type=Path, default=DEFAULT_BIGRAM)
    parser.add_argument("--names", type=Path, default=DEFAULT_NAMES)
    parser.add_argument("--name-review", type=Path, default=DEFAULT_NAME_REVIEW)
    parser.add_argument("--compound-review", type=Path, default=DEFAULT_COMPOUND_REVIEW)
    args = parser.parse_args(argv)

    try:
        lines = suggest_eval.load_eval_lines(args.eval)
        words, frequencies, raw = suggest_eval.load_dictionary_words(args.dict_asset)
        successes = suggest_eval.load_bigram_successes(args.bigram_asset, words, raw)
        displays = read_displays(args.names)
        name_rows = read_rows(args.name_review)
        compound_rows = read_rows(args.compound_review)
    except (
        MeasurementError,
        suggest_eval.SuggestEvalError,
    ) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    vocabulary = set(words)
    mirror = suggest_chain.ChainMirror(words, frequencies, successes)
    name_keys = set(displays)
    compounds = {
        row["word"]: int(row["frequency"])
        for row in compound_rows
        if row["approved"] == "yes"
    }

    # Training evidence per name, from the review table (training corpora only).
    evidence = {row["normalized_form"]: row for row in name_rows}
    train_noninitial = sum(
        int(row["noninitial_capitalized"]) + int(row["uncapitalized_noninitial"])
        for row in name_rows
    )
    train_uncapitalized = sum(int(row["uncapitalized_noninitial"]) for row in name_rows)

    pairs = 0
    name_pairs = 0
    name_top1 = 0
    name_visible = 0
    name_engine = 0
    name_lower_pairs = 0
    display_cells = 0
    false_cap_pairs = 0
    false_cap_cells = 0
    compound_pairs = 0
    compound_hits = 0

    per_name: dict[str, dict[str, int]] = {
        key: {"tokens": 0, "lower": 0, "pairs": 0, "top1": 0, "visible": 0,
              "display_cells": 0, "false_cap_pairs": 0, "false_cap_cells": 0}
        for key in name_keys
    }
    per_compound: dict[str, dict[str, int]] = {
        word: {"tokens": 0, "pairs": 0, "visible": 0} for word in compounds
    }

    for line in lines:
        tokens = line.split(" ")
        for index, token in enumerate(tokens):
            normalized = token.lower()
            if normalized in per_name:
                per_name[normalized]["tokens"] += 1
                if token == normalized:
                    per_name[normalized]["lower"] += 1
            if normalized in per_compound:
                per_compound[normalized]["tokens"] += 1
            if index + 1 >= len(tokens):
                continue
            pairs += 1
            head = token
            successor = tokens[index + 1]
            key = successor.lower()
            shown = mirror.predict_top3(head)
            shown_displays = [displays.get(word, word) for word in shown]
            if key in name_keys:
                name_pairs += 1
                stats = per_name[key]
                stats["pairs"] += 1
                if shown and shown[0] == key:
                    name_top1 += 1
                    stats["top1"] += 1
                if key in shown[:VISIBLE_CELLS]:
                    name_visible += 1
                    stats["visible"] += 1
                if key in shown:
                    name_engine += 1
                if successor == key:
                    name_lower_pairs += 1
                for word, display in zip(shown, shown_displays):
                    if word in name_keys:
                        display_cells += 1
                        stats["display_cells"] += 1
                        if display != word:
                            false_cap_cells += 1
                            stats["false_cap_cells"] += 1
                if successor == key and any(
                    word in name_keys and display != word
                    for word, display in zip(shown[:VISIBLE_CELLS], shown_displays[:VISIBLE_CELLS])
                ):
                    false_cap_pairs += 1
                    stats["false_cap_pairs"] += 1
            if key in per_compound:
                compound_pairs += 1
                per_compound[key]["pairs"] += 1
                if key in shown[:VISIBLE_CELLS]:
                    compound_hits += 1
                    per_compound[key]["visible"] += 1

    def prefix_coverage(keys: set[str]) -> tuple[int, int]:
        moments = 0
        hits = 0
        for key in sorted(keys):
            if key not in vocabulary:
                continue
            for length in (3, 5):
                if len(key) <= length:
                    continue
                moments += 1
                if key in mirror.prefix_top3(key[:length])[:VISIBLE_CELLS]:
                    hits += 1
        return moments, hits

    name_prefix_moments, name_prefix_hits = prefix_coverage(name_keys)
    compound_prefix_moments, compound_prefix_hits = prefix_coverage(set(compounds))

    def out(metric: str, value: object) -> None:
        print(f"EVAL|{metric}|{value}")

    out("eval_lines", len(lines))
    out("proper_name_keys", len(name_keys))
    out("proper_name_keys_in_dictionary", sum(1 for key in name_keys if key in vocabulary))
    out("compound_keys", len(compounds))
    out("compound_keys_in_dictionary", sum(1 for key in compounds if key in vocabulary))
    out("pairs", pairs)
    out("name_actual_pairs", name_pairs)
    out("name_actual_lowercase_pairs", name_lower_pairs)
    out("name_top1_hits", name_top1)
    out("name_visible_top3_hits", name_visible)
    out("name_engine_top4_hits", name_engine)
    out("name_top1_pct", f"{rate(name_top1, name_pairs):.4f}")
    out("name_visible_top3_pct", f"{rate(name_visible, name_pairs):.4f}")
    out("name_engine_top4_pct", f"{rate(name_engine, name_pairs):.4f}")
    out("name_display_cells", display_cells)
    out("name_display_before_cap_cells", 0)
    out("name_display_after_cap_cells", display_cells)
    out("name_false_cap_pairs", false_cap_pairs)
    out("name_false_cap_cells", false_cap_cells)
    out("name_false_cap_cell_pct", f"{rate(false_cap_cells, display_cells):.4f}")
    out("proper_name_dev_tokens", sum(item["tokens"] for item in per_name.values()))
    out("proper_name_dev_lowercase_tokens", sum(item["lower"] for item in per_name.values()))
    out("train_noninitial_occurrences", train_noninitial)
    out("train_uncapitalized_noninitial", train_uncapitalized)
    out("train_uncapitalized_pct", f"{rate(train_uncapitalized, train_noninitial):.4f}")
    out("compound_pairs", compound_pairs)
    out("compound_visible_top3_hits", compound_hits)
    out("compound_visible_top3_pct", f"{rate(compound_hits, compound_pairs):.4f}")
    out("name_prefix_moments", name_prefix_moments)
    out("name_prefix_visible_top3_hits", name_prefix_hits)
    out("name_prefix_visible_top3_pct", f"{rate(name_prefix_hits, name_prefix_moments):.4f}")
    out("compound_prefix_moments", compound_prefix_moments)
    out("compound_prefix_visible_top3_hits", compound_prefix_hits)
    out("compound_prefix_visible_top3_pct", f"{rate(compound_prefix_hits, compound_prefix_moments):.4f}")

    for key in sorted(per_name):
        stats = per_name[key]
        row = evidence.get(key, {})
        print(
            "NAME|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}".format(
                key,
                displays[key],
                stats["tokens"],
                stats["lower"],
                stats["pairs"],
                stats["top1"],
                stats["visible"],
                stats["display_cells"],
                stats["false_cap_cells"],
                str(int(row.get("noninitial_capitalized", "0"))
                    + int(row.get("uncapitalized_noninitial", "0"))),
                row.get("uncapitalized_noninitial", "0"),
            )
        )
    for key in sorted(per_compound):
        stats = per_compound[key]
        print(
            "COMPOUND|{}|{}|{}|{}|{}".format(
                key,
                compounds[key],
                stats["tokens"],
                stats["pairs"],
                stats["visible"],
            )
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
