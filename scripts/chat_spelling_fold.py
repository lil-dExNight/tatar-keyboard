#!/usr/bin/env python3
"""Fold Russian-letter spellings of Tatar words in the Tatar dictionary composition.

A chat spelling replaces the Tatar letters ә ө ү җ ң һ with their Russian neighbours э о у ж н х
throughout a word: `рәхмәт` is typed `рэхмэт`, `һәм` as `хэм`. The mapping is one-way: every
standard form has exactly one fully translated variant, while a variant can be shared by two
standard forms that differ only in those letters. Corpus text contains both spellings, so the
composition can admit the Russian-letter one as a separate word; it then blocks autocorrect and
crowds the suggestion strip (R2).

The rule is applied to the composition before the cutoff, so folded counts move with the word
they belong to. A variant is folded only when a single standard form exists and its frequency is
at least FOLD_DOMINANCE times the variant's; a variant at least as frequent as its only standard
form is a real word with a different meaning (`монда` against `моңда`) and stays. Weak or shared
evidence is never resolved automatically: every flagged word needs a reviewed row in
`data/dictionary/tatar-chat-spelling-review.tsv`, and a flagged word without one stops the
build. `propose` writes the table with the automatic verdict pre-filled for review.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]

REVIEW = Path("data/dictionary/tatar-chat-spelling-review.tsv")
REVIEW_HEADER = (
    "word", "decision", "target", "note",
)
DECISIONS = ("fold", "drop", "keep")

# Tatar letter to the Russian letter typed in its place.
CHAT_PAIRS = (
    ("ә", "э"),
    ("ө", "о"),
    ("ү", "у"),
    ("җ", "ж"),
    ("ң", "н"),
    ("һ", "х"),
)
SUBSTITUTE = {tatar: russian for tatar, russian in CHAT_PAIRS}
TRANSLATION = str.maketrans(SUBSTITUTE)
TATAR_LETTERS = frozenset(tatar for tatar, _russian in CHAT_PAIRS)
MIN_WORD_LENGTH = 3
# A standard form this many times more frequent than its variant makes the fold automatic.
FOLD_DOMINANCE = 4


class ChatSpellingError(ValueError):
    """A malformed review table, an unreviewed variant, or an impossible decision."""


@dataclass(frozen=True)
class Decision:
    word: str
    decision: str  # fold / drop / keep
    target: str  # the standard form for a fold, empty otherwise
    variant_frequency: int
    target_frequency: int
    note: str


@dataclass(frozen=True)
class FoldReport:
    folded: int
    dropped: int
    kept: int
    folded_tokens: int
    dropped_tokens: int

    def as_dict(self) -> dict[str, int]:
        return {
            "folded": self.folded,
            "dropped": self.dropped,
            "kept": self.kept,
            "folded_tokens": self.folded_tokens,
            "dropped_tokens": self.dropped_tokens,
        }


def chat_variant(word: str) -> str | None:
    """The fully translated variant of a standard form, or None when it has no Tatar letter."""
    if len(word) < MIN_WORD_LENGTH or TATAR_LETTERS.isdisjoint(word):
        return None
    variant = word.translate(TRANSLATION)
    return variant if variant != word else None


def _reduction_key(word: str) -> str | None:
    """The fully translated form of a word, the grouping key of its spelling variants.

    Two words share a key when they differ only at positions where one holds a Tatar letter and
    the other its Russian partner, so every chat spelling of a standard form stays in its group.
    """
    if len(word) < MIN_WORD_LENGTH:
        return None
    return word.translate(TRANSLATION)


def _dominates(standard: str, variant: str) -> bool:
    """Whether `standard` becomes `variant` by replacing some of its Tatar letters."""
    if standard == variant or len(standard) != len(variant):
        return False
    changed = False
    for left, right in zip(standard, variant):
        if left == right:
            continue
        if SUBSTITUTE.get(left) != right:
            return False
        changed = True
    return changed


def standards_by_variant(words: Iterable[str]) -> dict[str, tuple[str, ...]]:
    """Map each composition word that is a chat spelling to the standard forms it derives from.

    A variant may substitute only some of the Tatar letters (`һәм` is also typed `һэм`), so the
    rule compares every pair inside a fully translated group rather than only the fully
    translated member.
    """
    groups: dict[str, list[str]] = {}
    for word in words:
        key = _reduction_key(word)
        if key is not None:
            groups.setdefault(key, []).append(word)
    mapping: dict[str, tuple[str, ...]] = {}
    for members in groups.values():
        if len(members) < 2:
            continue
        for word in members:
            forms = tuple(sorted(form for form in members if _dominates(form, word)))
            if forms:
                mapping[word] = forms
    return mapping


def flagged(
    frequencies: Mapping[str, int], words: Iterable[str] | None = None
) -> list[Decision]:
    """Every composition word that is a variant of another composition word, with the
    automatic verdict that `propose` pre-fills."""
    mapping = standards_by_variant(frequencies)
    out: list[Decision] = []
    for word in sorted(words if words is not None else frequencies):
        forms = tuple(form for form in mapping.get(word, ()) if form != word)
        if not forms:
            continue
        variant_frequency = frequencies.get(word, 0)
        best = max(forms, key=lambda form: (frequencies.get(form, 0), form))
        best_frequency = frequencies.get(best, 0)
        if variant_frequency >= best_frequency:
            out.append(
                Decision(word, "keep", "", variant_frequency, best_frequency,
                         "at least as frequent as its standard form")
            )
        elif len(forms) == 1 and best_frequency >= FOLD_DOMINANCE * variant_frequency:
            out.append(
                Decision(word, "fold", best, variant_frequency, best_frequency,
                         "single standard form, dominant frequency")
            )
        elif len(forms) > 1:
            out.append(
                Decision(word, "drop", "", variant_frequency, best_frequency,
                         "shared by several standard forms")
            )
        else:
            out.append(
                Decision(word, "keep", "", variant_frequency, best_frequency,
                         "frequency does not separate the two spellings")
            )
    return out


def read_review(path: Path) -> dict[str, Decision]:
    """Read the reviewed table; every row must be complete and consistent."""
    rows: dict[str, Decision] = {}
    with path.open("r", encoding="utf-8", newline="") as stream:
        header = stream.readline().rstrip("\n").split("\t")
        if tuple(header) != REVIEW_HEADER:
            raise ChatSpellingError(
                f"{path}: header must be {' '.join(REVIEW_HEADER)!r}, found {header!r}"
            )
        for line_number, line in enumerate(stream, start=2):
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if len(fields) != len(REVIEW_HEADER):
                raise ChatSpellingError(
                    f"{path}:{line_number}: expected {len(REVIEW_HEADER)} columns, "
                    f"found {len(fields)}"
                )
            word, decision, target, note = fields
            if decision not in DECISIONS:
                raise ChatSpellingError(
                    f"{path}:{line_number}: decision {decision!r} is not one of {DECISIONS}"
                )
            if word in rows:
                raise ChatSpellingError(f"{path}:{line_number}: duplicate word {word!r}")
            if decision == "fold" and not target:
                raise ChatSpellingError(f"{path}:{line_number}: a fold needs a target")
            if decision != "fold" and target:
                raise ChatSpellingError(
                    f"{path}:{line_number}: only a fold carries a target, found {target!r}"
                )
            rows[word] = Decision(word, decision, target, 0, 0, note)
    return rows


def write_review(path: Path, decisions: Sequence[Decision]) -> None:
    lines = ["\t".join(REVIEW_HEADER)]
    for item in decisions:
        lines.append(
            "\t".join((item.word, item.decision, item.target, item.note))
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def apply(
    frequencies: Mapping[str, int], review: Mapping[str, Decision]
) -> tuple[dict[str, int], FoldReport]:
    """Fold, drop and keep the reviewed words.

    A word the rule would remove needs a reviewed row; a word it would keep needs none, because
    keeping changes nothing.
    """
    result = dict(frequencies)
    current = flagged(frequencies)
    current_words = {item.word for item in current}
    unreviewed = [
        item.word
        for item in current
        if item.word not in review and item.decision != "keep"
    ]
    if unreviewed:
        raise ChatSpellingError(
            "unreviewed Russian-letter spellings in the composition: "
            + ", ".join(unreviewed[:20])
            + (f" and {len(unreviewed) - 20} more" if len(unreviewed) > 20 else "")
        )
    folded = dropped = kept = 0
    folded_tokens = dropped_tokens = 0
    for word, item in review.items():
        if word not in result or word not in current_words:
            continue
        count = result[word]
        if item.decision == "keep":
            kept += 1
        elif item.decision == "drop":
            del result[word]
            dropped += 1
            dropped_tokens += count
        else:
            target = item.target
            if target == word or target not in result:
                raise ChatSpellingError(
                    f"{word!r} folds into {target!r}, which is not in the composition"
                )
            result[target] += count
            del result[word]
            folded += 1
            folded_tokens += count
    return result, FoldReport(folded, dropped, kept, folded_tokens, dropped_tokens)


def _read_frequencies(path: Path) -> dict[str, int]:
    frequencies: dict[str, int] = {}
    with path.open("r", encoding="utf-8", newline="") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if len(fields) != 2 or not fields[1].isdigit():
                raise ChatSpellingError(f"{path}:{line_number}: expected word<TAB>frequency")
            frequencies[fields[0]] = int(fields[1])
    return frequencies


def merge_proposal(
    frequencies: Mapping[str, int], reviewed: Mapping[str, Decision]
) -> tuple[list[Decision], list[str], list[str]]:
    """Pre-fill a proposal from the automatic verdicts without overwriting reviewed rows.

    Returns the rows to write, the flagged words that lack a decision, and the rows whose word is
    no longer flagged (they are kept, so an explicit keep still vetoes an automatic fold).
    """
    decisions = flagged(frequencies)
    proposal: list[Decision] = []
    missing: list[str] = []
    present: set[str] = set()
    for item in decisions:
        present.add(item.word)
        if item.word in reviewed:
            proposal.append(reviewed[item.word])
        elif item.decision == "keep":
            continue
        else:
            proposal.append(item)
            missing.append(item.word)
    stale = sorted(word for word in reviewed if word not in present)
    proposal.extend(reviewed[word] for word in stale)
    return proposal, sorted(missing), stale


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    propose = sub.add_parser(
        "propose", help="write the review table for a word<TAB>frequency composition"
    )
    propose.add_argument("--composition", type=Path, required=True)
    propose.add_argument("--output", type=Path, default=ROOT / REVIEW)
    propose.add_argument("--report", type=Path, default=None)
    args = parser.parse_args(argv)
    frequencies = _read_frequencies(args.composition)
    reviewed = read_review(args.output) if args.output.is_file() else {}
    decisions, missing, stale = merge_proposal(frequencies, reviewed)
    write_review(args.output, decisions)
    counts: dict[str, int] = {decision: 0 for decision in DECISIONS}
    for item in decisions:
        counts[item.decision] += 1
    summary = {
        "words": len(frequencies),
        "rows": len(decisions),
        "awaiting_review": missing,
        "rows_not_flagged": stale,
        **counts,
    }
    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    print(json.dumps(
        {key: value for key, value in summary.items()
         if not isinstance(value, list)} | {"awaiting_review": len(missing),
                                            "rows_not_flagged": len(stale)},
        ensure_ascii=False, sort_keys=True,
    ))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
