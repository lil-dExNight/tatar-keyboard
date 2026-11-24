#!/usr/bin/env python3
"""Validate and collect text donations exported by research/text-donation/index.html.

Input: one or more ``tatar-text-<donor>.jsonl`` files (format documented in the page header).
Output, written only when every input is valid: ``<out>/<donor_id>.json`` per donor (metadata
and scrubbed texts) and ``<out>/summary.json`` (donor count, Tatar tokens per donor, the largest
donor share, counts by source and by publication choice, dropped duplicate lines). The default
output directory is under build/, which git ignores: donations are never written into tracked
paths by this script.

The page's scrubber patterns are applied again as a safety net. A text in which they still find
something fails the run unless ``--rescrub`` is given, which replaces the findings and reports
how many there were. Lines whose normalized form repeats an earlier line (any donor) are
dropped. Exit 2 on any invalid input.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

import make_eval_set  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = ROOT / "build" / "native-text"
SCHEMA = "tt-text-donation/1"
SOURCES = frozenset({"messenger", "social", "email", "other"})
REGIONS = frozenset({"", "kazan", "tatarstan", "bashkortostan", "russia_other", "abroad"})
AGE_BANDS = frozenset({"", "under18", "18-29", "30-44", "45-59", "60plus"})
DONOR_ID = re.compile(r"d-[0-9a-f]{8}")
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
MAX_TEXT_CHARS = 20_000

# Mirrors RULES in research/text-donation/index.html, in the same order (the test suite checks
# that the pattern sources match). USER keeps the character before the @.
SCRUB_RULES: tuple[tuple[str, str, int], ...] = (
    ("URL", r"(?:https?://|www\.)[^\s]+", re.IGNORECASE),
    ("EMAIL", r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+", 0),
    ("USER", r"(^|[^A-Za-z0-9_])@[A-Za-z0-9_]{3,}", 0),
    ("PHONE", r"\+?\d[\d\s().-]{6,}\d", 0),
    ("NUM", r"\d{5,}", 0),
)
_COMPILED = tuple((name, re.compile(source, flags)) for name, source, flags in SCRUB_RULES)
PLACEHOLDER = re.compile(r"<(?:URL|EMAIL|USER|PHONE|NUM|NAME)>")


class IngestError(ValueError):
    """An invalid donation file (exit 2)."""


def scrub(text: str) -> tuple[str, int]:
    """Applies the page's scrubber; returns the scrubbed text and the number of replacements."""
    total = 0
    for name, pattern in _COMPILED:
        if name == "USER":
            text, count = pattern.subn(lambda m: m.group(1) + "<USER>", text)
        else:
            text, count = pattern.subn(f"<{name}>", text)
        total += count
    return text, total


def sentence_words(text: str) -> list[list[str]]:
    """Normalized Tatar-alphabet words per line segment; placeholders split a segment."""
    segments: list[list[str]] = []
    for line in text.splitlines():
        for part in PLACEHOLDER.split(line):
            words = make_eval_set.normalize_sentence(part)
            if words:
                segments.append(words)
    return segments


@dataclass
class Donation:
    donor_id: str
    publish_ok: bool
    region: str
    age_band: str
    date: str
    texts: list[dict] = field(default_factory=list)
    rescrubbed: int = 0


