#!/usr/bin/env python3
"""Item-5 trigram acceptance: schema-3 baseline vs schema-4 candidate on the frozen final set.

The baseline is a real schema-3 bigram-only table packed with the unchanged production training
configuration (``rebuild_assets.bigram_pack_argv`` for the Tatar asset) against the current
linked dictionary; the candidate is the shipped combined schema-4 asset, never modified here.
Both arms are replayed once through the production ``CompositePrefixComputer`` NEXT_WORD chain
by ``TrigramFinalAcceptanceTest`` (JVM), which writes one JSON measurement. This driver packs
the baseline, invokes that measurement, computes paired confidence intervals through
``evaluation_metrics``, measures table/memory/APK costs and writes the machine-readable gate
report. The final set is never used for selection or tuning: ``measure`` refuses to overwrite
an existing report, and a shakedown must name a different evaluation set and report path.

Commands:
  baseline --work-dir DIR [--corpus-dir DIR]     Pack the schema-3 baseline into DIR.
  measure  --work-dir DIR --report FILE [--eval-set PATH]
                                                 Run the JVM replay once (default: final set).
  gate     --work-dir DIR --report FILE --out FILE [--apk PATH]
                                                 Build and validate the gate report.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import zlib
import zipfile
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

import bigram_asset_pack  # noqa: E402
import dictionary_pack  # noqa: E402
import evaluation_metrics  # noqa: E402
import rebuild_assets  # noqa: E402
import suggest_chain  # noqa: E402
import trigram_asset_pack  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EVAL = ROOT / "app" / "src" / "test" / "resources" / "tt_trigram_common_voice_final.txt"
DICTIONARY_ASSET = ROOT / rebuild_assets.TATAR_DICTIONARY_ASSET
CANDIDATE_ASSET = ROOT / rebuild_assets.BIGRAMS[0].asset
TATAR_BIGRAM = rebuild_assets.BIGRAMS[0]
TEST_CLASS = (
    "rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TrigramFinalAcceptanceTest"
)
APK_ASSET_ENTRY = "assets/bigrams/tatar_bigrams_v2.tatbigr.zlib"
JOB_RELATIVE = "dist/item05-trigram-acceptance/job.properties"
APK_SIZE_CEILING = 5_242_880
LATENCY_P95_BUDGET_NS = 5_000_000
DEFAULT_CORPUS_DIR = Path.home() / "corpora-leipzig"


class AcceptanceError(RuntimeError):
    """A missing, stale or inconsistent acceptance input."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _run(argv: Sequence[str], **kwargs) -> None:
    print("+ " + " ".join(str(part) for part in argv), file=sys.stderr)
    completed = subprocess.run([str(part) for part in argv], check=False, **kwargs)
    if completed.returncode != 0:
        raise AcceptanceError(f"command failed with code {completed.returncode}: {argv[0]}")


def pack_baseline(work_dir: Path, corpus_dir: Path) -> dict[str, Path]:
    """Pack the real schema-3 baseline with the frozen production configuration."""
    work_dir.mkdir(parents=True, exist_ok=True)
    argv = rebuild_assets.bigram_pack_argv(ROOT, corpus_dir, work_dir, TATAR_BIGRAM)
    _run(argv, cwd=ROOT)
    raw = rebuild_assets._schema3_raw_path(work_dir, TATAR_BIGRAM)
    compressed = raw.with_name(raw.name + ".zlib")
    report = work_dir / f"{TATAR_BIGRAM.tag}-pack.generated.json"
    for path in (raw, compressed, report):
        if not path.is_file():
            raise AcceptanceError(f"baseline pack did not produce {path}")
    return {"raw": raw, "compressed": compressed, "report": report}


