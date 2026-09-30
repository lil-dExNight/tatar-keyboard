"""Measure what a conversational corpus would add to the SHIPPED top-100k dictionary.

TEST ARTIFACTS ONLY. Reads app/src/main/assets/... but never writes there.

Method, and why each step is what it is:

* The shipped asset carries the summed Leipzig frequency of exactly its 100 000 entries.
  Words Leipzig knows but ranked below the cutoff are NOT in the asset, so their written
  frequency is unknown -- but it is bounded: it is at most the boundary frequency B
  (10 for tat, 20 for rus), otherwise they would have made the cutoff.
* Therefore a merged frequency is an INTERVAL, not a point:
      w in shipped:      merged = shipped[w] + conv[w]                (exact)
      w not in shipped:  merged in [conv[w], conv[w] + B]             (bounded)
  Everything below is reported at BOTH ends of that interval, so no number here depends on
  a guess about data we do not have.
"""
from __future__ import annotations
import json, sys
from collections import Counter
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import corpuslib as CL
import dictionary_coverage as cov
from bigset import HashSet64, line_key
from stream import fast_normalizer

def count_conv(paths, tag, dedup_lines=True):
    lang = cov.language_for(tag); alpha = lang.alphabet
    norm = fast_normalizer(alpha); edge = CL._EDGE
    freq = Counter(); per_source = {}
    lines_used = 0; lines_skipped = 0
    for p in paths:
        p = Path(p); local = Counter()
        # Per-file dedup, keyed by an 8-byte digest, because a set of the
        # Russian OpenSubtitles lines themselves does not fit in memory.
        seen = HashSet64(max(1 << 16, p.stat().st_size // 55))
        with CL.open_text(p) as fh:
            for line in fh:
                key = line.strip()
                if dedup_lines and not seen.add(line_key(key)):
                    lines_skipped += 1; continue
                lines_used += 1
                for chunk in line.split():
                    w = chunk.strip(edge)
                    if not w:
                        continue
                    nw = norm(w)
                    if nw is not None:
                        local[nw] += 1
        per_source[p.name] = {"types": len(local), "tokens": sum(local.values())}
        freq.update(local)
    return freq, per_source, lines_used, lines_skipped

def rank_map(freq):
    order = sorted(freq.items(), key=lambda kv: (-kv[1], kv[0]))
    return {w: i + 1 for i, (w, _) in enumerate(order)}

def main():
    tag = sys.argv[1]; paths = sys.argv[2:]
    shipped, B = CL.load_shipped(tag)
    conv, per_source, lines_used, lines_skipped = count_conv(paths, tag)

    in_shipped = {w: c for w, c in conv.items() if w in shipped}
    outside    = {w: c for w, c in conv.items() if w not in shipped}

    def merged(bound):
        m = dict(shipped)
        for w, c in conv.items():
            m[w] = (m[w] + c) if w in shipped else (c + bound)
        return m

    res = {
        "language": tag,
        "boundary_frequency_B": B,
        "shipped_entries": len(shipped),
        "corpus_lines_used": lines_used,
        "corpus_duplicate_lines_skipped": lines_skipped,
        "corpus_types": len(conv),
        "corpus_tokens": sum(conv.values()),
        "per_source": per_source,
        "types_already_in_shipped": len(in_shipped),
        "types_outside_shipped": len(outside),
        "tokens_from_types_outside_shipped": sum(outside.values()),
    }

    base_rank = rank_map(shipped)
    for bound, label in ((0, "lower"), (B, "upper")):
        m = merged(bound)
        new_rank = rank_map(m)
        top = sorted(m.items(), key=lambda kv: (-kv[1], kv[0]))[:100_000]
        top_words = {w for w, _ in top}
        entered = [w for w in top_words if w not in shipped]
        displaced = [w for w in shipped if w not in top_words]
        res[f"entered_top100k_{label}"] = len(entered)
        res[f"displaced_from_top100k_{label}"] = len(displaced)
        res[f"rank_improved_count_{label}"] = sum(
            1 for w in shipped if new_rank.get(w, 10**9) < base_rank[w]
        )
    json.dump(res, sys.stdout, ensure_ascii=False, indent=2)
    print()

if __name__ == "__main__":
    main()
