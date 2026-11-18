#!/usr/bin/env bash
# Headless emulator release validation. Each --avd maps one Android API level to
# an existing AVD. Every API from minSdk through targetSdk must be mapped; the
# script refuses to present a partial matrix as complete release evidence.
#
# For each AVD it performs a signed upgrade, runs emulator-smoke.sh against the
# upgraded APK, executes the on-device backup/restore and field-switch tests,
# and checks that a corrupt Tatar personal dictionary is quarantined without a
# process crash. The emulator runs with -no-window. Use disposable AVDs with a
# 1080x2280 display because the smoke scenario uses calibrated key coordinates.
#
# The debug and androidTest APKs are instrumentation evidence only when they
# are the same build as the candidate: the debug package must be the candidate
# id plus a build-type suffix, the androidTest package the debug id plus
# .test, and the debug APK must carry the candidate's versionName/versionCode.
# AGP usually leaves both version fields empty in the instrumentation APK; that
# unversioned form is accepted, while any explicit test APK version must match
# the candidate. A mismatch aborts with exit 2 before any instrumentation PASS.
# The smoke leg also parses smoke/result.txt: a SKIP on the critical suggest-tt/suggest-ru
# pixel-delta probes downgrades that leg to FAIL even though the smoke exit
# code is 0.
#
# Required artifacts:
#   --candidate <apk>       signed candidate release APK
#   --previous <apk>        signed installed-base APK (default dist/tatar-keyboard-3.14.1.apk)
#   --debug-apk <apk>       matching debug APK for instrumentation/run-as probes
#   --androidtest-apk <apk> matching debug androidTest APK
#   --avd <api=name>        repeatable API-to-AVD mapping
#   --outdir <path>         evidence directory (default build/emulator-release-validation)
#   --plan                  validate and print the matrix without adb or an emulator
#
# Example (one mapping per supported API is required):
#   bash scripts/emulator-release-validation.sh \
#     --candidate dist/tatar-keyboard-4.0.0.apk \
#     --avd 24=tt_api24 --avd 25=tt_api25 # ... through the target API
#
# The result is never P0 signoff by itself: Direct Boot before first unlock,
# lock-screen quick reply, and TalkBack interaction require separate physical-
# device evidence. Those boundaries are explicit SKIP lines and make OVERALL
# INCOMPLETE even when every emulator leg passes.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CANDIDATE=""
PREVIOUS="$ROOT/dist/tatar-keyboard-3.14.1.apk"
DEBUG_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
ANDROIDTEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
OUTDIR="$ROOT/build/emulator-release-validation"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
EMULATOR="${EMULATOR:-$HOME/Android/Sdk/emulator/emulator}"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
PLAN=0
ONLY_APIS=""
AVD_APIS=()
AVD_NAMES=()

while [ $# -gt 0 ]; do
    case "$1" in
        --candidate) CANDIDATE="$2"; shift 2 ;;
        --previous) PREVIOUS="$2"; shift 2 ;;
        --debug-apk) DEBUG_APK="$2"; shift 2 ;;
        --androidtest-apk) ANDROIDTEST_APK="$2"; shift 2 ;;
        --avd)
            case "$2" in
                *=*) AVD_APIS+=("${2%%=*}"); AVD_NAMES+=("${2#*=}") ;;
                *) echo "ERROR: --avd expects api=name" >&2; exit 2 ;;
            esac
            shift 2
            ;;
        --outdir) OUTDIR="$2"; shift 2 ;;
        --only) ONLY_APIS="$2"; shift 2 ;;
        --plan) PLAN=1; shift ;;
        *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
    esac
done

EXPECTED_MIN=$(grep -oE 'minSdkVersion [0-9]+' "$ROOT/app/build.gradle" | awk '{print $2}' | head -1)
EXPECTED_TARGET=$(grep -oE 'targetSdkVersion [0-9]+' "$ROOT/app/build.gradle" | awk '{print $2}' | head -1)
case "$EXPECTED_MIN" in ''|*[!0-9]*) echo "ERROR: cannot parse min SDK" >&2; exit 2 ;; esac
case "$EXPECTED_TARGET" in ''|*[!0-9]*) echo "ERROR: cannot parse target SDK" >&2; exit 2 ;; esac
if [ "$EXPECTED_MIN" -gt "$EXPECTED_TARGET" ]; then
    echo "ERROR: min SDK exceeds target SDK" >&2
    exit 2
fi

mkdir -p "$OUTDIR"
RESULTS="$OUTDIR/result.txt"
: > "$RESULTS"
FAILURES=0
SKIPS=0
result() { # result PASS|FAIL|SKIP <check> <detail>
    local line="RESULT|$1|$2|$3"
    echo "$line"
    echo "$line" >> "$RESULTS"
    case "$1" in
        FAIL) FAILURES=$((FAILURES + 1)) ;;
        SKIP) SKIPS=$((SKIPS + 1)) ;;
    esac
}
log() { echo "emulator-release: $*" >&2; }

