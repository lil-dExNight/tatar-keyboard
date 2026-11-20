#!/usr/bin/env python3
"""One-off conversion of pinned Glot500 tat_Cyrl shards to plain text.

NOT part of the stdlib asset pipeline: it needs pyarrow and runs once per dump, with the
pyarrow venv interpreter. This remains a development tool; conversion does not authorize
production integration.

The tracked allowlist beside this script binds the exact dataset revision and shard hashes
to the reviewed source decisions. Only its explicitly allowed labels can be emitted.
Reviewed non-allowlisted labels are dropped and counted. Any other label, a revision
mismatch, or a shard mismatch aborts the entire conversion before an output is opened.
The review artifacts under ``dist/`` are provenance inputs for maintaining the tracked
allowlist, not runtime dependencies.

Every kept row must be tagged Cyrillic in both script columns; a row that is not is dropped
and counted. Output: one row per line (internal whitespace runs become one space), plus a
JSON report with per-label counts. Parquet and Arrow IPC shards use the same row schema.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

DEFAULT_ALLOWLIST = Path(__file__).with_name("glot500-legal-allowlist.json")
EXPECTED_COLUMNS = ("text", "dataset", "script", "lang_script")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def load_policy(path: Path) -> dict:
    policy = json.loads(path.read_text(encoding="utf-8"))
    if policy.get("schema_version") != 1:
        raise ValueError(f"{path}: unsupported allowlist schema")
    if policy.get("default") != "deny" or policy.get("label_matching") != "exact":
        raise ValueError(f"{path}: allowlist must use exact matching and default deny")
    revision = policy.get("dataset_revision")
    if not isinstance(revision, str) or len(revision) != 40:
        raise ValueError(f"{path}: invalid dataset revision")
    try:
        int(revision, 16)
    except ValueError as error:
        raise ValueError(f"{path}: invalid dataset revision") from error
    allowed = policy.get("allow_labels")
    rejected = policy.get("known_rejected_labels")
    if (not isinstance(allowed, list) or not allowed
            or any(not isinstance(label, str) or not label for label in allowed)
            or len(set(allowed)) != len(allowed)):
        raise ValueError(f"{path}: invalid allow_labels")
    if (not isinstance(rejected, list)
            or any(not isinstance(label, str) or not label for label in rejected)
            or len(set(rejected)) != len(rejected)
            or set(allowed) & set(rejected)):
        raise ValueError(f"{path}: invalid known_rejected_labels")
    pins = policy.get("shards")
    if not isinstance(pins, list) or not pins:
        raise ValueError(f"{path}: no pinned shards")
    for pin in pins:
        if (not isinstance(pin.get("path"), str)
                or not isinstance(pin.get("bytes"), int) or pin["bytes"] < 0
                or not isinstance(pin.get("sha256"), str)
                or len(pin["sha256"]) != 64):
            raise ValueError(f"{path}: invalid shard pin")
        try:
            int(pin["sha256"], 16)
        except ValueError as error:
            raise ValueError(f"{path}: invalid shard pin") from error
    return policy


def verify_shards(shards: list[Path], pins: list[dict]) -> list[dict]:
    if not shards:
        raise ValueError("no input shards")
    by_name: dict[str, list[dict]] = {}
    for pin in pins:
        by_name.setdefault(Path(pin["path"]).name, []).append(pin)
    verified = []
    seen = set()
    for shard in shards:
        candidates = by_name.get(shard.name, [])
        if len(candidates) != 1:
            raise ValueError(f"{shard}: shard is not uniquely pinned for this revision")
        pin = candidates[0]
        if pin["path"] in seen:
            raise ValueError(f"{shard}: duplicate input shard")
        seen.add(pin["path"])
        actual_bytes = shard.stat().st_size
        if actual_bytes != pin["bytes"]:
            raise ValueError(
                f"{shard}: shard size mismatch; expected {pin['bytes']}, got {actual_bytes}"
            )
        actual_sha256 = sha256(shard)
        if actual_sha256 != pin["sha256"]:
            raise ValueError(f"{shard}: shard SHA-256 mismatch")
        verified.append({
            "path": str(shard),
            "pinned_path": pin["path"],
            "bytes": actual_bytes,
            "sha256": actual_sha256,
        })
    return verified


def iter_columns(shard: Path, names: tuple[str, ...]):
    """Read selected columns without creating output; Arrow streams stay open per batch."""
    if shard.suffix == ".arrow":
        import pyarrow.ipc as ipc  # the one non-stdlib dependency; see module docstring

        with shard.open("rb") as stream:
            reader = ipc.open_stream(stream)
            if tuple(reader.schema.names) != EXPECTED_COLUMNS:
                raise ValueError(f"{shard}: unexpected columns {reader.schema.names}")
            for batch in reader:
                yield tuple(
                    batch.column(reader.schema.names.index(name)).to_pylist()
                    for name in names
                )
        return

    import pyarrow.parquet as pq  # the same optional dependency for Parquet inputs

    parquet = pq.ParquetFile(shard)
    for batch in parquet.iter_batches(batch_size=50000, columns=list(names)):
        yield tuple(batch.column(name).to_pylist() for name in names)


def convert(shards: list[Path], out_path: Path, report_path: Path,
            dataset_revision: str, allowlist_path: Path = DEFAULT_ALLOWLIST) -> dict:
    policy = load_policy(allowlist_path)
    expected_revision = policy["dataset_revision"]
    if dataset_revision != expected_revision:
        raise ValueError(
            f"dataset revision mismatch; expected {expected_revision}, got {dataset_revision}"
        )
    allowed = frozenset(policy["allow_labels"])
    rejected = frozenset(policy["known_rejected_labels"])
    verified_shards = verify_shards(shards, policy["shards"])

    # Validate every label before opening either output. Known denied labels are counted;
    # an unreviewed label fails closed rather than being silently emitted or discarded.
    per_label_read: Counter = Counter()
    for shard in shards:
        for (labels,) in iter_columns(shard, ("dataset",)):
            per_label_read.update(labels)
    unknown = {
        label for label in per_label_read
        if label not in allowed and label not in rejected
    }
    if unknown:
        names = ", ".join(sorted(str(label) for label in unknown))
        raise ValueError(f"unknown dataset labels; refusing all output: {names}")

    stats: dict[str, object] = {
        "shards": len(shards),
        "dataset_revision": expected_revision,
        "allowlist": str(allowlist_path),
        "allowlist_sha256": sha256(allowlist_path),
        "allowed_labels": sorted(allowed),
        "default_deny": True,
        "unknown_labels": [],
        "verified_shards": verified_shards,
        "rows_read": sum(per_label_read.values()),
        "rows_kept": 0,
        "rows_dropped_label": sum(per_label_read[label] for label in rejected),
        "rows_dropped_script": 0,
        "kept_bytes": 0,
        "dropped_labels": sorted(set(per_label_read) - allowed),
        "per_label_read": dict(sorted(per_label_read.items())),
        "per_label_kept": {},
    }
    per_label_kept: Counter = Counter()
    digest = hashlib.sha256()
    with out_path.open("w", encoding="utf-8", newline="\n") as out:
        for shard in shards:
            batches = iter_columns(shard, EXPECTED_COLUMNS)
            for texts, labels, scripts, lang_scripts in batches:
                for text, label, script, lang_script in zip(
                    texts, labels, scripts, lang_scripts
                ):
                    if label not in allowed:
                        # Keep the emission guard positive even though preflight already
                        # established that this is a reviewed denied label.
                        continue
                    if script != "Cyrl" or lang_script != "Cyrl":
                        stats["rows_dropped_script"] += 1
                        continue
                    line = " ".join(text.split()) + "\n"
                    encoded = line.encode("utf-8")
                    digest.update(encoded)
                    out.write(line)
                    stats["rows_kept"] += 1
                    per_label_kept[label] += 1
                    stats["kept_bytes"] += len(encoded)
    stats["per_label_kept"] = dict(sorted(per_label_kept.items()))
    stats["output"] = str(out_path)
    stats["output_sha256"] = digest.hexdigest()
    report_path.write_text(
        json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return stats


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("shards", nargs="+", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--dataset-revision", required=True)
    parser.add_argument(
        "--allowlist", type=Path, default=DEFAULT_ALLOWLIST,
        help=f"tracked revision and label policy (default: {DEFAULT_ALLOWLIST})",
    )
    args = parser.parse_args()
    for shard in args.shards:
        if not shard.is_file():
            print(f"error: missing shard {shard}", file=sys.stderr)
            return 2
    try:
        stats = convert(
            args.shards, args.out, args.report, args.dataset_revision, args.allowlist
        )
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
