#!/usr/bin/env python3
"""Measure the size and usefulness of a bigram table over an H x K matrix (prototype).

Input: Leipzig ``*-sentences.txt`` training and held-out corpora (downloaded by hand) and the
bundled dictionary asset (``--asset``); ``--language`` picks the alphabet (default Tatar).
Output: a JSON report per (heads, successes) configuration: sizes, held-out hit rate, peak RSS.
A pair counts only if both words are in the dictionary; ``bigram_asset_pack.py`` reuses this code.
Exits nonzero on a malformed sentence row, and when no configuration fits both size caps.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import resource
import sys
import time
import unicodedata
import zlib
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, Sequence, TextIO

sys.path.insert(0, str(Path(__file__).resolve().parent))

import dictionary_coverage as coverage  # noqa: E402
from dictionary_coverage import normalize_word  # noqa: E402
from dictionary_pack import decompress_asset, validate_raw  # noqa: E402

# Size caps per language; both are enforced by the generator (the raw cap binds first).
MAX_COMPRESSED_BYTES = 250_000
MAX_RAW_BYTES = 1_048_576

# The matrix: H heads by unigram frequency, K successes per head, without the top corner.
HEAD_COUNTS = (8_000, 10_000)
SUCCESSES_PER_HEAD = (4, 6, 8, 10)
EXCLUDED_CORNER = (10_000, 10)

# Successes kept per head while counting. Exact for every K in the matrix; anything beyond the
# largest K can never enter a bundled table, so it is dropped as soon as a shard pass completes.
KEPT_SUCCESSES_PER_HEAD = 32

# Header size of the schema-2 file.
HEADER_BYTES = 96

# zlib settings: the same mode as dictionary_pack.py, so compressed sizes are comparable.
COMPRESSION_LEVEL = 9
COMPRESSION_WBITS = 15
COMPRESSION_MEM_LEVEL = 9


class BigramInputError(ValueError):
    """A malformed input that must stop the generation rather than be skipped."""


@dataclass
class CorpusStats:
    """What one corpus contributed, including the loss the tokenizer rule causes."""

    path: str
    sha256: str
    sentences: int = 0
    tokens: int = 0
    tokens_rejected: int = 0
    pairs: int = 0

    @property
    def rejected_share(self) -> float:
        return 0.0 if self.tokens == 0 else self.tokens_rejected / self.tokens

    def to_dict(self) -> dict[str, object]:
        return {
            "path": self.path,
            "sha256": self.sha256,
            "sentences": self.sentences,
            "tokens": self.tokens,
            "tokens_rejected": self.tokens_rejected,
            "rejected_share": round(self.rejected_share, 6),
            "pairs": self.pairs,
        }


@dataclass
class Configuration:
    """One cell of the H x K matrix, with everything the report has to carry."""

    heads: int
    successes_per_head: int
    pair_count: int = 0
    success_vocabulary: int = 0
    raw_bytes: int = 0
    compressed_bytes: int = 0
    conditional_hit_rate: float = 0.0
    unconditional_hit_rate: float = 0.0
    events_with_prediction_share: float = 0.0
    events: int = 0
    passes_raw_cap: bool = False
    passes_compressed_cap: bool = False

    @property
    def name(self) -> str:
        return f"H={self.heads} K={self.successes_per_head}"

    def to_dict(self) -> dict[str, object]:
        return {
            "heads": self.heads,
            "successes_per_head": self.successes_per_head,
            "pairs": self.pair_count,
            "success_vocabulary": self.success_vocabulary,
            "raw_bytes": self.raw_bytes,
            "compressed_bytes": self.compressed_bytes,
            "conditional_top3_hit_rate": round(self.conditional_hit_rate, 6),
            "unconditional_top3_hit_rate": round(self.unconditional_hit_rate, 6),
            "events_with_prediction_share": round(self.events_with_prediction_share, 6),
            "events": self.events,
            "passes_raw_cap": self.passes_raw_cap,
            "passes_compressed_cap": self.passes_compressed_cap,
        }


def sha256_of(path: Path) -> str:
    """SHA-256 of an input corpus, recorded in the report."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_sentence_row(line: str, source: str, line_number: int) -> str:
    """Return the sentence text of one row, or raise.

    A row is exactly two tab-separated fields, the first a positive decimal id. Anything else
    raises instead of being skipped (``dictionary_coverage.parse_row`` reads only ``words.txt``).
    """
    fields = line.rstrip("\n").split("\t")
    if len(fields) != 2:
        raise BigramInputError(
            f"{source}:{line_number}: expected exactly two tab-separated fields, got {len(fields)}"
        )
    identifier, sentence = fields
    if not identifier or any(character < "0" or character > "9" for character in identifier):
        raise BigramInputError(
            f"{source}:{line_number}: sentence id must contain only ASCII decimal digits"
        )
    if int(identifier) <= 0:
        raise BigramInputError(f"{source}:{line_number}: sentence id must be positive")
    return sentence


