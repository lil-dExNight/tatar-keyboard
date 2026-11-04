#!/usr/bin/env python3
"""Fixture for a failing step: logs argv, exits nonzero without output."""
import sys

from fixturelib import log_argv


def main() -> int:
    log_argv()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