def run_measurement(work_dir: Path, report: Path, eval_set: Path) -> None:
    """Invoke the JVM replay exactly once; an existing report is never overwritten."""
    if report.exists():
        raise AcceptanceError(f"report already exists (one replay only): {report}")
    if not eval_set.is_file():
        raise AcceptanceError(f"evaluation set is missing: {eval_set}")
    baseline_raw = rebuild_assets._schema3_raw_path(work_dir, TATAR_BIGRAM)
    baseline_asset = baseline_raw.with_name(baseline_raw.name + ".zlib")
    if not baseline_raw.is_file() or not baseline_asset.is_file():
        raise AcceptanceError("baseline is not packed; run the baseline command first")
    raw = bigram_asset_pack.decompress(baseline_asset.read_bytes())
    words, dictionary_raw = _dictionary_words_and_raw()
    parsed_v3 = bigram_asset_pack.validate_raw_v3(
        raw, words, hashlib.sha256(dictionary_raw).digest()
    )
    job = ROOT / JOB_RELATIVE
    if job.parent.resolve() != work_dir.resolve():
        raise AcceptanceError(f"work dir must be {job.parent} (the harness job location)")
    job.parent.mkdir(parents=True, exist_ok=True)
    job.write_text(
        "enabled=true\n"
        f"eval.set={eval_set.resolve()}\n"
        f"baseline.asset={baseline_asset.resolve()}\n"
        f"baseline.raw={baseline_raw.resolve()}\n"
        f"baseline.heads={len(parsed_v3.successes_by_head)}\n"
        f"report.out={report.resolve()}\n",
        encoding="utf-8",
    )
    try:
        _run(
            ["./gradlew", ":app:testDebugUnitTest", "--tests", TEST_CLASS, "--rerun"],
            cwd=ROOT,
        )
    finally:
        job.unlink(missing_ok=True)
    if not report.is_file():
        raise AcceptanceError(f"JVM replay did not write {report}")


def _dictionary_words_and_raw() -> tuple[list[str], bytes]:
    raw = dictionary_pack.decompress_asset(DICTIONARY_ASSET.read_bytes())
    parsed = dictionary_pack.validate_raw(raw)
    return list(parsed.words), raw


def load_measurement(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("sentences"), list):
        raise AcceptanceError(f"invalid measurement report: {path}")
    return data


def per_record_arrays(
    measurement: dict,
) -> tuple[list[int], dict[str, dict[str, list[int]]]]:
    """Per-sentence hits and denominators in shared record order for both arms."""
    sentences = measurement["sentences"]
    if not sentences:
        raise AcceptanceError("measurement has no sentences")
    denominators: list[int] = []
    arrays: dict[str, dict[str, list[int]]] = {
        "top1": {"baseline": [], "candidate": []},
        "top3": {"baseline": [], "candidate": []},
        "top4": {"baseline": [], "candidate": []},
    }
    for record in sentences:
        pairs = record["pairs"]
        if not isinstance(pairs, int) or pairs <= 0:
            raise AcceptanceError("a sentence record has a nonpositive pair count")
        denominators.append(pairs)
        for field in ("top1", "top3", "top4"):
            for arm in ("baseline", "candidate"):
                hits = record[f"{arm}_{field}"]
                if not isinstance(hits, int) or hits < 0 or hits > pairs:
                    raise AcceptanceError(f"invalid {arm} {field} hits: {hits!r}")
                arrays[field][arm].append(hits)
    return denominators, arrays


def _top1_report(
    baseline_hits: Sequence[int],
    candidate_hits: Sequence[int],
    denominators: Sequence[int],
) -> dict[str, object]:
    """Same shape as paired_metric_report, for the informational top-1 metric."""
    evaluation_metrics._validate_series(
        baseline_hits, denominators, candidate_hits, denominators
    )
    arms = {}
    for name, hits in (("baseline", baseline_hits), ("candidate", candidate_hits)):
        low, high = evaluation_metrics.rate_ci95(hits, denominators)
        numerator = sum(hits)
        denominator = sum(denominators)
        arms[name] = {
            "hits": numerator,
            "denominator": denominator,
            "pct": round(numerator * 100.0 / denominator, 6),
            "ci95": [round(low, 6), round(high, 6)],
        }
    low, high = evaluation_metrics.paired_delta_ci95(
        baseline_hits, denominators, candidate_hits, denominators
    )
    return {
        "k": 1,
        "role": "diagnostic",
        "baseline": arms["baseline"],
        "candidate": arms["candidate"],
        "delta": {
            "pp": round(arms["candidate"]["pct"] - arms["baseline"]["pct"], 6),
            "ci95": [round(low, 6), round(high, 6)],
            "paired": True,
            "method": evaluation_metrics.BOOTSTRAP_METHOD,
            "resampling": evaluation_metrics.SHARED_RESAMPLES,
            "rounds": suggest_chain.BOOTSTRAP_ROUNDS,
        },
    }


