"""Sanity checks of the accepted dictionary, printed as JSON for review.

Usage: dict_accept_check.py BEFORE_DIR. Reads the dictionary assets in BEFORE_DIR and in the
tree, writes nothing, and reports: known garbage words (added by acceptance or already shipped),
words excluded by hand (must be absent), the rank of known silent words before and after, and
the top three completions for common prefixes before and after.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "research/corpus"))

import dictionary_coverage as cov
import dictionary_pack as dp

# Known garbage words; acceptance must not add any of them. `ме` is kept out only by the
# formal-fragment rule (too short), so it is checked here explicitly.
OPERATOR_GARBAGE = ["щрн", "нб", "фп", "бш", "ме"]
# Words excluded by hand (`EXCLUDED_WORDS` in scripts/dict_accept.py); none may be in the asset.
OPERATOR_EXCLUDED = ["можна"]
# Conversational words known to be under-suggested; their ranks are reported.
SILENT_RU = ["привет", "давай", "ладно", "слушай", "извини", "забыл", "ага", "позвони",
             "устал", "здравствуй", "целую", "скучаю", "приходи", "купи", "голоден",
             "напиши", "обнимаю"]
SILENT_TT = ["зинһар", "кил", "кит", "тыңла", "онытма"]
PREFIXES_RU = ["пап", "мам", "дет", "прив", "пожал", "спас", "здрав", "завтр", "можн",
               "позвон", "перест", "послуш"]
PREFIXES_TT = ["исәнм", "рәхм", "зинһ", "хәтерл", "шалтыр", "кайт"]


def load(path: Path, tag: str):
    language = cov.language_for(tag)
    parsed = dp.validate_raw(dp.decompress_asset(path.read_bytes(), language),
                             language=language)
    freqs = dict(zip(parsed.words, parsed.frequencies))
    ranks = {w: i + 1 for i, (w, _) in
             enumerate(sorted(freqs.items(), key=lambda kv: (-kv[1], kv[0])))}
    return parsed, freqs, ranks


def top3(parsed, prefix: str, tag: str):
    language = cov.language_for(tag)
    return [w for w, _ in dp.prefix_candidates(parsed, prefix, 3, language)]


def main() -> None:
    before_dir = Path(sys.argv[1])
    out = {}
    for tag, silent, prefixes in (("rus", SILENT_RU, PREFIXES_RU),
                                  ("tat", SILENT_TT, PREFIXES_TT)):
        name = f"{'russian' if tag == 'rus' else 'tatar'}_top100k_v1.tdict.zlib"
        before, bf, br = load(before_dir / name, tag)
        after, af, ar = load(ROOT / "app/src/main/assets/dictionaries" / name, tag)
        out[tag] = {
            # Keep "added by acceptance" apart from "already shipped": the Tatar `ме` came from
            # Leipzig long before the conversational corpora and is not an acceptance failure.
            "operator_garbage_added_by_acceptance": [w for w in OPERATOR_GARBAGE
                                                     if w in af and w not in bf],
            "operator_garbage_already_shipped": [w for w in OPERATOR_GARBAGE
                                                 if w in af and w in bf],
            "operator_excluded_in_dictionary": [w for w in OPERATOR_EXCLUDED if w in af],
            "silent_words": {
                w: {"before_rank": br.get(w), "after_rank": ar.get(w),
                    "before_freq": bf.get(w), "after_freq": af.get(w)}
                for w in silent},
            "prefix_top3": {
                p: {"before": top3(before, p, tag), "after": top3(after, p, tag)}
                for p in prefixes},
            "entries_before": len(bf),
            "entries_after": len(af),
            "words_added": len(set(af) - set(bf)),
            "words_displaced": len(set(bf) - set(af)),
        }
    json.dump(out, sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    main()
