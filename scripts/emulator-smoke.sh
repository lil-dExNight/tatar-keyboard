#!/bin/bash
# Emulator smoke test of the keyboard. Scenario: boot the AVD, install the APK,
# enable and select the IME, open SetupActivity, check the keyboard is up, type
# "мин" (tt) / "при" (ru) / "hi" (en) and check suggestions, cycle languages with
# the globe key tt -> ru -> en -> tt, open the emoji panel (long press on comma)
# and commit an emoji, and require an empty crash buffer. Additional probes on the
# tt layout (word-form suggestions, predictions after an accepted suggestion,
# learned emoji, emoji panel BACK) are described where they are defined.
#
# Flags:
#   --avd <name>     AVD (default tt_suggest_a14)
#   --apk <path>     APK (default app/build/outputs/apk/debug/app-debug.apk)
#   --no-boot        the emulator is already running; do not boot or kill it
#   --outdir <path>  evidence directory (default build/emulator-smoke/)
#
# The IME is selected by its FULL component id: a relative component name resolves
# against applicationId, so the short `org.tatarkeyboard.ime/.latin.LatinIME` does
# not work.
#
# uiautomator does not see the IME window (keys, suggestion strip and emoji panel are
# absent from the dump), so checks work around it:
#   - typed text is read from the SetupActivity try-it EditText, which also proves
#     the layout: "при" typed at ru coordinates on the tt layout gives other letters;
#   - the current language is read from pref_current_subtype via run-as (debuggable
#     package); on a release APK probe_layout taps the top-left letter key, which
#     gives "ә" on tt, "й" on ru, "q" on en;
#   - suggestions are a pixel delta of the strip between screenshots before and after
#     typing (ImageMagick compare; without ImageMagick the check is SKIP);
#   - the emoji panel check taps the first grid cell, which must commit an emoji to
#     the field (it appears as &#...; in the XML dump);
#   - keyboard up is dumpsys input_method mInputShown=true (see keyboard_shown).
#
# Suggestions are off by default. On a debuggable package the pref is written via
# run-as before the app starts, then the process is force-stopped, because a live
# process keeps the old prefs in memory and does not re-read the file. Force-stopping
# the selected IME resets default_input_method, so `ime set` must run after force-stop.
#
# The pref write goes straight into the package's shared_prefs, bypassing the
# SharedPreferences API. Only pref_tatar_suggestions and
# pref_tatar_suggestions_offer_spent are changed or added, other keys are kept, but
# nothing protects against a concurrent write by the app. Run on dedicated test AVDs,
# not on an emulator whose state you care about.
#
# Key coordinates are screen fractions calibrated on tt_suggest_a14 (1080x2280), like
# KeyGeom in baselineprofile/ImeBaselineProfileGenerator.java; on another screen size
# typing fails, by design. Bounds are read from the dump only where the widget is in
# it (the try-it field). On a non-debuggable APK the language switch is verified by
# probe_layout rather than a blind series of globe taps: the cycle order is rebuilt
# (resetSubtypeCycleOrder), so without reading the pref the taps could land on the
# wrong layout.
#
# Output: machine-readable `RESULT|PASS|FAIL|SKIP|check|detail` lines on stdout and in
# $OUTDIR/result.txt; any FAIL gives a non-zero exit code. The emulator is shut down
# if the script booted it.

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

