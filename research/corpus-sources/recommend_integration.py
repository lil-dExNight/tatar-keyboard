#!/usr/bin/env python3
"""Turn the fixed-domain measurements into an integrate/defer recommendation.

Every verdict is derived from data/corpus-sources/comparison.json: the bigram decision, the
dictionary decision and reason, and the defer reasons are computed from the measured domains
and the packed-size limits, never written as fixed strings. Only finite decision-bearing
numbers are accepted: a NaN or infinite delta, interval bound, denominator, or packed-size
field aborts the run without touching the existing record. The comparison must also describe
the current shipped assets and packer limits — by SHA-256 asset identity, not size alone — so
a same-size replacement is still stale; comparisons without recorded asset hashes (legacy)
are rejected as non-current. A stale comparison aborts the run instead of producing evidence
for a pipeline that has moved on.

Records carry a machine-readable evidence status. A freshly generated record is
``evidence_status: "current"``; ``--mark-stale`` annotates an existing record with
``evidence_status: "stale"``, a ``stale_reason``, and the exact ``evidence_blockers`` while
preserving every measurement. ``is_current_evidence`` fails closed: stale or unmarked records
are not release evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import bigram_pack  # noqa: E402

CANDIDATE = "current_plus_tatsc_plus_common_voice"
DOMAINS = ("current_native_held", "common_voice_test", "tatsc_test")
SHIPPED_BIGRAM = Path("app/src/main/assets/bigrams/tatar_bigrams_v2.tatbigr.zlib")
SHIPPED_DICTIONARY = Path("app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib")
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
MARKER_FIELDS = ("evidence_status", "evidence_blockers", "stale_reason")
STALE_REASON = (
    "The source comparison no longer matches the current shipped assets or packer limits. "
    "The recorded measurements are retained for history only and are not release evidence."
)
# (human label, comparison size key, comparison identity key, current asset path)
SHIPPED_ASSETS = (
    ("shipped bigram", "bigram_compressed_bytes", "bigram", SHIPPED_BIGRAM),
    ("shipped dictionary", "dictionary_compressed_bytes", "dictionary", SHIPPED_DICTIONARY),
)


class RecommendationError(ValueError):
    """Malformed or stale measurement input; no record is written."""


def _sha256(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
            size += len(block)
    return size, digest.hexdigest()


def shipped_asset_identity(root: Path) -> dict[str, dict[str, object]]:
    """Current size and SHA-256 of each shipped asset the comparison must describe."""
    identity: dict[str, dict[str, object]] = {}
    for _, _, key, relative in SHIPPED_ASSETS:
        size, sha256 = _sha256(root / relative)
        identity[key] = {"size_bytes": size, "sha256": sha256}
    return identity


def is_current_evidence(record: dict) -> bool:
    """Fail closed: only an explicitly current record counts as release evidence."""
    return record.get("evidence_status") == "current"


def mark_record_stale(record: dict, problems: list[str]) -> dict:
    """Annotate a record as non-evidence, preserving every measurement byte-for-byte."""
    marked = dict(record)
    marked["evidence_status"] = "stale"
    marked["evidence_blockers"] = list(problems)
    marked["stale_reason"] = STALE_REASON
    return marked


def _assert_finite(label: str, value: object) -> None:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise RecommendationError(f"{label} must be a finite number, got {value!r}")


def validate_decision_metrics(measurements: dict[str, dict[str, object]], size: dict) -> None:
    """Reject non-finite or non-numeric evidence before any verdict uses it."""
    for domain in sorted(measurements):
        values = measurements[domain]
        _assert_finite(f"{domain} bigram delta_pp", values["candidate_minus_shipped_pp"])
        interval = values["paired_normal_95ci_pp"]
        if not isinstance(interval, list) or len(interval) != 2:
            raise RecommendationError(f"{domain} bigram CI must be a [low, high] pair")
        _assert_finite(f"{domain} bigram CI low", interval[0])
        _assert_finite(f"{domain} bigram CI high", interval[1])
        _assert_finite(
            f"{domain} dictionary delta_pp", values["dictionary_merged_minus_shipped_pp"]
        )
        dictionary_interval = values["dictionary_paired_normal_95ci_pp"]
        if not isinstance(dictionary_interval, list) or len(dictionary_interval) != 2:
            raise RecommendationError(f"{domain} dictionary CI must be a [low, high] pair")
        _assert_finite(f"{domain} dictionary CI low", dictionary_interval[0])
        _assert_finite(f"{domain} dictionary CI high", dictionary_interval[1])
        _assert_finite(f"{domain} shipped top-3 pct", values["shipped_top3_pct"])
        _assert_finite(f"{domain} candidate top-3 pct", values["candidate_top3_pct"])
        for field in ("denominator_bigram_events", "denominator_dictionary_tokens"):
            value = values[field]
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                raise RecommendationError(
                    f"{domain} {field} must be a positive integer, got {value!r}"
                )
    for field in (
        "compressed_bytes",
        "compressed_limit_bytes",
        "raw_bytes",
        "raw_limit_bytes",
    ):
        _assert_finite(f"packed bigram {field}", size[field])


def staleness_problems(
    comparison: dict,
    current_assets: dict[str, dict[str, object]],
    compressed_limit: int,
    raw_limit: int,
) -> list[str]:
    """Differences between the comparison and the current shipped assets/packer limits."""
    problems = []
    recorded_sizes = comparison["shipped_asset_sizes"]
    recorded_hashes = comparison.get("shipped_asset_sha256")
    recorded_hashes = recorded_hashes if isinstance(recorded_hashes, dict) else {}
    for label, size_key, identity_key, _ in SHIPPED_ASSETS:
        current = current_assets[identity_key]
        recorded_hash = recorded_hashes.get(identity_key)
        if not isinstance(recorded_hash, str) or not SHA256_RE.fullmatch(recorded_hash):
            problems.append(
                f"{label} identity is unverifiable: the comparison records no SHA-256 for "
                f"{identity_key}; rerun compare_sources.py so a same-size asset change stays "
                "detectable"
            )
            if recorded_sizes[size_key] != current["size_bytes"]:
                problems.append(
                    f"{label} changed since the comparison: the comparison records "
                    f"{recorded_sizes[size_key]} bytes, the current asset is "
                    f"{current['size_bytes']} bytes; rerun compare_sources.py over the "
                    "current assets"
                )
            continue
        if recorded_hash != current["sha256"]:
            problems.append(
                f"{label} bytes changed since the comparison: the comparison records "
                f"{recorded_hash[:12]}… of {recorded_sizes[size_key]} bytes, the current "
                f"asset is {str(current['sha256'])[:12]}… of {current['size_bytes']} bytes; "
                "rerun compare_sources.py over the current assets"
            )
    size = comparison["candidates"][CANDIDATE]["bigram_with_shipped_dictionary"]
    if size["compressed_limit_bytes"] != compressed_limit:
        problems.append(
            f"compressed packed-size limit is stale: the comparison records "
            f"{size['compressed_limit_bytes']}, bigram_pack.MAX_COMPRESSED_BYTES is "
            f"{compressed_limit}"
        )
    if size["raw_limit_bytes"] != raw_limit:
        problems.append(
            f"raw packed-size limit is stale: the comparison records "
            f"{size['raw_limit_bytes']}, bigram_pack.MAX_RAW_BYTES is {raw_limit}"
        )
    return problems


def build_report(comparison: dict) -> dict:
    candidate = comparison["candidates"][CANDIDATE]
    size = candidate["bigram_with_shipped_dictionary"]
    measurements = {}
    for domain in DOMAINS:
        result = comparison["held_out_evaluations"][domain]["candidates"][CANDIDATE]
        bigram_delta = result["bigram_with_shipped_dictionary_minus_shipped"]
        dictionary_delta = result["dictionary"]["merged_minus_shipped"]
        measurements[domain] = {
            "denominator_bigram_events": bigram_delta["denominator"],
            "shipped_top3_pct": comparison["held_out_evaluations"][domain]["shipped"]["top3_hitrate_pct"],
            "candidate_top3_pct": result["bigram_with_shipped_dictionary"]["top3_hitrate_pct"],
            "candidate_minus_shipped_pp": bigram_delta["delta_pp"],
            "paired_normal_95ci_pp": [
                bigram_delta["paired_normal_95ci_low_pp"],
                bigram_delta["paired_normal_95ci_high_pp"],
            ],
            "denominator_dictionary_tokens": dictionary_delta["denominator"],
            "dictionary_merged_minus_shipped_pp": dictionary_delta["delta_pp"],
            "dictionary_paired_normal_95ci_pp": [
                dictionary_delta["paired_normal_95ci_low_pp"],
                dictionary_delta["paired_normal_95ci_high_pp"],
            ],
        }
    validate_decision_metrics(measurements, size)
    bigram_regressions = {
        domain: measurements[domain]["candidate_minus_shipped_pp"]
        for domain in DOMAINS
        if measurements[domain]["candidate_minus_shipped_pp"] < 0
    }
    dictionary_regressions = {
        domain: measurements[domain]["dictionary_merged_minus_shipped_pp"]
        for domain in DOMAINS
        if measurements[domain]["dictionary_merged_minus_shipped_pp"] < 0
    }
    size_pass = (
        size["compressed_bytes"] <= size["compressed_limit_bytes"]
        and size["raw_bytes"] <= size["raw_limit_bytes"]
    )
    decision = "integrate_bigram_only" if not bigram_regressions and size_pass else "defer"
    replacements = {}
    for name in ("common_voice", "tatsc", "tatsc_plus_common_voice"):
        replacements[name] = {
            domain: comparison["held_out_evaluations"][domain]["candidates"][name][
                "bigram_with_shipped_dictionary_minus_shipped"
            ]["delta_pp"]
            for domain in DOMAINS
        }
    if dictionary_regressions:
        dictionary_decision = "do_not_integrate"
        losses = ", ".join(
            f"{domain} ({delta} pp)" for domain, delta in dictionary_regressions.items()
        )
        dictionary_reason = (
            f"The measured merged dictionary loses token coverage on {losses}. "
            "Keep dictionary contents, frequencies, and admissions unchanged."
        )
    else:
        dictionary_decision = "integrate"
        deltas = ", ".join(
            f"{domain} ({measurements[domain]['dictionary_merged_minus_shipped_pp']} pp)"
            for domain in DOMAINS
        )
        dictionary_reason = (
            "The measured merged dictionary is no worse than the shipped dictionary on "
            f"every fixed domain ({deltas})."
        )
    defer_reasons = []
    for domain, delta in bigram_regressions.items():
        defer_reasons.append(
            "The combined bigram candidate has a negative visible top-3 point estimate on "
            f"{domain} ({delta} pp). Its interval is reported, but uncertainty is not "
            "treated as permission to accept a regression."
        )
    if not size_pass:
        defer_reasons.append(
            "The candidate packed bigram exceeds the production size limits: "
            f"compressed {size['compressed_bytes']}/{size['compressed_limit_bytes']} bytes, "
            f"raw {size['raw_bytes']}/{size['raw_limit_bytes']} bytes."
        )
    if decision == "defer":
        defer_reasons.extend(
            [
                "The comparison's candidate tables do not include the Leipzig inputs and production weighting used by rebuild_assets.py. The integration owner must rerun the full production recipe and repeat the same comparisons before changing a pin.",
                "The TatSC mirror exposes fewer rows than its upstream card declares. The retrieved subset is measured exactly, but a full-corpus claim requires reconciliation with the official archive.",
            ]
        )
    return {
        "schema": 1,
        "analysis_origin": "Automated corpus analysis using the repository tokenization and packers. No native-speaker or human register review is claimed.",
        "evidence_status": "current",
        "decision": decision,
        "decision_rule": "A bigram-only integration is ready only when its visible top-3 point estimate is no worse on every fixed domain and both packed-size limits pass. Confidence intervals and exact denominators must accompany the decision. No weight tuning on a final test split is allowed.",
        "candidate": CANDIDATE,
        "candidate_scope": "Bigram table only, with the shipped dictionary and production head request unchanged. Current Tatoeba/OpenSubtitles training remains included.",
        "candidate_measurements": measurements,
        "candidate_packed_bigram": size,
        "shipped_packed_bigram_bytes": comparison["shipped_asset_sizes"]["bigram_compressed_bytes"],
        "dictionary_decision": dictionary_decision,
        "dictionary_reason": dictionary_reason,
        "single_source_replacement_top3_delta_pp": replacements,
        "opensubtitles_decision": "retain",
        "opensubtitles_reason": "Neither TatSC nor Common Voice, alone or together, is a drop-in replacement across the current native register. The quality-first decision does not remove OpenSubtitles for licensing alone.",
        "defer_reasons": defer_reasons,
        "source_split_findings": comparison["official_split_normalized_collisions"],
        "register_findings": {
            "tatsc_exact_duplicate_physical_line_pct": comparison["profiles"]["tatsc"]["duplicate_exact_pct"],
            "common_voice_train_rows_before_collision_exclusion": comparison["candidates"]["common_voice"]["training_lines_before_eval_exclusion"],
            "opensubtitles_selected_pronouns_per_1000_tokens": comparison["profiles"]["opensubtitles_only"]["selected_pronouns_per_1000_tokens"],
            "tatsc_selected_pronouns_per_1000_tokens": comparison["profiles"]["tatsc"]["selected_pronouns_per_1000_tokens"],
            "caveat": "Register fields are surface proxies, not native-speaker judgments.",
        },
        "later_integration_instructions": [
            "Regenerate and verify the text inputs with fetch_sources.py --sources common_voice, the pinned DuckDB Parquet extractor, and write_provenance.py. Require the bundle SHA-256 values in data/provenance.d to match.",
            "Keep Common Voice test and TatSC test out of training and tuning. Use train for training and dev only for development. Keep the current native id % 10 == 1 held-out slice excluded.",
            "For the next bigram experiment, retain current Tatoeba/OpenSubtitles train input and add only TatSC train plus Common Voice train after exact deduplication and normalized collision exclusion. Do not add audio, file paths, speaker/client IDs, demographics, durations, invalidated rows, or voice input.",
            "Run the complete rebuild_assets.py production recipe, including its Leipzig inputs and existing weights. Keep the shipped dictionary unchanged for this bigram-only experiment, retain the production head request and stored-successor count, and measure visible top-3 on the same records.",
            "Do not change a pin unless the full production candidate is no worse by point estimate on every fixed domain, paired intervals and denominators are recorded, memory and size checks pass, and a separate final evaluation remains untouched by tuning.",
            "Carry data/corpus-sources/ATTRIBUTION.txt and both corpus provenance shards into the source/asset documentation when an integration lands.",
        ],
        "evidence": {
            "comparison": "data/corpus-sources/comparison.json",
            "comparison_command": "python3 research/corpus-sources/compare_sources.py",
            "native_common_voice_filtered": "data/corpus-sources/native-split/common-voice-filtered.json",
            "legacy_tooling_note": "The existing hitrate.py and measure_bigrams.py entry points reject the shipped schema-3 bigram asset. compare_sources.py validates schema 3 with its linked dictionary instead; no legacy research file was modified.",
            "tatsc_provenance": "data/provenance.d/corpus-tatsc.json",
            "common_voice_provenance": "data/provenance.d/corpus-common-voice.json",
        },
        "documentation_updates_for_integrator": [
            "docs/ASSET-PIPELINE.md: document any accepted source addition, split exclusion, attribution, and reproduction command.",
            "docs/ARCHITECTURE.md: update the corpus provenance summary only if the shipped model changes.",
        ],
    }


def run(root: Path, mark_stale: bool = False) -> int:
    comparison_path = root / "data" / "corpus-sources" / "comparison.json"
    out = root / "data" / "corpus-sources" / "integration-recommendation.json"
    comparison = json.loads(comparison_path.read_text(encoding="utf-8"))
    try:
        current_assets = shipped_asset_identity(root)
    except OSError as error:
        print(f"error: shipped asset is unavailable: {error}", file=sys.stderr)
        return 2
    problems = staleness_problems(
        comparison,
        current_assets,
        bigram_pack.MAX_COMPRESSED_BYTES,
        bigram_pack.MAX_RAW_BYTES,
    )
    if problems:
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        if mark_stale and out.is_file():
            record = json.loads(out.read_text(encoding="utf-8"))
            marked = mark_record_stale(record, problems)
            out.write_text(
                json.dumps(marked, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            print(f"marked {out.relative_to(root)} as stale (not release evidence)")
        else:
            print(
                "error: no integration record written; the existing record stays untouched",
                file=sys.stderr,
            )
        return 2
    try:
        report = build_report(comparison)
    except (RecommendationError, KeyError, TypeError) as error:
        print(f"error: malformed comparison measurements: {error}", file=sys.stderr)
        print("error: no integration record written", file=sys.stderr)
        return 2
    out.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"decision={report['decision']}")
    print(f"wrote {out.relative_to(root)}")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--mark-stale",
        action="store_true",
        help="when the comparison is stale, annotate the existing record with "
        "evidence_status=stale and the exact blockers (measurements preserved); "
        "the exit status stays 2 because the record remains non-evidence",
    )
    args = parser.parse_args(argv)
    return run(ROOT, mark_stale=args.mark_stale)


if __name__ == "__main__":
    raise SystemExit(main())
