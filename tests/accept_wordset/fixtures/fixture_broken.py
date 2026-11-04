#!/usr/bin/env python3
"""Fixture for a step that breaks its output contract: logs argv, prints no JSON."""
import sys

from fixturelib import log_argv


def main() -> int:
    log_argv()
    sys.stdout.write("not a json report\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
