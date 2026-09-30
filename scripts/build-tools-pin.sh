# shellcheck shell=bash
# Single source of the pinned Android build-tools version.
# Sourced (not executed) by every script that resolves tools under $SDK_ROOT/build-tools:
#   - scripts/release_pack.sh produces the release artifact, so it must use the pin and
#     fail when the pinned directory is absent: a different zipalign/apksigner build
#     would change the APK bytes. After bumping the pin, re-measure the release
#     artifact SHA-256 (see the pin comment in release_pack.sh).
#   - scripts/release_check.sh, scripts/check-no-internet.sh, scripts/emulator-smoke.sh
#     only read (aapt2 dump / apksigner verify): they prefer the pinned directory and
#     fall back to the newest installed build-tools, because dump/verify output is
#     stable across versions.
TT_BUILD_TOOLS_PIN="37.0.0"
