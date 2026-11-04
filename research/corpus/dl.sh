#!/bin/bash
set -u
cd "$(dirname "$0")"
dl(){ n="$2"; [ -s "$n" ] && { echo "skip $n"; return; }; curl -sS -L --retry 3 -o "$n.part" "$1" && mv "$n.part" "$n" && echo "ok $n $(stat -c%s "$n")"; }
dl https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2024/mono/tt.txt.gz  OpenSubtitles-v2024.tt.txt.gz
# The Tatar Tatoeba input is the weekly export (the OPUS snapshot lags it); the version is
# the export date. The TSV (id, lang, text) is reduced to the one-sentence-per-line shape
# of the OPUS mono dumps. A newer export fails the stream pin in scripts/make_eval_set.py.
dl https://downloads.tatoeba.org/exports/per_language/tat/tat_sentences.tsv.bz2 tat_sentences.tsv.bz2
if [ ! -s Tatoeba-v2026-10-31.tt.txt.gz ]; then
  bzcat tat_sentences.tsv.bz2 | cut -d'	' -f3 | gzip -n -9 > Tatoeba-v2026-10-31.tt.txt.gz.part
  mv Tatoeba-v2026-10-31.tt.txt.gz.part Tatoeba-v2026-10-31.tt.txt.gz
  echo "ok Tatoeba-v2026-10-31.tt.txt.gz $(stat -c%s Tatoeba-v2026-10-31.tt.txt.gz)"
fi
dl https://object.pouta.csc.fi/OPUS-QED/v2.0a/mono/tt.txt.gz            QED-v2.0a.tt.txt.gz
dl https://object.pouta.csc.fi/OPUS-TED2020/v1/mono/tt.txt.gz           TED2020-v1.tt.txt.gz
dl https://object.pouta.csc.fi/OPUS-Tatoeba/v2026-07-08/mono/ru.txt.gz  Tatoeba-v2026-07-08.ru.txt.gz
dl https://object.pouta.csc.fi/OPUS-QED/v2.0a/mono/ru.txt.gz            QED-v2.0a.ru.txt.gz
dl https://object.pouta.csc.fi/OPUS-TED2020/v1/mono/ru.txt.gz           TED2020-v1.ru.txt.gz
dl https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2024/mono/ru.txt.gz  OpenSubtitles-v2024.ru.txt.gz
echo "ALL DONE"
