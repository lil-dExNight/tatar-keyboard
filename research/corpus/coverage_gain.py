"""Held-out coverage on CONVERSATIONAL text: shipped dictionary vs dictionary + corpus.

Split rule, fixed before any number was read: line index % 10 == 0 is HELD OUT, the other
nine tenths are TRAIN. The split is by line and deterministic, so the test text never
contributes to the frequencies being tested. Duplicate lines are dropped BEFORE splitting,
so a subtitle repeated across uploads cannot appear in both halves.

Denominator: every held-out token that survives dict_tokens. A token is COVERED if the word
is present in the dictionary being tested (the shipped 100k, or the merged 100k). This is
word-completion coverage: a word absent from the dictionary can never be suggested.
"""
from __future__ import annotations
import json, sys
from collections import Counter
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import corpuslib as CL
import dictionary_coverage as cov
from stream import Split

def split_lines(paths):
    """The same split, streamed. Superseded by measure_filtered.py, which also reports what the
    filter removed; kept as the plainest statement of the split rule."""
    split = Split(paths)
    return ([line for line, _ in split.train()], [line for line, _ in split.held()])

def main():
    tag = sys.argv[1]; paths = sys.argv[2:]
    lang = cov.language_for(tag); alpha = lang.alphabet
    shipped, B = CL.load_shipped(tag)
    train, held = split_lines(paths)

    tr = Counter()
    for line in train:
        for w in CL.dict_tokens(line, alpha):
            tr[w] += 1

    out = {"language": tag, "train_lines": len(train), "held_lines": len(held),
           "train_tokens": sum(tr.values()), "boundary_B": B}

    def top100k(bound):
        m = dict(shipped)
        for w, c in tr.items():
            m[w] = (m[w] + c) if w in shipped else (c + bound)
        return {w for w, _ in sorted(m.items(), key=lambda kv: (-kv[1], kv[0]))[:100_000]}

    dicts = {"shipped": set(shipped)}
    for bound, label in ((0, "merged_lower"), (B, "merged_upper")):
        dicts[label] = top100k(bound)

    tot = 0; hit = {k: 0 for k in dicts}
    miss_shipped = Counter()
    for line in held:
        for w in CL.dict_tokens(line, alpha):
            tot += 1
            for k, d in dicts.items():
                if w in d:
                    hit[k] += 1
            if w not in dicts["shipped"]:
                miss_shipped[w] += 1
    out["held_tokens"] = tot
    for k in dicts:
        out[f"coverage_{k}_pct"] = round(100.0 * hit[k] / tot, 4) if tot else 0.0
    out["gain_lower_pp"] = round(out["coverage_merged_lower_pct"] - out["coverage_shipped_pct"], 4)
    out["gain_upper_pp"] = round(out["coverage_merged_upper_pct"] - out["coverage_shipped_pct"], 4)
    out["top_missing_from_shipped"] = miss_shipped.most_common(40)
    json.dump(out, sys.stdout, ensure_ascii=False, indent=2); print()

if __name__ == "__main__":
    main()
