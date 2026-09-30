"""One corpus pass per language: the conversational frequency table and every acceptance number.

The pass is expensive (the Russian corpus takes minutes just to read), so it does everything
at once and leaves two artifacts:

1. `data/dictionary/dict-accept/conv-freq-{ru,tt}.tsv` — the conversational frequency of every
   word that is either in the shipped-1.8.4 dictionary or in the acceptance queue. This file IS
   COMMITTED, and `scripts/dict_accept.py pack` builds the asset from it, so a rebuild is
   reproducible from the repository without the corpus on disk.
2. `research/corpus/out/accept_cov_{tag}.json` — coverage on the held-out split.

Shipped words need a conversational frequency too, not only new ones: dictionary CONTENTS are
limited by a second independent source, but FREQUENCIES come from the whole corpus. If new
words got subtitle frequencies while shipped words kept their written Leipzig frequencies,
the scales would differ: on the prefix «пап» the top three became `папочка|папин|папочку`
and `папа` could no longer be suggested although it was still in the dictionary.

Coverage is computed for four dictionaries at once, on the same held-out split:

  shipped        — the shipped-1.8.4 asset, the comparison baseline;
  accepted       — `shipped ∪ accepted` words, frequency = written + conversational.
                   Exactly what `scripts/dict_accept.py pack` builds;
  whole_queue    — the same, but with the WHOLE queue accepted: the cost of the bar,
                   expressed as coverage;
  filtered_lower — the full merge of the filtered corpus with no selection. It must equal
                   the `coverage_filtered_lower_pct` of the OpenSubtitles measurement; if it
                   does, the sample, the split and the normalization have not drifted.
"""
from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

import corpuslib as CL
import filters as F
import dictionary_coverage as cov
import dictionary_pack as dp
import dict_accept as DA
from measure_filtered import collect_split, held_tokens


def load_baseline(tag: str, directory: Path):
    """The dictionary BEFORE the rebuild, from an explicitly named directory.

    Not `CL.load_shipped`: by the time of the measurement `app/src/main/assets` already holds
    the new asset. The baseline copy comes from the 1.8.4 commit and its SHA-256 is printed in
    the result, so the comparison stays reproducible after the asset in the tree changes.
    """
    language = cov.language_for(tag)
    asset = (directory / CL.SHIPPED[tag].name).read_bytes()
    parsed = dp.validate_raw(dp.decompress_asset(asset, language), language=language)
    return dict(zip(parsed.words, parsed.frequencies)), min(parsed.frequencies), \
        hashlib.sha256(asset).hexdigest()


def top100k(frequencies: dict[str, int]) -> set[str]:
    return {w for w, _ in sorted(frequencies.items(), key=lambda kv: (-kv[1], kv[0]))[:100_000]}


def main() -> None:
    tag = sys.argv[1]
    baseline_dir = Path(sys.argv[2])
    paths = sys.argv[3:]
    language = cov.language_for(tag)
    shipped, boundary, baseline_sha = load_baseline(tag, baseline_dir)

    accepted = DA.read_accepted(tag)
    queue = {row.word: row.freq for row in DA.read_queue(tag)}

    split, freq, evidence = collect_split(paths, tag)
    kept, _removed = F.apply_filters(freq, evidence, tag)

    # Build artifact: the conversational frequency of words that may end up in the asset.
    # Words in neither the dictionary nor the queue are left out: no decision can add them,
    # so there is no reason to keep them in the repository.
    interesting = set(shipped) | set(queue)
    conv = {word: kept[word] for word in interesting if kept.get(word)}
    DA.write_conv_freq(tag, conv, sources=paths, baseline_sha=baseline_sha)

    def compose(words, conv_source):
        return {w: shipped.get(w, 0) + conv_source.get(w, 0) for w in words}

    dicts = {
        "shipped": set(shipped),
        "accepted": top100k(compose(set(shipped) | set(accepted), conv)),
        "whole_queue": top100k(compose(set(shipped) | set(queue), conv)),
        "filtered_lower": top100k(compose(set(shipped) | set(kept), kept)),
    }

    held = Counter()
    for word in held_tokens(split, language.alphabet):
        held[word] += 1
    total = sum(held.values())

    out = {
        "language": tag,
        "baseline_asset": str(baseline_dir / CL.SHIPPED[tag].name),
        "baseline_sha256": baseline_sha,
        "boundary_B": boundary,
        "train_lines": split.train_lines,
        "held_lines": split.held_lines,
        "held_tokens": total,
        "accepted_words": len(accepted),
        "queue_words": len(queue),
        "kept_types": len(kept),
        "conv_freq_rows": len(conv),
        "conv_freq_covers_shipped": sum(1 for w in shipped if w in conv),
    }
    for name, words in dicts.items():
        hit = sum(count for word, count in held.items() if word in words)
        out[f"coverage_{name}_pct"] = round(100.0 * hit / total, 4) if total else 0.0
        if name != "shipped":
            out[f"entered_{name}"] = len(words - set(shipped))
            out[f"displaced_{name}"] = len(set(shipped) - words)
    base = out["coverage_shipped_pct"]
    for name in dicts:
        if name != "shipped":
            out[f"gain_{name}_pp"] = round(out[f"coverage_{name}_pct"] - base, 4)

    # The cost of the bar, expressed not in words but in how often the rejected words occur
    # in text that took no part in ranking them.
    rejected_words = set(queue) - set(accepted)
    out["held_hits_on_rejected"] = sum(c for w, c in held.items() if w in rejected_words)
    out["held_hits_on_accepted"] = sum(c for w, c in held.items() if w in accepted)
    json.dump(out, sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    main()