def parse_file(path: Path, rescrub: bool) -> Donation:
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not lines:
        raise IngestError(f"{path}: empty file")
    try:
        records = [json.loads(line) for line in lines]
    except json.JSONDecodeError as error:
        raise IngestError(f"{path}: not JSON lines: {error}") from error
    head = records[0]
    if not isinstance(head, dict) or head.get("schema") != SCHEMA or head.get("kind") != "donor":
        raise IngestError(f"{path}: first record is not a {SCHEMA} donor record")
    if set(head) != {"schema", "kind", "donor_id", "publish_ok", "region", "age_band", "date"}:
        raise IngestError(f"{path}: unexpected donor fields {sorted(head)}")
    if not isinstance(head["donor_id"], str) or not DONOR_ID.fullmatch(head["donor_id"]):
        raise IngestError(f"{path}: bad donor_id")
    if not isinstance(head["publish_ok"], bool):
        raise IngestError(f"{path}: publish_ok must be a boolean")
    if head["region"] not in REGIONS or head["age_band"] not in AGE_BANDS:
        raise IngestError(f"{path}: unknown region or age band")
    if not isinstance(head["date"], str) or not DATE.fullmatch(head["date"]):
        raise IngestError(f"{path}: bad date")
    donation = Donation(head["donor_id"], head["publish_ok"], head["region"], head["age_band"],
                        head["date"])
    if len(records) < 2:
        raise IngestError(f"{path}: no text records")
    for expected_id, record in enumerate(records[1:], start=1):
        if not isinstance(record, dict) or set(record) != {"kind", "id", "source", "text"}:
            raise IngestError(f"{path}: record {expected_id} has unexpected fields")
        if record["kind"] != "text" or record["id"] != expected_id:
            raise IngestError(f"{path}: record {expected_id} is out of order")
        if record["source"] not in SOURCES:
            raise IngestError(f"{path}: record {expected_id} has unknown source")
        text = record["text"]
        if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT_CHARS:
            raise IngestError(f"{path}: record {expected_id} has an empty or oversized text")
        scrubbed, found = scrub(text)
        if found:
            if not rescrub:
                raise IngestError(
                    f"{path}: record {expected_id} still contains {found} scrubbable item(s); "
                    "rerun with --rescrub to replace them")
            donation.rescrubbed += found
        donation.texts.append({"id": expected_id, "source": record["source"], "text": scrubbed})
    return donation


def ingest(paths: Sequence[Path], rescrub: bool) -> tuple[list[Donation], dict]:
    donations = [parse_file(path, rescrub) for path in paths]
    seen_donors: set[str] = set()
    for donation in donations:
        if donation.donor_id in seen_donors:
            raise IngestError(f"donor {donation.donor_id} appears in more than one file")
        seen_donors.add(donation.donor_id)

    seen_lines: set[str] = set()
    duplicates = 0
    tokens_by_donor: dict[str, int] = {}
    by_source: Counter[str] = Counter()
    for donation in donations:
        tokens = 0
        for item in donation.texts:
            kept_lines = []
            for line in item["text"].splitlines():
                key = " ".join(w for seg in sentence_words(line) for w in seg)
                if key and key in seen_lines:
                    duplicates += 1
                    continue
                if key:
                    seen_lines.add(key)
                kept_lines.append(line)
            item["text"] = "\n".join(kept_lines)
            words = sum(len(seg) for seg in sentence_words(item["text"]))
            tokens += words
            by_source[item["source"]] += words
        donation.texts = [item for item in donation.texts if item["text"].strip()]
        tokens_by_donor[donation.donor_id] = tokens

    total = sum(tokens_by_donor.values())
    summary = {
        "schema": SCHEMA,
        "donors": len(donations),
        "tokens_total": total,
        "tokens_by_donor": dict(sorted(tokens_by_donor.items())),
        "max_donor_share_pct": round(100 * max(tokens_by_donor.values()) / total, 4) if total else 0.0,
        "tokens_by_source": dict(sorted(by_source.items())),
        "publish_ok_donors": sum(1 for d in donations if d.publish_ok),
        "duplicate_lines_dropped": duplicates,
        "rescrubbed_items": sum(d.rescrubbed for d in donations),
    }
    return donations, summary


def write_output(out: Path, donations: Sequence[Donation], summary: dict) -> None:
    out.mkdir(parents=True, exist_ok=True)
    for donation in donations:
        payload = {
            "donor_id": donation.donor_id,
            "publish_ok": donation.publish_ok,
            "region": donation.region,
            "age_band": donation.age_band,
            "date": donation.date,
            "texts": donation.texts,
        }
        (out / f"{donation.donor_id}.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    (out / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--rescrub", action="store_true")
    args = parser.parse_args(argv)
    try:
        donations, summary = ingest(args.files, args.rescrub)
    except (IngestError, OSError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 2
    write_output(args.out, donations, summary)
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
