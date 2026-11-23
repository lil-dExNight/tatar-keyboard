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

# The wrapper JAR is git-ignored, so the dirty-tree check cannot see a replaced copy. CI restores
# it from the pinned distribution and checks this digest; local release commands must do the same
# before the first ./gradlew call.
TT_GRADLE_WRAPPER_SHA256="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"

tt_check_gradle_wrapper() {
    local jar="gradle/wrapper/gradle-wrapper.jar"
    # A checkout without wrapper metadata cannot run Gradle at all; leave that failure to the
    # wrapper. The test fixtures use a stub gradlew and have no wrapper metadata.
    if [ ! -f "gradle/wrapper/gradle-wrapper.properties" ]; then
        return 0
    fi
    if [ ! -f "$jar" ]; then
        echo "ERROR: не найден $jar; восстановите его из gradle-wrapper.properties" >&2
        return 1
    fi
    if ! printf '%s  %s\n' "$TT_GRADLE_WRAPPER_SHA256" "$jar" | sha256sum -c - >/dev/null 2>&1; then
        echo "ERROR: SHA-256 $jar не совпадает с закреплённым значением" >&2
        return 1
    fi
}
