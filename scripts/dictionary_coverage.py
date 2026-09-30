#!/usr/bin/env python3
"""Merge Leipzig word-frequency files and report dictionary token coverage.

It accepts the canonical Leipzig format (numeric id, word, frequency) and the
reduced format (word, frequency), both tab-separated. Also the shared home of
``normalize_word`` and the per-language alphabets used by the other packers.
"""

from __future__ import annotations

import argparse
import gzip
import json
import sys
import unicodedata
from collections import Counter
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable, Sequence, TextIO


DEFAULT_CUTOFFS = (100_000, 150_000, 250_000)
TATAR_ALPHABET = frozenset("аәбвгдеёжҗзийклмнңоөпрстуүфхһцчшщъыьэюя")
TATAR_SPECIFIC = frozenset("әөүҗңһ")
RUSSIAN_ALPHABET = frozenset("абвгдеёжзийклмнопрстуфхцчшщъыьэюя")
RUSSIAN_SPECIFIC = frozenset("ёъ")
MAX_WORD_LENGTH = 64


@dataclass(frozen=True)
class Language:
    """One packable language: its tag, its accepted alphabet and its marker letters.

    ``alphabet`` is the only thing that decides which corpus rows survive filtering, and it is
    the same set the Kotlin validator enforces on the packed asset. ``specific`` is reporting
    only: the letters whose presence marks a word as characteristic of this language rather
    than of the shared Cyrillic core (Tatar's six extra letters; Russian's «ё» and «ъ», which no
    Tatar word carries in the same role).
    """

    tag: str
    display: str
    alphabet: frozenset[str]
    specific: frozenset[str]


TATAR = Language("tat", "Tatar", TATAR_ALPHABET, TATAR_SPECIFIC)
RUSSIAN = Language("rus", "Russian", RUSSIAN_ALPHABET, RUSSIAN_SPECIFIC)
LANGUAGES = {language.tag: language for language in (TATAR, RUSSIAN)}
DEFAULT_LANGUAGE = TATAR


def language_for(tag: str) -> Language:
    """The [Language] registered under [tag]; raises ``KeyError`` for an unknown tag."""
    return LANGUAGES[tag]


class MalformedRowError(ValueError):
    """A structurally invalid Leipzig row."""


class NoUsableWordsError(ValueError):
    """The inputs contained no word that survived validation and filtering."""


@dataclass
class SourceStats:
    path: str
    rows_read: int = 0
    rows_accepted: int = 0
    rows_malformed: int = 0
    rows_filtered: int = 0
    parsed_tokens: int = 0
    accepted_tokens: int = 0
    filtered_reasons: Counter[str] = field(default_factory=Counter)

    def to_dict(self) -> dict[str, object]:
        result = asdict(self)
        result["filtered_reasons"] = dict(sorted(self.filtered_reasons.items()))
        return result


def normalize_word(
    raw_word: str, alphabet: frozenset[str] = TATAR_ALPHABET
) -> tuple[str | None, str | None]:
    """Return a normalized Cyrillic word of [alphabet], or a filtering reason."""
    word = unicodedata.normalize("NFC", raw_word.strip()).lower()
    if not word:
        return None, "empty_word"
    if len(word) > MAX_WORD_LENGTH:
        return None, "too_long"
    if any(character not in alphabet for character in word):
        return None, (
            "outside_tatar_alphabet"
            if alphabet is TATAR_ALPHABET
            else "outside_alphabet"
        )
    return word, None


def _parse_positive_ascii_decimal(
    value_text: str, source: str, line_number: int, field: str
) -> int:
    if not value_text or any(
        character < "0" or character > "9" for character in value_text
    ):
        raise MalformedRowError(
            f"{source}:{line_number}: {field} must contain only ASCII decimal digits"
        )
    value = int(value_text)
    if value <= 0:
        raise MalformedRowError(
            f"{source}:{line_number}: {field} must be positive"
        )
    return value


