#!/usr/bin/env python3
"""Word-frequency extraction from the Taiga social-media segment (the Russian P3 round).

Subcommand ``taiga`` reads one Taiga social CoNLL file (fbtexts, twtexts or vktexts): the
raw text lives on the ``# text = `` comment lines; the token rows are not consulted, so the
morphological annotation cannot leak into the counts. Tokenization is the shared dict_tokens
rule (whitespace split, edge punctuation stripped, ``normalize_word``) with the RUSSIAN
alphabet passed explicitly: the Tatar default is a superset and would let Tatar letters
through. Latin-script tokens, URLs, mentions and the ``DataBaseItem: <id>`` separators all
fail the alphabet check and drop out; the report carries the per-reason drop counts next to
the raw and kept token totals.

Output: a word<TAB>frequency TSV (frequency descending, then code point order) and a JSON
report. stdlib only.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
sys.path.insert(0, str(SCRIPT_DIR.parents[1] / "scripts"))

import dictionary_coverage as coverage  # noqa: E402
from make_eval_set import EDGE_CHARS  # noqa: E402

TEXT_PREFIX = "# text = "


def ru_tokens(text: str, dropped: Counter[str] | None = None) -> list[str]:
    """Whitespace tokens normalized by the shared dict_tokens rule, Russian alphabet.

    When [dropped] is given, the ``normalize_word`` reason of every rejected token is
    counted into it.
    """
    words = []
    for chunk in text.split():
        word = chunk.strip(EDGE_CHARS)
        if not word:
            continue
        normalized, reason = coverage.normalize_word(word, coverage.RUSSIAN.alphabet)
        if normalized is None:
            if dropped is not None:
                dropped[reason or "unknown"] += 1
            continue
        words.append(normalized)
    return words


def iter_taiga_texts(path: Path):
    """The ``# text = `` payloads of a Taiga social CoNLL file, one per sentence block."""
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            if line.startswith(TEXT_PREFIX):
                yield line[len(TEXT_PREFIX):]


def extract(args) -> int:
    stats: Counter = Counter()
    dropped: Counter[str] = Counter()
    frequencies: Counter = Counter()
    for text in iter_taiga_texts(args.input):
        stats["text_lines_read"] += 1
        words = ru_tokens(text, dropped)
        stats["tokens_kept"] += len(words)
        frequencies.update(words)
    stats["tokens_dropped"] = sum(dropped.values())
    stats["tokens_raw"] = stats["tokens_kept"] + stats["tokens_dropped"]
    report = dict(stats)
    report["dropped_by_reason"] = dict(sorted(dropped.items()))
    entries = coverage.sorted_entries(frequencies)
    coverage.write_entries(args.output, entries)
    report["unique_words"] = len(entries)
    report["output"] = str(args.output)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    child = sub.add_parser("taiga", help="extract word frequencies from a Taiga social CoNLL file")
    child.add_argument("--input", type=Path, required=True)
    child.add_argument("--output", type=Path, required=True)
    child.add_argument("--report", type=Path, required=True)
    child.set_defaults(func=extract)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
