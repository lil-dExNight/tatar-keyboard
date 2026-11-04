"""Shared helper for the accept_wordset fixture scripts: log the invocation argv.

Each fixture appends its sys.argv as one JSON line to the file named by the environment
variable, so the tests can assert the step order and the arguments the runner passed.
"""
import json
import os
import sys

LOG_ENV = "ACCEPT_WORDSET_TEST_ARGV_LOG"


def log_argv() -> None:
    path = os.environ.get(LOG_ENV)
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(sys.argv) + "\n")