def iter_sentences(path: Path) -> Iterator[str]:
    with path.open("r", encoding="utf-8", errors="strict") as stream:
        for line_number, line in enumerate(stream, start=1):
            yield parse_sentence_row(line, path.name, line_number)


SENTENCE_TERMINATORS = frozenset(".!?…。！？")
QUOTE_CHARACTERS = frozenset("\"'«»‹›„“”‘’‚")


def _is_punctuation(character: str) -> bool:
    return unicodedata.category(character).startswith("P")


def _punctuation_class(character: str) -> str:
    if character in SENTENCE_TERMINATORS:
        return "sentence_terminal"
    if character in QUOTE_CHARACTERS:
        return "quote"
    category = unicodedata.category(character)
    if category in {"Ps", "Pe"}:
        return "bracket"
    if character == ",":
        return "comma"
    if character in ";:":
        return "clause_separator"
    if category == "Pd":
        return "dash"
    return "other_punctuation"


@dataclass(frozen=True)
class EdgeNormalizedToken:
    """One raw token after optional edge-punctuation removal."""

    word: str | None
    punctuation_classes: tuple[str, ...]
    sentence_boundary_before: bool
    sentence_boundary_after: bool
    internal_punctuation: bool


def edge_normalized_tokens(
    sentence: str, alphabet: frozenset[str] = coverage.TATAR_ALPHABET
) -> list[EdgeNormalizedToken]:
    """Strip Unicode punctuation only at token edges, then apply canonical word normalization.

    Sentence-ending edge punctuation remains as boundary metadata. Punctuation left inside the
    trimmed token is not removed, so canonical normalization still rejects the whole token.
    """
    result: list[EdgeNormalizedToken] = []
    for raw_token in sentence.split():
        start = 0
        end = len(raw_token)
        leading: list[str] = []
        trailing: list[str] = []
        while start < end and _is_punctuation(raw_token[start]):
            leading.append(raw_token[start])
            start += 1
        while end > start and _is_punctuation(raw_token[end - 1]):
            end -= 1
            trailing.append(raw_token[end])
        edge_characters = leading + trailing
        classes = tuple(sorted({_punctuation_class(character) for character in edge_characters}))
        core = raw_token[start:end]
        normalized, _reason = normalize_word(core, alphabet)
        result.append(
            EdgeNormalizedToken(
                word=normalized,
                punctuation_classes=classes,
                sentence_boundary_before=any(
                    character in SENTENCE_TERMINATORS for character in leading
                ),
                sentence_boundary_after=any(
                    character in SENTENCE_TERMINATORS for character in trailing
                ),
                internal_punctuation=any(_is_punctuation(character) for character in core),
            )
        )
    return result


