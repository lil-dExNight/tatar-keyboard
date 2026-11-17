#!/usr/bin/env python3
"""Build and check the reviewed Tatar proper-name display list.

The asset maps a lowercase dictionary key to the approved proper-name display form. ``build`` reads ``data/dictionary/tatar-proper-name-review.tsv`` plus the
``proper_display`` rows of ``data/dictionary/tatar-compound-review.tsv`` and writes
``app/src/main/assets/dictionaries/tatar_proper_names_v1.txt``. ``check`` verifies that the
asset equals what the review tables produce and, with ``--dictionary``, that every key is in
the Tatar dictionary. Exit: 0 ok, 1 mismatch or invalid content, 2 missing input.
"""

from __future__ import annotations

import argparse
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import dict_accept  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack  # noqa: E402

PROPER_NAME_REVIEW = Path("data/dictionary/tatar-proper-name-review.tsv")
COMPOUND_REVIEW = Path("data/dictionary/tatar-compound-review.tsv")
PROPER_NAME_ASSET = Path("app/src/main/assets/dictionaries/tatar_proper_names_v1.txt")
TATAR_DICTIONARY = Path("app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib")

PROPER_NAME_REVIEW_HEADER = [
    "normalized_form", "display_form", "total_occurrences", "noninitial_capitalized",
    "uncapitalized_noninitial", "cap_ratio", "sources", "approved", "reviewer",
    "review_date", "note",
]
COMPOUND_REVIEW_HEADER = [
    "word", "frequency", "category", "sources", "proper_display", "approved", "reviewer",
    "review_date", "note",
]

ASSET_HEADER = """\
# Reviewed Tatar proper-name display forms, v1.
#
# Source: data/dictionary/tatar-proper-name-review.tsv, plus the proper_display rows of
# data/dictionary/tatar-compound-review.tsv. Every row carries approved=yes, reviewer,
# review_date and note. Training sources only; development and final evaluation records
# were not read.
# License: CC BY 4.0 (Leipzig Corpora Collection), CC BY 2.0 FR (Tatoeba) and the
# OpenSubtitles terms; the provenance shard is data/provenance.d/asset-proper-names-tatar.json.
# Rows: key<TAB>display, sorted by key. The key is the NFC lowercase dictionary word, with
# U+002D only between two letters; the display is the approved proper-name form and its
# lowercase equals the key. Lines starting with # are comments.
"""


class ProperNameError(ValueError):
    """An invalid review row or asset row; the command exits nonzero and writes nothing."""


@dataclass(frozen=True)
class ReviewMetadata:
    """The widened-review fields `dict_accept.review_metadata_reason` inspects."""

    approved: str
    reviewer: str
    review_date: str
    note: str


@dataclass(frozen=True)
class CompoundReview:
    """The reviewed compound table: admission frequencies and proper display forms."""

    frequencies: dict[str, int]
    proper_displays: dict[str, str]


def _read_rows(path: Path, header: Sequence[str]):
    """Yield (line_number, row dict) of one review table; the header must match exactly."""
    try:
        handle = path.open(encoding="utf-8", newline="")
    except OSError as error:
        raise ProperNameError(f"cannot read {path}: {error}") from error
    with handle:
        header_seen = False
        for line_number, line in enumerate(handle, start=1):
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if not header_seen:
                if fields != list(header):
                    raise ProperNameError(
                        f"{path}:{line_number}: unexpected columns {fields}"
                    )
                header_seen = True
                continue
            if len(fields) != len(header):
                raise ProperNameError(
                    f"{path}:{line_number}: expected {len(header)} columns, "
                    f"got {len(fields)}"
                )
            yield line_number, dict(zip(header, fields))
        if not header_seen:
            raise ProperNameError(f"{path}: the header row is missing")


def _check_review(path: Path, line_number: int, row: dict[str, str]) -> None:
    metadata = ReviewMetadata(
        approved=row["approved"].strip(),
        reviewer=row["reviewer"].strip(),
        review_date=row["review_date"].strip(),
        note=row["note"].strip(),
    )
    reason = dict_accept.review_metadata_reason(metadata)  # type: ignore[arg-type]
    if reason:
        raise ProperNameError(f"{path}:{line_number}: {reason}")


