#!/bin/bash
# device-perf-ritual.sh — the per-release performance ritual against a real device
# (calibrated for the POCO C71, 720x1640, Android 15 HyperOS; O2/O4 of
# docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md, feeding docs/PERF-BUDGETS.md).
#
# Three legs, one machine-readable RESULT|... line per measurement (stdout and
# $OUTDIR/result.txt; raw evidence — meminfo dumps, framestats, screencaps — in
# $OUTDIR):
#   a) cold start x5: process start (field 22 of /proc/<pid>/stat, CLK_TCK=100)
#      -> first FrameCompleted of the InputMethod window (gfxinfo framestats).
#      The process is killed with `run-as <pkg> kill -9`, NOT am force-stop:
#      kill -9 keeps the selected IME untouched (HyperOS resets the default IME
#      when the SELECTED package is force-stopped, and `ime set` starts the IME
#      process immediately, which would contaminate the process-start timestamp).
#      kill -9 is also the realistic cold-start trigger (LMK reaping). The host
#      field is Chrome's omnibox (foreign package: the process provably starts
#      as the IME, not as our own setup activity).
#   b) PSS via dumpsys meminfo after four scenarios on our own SetupActivity
#      try-it field: keyboard open idle; after 50 scripted words (25 tt + 25 ru);
#      emoji panel open (the historical peak) and after close; after 30 s hidden
#      (the idle-release path fires at 10 s: glide + emoji indexes drop).
#   c) frame stats (O2-2 protocol): gfxinfo reset -> fixed 32-event Tatar typing
#      script ("сәләм дөнья мин сине яратам дус " — 27 letters + 5 spaces,
#      0.35 s between taps) -> the InputMethod window's last <=120 PROFILEDATA
#      rows; frame duration = FrameCompleted - IntendedVsync; p50/p90/p95 per
#      run, 3 runs.
#
# Force-stop discipline: safe_force_stop() NEVER force-stops the package while
# its IME is the selected one — it reads `settings get secure default_input_method`
# first and dances through a neutral IME (Gboard) when needed. The primary kill
# path is run-as kill -9, which needs no dance at all.
#
# Columns of the framestats CSV are located BY HEADER NAME: on Android 15
# FrameCompleted is column 17, not 14 as in the pre-FrameTimeline format the
# 2026-09-04 cold-start script assumed (it silently read SyncStart — ~30 ms
# earlier on the cold first frame here (26.6–32.7 ms across the five
# 2026-09-29 runs); still comfortably inside the 400 ms budget).
#
# Key coordinates are computed from the layout XMLs (rows_tatar/rows_russian +
# row_qwerty4) against the 720x1640 screen and verified on 2026-09-29 screencaps
# (build/device-uat-2026-09-29/perf/): tt rows y=1110/1206/1301/1396, bottom row
# y=1500; ru rows y=1153/1258/1363, bottom row y=1468; the globe and the space
# bar are tapped at y=1490 which lands inside the bottom row of BOTH layouts.
#
# Flags:
#   --serial <id>     device serial (default: $ANDROID_SERIAL, else the single
#                     online device, else the POCO C71 serial)
#   --pkg <package>   package under test (default org.tatarkeyboard.ime.debug;
#                     a non-debuggable package degrades cold start to SKIP)
#   --outdir <path>   evidence directory (default build/device-perf-ritual)
#   --cold-runs <n>   cold-start iterations (default 5)
#   --frame-runs <n>  frame-stat runs (default 3)
#
# Exit code: 0 with no FAIL line, 1 otherwise. The script is idempotent: the
# pre-run state (default IME, stay-on-while-plugged, screen awake/asleep) is
# restored by an EXIT trap.

set -uo pipefail

SERIAL="${ANDROID_SERIAL:-}"
PKG="org.tatarkeyboard.ime.debug"
OUTDIR=""
COLD_RUNS=5
FRAME_RUNS=3
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --pkg) PKG="$2"; shift 2 ;;
        --outdir) OUTDIR="$2"; shift 2 ;;
        --cold-runs) COLD_RUNS="$2"; shift 2 ;;
        --frame-runs) FRAME_RUNS="$2"; shift 2 ;;
        *) echo "unknown flag: $1" >&2; exit 2 ;;
    esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTDIR="${OUTDIR:-$ROOT/build/device-perf-ritual}"
