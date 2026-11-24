#!/usr/bin/env python3
"""Clean standardized JSONL corpora into sentence, lexical and chat-spelling streams."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research" / "corpus"))

import corpus_cleaning as cleaning  # noqa: E402
import evaluation_exclusions  # noqa: E402
from convert_sources import SOURCE_ORDER  # noqa: E402
from document_dedup import DocumentDeduplicator  # noqa: E402

LEXICAL_SOURCES = frozenset({"wikipedia", "corpus_tatar"})


class RepeatedHeaderFilter:
    """Drop short title-like lines after they recur across many documents."""

    def __init__(self, threshold: int = 20) -> None:
        self.threshold = threshold
        self.bloom = 0
        self.counts: dict[int, int] = {}

    @staticmethod
    def digest(text: str) -> int:
        return int.from_bytes(
            hashlib.blake2b(text.encode("utf-8"), digest_size=8).digest(), "big"
        )

    def is_repeated(self, text: str, document_id: str, seen_in_document: set[int]) -> bool:
        if (
            len(text) > 120
            or len(text.split()) > 8
            or text.endswith((".", "!", "?", "…"))
            or not text[:1].isupper()
        ):
            return False
        digest = self.digest(text)
        if digest in seen_in_document:
            return False
        seen_in_document.add(digest)
        slot = digest & ((1 << 24) - 1)
        mask = 1 << slot
        if not self.bloom & mask:
            self.bloom |= mask
            return False
        count = self.counts.get(digest, 1) + 1
        self.counts[digest] = count
        return count > self.threshold


def _atomic_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_text(text, encoding="utf-8")
    os.replace(temporary, path)


def _new_filter(stats: dict[str, object], name: str) -> dict[str, int]:
    filters = stats["filters"]
    assert isinstance(filters, dict)
    return filters.setdefault(name, {"lines": 0, "tokens": 0, "token_occurrences": 0})


def _drop(stats: dict[str, object], reason: str, tokens: int, weight: int) -> None:
    entry = _new_filter(stats, reason)
    entry["lines"] += 1
    entry["tokens"] += tokens
    entry["token_occurrences"] += tokens * weight


def clean_source(
    source: str,
    documents_path: Path,
    output_root: Path,
    deduplicator: DocumentDeduplicator,
    evaluation_keys: frozenset[str],
    tatar_words: frozenset[str],
    russian_words: frozenset[str],
    limit: int | None = None,
    evaluation_deduplicator: DocumentDeduplicator | None = None,
) -> dict[str, object]:
    """Run the fixed filter sequence and write one source's outputs atomically."""
    stats: dict[str, object] = {
        "source": source,
        "stream": "lexicon_only" if source in LEXICAL_SOURCES else "sentences",
        "raw_documents": 0,
        "raw_tokens": 0,
        "raw_token_occurrences": 0,
        "documents_after_dedup": 0,
        "duplicate_documents": {"exact": 0, "near": 0},
        "duplicate_tokens": {"exact": 0, "near": 0},
        "duplicate_representatives": {},
        "lines_extracted": 0,
        "extracted_tokens": 0,
        "kept_lines": 0,
        "kept_tokens": 0,
        "kept_token_occurrences": 0,
        "chat_spelling_lines": 0,
        "chat_spelling_tokens": 0,
        "chat_spelling_token_occurrences": 0,
        "language_labels": {},
        "filters": {},
    }
    representatives: Counter[str] = Counter()
    languages: Counter[str] = Counter()
    headers = RepeatedHeaderFilter()
    lexical_seen: set[str] = set()
    stream_directory = "lexicon-only" if source in LEXICAL_SOURCES else "sentences"
    sentence_path = output_root / stream_directory / f"{source}.txt"
    chat_path = output_root / "chat-spelling" / f"{source}.jsonl"
    sentence_path.parent.mkdir(parents=True, exist_ok=True)
    chat_path.parent.mkdir(parents=True, exist_ok=True)
    sentence_temp = sentence_path.with_name(sentence_path.name + ".part")
    chat_temp = chat_path.with_name(chat_path.name + ".part")
    sentence_hash = hashlib.sha256()
    chat_hash = hashlib.sha256()
    sentence_bytes = chat_bytes = 0

    with (
        documents_path.open("r", encoding="utf-8") as documents,
        sentence_temp.open("wb") as sentence_output,
        chat_temp.open("wb") as chat_output,
    ):
        for row_index, raw_row in enumerate(documents, 1):
            if limit is not None and row_index > limit:
                break
            if not raw_row.strip():
                continue
            row = json.loads(raw_row)
            text = str(row["text"])
            document_id = str(row["id"])
            frequency = int(row.get("frequency", 1))
            lexical = bool(row.get("lexical", False))
            tokens = len(cleaning.raw_tokens(cleaning.normalize_line(text)))
            stats["raw_documents"] += 1
            stats["raw_tokens"] += tokens
            stats["raw_token_occurrences"] += tokens * frequency
            if not lexical:
                match = deduplicator.check(source, document_id, text)
                if match is not None:
                    duplicates = stats["duplicate_documents"]
                    duplicate_tokens = stats["duplicate_tokens"]
                    assert isinstance(duplicates, dict) and isinstance(duplicate_tokens, dict)
                    duplicates[match.reason] += 1
                    duplicate_tokens[match.reason] += tokens * frequency
                    representatives[match.original_source] += 1
                    continue
            stats["documents_after_dedup"] += 1
            seen_headers: set[int] = set()
            for line in cleaning.extract_lines(text):
                line_tokens = len(cleaning.raw_tokens(line))
                stats["lines_extracted"] += 1
                stats["extracted_tokens"] += line_tokens
                key = evaluation_exclusions.sentence_key(line)
                if key and key in evaluation_keys:
                    _drop(stats, "evaluation_collision", line_tokens, frequency)
                    continue
                evaluation_match = (
                    evaluation_deduplicator.find(line)
                    if evaluation_deduplicator is not None else None
                )
                if evaluation_match is not None:
                    _drop(stats, f"evaluation_collision_{evaluation_match.reason}", line_tokens, frequency)
                    continue
                boilerplate = cleaning.boilerplate_reason(line)
                if boilerplate is not None:
                    _drop(stats, f"boilerplate_{boilerplate}", line_tokens, frequency)
                    continue
                quality = cleaning.quality_reason(line)
                if quality is not None:
                    _drop(stats, quality, line_tokens, frequency)
                    continue
                line = cleaning.normalize_line(line)
                variants = cleaning.chat_spelling_map(line, tatar_words, russian_words)
                if variants:
                    stats["chat_spelling_lines"] += 1
                    stats["chat_spelling_tokens"] += line_tokens
                    stats["chat_spelling_token_occurrences"] += line_tokens * frequency
                    chat_row = {
                        "source": source,
                        "document_id": document_id,
                        "text": line,
                        "variants": variants,
                    }
                    if frequency != 1:
                        chat_row["frequency"] = frequency
                    encoded = (
                        json.dumps(chat_row, ensure_ascii=False, sort_keys=True) + "\n"
                    ).encode("utf-8")
                    chat_output.write(encoded)
                    chat_hash.update(encoded)
                    chat_bytes += len(encoded)
                    continue
                if lexical:
                    if key in lexical_seen:
                        _drop(stats, "lexical_duplicate", line_tokens, frequency)
                        continue
                    lexical_seen.add(key)
                language = cleaning.classify_language(line, tatar_words, russian_words)
                languages[language] += 1
                if language != "tatar":
                    _drop(stats, f"language_{language}", line_tokens, frequency)
                    continue
                if headers.is_repeated(line, document_id, seen_headers):
                    _drop(stats, "repeated_header", line_tokens, frequency)
                    continue
                encoded = (line + "\n").encode("utf-8")
                sentence_output.write(encoded)
                sentence_hash.update(encoded)
                sentence_bytes += len(encoded)
                stats["kept_lines"] += 1
                stats["kept_tokens"] += line_tokens
                stats["kept_token_occurrences"] += line_tokens * frequency

    os.replace(sentence_temp, sentence_path)
    os.replace(chat_temp, chat_path)
    stats["duplicate_representatives"] = dict(sorted(representatives.items()))
    stats["language_labels"] = dict(sorted(languages.items()))
    stats["chat_spelling_line_share"] = (
        stats["chat_spelling_lines"] / stats["lines_extracted"]
        if stats["lines_extracted"] else 0.0
    )
    stats["chat_spelling_token_share"] = (
        stats["chat_spelling_tokens"] / stats["extracted_tokens"]
        if stats["extracted_tokens"] else 0.0
    )
    stats["chat_spelling_token_occurrence_share"] = (
        stats["chat_spelling_token_occurrences"] / stats["raw_token_occurrences"]
        if stats["raw_token_occurrences"] else 0.0
    )
    stats["kept_token_share"] = (
        stats["kept_tokens"] / stats["raw_tokens"] if stats["raw_tokens"] else 0.0
    )
    stats["kept_token_occurrence_share"] = (
        stats["kept_token_occurrences"] / stats["raw_token_occurrences"]
        if stats["raw_token_occurrences"] else 0.0
    )
    stats["output"] = {
        "path": str(sentence_path), "bytes": sentence_bytes,
        "sha256": sentence_hash.hexdigest(),
    }
    stats["chat_output"] = {
        "path": str(chat_path), "bytes": chat_bytes, "sha256": chat_hash.hexdigest(),
    }
    stats_path = output_root / "stats" / f"{source}.json"
    _atomic_text(stats_path, json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    return stats


def ensure_fresh_output(output_root: Path) -> None:
    """Refuse partial state instead of mixing retries in the deduplication database."""
    artifacts = (
        "dedup.sqlite", "dedup.sqlite-wal", "dedup.sqlite-shm", "summary.json",
        "evaluation.sqlite", "evaluation.sqlite-wal", "evaluation.sqlite-shm",
        "report-tables.md", "stats", "sentences", "lexicon-only", "chat-spelling",
    )
    existing = [name for name in artifacts if (output_root / name).exists()]
    if existing:
        raise FileExistsError(
            f"cleaning output already contains {existing[0]}; choose a fresh directory"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--documents", type=Path, default=ROOT / "build/corpus-raw/documents")
    parser.add_argument("--output", type=Path, default=ROOT / "build/corpus-clean")
    parser.add_argument("--speech-root", type=Path)
    parser.add_argument("--sources", nargs="+", choices=SOURCE_ORDER, default=SOURCE_ORDER)
    parser.add_argument("--limit-per-source", type=int)
    arguments = parser.parse_args()
    if arguments.limit_per_source is not None and arguments.limit_per_source <= 0:
        parser.error("--limit-per-source must be positive")
    arguments.output.mkdir(parents=True, exist_ok=True)
    try:
        ensure_fresh_output(arguments.output)
    except FileExistsError as error:
        parser.error(str(error))
    evaluation_keys, evaluation_records, evaluation_texts = evaluation_exclusions.load_evaluation_bundle(
        arguments.root, speech_root=arguments.speech_root
    )
    tatar_words, russian_words = cleaning.load_dictionary_vocabularies(arguments.root)
    all_stats: list[dict[str, object]] = []
    with (
        DocumentDeduplicator(arguments.output / "dedup.sqlite") as deduplicator,
        DocumentDeduplicator(arguments.output / "evaluation.sqlite") as evaluation_deduplicator,
    ):
        for index, text in enumerate(evaluation_texts, 1):
            evaluation_deduplicator.check("evaluation", str(index), text)
        for source in arguments.sources:
            print(json.dumps({"source": source, "status": "started"}), flush=True)
            stats = clean_source(
                source,
                arguments.documents / f"{source}.jsonl",
                arguments.output,
                deduplicator,
                evaluation_keys,
                tatar_words,
                russian_words,
                arguments.limit_per_source,
                evaluation_deduplicator,
            )
            all_stats.append(stats)
            print(json.dumps({
                "source": source, "status": "complete", "raw_tokens": stats["raw_tokens"],
                "kept_tokens": stats["kept_tokens"], "chat_lines": stats["chat_spelling_lines"],
            }), flush=True)
        matrix = deduplicator.cross_source_matrix()
        counts = deduplicator.counts()
    summary = {
        "sources": all_stats,
        "evaluation_exclusions": {
            "keys": len(evaluation_keys), "inputs": evaluation_records,
            "texts": len(evaluation_texts), "near_duplicate_seeding": True,
        },
        "document_duplicates": counts,
        "cross_source_duplicate_matrix": matrix,
    }
    _atomic_text(
        arguments.output / "summary.json",
        json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
