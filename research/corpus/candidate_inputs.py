#!/usr/bin/env python3
"""Turn cleaned corpus streams into the candidate inputs of the Tatar asset rebuild.

Three inputs, each written deterministically so its SHA-256 can be pinned in
`data/corpus-manifest.json`:

- `sentences`: a thinned bigram-training file in the two-field Leipzig sentence format
  (`id<TAB>sentence`, the id being the source line number). Thinning keeps the lines whose BLAKE2b
  digest falls on a stride wide enough to bring the file from the source's token count to an
  explicit budget, so the weight of a source is its budget and the selection does not depend on
  key order.
- `words`: word<TAB>count over a sentence stream, restricted to the Tatar alphabet, for the
  dictionary admission stage.
- `community`: word<TAB>count over community documents (ads, dating posts, comments). Only
  sentences that survived the cleaning run are counted, matched back to their document by the
  evaluation-split sentence key, so the language, boilerplate, duplicate, evaluation-collision
  and chat-spelling routing rules of `clean_corpora.py` still apply. A word is kept only when it
  appears in at least MIN_DOCUMENTS distinct documents, has at least MIN_COUNT occurrences,
  carries no digits (phone and price strings), and is written in lowercase at least once: a
  string that never appears in lowercase is a name. Community sources contribute counts only:
  their sentences never enter bigram training, because a single ad or post is too small to make
  a pair count meaningful and can carry personal data.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Iterator, Sequence

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import corpus_cleaning as cleaning  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402

WORD_RE = re.compile(r"[^\W\d_]+", re.UNICODE)
MIN_DOCUMENTS = 3
MIN_COUNT = 3
ALPHABET = coverage.TATAR_ALPHABET


class CandidateInputError(ValueError):
    """A malformed source line or an input that would produce an empty output."""


def _digest(line: str) -> int:
    return int.from_bytes(hashlib.blake2b(line.encode("utf-8"), digest_size=8).digest(), "big")


def tokens(line: str) -> Iterator[str]:
    """Alphabetic tokens of a line, lowercased, Tatar alphabet only."""
    for match in WORD_RE.finditer(line.lower()):
        word = match.group(0)
        if ALPHABET.issuperset(word):
            yield word


def sentence_file(
    source: Path, budget_tokens: int, stride: int
) -> tuple[list[tuple[int, str]], dict]:
    """Keep every line whose digest is divisible by `stride`, with its source line number."""
    if stride < 1:
        raise CandidateInputError("stride must be positive")
    kept: list[tuple[int, str]] = []
    kept_tokens = 0
    source_tokens = 0
    with source.open("r", encoding="utf-8", newline="") as stream:
        for line_number, line in enumerate(stream, start=1):
            text = line.rstrip("\r\n")
            if not text:
                continue
            source_tokens += len(text.split())
            if _digest(text) % stride:
                continue
            kept.append((line_number, text))
            kept_tokens += len(text.split())
    report = {
        "path": str(source),
        "stride": stride,
        "source_tokens": source_tokens,
        "kept_lines": len(kept),
        "kept_tokens": kept_tokens,
        "budget_tokens": budget_tokens,
    }
    if not kept:
        raise CandidateInputError(f"{source}: the stride kept no line")
    return kept, report


def word_counts(path: Path) -> tuple[dict[str, int], dict]:
    counts: dict[str, int] = {}
    lines = 0
    with path.open("r", encoding="utf-8", newline="") as stream:
        for line in stream:
            lines += 1
            for word in tokens(line):
                counts[word] = counts.get(word, 0) + 1
    if not counts:
        raise CandidateInputError(f"{path}: no token in the Tatar alphabet")
    return counts, {"path": str(path), "lines": lines, "types": len(counts),
                    "tokens": sum(counts.values())}


def lexicon_counts(path: Path) -> tuple[dict[str, int], dict]:
    """A curated word list: one type per line, attested once."""
    counts: dict[str, int] = {}
    lines = 0
    with path.open("r", encoding="utf-8", newline="") as stream:
        for line in stream:
            lines += 1
            words = list(tokens(line))
            if len(words) != 1:
                continue
            counts[words[0]] = 1
    if not counts:
        raise CandidateInputError(f"{path}: no single-word line in the Tatar alphabet")
    return counts, {"path": str(path), "lines": lines, "types": len(counts)}


def community_counts(path: Path, cleaned_stream: Path) -> tuple[dict[str, int], dict]:
    """Counts from community documents under the three-document and name rules.

    `cleaned_stream` is the cleaning run's output for the same source: a document sentence is
    counted only when it is one of the cleaned lines.
    """
    kept_keys: set[str] = set()
    with cleaned_stream.open("r", encoding="utf-8", newline="") as stream:
        for line in stream:
            key = cleaning.normalize_line(line).lower()
            if key:
                kept_keys.add(key)
    documents: dict[str, int] = {}
    occurrence_lowercase: dict[str, int] = {}
    occurrence_total: dict[str, int] = {}
    document_count = 0
    with path.open("r", encoding="utf-8", newline="") as stream:
        lines = list(stream)
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            text = json.loads(line)["text"]
        except (ValueError, KeyError, TypeError) as error:
            raise CandidateInputError(f"{path}:{line_number}: {error}") from error
        kept = [
            line for line in cleaning.extract_lines(text)
            if cleaning.normalize_line(line).lower() in kept_keys
        ]
        if not kept:
            continue
        document_count += 1
        seen: set[str] = set()
        for line in kept:
            for word in WORD_RE.findall(line):
                lowered = word.lower()
                if not ALPHABET.issuperset(lowered):
                    continue
                seen.add(lowered)
                occurrence_total[lowered] = occurrence_total.get(lowered, 0) + 1
                if word[:1].islower():
                    occurrence_lowercase[lowered] = occurrence_lowercase.get(lowered, 0) + 1
        for word in seen:
            documents[word] = documents.get(word, 0) + 1
    counts: dict[str, int] = {}
    rejected = {"documents": 0, "count": 0, "name": 0}
    for word, frequency in occurrence_total.items():
        if documents.get(word, 0) < MIN_DOCUMENTS:
            rejected["documents"] += 1
            continue
        if frequency < MIN_COUNT:
            rejected["count"] += 1
            continue
        if not occurrence_lowercase.get(word, 0):
            rejected["name"] += 1
            continue
        counts[word] = frequency
    if not counts:
        raise CandidateInputError(f"{path}: no word passed the community rules")
    report = {
        "path": str(path),
        "documents": document_count,
        "types": len(counts),
        "tokens": sum(counts.values()),
        "rejected": rejected,
        "rules": {
            "min_documents": MIN_DOCUMENTS,
            "min_count": MIN_COUNT,
            "names": "a word never written in lowercase",
            "digits": "tokens with digits are never counted",
        },
    }
    return counts, report


def write_counts(path: Path, counts: dict[str, int]) -> int:
    data = "".join(
        f"{word}\t{frequency}\n"
        for word, frequency in sorted(counts.items(), key=lambda item: (-item[1], item[0]))
    ).encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return len(data)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    sentences = sub.add_parser("sentences", help="thin a cleaned sentence stream")
    sentences.add_argument("--input", type=Path, required=True)
    sentences.add_argument("--budget-tokens", type=int, required=True)
    sentences.add_argument("--source-tokens", type=int, required=True,
                           help="token count of the whole source, from the cleaning summary")
    sentences.add_argument("--output", type=Path, required=True)
    sentences.add_argument("--report", type=Path, default=None)

    words = sub.add_parser("words", help="word counts of a cleaned sentence stream")
    words.add_argument("--input", type=Path, required=True)
    words.add_argument("--output", type=Path, required=True)
    words.add_argument("--report", type=Path, default=None)

    lexicon = sub.add_parser("lexicon", help="attestation counts of a curated word list")
    lexicon.add_argument("--input", type=Path, required=True)
    lexicon.add_argument("--output", type=Path, required=True)
    lexicon.add_argument("--report", type=Path, default=None)

    community = sub.add_parser("community", help="community counts under the document rule")
    community.add_argument("--input", type=Path, required=True)
    community.add_argument("--cleaned-stream", type=Path, required=True,
                           help="clean_corpora.py output for the same source")
    community.add_argument("--output", type=Path, required=True)
    community.add_argument("--report", type=Path, default=None)

    args = parser.parse_args(argv)
    if args.command == "sentences":
        stride = max(1, -(-args.source_tokens // args.budget_tokens))
        kept, report = sentence_file(args.input, args.budget_tokens, stride)
        data = ("".join(f"{identifier}\t{text}\n" for identifier, text in kept)).encode("utf-8")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(data)
        report["written_bytes"] = len(data)
        report["sha256"] = hashlib.sha256(data).hexdigest()
    else:
        if args.command == "words":
            counts, report = word_counts(args.input)
        elif args.command == "lexicon":
            counts, report = lexicon_counts(args.input)
        else:
            counts, report = community_counts(args.input, args.cleaned_stream)
        report["written_bytes"] = write_counts(args.output, counts)
        report["sha256"] = hashlib.sha256(args.output.read_bytes()).hexdigest()
    report["output"] = str(args.output)
    text = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True)
    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