def deflate_size(data: bytes, level: int) -> int:
    compressor = zlib.compressobj(level, zlib.DEFLATED, -15)
    return len(compressor.compress(data) + compressor.flush())


def apk_size_delta(apk_path: Path, baseline_compressed: Path) -> dict[str, object]:
    """Measure the package delta of swapping only the Tatar table entry.

    The APK entry is deflated; the compression level is identified by reproducing the shipped
    entry's compressed size exactly, then applied to the baseline asset. Zip headers are
    unchanged (same entry name), so the APK delta is the entry's compressed-size delta.
    """
    if not apk_path.is_file():
        raise AcceptanceError(f"APK is missing: {apk_path}")
    with zipfile.ZipFile(apk_path) as archive:
        info = archive.getinfo(APK_ASSET_ENTRY)
        if info.compress_type != zipfile.ZIP_DEFLATED:
            raise AcceptanceError(f"{APK_ASSET_ENTRY} is not deflated")
        shipped = archive.read(APK_ASSET_ENTRY)
        shipped_csize = info.compress_size
    candidate = CANDIDATE_ASSET.read_bytes()
    if shipped != candidate:
        raise AcceptanceError("APK table entry differs from the shipped candidate asset")
    baseline = baseline_compressed.read_bytes()
    for level in range(1, 10):
        if deflate_size(candidate, level) == shipped_csize:
            break
    else:
        raise AcceptanceError("cannot reproduce the APK deflate level for the table entry")
    baseline_csize = deflate_size(baseline, level)
    apk_size = apk_path.stat().st_size
    return {
        "method": "single-entry-deflate-substitution",
        "deflate_level": level,
        "apk_path": str(apk_path),
        "candidate_apk_bytes": apk_size,
        "baseline_apk_bytes": apk_size - shipped_csize + baseline_csize,
        "delta_bytes": shipped_csize - baseline_csize,
        "candidate_entry_compressed_bytes": shipped_csize,
        "baseline_entry_compressed_bytes": baseline_csize,
        "ceiling_bytes": APK_SIZE_CEILING,
    }


def build_measurement_block(measurement: dict) -> dict[str, object]:
    """The validated paired-metrics block: visible top-3, engine top-4, diagnostic top-1."""
    denominators, arrays = per_record_arrays(measurement)
    visible = evaluation_metrics.paired_metric_report(
        arrays["top3"]["baseline"],
        denominators,
        arrays["top3"]["candidate"],
        denominators,
        k=evaluation_metrics.VISIBLE_TOP_K,
        role="user",
    )
    engine = evaluation_metrics.paired_metric_report(
        arrays["top4"]["baseline"],
        denominators,
        arrays["top4"]["candidate"],
        denominators,
        k=evaluation_metrics.ENGINE_TOP_K,
        role="diagnostic",
    )
    top1 = _top1_report(
        arrays["top1"]["baseline"], arrays["top1"]["candidate"], denominators
    )
    block = evaluation_metrics.measurement_report(
        units=len(denominators),
        denominator_name="next-word adjacent pairs (committed-word contexts)",
        visible_top3_report=visible,
        engine_top4_report=engine,
    )
    block["metrics"]["top1"] = top1
    return block


