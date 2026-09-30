#!/bin/bash
# Recompute all measures for both languages with OpenSubtitles included.
# Strictly sequential: each measure keeps per-word-type counters in memory, and the Russian
# OpenSubtitles corpus is large enough that parallel runs would exhaust memory.
set -u
cd "$(dirname "$0")"
RU="Tatoeba-v2026-07-08.ru.txt.gz OpenSubtitles-v2024.ru.txt.gz"
TT="Tatoeba-v2026-07-08.tt.txt.gz OpenSubtitles-v2024.tt.txt.gz"

run(){ label="$1"; shift; echo "=== $label начато $(date +%T)"; "$@" > "out/$label.json" 2> "out/$label.err"; echo "=== $label готово $(date +%T) rc=$?"; }

run os_filtered_tat  python3 measure_filtered.py tat $TT
run os_hitrate_tat   python3 hitrate.py          tat $TT
run os_bigrams_tat   python3 measure_bigrams.py  tat $TT
run os_bytes_tat     python3 measure_bytes.py    tat $TT
run os_live_tat      python3 live_case.py        tat $TT
run os_profile_tt    python3 profile_corpus.py   tat $TT

run os_filtered_rus  python3 measure_filtered.py rus $RU
run os_hitrate_rus   python3 hitrate.py          rus $RU
run os_bigrams_rus   python3 measure_bigrams.py  rus $RU
run os_bytes_rus     python3 measure_bytes.py    rus $RU
run os_live_rus      python3 live_case.py        rus $RU
run os_profile_ru    python3 profile_corpus.py   rus Tatoeba-v2026-07-08.ru.txt.gz

echo "ALL MEASURES DONE $(date +%T)"