def display_form_reason(key: str, display: str) -> str:
    """Why this key/display pair is invalid; an empty string when it is valid.

    The key is a canonical NFC lowercase Tatar word with the internal-hyphen grammar. The
    display is NFC, non-empty, its lowercase equals the key, and it consists of Tatar
    alphabet letters plus U+002D only, so no apostrophe or other dash can appear.
    """
    alphabet = coverage.TATAR_ALPHABET
    normalized, reason = coverage.normalize_word(
        key, alphabet, allow_internal_hyphen=True
    )
    if reason is not None or normalized != key:
        return f"key {key!r} is not canonical ({reason or 'not NFC/lowercase'})"
    if not display:
        return f"key {key!r}: the display form is empty"
    if display != unicodedata.normalize("NFC", display):
        return f"key {key!r}: the display form is not NFC"
    if any(character not in alphabet and character != "-" for character in display.lower()):
        return (
            f"key {key!r}: display {display!r} has characters outside the Tatar "
            "alphabet and U+002D"
        )
    if display.lower() != key:
        return f"key {key!r}: display {display!r} does not lowercase to the key"
    return ""


def read_proper_name_review(path: Path) -> dict[str, str]:
    """The approved key -> display forms of the proper-name review, duplicates rejected."""
    forms: dict[str, str] = {}
    for line_number, row in _read_rows(path, PROPER_NAME_REVIEW_HEADER):
        key = row["normalized_form"]
        display = row["display_form"]
        if key in forms:
            raise ProperNameError(f"{path}:{line_number}: duplicate key {key!r}")
        reason = display_form_reason(key, display)
        if reason:
            raise ProperNameError(f"{path}:{line_number}: {reason}")
        _check_review(path, line_number, row)
        forms[key] = display
    return forms


def read_compound_review(path: Path) -> CompoundReview:
    """The reviewed compound table: canonical word, positive frequency, unique rows.

    A non-empty ``proper_display`` is the approved display form of a proper compound and
    follows the same display rule as the proper-name review.
    """
    frequencies: dict[str, int] = {}
    proper_displays: dict[str, str] = {}
    for line_number, row in _read_rows(path, COMPOUND_REVIEW_HEADER):
        word = row["word"]
        if word in frequencies:
            raise ProperNameError(f"{path}:{line_number}: duplicate word {word!r}")
        normalized, reason = coverage.normalize_word(
            word, coverage.TATAR_ALPHABET, allow_internal_hyphen=True
        )
        if reason is not None or normalized != word:
            raise ProperNameError(
                f"{path}:{line_number}: word {word!r} is not canonical "
                f"({reason or 'not NFC/lowercase'})"
            )
        frequency_text = row["frequency"]
        if not frequency_text or any(
            character < "0" or character > "9" for character in frequency_text
        ):
            raise ProperNameError(
                f"{path}:{line_number}: frequency {frequency_text!r} is not ASCII decimal"
            )
        frequency = int(frequency_text)
        if not 0 < frequency <= 0xFFFF_FFFF:
            raise ProperNameError(
                f"{path}:{line_number}: frequency {frequency} is not a positive u32"
            )
        _check_review(path, line_number, row)
        display = row["proper_display"]
        if display:
            display_reason = display_form_reason(word, display)
            if display_reason:
                raise ProperNameError(f"{path}:{line_number}: {display_reason}")
            proper_displays[word] = display
        frequencies[word] = frequency
    return CompoundReview(frequencies=frequencies, proper_displays=proper_displays)


def collect_display_forms(
    proper_name_review: Path, compound_review: Path
) -> dict[str, str]:
    """Every reviewed display form: proper names plus proper compounds, no duplicate keys."""
    forms = read_proper_name_review(proper_name_review)
    compounds = read_compound_review(compound_review)
    for key, display in compounds.proper_displays.items():
        if key in forms:
            raise ProperNameError(
                f"{compound_review}: key {key!r} is also in {proper_name_review}"
            )
        forms[key] = display
    return forms