def parse_row(line: str, source: str, line_number: int) -> tuple[str, int]:
    """Parse either id<TAB>word<TAB>frequency or word<TAB>frequency."""
    fields = line.rstrip("\r\n").split("\t")
    if len(fields) == 3:
        identifier, word, frequency_text = fields
        _parse_positive_ascii_decimal(identifier, source, line_number, "id")
    elif len(fields) == 2:
        word, frequency_text = fields
    else:
        raise MalformedRowError(
            f"{source}:{line_number}: expected 2 or 3 tab-separated fields, "
            f"got {len(fields)}"
        )

    frequency = _parse_positive_ascii_decimal(
        frequency_text, source, line_number, "frequency"
    )
    return word, frequency


def read_source(
    stream: TextIO,
    source: str,
    frequencies: Counter[str],
    *,
    skip_malformed: bool,
    alphabet: frozenset[str] = TATAR_ALPHABET,
) -> SourceStats:
    stats = SourceStats(path=source)
    for line_number, line in enumerate(stream, start=1):
        if not line.strip():
            continue
        stats.rows_read += 1
        try:
            raw_word, frequency = parse_row(line, source, line_number)
        except MalformedRowError:
            stats.rows_malformed += 1
            if not skip_malformed:
                raise
            continue

        stats.parsed_tokens += frequency
        word, reason = normalize_word(raw_word, alphabet)
        if reason is not None:
            stats.rows_filtered += 1
            stats.filtered_reasons[reason] += 1
            continue

        assert word is not None
        stats.rows_accepted += 1
        stats.accepted_tokens += frequency
        frequencies[word] += frequency
    return stats


def sorted_entries(frequencies: Counter[str]) -> list[tuple[str, int]]:
    """Sort deterministically: descending frequency, then Unicode word order."""
    return sorted(frequencies.items(), key=lambda item: (-item[1], item[0]))


def serialized_bytes(entries: Iterable[tuple[str, int]]) -> bytes:
    """Serialize the word<TAB>frequency<LF> form as UTF-8."""
    return b"".join(
        f"{word}\t{frequency}\n".encode("utf-8") for word, frequency in entries
    )


def packed_nul_u32_size(entries: Iterable[tuple[str, int]]) -> int:
    """Estimate packed UTF-8 word + NUL + uint32 frequency bytes, without an index."""
    return sum(len(word.encode("utf-8")) + 1 + 4 for word, _ in entries)


def build_report(
    frequencies: Counter[str],
    source_stats: Sequence[SourceStats],
    cutoffs: Sequence[int],
    language: Language = DEFAULT_LANGUAGE,
) -> tuple[dict[str, object], list[tuple[str, int]]]:
    entries = sorted_entries(frequencies)
    accepted_tokens = sum(frequencies.values())
    parsed_tokens = sum(stats.parsed_tokens for stats in source_stats)
    cumulative_tokens = 0
    coverage_by_rank: dict[int, int] = {}
    cutoff_set = set(cutoffs)
    for rank, (_, frequency) in enumerate(entries, start=1):
        cumulative_tokens += frequency
        if rank in cutoff_set:
            coverage_by_rank[rank] = cumulative_tokens

    cutoff_reports = []
    for requested_rank in cutoffs:
        selected = min(requested_rank, len(entries))
        covered_tokens = (
            coverage_by_rank[requested_rank]
            if requested_rank <= len(entries)
            else accepted_tokens
        )
        selected_entries = entries[:selected]
        tsv_bytes = serialized_bytes(selected_entries)
        cutoff_reports.append(
            {
                "requested_rank": requested_rank,
                "selected_words": selected,
                "covered_tokens": covered_tokens,
                "token_coverage": covered_tokens / accepted_tokens if accepted_tokens else 0.0,
                "serialized_tsv_bytes": len(tsv_bytes),
                "gzip_tsv_bytes": len(gzip.compress(tsv_bytes, compresslevel=9, mtime=0)),
                "packed_nul_u32_bytes": packed_nul_u32_size(selected_entries),
                "packed_nul_u32_plus_offsets_bytes": (
                    packed_nul_u32_size(selected_entries) + 4 * selected
                ),
                "boundary_frequency": selected_entries[-1][1] if selected_entries else None,
                "boundary_word": selected_entries[-1][0] if selected_entries else None,
            }
        )

    specific_letters = language.specific
    specific_words = sum(
        1 for word, _ in entries if any(letter in word for letter in specific_letters)
    )
    specific_tokens = sum(
        frequency
        for word, frequency in entries
        if any(letter in word for letter in specific_letters)
    )
    total_accepted_rows = sum(stats.rows_accepted for stats in source_stats)
    report: dict[str, object] = {
        "schema_version": 2,
        "normalization": {
            "unicode": "NFC",
            "case": "Unicode lowercase",
            "alphabet": f"{language.display} Cyrillic letters only",
            "language": language.tag,
            "max_word_length": MAX_WORD_LENGTH,
            "tie_break": "descending frequency, then ascending Unicode word order",
        },
        "sources": [stats.to_dict() for stats in source_stats],
        "totals": {
            "source_count": len(source_stats),
            "rows_read": sum(stats.rows_read for stats in source_stats),
            "rows_accepted": total_accepted_rows,
            "rows_malformed": sum(stats.rows_malformed for stats in source_stats),
            "rows_filtered": sum(stats.rows_filtered for stats in source_stats),
            "parsed_tokens": parsed_tokens,
            "accepted_tokens": accepted_tokens,
            "accepted_token_ratio": accepted_tokens / parsed_tokens if parsed_tokens else 0.0,
            "unique_words": len(entries),
            "duplicates_merged": total_accepted_rows - len(entries),
            "hapax_words": sum(1 for _, frequency in entries if frequency == 1),
            # Letters specific to the packed language (see Language.specific).
            "language_specific_letters": "".join(sorted(specific_letters)),
            "language_specific_words": specific_words,
            "language_specific_tokens": specific_tokens,
            "language_specific_word_ratio": specific_words / len(entries) if entries else 0.0,
            "language_specific_token_ratio": specific_tokens / accepted_tokens if accepted_tokens else 0.0,
            "all_words_serialized_tsv_bytes": len(serialized_bytes(entries)),
            "all_words_packed_nul_u32_bytes": packed_nul_u32_size(entries),
            "all_words_packed_nul_u32_plus_offsets_bytes": (
                packed_nul_u32_size(entries) + 4 * len(entries)
            ),
        },
        "cutoffs": cutoff_reports,
    }
    return report, entries


