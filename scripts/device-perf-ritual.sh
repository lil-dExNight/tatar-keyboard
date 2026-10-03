#!/bin/bash
# device-perf-ritual.sh: per-release performance check on a real device
# (calibrated for the POCO C71, 720x1640, Android 15 HyperOS).
#
# Legs, one machine-readable RESULT|... line per measurement (stdout and
# $OUTDIR/result.txt; raw evidence such as meminfo dumps, framestats, traces and
# screencaps goes to $OUTDIR). Every budgeted line carries budget_* and
# over_budget=true|false:
#   cold) cold start: process start (field 22 of /proc/<pid>/stat, CLK_TCK=100,
#      CLOCK_BOOTTIME, corrected to CLOCK_MONOTONIC) -> first FrameCompleted of
#      the InputMethod window (gfxinfo framestats). Host field: Chrome's omnibox
#      (foreign package, so the process starts as the IME). Two triggers:
#      - tap (debuggable package): `run-as <pkg> kill -9`, then a tap on the
#        omnibox starts the process. kill -9 keeps the IME selection and matches
#        how the system reclaims memory.
#      - switch (non-debuggable package, where run-as is refused and shell may
#        not signal the app uid): select the neutral IME, `am kill <pkg>` (it
#        only kills processes the system no longer binds, so it works once ours
#        is deselected), raise the neutral keyboard on the omnibox, then
#        `ime set <ours>`. The bind starts our process with the show request
#        already pending, so process start -> first frame is the same path as
#        after a tap. `am crash` is not used: a second crash inside a minute
#        shows the "keeps stopping" dialog and writes a crash report. No path
#        force-stops the package (HyperOS resets the default IME when the
#        package of the selected IME is force-stopped); the selection is put
#        back after every run and restored on exit.
#      The first --cold-warmup runs after an install are discarded (one-time
#      first-start work).
#   pss) PSS via dumpsys meminfo after four scenarios on our own SetupActivity
#      try-it field: keyboard open idle; after 50 scripted words (25 tt + 25 ru);
#      emoji panel open (the expected peak) and after close; after 30 s hidden
#      (the idle-release path fires at 10 s: glide + emoji indexes drop). The
#      ceiling depends on the build: debuggable builds run on the debug scale.
#      Each scenario also saves /proc/<pid>/smaps_rollup (its anon/file split
#      — RssAnon/RssFile or Pss_Anon/Pss_File by kernel — joins the result
#      line; skipped with an INFO note when the process or the file is
#      unreadable) and an App-Summary per-category extract.
#   frames) frame stats: gfxinfo reset -> fixed 32-event Tatar typing
#      script ("сәләм дөнья мин сине яратам дус ": 27 letters + 5 spaces,
#      0.35 s between taps) -> the InputMethod window's last <=120 PROFILEDATA
#      rows; frame duration = FrameCompleted - IntendedVsync; p50/p90/p95 per
#      run, 3 runs, p95 against the 16.7 ms deadline. The same dump gives the
#      janky frames line: "Janky frames" of the InputMethod window section
#      (frames that missed their FrameTimeline deadline) over the same script.
#   warm) warm show x5: process alive, keyboard hidden for 2 s (before the 10 s
#      idle release) -> tap on Chrome's omnibox -> first FrameCompleted of the
#      InputMethod window after a gfxinfo reset. The tap time is the
#      eventTimeNano of the ACTION_UP in the atrace `input view` capture (the
#      framework's deliverInputEvent slice of the receiving app). Both clocks
#      are CLOCK_MONOTONIC, and the time of `input tap` itself is not counted.
#   touch) touch handling: atrace `input view` capture over the 32-event script;
#      duration of every deliverInputEvent slice on the IME main thread. That
#      slice wraps the view's onTouchEvent, PointerTracker and the synchronous
#      commit to the editor, so it is an upper bound of the time spent in our
#      code per event; p95 is held to the budget. Event -> end of handling
#      (eventTimeNano to the slice end, trace clock aligned with the
#      trace_event_clock_sync marker) is printed without a budget.
#   suggest) suggestion round trip: atrace over the 32-event tt script with the
#      app sections of $PKG enabled (`-a "$PKG"`; a debuggable package allows
#      it, a release one does via its profileable-shell manifest flag). Every
#      lookup is an async TT#suggestLookup slice; the parser pairs the S/F
#      markers by cookie, writes the durations to suggest-lookup-ms.txt and
#      holds p95 to SUGGEST_BUDGET_MS. Zero slices is a FAIL: the gate is the
#      capture itself.
#   battery) opt-in, never interleaved with the USB-power perf legs: it
#      simulates the unplugged state (`dumpsys battery unplug`, restored on
#      exit). Idle protocol: keyboard hidden, batterystats reset, then a
#      --battery-seconds window; the utime+stime delta of /proc/<pid>/stat
#      (format-stable, clock ticks converted with CLK_TCK) must stay under an
#      observability ceiling and the wake lock, sensor and alarm counts of the
#      per-package batterystats dump must be zero. Active protocol: reset, then
#      the 32-event tt script, cpu_ms per event on the result line.
#   uimode) opt-in probe: screencaps before and after `cmd uimode night yes`
#      (flipped back right away), the byte-diff count of the two PNGs on the
#      result line; 0 means the palette did not follow the night flip.
#      Evidence only, never fails.
#   fontscale) opt-in probe: screencaps at the current font_scale and at 1.3.
#      Key labels are canvas-drawn in pixels and must ignore the system font
#      scale, so the whole-screen byte compare must be empty. Without image
#      tools there is no crop to the keyboard region, and a status-bar clock
#      tick or a cursor blink also diffs: check a FAIL against the saved PNGs
#      before acting on it.
#
# Release (non-debuggable) builds: --enable-suggestions-ui turns word
# suggestions on by driving the app's own settings screen with uiautomator
# (SettingsActivity -> Preferences row -> the suggestions switch, found by
# resource-id), and turns them off again on exit if they were off. The layout
# is then checked by typing a probe key and reading the try-it field back,
# because the current-language pref is readable only through run-as.
#
# Columns of the framestats CSV are located BY HEADER NAME: on Android 15
# FrameCompleted is column 17, not 14 as in the pre-FrameTimeline format, and a
# fixed index would silently read another column (SyncStart).
#
# Key coordinates are computed from the layout XMLs (rows_tatar/rows_russian +
# row_qwerty4) for the 720x1640 screen and checked on screenshots: tt rows
# y=1110/1206/1301/1396, bottom row y=1500; ru rows y=1153/1258/1363, bottom row
# y=1468; the globe and the space bar are tapped at y=1490, which is inside the
# bottom row of BOTH layouts.
#
# Flags:
#   --serial <id>     device serial (default: $ANDROID_SERIAL, else the single
#                     online device, else the POCO C71 serial)
#   --pkg <package>   package under test (default org.tatarkeyboard.ime.debug)
#   --outdir <path>   evidence directory (default build/device-perf-ritual)
#   --legs <list>     comma-separated subset of cold,pss,frames,warm,touch,suggest
#                     and the opt-in battery,uimode,fontscale (default: the six
#                     non-opt-in legs)
#   --cold-runs <n>   recorded cold-start iterations (default 5)
#   --cold-warmup <n> discarded cold-start iterations before them (default 1)
#   --cold-trigger <tap|switch>  cold-start trigger (default: tap for a
#                     debuggable package, switch otherwise)
#   --frame-runs <n>  frame-stat runs (default 3)
#   --warm-runs <n>   warm-show iterations (default 5)
#   --battery-seconds <n>  idle window of the battery leg (default 150)
#   --enable-suggestions-ui  turn suggestions on through the settings screen
#                     instead of the run-as pref write
#
# Exit code: 0 with no FAIL line, 1 otherwise. The script is idempotent: the
# pre-run state (default IME, enabled IMEs, stay-on-while-plugged, screen
# awake/asleep, the suggestions switch when set through the UI, atrace, the
# simulated unplug, night mode and the font scale) is restored by an EXIT trap.

