#!/usr/bin/env python3
"""Top-k accounting, paired bootstrap intervals, and gate-report validation.

The suggestion strip shows three cells by default. The engine composes four candidates; the
fourth is diagnostic and must never be reported as visible top-3. All two-arm intervals draw
the same record indices for both arms.

Commands:
  paired-ci INPUT.json       Compare per-record hit and denominator arrays.
  check-report REPORT.json  Validate top-k roles, denominators, pairing metadata, and
                            recomputed pct/pp rates.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Sequence

import suggest_chain

VISIBLE_TOP_K = 3
ENGINE_TOP_K = 4
BOOTSTRAP_METHOD = "paired-sentence-bootstrap"
SHARED_RESAMPLES = "shared-record-indices"


class EvaluationMetricError(ValueError):
    """An invalid metric, pairing, or gate report."""


def visible_top3(candidates: Sequence[str]) -> Sequence[str]:
    return candidates[:VISIBLE_TOP_K]


def engine_top4(candidates: Sequence[str]) -> Sequence[str]:
    return candidates[:ENGINE_TOP_K]


def _validate_series(
    baseline_hits: Sequence[int],
    baseline_denominators: Sequence[int],
    candidate_hits: Sequence[int],
    candidate_denominators: Sequence[int],
) -> None:
    lengths = {
        len(baseline_hits),
        len(baseline_denominators),
        len(candidate_hits),
        len(candidate_denominators),
    }
    if len(lengths) != 1:
        raise EvaluationMetricError("unpaired comparison: per-record array lengths differ")
    if not baseline_hits:
        raise EvaluationMetricError("comparison has no records")
    for arm, hits, denominators in (
        ("baseline", baseline_hits, baseline_denominators),
        ("candidate", candidate_hits, candidate_denominators),
    ):
        for index, (hit, denominator) in enumerate(zip(hits, denominators)):
            if not isinstance(hit, int) or isinstance(hit, bool) or hit < 0:
                raise EvaluationMetricError(f"{arm} hit at record {index} is not nonnegative")
            if (
                not isinstance(denominator, int)
                or isinstance(denominator, bool)
                or denominator < 0
            ):
                raise EvaluationMetricError(
                    f"{arm} denominator at record {index} is not nonnegative"
                )
            if hit > denominator:
                raise EvaluationMetricError(
                    f"{arm} hits exceed the denominator at record {index}"
                )
        if sum(denominators) <= 0:
            raise EvaluationMetricError(f"{arm} denominator total is zero")


def rate_ci95(
    hits: Sequence[int],
    denominators: Sequence[int],
    *,
    rounds: int = suggest_chain.BOOTSTRAP_ROUNDS,
    seed: int = suggest_chain.BOOTSTRAP_SEED,
) -> tuple[float, float]:
    _validate_series(hits, denominators, hits, denominators)
    return suggest_chain.bootstrap_rate_ci95(hits, denominators, rounds=rounds, seed=seed)


def paired_delta_ci95(
    baseline_hits: Sequence[int],
    baseline_denominators: Sequence[int],
    candidate_hits: Sequence[int],
    candidate_denominators: Sequence[int],
    *,
    rounds: int = suggest_chain.BOOTSTRAP_ROUNDS,
    seed: int = suggest_chain.BOOTSTRAP_SEED,
) -> tuple[float, float]:
    """Return the candidate-minus-baseline percentage-point CI95 over shared resamples."""
    _validate_series(
        baseline_hits, baseline_denominators, candidate_hits, candidate_denominators
    )
    if rounds < 100:
        raise EvaluationMetricError("bootstrap rounds must be at least 100")
    size = len(baseline_denominators)
    stream = suggest_chain.splitmix64_index_stream(size, seed)
    deltas: list[float] = []
    for _round in range(rounds):
        baseline_numerator = baseline_weight = 0
        candidate_numerator = candidate_weight = 0
        for _record in range(size):
            drawn = next(stream)
            baseline_numerator += baseline_hits[drawn]
            baseline_weight += baseline_denominators[drawn]
            candidate_numerator += candidate_hits[drawn]
            candidate_weight += candidate_denominators[drawn]
        if baseline_weight == 0 or candidate_weight == 0:
            raise EvaluationMetricError("a bootstrap resample has a zero denominator")
        deltas.append(
            candidate_numerator * 100.0 / candidate_weight
            - baseline_numerator * 100.0 / baseline_weight
        )
    deltas.sort()
    return (
        deltas[suggest_chain.nearest_rank_index(25, rounds)],
        deltas[suggest_chain.nearest_rank_index(975, rounds)],
    )


def _arm_report(
    hits: Sequence[int], denominators: Sequence[int], *, rounds: int, seed: int
) -> dict[str, object]:
    numerator = sum(hits)
    denominator = sum(denominators)
    low, high = rate_ci95(hits, denominators, rounds=rounds, seed=seed)
    return {
        "hits": numerator,
        "denominator": denominator,
        "pct": round(numerator * 100.0 / denominator, 6),
        "ci95": [round(low, 6), round(high, 6)],
    }


def paired_metric_report(
    baseline_hits: Sequence[int],
    baseline_denominators: Sequence[int],
    candidate_hits: Sequence[int],
    candidate_denominators: Sequence[int],
    *,
    k: int,
    role: str,
    rounds: int = suggest_chain.BOOTSTRAP_ROUNDS,
    seed: int = suggest_chain.BOOTSTRAP_SEED,
) -> dict[str, object]:
    if (k, role) not in ((VISIBLE_TOP_K, "user"), (ENGINE_TOP_K, "diagnostic")):
        raise EvaluationMetricError("metric must be visible top-3/user or engine top-4/diagnostic")
    _validate_series(
        baseline_hits, baseline_denominators, candidate_hits, candidate_denominators
    )
    baseline = _arm_report(baseline_hits, baseline_denominators, rounds=rounds, seed=seed)
    candidate = _arm_report(candidate_hits, candidate_denominators, rounds=rounds, seed=seed)
    low, high = paired_delta_ci95(
        baseline_hits,
        baseline_denominators,
        candidate_hits,
        candidate_denominators,
        rounds=rounds,
        seed=seed,
    )
    return {
        "k": k,
        "role": role,
        "baseline": baseline,
        "candidate": candidate,
        "delta": {
            "pp": round(candidate["pct"] - baseline["pct"], 6),
            "ci95": [round(low, 6), round(high, 6)],
            "paired": True,
            "method": BOOTSTRAP_METHOD,
            "resampling": SHARED_RESAMPLES,
            "rounds": rounds,
        },
    }


def measurement_report(
    *,
    units: int,
    denominator_name: str,
    visible_top3_report: dict[str, object],
    engine_top4_report: dict[str, object],
) -> dict[str, object]:
    return {
        "unit": "sentence",
        "units": units,
        "denominator_name": denominator_name,
        "gate_metric": "visible_top3",
        "metrics": {
            "visible_top3": visible_top3_report,
            "engine_top4": engine_top4_report,
        },
    }


def _finite_interval(value: object, where: str) -> None:
    if (
        not isinstance(value, list)
        or len(value) != 2
        or not all(isinstance(bound, (int, float)) and math.isfinite(bound) for bound in value)
        or value[0] > value[1]
    ):
        raise EvaluationMetricError(f"{where} is not an ordered finite CI95")


def _validate_arm(value: object, where: str) -> float:
    """Validate one arm; return the recomputed pct (raises on any mismatch)."""
    if not isinstance(value, dict):
        raise EvaluationMetricError(f"{where} is missing")
    hits = value.get("hits")
    denominator = value.get("denominator")
    if not isinstance(hits, int) or isinstance(hits, bool) or hits < 0:
        raise EvaluationMetricError(f"{where}.hits is invalid")
    if not isinstance(denominator, int) or isinstance(denominator, bool) or denominator <= 0:
        raise EvaluationMetricError(f"{where}.denominator is missing or invalid")
    if hits > denominator:
        raise EvaluationMetricError(f"{where}.hits exceeds its denominator")
    pct = value.get("pct")
    if not isinstance(pct, (int, float)) or isinstance(pct, bool) or not math.isfinite(pct):
        raise EvaluationMetricError(f"{where}.pct is missing or invalid")
    expected_pct = round(hits * 100.0 / denominator, 6)
    if pct != expected_pct:
        raise EvaluationMetricError(
            f"stale {where}.pct {pct!r}: recomputed {expected_pct!r} from hits/denominator"
        )
    _finite_interval(value.get("ci95"), f"{where}.ci95")
    return expected_pct


def validate_gate_report(report: object) -> None:
    """Reject wrong top-k labels, implicit denominators, unpaired deltas, and stale rates.

    pct and the candidate-minus-baseline pp are recomputed from hits and denominators, so a
    hand-edited or stale report (for example hits=2, denominator=8, pct=100) fails instead of
    passing on its shape alone.
    """
    if not isinstance(report, dict):
        raise EvaluationMetricError("report is not a JSON object")
    measurement = report.get("measurement")
    if not isinstance(measurement, dict):
        raise EvaluationMetricError("measurement metadata is missing")
    if measurement.get("gate_metric") != "visible_top3":
        raise EvaluationMetricError("gate metric is not visible top-3")
    if not isinstance(measurement.get("units"), int) or measurement["units"] <= 0:
        raise EvaluationMetricError("paired record count is missing")
    if not measurement.get("denominator_name"):
        raise EvaluationMetricError("denominator name is missing")
    metrics = measurement.get("metrics")
    if not isinstance(metrics, dict):
        raise EvaluationMetricError("measurement metrics are missing")
    for name, k, role in (
        ("visible_top3", VISIBLE_TOP_K, "user"),
        ("engine_top4", ENGINE_TOP_K, "diagnostic"),
    ):
        metric = metrics.get(name)
        if not isinstance(metric, dict):
            raise EvaluationMetricError(f"{name} metric is missing")
        if metric.get("k") != k or metric.get("role") != role:
            raise EvaluationMetricError(f"wrong top-k accounting for {name}")
        baseline_pct = _validate_arm(metric.get("baseline"), f"{name}.baseline")
        candidate_pct = _validate_arm(metric.get("candidate"), f"{name}.candidate")
        delta = metric.get("delta")
        if not isinstance(delta, dict):
            raise EvaluationMetricError(f"{name} comparison is missing")
        if (
            delta.get("paired") is not True
            or delta.get("method") != BOOTSTRAP_METHOD
            or delta.get("resampling") != SHARED_RESAMPLES
        ):
            raise EvaluationMetricError(f"unpaired comparison for {name}")
        pp = delta.get("pp")
        if not isinstance(pp, (int, float)) or isinstance(pp, bool) or not math.isfinite(pp):
            raise EvaluationMetricError(f"{name}.delta.pp is missing or invalid")
        expected_pp = round(candidate_pct - baseline_pct, 6)
        if pp != expected_pp:
            raise EvaluationMetricError(
                f"stale {name}.delta.pp {pp!r}: candidate-minus-baseline is {expected_pp!r}"
            )
        _finite_interval(delta.get("ci95"), f"{name}.delta.ci95")


def _load_json(path: Path) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise EvaluationMetricError(f"cannot read {path}: {error}") from error


def _paired_ci_command(path: Path) -> dict[str, object]:
    data = _load_json(path)
    if not isinstance(data, dict):
        raise EvaluationMetricError("input is not a JSON object")
    try:
        baseline = data["baseline"]
        candidate = data["candidate"]
        result = paired_metric_report(
            baseline["hits"],
            baseline["denominators"],
            candidate["hits"],
            candidate["denominators"],
            k=data.get("k", VISIBLE_TOP_K),
            role=data.get("role", "user"),
            rounds=data.get("rounds", suggest_chain.BOOTSTRAP_ROUNDS),
            seed=data.get("seed", suggest_chain.BOOTSTRAP_SEED),
        )
    except (KeyError, TypeError) as error:
        raise EvaluationMetricError(f"invalid paired input: {error}") from error
    return result


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)
    paired = subparsers.add_parser("paired-ci")
    paired.add_argument("input", type=Path)
    check = subparsers.add_parser("check-report")
    check.add_argument("report", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "paired-ci":
            print(json.dumps(_paired_ci_command(args.input), indent=2, sort_keys=True))
        else:
            validate_gate_report(_load_json(args.report))
            print("PASS: visible top-3, diagnostic engine top-4, denominators, and pairing")
    except EvaluationMetricError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
