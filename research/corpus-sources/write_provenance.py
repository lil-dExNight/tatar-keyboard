#!/usr/bin/env python3
"""Write text-only source bundles and their corpus provenance shards.

Run after fetch_sources.py. The bundles contain only config, upstream split, source row
number, and sentence text. They contain no audio path, clip identifier, speaker/client
identifier, demographic field, duration, or voice-input functionality.
"""
from __future__ import annotations

import hashlib
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build" / "corpus-sources"
PINS = ROOT / "data" / "corpus-sources" / "source-pins.json"
MANIFEST = BUILD / "sources.json"
OUT_DIR = ROOT / "data" / "provenance.d"
NOTICE = ROOT / "data" / "corpus-sources" / "ATTRIBUTION.txt"


def atomic_write(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_bytes(payload)
    temporary.replace(path)


def digest(path: Path) -> tuple[int, str]:
    h = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            h.update(chunk)
            size += len(chunk)
    return size, h.hexdigest()


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    pins = json.loads(PINS.read_text(encoding="utf-8"))
    grouped = defaultdict(list)
    for record in manifest["files"]:
        grouped[record["source"]].append(record)
    notices = []
    for source in ("tatsc", "common_voice"):
        records = sorted(grouped[source], key=lambda item: (item["split"], item["config"] or ""))
        bundle = BUILD / "text" / f"{source}.jsonl"
        parts = []
        counts = defaultdict(int)
        for record in records:
            text_path = ROOT / record["text_path"]
            for row_number, line in enumerate(text_path.read_text(encoding="utf-8").splitlines()):
                item = {
                    "config": record["config"],
                    "split": record["split"],
                    "source_row": row_number,
                    "text": line,
                }
                parts.append((json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"))
                counts[f"{record['config']}/{record['split']}"] += 1
        atomic_write(bundle, b"".join(parts))
        size, sha256 = digest(bundle)
        pin = pins[source]
        shard = {
            "id": f"{source}-tt-text-{pin['revision'][:12]}",
            "revision": pin["revision"],
            "size_bytes": size,
            "sha256": sha256,
            "license": pin["license"],
            "source_role": "Candidate text-only corpus for dictionary and next-word evaluation; no audio or speaker metadata.",
            "split_rule": "Preserve the upstream config and train/dev/test labels. Candidate training uses train only; dev is reserved; test is held out. Evaluation removes normalized test-line collisions from every training candidate.",
            "generation_command": (
                "python3 research/corpus-sources/fetch_sources.py --sources common_voice && "
                "build/corpus-sources/venv/bin/python research/corpus-sources/extract_tatsc_parquet.py && "
                "python3 research/corpus-sources/write_provenance.py"
                if source == "tatsc"
                else "python3 research/corpus-sources/fetch_sources.py --sources common_voice && python3 research/corpus-sources/write_provenance.py"
            ),
            "source_acquisition_command": (
                "build/corpus-sources/venv/bin/python research/corpus-sources/extract_tatsc_parquet.py"
                if source == "tatsc"
                else "python3 research/corpus-sources/fetch_sources.py --sources common_voice"
            ),
            "upstream": pin["upstream"],
            "upstream_page": pin["upstream_page"],
            "retrieval_dataset": pin["dataset"],
            "retrieval_revision": pin["revision"],
            "attribution": pin["attribution"],
            "bundle": str(bundle.relative_to(ROOT)),
            "split_rows": dict(sorted(counts.items())),
            "input_files": [
                {
                    key: value
                    for key, value in record.items()
                    if key in {"config", "split", "rows", "text_path", "text_size_bytes", "text_sha256", "raw_path", "raw_size_bytes", "raw_sha256"}
                }
                for record in records
            ],
            "contains_audio": False,
            "contains_speaker_metadata": False,
        }
        if source == "tatsc":
            shard["acquisition_dependency"] = {
                "name": "duckdb",
                "version": manifest.get("tatsc_extraction", {}).get("version", "2.0.0"),
                "scope": "acquisition-only remote Parquet text-column projection; not used by the app or asset packers",
            }
            shard["upstream_card_utterances"] = 271_914
            shard["retrieved_rows"] = sum(counts.values())
            shard["completeness_note"] = "The mirror exposes fewer rows than the upstream card declares. The retrieved count is used in every measurement; integration requires reconciliation with the official archive before claiming the full TatSC corpus."
        else:
            shard["retrieved_rows"] = sum(counts.values())
            shard["source_sentence_rule"] = "Use only the sentence column from the official train/dev/test TSV equivalents exposed by the mirror; discard all other columns. Invalidated, other, reported, and unvalidated inputs are excluded."
        destination = OUT_DIR / f"corpus-{source.replace('_', '-')}.json"
        atomic_write(destination, (json.dumps(shard, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8"))
        notices.append(
            f"{pin['upstream']}\n"
            f"License: {pin['license']}\n"
            f"Upstream: {pin['upstream_page']}\n"
            f"Retrieved from: https://huggingface.co/datasets/{pin['dataset']} at {pin['revision']}\n"
            f"Attribution: {pin['attribution']}\n"
            f"Provenance: {destination.relative_to(ROOT)}\n"
        )
        print(f"wrote {destination.relative_to(ROOT)}")
    atomic_write(NOTICE, ("\n".join(notices)).encode("utf-8"))
    print(f"wrote {NOTICE.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
