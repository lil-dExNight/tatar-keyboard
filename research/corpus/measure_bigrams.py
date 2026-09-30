"""What a conversational corpus would add to the SHIPPED bigram table.

TEST MEASUREMENT ONLY -- no asset under app/src/main/assets is written or replaced.

Pair extraction uses the bigram packer's adjacency rule verbatim (bigram_tokens): whitespace
split, and a token rejected by normalize_word BREAKS adjacency instead of being transparent, so
pairs counted here are comparable with the ones already in the shipped table. Self-pairs are
dropped, as in the packer.

Two separate questions are answered, because they have different answers:
  1. With heads UNCHANGED, how many conversational pairs are new successors, and how many
     would actually change the three cells the strip displays?
  2. With heads RECOMPUTED from merged unigram frequencies at H = 10 000, which heads enter?
     This is the question that matters, because imperatives get no predictions due to head
     selection, not due to the adjacency rule.
"""
from __future__ import annotations
import json, sys
from collections import Counter, defaultdict
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import corpuslib as CL, filters as F
import dictionary_coverage as cov
import bigram_asset_pack as bp
import pairs as P
from measure_filtered import collect, collect_split

H = 10_000
DISPLAY = 3          # TatBigrPrefixIndex.MAX_RESULTS
K = 4                # shipped successors per head

SHIPPED_BIGRAMS = {
    "tat": "app/src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib",
    "rus": "app/src/main/assets/bigrams/russian_bigrams_v1.tatbigr.zlib",
}

def shipped_table(tag):
    p = Path(__file__).resolve().parents[2] / SHIPPED_BIGRAMS[tag]
    return bp.validate_raw(bp.decompress(p.read_bytes()))

def count_pairs(lines, alpha, vocab):
    pairs = Counter()
    for line in lines:
        prev = None
        for tok in CL.bigram_tokens(line, alpha):
            if tok is None:
                prev = None; continue
            if prev is not None and prev != tok and prev in vocab and tok in vocab:
                pairs[(prev, tok)] += 1
            prev = tok
    return pairs

def main():
    tag = sys.argv[1]; paths = sys.argv[2:]
    lang = cov.language_for(tag); alpha = lang.alphabet
    shipped_dict, B = CL.load_shipped(tag)
    table = shipped_table(tag)
    split, freq, ev = collect_split(paths, tag)
    kept, removed = F.apply_filters(freq, ev, tag)

    # Vocabulary rule of the packer: both ends must be in the SHIPPED top-100k. For the "merged"
    # variant the vocabulary is the merged top-100k (lower bound -- the conservative one).
    merged = dict(shipped_dict)
    for w, c in kept.items():
        merged[w] = (merged[w] + c) if w in shipped_dict else c
    merged_top = {w for w, _ in sorted(merged.items(), key=lambda kv: (-kv[1], kv[0]))[:100_000]}

    conv_pairs = P.count_pairs(split.train(), alpha, set(shipped_dict))
    words = conv_pairs.words; ids = conv_pairs.ids
    out = {
        "language": tag,
        "shipped_heads": len(table.head_words),
        "shipped_pairs": sum(len(v) for v in table.successes_by_head.values()),
        "conv_pair_instances": conv_pairs.instances,
        "conv_distinct_pairs": len(conv_pairs),
    }

    # --- Q1: heads unchanged ---
    heads = set(table.head_words)
    head_ids = {ids[w] for w in heads if w in ids}
    shipped_succ_ids = {ids[h]: {ids[s] for s in succ if s in ids}
                        for h, succ in table.successes_by_head.items() if h in ids}
    new_succ = 0; pairs_on_existing_heads = 0; heads_touched = set()
    for head_id, succ_id, _count in conv_pairs.items():
        if head_id in head_ids:
            pairs_on_existing_heads += 1
            heads_touched.add(head_id)
            if succ_id not in shipped_succ_ids.get(head_id, ()):
                new_succ += 1
    out["conv_pairs_whose_head_is_a_shipped_head"] = pairs_on_existing_heads
    out["conv_pairs_that_are_new_successors"] = new_succ
    out["shipped_heads_touched_by_corpus"] = len(heads_touched)

    # would the displayed three cells change? merge counts: shipped order is known but shipped
    # COUNTS are not stored in the asset, so we can only report displacement candidates.
    # One grouped pass over the pair table, not one pass per head: at Russian OpenSubtitles
    # scale a per-head rescan would be ten thousand full scans.
    challengers = P.top_successors(conv_pairs, heads_touched, DISPLAY)
    changed = 0
    for head_id in heads_touched:
        cur = table.successes_by_head.get(words[head_id], [])[:DISPLAY]
        cand = [words[i] for i in challengers.get(head_id, ())]
        if cand and cand != cur:
            changed += 1
    out["shipped_heads_whose_top3_has_a_conversational_challenger"] = changed

    # --- Q2: heads recomputed at H = 10 000 from merged unigram frequencies ---
    def top_h(freqmap):
        return [w for w, _ in sorted(freqmap.items(), key=lambda kv: (-kv[1], kv[0]))[:H]]
    base_heads = set(top_h(shipped_dict))
    merged_heads = set(top_h({w: merged[w] for w in merged_top}))
    entering = merged_heads - base_heads
    out["heads_entering_at_H10000"] = len(entering)
    out["heads_leaving_at_H10000"] = len(base_heads - merged_heads)
    # Tie-break by word: `entering` is a set, so ordering ties by frequency alone made
    # this list differ between runs of the same command.
    ranked = sorted(entering, key=lambda w: (-kept.get(w, 0), w))
    out["heads_entering_examples"] = [
        {"word": w, "conv_freq": kept.get(w, 0), "in_shipped_dict": w in shipped_dict}
        for w in ranked[:40]
    ]
    # do the entering heads actually get successors from the conversational corpus?
    heads_with_any_pair = {head_id for head_id, _s, _c in conv_pairs.items()}
    with_succ = sum(1 for w in entering if ids.get(w, 0) in heads_with_any_pair)
    out["entering_heads_that_get_at_least_one_successor"] = with_succ
    json.dump(out, sys.stdout, ensure_ascii=False, indent=2); print()

if __name__ == "__main__":
    main()