mkdir -p "$OUTDIR"
RESULTS="$OUTDIR/result.txt"
: > "$RESULTS"
FAILURES=0

# Neutral IME for the force-stop dance (verified to exist in the preflight).
NEUTRAL_IME="com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
# Foreign host app for the cold-start leg: Chrome's omnibox is a deterministic
# text field (resource-id url_bar) on any screen size.
CHROME="com.android.chrome/com.google.android.apps.chrome.Main"
SETUP_ACTIVITY="rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity"

if [ -z "$SERIAL" ]; then
    SERIAL=$("$ADB" devices | awk '$2 == "device" {print $1; exit}')
fi
[ -n "$SERIAL" ] || { echo "no online device" >&2; exit 2; }

A() { "$ADB" -s "$SERIAL" "$@"; }
SHELL() { A shell "$@"; }
SHOT() { A exec-out screencap -p > "$OUTDIR/$1" 2>/dev/null; }

result() { # result PASS|FAIL|SKIP|INFO <check> <detail>
    local line="RESULT|$1|$2|$3"
    echo "$line"
    echo "$line" >> "$RESULTS"
    [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
}
log() { echo "ritual: $*" >&2; }

# ── state capture + restore ───────────────────────────────────────────────────

PREV_IME=$(SHELL settings get secure default_input_method 2>/dev/null | tr -d '\r')
PREV_STAYON=$(SHELL settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r')
PREV_AWAKE=$(SHELL dumpsys power 2>/dev/null | grep -oP 'mWakefulness=\K\w+' | head -1)
log "state on entry: ime=$PREV_IME stayon=$PREV_STAYON wakefulness=$PREV_AWAKE"

WOKEN=0
restore() {
    log "restoring device state"
    [ -n "$PREV_IME" ] && SHELL ime set "$PREV_IME" >/dev/null 2>&1 || true
    case "$PREV_STAYON" in
        ''|null|0) : ;;                       # was off; we never turned it off
        *) SHELL settings put global stay_on_while_plugged_in "$PREV_STAYON" >/dev/null 2>&1 || true ;;
    esac
    if [ "$WOKEN" = 1 ] && [ "$PREV_AWAKE" != "Awake" ]; then
        SHELL input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true
    fi
    SHELL input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
}
trap restore EXIT

# Screen on and kept on for the duration (USB power).
[ "$PREV_STAYON" = "0" ] || [ -z "$PREV_STAYON" ] || [ "$PREV_STAYON" = "null" ] \
    && SHELL svc power stayon true >/dev/null 2>&1 || true
SHELL "input keyevent KEYCODE_WAKEUP; input keyevent 82" >/dev/null 2>&1
WOKEN=1
sleep 1

# ── preflight ─────────────────────────────────────────────────────────────────

