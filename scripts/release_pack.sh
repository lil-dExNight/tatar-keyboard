#!/usr/bin/env bash
# Release packaging with zopfli recompression. Order matters: zipalign runs before
# signing, because the v2 signature covers the zip entry bytes and repacking a signed
# APK invalidates it. resources.arsc stays STORED (see step 1.5).
#
# Pipeline:
#   1. ./gradlew clean assembleRelease -PskipReleaseSigning --no-build-cache -> unsigned APK
#   2. zipalign -f -z 4                                      -> zopfli recompression + alignment
#   3. zipalign -c, resources.arsc STORED                    -> alignment and arsc checked
#   4. apksigner sign (keys from keystore.properties, v2 only, as in the AGP build)
#   5. apksigner verify --print-certs                        -> signature is valid
#
# Run from the repository root:
#   bash scripts/release_pack.sh [--no-sign] [output.apk]
# Default output: app/build/outputs/apk/release/app-release-zopfli.apk.
#
# The output is reproducible: the unsigned AGP build, zopfli (with the pinned build-tools)
# and apksigner v2 are all deterministic, so two runs on one tree give the same SHA-256.
# The script writes nothing outside build/ and app/build/.
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
cd "$REPO_ROOT"

LOG_DIR="build/release_pack"
mkdir -p "$LOG_DIR"

# --no-sign stops after alignment and leaves an aligned unsigned APK. CI (no keystore)
# uses it to pack twice and compare the bytes; local releases take the full signed path.
NO_SIGN=0
if [ "${1:-}" = "--no-sign" ]; then
    NO_SIGN=1
    shift
fi

OUT="${1:-app/build/outputs/apk/release/app-release-zopfli.apk}"

# Remove the output path up front. If it were a symlink, apksigner would write to its
# target; a stale file from an earlier run could be mistaken for a fresh result after a
# mid-pipeline failure. From here on each step either rewrites OUT or fails.
if [ -L "$OUT" ] || [ -e "$OUT" ]; then
    rm -f -- "$OUT"
fi

# --- SDK tools (zipalign, apksigner) ---------------------------------------------------

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK_ROOT/build-tools" ]; then
    echo "ERROR: Android SDK build-tools не найдены ($SDK_ROOT/build-tools);" >&2
    echo "       задайте ANDROID_HOME или ANDROID_SDK_ROOT" >&2
    exit 1
fi

resolve_tool() { # <name>
    local found
    # Tools come only from the pinned build-tools directory, so installing a newer SDK
    # package cannot change the APK bytes. TT_BUILD_TOOLS_VERSION overrides the pin (to
    # try an update); a missing directory is an error, never a fallback to another version.
    local pinned_dir="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
    if [ ! -d "$pinned_dir" ]; then
        echo "ERROR: запиннованные build-tools $BUILD_TOOLS_VERSION не найдены ($pinned_dir)." >&2
        echo "       Установите их (sdkmanager \"build-tools;$BUILD_TOOLS_VERSION\")" >&2
        echo "       или задайте TT_BUILD_TOOLS_VERSION=<версия> осознанно." >&2
        exit 1
    fi
    found=$(find "$pinned_dir" -maxdepth 2 -name "$1" -type f | sort -V | tail -1)
    if [ -z "$found" ]; then
        echo "ERROR: $1 не найден под $pinned_dir" >&2
        exit 1
    fi
    printf '%s' "$found"
}

# The pinned version lives in scripts/build-tools-pin.sh. Changing it means re-measuring the
# artifact SHA-256.
source "$SCRIPT_DIR/build-tools-pin.sh"
BUILD_TOOLS_VERSION="${TT_BUILD_TOOLS_VERSION:-$TT_BUILD_TOOLS_PIN}"

ZIPALIGN=$(resolve_tool zipalign)
APKSIGNER=$(resolve_tool apksigner)
echo "build-tools: $BUILD_TOOLS_VERSION (пин; переопределяется TT_BUILD_TOOLS_VERSION)"
echo "zipalign:  $ZIPALIGN"
echo "apksigner: $APKSIGNER"

# --- signing keys from keystore.properties (same convention as app/build.gradle) --------------

KS_PROPS="keystore.properties"
if [ "$NO_SIGN" = 0 ] && [ ! -f "$KS_PROPS" ]; then
    echo "ERROR: нет keystore.properties — подписывать нечем (unsigned-сборка и так доступна через gradle)" >&2
    exit 1
fi

ks_prop() { # <key>
    [ -f "$KS_PROPS" ] || return 0
    grep -E "^$1=" "$KS_PROPS" | head -1 | cut -d= -f2-
}
KS_FILE=$(ks_prop storeFile)
KS_ALIAS=$(ks_prop keyAlias)
KS_STORE_PASS=$(ks_prop storePassword)
KS_KEY_PASS=$(ks_prop keyPassword)
if [ "$NO_SIGN" = 0 ] && { [ -z "$KS_FILE" ] || [ -z "$KS_ALIAS" ]; }; then
    echo "ERROR: в keystore.properties нет storeFile/keyAlias" >&2
    exit 1
