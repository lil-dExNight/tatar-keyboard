#!/bin/bash
# Regenerate the acceptance review queues from Tatoeba + OpenSubtitles for both languages.
# Kept separate from run_os.sh so the queues can be rebuilt without rerunning every measure.
set -u
cd "$(dirname "$0")"
RU="Tatoeba-v2026-07-08.ru.txt.gz OpenSubtitles-v2024.ru.txt.gz"
TT="Tatoeba-v2026-07-08.tt.txt.gz OpenSubtitles-v2024.tt.txt.gz"
echo "tat начато $(date +%T)"
python3 make_review.py tat ../../data/dictionary/tt-conv-review.tsv $TT
echo "rus начато $(date +%T)"
python3 make_review.py rus ../../data/dictionary/ru-conv-review.tsv $RU
echo "ОЧЕРЕДИ ГОТОВЫ $(date +%T)"