def normalized_tokens(
    sentence: str,
    alphabet: frozenset[str] = coverage.TATAR_ALPHABET,
    recover_punctuation: bool = False,
) -> list[str | None]:
    """Normalize one sentence, using ``None`` for rejected tokens and sentence boundaries.

    By default, any punctuation rejects its whole token. With ``recover_punctuation``, edge
    punctuation is removed first; hard boundaries still insert ``None`` so no pair crosses them.
    Russian text needs the Russian alphabet because the Tatar one is a superset.
    """
    if not recover_punctuation:
        return [normalize_word(token, alphabet)[0] for token in sentence.split()]
    result: list[str | None] = []
    for token in edge_normalized_tokens(sentence, alphabet):
        if token.word is None:
            result.append(None)
            continue
        if token.sentence_boundary_before:
            result.append(None)
        result.append(token.word)
        if token.sentence_boundary_after:
            result.append(None)
    return result


def _eligible_pair(
    head: str | None, success: str | None, vocabulary: frozenset[str]
) -> tuple[str, str] | None:
    if head is None or success is None or head == success:
        return None
    if head not in vocabulary or success not in vocabulary:
        return None
    return head, success


def iter_pairs(tokens: Sequence[str | None], vocabulary: frozenset[str]) -> Iterator[tuple[str, str]]:
    """Adjacent in-vocabulary pairs of one sentence, self-pairs dropped."""
    for index in range(len(tokens) - 1):
        pair = _eligible_pair(tokens[index], tokens[index + 1], vocabulary)
        if pair is not None:
            yield pair


def measure_punctuation_recovery(
    paths: Sequence[Path],
    vocabulary: frozenset[str],
    alphabet: frozenset[str] = coverage.TATAR_ALPHABET,
) -> dict[str, object]:
    """Count recovered tokens and new in-vocabulary pairs by edge-punctuation class."""
    recovered_tokens: Counter[str] = Counter()
    recovered_pairs: Counter[str] = Counter()
    recovered_token_total = 0
    recovered_pair_total = 0
    boundary_pairs_blocked = 0
    internal_punctuation_rejected = 0
    for path in paths:
        for sentence in iter_sentences(path):
            raw_tokens = sentence.split()
            baseline = [normalize_word(token, alphabet)[0] for token in raw_tokens]
            recovered = edge_normalized_tokens(sentence, alphabet)
            for token in recovered:
                if token.word is not None and token.punctuation_classes:
                    recovered_token_total += 1
                    recovered_tokens.update(token.punctuation_classes)
                if token.word is None and token.internal_punctuation:
                    internal_punctuation_rejected += 1
            for index in range(len(recovered) - 1):
                left = recovered[index]
                right = recovered[index + 1]
                candidate = _eligible_pair(left.word, right.word, vocabulary)
                if left.sentence_boundary_after or right.sentence_boundary_before:
                    if candidate is not None:
                        boundary_pairs_blocked += 1
                    continue
                if candidate is None:
                    continue
                baseline_pair = _eligible_pair(baseline[index], baseline[index + 1], vocabulary)
                if baseline_pair is not None:
                    continue
                recovered_pair_total += 1
                recovered_pairs.update(set(left.punctuation_classes + right.punctuation_classes))
    return {
        "recovered_tokens": recovered_token_total,
        "recovered_tokens_by_class": dict(sorted(recovered_tokens.items())),
        "recovered_pairs": recovered_pair_total,
        "recovered_pairs_by_class": dict(sorted(recovered_pairs.items())),
        "pairs_blocked_at_sentence_boundaries": boundary_pairs_blocked,
        "internal_punctuation_tokens_rejected": internal_punctuation_rejected,
    }


def read_shipped_vocabulary(
    asset_path: Path, language: coverage.Language = coverage.DEFAULT_LANGUAGE
) -> tuple[frozenset[str], dict[str, int]]:
    """The word list and the unigram frequencies, both from the bundled dictionary asset."""
    parsed = validate_raw(decompress_asset(asset_path.read_bytes()), language=language)
    frequencies = {word: frequency for word, frequency in zip(parsed.words, parsed.frequencies)}
    return frozenset(parsed.words), frequencies


def select_heads(frequencies: dict[str, int], count: int) -> list[str]:
    """The ``count`` most frequent words, ties broken by code point ascending (as everywhere)."""
    ordered = sorted(frequencies.items(), key=lambda item: (-item[1], item[0]))
    return [word for word, _frequency in ordered[:count]]


