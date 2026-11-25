#!/usr/bin/env python3
"""Validate strict reference-device performance evidence without connecting to adb."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import re
import sys


DEFAULT_LEGS = "cold,pss,frames,warm,touch,suggest,first-suggestion,first-glide"
LEG_CHECKS = {
    "cold": ("cold-start",),
    "pss": ("pss",),
    "frames": ("frames", "jank", "frames-proof"),
    "warm": ("warm-show",),
    "touch": ("touch",),
    "suggest": ("suggest",),
    "first-suggestion": ("first-suggestion",),
    "first-glide": ("cold-first-glide", "warm-decode"),
    "spellcheck": ("spellcheck-warm", "spellcheck-cold", "spellcheck-memory"),
    "battery": ("battery.idle", "battery.screenoff", "battery.suggest-off", "battery.dealloc"),
    "uimode": ("uimode",),
    "fontscale": ("fontscale",),
}
BUDGETED = {
    "cold-start", "pss", "frames", "jank", "warm-show", "touch", "suggest", "first-suggestion",
    "cold-first-glide", "warm-decode",
    "spellcheck-warm", "spellcheck-cold", "spellcheck-memory",
}
# The reference-device touch budget is also checked against the measured p95, so a
# hand-edited over_budget=false cannot make an over-budget report pass.
TOUCH_BUDGET_MS = 6.0
# The PSS leg is one check line per scenario in device-perf-ritual.sh; strict
# evidence must cover all four of them, each with its own budget verdict.
PSS_SCENARIOS = ("keyboard-idle", "after-50-words", "emoji-panel-open", "idle-30s")
SKIPPED_SAMPLES = re.compile(r"(?:^|\s)skips=([1-9][0-9]*)(?:\s|$)")


def run_indexes(details: list[str]) -> set[str]:
    indexes: set[str] = set()
    for detail in details:
        run = parse_fields(detail).get("run")
        if run is not None:
            indexes.add(run)
    return indexes


def parse_fields(text: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for token in text.split():
        if "=" in token:
            key, value = token.split("=", 1)
            fields[key] = value
    return fields


def validate(path: Path, legs: list[str]) -> list[str]:
    issues: list[str] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as error:
        return [f"cannot read {path}: {error}"]

    statuses: Counter[str] = Counter()
    checks: dict[str, list[str]] = {}
    summaries: list[dict[str, str]] = []
    for number, line in enumerate(lines, 1):
        if not line.startswith("RESULT|"):
            continue
        parts = line.split("|")
        if len(parts) < 4:
            issues.append(f"line {number}: malformed RESULT")
            continue
        if parts[1] == "SUMMARY":
            summaries.append(parse_fields("|".join(parts[2:-1])))
            continue
        status, check = parts[1], parts[2]
        detail = "|".join(parts[3:])
        if status not in {"PASS", "FAIL", "SKIP", "INFO"}:
            issues.append(f"line {number}: unknown status {status!r}")
            continue
        statuses[status] += 1
        checks.setdefault(check, []).append(detail)
        if status == "FAIL":
            issues.append(f"line {number}: FAIL {check}: {detail}")
        elif status == "SKIP":
            issues.append(f"line {number}: SKIP {check}: {detail}")
        if SKIPPED_SAMPLES.search(detail):
            issues.append(f"line {number}: skipped samples in {check}: {detail}")
        fields = parse_fields(detail)
        if fields.get("over_budget") == "true":
            issues.append(f"line {number}: over budget {check}: {detail}")
        if status == "INFO" and check == "touch":
            try:
                p95 = float(fields["p95_ms"])
                budget = float(fields["budget_ms"])
            except (KeyError, ValueError):
                issues.append(f"line {number}: touch needs numeric p95_ms and budget_ms")
            else:
                if budget != TOUCH_BUDGET_MS:
                    issues.append(
                        f"line {number}: touch budget_ms={fields['budget_ms']}, expected 6"
                    )
                if p95 > TOUCH_BUDGET_MS:
                    issues.append(
                        f"line {number}: touch p95_ms={fields['p95_ms']} exceeds 6"
                    )

    if len(summaries) != 1:
        issues.append(f"expected one RESULT|SUMMARY line, found {len(summaries)}")
        return issues
    summary = summaries[0]
    if summary.get("strict") != "1":
        issues.append("report was not produced by --strict")
    if summary.get("p0_signoff") != "ready":
        issues.append(f"summary p0_signoff={summary.get('p0_signoff', 'missing')!r}, expected 'ready'")
    if summary.get("legs") != ",".join(legs):
        issues.append(f"summary legs={summary.get('legs', 'missing')!r}, expected {','.join(legs)!r}")
    for key, actual in (("fail", statuses["FAIL"]), ("skip", statuses["SKIP"])):
        if summary.get(key) != str(actual):
            issues.append(f"summary {key}={summary.get(key, 'missing')!r}, actual={actual}")
    sampled = sum(1 for details in checks.values() for detail in details if SKIPPED_SAMPLES.search(detail))
    if summary.get("sample_skips") != str(sampled):
        issues.append(f"summary sample_skips={summary.get('sample_skips', 'missing')!r}, actual={sampled}")

    for leg in legs:
        for check in LEG_CHECKS[leg]:
            details = checks.get(check, [])
            if not details:
                issues.append(f"required leg {leg} has no {check} result")
            elif check in BUDGETED and not any("over_budget=" in detail for detail in details):
                issues.append(f"required leg {leg} has no budgeted {check} result")

    if "pss" in legs:
        pss_details = checks.get("pss", [])
        for scenario in PSS_SCENARIOS:
            matching = [
                detail for detail in pss_details
                if parse_fields(detail).get("scenario") == scenario
            ]
            if not matching:
                issues.append(f"required pss scenario {scenario} has no result")
            elif not any("over_budget=" in detail for detail in matching):
                issues.append(f"required pss scenario {scenario} has no budgeted result")

    if "frames" in legs:
        # Jank evidence is complete only when every measured frame run has a
        # jank line and every jank line carries a budget verdict; a frames
        # run without its jank half proves nothing about the deadline misses.
        jank_details = checks.get("jank", [])
        missing_runs = sorted(
            run_indexes(checks.get("frames", [])) - run_indexes(jank_details),
            key=lambda value: (0, int(value)) if value.isdigit() else (1, value),
        )
        if missing_runs:
            issues.append(f"required leg frames is missing jank evidence for run {', '.join(missing_runs)}")
        unbudgeted = [detail for detail in jank_details if "over_budget=" not in detail]
        if unbudgeted:
            issues.append(f"required leg frames has {len(unbudgeted)} jank result(s) without over_budget=")
    return issues


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--legs", default=DEFAULT_LEGS)
    arguments = parser.parse_args(argv)
    legs = arguments.legs.split(",")
    unknown = [leg for leg in legs if leg not in LEG_CHECKS]
    if unknown or not all(legs) or len(legs) != len(set(legs)):
        parser.error(f"invalid --legs list: {arguments.legs}")

    issues = validate(arguments.report, legs)
    if issues:
        for issue in issues:
            print(f"RELEASE_PERF|FAIL|{issue}")
        return 1
    print(f"RELEASE_PERF|PASS|strict=1 skip=0 sample_skips=0 legs={arguments.legs}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