def serialize_display_forms(forms: dict[str, str]) -> bytes:
    """The asset bytes: the comment header, then key<TAB>display rows sorted by key."""
    lines = [ASSET_HEADER]
    lines.extend(f"{key}\t{forms[key]}\n" for key in sorted(forms))
    return "".join(lines).encode("utf-8")


def parse_asset(data: bytes, source: str) -> dict[str, str]:
    """Parse and validate an asset; comment and blank lines are ignored."""
    try:
        text = data.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise ProperNameError(f"{source}: not valid UTF-8: {error}") from error
    forms: dict[str, str] = {}
    previous_key: str | None = None
    for line_number, line in enumerate(text.splitlines(), start=1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 2:
            raise ProperNameError(
                f"{source}:{line_number}: expected key<TAB>display, got {len(fields)} fields"
            )
        key, display = fields
        if key in forms:
            raise ProperNameError(f"{source}:{line_number}: duplicate key {key!r}")
        if previous_key is not None and key < previous_key:
            raise ProperNameError(f"{source}:{line_number}: rows are not sorted by key")
        reason = display_form_reason(key, display)
        if reason:
            raise ProperNameError(f"{source}:{line_number}: {reason}")
        forms[key] = display
        previous_key = key
    if not forms:
        raise ProperNameError(f"{source}: no display rows")
    return forms


def build_asset(proper_name_review: Path, compound_review: Path) -> bytes:
    """The deterministic asset bytes of the two review tables."""
    return serialize_display_forms(collect_display_forms(proper_name_review, compound_review))


def missing_dictionary_keys(forms: dict[str, str], dictionary_path: Path) -> list[str]:
    """The display keys absent from the Tatar dictionary, sorted."""
    parsed = dictionary_pack.validate_asset(dictionary_path.read_bytes())
    return sorted(set(forms) - set(parsed.words))


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--review", type=Path, default=ROOT / PROPER_NAME_REVIEW,
                        help="the proper-name review table (default %(default)s)")
    parser.add_argument("--compounds", type=Path, default=ROOT / COMPOUND_REVIEW,
                        help="the compound review table (default %(default)s)")
    sub = parser.add_subparsers(dest="command", required=True)
    build = sub.add_parser("build", help="write the asset from the review tables")
    build.add_argument("--output", type=Path, default=ROOT / PROPER_NAME_ASSET,
                       help="where to write the asset (default %(default)s)")
    check = sub.add_parser("check", help="verify the asset against the review tables")
    check.add_argument("--asset", type=Path, default=ROOT / PROPER_NAME_ASSET,
                       help="the asset to verify (default %(default)s)")
    check.add_argument("--dictionary", type=Path, default=None,
                       help="also require every key to be in this Tatar dictionary asset")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    for path in (args.review, args.compounds):
        if not path.is_file():
            print(f"error: missing input {path}", file=sys.stderr)
            return 2
    try:
        expected = build_asset(args.review, args.compounds)
        if args.command == "build":
            dict_accept._atomic_write(args.output, expected)
            print(f"{args.output}: {len(expected)} bytes", file=sys.stderr)
            return 0
        if not args.asset.is_file():
            print(f"error: missing asset {args.asset}", file=sys.stderr)
            return 2
        actual = args.asset.read_bytes()
        if actual != expected:
            print(
                f"error: {args.asset} does not match the review tables; regenerate it with "
                "`python3 scripts/proper_name_pack.py build`",
                file=sys.stderr,
            )
            parse_asset(actual, str(args.asset))
            return 1
        if args.dictionary is not None:
            if not args.dictionary.is_file():
                print(f"error: missing dictionary {args.dictionary}", file=sys.stderr)
                return 2
            absent = missing_dictionary_keys(
                parse_asset(actual, str(args.asset)), args.dictionary
            )
            if absent:
                print(
                    f"error: {len(absent)} keys are not in {args.dictionary}: "
                    + ", ".join(absent[:10]),
                    file=sys.stderr,
                )
                return 1
    except ProperNameError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(f"{args.asset}: ok", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