def count_pairs(
    paths: Sequence[Path],
    heads: frozenset[str],
    vocabulary: frozenset[str],
    shards: int,
    stats: list[CorpusStats],
    alphabet: frozenset[str] = coverage.TATAR_ALPHABET,
    recover_punctuation: bool = False,
) -> dict[str, list[tuple[str, int]]]:
    """Count pairs for every head, one shard of heads per pass over the corpora.

    All pairs at once do not fit in memory on an ordinary machine, hence the shards; peak RSS is
    reported so the run can be reproduced elsewhere.
    """
    table: dict[str, list[tuple[str, int]]] = {}
    for shard in range(shards):
        counts: dict[str, Counter[str]] = {}
        for path in paths:
            corpus = next((entry for entry in stats if entry.path == str(path)), None)
            first_pass = shard == 0
            for sentence in iter_sentences(path):
                tokens = normalized_tokens(sentence, alphabet, recover_punctuation)
                if corpus is not None and first_pass:
                    raw_token_count = len(sentence.split())
                    corpus.sentences += 1
                    corpus.tokens += raw_token_count
                    corpus.tokens_rejected += raw_token_count - sum(
                        token is not None for token in tokens
                    )
                for head, success in iter_pairs(tokens, vocabulary):
                    if head not in heads or hash(head) % shards != shard:
                        continue
                    counts.setdefault(head, Counter())[success] += 1
                    if corpus is not None and first_pass:
                        corpus.pairs += 1
        for head, successes in counts.items():
            table[head] = sorted(
                successes.items(), key=lambda item: (-item[1], item[0])
            )[:KEPT_SUCCESSES_PER_HEAD]
    return table


def count_continuations(
    paths: Sequence[Path],
    vocabulary: frozenset[str],
    alphabet: frozenset[str] = coverage.TATAR_ALPHABET,
) -> tuple[dict[str, int], dict[str, int], int]:
    """Kneser-Ney continuation counts: one pass over the training sentences.

    Returns ``(successor_continuations, head_continuations, continuation_total)``:
    ``successor_continuations[w]`` is the number of distinct in-vocabulary words that [w]
    follows (N₁₊(•,w)), ``head_continuations[h]`` the number of distinct in-vocabulary
    successors of [h] (N₁₊(h,•)), and the total is the number of distinct in-vocabulary
    pairs. Pair eligibility is the ``count_pairs`` rule. Distinctness makes the counts
    invariant to a corpus file listed more than once (a count weight), so callers
    deduplicate the paths.
    """
    words = sorted(vocabulary)
    index = {word: position for position, word in enumerate(words)}
    width = len(words)
    pairs: set[int] = set()
    for path in paths:
        for sentence in iter_sentences(path):
            tokens = normalized_tokens(sentence, alphabet)
            for head, success in iter_pairs(tokens, vocabulary):
                pairs.add(index[head] * width + index[success])
    successor_continuations: dict[str, int] = {}
    head_continuations: dict[str, int] = {}
    for key in pairs:
        head_position, success_position = divmod(key, width)
        head_word = words[head_position]
        head_continuations[head_word] = head_continuations.get(head_word, 0) + 1
        success_word = words[success_position]
        successor_continuations[success_word] = successor_continuations.get(success_word, 0) + 1
    return successor_continuations, head_continuations, len(pairs)


def blob_bytes(words: Iterable[str]) -> int:
    """Measured UTF-8 size of a word blob."""
    return sum(len(word.encode("utf-8")) for word in words)


def raw_size(heads: Sequence[str], pair_count: int, successes: Sequence[str]) -> int:
    """The documented schema-2 layout: 96 + 8*(H+1) + head blob + 4*P + 4*(V+1) + success blob."""
    return (
        HEADER_BYTES
        + 8 * (len(heads) + 1)
        + blob_bytes(heads)
        + 4 * pair_count
        + 4 * (len(successes) + 1)
        + blob_bytes(successes)
    )


