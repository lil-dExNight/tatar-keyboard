#!/usr/bin/env python3
"""Word-frequency extraction from the extra Tatar corpora, plus the Russian-bleed filter.

Subcommands (all stdlib-only; the HPLT input is the plain-text intermediate written by
hplt_dump_to_text.py, never the parquet):

* ``madlad``: read the MADLAD-400 tt clean split (gzip JSON lines, one ``{"text": ...}``
  object per line; any other key set stops the run),
* ``ttwiki``: read a MediaWiki XML dump (bz2), take namespace-0 pages, extract the ``text``
  payloads and strip wiki markup minimally (templates, file/category links, ref tags, HTML
  tags and external links go away; plain link text stays; no attempt at table or list
  structure),
* ``hplt``: read the filtered plain-text intermediate, one document per line,
* ``glot500``: read the label-filtered plain-text intermediate of glot500_dump_to_text.py,
  one sentence-level row per line.

Tokenization is the shared dict_tokens rule (whitespace split, edge punctuation stripped,
``normalize_word`` with the Tatar alphabet), so counts are in the same word space as the
eval harness. Output per source: a word<TAB>frequency TSV (frequency descending, then code
point order) and a JSON report with per-reason drop counts.

``filter`` removes Russian bleed from an extracted list, per word:

1. a word with a Tatar-specific letter stays (``tatar_letter``);
2. a word with a Russian-specific letter (``ё``, ``ъ``) is dropped (``russian_letter``);
3. a word attested in the Tatar reference layer stays (``tatar_attested``);
4. a word attested in the Russian reference layer but not the Tatar one is dropped
   (``russian_only``);
5. anything else stays (``unattested``; reported separately).

The Tatar reference layer is the two Leipzig tt word lists, the conversational frequency
file and the shipped Tatar dictionary. The Russian reference layer is the three Leipzig ru
word lists. Rule 3 before rule 4 keeps genuine Russian loanwords that Tatar text uses.
"""
from __future__ import annotations

import argparse
import bz2
import gzip
import json
import sys
import unicodedata
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
sys.path.insert(0, str(SCRIPT_DIR.parents[1] / "scripts"))

import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack  # noqa: E402
from make_eval_set import EDGE_CHARS  # noqa: E402

MW_NS = "{http://www.mediawiki.org/xml/export-0.11/}"


def dict_tokens(text: str) -> list[str]:
    """Whitespace tokens normalized by the shared rule; non-words dropped."""
    words = []
    for chunk in text.split():
        word = chunk.strip(EDGE_CHARS)
        if not word:
            continue
        normalized, _reason = coverage.normalize_word(word)
        if normalized is not None:
            words.append(normalized)
    return words


def count_lines(lines, stats: Counter, frequencies: Counter) -> None:
    """Tokenize each line and add to the counters."""
    for line in lines:
        stats["lines_read"] += 1
        for word in dict_tokens(line):
            stats["tokens_accepted"] += 1
            frequencies[word] += 1


def iter_madlad_lines(path: Path):
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            row = json.loads(line)
            if set(row) != {"text"} or not isinstance(row["text"], str):
                raise ValueError(f"{path}:{number}: unexpected JSON shape {sorted(row)}")
            yield row["text"]