def evaluate_gates(
    block: dict[str, object],
    sizes: dict[str, int],
    latency: dict,
    apk: dict[str, object],
    *,
    bigram_part_identical: bool,
) -> tuple[dict[str, dict[str, object]], str]:
    """Pre-declared acceptance gates; the trigram candidate is rejected on any failure."""
    visible = block["metrics"]["visible_top3"]
    gates = {
        "visible_top3_improvement": {
            "rule": "candidate-minus-baseline visible top-3 pp has paired CI95 entirely above 0",
            "delta_pp": visible["delta"]["pp"],
            "ci95": visible["delta"]["ci95"],
            "pass": visible["delta"]["ci95"][0] > 0,
        },
        "latency": {
            "rule": "candidate NEXT_WORD p95 stays within the 5 ms production host budget",
            "candidate_p95_ns": latency["candidate"]["p95_ns"],
            "baseline_p95_ns": latency["baseline"]["p95_ns"],
            "pass": latency["candidate"]["p95_ns"] <= LATENCY_P95_BUDGET_NS,
        },
        "memory": {
            "rule": "both arms are mmap with zero retained-heap table payload; candidate raw "
            "stays within the shared table cap",
            "mmap_raw_delta_bytes": sizes["candidate_raw"] - sizes["baseline_raw"],
            "retained_heap_delta_bytes": 0,
            "pass": sizes["candidate_raw"] <= bigram_asset_pack.MAX_RAW_BYTES,
        },
        "package_size": {
            "rule": "candidate APK stays within the 5,242,880-byte release ceiling",
            "candidate_apk_bytes": apk["candidate_apk_bytes"],
            "delta_bytes": apk["delta_bytes"],
            "pass": apk["candidate_apk_bytes"] <= APK_SIZE_CEILING,
        },
        "bigram_part_parity": {
            "rule": "the candidate's bigram part decodes identically to the schema-3 baseline",
            "pass": bigram_part_identical,
        },
    }
    verdict = "ACCEPT" if all(gate["pass"] for gate in gates.values()) else "REJECT"
    return gates, verdict


