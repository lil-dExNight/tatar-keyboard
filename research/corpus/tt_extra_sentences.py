#!/usr/bin/env python3
"""Sentence corpora from the extra Tatar sources, in the Leipzig ``id<TAB>sentence`` shape.

The sources (read through the ``tt_extra_freq.py`` iterators, which also strip the wiki
markup): the tt.wikipedia.org XML dump (ns-0 pages), the MADLAD-400 tt clean split (gzip
JSON lines) and the language-filtered HPLT 2.0 tat_Cyrl plain-text dump, one document per
line. Each document is split into sentences, Russian-bleed sentences are dropped, exact
sentences are deduplicated, the survivors are renumbered 1..N and written as strict
``id<TAB>sentence`` rows for ``scripts/bigram_asset_pack.py``.

Sentence rule: a document breaks at real newlines and at the literal two-character sequence
``\\n`` that the HPLT and MADLAD exports carry; inside a paragraph a sentence ends at ``.``,
``!``, ``?`` or ``…`` plus any immediately following closing quotes or brackets, when the
next character is whitespace or the paragraph ends. A dot followed by a digit does not
break, so dates like ``16.04.2019`` stay whole; a sentence-final abbreviation breaks, as it
does in the Leipzig corpora. Sentences keep their punctuation: the packer's tokenizer
rejects a token that carries it, and that loss is the packer's documented rule, applied
uniformly to every corpus.

Bleed rule: the sentence's tokens are the ``tt_extra_freq.dict_tokens`` output (whitespace
split, edge punctuation stripped, ``normalize_word``); each token is classified by
``tt_extra_freq.bleed_class`` against the same reference layers as the dictionary round
(the two Leipzig tt word lists, the conversational frequency file and the shipped Tatar
dictionary on one side, the three Leipzig ru word lists on the other). A sentence is dropped
when the ``russian_letter`` plus ``russian_only`` share of its tokens exceeds
``--max-ru-share``: the packer counts only pairs of shipped-dictionary words, and common
Russian words sit in the dictionary precisely because Tatar text uses them, so a mostly
Russian sentence would train Russian pairs. A single Russian loanword inside a Tatar
sentence stays.

Dedup: exact, on the sentence text, with the ``bigset.line_key`` 64-bit BLAKE2b digests of
``make_conv_train.py``. ``--seen-in``/``--seen-out`` chain a digest file across sources, so
a sentence shared by two sources is credited to the earlier one; the fixed source order is
ttwiki, madlad, hplt.

Thinning to the training weight is a separate step over the output ids (keep ``id % K ==
0``), the same hand filter as the conversational files. The report carries the kept token
mass the K is chosen from.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from array import array
from collections import Counter
from fractions import Fraction
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
sys.path.insert(0, str(SCRIPT_DIR.parents[1] / "scripts"))

import dictionary_coverage as coverage  # noqa: E402
from bigset import HashSet64, line_key  # noqa: E402
from make_eval_set import EDGE_CHARS  # noqa: E402
from tt_extra_freq import (  # noqa: E402
    bleed_class,
    iter_madlad_lines,
    iter_plain_lines,
    iter_ttwiki_lines,
    read_dictionary_words,
    read_word_set,
)

SENTENCE_FINAL = frozenset(".!?…")
CLOSERS = frozenset("\"'»“”’)]}")
RUSSIAN_CLASSES = frozenset(("russian_letter", "russian_only"))

# Bucket edges of the per-sentence Russian-token share histogram in the report.
SHARE_BUCKETS = (Fraction(0), Fraction(1, 10), Fraction(1, 4), Fraction(1, 2),
                 Fraction(3, 4))


def split_sentences(text: str) -> list[str]:
    """Sentences of one document, by the rule in the module docstring."""
    sentences: list[str] = []
    for paragraph in text.replace("\\n", "\n").split("\n"):
        start = 0
        index = 0
        size = len(paragraph)
        while index < size:
            if paragraph[index] not in SENTENCE_FINAL:
                index += 1
                continue
            end = index + 1
            while end < size and paragraph[end] in CLOSERS:
                end += 1
            if end < size and not paragraph[end].isspace():
                index += 1
                continue
            sentence = " ".join(paragraph[start:end].split())
            if sentence:
                sentences.append(sentence)
            index = end
            start = end
        tail = " ".join(paragraph[start:].split())
        if tail:
            sentences.append(tail)
    return sentences


def share_bucket(russian: int, total: int) -> str:
    share = Fraction(russian, total)
    for edge in SHARE_BUCKETS:
        if share <= edge:
            return f"<= {edge.numerator}/{edge.denominator}"
    return "<= 1/1"


def sentence_tokens(sentence: str, cache: dict[str, str | None]) -> list[str]:
    """The ``tt_extra_freq.dict_tokens`` rule with a per-process memo cache.

    The cache key is the raw whitespace chunk; the cached value is the normalized word or
    None. The rule itself is unchanged: strip the edge characters, then ``normalize_word``.
    The cache is cleared when it grows past the cap, so a long run stays in memory.
    """
    words: list[str] = []
    for chunk in sentence.split():
        if chunk in cache:
            word = cache[chunk]
        else:
            stripped = chunk.strip(EDGE_CHARS)
            word = coverage.normalize_word(stripped)[0] if stripped else None
            if len(cache) >= 4_000_000:
                cache.clear()
            cache[chunk] = word
        if word is not None:
            words.append(word)
    return words


def iter_documents(command: str, path: Path, counters: Counter):
    if command == "madlad":
        yield from iter_madlad_lines(path)
    elif command == "ttwiki":
        yield from iter_ttwiki_lines(path, counters)
    else:
        yield from iter_plain_lines(path)


def build(args) -> int:
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

    seen_keys = array("q")
    if args.seen_in is not None:
        with args.seen_in.open("rb") as stream:
            seen_keys.frombytes(stream.read())
    seen = HashSet64(len(seen_keys) + 1)
    for key in seen_keys:
        seen.add(key)
    max_share = Fraction(args.max_ru_share)

    counters: Counter = Counter()
    stats: dict[str, object] = {
        "input": str(args.input),
        "output": str(args.output),
        "max_ru_share": f"{max_share.numerator}/{max_share.denominator}",
        "documents_read": 0,
        "sentences_split": 0,
        "dropped_empty": 0,
        "dropped_bleed": 0,
        "dropped_duplicate": 0,
        "kept": 0,
        "kept_tokens": 0,
        "kept_tokens_russian": 0,
        "ru_share_histogram": {
            **{f"<= {e.numerator}/{e.denominator}": 0 for e in SHARE_BUCKETS},
            "<= 1/1": 0,
        },
    }
    digest = hashlib.sha256()
    written = 0
    token_cache: dict[str, str | None] = {}
    class_cache: dict[str, str] = {}
    with args.output.open("w", encoding="utf-8", newline="\n") as out:
        for document in iter_documents(args.command, args.input, counters):
            stats["documents_read"] += 1
            for sentence in split_sentences(document):
                stats["sentences_split"] += 1
                tokens = sentence_tokens(sentence, token_cache)
                if not tokens:
                    stats["dropped_empty"] += 1
                    continue
                russian = 0
                for token in tokens:
                    klass = class_cache.get(token)
                    if klass is None:
                        klass = bleed_class(token, tatar_refs_frozen, russian_refs_frozen)
                        if len(class_cache) >= 4_000_000:
                            class_cache.clear()
                        class_cache[token] = klass
                    if klass in RUSSIAN_CLASSES:
                        russian += 1
                stats["ru_share_histogram"][share_bucket(russian, len(tokens))] += 1  # type: ignore[index]
                if Fraction(russian, len(tokens)) > max_share:
                    stats["dropped_bleed"] += 1
                    continue
                key = line_key(sentence)
                if not seen.add(key):
                    stats["dropped_duplicate"] += 1
                    continue
                seen_keys.append(key)
                written += 1
                stats["kept"] += 1
                stats["kept_tokens"] += len(tokens)
                stats["kept_tokens_russian"] += russian
                row = f"{written}\t{sentence}\n"
                digest.update(row.encode("utf-8"))
                out.write(row)
    for key in ("pages_read", "pages_kept"):
        if counters.get(key):
            stats[key] = counters[key]
    stats["output_bytes"] = args.output.stat().st_size
    stats["output_sha256"] = digest.hexdigest()
    if args.seen_out is not None:
        with args.seen_out.open("wb") as stream:
            seen_keys.tofile(stream)
        stats["seen_out"] = str(args.seen_out)
        stats["seen_sentences_total"] = len(seen)
    args.report.write_text(
        json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("ttwiki", "madlad", "hplt"):
        child = sub.add_parser(name, help=f"sentence-split the {name} source")
        child.add_argument("--input", type=Path, required=True)
        child.add_argument("--output", type=Path, required=True)
        child.add_argument("--report", type=Path, required=True)
        child.add_argument("--tatar-refs", type=Path, nargs="*", default=[])
        child.add_argument("--tatar-dict-asset", type=Path, default=None)
        child.add_argument("--russian-refs", type=Path, nargs="*", default=[])
        child.add_argument(
            "--max-ru-share",
            default="1/2",
            help="drop a sentence whose Russian-classified token share exceeds this "
            "fraction (default %(default)s)",
        )
        child.add_argument(
            "--seen-in", type=Path, default=None,
            help="64-bit digest file of the sentences earlier sources already emitted",
        )
        child.add_argument(
            "--seen-out", type=Path, default=None,
            help="where to write the digest file for the next source",
        )
        child.set_defaults(func=build)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
