#!/bin/bash
# Emulator smoke test of the keyboard. Scenario: boot the AVD, install the APK,
# enable and select the IME, open SetupActivity, check the keyboard is up, type
# "мин" (tt) / "при" (ru) / "hi" (en) and check suggestions, cycle languages with
# the globe key tt -> ru -> en -> tt, open the emoji panel (long press on comma)
# and commit an emoji, and require an empty crash buffer. Additional probes on the
# tt layout (word-form suggestions, predictions after an accepted suggestion,
# learned emoji, emoji panel BACK) are described where they are defined. Four UX legs
# follow them — one-handed dock, number row, night mode and a landscape rotation; each
# restores the state it toggled, and they are described where they are defined.
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
# Typing, space and layout-probe actions are confirmed, not slept:
#   - every typed word's intended tail is polled for a bounded time (typed_checks),
#     and every space tap is retried only while the field is byte-identical to its
#     pre-tap text (confirm_space / space_and_confirm), so a late commit is waited
#     for and a wrong commit is never tapped over;
#   - the release layout probe's KEYCODE_DEL cleanup reports the layout only when
#     the field is byte-identical to the pre-probe text, and re-sends the DEL only
#     while the field still shows exactly the post-probe text: a re-sent DEL that
#     overtakes a first DEL still in the input queue deletes the committed tail
#     (the API 28 'минпри' artefacts). A cleanup that still fails after the
#     bounded retry restores the field to a known byte-empty state with
#     clear_tryit instead of leaving the residue as the next probe's baseline;
#   - clear_tryit deterministically empties the try-it field (refocus, caret to
#     the end, bounded KEYCODE_DELs until two byte-empty reads; timeout = hard
#     FAIL). It runs before the language-probe cycle and before every typed
#     phase whose tail assertion is anchored on a standalone word, so a dropped
#     probe DEL cannot resurface one leg later as 'мин әпри' or 'ййтатар';
#   - NEXT_WORD probes wait for the band CONTENTS before tapping: the product
#     paints a provisional global-fallback band before the pinned bigram row, and
#     with uiautomator blind to the IME window the content check is pixel identity
#     against scripts/emulator-smoke-refs/<sdk>/<probe>.png (see wait_band). A
#     band that never matches inside the bounded wait fails the probe without
#     tapping, instead of committing a fallback word;
#   - a typed phase that must start from an empty field calls clear_tryit after
#     the layout probes: it refocuses the field, moves the caret to the end and
#     sends bounded KEYCODE_DELs until two byte-empty reads; a 15-second timeout
#     is a FAIL, not a guessed prefix in the next typed check.
#
# Word suggestions are on by default. On a debuggable package the script still writes
# the pref via run-as before the app starts, because a reused AVD keeps the value of an
# earlier session; then the process is force-stopped, because a live process keeps the
# old prefs in memory and does not re-read the file. Force-stopping the selected IME
# resets default_input_method, so `ime set` must run after force-stop. On a
# non-debuggable package the suggestion probes rely on the default, so they fail if
# suggestions were turned off on that AVD; the learned-emoji probe needs run-as and is
# skipped.
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
SMOKE_ANIMATION_SAVED=""
SMOKE_SCREEN_OFF=""
cleanup() {
    if [ "$SMOKE_SCREEN_OFF" = 1 ]; then
        rotation_screen_on
    fi
    if [ "$SMOKE_ANIMATION_SAVED" = 1 ] && [ -n "$SERIAL" ]; then
        "$ADB" -s "$SERIAL" shell settings put global window_animation_scale "${SMOKE_SAVED_WINDOW_SCALE:-1}" >/dev/null 2>&1 || true
        "$ADB" -s "$SERIAL" shell settings put global transition_animation_scale "${SMOKE_SAVED_TRANSITION_SCALE:-1}" >/dev/null 2>&1 || true
        "$ADB" -s "$SERIAL" shell settings put global animator_duration_scale "${SMOKE_SAVED_ANIMATOR_SCALE:-1}" >/dev/null 2>&1 || true
    fi
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
# A prior interrupted rotation probe can leave a disposable AVD in landscape.
# wm size still reports the natural portrait dimensions, so explicitly enter
# portrait before using the calibrated coordinates; restore the found state at
# the end just like the landscape leg restores its own changes.
SMOKE_SAVED_ACC=$(SHELL settings get system accelerometer_rotation 2>/dev/null | tr -d '\r' || true)
SMOKE_SAVED_ROT=$(SHELL settings get system user_rotation 2>/dev/null | tr -d '\r' || true)
[ "$SMOKE_SAVED_ACC" = "null" ] && SMOKE_SAVED_ACC=1
[ "$SMOKE_SAVED_ROT" = "null" ] && SMOKE_SAVED_ROT=0
SMOKE_SDK=$(SHELL getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)
rotation_screen_off() {
    [ "$SMOKE_SDK" = 37 ] || return 0
    [ "$SMOKE_SCREEN_OFF" = 1 ] && return 0
    SHELL input keyevent KEYCODE_POWER >/dev/null 2>&1 || true
    SMOKE_SCREEN_OFF=1
    sleep 2
}
rotation_screen_on() {
    [ "$SMOKE_SCREEN_OFF" = 1 ] || return 0
    SHELL input keyevent KEYCODE_POWER >/dev/null 2>&1 || true
    SMOKE_SCREEN_OFF=""
    SHELL wm dismiss-keyguard >/dev/null 2>&1 || true
    sleep 1
}
if [ "$SMOKE_SDK" = 37 ]; then
    SMOKE_SAVED_WINDOW_SCALE=$(SHELL settings get global window_animation_scale 2>/dev/null | tr -d '\r' || true)
    SMOKE_SAVED_TRANSITION_SCALE=$(SHELL settings get global transition_animation_scale 2>/dev/null | tr -d '\r' || true)
    SMOKE_SAVED_ANIMATOR_SCALE=$(SHELL settings get global animator_duration_scale 2>/dev/null | tr -d '\r' || true)
    [ "$SMOKE_SAVED_WINDOW_SCALE" = "null" ] && SMOKE_SAVED_WINDOW_SCALE=1
    [ "$SMOKE_SAVED_TRANSITION_SCALE" = "null" ] && SMOKE_SAVED_TRANSITION_SCALE=1
    [ "$SMOKE_SAVED_ANIMATOR_SCALE" = "null" ] && SMOKE_SAVED_ANIMATOR_SCALE=1
    SMOKE_ANIMATION_SAVED=1
    SHELL settings put global window_animation_scale 0 >/dev/null 2>&1 || true
    SHELL settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
    SHELL settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
fi
# API 37's gfxstream readback aborts on foreground transition snapshots.
rotation_screen_off
SHELL settings put system accelerometer_rotation 0 || true
SHELL settings put system user_rotation 0 || true
sleep 2
rotation_screen_on
# API 37's gfxstream guest aborts inside screencap's color-buffer readback.
# Capture that image through the emulator console instead, which produces the
# same full-screen PNG without invoking the guest renderer. Other APIs retain
# the faster exec-out path. A failed host capture is deliberately left as an
# invalid/empty image so the existing pixel assertions still fail.
SHOT() {
    local capture_dir host_screenshot
    if [ "$SMOKE_SDK" != 37 ]; then
        A exec-out screencap -p > "$OUTDIR/$1" 2>/dev/null
        return
    fi
    capture_dir=$(mktemp -d "$OUTDIR/.screenshot.XXXXXX")
    # The emulator process does not share the driver's working directory.
    capture_dir=$(realpath "$capture_dir")
    host_screenshot=""
    if A emu screenrecord screenshot "$capture_dir" >/dev/null 2>&1; then
        # Current emulator builds choose Screenshot_<timestamp>.png rather than
        # the screenshot.png name used by older builds.
        host_screenshot=$(find "$capture_dir" -maxdepth 1 -type f -name '*.png' -print -quit)
    fi
    if [ -n "$host_screenshot" ] && [ -s "$host_screenshot" ]; then
        mv "$host_screenshot" "$OUTDIR/$1"
    else
        : > "$OUTDIR/$1"
        log "screenshot $1 failed; pixel checks will remain FAIL"
    fi
    rm -rf "$capture_dir"
}
DUMP_UI() {                                  # uiautomator dump → stdout + last evidence
    local dump
    SHELL uiautomator dump /data/local/tmp/smoke-ui.xml >"$OUTDIR/uiautomator-dump.log" 2>&1
    dump=$(A exec-out cat /data/local/tmp/smoke-ui.xml 2>/dev/null | tr -d '\r')
    printf '%s\n' "$dump" >"$OUTDIR/last-ui.xml"
    printf '%s\n' "$dump"
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
    local dump root x1 y1 x2 y2 attempt ime_top action_bottom start_y end_y
    dump=$(DUMP_UI)
    for attempt in $(seq 1 12); do
        grep -q 'setup_test_field' <<<"$dump" && break
        # On edge-to-edge releases the root extends behind the IME. Start the
        # swipe above the visible IME frame, not at a fraction that lands on
        # the keyboard, and keep the end below the overlapping action bar.
        root=$(grep -oP '<node[^>]*setup_root[^>]*bounds="\[\K[0-9,\]\[]+' <<<"$dump" | head -1 || true)
        [ -n "$root" ] || break
        read -r x1 y1 x2 y2 <<<"$(tr '[],' '    ' <<<"$root")"
        start_y=$(( (2 * y1 + y2) / 3 ))
        ime_top=$(SHELL dumpsys window 2>/dev/null | tr -d '\r' \
            | grep 'type=ime frame=.*visible=true' \
            | grep -oP 'type=ime frame=\[[0-9]+,\K[0-9]+' | head -1 || true)
        if [[ "$ime_top" =~ ^[0-9]+$ ]] && [ "$start_y" -ge $((ime_top - 16)) ]; then
            start_y=$((ime_top - 16))
        fi
        end_y=$(( y1 + (y2 - y1) / 10 ))
        action_bottom=$(grep -oP '<node[^>]*android:id/action_bar"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,\K[0-9]+' <<<"$dump" | head -1 || true)
        if [[ "$action_bottom" =~ ^[0-9]+$ ]] && [ "$end_y" -lt $((action_bottom + 8)) ]; then
            end_y=$((action_bottom + 8))
        fi
        [ "$start_y" -gt "$y1" ] && [ "$end_y" -lt "$start_y" ] || break
        SHELL input swipe $(( (x1 + x2) / 2 )) "$start_y" \
            $(( (x1 + x2) / 2 )) "$end_y" 400
        sleep 1
        dump=$(DUMP_UI)
    done
    grep -q 'setup_test_field' <<<"$dump" || { echo "__NOFIELD__"; return; }
    grep -oP '<node[^>]*setup_test_field[^>]*' <<<"$dump" \
        | grep -oP 'text="\K[^"]*' | head -1 || true
}
type_word() {                                # type_word "0.42,0.85 0.51,0.85 ..."
    local xy
    for xy in $1; do
        TAPF "${xy%,*}" "${xy#*,}"
        sleep 0.4
    done
}

# Every typed word and every space tap is confirmed against the try-it field with
# bounded polls: on API 28 the commit can lag the tap by seconds, and a check that
# reads once after a fixed sleep races it. LAST_FIELD_TEXT carries the last read so
# callers report the actual text. field_tail_later returns 0 only on a real match;
# a wrong text is never retried away, it is returned to the caller to fail on.
LAST_FIELD_TEXT=""
field_tail_later() {
    # args: <tail-regex> <attempts> <interval-s>
    local expect="$1" attempts="$2" interval="$3" attempt
    for attempt in $(seq 1 "$attempts"); do
        LAST_FIELD_TEXT=$(field_text)
        echo "$LAST_FIELD_TEXT" | grep -qE "$expect" && return 0
        [ "$attempt" -lt "$attempts" ] && sleep "$interval"
    done
    return 1
}

# Tap SPACE and confirm the expected committed tail (a word + trailing space). A
# dropped tap is retried only while the field is byte-identical to the pre-tap
# text: once anything else was committed the retry stops, so a lost tap is
# tolerated and a wrong commit is never tapped over. SPACE is defined below; this
# function is called only after it is set.
confirm_space() {
    # args: <committed-tail-regex>; returns 0 once the tail is confirmed
    local expect="$1" pre attempt poll
    pre=$(field_text)
    LAST_FIELD_TEXT="$pre"
    for attempt in 1 2 3; do
        TAPF ${SPACE%,*} ${SPACE#*,}
        for poll in 1 2 3 4 5; do
            sleep 1
            LAST_FIELD_TEXT=$(field_text)
            echo "$LAST_FIELD_TEXT" | grep -qE "$expect" && return 0
            [ "$LAST_FIELD_TEXT" = "$pre" ] || return 1
        done
    done
    return 1
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

# NEXT_WORD band content is verified against reference crops: the product paints a
# provisional global-fallback band (һәм белән дә…) first and replaces it with the
# pinned bigram successors (теле телен дәүләт) when the asynchronous answer
# arrives, so a tap timed on "the band stopped changing" can commit the fallback
# word (the API 28 nextword-tt-татар failures). uiautomator does not see the IME
# window, so the content check is pixel identity: the strip of one image+APK
# renders byte-identically across runs (measured AE=0 over 8 API 28 runs), and
# the two bands differ by thousands of pixels, far above BAND_DIFF_MAX.
# References live per API level under scripts/emulator-smoke-refs/<sdk>/<name>.png
# and are the BAND_CROP crop of a known-good smoke screenshot; regenerate them
# when the APK's strip rendering changes. An image without a reference falls back
# to the bounded tap retry alone.
BAND_CROP="1080x121+0+1297"
BAND_DIFF_MAX=200
BAND_REFS="$ROOT/scripts/emulator-smoke-refs"
band_ref() {
    # args: <name>; stdout: the reference path, or empty when absent
    local api path
    api=$(SHELL getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)
    path="$BAND_REFS/${api:-unknown}/$1.png"
    [ -f "$path" ] && echo "$path" || true
}
band_diff() {
    # args: <live.png> <ref.png>; stdout: the AE pixel delta
    local out
    out=$(compare -metric AE \
        <(convert "$1" -crop "$BAND_CROP" +repage png:-) \
        "$2" null: 2>&1 || true)
    echo "$out" | grep -oP '\d+' | head -1 || echo 999999
}
# wait_band <ref-name> <shot-prefix>: poll the strip until it matches the
# reference on two consecutive shots (a band repainting into another layout must
# not be tapped mid-frame). Returns 0 on the match, 1 on the bounded timeout and
# 2 when there is no reference for this image (the caller keeps the tap retry).
wait_band() {
    local ref="$1" prefix="$2" poll live matches=0
    ref=$(band_ref "$ref")
    [ -n "$ref" ] || return 2
    [ "$HAVE_MAGICK" = 1 ] || return 2
    for poll in $(seq 1 12); do
        SHOT "$prefix-$poll.png"
        live=$(band_diff "$OUTDIR/$prefix-$poll.png" "$ref")
        if [ "$live" -le "$BAND_DIFF_MAX" ]; then
            matches=$((matches + 1))
            [ "$matches" -ge 2 ] && return 0
        else
            matches=0
        fi
        [ "$poll" -lt 12 ] && sleep 1
    done
    return 1
}
HAVE_MAGICK=0
if command -v compare >/dev/null 2>&1 && command -v convert >/dev/null 2>&1; then
    HAVE_MAGICK=1
fi

# ── install and select the IME ──────────────────────────────────────────────────

# Wait for a real package-manager query before every attempt. API 37 uses a
# bounded push + local pm install retry from the outset: its streamed session
# crashes StorageManagerService.allocateBytes. Keep non-incremental adb install
# on every other API, including its existing reconnect retry.
wait_package_manager() {
    local _
    for _ in $(seq 1 60); do
        if SHELL service check package 2>/dev/null | grep -q found \
                && SHELL pm path android 2>/dev/null | grep -q '^package:'; then
            return 0
        fi
        sleep 2
    done
    return 1
}
install_ok=0
remote_apk="/data/local/tmp/$(basename "$APK")"
for install_attempt in 1 2 3; do
    if ! wait_package_manager; then
        [ "$install_attempt" = 1 ] && result FAIL install "package manager not ready after 120 seconds"
        break
    fi
    if [ "$SMOKE_SDK" = 37 ]; then
        if A push "$APK" "$remote_apk" >"$OUTDIR/install.log" 2>&1 \
                && A shell pm install -r "$remote_apk" >>"$OUTDIR/install.log" 2>&1; then
            install_ok=1
            A shell rm -f "$remote_apk" >/dev/null 2>&1 || true
            break
        fi
    elif A install --no-incremental -r "$APK" >"$OUTDIR/install.log" 2>&1; then
        install_ok=1
        break
    fi
    [ "$install_attempt" = 3 ] && break
    A reconnect >/dev/null 2>&1 || true
    sleep 3
done
if [ "$install_ok" = 1 ]; then
    # A system_server restart mid-install can leave the package half-registered
    # (install reports Success but "Activity class ... does not exist" on am
    # start). Re-check the resolved activity and re-install once if it is gone.
    resolve_attempts=0
    while [ "$resolve_attempts" -lt 4 ]; do
        resolved=$(SHELL cmd package resolve-activity --brief "$PKG/$SETUP_ACTIVITY" 2>/dev/null | tr -d '\r')
        [[ "$resolved" == *"$SETUP_ACTIVITY"* ]] && break
        resolve_attempts=$((resolve_attempts + 1))
        [ "$resolve_attempts" = 4 ] && A install --no-incremental -r "$APK" >>"$OUTDIR/install.log" 2>&1 || true
        sleep 3
    done
    if [[ "$resolved" == *"$SETUP_ACTIVITY"* ]]; then
        result PASS install "$(basename "$APK") pkg=$PKG"
    else
        result FAIL install "package half-registered after PM restart: $resolved"
        exit 1
    fi
else
    result FAIL install "$(tail -1 "$OUTDIR/install.log")"
    exit 1
fi

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
# if there is no file yet (clean AVD), write a minimal stub. PREFS_ACCESS=1 means the
# prefs file was written via run-as, so later steps can read and seed it too.
RUN_AS=0
PREFS_ACCESS=0
PREFS_PATH="/data/user_de/0/$PKG/shared_prefs/${PKG}_preferences.xml"
if A shell "run-as $PKG true" >/dev/null 2>&1; then
    RUN_AS=1
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
        PREFS_ACCESS=1
    fi
fi
if [ "$PREFS_ACCESS" = 1 ]; then
    result PASS suggestions-enabled "pref_tatar_suggestions=true через run-as"
elif [ "$RUN_AS" = 1 ]; then
    result FAIL suggestions-enabled "run-as works, but writing $PREFS_PATH failed"
else
    result SKIP suggestions-enabled "package not debuggable: the pref is not written, the probes rely on the default (on)"
fi

# First grid cell of the emoji panel (x=0.059 = first column center, 8 columns across the
# full width). The panel top moves with the suggestion strip, which is visible because
# suggestions are on: panel top 1297, first section header 1418-1500, row 0 center
# 1568 px ~ 0.6877 (with suggestions off the strip is hidden and row 0 moves to ~ 0.7408).
# Row 0 is always an emoji cell whatever the recents state: with recents it is the recents
# row, without them the first Smileys row.
GRID_CELL0_Y=0.6877

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
    [ "$PREFS_ACCESS" = 1 ] || { echo ""; return; }
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
# app's field. API 35 edge-to-edge insets can leave the try-it field below the
# fold, so scroll the setup root when the first dump does not contain it.
# The API 37 image restarts system_server under the first minutes of load and
# `am start` then dies with "Failure calling service activity: Broken pipe":
# wait for the service and never let one failed start abort the run (set -e) —
# the focus check below is the verdict.
for _ in $(seq 1 30); do
    if SHELL service check activity 2>/dev/null | grep -q found; then
        break
    fi
    sleep 2
done
focus=""
for _ in 1 2 3; do
    SHELL am start --activity-clear-task -n "$PKG/$SETUP_ACTIVITY" \
        >"$OUTDIR/am-start.log" 2>&1 || true
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
# states (adjustResize), so fresh bounds and the scroll above are enough.
bounds=$(DUMP_UI | grep -oP '<node[^>]*setup_test_field[^>]*bounds="\[\K[0-9,\]\[]+' | head -1 || true)
if [ -z "$bounds" ]; then
    local_root=""
    root_x1=0; root_y1=0; root_x2=0; root_y2=0
    for scroll_attempt in 1 2 3 4; do
        [ -n "$bounds" ] && break
        local_root=$(DUMP_UI | grep -oP '<node[^>]*setup_root[^>]*bounds="\[\K[0-9,\]\[]+' | head -1 || true)
        [ -n "$local_root" ] || break
        read -r root_x1 root_y1 root_x2 root_y2 <<<"$(tr '[],' '    ' <<<"$local_root")"
        SHELL input swipe $(( (root_x1 + root_x2) / 2 )) $(( (2 * root_y1 + root_y2) / 3 )) \
            $(( (root_x1 + root_x2) / 2 )) $(( root_y1 + (root_y2 - root_y1) / 10 )) 400
        sleep 1
        bounds=$(DUMP_UI | grep -oP '<node[^>]*setup_test_field[^>]*bounds="\[\K[0-9,\]\[]+' | head -1 || true)
    done
fi
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
    local text
    SHOT "smoke-${tag}-before.png"
    type_word "$coords"
    # Wait for the intended tail instead of reading once after a fixed sleep:
    # on API 28 the commit lags the key taps by seconds. The verdict below is
    # on the last read, so a wrong or missing tail is still a hard FAIL.
    if field_tail_later "$expect" 6 1; then :; fi
    text="$LAST_FIELD_TEXT"
    SHOT "smoke-${tag}-after.png"
    if [ "$text" = "__NOFIELD__" ]; then
        result FAIL "type-${tag}-${word}" "try-it поле не найдено в дампе"
    elif echo "$text" | grep -qE "$expect"; then
        result PASS "type-${tag}-${word}" "в поле: '$text'"
    else
        result FAIL "type-${tag}-${word}" "в поле: '$text' (ждали хвост /$expect/)"
    fi
    if [ -n "$nosuggest" ]; then
        result SKIP "suggest-${tag}-${word}" "у en нет словаря в ассетах — подсказок не бывает by design"
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

# Confirm a committed space and report it as its own check. A FAIL here is the
# precise diagnosis of the injected-space timing race on API 28: without the
# confirmation the following layout types onto the word still being composed and the
# failure only surfaces one leg later as a wrong typed_checks tail.
space_and_confirm() {
    # args: <tag> <committed-tail-regex>
    if confirm_space "$2"; then
        result PASS "space-$1" "в поле: '$LAST_FIELD_TEXT'"
    else
        result FAIL "space-$1" "пробел не подтверждён: '$LAST_FIELD_TEXT' (ждали хвост /$2/)"
    fi
}

# Key coordinates (screen fractions, tt_suggest_a14 1080x2280).
# tt, 5 rows: row 1 ("йцукен") y~0.7206, row 2 ("фыва") y~0.7851, row 3 ("ячсм") y~0.8474.
TT_MIN="0.4231,0.8474 0.5138,0.8474 0.5000,0.7206"          # м и н
# ru/en, 4 rows: row 1 y~0.6829, row 2 y~0.7575, row 3 y~0.8329.
RU_PRI="0.4091,0.7575 0.5000,0.7575 0.5000,0.8329"          # п р и
EN_HI="0.5500,0.7575 0.7500,0.6829"                          # h i
GLOBE="0.30,0.9075"
COMMA="0.2009,0.9075"

# Full letter map of the tt layout (screen fractions, calibrated for 1080×2280),
# derived from rows_tatar.xml: extra row 8×12.5% at y≈0.665 (matches PROBE_KEY),
# rows 1–2 11×9.091% at y≈0.7206/0.7851, row 3 = shift 10.8% + 9×8.711% + delete
# at y≈0.8474 (м/и/н land on the same keys as TT_MIN above).
tt_word_coords() {                       # tt_word_coords "татар" → "x,y x,y ..."
    python3 - "$1" <<'PYEOF'
import sys

ROWS = ("әөүҗңһёъ", 0.6650), ("йцукенгшщзх", 0.7206), ("фывапролджэ", 0.7851)
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

# Suggestion strip cells: three equal thirds by default (SuggestionStripState's visible cell
# count; the fourth-cell setting stays off here), centers x = (i + 0.5) / 3.
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

# Reopen SetupActivity, scroll the try-it field into view, tap it, wait for the keyboard,
# settle 2 s. Shared by clear_tryit, panel-back-reopen, the learned-emoji-tt rounds, the
# UX legs and the landscape leg. A round restarts the activity because BACK hides the
# WHOLE IME window by platform design, and a further BACK lands on SetupActivity and
# closes it.
refocus_tryit() {
    # See the setup-activity leg: one failed start is not a round verdict, it
    # is the API 37 service restarting; the keyboard/focus checks decide.
    SHELL am start --activity-clear-task -n "$PKG/$SETUP_ACTIVITY" >/dev/null 2>&1 || true
    sleep 3
    local b x1 y1 x2 y2 sb sx1 sy1 sx2 sy2 attempt
    # The field can be below the fold, and on API 35 the root extends behind
    # the IME. Swipe only in the upper third of the visible app area and retry
    # until the field enters the accessibility hierarchy.
    for attempt in 1 2 3 4; do
        b=$(DUMP_UI | grep -oP '<node[^>]*setup_test_field[^>]*bounds="\[\K[0-9,\]\[]+' | head -1 || true)
        [ -n "$b" ] && break
        sb=$(DUMP_UI | grep -oP '<node[^>]*setup_root[^>]*bounds="\[\K[0-9,\]\[]+' | head -1 || true)
        [ -n "$sb" ] || break
        read -r sx1 sy1 sx2 sy2 <<<"$(echo "$sb" | tr '[],' '    ')"
        SHELL input swipe $(( (sx1 + sx2) / 2 )) $(( (2 * sy1 + sy2) / 3 )) \
            $(( (sx1 + sx2) / 2 )) $(( sy1 + (sy2 - sy1) / 10 )) 400
        sleep 1
    done
    if [ -n "$b" ]; then
        read -r x1 y1 x2 y2 <<<"$(echo "$b" | tr '[],' '    ')"
        SHELL input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
    else
        TAPF 0.5 0.81
    fi
    local i focus_attempt
    for focus_attempt in 1 2 3; do
        for i in $(seq 1 5); do
            keyboard_shown && break 2
            sleep 1
        done
        # A tap that raced the IME window's show animation is dropped; re-read
        # the bounds and tap again instead of failing the round (the API 28
        # panel-back-reopen leg lost exactly one such tap).
        b=$(DUMP_UI | grep -oP '<node[^>]*setup_test_field[^>]*bounds="\[\K[0-9,\]\[]+' | head -1 || true)
        [ -n "$b" ] || break
        read -r x1 y1 x2 y2 <<<"$(echo "$b" | tr '[],' '    ')"
        SHELL input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
    done
    keyboard_shown || return 1
    # The keyboard reports shown before its first frame accepts touches; without this
    # settle the first tap of a round is lost.
    sleep 2
    return 0
}

# clear_tryit <phase>: make the try-it field byte-empty before a phase whose typed
# tail or NEXT_WORD band assumes an empty prefix — the language-probe cycle (so the
# probes restore the field to a KNOWN clean text, not to the damage an earlier probe
# left) and every standalone typed word. The release layout probe erases its probe
# character with DEL, but on API 28 a dropped DEL can leave ә/й in the field past the
# probe's own bounded retry; probe_layout then reports no layout, the next probe's
# raw_before silently includes the residue, and the damage surfaces one leg later as a
# wrong typed tail ('мин әпри', 'ййтатар'). uiautomator reports an empty EditText's
# hint as its text, so the three setup hints count as empty. A clear is confirmed by
# two consecutive empty reads; a field that never empties inside the bounded drain is
# a hard FAIL of the whole run, never a guessed prefix for the next check.
clear_tryit() {
    local phase="$1" text empty_reads=0 attempt
    if ! refocus_tryit; then
        result FAIL clear-tryit "$phase: keyboard did not come up for the field refocus"
        exit 1
    fi
    SHELL input keyevent KEYCODE_MOVE_END
    for attempt in $(seq 1 30); do
        text=$(field_text)
        if [ "$text" = "__NOFIELD__" ]; then
            result FAIL clear-tryit "$phase: try-it field not in the dump"
            exit 1
        elif [ -z "$text" ] || [[ "$text" == "Try it:"* || "$text" == "Сынап карагыз:"* || "$text" == "Попробуйте:"* ]]; then
            empty_reads=$((empty_reads + 1))
            if [ "$empty_reads" -ge 2 ]; then
                result PASS clear-tryit "$phase: try-it field byte-empty"
                return 0
            fi
        else
            empty_reads=0
            SHELL input keyevent KEYCODE_DEL
        fi
        sleep 0.5
    done
    text=$(field_text)
    result FAIL clear-tryit "$phase: try-it field did not empty within 15 seconds; field: '$text'"
    exit 1
}

# type_tt_and_tap_cell2 <word> <tag> [cell] [band-ref]: type <word> + space on the
# tt layout, wait for the expected NEXT_WORD band (see wait_band), tap the given
# suggestion cell (default the last cell), read the field again.
# Stdout: field-after-space <TAB> field-after-tap <TAB> band state, where the band
# state is "matched" (the reference band was shown before the tap), "unverified"
# (no reference for this image: the bounded tap retry below is the only safety
# net) or "timeout" (the reference band never appeared and nothing was tapped).
type_tt_and_tap_cell2() {
    local word="$1" tag="$2" cell="${3:-$STRIP_CELL2}"
    local band_ref_name="${4:-nextword-$tag}"
    local coords mid after poll tap_attempt band_state
    coords=$(tt_word_coords "$word")
    type_word "$coords"
    # The commit of the word + space is asynchronous; confirm it before asking
    # for NEXT_WORD evidence. A space that never commits is the caller's FAIL.
    if ! confirm_space "$word "'$'; then
        printf '%s\t%s\t%s\n' "$LAST_FIELD_TEXT" "$LAST_FIELD_TEXT" "band=space"
        return
    fi
    mid="$LAST_FIELD_TEXT"
    # Wait for the expected band contents before tapping, not for a stable-looking
    # band: the provisional global fallback (һәм белән дә…) is also stable while
    # the pinned bigram successors are still being computed, and a tap on it
    # commits the wrong word. With no reference for this image the old bounded
    # tap retry is the only net (band=unverified).
    if wait_band "$band_ref_name" "smoke-band-${tag}"; then
        band_state=matched
    elif [ $? -eq 1 ]; then
        band_state=timeout
        printf '%s\t%s\t%s\n' "$mid" "$mid" "band=$band_state"
        return
    else
        band_state=unverified
    fi
    after="$mid"
    # Retry only a tap that committed nothing, and poll the field after every
    # tap so a late commit is never mistaken for a dead band and tapped again.
    # Any committed word goes back to the exact caller assertion immediately; a
    # wrong word is never retried away.
    for tap_attempt in 1 2 3; do
        [ "$tap_attempt" -gt 1 ] && sleep 2
        SHOT "smoke-wordform-${tag}.png"
        TAPF ${cell%,*} ${cell#*,}
        for poll in $(seq 1 4); do
            sleep 1
            after=$(field_text)
            [ "$after" != "$mid" ] && break
        done
        [ "$after" != "$mid" ] && break
    done
    printf '%s\t%s\t%s\n' "$mid" "$after" "band=$band_state"
}

# ── tt: «мин» ──
typed_checks tt "мин" "$TT_MIN" '^мин$'
space_and_confirm tt-мин '^мин $'

# ── languages via the globe key: tt -> ru -> en -> tt ──
# The globe key cycles the language list, and the list order is rebuilt
# (resetSubtypeCycleOrder, MRU rotation of the pref), so a blind series of taps
# lands unpredictably. On a debuggable package the feedback is the
# pref_current_subtype pref (run-as); on a release APK the pref is unreadable, so a
# functional probe detects the layout and the script taps until it matches.

# Functional probe of the current layout (for a release APK, where run-as is not
# available). The point (0.045, 0.665) is inside "ә" in the top Tatar row of the tt
# layout (8 keys of 12.5%: "ә" covers x 0-0.125); on the 4-row ru/en layouts the same
# pixel is the first key of the first row: "й" (ru, 11 keys of 9.091%) and "q" (en,
# 10 keys of 10%). The probed character is erased with KEYCODE_DEL.
PROBE_KEY="0.045,0.665"
# probe_layout -> tatar|russian|qwerty|""
probe_layout() {
    local before raw_before after cleaned layout cleanup_attempt
    before=$(field_text)
    raw_before="$before"
    [ "$before" = "__NOFIELD__" ] && { echo ""; return; }
    [[ "$before" == "Try it:"* ]] && before=""   # an empty field reports the hint as text
    TAPF ${PROBE_KEY%,*} ${PROBE_KEY#*,}
    sleep 0.6
    after=$(field_text)
    if [ ${#after} -le ${#before} ]; then
        echo ""                            # the tap missed the key
        return
    fi
    if   [[ "$after" == *"ә" || "$after" == *"Ә" ]]; then layout=tatar
    elif [[ "$after" == *"й" || "$after" == *"Й" ]]; then layout=russian
    elif [[ "$after" == *"q" || "$after" == *"Q" ]]; then layout=qwerty
    else echo ""; return
    fi
    # KEYCODE_DEL is asynchronous on the legacy images. Confirm the field is
    # byte-identical to the pre-probe text before reporting the layout; otherwise
    # a burst of layout polls leaves q/й/ә in the try-it field and changes the
    # very suggestion context the following legs are meant to verify. The DEL is
    # re-sent ONLY while the field still shows exactly the post-probe text: a
    # re-sent DEL that overtakes a first DEL still travelling through the input
    # queue deletes the committed tail as well (the API 28 'минпри' artefacts
    # were the probe's second DEL eating the space after мин). Any other field
    # state — shorter (over-deleted) or different — makes the probe unusable:
    # report no layout. Either way the damage is not left as the next probe's
    # raw_before (which would silently bake the residue into every later probe
    # and surface one leg later as a wrong typed tail, e.g. 'мин әпри'): restore
    # the field to a known byte-empty state with clear_tryit before returning.
    local pre_delete="$after"
    SHELL input keyevent KEYCODE_DEL
    for cleanup_attempt in $(seq 1 12); do
        sleep 0.5
        cleaned=$(field_text)
        [ "$cleaned" = "$raw_before" ] && { echo "$layout"; return; }
        [ "$cleaned" = "$pre_delete" ] || break
        [ $((cleanup_attempt % 4)) -eq 0 ] && SHELL input keyevent KEYCODE_DEL
    done
    clear_tryit "probe-$layout-restore"
    echo ""
}

# On API 28 the subtype can change before the replacement keyboard surface is
# ready for input. A single functional probe can therefore still report the
# outgoing layout, and issuing another globe pair on that evidence switches away
# from the subtype that is just arriving. Poll until the wanted layout answers
# twice in a row, or return the last different observation after a bounded wait.
# wait_for_layout tatar|russian|qwerty
wait_for_layout() {
    local want="$1" cur="" matches=0 attempt
    for attempt in $(seq 1 6); do
        cur=$(probe_layout)
        if [ "$cur" = "$want" ]; then
            matches=$((matches + 1))
            [ "$matches" -eq 2 ] && break
        else
            matches=0
        fi
        [ "$attempt" -lt 6 ] && sleep 0.8
    done
    # One wanted answer on the last poll is not confirmation; do not turn that
    # unstable sample into a PASS merely because the retry budget ran out.
    if [ "$matches" -lt 2 ] && [ "$cur" = "$want" ]; then
        cur=""
    fi
    echo "$cur"
}

switch_and_check() {                         # $1 tag, $2 expected layout
    local tag="$1" want="$2" pref="" tap cur=""
    if [ "$PREFS_ACCESS" = 1 ]; then
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
    if [ "$cur" = "$want" ]; then
        # An already-active target still gets the same two-sample confirmation.
        cur=$(wait_for_layout "$want")
    else
        for tap in 1 2 3; do
            TAPF ${GLOBE%,*} ${GLOBE#*,}
            sleep 0.8
            TAPF ${GLOBE%,*} ${GLOBE#*,}
            sleep 1.2
            cur=$(wait_for_layout "$want")
            [ "$cur" = "$want" ] && break
        done
    fi
    if [ "$cur" = "$want" ]; then
        result PASS "subtype-$tag" "функциональный зонд: раскладка $want"
    else
        result FAIL "subtype-$tag" "зонд показывает '$cur' (ждали $want)"
    fi
}

# The probe cycle starts from a byte-empty field: the probes then restore that
# known text, and a probe whose DEL was dropped cannot bake residue into the
# next probe's baseline (see probe_layout). Each following typed phase clears
# again, because its tail assertion is anchored on a standalone word.
clear_tryit "language-cycle"

switch_and_check ru russian
clear_tryit "ru-typed"
SHOT smoke-ru-layout.png
typed_checks ru "при" "$RU_PRI" '^при$'
space_and_confirm ru-при '^при $'

switch_and_check en qwerty
clear_tryit "en-typed"
SHOT smoke-en-layout.png
typed_checks en "hi" "$EN_HI" 'hi$' nosuggest
space_and_confirm en-hi 'hi $'

switch_and_check tt tatar
clear_tryit "nextword-татар"
SHOT smoke-tt-back.png

# ── next-word continuations after a committed tt word + space ──
# Tap-and-read, not pixel diff: uiautomator does not see the IME window, so the
# strip content is proven by tapping a cell and reading the try-it field.
# Two probes against the bundled assets (the bigram table stores up to four
# successors per head; the strip shows three by default):
#  1. татар is a bigram head whose first three successors (теле, телен, дәүләт)
#     fill the whole strip, so cell 2 (index 1) must commit телен: bigram
#     successors take priority and the fallback does not run (CompositePrefixComputer).
#     Any other word fails.
#  2. сәләм is not a head, so the global top words fill the strip: cell 1
#     (index 0) must commit һәм.
wf=$(type_tt_and_tap_cell2 "татар" tatar "$STRIP_CELL1" nextword-tatar)
wf_mid="${wf%%$'\t'*}"
wf_rest="${wf#*$'\t'}"
wf_after="${wf_rest%%$'\t'*}"
wf_band="${wf_rest#*$'\t'}"
w2=$(second_word_after "$wf_after" "татар")
if [ "$wf_mid" = "__NOFIELD__" ] || [ "$wf_after" = "__NOFIELD__" ]; then
    result FAIL nextword-tt-татар "try-it field not in the dump"
elif ! echo "$wf_mid" | grep -qE 'татар $'; then
    result FAIL nextword-tt-татар "татар did not commit; field: '$wf_mid'"
elif [ "$wf_band" = "band=timeout" ]; then
    result FAIL nextword-tt-татар "the pinned band (теле телен дәүләт) never matched the reference in the bounded wait; nothing tapped; field: '$wf_after'"
elif [ "$wf_after" = "$wf_mid" ]; then
    result FAIL nextword-tt-татар "cell-2 tap committed nothing; field: '$wf_after'"
elif [ "$w2" = "телен" ]; then
    result PASS nextword-tt-татар "cell 2 = телен: the successors fill the whole strip (pinned, $wf_band)"
else
    result FAIL nextword-tt-татар "cell 2 committed '$w2' (expected телен from the pinned successor row); field: '$wf_after'"
fi

clear_tryit "nextword-сәләм"
wf=$(type_tt_and_tap_cell2 "сәләм" syalam "$STRIP_CELL0" nextword-syalam)
wf_mid="${wf%%$'\t'*}"
wf_rest="${wf#*$'\t'}"
wf_after="${wf_rest%%$'\t'*}"
wf_band="${wf_rest#*$'\t'}"
w2=$(second_word_after "$wf_after" "сәләм")
if [ "$wf_mid" = "__NOFIELD__" ] || [ "$wf_after" = "__NOFIELD__" ]; then
    result FAIL nextword-tt-сәләм "try-it field not in the dump"
elif ! echo "$wf_mid" | grep -qE 'сәләм $'; then
    result FAIL nextword-tt-сәләм "сәләм did not commit; field: '$wf_mid'"
elif [ "$wf_band" = "band=timeout" ]; then
    result FAIL nextword-tt-сәләм "the fallback band (һәм белән дә…) never matched the reference in the bounded wait; nothing tapped; field: '$wf_after'"
elif [ "$wf_after" = "$wf_mid" ]; then
    result FAIL nextword-tt-сәләм "cell-1 tap committed nothing; field: '$wf_after'"
elif [ "$w2" = "һәм" ]; then
    result PASS nextword-tt-сәләм "cell 1 = һәм: the global fallback leads a headless answer (pinned, $wf_band)"
else
    result FAIL nextword-tt-сәләм "cell 1 committed '$w2' (expected һәм); field: '$wf_after'"
fi

# ── next-word predictions right after an accepted suggestion ──
# The сәләм probe's cell-1 tap committed һәм with a trailing space, and the tap itself must
# re-issue the NEXT_WORD lookup without waiting for a keystroke. һәм is a bigram head whose first
# successor is башка, so a second tap on cell 1 (index 0) must commit башка.
if [ "$wf_after" = "__NOFIELD__" ] || [ "$wf_mid" = "__NOFIELD__" ] || [ "$wf_after" = "$wf_mid" ]; then
    result SKIP tap-followup-tt-сәләм "the сәләм probe above already failed; nothing to follow up on"
else
    # The API 28 image can answer this NEXT_WORD lookup after the first probe:
    # a tap on the still-empty band commits nothing, and the answer arrives a
    # moment later (the emoji-panel evidence then contains башка). Retry only
    # an unchanged field; a wrong committed word remains a product failure.
    followup_done=0
    for followup_attempt in 1 2 3; do
        sleep 2                      # the follow-up NEXT_WORD answer is asynchronous
        TAPF ${STRIP_CELL0%,*} ${STRIP_CELL0#*,}
        sleep 1
        wf_again=$(field_text)
        w3=$(second_word_after "$wf_again" "$w2")
        if [ "$wf_again" = "__NOFIELD__" ]; then
            result FAIL tap-followup-tt-сәләм "try-it field not in the dump"
            followup_done=1
            break
        elif [ "$wf_again" = "$wf_after" ]; then
            continue
        elif [ "$w3" = "башка" ]; then
            result PASS tap-followup-tt-сәләм "cell 1 of the follow-up band = башка: predictions for the accepted word without a keystroke"
            followup_done=1
            break
        else
            result FAIL tap-followup-tt-сәләм "follow-up cell 1 committed '$w3' (expected башка from the successors); field: '$wf_again'"
            followup_done=1
            break
        fi
    done
    if [ "$followup_done" != 1 ]; then
        result FAIL tap-followup-tt-сәләм "strip stayed empty after $followup_attempt attempts; field: '$wf_again'"
    fi
fi

# ── emoji panel: long press on comma, tap the first grid cell ──
before_emoji=$(field_text)
LONGPRESSF ${COMMA%,*} ${COMMA#*,}
sleep 2
SHOT smoke-emoji-panel.png
# Tap the first grid cell at GRID_CELL0_Y (set above for the visible suggestion strip).
# The panel has no separate search bar (search is a cell in
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
    # BACK hides the whole IME window; the field re-tap must wait for it to come
    # back before the м probe tap, exactly like refocus_tryit's focus loop. On
    # API 28 the re-show is asynchronous: a м tap that lands before the first
    # keyboard frame commits nothing (the API 28 '&#128512;' artefact), and a
    # re-tap that raced the show animation is dropped, so re-read the bounds and
    # retry the field tap a bounded number of times.
    pbr_revived=0
    for pbr_attempt in 1 2 3; do
        b=$(DUMP_UI | grep -oP '<node[^>]*setup_test_field[^>]*bounds="\[\K[0-9,\]\[]+' | head -1 || true)
        if [ -n "$b" ]; then
            read -r x1 y1 x2 y2 <<<"$(echo "$b" | tr '[],' '    ')"
            SHELL input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
        else
            TAPF 0.5 0.81
        fi
        for pbr_poll in $(seq 1 5); do
            keyboard_shown && { pbr_revived=1; break; }
            sleep 1
        done
        [ "$pbr_revived" = 1 ] && break
    done
    if [ "$pbr_revived" != 1 ]; then
        pbr_detail="the IME window did not come back after the panel BACK within the bounded field re-tap wait"
    else
        # The keyboard reports shown before its first frame accepts touches.
        sleep 2
        pbr_pre=$(field_text)
        TAPF 0.4231 0.8474      # м on the tt layout (same key as TT_MIN)
        # The м commit is asynchronous on API 28: poll until the field changes.
        # An unchanged field after the bounded wait is a lost tap; a changed
        # field without the м tail is a wrong commit and fails at once.
        for pbr_poll in $(seq 1 6); do
            sleep 1
            pbr_field=$(field_text)
            [ "$pbr_field" != "$pbr_pre" ] && break
        done
        if echo "$pbr_field" | grep -qE 'м$'; then
            SHELL input keyevent KEYCODE_DEL    # probe cleanup: the м (the next probe's activity
            sleep 0.4                           # restart clears the emoji the panel check committed)
        fi
    fi
fi
if [ -n "$pbr_detail" ]; then
    result FAIL panel-back-reopen "$pbr_detail"
elif [ "$pbr_field" = "__NOFIELD__" ]; then
    result FAIL panel-back-reopen "try-it field not in the dump after the м tap"
elif echo "$pbr_field" | grep -qE 'м$'; then
    result PASS panel-back-reopen "BACK hid the window (mInputShown=$pbr_shown), field re-tap revived it; field: '$pbr_field'"
else
    result FAIL panel-back-reopen "the м probe tap committed no м tail after the keyboard came back; field: '$pbr_field' (expected a м tail)"
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
#     personal dictionary ON + emoji suggestions ON (both default on, seeded so a reused
#     AVD with either off still works), so every earlier probe runs with unchanged prefs.
if [ "$PREFS_ACCESS" != 1 ]; then
    result SKIP learned-emoji-tt "prefs cannot be seeded via run-as"
else
    # force-stop first: a live process could flush its in-memory counters over the files
    # the rm below deletes. Then the learned store is removed (a reused AVD may have one),
    # the two prefs are seeded, and the IME is selected again (force-stopping the selected
    # IME resets default_input_method, as at the top of the script).
    SHELL am force-stop "$PKG" || true
    sleep 1
    # (run-as execs its command directly, without a shell, so the glob needs sh -c.)
    A shell "run-as $PKG sh -c 'rm -f /data/data/$PKG/no_backup/personal/personal-emoji-* /data/data/$PKG/no_backup/personal/salt-emoji*'" \
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
        confirm_space '^хәйерле $' || true
        type_word "$(tt_word_coords "иртә")"
        confirm_space '^хәйерле иртә $' || true
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
        TAPF 0.3438 0.5272       # the ☀️ result cell (5th for "кояш", asset order): column 2, upper row of the two-row band
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
        confirm_space '^хәйерле $' || true
        type_word "$(tt_word_coords "иртә")"
        confirm_space '^хәйерле иртә $' || true
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
    A shell "run-as $PKG sh -c 'rm -f /data/data/$PKG/no_backup/personal/personal-emoji-* /data/data/$PKG/no_backup/personal/salt-emoji*'" \
        2>/dev/null || true
fi

# ── UX legs: one-handed dock, number row, night mode, landscape ──
# Each leg toggles one setting, checks the keyboard's reaction and restores the state it
# found, so the next run starts from the same AVD state. The pref legs rewrite the prefs
# file through run-as like the suggestions seed at the top; a leg that cannot write the
# prefs is a SKIP, not a FAIL. The scenario leaves the tt layout active, and the probes
# below rely on it.

# patch_prefs <in.xml> <out.xml> <name> <type> <value>: set or add ONE pref entry and keep
# the rest of the file; with no usable input file write a minimal stub. The prefs file
# keeps one XML element per entry, so a typed regex rewrite is safe.
patch_prefs() {
    python3 - "$1" "$2" "$3" "$4" "$5" <<'PYEOF'
import re
import sys
from pathlib import Path

src, dst, name, ptype, value = sys.argv[1:6]
xml = Path(src).read_text(encoding="utf-8") if Path(src).is_file() else ""
entry = f'<{ptype} name="{name}" value="{value}" />'
pattern = re.compile(rf'<{ptype} name="{name}" value="[^"]*" ?/>')
if pattern.search(xml):
    xml = pattern.sub(entry, xml)
elif "</map>" in xml:
    xml = xml.replace("</map>", f"    {entry}\n</map>", 1)
else:
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
           f"<map>\n    {entry}\n</map>\n")
Path(dst).write_text(xml, encoding="utf-8")
PYEOF
}

# write_pref <name> <type> <value>: rewrite the pref via run-as. The pre-write file is
# saved under $OUTDIR, so restore_pref puts back exactly what the leg found.
write_pref() {
    [ "$PREFS_ACCESS" = 1 ] || return 1
    A shell "run-as $PKG cat $PREFS_PATH" 2>/dev/null | tr -d '\r' \
        > "$OUTDIR/prefs-before-$1.xml" || true
    patch_prefs "$OUTDIR/prefs-before-$1.xml" "$OUTDIR/prefs-set-$1.xml" "$1" "$2" "$3"
    A shell "run-as $PKG sh -c 'cat > $PREFS_PATH'" < "$OUTDIR/prefs-set-$1.xml"
}

# reload_ime: make the IME re-read the prefs file (a live process caches it in memory) and
# re-select it — force-stopping the selected IME resets default_input_method, as at the top.
reload_ime() {
    SHELL am force-stop "$PKG" || true
    sleep 1
    SHELL ime enable "$IME_ID" >/dev/null 2>&1 || true
    SHELL ime set "$IME_ID" >/dev/null 2>&1 || true
    sleep 1
}

# restore_pref <name>: put back the file write_pref saved, with the process stopped first —
# a live process would flush its in-memory prefs over the restored file. An empty saved
# file means the prefs file did not exist before the leg, so the restore removes it.
restore_pref() {
    SHELL am force-stop "$PKG" || true
    sleep 1
    if [ -s "$OUTDIR/prefs-before-$1.xml" ]; then
        A shell "run-as $PKG sh -c 'cat > $PREFS_PATH'" < "$OUTDIR/prefs-before-$1.xml" || true
    else
        A shell "run-as $PKG rm -f $PREFS_PATH" 2>/dev/null || true
    fi
    SHELL ime enable "$IME_ID" >/dev/null 2>&1 || true
    SHELL ime set "$IME_ID" >/dev/null 2>&1 || true
    sleep 1
}

# ── one-handed dock ──
# The dock scales the key grid to 85% and moves it to the chosen side; with the right dock
# the freed 15% strip is on the LEFT. The probe is the moved ә: full-width ә is the first
# key of the extra row (center x = 0.0833); docked right, the same key's center moves to
# 0.15 + 0.85 × 0.0833 ≈ 0.2208, where the full-width layout has ө — so an ә from that
# pixel exists only with the dock in place. The y is the extra row's center, unchanged:
# the dock is horizontal. The probe must stay clear of the strip at x < 0.15: a tap there
# is the exit handle and writes the pref back to off.
if [ "$PREFS_ACCESS" != 1 ]; then
    result SKIP one-handed-dock "prefs cannot be written via run-as"
else
    oh_detail=""
    oh_field=""
    write_pref pref_one_handed_side int 2 || oh_detail="the pref write failed"
    if [ -z "$oh_detail" ]; then
        reload_ime
        refocus_tryit || oh_detail="keyboard did not come up with the dock on"
    fi
    if [ -z "$oh_detail" ]; then
        TAPF 0.2208 0.665              # ә of the right-docked tt layout
        sleep 0.6
        oh_field=$(field_text)
        SHELL input keyevent KEYCODE_DEL
        sleep 0.4
    fi
    restore_pref pref_one_handed_side
    if [ -n "$oh_detail" ]; then
        result FAIL one-handed-dock "$oh_detail"
    elif [ "$oh_field" = "__NOFIELD__" ]; then
        result FAIL one-handed-dock "try-it поле не найдено в дампе"
    elif echo "$oh_field" | grep -qE '[әӘ] *$'; then
        result PASS one-handed-dock "ә из x=0.2208: док сдвинул сетку (в полной ширине там ө); в поле: '$oh_field'"
    else
        result FAIL one-handed-dock "из точки докованной ә пришло не ә; в поле: '$oh_field'"
    fi
fi

# ── number row ──
# pref_show_number_row=true prepends the shared row_qwerty0 (1234567890) to the letter
# rows. The kbd's two cases share the keyboard's total height (same base height, same
# bonusHeight; rowHeight 20%p vs 16.667%p), so the rows compress in place instead of
# growing upward: the digit row centers at y ≈ 0.660, where the row-less layout has ү in
# the extra row. The probe taps 5 (the digit row's middle key): a 5 in the field exists
# only with the row on; with it off the same pixel types ү.
if [ "$PREFS_ACCESS" != 1 ]; then
    result SKIP number-row "prefs cannot be written via run-as"
else
    nr_detail=""
    nr_field=""
    write_pref pref_show_number_row boolean true || nr_detail="the pref write failed"
    if [ -z "$nr_detail" ]; then
        reload_ime
        refocus_tryit || nr_detail="keyboard did not come up with the number row on"
    fi
    if [ -z "$nr_detail" ]; then
        TAPF 0.45 0.6599               # 5 in the digit row (with the row off: ү)
        sleep 0.6
        nr_field=$(field_text)
        SHELL input keyevent KEYCODE_DEL
        sleep 0.4
    fi
    restore_pref pref_show_number_row
    if [ -n "$nr_detail" ]; then
        result FAIL number-row "$nr_detail"
    elif [ "$nr_field" = "__NOFIELD__" ]; then
        result FAIL number-row "try-it поле не найдено в дампе"
    elif echo "$nr_field" | grep -qE '5 *$'; then
        result PASS number-row "цифра 5 из ряда цифр (y=0.66); в поле: '$nr_field'"
    else
        result FAIL number-row "из точки цифры 5 пришло не 5 (без ряда там ү); в поле: '$nr_field'"
    fi
fi

# ── night mode ──
# `cmd uimode night yes` flips the configuration's uiMode; the keyboard theme follows
# (values-night/), so the keyboard area's pixels must change wholesale. The crop starts
# below the suggestion strip, whose content does not depend on the theme. Both shots show
# the same fresh tt keyboard (the activity restarts between them), so any large delta is
# the theme. The saved mode is restored, not a hardcoded one, so a reused AVD keeps the
# state the script found.
NIGHT_CROP="1080x760+0+1440"
NIGHT_DIFF_MIN=100000
crop_diff() {                                # crop_diff <crop> before.png after.png → AE
    local out
    out=$(compare -metric AE \
        <(convert "$2" -crop "$1" +repage png:-) \
        <(convert "$3" -crop "$1" +repage png:-) null: 2>&1 || true)
    echo "$out" | grep -oP '\d+' | head -1 || echo 0
}
if [ "$HAVE_MAGICK" != 1 ]; then
    result SKIP night-mode "нет ImageMagick для пиксельной дельты"
else
    # API 24-25 have no `cmd uimode` shell implementation. Call UiModeManager's
    # stable binder transaction instead of measuring two unchanged screenshots
    # and misreporting a product failure. Where `cmd` exists, try it as shell
    # first: on API 30+ the write succeeds even with mNightModeLocked=true, so
    # no root is needed there. Only when the write demonstrably does not change
    # mNightMode (API 29 answers "Night mode: no" and keeps the old value) do we
    # restart adbd as root and repeat the SAME `cmd uimode` call, which that
    # image honors. Root changes no product code and adbd is unrooted after the
    # saved mode is restored.
    nm_saved=$(SHELL cmd uimode night 2>/dev/null | tr -d '\r' | grep -oP '(yes|no|auto)' | head -1 || true)
    nm_legacy=0
    nm_root=0
    if [ -z "$nm_saved" ]; then
        nm_legacy=1
        # Unguarded this assignment aborts the whole smoke under set -e whenever
        # the parcel is absent or its format changes (API 30+ prints another
        # layout), which used to end result.txt at the previous leg with no
        # night-mode evidence at all. The case below already defaults a missing
        # value to MODE_NIGHT_NO.
        nm_saved=$(SHELL service call uimode 5 2>/dev/null | tr -d '\r' | grep -oP 'Parcel\([0-9a-f]+\s+\K[0-9a-f]+' | head -1 || true)
        case "$nm_saved" in 0|1|2|3) ;; *) nm_saved=1 ;; esac
    fi
    # yes|no|auto for `cmd`, or the legacy numeric mode, mapped to the value
    # dumpsys uimode reports as mNightMode=<n>.
    nm_value() {
        case "$1" in
            yes) echo 2 ;;
            no) echo 1 ;;
            auto) echo 0 ;;
            *) echo "$1" ;;
        esac
    }
    set_night_mode() {
        if [ "$nm_legacy" = 1 ]; then
            SHELL service call uimode 4 i32 "$1" >/dev/null 2>&1 || true
            return
        fi
        SHELL cmd uimode night "$1" >/dev/null 2>&1 || true
        sleep 1
        if ! SHELL dumpsys uimode 2>/dev/null | tr -d '\r' | grep -q "mNightMode=$(nm_value "$1") "; then
            A root >/dev/null 2>&1 || true
            A wait-for-device
            sleep 3
            nm_root=1
            SHELL cmd uimode night "$1" >/dev/null 2>&1 || true
        fi
    }
    nm_detail=""
    # Normalize the baseline to day before the reference shot. The mode is a
    # disk-persisted system setting, so a reused AVD can boot already in night
    # mode; both screenshots then show the same theme, the delta is 0 px, and a
    # correct keyboard is reported as a failure. Enter day exactly like the
    # night flip below (same root fallback) and restore the saved mode at the
    # end, so a reused AVD still keeps the state the script found.
    if [ "$nm_legacy" = 1 ]; then
        set_night_mode 1
    else
        set_night_mode no
    fi
    sleep 2                         # the config change recreates the activity
    SHELL dumpsys uimode >"$OUTDIR/uimode-day.txt" 2>/dev/null
    if ! grep -q 'mNightMode=1 ' "$OUTDIR/uimode-day.txt"; then
        nm_detail="image did not enter day mode (mNightMode unchanged); see uimode-day.txt"
    fi
    if [ -z "$nm_detail" ]; then
        refocus_tryit || nm_detail="keyboard did not come up for the day shot"
    fi
    if [ -z "$nm_detail" ]; then
        SHOT smoke-night-off.png
        if [ "$nm_legacy" = 1 ]; then
            set_night_mode 2
        else
            set_night_mode yes
        fi
        sleep 2                         # the config change recreates the activity
        # A flip the image refused is not a keyboard failure: record it as such,
        # with the dumpsys evidence, instead of diffing two identical shots.
        SHELL dumpsys uimode >"$OUTDIR/uimode-night.txt" 2>/dev/null
        if ! grep -q 'mNightMode=2 ' "$OUTDIR/uimode-night.txt"; then
            nm_detail="image did not enter night mode (mNightMode unchanged); see uimode-night.txt"
        fi
        refocus_tryit || nm_detail="keyboard did not come up for the night shot"
    fi
    if [ -z "$nm_detail" ]; then
        SHOT smoke-night-on.png
    fi
    if [ "$nm_legacy" = 1 ]; then
        set_night_mode "$((16#$nm_saved))"
    else
        set_night_mode "$nm_saved"
    fi
    if [ "$nm_root" = 1 ]; then
        A unroot >/dev/null 2>&1 || true
        A wait-for-device
        sleep 3
    fi
    sleep 1
    if [ -n "$nm_detail" ]; then
        result FAIL night-mode "$nm_detail"
    else
        nm_diff=$(crop_diff "$NIGHT_CROP" "$OUTDIR/smoke-night-off.png" "$OUTDIR/smoke-night-on.png")
        if [ "$nm_diff" -ge "$NIGHT_DIFF_MIN" ]; then
            result PASS night-mode "тема переключилась: $nm_diff px (порог $NIGHT_DIFF_MIN)"
        else
            result FAIL night-mode "клавиатура не изменилась: $nm_diff px (порог $NIGHT_DIFF_MIN)"
        fi
    fi
fi

# ── landscape rotate + one typed check ──
# user_rotation=1 with the accelerometer off forces landscape. In landscape the try-it
# field sits below the fold of the setup ScrollView, so refocus_tryit scrolls the field
# into the dump and taps it. The tap targets are NOT screen fractions here: TAPF's cached
# portrait size would land them off-screen, so the probe reads the IME's visible frame
# from dumpsys (the InsetsSource of type ime; the InputMethod window's own mFrame covers
# the whole screen, and its " Frames:" line does not even exist on every image) and
# computes the extra row's ә from it (frame + 44dp strip + one of five equal rows). The
# rotation values are restored to what the script found.
ls_saved_acc=$(SHELL settings get system accelerometer_rotation | tr -d '\r' || true)
ls_saved_rot=$(SHELL settings get system user_rotation | tr -d '\r' || true)
rotation_screen_off
# An unset shell setting is the literal string "null"; putting "null" back would
# fail the settings provider and leave the AVD locked in landscape.
[ "$ls_saved_acc" = "null" ] && ls_saved_acc=1
[ "$ls_saved_rot" = "null" ] && ls_saved_rot=0
SHELL settings put system accelerometer_rotation 0 || true
SHELL settings put system user_rotation 1 || true
sleep 2
rotation_screen_on
ls_detail=""
ls_field=""
refocus_tryit || ls_detail="keyboard did not come up in landscape"
if [ -z "$ls_detail" ]; then
    ls_dump="$OUTDIR/dumpsys-window-landscape.txt"
    SHELL dumpsys window > "$ls_dump" 2>&1
    # API 30+ reports the IME insets frame. API 30-33 name the type
    # ITYPE_IME, API 34+ uses ime; the id is absent on older images.
    # Older images only have the classic InputMethod window block with
    # mFrame. Keep the dump as evidence either way.
    ls_frame=$(python3 - "$ls_dump" <<'PY'
import re
import sys

with open(sys.argv[1], encoding="utf-8", errors="replace") as source:
    text = source.read().replace("\r", "")
m = re.search(
    r"InsetsSource(?: id=\d+)? type=(?:ime|ITYPE_IME) "
    r"frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
    text)
if m:
    print(" ".join(m.groups()))
else:
    found = False
    for window in re.finditer(
            r"Window[^\n]*InputMethod[^\n]*:\n((?:[ \t]+[^\n]*\n)+)",
            text):
        m = re.search(
            r"mFrame=Rect\(\s*(\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)",
            window.group(1))
        if m:
            print(" ".join(m.groups()))
            found = True
            break
    if not found:
        # Classic dumps make the InputMethod window fill the display. While the
        # keyboard is shown, mCurrent (mCur on some branches) ends where the
        # app stops and mDock covers the IME area, so their intersection is
        # the actual keyboard viewport. The bracketing differs by release:
        # API 24-27 print mCur=(0,66)-(2148,425), later images use
        # mCurrent=[0,66][2148,425]; both name the same rectangles.
        def legacy_rect(name):
            match = re.search(name + r"=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", text)
            if match is None:
                match = re.search(name + r"=\((\d+),(\d+)\)-\((\d+),(\d+)\)", text)
            return match

        dock = legacy_rect("mDock")
        cur = legacy_rect(r"m(?:Cur|Current)")
        if dock and cur:
            dl, dt, dr, db = (int(value) for value in dock.groups())
            _cl, _ct, _cr, cb = (int(value) for value in cur.groups())
            if dt < cb < db:
                print(dl, cb, dr, db)
PY
)
    if [ -z "$ls_frame" ]; then
        ls_detail="IME frame not found in dumpsys window; see dumpsys-window-landscape.txt"
    fi
fi
if [ -z "$ls_detail" ]; then
    read -r ls_l ls_t ls_r ls_b <<<"$ls_frame"
    # 44dp of strip at density 2.75 above five equal rows; ә is the extra row's first
    # key. The frame's bottom pad (the empty window tail behind the navigation bar) only
    # shifts the row arithmetic, and ә's key is twice as tall as the shift.
    ls_rowh=$(( (ls_b - ls_t - 121) / 5 ))
    ls_x=$(python3 -c "print(round($ls_l + ($ls_r - $ls_l) * 0.08333))")
    ls_y=$(( ls_t + 121 + ls_rowh / 2 ))
    # On the slow legacy images the first tap right after the rotation lands
    # before the freshly rotated IME surface accepts touches: it commits
    # nothing even though mInputShown is already true. Poll the field and
    # re-tap until the ә arrives; a working keyboard answers on the first
    # round, a dead one fails after the window instead of flaking.
    for _ in $(seq 1 10); do
        SHELL input tap "$ls_x" "$ls_y"
        sleep 0.6
        ls_field=$(field_text)
        echo "$ls_field" | grep -qE '[әӘ] *$' && break
    done
    SHELL input keyevent KEYCODE_DEL
    sleep 0.4
fi
# Restore the accelerometer BEFORE user_rotation: with auto-rotate already on, the
# user_rotation write re-evaluates the orientation and this image resets
# accelerometer_rotation to 0, so the reverse order loses the saved value.
rotation_screen_off
SHELL settings put system accelerometer_rotation "${ls_saved_acc:-1}" || true
SHELL settings put system user_rotation "${ls_saved_rot:-0}" || true
sleep 2
SHELL settings put system accelerometer_rotation "${SMOKE_SAVED_ACC:-1}" || true
SHELL settings put system user_rotation "${SMOKE_SAVED_ROT:-0}" || true
sleep 2
rotation_screen_on
if [ -n "$ls_detail" ]; then
    result FAIL landscape-type "$ls_detail"
elif [ "$ls_field" = "__NOFIELD__" ]; then
    result FAIL landscape-type "try-it поле не найдено в дампе"
elif echo "$ls_field" | grep -qE '[әӘ] *$'; then
    result PASS landscape-type "ә набрана в landscape ($ls_frame); в поле: '$ls_field'"
else
    result FAIL landscape-type "в landscape пришло не ә; в поле: '$ls_field'"
fi

# ── crash buffer ──
# Only a crash of the package under test fails the leg. A bare 'AndroidRuntime'
# or any-process rule misfires here: on API 24-27 images a zygote fork can end
# in a native SIGSEGV whose backtrace prints an AndroidRuntime symbol, and other
# preinstalled apps may crash on their own; neither is this release's evidence.
if ! A logcat -b crash -d > "$OUTDIR/logcat-crash.txt" 2>&1; then
    result FAIL crash-log "не удалось прочитать crash-буфер: $(tail -1 "$OUTDIR/logcat-crash.txt")"
elif { grep -A3 'FATAL EXCEPTION' "$OUTDIR/logcat-crash.txt" | grep -qF "Process: $PKG"; } \
        || grep -qF ">>> $PKG <<<" "$OUTDIR/logcat-crash.txt"; then
    result FAIL crash-log "crash of $PKG in the buffer; see logcat-crash.txt"
else
    result PASS crash-log "crash-буфер пуст"
fi

# ── summary ──
passes=$(grep -c '^RESULT|PASS|' "$RESULTS" || true)
skips=$(grep -c '^RESULT|SKIP|' "$RESULTS" || true)
echo "RESULT|SUMMARY|pass=$passes fail=$FAILURES skip=$skips|outdir=$OUTDIR" | tee -a "$RESULTS"
[ "$FAILURES" = 0 ]
