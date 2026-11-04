"""Generate a word-acceptance queue for the curator: data/dictionary/*-conv-review.tsv.

`approved` is written EMPTY for every row, always. This script has no code path that can
write anything else into that column; only the human curator fills it in.

Sort order = descending usefulness, so the curator can work top-down and stop at any point:
primary key is how many times the word occurs in the HELD-OUT conversational split (text that
took no part in ranking it), because that is literally "how often this word would have been
available to suggest". Train frequency breaks ties, then code-point order for determinism.

No sentence from any corpus is written into the repository. The columns carry counts and
provenance only. Example sentences would help the review, but they are corpus text, and the
largest source of that text -- OpenSubtitles -- grants no licence to redistribute it. Word
forms with counts are facts about the text, not the text itself; whole sentences would be the
text. The queue therefore holds counters only.
"""
from __future__ import annotations
import sys
from collections import Counter, defaultdict
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import corpuslib as CL, filters as F
import dictionary_coverage as cov
from stream import Split, HELD_RESIDUE, fast_normalizer

HEADER = ["word", "heldout_hits", "train_freq", "train_freq_clean", "sources",
          "license_status", "cap_ratio", "enters_top100k", "approved", "reviewer",
          "review_date", "note"]

# Licence of each source, decided by reading the source's own terms.
# A word inherits the MOST permissive status among the sources it was seen in, because that
# is the status under which it could actually be shipped. The `sources` column keeps the full
# provenance either way, so a word's origin never becomes indistinguishable.
#
# OpenSubtitles has no licence grant; the project uses it for both languages as an accepted
# risk. Its status is not merged into "clean", so the queue keeps saying which
# words carry that risk.
LICENSE_RANK = {"clean": 0, "risk": 1, "unusable": 2}
SOURCE_LICENSE = {
    "Tatoeba": "clean",          # CC BY 2.0 FR -- a real grant
    "OpenSubtitles": "risk",     # no licence grant at all; used as an accepted risk
    "QED": "unusable",           # "made public for RESEARCH purpose only"
    "TED2020": "unusable",       # TED Talks Usage Policy = CC BY-NC-ND 4.0
}
LICENSE_LABEL = {
    "clean": "clean:CC-BY-2.0-FR",
    "risk": "risk-accepted:no-grant",
    "unusable": "UNUSABLE:research-or-ND",
}

PREAMBLE = """\
# Word acceptance queue for the conversational register. One row is one candidate word.
# The sources and the filter rule are in research/corpus/filters.py; OpenSubtitles is used
# as an accepted risk.
# None of these words is in a shipped dictionary.
#
# approved is filled in by the reviewer alone, word by word. The generator always writes it
# empty; a queue table is data, not an interface string.
# approved: (empty) | yes | no
#
# Order: descending usefulness. The primary key is heldout_hits, the occurrences in the
# held-out conversational split, which took no part in ranking the word; train_freq breaks
# ties. The list is long on purpose: work from the top and stop at any row.
#
# heldout_hits     occurrences in the held-out conversational split (10 % of the rows after
#                  deduplication)
# train_freq       occurrences in the training part of the conversational corpora, all sources
# train_freq_clean occurrences in the clean-licence corpora only (Tatoeba). This is the count
#                  that survives if OpenSubtitles is dropped. Zero means the word rests on
#                  subtitles alone even when license_status is clean: the clean label says the
#                  form occurs in Tatoeba, not that its count is free of OpenSubtitles.
# sources          the corpora the word came from
# cap_ratio        share of occurrences capitalized outside the line start; the closer to 1, the
#                  likelier a proper name that passed the filter. 0.00 is clean.
# enters_top100k   whether the word would enter the top 100k of the merged ranking (lower bound)
# license_status   the licence the word was taken under:
#   clean:CC-BY-2.0-FR     Tatoeba, a real grant
#   risk-accepted:no-grant OpenSubtitles only. OpenSubtitles grants no licence at all, so the
#                          source is used as an accepted risk; the row keeps the origin visible
#                          and is reviewed like the clean rows.
#
# Words seen only in QED (research use only) or TED2020 (CC BY-NC-ND) never enter the queue: no
# decision could accept them, and keeping them would waste review time. The generator prints
# their count.
#
# Words that miss the top 100k even at the upper frequency bound are left out as well: the
# dictionary cutoff is 100 000 entries, so such a word cannot enter the asset, and the generator
# prints how many were dropped.
"""