def compress(raw: bytes) -> bytes:
    compressor = zlib.compressobj(
        level=COMPRESSION_LEVEL,
        method=zlib.DEFLATED,
        wbits=COMPRESSION_WBITS,
        memLevel=COMPRESSION_MEM_LEVEL,
        strategy=zlib.Z_DEFAULT_STRATEGY,
    )
    return compressor.compress(raw) + compressor.flush(zlib.Z_FINISH)


def serialize_table(
    heads: Sequence[str], table: dict[str, list[tuple[str, int]]], successes_per_head: int
) -> tuple[bytes, int, list[str]]:
    """A byte image of the table, built only to measure its compressed size.

    Close enough to the schema-2 layout for the number to be meaningful: head blob, per-head
    success id runs, success blob. No weight byte: the rank is implied by position.
    """
    success_vocabulary: dict[str, int] = {}
    pair_ids: list[int] = []
    offsets: list[int] = [0]
    for head in heads:
        for success, _count in table.get(head, ())[:successes_per_head]:
            pair_ids.append(success_vocabulary.setdefault(success, len(success_vocabulary)))
        offsets.append(len(pair_ids))

    parts = [b"\0" * HEADER_BYTES]
    parts.append(b"".join(offset.to_bytes(8, "little") for offset in offsets))
    parts.append("".join(heads).encode("utf-8"))
    parts.append(b"".join(identifier.to_bytes(4, "little") for identifier in pair_ids))
    ordered_successes = sorted(success_vocabulary, key=success_vocabulary.get)
    parts.append(
        b"".join(
            offset.to_bytes(4, "little")
            for offset in range(len(ordered_successes) + 1)
        )
    )
    parts.append("".join(ordered_successes).encode("utf-8"))
    return b"".join(parts), len(pair_ids), ordered_successes


@dataclass
class HeldOutTally:
    """Counters of one configuration over the held-out corpus."""

    events: int = 0
    events_with_prediction: int = 0
    hits: int = 0


def evaluate(
    holdout_paths: Sequence[Path],
    vocabulary: frozenset[str],
    table: dict[str, list[tuple[str, int]]],
    head_rank: dict[str, int],
    configurations: Sequence[Configuration],
    stats: list[CorpusStats],
    alphabet: frozenset[str] = coverage.TATAR_ALPHABET,
) -> None:
    """One pass over the held-out corpus, all configurations tallied at once.

    An event is every position whose previous token in the same sentence passed
    ``normalize_word`` (the runtime rule); sentence starts and positions after punctuation are not
    events. An event with no prediction counts as a miss, so the rate is unconditional.
    """
    tallies = [HeldOutTally() for _ in configurations]
    for path in holdout_paths:
        corpus = next((entry for entry in stats if entry.path == str(path)), None)
        for sentence in iter_sentences(path):
            tokens = normalized_tokens(sentence, alphabet)
            if corpus is not None:
                corpus.sentences += 1
                corpus.tokens += len(tokens)
                corpus.tokens_rejected += sum(1 for token in tokens if token is None)
            for index in range(len(tokens) - 1):
                head = tokens[index]
                if head is None:
                    continue
                target = tokens[index + 1]
                successes = table.get(head, ())
                rank = next(
                    (
                        position
                        for position, (word, _count) in enumerate(successes)
                        if word == target
                    ),
                    None,
                )
                head_position = head_rank.get(head)
                for tally, configuration in zip(tallies, configurations):
                    tally.events += 1
                    if head_position is None or head_position >= configuration.heads:
                        continue
                    available = min(configuration.successes_per_head, len(successes))
                    if available == 0:
                        continue
                    tally.events_with_prediction += 1
                    if rank is not None and rank < min(3, available):
                        tally.hits += 1

    for tally, configuration in zip(tallies, configurations):
        configuration.events = tally.events
        configuration.unconditional_hit_rate = (
            0.0 if tally.events == 0 else tally.hits / tally.events
        )
        configuration.conditional_hit_rate = (
            0.0
            if tally.events_with_prediction == 0
            else tally.hits / tally.events_with_prediction
        )
        configuration.events_with_prediction_share = (
            0.0 if tally.events == 0 else tally.events_with_prediction / tally.events
        )


