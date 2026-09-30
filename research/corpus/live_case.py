"""The live case: what happens to a fixed list of words that got no next-word predictions.

The word lists (frequent conversational imperatives and states) were recorded earlier by
the project, not chosen here. H = 10 000 is the bigram head cutoff the shipped table uses.
"""
from __future__ import annotations
import json, sys
from collections import Counter
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import corpuslib as CL
import dictionary_coverage as cov
from measure_dict import count_conv, rank_map

H = 10_000

WORDS = {
    "tat": ["зинһар", "сагындым", "кил", "кит", "тыңла", "онытма", "шалтырат",
            "ашыйсы", "арыдым", "сәлам", "әйдә", "яратам", "ярар", "ничек",
            "рәхмәт", "исәнме", "исәнмесез", "бир", "хәерле", "кичер"],
    "rus": ["привет", "давай", "ладно", "слушай", "извини", "скучаю", "целую",
            "обнимаю", "позвони", "напиши", "приходи", "купи", "забыл", "устал",
            "голоден", "спасибо", "пожалуйста", "здравствуй", "пока", "ага",
            "конечно", "нормально", "дела", "хочу", "люблю"],
}

def main():
    tag = sys.argv[1]; paths = sys.argv[2:]
    shipped, B = CL.load_shipped(tag)
    conv, *_ = count_conv(paths, tag)
    base_rank = rank_map(shipped)

    rows = []
    for bound, label in ((0, "lower"), (B, "upper")):
        m = dict(shipped)
        for w, c in conv.items():
            m[w] = (m[w] + c) if w in shipped else (c + bound)
        nr = rank_map(m)
        for w in WORDS[tag]:
            rows.append({
                "bound": label, "word": w,
                "in_shipped_dict": w in shipped,
                "shipped_freq": shipped.get(w),
                "shipped_rank": base_rank.get(w),
                "was_bigram_head": bool(base_rank.get(w) and base_rank[w] <= H),
                "conv_freq": conv.get(w, 0),
                "merged_freq": m.get(w),
                "merged_rank": nr.get(w),
                "becomes_bigram_head": bool(nr.get(w) and nr[w] <= H),
            })
    json.dump(rows, sys.stdout, ensure_ascii=False, indent=1); print()

if __name__ == "__main__":
    main()
