#!/usr/bin/env python3
"""Measure the six reviewed DL-2 letter classes against the production baseline, per class.

The harness compares the existing production baseline arm with a candidate arm that adds exactly
one class: the single-letter substitution rule under test, applied with the engine's own
same-length ranking on the display side (`dl2-candidate-v1`) and a single-unambiguous-variant
autocorrect advice behind the production guards, including the context-successor guard on armed
lookups (`dl2-advice-v1`). Both arms run the same production index wiring, the same bigram table,
the same normalization and the same ordered records. Nothing here enables a production class and
no production source is modified: the JVM bridge (`Dl2ClassMeasurement`) is env-gated and only
runs through the `dl2Benchmark` task of `research/dl2-evaluation/dl2-benchmark.init.gradle`.

Evidence contract:

- Recovery is exactly the class's `split=final`, `record_kind=pair`, `approved=yes` worksheet
  rows, in frozen worksheet order, hit at visible top-3 (`index.lookup(typed).take(3)`).
  Development approved pairs are measured only as a positive control that the class engages.
- A class with no approved final pair rows is logically ineligible: no recovery denominator
  exists and none is synthesized. The safety diagnostics still run and the report records the
  ineligibility.
- Safety combines, separately and disclosed per source: the exact reviewed safety rows, the
  regenerated valid-OOV sets (regression gates labeled `development_regression_evaluation` by
  their provenance, never final evidence), and the class's final safety rows with `approved=yes`.
  Any non-null autocorrect advice for a protected word is a false replacement.
- The paired CI95 aggregates collision groups into one bootstrap unit (hits summed, denominator =
  group row count), so rows sharing a collision group are not resampled as unrelated records.
- Known telemetry gap: `computeAutocorrectAdvice` can return on probe-generation failure before
  `lastAutocorrectProbeCount` is written, so the autocorrect-side over-budget gate is reported as
  unmeasurable; the display-side `lastFuzzyOverBudget` gate is measured.

The reviewed worksheets are not tracked. ``measure`` needs ``--worksheet-dir`` pointing at a
directory holding ``worksheet-<slug>.tsv`` when the default local path is absent; it fails with
an explicit error otherwise. Unit tests build their own temporary worksheet fixtures.

Usage:
    python3 scripts/dl2_acceptance.py measure --class ae [--all] [--outdir DIR] [--worksheet-dir DIR]
    python3 scripts/dl2_acceptance.py validate --report PATH [--report PATH ...]
    python3 scripts/dl2_acceptance.py gates --report PATH [--report PATH ...]

`measure` writes `out/<slug>/report.json` plus the batch and result TSVs next to it. The small
final approved safety samples of ә/е, ө/о and ү/у are reported as explicit adequacy limitations,
never silently treated as sufficient.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import subprocess
import sys
import unicodedata
from collections import Counter, OrderedDict
from pathlib import Path
from typing import Any, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import evaluation_metrics  # noqa: E402

REPORT_SCHEMA = "dl2-acceptance-report-v1"
# The bridge result TSV contract; must match Dl2ClassMeasurement.RESULT_HEADER.
RESULT_COLUMNS = (
    "record_index", "id", "kind", "predecessor", "word_in_dictionary",
    "baseline_hit", "candidate_hit", "baseline_hit4", "candidate_hit4",
    "baseline_advice", "candidate_advice", "advice_source",
    "armed_successors", "armed_baseline_advice", "armed_candidate_advice", "guard_blocked",
    "class_variants", "class_frequency_probes",
    "baseline_over_budget", "candidate_over_budget",
    "baseline_fuzzy_probes", "candidate_fuzzy_probes",
    "baseline_autocorrect_probes", "candidate_autocorrect_probes",
    "baseline_ns", "candidate_ns",
    "baseline_candidates", "candidate_candidates",
    "baseline_fires", "candidate_fires",
)
INIT_SCRIPT = ROOT / "research/dl2-evaluation/dl2-benchmark.init.gradle"
REVIEW_DIR = ROOT / "major-release-plan/review/dl2"  # default, overridden by --worksheet-dir
EXACT_PAIRS_PATH = ROOT / "data/dictionary/tatar-autocorrect-review.tsv"
EXACT_SAFETY_PATH = ROOT / "data/dictionary/tatar-autocorrect-safety.tsv"
VALID_OOV_COMMAND = "scripts/typo_eval_pack.py"
VALID_OOV_SOURCES = {
    "tat": (
        "app/src/test/resources/tt_eval_sentences.txt",
        "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
        "data/dictionary/dict-accept/conv-freq-tt.tsv",
    ),
    "rus": (
        "app/src/test/resources/ru_eval_sentences.txt",
        "app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
        "data/dictionary/dict-accept/conv-freq-ru.tsv",
    ),
}
VALID_OOV_ROLE = "development_regression_evaluation"

# Class slug -> (worksheet record class, language, directed substitutions). The substitutions are
# fixed class definitions; nothing is derived from worksheet approvals, so the candidate arm
# cannot read final evidence to choose its behavior.
CLASSES: OrderedDict[str, dict[str, Any]] = OrderedDict(
    (
        ("ae", {"record_class": "ә/е", "language": "tat", "substitutions": ("ә>е", "е>ә")}),
        ("oe", {"record_class": "ө/о", "language": "tat", "substitutions": ("ө>о", "о>ө")}),
        ("ue", {"record_class": "ү/у", "language": "tat", "substitutions": ("ү>у", "у>ү")}),
        ("ng", {"record_class": "ң/н", "language": "tat", "substitutions": ("ң>н", "н>ң")}),
        ("hkh", {"record_class": "һ/х", "language": "tat", "substitutions": ("һ>х", "х>һ")}),
        ("ye-yo", {"record_class": "е→ё", "language": "rus", "substitutions": ("е>ё",)}),
    )
)
RISK_COLUMNS = ("risk_mass_edit", "risk_proper_name", "risk_duplicate", "risk_corpus_overlap")
TIMING_BUDGET_MS = 5.0
# Reporting heuristic that flags an adequacy limitation when the final approved safety sample is
# this small; it is not an acceptance threshold and never upgrades a sample to sufficient.
SMALL_FINAL_SAFETY_LIMIT = 10
EXPECTED_EXACT_PAIR_COUNTS = {"ү/у": 184, "ң/н": 35}


class AcceptanceError(RuntimeError):
    """A missing input, an inconsistent worksheet row, or a failed bridge run."""


def normalize(word: str) -> str:
    """The engine lookup form: NFC, lowercase — the same convention TatarWordUtils applies."""
    return unicodedata.normalize("NFC", word).lower()


def display_path(path: Path) -> str:
    """Repository-relative when the file lives in the repo, absolute otherwise."""
    resolved = path.resolve()
    try:
        return resolved.relative_to(ROOT).as_posix()
    except ValueError:
        return str(resolved)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def percentile(sorted_values: Sequence[int | float], fraction: float) -> float:
    if not sorted_values:
        return 0.0
    rank = max(1, math.ceil(len(sorted_values) * fraction))
    return sorted_values[rank - 1]


def read_worksheet(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        raise AcceptanceError(
            f"missing reviewed worksheet: {path}; the reviewed worksheets are not tracked and "
            "must be supplied through --worksheet-dir on this checkout"
        )
    with path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    if not rows:
        raise AcceptanceError(f"empty worksheet: {path}")
    for field in ("record_id", "record_kind", "class", "split", "approved", "typed_form",
                  "proposed_correction", "preserve_word", "expected_unchanged", "context",
                  "collision_group"):
        if field not in rows[0]:
            raise AcceptanceError(f"worksheet {path} lacks the {field} column")
    return rows


def extract_predecessor(context: str, word: str) -> str:
    """The normalized token before the first occurrence of [word] in [context], or ''."""
    if not context.strip():
        return ""
    punctuation = ",.;:!?«»\"'()–—-"
    tokens = [token.strip(punctuation) for token in normalize(context).split()]
    for position, token in enumerate(tokens):
        if token == word and position > 0 and tokens[position - 1]:
            return tokens[position - 1]
    return ""


def select_worksheet(slug: str, path: Path) -> dict[str, Any]:
    """Final approved recovery and safety rows, development controls and exposure counts."""
    rows = read_worksheet(path)
    recovery: list[dict[str, str]] = []
    degenerate = 0
    for row in rows:
        if row["record_kind"] != "pair" or row["split"] != "final" or row["approved"] != "yes":
            continue
        word = normalize(row["typed_form"])
        correction = normalize(row["proposed_correction"])
        if not word or not correction or word == correction:
            degenerate += 1
            continue
        recovery.append(
            {
                "record_id": row["record_id"],
                "word": word,
                "correction": correction,
                "collision_group": row["collision_group"],
            }
        )
    safety: list[dict[str, str]] = []
    safety_contract_violations = 0
    for row in rows:
        if row["record_kind"] != "safety" or row["split"] != "final" or row["approved"] != "yes":
            continue
        word = normalize(row["preserve_word"] or row["typed_form"])
        if (
            normalize(row["typed_form"]) != word
            or normalize(row["expected_unchanged"]) != word
            or row["proposed_correction"].strip()
        ):
            safety_contract_violations += 1
            continue
        safety.append(
            {
                "record_id": row["record_id"],
                "word": word,
                "predecessor": extract_predecessor(row["context"], word),
            }
        )
    control: list[dict[str, str]] = []
    for row in rows:
        if row["record_kind"] != "pair" or row["split"] != "development" or row["approved"] != "yes":
            continue
        word = normalize(row["typed_form"])
        correction = normalize(row["proposed_correction"])
        if not word or not correction or word == correction:
            continue
        control.append({"record_id": row["record_id"], "word": word, "correction": correction})
    final_pairs = [row for row in rows if row["record_kind"] == "pair" and row["split"] == "final"]
    risk_exposure = {
        column: sum(1 for row in final_pairs if row["approved"] == "yes" and row.get(column) == "yes")
        for column in RISK_COLUMNS
    }
    return {
        "recovery": recovery,
        "safety": safety,
        "control": control,
        "degenerate_recovery_rows": degenerate,
        "safety_contract_violations": safety_contract_violations,
        "risk_exposure": risk_exposure,
        "final_pair_rows": len(final_pairs),
    }


def parse_exact_pairs(text: str) -> list[dict[str, str]]:
    """Approved exact reviewed pairs; comment lines before the TSV header are skipped."""
    rows = [line for line in text.splitlines() if not line.startswith("#")]
    pairs = []
    for row in csv.DictReader(rows, delimiter="\t"):
        if row.get("approved") != "yes":
            continue
        pairs.append(
            {
                "word": normalize(row["typed"]),
                "correction": normalize(row["replacement"]),
                "record_class": row["class"],
            }
        )
    return pairs


def read_exact_pairs(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        raise AcceptanceError(f"missing exact reviewed pairs: {path}")
    pairs = parse_exact_pairs(path.read_text(encoding="utf-8"))
    counts = Counter(pair["record_class"] for pair in pairs)
    for record_class, expected in EXPECTED_EXACT_PAIR_COUNTS.items():
        if counts.get(record_class, 0) != expected:
            raise AcceptanceError(
                f"exact reviewed pair count for {record_class} is {counts.get(record_class, 0)}, "
                f"expected {expected}"
            )
    if len(pairs) != sum(EXPECTED_EXACT_PAIR_COUNTS.values()):
        raise AcceptanceError(f"exact reviewed pair count is {len(pairs)}, expected 219")
    return pairs


def read_exact_safety(path: Path) -> dict[str, Any]:
    """The declared 1,607-row accounting; the known үзенә duplicate is disclosed, not merged."""
    if not path.is_file():
        raise AcceptanceError(f"missing exact reviewed safety set: {path}")
    words = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "\t" in line:
                raise AcceptanceError(f"unexpected multi-column exact safety row: {line!r}")
            words.append(normalize(line))
    duplicated = sorted(word for word, count in Counter(words).items() if count > 1)
    return {"rows": words, "row_count": len(words), "unique_words": len(set(words)), "duplicated": duplicated}


def regenerate_valid_oov(outdir: Path) -> dict[str, Any]:
    """Regenerate both valid-OOV sets on the committed dictionaries, into the run outdir."""
    result: dict[str, Any] = {}
    for language, (eval_set, dictionary, conv_frequency) in VALID_OOV_SOURCES.items():
        output = outdir / f"{language}_valid_oov_safety_set.tsv"
        command = [
            sys.executable, str(ROOT / VALID_OOV_COMMAND), "safety-set",
            "--language", language,
            "--eval-set", str(ROOT / eval_set),
            "--dictionary", str(ROOT / dictionary),
            "--conv-frequency", str(ROOT / conv_frequency),
            "--output", str(output),
        ]
        completed = subprocess.run(command, cwd=ROOT, check=False, capture_output=True, text=True)
        if completed.returncode != 0:
            raise AcceptanceError(
                f"valid-OOV regeneration failed for {language}: {completed.stderr.strip()}"
            )
        words = []
        with output.open(encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                words.append(normalize(line.split("\t")[0]))
        if not words:
            raise AcceptanceError(f"regenerated valid-OOV set for {language} is empty")
        result[language] = {
            "path": output.relative_to(ROOT).as_posix(),
            "rows": words,
            "row_count": len(words),
            "unique_words": len(set(words)),
            "sha256": sha256_file(output),
            "role": VALID_OOV_ROLE,
        }
    return result


def build_batch(slug: str, selection: dict[str, Any], exact_pairs: Sequence[Mapping[str, str]],
                exact_safety: Mapping[str, Any], valid_oov: Mapping[str, Any]) -> list[dict[str, str]]:
    """One flat record list; order is frozen by this function."""
    language = CLASSES[slug]["language"]
    records: list[dict[str, str]] = []

    def add(kind: str, word: str, correction: str = "", predecessor: str = "", record_id: str = "") -> None:
        records.append(
            {
                "record_index": str(len(records)),
                "id": record_id or f"{kind}:{word}" + (f":{correction}" if correction else ""),
                "kind": kind,
                "language": language,
                "word": word,
                "correction": correction,
                "predecessor": predecessor,
                "class": slug,
            }
        )

    for row in selection["recovery"]:
        add("recovery", row["word"], row["correction"], record_id=row["record_id"])
    for row in selection["control"]:
        add("control", row["word"], row["correction"], record_id=row["record_id"])
    for row in selection["safety"]:
        add("safety", row["word"], predecessor=row["predecessor"], record_id=row["record_id"])
    if language == "tat":
        for word in exact_safety["rows"]:
            add("safety", word)
    for word in valid_oov[language]["rows"]:
        add("safety", word)
    if language == "tat":
        # The reviewed exact pairs are Tatar production behavior; the Russian е→ё arm has none.
        for pair in exact_pairs:
            add("baseline_pair", pair["word"], pair["correction"])
    return records


def run_bridge(records: Sequence[Mapping[str, str]], outdir: Path) -> tuple[list[dict[str, str]], dict[str, Any]]:
    """Replay the batch through the env-gated JVM bridge and parse the result TSV."""
    if not INIT_SCRIPT.is_file():
        raise AcceptanceError(f"missing bridge wiring: {INIT_SCRIPT}")
    if not records:
        raise AcceptanceError("empty measurement batch")
    input_path = outdir / "batch.tsv"
    output_path = outdir / "results.tsv"
    with input_path.open("w", encoding="utf-8", newline="") as handle:
        handle.write("\t".join(records[0].keys()) + "\n")
        for record in records:
            handle.write("\t".join(record.values()) + "\n")
    # Paths travel as -P project properties, not process environment: a long-lived Gradle daemon
    # can keep the environment of an earlier class's invocation, which would silently write this
    # class's results to the previous class's paths.
    command = [
        str(ROOT / "gradlew"), "--offline", "--console=plain", "-I", str(INIT_SCRIPT),
        ":app:dl2Benchmark",
        f"-Pdl2BenchmarkInput={input_path}",
        f"-Pdl2BenchmarkOutput={output_path}",
    ]
    completed = subprocess.run(command, cwd=ROOT, env=os.environ.copy(), check=False)
    if completed.returncode != 0:
        raise AcceptanceError(f"measurement bridge failed with exit code {completed.returncode}")
    if not output_path.is_file():
        raise AcceptanceError(
            f"measurement bridge reported success but wrote no result TSV at {output_path}"
        )
    if output_path.stat().st_mtime_ns < input_path.stat().st_mtime_ns:
        raise AcceptanceError(
            f"measurement bridge result {output_path} is older than its batch; refusing stale output"
        )
    lines = output_path.read_text(encoding="utf-8").splitlines()
    if not lines:
        raise AcceptanceError("measurement bridge wrote no output")
    header = tuple(lines[0].split("\t"))
    if header != RESULT_COLUMNS:
        raise AcceptanceError("unexpected bridge result header")
    results: list[dict[str, str]] = []
    metadata: dict[str, Any] = {}
    for line in lines[1:]:
        fields = line.split("\t")
        if fields[0] == "metadata":
            metadata = json.loads(fields[1])
            continue
        if len(fields) != len(header):
            raise AcceptanceError("malformed bridge result row")
        results.append(dict(zip(header, fields)))
    if len(results) != len(records):
        raise AcceptanceError(
            f"bridge returned {len(results)} rows for {len(records)} records"
        )
    for record, result in zip(records, results):
        if result["record_index"] != record["record_index"] or result["id"] != record["id"]:
            raise AcceptanceError("bridge result order does not match the batch")
    return results, metadata


def _int(row: Mapping[str, str], key: str) -> int:
    return int(row[key])


def _bool(row: Mapping[str, str], key: str) -> bool:
    return row[key] == "1"


def group_recovery_units(recovery: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """One bootstrap unit per collision group, in first-seen order."""
    units: OrderedDict[str, dict[str, Any]] = OrderedDict()
    for row in recovery:
        unit = units.setdefault(row["collision_group"], {"hits": [], "rows": 0})
        unit["hits"].append(row)
        unit["rows"] += 1
    return [
        {"collision_group": group, "rows": unit["rows"], "records": unit["hits"]}
        for group, unit in units.items()
    ]


def paired_measurement(recovery: Sequence[Mapping[str, Any]]) -> tuple[dict[str, Any], dict[str, Any]]:
    """Visible-top-3 (gate) and engine-top-4 (diagnostic) paired reports over collision groups."""
    units = group_recovery_units(recovery)
    baseline_hits = [sum(record["baseline_hit"] for record in unit["records"]) for unit in units]
    candidate_hits = [sum(record["candidate_hit"] for record in unit["records"]) for unit in units]
    denominators = [unit["rows"] for unit in units]
    baseline4 = [sum(record["baseline_hit4"] for record in unit["records"]) for unit in units]
    candidate4 = [sum(record["candidate_hit4"] for record in unit["records"]) for unit in units]
    return (
        {
            "unit": "worksheet_row",
            "units": len(recovery),
            "denominator_name": "approved_final_pair_rows",
            "gate_metric": "visible_top3",
            "aggregation": "collision-group",
            "collision_groups": len(units),
            "metrics": {
                "visible_top3": evaluation_metrics.paired_metric_report(
                    baseline_hits, denominators, candidate_hits, denominators, k=3, role="user",
                ),
                "engine_top4": evaluation_metrics.paired_metric_report(
                    baseline4, denominators, candidate4, denominators, k=4, role="diagnostic",
                ),
            },
        },
        {
            "record_ids": [record["record_id"] for record in recovery],
            "collision_groups": [record["collision_group"] for record in recovery],
            "baseline_hits": [record["baseline_hit"] for record in recovery],
            "candidate_hits": [record["candidate_hit"] for record in recovery],
            "baseline_hit4": [record["baseline_hit4"] for record in recovery],
            "candidate_hit4": [record["candidate_hit4"] for record in recovery],
        },
    )


def safety_summary(results: Sequence[Mapping[str, Any]], source_bounds: Sequence[Mapping[str, int]]) -> dict[str, Any]:
    """Per-source and combined false-replacement accounting for the protected rows."""
    sources: list[dict[str, Any]] = []
    for bound in source_bounds:
        rows = results[bound["start"]:bound["end"]]
        armed_rows = sum(1 for row in rows if _int(row, "armed_successors") > 0)
        baseline_false = [
            row for row in rows
            if row["baseline_advice"] or row["armed_baseline_advice"]
        ]
        candidate_false = [
            row for row in rows
            if row["candidate_advice"] or row["armed_candidate_advice"]
        ]
        sources.append(
            {
                "source": bound["source"],
                "role": bound["role"],
                "rows": len(rows),
                "in_dictionary": sum(1 for row in rows if _bool(row, "word_in_dictionary")),
                "armed_rows": armed_rows,
                "unarmed_rows": len(rows) - armed_rows,
                "baseline_false_replacements": len(baseline_false),
                "candidate_false_replacements": len(candidate_false),
                "baseline_false_examples": [row["baseline_advice"] or row["armed_baseline_advice"] for row in baseline_false[:10]],
                "candidate_false_examples": [row["candidate_advice"] or row["armed_candidate_advice"] for row in candidate_false[:10]],
            }
        )
    all_safety = [row for row in results if row["kind"] == "safety"]
    return {
        "sources": sources,
        "combined": {
            "rows": len(all_safety),
            "baseline_false_replacements": sum(
                1 for row in all_safety if row["baseline_advice"] or row["armed_baseline_advice"]
            ),
            "candidate_false_replacements": sum(
                1 for row in all_safety if row["candidate_advice"] or row["armed_candidate_advice"]
            ),
            "armed_rows": sum(1 for row in all_safety if _int(row, "armed_successors") > 0),
            "armed_baseline_fires": sum(1 for row in all_safety if row["armed_baseline_advice"]),
            # The unarmed lookup is measured for every protected row, including rows whose
            # predecessor also arms successors; the counters report both paths independently.
            "unarmed_baseline_fires": sum(1 for row in all_safety if row["baseline_advice"]),
            "armed_candidate_fires": sum(1 for row in all_safety if row["armed_candidate_advice"]),
            "unarmed_candidate_fires": sum(1 for row in all_safety if row["candidate_advice"]),
            "guard_blocked_fires": sum(1 for row in all_safety if _bool(row, "guard_blocked")),
        },
    }


def probes_summary(results: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    def counters(key: str) -> dict[str, Any]:
        values = sorted(_int(row, key) for row in results)
        return {
            "p95": percentile(values, 0.95),
            "max": max(values) if values else 0,
            "denominator": len(values),
        }

    return {
        "baseline_fuzzy_probes": counters("baseline_fuzzy_probes"),
        "candidate_fuzzy_probes": counters("candidate_fuzzy_probes"),
        "class_frequency_probes": counters("class_frequency_probes"),
        "baseline_fuzzy_over_budget_lookups": sum(
            1 for row in results if _bool(row, "baseline_over_budget")
        ),
        "candidate_fuzzy_over_budget_lookups": sum(
            1 for row in results if _bool(row, "candidate_over_budget")
        ),
        "baseline_autocorrect_probes": counters("baseline_autocorrect_probes"),
        "candidate_autocorrect_probes": counters("candidate_autocorrect_probes"),
        # computeAutocorrectAdvice returns on probe-generation failure before
        # lastAutocorrectProbeCount is written, so a zero counter cannot prove a zero
        # autocorrect over-budget outcome. The gate is reported as unmeasurable.
        "autocorrect_probe_telemetry": "gap",
    }


def timing_summary(results: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    def summary(key: str) -> dict[str, Any]:
        values = sorted(_int(row, key) for row in results)
        return {
            "p50_ms": round(percentile(values, 0.50) / 1e6, 4),
            "p95_ms": round(percentile(values, 0.95) / 1e6, 4),
            "max_ms": round(values[-1] / 1e6, 4) if values else 0.0,
            "samples": len(values),
        }

    return {"baseline": summary("baseline_ns"), "candidate": summary("candidate_ns")}


def control_summary(results: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    controls = [row for row in results if row["kind"] == "control"]
    return {
        "rows": len(controls),
        "baseline_hits": sum(_int(row, "baseline_hit") for row in controls),
        "candidate_hits": sum(_int(row, "candidate_hit") for row in controls),
        "class_engaged_records": sum(
            1 for row in controls
            if row["advice_source"] == "class"
            or _int(row, "candidate_hit") > _int(row, "baseline_hit")
        ),
        "class_variant_records": sum(1 for row in controls if row["class_variants"]),
    }


def exact_pair_summary(results: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    pairs = [row for row in results if row["kind"] == "baseline_pair"]
    return {
        "rows": len(pairs),
        "baseline_fired": sum(1 for row in pairs if _bool(row, "baseline_fires")),
        "candidate_fired": sum(1 for row in pairs if _bool(row, "candidate_fires")),
    }


def build_report(slug: str, worksheet: Path, selection: dict[str, Any], exact_pairs: Sequence[Mapping[str, str]],
                 exact_safety: Mapping[str, Any], valid_oov: Mapping[str, Any],
                 results: Sequence[Mapping[str, str]], metadata: Mapping[str, Any]) -> dict[str, Any]:
    language = CLASSES[slug]["language"]
    recovery_results = [row for row in results if row["kind"] == "recovery"]
    eligible = len(selection["recovery"]) > 0 and len(recovery_results) == len(selection["recovery"])
    recovery_records: list[dict[str, Any]] = []
    for selected, result in zip(selection["recovery"], recovery_results):
        if result["id"] != selected["record_id"]:
            raise AcceptanceError(f"recovery result order mismatch at {selected['record_id']}")
        recovery_records.append(
            {
                "record_id": selected["record_id"],
                "collision_group": selected["collision_group"],
                "baseline_hit": _int(result, "baseline_hit"),
                "candidate_hit": _int(result, "candidate_hit"),
                "baseline_hit4": _int(result, "baseline_hit4"),
                "candidate_hit4": _int(result, "candidate_hit4"),
            }
        )
    measurement = None
    recovery_arrays = None
    if eligible:
        measurement, recovery_arrays = paired_measurement(recovery_records)
    source_bounds: list[dict[str, Any]] = []
    start = sum(1 for row in results if row["kind"] in ("recovery", "control"))
    if selection["safety"]:
        source_bounds.append({"source": "final_safety_worksheet", "role": "final", "start": start,
                              "end": start + len(selection["safety"])})
        start += len(selection["safety"])
    if language == "tat":
        source_bounds.append({"source": "exact_reviewed_safety", "role": "regression_baseline",
                              "start": start, "end": start + len(exact_safety["rows"])})
        start += len(exact_safety["rows"])
    def oov_row_count(entry: Mapping[str, Any]) -> int:
        rows = entry.get("rows")
        return len(rows) if rows is not None else int(entry.get("row_count", 0))

    source_bounds.append({"source": f"valid_oov_{language}", "role": VALID_OOV_ROLE,
                          "start": start, "end": start + oov_row_count(valid_oov[language])})
    timing = timing_summary(results)
    final_safety_rows = len(selection["safety"])
    gates: dict[str, Any] = {}
    if measurement is not None:
        ci_low = measurement["metrics"]["visible_top3"]["delta"]["ci95"][0]
        gates["recovery_delta_ci95_above_zero"] = {
            "status": "pass" if ci_low > 0 else "fail",
            "ci95": measurement["metrics"]["visible_top3"]["delta"]["ci95"],
        }
    else:
        gates["recovery_delta_ci95_above_zero"] = {
            "status": "not_applicable",
            "reason": "no approved final pair rows; the class is logically ineligible",
        }
    safety = safety_summary(results, source_bounds)
    oov_source = next(
        source for source in safety["sources"] if source["source"].startswith("valid_oov_")
    )
    if oov_source["in_dictionary"] > 0:
        raise AcceptanceError(
            f"{oov_source['source']}: {oov_source['in_dictionary']} rows are dictionary words "
            "on the measured dictionary; the valid-OOV set does not match the arms' dictionary"
        )
    gates["zero_candidate_false_replacements"] = {
        "status": "pass" if safety["combined"]["candidate_false_replacements"] == 0 else "fail",
        "candidate_false_replacements": safety["combined"]["candidate_false_replacements"],
        "baseline_false_replacements": safety["combined"]["baseline_false_replacements"],
        "per_source": {source["source"]: source["candidate_false_replacements"] for source in safety["sources"]},
    }
    probes = probes_summary(results)
    gates["zero_display_over_budget_lookups"] = {
        "status": "pass" if probes["candidate_fuzzy_over_budget_lookups"] == 0 else "fail",
        "candidate_over_budget": probes["candidate_fuzzy_over_budget_lookups"],
        "baseline_over_budget": probes["baseline_fuzzy_over_budget_lookups"],
    }
    gates["zero_autocorrect_over_budget_lookups"] = {
        "status": "unmeasurable",
        "reason": (
            "computeAutocorrectAdvice returns on probe-generation failure before "
            "lastAutocorrectProbeCount is written, so a zero counter cannot prove the gate"
        ),
    }
    gates["host_autocorrect_p95_within_budget"] = {
        "status": "pass" if timing["candidate"]["p95_ms"] <= TIMING_BUDGET_MS else "fail",
        "candidate_p95_ms": timing["candidate"]["p95_ms"],
        "budget_ms": TIMING_BUDGET_MS,
    }
    exact_summary = exact_pair_summary(results)
    if language == "tat":
        preserved = (
            exact_summary["rows"] == sum(EXPECTED_EXACT_PAIR_COUNTS.values())
            and exact_summary["baseline_fired"] == exact_summary["rows"]
            and exact_summary["candidate_fired"] == exact_summary["rows"]
        )
        gates["exact_reviewed_pairs_preserved"] = {
            "status": "pass" if preserved else "fail",
            **exact_summary,
            "expected_counts": EXPECTED_EXACT_PAIR_COUNTS,
        }
    else:
        gates["exact_reviewed_pairs_preserved"] = {
            "status": "not_applicable",
            "reason": "the reviewed exact pairs are Tatar production behavior",
            **exact_summary,
        }
    control = control_summary(results)
    gates["positive_control_engaged"] = {
        "status": "pass" if control["rows"] > 0 and control["class_engaged_records"] > 0
        else ("not_applicable" if control["rows"] == 0 else "fail"),
        **control,
    }
    gates["final_safety_sample_adequacy"] = {
        "status": "limitation" if final_safety_rows < SMALL_FINAL_SAFETY_LIMIT else "reported",
        "final_safety_rows": final_safety_rows,
        "reporting_limit": SMALL_FINAL_SAFETY_LIMIT,
        "note": (
            "zero observed false replacements is not proof of broad safety; the final approved "
            "safety sample must be judged adequate by the operator"
        ),
    }
    risk_exposure = selection["risk_exposure"]
    gates["reviewed_risk_resolution"] = {
        "status": "operator_required" if any(risk_exposure.values()) else "reported",
        "flagged_final_approved_pair_rows": risk_exposure,
        "note": "risk flags are triage; each flagged component needs an explicit disposition",
    }
    hard_gates = ("recovery_delta_ci95_above_zero", "zero_candidate_false_replacements",
                  "zero_display_over_budget_lookups", "host_autocorrect_p95_within_budget",
                  "positive_control_engaged")
    if language == "tat":
        hard_gates = hard_gates + ("exact_reviewed_pairs_preserved",)
    blocking = [
        name for name, gate in gates.items()
        if gate["status"] not in ("pass", "not_applicable", "reported", "limitation")
    ]
    accepted = (
        eligible
        and all(gates[name]["status"] == "pass" for name in hard_gates)
        and not blocking
        and gates["final_safety_sample_adequacy"]["status"] != "limitation"
    )
    return {
        "schema": REPORT_SCHEMA,
        "class_slug": slug,
        "record_class": CLASSES[slug]["record_class"],
        "language": language,
        "eligible": eligible,
        "ineligible_reason": None if eligible else "no approved final pair rows in either split",
        "inputs": {
            "worksheet": {"path": display_path(worksheet), "sha256": sha256_file(worksheet)},
            "candidate_semantics": metadata.get("candidate_semantics"),
            "advice_semantics": metadata.get("advice_semantics"),
            "dictionaries": metadata.get("dictionaries"),
            "exact_reviewed_pairs": {
                "path": EXACT_PAIRS_PATH.relative_to(ROOT).as_posix(),
                "rows": len(exact_pairs),
            },
            "exact_reviewed_safety": {
                "path": EXACT_SAFETY_PATH.relative_to(ROOT).as_posix(),
                "row_count": exact_safety["row_count"],
                "unique_words": exact_safety["unique_words"],
                "duplicated_words": exact_safety["duplicated"],
            },
            "valid_oov": {
                language_tag: {key: value for key, value in entry.items() if key != "rows"}
                for language_tag, entry in valid_oov.items()
            },
        },
        "selection": {
            "final_approved_pair_rows": len(selection["recovery"]),
            "final_pair_rows": selection["final_pair_rows"],
            "degenerate_recovery_rows": selection["degenerate_recovery_rows"],
            "final_approved_safety_rows": len(selection["safety"]),
            "safety_contract_violations": selection["safety_contract_violations"],
            "development_approved_control_rows": len(selection["control"]),
        },
        "risk_exposure": risk_exposure,
        "recovery": recovery_arrays,
        "measurement": measurement,
        "control": control,
        "safety": safety,
        "probes": probes,
        "timing": timing,
        "gates": gates,
        "accepted_candidate": accepted,
        "limitations": [
            "the valid-OOV sets are development regression evidence, never final acceptance evidence",
        ] + (
            [] if gates["final_safety_sample_adequacy"]["status"] != "limitation"
            else [f"final approved safety sample of {final_safety_rows} rows is too small to establish safety"]
        ),
    }


def validate_report(report: Mapping[str, Any]) -> None:
    """Structural and arithmetic validation; raises AcceptanceError on any inconsistency."""
    if not isinstance(report, dict) or report.get("schema") != REPORT_SCHEMA:
        raise AcceptanceError(f"report schema is not {REPORT_SCHEMA}")
    slug = report["class_slug"]
    if slug not in CLASSES:
        raise AcceptanceError(f"unknown class slug {slug}")
    measurement = report["measurement"]
    recovery = report["recovery"]
    if report["eligible"]:
        if not isinstance(measurement, dict) or not isinstance(recovery, dict):
            raise AcceptanceError("eligible report lacks recovery arrays or measurement")
        try:
            # validate_gate_report reads report["measurement"]; the acceptance report stores the
            # measurement object directly, so it is wrapped for the shared validator.
            evaluation_metrics.validate_gate_report({"measurement": measurement})
        except evaluation_metrics.EvaluationMetricError as error:
            raise AcceptanceError(f"invalid paired measurement: {error}") from error
        ids = recovery["record_ids"]
        baseline_hits = recovery["baseline_hits"]
        candidate_hits = recovery["candidate_hits"]
        if not (len(ids) == len(baseline_hits) == len(candidate_hits) == measurement["units"]):
            raise AcceptanceError("recovery arrays do not match the paired unit count")
        if len(set(ids)) != len(ids):
            raise AcceptanceError("recovery record ids are not unique")
        for value in baseline_hits + candidate_hits:
            if value not in (0, 1):
                raise AcceptanceError("recovery hits must be binary per record")
        visible = measurement["metrics"]["visible_top3"]
        if visible["baseline"]["hits"] != sum(baseline_hits):
            raise AcceptanceError("stale baseline visible-top-3 hit total")
        if visible["candidate"]["hits"] != sum(candidate_hits):
            raise AcceptanceError("stale candidate visible-top-3 hit total")
        for metric in measurement["metrics"].values():
            if metric["baseline"]["denominator"] != len(ids):
                raise AcceptanceError("paired denominators do not equal the record count")
            if metric["candidate"]["denominator"] != metric["baseline"]["denominator"]:
                raise AcceptanceError("candidate and baseline denominators differ")
    else:
        if measurement is not None or recovery is not None:
            raise AcceptanceError("ineligible report must not carry a recovery measurement")
        if not report.get("ineligible_reason"):
            raise AcceptanceError("ineligible report lacks a reason")
    safety = report["safety"]
    combined = safety["combined"]
    per_source = sum(source["rows"] for source in safety["sources"])
    if per_source != combined["rows"]:
        raise AcceptanceError("safety sources do not cover the combined denominator")
    recomputed = sum(source["candidate_false_replacements"] for source in safety["sources"])
    if recomputed != combined["candidate_false_replacements"]:
        raise AcceptanceError("stale combined candidate false-replacement count")


def evaluate_gates(report: Mapping[str, Any]) -> dict[str, Any]:
    """Recompute the hard gate verdicts from the report arrays, independent of the bridge."""
    gates: dict[str, Any] = {}
    if report["eligible"]:
        measurement = report["measurement"]
        ci_low = measurement["metrics"]["visible_top3"]["delta"]["ci95"][0]
        gates["recovery_delta_ci95_above_zero"] = ci_low > 0
    safety = report["safety"]["combined"]
    gates["zero_candidate_false_replacements"] = safety["candidate_false_replacements"] == 0
    gates["zero_display_over_budget_lookups"] = (
        report["probes"]["candidate_fuzzy_over_budget_lookups"] == 0
    )
    gates["host_autocorrect_p95_within_budget"] = (
        report["timing"]["candidate"]["p95_ms"] <= TIMING_BUDGET_MS
    )
    if report["language"] == "tat":
        pairs = report["gates"]["exact_reviewed_pairs_preserved"]
        gates["exact_reviewed_pairs_preserved"] = (
            pairs["rows"] == pairs["baseline_fired"] == pairs["candidate_fired"] == 219
        )
    return gates


def measure_class(slug: str, outdir: Path, worksheet_dir: Path) -> dict[str, Any]:
    class_outdir = outdir / slug
    class_outdir.mkdir(parents=True, exist_ok=True)
    worksheet = worksheet_dir / f"worksheet-{slug}.tsv"
    selection = select_worksheet(slug, worksheet)
    exact_pairs = read_exact_pairs(EXACT_PAIRS_PATH)
    exact_safety = read_exact_safety(EXACT_SAFETY_PATH)
    valid_oov = regenerate_valid_oov(class_outdir)
    records = build_batch(slug, selection, exact_pairs, exact_safety, valid_oov)
    results, metadata = run_bridge(records, class_outdir)
    report = build_report(slug, worksheet, selection, exact_pairs, exact_safety, valid_oov,
                          results, metadata)
    validate_report(report)
    report_path = class_outdir / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                           encoding="utf-8")
    return report


def write_report_lines(report: Mapping[str, Any]) -> list[str]:
    lines = [
        f"dl2-acceptance: class={report['class_slug']} ({report['record_class']}) "
        f"eligible={report['eligible']}",
    ]
    for name, gate in report["gates"].items():
        detail = ""
        if "candidate_p95_ms" in gate:
            detail = f" p95={gate['candidate_p95_ms']}ms"
        elif "candidate_false_replacements" in gate:
            detail = f" false_replacements={gate['candidate_false_replacements']}"
        elif "ci95" in gate:
            detail = f" ci95={gate['ci95']}"
        lines.append(f"GATE|{name}|{gate['status']}{detail}")
    lines.append(f"RESULT|accepted_candidate={report['accepted_candidate']}")
    return lines


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    measure = commands.add_parser("measure", help="run the bridge and build per-class reports")
    measure.add_argument("--class", dest="classes", action="append", choices=CLASSES.keys())
    measure.add_argument("--all", action="store_true", help="measure every class")
    measure.add_argument("--outdir", default=str(ROOT / "research/dl2-evaluation/out"))
    measure.add_argument(
        "--worksheet-dir", default=str(REVIEW_DIR),
        help="directory holding worksheet-<slug>.tsv; the reviewed worksheets are not tracked",
    )
    validate = commands.add_parser("validate", help="validate saved reports")
    validate.add_argument("--report", action="append", required=True)
    gates = commands.add_parser("gates", help="recompute hard gate verdicts from saved reports")
    gates.add_argument("--report", action="append", required=True)
    args = parser.parse_args(argv)
    if args.command == "measure":
        slugs = list(CLASSES.keys()) if args.all else (args.classes or [])
        if not slugs:
            parser.error("measure needs --class or --all")
        failures = 0
        worksheet_dir = Path(args.worksheet_dir)
        for slug in slugs:
            report = measure_class(slug, Path(args.outdir), worksheet_dir)
            for line in write_report_lines(report):
                print(line)
            if not report["accepted_candidate"]:
                failures += 1
        return 0 if failures == 0 else 1
    for path in args.report:
        report = json.loads(Path(path).read_text(encoding="utf-8"))
        if args.command == "validate":
            validate_report(report)
            print(f"dl2-acceptance: {path} is valid ({report['class_slug']})")
        else:
            for name, passed in evaluate_gates(report).items():
                print(f"GATE|{report['class_slug']}|{name}|{'pass' if passed else 'fail'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
