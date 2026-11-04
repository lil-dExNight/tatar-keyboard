#!/usr/bin/env python3
"""Fixture for scripts/dictionary_pack.py query-audit: logs argv, prints canned TSV rows."""
import sys

from fixturelib import log_argv


def main() -> int:
    log_argv()
    sys.stdout.write("prefix\tcandidates\n"
                     "мәх\tмәхәббәт,мәхбүс\n"
                     "бәх\tбәхет,бәхетле\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
