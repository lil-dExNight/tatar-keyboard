#!/usr/bin/env python3
"""Write provenance shards for the gitignored research/corpus training dumps.

The ``research/corpus`` dumps (OpenSubtitles, Tatoeba, QED, TED2020) are gitignored upstream
inputs that train the dictionary, bigram, and companion assets. Each shard records the dump's
measured size and SHA-256, license, source role, split rule, and the ``research/corpus/dl.sh``
acquisition command. Dumps whose bytes are pinned in ``data/corpus-manifest.json`` are
cross-checked against that pin; a drifted dump fails the build rather than being blessed.

Only dumps actually present on disk get a shard; a shard for a removed dump is deleted.
The committed final-evaluation set is built separately by
``research/corpus/acquire_common_voice_final.py``.

Run after ``research/corpus/dl.sh``. Exits 2 on a drifted or unreadable dump; writes are atomic.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "data" / "corpus-manifest.json"
PROVENANCE_DIR = ROOT / "data" / "provenance.d"
CORPUS_DIR = ROOT / "research" / "corpus"

# research/corpus dump files that train the Tatar pipeline, with the license the corpus
# notices record and the acquisition command research/corpus/dl.sh runs.
CORPUS_DUMPS: dict[str, dict[str, str]] = {
    "OpenSubtitles-v2024.tt.txt.gz": {
        "license": "LicenseRef-OpenSubtitles-No-Grant",
        "split_rule": "training input; held-out lines excluded from evaluation by the eval builders",
        "role": "raw_corpus",
    },
    "Tatoeba-v2026-10-31.tt.txt.gz": {
        "license": "CC-BY-2.0-FR",
        "split_rule": "training input; Tatoeba heldout rows id % 10 == 1 form the development set",
        "role": "raw_corpus",
    },
    "QED-v2.0a.tt.txt.gz": {
        "license": "CC-BY-SA-4.0",
        "split_rule": "training input; not split upstream",
        "role": "raw_corpus",
    },
    "TED2020-v1.tt.txt.gz": {
        "license": "CC-BY-4.0",
        "split_rule": "training input; not split upstream",
        "role": "raw_corpus",
    },
}


class ProvenanceBuildError(ValueError):
    """A missing, malformed, or drifted corpus dump."""


def _sha256(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
            size += len(block)
    return size, digest.hexdigest()


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_bytes(data)
    temporary.replace(path)


def build_corpus_dump_shards(root: Path) -> dict[str, object]:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.is_file() else {}
    written: list[str] = []
    removed: list[str] = []
    for name, meta in CORPUS_DUMPS.items():
        stem = name.replace(".txt.gz", "").lower()
        shard_path = PROVENANCE_DIR / f"corpus-dump-{stem}.json"
        dump = CORPUS_DIR / name
        if not dump.is_file():
            if shard_path.exists():
                shard_path.unlink()
                removed.append(shard_path.name)
            continue
        size, sha256 = _sha256(dump)
        pin = manifest.get(name)
        if pin is not None and (pin.get("size") != size or pin.get("sha256") != sha256):
            raise ProvenanceBuildError(
                f"{name}: dump differs from the corpus manifest pin "
                f"(size {pin.get('size')}/{str(pin.get('sha256'))[:12]}…, "
                f"got {size}/{sha256[:12]}…)"
            )
        shard = {
            "id": f"corpus-dump-{stem}",
            "revision": name.replace(".txt.gz", ""),
            "size_bytes": size,
            "sha256": sha256,
            "license": meta["license"],
            "source_role": meta["role"],
            "split_rule": meta["split_rule"],
            "generation_command": f"research/corpus/dl.sh  # {name}",
            "path": f"research/corpus/{name}",
        }
        _atomic_write(
            shard_path,
            (json.dumps(shard, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode(
                "utf-8"
            ),
        )
        written.append(shard_path.name)
    return {"written": written, "removed": removed}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.parse_args(argv)
    try:
        report = build_corpus_dump_shards(ROOT)
    except (ProvenanceBuildError, OSError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
