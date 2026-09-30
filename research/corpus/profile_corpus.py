"""Profile a conversational corpus: volume, duplication, token yield. Measurement only."""
from __future__ import annotations
import sys, json, hashlib
from collections import Counter
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import corpuslib as CL
import dictionary_coverage as cov
from bigset import HashSet64, line_key
from stream import fast_normalizer

def profile(path: Path, tag: str) -> dict:
    lang = cov.language_for(tag)
    alpha = lang.alphabet
    norm = fast_normalizer(alpha)
    lines = 0; chars = 0
    # A Python set of the corpus's own lines does not fit in memory for the Russian
    # OpenSubtitles file; HashSet64 stores an 8-byte digest per unique line instead.
    seen = HashSet64(max(1 << 16, path.stat().st_size // 55)); dup_lines = 0
    raw_tokens = 0; kept = 0
    h = hashlib.sha256()
    with CL.open_text(path) as fh:
        for line in fh:
            h.update(line.encode("utf-8", "replace"))
            line = line.rstrip("\n")
            lines += 1; chars += len(line)
            key = line.strip()
            if not seen.add(line_key(key)):
                dup_lines += 1
            for chunk in line.split():
                raw_tokens += 1
                w = chunk.strip(CL._EDGE)
                if w and norm(w) is not None:
                    kept += 1
    return {
        "file": path.name,
        "sha256": h.hexdigest(),
        "bytes_gz": path.stat().st_size,
        "lines": lines,
        "chars": chars,
        "duplicate_lines": dup_lines,
        "unique_lines": len(seen),
        "dup_pct": round(100.0 * dup_lines / lines, 3) if lines else 0.0,
        "raw_tokens": raw_tokens,
        "tokens_kept": kept,
        "keep_pct": round(100.0 * kept / raw_tokens, 3) if raw_tokens else 0.0,
    }

if __name__ == "__main__":
    tag = sys.argv[1]
    out = [profile(Path(p), tag) for p in sys.argv[2:]]
    print(json.dumps(out, ensure_ascii=False, indent=2))
