"""Generate a word-acceptance queue for the operator: data/dictionary/*-conv-review.tsv.

`approved` is written EMPTY for every row, always. This script has no code path that can
write anything else into that column -- setting it is the operator's act, personally and by
name, and nothing here may do it on his behalf.

Sort order = descending usefulness, so a human can work top-down and stop whenever he likes:
primary key is how many times the word occurs in the HELD-OUT conversational split (text that
took no part in ranking it), because that is literally "how often this word would have been
available to suggest". Train frequency breaks ties, then code-point order for determinism.

No sentence from any corpus is written into the repository. The columns carry counts and
provenance only. Example sentences would have been genuinely useful for review, but they are
corpus text, and the largest source of that text -- OpenSubtitles -- grants no licence to
redistribute it. Word forms with counts are facts about the text, not the text itself; whole
sentences would be the text. That distinction is the whole reason this file holds only
counters, and it survives the operator's decision to use the source.
"""
from __future__ import annotations
import sys
from collections import Counter, defaultdict
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import corpuslib as CL, filters as F
import dictionary_coverage as cov
from stream import Split, fast_normalizer

HEADER = ["word", "heldout_hits", "train_freq", "train_freq_clean", "sources",
          "license_status", "cap_ratio", "enters_top100k", "approved", "reviewer",
          "review_date", "note"]

# Licence of each source, decided by reading the source's own terms (docs/CORPUS.md).
# A word inherits the MOST permissive status among the sources it was seen in, because that
# is the status under which it could actually be shipped. The `sources` column keeps the full
# provenance either way, so a word's origin never becomes indistinguishable.
#
# OpenSubtitles moved from "undecided" to "risk" on 2026-08-24: the operator answered the
# question tt-corpus put to him and chose to use it for BOTH languages knowing that it carries
# no licence grant at all. The status is deliberately NOT merged into "clean" -- the decision
# was to accept a risk, not to discover a licence, and the queue has to keep saying which
# words carry it. docs/CORPUS-OS.md records the decision; docs/PUBLISH-CHECKLIST.md carries it
# forward to release.
LICENSE_RANK = {"clean": 0, "risk": 1, "unusable": 2}
SOURCE_LICENSE = {
    "Tatoeba": "clean",          # CC BY 2.0 FR -- a real grant
    "OpenSubtitles": "risk",     # no licence grant at all; used by operator decision 2026-08-24
    "QED": "unusable",           # "made public for RESEARCH purpose only"
    "TED2020": "unusable",       # TED Talks Usage Policy = CC BY-NC-ND 4.0
}
LICENSE_LABEL = {
    "clean": "clean:CC-BY-2.0-FR",
    "risk": "risk-accepted:no-grant",
    "unusable": "UNUSABLE:research-or-ND",
}

