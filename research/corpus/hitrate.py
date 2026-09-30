"""Top-3 next-word hit-rate on HELD-OUT CONVERSATIONAL text.

This is the bigram prototype's metric (scripts/bigram_pack.py), moved onto the register the
keyboard is actually used in.

Denominator (identical to the runtime rule, so the number measures a function the product
really has): every position inside a held-out line where the PREVIOUS token passed
normalize_word and stands in the same line. Line starts and positions after a rejected token
are not events. An event with no prediction counts as a MISS.

Three tables are compared on the same events:
  * shipped      -- the table in app/src/main/assets, as users have it today.
  * conv_only    -- a table built from the conversational TRAIN split alone, K = 4, H = 10 000.
  * shipped_plus -- shipped successors, with conversational successors appended for heads the
                    shipped table already has, and conversational heads added where shipped has
                    none. This is a LOWER bound on a true merged rebuild: it never re-ranks a
                    shipped head's existing cells, so it cannot show the re-ranking gain.

An exact merged rebuild is NOT possible from what is downloaded: the shipped asset stores the
ORDER of successors but not their counts, and the Leipzig sentence archives that produced them
are not present.
"""
from __future__ import annotations
import json, sys
from collections import Counter, defaultdict
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import corpuslib as CL, filters as F
import dictionary_coverage as cov
from stream import fast_normalizer
import pairs as P
from measure_filtered import collect_split
from measure_bigrams import shipped_table, H, K, DISPLAY

def build_conv_table(pairs, heads):
    """Top-K successors per conversational head, from the packed pair table."""
    head_ids = {pairs.ids[w] for w in heads if w in pairs.ids}
    words = pairs.words
    return {words[h]: [words[s] for s in succ]
            for h, succ in P.top_successors(pairs, head_ids, K).items()}

def main():
    tag = sys.argv[1]; paths = sys.argv[2:]
    lang = cov.language_for(tag); alpha = lang.alphabet
    shipped_dict, B = CL.load_shipped(tag)
    table = shipped_table(tag)
    split, freq, ev = collect_split(paths, tag)
    kept, _ = F.apply_filters(freq, ev, tag)

    merged = dict(shipped_dict)
    for w, c in kept.items():
        merged[w] = (merged[w] + c) if w in shipped_dict else c
    merged_top = dict(sorted(merged.items(), key=lambda kv: (-kv[1], kv[0]))[:100_000])
    conv_heads = {w for w, _ in sorted(merged_top.items(), key=lambda kv: (-kv[1], kv[0]))[:H]}

    conv_pairs = P.count_pairs(split.train(), alpha, set(merged_top))
    conv_tbl = build_conv_table(conv_pairs, conv_heads)

    shipped_tbl = {h: list(v) for h, v in table.successes_by_head.items()}
    plus = {h: list(v) for h, v in shipped_tbl.items()}
    for h, succ in conv_tbl.items():
        if h in plus:
            for s in succ:
                if s not in plus[h]:
                    plus[h].append(s)
        else:
            plus[h] = list(succ)

    tables = {"shipped": shipped_tbl, "conv_only": conv_tbl, "shipped_plus": plus}
    events = 0
    hits = {k: 0 for k in tables}
    covered = {k: 0 for k in tables}
    norm = fast_normalizer(alpha)
    for line, _source in split.held():
        prev = None
        for tok in (norm(chunk) for chunk in line.split()):
            if tok is None:
                prev = None; continue
            if prev is not None:
                events += 1
                for k, t in tables.items():
                    cells = t.get(prev, [])[:DISPLAY]
                    if cells:
                        covered[k] += 1
                        if tok in cells:
                            hits[k] += 1
            prev = tok
    res = {"language": tag, "held_lines": split.held_lines, "events": events,
           "conv_table_heads": len(conv_tbl),
           "conv_table_pairs": sum(len(v) for v in conv_tbl.values())}
    for k in tables:
        res[f"top3_hitrate_{k}_pct"] = round(100.0 * hits[k] / events, 4) if events else 0.0
        res[f"events_with_a_prediction_{k}_pct"] = round(100.0 * covered[k] / events, 4) if events else 0.0
    base = res["top3_hitrate_shipped_pct"]
    for k in tables:
        if k != "shipped":
            res[f"delta_{k}_pp"] = round(res[f"top3_hitrate_{k}_pct"] - base, 4)
    json.dump(res, sys.stdout, ensure_ascii=False, indent=2); print()

if __name__ == "__main__":
    main()