SHELL true >/dev/null 2>&1 || { result FAIL device "serial $SERIAL unreachable"; exit 1; }
MODEL=$(SHELL getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID_REL=$(SHELL getprop ro.build.version.release 2>/dev/null | tr -d '\r')
BATTERY=$(SHELL dumpsys battery 2>/dev/null | grep -oP 'level: \K\d+' | head -1)
WH=$(SHELL wm size 2>/dev/null | grep -oP '\d+x\d+' | head -1)

SHELL pm path "$PKG" >/dev/null 2>&1 || { result FAIL package "$PKG not installed"; exit 1; }
IME_ID=$(SHELL ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$PKG/" | head -1)
[ -n "$IME_ID" ] || { result FAIL ime-id "ime list shows no IME of $PKG"; exit 1; }
SHELL ime enable "$IME_ID" >/dev/null 2>&1 || true
if ! SHELL ime list -s 2>/dev/null | tr -d '\r' | grep -qx "$NEUTRAL_IME"; then
    NEUTRAL_IME=$(SHELL ime list -s 2>/dev/null | tr -d '\r' | grep -v "^$PKG/" | head -1)
fi
[ -n "$NEUTRAL_IME" ] || { result FAIL neutral-ime "no second IME to dance through"; exit 1; }
RUN_AS_OK=0
A shell "run-as $PKG true" >/dev/null 2>&1 && RUN_AS_OK=1
VER=$(SHELL dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | grep -oP '= *\K\S+')
{
    echo "date: $(date -Iseconds)"
    echo "serial: $SERIAL  model: $MODEL  android: $ANDROID_REL  screen: $WH"
    echo "package: $PKG  versionName: $VER  battery: ${BATTERY}%"
    echo "ime: $IME_ID  neutral: $NEUTRAL_IME  run-as: $RUN_AS_OK"
    echo "prev ime: $PREV_IME  prev stayon: $PREV_STAYON  prev wakefulness: $PREV_AWAKE"
    if [ -d "$ROOT/.git" ]; then
        dirty=clean; [ -n "$(git -C "$ROOT" status --porcelain)" ] && dirty=dirty
        echo "git: $(git -C "$ROOT" rev-parse --short HEAD) $dirty"
    fi
} > "$OUTDIR/meta.txt"
result INFO device "serial=$SERIAL model=$MODEL android=$ANDROID_REL screen=$WH pkg=$PKG version=$VER battery=${BATTERY}%"

if [ "$WH" != "720x1640" ]; then
    log "WARNING: screen $WH, key coordinates are calibrated for 720x1640"
    result INFO screen-size "screen $WH differs from the 720x1640 calibration; taps may miss"
fi

# Suggestions must be live for the frame leg (O2-2 measured with the strip
# updating). The debuggable package gets the same surgical run-as pref write the
# emulator smoke does; a non-debuggable package keeps whatever state it has.
PREFS_PATH="/data/user_de/0/$PKG/shared_prefs/${PKG}_preferences.xml"
if [ "$RUN_AS_OK" = 1 ]; then
    pref=$(SHELL "run-as $PKG cat $PREFS_PATH" 2>/dev/null | tr -d '\r' \
        | grep -oP 'name="pref_tatar_suggestions" value="\K[^"]*' | head -1 || true)
    if [ "$pref" != "true" ]; then
        A shell "run-as $PKG cat $PREFS_PATH" 2>/dev/null | tr -d '\r' > "$OUTDIR/prefs-before.xml" || true
        python3 - "$OUTDIR/prefs-before.xml" > "$OUTDIR/prefs-new.xml" <<'PYEOF'
import re
import sys
from pathlib import Path
source = Path(sys.argv[1])
xml = source.read_text(encoding="utf-8") if source.is_file() else ""
for key in ("pref_tatar_suggestions", "pref_tatar_suggestions_offer_spent"):
    entry = f'<boolean name="{key}" value="true" />'
    pattern = re.compile(rf'<boolean name="{key}" value="[^"]*" ?/>')
    if pattern.search(xml):
        xml = pattern.sub(entry, xml)
    elif "</map>" in xml:
        xml = xml.replace("</map>", f"    {entry}\n</map>", 1)
    else:
        xml = ""
if not xml:
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           + "".join(f'    <boolean name="{k}" value="true" />\n'
                     for k in ("pref_tatar_suggestions", "pref_tatar_suggestions_offer_spent"))
           + "</map>\n")
sys.stdout.write(xml)
PYEOF
        A shell "run-as $PKG sh -c 'mkdir -p \$(dirname $PREFS_PATH) && cat > $PREFS_PATH'" \
            < "$OUTDIR/prefs-new.xml" \
            && result INFO suggestions-pref "pref_tatar_suggestions=true written via run-as (was '${pref:-missing}')" \
            || result INFO suggestions-pref "pref write failed; keeping device state"
    else
        result INFO suggestions-pref "pref_tatar_suggestions already true"
    fi
else
    result INFO suggestions-pref "package not debuggable; suggestions state untouched"
fi

# ── helpers ───────────────────────────────────────────────────────────────────

current_ime() { SHELL settings get secure default_input_method 2>/dev/null | tr -d '\r'; }

keyboard_shown() {
    SHELL dumpsys input_method 2>/dev/null | grep -q "mInputShown=true"
}
wait_keyboard() { # wait_keyboard <seconds> -> 0 if mInputShown=true in time
    local deadline=$((SECONDS + $1))
    while [ $SECONDS -lt $deadline ]; do
        keyboard_shown && return 0
        sleep 0.5
    done
    return 1
}

# Never force-stop the package while its IME is selected: HyperOS silently
# resets the default IME. Dance through the neutral IME first, re-select ours
# after. (The cold leg uses run-as kill -9 instead; this is the fallback and
# the documented discipline for any future force-stop in this script.)
safe_force_stop() {
    local cur
    cur=$(current_ime)
    if [ "$cur" = "$IME_ID" ]; then
        SHELL ime set "$NEUTRAL_IME" >/dev/null 2>&1
        sleep 0.5
        SHELL am force-stop "$PKG" >/dev/null 2>&1
        sleep 0.5
        SHELL ime set "$IME_ID" >/dev/null 2>&1
    else
        SHELL am force-stop "$PKG" >/dev/null 2>&1
    fi
}

# kill_app: process death without touching the IME selection. run-as kill -9
# keeps default_input_method intact (verified 2026-09-29) and leaves the process
# start to the next field focus — the honest cold-start trigger.
kill_app() {
    local pid
    pid=$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')
    [ -z "$pid" ] && return 0
    if [ "$RUN_AS_OK" = 1 ]; then
        SHELL "run-as $PKG kill -9 $pid" >/dev/null 2>&1
    else
        safe_force_stop
    fi
    sleep 1
    pid=$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')
    [ -z "$pid" ]
}

dump_ui() {
    SHELL uiautomator dump /data/local/tmp/ritual-ui.xml >/dev/null 2>&1
    A exec-out cat /data/local/tmp/ritual-ui.xml 2>/dev/null | tr -d '\r'
}
# tap_node <resource-id-substring> -> taps the centre of the first match
tap_node() {
    local dump bounds x y
    dump=$(dump_ui)
    bounds=$(echo "$dump" | grep -oP "$1[^>]*bounds=\"\K[^\"]*" | head -1)
    [ -n "$bounds" ] || return 1
    read -r x y <<<"$(python3 -c "
import re, sys
m = [int(v) for v in re.findall(r'\d+', '$bounds')]
print((m[0]+m[2])//2, (m[1]+m[3])//2)")"
    SHELL input tap "$x" "$y" >/dev/null 2>&1
}

# raise_keyboard_over_setup: fresh SetupActivity (keyboard down -> try-it field
# visible), tap the field, wait for the IME window.
raise_keyboard_over_setup() {
    SHELL am start --activity-clear-task -n "$PKG/$SETUP_ACTIVITY" >/dev/null 2>&1
    sleep 3
    tap_node setup_test_field || return 1
    wait_keyboard 10
}

# Key coordinates via python (no host-locale dependence for the Cyrillic
# char splitting). Emits "x y" per event.
script_points() { # script_points tt|ru "word1 word2 ..."
    python3 - "$1" "$2" <<'PYEOF'
import sys
layout, text = sys.argv[1], sys.argv[2]
TT = {
    "ә": (60, 1110), "ө": (180, 1110), "ү": (300, 1110), "җ": (420, 1110),
    "ң": (540, 1110), "һ": (660, 1110),
    "й": (33, 1206), "ц": (98, 1206), "у": (164, 1206), "к": (229, 1206),
    "е": (295, 1206), "н": (360, 1206), "г": (425, 1206), "ш": (491, 1206),
    "щ": (556, 1206), "з": (622, 1206), "х": (687, 1206),
    "ф": (33, 1301), "ы": (98, 1301), "в": (164, 1301), "а": (229, 1301),
    "п": (295, 1301), "р": (360, 1301), "о": (425, 1301), "л": (491, 1301),
    "д": (556, 1301), "ж": (622, 1301), "э": (687, 1301),
    "я": (109, 1396), "ч": (172, 1396), "с": (235, 1396), "м": (297, 1396),
    "и": (360, 1396), "т": (423, 1396), "ь": (485, 1396), "б": (548, 1396),
    "ю": (611, 1396), " ": (396, 1490),
}
RU = {k: (x, {1110: 1153, 1206: 1153, 1301: 1258, 1396: 1363, 1490: 1490}[y])
      for k, (x, y) in TT.items()}
# The ru extra row does not exist; the tt extra row maps onto ru row 1 letters.
RU.update({"й": (33, 1153), "ц": (98, 1153), "у": (164, 1153), "к": (229, 1153),
           "е": (295, 1153), "н": (360, 1153), "г": (425, 1153), "ш": (491, 1153),
           "щ": (556, 1153), "з": (622, 1153), "х": (687, 1153),
           "ф": (33, 1258), "ы": (98, 1258), "в": (164, 1258), "а": (229, 1258),
           "п": (295, 1258), "р": (360, 1258), "о": (425, 1258), "л": (491, 1258),
           "д": (556, 1258), "ж": (622, 1258), "э": (687, 1258),
           "я": (109, 1363), "ч": (172, 1363), "с": (235, 1363), "м": (297, 1363),
           "и": (360, 1363), "т": (423, 1363), "ь": (485, 1363), "б": (548, 1363),
           "ю": (611, 1363), " ": (396, 1490)})
table = TT if layout == "tt" else RU
for ch in text:
    if ch not in table:
        sys.exit(f"no coordinate for {ch!r} on layout {layout}")
    print(*table[ch])
PYEOF
}

type_text() { # type_text tt|ru "text" <gap-seconds>
    local gap="$3" pts line x y
    # Materialize the point list BEFORE the tap loop: `adb shell` inside a
    # `while read` pipeline would eat the loop's stdin and silently drop every
    # point after the first few (hit 2026-09-29: 5 frames for a 32-tap script).
    pts=$(script_points "$1" "$2") || { log "script_points failed for layout $1"; return 1; }
    while IFS= read -r line; do
        x="${line% *}"; y="${line#* }"
        SHELL input tap "$x" "$y" </dev/null >/dev/null 2>&1
        sleep "$gap"
    done <<< "$pts"
}

# switch the subtype via the globe key with pref feedback (the cycle order is
# MRU-rotated, so blind tap counts are meaningless — same lesson as the smoke).
# The pref value looks like "tt_RU:tatar" — match a bare prefix, not "<code>:".
globe_to() { # globe_to tt|ru|en -> 0 when pref_current_subtype starts with the code
    local want="$1" i poll pref=""
    [ "$RUN_AS_OK" = 1 ] || return 2
    for i in 1 2 3 4 5; do
        for poll in 1 2 3; do
            pref=$(SHELL "run-as $PKG cat $PREFS_PATH" 2>/dev/null | tr -d '\r' \
                | grep -oP 'name="pref_current_subtype"[^>]*>\K[^<]*' | head -1 || true)
            case "$pref" in "$want"*) return 0 ;; esac
            sleep 0.8
        done
        SHELL input tap 216 1490 </dev/null >/dev/null 2>&1   # globe, y shared by tt/ru bottom rows
        sleep 1.2
    done
    return 1
}

meminfo_total() { # meminfo_total <label> -> echoes "total_kb swap_kb"; dumps the raw file
    SHELL dumpsys meminfo "$PKG" > "$OUTDIR/meminfo-$1.txt" 2>/dev/null
    awk '/TOTAL PSS:/ {print $3, $NF; exit}' "$OUTDIR/meminfo-$1.txt"
}
pss_point() { # pss_point <scenario>
    local kb sw
    read -r kb sw <<<"$(meminfo_total "$1")"
    if [ -z "${kb:-}" ]; then
        result FAIL pss "scenario=$1 unreadable (process dead?)"
    else
        result INFO pss "scenario=$1 total_kb=$kb swap_kb=$sw"
    fi
}

# framestats parser: window section by name, columns by header name.
framestats() { # framestats <file> <window-regex> durations|firstframe
    python3 - "$1" "$2" "$3" <<'PYEOF'
import re
import sys
path, window, mode = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding="utf-8", errors="replace").read()
match = re.search(rf"^Window: .*{window}.*$", text, re.M)
if not match:
    sys.exit(f"window '{window}' not in the framestats dump")
section = text[match.end():]
nxt = section.find("\nWindow: ")
if nxt >= 0:
    section = section[:nxt]
blocks = section.split("---PROFILEDATA---")
rows, header = [], None
for block in blocks:
    for line in block.splitlines():
        if line.startswith("Flags,"):
            header = line.rstrip(",").split(",")
        elif header and re.match(r"^[01],", line):
            cells = line.rstrip(",").split(",")
            if len(cells) > header.index("FrameCompleted"):
                rows.append(cells)
if not header or not rows:
    sys.exit("no PROFILEDATA rows")
done_i, vsync_i = header.index("FrameCompleted"), header.index("IntendedVsync")
if mode == "firstframe":
    print(rows[0][done_i])
else:
    durs = sorted((int(r[done_i]) - int(r[vsync_i])) / 1e6 for r in rows)
    def pct(p):
        return durs[min(len(durs) - 1, int(p * len(durs) / 100))]
    print(f"{len(durs)} {pct(50):.2f} {pct(90):.2f} {pct(95):.2f}")
PYEOF
}

