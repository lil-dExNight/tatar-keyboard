#!/usr/bin/env python3
"""Build a training-only Tatar proper-name native-review queue.

Every corpus input is checked against the manifest before tokenization. The queue contains
counts and surface forms, never corpus sentences, and all native-review fields are empty.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "research" / "corpus"))

import corpuslib as CL  # noqa: E402
import dictionary_coverage as cov  # noqa: E402
import dictionary_pack as dp  # noqa: E402
import filters as F  # noqa: E402
from stream import fast_normalizer  # noqa: E402

DEFAULT_SHIPPED = ROOT / "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib"
DEFAULT_OUT = ROOT / "build/operator-review/tt-proper-names.tsv"
DEFAULT_REPORT = ROOT / "build/operator-review/tt-proper-names-report.json"
MIN_DISPLAY_OCCURRENCES = 2
MAX_DISPLAY_VARIANTS = 5

SOURCE_DEFINITIONS = {
    "tt_conv_train90-sentences.txt": {
        "name": "tt_conv_train90",
        "license": "CC-BY-2.0-FR AND LicenseRef-OpenSubtitles",
        "risk": "risk",
        "revision": "Tatoeba 2026-10-31 plus OpenSubtitles 2024, id % 10 != 1",
    },
    "tat_mixed_2015_1M-sentences.txt": {
        "name": "tat_mixed_2015",
        "license": "CC-BY-4.0",
        "risk": "clean",
        "revision": "Leipzig tat_mixed_2015_1M",
    },
    "tat_web_2018_1M-sentences.txt": {
        "name": "tat_web_2018",
        "license": "CC-BY-4.0",
        "risk": "clean",
        "revision": "Leipzig tat_web_2018_1M",
    },
}

HEADER = [
    "normalized_form", "observed_display_form", "name_kind", "total_occurrences",
    "noninitial_occurrences", "noninitial_capitalized", "uncapitalized_noninitial",
    "cap_ratio", "display_occurrences", "source_count", "source_evidence",
    "display_variants", "shipped_frequency", "license_risk", "suggested_handling",
    "risk_flags", "reviewed_display_form", "base_normalized_form", "approved",
    "reviewer", "review_date", "note",
]
NATIVE_REVIEW_FIELDS = (
    "name_kind", "reviewed_display_form", "base_normalized_form", "approved",
    "reviewer", "review_date", "note",
)
FORBIDDEN_PATH_WORDS = {"dev", "development", "eval", "evaluation", "final", "heldout"}
FORBIDDEN_BASENAMES = {
    "tt_conv_heldout10-sentences.txt", "tt_conv_all-sentences.txt",
    "tt_eval_sentences.txt", "tt_common_voice_final.txt",
}
REJECTION_REASONS = (
    "line_initial_only", "below_noninitial_evidence", "below_capitalization_ratio",
    "all_caps_only", "no_titlecase_display", "below_display_evidence",
)


class ReviewError(ValueError):
    """Invalid source identity, source role, or review request."""


@dataclass(frozen=True, slots=True)
class Source:
    path: Path
    manifest_key: str
    name: str
    sha256: str
    size: int
    manifest_source: str
    revision: str
    license: str
    risk: str


@dataclass(slots=True)
class Evidence:
    total: int = 0
    noninitial: int = 0
    capitalized: int = 0
    all_caps: int = 0
    capitalized_sources: int = 0
    displays: Counter[str] | None = None


@dataclass(slots=True)
class SourceCounts:
    total: int = 0
    noninitial: int = 0
    capitalized: int = 0
    display: int = 0
    lines: int = 0
    last_line: int = -1


@dataclass(slots=True)
class Candidate:
    normalized: str
    display: str
    evidence: Evidence
    source_counts: list[SourceCounts] = field(default_factory=list)

    @property
    def display_occurrences(self) -> int:
        assert self.evidence.displays is not None
        return self.evidence.displays[self.display]

    @property
    def source_count(self) -> int:
        return self.evidence.capitalized_sources.bit_count()


def reject_forbidden_path(path: Path) -> None:
    """Reject path components or filenames that identify held-out evaluation data."""
    for candidate in (path, path.resolve()):
        name = candidate.name.lower()
        name_words = set(re.split(r"[^a-z0-9]+", name))
        if name in FORBIDDEN_BASENAMES:
            raise ReviewError(f"development/final input is forbidden: {path}")
        if (
            ("tatsc" in name_words or {"common", "voice"} <= name_words)
            and name_words & {"test", "testing"}
        ):
            raise ReviewError(f"development/final input is forbidden: {path}")
        for component in candidate.parts:
            words = set(re.split(r"[^a-z0-9]+", component.lower()))
            if words & FORBIDDEN_PATH_WORDS:
                raise ReviewError(f"development/final input is forbidden: {path}")


def file_sha256_and_size(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def load_sources(manifest_path: Path, paths: Sequence[Path]) -> list[Source]:
    """Resolve exactly the declared training inputs and verify every pin before counting."""
    reject_forbidden_path(manifest_path)
    for path in paths:
        reject_forbidden_path(path)
    with manifest_path.open("r", encoding="utf-8") as stream:
        manifest = json.load(stream)
    names = [path.name for path in paths]
    if len(names) != len(set(names)) or set(names) != set(SOURCE_DEFINITIONS):
        raise ReviewError(
            "corpora must be exactly one each of: " + ", ".join(SOURCE_DEFINITIONS)
        )
    sources = []
    for path in paths:
        pin = manifest.get(path.name)
        if not isinstance(pin, dict) or not {"sha256", "size", "source"} <= pin.keys():
            raise ReviewError(f"manifest has no complete pin for {path.name}")
        actual_hash, actual_size = file_sha256_and_size(path)
        if actual_size != pin["size"] or actual_hash != pin["sha256"]:
            raise ReviewError(
                f"corpus pin mismatch for {path}: expected size={pin['size']} "
                f"sha256={pin['sha256']}, got size={actual_size} sha256={actual_hash}"
            )
        definition = SOURCE_DEFINITIONS[path.name]
        sources.append(Source(
            path=path.resolve(), manifest_key=path.name, name=definition["name"],
            sha256=actual_hash, size=actual_size, manifest_source=pin["source"],
            revision=definition["revision"], license=definition["license"],
            risk=definition["risk"],
        ))
    return sources


def is_display_surface(surface: str) -> bool:
    return bool(surface) and surface[0].isupper() and surface[1:] == surface[1:].lower()


def token_rejection(surface: str) -> str:
    if not surface:
        return "empty_after_edge_strip"
    if len(surface) > cov.MAX_WORD_LENGTH:
        return "too_long"
    if any(character in "-‐‑‒–—'’‘ʼʻʹ" for character in surface):
        return "hyphen_or_apostrophe"
    if any(unicodedata.name(character, "").startswith("LATIN") for character in surface):
        return "latin"
    return "outside_tatar_alphabet"


def _sentence_text(line: str) -> str:
    """Strip a Leipzig numeric sentence id before assigning lexical token positions."""
    stripped = line.rstrip("\r\n")
    if "\t" in stripped:
        first, rest = stripped.split("\t", 1)
        if first.isdigit():
            return rest
    return stripped


def observe_sources(
    sources: Sequence[Source],
) -> tuple[dict[str, Evidence], list[dict[str, object]]]:
    """Collect global case evidence; selected per-source details use a later bounded pass."""
    alphabet = cov.language_for("tat").alphabet
    normalize = fast_normalizer(alphabet)
    evidence: dict[str, Evidence] = {}
    source_reports = []
    for source_index, source in enumerate(sources):
        source_bit = 1 << source_index
        rejections: Counter[str] = Counter()
        lines_read = 0
        tokens_read = 0
        normalized_tokens = 0
        with CL.open_text(source.path) as stream:
            for line in stream:
                lines_read += 1
                for position, chunk in enumerate(_sentence_text(line).split()):
                    tokens_read += 1
                    surface = unicodedata.normalize("NFC", chunk.strip(CL._EDGE))
                    normalized = normalize(surface)
                    if normalized is None:
                        rejections[token_rejection(surface)] += 1
                        continue
                    normalized_tokens += 1
                    item = evidence.get(normalized)
                    if item is None:
                        item = Evidence()
                        evidence[normalized] = item
                    item.total += 1
                    if position == 0:
                        continue
                    item.noninitial += 1
                    if not surface[:1].isupper():
                        continue
                    item.capitalized += 1
                    item.capitalized_sources |= source_bit
                    if surface.isupper():
                        item.all_caps += 1
                    if is_display_surface(surface):
                        if item.displays is None:
                            item.displays = Counter()
                        item.displays[surface] += 1
        source_reports.append({
            "name": source.name, "lines_read": lines_read, "tokens_read": tokens_read,
            "normalized_tokens": normalized_tokens,
            "rejected_tokens": dict(sorted(rejections.items())),
        })
    return evidence, source_reports


def chosen_display(item: Evidence) -> str | None:
    if not item.displays:
        return None
    return min(item.displays, key=lambda surface: (-item.displays[surface], surface))


def rejection_reason(item: Evidence) -> str | None:
    if item.noninitial == 0:
        return "line_initial_only"
    if item.noninitial < F.MIN_CASE_EVIDENCE:
        return "below_noninitial_evidence"
    if item.capitalized / item.noninitial < F.PROPER_RATIO:
        return "below_capitalization_ratio"
    if item.capitalized and item.all_caps == item.capitalized:
        return "all_caps_only"
    display = chosen_display(item)
    if display is None:
        return "no_titlecase_display"
    assert item.displays is not None
    if item.displays[display] < MIN_DISPLAY_OCCURRENCES:
        return "below_display_evidence"
    return None


def select_candidates(
    evidence: dict[str, Evidence], limit: int,
) -> tuple[list[Candidate], int, dict[str, int]]:
    rejected: Counter[str] = Counter({reason: 0 for reason in REJECTION_REASONS})
    candidates = []
    for normalized, item in evidence.items():
        reason = rejection_reason(item)
        if reason is not None:
            rejected[reason] += 1
            continue
        display = chosen_display(item)
        assert display is not None
        candidates.append(Candidate(normalized, display, item))
    qualifying = len(candidates)
    candidates.sort(key=lambda candidate: (
        -candidate.source_count, -candidate.display_occurrences,
        -candidate.evidence.capitalized, -candidate.evidence.total, candidate.normalized,
    ))
    return candidates[:limit], qualifying, dict(sorted(rejected.items()))


def attach_source_counts(sources: Sequence[Source], candidates: list[Candidate]) -> None:
    """Replay inputs for bounded per-source counts and distinct-line evidence."""
    if not candidates:
        return
    by_normalized = {candidate.normalized: candidate for candidate in candidates}
    for candidate in candidates:
        candidate.source_counts = [SourceCounts() for _source in sources]
    normalize = fast_normalizer(cov.language_for("tat").alphabet)
    for source_index, source in enumerate(sources):
        with CL.open_text(source.path) as stream:
            for line_number, line in enumerate(stream, 1):
                for position, chunk in enumerate(_sentence_text(line).split()):
                    surface = unicodedata.normalize("NFC", chunk.strip(CL._EDGE))
                    normalized = normalize(surface)
                    candidate = by_normalized.get(normalized) if normalized else None
                    if candidate is None:
                        continue
                    counts = candidate.source_counts[source_index]
                    counts.total += 1
                    if counts.last_line != line_number:
                        counts.lines += 1
                        counts.last_line = line_number
                    if position == 0:
                        continue
                    counts.noninitial += 1
                    if surface[:1].isupper():
                        counts.capitalized += 1
                    if surface == candidate.display:
                        counts.display += 1
    for candidate in candidates:
        counts = candidate.source_counts
        item = candidate.evidence
        actual_mask = sum(
            (1 << index) for index, count in enumerate(counts) if count.capitalized
        )
        if (
            sum(count.total for count in counts) != item.total
            or sum(count.noninitial for count in counts) != item.noninitial
            or sum(count.capitalized for count in counts) != item.capitalized
            or sum(count.display for count in counts) != candidate.display_occurrences
            or actual_mask != item.capitalized_sources
        ):
            raise ReviewError("corpus content changed between counting passes")


def load_shipped(path: Path) -> tuple[dict[str, int], dict[str, object]]:
    reject_forbidden_path(path)
    asset = path.read_bytes()
    language = cov.language_for("tat")
    parsed = dp.validate_raw(dp.decompress_asset(asset, language), language=language)
    frequencies = dict(zip(parsed.words, parsed.frequencies, strict=True))
    return frequencies, {
        "path": str(path.resolve()), "size": len(asset),
        "sha256": hashlib.sha256(asset).hexdigest(), "entries": len(parsed.words),
    }


def ordered_variants(item: Evidence) -> list[tuple[str, int]]:
    if not item.displays:
        return []
    return sorted(item.displays.items(), key=lambda pair: (-pair[1], pair[0]))


def build_rows(
    candidates: Sequence[Candidate], sources: Sequence[Source], shipped: dict[str, int],
) -> list[dict[str, object]]:
    rows = []
    for candidate in candidates:
        item = candidate.evidence
        uncapitalized = item.noninitial - item.capitalized
        frequency = shipped.get(candidate.normalized, 0)
        variants = ordered_variants(item)
        risky = any(
            count.capitalized and source.risk == "risk"
            for count, source in zip(candidate.source_counts, sources, strict=True)
        )
        clean = any(
            count.capitalized and source.risk == "clean"
            for count, source in zip(candidate.source_counts, sources, strict=True)
        )
        license_risk = "mixed" if risky and clean else "risk" if risky else "clean"
        flags = []
        if frequency or uncapitalized:
            flags.append("homograph")
        if candidate.source_count == 1:
            flags.append("single-source")
        if risky:
            flags.append("open-subtitles")
        if len(variants) > 1:
            flags.append("display-variant")
        if frequency:
            flags.append("already-shipped-lowercase")
        if uncapitalized:
            flags.append("uncapitalized-competition")
        if not any(character in cov.TATAR_SPECIFIC for character in candidate.normalized):
            flags.append("no-tatar-specific-letter")
        if frequency or uncapitalized:
            handling = "review-homograph"
        elif len(variants) > 1:
            handling = "review-display-variant"
        elif candidate.source_count == 1:
            handling = "review-single-source"
        else:
            handling = "review-multi-source"
        source_evidence = ";".join(
            f"{source.name}={count.total}/{count.noninitial}/{count.capitalized}/"
            f"{count.display}/{count.lines}"
            for source, count in zip(sources, candidate.source_counts, strict=True)
        )
        row: dict[str, object] = {
            "normalized_form": candidate.normalized,
            "observed_display_form": candidate.display,
            "total_occurrences": item.total,
            "noninitial_occurrences": item.noninitial,
            "noninitial_capitalized": item.capitalized,
            "uncapitalized_noninitial": uncapitalized,
            "cap_ratio": f"{item.capitalized / item.noninitial:.6f}",
            "display_occurrences": candidate.display_occurrences,
            "source_count": candidate.source_count,
            "source_evidence": source_evidence,
            "display_variants": ";".join(
                f"{surface}={count}" for surface, count in variants[:MAX_DISPLAY_VARIANTS]
            ),
            "shipped_frequency": frequency,
            "license_risk": license_risk,
            "suggested_handling": handling,
            "risk_flags": ";".join(flags),
        }
        for field_name in NATIVE_REVIEW_FIELDS:
            row[field_name] = ""
        rows.append(row)
    return rows


def make_preamble(
    sources: Sequence[Source], limit: int, qualifying: int, selected: int,
) -> str:
    lines = [
        "# Tatar proper-name candidates for native review; no row is an admission decision.",
        "# All inputs are training-only. Their sizes and SHA-256 values were verified before counting.",
    ]
    for source in sources:
        lines.append(
            f"# INPUT source={source.name} file={source.manifest_key} size={source.size} "
            f"sha256={source.sha256} revision={source.revision} license={source.license}"
        )
        lines.append(f"# INPUT manifest_source={source.manifest_source}")
    lines.extend([
        "# Eligibility: noninitial_occurrences>=3, cap_ratio>=0.80, display_occurrences>=2,",
        "# and a deterministic observed display form. Line-initial tokens never enter case evidence.",
        "# Latin, internal hyphen/apostrophe, and non-Tatar-alphabet tokens are excluded. All-caps-only",
        "# forms and one-off display forms are excluded. All-caps tokens still count as capitalized.",
        "# observed_display_form: most frequent non-line-initial NFC initial-uppercase/rest-lowercase",
        "# surface; ties use code-point order. display_variants lists at most five surface=count facts.",
        f"# Ranking: source_count, display_occurrences, noninitial_capitalized, total_occurrences",
        f"# descending, then normalized_form; cut at {limit}. Eligible={qualifying}; selected={selected}.",
        "# source_count counts input streams with non-line-initial capitalized evidence, not independent lineage.",
        "# source_evidence: source=total/noninitial/capitalized/display/lines; lines are distinct source lines.",
        "# license_risk: clean=Leipzig-only case evidence; risk=conversational aggregate only; mixed=both.",
        "# The conversational aggregate includes OpenSubtitles, which grants no redistribution license.",
        "# suggested_handling always requires native review. homograph flags possible ordinary-word collision",
        "# from shipped lowercase presence or any lowercase noninitial evidence; it is not a semantic verdict.",
        "# Other flags: single-source, open-subtitles, display-variant, already-shipped-lowercase,",
        "# uncapitalized-competition, no-tatar-specific-letter. A reviewer may add inflected and its base key.",
        "# name_kind, reviewed_display_form, base_normalized_form, approved, reviewer, review_date, and note",
        "# are intentionally empty. Only the native reviewer fills them; approved=yes requires metadata.",
        "# No corpus sentences are included. Counts and individual surface forms are the only corpus evidence.",
    ])
    return "\n".join(lines) + "\n"


def render_tsv(
    rows: Sequence[dict[str, object]], sources: Sequence[Source], limit: int, qualifying: int,
) -> str:
    output = [make_preamble(sources, limit, qualifying, len(rows)), "\t".join(HEADER) + "\n"]
    for row in rows:
        output.append("\t".join(str(row[column]) for column in HEADER) + "\n")
    return "".join(output)


def make_report(
    sources: Sequence[Source], source_counts: Sequence[dict[str, object]],
    evidence: dict[str, Evidence], candidates: Sequence[Candidate],
    rows: Sequence[dict[str, object]], qualifying: int, rejected: dict[str, int],
    limit: int, shipped_report: dict[str, object],
) -> dict[str, object]:
    selected_distribution = {}
    for index, source in enumerate(sources):
        selected_distribution[source.name] = {
            "candidates_with_capitalized_evidence": sum(
                candidate.source_counts[index].capitalized > 0 for candidate in candidates
            ),
            "selected_total_occurrences": sum(
                candidate.source_counts[index].total for candidate in candidates
            ),
        }
    source_count_histogram = Counter(str(candidate.source_count) for candidate in candidates)
    return {
        "schema": "tt-proper-name-review-v1",
        "inputs": [{
            "name": source.name, "path": str(source.path), "manifest_key": source.manifest_key,
            "size": source.size, "sha256": source.sha256, "role": "training",
            "revision": source.revision, "license": source.license,
            "manifest_source": source.manifest_source,
        } for source in sources],
        "development_or_final_paths_read": False,
        "no_corpus_sentences_written": True,
        "native_review_fields_empty": all(not row[field] for row in rows for field in NATIVE_REVIEW_FIELDS),
        "thresholds": {
            "minimum_noninitial_occurrences": F.MIN_CASE_EVIDENCE,
            "minimum_capitalization_ratio": F.PROPER_RATIO,
            "minimum_display_occurrences": MIN_DISPLAY_OCCURRENCES,
        },
        "ranking": [
            "-source_count", "-display_occurrences", "-noninitial_capitalized",
            "-total_occurrences", "normalized_form",
        ],
        "source_count_note": "Distinct input streams, not independent corpus lineage.",
        "token_counts": {
            "lines_read": sum(int(count["lines_read"]) for count in source_counts),
            "tokens_read": sum(int(count["tokens_read"]) for count in source_counts),
            "normalized_tokens": sum(int(count["normalized_tokens"]) for count in source_counts),
            "normalized_types": len(evidence),
        },
        "per_source_token_counts": list(source_counts),
        "rejected_types": rejected,
        "qualifying_candidates_before_cut": qualifying,
        "requested_limit": limit,
        "selected_count": len(rows),
        "shortfall": max(0, limit - len(rows)),
        "selected_per_source": selected_distribution,
        "selected_source_count_histogram": dict(sorted(source_count_histogram.items())),
        "selected_license_risk": dict(sorted(Counter(str(row["license_risk"]) for row in rows).items())),
        "selected_already_in_shipped_dictionary": sum(bool(row["shipped_frequency"]) for row in rows),
        "selected_with_uncapitalized_noninitial": sum(bool(row["uncapitalized_noninitial"]) for row in rows),
        "shipped_dictionary": shipped_report,
    }


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--manifest", type=Path, default=ROOT / "data/corpus-manifest.json")
    parser.add_argument("--shipped", type=Path, default=DEFAULT_SHIPPED)
    parser.add_argument("--limit", type=int, default=150)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("corpora", nargs="+", type=Path)
    return parser


def run(args: argparse.Namespace) -> dict[str, object]:
    if args.limit <= 0:
        raise ReviewError("limit must be positive")
    reject_forbidden_path(args.out)
    reject_forbidden_path(args.report)
    if args.out.resolve() == args.report.resolve():
        raise ReviewError("TSV and JSON output paths must differ")
    sources = load_sources(args.manifest, args.corpora)
    shipped, shipped_report = load_shipped(args.shipped)
    evidence, source_counts = observe_sources(sources)
    candidates, qualifying, rejected = select_candidates(evidence, args.limit)
    attach_source_counts(sources, candidates)
    rows = build_rows(candidates, sources, shipped)
    report = make_report(
        sources, source_counts, evidence, candidates, rows, qualifying, rejected,
        args.limit, shipped_report,
    )
    tsv_text = render_tsv(rows, sources, args.limit, qualifying)
    report_text = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(tsv_text, encoding="utf-8", newline="\n")
    args.report.write_text(report_text, encoding="utf-8", newline="\n")
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = create_argument_parser()
    args = parser.parse_args(argv)
    try:
        report = run(args)
    except (ReviewError, OSError, json.JSONDecodeError, dp.DictionaryPackError) as error:
        parser.error(str(error))
    print(
        f"{args.out}: {report['selected_count']} candidates; "
        f"eligible before cut={report['qualifying_candidates_before_cut']}; "
        f"shortfall={report['shortfall']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
