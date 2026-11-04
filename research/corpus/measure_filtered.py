"""Filtered measurement: coverage gain, new words, and a full account of what filtering cut.

Same held-out split as coverage_gain.py (one line in ten held out after dedup, the residue per
language in stream.HELD_RESIDUE; the Tatar residue matches the eval-set slice, so the
conversational frequency table never counts an eval line).
Filters are applied to TRAIN only; the held-out text is never filtered, because it is the
thing being predicted, not a source of frequencies.
"""
from __future__ import annotations
import json, sys
from collections import Counter
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import corpuslib as CL, filters as F
import dictionary_coverage as cov
from stream import Split, TRAIN, HELD, HELD_RESIDUE, fast_normalizer

def collect(paths, tag):
    lang = cov.language_for(tag); alpha = lang.alphabet
    residue = HELD_RESIDUE[tag]
    seen = set(); train_lines = []; held_lines = []
    for p in paths:
        with CL.open_text(Path(p)) as fh:
            for line in fh:
                k = line.strip()
                if not k or k in seen: continue
                seen.add(k)
                (held_lines if len(seen) % 10 == residue else train_lines).append(line)
    ev = F.CaseEvidence(); freq = Counter()
    for line in train_lines:
        first = True
        for chunk in line.split():
            w = chunk.strip(CL._EDGE)
            if not w:
                continue
            norm, _ = cov.normalize_word(w, alpha)
            if norm is not None:
                ev.observe(w, norm, first)
                freq[norm] += 1
            first = False
    return train_lines, held_lines, freq, ev


def collect_split(paths, tag):
    """Streaming twin of collect(): the same split and the same counts, without holding lines.

    Returns (split, freq, ev). ``selftest.py`` asserts the split is line-for-line identical to
    the one collect() builds; the frequency and case-evidence loops below are the same loops,
    reading from the stream instead of from a list.
    """
    lang = cov.language_for(tag); alpha = lang.alphabet
    norm = fast_normalizer(alpha)
    split = Split(paths, held_residue=HELD_RESIDUE[tag])
    ev = F.CaseEvidence(); freq = Counter()
    observe = ev.observe
    edge = CL._EDGE
    for line, _source in split.train():
        first = True
        for chunk in line.split():
            w = chunk.strip(edge)
            if not w:
                # A punctuation-only chunk does NOT consume the line-initial flag: the first
                # WORD of the line is what capitalization evidence must exclude, and a leading
                # dash or quote in a subtitle would otherwise make it look non-initial.
                continue
            nw = norm(w)
            if nw is not None:
                observe(w, nw, first); freq[nw] += 1
            first = False
    return split, freq, ev


def held_tokens(split, alpha):
    """Yield every normalized held-out token, in order (dict_tokens semantics)."""
    norm = fast_normalizer(alpha)
    edge = CL._EDGE
    for line, _source in split.held():
        for chunk in line.split():
            w = chunk.strip(edge)
            if w:
                nw = norm(w)
                if nw is not None:
                    yield nw

def main():
    tag = sys.argv[1]; paths = sys.argv[2:]
    lang = cov.language_for(tag); alpha = lang.alphabet
    shipped, B = CL.load_shipped(tag)
    split, freq, ev = collect_split(paths, tag)
    kept, removed = F.apply_filters(freq, ev, tag)

    out = {"language": tag, "train_lines": split.train_lines, "held_lines": split.held_lines,
           "boundary_B": B,
           "types_before_filter": len(freq), "tokens_before_filter": sum(freq.values()),
           "types_after_filter": len(kept), "tokens_after_filter": sum(kept.values()),
           "removed": {r: {"types": len(c), "tokens": sum(c.values()),
                           "examples": [w for w, _ in c.most_common(25)]}
                       for r, c in removed.items()}}

    def top100k(src, bound):
        m = dict(shipped)
        for w, c in src.items():
            m[w] = (m[w] + c) if w in shipped else (c + bound)
        return {w for w, _ in sorted(m.items(), key=lambda kv: (-kv[1], kv[0]))[:100_000]}

    dicts = {"shipped": set(shipped)}
    for bound, lab in ((0, "lower"), (B, "upper")):
        dicts[f"unfiltered_{lab}"] = top100k(freq, bound)
        dicts[f"filtered_{lab}"] = top100k(kept, bound)

    tot = 0; hit = {k: 0 for k in dicts}
    for w in held_tokens(split, alpha):
        tot += 1
        for k, d in dicts.items():
            if w in d: hit[k] += 1
    out["held_tokens"] = tot
    for k in dicts:
        out[f"coverage_{k}_pct"] = round(100.0 * hit[k] / tot, 4)
    base = out["coverage_shipped_pct"]
    for k in dicts:
        if k != "shipped":
            out[f"gain_{k}_pp"] = round(out[f"coverage_{k}_pct"] - base, 4)
    for bound, lab in ((0, "lower"), (B, "upper")):
        entered = dicts[f"filtered_{lab}"] - set(shipped)
        out[f"entered_top100k_filtered_{lab}"] = len(entered)
    json.dump(out, sys.stdout, ensure_ascii=False, indent=2); print()

if __name__ == "__main__":
    main()