avd_for() { # avd_for <api> -> AVD name or failure
    local i
    for i in "${!AVD_APIS[@]}"; do
        [ "${AVD_APIS[$i]}" = "$1" ] && { printf '%s' "${AVD_NAMES[$i]}"; return 0; }
    done
    return 1
}

MATRIX_OK=1
SEEN_APIS=","
for i in "${!AVD_APIS[@]}"; do
    api="${AVD_APIS[$i]}"
    name="${AVD_NAMES[$i]}"
    case "$api" in ''|*[!0-9]*) echo "ERROR: non-numeric API in --avd: $api" >&2; exit 2 ;; esac
    if [ -z "$name" ] || [ "$api" -lt "$EXPECTED_MIN" ] || [ "$api" -gt "$EXPECTED_TARGET" ]; then
        echo "ERROR: invalid --avd mapping: $api=$name" >&2
        exit 2
    fi
    case "$SEEN_APIS" in
        *",$api,"*) echo "ERROR: duplicate --avd mapping for API $api" >&2; exit 2 ;;
    esac
    SEEN_APIS+="$api,"
done

for ((api = EXPECTED_MIN; api <= EXPECTED_TARGET; api++)); do
    if name=$(avd_for "$api"); then
        echo "PLAN|api=$api|avd=$name"
    else
        MATRIX_OK=0
        result SKIP "api.$api" "no --avd $api=<name> mapping"
    fi
done
if [ "${#AVD_APIS[@]}" -eq 0 ]; then
    MATRIX_OK=0
fi

api_selected() { # api_selected <api> -> true when --only is unset or lists <api>
    [ -z "$ONLY_APIS" ] && return 0
    case ",$ONLY_APIS," in *",$1,"*) return 0 ;; *) return 1 ;; esac
}

physical_boundaries() {
    result SKIP physical.direct-boot "separate physical-device evidence required before first unlock"
    result SKIP physical.lock-screen "separate physical-device evidence required for lock-screen quick reply"
    result SKIP physical.talkback "separate physical-device evidence required for spoken accessibility interaction"
}

if [ "$PLAN" = 1 ]; then
    physical_boundaries
    if [ "$MATRIX_OK" = 1 ] && [ "$FAILURES" = 0 ]; then
        echo "EMULATOR_PLAN|READY|apis=$EXPECTED_MIN-$EXPECTED_TARGET"
    else
        echo "EMULATOR_PLAN|INCOMPLETE|missing_api_mappings"
    fi
    echo "OVERALL|INCOMPLETE|physical_only=direct-boot,lock-screen,talkback"
    exit 1
fi

if [ "$MATRIX_OK" != 1 ]; then
    echo "OVERALL|INCOMPLETE|complete_the_api_matrix_before_running" | tee -a "$RESULTS"
    exit 1
