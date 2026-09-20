#!/bin/bash
# Единый эмуляторный смоук клавиатуры (DEV-3) — вместо разрозненных ручных
# прогонов. Сценарий: поднять AVD → установить APK → включить и
# выбрать IME (ПОЛНЫЙ id — относительное имя компонента резолвится против
# applicationId, короткий `org.tatarkeyboard.ime/.latin.LatinIME` НЕ работает,
# см. docs/RESTRUCTURE.md фаза 4б) → SetupActivity → клавиатура поднялась →
# набор «мин» (tt) / «при» (ru) / «hi» (en) с проверкой подсказок →
# переключение сабтипов глобусом tt→ru→en→tt → эмодзи-панель (долгий тап
# запятой) с коммитом эмодзи → пустой crash-буфер.
# TT-SUGGESTIONS P5: back on the tt layout, two word-form probes (татар, сакчы)
# type a word + space and tap the middle suggestion cell, reading the field to
# prove what the strip committed (tap-and-read; the IME window is invisible to
# uiautomator). Details at the probes below.
#
# Флаги:
#   --avd <имя>      AVD (по умолчанию tt_suggest_a14)
#   --apk <путь>     APK (по умолчанию app/build/outputs/apk/debug/app-debug.apk)
#   --no-boot        эмулятор уже запущен, не поднимать и не гасить
#   --outdir <путь>  каталог свидетельств (по умолчанию build/emulator-smoke/)
#
# Как что проверяется (uiautomator НЕ видит окно IME — клавиши, полосу
# подсказок и эмодзи-панель в дампе нет, проверено на API 34):
#   - набранный текст читается из EditText try-it поля SetupActivity (оно в
#     дампе есть) — это заодно функциональное доказательство раскладки:
#     «при» по координатам ru-раскладки на tt-раскладке дало бы другие буквы;
#   - переключение сабтипов читается из префа pref_current_subtype через
#     run-as (debuggable-пакет); на релизном APK раскладка определяется
#     функциональным зондом (probe_layout): тап по верхнему левому углу
#     буквенной области даёт «ә» на tt, «й» на ru, «q» на en;
#   - подсказки — пиксельная дельта полосы над клавиатурой между скриншотом
#     до и после набора слова (ImageMagick compare; нет ImageMagick — SKIP);
#   - эмодзи-панель — тап по первой ячейке сетки обязан закоммитить эмодзи
#     в поле (в XML-дампе эмодзи приезжает как &#...;);
#   - клавиатура поднята — dumpsys input_method mIsInputViewShown=true.
#
# Подсказки — opt-in (по умолчанию выключены, Settings.readTatarSuggestionsEnabled).
# На debuggable-пакете преф пишется через run-as ДО старта приложения; процесс
# затем force-stop'ается, иначе живой процесс держит старые префы в памяти
# (файл снаружи он не перечитывает). force-stop выбранного IME сбрасывает
# default_input_method — поэтому ime set идёт строго ПОСЛЕ force-stop
# (та же грабля, что в фазе 4б реструктуризации).
#
# Деструктивность записи префов (C4 аудита 2026-09-02): правка идёт через run-as
# прямо в shared_prefs пакета, МИМО API SharedPreferences. До 2026-09-02 файл
# затирался ЦЕЛИКОМ заготовкой из двух ключей — все прочие настройки приложения
# на этом AVD терялись безвозвратно. Теперь правка точечная (меняются или
# добавляются только pref_tatar_suggestions и pref_tatar_suggestions_offer_spent,
# остальные ключи сохраняются), но это всё равно запись в обход API: живой
# процесс файл с диска не перечитывает (поэтому дальше force-stop), а гонку
# с одновременной записью самого приложения ничто не страхует. Запускать на
# выделенных тестовых AVD, а не на эмуляторе с настроенным состоянием.
#
# Координаты клавиш — доли экрана, откалиброваны на tt_suggest_a14 (1080×2280),
# как KeyGeom в baselineprofile/ImeBaselineProfileGenerator.java; на AVD с
# другим размером сценарий набора не пройдёт — это осознанно.
# Окно IME не видно uiautomator (проверено на API 34), поэтому клавиши остаются
# долями экрана; bounds из дампа читаются там, где виджет в дампе есть (try-it
# поле). Переключение сабтипов глобусом на не-debuggable APK проверяется
# функциональным зондом (probe_layout) — слепая серия тапов по глобусу была
# нестабильна: порядок цикла пересобирается (resetSubtypeCycleOrder), и без
# чтения pref'а три тапа приземлялись не на той раскладке (флаки type-en-hi
# на release APK, 2026-08-31).
#
# Итог — машинные строки `RESULT|PASS|FAIL|SKIP|проверка|деталь` в stdout и
# $OUTDIR/result.txt; любой FAIL = ненулевой код выхода. Эмулятор гасится,
# если скрипт сам его поднял.

