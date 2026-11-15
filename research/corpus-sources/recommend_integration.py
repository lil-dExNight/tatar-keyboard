#!/usr/bin/env python3
"""Turn the fixed-domain measurements into an integrate/defer recommendation."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPARISON = ROOT / "data" / "corpus-sources" / "comparison.json"
OUT = ROOT / "data" / "corpus-sources" / "integration-recommendation.json"
CANDIDATE = "current_plus_tatsc_plus_common_voice"
DOMAINS = ("current_native_held", "common_voice_test", "tatsc_test")


def main() -> int:
    comparison = json.loads(COMPARISON.read_text(encoding="utf-8"))
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
    bigram_observed_non_regression = all(
        measurements[domain]["candidate_minus_shipped_pp"] >= 0 for domain in DOMAINS
    )
    dictionary_observed_non_regression = all(
        measurements[domain]["dictionary_merged_minus_shipped_pp"] >= 0 for domain in DOMAINS
    )
    size_pass = (
        size["compressed_bytes"] <= size["compressed_limit_bytes"]
        and size["raw_bytes"] <= size["raw_limit_bytes"]
    )
    decision = "integrate_bigram_only" if bigram_observed_non_regression and size_pass else "defer"
    replacements = {}
    for name in ("common_voice", "tatsc", "tatsc_plus_common_voice"):
        replacements[name] = {
            domain: comparison["held_out_evaluations"][domain]["candidates"][name][
                "bigram_with_shipped_dictionary_minus_shipped"
            ]["delta_pp"]
            for domain in DOMAINS
        }
    report = {
        "schema": 1,
        "analysis_origin": "Automated corpus analysis using the repository tokenization and packers. No native-speaker or human register review is claimed.",
        "decision": decision,
        "decision_rule": "A bigram-only integration is ready only when its visible top-3 point estimate is no worse on every fixed domain and both packed-size limits pass. Confidence intervals and exact denominators must accompany the decision. No weight tuning on a final test split is allowed.",
        "candidate": CANDIDATE,
        "candidate_scope": "Bigram table only, with the shipped dictionary and production head request unchanged. Current Tatoeba/OpenSubtitles training remains included.",
        "candidate_measurements": measurements,
        "candidate_packed_bigram": size,
        "shipped_packed_bigram_bytes": comparison["shipped_asset_sizes"]["bigram_compressed_bytes"],
        "dictionary_decision": "do_not_integrate",
        "dictionary_reason": "The measured merged dictionary loses token coverage on every fixed domain. Keep dictionary contents, frequencies, and admissions unchanged.",
        "single_source_replacement_top3_delta_pp": replacements,
        "opensubtitles_decision": "retain",
        "opensubtitles_reason": "Neither TatSC nor Common Voice, alone or together, is a drop-in replacement across the current native register. The quality-first decision does not remove OpenSubtitles for licensing alone.",
        "defer_reasons": [
            "The combined bigram candidate has a negative visible top-3 point estimate on Common Voice test. Its interval is reported, but uncertainty is not treated as permission to accept a regression.",
            "The comparison's candidate tables do not include the Leipzig inputs and production weighting used by rebuild_assets.py. The integration owner must rerun the full production recipe and repeat the same comparisons before changing a pin.",
            "The TatSC mirror exposes fewer rows than its upstream card declares. The retrieved subset is measured exactly, but a full-corpus claim requires reconciliation with the official archive.",
        ],
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
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"decision={decision}")
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