set -uo pipefail

SERIAL="${ANDROID_SERIAL:-}"
PKG="org.tatarkeyboard.ime.debug"
OUTDIR=""
LEGS="cold,pss,frames,warm,touch,suggest"
COLD_RUNS=5
COLD_WARMUP=1
COLD_TRIGGER=""
FRAME_RUNS=3
WARM_RUNS=5
BATTERY_SECONDS=150
SUGGESTIONS_UI=0
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --pkg) PKG="$2"; shift 2 ;;
        --outdir) OUTDIR="$2"; shift 2 ;;
        --legs) LEGS="$2"; shift 2 ;;
        --cold-runs) COLD_RUNS="$2"; shift 2 ;;
        --cold-warmup) COLD_WARMUP="$2"; shift 2 ;;
        --cold-trigger) COLD_TRIGGER="$2"; shift 2 ;;
        --frame-runs) FRAME_RUNS="$2"; shift 2 ;;
        --warm-runs) WARM_RUNS="$2"; shift 2 ;;
        --battery-seconds) BATTERY_SECONDS="$2"; shift 2 ;;
        --enable-suggestions-ui) SUGGESTIONS_UI=1; shift ;;
        *) echo "unknown flag: $1" >&2; exit 2 ;;
    esac
done
case "$COLD_TRIGGER" in ''|tap|switch) : ;; *) echo "bad --cold-trigger: $COLD_TRIGGER" >&2; exit 2 ;; esac
case "$BATTERY_SECONDS" in ''|*[!0-9]*) echo "bad --battery-seconds: $BATTERY_SECONDS" >&2; exit 2 ;; esac
leg_on() { case ",$LEGS," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

# Budgets printed with the RESULT lines. The PSS ceiling of a debuggable build is
# on the debug scale (a debug build holds far more memory than a release build).
COLD_BUDGET_MS=400
WARM_BUDGET_MS=150
TOUCH_BUDGET_MS=5
SUGGEST_BUDGET_MS=32
FRAME_BUDGET_MS=16.7
JANK_BUDGET_PCT=1.0
PSS_BUDGET_DEBUG_KB=114000
PSS_BUDGET_RELEASE_KB=69000
# Idle-window CPU ceiling of the battery leg: an observability assertion (it
# catches a stuck worker or a wake loop), not a tuned budget. The debug build
# gets its own ceiling: it runs the JIT and more GC, and the 10 s deallocate
# pass fires inside the window by design.
BATTERY_IDLE_CPU_BUDGET_MS=1000
BATTERY_IDLE_CPU_BUDGET_DEBUG_MS=2000
CLK_TCK=$(getconf CLK_TCK 2>/dev/null || echo 100)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTDIR="${OUTDIR:-$ROOT/build/device-perf-ritual}"
mkdir -p "$OUTDIR"
RESULTS="$OUTDIR/result.txt"
: > "$RESULTS"
FAILURES=0

# Neutral IME to switch to before a force-stop (checked in the preflight).
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
PREV_ENABLED=$(SHELL settings get secure enabled_input_methods 2>/dev/null | tr -d '\r')
PREV_STAYON=$(SHELL settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r')
PREV_AWAKE=$(SHELL dumpsys power 2>/dev/null | grep -oP 'mWakefulness=\K\w+' | head -1)
PREV_FONTSCALE=$(SHELL settings get system font_scale 2>/dev/null | tr -d '\r')
log "state on entry: ime=$PREV_IME stayon=$PREV_STAYON wakefulness=$PREV_AWAKE font_scale=$PREV_FONTSCALE"

WOKEN=0
IME_ID=""
IME_WAS_ENABLED=1
SUGG_UI_RESTORE=""     # "false" when the UI switch was turned on by this run
ATRACE_ON=0
BATTERY_ON=0           # the battery leg simulated unplug; reset restores the real state
UIMODE_ON=0            # the uimode probe flipped night mode
FONTSCALE_ON=0         # the fontscale probe overrode font_scale
restore() {
    log "restoring device state"
    [ "$ATRACE_ON" = 1 ] && SHELL atrace --async_stop >/dev/null 2>&1 || true
    [ "$BATTERY_ON" = 1 ] && SHELL dumpsys battery reset >/dev/null 2>&1 || true
    [ "$UIMODE_ON" = 1 ] && SHELL cmd uimode night no >/dev/null 2>&1 || true
    if [ "$FONTSCALE_ON" = 1 ]; then
        # a never-set font_scale reads back null; delete restores the default
        # instead of pinning an explicit value
        case "$PREV_FONTSCALE" in
            ''|null) SHELL settings delete system font_scale >/dev/null 2>&1 || true ;;
            *) SHELL settings put system font_scale "$PREV_FONTSCALE" >/dev/null 2>&1 || true ;;
        esac
    fi
    if [ -n "$SUGG_UI_RESTORE" ]; then
        set_suggestions_ui "$SUGG_UI_RESTORE" >/dev/null \
            && log "suggestions switch restored to $SUGG_UI_RESTORE" \
            || log "WARNING: could not restore the suggestions switch to $SUGG_UI_RESTORE"
    fi
    [ -n "$PREV_IME" ] && SHELL ime set "$PREV_IME" >/dev/null 2>&1 || true
    if [ "$IME_WAS_ENABLED" = 0 ] && [ -n "$IME_ID" ]; then
        SHELL ime disable "$IME_ID" >/dev/null 2>&1 || true
    fi
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
case ":$PREV_ENABLED:" in *":$IME_ID:"*|*":$IME_ID;"*) : ;; *) IME_WAS_ENABLED=0 ;; esac
SHELL ime enable "$IME_ID" >/dev/null 2>&1 || true
if ! SHELL ime list -s 2>/dev/null | tr -d '\r' | grep -qx "$NEUTRAL_IME"; then
    NEUTRAL_IME=$(SHELL ime list -s 2>/dev/null | tr -d '\r' | grep -v "^$PKG/" | head -1)