set -euo pipefail

AVD="tt_suggest_a14"
APK=""
NO_BOOT=0
OUTDIR=""

while [ $# -gt 0 ]; do
    case "$1" in
        --avd) AVD="$2"; shift 2 ;;
        --apk) APK="$2"; shift 2 ;;
        --no-boot) NO_BOOT=1; shift ;;
        --outdir) OUTDIR="$2"; shift 2 ;;
        *) echo "unknown flag: $1" >&2; exit 2 ;;
    esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
OUTDIR="${OUTDIR:-$ROOT/build/emulator-smoke}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
EMULATOR="${EMULATOR:-$HOME/Android/Sdk/emulator/emulator}"
SDK_ROOT="${ANDROID_HOME:-$HOME/Android/Sdk}"
SETUP_ACTIVITY="rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity"

mkdir -p "$OUTDIR"
RESULTS="$OUTDIR/result.txt"
: > "$RESULTS"
FAILURES=0

result() {  # result PASS|FAIL|SKIP <проверка> <деталь>
    local line="RESULT|$1|$2|$3"
    echo "$line"
    echo "$line" >> "$RESULTS"
    [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
}

log() { echo "smoke: $*" >&2; }

[ -x "$ADB" ] || { echo "adb не найден: $ADB" >&2; exit 2; }
[ -f "$APK" ] || { echo "APK не найден: $APK" >&2; exit 2; }

# Пакет НЕ хардкодим: debug-сборка несёт applicationIdSuffix ".debug"
# (app/build.gradle). Пакет читается из самого APK через aapt2 (тот же приём,
# что scripts/check-no-internet.sh), а id IME ищется уже по нему.
AAPT2=$(find "$SDK_ROOT/build-tools" -name aapt2 2>/dev/null | sort -V | tail -1)
PKG=$("$AAPT2" dump packagename "$APK" 2>/dev/null || true)
[ -n "$PKG" ] || { echo "не удалось прочитать пакет из $APK" >&2; exit 2; }

EMU_PID=""
SERIAL=""
cleanup() {
    if [ -n "$EMU_PID" ]; then
        log "гасим эмулятор (pid $EMU_PID)"
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || kill "$EMU_PID" 2>/dev/null || true
        wait "$EMU_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ── загрузка ──────────────────────────────────────────────────────────────────

pick_serial() {
    if [ -n "${ANDROID_SERIAL:-}" ]; then echo "$ANDROID_SERIAL"; return; fi
    "$ADB" devices | awk '$2 == "device" && $1 ~ /^emulator-/ {print $1; exit}'
}

if [ "$NO_BOOT" = 0 ]; then
    [ -x "$EMULATOR" ] || { echo "emulator не найден: $EMULATOR" >&2; exit 2; }
    # Уже работающие эмуляторы запоминаем: свой экземпляр узнаём как НОВЫЙ
    # serial в adb devices, иначе при живом соседнем эмуляторе сценарий
    # уехал бы на чужое устройство.
    before_serials=$("$ADB" devices | awk '$2 == "device" {print $1}' | sort)
    log "поднимаем AVD $AVD (-no-window)"
    "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-snapshot-save \
        >"$OUTDIR/emulator.log" 2>&1 &
    EMU_PID=$!
    deadline=$((SECONDS + 300))
    SERIAL=""
    while [ $SECONDS -lt $deadline ]; do
        SERIAL=$(comm -13 <(echo "$before_serials") \
                 <("$ADB" devices | awk '$2 == "device" {print $1}' | sort) | head -1)
        [ -n "$SERIAL" ] && break
        sleep 2
    done
    [ -n "$SERIAL" ] || { echo "эмулятор не появился в adb devices (AVD уже запущен?)" >&2; exit 2; }
    "$ADB" -s "$SERIAL" wait-for-device
    booted=""
    while [ $SECONDS -lt $deadline ]; do
        booted=$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        [ "$booted" = "1" ] && break
        sleep 3
    done
    [ "$booted" = "1" ] || { echo "эмулятор не загрузился за 300 с" >&2; exit 2; }
    # package manager и systemui просыпаются позже boot_completed
    sleep 10
else
    SERIAL=$(pick_serial || true)
    [ -n "$SERIAL" ] || { echo "нет online-устройства (флаг --no-boot)" >&2; exit 2; }
    booted=$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$booted" = "1" ] || { echo "устройство $SERIAL не загружено" >&2; exit 2; }
fi
log "устройство: $SERIAL"
result PASS boot "serial=$SERIAL avd=$AVD"

wh=$("$ADB" -s "$SERIAL" shell wm size | grep -oP '\d+x\d+' | head -1)
if [ -z "$wh" ]; then
    # Пустой ответ wm size (surfaceflinger ещё не поднялся / устройство в
    # странном состоянии) — раньше скрипт молча падал по set -e в TAPF без
    # RESULT-строки (C4 аудита 2026-09-02). Это честный FAIL.
    result FAIL screen-size "wm size вернул пустоту — координаты клавиш не вычислить"
    exit 1
fi
if [ "$wh" != "1080x2280" ]; then
    log "ВНИМАНИЕ: экран $wh, координаты клавиш откалиброваны под 1080x2280"
fi

A() { "$ADB" -s "$SERIAL" "$@"; }            # adb на выбранном устройстве
SHELL() { A shell "$@"; }                    # adb shell
SHOT() { A exec-out screencap -p > "$OUTDIR/$1" 2>/dev/null; }
DUMP_UI() {                                  # uiautomator dump → stdout
    SHELL uiautomator dump /data/local/tmp/smoke-ui.xml >/dev/null 2>&1
    A exec-out cat /data/local/tmp/smoke-ui.xml 2>/dev/null | tr -d '\r'
}
TAPF() {                                     # доли экрана: TAPF 0.42 0.85
    local x y
    x=$(python3 -c "print(round($1 * ${wh%x*}))")
    y=$(python3 -c "print(round($2 * ${wh#*x}))")
    SHELL input tap "$x" "$y"
}
LONGPRESSF() {                               # долгий тап по долям экрана
    local x y
    x=$(python3 -c "print(round($1 * ${wh%x*}))")
    y=$(python3 -c "print(round($2 * ${wh#*x}))")
    SHELL input swipe "$x" "$y" "$x" "$y" 900
}
keyboard_shown() {
    SHELL dumpsys input_method 2>/dev/null | grep -q "mIsInputViewShown=true"
}
field_text() {                               # текст try-it поля SetupActivity
    local dump
    dump=$(DUMP_UI)
    echo "$dump" | grep -q 'setup_test_field' || { echo "__NOFIELD__"; return; }
    echo "$dump" | grep -oP '<node[^>]*setup_test_field[^>]*' \
        | grep -oP 'text="\K[^"]*' | head -1 || true
}
type_word() {                                # type_word "0.42,0.85 0.51,0.85 ..."
    local xy
    for xy in $1; do
        TAPF "${xy%,*}" "${xy#*,}"
        sleep 0.4
    done
}

# Пиксельная дельта полосы подсказок (union-регион обоих вариантов высоты
# клавиатуры: 5-рядная tt и 4-рядные ru/en). Калибровка 2026-08-31: пустая
# полоса vs полоса со словами — 5,5 тыс. и 16 тыс. различающихся пикселей.
STRIP_CROP="1080x190+0+1300"
STRIP_DIFF_MIN=2000
strip_diff() {                               # strip_diff before.png after.png → AE
    # compare печатает метрику в stderr и возвращает 1 на «различаются» —
    # поэтому вывод ловим целиком, а не полагаемся на код выхода (pipefail).
    local out
    out=$(compare -metric AE \
        <(convert "$1" -crop "$STRIP_CROP" +repage png:-) \
        <(convert "$2" -crop "$STRIP_CROP" +repage png:-) null: 2>&1 || true)
    echo "$out" | grep -oP '\d+' | head -1 || echo 0
}
HAVE_MAGICK=0
if command -v compare >/dev/null 2>&1 && command -v convert >/dev/null 2>&1; then
    HAVE_MAGICK=1
fi

# ── установка и выбор IME ─────────────────────────────────────────────────────

A install -r "$APK" >"$OUTDIR/install.log" 2>&1 \
    && result PASS install "$(basename "$APK") pkg=$PKG" \
    || { result FAIL install "$(tail -1 "$OUTDIR/install.log")"; exit 1; }

# На медленных/старых образах (tatar_e5_test, API 30) IME появляется в
# списке не мгновенно после install — опрашиваем до 30 с. `ime list -a -s`,
# а не `-s`: на API 30 короткий список без `-a` содержит только ВКЛЮЧЁННЫЕ
# IME (наш ещё не включён), на API 34 — все; `-a` работает на обоих.
IME_ID=""
for _ in $(seq 1 15); do
    IME_ID=$(SHELL ime list -a -s | tr -d '\r' | grep "^$PKG/" | head -1 || true)
    [ -n "$IME_ID" ] && break
    sleep 2
done
if [ -z "$IME_ID" ]; then
    result FAIL ime-id "ime list -a -s не показывает $PKG"
    exit 1
fi
result PASS ime-id "$IME_ID"
SHELL ime list -s > "$OUTDIR/ime-list.txt" 2>&1 || true

# Подсказки включаем префом ДО первого чтения настроек приложением.
# Правка ТОЧЕЧНАЯ (C4 аудита 2026-09-02): читаем текущий преф-файл, меняем или
# добавляем только два своих ключа, остальные сохраняем; файла ещё нет (чистый
# AVD) — пишем минимальную заготовку, как раньше.
SUGGESTIONS=off
PREFS_PATH="/data/user_de/0/$PKG/shared_prefs/${PKG}_preferences.xml"
if A shell "run-as $PKG true" >/dev/null 2>&1; then
    # `|| true`: on a clean AVD (or with -no-snapshot-save discarding every
    # previous session) the prefs file does not exist yet and run-as cat exits
    # non-zero — with pipefail + set -e that killed the script BEFORE the python
    # below could write its minimal stub, with no RESULT line (hit 2026-09-20 on
    # tt_suggest_a14). The missing-file fallback is the documented path.
    A shell "run-as $PKG cat $PREFS_PATH" 2>/dev/null | tr -d '\r' > "$OUTDIR/prefs-before.xml" || true
    python3 - "$OUTDIR/prefs-before.xml" > "$OUTDIR/prefs-new.xml" <<'PYEOF'
import re
import sys
from pathlib import Path

source = Path(sys.argv[1])
xml = source.read_text(encoding="utf-8") if source.is_file() else ""
KEYS = ("pref_tatar_suggestions", "pref_tatar_suggestions_offer_spent")

for key in KEYS:
    entry = f'<boolean name="{key}" value="true" />'
    pattern = re.compile(rf'<boolean name="{key}" value="[^"]*" ?/>')
    if pattern.search(xml):
        xml = pattern.sub(entry, xml)
    elif "</map>" in xml:
        xml = xml.replace("</map>", f"    {entry}\n</map>", 1)
    else:
        xml = ""  # файла не было или он не XML — ниже пишем заготовку целиком
        break
if not xml:
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           + "".join(f'    <boolean name="{key}" value="true" />\n' for key in KEYS)
           + "</map>\n")
sys.stdout.write(xml)
PYEOF
    if A shell "run-as $PKG sh -c 'mkdir -p \$(dirname $PREFS_PATH) && cat > $PREFS_PATH'" \
        < "$OUTDIR/prefs-new.xml"; then
        SUGGESTIONS=on
    fi
fi
if [ "$SUGGESTIONS" = on ]; then
    result PASS suggestions-enabled "pref_tatar_suggestions=true через run-as"
else
    result SKIP suggestions-enabled "пакет не debuggable, opt-in поток не автоматизирован"
fi

# Живой процесс держит старые префы в памяти — убиваем. force-stop выбранного
# IME сбрасывает default_input_method, поэтому выбор восстанавливаем ПОСЛЕ.
SHELL am force-stop "$PKG" || true
sleep 1
SHELL ime enable "$IME_ID" >/dev/null 2>&1 || true
SHELL ime set "$IME_ID" >/dev/null 2>&1 || true
sleep 1
current=$(SHELL settings get secure default_input_method | tr -d '\r')
if [ "$current" = "$IME_ID" ]; then
    result PASS ime-selected "$current"
else
    result FAIL ime-selected "default_input_method=$current"
fi

read_pref() {                                # read_pref <имя> → значение или ""
    [ "$SUGGESTIONS" = on ] || { echo ""; return; }
    A shell "run-as $PKG cat $PREFS_PATH" 2>/dev/null | tr -d '\r' \
        | grep -oP "name=\"$1\"[^>]*>\\K[^<]*" | head -1 || true
}

# ── сценарий ──────────────────────────────────────────────────────────────────

current_focus() {
    SHELL dumpsys window 2>/dev/null | tr -d '\r' \
        | grep -oP 'mCurrentFocus=Window\{[0-9a-f]+ u[0-9]+ \K[^}]+' | tail -1 || true
}

SHELL logcat -b crash -c 2>/dev/null || true   # crash-буфер чистим заранее

# SetupActivity должна стать фокусом — иначе весь дальнейший сценарий пишет
# в чужое поле (поймано первым же прогоном: набор ушёл в Google Messages).
focus=""
for _ in 1 2 3; do
    SHELL am start --activity-clear-task -n "$PKG/$SETUP_ACTIVITY" \
        >"$OUTDIR/am-start.log" 2>&1
    for _ in $(seq 1 10); do
        sleep 1
        focus=$(current_focus)
        [[ "$focus" == "$PKG/"* ]] && break
    done
    [[ "$focus" == "$PKG/"* ]] && break
done
if [[ "$focus" == "$PKG/"* ]]; then
    result PASS setup-activity "в фокусе: $focus"
else
    result FAIL setup-activity "в фокусе: '$focus' — сценарий бессмысленен, стоп"
    exit 1
fi

# Тап по try-it полю: центр по свежим bounds из дампа. Никакого BACK для
# «детерминированного состояния»: если IME-окно ещё не спряталось (гонка
# после перебинда), BACK уйдёт активности и ЗАКРОЕТ её — поймано вторым
# прогоном, весь набор после этого ушёл в Google Messages. Поле видно в обоих
# состояниях (adjustResize), свежие bounds решают.
bounds=$(DUMP_UI | grep -oP '<node[^>]*setup_test_field[^>]*bounds="\[\K[0-9,\]\[]+' | head -1 || true)
if [ -n "$bounds" ]; then
    read -r x1 y1 x2 y2 <<<"$(echo "$bounds" | tr '[],' '    ')"
    SHELL input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
else
    TAPF 0.5 0.81   # типичное положение поля при погашенной клавиатуре
fi
shown=0
for _ in $(seq 1 30); do
    keyboard_shown && { shown=1; break; }
    sleep 1
done
SHELL dumpsys input_method > "$OUTDIR/dumpsys-input_method.txt" 2>&1
if [ "$shown" = 1 ]; then
    result PASS keyboard-up "mIsInputViewShown=true"
else
    result FAIL keyboard-up "клавиатура не поднялась за 30 с"
fi

field0=$(field_text)
if [ "$field0" = "__NOFIELD__" ]; then
    result FAIL field-empty "try-it поле не найдено в дампе"
elif [ -z "$field0" ] || [[ "$field0" == "Try it:"* ]]; then
    # uiautomator отдаёт hint как text — пустое поле выглядит как подсказка
    result PASS field-empty "try-it поле пустое после чистого старта"
else
    result FAIL field-empty "в поле уже есть текст: '$field0'"
fi

# Проверка «слово набрано + подсказки показаны» для одной раскладки.
# $1 — тег (tt/ru/en), $2 — слово для отчёта, $3 — координаты клавиш,
# $4 — регэксп ожидаемого хвоста поля, $5 — "nosuggest" для раскладки без словаря.
typed_checks() {
    local tag="$1" word="$2" coords="$3" expect="$4" nosuggest="${5:-}"
    SHOT "smoke-${tag}-before.png"
    type_word "$coords"
    sleep 1.5
    SHOT "smoke-${tag}-after.png"
    local text
    text=$(field_text)
    if [ "$text" = "__NOFIELD__" ]; then
        result FAIL "type-${tag}-${word}" "try-it поле не найдено в дампе"
    elif echo "$text" | grep -qE "$expect"; then
        result PASS "type-${tag}-${word}" "в поле: '$text'"
    else
        result FAIL "type-${tag}-${word}" "в поле: '$text' (ждали хвост /$expect/)"
    fi
    if [ -n "$nosuggest" ]; then
        result SKIP "suggest-${tag}-${word}" "у en нет словаря в ассетах — подсказок не бывает by design"
    elif [ "$SUGGESTIONS" != on ]; then
        result SKIP "suggest-${tag}-${word}" "подсказки не включены (не debuggable)"
    elif [ "$HAVE_MAGICK" != 1 ]; then
        result SKIP "suggest-${tag}-${word}" "нет ImageMagick для пиксельной дельты полосы"
    else
        local diff
        diff=$(strip_diff "$OUTDIR/smoke-${tag}-before.png" "$OUTDIR/smoke-${tag}-after.png")
        if [ "$diff" -ge "$STRIP_DIFF_MIN" ]; then
            result PASS "suggest-${tag}-${word}" "полоса подсказок ожила: $diff px (порог $STRIP_DIFF_MIN)"
        else
            result FAIL "suggest-${tag}-${word}" "полоса не изменилась: $diff px (порог $STRIP_DIFF_MIN)"
        fi
    fi
}

# Пробел коммитит слово, чтобы следующая раскладка начинала набор с чистого
# composing-текста (иначе подсказки считаются по склейке «минпри»).
SPACE="0.55,0.9075"

# Координаты клавиш (доли экрана, tt_suggest_a14 1080×2280).
# tt, 5 рядов: ряд1 (йцукен) y≈0.7206, ряд2 (фыва) y≈0.7851, ряд3 (ячсм) y≈0.8474.
TT_MIN="0.4231,0.8474 0.5138,0.8474 0.5000,0.7206"          # м и н
# ru/en, 4 ряда: ряд1 y≈0.6829, ряд2 y≈0.7575, ряд3 y≈0.8329.
RU_PRI="0.4091,0.7575 0.5000,0.7575 0.5000,0.8329"          # п р и
EN_HI="0.5500,0.7575 0.7500,0.6829"                          # h i
GLOBE="0.30,0.9075"
COMMA="0.2009,0.9075"

# Full letter map of the tt layout (screen fractions, calibrated for 1080×2280),
# derived from rows_tatar.xml: extra row 6×16.667% at y≈0.665 (matches PROBE_KEY),
# rows 1–2 11×9.091% at y≈0.7206/0.7851, row 3 = shift 10.8% + 9×8.711% + delete
# at y≈0.8474 (м/и/н land on the same keys as TT_MIN above).
tt_word_coords() {                       # tt_word_coords "татар" → "x,y x,y ..."
    python3 - "$1" <<'PYEOF'
import sys

ROWS = ("әөүҗңһ", 0.6650), ("йцукенгшщзх", 0.7206), ("фывапролджэ", 0.7851)
ROW3 = "ячсмитьбю"  # 10.8% shift key, then 9 keys of 8.711%

coords = {}
for letters, y in ROWS:
    for i, ch in enumerate(letters):
        coords[ch] = f"{(i + 0.5) / len(letters):.4f},{y}"
for i, ch in enumerate(ROW3):
    coords[ch] = f"{0.108 + (i + 0.5) * 0.08711:.4f},0.8474"

try:
    print(" ".join(coords[ch] for ch in sys.argv[1]))
except KeyError as exc:
    sys.exit(f"no tt key for {exc.args[0]!r}")
PYEOF
}

# Middle suggestion cell (cell 2 of 3 — equal thirds, SuggestionStripState).
# y measured on the tt 5-row layout (1080×2280, 40dp strip = 110 px): the strip
# spans ~1308–1418 px directly above the keyboard → centre ≈ 1363 px ≈ 0.598.
STRIP_CELL2="0.5000,0.5980"

second_word_after() {                    # second_word_after "<text>" "<word>" → token after last <word>
    python3 - "$1" "$2" <<'PYEOF'
import sys

tokens = sys.argv[1].split()
try:
    at = len(tokens) - 1 - tokens[::-1].index(sys.argv[2])
    print(tokens[at + 1] if at + 1 < len(tokens) else "")
except ValueError:
    print("")
PYEOF
}

# type_tt_and_tap_cell2 <word> <tag>: type <word> + space on the tt layout,
# screenshot the strip, tap the middle suggestion cell (only when suggestions
# are on), read the field again. Stdout: field-after-space <TAB> field-after-tap.
type_tt_and_tap_cell2() {
    local word="$1" tag="$2" coords mid after
    coords=$(tt_word_coords "$word")
    type_word "$coords"
    TAPF ${SPACE%,*} ${SPACE#*,}
    sleep 2                              # the NEXT_WORD answer is asynchronous
    mid=$(field_text)
    SHOT "smoke-wordform-${tag}.png"
    if [ "$SUGGESTIONS" = on ]; then
        TAPF ${STRIP_CELL2%,*} ${STRIP_CELL2#*,}
        sleep 1
        after=$(field_text)
    else
        after="$mid"
    fi
    printf '%s\t%s\n' "$mid" "$after"
}

# ── tt: «мин» ──
typed_checks tt "мин" "$TT_MIN" '^мин$'
TAPF ${SPACE%,*} ${SPACE#*,}
sleep 1

# ── сабтипы глобусом: tt → ru → en → tt ──
# Глобус циклит список сабтипов, а порядок списка пересобирается
# (resetSubtypeCycleOrder, MRU-ротация префа) — поэтому без обратной связи
# серия тапов приземляется непредсказуемо. На debuggable-пакете обратная
# связь — преф pref_current_subtype (run-as); на релизном APK преф нечитаем,
# раскладку определяем функциональным зондом и тапаем до совпадения.

# Функциональный зонд текущей раскладки (для release-APK, где run-as
# недоступен). Точка (0.045, 0.665) — внутри «ә» верхнего татарского ряда
# tt-раскладки (6 клавиш по 16.667%: «ә» занимает x 0–0.167); на 4-рядных
# ru/en тот же пиксель — первая клавиша первого ряда: «й» (ru, 11 клавиш по
# 9.091%) и «q» (en, 10 клавиш по 10%). Зондированный символ стирается
# KEYCODE_DEL, поле не портится.
PROBE_KEY="0.045,0.665"
probe_layout() {                           # → tatar|russian|qwerty|""
    local before after
    before=$(field_text)
    [ "$before" = "__NOFIELD__" ] && { echo ""; return; }
    [[ "$before" == "Try it:"* ]] && before=""   # пустое поле отдаёт hint как text
    TAPF ${PROBE_KEY%,*} ${PROBE_KEY#*,}
    sleep 0.6
    after=$(field_text)
    if [ ${#after} -le ${#before} ]; then
        echo ""                            # тап не достал до клавиши
        return
    fi
    SHELL input keyevent KEYCODE_DEL       # стереть зондированный символ
    sleep 0.4
    if   [[ "$after" == *"ә" || "$after" == *"Ә" ]]; then echo tatar
    elif [[ "$after" == *"й" || "$after" == *"Й" ]]; then echo russian
    elif [[ "$after" == *"q" || "$after" == *"Q" ]]; then echo qwerty
    else echo ""
    fi
}

switch_and_check() {                         # $1 тег, $2 ожидаемый layout
    local tag="$1" want="$2" pref="" tap cur=""
    if [ "$SUGGESTIONS" = on ]; then
        for tap in 1 2 3; do
            TAPF ${GLOBE%,*} ${GLOBE#*,}
            for _ in $(seq 1 8); do
                sleep 1
                pref=$(read_pref pref_current_subtype)
                [[ "$pref" == *":$want" ]] && break
            done
            [[ "$pref" == *":$want" ]] && break
        done
        if [[ "$pref" == *":$want" ]]; then
            result PASS "subtype-$tag" "pref_current_subtype=$pref"
        else
            result FAIL "subtype-$tag" "pref_current_subtype='$pref' (ждали :$want)"
        fi
        return
    fi
    # release: преф нечитаем — функциональный зонд. Тонкость: зонд печатает
    # символ, commitText вызывает resetSubtypeCycleOrder (текущий сабтип
    # уходит в голову цикла), поэтому ОДИНОЧНЫЙ тап глобуса после зонда
    # всегда возвращает предыдущую раскладку — tt↔ru качаются, а en
    # недостижим (именно так ломался type-en-hi). ДВОЙНОЙ тап без печати
    # между тапами из [cur, prev, X] приземляется в X; три зонда подряд
    # покрывают все три раскладки.
    cur=$(probe_layout)
    for tap in 1 2 3; do
        [ "$cur" = "$want" ] && break
        TAPF ${GLOBE%,*} ${GLOBE#*,}
        sleep 0.8
        TAPF ${GLOBE%,*} ${GLOBE#*,}
        sleep 1.2
        cur=$(probe_layout)
    done
    if [ "$cur" = "$want" ]; then
        result PASS "subtype-$tag" "функциональный зонд: раскладка $want"
    else
        result FAIL "subtype-$tag" "зонд показывает '$cur' (ждали $want)"
    fi
}

switch_and_check ru russian
SHOT smoke-ru-layout.png
typed_checks ru "при" "$RU_PRI" 'мин при$'
TAPF ${SPACE%,*} ${SPACE#*,}
sleep 1

switch_and_check en qwerty
SHOT smoke-en-layout.png
typed_checks en "hi" "$EN_HI" 'hi$' nosuggest
TAPF ${SPACE%,*} ${SPACE#*,}
sleep 1

switch_and_check tt tatar
SHOT smoke-tt-back.png

# ── word forms after a committed tt word + space (TT-SUGGESTIONS P3/P5) ──
# Tap-and-read, not pixel diff: uiautomator does not see the IME window, so the
# strip content is proven by tapping the middle cell and reading the try-it
# field. Two probes against the shipped assets:
#  1. татар — the pinned schema-3 table carries three successors for it (теле,
#     дәүләт, телен; re-measured on the shipped assets 2026-09-20), so no strip
#     cell is free and cell 2 MUST commit дәүләт: bigram successors keep
#     priority and forms never displace them (CompositePrefixComputer). A form
#     here, or any other word, is a contract breach.
#  2. сакчы — a dictionary word the table does NOT carry as a head, so all
#     three cells are free and cell 2 MUST commit an inflected form of сакчы
#     (top forms by frequency on the shipped dictionary: сакчысы, сакчылар,
#     сакчысын). This is the on-device proof of the P3 after-word forms.
wf=$(type_tt_and_tap_cell2 "татар" tatar)
wf_mid="${wf%$'\t'*}"
wf_after="${wf#*$'\t'}"
w2=$(second_word_after "$wf_after" "татар")
if [ "$wf_mid" = "__NOFIELD__" ] || [ "$wf_after" = "__NOFIELD__" ]; then
    result FAIL wordform-tt-татар "try-it field not in the dump"
elif ! echo "$wf_mid" | grep -qE 'татар $'; then
    result FAIL wordform-tt-татар "татар did not commit; field: '$wf_mid'"
elif [ "$SUGGESTIONS" != on ]; then
    result SKIP wordform-tt-татар "suggestions not enabled (non-debuggable package)"
elif [ "$wf_after" = "$wf_mid" ]; then
    result FAIL wordform-tt-татар "middle cell tap committed nothing; field: '$wf_after'"
elif [ "$w2" = "дәүләт" ]; then
    result PASS wordform-tt-татар "cell 2 = дәүләт: successors fill all 3 cells, forms keep free-cell priority (pinned)"
elif echo "$w2" | grep -qE '^татар.'; then
    result PASS wordform-tt-татар "cell 2 = $w2: a form of татар took a free cell"
else
    result FAIL wordform-tt-татар "cell 2 committed '$w2' (expected дәүләт or a татар form); field: '$wf_after'"
fi

wf=$(type_tt_and_tap_cell2 "сакчы" sakcy)
wf_mid="${wf%$'\t'*}"
wf_after="${wf#*$'\t'}"
w2=$(second_word_after "$wf_after" "сакчы")
if [ "$wf_mid" = "__NOFIELD__" ] || [ "$wf_after" = "__NOFIELD__" ]; then
    result FAIL wordform-tt-сакчы "try-it field not in the dump"
elif ! echo "$wf_mid" | grep -qE 'сакчы $'; then
    result FAIL wordform-tt-сакчы "сакчы did not commit; field: '$wf_mid'"
elif [ "$SUGGESTIONS" != on ]; then
    result SKIP wordform-tt-сакчы "suggestions not enabled (non-debuggable package)"
elif [ "$wf_after" = "$wf_mid" ]; then
    result FAIL wordform-tt-сакчы "middle cell tap committed nothing; field: '$wf_after'"
elif echo "$w2" | grep -qE '^сакчы.'; then
    result PASS wordform-tt-сакчы "cell 2 = $w2: inflected form offered in a free cell and committed"
else
    result FAIL wordform-tt-сакчы "cell 2 committed '$w2' (expected a сакчы form); field: '$wf_after'"
fi

# ── эмодзи-панель: долгий тап запятой, тап по первой ячейке сетки ──
before_emoji=$(field_text)
LONGPRESSF ${COMMA%,*} ${COMMA#*,}
sleep 2
SHOT smoke-emoji-panel.png
# Первая ячейка сетки эмодзи (калибровка 1080×2280): x=0.059, y=0.777.
TAPF 0.059 0.777
sleep 1
after_emoji=$(field_text)
SHELL input keyevent KEYCODE_BACK   # закрыть панель
sleep 1
if [ ${#after_emoji} -gt ${#before_emoji} ] && echo "$after_emoji" | grep -qE '&#[0-9]+;|😀'; then
    result PASS emoji-panel "в поле закоммичен эмодзи: '$after_emoji'"
else
    result FAIL emoji-panel "поле до/после: '$before_emoji' → '$after_emoji'"
fi
SHOT smoke-final.png

# ── crash-буфер ──
A logcat -b crash -d > "$OUTDIR/logcat-crash.txt" 2>&1 || true
if grep -qE 'FATAL EXCEPTION|AndroidRuntime' "$OUTDIR/logcat-crash.txt"; then
    result FAIL crash-log "$(grep -cE 'FATAL EXCEPTION' "$OUTDIR/logcat-crash.txt") FATAL в crash-буфере"
else
    result PASS crash-log "crash-буфер пуст"
fi

# ── итог ──
passes=$(grep -c '^RESULT|PASS|' "$RESULTS" || true)
skips=$(grep -c '^RESULT|SKIP|' "$RESULTS" || true)
echo "RESULT|SUMMARY|pass=$passes fail=$FAILURES skip=$skips|outdir=$OUTDIR" | tee -a "$RESULTS"
[ "$FAILURES" = 0 ]
