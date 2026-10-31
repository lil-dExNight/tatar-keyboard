"""What the merged data would COST in bytes, measured by actually packing a test asset.

Writes only into research/corpus/out/. The shipped assets under app/src/main/assets are read
and never written. The packer is the real one (scripts/dictionary_pack.py), so the numbers are
the real format's numbers, not an estimate: same schema, same zlib settings, same validation.
"""
from __future__ import annotations
import json, sys
from collections import Counter
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import corpuslib as CL, filters as F
import dictionary_coverage as cov
import dictionary_pack as dp
from measure_filtered import collect_split

OUT = Path(__file__).resolve().parent / "out"

def pack(entries, tag):
    """Serialize + compress through the shipped packer and return (raw, asset)."""
    raw = dp.serialize_entries(entries)
    return raw, dp.compress_raw(raw)

def main():
    tag = sys.argv[1]; paths = sys.argv[2:]
    lang = cov.language_for(tag)
    shipped, B = CL.load_shipped(tag)
    shipped_asset = CL.SHIPPED[tag].read_bytes()
    shipped_raw = dp.decompress_asset(shipped_asset, lang)

    _split, freq, ev = collect_split(paths, tag)
    kept, _ = F.apply_filters(freq, ev, tag)

    merged = dict(shipped)
    for w, c in kept.items():
        merged[w] = (merged[w] + c) if w in shipped else c
    top = sorted(merged.items(), key=lambda kv: (-kv[1], kv[0]))[:100_000]
    # the format stores entries in code-point order for binary search
    entries = sorted(top, key=lambda kv: kv[0])

    raw, asset = pack(entries, tag)
    res = {
        "language": tag,
        "shipped_asset_bytes": len(shipped_asset),
        "shipped_raw_bytes": len(shipped_raw),
        "merged_asset_bytes": len(asset),
        "merged_raw_bytes": len(raw),
        "delta_asset_bytes": len(asset) - len(shipped_asset),
        "delta_raw_bytes": len(raw) - len(shipped_raw),
        "entry_count": len(entries),
        "format_limit_raw": dp.MAX_UNCOMPRESSED_BYTES,
        "fits_raw": len(raw) <= dp.MAX_UNCOMPRESSED_BYTES,
    }
    (OUT / f"testasset_{tag}.tdict.zlib").write_bytes(asset)
    res["test_asset_written_to"] = str((OUT / f"testasset_{tag}.tdict.zlib").relative_to(Path.cwd()))
    json.dump(res, sys.stdout, ensure_ascii=False, indent=2); print()

if __name__ == "__main__":
    main()
