# shellcheck shell=bash
# Shared verdicts for `am instrument -r` logs. Sourced by scripts that run
# instrumentation and report RESULT lines: a zero runner exit code and the final
# INSTRUMENTATION_CODE do not prove that every test passed.

instrumentation_log_failed() { # instrumentation_log_failed <log> -> 0 when a test failed
    local output="$1"
    grep -Eq '^INSTRUMENTATION_STATUS_CODE: -2([[:space:]]|$)' "$output" && return 0
    grep -Eq '^FAILURES!!!([[:space:]]|$)' "$output" && return 0
    grep -Eq 'Failures:[[:space:]]*[1-9][0-9]*([^0-9]|$)' "$output" && return 0
    return 1
}

instrumentation_log_succeeded() { # instrumentation_log_succeeded <log> -> 0 when the run passed
    local output="$1"
    grep -Eq '^INSTRUMENTATION_CODE: -1([[:space:]]|$)' "$output" || return 1
    ! instrumentation_log_failed "$output"
}