def main():
    tag = sys.argv[1]; out_path = Path(sys.argv[2]); paths = sys.argv[3:]
    lang = cov.language_for(tag); alpha = lang.alphabet
    shipped, B = CL.load_shipped(tag)

    # Streaming split: the same dedup-then-every-tenth-line rule as the in-memory version, but
    # the lines are replayed from the files instead of being held in memory, because the
    # Russian OpenSubtitles file does not fit in memory. The held-out residue is per language
    # (stream.HELD_RESIDUE); the Tatar residue matches the eval-set slice.
    split = Split(paths, held_residue=HELD_RESIDUE[tag])
    norm = fast_normalizer(alpha)
    origin = defaultdict(set)
    # Counted separately from `freq`, because `freq` is the MERGED count and the merged count is
    # what ranks the word. Without this column a row marked clean:CC-BY-2.0-FR reads as "owes
    # nothing to OpenSubtitles", which is false: in the Russian run every single clean row is
    # OpenSubtitles+Tatoeba, and its rank comes overwhelmingly from the subtitles. This column is
    # what remains if OpenSubtitles is ever dropped.
    clean_freq = Counter()

    ev = F.CaseEvidence(); freq = Counter()
    for line, name in split.train():
        first = True
        for chunk in line.split():
            w = chunk.strip(CL._EDGE)
            if not w: continue
            nw = norm(w)
            if nw is not None:
                ev.observe(w, nw, first); freq[nw] += 1; origin[nw].add(name)
                if SOURCE_LICENSE.get(name) == "clean":
                    clean_freq[nw] += 1
            first = False
    kept, _removed = F.apply_filters(freq, ev, tag)

    held_hits = Counter()
    for line, _name in split.held():
        for chunk in line.split():
            w = chunk.strip(CL._EDGE)
            if not w: continue
            nw = norm(w)
            if nw is not None:
                held_hits[nw] += 1

    # Two merged frequency maps, one per end of the interval the shipped asset leaves open: a
    # word absent from the asset has an unknown written frequency, bounded above by B.
    #   lower: the word gets exactly its conversational count
    #   upper: it gets its count plus B, the most generous assumption possible
    def top100k(bound):
        merged = dict(shipped)
        for w, c in kept.items():
            merged[w] = (merged[w] + c) if w in shipped else (c + bound)
        return {w for w, _ in sorted(merged.items(), key=lambda kv: (-kv[1], kv[0]))[:100_000]}

    top = top100k(0)
    reachable = top100k(B)

    def status_of(w):
        return min((SOURCE_LICENSE.get(x, "unusable") for x in origin[w]),
                   key=lambda s: LICENSE_RANK[s])

    # Words seen ONLY in a source that can never be licensed (QED "research purpose only",
    # TED2020 CC BY-NC-ND) are dropped from the queue entirely rather than listed as
    # unapprovable. They cannot be accepted under ANY decision, so carrying them would only
    # cost the curator reading time. Their count is reported instead.
    all_new = [w for w in kept if w not in shipped]
    dropped_unusable = [w for w in all_new if status_of(w) == "unusable"]
    usable = [w for w in all_new if status_of(w) != "unusable"]

    # A word that does not reach top-100k even at the UPPER end of the interval cannot enter the
    # shipped asset under any assumption about the frequencies the asset does not carry -- the
    # cutoff is fixed at 100 000 entries. Reviewing such a word is work that cannot change
    # anything, so it is left out and counted instead. In practice this removes most Russian
    # candidates and no Tatar ones; the rule is the same for both languages on purpose.
    rows = [w for w in usable if w in reachable]
    dropped_unreachable = [w for w in usable if w not in reachable]
    rows.sort(key=lambda w: (-held_hits.get(w, 0), -kept[w], w))

    with out_path.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write(PREAMBLE)
        fh.write("\t".join(HEADER) + "\n")
        for w in rows:
            status = min((SOURCE_LICENSE.get(x, "unusable") for x in origin[w]),
                         key=lambda s: LICENSE_RANK[s])
            fh.write("\t".join([
                w, str(held_hits.get(w, 0)), str(kept[w]), str(clean_freq.get(w, 0)),
                "+".join(sorted(origin[w])), LICENSE_LABEL[status], f"{ev.ratio(w):.2f}",
                "yes" if w in top else "no",
                "", "", "",                      # approved, reviewer, review_date -- ALWAYS empty
                "",
            ]) + "\n")
    counts = Counter(status_of(w) for w in rows)
    print(f"{out_path}: {len(rows)} candidate words "
          f"({sum(1 for w in rows if w in top)} would enter top-100k); by licence: {dict(counts)}; "
          f"dropped as unlicensable: {len(dropped_unusable)}; "
          f"dropped as unreachable (below the cutoff even at the upper bound): "
          f"{len(dropped_unreachable)}")

if __name__ == "__main__":
    main()
