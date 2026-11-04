#!/usr/bin/env python3
"""Fixture for the gate check: logs argv, prints a rejecting gate report."""
import json
import sys

from fixturelib import log_argv


def main() -> int:
    log_argv()
    json.dump({"coverage_delta_pp": 0.1,
               "coverage_gate_passed": False,
               "chain_top3_delta_pp": 0.2,
               "chain_top3_delta_ci95": [-0.4, 0.8],
               "gate_passed": False}, sys.stdout)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
