# shellcheck shell=bash
# The tracked paths whose content can change the release APK bytes. Sourced (not executed) by
# scripts/release_pack.sh, which refuses to pack while any of them differs from HEAD or is
# untracked, and by scripts/release_check.sh (artifact.freshness), which binds the APK's recorded
# commit to HEAD through them. Documents, store texts, tests of the pipeline and research files
# are outside the list: a commit that changes only those keeps a packed APK valid.
# shellcheck disable=SC2034
TT_BUILD_INPUTS=(
    app
    baselineprofile
    gradle
    build.gradle
    settings.gradle
    gradle.properties
    gradlew
    gradlew.bat
    scripts/release_pack.sh
    scripts/build-tools-pin.sh
)

# tt_dirty_build_inputs: prints the changes not in HEAD (tracked or untracked) under the build inputs, one
# `git status --porcelain` line each; prints nothing for a clean tree.
tt_dirty_build_inputs() {
    git status --porcelain --untracked-files=normal -- "${TT_BUILD_INPUTS[@]}"
}
