#!/bin/bash
# Холодный старт IME на живом устройстве POCO C71 (720x1640, Android 15 Go edition).
# Метод тот же, что docs/archive/bigrams/imperative-heads/evidence/coldstart.sh:
# старт процесса (поле 22 /proc/<pid>/stat, CLK_TCK=100) -> первый FrameCompleted
# (колонка 14 первой строки ---PROFILEDATA---).
# Три поправки на живое железо:
#  1. kill -9 запрещён (shell не может убить чужой uid) -> am force-stop;
#  2. после force-stop поле СОХРАНЯЕТ фокус, но клавиатура не переподнимается
#     тапом по нему — нужен цикл расфокус/фокус (кнопка правки Notein, 664x1504);
#  3. каждый тап по этой кнопке ПЕРЕКЛЮЧАЕТ режим, поэтому raise() бьёт до
#     подтверждённого mIsInputViewShown=true, максимум 3 раза.
A=$HOME/Android/Sdk/platform-tools/adb
DEV=${DEV:-9b0100593053303634000b3326e0cb}
PKG=org.tatarkeyboard.ime
BX=${BX:-664}; BY=${BY:-1504}
N=${1:-20}
shown() { $A -s "$DEV" shell dumpsys input_method 2>/dev/null | grep -q "mIsInputViewShown=true"; }
raise() { for k in 1 2 3; do $A -s "$DEV" shell input tap $BX $BY >/dev/null 2>&1
                             sleep 1.5; shown && return 0; done; return 1; }
for i in $(seq 1 "$N"); do
  shown || raise
  $A -s "$DEV" shell am force-stop $PKG >/dev/null 2>&1
  sleep 2
  raise || { echo "SKIP window did not come back"; continue; }
  new=$($A -s "$DEV" shell pidof $PKG | tr -d '\r')
  [ -z "$new" ] && { echo "SKIP no process"; continue; }
  sleep 0.5
  start=$($A -s "$DEV" shell cat /proc/"$new"/stat 2>/dev/null | awk '{print $22}' | tr -d '\r')
  frame=$($A -s "$DEV" shell dumpsys gfxinfo $PKG framestats 2>/dev/null \
          | awk '/---PROFILEDATA---/{f=1;next} f&&/^[0-9]/{print $0; exit}' \
          | tr -d '\r' | awk -F, '{print $14}')
  { [ -z "$start" ] || [ -z "$frame" ] || [ "$frame" = "0" ]; } && { echo "SKIP no data"; continue; }
  python3 -c "import sys; print(f'{int(sys.argv[2])/1e6 - int(sys.argv[1])*10.0:.1f}')" "$start" "$frame"
done
