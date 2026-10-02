#!/usr/bin/env bash
# Runs the python packer tests (tests/*/test_*.py): plain unittest modules, one process per
# file, in parallel. CI, scripts/release_check.sh and the local gate loop all call this, so
# the file set and the failure semantics live in one place. Exit: 0 when every module is
# green; xargs exits nonzero (123) when any module fails.
set -uo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$SCRIPT_DIR/.."

shopt -s nullglob
files=(tests/*/test_*.py)
if [ ${#files[@]} -eq 0 ]; then
    echo "ERROR: tests/*/test_*.py не найдены" >&2
    exit 2
fi

# TT_PYTHON_TEST_JOBS overrides the parallelism width.
printf '%s\n' "${files[@]}" | xargs -P "${TT_PYTHON_TEST_JOBS:-8}" -n 1 python3
