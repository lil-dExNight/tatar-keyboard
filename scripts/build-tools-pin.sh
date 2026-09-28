# shellcheck shell=bash
# B8 (2026-09-28): single source of truth for the pinned Android build-tools version.
# Sourced (not executed) by every script that resolves tools under $SDK_ROOT/build-tools:
#   - scripts/release_pack.sh — artifact-PRODUCING consumer: it MUST use the pin and
#     fail loudly when the pinned directory is absent; a silent switch to another
#     zipalign/apksigner build would change the APK bytes. Bumping this pin is a
#     deliberate step: re-measure the release artifact SHA-256 (see the pin comment
#     in release_pack.sh).
#   - scripts/release_check.sh, scripts/check-no-internet.sh, scripts/emulator-smoke.sh —
#     READ-ONLY consumers (aapt2 dump / apksigner verify): they prefer the pinned
#     directory and may fall back to the newest installed build-tools when the pin is
#     absent, because dump/verify output is stable across build-tools versions.
TT_BUILD_TOOLS_PIN="37.0.0"