result() {  # result PASS|FAIL|SKIP <check> <detail>
    local line="RESULT|$1|$2|$3"
    echo "$line"
    echo "$line" >> "$RESULTS"
    [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
}

log() { echo "smoke: $*" >&2; }

[ -x "$ADB" ] || { echo "adb не найден: $ADB" >&2; exit 2; }
[ -f "$APK" ] || { echo "APK не найден: $APK" >&2; exit 2; }

# The package is not hard-coded: the debug build has applicationIdSuffix ".debug"
# (app/build.gradle). It is read from the APK with aapt2, and the IME id is derived
# from it. aapt2 comes from the pinned $TT_BUILD_TOOLS_PIN directory
# (scripts/build-tools-pin.sh) or, if absent, the newest installed build-tools;
# dump output is stable across versions.
source "$ROOT/scripts/build-tools-pin.sh"
if [ -d "$SDK_ROOT/build-tools/$TT_BUILD_TOOLS_PIN" ]; then
    AAPT2=$(find "$SDK_ROOT/build-tools/$TT_BUILD_TOOLS_PIN" -name aapt2 2>/dev/null | sort -V | tail -1)
else
    AAPT2=$(find "$SDK_ROOT/build-tools" -name aapt2 2>/dev/null | sort -V | tail -1)
fi
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

# ── boot ──────────────────────────────────────────────────────────────────────

pick_serial() {
    if [ -n "${ANDROID_SERIAL:-}" ]; then echo "$ANDROID_SERIAL"; return; fi
    "$ADB" devices | awk '$2 == "device" && $1 ~ /^emulator-/ {print $1; exit}'
}

if [ "$NO_BOOT" = 0 ]; then
    [ -x "$EMULATOR" ] || { echo "emulator не найден: $EMULATOR" >&2; exit 2; }
    # Remember the emulators already running: our instance is the NEW serial in
    # adb devices; otherwise, with another emulator alive, the scenario could run
    # on the wrong device.
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
    # package manager and systemui come up later than boot_completed
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
    # An empty wm size answer (surfaceflinger not up yet, or the device is in a
    # bad state) would otherwise make TAPF die under set -e without a RESULT line.
    result FAIL screen-size "wm size вернул пустоту — координаты клавиш не вычислить"
    exit 1
fi
if [ "$wh" != "1080x2280" ]; then
    log "ВНИМАНИЕ: экран $wh, координаты клавиш откалиброваны под 1080x2280"
fi

A() { "$ADB" -s "$SERIAL" "$@"; }            # adb on the selected device
SHELL() { A shell "$@"; }                    # adb shell
SHOT() { A exec-out screencap -p > "$OUTDIR/$1" 2>/dev/null; }
DUMP_UI() {                                  # uiautomator dump → stdout
    SHELL uiautomator dump /data/local/tmp/smoke-ui.xml >/dev/null 2>&1
    A exec-out cat /data/local/tmp/smoke-ui.xml 2>/dev/null | tr -d '\r'
}
TAPF() {                                     # screen fractions: TAPF 0.42 0.85
    local x y
    x=$(python3 -c "print(round($1 * ${wh%x*}))")
    y=$(python3 -c "print(round($2 * ${wh#*x}))")
    SHELL input tap "$x" "$y"
}
LONGPRESSF() {                               # long press at screen fractions
    local x y
    x=$(python3 -c "print(round($1 * ${wh%x*}))")
    y=$(python3 -c "print(round($2 * ${wh#*x}))")
    SHELL input swipe "$x" "$y" "$x" "$y" 900
}
keyboard_shown() {
    # Uses mInputShown, not mIsInputViewShown. The latter is sticky by platform design:
    # InputMethodService.updateInputViewShown only re-evaluates it while the decor is
    # visible, so after the first BACK-hide it stays true forever (Gboard shows the same).
    # mInputShown is the IMMS-side field that actually flips on hide/show.
    SHELL dumpsys input_method 2>/dev/null | grep -q "mInputShown=true"
}
field_text() {                               # text of the SetupActivity try-it field
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

# Pixel delta of the suggestion strip (union of both keyboard heights: the 5-row tt
# layout and the 4-row ru/en layouts). An empty strip against a strip with words
# differs by several thousand pixels; STRIP_DIFF_MIN is well below that.
STRIP_CROP="1080x190+0+1300"
STRIP_DIFF_MIN=2000
strip_diff() {                               # strip_diff before.png after.png → AE
    # compare prints the metric to stderr and exits 1 when images differ, so the
    # output is captured whole instead of relying on the exit code (pipefail).
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

# ── install and select the IME ──────────────────────────────────────────────────

A install -r "$APK" >"$OUTDIR/install.log" 2>&1 \
    && result PASS install "$(basename "$APK") pkg=$PKG" \
    || { result FAIL install "$(tail -1 "$OUTDIR/install.log")"; exit 1; }

# On slow or old images (API 30) the IME does not appear in the list right after
# install, so poll for up to 30 s. `ime list -a -s`, not `-s`: on API 30 the list
# without `-a` has only ENABLED IMEs (ours is not enabled yet), on API 34 all of
# them; `-a` works on both.
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

# Enable suggestions through the pref BEFORE the app first reads its settings.
# Read the current prefs file, change or add only our two keys and keep the rest;
# if there is no file yet (clean AVD), write a minimal stub.
SUGGESTIONS=off
PREFS_PATH="/data/user_de/0/$PKG/shared_prefs/${PKG}_preferences.xml"
if A shell "run-as $PKG true" >/dev/null 2>&1; then
    # `|| true`: on a clean AVD (or with -no-snapshot-save discarding every
    # previous session) the prefs file does not exist yet and run-as cat exits
    # non-zero; with pipefail + set -e that would kill the script before the
    # python below writes its minimal stub.
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
        xml = ""  # no file, or not XML: write the whole stub below
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

# First grid cell of the emoji panel (x=0.059 = first column center, 8 columns across the
# full width). The y depends on the mode because the panel top moves with the suggestion
# strip: strip visible (debuggable + suggestions on) -> panel top 1297, first section
# header 1418-1500, row 0 center 1568 px ~ 0.6877; strip hidden (release) -> panel top
# 1418, row 0 center 1689 px ~ 0.7408. Row 0 is always an emoji cell whatever the recents
# state: with recents it is the recents row, without them the first Smileys row. A single
# y between the two modes would land on the second section header when recents exist.
if [ "$SUGGESTIONS" = on ]; then GRID_CELL0_Y=0.6877; else GRID_CELL0_Y=0.7408; fi

# A live process keeps the old prefs in memory, so stop it. Force-stopping the
# selected IME resets default_input_method, so the IME is selected again AFTER.
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

read_pref() {                                # read_pref <name> -> value or ""
    [ "$SUGGESTIONS" = on ] || { echo ""; return; }
    A shell "run-as $PKG cat $PREFS_PATH" 2>/dev/null | tr -d '\r' \
        | grep -oP "name=\"$1\"[^>]*>\\K[^<]*" | head -1 || true
}

# ── scenario ──────────────────────────────────────────────────────────────────

current_focus() {
    SHELL dumpsys window 2>/dev/null | tr -d '\r' \
        | grep -oP 'mCurrentFocus=Window\{[0-9a-f]+ u[0-9]+ \K[^}]+' | tail -1 || true
}

SHELL logcat -b crash -c 2>/dev/null || true   # clear the crash buffer up front

# SetupActivity must get focus; otherwise the whole scenario types into another
# app's field.
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

# Tap the center of the try-it field using fresh bounds from the dump. Do not press
# BACK to reach a "clean state": if the IME window has not hidden yet (race after a
# rebind), BACK goes to the activity and closes it. The field is visible in both
# states (adjustResize), so fresh bounds are enough.
bounds=$(DUMP_UI | grep -oP '<node[^>]*setup_test_field[^>]*bounds="\[\K[0-9,\]\[]+' | head -1 || true)
if [ -n "$bounds" ]; then
    read -r x1 y1 x2 y2 <<<"$(echo "$bounds" | tr '[],' '    ')"
    SHELL input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
else
    TAPF 0.5 0.81   # usual field position with the keyboard hidden
fi
shown=0
for _ in $(seq 1 30); do
    keyboard_shown && { shown=1; break; }
    sleep 1
done
SHELL dumpsys input_method > "$OUTDIR/dumpsys-input_method.txt" 2>&1
if [ "$shown" = 1 ]; then
    result PASS keyboard-up "mInputShown=true"
else
    result FAIL keyboard-up "клавиатура не поднялась за 30 с"
fi

field0=$(field_text)
if [ "$field0" = "__NOFIELD__" ]; then
    result FAIL field-empty "try-it поле не найдено в дампе"
elif [ -z "$field0" ] || [[ "$field0" == "Try it:"* || "$field0" == "Сынап карагыз:"* || "$field0" == "Попробуйте:"* ]]; then
    # uiautomator reports the hint as text, so an empty field shows the hint. The
    # prefixes match setup_test_field_hint in all three locales (the default UI
    # language is Tatar, so the English prefix alone is not enough).
    result PASS field-empty "try-it поле пустое после чистого старта"
else
    result FAIL field-empty "в поле уже есть текст: '$field0'"
fi

# Checks "word typed + suggestions shown" for one layout.
# $1 tag (tt/ru/en), $2 word for the report, $3 key coordinates,
# $4 regex for the expected end of the field, $5 "nosuggest" for a layout without a dictionary.
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

# Space commits the word, so the next layout starts with no composing text
# (otherwise suggestions would be computed for the concatenation "минпри").
SPACE="0.55,0.9075"

# Key coordinates (screen fractions, tt_suggest_a14 1080x2280).
# tt, 5 rows: row 1 ("йцукен") y~0.7206, row 2 ("фыва") y~0.7851, row 3 ("ячсм") y~0.8474.
TT_MIN="0.4231,0.8474 0.5138,0.8474 0.5000,0.7206"          # м и н
# ru/en, 4 rows: row 1 y~0.6829, row 2 y~0.7575, row 3 y~0.8329.
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

# Suggestion strip cells: three equal thirds (SuggestionStripState), centers x = (i + 0.5) / 3.
# The y is measured on the tt 5-row layout (1080x2280, density 2.75). The 44dp strip (121 px)
# sits on the keyboard's top edge (1418 px) and spans ~1297-1418 px, so its center is
# ~1357.5 px ~ 0.5954.
STRIP_CELL0="0.1667,0.5954"
STRIP_CELL1="0.5000,0.5954"
STRIP_CELL2="0.8333,0.5954"

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

# type_tt_and_tap_cell2 <word> <tag> [cell]: type <word> + space on the tt layout,
# screenshot the strip, tap the given suggestion cell (only when suggestions
# are on; default the last cell), read the field again. Stdout: field-after-space <TAB>
# field-after-tap.
type_tt_and_tap_cell2() {
    local word="$1" tag="$2" cell="${3:-$STRIP_CELL2}" coords mid after
    coords=$(tt_word_coords "$word")
    type_word "$coords"
    TAPF ${SPACE%,*} ${SPACE#*,}
    sleep 2                              # the NEXT_WORD answer is asynchronous
    mid=$(field_text)
    SHOT "smoke-wordform-${tag}.png"
    if [ "$SUGGESTIONS" = on ]; then
        TAPF ${cell%,*} ${cell#*,}
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

# ── languages via the globe key: tt -> ru -> en -> tt ──
# The globe key cycles the language list, and the list order is rebuilt
# (resetSubtypeCycleOrder, MRU rotation of the pref), so a blind series of taps
# lands unpredictably. On a debuggable package the feedback is the
# pref_current_subtype pref (run-as); on a release APK the pref is unreadable, so a
# functional probe detects the layout and the script taps until it matches.

# Functional probe of the current layout (for a release APK, where run-as is not
# available). The point (0.045, 0.665) is inside "ә" in the top Tatar row of the tt
# layout (6 keys of 16.667%: "ә" covers x 0-0.167); on the 4-row ru/en layouts the same
# pixel is the first key of the first row: "й" (ru, 11 keys of 9.091%) and "q" (en,
# 10 keys of 10%). The probed character is erased with KEYCODE_DEL.
PROBE_KEY="0.045,0.665"
probe_layout() {                           # -> tatar|russian|qwerty|""
    local before after
    before=$(field_text)
    [ "$before" = "__NOFIELD__" ] && { echo ""; return; }
    [[ "$before" == "Try it:"* ]] && before=""   # an empty field reports the hint as text
    TAPF ${PROBE_KEY%,*} ${PROBE_KEY#*,}
    sleep 0.6
    after=$(field_text)
    if [ ${#after} -le ${#before} ]; then
        echo ""                            # the tap missed the key
        return
    fi
    SHELL input keyevent KEYCODE_DEL       # erase the probed character
    sleep 0.4
    if   [[ "$after" == *"ә" || "$after" == *"Ә" ]]; then echo tatar
    elif [[ "$after" == *"й" || "$after" == *"Й" ]]; then echo russian
    elif [[ "$after" == *"q" || "$after" == *"Q" ]]; then echo qwerty
    else echo ""
    fi
}

switch_and_check() {                         # $1 tag, $2 expected layout
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
    # Release: the pref is unreadable, so use the functional probe. The probe types a
    # character, and commitText calls resetSubtypeCycleOrder (the current language
    # moves to the head of the cycle), so a SINGLE globe tap after a probe always
    # returns to the previous layout: tt and ru alternate and en is never reached.
    # A DOUBLE tap with no typing in between goes from [cur, prev, X] to X; three
    # probes in a row cover all three layouts.
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

# ── word forms after a committed tt word + space ──
# Tap-and-read, not pixel diff: uiautomator does not see the IME window, so the
# strip content is proven by tapping a cell and reading the try-it field.
# Two probes against the bundled assets (the bigram table stores up to four
# successors per head; the strip shows three):
#  1. татар is a bigram head whose first three successors (теле, дәүләт, телен)
#     fill the whole strip, so cell 2 (index 1) must commit дәүләт: bigram
#     successors take priority and neither word forms nor the fallback run
#     (CompositePrefixComputer). Any other word fails.
#  2. сәләм is not a head, so its one attested after-word form (сәләмә) comes
#     first and the global top words fill the rest (һәм, белән): cell 1
#     (index 0) must commit сәләмә, proving word forms come before the fill.
wf=$(type_tt_and_tap_cell2 "татар" tatar "$STRIP_CELL1")
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
    result FAIL wordform-tt-татар "cell-2 tap committed nothing; field: '$wf_after'"
elif [ "$w2" = "дәүләт" ]; then
    result PASS wordform-tt-татар "cell 2 = дәүләт: the successors fill the whole strip (pinned)"
else
    result FAIL wordform-tt-татар "cell 2 committed '$w2' (expected дәүләт from the pinned successor row); field: '$wf_after'"
fi

wf=$(type_tt_and_tap_cell2 "сәләм" syalam "$STRIP_CELL0")
wf_mid="${wf%$'\t'*}"
wf_after="${wf#*$'\t'}"
w2=$(second_word_after "$wf_after" "сәләм")
if [ "$wf_mid" = "__NOFIELD__" ] || [ "$wf_after" = "__NOFIELD__" ]; then
    result FAIL wordform-tt-сәләм "try-it field not in the dump"
elif ! echo "$wf_mid" | grep -qE 'сәләм $'; then
    result FAIL wordform-tt-сәләм "сәләм did not commit; field: '$wf_mid'"
elif [ "$SUGGESTIONS" != on ]; then
    result SKIP wordform-tt-сәләм "suggestions not enabled (non-debuggable package)"
elif [ "$wf_after" = "$wf_mid" ]; then
    result FAIL wordform-tt-сәләм "cell-1 tap committed nothing; field: '$wf_after'"
elif [ "$w2" = "сәләмә" ]; then
    result PASS wordform-tt-сәләм "cell 1 = сәләмә: the after-word form leads the fill (pinned)"
else
    result FAIL wordform-tt-сәләм "cell 1 committed '$w2' (expected сәләмә); field: '$wf_after'"
fi

# ── next-word predictions right after an accepted suggestion ──
# The сәләм probe's cell-1 tap committed сәләмә with a trailing space, and the tap
# itself must re-issue the NEXT_WORD lookup without waiting for a keystroke.
# сәләмә is not a bigram head and has no word forms, so the strip is the top-word
# fill [һәм, белән, да]: a second tap on cell 1 (index 0) must commit һәм.
if [ "$SUGGESTIONS" != on ]; then
    result SKIP tap-followup-tt-сәләм "suggestions not enabled (non-debuggable package)"
elif [ "$wf_after" = "__NOFIELD__" ] || [ "$wf_mid" = "__NOFIELD__" ] || [ "$wf_after" = "$wf_mid" ]; then
    result SKIP tap-followup-tt-сәләм "the сәләм probe above already failed; nothing to follow up on"
else
    sleep 2                          # the follow-up NEXT_WORD answer is asynchronous
    TAPF ${STRIP_CELL0%,*} ${STRIP_CELL0#*,}
    sleep 1
    wf_again=$(field_text)
    w3=$(second_word_after "$wf_again" "$w2")
    if [ "$wf_again" = "__NOFIELD__" ]; then
        result FAIL tap-followup-tt-сәләм "try-it field not in the dump"
    elif [ "$wf_again" = "$wf_after" ]; then
        result FAIL tap-followup-tt-сәләм "strip stayed empty after the accepted suggestion; field: '$wf_again'"
    elif [ "$w3" = "һәм" ]; then
        result PASS tap-followup-tt-сәләм "cell 1 of the follow-up band = һәм: predictions for the accepted word without a keystroke"
    else
        result FAIL tap-followup-tt-сәләм "follow-up cell 1 committed '$w3' (expected һәм from the fill); field: '$wf_again'"
    fi
fi

# ── emoji panel: long press on comma, tap the first grid cell ──
before_emoji=$(field_text)
LONGPRESSF ${COMMA%,*} ${COMMA#*,}
sleep 2
SHOT smoke-emoji-panel.png
# Tap the first grid cell at GRID_CELL0_Y (set above; depends on whether the suggestion
# strip shifts the panel top). The panel has no separate search bar (search is a cell in
# the tab row), so the grid starts right below the tabs: gridTop = panel top + 121.
TAPF 0.059 "$GRID_CELL0_Y"
sleep 1
after_emoji=$(field_text)
SHELL input keyevent KEYCODE_BACK   # close the panel
sleep 1
if [ ${#after_emoji} -gt ${#before_emoji} ] && echo "$after_emoji" | grep -qE '&#[0-9]+;|😀'; then
    result PASS emoji-panel "в поле закоммичен эмодзи: '$after_emoji'"
else
    result FAIL emoji-panel "поле до/после: '$before_emoji' → '$after_emoji'"
fi
SHOT smoke-final.png

# Reopen SetupActivity, tap the try-it field, wait for the keyboard, settle 2 s.
# Shared by panel-back-reopen and the learned-emoji-tt rounds. Each round restarts the
# activity because BACK hides the WHOLE IME window by platform design, and a further
# BACK lands on SetupActivity and closes it.
refocus_tryit() {
    SHELL am start --activity-clear-task -n "$PKG/$SETUP_ACTIVITY" >/dev/null 2>&1
    sleep 3
    local b x1 y1 x2 y2
    b=$(DUMP_UI | grep -oP '<node[^>]*setup_test_field[^>]*bounds="\[\K[0-9,\]\[]+' | head -1 || true)
    if [ -n "$b" ]; then
        read -r x1 y1 x2 y2 <<<"$(echo "$b" | tr '[],' '    ')"
        SHELL input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
    else
        TAPF 0.5 0.81
    fi
    local i
    for i in $(seq 1 15); do
        keyboard_shown && break
        sleep 1
    done
    keyboard_shown || return 1
    # The keyboard reports shown before its first frame accepts touches; without this
    # settle the first tap of a round is lost.
    sleep 2
    return 0
}

# ── panel-back-reopen ──
# BACK out of the open panel hides the WHOLE IME window by design (framework
# InputMethodService.handleBack -> requestHideSelf; Gboard behaves the same), so the
# probe must re-tap the field after BACK before typing; key taps right after BACK would
# hit no keyboard. Steps: refocus the try-it field -> long-press comma -> prove the panel
# opened (the first-grid-cell tap commits an emoji, as in the emoji-panel probe) -> BACK
# -> re-tap the field (fresh bounds, the window resize may have moved it) -> tap м ->
# PASS if the field ends with м -> DEL cleanup.
pbr_detail=""
pbr_field=""
pbr_shown=""
if ! refocus_tryit; then
    pbr_detail="keyboard did not come up on the pre-panel refocus"
fi
if [ -z "$pbr_detail" ]; then
    LONGPRESSF ${COMMA%,*} ${COMMA#*,}
    sleep 2
    SHOT smoke-panel-back-reopen-panel.png
    TAPF 0.059 "$GRID_CELL0_Y"   # first grid cell, same calibration as the emoji-panel probe
    sleep 1
    pbr_panel=$(field_text)
    if [ "$pbr_panel" = "__NOFIELD__" ]; then
        pbr_detail="try-it field not in the dump after the grid-cell tap"
    elif ! echo "$pbr_panel" | grep -qE '&#[0-9]+;|😀'; then
        pbr_detail="panel did not open: the first-grid-cell tap committed no emoji; field: '$pbr_panel'"
    fi
fi
if [ -z "$pbr_detail" ]; then
    SHELL input keyevent KEYCODE_BACK
    sleep 1
    # Evidence only: mInputShown=false here is the platform-normal hidden state.
    pbr_shown=$(SHELL dumpsys input_method 2>/dev/null | grep -oP 'mInputShown=\K\w+' | head -1 || true)
    b=$(DUMP_UI | grep -oP '<node[^>]*setup_test_field[^>]*bounds="\[\K[0-9,\]\[]+' | head -1 || true)
    if [ -n "$b" ]; then
        read -r x1 y1 x2 y2 <<<"$(echo "$b" | tr '[],' '    ')"
        SHELL input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
    else
        TAPF 0.5 0.81
    fi
    sleep 2
    TAPF 0.4231 0.8474      # м on the tt layout (same key as TT_MIN)
    sleep 0.6
    pbr_field=$(field_text)
    SHELL input keyevent KEYCODE_DEL    # probe cleanup: the м (the next probe's activity
    sleep 0.4                           # restart clears the emoji the panel check committed)
fi
if [ -n "$pbr_detail" ]; then
    result FAIL panel-back-reopen "$pbr_detail"
elif [ "$pbr_field" = "__NOFIELD__" ]; then
    result FAIL panel-back-reopen "try-it field not in the dump after the м tap"
elif echo "$pbr_field" | grep -qE 'м$'; then
    result PASS panel-back-reopen "BACK hid the window (mInputShown=$pbr_shown), field re-tap revived it; field: '$pbr_field'"
else
    result FAIL panel-back-reopen "post-BACK field re-tap did not revive the keyboard; field: '$pbr_field' (expected a м tail)"
fi

# ── learned-emoji-tt: a learned word -> emoji pair takes the last strip cell ──
# Two observations of one (word, emoji) co-usage make it a learned emoji
# (PersonalEmojiStore.LEARN_THRESHOLD = 2), which then outranks the static table for the
# last strip cell (SuggestionsController consults the personal source first).
# Probe: type "хәйерле иртә" + space on the tt layout (the static table maps иртә -> 🌅,
# emoji_suggest_v1.txt), then insert ☀️ through the emoji SEARCH (panel -> the search cell
# in the tab row -> query "кояш" -> the ☀️ result cell), twice; on the third
# "хәйерле иртә" + space the last cell must commit ☀️, not 🌅.
#
# Mechanics (calibrated on tt_suggest_a14, 1080x2280):
#   - search opens from the 🔍 cell at the RIGHT END of the tab row. The row's cells share
#     the width inside the 8dp side insets: with 10 categories (recents + 9 base; the
#     emoji-panel probe above already committed 😀) the cell spans x in [954.4, 1058] px,
#     with 9 (no recents) [942.9, 1058]; x=0.9407 (1016 px) hits it in both. The row center
#     is y=0.5954: the panel top is the strip's top (1297 px) and the row is 44dp = 121 px;
#   - with a query the 100dp search area sits where the strip was, the result row centered
#     at y~0.5908; "кояш" ranks ☀️ fifth (keyword bucket in asset order: 😎 🌻 🌅 🌇 ☀️ ...),
#     cell center x~0.6157;
#   - after a pick the search stays open; BACK out of the panel/search hides the WHOLE IME
#     window (platform design, see panel-back-reopen), and a BACK at the wrong moment lands
#     on SetupActivity and closes it, so each round reopens the activity instead;
#   - the first query letter doubles as the search-open check: routed into the search it
#     never reaches the try-it field, while a missed tap lands on the panel grid (an emoji
#     appears in the field) or on the letter keyboard (a composing letter does);
#   - the probe's prefs are seeded via run-as like the suggestions pref, but only here:
#     personal dictionary ON (default off) + emoji suggestions ON (default on, seeded so a
#     reused AVD with it off still works), so every earlier probe runs with unchanged prefs.
if [ "$SUGGESTIONS" != on ]; then
    result SKIP learned-emoji-tt "suggestions not enabled (non-debuggable package)"
else
    # force-stop first: a live process could flush its in-memory counters over the files
    # the rm below deletes. Then the learned store is removed (a reused AVD may have one),
    # the two prefs are seeded, and the IME is selected again (force-stopping the selected
    # IME resets default_input_method, as at the top of the script).
    SHELL am force-stop "$PKG" || true
    sleep 1
    # (run-as execs its command directly, without a shell, so the glob needs sh -c.)
    A shell "run-as $PKG sh -c 'rm -f /data/data/$PKG/no_backup/personal/personal-emoji-* /data/data/$PKG/no_backup/personal/salt-emoji.bin'" \
        2>/dev/null || true
    # The AVD disk persists across runs: the probe's ☀️ picks land in the recent-emoji
    # list, and a ☀️ at the top of recents would change what the emoji-panel probe commits
    # on the NEXT run (it expects 😀). Back up the recents file here; the epilogue restores it.
    if A shell "run-as $PKG cat /data/data/$PKG/no_backup/recent_emoji_v1" \
        > "$OUTDIR/recents-before-emoji-learn.bin" 2>/dev/null; then
        RECENTS_SAVED=1
    else
        RECENTS_SAVED=0
    fi
    A shell "run-as $PKG cat $PREFS_PATH" 2>/dev/null | tr -d '\r' \
        > "$OUTDIR/prefs-before-emoji-learn.xml" || true
    python3 - "$OUTDIR/prefs-before-emoji-learn.xml" > "$OUTDIR/prefs-emoji-learn.xml" <<'PYEOF'
import re
import sys
from pathlib import Path

source = Path(sys.argv[1])
xml = source.read_text(encoding="utf-8") if source.is_file() else ""
KEYS = ("pref_personal_dictionary", "pref_emoji_suggestions")

for key in KEYS:
    entry = f'<boolean name="{key}" value="true" />'
    pattern = re.compile(rf'<boolean name="{key}" value="[^"]*" ?/>')
    if pattern.search(xml):
        xml = pattern.sub(entry, xml)
    elif "</map>" in xml:
        xml = xml.replace("</map>", f"    {entry}\n</map>", 1)
    else:
        xml = ""  # not parseable: write the minimal stub below
        break
if not xml:
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           + "".join(f'    <boolean name="{key}" value="true" />\n' for key in KEYS)
           + "</map>\n")
sys.stdout.write(xml)
PYEOF
    A shell "run-as $PKG sh -c 'cat > $PREFS_PATH'" < "$OUTDIR/prefs-emoji-learn.xml" || true
    SHELL ime enable "$IME_ID" >/dev/null 2>&1 || true
    SHELL ime set "$IME_ID" >/dev/null 2>&1 || true
    sleep 1

    # The ☀️ cluster (U+2600 U+FE0F) at the field's tail, literal or XML-escaped, spaces allowed.
    SUN_TAIL_RE='(☀️|&#9728;(&#65039;)?) *$'
    detail=""
    for round in 1 2; do
        refocus_tryit || { detail="keyboard did not come up before round $round"; break; }
        type_word "$(tt_word_coords "хәйерле")"
        TAPF ${SPACE%,*} ${SPACE#*,}
        type_word "$(tt_word_coords "иртә")"
        TAPF ${SPACE%,*} ${SPACE#*,}
        sleep 1.5
        f=$(field_text)
        echo "$f" | grep -qE '^хәйерле иртә $' \
            || { detail="round $round: phrase did not commit; field: '$f'"; break; }
        LONGPRESSF ${COMMA%,*} ${COMMA#*,}
        sleep 2
        TAPF 0.9407 0.5954       # the tab row's rightmost cell: the 🔍 search cell
        sleep 3                  # first open per process loads the search index on a worker
        before_k=$(field_text)
        TAPF 0.3182 0.7206       # к: the search-open check (must not reach the field)
        sleep 0.8
        after_k=$(field_text)
        [ "$after_k" = "$before_k" ] \
            || { detail="round $round: the search did not open (the probe letter reached the field): '$after_k'"; break; }
        type_word "$(tt_word_coords "ояш")"
        sleep 1.5
        SHOT "smoke-learned-emoji-search-$round.png"
        TAPF 0.6157 0.5908       # the ☀️ result cell (5th for "кояш", asset order)
        sleep 1.5
        f=$(field_text)
        echo "$f" | grep -qE "$SUN_TAIL_RE" \
            || { detail="round $round: the search pick committed no ☀️; field: '$f'"; break; }
    done
    if [ -z "$detail" ]; then
        refocus_tryit || detail="keyboard did not come up for the verification round"
    fi
    if [ -z "$detail" ]; then
        type_word "$(tt_word_coords "хәйерле")"
        TAPF ${SPACE%,*} ${SPACE#*,}
        type_word "$(tt_word_coords "иртә")"
        TAPF ${SPACE%,*} ${SPACE#*,}
        sleep 2                  # the NEXT_WORD answer (and the learned emoji) is asynchronous
        SHOT smoke-learned-emoji-band.png
        TAPF ${STRIP_CELL2%,*} ${STRIP_CELL2#*,}
        sleep 1
        f=$(field_text)
        if echo "$f" | grep -qE "$SUN_TAIL_RE"; then
            result PASS learned-emoji-tt "learned ☀️ overrode the static 🌅 in the strip tail; field: '$f'"
        else
            detail="the tail cell committed no ☀️ (the static answer would be 🌅); field: '$f'"
        fi
    fi
    [ -n "$detail" ] && result FAIL learned-emoji-tt "$detail"
    # Epilogue: restore the AVD state the probe found (prefs without the two seeded keys,
    # recents without the probe's ☀️, no learned store), so the NEXT run starts from the
    # same state. force-stop first: a live process would flush its in-memory state over
    # the restored files.
    SHELL am force-stop "$PKG" || true
    sleep 1
    A shell "run-as $PKG sh -c 'cat > $PREFS_PATH'" \
        < "$OUTDIR/prefs-before-emoji-learn.xml" 2>/dev/null || true
    if [ "$RECENTS_SAVED" = 1 ]; then
        A shell "run-as $PKG sh -c 'cat > /data/data/$PKG/no_backup/recent_emoji_v1'" \
            < "$OUTDIR/recents-before-emoji-learn.bin" 2>/dev/null || true
    else
        A shell "run-as $PKG rm -f /data/data/$PKG/no_backup/recent_emoji_v1" 2>/dev/null || true
    fi
    # (run-as execs its command directly, without a shell, so the glob needs sh -c.)
    A shell "run-as $PKG sh -c 'rm -f /data/data/$PKG/no_backup/personal/personal-emoji-* /data/data/$PKG/no_backup/personal/salt-emoji.bin'" \
        2>/dev/null || true
fi

# ── crash buffer ──
A logcat -b crash -d > "$OUTDIR/logcat-crash.txt" 2>&1 || true
if grep -qE 'FATAL EXCEPTION|AndroidRuntime' "$OUTDIR/logcat-crash.txt"; then
    result FAIL crash-log "$(grep -cE 'FATAL EXCEPTION' "$OUTDIR/logcat-crash.txt") FATAL в crash-буфере"
else
    result PASS crash-log "crash-буфер пуст"
fi

# ── summary ──
passes=$(grep -c '^RESULT|PASS|' "$RESULTS" || true)
skips=$(grep -c '^RESULT|SKIP|' "$RESULTS" || true)
echo "RESULT|SUMMARY|pass=$passes fail=$FAILURES skip=$skips|outdir=$OUTDIR" | tee -a "$RESULTS"
[ "$FAILURES" = 0 ]
