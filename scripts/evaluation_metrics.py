#!/usr/bin/env python3
"""Top-k accounting, paired bootstrap intervals, and gate-report validation.

The suggestion strip shows three cells by default. The engine composes four candidates; the
fourth is diagnostic and must never be reported as visible top-3. All two-arm intervals draw
the same record indices for both arms.

Commands:
  paired-ci INPUT.json       Compare per-record hit and denominator arrays.
  check-report REPORT.json  Validate top-k roles, denominators, pairing metadata, and
                            recomputed pct/pp rates.
  decide-domains BASELINE.json CANDIDATE.json
                            Apply the per-domain candidate decision rule.
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
DOMAIN_METRICS = ("coverage", "completion_cp3", "next_word")
NATIVE_CHAT_DOMAIN = "native_chat"


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


def series_summary(
    hits: Sequence[int],
    denominators: Sequence[int],
    *,
    rounds: int = suggest_chain.BOOTSTRAP_ROUNDS,
    seed: int = suggest_chain.BOOTSTRAP_SEED,
) -> dict[str, object]:
    """Summarize one bounded per-record metric with its CI95 and normal-approximation MDE."""
    _validate_series(hits, denominators, hits, denominators)
    numerator = sum(hits)
    denominator = sum(denominators)
    low, high = rate_ci95(hits, denominators, rounds=rounds, seed=seed)
    pct = numerator * 100.0 / denominator
    return {
        "hits": numerator,
        "denominator": denominator,
        "pct": round(pct, 6),
        "ci95": [round(low, 6), round(high, 6)],
        "mde_pp": round(
            suggest_chain.minimum_detectable_effect_pp(numerator / denominator, denominator),
            6,
        ),
    }


def _domain_series(
    report: dict[str, object], required_metrics: Sequence[str]
) -> dict[str, dict[str, object]]:
    domains = report.get("domains")
    if not isinstance(domains, list) or not domains:
        raise EvaluationMetricError("domain report has no domains")
    result: dict[str, dict[str, object]] = {}
    for domain in domains:
        if not isinstance(domain, dict) or not isinstance(domain.get("id"), str):
            raise EvaluationMetricError("domain report contains an invalid domain")
        identifier = domain["id"]
        if identifier in result:
            raise EvaluationMetricError(f"duplicate domain {identifier}")
        series = domain.get("series")
        digest = domain.get("input_sha256")
        if not isinstance(series, dict) or not isinstance(digest, str) or len(digest) != 64:
            raise EvaluationMetricError(f"domain {identifier} has no series or input hash")
        for name in required_metrics:
            values = series.get(name)
            if not isinstance(values, dict):
                raise EvaluationMetricError(f"domain {identifier} has no {name} series")
            hits = values.get("hits")
            denominators = values.get("denominators")
            if not isinstance(hits, list) or not isinstance(denominators, list):
                raise EvaluationMetricError(f"domain {identifier} has invalid {name} arrays")
            _validate_series(hits, denominators, hits, denominators)
        result[identifier] = domain
    return result


def decide_domain_candidate(
    baseline_report: dict[str, object],
    candidate_report: dict[str, object],
    *,
    rounds: int = suggest_chain.BOOTSTRAP_ROUNDS,
    seed: int = suggest_chain.BOOTSTRAP_SEED,
    metrics: Sequence[str] = DOMAIN_METRICS,
) -> dict[str, object]:
    """Apply the per-domain asset rule to candidate-minus-baseline paired measurements.

    Native chat requires a nonnegative point delta and a nonnegative paired CI95 lower bound.
    Each other domain permits a loss no larger than its baseline MDE. Without native chat the
    same secondary-domain rule applies and the result is explicitly marked as a fallback.
    """
    if not metrics or any(name not in DOMAIN_METRICS for name in metrics):
        raise EvaluationMetricError("unknown or empty domain metric selection")
    if len(set(metrics)) != len(metrics):
        raise EvaluationMetricError("duplicate domain metric selection")
    baseline_domains = _domain_series(baseline_report, metrics)
    candidate_domains = _domain_series(candidate_report, metrics)
    if set(baseline_domains) != set(candidate_domains):
        raise EvaluationMetricError("baseline and candidate domain sets differ")
    native_present = NATIVE_CHAT_DOMAIN in baseline_domains
    comparisons: dict[str, object] = {}
    failures: list[str] = []
    for identifier in baseline_domains:
        baseline_domain = baseline_domains[identifier]
        candidate_domain = candidate_domains[identifier]
        if baseline_domain["input_sha256"] != candidate_domain["input_sha256"]:
            raise EvaluationMetricError(f"domain {identifier} uses different evaluation inputs")
        metric_results: dict[str, object] = {}
        for name in metrics:
            baseline = baseline_domain["series"][name]
            candidate = candidate_domain["series"][name]
            baseline_hits = baseline["hits"]
            baseline_denominators = baseline["denominators"]
            candidate_hits = candidate["hits"]
            candidate_denominators = candidate["denominators"]
            baseline_pct = (
                sum(baseline_hits) * 100.0 / sum(baseline_denominators)
            )
            candidate_pct = (
                sum(candidate_hits) * 100.0 / sum(candidate_denominators)
            )
            low, high = paired_delta_ci95(
                baseline_hits,
                baseline_denominators,
                candidate_hits,
                candidate_denominators,
                rounds=rounds,
                seed=seed,
            )
            delta = candidate_pct - baseline_pct
            mde = suggest_chain.minimum_detectable_effect_pp(
                sum(baseline_hits) / sum(baseline_denominators),
                sum(baseline_denominators),
            )
            if identifier == NATIVE_CHAT_DOMAIN:
                passed = delta >= 0.0 and low >= 0.0
                rule = "delta >= 0 and paired CI95 lower bound >= 0"
            else:
                passed = delta >= -mde
                rule = "delta >= -baseline MDE"
            if not passed:
                failures.append(f"{identifier}:{name}")
            metric_results[name] = {
                "baseline_pct": round(baseline_pct, 6),
                "candidate_pct": round(candidate_pct, 6),
                "delta_pp": round(delta, 6),
                "delta_ci95": [round(low, 6), round(high, 6)],
                "baseline_mde_pp": round(mde, 6),
                "rule": rule,
                "passed": passed,
            }
        comparisons[identifier] = metric_results
    if native_present:
        message = (
            "native chat and every secondary domain passed"
            if not failures
            else "one or more domain metrics failed"
        )
    else:
        message = "native chat absent; secondary domains only"
        if failures:
            message += "; one or more domain metrics failed"
    return {
        "acceptable": not failures,
        "metrics": list(metrics),
        "native_chat": "evaluated" if native_present else "absent",
        "secondary_domain_fallback": not native_present,
        "message": message,
        "failures": failures,
        "domains": comparisons,
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
    decision = subparsers.add_parser("decide-domains")
    decision.add_argument("baseline", type=Path)
    decision.add_argument("candidate", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "paired-ci":
            print(json.dumps(_paired_ci_command(args.input), indent=2, sort_keys=True))
        elif args.command == "check-report":
            validate_gate_report(_load_json(args.report))
            print("PASS: visible top-3, diagnostic engine top-4, denominators, and pairing")
        else:
            baseline = _load_json(args.baseline)
            candidate = _load_json(args.candidate)
            if not isinstance(baseline, dict) or not isinstance(candidate, dict):
                raise EvaluationMetricError("domain reports must be JSON objects")
            verdict = decide_domain_candidate(baseline, candidate)
            print(json.dumps(verdict, ensure_ascii=False, indent=2, sort_keys=True))
            return 0 if verdict["acceptable"] else 1
    except EvaluationMetricError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
