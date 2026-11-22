#!/usr/bin/env python3
"""Write provenance shards for the gitignored research/corpus training dumps.

The ``research/corpus`` dumps (OpenSubtitles, Tatoeba, QED, TED2020) are gitignored upstream
inputs that train the dictionary, bigram, and companion assets. Each shard records the dump's
measured size and SHA-256, license, source role, split rule, and the ``research/corpus/dl.sh``
acquisition command. Every dump is cross-checked against its explicit acquisition pin and
converted-corpus pin mapping in ``CORPUS_DUMPS``; a drifted dump, a missing mapping, or a
dangling converted pin fails the build before any shard is rewritten.

Only dumps actually present on disk get a freshly written shard. A shard for a dump that is
merely absent locally is kept: the registry declares the dump, and
``scripts/provenance.py`` requires every registry dump to stay declared even in a clean
checkout without corpora. Shards whose dump has left the registry are deleted.
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

# research/corpus dump files that train the Tatar pipeline, with the license the corpus
# notices record and the acquisition command research/corpus/dl.sh runs.
#
# Each dump carries an explicit pin mapping; raw dump names are never looked up among the
# converted-corpus keys of data/corpus-manifest.json (that lookup could only miss and used
# to disable the cross-check silently):
# - "acquisition_pin": the expected size and SHA-256 of the raw bytes dl.sh fetches. The pin
#   mirrors the dump's committed provenance shard in data/provenance.d (and, for the two
#   conversational dumps, input_provenance.current_files in
#   data/corpus-sources/comparison.json). A deliberate corpus update moves every record in
#   one reviewed change; a drifted dump fails instead of being blessed.
# - "converted_pins": the data/corpus-manifest.json keys of the converted training streams
#   the dump feeds. The review-only dumps (QED, TED2020) feed none.
CORPUS_DUMPS: dict[str, dict[str, object]] = {
    "OpenSubtitles-v2024.tt.txt.gz": {
        "license": "LicenseRef-OpenSubtitles",
        "split_rule": "training input; held-out lines excluded from evaluation by the eval builders",
        "role": "raw_corpus",
        "acquisition_pin": {
            "size_bytes": 1729531,
            "sha256": "4324a04983c8bed56ee07e5627410ae7caa20a76477a8ecfd474822420d2d740",
        },
        "converted_pins": ("tt_conv_train90-sentences.txt",),
    },
    "Tatoeba-v2026-10-31.tt.txt.gz": {
        "license": "CC-BY-2.0-FR",
        "split_rule": "training input; Tatoeba heldout rows id % 10 == 1 form the development set",
        "role": "raw_corpus",
        "acquisition_pin": {
            "size_bytes": 1892694,
            "sha256": "9ed9b8cdb35268b89960a89f006df7b7099aaf56999eead7edb5542b0253d3e1",
        },
        "converted_pins": ("tt_conv_train90-sentences.txt",),
    },
    "QED-v2.0a.tt.txt.gz": {
        "license": "CC-BY-SA-4.0",
        "split_rule": "training input; not split upstream",
        "role": "raw_corpus",
        "acquisition_pin": {
            "size_bytes": 34607,
            "sha256": "e1a26d3c5dc7a256e0808dd1449f3d1ab1942c37a745026a012f985e9ab48ddd",
        },
        "converted_pins": (),
    },
    "TED2020-v1.tt.txt.gz": {
        "license": "CC-BY-4.0",
        "split_rule": "training input; not split upstream",
        "role": "raw_corpus",
        "acquisition_pin": {
            "size_bytes": 9124,
            "sha256": "979610c6ebbf2f6d6cf6d49d0a7218485ac9cd1ebd4e9964a06cb4775e429be2",
        },
        "converted_pins": (),
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


def build_corpus_dump_shards(
    root: Path, dumps: dict[str, dict[str, object]] | None = None
) -> dict[str, object]:
    specs = CORPUS_DUMPS if dumps is None else dumps
    manifest_path = root / "data" / "corpus-manifest.json"
    provenance_dir = root / "data" / "provenance.d"
    corpus_dir = root / "research" / "corpus"
    manifest = (
        json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}
    )

    # Validate every present dump before touching any shard: a drifted dump, a missing pin
    # mapping, or a dangling converted pin fails with the committed shards left untouched.
    measured: dict[str, tuple[int, str]] = {}
    for name, meta in specs.items():
        dump = corpus_dir / name
        if not dump.is_file():
            continue
        pin = meta.get("acquisition_pin")
        if not isinstance(pin, dict):
            raise ProvenanceBuildError(
                f"{name}: no acquisition pin mapping; map each raw dump to its acquisition "
                "or converted-corpus pin explicitly"
            )
        for converted in meta.get("converted_pins", ()):
            if converted not in manifest:
                raise ProvenanceBuildError(
                    f"{name}: converted-corpus pin {converted!r} is missing from "
                    "data/corpus-manifest.json"
                )
        size, sha256 = _sha256(dump)
        if pin.get("size_bytes") != size:
            raise ProvenanceBuildError(
                f"{name}: dump size differs from its acquisition pin "
                f"({pin.get('size_bytes')} != {size})"
            )
        if pin.get("sha256") != sha256:
            raise ProvenanceBuildError(
                f"{name}: dump SHA-256 differs from its acquisition pin "
                f"({str(pin.get('sha256'))[:12]}… != {sha256[:12]}…)"
            )
        measured[name] = (size, sha256)

    written: list[str] = []
    removed: list[str] = []
    for name, meta in specs.items():
        stem = name.replace(".txt.gz", "").lower()
        shard_path = provenance_dir / f"corpus-dump-{stem}.json"
        if name not in measured:
            # Absent locally: the registry still requires this dump, so its declaration and
            # acquisition pin stay committed; provenance reports the dump as not present.
            continue
        size, sha256 = measured[name]
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

    # A shard declaring a dump that left the registry is a stale declaration: provenance
    # rejects it as undeclared, so drop it. Anything this builder does not own is left alone.
    prefix = corpus_dir.relative_to(root).as_posix()
    declared = {f"{prefix}/{name}" for name in specs}
    for shard_file in sorted(provenance_dir.glob("corpus-dump-*.json")):
        if shard_file.name in written:
            continue
        try:
            recorded = json.loads(shard_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        recorded_path = recorded.get("path") if isinstance(recorded, dict) else None
        if not isinstance(recorded_path, str) or not recorded_path.startswith(f"{prefix}/"):
            continue
        if recorded_path in declared:
            continue
        shard_file.unlink()
        removed.append(shard_file.name)
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