def matrix_configurations() -> list[Configuration]:
    return [
        Configuration(heads=heads, successes_per_head=successes)
        for heads in HEAD_COUNTS
        for successes in SUCCESSES_PER_HEAD
        if (heads, successes) != EXCLUDED_CORNER
    ]


def peak_rss_bytes() -> int:
    """Peak RSS of this process. On Linux ru_maxrss is KiB, on macOS it is bytes."""
    usage = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return usage if sys.platform == "darwin" else usage * 1024


def run_matrix(
    train_paths: Sequence[Path],
    holdout_paths: Sequence[Path],
    asset_path: Path,
    shards: int,
    language: coverage.Language = coverage.DEFAULT_LANGUAGE,
) -> dict[str, object]:
    started = time.monotonic()
    vocabulary, frequencies = read_shipped_vocabulary(asset_path, language)
    stats = [
        CorpusStats(path=str(path), sha256=sha256_of(path))
        for path in list(train_paths) + list(holdout_paths)
    ]

    largest_heads = max(HEAD_COUNTS)
    ordered_heads = select_heads(frequencies, largest_heads)
    head_rank = {word: position for position, word in enumerate(ordered_heads)}
    table = count_pairs(
        train_paths, frozenset(ordered_heads), vocabulary, shards, stats, language.alphabet
    )

    configurations = matrix_configurations()
    for configuration in configurations:
        heads = ordered_heads[: configuration.heads]
        image, pair_count, successes = serialize_table(
            heads, table, configuration.successes_per_head
        )
        configuration.pair_count = pair_count
        configuration.success_vocabulary = len(successes)
        configuration.raw_bytes = raw_size(heads, pair_count, successes)
        configuration.compressed_bytes = len(compress(image))
        configuration.passes_raw_cap = configuration.raw_bytes <= MAX_RAW_BYTES
        configuration.passes_compressed_cap = (
            configuration.compressed_bytes <= MAX_COMPRESSED_BYTES
        )

    evaluate(
        holdout_paths,
        vocabulary,
        table,
        head_rank,
        configurations,
        stats,
        language.alphabet,
    )

    return {
        "language": language.tag,
        "corpora": [entry.to_dict() for entry in stats],
        "configurations": [configuration.to_dict() for configuration in configurations],
        "caps": {
            "max_compressed_bytes": MAX_COMPRESSED_BYTES,
            "max_raw_bytes": MAX_RAW_BYTES,
        },
        "peak_rss_bytes": peak_rss_bytes(),
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "shards": shards,
    }


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    matrix = subparsers.add_parser("matrix", help="run the seven H x K configurations")
    matrix.add_argument("--train", nargs="+", required=True, type=Path)
    matrix.add_argument("--holdout", nargs="+", required=True, type=Path)
    matrix.add_argument("--asset", required=True, type=Path)
    matrix.add_argument("--shards", type=int, default=8)
    matrix.add_argument("--report", type=Path)
    matrix.add_argument(
        "--language", default=coverage.DEFAULT_LANGUAGE.tag, choices=sorted(coverage.LANGUAGES)
    )
    return parser


def main(argv: Sequence[str] | None = None, stream: TextIO = sys.stdout) -> int:
    arguments = create_argument_parser().parse_args(argv)
    if arguments.shards < 1:
        raise SystemExit("shards must be positive")
    report = run_matrix(
        arguments.train,
        arguments.holdout,
        arguments.asset,
        arguments.shards,
        coverage.language_for(arguments.language),
    )
    text = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True)
    if arguments.report is not None:
        arguments.report.write_text(text + "\n", encoding="utf-8")
    print(text, file=stream)
    passing = [
        entry
        for entry in report["configurations"]
        if entry["passes_raw_cap"] and entry["passes_compressed_cap"]
    ]
    if not passing:
        # A matrix where every configuration is over a cap must not look like a successful run.
        print("no configuration fits both caps", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
