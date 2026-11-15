#!/usr/bin/env python3
"""Extract only TatSC text columns from pinned remote Parquet files.

This is the fast acquisition path. DuckDB 2.0.0 performs HTTP range reads and projects only
the text column; audio, duration, file_id, and speaker_id columns are neither materialized nor
written. DuckDB is an acquisition-time tool, not an application or asset-pipeline dependency.

Reproduce:
    python3 -m venv build/corpus-sources/venv
    build/corpus-sources/venv/bin/pip install duckdb==2.0.0
    python3 research/corpus-sources/fetch_sources.py --sources common_voice
    build/corpus-sources/venv/bin/python research/corpus-sources/extract_tatsc_parquet.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import duckdb

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build" / "corpus-sources"
PINS = ROOT / "data" / "corpus-sources" / "source-pins.json"
MANIFEST = BUILD / "sources.json"
sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch_sources import atomic_write, clean_sentence, file_digest, request_json  # noqa: E402


def main() -> int:
    pins = json.loads(PINS.read_text(encoding="utf-8"))
    tatsc = pins["tatsc"]
    expected_duckdb = tatsc["acquisition_dependency"]["version"]
    if duckdb.__version__ != expected_duckdb:
        raise RuntimeError(f"duckdb {duckdb.__version__} is active; pinned {expected_duckdb}")
    dataset = tatsc["dataset"]
    revision = tatsc["revision"]
    metadata = request_json(f"https://huggingface.co/api/datasets/{dataset}")
    if metadata["sha"] != revision:
        raise RuntimeError(f"TatSC is at {metadata['sha']}, pinned {revision}")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {
        "schema": 1,
        "generated_by": "research/corpus-sources/fetch_sources.py",
        "datasets": {},
        "files": [],
    }
    records = [record for record in manifest["files"] if record["source"] != "tatsc"]
    connection = duckdb.connect()
    connection.execute("set threads = 2")
    for config, splits in tatsc["splits"].items():
        for split, expected_rows in splits.items():
            remote_path = f"{config}/{split}.parquet"
            url = f"https://huggingface.co/datasets/{dataset}/resolve/{revision}/{remote_path}"
            rows = connection.execute("select text from read_parquet(?)", [url]).fetchall()
            if len(rows) != expected_rows:
                raise RuntimeError(f"{config}/{split}: {len(rows)} rows, pinned {expected_rows}")
            texts = []
            for (text,) in rows:
                if not isinstance(text, str) or not text.strip():
                    raise RuntimeError(f"{config}/{split}: empty or non-text transcript")
                texts.append(clean_sentence(text))
            output = BUILD / "text" / "tatsc" / f"tt-{config}-{split}.txt"
            atomic_write(output, ("\n".join(texts) + "\n").encode("utf-8"))
            size, digest = file_digest(output)
            records.append(
                {
                    "source": "tatsc",
                    "config": config,
                    "split": split,
                    "rows": len(texts),
                    "text_path": str(output.relative_to(ROOT)),
                    "text_size_bytes": size,
                    "text_sha256": digest,
                    "remote_parquet": remote_path,
                    "extraction": f"duckdb {duckdb.__version__} remote column projection: SELECT text",
                }
            )
            print(f"tatsc {config}/{split}: {len(texts)} sentences", flush=True)
    manifest["datasets"]["tatsc"] = {
        "dataset": dataset,
        "revision": revision,
        "license": tatsc["license"],
    }
    manifest["tatsc_extraction"] = {
        "tool": "duckdb",
        "version": duckdb.__version__,
        "command": "build/corpus-sources/venv/bin/python research/corpus-sources/extract_tatsc_parquet.py",
        "projected_columns": ["text"],
    }
    manifest["files"] = sorted(records, key=lambda record: (record["source"], record["config"] or "", record["split"]))
    atomic_write(MANIFEST, (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8"))
    print(f"wrote {MANIFEST.relative_to(ROOT)}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