fi
# A relative storeFile resolves against app/ (like file() in app/build.gradle).
case "$KS_FILE" in
    /*) ;;
    *)  KS_FILE="app/$KS_FILE" ;;
esac
if [ "$NO_SIGN" = 0 ] && [ ! -f "$KS_FILE" ]; then
    echo "ERROR: keystore не найден: $KS_FILE" >&2
    exit 1
fi

# --- 1. unsigned release ---------------------------------------------------------------------

echo "== 1/5 clean assembleRelease -PskipReleaseSigning --no-build-cache =="
mkdir -p "$LOG_DIR"
./gradlew clean assembleRelease -PskipReleaseSigning --no-build-cache --console=plain \
    >"$LOG_DIR/assemble.log" 2>&1 || {
        echo "ERROR: сборка упала, лог $LOG_DIR/assemble.log" >&2
        tail -20 "$LOG_DIR/assemble.log" >&2 || true
        exit 1
    }
# gradle clean deletes the root build/ together with LOG_DIR; recreate it.
mkdir -p "$LOG_DIR"

UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$UNSIGNED" ]; then
    echo "ERROR: $UNSIGNED не появился (skipReleaseSigning не сработал?)" >&2
    exit 1
fi

# --- 1.5. resources.arsc stays STORED ----------------------------------------------------------
# Do not deflate resources.arsc. Android 11+ refuses to install an app targeting SDK 30+
# whose arsc is compressed (it is mmapped):
#   Failure [-124: ... Targeting R+ (version 30 and above) requires the resources.arsc of
#   installed APKs to be stored uncompressed and aligned on a 4-byte boundary]
# `zipalign -c 4` does not catch this (it prints "OK - compressed" and exits 0), so step 3
# checks it explicitly, and release_check.sh has the artifact.arsc_stored gate.

# --- 2. zipalign -z (zopfli) -----------------------------------------------------------------

echo "== 2/5 zipalign -z (zopfli) =="
ALIGNED="$LOG_DIR/app-release-zopfli-aligned.apk"
"$ZIPALIGN" -f -z 4 "$UNSIGNED" "$ALIGNED"

# --- 3. alignment check -----------------------------------------------------------------------

echo "== 3/5 zipalign -c + resources.arsc STORED =="
if "$ZIPALIGN" -c 4 "$ALIGNED" >"$LOG_DIR/zipalign-check.log" 2>&1; then
    echo "  выравнивание OK"
else
    echo "ERROR: выравнивание сломано, лог $LOG_DIR/zipalign-check.log" >&2
    exit 1
fi
# `zipalign -c` accepts a compressed resources.arsc ("OK - compressed", exit 0), so it does not
# catch an APK that Android 11+ refuses to install (see step 1.5). Check it explicitly here.
python3 - "$ALIGNED" <<'PYEOF'
import sys
import zipfile

apk = sys.argv[1]
info = zipfile.ZipFile(apk).getinfo('resources.arsc')
if info.compress_type != zipfile.ZIP_STORED:
    raise SystemExit(
        'resources.arsc is COMPRESSED — Android 11+ (targetSdk 30+) refuses to install such an '
        'APK: "requires the resources.arsc of installed APKs to be stored uncompressed and '
        'aligned on a 4-byte boundary"')
print(f'resources.arsc: STORED, {info.file_size} B')
PYEOF

# --- 4. signing (v2 only, as in the AGP build) -------------------------------------------------

if [ "$NO_SIGN" = 1 ]; then
    cp -- "$ALIGNED" "$OUT"
    SIZE_UNSIGNED=$(stat -c %s "$UNSIGNED")
    SIZE_OUT=$(stat -c %s "$OUT")
    SHA_OUT=$(sha256sum "$OUT" | awk '{print $1}')
    echo "== --no-sign: остановка после выравнивания =="
    echo "unsigned:        $SIZE_UNSIGNED Б"
    echo "aligned (zopfli): $SIZE_OUT Б"
    echo "sha256:          $SHA_OUT"
    echo "RESULT|OK|pack_unsigned|$OUT|$SIZE_OUT|$SHA_OUT"
    exit 0
fi

echo "== 4/5 apksigner sign =="
# Passwords go through env: rather than pass: in argv, because any user on the host can
# read a process's command line via ps, but not its environment.
KS_STORE_PASS="$KS_STORE_PASS" KS_KEY_PASS="$KS_KEY_PASS" \
"$APKSIGNER" sign \
    --ks "$KS_FILE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass env:KS_STORE_PASS --key-pass env:KS_KEY_PASS \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false \
    --out "$OUT" "$ALIGNED"

# --- 5. verification ---------------------------------------------------------------------------

echo "== 5/5 apksigner verify =="
if ! "$APKSIGNER" verify --print-certs "$OUT" | tee "$LOG_DIR/verify.log"; then
    echo "ERROR: подпись не верифицируется" >&2
    exit 1
fi

SIZE_UNSIGNED=$(stat -c %s "$UNSIGNED")
SIZE_OUT=$(stat -c %s "$OUT")
SHA_OUT=$(sha256sum "$OUT" | awk '{print $1}')
echo
echo "RESULT|unsigned|$SIZE_UNSIGNED"
echo "RESULT|zopfli+signed|$SIZE_OUT"
printf 'RESULT|delta|%+d Б\n' "$((SIZE_OUT - SIZE_UNSIGNED))"
echo "RESULT|sha256|$SHA_OUT"
echo "RESULT|out|$OUT"
