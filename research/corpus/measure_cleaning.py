#!/usr/bin/env python3
"""Measure corpus filters against known-Tatar text and reviewed source samples."""
from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research" / "corpus"))

import corpus_cleaning as cleaning  # noqa: E402


def binary_metrics(rows: list[tuple[bool, bool]]) -> dict[str, object]:
    counts = Counter(rows)
    tp, fp = counts[True, True], counts[False, True]
    fn, tn = counts[True, False], counts[False, False]
    return {
        "labels": len(rows), "tp": tp, "fp": fp, "fn": fn, "tn": tn,
        "precision": tp / (tp + fp) if tp + fp else None,
        "recall": tp / (tp + fn) if tp + fn else None,
        "false_positive_rate": fp / (fp + tn) if fp + tn else None,
        "false_negative_rate": fn / (fn + tp) if fn + tp else None,
    }


def false_reject_report(
    paths: list[Path], tatar_words: frozenset[str], russian_words: frozenset[str]
) -> dict[str, object]:
    sources = []
    total = rejected = 0
    for path in paths:
        lines = [
            line.strip() for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")
        ]
        labels = Counter(cleaning.classify_language(line, tatar_words, russian_words) for line in lines)
        rejected_here = sum(count for label, count in labels.items() if label != "tatar")
        total += len(lines)
        rejected += rejected_here
        sources.append({
            "path": str(path), "lines": len(lines), "labels": dict(sorted(labels.items())),
            "false_rejects": rejected_here,
            "false_reject_rate": rejected_here / len(lines) if lines else None,
        })
    return {
        "sources": sources, "lines": total, "false_rejects": rejected,
        "false_reject_rate": rejected / total if total else None,
    }


def read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def reviewed_metrics(
    r5_root: Path, tatar_words: frozenset[str], russian_words: frozenset[str]
) -> dict[str, object]:
    language_confusion: Counter[tuple[str, str]] = Counter()
    boilerplate_rows: list[tuple[bool, bool]] = []
    chat_rows: list[tuple[bool, bool]] = []
    mt_rows: list[tuple[bool, bool]] = []
    per_source = {}
    quality_root = r5_root / "r5c/quality"
    for path in sorted(quality_root.glob("*-labels.tsv")):
        rows = read_tsv(path)
        if not rows or "russian_letters" not in rows[0]:
            continue
        source_boilerplate = []
        source_chat = []
        for row in rows:
            text = row["text"]
            gold_language = row["language"]
            predicted_language = cleaning.classify_language(text, tatar_words, russian_words)
            language_confusion[gold_language, predicted_language] += 1
            gold_boilerplate = row["boilerplate"] == "True"
            predicted_boilerplate = cleaning.boilerplate_reason(text) is not None
            boilerplate_rows.append((gold_boilerplate, predicted_boilerplate))
            source_boilerplate.append((gold_boilerplate, predicted_boilerplate))
            gold_chat = row["russian_letters"] == "True"
            predicted_chat = bool(cleaning.chat_spelling_map(text, tatar_words, russian_words))
            chat_rows.append((gold_chat, predicted_chat))
            source_chat.append((gold_chat, predicted_chat))
            if row["machine_translation"] == "no":
                mt_rows.append((False, cleaning.quality_reason(text) == "machine_translation"))
        per_source[path.stem.removesuffix("-labels")] = {
            "boilerplate": binary_metrics(source_boilerplate),
            "chat_spelling": binary_metrics(source_chat),
        }

    fineweb_root = r5_root / "r5a/quality/fineweb"
    fineweb_lines = (fineweb_root / "normalized.txt").read_text(encoding="utf-8").splitlines()
    for row in read_tsv(fineweb_root / "labels.tsv"):
        if row["machine_translation"] != "yes":
            continue
        text = fineweb_lines[int(row["line"]) - 1]
        mt_rows.append((True, cleaning.quality_reason(text) == "machine_translation"))

    return {
        "reviewed_line_sources": sorted(per_source),
        "language_confusion": [
            {"gold": gold, "predicted": predicted, "count": count}
            for (gold, predicted), count in sorted(language_confusion.items())
        ],
        "boilerplate": binary_metrics(boilerplate_rows),
        "chat_spelling": binary_metrics(chat_rows),
        "machine_translation": binary_metrics(mt_rows),
        "per_source": per_source,
    }


def seo_review_metrics(path: Path) -> dict[str, object]:
    rows = read_tsv(path)
    labeled = [
        (row["gold"].lower() == "yes", cleaning.quality_reason(row["text"]) == "seo_spam")
        for row in rows
    ]
    return binary_metrics(labeled)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--speech-root", type=Path)
    parser.add_argument("--r5-root", type=Path)
    parser.add_argument("--seo-labels", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    tatar_words, russian_words = cleaning.load_dictionary_vocabularies(arguments.root)
    speech_root = arguments.speech_root or arguments.root / "build/corpus-sources"
    native_paths = [arguments.root / "app/src/test/resources/tt_eval_sentences.txt"]
    native_paths.extend(sorted((speech_root / "common-voice").glob("tt-test.txt")))
    native_paths.extend(sorted((speech_root / "tatsc").glob("tt-*-test.txt")))
    report: dict[str, object] = {
        "known_tatar_language_id": false_reject_report(native_paths, tatar_words, russian_words),
    }
    if arguments.r5_root is not None:
        report["reviewed_samples"] = reviewed_metrics(
            arguments.r5_root, tatar_words, russian_words
        )
    if arguments.seo_labels is not None:
        report["seo_spam"] = seo_review_metrics(arguments.seo_labels)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
