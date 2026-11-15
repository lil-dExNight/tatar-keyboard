#!/usr/bin/env python3
"""Compare current, TatSC, and Common Voice Tatar text on fixed held-out events.

The two external test domains are the upstream Common Voice test split and the combined
TatSC audiobooks/web test splits. Every candidate is trained with both domains' normalized
test sentences removed, so the same candidate tables can be compared without test leakage.
Official dev splits are never used for training. Current OpenSubtitles/Tatoeba input uses
its existing exact-line deduplication; it has no upstream train/dev/test split.

Dictionary and bigram construction mirrors the existing research tools: the shipped
100k-entry baseline, the current frequency filters, the production head count and extra-head
list, eight stored successors, and visible top-3 hits. Size measurements call the production
packers but write no asset.

Usage:
    python3 research/corpus-sources/fetch_sources.py
    python3 research/corpus-sources/compare_sources.py
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import statistics
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build" / "corpus-sources"
OUT = ROOT / "data" / "corpus-sources" / "comparison.json"
MANIFEST = BUILD / "sources.json"
RESEARCH = ROOT / "research" / "corpus"
sys.path.insert(0, str(RESEARCH))
sys.path.insert(0, str(ROOT / "scripts"))

import corpuslib as CL  # noqa: E402
import filters as F  # noqa: E402
import pairs as P  # noqa: E402
import bigram_asset_pack as BP  # noqa: E402
import bigram_pack as BIGRAM_PACK  # noqa: E402
import dictionary_coverage as COV  # noqa: E402
import dictionary_pack as DP  # noqa: E402
from stream import Split  # noqa: E402

LANG = COV.language_for("tat")
ALPHA = LANG.alphabet
H = 10_132
K = 8
EXTRA_HEADS = ROOT / "scripts" / "bigram_extra_heads_tat.txt"
CURRENT_PATHS = [
    RESEARCH / "Tatoeba-v2026-10-31.tt.txt.gz",
    RESEARCH / "OpenSubtitles-v2024.tt.txt.gz",
]
OS_PATH = RESEARCH / "OpenSubtitles-v2024.tt.txt.gz"
PRONOUNS = {"мин", "син", "ул", "без", "сез", "алар"}


def line_key(text: str) -> str:
    return " ".join(unicodedata.normalize("NFC", text).casefold().split())


def read_lines(path: Path) -> list[str]:
    with CL.open_text(path) as handle:
        return [line.rstrip("\r\n") for line in handle if line.strip()]


def file_provenance(path: Path) -> dict:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return {
        "path": str(path.relative_to(ROOT)),
        "size_bytes": path.stat().st_size,
        "sha256": digest.hexdigest(),
    }


def exact_dedup(lines: list[str]) -> tuple[list[str], int]:
    seen = set()
    result = []
    duplicates = 0
    for line in lines:
        key = line.strip()
        if not key or key in seen:
            duplicates += 1
            continue
        seen.add(key)
        result.append(line)
    return result, duplicates


def percentile(sorted_values: list[int], fraction: float) -> float:
    if not sorted_values:
        return 0.0
    position = min(len(sorted_values) - 1, max(0, math.ceil(fraction * len(sorted_values)) - 1))
    return float(sorted_values[position])


def profile(lines: list[str], shipped: set[str], split_lines: dict[str, int]) -> dict:
    unique, exact_duplicates = exact_dedup(lines)
    normalized = [line_key(line) for line in unique]
    normalized_counts = Counter(normalized)
    normalized_duplicates = len(normalized) - len(normalized_counts)
    raw_tokens = 0
    kept_tokens = 0
    shipped_hits = 0
    specific = 0
    russian_specific = 0
    pronouns = 0
    word_characters = 0
    lengths = []
    types = Counter()
    questions = exclamations = dialogue_dash = 0
    for line in unique:
        stripped = line.strip()
        questions += "?" in stripped or "？" in stripped
        exclamations += "!" in stripped
        dialogue_dash += stripped.startswith(("-", "–", "—"))
        raw_tokens += len(line.split())
        tokens = list(CL.dict_tokens(line, ALPHA))
        kept_tokens += len(tokens)
        lengths.append(len(tokens))
        for token in tokens:
            types[token] += 1
            shipped_hits += token in shipped
            specific += any(character in LANG.specific for character in token)
            russian_specific += any(character in "ёъ" for character in token)
            pronouns += token in PRONOUNS
            word_characters += len(token)
    lengths.sort()
    denominator = max(1, kept_tokens)
    return {
        "physical_lines": len(lines),
        "unique_exact_lines": len(unique),
        "duplicate_exact_lines": exact_duplicates,
        "duplicate_exact_pct": round(100 * exact_duplicates / max(1, len(lines)), 4),
        "unique_normalized_lines": len(normalized_counts),
        "duplicate_normalized_after_exact_dedup": normalized_duplicates,
        "split_unique_exact_lines": split_lines,
        "raw_whitespace_tokens": raw_tokens,
        "normalized_tokens": kept_tokens,
        "token_keep_pct": round(100 * kept_tokens / max(1, raw_tokens), 4),
        "unique_types": len(types),
        "types_with_frequency_at_least_3": sum(count >= 3 for count in types.values()),
        "hapax_types_pct": round(100 * sum(count == 1 for count in types.values()) / max(1, len(types)), 4),
        "mean_tokens_per_line": round(kept_tokens / max(1, len(unique)), 4),
        "median_tokens_per_line": percentile(lengths, 0.5),
        "p90_tokens_per_line": percentile(lengths, 0.9),
        "mean_word_characters": round(word_characters / denominator, 4),
        "tatar_specific_token_pct": round(100 * specific / denominator, 4),
        "tokens_containing_yo_or_hard_sign_pct": round(100 * russian_specific / denominator, 4),
        "selected_pronouns_per_1000_tokens": round(1000 * pronouns / denominator, 4),
        "question_lines_pct": round(100 * questions / max(1, len(unique)), 4),
        "exclamation_lines_pct": round(100 * exclamations / max(1, len(unique)), 4),
        "dialogue_dash_lines_pct": round(100 * dialogue_dash / max(1, len(unique)), 4),
        "shipped_dictionary_token_coverage_pct": round(100 * shipped_hits / denominator, 4),
    }


def load_official_splits(manifest: dict, source: str) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    grouped: dict[str, list[str]] = defaultdict(list)
    for record in manifest["files"]:
        if record["source"] != source:
            continue
        grouped[record["split"]].extend(read_lines(ROOT / record["text_path"]))
    result = {}
    for split, lines in grouped.items():
        result[split], _ = exact_dedup(lines)
    return result, dict(grouped)


def collision_report(splits: dict[str, list[str]]) -> dict:
    keys = {split: {line_key(line) for line in lines} for split, lines in splits.items()}
    names = sorted(keys)
    collisions = {}
    for index, left in enumerate(names):
        for right in names[index + 1 :]:
            collisions[f"{left}__{right}"] = len(keys[left] & keys[right])
    return collisions


def overlap_report(sources: dict[str, list[str]]) -> dict:
    keys = {name: {line_key(line) for line in lines} for name, lines in sources.items()}
    result = {}
    names = sorted(keys)
    for index, left in enumerate(names):
        for right in names[index + 1 :]:
            intersection = len(keys[left] & keys[right])
            union = len(keys[left] | keys[right])
            result[f"{left}__{right}"] = {
                "normalized_line_intersection": intersection,
                "jaccard_pct": round(100 * intersection / max(1, union), 6),
            }
    return result


def training_frequencies(lines: list[str]) -> tuple[Counter, F.CaseEvidence]:
    frequencies = Counter()
    evidence = F.CaseEvidence()
    for line in lines:
        first = True
        for chunk in line.split():
            word = chunk.strip(CL._EDGE)
            if not word:
                continue
            normalized, _ = COV.normalize_word(word, ALPHA)
            if normalized is not None:
                evidence.observe(word, normalized, first)
                frequencies[normalized] += 1
            first = False
    return frequencies, evidence


def read_extra_heads(vocabulary: set[str]) -> tuple[list[str], int]:
    result = []
    skipped = 0
    for line in EXTRA_HEADS.read_text(encoding="utf-8").splitlines():
        word = line.split("#", 1)[0].strip()
        if not word:
            continue
        if word in vocabulary:
            result.append(word)
        else:
            skipped += 1
    return result, skipped


def normalized_tokens(lines: list[str]) -> list[str]:
    result = []
    for line in lines:
        result.extend(CL.dict_tokens(line, ALPHA))
    return result


def bigram_events(lines: list[str]) -> list[tuple[str, str]]:
    result = []
    for line in lines:
        previous = None
        for chunk in line.split():
            normalized, _ = COV.normalize_word(chunk, ALPHA)
            if normalized is None:
                previous = None
                continue
            if previous is not None:
                result.append((previous, normalized))
            previous = normalized
    return result


def evaluate_table(events: list[tuple[str, str]], table: dict[str, list[str]]) -> dict:
    hits = []
    covered = 0
    for previous, token in events:
        cells = table.get(previous, ())[:3]
        covered += bool(cells)
        hits.append(token in cells)
    return {
        "events": len(events),
        "top3_hitrate_pct": round(100 * sum(hits) / max(1, len(hits)), 6),
        "events_with_a_prediction_pct": round(100 * covered / max(1, len(hits)), 6),
        "hits": hits,
    }


def paired_delta(left: list[bool], right: list[bool], denominator: int) -> dict:
    if len(left) != len(right):
        raise ValueError("paired vectors differ in length")
    differences = [int(a) - int(b) for a, b in zip(left, right)]
    count = len(differences)
    mean = sum(differences) / max(1, count)
    second_moment = sum(value * value for value in differences) / max(1, count)
    variance = max(0.0, second_moment - mean * mean)
    standard_error = math.sqrt(variance / max(1, count))
    return {
        "denominator": denominator,
        "delta_pp": round(100 * mean, 6),
        "paired_normal_95ci_low_pp": round(100 * (mean - 1.96 * standard_error), 6),
        "paired_normal_95ci_high_pp": round(100 * (mean + 1.96 * standard_error), 6),
        "left_only": sum(a and not b for a, b in zip(left, right)),
        "right_only": sum(b and not a for a, b in zip(left, right)),
    }


def evaluate_dictionary(lines: list[str], shipped: set[str], merged: set[str]) -> dict:
    tokens = normalized_tokens(lines)
    shipped_hits = [token in shipped for token in tokens]
    merged_hits = [token in merged for token in tokens]
    return {
        "tokens": len(tokens),
        "shipped_coverage_pct": round(100 * sum(shipped_hits) / max(1, len(tokens)), 6),
        "merged_coverage_pct": round(100 * sum(merged_hits) / max(1, len(tokens)), 6),
        "merged_minus_shipped": paired_delta(merged_hits, shipped_hits, len(tokens)),
    }


def build_candidate(
    name: str,
    lines: list[str],
    shipped_dict: dict[str, int],
    shipped_bigrams: dict[str, list[str]],
) -> tuple[dict, dict[str, list[str]], dict[str, list[str]], set[str]]:
    frequencies, evidence = training_frequencies(lines)
    kept, removed = F.apply_filters(frequencies, evidence, "tat")
    merged = dict(shipped_dict)
    for word, count in kept.items():
        merged[word] = merged.get(word, 0) + count
    ranked = sorted(merged.items(), key=lambda item: (-item[1], item[0]))
    merged_entries = ranked[:100_000]
    merged_vocabulary = {word for word, _count in merged_entries}
    heads_by_frequency = [word for word, _count in merged_entries[:H]]
    promoted, skipped_extra_heads = read_extra_heads(merged_vocabulary)
    head_set = set(heads_by_frequency)
    heads_by_frequency.extend(word for word in promoted if word not in head_set)

    pair_counts = P.count_pairs(lines, ALPHA, merged_vocabulary)
    head_ids = {pair_counts.ids[word] for word in heads_by_frequency if word in pair_counts.ids}
    grouped: dict[int, list[tuple[str, int]]] = defaultdict(list)
    for head_id, successor_id, count in pair_counts.items():
        if head_id in head_ids:
            grouped[head_id].append((pair_counts.words[successor_id], count))
    table_with_counts = {}
    for head_id, successors in grouped.items():
        successors.sort(key=lambda item: (-item[1], item[0]))
        table_with_counts[pair_counts.words[head_id]] = successors[:K]
    candidate_table = {head: [word for word, _count in successors] for head, successors in table_with_counts.items()}
    plus = {head: list(successors) for head, successors in shipped_bigrams.items()}
    for head, successors in candidate_table.items():
        if head in plus:
            for successor in successors:
                if successor not in plus[head]:
                    plus[head].append(successor)
        else:
            plus[head] = list(successors)

    dictionary_entries = sorted(merged_entries, key=lambda item: item[0])
    dictionary_raw = DP.serialize_entries(dictionary_entries, LANG)
    dictionary_asset = DP.compress_raw(dictionary_raw)
    DP.validate_raw(dictionary_raw, expected_count=len(dictionary_entries), language=LANG)
    word_index = {word: index for index, (word, _count) in enumerate(dictionary_entries)}
    bigram = BP.pack_bigram_table_v3(
        heads_by_frequency,
        table_with_counts,
        K,
        word_index,
        hashlib.sha256(dictionary_raw).digest(),
    )
    novel_successors = 0
    touched_shipped_heads = 0
    for head, successors in candidate_table.items():
        if head not in shipped_bigrams:
            continue
        touched_shipped_heads += 1
        novel_successors += sum(successor not in shipped_bigrams[head] for successor in successors)

    result = {
        "training_lines_after_eval_exclusion": len(lines),
        "types_before_filter": len(frequencies),
        "tokens_before_filter": sum(frequencies.values()),
        "types_after_filter": len(kept),
        "tokens_after_filter": sum(kept.values()),
        "removed_types": {reason: len(counter) for reason, counter in sorted(removed.items())},
        "pair_instances": pair_counts.instances,
        "distinct_pairs": len(pair_counts),
        "candidate_table_heads": len(candidate_table),
        "candidate_table_pairs": sum(len(successors) for successors in candidate_table.values()),
        "extra_heads_not_in_merged_dictionary": skipped_extra_heads,
        "touched_shipped_heads": touched_shipped_heads,
        "novel_successors_in_candidate_top8": novel_successors,
        "dictionary": {
            "entries": len(dictionary_entries),
            "raw_bytes": len(dictionary_raw),
            "compressed_bytes": len(dictionary_asset),
            "raw_limit_bytes": DP.MAX_UNCOMPRESSED_BYTES,
            "fits_raw": len(dictionary_raw) <= DP.MAX_UNCOMPRESSED_BYTES,
        },
        "bigram": {
            "heads": bigram.head_count,
            "pairs": bigram.pair_count,
            "raw_bytes": len(bigram.raw),
            "compressed_bytes": len(bigram.compressed),
            "raw_limit_bytes": BP.MAX_RAW_BYTES,
            "compressed_limit_bytes": BP.MAX_COMPRESSED_BYTES,
        },
    }
    return result, plus, candidate_table, merged_vocabulary


def build_bigram_with_shipped_dictionary(
    lines: list[str],
    shipped_dict: dict[str, int],
    production_heads: list[str],
    dictionary_raw: bytes,
) -> tuple[dict, dict[str, list[str]]]:
    vocabulary = frozenset(shipped_dict)
    requested_heads = set(production_heads)
    pair_counts = Counter()
    for line in lines:
        tokens = BIGRAM_PACK.normalized_tokens(line, ALPHA, recover_punctuation=True)
        for head, successor in BIGRAM_PACK.iter_pairs(tokens, vocabulary):
            if head in requested_heads:
                pair_counts[(head, successor)] += 1
    grouped: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for (head, successor), count in pair_counts.items():
        grouped[head].append((successor, count))
    table_with_counts = {}
    for head, successors in grouped.items():
        successors.sort(key=lambda item: (-item[1], item[0]))
        table_with_counts[head] = successors[:K]
    candidate_table = {head: [word for word, _count in successors] for head, successors in table_with_counts.items()}
    word_index = {word: index for index, word in enumerate(sorted(shipped_dict))}
    packed = BP.pack_bigram_table_v3(
        production_heads,
        table_with_counts,
        K,
        word_index,
        hashlib.sha256(dictionary_raw).digest(),
    )
    result = {
        "heads": packed.head_count,
        "pairs": packed.pair_count,
        "raw_bytes": len(packed.raw),
        "compressed_bytes": len(packed.compressed),
        "raw_limit_bytes": BP.MAX_RAW_BYTES,
        "compressed_limit_bytes": BP.MAX_COMPRESSED_BYTES,
        "pair_instances": sum(pair_counts.values()),
        "distinct_pairs": len(pair_counts),
    }
    return result, candidate_table


def load_shipped_bigrams(shipped_words: set[str]) -> dict[str, list[str]]:
    path = ROOT / "app/src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib"
    raw = BP.decompress(path.read_bytes())
    dictionary_raw = DP.decompress_asset(CL.SHIPPED["tat"].read_bytes(), LANG)
    try:
        parsed = BP.validate_raw(raw)
    except BP.BigramFormatError as error:
        if "unsupported schema id: 3" not in str(error):
            raise
        parsed = BP.validate_raw_v3(raw, sorted(shipped_words), hashlib.sha256(dictionary_raw).digest())
    return {head: list(successors) for head, successors in parsed.successes_by_head.items()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUT)
    arguments = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    shipped_dict, boundary = CL.load_shipped("tat")
    shipped_vocabulary = set(shipped_dict)
    shipped_bigrams = load_shipped_bigrams(shipped_vocabulary)
    shipped_dictionary_raw = DP.decompress_asset(CL.SHIPPED["tat"].read_bytes(), LANG)
    production_heads = [
        word
        for word, _count in sorted(shipped_dict.items(), key=lambda item: (-item[1], item[0]))[:H]
    ]
    promoted, skipped_production_heads = read_extra_heads(shipped_vocabulary)
    production_head_set = set(production_heads)
    production_heads.extend(word for word in promoted if word not in production_head_set)
    shipped_heads_outside_request = sorted(set(shipped_bigrams) - set(production_heads))
    requested_heads_without_shipped_pairs = len(set(production_heads) - set(shipped_bigrams))

    current_raw = [line for path in CURRENT_PATHS for line in read_lines(path)]
    opensubtitles_raw = read_lines(OS_PATH)
    current, _ = exact_dedup(current_raw)
    opensubtitles, _ = exact_dedup(opensubtitles_raw)
    tatsc_splits, tatsc_raw_splits = load_official_splits(manifest, "tatsc")
    cv_splits, cv_raw_splits = load_official_splits(manifest, "common_voice")
    tatsc_all_raw = [line for lines in tatsc_raw_splits.values() for line in lines]
    cv_all_raw = [line for lines in cv_raw_splits.values() for line in lines]

    source_lines = {
        "current_tatoeba_opensubtitles": current_raw,
        "opensubtitles_only": opensubtitles_raw,
        "tatsc": tatsc_all_raw,
        "common_voice": cv_all_raw,
    }
    profiles = {
        name: profile(lines, shipped_vocabulary, {})
        for name, lines in source_lines.items()
    }
    profiles["tatsc"]["split_unique_exact_lines"] = {split: len(lines) for split, lines in tatsc_splits.items()}
    profiles["common_voice"]["split_unique_exact_lines"] = {split: len(lines) for split, lines in cv_splits.items()}

    cv_test = cv_splits["test"]
    tatsc_test = tatsc_splits["test"]
    legacy_split = Split(CURRENT_PATHS, held_residue=1)
    current_native_held = [line.rstrip("\r\n") for line, _source in legacy_split.held()]
    eval_domains = {
        "common_voice_test": cv_test,
        "tatsc_test": tatsc_test,
        "current_native_held": current_native_held,
    }
    eval_keys = {line_key(line) for lines in eval_domains.values() for line in lines}
    train_lines = {
        "current_tatoeba_opensubtitles": current,
        "opensubtitles_only": opensubtitles,
        "tatsc": tatsc_splits["train"],
        "common_voice": cv_splits["train"],
        "tatsc_plus_common_voice": tatsc_splits["train"] + cv_splits["train"],
        "current_plus_tatsc": current + tatsc_splits["train"],
        "current_plus_tatsc_plus_common_voice": current + tatsc_splits["train"] + cv_splits["train"],
    }
    candidates = {}
    plus_tables = {}
    candidate_tables = {}
    production_bigram_tables = {}
    dictionaries = {}
    for name, lines in train_lines.items():
        before = len(lines)
        exact, _ = exact_dedup(lines)
        filtered = [line for line in exact if line_key(line) not in eval_keys]
        print(f"building {name}: {len(filtered)} lines", flush=True)
        result, plus, candidate_table, vocabulary = build_candidate(name, filtered, shipped_dict, shipped_bigrams)
        result["training_lines_before_eval_exclusion"] = before
        result["training_lines_removed_by_exact_dedup"] = before - len(exact)
        result["training_lines_removed_by_eval_collision"] = len(exact) - len(filtered)
        candidates[name] = result
        plus_tables[name] = plus
        candidate_tables[name] = candidate_table
        production_size, production_table = build_bigram_with_shipped_dictionary(
            filtered, shipped_dict, production_heads, shipped_dictionary_raw
        )
        result["bigram_with_shipped_dictionary"] = production_size
        production_bigram_tables[name] = production_table
        dictionaries[name] = vocabulary

    evaluations = {}
    baseline_by_domain = {}
    for domain, lines in eval_domains.items():
        events = bigram_events(lines)
        baseline = evaluate_table(events, shipped_bigrams)
        baseline_by_domain[domain] = baseline
        domain_result = {
            "test_lines": len(lines),
            "events": len(events),
            "tokens": len(normalized_tokens(lines)),
            "shipped": {key: value for key, value in baseline.items() if key != "hits"},
            "candidates": {},
        }
        for name in candidates:
            plus = evaluate_table(events, plus_tables[name])
            candidate_only = evaluate_table(events, candidate_tables[name])
            production_bigram = evaluate_table(events, production_bigram_tables[name])
            dictionary = evaluate_dictionary(lines, shipped_vocabulary, dictionaries[name])
            domain_result["candidates"][name] = {
                "bigram_with_shipped_dictionary": {key: value for key, value in production_bigram.items() if key != "hits"},
                "bigram_with_shipped_dictionary_minus_shipped": paired_delta(production_bigram["hits"], baseline["hits"], len(events)),
                "candidate_only": {key: value for key, value in candidate_only.items() if key != "hits"},
                "candidate_only_minus_shipped": paired_delta(candidate_only["hits"], baseline["hits"], len(events)),
                "shipped_plus": {key: value for key, value in plus.items() if key != "hits"},
                "shipped_plus_minus_shipped": paired_delta(plus["hits"], baseline["hits"], len(events)),
                "dictionary": dictionary,
            }
        current_plus = evaluate_table(events, plus_tables["current_tatoeba_opensubtitles"])
        current_candidate = evaluate_table(events, candidate_tables["current_tatoeba_opensubtitles"])
        current_production_bigram = evaluate_table(events, production_bigram_tables["current_tatoeba_opensubtitles"])
        for name in candidates:
            plus = evaluate_table(events, plus_tables[name])
            candidate_only = evaluate_table(events, candidate_tables[name])
            production_bigram = evaluate_table(events, production_bigram_tables[name])
            domain_result["candidates"][name]["shipped_plus_minus_current_plus"] = paired_delta(
                plus["hits"], current_plus["hits"], len(events)
            )
            domain_result["candidates"][name]["candidate_only_minus_current_candidate"] = paired_delta(
                candidate_only["hits"], current_candidate["hits"], len(events)
            )
            domain_result["candidates"][name]["bigram_with_shipped_dictionary_minus_current"] = paired_delta(
                production_bigram["hits"], current_production_bigram["hits"], len(events)
            )
        evaluations[domain] = domain_result

    result = {
        "schema": 1,
        "method": {
            "command": "python3 research/corpus-sources/compare_sources.py",
            "dictionary_boundary_frequency": boundary,
            "dictionary_merge_bound": "lower: words absent from the shipped asset receive only their candidate frequency; no Leipzig tail credit",
            "dictionary_entries": 100_000,
            "bigram_frequency_heads": H,
            "bigram_extra_heads": "scripts/bigram_extra_heads_tat.txt",
            "shipped_heads_outside_production_request": shipped_heads_outside_request,
            "requested_heads_without_shipped_pairs": requested_heads_without_shipped_pairs,
            "production_extra_heads_not_in_shipped_dictionary": skipped_production_heads,
            "stored_successors_per_head": K,
            "production_punctuation_recovery": True,
            "visible_successors": 3,
            "bigram_variants": "bigram_with_shipped_dictionary keeps the production head set and dictionary unchanged; candidate_only also reranks heads against a merged dictionary; shipped_plus appends without reranking shipped successors",
            "normalization": "NFC; existing dictionary/bigram token rules; line collisions use NFC + casefold + whitespace collapse",
            "confidence_intervals": "paired normal approximation on per-event hit differences; 95%; identical denominators",
            "training_exclusion": "All normalized sentences in the Common Voice test, TatSC test, and current native held-out domains are removed from every candidate's training input.",
            "size_measurement": "Production dictionary schema and TATBIGR schema-3 packers, in memory only; no bundled asset or pin is written.",
            "register_caveat": "Register fields are surface proxies, not human judgments of naturalness or domain labels.",
        },
        "input_provenance": {
            "manifest": "build/corpus-sources/sources.json",
            "current_files": [file_provenance(path) for path in CURRENT_PATHS],
            "source_files": manifest["files"],
            "tatsc_revision": manifest["datasets"]["tatsc"]["revision"],
            "common_voice_revision": manifest["datasets"]["common_voice"]["revision"],
        },
        "profiles": profiles,
        "official_split_normalized_collisions": {
            "tatsc": collision_report(tatsc_splits),
            "common_voice": collision_report(cv_splits),
        },
        "source_overlap": overlap_report(source_lines),
        "candidates": candidates,
        "held_out_evaluations": evaluations,
        "shipped_asset_sizes": {
            "dictionary_compressed_bytes": CL.SHIPPED["tat"].stat().st_size,
            "bigram_compressed_bytes": (ROOT / "app/src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib").stat().st_size,
        },
    }
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {arguments.output.relative_to(ROOT)}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
