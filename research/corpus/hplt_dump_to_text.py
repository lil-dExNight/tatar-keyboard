#!/usr/bin/env python3
"""One-off conversion of the HPLT 2.0 tat_Cyrl parquet shards to plain text.

NOT part of the stdlib asset pipeline: it needs pyarrow and runs once per dump, with the
pyarrow venv interpreter. The asset pipeline only ever reads the resulting plain-text file.

Document filter, applied before any text is written: the document-level language list must
give tat_Cyrl a probability of at least MIN_TAT_PROB, and at least MIN_TAT_SEG_SHARE of the
segment-level language labels must be tat_Cyrl. The shard set is already the tat_Cyrl slice
of the corpus, so this removes the residual mixed-language documents; residual Russian
*words* inside kept documents are removed later by the per-word bleed filter.

Output: one document per line (internal newlines become spaces), plus a JSON report.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

MIN_TAT_PROB = 0.9
MIN_TAT_SEG_SHARE = 0.5

TAT_LANG = "tat_Cyrl"


def convert(shards: list[Path], out_path: Path, report_path: Path) -> dict:
    import pyarrow.parquet as pq  # the one non-stdlib import; see module docstring

    stats = {
        "shards": len(shards),
        "docs_read": 0,
        "docs_kept": 0,
        "docs_dropped_low_prob": 0,
        "docs_dropped_low_seg_share": 0,
        "kept_bytes": 0,
        "filter": {"min_tat_prob": MIN_TAT_PROB, "min_tat_seg_share": MIN_TAT_SEG_SHARE},
    }
    digest = hashlib.sha256()
    with out_path.open("w", encoding="utf-8", newline="\n") as out:
        for shard in shards:
            parquet = pq.ParquetFile(shard)
            for batch in parquet.iter_batches(
                batch_size=20000, columns=["lang", "prob", "seg_langs", "text"]
            ):
                langs = batch.column("lang").to_pylist()
                probs = batch.column("prob").to_pylist()
                seg_langs = batch.column("seg_langs").to_pylist()
                texts = batch.column("text").to_pylist()
                for doc_langs, doc_probs, doc_segs, text in zip(
                    langs, probs, seg_langs, texts
                ):
                    stats["docs_read"] += 1
                    tat_prob = (
                        doc_probs[doc_langs.index(TAT_LANG)]
                        if TAT_LANG in doc_langs
                        else 0.0
                    )
                    if tat_prob < MIN_TAT_PROB:
                        stats["docs_dropped_low_prob"] += 1
                        continue
                    share = (
                        doc_segs.count(TAT_LANG) / len(doc_segs) if doc_segs else 0.0
                    )
                    if share < MIN_TAT_SEG_SHARE:
                        stats["docs_dropped_low_seg_share"] += 1
                        continue
                    line = " ".join(text.split()) + "\n"
                    encoded = line.encode("utf-8")
                    digest.update(encoded)
                    out.write(line)
                    stats["docs_kept"] += 1
                    stats["kept_bytes"] += len(encoded)
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
    args = parser.parse_args()
    for shard in args.shards:
        if not shard.is_file():
            print(f"error: missing shard {shard}", file=sys.stderr)
            return 2
    stats = convert(args.shards, args.out, args.report)
    print(json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
