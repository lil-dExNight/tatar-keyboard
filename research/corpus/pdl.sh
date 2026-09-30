#!/bin/bash
# Resume the download of OpenSubtitles-v2024.ru.txt.gz with parallel Range requests.
# The server throttles each connection, so several connections finish much sooner.
set -u
cd "$(dirname "$0")"
URL=https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2024/mono/ru.txt.gz
TOTAL=1518001327
BASE=558000000          # bytes already in .part (rounded down to be safe)
N=8
truncate -s $BASE OpenSubtitles-v2024.ru.txt.gz.part
REM=$((TOTAL-BASE)); CH=$(( (REM+N-1)/N ))
mkdir -p chunks
for i in $(seq 0 $((N-1))); do
  s=$((BASE+i*CH)); e=$((s+CH-1)); [ $e -ge $TOTAL ] && e=$((TOTAL-1))
  [ $s -ge $TOTAL ] && continue
  curl -sS -L --retry 5 --retry-delay 2 -r "$s-$e" -o "chunks/c$i" "$URL" &
done
wait
echo "chunks done"
for i in $(seq 0 $((N-1))); do [ -f "chunks/c$i" ] && cat "chunks/c$i" >> OpenSubtitles-v2024.ru.txt.gz.part; done
SZ=$(stat -c%s OpenSubtitles-v2024.ru.txt.gz.part)
echo "size=$SZ expected=$TOTAL"
[ "$SZ" = "$TOTAL" ] || { echo "SIZE MISMATCH"; exit 1; }
echo "md5=$(md5sum OpenSubtitles-v2024.ru.txt.gz.part | cut -d' ' -f1) expected=b98f0bd033642818f87c6ff14b78d22c"
gzip -t OpenSubtitles-v2024.ru.txt.gz.part && echo "gzip OK" || { echo "GZIP BAD"; exit 1; }
mv OpenSubtitles-v2024.ru.txt.gz.part OpenSubtitles-v2024.ru.txt.gz
rm -rf chunks
echo "ALL DONE ru"
