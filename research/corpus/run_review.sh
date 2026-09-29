#!/bin/bash
# Перегенерация очередей приёмки после решения оператора по OpenSubtitles.
# Отдельным скриптом, а не внутри run_os.sh: тот в этот момент уже выполнялся, а bash читает
# скрипт по мере исполнения — правка работающего файла сдвигает смещения и ломает разбор.
set -u
cd "$(dirname "$0")"
RU="Tatoeba-v2026-07-08.ru.txt.gz OpenSubtitles-v2024.ru.txt.gz"
TT="Tatoeba-v2026-07-08.tt.txt.gz OpenSubtitles-v2024.tt.txt.gz"
echo "tat начато $(date +%T)"
python3 make_review.py tat ../../data/dictionary/tt-conv-review.tsv $TT
echo "rus начато $(date +%T)"
python3 make_review.py rus ../../data/dictionary/ru-conv-review.tsv $RU
echo "ОЧЕРЕДИ ГОТОВЫ $(date +%T)"
