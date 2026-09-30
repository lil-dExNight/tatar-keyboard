#!/usr/bin/env bash
# Checks two privacy properties on the built APK, not only in source:
#   (1) the app never requests android.permission.INTERNET;
#   (2) backup is a whitelist: android:allowBackup="false", and the dataExtractionRules
#       file it references has no <include> element and excludes every app data domain
#       in both sections (cloud-backup and device-transfer).
#
# dataExtractionRules must stay: on Android 12+ allowBackup="false" stops cloud backup
# but not device-to-device transfer; only the <device-transfer> section closes that.
# android:fullBackupContent is dead with allowBackup=false (Auto Backup never runs), so
# its presence is an error.
#
# Level 1: grep the source manifest for INTERNET (instant, no toolchain needed).
# Level 2: aapt2 on the built APK (authoritative, sees the merged manifest):
#   - INTERNET: aapt2 dump permissions.
#   - Backup:   aapt2 dump xmltree of the in-APK manifest gives the actual allowBackup
#               value and the dataExtractionRules resource id; the rules file is then
#               extracted through that id (release resource shrinking obfuscates its
#               path) and checked. <application> attributes are subject to manifest
#               merging and tools:replace, and a backup mistake is invisible until the
#               user finds their data on a new phone.
# Usage: check-no-internet.sh [path/to.apk]  (default: debug APK)
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
MANIFEST="app/src/main/AndroidManifest.xml"

# Domains every backup rule section must exclude whole (regular + device-protected:
# files, SharedPreferences, databases, external storage). Kept in lockstep with
# res/xml/data_extraction_rules.xml.
BACKUP_DOMAINS=(file database sharedpref external device_file device_database device_sharedpref)

# Level 2 for backup: prove on the built APK that backup is a whitelist.
# Args: <apk> <aapt2>
check_backup_apk() {
    local apk="$1" aapt2="$2"
    local manifest_tree allow_line der_id fbc_id res_dump

    manifest_tree=$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")

    # android:allowBackup must be present and false. Print the raw line as the proof.
    allow_line=$(grep -F ":allowBackup(" <<<"$manifest_tree" || true)
    if [ -z "$allow_line" ]; then
        echo "ERROR: android:allowBackup absent from built manifest in $apk (default is true)" >&2
        exit 1
    fi
    echo "Backup: raw manifest line -> ${allow_line#"${allow_line%%[![:space:]]*}"}"
    if ! grep -qE ":allowBackup\(0x[0-9a-fA-F]+\)=false([[:space:]].*)?$" <<<"$allow_line"; then
        echo "ERROR: android:allowBackup is not false in built APK $apk" >&2
        exit 1
    fi

    # dataExtractionRules must be referenced. Capture the resource id it points at.
    der_id=$(grep -F ":dataExtractionRules(" <<<"$manifest_tree" | sed -nE 's/.*=@(0x[0-9a-fA-F]+).*/\1/p' | head -1)
    if [ -z "$der_id" ]; then
        echo "ERROR: android:dataExtractionRules reference missing from built manifest in $apk" >&2
        echo "       (it is the only thing closing device-to-device transfer on API 31+; E2b-3)" >&2
        exit 1
    fi
    # fullBackupContent is dead once allowBackup is false (Auto Backup never runs);
    # it must not come back.
    fbc_id=$(grep -F ":fullBackupContent(" <<<"$manifest_tree" | sed -nE 's/.*=@(0x[0-9a-fA-F]+).*/\1/p' | head -1 || true)
    if [ -n "$fbc_id" ]; then
        echo "ERROR: android:fullBackupContent reference present in built manifest in $apk" >&2
        echo "       (dead with allowBackup=false; removed in phase 3a — remove it again)" >&2
        exit 1
    fi
    echo "Backup: manifest references dataExtractionRules=@$der_id (fullBackupContent absent as intended)"

    res_dump=$("$aapt2" dump resources "$apk")

    local der_file
    der_file=$(_resolve_res_file "$res_dump" "$der_id")
    if [ -z "$der_file" ]; then
        echo "ERROR: cannot resolve dataExtractionRules ($der_id) to a file inside $apk" >&2
        exit 1
    fi

    # data-extraction-rules (API 31+): both sections, whitelist, all domains excluded.
    _check_rules_tree "$apk" "$aapt2" "$der_file" "data-extraction-rules" \
        "cloud-backup device-transfer" 2

    echo "Level 2 OK: backup closed as a whitelist (allowBackup=false, dataExtractionRules with no <include>, all domains excluded) in $apk"
}