# ── leg A: cold start xN ─────────────────────────────────────────────────────
# Dance: ours must NOT be force-stopped while selected. kill -9 needs no dance;
# the ime selection is only touched to make ours the default once, up front.

if [ "$RUN_AS_OK" != 1 ]; then
    result SKIP cold-start "package $PKG not debuggable; run-as kill -9 unavailable (force-stop would contaminate the start timestamp via ime set)"
else
    cur=$(current_ime)
    [ "$cur" = "$IME_ID" ] || SHELL ime set "$IME_ID" >/dev/null 2>&1
    # Chrome to the front once; dismiss a possible first-run dialog (BACK).
    SHELL am start -n "$CHROME" >/dev/null 2>&1
    sleep 4
    dump_ui | grep -q "url_bar" || { SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 2; }
    colds=()
    cold_skips=0
    for i in $(seq 1 "$COLD_RUNS"); do
        # defocus: hide the keyboard / leave the omnibox editor of the last round
        SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1
        sleep 1
        kill_app || { cold_skips=$((cold_skips + 1)); log "cold $i: kill failed"; continue; }
        SHELL am start -n "$CHROME" >/dev/null 2>&1
        sleep 2
        tap_node url_bar || { cold_skips=$((cold_skips + 1)); log "cold $i: url_bar not found"; continue; }
        if ! wait_keyboard 10; then
            # the field may have kept focus: defocus once and re-tap
            SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1
            sleep 1
            tap_node url_bar
            wait_keyboard 10 || { cold_skips=$((cold_skips + 1)); log "cold $i: keyboard never shown"; continue; }
        fi
        sleep 1
        pid=$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')
        start=$(SHELL cat "/proc/$pid/stat" 2>/dev/null | awk '{print $22}' | tr -d '\r')
        SHELL dumpsys gfxinfo "$PKG" framestats > "$OUTDIR/cold-framestats-$i.txt" 2>/dev/null
        frame=$(framestats "$OUTDIR/cold-framestats-$i.txt" "InputMethod" firstframe 2>/dev/null || true)
        if [ -z "${start:-}" ] || [ -z "${frame:-}" ] || [ "$frame" = "0" ]; then
            cold_skips=$((cold_skips + 1)); log "cold $i: no data (pid='$pid' start='${start:-}' frame='${frame:-}')"; continue
        fi
        ms=$(python3 -c "print(f'{int($frame)/1e6 - int($start)*10.0:.1f}')")
        colds+=("$ms")
        log "cold $i: ${ms} ms (pid $pid)"
    done
    if [ ${#colds[@]} -ge 3 ]; then
        stats=$(printf '%s\n' "${colds[@]}" | python3 -c "
import sys
v = sorted(float(x) for x in sys.stdin)
n = len(v)
med = v[n // 2] if n % 2 else (v[n // 2 - 1] + v[n // 2]) / 2
print(f'{med:.1f} {v[0]:.1f} {v[-1]:.1f}')")
        read -r med mn mx <<<"$stats"
        over=$(python3 -c "print('true' if float('$med') >= 400 else 'false')")
        result INFO cold-start "runs=${#colds[@]} skips=$cold_skips median_ms=$med min_ms=$mn max_ms=$mx values_ms=$(IFS=,; echo "${colds[*]}") budget_ms=400 over_budget=$over build=debug"
    else
        result FAIL cold-start "only ${#colds[@]} valid runs of $COLD_RUNS"
    fi
fi

# ── leg B: PSS across four scenarios ─────────────────────────────────────────

kill_app || true
cur=$(current_ime)
[ "$cur" = "$IME_ID" ] || SHELL ime set "$IME_ID" >/dev/null 2>&1
if raise_keyboard_over_setup; then
    sleep 3
    pss_point keyboard-idle
    SHOT pss-keyboard-idle.png

    globe_to tt || result INFO pss "tt layout unconfirmed before the tt typing half"
    log "typing 25 tt words"
    type_text tt "сәләм дөнья мин сине яратам дус һәм белән татар теле дәүләт китап укытучы мәктәп иртә кич бүген әти әни бала су юл өй кеше якты " 0.12
    if globe_to ru; then
        log "typing 25 ru words"
        type_text ru "привет время дом работа город улица книга школа друг семья день ночь утро вечер вода хлеб мир свет язык река море поле лес гора человек " 0.12
    else
        result INFO pss "ru half of the typing leg skipped: globe switch unconfirmed"
    fi
    sleep 2
    pss_point after-50-words
    SHOT pss-after-50-words.png

    # emoji panel: long-press the comma key (bottom row), settle, measure peak
    SHELL input swipe 144 1490 144 1490 900 >/dev/null 2>&1
    sleep 3
    pss_point emoji-panel-open
    SHOT pss-emoji-panel-open.png
    SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1   # panel -> letters
    sleep 2
    pss_point emoji-panel-closed

    keyboard_shown && SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1   # hide the IME window
    sleep 1
    log "idle 30 s (the deallocate pass fires at 10 s)"
    sleep 30
    pss_point idle-30s
    SHOT pss-idle-30s.png
else
    result FAIL pss "keyboard did not raise over SetupActivity"
fi

# ── leg C: frame stats, O2-2 protocol (32-event tt script, <=120 frames x3) ──

FRAME_SCRIPT="сәләм дөнья мин сине яратам дус "   # 27 letters + 5 spaces = 32 events
n_events=$(python3 -c "print(len('$FRAME_SCRIPT'))")
log "frame script: '$FRAME_SCRIPT' ($n_events events)"
if [ "$n_events" != "32" ]; then
    result FAIL frames "script must be exactly 32 events, got $n_events"
else
    for run in $(seq 1 "$FRAME_RUNS"); do
        if ! raise_keyboard_over_setup; then
            result FAIL "frames-run-$run" "keyboard did not raise"
            continue
        fi
        if ! globe_to tt; then
            result FAIL "frames-run-$run" "tt layout unconfirmed (globe_to tt failed)"
            continue
        fi
        sleep 9        # let the suggestion engine publish (slow on this class of device)
        SHELL dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
        sleep 0.5
        type_text tt "$FRAME_SCRIPT" 0.35
        sleep 1.5
        SHELL dumpsys gfxinfo "$PKG" framestats > "$OUTDIR/frames-run$run.txt" 2>/dev/null
        out=$(framestats "$OUTDIR/frames-run$run.txt" "InputMethod" durations 2>/dev/null || true)
        if [ -z "$out" ]; then
            result FAIL "frames-run-$run" "no InputMethod PROFILEDATA rows"
        else
            read -r n p50 p90 p95 <<<"$out"
            if [ "$n" -lt 32 ]; then
                result FAIL "frames-run-$run" "only n=$n frames for a 32-event script — taps not landing"
            else
                result INFO frames "run=$run n=$n p50_ms=$p50 p90_ms=$p90 p95_ms=$p95 protocol=O2-2 script=32-event-tt window=InputMethod"
            fi
        fi
    done
    # functional proof that the frame leg typed the Tatar script on the tt
    # layout: hide the keyboard and read the try-it field back
    SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1.5
    field=$(dump_ui | grep -oP '<node[^>]*setup_test_field[^>]*' | grep -oP 'text="\K[^"]*' | head -1 || true)
    case "$field" in
        *"яратам"*) result INFO frames-proof "try-it field holds the tt script tail as typed" ;;
        *) result FAIL frames-proof "unexpected field content: '${field: -60}'" ;;
    esac
fi

log "done; failures: $FAILURES"
[ "$FAILURES" = 0 ]
