#!/usr/bin/env python3
"""Build a sentence-start suggestion table (TT-SUGGESTIONS P4; ROADMAP P1 P3b).

The tool uses only the Python standard library. The inputs are Leipzig Corpora
Collection sentence files (CC BY 4.0, not committed) and the SHIPPED dictionary
asset of the same language. The output is a deterministic UTF-8/LF text asset
(data, not code): a ``#`` comment header with the attribution and the
provenance, then one ``word<TAB>freq`` row per record, sorted by frequency
descending, then word ascending. Built tables:
``tatar_sentstart_v1.txt`` (tat_mixed_2015_1M + tat_web_2018_1M) and, since
ROADMAP Phase 1, ``russian_sentstart_v1.txt`` (rus_news_2022_1M,
rus_news_2019_1M, rus_wikipedia_2021_1M) via ``--language rus``.

A record is a word seen as the FIRST token of a corpus sentence. The token is
taken with the exact normalization of the dictionary pipeline: surrounding
punctuation stripped (the ``dict_tokens`` rule), then
:func:`dictionary_coverage.normalize_word` (NFC, lowercase, Tatar alphabet
filter). The membership filter is the shipped dictionary itself, decoded with
the :mod:`dictionary_pack` reader APIs — deliberately the stricter choice over
the Leipzig ``*-words.txt`` lists: the table can then never offer a word the
keyboard does not itself know (a Leipzig-only form would be an uncompletable,
unrankable stranger to the engine), and the runtime contract "every
sentence-start cell is a dictionary word" is true by construction.

The generator is fail-closed. It exits with a nonzero status and writes no
partial asset when:

* an input is missing, not valid UTF-8, or a sentence row has no
  ``id<TAB>sentence`` shape,
* the dictionary asset fails to decode or validate,
* no first token survives normalization, or none of the survivors is a
  dictionary word, or
* a guardrail is breached (asset > MAX_ASSET_BYTES, or the record count leaves
  [MIN_RECORDS, MAX_RECORDS]).

The header carries no wall-clock fields: two builds over the same inputs are
byte-identical.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import tempfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack  # noqa: E402

# Characters that may legitimately hug a word at a sentence edge -- the exact
# rule of research/corpus/corpuslib.py (``_EDGE``), kept in sync by value, not
# by import, so scripts/ stays self-contained (the same convention
# scripts/make_eval_set.py documents for its own copy).
EDGE_CHARS = "\"'«»„“”‘’()[]{}<>.,!?;:…—–-*_/\\|~`^&#№%+=@$"

DEFAULT_TOP = 64

# Guardrails. A change to any number is a written decision, not a silent bump.
# The table is the top ~64 of a frequency list; a build that yields a handful
# of records or hundreds of them is a data accident, not a new distribution.
MAX_ASSET_BYTES = 16384
MIN_RECORDS = 16
MAX_RECORDS = 256

HEADER_LINES_TAT = (
    "# Tatar sentence-start suggestions, v1 (TT-SUGGESTIONS, phase P4).",
    "#",
    "# Source: sentence-initial tokens of the Leipzig Corpora Collection Tatar corpora",
    "# tat_mixed_2015_1M and tat_web_2018_1M",
    "# (https://wortschatz.uni-leipzig.de/en/download/tat).",
    "# License: Creative Commons Attribution 4.0 International (CC BY 4.0),",
    "# https://creativecommons.org/licenses/by/4.0/ -- see NOTICE.txt beside this file.",
    "#",
    "# Pipeline: the first whitespace token of every sentence, hugging punctuation",
    "# stripped (the dict_tokens rule), normalized exactly like the dictionary pipeline",
    "# (NFC, lowercase, Tatar alphabet filter), kept only when present in the shipped",
    "# Tatar dictionary -- the table can never offer a word the keyboard does not know.",
    "# Rows: word<TAB>sentence-initial occurrences, sorted by count descending, word",
    "# ascending.",
)

HEADER_LINES_RUS = (
    "# Russian sentence-start suggestions, v1 (ROADMAP Phase 1, P3b).",
    "#",
    "# Source: sentence-initial tokens of the Leipzig Corpora Collection Russian corpora",
    "# rus_news_2022_1M, rus_news_2019_1M and rus_wikipedia_2021_1M",
    "# (https://wortschatz.uni-leipzig.de/en/download/Russian).",
    "# License: Creative Commons Attribution 4.0 International (CC BY 4.0),",
    "# https://creativecommons.org/licenses/by/4.0/ -- see NOTICE.txt beside this file.",
    "#",
    "# Pipeline: the first whitespace token of every sentence, hugging punctuation",
    "# stripped (the dict_tokens rule), normalized exactly like the dictionary pipeline",
    "# (NFC, lowercase, Russian alphabet filter), kept only when present in the shipped",
    "# Russian dictionary -- the table can never offer a word the keyboard does not know.",
    "# Rows: word<TAB>sentence-initial occurrences, sorted by count descending, word",
    "# ascending.",
)

HEADER_LINES = {"tat": HEADER_LINES_TAT, "rus": HEADER_LINES_RUS}


class SentStartPackError(ValueError):
    """A fail-closed generator error (exit 2)."""


class SentStartGuardrailError(SentStartPackError):
    """A guardrail breach: too many bytes, or a suspicious record count (exit 4)."""


@dataclass(frozen=True)
class FirstTokenCounts:
    counts: Counter[str]
    rows_read: int
    tokens_accepted: int
    tokens_dropped: int


@dataclass(frozen=True)
class SentStartTable:
    text: str
    data: bytes
    record_count: int
    top_frequency: int
    bottom_frequency: int

    @property
    def byte_size(self) -> int:
        return len(self.data)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()


def read_first_tokens(
    paths: Sequence[Path], language: coverage.Language = coverage.TATAR
) -> FirstTokenCounts:
    """Count normalized sentence-initial tokens over the Leipzig sentence files.

    Every row must be ``id<TAB>sentence``; a row without exactly that shape is
    structural corruption and fails the build. The first whitespace token of the
    sentence is stripped of hugging punctuation and normalized with
    :func:`dictionary_coverage.normalize_word` under [language]'s alphabet;
    tokens it rejects are dropped.
    """
    counts: Counter[str] = Counter()
    rows_read = 0
    accepted = 0
    dropped = 0
    for path in paths:
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as error:
            raise SentStartPackError(f"{path.name}: input is not valid UTF-8") from error
        for lineno, raw_line in enumerate(text.split("\n"), start=1):
            line = raw_line.rstrip("\r")
            if not line:
                continue
            rows_read += 1
            if line.count("\t") != 1:
                raise SentStartPackError(
                    f"{path.name}:{lineno}: expected exactly one tab (id<TAB>sentence)"
                )
            _row_id, sentence = line.split("\t")
            chunks = sentence.split()
            if not chunks:
                dropped += 1
                continue
            token = chunks[0].strip(EDGE_CHARS)
            if not token:
                dropped += 1
                continue
            normalized, _reason = coverage.normalize_word(token, language.alphabet)
            if normalized is None:
                dropped += 1
                continue
            counts[normalized] += 1
            accepted += 1
    if rows_read == 0:
        raise SentStartPackError("the sentence inputs hold no rows")
    if accepted == 0:
        raise SentStartPackError("no sentence-initial token survived normalization")
    return FirstTokenCounts(counts, rows_read, accepted, dropped)


def read_dictionary_words(
    path: Path, language: coverage.Language = coverage.TATAR
) -> frozenset[str]:
    """The shipped dictionary's word list, decoded with the pack reader APIs.

    Decompression or validation failure is a build failure: the membership
    filter is the asset the runtime actually ships, never an approximation.
    """
    try:
        asset = path.read_bytes()
        raw = dictionary_pack.decompress_asset(asset, language)
        parsed = dictionary_pack.validate_raw(raw, language=language)
    except (OSError, dictionary_pack.DictionaryPackError) as error:
        raise SentStartPackError(f"{path.name}: cannot decode the dictionary: {error}") from error
    if not parsed.words:
        raise SentStartPackError(f"{path.name}: the dictionary holds no words")
    return frozenset(parsed.words)


def build_table(
    counts: Counter[str],
    dictionary_words: frozenset[str],
    *,
    top: int,
    max_bytes: int,
    min_records: int,
    max_records: int,
    language: coverage.Language = coverage.TATAR,
) -> SentStartTable:
    """Compose the deterministic asset: top [top] dictionary words by sentence-initial
    frequency, rows sorted by (count desc, word asc)."""
    if top <= 0:
        raise SentStartPackError(f"top must be positive, got {top}")
    eligible = [(word, count) for word, count in counts.items() if word in dictionary_words]
    if not eligible:
        raise SentStartPackError("no sentence-initial token is a shipped dictionary word")
    eligible.sort(key=lambda item: (-item[1], item[0]))
    chosen = eligible[:top]
    lines = list(HEADER_LINES[language.tag])
    lines.extend(f"{word}\t{count}" for word, count in chosen)
    text = "\n".join(lines) + "\n"
    data = text.encode("utf-8")
    table = SentStartTable(
        text=text,
        data=data,
        record_count=len(chosen),
        top_frequency=chosen[0][1],
        bottom_frequency=chosen[-1][1],
    )
    if table.byte_size > max_bytes:
        raise SentStartGuardrailError(
            f"asset is {table.byte_size} bytes, over the {max_bytes} guardrail"
        )
    if table.record_count > max_records:
        raise SentStartGuardrailError(
            f"asset has {table.record_count} records, over the {max_records} guardrail"
        )
    if table.record_count < min_records:
        raise SentStartGuardrailError(
            f"asset has {table.record_count} records, below the {min_records} floor"
        )
    return table


def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = tempfile.NamedTemporaryFile(
        dir=str(path.parent), delete=False, prefix=path.name, suffix=".tmp"
    )
    try:
        with handle:
            handle.write(data)
        Path(handle.name).replace(path)
    except BaseException:
        Path(handle.name).unlink(missing_ok=True)
        raise


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    build = subparsers.add_parser("build", help="build the sentence-start asset")
    build.add_argument("--sentences", type=Path, nargs="+", required=True,
                       help="Leipzig sentence files (id<TAB>sentence)")
    build.add_argument("--dictionary", type=Path, required=True,
                       help="the shipped dictionary asset (.tdict.zlib)")
    build.add_argument("--language", choices=sorted(coverage.LANGUAGES), default="tat",
                       help="the language of the table (default tat)")
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--top", type=int, default=DEFAULT_TOP,
                       help=f"records to keep (default {DEFAULT_TOP})")
    return parser


def _print_json(payload: dict) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        if args.command == "build":
            language = coverage.language_for(args.language)
            counts = read_first_tokens(args.sentences, language)
            dictionary_words = read_dictionary_words(args.dictionary, language)
            table = build_table(
                counts.counts,
                dictionary_words,
                top=args.top,
                max_bytes=MAX_ASSET_BYTES,
                min_records=MIN_RECORDS,
                max_records=MAX_RECORDS,
                language=language,
            )
            write_atomic(args.output, table.data)
            _print_json(
                {
                    "asset_bytes": table.byte_size,
                    "asset_sha256": table.sha256,
                    "bottom_frequency": table.bottom_frequency,
                    "dictionary_words": len(dictionary_words),
                    "language": language.tag,
                    "record_count": table.record_count,
                    "rows_read": counts.rows_read,
                    "tokens_accepted": counts.tokens_accepted,
                    "tokens_dropped": counts.tokens_dropped,
                    "top_frequency": table.top_frequency,
                }
            )
        else:  # pragma: no cover
            raise AssertionError(args.command)
    except SentStartGuardrailError as error:
        print(f"error: {error}", file=sys.stderr)
        return 4
    except (SentStartPackError, OSError, UnicodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
