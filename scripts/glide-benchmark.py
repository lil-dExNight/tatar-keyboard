#!/usr/bin/env python3
"""Replay recorded/synthetic glides through the production JVM decoder and compare reports.

The evaluator has no third-party Python dependencies and invokes Gradle offline. Its reports
contain no wall-clock timestamps or latency measurements, so identical inputs and sources produce
identical JSON. Use `freeze` to pin whole collection sessions/devices before final evaluation.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unicodedata
from typing import Any, Iterable, Sequence

ROOT = Path(__file__).resolve().parents[1]
INIT_SCRIPT = ROOT / "research/glide-evaluation/glide-benchmark.init.gradle"
REFERENCE_GEOMETRY_FIXTURE = (
    ROOT / "research/glide-evaluation/fixtures/reference-geometry.json"
)
REPORT_SCHEMA = "glide-benchmark-report-v1"
DATASET_SCHEMA = "glide-benchmark-dataset-v1"
SPLIT_POLICY = "whole-session-and-device-v1"
VISIBLE_TOP_N = 3
ENGINE_TOP_N = 4
TRIAL_KINDS = frozenset(
    {"ordinary", "tap", "tremor", "canceled", "short_drag", "unintended_start"}
)
SPEED_CONDITIONS = frozenset({"unspecified", "slow", "normal", "fast"})
# Derived speed is path length in key radii / wall-clock seconds. These reporting bins are not
# decoder knobs and never alter a decode. The instructed speed condition is reported separately.
SLOW_SPEED_MAX = 6.0
FAST_SPEED_MIN = 14.0
# Every production source the benchmark bridge compiles against or decodes through, so the
# decoder fingerprint changes whenever any of them changes: the glide decoder sources
# (including GlideUtf8's stored-UTF-8 word walk), the dictionary inventory/prefix index
# (TdictPrefixIndex also hosts DictionaryIdentity), AutocorrectPolicy (AutocorrectAdvice.kt),
# and the storage contracts/validator that open the pinned assets (DictionaryArtifactSpec and
# TdictFormat live in DictionaryStorageContracts.kt).
PRODUCTION_SOURCES = (
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/glide/GlideDecoder.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/glide/GlideWordIndex.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/glide/GlideIdealPaths.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/glide/GlideResampler.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/glide/GlidePath.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/glide/GlideKeyGeometry.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/glide/GlideWordInventory.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/glide/GlideUtf8.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/engine/TdictGlideInventory.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/engine/TdictPrefixIndex.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/engine/AutocorrectAdvice.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage/TdictValidator.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage/DictionaryStorageContracts.kt",
    "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage/StoreValidationToken.kt",
)
DICTIONARY_ASSETS = {
    "tt": "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
    "ru": "app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
}


class BenchmarkError(RuntimeError):
    pass


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fingerprint(value: Any) -> str:
    return sha256_bytes(canonical(value).encode("utf-8"))


def resolve_path(value: str | Path) -> Path:
    path = Path(value)
    if path.is_absolute():
        return path.resolve()
    from_cwd = path.resolve()
    if from_cwd.exists():
        return from_cwd
    return (ROOT / path).resolve()


def display_path(path: Path) -> str:
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path.resolve())


def write_text_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    try:
        temporary.write_text(text, encoding="utf-8", newline="\n")
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def write_json(path: Path, value: Any) -> None:
    write_text_atomic(path, json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n")


def finite_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def normalize_geometry(value: Any) -> dict[str, Any] | None:
    if value is None:
        return None
    if not isinstance(value, dict) or value.get("model") != "grid-v1":
        raise ValueError("unsupported key_geometry")
    keys = value.get("keys")
    if not isinstance(keys, list) or not keys:
        raise ValueError("key_geometry.keys must be non-empty")
    normalized = []
    for item in keys:
        if not isinstance(item, dict):
            raise ValueError("key geometry key must be an object")
        letter = item.get("letter")
        if not isinstance(letter, str) or len(letter) != 1:
            raise ValueError("key geometry letter must be one code point")
        rect = {}
        for side in ("left", "top", "right", "bottom"):
            number = item.get(side)
            if not finite_number(number):
                raise ValueError(f"key geometry {side} must be finite")
            rect[side] = int(number)
            if rect[side] != number:
                raise ValueError(f"key geometry {side} must be an integer")
        if rect["right"] <= rect["left"] or rect["bottom"] <= rect["top"]:
            raise ValueError("key geometry rectangle must be positive")
        more = item.get("more", [])
        if not isinstance(more, list) or any(not isinstance(x, str) or len(x) != 1 for x in more):
            raise ValueError("key geometry more keys must be one-code-point strings")
        normalized.append({"letter": letter, **rect, "more": sorted(more)})
    result = {"model": "grid-v1", "grid_width": value.get("grid_width", 100000), "keys": normalized}
    if not finite_number(result["grid_width"]) or result["grid_width"] <= 0:
        raise ValueError("key geometry grid width must be positive")
    result["grid_width"] = int(result["grid_width"])
    if "key_radius" in value:
        if not finite_number(value["key_radius"]) or value["key_radius"] <= 0:
            raise ValueError("key geometry radius must be positive")
        result["key_radius"] = value["key_radius"]
    return result


_reference_geometry_cache: dict[str, dict[str, Any]] = {}


def reference_geometry(layout: str) -> dict[str, Any]:
    """The concrete production reference key geometry for [layout], from the committed fixture.

    Records without key_geometry decode against the production layout's reference geometry
    (the bridge uses the same production fixture). The concrete fixture geometry — not a
    sentinel — is hashed into their geometry_key, so a layout-generation change is visible in
    every geometry identity a report carries. A missing or malformed fixture fails closed.
    """
    if not _reference_geometry_cache:
        if not REFERENCE_GEOMETRY_FIXTURE.is_file():
            raise BenchmarkError(f"missing reference geometry fixture: {REFERENCE_GEOMETRY_FIXTURE}")
        try:
            fixture = json.loads(REFERENCE_GEOMETRY_FIXTURE.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            raise BenchmarkError(f"reference geometry fixture is not JSON: {error}") from error
        if not isinstance(fixture, dict):
            raise BenchmarkError("reference geometry fixture must be an object per layout")
        for name in DICTIONARY_ASSETS:
            try:
                normalized = normalize_geometry(fixture.get(name))
            except ValueError as error:
                raise BenchmarkError(f"reference geometry fixture {name}: {error}") from error
            if normalized is None:
                raise BenchmarkError(f"reference geometry fixture has no {name} geometry")
            _reference_geometry_cache[name] = normalized
    return _reference_geometry_cache[layout]


def normalize_record(
    raw: Any,
    *,
    source: dict[str, Any],
    line_number: int,
    record_index: int,
) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError("record must be a JSON object")
    schema = raw.get("collector_schema", 1)
    if schema not in (1, 2):
        raise ValueError(f"unsupported collector_schema {schema!r}")
    prompt = raw.get("prompt")
    layout = raw.get("layout_id")
    if not isinstance(prompt, str) or not prompt.strip():
        raise ValueError("prompt must be a non-empty string")
    if layout not in DICTIONARY_ASSETS:
        raise ValueError(f"unsupported layout_id {layout!r}")
    arrays = []
    for key in ("path_x", "path_y", "dt_ms"):
        value = raw.get(key)
        if not isinstance(value, list) or not 1 <= len(value) <= 512:
            raise ValueError(f"{key} must contain 1..512 samples")
        if any(not finite_number(number) for number in value):
            raise ValueError(f"{key} contains a non-finite number")
        arrays.append(value)
    xs, ys, dts = arrays
    if len(xs) != len(ys) or len(xs) != len(dts):
        raise ValueError("path arrays have different lengths")
    if any(number < 0 for number in dts) or (dts and dts[0] != 0):
        raise ValueError("dt_ms must start at zero and be nondecreasing")
    gate_passed = raw.get("gate_passed", True)
    if not isinstance(gate_passed, bool):
        raise ValueError("gate_passed must be boolean")
    trial_kind = raw.get("trial_kind", "ordinary")
    if trial_kind not in TRIAL_KINDS:
        raise ValueError(f"unsupported trial_kind {trial_kind!r}")
    speed_condition = raw.get("speed_condition", "unspecified")
    if speed_condition not in SPEED_CONDITIONS:
        raise ValueError(f"unsupported speed_condition {speed_condition!r}")
    trace_status = raw.get("trace_status", "completed")
    if trace_status not in ("completed", "canceled"):
        raise ValueError("trace_status must be completed or canceled")
    geometry = normalize_geometry(raw.get("key_geometry"))
    participant = raw.get("participant")
    explicit_session = isinstance(raw.get("session_id"), str) and bool(raw["session_id"])
    explicit_device = isinstance(raw.get("device_id"), str) and bool(raw["device_id"])
    session = raw["session_id"] if explicit_session else f"legacy-session:{source['sha256'][:16]}"
    if explicit_device:
        device = raw["device_id"]
        device_source = "explicit"
    elif isinstance(participant, str) and participant:
        device = f"participant-fallback:{participant}"
        device_source = "participant_fallback"
    else:
        device = f"unknown-device:{source['sha256'][:16]}"
        device_source = "input_fallback"
    record_id = raw.get("record_id")
    if not isinstance(record_id, str) or not record_id:
        record_id = f"{source['path']}:{line_number}"
    normalized = {
        "record_index": record_index,
        "record_id": record_id,
        "prompt": prompt,
        "layout_id": layout,
        "path_x": xs,
        "path_y": ys,
        "dt_ms": dts,
        "key_geometry": geometry,
        "session_id": session,
        "device_id": device,
        "device_id_source": device_source,
        "explicit_session_id": explicit_session,
        "explicit_device_id": explicit_device,
        "participant": participant if isinstance(participant, str) else None,
        "density_bucket": raw.get("density_bucket"),
        "board_w": raw.get("board_w"),
        "board_h": raw.get("board_h"),
        "orientation": raw.get("orientation", "unspecified"),
        "trial_kind": trial_kind,
        "speed_condition": speed_condition,
        "trace_status": trace_status,
        "gate_passed": gate_passed,
        "rejection_reason": raw.get("rejection_reason") or (None if gate_passed else "unspecified"),
        "collector_schema": schema,
        "synthetic_variant": raw.get("synthetic_variant") if isinstance(raw.get("synthetic_variant"), str) else None,
        "source_path": source["path"],
        "source_line": line_number,
        "source_sha256": source["sha256"],
    }
    normalized["geometry_key"] = geometry if geometry is not None else reference_geometry(layout)
    normalized["geometry_id"] = fingerprint(normalized["geometry_key"])[:16]
    normalized["record_fingerprint"] = fingerprint(
        {key: normalized[key] for key in (
            "prompt", "layout_id", "path_x", "path_y", "dt_ms", "key_geometry",
            "session_id", "device_id", "trial_kind", "gate_passed", "rejection_reason",
            "synthetic_variant",
        )}
    )
    return normalized


def load_inputs(paths: Sequence[str | Path]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    if not paths:
        raise BenchmarkError("at least one input JSONL file is required")
    records: list[dict[str, Any]] = []
    invalid: list[dict[str, Any]] = []
    sources: list[dict[str, Any]] = []
    for value in paths:
        path = resolve_path(value)
        if not path.is_file():
            raise BenchmarkError(f"input does not exist: {path}")
        raw_bytes = path.read_bytes()
        source = {
            "path": display_path(path),
            "resolved_path": str(path),
            "size_bytes": len(raw_bytes),
            "sha256": sha256_bytes(raw_bytes),
        }
        try:
            text = raw_bytes.decode("utf-8")
        except UnicodeDecodeError as error:
            raise BenchmarkError(f"input is not UTF-8: {path}: {error}") from error
        source["records"] = 0
        source["invalid_records"] = 0
        for line_number, line in enumerate(text.splitlines(), 1):
            if not line.strip():
                continue
            source["records"] += 1
            try:
                raw = json.loads(line)
                record = normalize_record(
                    raw, source=source, line_number=line_number, record_index=len(records)
                )
                records.append(record)
            except (ValueError, TypeError, json.JSONDecodeError) as error:
                source["invalid_records"] += 1
                invalid.append({
                    "source_path": source["path"], "source_line": line_number,
                    "source_sha256": source["sha256"], "reason": str(error),
                })
        if source["records"] == 0:
            raise BenchmarkError(f"input has no records: {path}")
        sources.append(source)
    return records, invalid, sources


def public_source(source: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in source.items() if key != "resolved_path"}


def dataset_fingerprint(split: str, sources: Sequence[dict[str, Any]]) -> str:
    return fingerprint({
        "policy": SPLIT_POLICY,
        "split": split,
        "inputs": sorted(
            ({"sha256": source["sha256"], "size_bytes": source["size_bytes"]} for source in sources),
            key=lambda item: item["sha256"],
        ),
    })


def freeze_dataset(args: argparse.Namespace) -> dict[str, Any]:
    split_inputs = {"development": args.development_input, "final": args.final_input}
    split_data = {}
    identities: dict[str, dict[str, set[str]]] = {}
    for split, paths in split_inputs.items():
        if not paths:
            split_data[split] = {"frozen": False, "inputs": [], "sessions": [], "devices": []}
            identities[split] = {"sessions": set(), "devices": set()}
            continue
        records, invalid, sources = load_inputs(paths)
        check_geometry_consistency(records)
        if invalid:
            raise BenchmarkError(f"cannot freeze {split}: {len(invalid)} invalid records must be fixed, not dropped")
        if split == "final":
            missing = [r["record_id"] for r in records if not r["explicit_session_id"] or not r["explicit_device_id"]]
            if missing:
                raise BenchmarkError(
                    "final records need explicit session_id and device_id; legacy fallbacks are development-only"
                )
        sessions = sorted({r["session_id"] for r in records})
        devices = sorted({r["device_id"] for r in records})
        identities[split] = {"sessions": set(sessions), "devices": set(devices)}
        split_data[split] = {
            "frozen": True,
            "split_fingerprint": dataset_fingerprint(split, sources),
            "inputs": [public_source(source) for source in sources],
            "sessions": sessions,
            "devices": devices,
            "records": len(records),
            "source_kind": args.source_kind,
        }
    for kind in ("sessions", "devices"):
        overlap = identities["development"][kind] & identities["final"][kind]
        if overlap:
            raise BenchmarkError(f"{kind} leak across development/final: {sorted(overlap)[:5]}")
    manifest = {
        "schema": DATASET_SCHEMA,
        "split_policy": SPLIT_POLICY,
        "freeze_rule": "final is immutable before decoder tuning; no per-record partitioning",
        "splits": split_data,
    }
    write_json(resolve_path(args.output), manifest)
    return manifest


def manifest_inputs(path: Path, split: str) -> tuple[list[str], dict[str, Any], bool]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BenchmarkError(f"cannot read dataset manifest {path}: {error}") from error
    if manifest.get("schema") != DATASET_SCHEMA or manifest.get("split_policy") != SPLIT_POLICY:
        raise BenchmarkError("unsupported dataset manifest")
    selected = manifest.get("splits", {}).get(split)
    if not isinstance(selected, dict) or not selected.get("frozen"):
        raise BenchmarkError(f"dataset split {split} is not frozen")
    inputs = selected.get("inputs")
    if not isinstance(inputs, list) or not inputs:
        raise BenchmarkError(f"dataset split {split} has no inputs")
    paths = []
    for source in inputs:
        resolved = resolve_path(source["path"])
        if not resolved.is_file():
            raise BenchmarkError(f"frozen input missing: {resolved}")
        if resolved.stat().st_size != source["size_bytes"] or sha256_file(resolved) != source["sha256"]:
            raise BenchmarkError(f"frozen input changed: {source['path']}")
        paths.append(str(resolved))
    expected = selected.get("split_fingerprint")
    actual = dataset_fingerprint(split, inputs)
    if expected != actual:
        raise BenchmarkError("dataset manifest split fingerprint does not match its inputs")
    return paths, manifest, True


def run_production_bridge(records: Sequence[dict[str, Any]]) -> tuple[dict[int, dict[str, Any]], dict[str, Any]]:
    if not records:
        return {}, {"geometries": {}, "production_decoder": None}
    if not INIT_SCRIPT.is_file():
        raise BenchmarkError(f"missing benchmark bridge wiring: {INIT_SCRIPT}")
    with tempfile.TemporaryDirectory(prefix="glide-benchmark-") as directory:
        temporary = Path(directory)
        normalized_path = temporary / "records.jsonl"
        output_path = temporary / "decoded.jsonl"
        normalized_path.write_text(
            "".join(canonical(record) + "\n" for record in records), encoding="utf-8", newline="\n"
        )
        env = os.environ.copy()
        env.update({
            "GLIDE_BENCHMARK_INPUT": str(normalized_path),
            "GLIDE_BENCHMARK_OUTPUT": str(output_path),
            "GLIDE_BENCHMARK_ROOT": str(ROOT),
        })
        command = [
            str(ROOT / "gradlew"), "--offline", "--console=plain", "-I", str(INIT_SCRIPT),
            ":app:glideBenchmark",
        ]
        completed = subprocess.run(command, cwd=ROOT, env=env, check=False)
        if completed.returncode != 0:
            raise BenchmarkError(f"production decoder bridge failed with exit code {completed.returncode}")
        if not output_path.is_file():
            raise BenchmarkError("production decoder bridge wrote no output")
        results: dict[int, dict[str, Any]] = {}
        metadata: dict[str, Any] = {}
        for line in output_path.read_text(encoding="utf-8").splitlines():
            value = json.loads(line)
            if value.get("type") == "metadata":
                metadata = value
                continue
            if value.get("type") != "record":
                raise BenchmarkError("unknown bridge output row")
            index = value["record_index"]
            if index in results:
                raise BenchmarkError(f"duplicate bridge record_index {index}")
            results[index] = value
        expected = {record["record_index"] for record in records}
        if set(results) != expected:
            raise BenchmarkError("production decoder bridge did not return every input record exactly once")
        return results, metadata


def path_metrics(record: dict[str, Any]) -> tuple[float, int, float, str]:
    xs, ys, dts = record["path_x"], record["path_y"], record["dt_ms"]
    length = sum(math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) for i in range(1, len(xs)))
    duration_ms = int(round(sum(dts)))
    if duration_ms <= 0:
        return length, duration_ms, 0.0, "unrated"
    speed = length * 1000.0 / duration_ms
    bucket = "slow" if speed < SLOW_SPEED_MAX else "fast" if speed >= FAST_SPEED_MIN else "normal"
    return length, duration_ms, speed, bucket


def path_vertical_transition_count(record: dict[str, Any]) -> int:
    """Count adjacent-sample changes between nearest key-row bands in the recorded path.

    Path coordinates are normalized by the geometry-derived key radius. Row centers come from the
    record's concrete geometry, never from the prompt or decoder result. A midpoint tie selects
    the upper row, making the result deterministic. A segment that skips a row is one transition.
    """
    geometry = record["geometry_key"]
    keys = geometry["keys"]
    radius = min(min(key["right"] - key["left"], key["bottom"] - key["top"]) for key in keys)
    row_centers = sorted({key["top"] + key["bottom"] for key in keys})
    previous_row = None
    transitions = 0
    for normalized_y in record["path_y"]:
        doubled_y = 2.0 * normalized_y * radius
        row = min(range(len(row_centers)), key=lambda index: (abs(doubled_y - row_centers[index]), index))
        if previous_row is not None and row != previous_row:
            transitions += 1
        previous_row = row
    return transitions


def error_class(record: dict[str, Any], decoded: dict[str, Any]) -> str:
    if record["trial_kind"] != "ordinary":
        return "negative_control"
    if decoded["rank"] == 1:
        return "none"
    if decoded["unmappable_letters"]:
        return "unmappable"
    if decoded["target_stage"] == "out_of_vocabulary":
        return "out_of_vocabulary"
    if not record["gate_passed"]:
        return "collector_rejected"
    if decoded["path_too_short"]:
        return "path_too_short"
    if decoded["endpoint_start_pruned"] or decoded["endpoint_end_pruned"]:
        return "endpoint_pruned"
    if decoded["length_pruned"]:
        return "length_pruned"
    if decoded["decode_empty"]:
        return "decoder_rejected"
    if decoded["rank"] == 4:
        return "visible_top3_miss_engine_rank4"
    if decoded["rank"] in (2, 3):
        return "rank_below_top1"
    return "below_engine_top4"


def rejection_reason(record: dict[str, Any], decoded: dict[str, Any], error: str) -> str | None:
    if not record["gate_passed"]:
        return f"collector:{record['rejection_reason']}"
    if decoded["path_too_short"]:
        return "decoder:path_too_short"
    if decoded["decode_empty"]:
        return f"decoder:empty:{decoded['target_stage']}"
    if record["trial_kind"] != "ordinary" and not decoded["decode_empty"]:
        return "negative_control:unexpected_candidate"
    if error in ("none", "negative_control"):
        return None
    return f"target:{error}"


def evaluate_records(
    records: Sequence[dict[str, Any]], results: dict[int, dict[str, Any]]
) -> list[dict[str, Any]]:
    evaluated = []
    for record in records:
        decoded = results[record["record_index"]]
        length, duration, speed, speed_bucket = path_metrics(record)
        recorded_vertical_transitions = path_vertical_transition_count(record)
        error = error_class(record, decoded)
        classes = ["in_vocabulary" if decoded["target_indexed"] else "out_of_vocabulary"]
        if decoded["unmappable_letters"]:
            classes.append("unmappable")
        if decoded["doubled_letter"]:
            classes.append("doubled_letter")
        if decoded["vertical_transition_count"]:
            classes.append("vertical_transition")
        for letter in decoded["alias_letters"]:
            classes.extend(("alias", f"alias:{letter}"))
        for letter in decoded["extra_row_letters"]:
            classes.extend(("extra_row", f"extra_row:{letter}"))
        positive = record["trial_kind"] == "ordinary"
        evaluated.append({
            "record_index": record["record_index"],
            "record_id": record["record_id"],
            "record_fingerprint": record["record_fingerprint"],
            "source_path": record["source_path"],
            "source_line": record["source_line"],
            "session_id": record["session_id"],
            "device_id": record["device_id"],
            "device_id_source": record["device_id_source"],
            "layout_id": record["layout_id"],
            "prompt": record["prompt"],
            "normalized_prompt": decoded["normalized_prompt"],
            "positive": positive,
            "trial_kind": record["trial_kind"],
            "trace_status": record["trace_status"],
            "synthetic_variant": record["synthetic_variant"],
            "gate_passed": record["gate_passed"],
            "input_rejection_reason": record["rejection_reason"],
            "rank": decoded["rank"],
            "top1": positive and decoded["rank"] == 1,
            "visible_top3": positive and 1 <= decoded["rank"] <= VISIBLE_TOP_N,
            "engine_top4": positive and 1 <= decoded["rank"] <= ENGINE_TOP_N,
            "candidates": decoded["candidates"],
            "visible_candidates": decoded["candidates"][:VISIBLE_TOP_N],
            "decode_empty": decoded["decode_empty"],
            "error_class": error,
            "rejection_reason": rejection_reason(record, decoded, error),
            "target_stage": decoded["target_stage"],
            "unmappable_letters": decoded["unmappable_letters"],
            "pruning": {
                "endpoint_start": decoded["endpoint_start_pruned"],
                "endpoint_end": decoded["endpoint_end_pruned"],
                "length": decoded["length_pruned"],
            },
            "candidate_count_after_endpoint_pruning": decoded["candidate_count_after_endpoint_pruning"],
            "fully_scored_candidate_count": decoded["fully_scored_candidate_count"],
            "classes": sorted(set(classes)),
            "alias_letters": decoded["alias_letters"],
            "doubled_letter": decoded["doubled_letter"],
            "vertical_transition_count": decoded["vertical_transition_count"],
            "path_vertical_transition_count": recorded_vertical_transitions,
            "extra_row_letters": decoded["extra_row_letters"],
            "length_key_radii": round(length, 6),
            "duration_ms": duration,
            "speed_key_radii_per_second": round(speed, 6),
            "speed_bucket": speed_bucket,
            "instructed_speed": record["speed_condition"],
            "orientation": record["orientation"],
            "geometry_id": record["geometry_id"],
            "geometry_bucket": (
                f"{record['geometry_id']}:board={record['board_w']}x{record['board_h']}"
                f":density={record['density_bucket']}:orientation={record['orientation']}"
            ),
            "density_bucket": record["density_bucket"],
            "board_w": record["board_w"],
            "board_h": record["board_h"],
        })
    return evaluated


def ratio(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 6) if denominator else None


def summarize(evaluated: Sequence[dict[str, Any]]) -> dict[str, Any]:
    positive = [record for record in evaluated if record["positive"]]
    accepted = [record for record in positive if record["gate_passed"]]
    negative = [record for record in evaluated if not record["positive"]]
    errors = collections.Counter(record["error_class"] for record in positive if record["error_class"] != "none")
    stages = collections.Counter(record["target_stage"] for record in positive)
    collector_reasons = collections.Counter(
        str(record["input_rejection_reason"]) for record in evaluated if not record["gate_passed"]
    )
    path_vertical_counts = [
        record["path_vertical_transition_count"]
        for record in evaluated if record["positive"]
    ]
    records_with_path_vertical = sum(count > 0 for count in path_vertical_counts)
    return {
        "records": len(evaluated),
        "positive_records": len(positive),
        "gate_accepted_positive_records": len(accepted),
        "negative_control_records": len(negative),
        "top1": sum(record["top1"] for record in positive),
        "visible_top3": sum(record["visible_top3"] for record in positive),
        "engine_top4": sum(record["engine_top4"] for record in positive),
        "top1_rate": ratio(sum(record["top1"] for record in positive), len(positive)),
        "visible_top3_rate": ratio(sum(record["visible_top3"] for record in positive), len(positive)),
        "engine_top4_rate": ratio(sum(record["engine_top4"] for record in positive), len(positive)),
        "gate_accepted_top1_rate": ratio(sum(record["top1"] for record in accepted), len(accepted)),
        "gate_accepted_visible_top3_rate": ratio(
            sum(record["visible_top3"] for record in accepted), len(accepted)
        ),
        "empty_decodes": sum(record["decode_empty"] for record in evaluated),
        "negative_nonempty_decodes": sum(not record["decode_empty"] for record in negative),
        "unmappable": sum(bool(record["unmappable_letters"]) for record in positive),
        "out_of_vocabulary": sum(record["target_stage"] == "out_of_vocabulary" for record in positive),
        "endpoint_start_pruned": sum(record["pruning"]["endpoint_start"] for record in positive),
        "endpoint_end_pruned": sum(record["pruning"]["endpoint_end"] for record in positive),
        "endpoint_pruned": sum(
            record["pruning"]["endpoint_start"] or record["pruning"]["endpoint_end"] for record in positive
        ),
        "length_pruned": sum(record["pruning"]["length"] for record in positive),
        "decoder_rejected": sum(record["error_class"] == "decoder_rejected" for record in positive),
        "collector_rejected": sum(not record["gate_passed"] for record in positive),
        "path_vertical_transition_records": records_with_path_vertical,
        "path_vertical_transition_record_rate": ratio(
            records_with_path_vertical, len(path_vertical_counts)
        ),
        "path_vertical_transitions": sum(path_vertical_counts),
        "error_classes": dict(sorted(errors.items())),
        "target_stages": dict(sorted(stages.items())),
        "collector_rejection_reasons": dict(sorted(collector_reasons.items())),
    }


def grouped_summaries(evaluated: Sequence[dict[str, Any]], key: str) -> dict[str, Any]:
    groups: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    for record in evaluated:
        value = record[key]
        values = value if isinstance(value, list) else [value]
        for item in values:
            groups[str(item)].append(record)
    return {name: summarize(groups[name]) for name in sorted(groups)}


def source_identities(records: Sequence[dict[str, Any]]) -> dict[str, Any]:
    layouts = sorted({record["layout_id"] for record in records})
    production = {path: sha256_file(ROOT / path) for path in PRODUCTION_SOURCES}
    dictionaries = {layout: {
        "path": DICTIONARY_ASSETS[layout], "sha256": sha256_file(ROOT / DICTIONARY_ASSETS[layout])
    } for layout in layouts}
    geometries = sorted({canonical(record["geometry_key"]) for record in records})
    return {
        "production_sources": production,
        "decoder_fingerprint": fingerprint(production),
        "dictionaries": dictionaries,
        "dictionary_fingerprint": fingerprint(dictionaries),
        "geometry_fingerprint": fingerprint(geometries),
    }


def build_report(
    *,
    records: Sequence[dict[str, Any]],
    invalid: Sequence[dict[str, Any]],
    sources: Sequence[dict[str, Any]],
    split: str,
    frozen: bool,
    manifest_path: str | None,
    source_kind: str,
    results: dict[int, dict[str, Any]],
    bridge_metadata: dict[str, Any],
) -> dict[str, Any]:
    evaluated = evaluate_records(records, results)
    identities = source_identities(records) if records else {
        "production_sources": {}, "decoder_fingerprint": None, "dictionaries": {},
        "dictionary_fingerprint": fingerprint({}), "geometry_fingerprint": fingerprint([]),
    }
    classes: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    for record in evaluated:
        for tag in record["classes"]:
            classes[tag].append(record)
    unmappable: dict[str, Any] = {}
    for record in evaluated:
        if record["positive"] and record["unmappable_letters"]:
            item = unmappable.setdefault(record["normalized_prompt"], {"records": 0, "missing_letters": set()})
            item["records"] += 1
            item["missing_letters"].update(record["unmappable_letters"])
    return {
        "schema": REPORT_SCHEMA,
        "evaluator": "scripts/glide-benchmark.py + production GlideDecoder JVM bridge",
        "visible_top_n": VISIBLE_TOP_N,
        "engine_diagnostic_top_n": ENGINE_TOP_N,
        "source_kind": source_kind,
        "new_human_coverage": False,
        "split": split,
        "dataset_frozen": frozen,
        "dataset_manifest": manifest_path,
        "split_policy": SPLIT_POLICY if frozen else "development-only-unfrozen-direct-input",
        "dataset_fingerprint": dataset_fingerprint(split, sources),
        "inputs": [public_source(source) for source in sources],
        "invalid_records": list(invalid),
        "source_identity": identities,
        "bridge": bridge_metadata,
        "speed_bins": {
            "unit": "key_radii_per_second", "slow_below": SLOW_SPEED_MAX,
            "normal_inclusive": [SLOW_SPEED_MAX, FAST_SPEED_MIN], "fast_at_or_above": FAST_SPEED_MIN,
        },
        "path_vertical_transition_metric": (
            "positive-record adjacent-sample changes between nearest key-row bands, computed from "
            "path_y and geometry_key; midpoint ties select the upper row"
        ),
        "summary": summarize(evaluated),
        "breakdowns": {
            "class": {name: summarize(groups) for name, groups in sorted(classes.items())},
            "speed": grouped_summaries(evaluated, "speed_bucket"),
            "instructed_speed": grouped_summaries(evaluated, "instructed_speed"),
            "orientation": grouped_summaries(evaluated, "orientation"),
            "geometry": grouped_summaries(evaluated, "geometry_bucket"),
            "session": grouped_summaries(evaluated, "session_id"),
            "device": grouped_summaries(evaluated, "device_id"),
            "prompt": grouped_summaries(evaluated, "normalized_prompt"),
        },
        "unmappable_words": {
            word: {"records": item["records"], "missing_letters": sorted(item["missing_letters"])}
            for word, item in sorted(unmappable.items())
        },
        "records": evaluated,
    }


def check_geometry_consistency(records: Sequence[dict[str, Any]]) -> None:
    """Refuse a batch that mixes old and new geometry for one layout.

    Records without key_geometry always decode against the current production reference
    geometry. If the same layout also carries recorded geometry that does not match the
    reference, the legacy records would silently decode against a different layout generation
    than the rest of the batch. Distinct recorded device geometries stay legal; only the
    implicit-reference/explicit-foreign mix is rejected.
    """
    implicit: set[str] = set()
    foreign: dict[str, set[str]] = collections.defaultdict(set)
    for record in records:
        if record["key_geometry"] is None:
            implicit.add(record["layout_id"])
        elif record["geometry_id"] != fingerprint(reference_geometry(record["layout_id"]))[:16]:
            foreign[record["layout_id"]].add(record["geometry_id"])
    for layout in sorted(implicit & foreign.keys()):
        offenders = ", ".join(sorted(foreign[layout])[:5])
        raise BenchmarkError(
            f"layout {layout!r} mixes records without key_geometry (production reference "
            f"geometry) with recorded geometry {offenders}: the legacy records would decode "
            f"against a different layout generation; re-record them with the current collector "
            f"or split the inputs"
        )


def evaluate_dataset(args: argparse.Namespace) -> dict[str, Any]:
    if bool(args.manifest) == bool(args.input):
        raise BenchmarkError("use exactly one of --manifest or --input")
    if args.manifest:
        manifest_path = resolve_path(args.manifest)
        paths, manifest, frozen = manifest_inputs(manifest_path, args.split)
        manifest_display = display_path(manifest_path)
        manifest_kind = manifest["splits"][args.split].get("source_kind", "recorded")
        source_kind = "synthetic" if args.synthetic or manifest_kind == "synthetic" else "recorded"
    else:
        if args.split != "development":
            raise BenchmarkError("final evaluation requires a frozen --manifest")
        paths = args.input
        frozen = False
        manifest_display = None
        source_kind = "synthetic" if args.synthetic else "recorded"
    records, invalid, sources = load_inputs(paths)
    check_geometry_consistency(records)
    results, metadata = run_production_bridge(records)
    report = build_report(
        records=records, invalid=invalid, sources=sources, split=args.split, frozen=frozen,
        manifest_path=manifest_display, source_kind=source_kind,
        results=results, bridge_metadata=metadata,
    )
    write_json(resolve_path(args.output), report)
    return report


def paired_counts(baseline: Sequence[dict[str, Any]], candidate: Sequence[dict[str, Any]], key: str) -> dict[str, Any]:
    both_yes = sum(a[key] and b[key] for a, b in zip(baseline, candidate))
    baseline_only = sum(a[key] and not b[key] for a, b in zip(baseline, candidate))
    candidate_only = sum(not a[key] and b[key] for a, b in zip(baseline, candidate))
    both_no = sum(not a[key] and not b[key] for a, b in zip(baseline, candidate))
    return {
        "both_correct": both_yes,
        "baseline_only": baseline_only,
        "candidate_only": candidate_only,
        "both_wrong": both_no,
        "net_improvement": candidate_only - baseline_only,
        "regressed_record_ids": [b["record_id"] for a, b in zip(baseline, candidate) if a[key] and not b[key]],
        "improved_record_ids": [b["record_id"] for a, b in zip(baseline, candidate) if not a[key] and b[key]],
    }


def compare_reports(args: argparse.Namespace) -> dict[str, Any]:
    baseline = json.loads(resolve_path(args.baseline).read_text(encoding="utf-8"))
    candidate = json.loads(resolve_path(args.candidate).read_text(encoding="utf-8"))
    for report in (baseline, candidate):
        if report.get("schema") != REPORT_SCHEMA or report.get("visible_top_n") != VISIBLE_TOP_N:
            raise BenchmarkError("unsupported or non-visible-top-3 report")
    for key in ("dataset_fingerprint", "split", "source_kind"):
        if baseline.get(key) != candidate.get(key):
            raise BenchmarkError(f"reports do not share {key}")
    for key in ("geometry_fingerprint", "dictionary_fingerprint"):
        if baseline["source_identity"].get(key) != candidate["source_identity"].get(key):
            raise BenchmarkError(f"reports do not share {key}")
    before, after = baseline["records"], candidate["records"]
    if len(before) != len(after) or any(
        a["record_fingerprint"] != b["record_fingerprint"] for a, b in zip(before, after)
    ):
        raise BenchmarkError("reports do not contain identical ordered records")
    positive_before = [record for record in before if record["positive"]]
    positive_after = [record for record in after if record["positive"]]
    comparison = {
        "schema": "glide-benchmark-comparison-v1",
        "dataset_fingerprint": baseline["dataset_fingerprint"],
        "split": baseline["split"],
        "source_kind": baseline["source_kind"],
        "baseline_decoder_fingerprint": baseline["source_identity"]["decoder_fingerprint"],
        "candidate_decoder_fingerprint": candidate["source_identity"]["decoder_fingerprint"],
        "geometry_fingerprint": baseline["source_identity"]["geometry_fingerprint"],
        "dictionary_fingerprint": baseline["source_identity"]["dictionary_fingerprint"],
        "metrics": {},
        "paired": {
            "top1": paired_counts(positive_before, positive_after, "top1"),
            "visible_top3": paired_counts(positive_before, positive_after, "visible_top3"),
            "engine_top4": paired_counts(positive_before, positive_after, "engine_top4"),
        },
    }
    for key in (
        "top1_rate", "visible_top3_rate", "engine_top4_rate", "empty_decodes", "unmappable",
        "out_of_vocabulary", "endpoint_pruned", "length_pruned", "decoder_rejected",
        "negative_nonempty_decodes",
    ):
        old = baseline["summary"].get(key)
        new = candidate["summary"].get(key)
        comparison["metrics"][key] = {
            "baseline": old, "candidate": new,
            "delta": round(new - old, 6) if old is not None and new is not None else None,
        }
    if args.output:
        write_json(resolve_path(args.output), comparison)
    return comparison


def load_glide_pack():
    path = ROOT / "scripts/glide_pack.py"
    spec = importlib.util.spec_from_file_location("glide_benchmark_pack", path)
    if spec is None or spec.loader is None:
        raise BenchmarkError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def synthetic_record(
    prompt: str,
    points: Sequence[tuple[float, float, float]],
    radius: float,
    index: int,
    **metadata: Any,
) -> dict[str, Any]:
    xs = [round(x / radius, 6) for x, _y, _t in points]
    ys = [round(y / radius, 6) for _x, y, _t in points]
    times = [point[2] for point in points]
    dts = [0] + [max(0, int(round(times[i] - times[i - 1]))) for i in range(1, len(times))]
    return {
        "collector_schema": 2,
        "record_id": f"synthetic-v1-{index:03d}",
        "participant": "synthetic",
        "device_id": "synthetic-reference",
        "session_id": "synthetic-v1",
        "layout_id": "tt",
        "prompt": prompt,
        "path_x": xs,
        "path_y": ys,
        "dt_ms": dts,
        "density_bucket": 440,
        "board_w": 1080,
        "board_h": 565,
        "orientation": "reference",
        "trial_kind": "ordinary",
        "speed_condition": "unspecified",
        "trace_status": "completed",
        "gate_passed": True,
        "rejection_reason": None,
        **metadata,
    }


def make_synthetic(args: argparse.Namespace) -> None:
    pack = load_glide_pack()
    layout_dir = ROOT / "app/src/main/res/xml"
    rects = pack.read_glide_geometry(layout_dir)
    aliases = pack.read_layout_aliases(layout_dir, rects)
    by_letter = pack.letters_by_code_point(rects, aliases)
    radius = pack.key_radius(rects)
    rows = []
    specifications = [
        ("сәләм", False, pack.PERSONA_NORMAL),
        ("сәлләм", False, pack.PERSONA_NORMAL),
        ("сәлләм", True, pack.PERSONA_SLOW),
        ("тәъмин", False, pack.PERSONA_NORMAL),
        ("һөҗүм", False, pack.PERSONA_FAST),
        ("бәләкәй", False, pack.PERSONA_SLOW),
        ("милли", True, pack.PERSONA_FAST),
    ]
    speed_names = {pack.PERSONA_NORMAL: "normal", pack.PERSONA_FAST: "fast", pack.PERSONA_SLOW: "slow"}
    for word, loop, persona in specifications:
        points = pack.generate_gesture(
            word, by_letter, radius, draw_loop=loop, **pack.persona_knobs(persona)
        )
        rows.append(synthetic_record(
            word, points, radius, len(rows) + 1,
            speed_condition=speed_names[persona], synthetic_variant="loop" if loop else "plain",
        ))
    # A known path with an intentionally unmappable target; no human/corpus attestation is implied.
    unmappable = pack.generate_gesture("сәләм", by_letter, radius)
    rows.append(synthetic_record("foo", unmappable, radius, len(rows) + 1, synthetic_variant="unmappable"))
    # Start/end pruning: replay another word's path against a valid dictionary target.
    wrong_path = pack.generate_gesture("белән", by_letter, radius)
    rows.append(synthetic_record("сәләм", wrong_path, radius, len(rows) + 1, synthetic_variant="wrong_path"))
    # A long excursion with the true endpoints makes length pruning deterministic without tuning.
    start = by_letter[ord("с")]
    end = by_letter[ord("м")]
    long_points = [
        (start.center_x, start.center_y, 0),
        (start.center_x, start.center_y + 4 * radius, 100),
        (start.center_x, start.center_y, 200),
        (start.center_x, start.center_y + 4 * radius, 300),
        (end.center_x, end.center_y, 500),
    ]
    rows.append(synthetic_record("сәләм", long_points, radius, len(rows) + 1, synthetic_variant="length_prune"))
    tap = [(start.center_x, start.center_y, 0), (start.center_x, start.center_y, 100)]
    rows.append(synthetic_record(
        "сәләм", tap, radius, len(rows) + 1, trial_kind="tap", gate_passed=False,
        rejection_reason="too_few_samples", synthetic_variant="negative_tap",
    ))
    tremor = [
        (start.center_x + ((i % 3) - 1) * radius * 0.03,
         start.center_y + ((i % 2) * 2 - 1) * radius * 0.03, i * 12)
        for i in range(12)
    ]
    rows.append(synthetic_record(
        "сәләм", tremor, radius, len(rows) + 1, trial_kind="tremor", gate_passed=False,
        rejection_reason="missed_letter", synthetic_variant="negative_tremor",
    ))
    destination = resolve_path(args.output)
    write_text_atomic(destination, "".join(canonical(row) + "\n" for row in rows))


def print_evaluation_summary(report: dict[str, Any], output: str) -> None:
    summary = report["summary"]
    def percent(value: float | None) -> str:
        return "n/a" if value is None else f"{100 * value:.2f}%"
    print(
        f"glide-benchmark: records={summary['records']} positive={summary['positive_records']} "
        f"top1={percent(summary['top1_rate'])} visible_top3={percent(summary['visible_top3_rate'])} "
        f"unmappable={summary['unmappable']} endpoint_pruned={summary['endpoint_pruned']} "
        f"length_pruned={summary['length_pruned']} rejected={summary['decoder_rejected']} "
        f"vertical_paths={summary['path_vertical_transition_records']}/{summary['positive_records']} "
        f"({percent(summary['path_vertical_transition_record_rate'])}) "
        f"vertical_transitions={summary['path_vertical_transitions']}"
    )
    print(f"glide-benchmark: wrote {output}")


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    evaluate = subparsers.add_parser("evaluate", help="run the production decoder on JSONL records")
    evaluate.add_argument("--input", action="append", help="collector JSONL; repeatable")
    evaluate.add_argument("--manifest", help="frozen dataset manifest")
    evaluate.add_argument("--split", choices=("development", "final"), default="development")
    evaluate.add_argument("--synthetic", action="store_true", help="label inputs as synthetic, never human evidence")
    evaluate.add_argument("--output", required=True, help="report JSON")
    evaluate.set_defaults(handler=evaluate_dataset)

    freeze = subparsers.add_parser("freeze", help="freeze whole sessions/devices into development/final splits")
    freeze.add_argument("--development-input", action="append", default=[])
    freeze.add_argument("--final-input", action="append", default=[])
    freeze.add_argument("--source-kind", choices=("recorded", "synthetic"), default="recorded")
    freeze.add_argument("--output", required=True)
    freeze.set_defaults(handler=freeze_dataset)

    compare = subparsers.add_parser("compare", help="compare two reports on identical inputs and geometry")
    compare.add_argument("baseline")
    compare.add_argument("candidate")
    compare.add_argument("--output")
    compare.set_defaults(handler=compare_reports)

    synthetic = subparsers.add_parser("make-synthetic", help="write the deterministic synthetic smoke fixture")
    synthetic.add_argument("--output", required=True)
    synthetic.set_defaults(handler=make_synthetic)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_parser().parse_args(argv)
    try:
        result = args.handler(args)
        if args.command == "evaluate":
            print_evaluation_summary(result, args.output)
        elif args.command == "freeze":
            counts = {name: split.get("records", 0) for name, split in result["splits"].items()}
            print(f"glide-benchmark: froze {counts}; wrote {args.output}")
        elif args.command == "compare":
            for name, paired in result["paired"].items():
                print(
                    f"{name}: improved={paired['candidate_only']} regressed={paired['baseline_only']} "
                    f"net={paired['net_improvement']:+d}"
                )
            if args.output:
                print(f"glide-benchmark: wrote {args.output}")
        elif args.command == "make-synthetic":
            print(f"glide-benchmark: wrote {args.output}")
        return 0
    except (BenchmarkError, OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"glide-benchmark: error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