# Resolve a resource id (e.g. 0x7f110002) to the file path it maps to inside the APK.
# Args: <resources-dump> <id>
_resolve_res_file() {
    awk -v id="$2" '
        $1=="resource" && $2==id {found=1; next}
        found && /\(file\)/ {for (i=1;i<=NF;i++) if ($i=="(file)") {print $(i+1); exit}}
        found && $1=="resource" {exit}
    ' <<<"$1"
}

# Verify one backup rule file inside the APK is the whitelist edition.
# Args: <apk> <aapt2> <file-in-apk> <expected-root> <space-separated-sections> <per-domain-count>
_check_rules_tree() {
    local apk="$1" aapt2="$2" file="$3" root="$4" sections="$5" per="$6"
    local tree
    tree=$("$aapt2" dump xmltree --file "$file" "$apk")

    if ! grep -qF "E: $root" <<<"$tree"; then
        echo "ERROR: $file in $apk is not a <$root> document" >&2
        exit 1
    fi
    # Whitelist invariant: not one allowing element.
    if grep -qF "E: include" <<<"$tree"; then
        echo "ERROR: $file in $apk contains an <include> element — backup is not a whitelist" >&2
        exit 1
    fi
    # Every required section present.
    local section
    for section in $sections; do
        if ! grep -qF "E: $section" <<<"$tree"; then
            echo "ERROR: $file in $apk is missing the <$section> section" >&2
            exit 1
        fi
    done
    # Every data domain excluded, once per section.
    local domain got
    for domain in "${BACKUP_DOMAINS[@]}"; do
        got=$(grep -cF "domain=\"$domain\"" <<<"$tree" || true)
        if [ "$got" -ne "$per" ]; then
            echo "ERROR: $file in $apk excludes domain '$domain' $got time(s), expected $per" >&2
            exit 1
        fi
    done
}

# Level 1: source manifest (INTERNET, instant signal)
if grep -qF "android.permission.INTERNET" "$MANIFEST"; then
    echo "ERROR: android.permission.INTERNET found in $MANIFEST" >&2
    exit 1
fi
echo "Level 1 OK: no INTERNET in source manifest"

# Level 2: built APK (merged manifest)
if [ -f "$APK" ]; then
    SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$SDK_ROOT" ] || [ ! -d "$SDK_ROOT/build-tools" ]; then
        echo "ERROR: Android SDK build-tools not found (set ANDROID_HOME or ANDROID_SDK_ROOT)" >&2
        exit 1
    fi
    # Read-only use (aapt2 dump): prefer the pinned $TT_BUILD_TOOLS_PIN directory
    # (scripts/build-tools-pin.sh), else fall back to the newest installed version,
    # since dump output is stable across build-tools versions.
    source "$SCRIPT_DIR/build-tools-pin.sh"
    if [ -d "$SDK_ROOT/build-tools/$TT_BUILD_TOOLS_PIN" ]; then
        AAPT2=$(find "$SDK_ROOT/build-tools/$TT_BUILD_TOOLS_PIN" -name aapt2 | sort -V | tail -1)
    else
        AAPT2=$(find "$SDK_ROOT/build-tools" -name aapt2 | sort -V | tail -1)
    fi
    if [ -z "$AAPT2" ]; then
        echo "ERROR: aapt2 not found under $SDK_ROOT/build-tools" >&2
        exit 1
    fi
    PERMS=$("$AAPT2" dump permissions "$APK")
    echo "$PERMS"
    if grep -qF "android.permission.INTERNET" <<< "$PERMS"; then
        echo "ERROR: android.permission.INTERNET found in built APK $APK" >&2
        exit 1
    fi
    echo "Level 2 OK: no INTERNET in built APK"
    check_backup_apk "$APK" "$AAPT2"
else
    echo "WARNING: level 2 skipped (no APK at $APK)"
fi

echo "no INTERNET permission"