fi
if [ -n "$ONLY_APIS" ]; then
    for api in ${ONLY_APIS//,/ }; do
        avd_for "$api" >/dev/null 2>&1 || { echo "ERROR: --only $api has no --avd $api=<name> mapping" >&2; exit 2; }
    done
fi
[ -n "$CANDIDATE" ] || { echo "ERROR: --candidate is required" >&2; exit 2; }
for artifact in "$CANDIDATE" "$PREVIOUS" "$DEBUG_APK" "$ANDROIDTEST_APK"; do
    [ -f "$artifact" ] && [ -r "$artifact" ] || { echo "ERROR: unreadable APK: $artifact" >&2; exit 2; }
done
[ -x "$ADB" ] || { echo "ERROR: adb not found: $ADB" >&2; exit 2; }
[ -x "$EMULATOR" ] || { echo "ERROR: emulator not found: $EMULATOR" >&2; exit 2; }
[ -d "$SDK_ROOT/build-tools" ] || { echo "ERROR: build-tools not found under $SDK_ROOT" >&2; exit 2; }

source "$ROOT/scripts/build-tools-pin.sh"
source "$ROOT/scripts/instrumentation-verdict.sh"
if [ -d "$SDK_ROOT/build-tools/$TT_BUILD_TOOLS_PIN" ]; then
    TOOL_ROOT="$SDK_ROOT/build-tools/$TT_BUILD_TOOLS_PIN"
else
    TOOL_ROOT="$SDK_ROOT/build-tools"
fi
AAPT2=$(find "$TOOL_ROOT" -maxdepth 2 -name aapt2 -type f | sort -V | tail -1)
APKSIGNER=$(find "$TOOL_ROOT" -maxdepth 2 -name apksigner -type f | sort -V | tail -1)
[ -n "$AAPT2" ] && [ -n "$APKSIGNER" ] || { echo "ERROR: aapt2/apksigner not found" >&2; exit 2; }

badge() { # badge <apk> [allow-unversioned] -> package|versionCode|versionName
    local text package vc vn
    text=$("$AAPT2" dump badging "$1" 2>/dev/null | head -1)
    package=$(sed -nE "s/.*package: name='([^']+)'.*/\1/p" <<<"$text")
    vc=$(sed -nE "s/.*versionCode='([0-9]+)'.*/\1/p" <<<"$text")
    vn=$(sed -nE "s/.*versionName='([^']*)'.*/\1/p" <<<"$text")
    [ -n "$package" ] || return 1
    if [ "${2:-}" != "allow-unversioned" ]; then
        [ -n "$vc" ] && [ -n "$vn" ] || return 1
    fi
    printf '%s|%s|%s' "$package" "$vc" "$vn"
}
certificate() { # certificate <apk> -> exactly one verified SHA-256 digest
    local output
    output=$("$APKSIGNER" verify --print-certs "$1" 2>&1) || return 1
    mapfile -t digests < <(grep -oE 'certificate SHA-256 digest: [0-9a-f]{64}' <<<"$output" \
        | grep -oE '[0-9a-f]{64}' | sort -u)
    [ "${#digests[@]}" -eq 1 ] || return 1
    printf '%s' "${digests[0]}"
}

CANDIDATE_BADGE=$(badge "$CANDIDATE") || { echo "ERROR: bad candidate badging" >&2; exit 2; }
PREVIOUS_BADGE=$(badge "$PREVIOUS") || { echo "ERROR: bad previous APK badging" >&2; exit 2; }
DEBUG_BADGE=$(badge "$DEBUG_APK") || { echo "ERROR: bad debug APK badging" >&2; exit 2; }
ANDROIDTEST_BADGE=$(badge "$ANDROIDTEST_APK" allow-unversioned) || { echo "ERROR: bad androidTest APK badging" >&2; exit 2; }
IFS='|' read -r PKG CANDIDATE_VC CANDIDATE_VN <<<"$CANDIDATE_BADGE"
IFS='|' read -r PREVIOUS_PKG PREVIOUS_VC PREVIOUS_VN <<<"$PREVIOUS_BADGE"
IFS='|' read -r DEBUG_PKG DEBUG_VC DEBUG_VN <<<"$DEBUG_BADGE"
IFS='|' read -r TEST_PKG TEST_VC TEST_VN <<<"$ANDROIDTEST_BADGE"
[ "$PKG" = "$PREVIOUS_PKG" ] || { echo "ERROR: upgrade package identity differs" >&2; exit 2; }
[ "$CANDIDATE_VC" -gt "$PREVIOUS_VC" ] || { echo "ERROR: candidate versionCode must increase" >&2; exit 2; }
CANDIDATE_CERT=$(certificate "$CANDIDATE") || { echo "ERROR: candidate signature invalid or multi-signed" >&2; exit 2; }
PREVIOUS_CERT=$(certificate "$PREVIOUS") || { echo "ERROR: previous signature invalid or multi-signed" >&2; exit 2; }
[ "$CANDIDATE_CERT" = "$PREVIOUS_CERT" ] || { echo "ERROR: upgrade certificates differ" >&2; exit 2; }
result PASS artifact.upgrade-contract "package=$PKG from=$PREVIOUS_VN/$PREVIOUS_VC to=$CANDIDATE_VN/$CANDIDATE_VC certificate=${CANDIDATE_CERT:0:12}…"
# The instrumentation and run-as legs run against the debug application id, so
# those APKs are candidate evidence only when they are the same build: the
# debug package is the candidate id plus a build-type suffix, the androidTest
# package is the debug id plus .test (the instrument target below assumes
# exactly that), and both carry the candidate's versionName/versionCode.
case "$DEBUG_PKG" in
    "$PKG"|"$PKG".*) ;;
    *) echo "ERROR: debug package $DEBUG_PKG is not the candidate package $PKG plus a suffix" >&2; exit 2 ;;
esac
[ "$DEBUG_VN" = "$CANDIDATE_VN" ] && [ "$DEBUG_VC" -eq "$CANDIDATE_VC" ] || {
    echo "ERROR: debug APK version $DEBUG_VN/$DEBUG_VC does not match candidate $CANDIDATE_VN/$CANDIDATE_VC" >&2
    exit 2
}
[ "$TEST_PKG" = "$DEBUG_PKG.test" ] || {
    echo "ERROR: androidTest package $TEST_PKG is not $DEBUG_PKG.test" >&2
    exit 2
}
# AGP-generated instrumentation APKs normally omit both version fields: the
# instrumented debug application supplies the version. Accept only that fully
# unspecified form; if either field is present, require an exact candidate match.
TEST_VERSION_SOURCE="explicit"
if [ -z "$TEST_VN" ] && [ -z "$TEST_VC" ]; then
    TEST_VN="$CANDIDATE_VN"
    TEST_VC="$CANDIDATE_VC"
    TEST_VERSION_SOURCE="unspecified-by-AGP"
