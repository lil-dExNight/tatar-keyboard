"""Convert conversational corpora into the Leipzig ``id<TAB>sentence`` shape the
bigram packer (``scripts/bigram_asset_pack.py``) accepts, with line deduplication.

Why this exists. ``parse_sentence_row`` (scripts/bigram_pack.py) is deliberately strict:
exactly two tab-separated fields, the first a positive decimal id. The conversational
dumps (Tatoeba, OpenSubtitles) are plain one-sentence-per-line text. This script is the
only place where they meet.

Rules, each chosen to match an already-reviewed rule rather than invent a new one:

* DEDUP FIRST, by ``line.strip()`` exactly as ``stream.Split`` does (the Russian
  OpenSubtitles dump is close to half duplicate lines; left in, they would multiply the
  pair counts of repeated subtitle lines). Keys are the same
  deterministic 64-bit BLAKE2b digests as ``bigset.line_key`` -- same collision
  accounting applies.
* FILE ORDER IS SIGNIFICANT and mirrors every corpus measurement of the project:
  Tatoeba first, OpenSubtitles second; a duplicate is credited to the earlier file.
* Every surviving line is renumbered 1..N, so the result is a valid strict Leipzig-style
  input. Tabs inside a line are replaced by a single space (there are none in the
  dumps at hand, but the packer's parser must never see a third field).
* Nothing is filtered by content: pair-level restriction to the shipped vocabulary
  happens inside the packer, unchanged.

Usage:

    python3 make_conv_train.py OUT.txt IN1.txt.gz [IN2.txt.gz ...]

Prints a small JSON report (per-file and total line counts, duplicates, output SHA-256)
to stdout. stdlib only.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import corpuslib as CL  # noqa: E402
from bigset import HashSet64, line_key  # noqa: E402


def main(argv: list[str]) -> int:
    out_path = Path(argv[0])
    in_paths = [Path(p) for p in argv[1:]]
    seen = HashSet64(max(1 << 16, sum(p.stat().st_size for p in in_paths) // 55))
    digest = hashlib.sha256()
    per_file = []
    next_id = 0
    with out_path.open("wb") as out:
        for path in in_paths:
            total = unique = 0
            with CL.open_text(path) as handle:
                for line in handle:
                    total += 1
                    key = line.strip()
                    if not key or not seen.add(line_key(key)):
                        continue
                    unique += 1
                    next_id += 1
                    row = f"{next_id}\t{key.replace(chr(9), ' ')}\n".encode("utf-8")
                    digest.update(row)
                    out.write(row)
            per_file.append(
                {"file": path.name, "lines": total, "unique_kept": unique,
                 "duplicates_dropped": total - unique}
            )
    report = {
        "output": str(out_path),
        "output_bytes": out_path.stat().st_size,
        "output_sha256": digest.hexdigest(),
        "output_lines": next_id,
        "inputs": per_file,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
