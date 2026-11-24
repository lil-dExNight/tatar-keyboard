#!/usr/bin/env python3
"""Validate cleaning statistics and render compact per-source report tables."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import corpus_cleaning as cleaning

FILTER_GROUPS = (
    "evaluation_collision", "boilerplate", "machine_translation", "seo_spam",
    "encoding", "language", "repeated_header", "lexical_duplicate",
)


def filter_occurrences(stats: dict[str, object]) -> dict[str, int]:
    """Aggregate reason-level counts without changing their fixed filter order."""
    result = {name: 0 for name in FILTER_GROUPS}
    filters = stats["filters"]
    assert isinstance(filters, dict)
    assigned = set()
    for reason, values in filters.items():
        group = next(
            (name for name in FILTER_GROUPS if reason == name or reason.startswith(name + "_")),
            None,
        )
        if group is not None:
            result[group] += int(values["token_occurrences"])
            assigned.add(reason)
    result["other"] = sum(
        int(values["token_occurrences"])
        for reason, values in filters.items() if reason not in assigned
    )
    return result


def augment_occurrence_stats(stats: dict[str, object], chat_path: Path) -> None:
    """Check occurrence-weighted chat statistics against the written stream."""
    occurrences = lines = 0
    with chat_path.open("r", encoding="utf-8") as stream:
        for line in stream:
            if not line.strip():
                continue
            row = json.loads(line)
            lines += 1
            occurrences += (
                len(cleaning.raw_tokens(row["text"])) * int(row.get("frequency", 1))
            )
    if lines != stats["chat_spelling_lines"]:
        raise ValueError(f"{stats['source']}: chat line count disagrees with statistics")
    existing = stats.get("chat_spelling_token_occurrences")
    if existing is not None and int(existing) != occurrences:
        raise ValueError(f"{stats['source']}: chat token count disagrees with statistics")
    stats["chat_spelling_token_occurrences"] = occurrences
    raw = int(stats["raw_token_occurrences"])
    stats["chat_spelling_token_occurrence_share"] = occurrences / raw if raw else 0.0
    stats["kept_token_occurrence_share"] = int(stats["kept_token_occurrences"]) / raw if raw else 0.0


def validate_conservation(stats: dict[str, object]) -> None:
    """Require every raw token occurrence to have exactly one output or drop reason."""
    filters = stats["filters"]
    assert isinstance(filters, dict)
    duplicates = stats["duplicate_tokens"]
    assert isinstance(duplicates, dict)
    accounted = (
        int(stats["kept_token_occurrences"])
        + int(stats["chat_spelling_token_occurrences"])
        + int(duplicates["exact"]) + int(duplicates["near"])
        + sum(int(values["token_occurrences"]) for values in filters.values())
    )
    raw = int(stats["raw_token_occurrences"])
    if accounted != raw:
        raise ValueError(f"{stats['source']}: token conservation failed: {accounted} != {raw}")


def _percent(value: int, denominator: int) -> str:
    return f"{100 * value / denominator:.4f}" if denominator else "0.0000"


def render_tables(summary: dict[str, object]) -> str:
    """Render occurrence-weighted source rows and a sparse document-pair matrix."""
    header = (
        "| Source | Raw tokens | Kept tokens | Exact dup % | Near dup % | Boilerplate % | "
        "Language % | Eval % | MT % | SEO % | Encoding % | Repeated % | Lexical % | Chat % | Other % |\n"
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
    )
    rows = [header]
    for stats in summary["sources"]:
        raw = int(stats["raw_token_occurrences"])
        kept = int(stats["kept_token_occurrences"])
        filters = filter_occurrences(stats)
        duplicates = stats["duplicate_tokens"]
        assert isinstance(duplicates, dict)
        rows.append(
            f"| {stats['source']} | {raw} | {kept} "
            f"| {_percent(int(duplicates['exact']), raw)} "
            f"| {_percent(int(duplicates['near']), raw)} "
            f"| {_percent(filters['boilerplate'], raw)} "
            f"| {_percent(filters['language'], raw)} "
            f"| {_percent(filters['evaluation_collision'], raw)} "
            f"| {_percent(filters['machine_translation'], raw)} "
            f"| {_percent(filters['seo_spam'], raw)} "
            f"| {_percent(filters['encoding'], raw)} "
            f"| {_percent(filters['repeated_header'], raw)} "
            f"| {_percent(filters['lexical_duplicate'], raw)} "
            f"| {_percent(int(stats['chat_spelling_token_occurrences']), raw)} "
            f"| {_percent(filters['other'], raw)} |"
        )
    matrix = [
        "", "## Sparse cross-source duplicate matrix (documents)",
        "Rows are removed duplicate sources; columns are retained representative sources. "
        "Unlisted pairs are zero. Within-source duplicates remain in the per-source JSON.", "",
        "| Duplicate source | Representative source | Documents |", "|---|---|---:|",
    ]
    pairs = summary["cross_source_duplicate_matrix"]
    assert isinstance(pairs, dict)
    for duplicate, representatives in sorted(pairs.items()):
        for representative, count in sorted(representatives.items()):
            if duplicate != representative:
                matrix.append(f"| {duplicate} | {representative} | {count} |")
    return "\n".join(rows + matrix) + "\n"


def finalize(output_root: Path) -> dict[str, object]:
    """Synchronize derived statistics and write Markdown evidence."""
    summary_path = output_root / "summary.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    stats_by_source = {}
    for stats in summary["sources"]:
        source = str(stats["source"])
        stats_path = output_root / "stats" / f"{source}.json"
        current = json.loads(stats_path.read_text(encoding="utf-8"))
        augment_occurrence_stats(current, output_root / "chat-spelling" / f"{source}.jsonl")
        validate_conservation(current)
        stream_directory = "lexicon-only" if current["stream"] == "lexicon_only" else "sentences"
        current["output"]["path"] = str(output_root / stream_directory / f"{source}.txt")
        current["chat_output"]["path"] = str(output_root / "chat-spelling" / f"{source}.jsonl")
        temporary = stats_path.with_suffix(".json.part")
        temporary.write_text(
            json.dumps(current, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary, stats_path)
        stats_by_source[source] = current
    summary["sources"] = [stats_by_source[stats["source"]] for stats in summary["sources"]]
    temporary = summary_path.with_suffix(".json.part")
    temporary.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, summary_path)
    tables = render_tables(summary)
    (output_root / "report-tables.md").write_text(tables, encoding="utf-8")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, default=Path("build/corpus-clean"))
    arguments = parser.parse_args()
    summary = finalize(arguments.output)
    print(json.dumps({
        "sources": len(summary["sources"]),
        "duplicates": summary["document_duplicates"],
        "tables": str(arguments.output / "report-tables.md"),
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