else
    [ "$TEST_VN" = "$CANDIDATE_VN" ] && [ "$TEST_VC" -eq "$CANDIDATE_VC" ] || {
        echo "ERROR: androidTest version $TEST_VN/$TEST_VC does not match candidate $CANDIDATE_VN/$CANDIDATE_VC" >&2
        exit 2
    }
fi
result PASS artifact.instrumentation-contract "debug=$DEBUG_PKG/$DEBUG_VN/$DEBUG_VC androidTest=$TEST_PKG/$TEST_VN/$TEST_VC candidate=$PKG/$CANDIDATE_VN/$CANDIDATE_VC androidTestVersion=$TEST_VERSION_SOURCE"

EMU_PID=""
SERIAL=""
CURRENT_EVIDENCE_DIR=""
cleanup() {
    if [ -n "$SERIAL" ] && [ -n "$CURRENT_EVIDENCE_DIR" ]; then
        # Keep the guest log before killing the emulator: an image-level
        # system_server restart otherwise leaves only a client Broken pipe or
        # DeadObjectException, with no evidence of the service that disappeared.
        timeout 45 "$ADB" -s "$SERIAL" logcat -b all -d \
            >"$CURRENT_EVIDENCE_DIR/logcat-all.txt" 2>&1 || true
    fi
    if [ -n "$EMU_PID" ]; then
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || kill "$EMU_PID" 2>/dev/null || true
        wait "$EMU_PID" 2>/dev/null || true
        EMU_PID=""
        SERIAL=""
        CURRENT_EVIDENCE_DIR=""
    fi
}
trap cleanup EXIT