fi
[ -n "$NEUTRAL_IME" ] || { result FAIL neutral-ime "no second IME to dance through"; exit 1; }
RUN_AS_OK=0
A shell "run-as $PKG true" >/dev/null 2>&1 && RUN_AS_OK=1
if [ "$RUN_AS_OK" = 1 ]; then
    BUILD=debug; PSS_BUDGET_KB=$PSS_BUDGET_DEBUG_KB; BATTERY_CPU_BUDGET=$BATTERY_IDLE_CPU_BUDGET_DEBUG_MS
    [ -n "$COLD_TRIGGER" ] || COLD_TRIGGER=tap
else
    BUILD=release; PSS_BUDGET_KB=$PSS_BUDGET_RELEASE_KB; BATTERY_CPU_BUDGET=$BATTERY_IDLE_CPU_BUDGET_MS
    [ -n "$COLD_TRIGGER" ] || COLD_TRIGGER=switch
fi
VER=$(SHELL dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | grep -oP '= *\K\S+')
{
    echo "date: $(date -Iseconds)"
    echo "serial: $SERIAL  model: $MODEL  android: $ANDROID_REL  screen: $WH"
    echo "package: $PKG  versionName: $VER  battery: ${BATTERY}%"
    echo "ime: $IME_ID  neutral: $NEUTRAL_IME  run-as: $RUN_AS_OK  build: $BUILD"
    echo "legs: $LEGS  cold trigger: $COLD_TRIGGER  suggestions via UI: $SUGGESTIONS_UI"
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

# Suggestions must be on for the frame leg (frames are measured with the strip
# updating). The debuggable package gets the same targeted run-as pref write as
# the emulator smoke unless --enable-suggestions-ui is given; the UI path runs
# after the helpers below. Without either, the package keeps its state.
PREFS_PATH="/data/user_de/0/$PKG/shared_prefs/${PKG}_preferences.xml"
if [ "$SUGGESTIONS_UI" = 1 ]; then
    :
elif [ "$RUN_AS_OK" = 1 ]; then
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
    result INFO suggestions-pref "package not debuggable and no --enable-suggestions-ui; suggestions state untouched"
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

# kill_app: kill the process without a force-stop (HyperOS resets the default IME
# when the package of the selected IME is force-stopped).
# - debuggable: run-as kill -9; the IME selection stays and the next field focus
#   starts the process.
# - otherwise: select the neutral IME, then `am kill`, which only reaches a
#   process the system no longer binds. The neutral IME stays selected; callers
#   select ours again when they need it (`ime set` starts the process at once).
# `kill_app deselect` takes the second path for any package.
kill_app() {
    local pid
    if [ "$RUN_AS_OK" = 1 ] && [ "${1:-}" != deselect ]; then
        pid=$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')
        [ -z "$pid" ] && return 0
        SHELL "run-as $PKG kill -9 $pid" >/dev/null 2>&1
    else
        [ "$(current_ime)" = "$IME_ID" ] && SHELL ime set "$NEUTRAL_IME" >/dev/null 2>&1
        sleep 1
        SHELL am kill "$PKG" >/dev/null 2>&1
    fi
    sleep 1
    pid=$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')
    [ -z "$pid" ]
}
select_ours() { [ "$(current_ime)" = "$IME_ID" ] || SHELL ime set "$IME_ID" >/dev/null 2>&1; }

dump_ui() {
    SHELL uiautomator dump /data/local/tmp/ritual-ui.xml >/dev/null 2>&1
    A exec-out cat /data/local/tmp/ritual-ui.xml 2>/dev/null | tr -d '\r'
}
# tap_node <resource-id-substring> -> taps the center of the first match
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

# Text of the try-it field. uiautomator leaves out views covered by the IME
# window, so this works only while the keyboard is hidden.
field_text() {
    dump_ui | grep -oP '<node[^>]*setup_test_field[^>]*' | grep -oP ' text="\K[^"]*' | head -1
}

SETTINGS_ACTIVITY="rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity"
# node_attr <dump> <resource-id> <attr> -> value of the attribute on the first node with that id
node_attr() {
    echo "$1" | grep -oP "<node[^>]*resource-id=\"$PKG:id/$2\"[^>]*" | head -1 \
        | grep -oP " $3=\"\K[^\"]*"
}
# set_suggestions_ui true|false: open the app's settings, go to Preferences and
# set the word-suggestions switch (resource-id row_switch_tatar_suggestions)
# with a real tap. Prints the state found before the change; returns 0 when the
# switch ends in the wanted state.
set_suggestions_ui() {
    local want="$1" dump state bounds x y i
    SHELL am start --activity-clear-task -n "$PKG/$SETTINGS_ACTIVITY" >/dev/null 2>&1
    sleep 3
    tap_node "$PKG:id/row_link_preferences" || { SHELL input keyevent KEYCODE_HOME >/dev/null 2>&1; return 1; }
    sleep 2
    for i in 1 2 3; do
        dump=$(dump_ui)
        bounds=$(node_attr "$dump" row_switch_tatar_suggestions bounds)
        if [ -n "$bounds" ]; then
            read -r x y <<<"$(python3 -c "
import re
m = [int(v) for v in re.findall(r'\d+', '$bounds')]
print((m[0] + m[2]) // 2, (m[1] + m[3]) // 2 if m[3] - m[1] >= 80 else -1)")"
            [ "$y" -gt 0 ] && break
        fi
        # row missing or clipped by the screen edge: scroll the list up and look again
        SHELL input swipe 360 1300 360 800 400 >/dev/null 2>&1
        sleep 1.5
        bounds=""
    done
    [ -n "$bounds" ] || { SHELL input keyevent KEYCODE_HOME >/dev/null 2>&1; return 1; }
    state=$(node_attr "$dump" row_switch_tatar_suggestions checked)
    echo "$state"
    if [ "$state" != "$want" ]; then
        SHELL input tap "$x" "$y" >/dev/null 2>&1
        sleep 1.5
        state=$(node_attr "$(dump_ui)" row_switch_tatar_suggestions checked)
    fi
    SHELL input keyevent KEYCODE_HOME >/dev/null 2>&1
    sleep 1
    [ "$state" = "$want" ]
}

# probe_layout: with the keyboard up over the try-it field, tap the top-left key
# (x=60, y=1110: "ә" on tt, "й" on ru, "q" on en), hide the keyboard, read the
# field back, show the keyboard again and delete the probe letter. Prints
# tt|ru|en|unknown. Showing the keyboard by a tap on the field moves the cursor
# to the tap point, so Ctrl+End puts it back at the end before each edit.
cursor_to_end() { SHELL input keycombination KEYCODE_CTRL_LEFT KEYCODE_MOVE_END </dev/null >/dev/null 2>&1; sleep 0.3; }
probe_layout() {
    local after last lay
    cursor_to_end
    SHELL input tap 60 1110 </dev/null >/dev/null 2>&1
    sleep 0.8
    SHELL input keyevent KEYCODE_BACK </dev/null >/dev/null 2>&1
    sleep 1.2
    after=$(field_text)
    last=$(python3 -c 'import sys; t = sys.argv[1]; print(t[-1].lower() if t else "")' "$after")
    case "$last" in ә) lay=tt ;; й) lay=ru ;; q) lay=en ;; *) lay=unknown; log "probe read '${after: -20}'" ;; esac
    tap_node setup_test_field </dev/null && wait_keyboard 5 </dev/null
    sleep 0.5
    cursor_to_end
    case "$lay" in
        tt) SHELL input tap 687 1396 </dev/null >/dev/null 2>&1 ;;   # tt backspace
        ru|en) SHELL input tap 687 1363 </dev/null >/dev/null 2>&1 ;;  # ru/en backspace
    esac
    sleep 0.5
    echo "$lay"
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
    # point after the first few.
    pts=$(script_points "$1" "$2") || { log "script_points failed for layout $1"; return 1; }
    while IFS= read -r line; do
        x="${line% *}"; y="${line#* }"
        SHELL input tap "$x" "$y" </dev/null >/dev/null 2>&1
        sleep "$gap"
    done <<< "$pts"
}

