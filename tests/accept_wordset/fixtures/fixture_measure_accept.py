#!/usr/bin/env python3
"""Fixture for research/corpus/measure_accept.py: logs argv, prints a canned coverage report."""
import json
import sys

from fixturelib import log_argv


def main() -> int:
    log_argv()
    json.dump({"language": sys.argv[1],
               "coverage_shipped_pct": 91.0,
               "coverage_accepted_pct": 91.5,
               "gain_accepted_pp": 0.5}, sys.stdout)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