A() { "$ADB" -s "$SERIAL" "$@"; }
SHELL() { A shell "$@"; }
# The package service registers before PackageManager is usable, and a
# restarting system_server drops it entirely: a plain `service check package`
# is not enough for the install stream (the API 37 image answered "found" and
# then died with "Failure calling service package: Broken pipe"). Require the
# binder to answer a real query before every install.
wait_package_manager() { # wait_package_manager -> 0 when pm answers
    local i
    for i in $(seq 1 60); do
        if SHELL service check package 2>/dev/null | grep -q found \
                && SHELL pm path android 2>/dev/null | grep -q '^package:'; then
            return 0
        fi
        sleep 2
    done
    return 1
}
# adb install has no inactivity watchdog: on the old images the install stream
# can wedge silently while every other adb verb keeps answering (observed on
# tt_api24: the client sat for minutes with the device fully responsive), which
# would hang the whole matrix. Bound each install, reconnect the transport once,
# and retry; a still-failing install fails the leg instead of the run.
install_apk() { # install_apk <apk> <log>
    local attempt remote
    # --no-incremental forces the streamed install path: on the API 37 image
    # the incremental-install service crashes system_server mid-install
    # (services vanish: "Can't find service: package"), so the whole leg dies.
    for attempt in 1 2; do
        wait_package_manager || { log "package manager not ready for $(basename "$1")"; return 1; }
        if timeout 240 "$ADB" -s "$SERIAL" install --no-incremental -r "$1" >"$2" 2>&1; then
            return 0
        fi
        [ "$attempt" = 1 ] || break
        log "install of $(basename "$1") wedged or failed; waiting for the package manager and retrying"
        "$ADB" -s "$SERIAL" reconnect >/dev/null 2>&1 || true
        # A booting or restarting system_server has no usable package manager
        # yet: the transport keeps answering while every install verb fails,
        # so wait for a real query instead of spending the retry blind.
        wait_package_manager || true
        sleep 5
    done
    # Last resort: the sync protocol and the on-device installer are independent
    # of the install stream that wedges, so this path still completes.
    remote="/data/local/tmp/$(basename "$1")"
    wait_package_manager || return 1
    if timeout 240 "$ADB" -s "$SERIAL" push "$1" "$remote" >>"$2" 2>&1 \
            && timeout 240 "$ADB" -s "$SERIAL" shell pm install -r "$remote" >>"$2" 2>&1; then
        "$ADB" -s "$SERIAL" shell rm -f "$remote" >/dev/null 2>&1 || true
        return 0
    fi
    return 1
}
booted_api() { SHELL getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'; }
package_dump() { SHELL dumpsys package "$PKG" 2>/dev/null; }
package_field() { package_dump | grep -oP "$2=\\K[^[:space:]]+" | head -1; }
instrument() { # instrument <api-dir> <log-name> <class> <check>
    local directory="$1" output="$2" class="$3" check="$4" rc
    SHELL am instrument -w -r -e class "$class" \
        "$DEBUG_PKG.test/android.test.InstrumentationTestRunner" >"$directory/$output" 2>&1
    rc=$?
    cat "$directory/$output"
    if [ "$rc" = 0 ] && instrumentation_log_succeeded "$directory/$output"; then
        result PASS "$check" "$class"
    else
        result FAIL "$check" "rc=$rc class=$class log=$directory/$output"
        return 1
    fi
}

# The smoke exit code is non-zero only for a FAIL, so a SKIP would still let
# the leg PASS. The suggest-tt/suggest-ru pixel-delta probes are the Tatar
# suggestion evidence of this leg; their SKIP (typically a host without
# ImageMagick) must fail the leg instead of passing silently. suggest-en-hi is
# not critical: the en layout ships no dictionary, so its SKIP is by design.
# learned-emoji-tt and suggestions-enabled SKIP on the non-debuggable release
# candidate because run-as is refused; those boundaries are documented in the
# smoke header and are not suggestion-answer evidence.
critical_smoke_skips() { # critical_smoke_skips <result.txt> -> critical SKIP lines
    [ -f "$1" ] && grep -E '^RESULT\|SKIP\|suggest-(tt|ru)-' "$1" || true
}

run_avd() { # run_avd <api> <name>
    local api="$1" name="$2" directory before_serials deadline booted actual
    directory="$OUTDIR/api-$api"
    mkdir -p "$directory"
    CURRENT_EVIDENCE_DIR="$directory"
    before_serials=$("$ADB" devices | awk '$2 == "device" {print $1}' | sort)
    log "booting API $api AVD $name"
    "$EMULATOR" -avd "$name" -no-window -no-audio -no-snapshot-save \
        >"$directory/emulator.log" 2>&1 &
    EMU_PID=$!
    SERIAL=""
    deadline=$((SECONDS + 300))
    while [ $SECONDS -lt $deadline ]; do
        SERIAL=$(comm -13 <(printf '%s\n' "$before_serials") \
            <("$ADB" devices | awk '$2 == "device" {print $1}' | sort) | head -1)
        [ -n "$SERIAL" ] && break
        sleep 2
    done
    if [ -z "$SERIAL" ]; then
        result FAIL "api.$api.boot" "new emulator serial did not appear"
        return 1
    fi
    "$ADB" -s "$SERIAL" wait-for-device
    booted=""
    while [ $SECONDS -lt $deadline ]; do
        booted=$(SHELL getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        [ "$booted" = 1 ] && break
        sleep 3
    done
    if [ "$booted" != 1 ]; then
        result FAIL "api.$api.boot" "sys.boot_completed timeout serial=$SERIAL"
        return 1
    fi
    sleep 10
    # Calibrated input coordinates require portrait even if an earlier probe
    # left this disposable AVD's rotation locked in landscape.
    SHELL settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
    SHELL settings put system user_rotation 0 >/dev/null 2>&1 || true
    sleep 2
    actual=$(booted_api)
    if [ "$actual" != "$api" ]; then
        result FAIL "api.$api.boot" "expected SDK $api, actual '$actual' AVD=$name"
        return 1
    fi
    result PASS "api.$api.boot" "serial=$SERIAL avd=$name sdk=$actual"

    # A fresh image registers the system services after sys.boot_completed: the
    # API 37 boot that passed above still had no package service for the first
    # install ("Can't find service: package"). Bound the wait so the install
    # cannot race a starting system_server.
    local services_ready=0
    for _ in $(seq 1 60); do
        if SHELL service check package 2>/dev/null | grep -q found \
                && SHELL service check input_method 2>/dev/null | grep -q found; then
            services_ready=1
            break
        fi
        sleep 2
    done
    if [ "$services_ready" != 1 ]; then
        result FAIL "api.$api.boot" "package/input_method services absent after 120 seconds"
        return 1
    fi
    # API 36+ guests report the host's AT keyboard as a physical keyboard, so
    # InputMethodService.onEvaluateInputViewShown() suppresses the soft input
    # view and implicit shows fail with PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE: the
    # keyboard window never draws and calibrated key taps land on nothing.
    # The standard SHOW_IME_WITH_HARD_KEYBOARD opt-in restores the on-screen
    # keyboard on these touch-only test devices. Set it before the first IME
    # process starts because the service caches the flag.
    if [ "$actual" -ge 36 ]; then
        SHELL settings put secure show_ime_with_hard_keyboard 1 >/dev/null 2>&1 || true
    fi

    # A previous leg leaves the debug IME selected. Clear it before the release
    # scenario so a stale debug process or crash dialog cannot steal focus; the
    # matching debug APK is installed afresh for instrumentation below.
    SHELL pm clear "$DEBUG_PKG" >/dev/null 2>&1 || true
    # Install the old signed package from a deterministic absent state, select its
    # IME, then replace it in place. firstInstallTime and the selection must survive.
    A uninstall "$PKG" >/dev/null 2>&1 || true
    if ! install_apk "$PREVIOUS" "$directory/install-previous.log"; then
        result FAIL "api.$api.upgrade" "previous install failed; see install-previous.log"
        return 1
    fi
    local old_ime old_first new_first new_vc current_ime
    old_ime=""
    for _ in $(seq 1 15); do
        old_ime=$(SHELL ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$PKG/" | head -1)
        [ -n "$old_ime" ] && break
        sleep 2
    done
    if [ -z "$old_ime" ]; then
        result FAIL "api.$api.upgrade" "old package has no listed IME after 30 seconds; see install-previous.log"
        return 1
    fi
    # `ime set` is asynchronous, and on a fresh image (API 37) the first call
    # can lose to the stock IME that the factory state selected: without this
    # wait the upgrade below "preserves" a selection that was never made and
    # the leg fails on the stock id instead of on the candidate.
    local old_selected
    old_selected=""
    for _ in $(seq 1 15); do
        SHELL ime enable "$old_ime" >/dev/null 2>&1 || true
        SHELL ime set "$old_ime" >/dev/null 2>&1 || true
        old_selected=$(SHELL settings get secure default_input_method 2>/dev/null | tr -d '\r')
        [ "$old_selected" = "$old_ime" ] && break
        sleep 1
    done
    if [ "$old_selected" != "$old_ime" ]; then
        result FAIL "api.$api.upgrade" "old IME selection did not stick: '$old_selected' != '$old_ime'"
        return 1
    fi
    old_first=$(package_field "$PKG" firstInstallTime)
    if ! install_apk "$CANDIDATE" "$directory/install-candidate.log"; then
        result FAIL "api.$api.upgrade" "candidate install -r failed; see install-candidate.log"
        return 1
    fi
    new_first=$(package_field "$PKG" firstInstallTime)
    new_vc=$(package_field "$PKG" versionCode)
    current_ime=$(SHELL settings get secure default_input_method | tr -d '\r')
    if [ -n "$old_first" ] && [ "$old_first" = "$new_first" ] \
            && [ "$new_vc" = "$CANDIDATE_VC" ] && [ "$current_ime" = "$old_ime" ]; then
        result PASS "api.$api.upgrade" "from=$PREVIOUS_VN to=$CANDIDATE_VN firstInstallTime=preserved ime_selection=preserved"
    else
        result FAIL "api.$api.upgrade" "first=$old_first/$new_first versionCode=$new_vc/$CANDIDATE_VC ime=$current_ime/$old_ime"
        return 1
    fi

    if ANDROID_SERIAL="$SERIAL" ADB="$ADB" bash "$ROOT/scripts/emulator-smoke.sh" \
            --no-boot --apk "$CANDIDATE" --outdir "$directory/smoke" \
            >"$directory/smoke-driver.log" 2>&1; then
        local smoke_skips
        smoke_skips=$(critical_smoke_skips "$directory/smoke/result.txt")
        if [ -n "$smoke_skips" ]; then
            printf '%s\n' "$smoke_skips"
            result FAIL "api.$api.tatar-input" \
                "critical suggestion checks skipped in smoke: $(awk -F'|' '{print $3}' <<<"$smoke_skips" | paste -sd, -)"
        else
            result PASS "api.$api.tatar-input" "upgraded release smoke passed; see smoke/result.txt"
        fi
    else
        result FAIL "api.$api.tatar-input" "upgraded release smoke failed; see smoke-driver.log"
    fi
    cat "$directory/smoke-driver.log"

    # Instrumentation uses the distinct debug application id. Clearing it makes
    # backup/corruption probes independent of prior AVD runs.
    if ! install_apk "$DEBUG_APK" "$directory/install-debug.log" \
            || ! install_apk "$ANDROIDTEST_APK" "$directory/install-androidtest.log"; then
        result FAIL "api.$api.instrumentation-install" "see install-debug.log/install-androidtest.log"
        return 1
    fi
    SHELL pm clear "$DEBUG_PKG" >/dev/null 2>&1 || true
    local debug_ime
    debug_ime=""
    # Package manager registration is asynchronous on API 33: the install is
    # successful before `ime list` shows the new package. Poll as for the
    # release package above instead of failing the leg immediately.
    for _ in $(seq 1 15); do
        debug_ime=$(SHELL ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$DEBUG_PKG/" | head -1)
        [ -n "$debug_ime" ] && break
        sleep 2
    done
    if [ -z "$debug_ime" ]; then
        result FAIL "api.$api.instrumentation-install" "debug package has no listed IME after 30 seconds"
        return 1
    fi
    SHELL ime enable "$debug_ime" >/dev/null 2>&1 || true
    SHELL ime set "$debug_ime" >/dev/null 2>&1 || true
    instrument "$directory" backup-restore.log \
        rkr.simplekeyboard.inputmethod.latin.settings.backup.BackupRestoreDeviceTest \
        "api.$api.backup-recovery" || return 1
    instrument "$directory" field-switch.log \
        rkr.simplekeyboard.inputmethod.latin.PasswordFieldSwitchDeviceTest \
        "api.$api.field-switch" || return 1

    SHELL pm clear "$DEBUG_PKG" >/dev/null 2>&1 || true
    SHELL ime enable "$debug_ime" >/dev/null 2>&1 || true
    SHELL ime set "$debug_ime" >/dev/null 2>&1 || true
    local prefs personal_dir corrupt_file quarantine found dump bounds x y crash crash_rc keyboard_shown
    prefs="/data/user_de/0/$DEBUG_PKG/shared_prefs/${DEBUG_PKG}_preferences.xml"
    personal_dir="/data/data/$DEBUG_PKG/no_backup/personal"
    corrupt_file="$personal_dir/personal-tt_RU-s1-f1.tpers"
    quarantine="$corrupt_file.quarantine"
    # The corrupt fixture belongs to tt_RU. Select that subtype explicitly so
    # every API opens the seeded store regardless of the AVD's persisted locale.
    cat >"$directory/corruption-prefs.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="pref_tatar_suggestions" value="true" />
    <boolean name="pref_personal_dictionary" value="true" />
    <string name="pref_current_subtype">tt_RU:tatar</string>
</map>
EOF
    printf 'invalid personal dictionary fixture\n' >"$directory/corrupt.tpers"
    if ! A shell "run-as $DEBUG_PKG sh -c 'mkdir -p \$(dirname $prefs) && cat > $prefs'" \
            <"$directory/corruption-prefs.xml" >/dev/null 2>&1 \
            || ! A shell "run-as $DEBUG_PKG sh -c 'mkdir -p $personal_dir && cat > $corrupt_file'" \
            <"$directory/corrupt.tpers" >/dev/null 2>&1; then
        result FAIL "api.$api.corruption" "run-as could not seed the debug personal store"
        return 1
    fi
    SHELL logcat -b crash -c >/dev/null 2>&1 || true
    SHELL am force-stop "$DEBUG_PKG" >/dev/null 2>&1 || true
    # Setting the IME is asynchronous on old images: without this wait the
    # activity renders the wizard instead of the done block, which has no
    # try-it field. The same AVD takes several seconds after `ime set`.
    local debug_current
    debug_current=""
    for _ in $(seq 1 15); do
        SHELL ime enable "$debug_ime" >/dev/null 2>&1 || true
        SHELL ime set "$debug_ime" >/dev/null 2>&1 || true
        debug_current=$(SHELL settings get secure default_input_method 2>/dev/null | tr -d '\r')
        [ "$debug_current" = "$debug_ime" ] && break
        sleep 1
    done
    if [ "$debug_current" != "$debug_ime" ]; then
        result FAIL "api.$api.corruption" "debug IME selection did not stick: '$debug_current' != '$debug_ime'"
        return 1
    fi
    # The API 37 image restarts system_server under load and the first `am
    # start` can then die with "Failure calling service activity: Broken pipe"
    # even though the activity is fine. Wait for the service and retry.
    local activity_started=0 activity_attempt
    for activity_attempt in 1 2 3; do
        for _ in $(seq 1 30); do
            if SHELL service check activity 2>/dev/null | grep -q found; then
                break
            fi
            sleep 2
        done
        if SHELL am start -W --activity-clear-task -n "$DEBUG_PKG/rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity" \
                >"$directory/corruption-activity.log" 2>&1; then
            activity_started=1
            break
        fi
        sleep 3
    done
    if [ "$activity_started" != 1 ]; then
        result FAIL "api.$api.corruption" "SetupActivity did not start"
        return 1
    fi
    sleep 2
    bounds=""
    dump=""
    local ui_tmp="/data/local/tmp/emulator-release-ui.xml" attempt
    for attempt in $(seq 1 15); do
        SHELL uiautomator dump "$ui_tmp" >"$directory/uiautomator-dump.log" 2>&1 || true
        dump=$(A exec-out cat "$ui_tmp" 2>/dev/null | tr -d '\r')
        printf '%s\n' "$dump" >"$directory/corruption-ui.xml"
        bounds=$(grep -oP '<node[^>]*setup_test_field[^>]*bounds="\[\K[0-9,\]\[]+' <<<"$dump" | head -1)
        [ -n "$bounds" ] && break
        # The field sits below the fold on a short viewport: scroll the setup
        # ScrollView toward the done block before the next dump.
        [ "$attempt" -ge 2 ] && SHELL input swipe 540 1700 540 500 400 >/dev/null 2>&1 || true
        sleep 1
    done
    if [ -z "$bounds" ]; then
        result FAIL "api.$api.corruption" "try-it field bounds unavailable after $attempt dumps; see corruption-ui.xml"
        return 1
    fi
    mapfile -t points < <(grep -oE '[0-9]+' <<<"$bounds")
    if [ "${#points[@]}" -ne 4 ]; then
        result FAIL "api.$api.corruption" "malformed try-it field bounds: $bounds"
        return 1
    fi
    x=$(((points[0] + points[2]) / 2))
    y=$(((points[1] + points[3]) / 2))
    SHELL input tap "$x" "$y" >/dev/null 2>&1 || true
    keyboard_shown=0
    for _ in $(seq 1 15); do
        if SHELL dumpsys input_method 2>/dev/null | grep -q 'mInputShown=true'; then
            keyboard_shown=1
            break
        fi
        # Older images can accept the field focus before the selected IME is
        # fully bound. Keep the same fresh-bounds tap as the initial focus,
        # but never let an absent keyboard masquerade as a product failure.
        SHELL input tap "$x" "$y" >/dev/null 2>&1 || true
        sleep 1
    done
    if [ "$keyboard_shown" != 1 ]; then
        # Without the keyboard the store is never exercised, so the crash buffer
        # and the IME state are the only evidence of why it stayed away; keep
        # both instead of failing blind.
        A logcat -b crash -d >"$directory/corruption-crash.log" 2>&1 || true
        SHELL dumpsys input_method >"$directory/corruption-imms.txt" 2>&1 || true
        result FAIL "api.$api.corruption" "debug IME did not show after 15 seconds; corruption store was not exercised"
        return 1
    fi
    found=0
    # /system/bin/test does not exist on the old images (API 24-29): a bare
    # `run-as <pkg> test -f <file>` fails there with "exec failed for test:
    # Permission denied" even when the file is present, which misreported a
    # correct quarantine as missing. Go through the shell builtin, exactly like
    # the original-file check below. The store opens on the IME worker after
    # the keyboard shows, so poll rather than sampling once.
    for _ in $(seq 1 45); do
        if A shell "run-as $DEBUG_PKG sh -c 'test -f $quarantine'" >/dev/null 2>&1; then
            found=1
            break
        fi
        sleep 1
    done
    A shell "run-as $DEBUG_PKG sh -c 'ls -la $personal_dir'" \
        >"$directory/corruption-store.log" 2>&1 || true
    crash=$(A logcat -b crash -d 2>&1)
    crash_rc=$?
    printf '%s\n' "$crash" >"$directory/corruption-crash.log"
    if [ "$found" = 1 ] && [ "$crash_rc" = 0 ] \
            && A shell "run-as $DEBUG_PKG sh -c 'test ! -e $corrupt_file'" >/dev/null 2>&1 \
            && ! grep -q 'FATAL EXCEPTION' <<<"$crash"; then
        result PASS "api.$api.corruption" "invalid .tpers quarantined; original moved; crash buffer empty"
    else
        result FAIL "api.$api.corruption" "quarantine=$found logcat_rc=$crash_rc fatal=$(grep -c 'FATAL EXCEPTION' <<<"$crash") see corruption-crash.log"
        return 1
    fi
    return 0
}

for i in "${!AVD_APIS[@]}"; do
    api_selected "${AVD_APIS[$i]}" || continue
    run_avd "${AVD_APIS[$i]}" "${AVD_NAMES[$i]}" || true
    cleanup
done
physical_boundaries
if [ -n "$ONLY_APIS" ]; then
    echo "EMULATOR|PARTIAL|apis=$ONLY_APIS|failures=$FAILURES" | tee -a "$RESULTS"
elif [ "$FAILURES" = 0 ]; then
    echo "EMULATOR|PASS|apis=$EXPECTED_MIN-$EXPECTED_TARGET" | tee -a "$RESULTS"
else
    echo "EMULATOR|FAIL|failures=$FAILURES" | tee -a "$RESULTS"
fi
printf 'RESULT|SUMMARY|fail=%s skip=%s p0_signoff=blocked|outdir=%s\n' \
    "$FAILURES" "$SKIPS" "$OUTDIR" | tee -a "$RESULTS"
if [ -n "$ONLY_APIS" ]; then
    echo "OVERALL|PARTIAL|apis=$ONLY_APIS|physical_only=direct-boot,lock-screen,talkback" | tee -a "$RESULTS"
else
    echo "OVERALL|INCOMPLETE|physical_only=direct-boot,lock-screen,talkback" | tee -a "$RESULTS"
fi
exit 1