# Switch the language via the globe key with pref feedback (the cycle order is
# MRU-rotated, so blind tap counts are meaningless; see emulator-smoke.sh).
# The pref value looks like "tt_RU:tatar", so match a bare prefix, not "<code>:".
# Without run-as the feedback comes from probe_layout instead of the pref.
globe_to() { # globe_to tt|ru|en -> 0 when the wanted layout is active
    local want="$1" i poll pref=""
    if [ "$RUN_AS_OK" != 1 ]; then
        for i in 1 2 3 4; do
            pref=$(probe_layout)
            # a probe tap that lands during the show animation reads back nothing: probe again
            [ "$pref" = unknown ] && { sleep 1; pref=$(probe_layout); }
            log "layout probe: $pref (want $want)"
            [ "$pref" = "$want" ] && return 0
            SHELL input tap 216 1490 </dev/null >/dev/null 2>&1   # globe
            sleep 1.2
        done
        return 1
    fi
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
# meminfo_categories <dump> <out>: the per-category PSS lines of the App Summary
# (evidence file; the plain per-category table has no colons, so these match
# only the summary lines).
meminfo_categories() {
    grep -E '^[[:space:]]+(Java Heap|Native Heap|Code|Graphics|Private Other|System|TOTAL):' \
        "$1" > "$2" 2>/dev/null || true
}
# smaps_rollup <pid> <out>: /proc/<pid>/smaps_rollup of the package process.
# A shell read works where ptrace rules allow it; run-as is the fallback for a
# debuggable package. The anon/file field names vary by kernel: RssAnon/RssFile
# or Pss_Anon/Pss_File — accept either.
smaps_rollup() {
    SHELL cat "/proc/$1/smaps_rollup" > "$2" 2>/dev/null
    grep -qE '^(RssAnon|Pss_Anon):' "$2" 2>/dev/null && return 0
    [ "$RUN_AS_OK" = 1 ] || return 1
    SHELL "run-as $PKG cat /proc/$1/smaps_rollup" > "$2" 2>/dev/null
    grep -qE '^(RssAnon|Pss_Anon):' "$2" 2>/dev/null
}
pss_point() { # pss_point <scenario>
    local kb sw over pid anon file
    read -r kb sw <<<"$(meminfo_total "$1")"
    meminfo_categories "$OUTDIR/meminfo-$1.txt" "$OUTDIR/meminfo-categories-$1.txt"
    anon=""; file=""
    pid=$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')
    if [ -z "$pid" ]; then
        result INFO pss-smaps "scenario=$1 no pid for $PKG; anon/file split skipped"
    elif smaps_rollup "$pid" "$OUTDIR/smaps-$1.txt"; then
        read -r anon file <<<"$(awk '/^(RssAnon|Pss_Anon):/ {a=$2} /^(RssFile|Pss_File):/ {f=$2} END {print a+0, f+0}' "$OUTDIR/smaps-$1.txt")"
    else
        result INFO pss-smaps "scenario=$1 smaps_rollup unreadable for pid $pid; anon/file split skipped"
    fi
    if [ -z "${kb:-}" ]; then
        result FAIL pss "scenario=$1 unreadable (process dead?)"
    else
        over=$( [ "$kb" -gt "$PSS_BUDGET_KB" ] && echo true || echo false )
        result INFO pss "scenario=$1 total_kb=$kb swap_kb=$sw anon_kb=${anon:-unknown} file_kb=${file:-unknown} budget_kb=$PSS_BUDGET_KB over_budget=$over build=$BUILD"
    fi
}

# framestats parser: window section by name, columns by header name.
# Modes: durations -> "n p50 p90 p95"; firstframe -> FrameCompleted of the first
# row; jank -> "total janky percent" from the section's "Janky frames" line.
framestats() { # framestats <file> <window-regex> durations|firstframe|jank
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
if mode == "jank":
    total = re.search(r"^Total frames rendered: (\d+)", section, re.M)
    janky = re.search(r"^Janky frames: (\d+) \(([\d.]+)%\)", section, re.M)
    if not total or not janky:
        sys.exit("no Janky frames line")
    print(total.group(1), janky.group(1), janky.group(2))
    sys.exit(0)
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

# atrace text parser for the framework input slices.
# Line shape: "<task>-<tid> (<tgid>) [cpu] flags <ts>: tracing_mark_write: B|<pid>|<name>"
# or "...: E|<pid>". Per thread, the last "dispatchInputEvent MotionEvent ACTION_x"
# names the action of the next "deliverInputEvent src=.. eventTimeNano=N" slice.
# Modes:
#   tapup <before_ns>: eventTimeNano of the last ACTION_UP delivered to any process
#     with eventTimeNano < before_ns (CLOCK_MONOTONIC).
#   handling <pid>: "n p50 p95 max lat_p50 lat_p95" of deliverInputEvent slices on the
#     main thread of <pid> (tid == pid); lat = slice end - eventTimeNano, with the trace
#     clock aligned by the trace_event_clock_sync parent_ts marker.
trace_input() { # trace_input <file> tapup|handling <arg>
    python3 - "$1" "$2" "$3" <<'PYEOF'
import re
import sys
path, mode, arg = sys.argv[1], sys.argv[2], int(sys.argv[3])
line_re = re.compile(r"^\s*.*?-(\d+)\s+\(\s*[\d-]+\)\s+\[\d+\]\s+\S+\s+([\d.]+): "
                     r"tracing_mark_write: (.*)$")
offset = None
stacks, last_action, slices, ups = {}, {}, [], []
for line in open(path, encoding="utf-8", errors="replace"):
    m = line_re.match(line)
    if not m:
        continue
    tid, ts, body = int(m.group(1)), float(m.group(2)), m.group(3).strip()
    if body.startswith("trace_event_clock_sync: parent_ts="):
        offset = ts - float(body.split("=", 1)[1])
        continue
    parts = body.split("|", 2)
    if parts[0] == "B" and len(parts) == 3:
        pid, name = int(parts[1]), parts[2]
        act = re.match(r"dispatchInputEvent MotionEvent (ACTION_\w+)", name)
        if act:
            last_action[tid] = act.group(1)
        stacks.setdefault(tid, []).append((name, ts, pid, last_action.get(tid)))
    elif parts[0] == "E":
        if not stacks.get(tid):
            continue
        name, start, pid, action = stacks[tid].pop()
        ev = re.match(r"deliverInputEvent src=\S+ eventTimeNano=(\d+)", name)
        if not ev:
            continue
        ev_ns = int(ev.group(1))
        if action == "ACTION_UP":
            ups.append(ev_ns)
        if pid == tid:
            slices.append((pid, (ts - start) * 1e3, ts, ev_ns))
if mode == "tapup":
    cands = [u for u in ups if u < arg]
    if not cands:
        sys.exit("no ACTION_UP before the frame")
    print(max(cands))
else:
    mine = [s for s in slices if s[0] == arg]
    if not mine:
        sys.exit("no deliverInputEvent slices on the main thread")
    def pct(v, p):
        v = sorted(v)
        return v[min(len(v) - 1, int(p * len(v) / 100))]
    durs = [s[1] for s in mine]
    lats = [((s[2] - offset) * 1e9 - s[3]) / 1e6 for s in mine] if offset is not None else [0.0]
    print(f"{len(durs)} {pct(durs, 50):.3f} {pct(durs, 95):.3f} {max(durs):.3f} "
          f"{pct(lats, 50):.2f} {pct(lats, 95):.2f}")
PYEOF
}

# Async-slice parser for app sections (atrace -a): pairs "S|<pid>|<name>|<cookie>"
# with the matching "F|<pid>|<name>|<cookie>" and prints "n p50 p95" of the
# durations in ms; one duration per line goes to <out-file>. Percentiles follow
# the stats helper. Unmatched begins (a slice still open at trace stop) are
# dropped. Exit 1 when no complete pair was found.
trace_async() { # trace_async <trace-file> <section-name> <out-file>
    python3 - "$1" "$2" "$3" <<'PYEOF'
import re
import sys
path, section, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
line_re = re.compile(r"^\s*\S+-(\d+)\s+(?:\(\s*[\d-]+\)\s+)?\[\d+\]\s+\S+\s+([\d.]+): "
                     r"tracing_mark_write: (.*)$")
open_slices = {}
durations = []
for line in open(path, encoding="utf-8", errors="replace"):
    m = line_re.match(line)
    if not m:
        continue
    ts, body = float(m.group(2)), m.group(3).strip()
    parts = body.split("|")
    if len(parts) != 4 or parts[2] != section:
        continue
    key = (parts[1], parts[3])
    if parts[0] == "S":
        open_slices[key] = ts
    elif parts[0] == "F" and key in open_slices:
        durations.append((ts - open_slices.pop(key)) * 1e3)
with open(out_path, "w", encoding="utf-8") as out:
    for d in durations:
        out.write(f"{d:.3f}\n")
if not durations:
    sys.exit(f"no async slices named {section}")
durations.sort()
n = len(durations)
p50 = durations[n // 2] if n % 2 else (durations[n // 2 - 1] + durations[n // 2]) / 2
p95 = durations[min(n - 1, int(0.95 * n))]
print(f"{n} {p50:.2f} {p95:.2f}")
PYEOF
}

# median / p95 / min / max of the numbers on stdin: "n med p95 min max"
stats() {
    python3 -c "
import sys
v = sorted(float(x) for x in sys.stdin.read().split())
n = len(v)
med = v[n // 2] if n % 2 else (v[n // 2 - 1] + v[n // 2]) / 2
p95 = v[min(n - 1, int(0.95 * n))]
print(f'{n} {med:.1f} {p95:.1f} {v[0]:.1f} {v[-1]:.1f}')"
}
# gt / ge <value> <budget> -> true|false (a "< budget" row is over at ge, a "<= budget" row at gt)
gt() { python3 -c "import sys; print('true' if float(sys.argv[1]) > float(sys.argv[2]) else 'false')" "$1" "$2"; }
ge() { python3 -c "import sys; print('true' if float(sys.argv[1]) >= float(sys.argv[2]) else 'false')" "$1" "$2"; }

# trace_start [extra atrace flags...]: extra flags go after the categories; the
# suggest leg passes `-a "$PKG"` to capture the app's async sections. The touch
# and warm legs keep the bare `input view` capture their parsers expect.
trace_start() {
    SHELL atrace --async_start -b 4096 input view "$@" >/dev/null 2>&1 && ATRACE_ON=1
}
trace_stop() { # trace_stop <file>
    SHELL atrace --async_stop > "$1" 2>/dev/null
    ATRACE_ON=0
}

# Field 22 of /proc/<pid>/stat counts CLOCK_BOOTTIME ticks (suspend included), while the
# framestats timestamps are CLOCK_MONOTONIC (suspend excluded). Their difference is the total
# suspend time since boot; `dumpsys alarm` prints both clocks, so it is read here in ms.
suspend_offset_ms() {
    SHELL dumpsys alarm 2>/dev/null | python3 -c '
import re, sys
units = {"d": 86400000, "h": 3600000, "m": 60000, "s": 1000, "ms": 1}
def ms(text):
    return sum(int(n) * units[u] for n, u in re.findall(r"(\d+)(ms|d|h|m|s)", text))
clocks = dict(re.findall(r"Runtime uptime \((elapsed|uptime)\): \+(\S+)", sys.stdin.read()))
if set(clocks) != {"elapsed", "uptime"}:
    sys.exit(1)
print(ms(clocks["elapsed"]) - ms(clocks["uptime"]))
'
}

# proc_cpu_ms <pid> -> utime+stime of the process in ms: fields 14+15 of
# /proc/<pid>/stat are clock ticks, converted with CLK_TCK. Format-stable across
# Android versions, so it is the independent counter of the battery leg.
proc_cpu_ms() {
    local ticks
    ticks=$(SHELL cat "/proc/$1/stat" 2>/dev/null | tr -d '\r' | awk '{print $14 + $15}')
    [ -n "$ticks" ] || return 1
    python3 -c "print(int('$ticks') * 1000 // $CLK_TCK)"
}

# battery_counts <batterystats-dump> -> "wakelocks sensors alarms recognized".
# The per-package batterystats layout varies by Android version, so the counts
# are collected tolerantly. The package filter argument is ignored by some
# builds (HyperOS), so the caller passes the package's uid (u0a<appId-10000>)
# and only the uid's own section is read: between the "  <uid>:" header and the
# next same-indent header. Every "Wake lock" line contributes its "(Nx)" count,
# "Sensor <id>" lines likewise, and the "Alarms: N" line its number; an uid
# section that exists but lists none is the zero evidence. "recognized" is no
# when the uid section was not found at all, in which case the zero counts
# prove nothing and the caller reports INFO instead of PASS.
battery_counts() {
    python3 - "$1" "$2" <<'PYEOF'
import re
import sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
uid = sys.argv[2]
header = re.compile(r"^  (\S+):\s*$")
section = None
for line in text.splitlines():
    m = header.match(line)
    if m:
        if section is not None:
            break
        section = [] if m.group(1) == uid else None
        continue
    if section is not None:
        section.append(line)
if section is None:
    print(0, 0, 0, "no")
    sys.exit(0)
wl = sensor = alarm = 0
for line in section:
    low = line.lower()
    if "wake lock" in low:
        m = re.search(r"\((\d+)x\)", line) or re.search(r"\b(\d+)x\b", line)
        wl += int(m.group(1)) if m else 1
    elif re.match(r"\s*Sensor\s+\d+", line):
        m = re.search(r"\((\d+)x\)", line) or re.search(r"\b(\d+)x\b", line)
        sensor += int(m.group(1)) if m else 0
    else:
        m = re.search(r"\bAlarms?:\s*(\d+)", line)
        if m:
            alarm += int(m.group(1))
print(wl, sensor, alarm, "yes")
PYEOF
}

# Chrome to the front with the omnibox ready; dismiss a possible first-run dialog (BACK).
chrome_front() {
    SHELL am start -n "$CHROME" >/dev/null 2>&1
    sleep 3
    dump_ui | grep -q "url_bar" || { SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 2; }
}
# Hide the keyboard (at most two BACKs, so Chrome itself is never left).
hide_keyboard() {
    local i
    for i in 1 2; do
        keyboard_shown || return 0
        SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1
        sleep 1
    done
    ! keyboard_shown
}

# ── suggestions through the settings UI ──────────────────────────────────────

if [ "$SUGGESTIONS_UI" = 1 ]; then
    before=$(set_suggestions_ui true)
    rc=$?
    if [ $rc = 0 ]; then
        [ "$before" = "false" ] && SUGG_UI_RESTORE=false
        result INFO suggestions-pref "suggestions switch on via the settings UI (was '${before:-unknown}')"
    else
        [ "$before" = "false" ] && SUGG_UI_RESTORE=false
        result FAIL suggestions-pref "settings UI: switch not on (found '${before:-no row}')"
    fi
fi

# ── leg: cold start ──────────────────────────────────────────────────────────

if leg_on cold; then
    if [ "$COLD_TRIGGER" = tap ] && [ "$RUN_AS_OK" != 1 ]; then
        result FAIL cold-start "trigger tap needs a debuggable package (run-as kill -9)"
    else
        select_ours
        chrome_front
        colds=()
        cold_skips=0
        total=$((COLD_WARMUP + COLD_RUNS))
        for i in $(seq 1 "$total"); do
            hide_keyboard
            SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1   # leave the omnibox editor
            sleep 1
            kill_app "$( [ "$COLD_TRIGGER" = switch ] && echo deselect )" || { cold_skips=$((cold_skips + 1)); log "cold $i: kill failed"; select_ours; continue; }
            chrome_front
            if [ "$COLD_TRIGGER" = tap ]; then
                tap_node url_bar || { cold_skips=$((cold_skips + 1)); log "cold $i: url_bar not found"; continue; }
                if ! wait_keyboard 10; then
                    # the field may have kept focus: defocus once and re-tap
                    SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1
                    sleep 1
                    tap_node url_bar
                    wait_keyboard 10 || { cold_skips=$((cold_skips + 1)); log "cold $i: keyboard never shown"; continue; }
                fi
            else
                # neutral keyboard up on the omnibox, then select ours: the bind starts the process
                tap_node url_bar
                wait_keyboard 10 || { cold_skips=$((cold_skips + 1)); log "cold $i: neutral keyboard never shown"; select_ours; continue; }
                sleep 1
                SHELL ime set "$IME_ID" >/dev/null 2>&1
                deadline=$((SECONDS + 10))
                while [ $SECONDS -lt $deadline ] && [ -z "$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')" ]; do
                    sleep 0.3
                done
            fi
            sleep 2
            pid=$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')
            start=$(SHELL cat "/proc/$pid/stat" 2>/dev/null | awk '{print $22}' | tr -d '\r')
            SHELL dumpsys gfxinfo "$PKG" framestats > "$OUTDIR/cold-framestats-$i.txt" 2>/dev/null
            frame=$(framestats "$OUTDIR/cold-framestats-$i.txt" "InputMethod" firstframe 2>/dev/null || true)
            offset=$(suspend_offset_ms || true)
            if [ -z "${start:-}" ] || [ -z "${frame:-}" ] || [ "$frame" = "0" ] || [ -z "${offset:-}" ]; then
                cold_skips=$((cold_skips + 1)); log "cold $i: no data (pid='$pid' start='${start:-}' frame='${frame:-}' offset='${offset:-}')"; continue
            fi
            ms=$(python3 -c "print(f'{int($frame)/1e6 - (int($start)*10.0 - int($offset)):.1f}')")
            if [ "$i" -le "$COLD_WARMUP" ]; then
                log "cold $i: ${ms} ms (warm-up, discarded; pid $pid)"
                continue
            fi
            colds+=("$ms")
            log "cold $i: ${ms} ms (pid $pid)"
        done
        select_ours
        if [ ${#colds[@]} -ge 3 ]; then
            read -r n med p95 mn mx <<<"$(printf '%s\n' "${colds[@]}" | stats)"
            result INFO cold-start "runs=$n skips=$cold_skips median_ms=$med p95_ms=$p95 min_ms=$mn max_ms=$mx values_ms=$(IFS=,; echo "${colds[*]}") budget_ms=$COLD_BUDGET_MS over_budget=$(ge "$med" "$COLD_BUDGET_MS") build=$BUILD trigger=$COLD_TRIGGER"
        else
            result FAIL cold-start "only ${#colds[@]} valid runs of $COLD_RUNS"
        fi
    fi
fi

# ── leg: PSS across four scenarios ───────────────────────────────────────────

if leg_on pss; then
    kill_app || true
    select_ours
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
fi

# ── leg: frame stats and janky frames (32-event tt script, <=120 frames xN) ─

FRAME_SCRIPT="сәләм дөнья мин сине яратам дус "   # 27 letters + 5 spaces = 32 events
n_events=$(python3 -c "print(len('$FRAME_SCRIPT'))")
if leg_on frames; then
    log "frame script: '$FRAME_SCRIPT' ($n_events events)"
    select_ours
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
                    result INFO frames "run=$run n=$n p50_ms=$p50 p90_ms=$p90 p95_ms=$p95 budget_ms=$FRAME_BUDGET_MS over_budget=$(gt "$p95" "$FRAME_BUDGET_MS") build=$BUILD protocol=O2-2 script=32-event-tt window=InputMethod"
                fi
            fi
            jank=$(framestats "$OUTDIR/frames-run$run.txt" "InputMethod" jank 2>/dev/null || true)
            if [ -z "$jank" ]; then
                result FAIL "jank-run-$run" "no Janky frames line in the InputMethod section"
            else
                read -r total janky pct <<<"$jank"
                result INFO jank "run=$run frames=$total janky=$janky janky_pct=$pct budget_pct=$JANK_BUDGET_PCT over_budget=$(gt "$pct" "$JANK_BUDGET_PCT") build=$BUILD script=32-event-tt window=InputMethod"
            fi
        done
        # functional proof that the frame leg typed the Tatar script on the tt
        # layout: hide the keyboard and read the try-it field back
        SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1
        sleep 1.5
        field=$(field_text || true)
        case "$field" in
            *"яратам"*) result INFO frames-proof "try-it field holds the tt script tail as typed" ;;
            *) result FAIL frames-proof "unexpected field content: '${field: -60}'" ;;
        esac
    fi
fi

# ── leg: warm show (process alive, keyboard hidden 2 s -> first IME frame) ───

if leg_on warm; then
    select_ours
    chrome_front
    tap_node url_bar && wait_keyboard 10 || log "warm: first show over Chrome failed"
    warms=()
    warm_skips=0
    for i in $(seq 1 "$WARM_RUNS"); do
        hide_keyboard || { warm_skips=$((warm_skips + 1)); log "warm $i: keyboard did not hide"; continue; }
        sleep 2
        if [ -z "$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')" ]; then
            warm_skips=$((warm_skips + 1)); log "warm $i: process not alive"; continue
        fi
        SHELL dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
        trace_start
        sleep 0.5
        tap_node url_bar
        wait_keyboard 5
        sleep 1.5
        trace_stop "$OUTDIR/warm-trace-$i.txt"
        SHELL dumpsys gfxinfo "$PKG" framestats > "$OUTDIR/warm-framestats-$i.txt" 2>/dev/null
        frame=$(framestats "$OUTDIR/warm-framestats-$i.txt" "InputMethod" firstframe 2>/dev/null || true)
        up=""
        [ -n "$frame" ] && up=$(trace_input "$OUTDIR/warm-trace-$i.txt" tapup "$frame" 2>/dev/null || true)
        if [ -z "$frame" ] || [ -z "$up" ]; then
            warm_skips=$((warm_skips + 1)); log "warm $i: no data (frame='$frame' tap_up='$up')"; continue
        fi
        ms=$(python3 -c "print(f'{($frame - $up) / 1e6:.1f}')")
        warms+=("$ms")
        log "warm $i: ${ms} ms"
    done
    hide_keyboard || true
    if [ ${#warms[@]} -ge 3 ]; then
        read -r n med p95 mn mx <<<"$(printf '%s\n' "${warms[@]}" | stats)"
        result INFO warm-show "runs=$n skips=$warm_skips median_ms=$med p95_ms=$p95 min_ms=$mn max_ms=$mx values_ms=$(IFS=,; echo "${warms[*]}") budget_ms=$WARM_BUDGET_MS over_budget=$(ge "$med" "$WARM_BUDGET_MS") build=$BUILD host=chrome-omnibox"
    else
        result FAIL warm-show "only ${#warms[@]} valid runs of $WARM_RUNS"
    fi
fi

# ── leg: touch handling in our code (deliverInputEvent on the IME main thread) ─

if leg_on touch; then
    select_ours
    if raise_keyboard_over_setup && globe_to tt; then
        sleep 9
        pid=$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')
        trace_start
        sleep 0.5
        type_text tt "$FRAME_SCRIPT" 0.35
        sleep 1
        trace_stop "$OUTDIR/touch-trace.txt"
        out=$(trace_input "$OUTDIR/touch-trace.txt" handling "$pid" 2>/dev/null || true)
        if [ -z "$out" ]; then
            result FAIL touch "no deliverInputEvent slices for pid $pid"
        else
            read -r n p50 p95 mx lat50 lat95 <<<"$out"
            [ "$n" -ge 64 ] || result FAIL touch "only n=$n input events for a 32-tap script (64 expected)"
            result INFO touch "events=$n p50_ms=$p50 p95_ms=$p95 max_ms=$mx event_to_handled_p50_ms=$lat50 event_to_handled_p95_ms=$lat95 budget_ms=$TOUCH_BUDGET_MS over_budget=$(ge "$p95" "$TOUCH_BUDGET_MS") build=$BUILD script=32-event-tt"
        fi
        hide_keyboard || true
    else
        result FAIL touch "keyboard or tt layout not ready over SetupActivity"
    fi
fi

# ── leg: suggestion round trip (TT#suggestLookup async slices) ───────────────

if leg_on suggest; then
    select_ours
    if raise_keyboard_over_setup && globe_to tt; then
        sleep 9        # let the suggestion engine publish, as in the touch leg
        trace_start -a "$PKG"
        sleep 0.5
        type_text tt "$FRAME_SCRIPT" 0.35
        sleep 1
        trace_stop "$OUTDIR/suggest-trace.txt"
        out=$(trace_async "$OUTDIR/suggest-trace.txt" "TT#suggestLookup" "$OUTDIR/suggest-lookup-ms.txt" 2>/dev/null || true)
        if [ -z "$out" ]; then
            result FAIL suggest "no TT#suggestLookup slices in trace (app sections not captured?)"
        else
            read -r n p50 p95 <<<"$out"
            result INFO suggest "n=$n p50_ms=$p50 p95_ms=$p95 budget_ms=$SUGGEST_BUDGET_MS over_budget=$(gt "$p95" "$SUGGEST_BUDGET_MS") build=$BUILD protocol=32-event-tt"
        fi
        hide_keyboard || true
    else
        result FAIL suggest "keyboard or tt layout not ready over SetupActivity"
    fi
fi

# ── leg: battery observability (opt-in; simulates the unplugged state) ───────

if leg_on battery; then
    BATTERY_ON=1
    select_ours
    # The uid scopes the batterystats reading: some builds ignore the package
    # filter and dump globally (HyperOS). u0a<N> holds appId = 10000 + N.
    APPID=$(SHELL dumpsys package "$PKG" 2>/dev/null | grep -m1 -oP 'appId=\K[0-9]+' | tr -d '\r')
    PKG_UID="u0a$(( ${APPID:-10000} - 10000 ))"
    if raise_keyboard_over_setup; then
        hide_keyboard || true
        SHELL dumpsys battery unplug >/dev/null 2>&1
        SHELL dumpsys batterystats reset >/dev/null 2>&1
        sleep 2        # let the reset settle out of the window
        pid=$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')
        cpu_before=$(proc_cpu_ms "$pid" 2>/dev/null || true)
        if [ -z "$pid" ] || [ -z "${cpu_before:-}" ]; then
            result FAIL battery.idle "process stat unreadable (pid='${pid:-}')"
        else
            log "battery idle window: ${BATTERY_SECONDS}s"
            sleep "$BATTERY_SECONDS"
            cpu_after=$(proc_cpu_ms "$pid" 2>/dev/null || true)
            SHELL dumpsys batterystats "$PKG" > "$OUTDIR/batterystats.txt" 2>/dev/null
            if [ -z "${cpu_after:-}" ]; then
                result FAIL battery.idle "process $pid gone after the idle window"
            else
                read -r wl sensor alarm recognized <<<"$(battery_counts "$OUTDIR/batterystats.txt" "$PKG_UID")"
                cpu_delta=$((cpu_after - cpu_before))
                if [ "$recognized" = no ]; then
                    result INFO battery.idle "batterystats has no $PKG_UID section; raw wakelocks=$wl sensors=$sensor alarms=$alarm cpu_ms_delta=$cpu_delta window_s=$BATTERY_SECONDS build=$BUILD"
                elif [ "$wl" = 0 ] && [ "$sensor" = 0 ] && [ "$alarm" = 0 ] && [ "$cpu_delta" -le "$BATTERY_CPU_BUDGET" ]; then
                    result PASS battery.idle "wakelocks=0 sensors=0 alarms=0 cpu_ms_delta=$cpu_delta budget_cpu_ms=$BATTERY_CPU_BUDGET window_s=$BATTERY_SECONDS build=$BUILD"
                else
                    result FAIL battery.idle "wakelocks=$wl sensors=$sensor alarms=$alarm cpu_ms_delta=$cpu_delta budget_cpu_ms=$BATTERY_CPU_BUDGET window_s=$BATTERY_SECONDS build=$BUILD"
                fi
            fi
        fi
        # The active protocol runs on real power with the screen back on: the idle
        # window's simulated unplug let the device sleep.
        SHELL dumpsys battery reset >/dev/null 2>&1; BATTERY_ON=0
        SHELL "input keyevent KEYCODE_WAKEUP; input keyevent 82" >/dev/null 2>&1
        sleep 1
        # active protocol: the 32-event tt script, CPU per event
        SHELL dumpsys batterystats reset >/dev/null 2>&1
        if raise_keyboard_over_setup && globe_to tt; then
            sleep 2
            pid=$(SHELL pidof "$PKG" 2>/dev/null | tr -d '\r')
            cpu_before=$(proc_cpu_ms "$pid" 2>/dev/null || true)
            type_text tt "$FRAME_SCRIPT" 0.35
            sleep 1
            cpu_after=$(proc_cpu_ms "$pid" 2>/dev/null || true)
            if [ -n "${cpu_before:-}" ] && [ -n "${cpu_after:-}" ]; then
                cpu_delta=$((cpu_after - cpu_before))
                n_events=$(python3 -c "print(len('$FRAME_SCRIPT'))")
                per=$(python3 -c "print(f'{$cpu_delta / $n_events:.1f}')")
                result INFO battery.active "cpu_ms=$cpu_delta events=$n_events cpu_ms_per_event=$per build=$BUILD"
            else
                result FAIL battery.active "process stat unreadable (pid='${pid:-}')"
            fi
        else
            result FAIL battery.active "keyboard or tt layout not ready over SetupActivity"
        fi
    else
        result FAIL battery.idle "keyboard did not raise over SetupActivity"
    fi
fi

# ── probe: night-mode palette flip (opt-in, evidence only) ───────────────────

if leg_on uimode; then
    select_ours
    SHELL "input keyevent KEYCODE_WAKEUP; input keyevent 82" >/dev/null 2>&1
    sleep 1        # an earlier leg's idle window may have slept the screen
    if raise_keyboard_over_setup; then
        UIMODE_ON=1
        SHOT uimode-light.png
        SHELL cmd uimode night yes >/dev/null 2>&1
        sleep 2
        keyboard_shown || { tap_node setup_test_field >/dev/null 2>&1 && wait_keyboard 5 || true; }
        SHOT uimode-night.png
        SHELL cmd uimode night no >/dev/null 2>&1
        sleep 2        # restore() sets night no again when this probe ran
        diff_bytes=$(cmp -l "$OUTDIR/uimode-light.png" "$OUTDIR/uimode-night.png" 2>/dev/null | wc -l)
        result INFO uimode "night_flip pixel_diff_bytes=$diff_bytes (0 means stale palette — investigate)"
    else
        result FAIL uimode "keyboard did not raise over SetupActivity"
    fi
fi

# ── probe: key labels must ignore the system font scale (opt-in) ─────────────

if leg_on fontscale; then
    select_ours
    SHELL "input keyevent KEYCODE_WAKEUP; input keyevent 82" >/dev/null 2>&1
    sleep 1        # an earlier leg's idle window may have slept the screen
    if raise_keyboard_over_setup; then
        FONTSCALE_ON=1
        SHOT fontscale-normal.png
        SHELL settings put system font_scale 1.3 >/dev/null 2>&1
        sleep 2
        keyboard_shown || { tap_node setup_test_field >/dev/null 2>&1 && wait_keyboard 5 || true; }
        SHOT fontscale-large.png
        if cmp -s "$OUTDIR/fontscale-normal.png" "$OUTDIR/fontscale-large.png"; then
            result PASS fontscale "labels pixel-identical under font_scale 1.3"
        else
            diff_bytes=$(cmp -l "$OUTDIR/fontscale-normal.png" "$OUTDIR/fontscale-large.png" 2>/dev/null | wc -l)
            result FAIL fontscale "screen changed under font_scale 1.3: pixel_diff_bytes=$diff_bytes"
        fi
    else
        result FAIL fontscale "keyboard did not raise over SetupActivity"
    fi
fi

log "done; failures: $FAILURES"
[ "$FAILURES" = 0 ]
