"""Normalized sentence keys and source text that corpus cleaning must never emit."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import dictionary_coverage as coverage  # noqa: E402
import evaluation_splits  # noqa: E402


def sentence_key(text: str) -> str:
    """Use the split gate's normalization and the Cyrillic superset alphabet."""
    return evaluation_splits.normalize_sentence(text, coverage.TATAR_ALPHABET)


def _add_line(
    keys: set[str], line: str, column: int, path: Path, line_number: int,
    texts: list[str] | None,
) -> bool:
    fields = line.rstrip("\r\n").split("\t")
    if len(fields) <= column:
        raise ValueError(f"{path}:{line_number}: sentence column {column} is absent")
    normalized = sentence_key(fields[column])
    if not normalized:
        return False
    keys.add(normalized)
    if texts is not None:
        texts.append(fields[column])
    return True


def load_split_evaluation_keys(
    root: Path,
    declaration_names: tuple[str, ...] = ("splits-tt.json", "splits-ru.json"),
    texts: list[str] | None = None,
) -> tuple[frozenset[str], list[dict[str, object]]]:
    """Load committed development and final sentence sources from split declarations."""
    keys: set[str] = set()
    records: list[dict[str, object]] = []
    for name in declaration_names:
        path = root / "data" / "evaluation" / name
        declaration = json.loads(path.read_text(encoding="utf-8"))
        for source in declaration["sources"]:
            if source["kind"] != "sentences" or source["split"] not in {"development", "final"}:
                continue
            source_path = root / source["path"]
            before = len(keys)
            rows = 0
            column = int(source.get("sentence_column", 0))
            with source_path.open("r", encoding="utf-8", newline="") as stream:
                for line_number, line in enumerate(stream, 1):
                    if not line.strip() or line.startswith("#"):
                        continue
                    rows += _add_line(keys, line, column, source_path, line_number, texts)
            records.append({
                "id": source["id"], "path": source["path"], "rows": rows,
                "new_keys": len(keys) - before,
            })
    return frozenset(keys), records


def load_speech_evaluation_keys(
    root: Path,
    inventory_path: Path | None = None,
    speech_root: Path | None = None,
    texts: list[str] | None = None,
) -> tuple[frozenset[str], list[dict[str, object]]]:
    """Load fetched Common Voice and TatSC development/test splits."""
    if inventory_path is None:
        inventory_path = root / "data" / "evaluation" / "final-exclusions-tt.json"
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    keys: set[str] = set()
    records: list[dict[str, object]] = []
    for source in inventory["sources"]:
        identifier = source["id"]
        if not identifier.startswith(("common-voice-", "tatsc-")):
            continue
        if identifier.endswith("-train"):
            continue
        if source["format"] != "text":
            raise ValueError(f"{identifier}: unsupported speech exclusion format")
        configured_path = source["path"]
        if speech_root is None:
            source_path = root / configured_path
        else:
            prefix = "build/corpus-sources/"
            if not configured_path.startswith(prefix):
                raise ValueError(f"{identifier}: unexpected speech path {configured_path}")
            source_path = speech_root / configured_path[len(prefix):]
        if not source_path.is_file():
            raise FileNotFoundError(f"required speech evaluation text is missing: {source_path}")
        before = len(keys)
        rows = 0
        with source_path.open("r", encoding="utf-8", newline="") as stream:
            for line_number, line in enumerate(stream, 1):
                if line.strip() and not line.startswith("#"):
                    rows += _add_line(
                        keys, line, int(source.get("sentence_column", 0)),
                        source_path, line_number, texts,
                    )
        records.append({
            "id": identifier, "path": str(source_path), "rows": rows,
            "new_keys": len(keys) - before,
        })
    return frozenset(keys), records


def load_evaluation_bundle(
    root: Path, speech_root: Path | None = None
) -> tuple[frozenset[str], list[dict[str, object]], list[str]]:
    """Load normalized collision keys and the corresponding source sentences."""
    texts: list[str] = []
    split_keys, split_records = load_split_evaluation_keys(root, texts=texts)
    speech_keys, speech_records = load_speech_evaluation_keys(
        root, speech_root=speech_root, texts=texts
    )
    return split_keys | speech_keys, split_records + speech_records, texts


def load_evaluation_keys(
    root: Path, speech_root: Path | None = None
) -> tuple[frozenset[str], list[dict[str, object]]]:
    """Combine committed and fetched evaluation exclusions."""
    keys, records, _texts = load_evaluation_bundle(root, speech_root)
    return keys, records
