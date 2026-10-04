#!/usr/bin/env python3
"""One-off conversion of the Glot500 tat_Cyrl parquet shards to plain text.

NOT part of the stdlib asset pipeline: it needs pyarrow and runs once per dump, with the
pyarrow venv interpreter. The asset pipeline only ever reads the resulting plain-text file.

The rows are sentence-level and carry a per-row ``dataset`` source label. Four labels are
dropped before any text is written:

* ``Leipzig_mixed`` and ``Leipzig_web``: the same Leipzig corpora already ship as dictionary
  and bigram evidence (tat_mixed_2015_1M, tat_web_2018_1M); counting them again inside the
  Glot500 stream would double their weight by an uncontrolled factor;
* ``Leipzig_news``: tat_news_2015_1M is the frozen written held-out set and must not shape
  shipped data;
* ``Tatoeba``: the Tatar eval set is built from Tatoeba, so Tatoeba rows stay out of every
  training stream (the sentence-level decontamination in tt_extra_sentences.py still runs
  on top of this label filter).

Every kept row must be tagged Cyrillic in both script columns; a row that is not is dropped
and counted. Output: one row per line (internal whitespace runs become one space), plus a
JSON report with per-label counts.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

DROP_LABELS = frozenset(
    {"Leipzig_mixed", "Leipzig_web", "Leipzig_news", "Tatoeba"}
)


def convert(shards: list[Path], out_path: Path, report_path: Path,
            also_drop: frozenset[str] = frozenset()) -> dict:
    import pyarrow.parquet as pq  # the one non-stdlib import; see module docstring

    drop = DROP_LABELS | also_drop

    stats: dict[str, object] = {
        "shards": len(shards),
        "rows_read": 0,
        "rows_kept": 0,
        "rows_dropped_label": 0,
        "rows_dropped_script": 0,
        "kept_bytes": 0,
        "dropped_labels": sorted(drop),
        "per_label_read": {},
        "per_label_kept": {},
    }
    per_label_read: Counter = Counter()
    per_label_kept: Counter = Counter()
    digest = hashlib.sha256()
    with out_path.open("w", encoding="utf-8", newline="\n") as out:
        for shard in shards:
            parquet = pq.ParquetFile(shard)
            for batch in parquet.iter_batches(
                batch_size=50000, columns=["text", "dataset", "script", "lang_script"]
            ):
                texts = batch.column("text").to_pylist()
                labels = batch.column("dataset").to_pylist()
                scripts = batch.column("script").to_pylist()
                lang_scripts = batch.column("lang_script").to_pylist()
                for text, label, script, lang_script in zip(
                    texts, labels, scripts, lang_scripts
                ):
                    stats["rows_read"] += 1
                    per_label_read[label] += 1
                    if label in drop:
                        stats["rows_dropped_label"] += 1
                        continue
                    if script != "Cyrl" or lang_script != "Cyrl":
                        stats["rows_dropped_script"] += 1
                        continue
                    line = " ".join(text.split()) + "\n"
                    encoded = line.encode("utf-8")
                    digest.update(encoded)
                    out.write(line)
                    stats["rows_kept"] += 1
                    per_label_kept[label] += 1
                    stats["kept_bytes"] += len(encoded)
    stats["per_label_read"] = dict(sorted(per_label_read.items()))
    stats["per_label_kept"] = dict(sorted(per_label_kept.items()))
    stats["output"] = str(out_path)
    stats["output_sha256"] = digest.hexdigest()
    report_path.write_text(
        json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return stats


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("shards", nargs="+", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument(
        "--also-drop", action="append", default=[],
        help="an extra dataset label to drop (repeatable)",
    )
    args = parser.parse_args()
    for shard in args.shards:
        if not shard.is_file():
            print(f"error: missing shard {shard}", file=sys.stderr)
            return 2
    stats = convert(args.shards, args.out, args.report, frozenset(args.also_drop))
    print(json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
