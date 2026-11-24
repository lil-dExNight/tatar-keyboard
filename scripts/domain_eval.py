#!/usr/bin/env python3
"""Canonicalize Tatar evaluation domains, measure them, and compare candidate assets.

Raw Common Voice and TatSC files come from research/corpus-sources/fetch_sources.py.
OpenSubtitles uses every tenth physical input line, starting with the first. Native chat
placeholders split lines into independent segments. Every domain then uses the Tatoeba
canonicalization: make_eval_set.normalize_sentence, 3..12 words, and first-occurrence dedup.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence, TextIO

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "research" / "corpus"))

import dictionary_coverage as coverage  # noqa: E402
import evaluation_metrics as metrics  # noqa: E402
import filters as corpus_filters  # noqa: E402
import make_eval_set  # noqa: E402
import native_text_ingest  # noqa: E402
import suggest_chain  # noqa: E402
import suggest_eval  # noqa: E402

SCHEMA = "tt-domain-evaluation-v1"
DEFAULT_OUTPUT = ROOT / "build" / "domain-eval"
TATAR_SPECIFIC = frozenset("әөүҗңһ")


class DomainEvalError(ValueError):
    """An invalid input, failed measurement, or inconsistent domain report."""


@dataclass(frozen=True)
class DomainSpec:
    identifier: str
    label: str
    path: Path
    native: bool = False
    subtitles: bool = False


@dataclass
class DomainData:
    spec: DomainSpec
    lines: list[str]
    canonical_path: Path
    input_sha256: str
    raw_lines: list[str]
    excluded_per_line: list[int]


DOMAIN_ORDER = (
    ("native_chat", "Native chat"),
    ("common_voice", "Common Voice test"),
    ("tatsc_audiobooks", "TatSC audiobooks test"),
    ("tatsc_web", "TatSC crowdsourced web test"),
    ("subtitles", "OpenSubtitles every 10th"),
    ("tatoeba", "Tatoeba eval"),
)
DEFAULT_PATHS = {
    "native_chat": ROOT / "build" / "native-text" / "normalized-final.txt",
    "common_voice": ROOT / "build" / "corpus-sources" / "text" / "common-voice" / "tt-test.txt",
    "tatsc_audiobooks": (
        ROOT / "build" / "corpus-sources" / "text" / "tatsc" / "tt-audiobooks-test.txt"
    ),
    "tatsc_web": (
        ROOT
        / "build"
        / "corpus-sources"
        / "text"
        / "tatsc"
        / "tt-crowdsourced_web-test.txt"
    ),
    "subtitles": ROOT / "research" / "corpus" / make_eval_set.OPENSUBTITLES_FILE,
    "tatoeba": suggest_eval.DEFAULT_EVAL,
}
EXCLUSION_RULE = (
    "normalized token or a stem before a known inflectional suffix has no Tatar-specific "
    "letter, has at least three non-line-initial OpenSubtitles occurrences with at least "
    "80% capitalized, and occurs in no other available evaluation domain"
)


def is_foreign_name_form(word: str) -> bool:
    """Recognize an uninflected or inflected foreign-name form by its alphabet."""
    if not TATAR_SPECIFIC.intersection(word):
        return True
    for cut in range(len(word) - 1, 1, -1):
        if (
            not TATAR_SPECIFIC.intersection(word[:cut])
            and suggest_chain.is_inflected_continuation(word[cut:])
        ):
            return True
    return False


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_raw_lines(path: Path) -> list[str]:
    try:
        if path.suffix == ".gz":
            with gzip.open(path, "rt", encoding="utf-8", errors="replace", newline="") as handle:
                return [line.rstrip("\r\n") for line in handle]
        with path.open("r", encoding="utf-8", errors="replace", newline="") as handle:
            return [line.rstrip("\r\n") for line in handle]
    except OSError as error:
        raise DomainEvalError(f"cannot read {path}: {error}") from error


def canonicalize(
    raw_lines: Sequence[str], *, native: bool, skip_comments: bool = False
) -> list[str]:
    """Return canonical 3..12-word lines, with placeholders breaking native adjacency."""
    result: list[str] = []
    seen: set[str] = set()
    for raw in raw_lines:
        if not raw.strip() or (skip_comments and raw.startswith("#")):
            continue
        parts = native_text_ingest.PLACEHOLDER.split(raw) if native else (raw,)
        for part in parts:
            words = make_eval_set.normalize_sentence(part)
            if not (
                make_eval_set.DEFAULT_MIN_WORDS
                <= len(words)
                <= make_eval_set.DEFAULT_MAX_WORDS
            ):
                continue
            line = " ".join(words)
            if line not in seen:
                seen.add(line)
                result.append(line)
    return result


def prepare_domains(
    specs: Sequence[DomainSpec], output: Path
) -> tuple[list[DomainData], dict[str, object]]:
    prepared: list[DomainData] = []
    canonical_dir = output / "canonical"
    canonical_dir.mkdir(parents=True, exist_ok=True)
    for spec in specs:
        raw_lines = read_raw_lines(spec.path)
        sampled = raw_lines[::10] if spec.subtitles else raw_lines
        lines = canonicalize(
            sampled, native=spec.native, skip_comments=spec.identifier == "tatoeba"
        )
        if not lines:
            raise DomainEvalError(f"domain {spec.identifier} has no canonical lines")
        canonical_path = canonical_dir / f"{spec.identifier}.txt"
        payload = ("\n".join(lines) + "\n").encode("utf-8")
        canonical_path.write_bytes(payload)
        prepared.append(
            DomainData(
                spec=spec,
                lines=lines,
                canonical_path=canonical_path,
                input_sha256=hashlib.sha256(payload).hexdigest(),
                raw_lines=sampled if spec.subtitles else [],
                excluded_per_line=[0] * len(lines),
            )
        )
    return prepared, apply_headline_exclusions(prepared)


def load_canonical_domains(directory: Path) -> list[DomainData]:
    """Load canonical files produced by this entry point, in registry order."""
    domains: list[DomainData] = []
    for identifier, label in DOMAIN_ORDER:
        path = directory / f"{identifier}.txt"
        if not path.is_file():
            continue
        payload = path.read_bytes()
        lines = canonicalize(payload.decode("utf-8").splitlines(), native=False)
        if not lines:
            raise DomainEvalError(f"canonical domain is empty: {path}")
        spec = DomainSpec(identifier=identifier, label=label, path=path)
        domains.append(
            DomainData(
                spec=spec,
                lines=lines,
                canonical_path=path,
                input_sha256=hashlib.sha256(payload).hexdigest(),
                raw_lines=[],
                excluded_per_line=[0] * len(lines),
            )
        )
    if not domains:
        raise DomainEvalError(f"no canonical domain files found in {directory}")
    return domains


def apply_headline_exclusions(domains: Sequence[DomainData]) -> dict[str, object]:
    """Pin the subtitle-only foreign-name set used by both comparison arms."""
    other_tokens = {
        word
        for domain in domains
        if not domain.spec.subtitles
        for line in domain.lines
        for word in line.split(" ")
    }
    evidence = corpus_filters.CaseEvidence()
    subtitle_domains = [domain for domain in domains if domain.spec.subtitles]
    for domain in subtitle_domains:
        for line in domain.raw_lines:
            first = True
            for chunk in line.split():
                surface = chunk.strip(make_eval_set.EDGE_CHARS)
                if surface:
                    normalized, _reason = coverage.normalize_word(surface)
                    if normalized is not None:
                        evidence.observe(surface, normalized, first)
                first = False
    excluded = {
        word
        for word in evidence.total
        if is_foreign_name_form(word)
        and word not in other_tokens
        and evidence.is_proper(word)
    }
    for domain in subtitle_domains:
        domain.excluded_per_line = [
            sum(word in excluded for word in line.split(" ")) for line in domain.lines
        ]
    return {
        "rule": EXCLUSION_RULE,
        "words": sorted(excluded),
        "types": len(excluded),
        "subtitle_tokens": sum(
            sum(domain.excluded_per_line) for domain in subtitle_domains
        ),
    }


def parse_eval_output(text: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in text.splitlines():
        if not line:
            continue
        fields = line.split("|", 2)
        if len(fields) != 3 or fields[0] != "EVAL":
            raise DomainEvalError(f"unexpected suggest_eval output: {line!r}")
        result[fields[1]] = fields[2]
    if not result:
        raise DomainEvalError("suggest_eval produced no metrics")
    return result


def adjusted_headline_series(
    domain: DomainData,
    coverage_series: dict[str, object],
    vocabulary: frozenset[str],
    excluded_words: frozenset[str],
) -> dict[str, object]:
    if not domain.spec.subtitles:
        return coverage_series
    hits: list[int] = []
    denominators: list[int] = []
    for line in domain.lines:
        retained = [word for word in line.split(" ") if word not in excluded_words]
        hits.append(sum(word in vocabulary for word in retained))
        denominators.append(len(retained))
    return {"hits": hits, "denominators": denominators}


def evaluate_assets(
    domains: Sequence[DomainData],
    *,
    dict_asset: Path,
    bigram_asset: Path,
    output: Path,
    arm: str,
    bootstrap_rounds: int,
    exclusion_report: dict[str, object],
) -> dict[str, object]:
    """Run suggest_eval unchanged for its metrics and retain its per-record JSON arrays."""
    excluded_words = frozenset(exclusion_report["words"])
    words, _frequencies, _raw = suggest_eval.load_dictionary_words(dict_asset)
    vocabulary = frozenset(words)
    arm_dir = output / arm
    arm_dir.mkdir(parents=True, exist_ok=True)
    entries: list[dict[str, object]] = []
    for domain in domains:
        series_path = arm_dir / f"{domain.spec.identifier}.series.json"
        command = [
            sys.executable,
            str(ROOT / "scripts" / "suggest_eval.py"),
            "--eval",
            str(domain.canonical_path),
            "--dict-asset",
            str(dict_asset),
            "--bigram-asset",
            str(bigram_asset),
            "--series-output",
            str(series_path),
        ]
        completed = subprocess.run(command, capture_output=True, text=True, check=False)
        (arm_dir / f"{domain.spec.identifier}.suggest_eval.txt").write_text(
            completed.stdout, encoding="utf-8"
        )
        if completed.returncode != 0:
            raise DomainEvalError(
                f"suggest_eval failed for {domain.spec.identifier}: {completed.stderr.strip()}"
            )
        series = json.loads(series_path.read_text(encoding="utf-8"))
        headline = adjusted_headline_series(
            domain, series["coverage"], vocabulary, excluded_words
        )
        series["headline_coverage"] = headline
        summaries = {
            name: metrics.series_summary(
                values["hits"], values["denominators"], rounds=bootstrap_rounds
            )
            for name, values in series.items()
        }
        entries.append(
            {
                "id": domain.spec.identifier,
                "label": domain.spec.label,
                "source_path": str(domain.spec.path),
                "canonical_path": str(domain.canonical_path),
                "input_sha256": domain.input_sha256,
                "lines": len(domain.lines),
                "excluded_tokens": sum(domain.excluded_per_line),
                "eval": parse_eval_output(completed.stdout),
                "series": series,
                "summaries": summaries,
            }
        )
    return {
        "schema": SCHEMA,
        "dictionary_asset": str(dict_asset),
        "dictionary_sha256": file_sha256(dict_asset),
        "bigram_asset": str(bigram_asset),
        "bigram_sha256": file_sha256(bigram_asset),
        "bootstrap_rounds": bootstrap_rounds,
        "metric_units": {
            "coverage": "sentence",
            "headline_coverage": "sentence",
            "completion_cp3": "unique word",
            "next_word": "sentence",
        },
        "headline_exclusion": exclusion_report,
        "domains": entries,
    }


def print_domain_table(report: dict[str, object], stream: TextIO = sys.stdout) -> None:
    print("Cells: percent [CI95]; MDE in percentage points.", file=stream)
    print(
        "| Domain | Coverage | Headline coverage | Completion cp3 | Next word |\n"
        "|---|---:|---:|---:|---:|",
        file=stream,
    )
    for domain in report["domains"]:
        cells = []
        for name in ("coverage", "headline_coverage", "completion_cp3", "next_word"):
            summary = domain["summaries"][name]
            low, high = summary["ci95"]
            cells.append(
                f"{summary['pct']:.2f} [{low:.2f}, {high:.2f}]; {summary['mde_pp']:.2f}"
            )
        print(f"| {domain['label']} | " + " | ".join(cells) + " |", file=stream)


def print_decision(decision: dict[str, object], stream: TextIO = sys.stdout) -> None:
    print(
        "| Domain | Metric | Baseline | Candidate | Delta [paired CI95] | MDE | Pass |\n"
        "|---|---|---:|---:|---:|---:|---|",
        file=stream,
    )
    for identifier, domain_metrics in decision["domains"].items():
        for name, result in domain_metrics.items():
            low, high = result["delta_ci95"]
            print(
                f"| {identifier} | {name} | {result['baseline_pct']:.4f} | "
                f"{result['candidate_pct']:.4f} | {result['delta_pp']:+.4f} "
                f"[{low:+.4f}, {high:+.4f}] | {result['baseline_mde_pp']:.4f} | "
                f"{str(result['passed']).lower()} |",
                file=stream,
            )
    verdict = "ACCEPT" if decision["acceptable"] else "REJECT"
    print(f"DECISION|{verdict}|{decision['message']}", file=stream)


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    for identifier, label in DOMAIN_ORDER:
        parser.add_argument(
            f"--{identifier.replace('_', '-')}",
            type=Path,
            default=None,
            help=f"{label} raw input",
        )
    parser.add_argument("--dict-asset", type=Path, default=suggest_eval.DEFAULT_DICT_ASSET)
    parser.add_argument("--bigram-asset", type=Path, default=suggest_eval.DEFAULT_BIGRAM_ASSET)
    parser.add_argument("--candidate-dict", type=Path, default=None)
    parser.add_argument("--candidate-bigram", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--bootstrap-rounds", type=int, default=2000)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_parser().parse_args(argv)
    if (args.candidate_dict is None) != (args.candidate_bigram is None):
        print(
            "error: candidate dictionary and bigram table must be supplied together",
            file=sys.stderr,
        )
        return 2
    if args.bootstrap_rounds < 100:
        print("error: bootstrap rounds must be at least 100", file=sys.stderr)
        return 2
    specs: list[DomainSpec] = []
    for identifier, label in DOMAIN_ORDER:
        configured = getattr(args, identifier)
        path = configured if configured is not None else DEFAULT_PATHS[identifier]
        if not path.is_file():
            if configured is not None or identifier == "tatoeba":
                print(f"error: domain input is missing: {path}", file=sys.stderr)
                return 2
            print(f"DOMAIN_SKIP|{identifier}|{path}", file=sys.stderr)
            continue
        specs.append(
            DomainSpec(
                identifier=identifier,
                label=label,
                path=path,
                native=identifier == "native_chat",
                subtitles=identifier == "subtitles",
            )
        )
    try:
        domains, exclusion = prepare_domains(specs, args.output)
        baseline = evaluate_assets(
            domains,
            dict_asset=args.dict_asset,
            bigram_asset=args.bigram_asset,
            output=args.output,
            arm="baseline",
            bootstrap_rounds=args.bootstrap_rounds,
            exclusion_report=exclusion,
        )
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "baseline-report.json").write_text(
            json.dumps(baseline, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print_domain_table(baseline)
        if args.candidate_dict is not None:
            candidate = evaluate_assets(
                domains,
                dict_asset=args.candidate_dict,
                bigram_asset=args.candidate_bigram,
                output=args.output,
                arm="candidate",
                bootstrap_rounds=args.bootstrap_rounds,
                exclusion_report=exclusion,
            )
            (args.output / "candidate-report.json").write_text(
                json.dumps(candidate, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            decision = metrics.decide_domain_candidate(
                baseline, candidate, rounds=args.bootstrap_rounds
            )
            (args.output / "decision.json").write_text(
                json.dumps(decision, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            print_decision(decision)
            return 0 if decision["acceptable"] else 1
    except (
        DomainEvalError,
        metrics.EvaluationMetricError,
        OSError,
        json.JSONDecodeError,
    ) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