PREAMBLE = """\
# Очередь приёмки слов разговорного регистра. Одна строка — одно слово-кандидат.
# Источник и метод: docs/CORPUS.md, решение по OpenSubtitles — docs/CORPUS-OS.md.
# Слова НЕ добавлены ни в один поставляемый словарь.
#
# approved заполняет ТОЛЬКО оператор лично, поимённо. Генератор всегда пишет её пустой;
# правило записано в docs/TATAR-REVIEW-QUEUE.tsv: словарные наборы — не строки интерфейса.
# approved: (пусто) | yes | no
#
# Сортировка — по убыванию пользы: сначала heldout_hits (сколько раз слово встретилось в
# отложенной разговорной выборке, не участвовавшей в его ранжировании), затем train_freq.
# Список длинный намеренно: можно идти сверху вниз и остановиться в любой момент.
#
# heldout_hits  — вхождений в отложенной разговорной выборке (10 % строк, после дедупликации)
# train_freq    — вхождений в обучающей части разговорных корпусов, ВСЕХ вместе
# train_freq_clean — из них вхождений только в корпусах с чистой лицензией (Tatoeba). Именно
#                 это число останется, если решение по OpenSubtitles отменят. Ноль означает,
#                 что слово держится на одних субтитрах, даже когда license_status = clean:
#                 метка clean говорит, что словоформа встречается в Tatoeba, а НЕ что её
#                 частота получена без OpenSubtitles.
# sources       — из каких корпусов слово пришло
# cap_ratio     — доля вхождений с заглавной буквы НЕ в начале строки; чем ближе к 1,
#                 тем вероятнее это имя собственное, проскочившее фильтр. 0.00 — чисто.
# enters_top100k — вошло бы слово в top-100k при слиянии частот (нижняя оценка)
# license_status — под какой лицензией слово получено:
#   clean:CC-BY-2.0-FR      — Tatoeba, грант есть
#   risk-accepted:no-grant  — слово встречается только в OpenSubtitles. Лицензионного гранта у
#                             OpenSubtitles нет вообще: это не «неясная» лицензия, а
#                             отсутствующая. Оператор 2026-08-24 решил взять источник, зная это.
#                             Строка помечена, чтобы происхождение слова оставалось видно;
#                             вычитывается она наравне с чистыми.
# Слова, встречающиеся ТОЛЬКО в QED (research only) или TED2020 (CC BY-NC-ND), в очередь
# не попадают вовсе: их нельзя принять ни при каком решении оператора, и держать их здесь
# значило бы тратить его время. Их число указано в docs/CORPUS.md.
#
# Не попадают и слова, которые не доходят до top-100k даже при ВЕРХНЕЙ границе интервала
# частот: отсечка словаря жёстко 100 000 записей, такое слово не войдёт в ассет ни при каком
# допущении, и вычитывать его — работа, которая ничего не изменит. Сколько таких слов
# отброшено, печатает сам генератор и записано в docs/CORPUS-OS.md.
"""

def main():
    tag = sys.argv[1]; out_path = Path(sys.argv[2]); paths = sys.argv[3:]
    lang = cov.language_for(tag); alpha = lang.alphabet
    shipped, B = CL.load_shipped(tag)

    # Streaming split: the same dedup-then-every-tenth-line rule as before, but the lines are
    # replayed from the files instead of being held in memory. The Russian OpenSubtitles file
    # is 1,5 ГБ compressed and does not fit the old shape. selftest.py proves the split is
    # line-for-line identical.
    split = Split(paths)
    norm = fast_normalizer(alpha)
    origin = defaultdict(set)
    # Counted separately from `freq`, because `freq` is the MERGED count and the merged count is
    # what ranks the word. Without this column a row marked clean:CC-BY-2.0-FR reads as "owes
    # nothing to OpenSubtitles", which is false: in the Russian run every single clean row is
    # OpenSubtitles+Tatoeba, and its rank comes overwhelmingly from the subtitles. This column is
    # what actually survives if the operator reverts the OpenSubtitles decision.
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
    # unapprovable. They cannot be accepted under ANY decision the operator might take, so
    # carrying them would only cost him reading time. Their count is reported instead.
    all_new = [w for w in kept if w not in shipped]
    dropped_unusable = [w for w in all_new if status_of(w) == "unusable"]
    usable = [w for w in all_new if status_of(w) != "unusable"]

    # A word that does not reach top-100k even at the UPPER end of the interval cannot enter the
    # shipped asset under any assumption about the frequencies the asset does not carry -- the
    # cutoff is fixed at 100 000 entries. Reviewing such a word is work that cannot change
    # anything, so it is left out and counted instead.
    #
    # This bound did nothing until OpenSubtitles arrived: every one of the 3 734 Tatar candidates
    # is reachable, so the Tatar queue is unchanged by it. The Russian corpus produced 454 375
    # candidates, of which 35 444 are reachable; carrying the other 418 931 would have put a
    # 33-МБ file into the repository for a person who cannot read it and could not act on it.
    # Same rule for both languages on purpose: the project has refused per-language rules before.
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