def build_gate_report(
    measurement: dict, work_dir: Path, apk_path: Path, eval_set: Path
) -> dict[str, object]:
    block = build_measurement_block(measurement)

    baseline_raw = rebuild_assets._schema3_raw_path(work_dir, TATAR_BIGRAM)
    baseline_asset = baseline_raw.with_name(baseline_raw.name + ".zlib")
    dictionary_words, dictionary_raw = _dictionary_words_and_raw()
    candidate_raw = bigram_asset_pack.decompress(CANDIDATE_ASSET.read_bytes())
    baseline_raw_bytes = baseline_raw.read_bytes()
    baseline_parsed = bigram_asset_pack.validate_raw_v3(
        baseline_raw_bytes, dictionary_words, hashlib.sha256(dictionary_raw).digest()
    )
    candidate_parsed = trigram_asset_pack.validate_raw_v4(
        candidate_raw, dictionary_words, hashlib.sha256(dictionary_raw).digest()
    )
    bigram_part_identical = (
        baseline_parsed.successes_by_head == candidate_parsed.bigram.successes_by_head
    )
    sizes = measurement["sizes"]
    for name, actual in (
        ("baseline_raw", len(baseline_raw_bytes)),
        ("baseline_compressed", baseline_asset.stat().st_size),
        ("candidate_raw", len(candidate_raw)),
        ("candidate_compressed", CANDIDATE_ASSET.stat().st_size),
    ):
        if sizes[name] != actual:
            raise AcceptanceError(f"size mismatch for {name}: report {sizes[name]} vs {actual}")
    if eval_set.resolve() != DEFAULT_EVAL.resolve() or measurement["eval_sha256"] != sha256_file(
        eval_set
    ):
        raise AcceptanceError("evaluation set identity mismatch")

    latency = measurement["latency_ns"]
    allocation = measurement["allocation_bytes_per_round"]
    mmap_delta = sizes["candidate_raw"] - sizes["baseline_raw"]
    compressed_delta = sizes["candidate_compressed"] - sizes["baseline_compressed"]
    apk = apk_size_delta(apk_path, baseline_asset)
    costs = {
        "table_bytes": {
            **sizes,
            "raw_delta": mmap_delta,
            "compressed_delta": compressed_delta,
        },
        "memory": {
            "strategy": "mmap",
            "mmap_raw_delta_bytes": mmap_delta,
            "retained_heap_delta_bytes": 0,
            "heap_payload_bytes_per_arm": 0,
            "max_raw_bytes_cap": bigram_asset_pack.MAX_RAW_BYTES,
            "candidate_raw_within_cap": sizes["candidate_raw"]
            <= bigram_asset_pack.MAX_RAW_BYTES,
            "allocation_bytes_per_round": allocation,
            "allocation_delta_bytes": allocation["candidate"] - allocation["baseline"],
        },
        "latency_ns": {
            "baseline": latency["baseline"],
            "candidate": latency["candidate"],
            "delta_p50_ns": latency["candidate"]["p50_ns"] - latency["baseline"]["p50_ns"],
            "delta_p95_ns": latency["candidate"]["p95_ns"] - latency["baseline"]["p95_ns"],
            "delta_max_ns": latency["candidate"]["max_ns"] - latency["baseline"]["max_ns"],
            "budget_p95_ns": LATENCY_P95_BUDGET_NS,
        },
        "apk_bytes": apk,
    }
    gates, verdict = evaluate_gates(
        block, sizes, latency, apk, bigram_part_identical=bigram_part_identical
    )
    fingerprints = {
        "dictionary_asset": str(DICTIONARY_ASSET.relative_to(ROOT)),
        "dictionary_compressed_sha256": sha256_file(DICTIONARY_ASSET),
        "dictionary_raw_sha256": hashlib.sha256(dictionary_raw).hexdigest(),
        "baseline_schema": 3,
        "baseline_raw_sha256": hashlib.sha256(baseline_raw_bytes).hexdigest(),
        "baseline_compressed_sha256": sha256_file(baseline_asset),
        "candidate_schema": 4,
        "candidate_asset": str(CANDIDATE_ASSET.relative_to(ROOT)),
        "candidate_raw_sha256": hashlib.sha256(candidate_raw).hexdigest(),
        "candidate_compressed_sha256": sha256_file(CANDIDATE_ASSET),
        "final_set_path": str(eval_set.relative_to(ROOT)),
        "final_set_sha256": sha256_file(eval_set),
        "training_configuration": {
            "heads": TATAR_BIGRAM.heads,
            "successes_per_head": TATAR_BIGRAM.successes_per_head,
            "extra_heads": TATAR_BIGRAM.extra_heads,
            "extra_heads_sha256": sha256_file(ROOT / TATAR_BIGRAM.extra_heads)
            if TATAR_BIGRAM.extra_heads
            else None,
            "recover_punctuation": TATAR_BIGRAM.recover_punctuation,
            "train": list(TATAR_BIGRAM.train),
        },
        "bigram_part_identical": bigram_part_identical,
    }
    report = {
        "schema": "item-05-trigram-acceptance/v1",
        "verdict": verdict,
        "measurement": block,
        "trigram_covered_moments": measurement["trigram_covered_moments"],
        "moments": measurement["moments"],
        "costs": costs,
        "gates": gates,
        "fingerprints": fingerprints,
    }
    evaluation_metrics.validate_gate_report(report)
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)
    baseline = subparsers.add_parser("baseline")
    baseline.add_argument("--work-dir", type=Path, required=True)
    baseline.add_argument("--corpus-dir", type=Path, default=DEFAULT_CORPUS_DIR)
    measure = subparsers.add_parser("measure")
    measure.add_argument("--work-dir", type=Path, required=True)
    measure.add_argument("--report", type=Path, required=True)
    measure.add_argument("--eval-set", type=Path, default=DEFAULT_EVAL)
    gate = subparsers.add_parser("gate")
    gate.add_argument("--work-dir", type=Path, required=True)
    gate.add_argument("--report", type=Path, required=True)
    gate.add_argument("--out", type=Path, required=True)
    gate.add_argument("--eval-set", type=Path, default=DEFAULT_EVAL)
    gate.add_argument(
        "--apk",
        type=Path,
        default=ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk",
    )
    args = parser.parse_args(argv)
    try:
        if args.command == "baseline":
            paths = pack_baseline(args.work_dir, args.corpus_dir)
            print(json.dumps({key: str(value) for key, value in paths.items()}, indent=2))
        elif args.command == "measure":
            run_measurement(args.work_dir, args.report, args.eval_set)
            print(f"measurement written: {args.report}")
        else:
            report = build_gate_report(
                load_measurement(args.report), args.work_dir, args.apk, args.eval_set
            )
            args.out.parent.mkdir(parents=True, exist_ok=True)
            args.out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
            print(f"gate report: {args.out}")
            print(f"verdict: {report['verdict']}")
    except AcceptanceError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
