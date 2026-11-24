#!/usr/bin/env python3
"""Fixture with a passing legacy gate and a rejected per-domain decision."""
import json
import sys

from fixturelib import log_argv


def main() -> int:
    log_argv()
    json.dump(
        {
            "coverage_delta_pp": 0.6,
            "coverage_gate_passed": True,
            "chain_top3_delta_pp": 1.1,
            "chain_top3_delta_ci95": [0.4, 1.8],
            "gate_passed": True,
            "domain_decision": {"acceptable": False},
        },
        sys.stdout,
    )
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