def strip_wiki_markup(text: str) -> str:
    """Minimal deterministic wiki markup removal; see the module docstring."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("{{", i):
            end = text.find("}}", i + 2)
            i = n if end < 0 else end + 2
        elif text.startswith("<!--", i):
            end = text.find("-->", i + 4)
            i = n if end < 0 else end + 3
        elif text.startswith("[[", i):
            end = text.find("]]", i + 2)
            if end < 0:
                i = n
                continue
            body = text[i + 2:end]
            label = body.rsplit("|", 1)[-1] if "|" in body else body
            lowered = body.split(":", 1)[0].strip().lower()
            if lowered in ("file", "image", "файл", "рәсем", "category", "категория"):
                pass  # file and category links carry no running text
            else:
                out.append(" " + label + " ")
            i = end + 2
        elif text.startswith("[", i) and not text.startswith("[[", i):
            end = text.find("]", i + 1)
            if end < 0:
                i = n
                continue
            body = text[i + 1:end]
            if body.startswith(("http://", "https://", "//")):
                parts = body.split(None, 1)
                if len(parts) == 2:
                    out.append(" " + parts[1] + " ")
            else:
                out.append(" " + body + " ")
            i = end + 1
        elif text[i] == "<":
            end = text.find(">", i + 1)
            if end < 0:
                i = n
                continue
            tag_text = text[i + 1:end].strip("/ ")
            if not tag_text:
                out.append(text[i])
                i += 1
                continue
            tag = tag_text.split(None, 1)[0].lower()
            if tag in ("ref", "gallery", "nowiki", "math", "score", "syntaxhighlight") and not text[i + 1:end].strip().endswith("/"):
                close = text.find(f"</{tag}>", end)
                i = n if close < 0 else close + len(tag) + 3
            else:
                i = end + 1  # keep the tagged content, drop the tag itself
        else:
            out.append(text[i])
            i += 1
    return "".join(out).replace("'''", "").replace("''", "")


def iter_ttwiki_lines(path: Path, stats: Counter):
    with bz2.open(path, "rb") as stream:
        context = ET.iterparse(stream, events=("end",))
        for _event, element in context:
            if element.tag == MW_NS + "page":
                ns = element.findtext(MW_NS + "ns")
                text = ""
                for revision in element.iter(MW_NS + "revision"):
                    text = revision.findtext(MW_NS + "text") or ""
                    break
                stats["pages_read"] += 1
                if ns == "0":
                    stats["pages_kept"] += 1
                    yield strip_wiki_markup(text)
                element.clear()


def iter_plain_lines(path: Path):
    with path.open("r", encoding="utf-8") as stream:
        yield from stream


def write_outputs(frequencies: Counter, stats: Counter, out: Path, report: Path) -> None:
    entries = coverage.sorted_entries(frequencies)
    coverage.write_entries(out, entries)
    stats["unique_words"] = len(entries)
    stats["tokens_total"] = sum(frequencies.values())
    stats["output"] = str(out)
    report.write_text(
        json.dumps(dict(stats), ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(dict(stats), ensure_ascii=False, indent=2, sort_keys=True))


def extract(args) -> int:
    stats: Counter = Counter()
    frequencies: Counter = Counter()
    if args.command == "madlad":
        count_lines(iter_madlad_lines(args.input), stats, frequencies)
    elif args.command == "ttwiki":
        count_lines(iter_ttwiki_lines(args.input, stats), stats, frequencies)
    else:
        count_lines(iter_plain_lines(args.input), stats, frequencies)
    write_outputs(frequencies, stats, args.output, args.report)
    return 0


# --- Russian-bleed filter ---------------------------------------------------------------------

TATAR_SPECIFIC = coverage.TATAR.specific
RUSSIAN_SPECIFIC = coverage.RUSSIAN.specific


def read_word_set(path: Path, alphabet) -> frozenset[str]:
    """Words of a Leipzig-format or word<TAB>freq TSV, normalized into the alphabet."""
    frequencies: Counter = Counter()
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        coverage.read_source(
            stream, str(path), frequencies, skip_malformed=True, alphabet=alphabet
        )
    return frozenset(frequencies)


def read_dictionary_words(asset: Path) -> frozenset[str]:
    parsed = dictionary_pack.validate_raw(dictionary_pack.decompress_asset(asset.read_bytes()))
    return frozenset(parsed.words)


def bleed_class(word: str, tatar_refs: frozenset[str], russian_refs: frozenset[str]) -> str:
    if any(letter in word for letter in TATAR_SPECIFIC):
        return "tatar_letter"
    if any(letter in word for letter in RUSSIAN_SPECIFIC):
        return "russian_letter"
    if word in tatar_refs:
        return "tatar_attested"
    if word in russian_refs:
        return "russian_only"
    return "unattested"


def filter_source(args) -> int:
    tatar_refs: set[str] = set()
    for path in args.tatar_refs:
        tatar_refs |= read_word_set(path, coverage.TATAR.alphabet)
    if args.tatar_dict_asset is not None:
        tatar_refs |= read_dictionary_words(args.tatar_dict_asset)
    russian_refs: set[str] = set()
    for path in args.russian_refs:
        russian_refs |= read_word_set(path, coverage.RUSSIAN.alphabet)
    tatar_refs_frozen = frozenset(tatar_refs)
    russian_refs_frozen = frozenset(russian_refs)

    kept: Counter = Counter()
    class_words: Counter = Counter()
    class_tokens: Counter = Counter()
    rows = 0
    with args.input.open("r", encoding="utf-8") as stream:
        for line in stream:
            if not line.strip():
                continue
            word, freq_text = line.rstrip("\n").split("\t")
            rows += 1
            klass = bleed_class(word, tatar_refs_frozen, russian_refs_frozen)
            class_words[klass] += 1
            class_tokens[klass] += int(freq_text)
            if klass not in ("russian_letter", "russian_only"):
                kept[word] += int(freq_text)
    stats: dict[str, object] = {
        "input": str(args.input),
        "tatar_ref_words": len(tatar_refs_frozen),
        "russian_ref_words": len(russian_refs_frozen),
        "rows_read": rows,
        "by_class": {
            klass: {"words": class_words[klass], "tokens": class_tokens[klass]}
            for klass in sorted(class_words)
        },
        "kept_words": len(kept),
        "kept_tokens": sum(kept.values()),
        "dropped_words": rows - len(kept),
        "dropped_tokens": sum(
            class_tokens[klass] for klass in ("russian_letter", "russian_only")
        ),
    }
    coverage.write_entries(args.output, coverage.sorted_entries(kept))
    args.report.write_text(
        json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("madlad", "ttwiki", "hplt", "glot500"):
        child = sub.add_parser(name, help=f"extract word frequencies from the {name} source")
        child.add_argument("--input", type=Path, required=True)
        child.add_argument("--output", type=Path, required=True)
        child.add_argument("--report", type=Path, required=True)
        child.set_defaults(func=extract)
    child = sub.add_parser("filter", help="drop Russian bleed from an extracted list")
    child.add_argument("--input", type=Path, required=True)
    child.add_argument("--output", type=Path, required=True)
    child.add_argument("--report", type=Path, required=True)
    child.add_argument("--tatar-refs", type=Path, nargs="*", default=[])
    child.add_argument("--tatar-dict-asset", type=Path, default=None)
    child.add_argument("--russian-refs", type=Path, nargs="*", default=[])
    child.set_defaults(func=filter_source)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
