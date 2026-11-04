#!/usr/bin/env python3
"""Fixture for scripts/dict_accept.py select: logs argv, prints a canned select report."""
import json
import sys

from fixturelib import log_argv


def main() -> int:
    log_argv()
    json.dump({"languages": {"tat": {"accepted": 2, "rejected": 1},
                             "rus": {"accepted": 3, "rejected": 4}}}, sys.stdout)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