def parse_cutoffs(raw_cutoffs: str) -> tuple[int, ...]:
    try:
        cutoffs = tuple(sorted({int(value) for value in raw_cutoffs.split(",")}))
    except ValueError as error:
        raise argparse.ArgumentTypeError("cutoffs must be comma-separated integers") from error
    if not cutoffs or any(cutoff <= 0 for cutoff in cutoffs):
        raise argparse.ArgumentTypeError("cutoffs must be positive")
    return cutoffs


def write_entries(path: Path, entries: Iterable[tuple[str, int]]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for word, frequency in entries:
            stream.write(f"{word}\t{frequency}\n")


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="Leipzig *-words.txt files")
    parser.add_argument(
        "--language",
        choices=sorted(LANGUAGES),
        default=DEFAULT_LANGUAGE.tag,
        help="alphabet to filter by (default: tat)",
    )
    parser.add_argument(
        "--cutoffs",
        type=parse_cutoffs,
        default=DEFAULT_CUTOFFS,
        help="comma-separated ranks (default: 100000,150000,250000)",
    )
    parser.add_argument(
        "--skip-malformed",
        action="store_true",
        help="count and skip malformed rows instead of failing",
    )
    parser.add_argument(
        "--output-words",
        type=Path,
        help="optional local merged word-frequency TSV; do not commit licensed data",
    )
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="pretty-print JSON output",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    language = language_for(args.language)
    frequencies: Counter[str] = Counter()
    sources: list[SourceStats] = []
    try:
        for path in args.inputs:
            with path.open("r", encoding="utf-8-sig", newline="") as stream:
                sources.append(
                    read_source(
                        stream,
                        str(path),
                        frequencies,
                        skip_malformed=args.skip_malformed,
                        alphabet=language.alphabet,
                    )
                )
        if not frequencies:
            raise NoUsableWordsError(
                f"inputs contain no usable {language.display} words"
            )
        report, entries = build_report(frequencies, sources, args.cutoffs, language)
        if args.output_words:
            write_entries(args.output_words, entries)
    except (MalformedRowError, NoUsableWordsError, OSError, UnicodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    json.dump(
        report,
        sys.stdout,
        ensure_ascii=False,
        indent=2 if args.pretty else None,
        sort_keys=True,
    )
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
