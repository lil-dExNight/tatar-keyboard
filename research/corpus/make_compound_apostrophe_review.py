#!/usr/bin/env python3
"""Generate native-review tables for Tatar hyphen compounds and apostrophe forms.

The tables hold exact written forms and counts from the three pinned training corpora, shipped
dictionary evidence, normalization collisions, proposed handling and empty native-review fields.
No corpus sentence is written. Run without arguments to write under the build review directory; use
--validate to recheck those files against the corpora and dictionary.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import unicodedata
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Iterator, Sequence

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack as dictionary_pack  # noqa: E402

MANIFEST_PATH = ROOT / "data" / "corpus-manifest.json"
DICTIONARY_PATH = (
    ROOT / "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib"
)
OUTPUT_DIR = ROOT / "build" / "operator-review"

SOURCE_FILES = {
    "tt_conv_train90": "tt_conv_train90-sentences.txt",
    "tat_mixed_2015": "tat_mixed_2015_1M-sentences.txt",
    "tat_web_2018": "tat_web_2018_1M-sentences.txt",
}
SOURCE_SUBDIRS = {
    "tt_conv_train90": None,
    "tat_mixed_2015": "tat_mixed_2015_1M",
    "tat_web_2018": "tat_web_2018_1M",
}
SOURCE_ORDER = tuple(SOURCE_FILES)

APOSTROPHES = ("'", "\u2019")
STRAIGHT = "'"
CURLY = "\u2019"
HYPHENS = ("-", "\u2010", "\u2011", "\u2012", "\u2013", "\u2014", "\u2212")
EDGE_CHARS = (
    ".,!?;:\"«»„“”‘’()[]{}<>…—–*_/\\|~`^&#№%+=@$"
    "\u00a0\u200b\u200e\u200f"
)
TRIM_CHARS = EDGE_CHARS + "".join(APOSTROPHES) + "".join(HYPHENS)
MAX_WORD_LENGTH = 64
MIN_OCCURRENCES = 2
MIN_BASE_OCCURRENCES = 10
TARGET_COMPOUNDS = 100
TARGET_APOSTROPHES = 100
COMPOUND_QUOTAS = {
    "coordinated": 46,
    "reduplicated": 22,
    "inflected_whole": 18,
    "single_letter_prefix": 4,
    "numeric": 2,
    "latin": 3,
    "mixed_script": 2,
    "debris": 3,
}
APOSTROPHE_QUOTAS = {"straight": 35, "curly": 65}
COMPOUND_CATEGORIES = tuple(COMPOUND_QUOTAS)
APOSTROPHE_CLASSES = ("straight", "curly")
REVIEW_FIELDS = ("approved", "reviewer", "review_date", "note")
REVIEW_COLUMNS = list(REVIEW_FIELDS)
MOJIBAKE_MARKERS = frozenset("ÐÑðñ\ufffd")

COMPOUND_HEADER = [
    "rank",
    "form",
    "category",
    "total_occurrences",
    *[f"{name}_occurrences" for name in SOURCE_ORDER],
    "separator_codepoints",
    "parts",
    "case_variants",
    "mark_variants",
    "base_form",
    "base_occurrences",
    "shipped_exact",
    "shipped_parts",
    "shipped_folded",
    "folded_key",
    "collision_attested_forms",
    "collision_shipped_forms",
    "collision_status",
    "proposed_handling",
    "risk_note",
    *REVIEW_COLUMNS,
]
APOSTROPHE_HEADER = [
    "rank",
    "form",
    "apostrophe_class",
    "apostrophe_codepoint",
    "script",
    "total_occurrences",
    *[f"{name}_occurrences" for name in SOURCE_ORDER],
    "case_variants",
    "mark_variants",
    "nfc_lower_key",
    "apostrophe_removed_key",
    "shipped_exact",
    "shipped_parts",
    "shipped_folded",
    "collision_attested_forms",
    "collision_shipped_forms",
    "collision_status",
    "proposed_handling",
    "risk_note",
    *REVIEW_COLUMNS,
]

COMPOUND_DEFINITIONS = {
    "coordinated": "Two or more Tatar Cyrillic parts joined by a separator; no inflection-only base was found.",
    "reduplicated": "A stem repeats exactly or with an inflectional suffix across the separator.",
    "inflected_whole": "The final part carries an inflection and the shorter whole form is attested.",
    "single_letter_prefix": "At least one part is a single letter, usually an abbreviation or letter prefix.",
    "numeric": "The form contains digits; ranges, dates, models and numeric labels are not lexical words.",
    "latin": "All letters are Latin; the form is outside the Tatar Cyrillic dictionary alphabet.",
    "mixed_script": "Latin and Cyrillic letters occur in one token; the Cyrillic dictionary validator rejects it.",
    "debris": "Encoding damage, a URL-like token, a stray quote, or another non-word shape.",
}
APOSTROPHE_DEFINITIONS = {
    "straight": "The token contains U+0027 APOSTROPHE and no U+2019.",
    "curly": "The token contains U+2019 RIGHT SINGLE QUOTATION MARK and no U+0027.",
}
NORMALIZATION_DEFINITIONS = {
    "unicode": "NFC",
    "case": "Unicode lowercase",
    "compound_folding": "NFC lowercase, then remove every internal hyphen or dash character.",
    "apostrophe_folding": "NFC lowercase, then remove every U+0027 and U+2019 apostrophe.",
    "case_variants": "Exact forms with the same NFC-lowercase key and the same separator or apostrophe sequence.",
    "mark_variants": "Exact forms with the same letters but another dash or apostrophe character; counts stay separate.",
    "written_forms": "Every row is one exact written form. Counts are never merged across case or punctuation.",
}

# Longest suffixes are tested first. A base must be an attested compound with at least
# MIN_BASE_OCCURRENCES occurrences before a form is called inflected_whole.
INFLECTION_SUFFIXES = tuple(
    sorted(
        {
            "ларыннан", "ләреннән", "нарыннан", "нәреннән",
            "ларындагы", "ләрендәге", "нарындагы", "нәрендәге",
            "ларында", "ләрендә", "нарында", "нәрендә",
            "ларын", "ләрен", "нарын", "нәрен",
            "ларыбыз", "ләребез", "нарыбыз", "нәребез",
            "ларыгыз", "ләрегез", "нарыгыз", "нәрегез",
            "лары", "ләре", "нары", "нәре",
            "лар", "ләр", "нар", "нәр",
            "ебезне", "ыбызны", "егезне", "ыгызны",
            "ебез", "ыбыз", "егез", "ыгыз",
            "ләрен", "ларын", "нарын", "нәрен",
            "сенә", "сына", "сенән", "сыннан",
            "енә", "ына", "еннән", "ыннан",
            "дан", "дән", "тан", "тән", "нан", "нән", "ннан", "ннән",
            "да", "дә", "та", "тә",
            "га", "гә", "ка", "кә",
            "ның", "нең", "ны", "не",
            "ым", "ем", "ың", "ең",
            "сы", "се", "сен", "сын",
            "ен", "ын", "ы", "е", "м", "н",
        },
        key=lambda value: (-len(value), value),
    )
)
REDUP_SUFFIXES = frozenset(INFLECTION_SUFFIXES) | frozenset(
    {"сенә", "сына", "сен", "сын", "се", "сы", "енә", "ына", "ләрен", "ларын"}
)


class ReviewError(ValueError):
    """A corpus pin, input, or generated table does not satisfy the review contract."""


@dataclass(frozen=True)
class DictionaryData:
    path: Path
    asset_sha256: str
    size: int
    frequencies: dict[str, int]


@dataclass
class CorpusData:
    per_source: dict[str, Counter[str]]
    total: Counter[str]
    lines: dict[str, int]
    tokens: dict[str, int]


@dataclass(frozen=True)
class Selection:
    forms: list[str]
    category_counts: dict[str, int]
    eligible_count: int
    shortfall_reason: str
    quota_fulfillment: dict[str, dict[str, int]]


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _default_source_paths() -> dict[str, Path]:
    home = Path.home() / "corpora-leipzig"
    result: dict[str, Path] = {}
    for name, filename in SOURCE_FILES.items():
        candidates = [home / filename]
        subdir = SOURCE_SUBDIRS[name]
        if subdir:
            candidates.insert(0, home / subdir / filename)
        candidates.append(ROOT / filename)
        result[name] = next((path for path in candidates if path.is_file()), candidates[0])
    return result


def _parse_overrides(values: Sequence[str]) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for value in values:
        name, separator, path_text = value.partition("=")
        if not separator or name not in SOURCE_FILES or not path_text:
            raise ReviewError(
                f"--corpus expects NAME=PATH for one of {', '.join(SOURCE_ORDER)}: {value!r}"
            )
        result[name] = Path(path_text)
    return result


def resolve_sources(corpus_dir: Path | None, overrides: Sequence[str]) -> dict[str, Path]:
    if corpus_dir is not None:
        paths = {name: corpus_dir / filename for name, filename in SOURCE_FILES.items()}
    else:
        paths = _default_source_paths()
    paths.update(_parse_overrides(overrides))
    missing = [str(path) for path in paths.values() if not path.is_file()]
    if missing:
        raise ReviewError("missing pinned corpus file(s): " + ", ".join(missing))
    return paths


def verify_corpora(
    paths: dict[str, Path], manifest_path: Path
) -> dict[str, dict[str, object]]:
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReviewError(f"cannot read corpus manifest {manifest_path}: {error}") from error
    if not isinstance(manifest, dict):
        raise ReviewError(f"corpus manifest {manifest_path} is not an object")
    pins: dict[str, dict[str, object]] = {}
    for name in SOURCE_ORDER:
        path = paths[name]
        filename = SOURCE_FILES[name]
        entry = manifest.get(filename)
        if not isinstance(entry, dict):
            raise ReviewError(f"corpus manifest has no entry for {filename}")
        expected_size = entry.get("size")
        expected_sha = entry.get("sha256")
        if not isinstance(expected_size, int) or not isinstance(expected_sha, str):
            raise ReviewError(f"corpus manifest entry for {filename} is malformed")
        actual_size = path.stat().st_size
        if actual_size != expected_size:
            raise ReviewError(
                f"{path}: size {actual_size} does not match pinned {expected_size}"
            )
        actual_sha = _sha256_file(path)
        if actual_sha != expected_sha:
            raise ReviewError(f"{path}: SHA-256 does not match the pinned corpus")
        pins[name] = {
            "name": name,
            "file": filename,
            "size": actual_size,
            "sha256": actual_sha,
            "source": entry.get("source", ""),
        }
    return pins


def _sentence_text(line: str) -> str:
    stripped = line.rstrip("\r\n")
    if "\t" in stripped:
        first, rest = stripped.split("\t", 1)
        if first.isdigit():
            return rest
    return stripped


def tokenize_line(line: str) -> Iterator[str]:
    for raw in _sentence_text(line).split():
        token = raw.strip(TRIM_CHARS)
        if token:
            yield token


def scan_corpora(paths: dict[str, Path]) -> CorpusData:
    per_source: dict[str, Counter[str]] = {}
    total: Counter[str] = Counter()
    lines: dict[str, int] = {}
    tokens: dict[str, int] = {}
    for name in SOURCE_ORDER:
        counter: Counter[str] = Counter()
        per_source[name] = counter
        line_count = 0
        token_count = 0
        with paths[name].open("r", encoding="utf-8", errors="replace", newline="") as stream:
            for line in stream:
                line_count += 1
                for token in tokenize_line(line):
                    counter[token] += 1
                    total[token] += 1
                    token_count += 1
        lines[name] = line_count
        tokens[name] = token_count
    return CorpusData(per_source=per_source, total=total, lines=lines, tokens=tokens)


def load_dictionary(path: Path) -> DictionaryData:
    try:
        asset = path.read_bytes()
        parsed = dictionary_pack.validate_asset(asset, language=coverage.TATAR)
    except (OSError, dictionary_pack.DictionaryPackError) as error:
        raise ReviewError(f"cannot read shipped Tatar dictionary {path}: {error}") from error
    frequencies = dict(zip(parsed.words, parsed.frequencies, strict=True))
    return DictionaryData(
        path=path,
        asset_sha256=hashlib.sha256(asset).hexdigest(),
        size=len(asset),
        frequencies=frequencies,
    )


def nfc_lower(text: str) -> str:
    return unicodedata.normalize("NFC", text).lower()


def _has_control(text: str) -> bool:
    return any(unicodedata.category(char) in {"Cc", "Cf", "Cs", "Co", "Cn"} for char in text)


def _has_mojibake(text: str) -> bool:
    return any(marker in text for marker in MOJIBAKE_MARKERS)


def _letters(text: str) -> list[str]:
    return [char for char in text if char.isalpha()]


def _script_kind(text: str) -> str:
    if _has_control(text) or _has_mojibake(text):
        return "debris"
    letters = _letters(text)
    if not letters:
        return "numeric" if any(char.isdigit() for char in text) else "debris"
    names = [unicodedata.name(char, "") for char in letters]
    has_latin = any(name.startswith("LATIN") for name in names)
    has_cyrillic = any(name.startswith("CYRILLIC") for name in names)
    if has_latin and has_cyrillic:
        return "mixed_script"
    if all(name.startswith("LATIN") for name in names):
        return "latin"
    if all(name.startswith("CYRILLIC") for name in names):
        return "cyrillic"
    return "debris"


def _all_tatar_cyrillic(parts: Sequence[str]) -> bool:
    return all(
        part
        and all(char.lower() in coverage.TATAR_ALPHABET for char in part)
        for part in parts
    )


def _split_on(text: str, separators: Sequence[str]) -> list[str]:
    parts = [""]
    for char in text:
        if char in separators:
            parts.append("")
        else:
            parts[-1] += char
    return parts


def _separator_codepoints(text: str, separators: Sequence[str]) -> list[str]:
    seen: list[str] = []
    for char in text:
        if char in separators and char not in seen:
            seen.append(char)
    return [f"U+{ord(char):04X}" for char in seen]


def _signature(text: str, separators: Sequence[str]) -> tuple[str, ...]:
    return tuple(char for char in text if char in separators)


def _reduplicated(parts: Sequence[str]) -> bool:
    if len(parts) < 2:
        return False
    lowered = [part.lower() for part in parts]
    if len(set(lowered)) == 1:
        return True
    if len(lowered) != 2:
        return False
    first, second = lowered
    if first.startswith(second) and first[len(second):] in REDUP_SUFFIXES:
        return True
    if second.startswith(first) and second[len(first):] in REDUP_SUFFIXES:
        return True
    return False


def _inflected_base(
    form: str, parts: Sequence[str], candidates: dict[str, int]
) -> str | None:
    last = parts[-1]
    if len(last) < 3:
        return None
    lowered = last.lower()
    for suffix in INFLECTION_SUFFIXES:
        if len(lowered) <= len(suffix) + 1 or not lowered.endswith(suffix):
            continue
        base = form[: -len(suffix)]
        if candidates.get(base, 0) >= MIN_BASE_OCCURRENCES:
            return base
    return None


def classify_compound(
    form: str, candidates: dict[str, int]
) -> tuple[str | None, str]:
    if len(form) > MAX_WORD_LENGTH:
        return None, "too_long"
    if _has_control(form):
        return None, "control_character"
    if _has_mojibake(form):
        return "debris", "encoding_mojibake"
    if any(char in APOSTROPHES for char in form):
        return None, "apostrophe_form"
    parts = _split_on(form, HYPHENS)
    if len(parts) < 2 or any(not part for part in parts):
        return None, "empty_part"
    if any(separator * 2 in form for separator in HYPHENS):
        return None, "repeated_separator"
    if any(char.isdigit() for char in form):
        allowed = set("0123456789.,:+%/") | set(HYPHENS)
        if any(not (char.isalpha() or char in allowed) for char in form):
            return "debris", "numeric_debris"
        return "numeric", "ok"
    if any(not char.isalpha() and char not in HYPHENS for char in form):
        return "debris", "non_letter_punctuation"
    kind = _script_kind(form)
    if kind == "debris":
        return "debris", "non_tatar_letters"
    if kind == "mixed_script":
        return "mixed_script", "ok"
    if kind == "latin":
        return "latin", "ok"
    if not _all_tatar_cyrillic(parts):
        return "debris", "non_tatar_letters"
    if any(len(part) == 1 for part in parts):
        return "single_letter_prefix", "ok"
    if _reduplicated(parts):
        return "reduplicated", "ok"
    if _inflected_base(form, parts, candidates) is not None:
        return "inflected_whole", "ok"
    return "coordinated", "ok"


def classify_apostrophe(form: str) -> tuple[str | None, str]:
    marks = [char for char in form if char in APOSTROPHES]
    if not marks:
        return None, "no_apostrophe"
    if len(set(marks)) != 1:
        return None, "mixed_apostrophe_marks"
    mark = marks[0]
    if form.count(mark) != 1:
        return None, "multiple_apostrophes"
    if len(form) > MAX_WORD_LENGTH:
        return None, "too_long"
    if _has_control(form):
        return None, "control_character"
    if _has_mojibake(form):
        return None, "encoding_mojibake"
    if any(separator * 2 in form for separator in HYPHENS):
        return None, "repeated_separator"
    parts = _split_on(form, (*APOSTROPHES, *HYPHENS))
    if len(parts) < 2 or any(not part for part in parts):
        return None, "empty_part"
    if any(char.isdigit() for char in form):
        allowed = set("0123456789.,:+%/") | set(HYPHENS) | set(APOSTROPHES)
        if any(not (char.isalpha() or char in allowed) for char in form):
            return None, "numeric_debris"
        return "numeric", "ok"
    if any(not char.isalpha() and char not in APOSTROPHES and char not in HYPHENS for char in form):
        return None, "non_letter_punctuation"
    kind = _script_kind(form)
    if kind == "debris":
        return None, "non_letter_script"
    if kind == "mixed_script":
        return "mixed_script", "ok"
    if kind == "latin":
        return "latin", "ok"
    if kind == "cyrillic" and _all_tatar_cyrillic(parts):
        return "cyrillic", "ok"
    return None, "non_tatar_letters"


def _candidate_compounds(corpus: CorpusData) -> dict[str, int]:
    return {
        form: total
        for form, total in corpus.total.items()
        if total >= MIN_OCCURRENCES
        and any(char in HYPHENS for char in form)
        and not any(char in APOSTROPHES for char in form)
    }


def _candidate_apostrophes(corpus: CorpusData) -> tuple[dict[str, int], Counter[str]]:
    eligible: dict[str, int] = {}
    excluded: Counter[str] = Counter()
    for form, total in corpus.total.items():
        if not any(char in APOSTROPHES for char in form):
            continue
        if total < MIN_OCCURRENCES:
            excluded["single_occurrence"] += 1
            continue
        category, reason = classify_apostrophe(form)
        if category is None:
            excluded[reason] += 1
            continue
        eligible[form] = total
    return eligible, excluded


def _ordered(forms: Iterable[str], counts: Counter[str]) -> list[str]:
    return sorted(forms, key=lambda form: (-counts[form], form))


def select_by_quota(
    eligible: dict[str, int],
    group_of: Callable[[str], str],
    quotas: dict[str, int],
    target: int,
    counts: Counter[str],
) -> Selection:
    groups: dict[str, list[str]] = defaultdict(list)
    for form in eligible:
        groups[group_of(form)].append(form)
    selected: list[str] = []
    selected_set: set[str] = set()
    for group, quota in quotas.items():
        for form in _ordered(groups.get(group, []), counts)[:quota]:
            selected.append(form)
            selected_set.add(form)
    if len(selected) < target:
        remaining = [form for form in eligible if form not in selected_set]
        for form in _ordered(remaining, counts)[: target - len(selected)]:
            selected.append(form)
            selected_set.add(form)
    selected.sort(key=lambda form: (-counts[form], form))
    category_counts = Counter(group_of(form) for form in selected)
    quota_fulfillment = {
        group: {
            "quota": quota,
            "selected": category_counts.get(group, 0),
            "eligible": len(groups.get(group, [])),
        }
        for group, quota in quotas.items()
    }
    shortfall = ""
    if len(selected) < target:
        shortfall = (
            f"only {len(eligible)} eligible forms after the {MIN_OCCURRENCES}-occurrence "
            f"minimum, target {target}"
        )
    return Selection(
        forms=selected,
        category_counts=dict(sorted(category_counts.items())),
        eligible_count=len(eligible),
        shortfall_reason=shortfall,
        quota_fulfillment=quota_fulfillment,
    )


def _collect_groups(
    forms: Iterable[str], key_function: Callable[[str], str], wanted: set[str]
) -> dict[str, list[str]]:
    groups: dict[str, list[str]] = defaultdict(list)
    for form in forms:
        key = key_function(form)
        if key in wanted:
            groups[key].append(form)
    return groups


def _variant_rows(forms: Iterable[str], counts: Counter[str]) -> list[dict[str, object]]:
    return [
        {"form": form, "total": counts[form]}
        for form in sorted(forms, key=lambda form: (-counts[form], form))
    ]


def _collision_attested(
    form: str,
    folded_key: str,
    folded_groups: dict[str, list[str]],
    counts: Counter[str],
) -> list[dict[str, object]]:
    other_forms = [
        other
        for other in folded_groups.get(folded_key, [])
        if other != form and nfc_lower(other) != nfc_lower(form)
    ]
    return _variant_rows(other_forms, counts)


def _collision_shipped(
    folded_key: str, dictionary: DictionaryData
) -> list[dict[str, object]]:
    frequency = dictionary.frequencies.get(folded_key)
    if frequency is None:
        return []
    return [{"form": folded_key, "frequency": frequency}]


def _collision_status(attested: Sequence[object], shipped: Sequence[object]) -> str:
    if attested and shipped:
        return "attested_and_shipped"
    if attested:
        return "attested"
    if shipped:
        return "shipped"
    return "none"


def _shipped_parts(parts: Sequence[str], dictionary: DictionaryData) -> dict[str, int]:
    return {part: dictionary.frequencies.get(nfc_lower(part), 0) for part in parts}


def _format_parts(parts: Sequence[str]) -> str:
    return "|".join(parts)


def _format_variants(variants: Sequence[dict[str, object]], value_key: str) -> str:
    if not variants:
        return "-"
    return ";".join(f"{item['form']}={item[value_key]}" for item in variants)


def _mark_variants(
    form: str,
    nfc_key: str,
    groups: dict[str, list[str]],
    counts: Counter[str],
    signature: Sequence[str],
    signature_function: Callable[[str], tuple[str, ...]],
) -> list[dict[str, object]]:
    forms = [
        other
        for other in groups.get(nfc_key, [])
        if other != form and signature_function(other) != tuple(signature)
    ]
    return _variant_rows(forms, counts)


def _case_variants(
    form: str,
    nfc_key: str,
    groups: dict[str, list[str]],
    counts: Counter[str],
    signature: Sequence[str],
    signature_function: Callable[[str], tuple[str, ...]],
) -> list[dict[str, object]]:
    forms = [
        other
        for other in groups.get(nfc_key, [])
        if other != form and signature_function(other) == tuple(signature)
    ]
    return _variant_rows(forms, counts)


def _compound_mark_key(form: str) -> str:
    return nfc_lower("".join("-" if char in HYPHENS else char for char in form))


def _apostrophe_mark_key(form: str) -> str:
    return nfc_lower("".join("'" if char in APOSTROPHES else char for char in form))


def _apostrophe_signature(form: str) -> tuple[str, ...]:
    return _signature(form, APOSTROPHES)


def _compound_signature(form: str) -> tuple[str, ...]:
    return _signature(form, HYPHENS)


def _compound_folded_key(form: str) -> str:
    return "".join(char for char in nfc_lower(form) if char not in HYPHENS)


def _apostrophe_folded_key(form: str) -> str:
    return "".join(char for char in nfc_lower(form) if char not in APOSTROPHES)


def _collision_clause(
    key: str,
    attested: Sequence[dict[str, object]],
    shipped: Sequence[dict[str, object]],
    operation: str,
) -> str:
    pieces: list[str] = []
    if attested:
        shown = ", ".join("'" + str(item["form"]) + "'" for item in attested[:3])
        if len(attested) > 3:
            shown += f", and {len(attested) - 3} more"
        pieces.append(f"attested {shown}")
    if shipped:
        shown_shipped = ", ".join("'" + str(item["form"]) + "'" for item in shipped)
        pieces.append(f"shipped {shown_shipped}")
    if pieces:
        return (
            f"{operation} gives '{key}', which also matches {' and '.join(pieces)}; "
            "folding would merge distinct written forms."
        )
    return f"{operation} gives '{key}', with no other attested or shipped match."


def _mark_clause(variants: Sequence[dict[str, object]], label: str) -> str:
    if not variants:
        return ""
    shown = "; ".join(f"'{item['form']}'={item['total']}" for item in variants[:3])
    return f"Other {label} variant(s) stay separate: {shown}."


def _compound_risk(
    form: str,
    category: str,
    parts: Sequence[str],
    separator_codepoints: Sequence[str],
    base_form: str | None,
    base_occurrences: int,
    collision_key: str,
    attested: Sequence[dict[str, object]],
    shipped: Sequence[dict[str, object]],
    mark_variants: Sequence[dict[str, object]],
) -> str:
    if category == "numeric":
        first = "Numeric range, date, model or label; not a lexical word. Preserve the input and exclude it from dictionary admission."
    elif category == "latin":
        first = "Latin-script form outside the Tatar Cyrillic dictionary alphabet; preserve exact spelling and case."
    elif category == "mixed_script":
        first = "Mixed Latin/Cyrillic token; the shipped Cyrillic validator rejects it. Preserve the token as typed."
    elif category == "single_letter_prefix":
        first = "A one-letter part is an abbreviation or letter, not a standalone word; splitting would emit a nonword."
    elif category == "reduplicated":
        first = "The stem repeats across the separator; folding would erase the reduplication."
    elif category == "inflected_whole" and base_form is not None:
        first = (
            f"The final part carries inflection; the shorter whole form '{base_form}' is attested "
            f"{base_occurrences} times. Preserve the whole compound."
        )
    elif category == "coordinated":
        first = (
            "Coordinated parts "
            + " / ".join(f"'{part}'" for part in parts)
            + " are joined by the separator; splitting would emit separate words."
        )
    else:
        if _has_mojibake(form):
            first = "Encoding damage is present; this is not a clean Tatar written form. Repair at the source."
        elif any(char in "”’\"@" for char in form):
            first = "A stray quote, URL marker or email marker makes this a non-word token; do not admit it."
        else:
            first = "The token is not a clean compound; treat it as input debris, not a dictionary word."
    clauses = [first]
    if separator_codepoints != ["U+002D"]:
        clauses.append(
            "The separator is "
            + "+".join(separator_codepoints)
            + ", not U+002D HYPHEN-MINUS; keep the exact written form."
        )
    clauses.append(_collision_clause(collision_key, attested, shipped, "Hyphen removal"))
    clauses.append(_mark_clause(mark_variants, "dash"))
    return " ".join(clause for clause in clauses if clause)


def _apostrophe_risk(
    form: str,
    apostrophe_class: str,
    script: str,
    collision_key: str,
    attested: Sequence[dict[str, object]],
    shipped: Sequence[dict[str, object]],
    mark_variants: Sequence[dict[str, object]],
) -> str:
    if script == "latin":
        first = "Latin-script apostrophe form; the Tatar dictionary alphabet is Cyrillic, so it cannot enter that dictionary. Preserve the exact mark and case."
    elif script == "cyrillic":
        first = "Cyrillic plus an apostrophe is outside the shipped Tatar alphabet; treat it as transliteration, abbreviation or typo evidence, not a dictionary word."
    elif script == "mixed_script":
        first = "Mixed Latin/Cyrillic text fails the Cyrillic alphabet validator; preserve the typed token and keep it out of suggestions."
    else:
        first = "Numeric text plus an apostrophe suffix is not a lexical word; preserve the input and exclude it from dictionary admission."
    clauses = [first]
    clauses.append(
        _collision_clause(
            collision_key, attested, shipped, f"{apostrophe_class.capitalize()} apostrophe removal"
        )
    )
    clauses.append(_mark_clause(mark_variants, "apostrophe"))
    return " ".join(clause for clause in clauses if clause)


def _compound_handling(category: str, collision_status: str) -> str:
    if category in {"numeric", "debris"}:
        return "exclude_from_dictionary"
    if category == "latin":
        return "preserve_foreign_form"
    if category == "mixed_script":
        return "preserve_mixed_script"
    if collision_status != "none":
        return "preserve_exact_review_collision"
    return {
        "coordinated": "preserve_compound",
        "reduplicated": "preserve_reduplication",
        "inflected_whole": "preserve_inflected_compound",
        "single_letter_prefix": "preserve_prefix_token",
    }[category]


def _apostrophe_handling(script: str, collision_status: str) -> str:
    if collision_status != "none":
        return "preserve_exact_review_collision"
    if script == "numeric":
        return "preserve_numeric_suffix"
    return {
        "latin": "preserve_apostrophe_latin",
        "cyrillic": "preserve_apostrophe_cyrillic",
        "mixed_script": "preserve_apostrophe_mixed",
    }[script]


def build_compound_rows(
    selection: Selection,
    candidates: dict[str, int],
    corpus: CorpusData,
    dictionary: DictionaryData,
) -> list[dict[str, object]]:
    wanted_nfc = {nfc_lower(form) for form in selection.forms}
    wanted_mark = {_compound_mark_key(form) for form in selection.forms}
    wanted_folded = {_compound_folded_key(form) for form in selection.forms}
    nfc_groups = _collect_groups(corpus.total, nfc_lower, wanted_nfc)
    mark_groups = _collect_groups(corpus.total, _compound_mark_key, wanted_mark)
    folded_groups = _collect_groups(corpus.total, _compound_folded_key, wanted_folded)
    rows: list[dict[str, object]] = []
    for rank, form in enumerate(selection.forms, start=1):
        category, _reason = classify_compound(form, candidates)
        assert category is not None
        parts = _split_on(form, HYPHENS)
        nfc_key = nfc_lower(form)
        mark_key = _compound_mark_key(form)
        folded_key = _compound_folded_key(form)
        separator_codepoints = _separator_codepoints(form, HYPHENS)
        signature = _compound_signature(form)
        case_variants = _case_variants(
            form, nfc_key, nfc_groups, corpus.total, signature, _compound_signature
        )
        mark_variants = _mark_variants(
            form, mark_key, mark_groups, corpus.total, signature, _compound_signature
        )
        base_form = _inflected_base(form, parts, candidates) if category == "inflected_whole" else None
        base_occurrences = corpus.total[base_form] if base_form is not None else 0
        attested = _collision_attested(form, folded_key, folded_groups, corpus.total)
        shipped = _collision_shipped(folded_key, dictionary)
        status = _collision_status(attested, shipped)
        shipped_parts = _shipped_parts(parts, dictionary)
        risk_note = _compound_risk(
            form,
            category,
            parts,
            separator_codepoints,
            base_form,
            base_occurrences,
            folded_key,
            attested,
            shipped,
            mark_variants,
        )
        source_counts = {name: corpus.per_source[name][form] for name in SOURCE_ORDER}
        rows.append(
            {
                "rank": rank,
                "form": form,
                "category": category,
                "total_occurrences": corpus.total[form],
                "source_counts": source_counts,
                "separator_codepoints": separator_codepoints,
                "parts": list(parts),
                "case_variants": case_variants,
                "mark_variants": mark_variants,
                "base_form": base_form,
                "base_occurrences": base_occurrences,
                "shipped_exact": dictionary.frequencies.get(form, 0),
                "shipped_parts": shipped_parts,
                "shipped_folded": dictionary.frequencies.get(folded_key, 0),
                "folded_key": folded_key,
                "collision_attested_forms": attested,
                "collision_shipped_forms": shipped,
                "collision_status": status,
                "proposed_handling": _compound_handling(category, status),
                "risk_note": risk_note,
                "review": {field: "" for field in REVIEW_FIELDS},
            }
        )
    return rows


def build_apostrophe_rows(
    selection: Selection,
    corpus: CorpusData,
    dictionary: DictionaryData,
) -> list[dict[str, object]]:
    wanted_nfc = {nfc_lower(form) for form in selection.forms}
    wanted_mark = {_apostrophe_mark_key(form) for form in selection.forms}
    wanted_folded = {_apostrophe_folded_key(form) for form in selection.forms}
    nfc_groups = _collect_groups(corpus.total, nfc_lower, wanted_nfc)
    mark_groups = _collect_groups(corpus.total, _apostrophe_mark_key, wanted_mark)
    folded_groups = _collect_groups(corpus.total, _apostrophe_folded_key, wanted_folded)
    rows: list[dict[str, object]] = []
    for rank, form in enumerate(selection.forms, start=1):
        category, _reason = classify_apostrophe(form)
        assert category is not None
        apostrophe_class = "straight" if STRAIGHT in form else "curly"
        codepoint = "U+0027" if apostrophe_class == "straight" else "U+2019"
        parts = _split_on(form, (*APOSTROPHES, *HYPHENS))
        nfc_key = nfc_lower(form)
        mark_key = _apostrophe_mark_key(form)
        folded_key = _apostrophe_folded_key(form)
        signature = _apostrophe_signature(form)
        case_variants = _case_variants(
            form, nfc_key, nfc_groups, corpus.total, signature, _apostrophe_signature
        )
        mark_variants = _mark_variants(
            form, mark_key, mark_groups, corpus.total, signature, _apostrophe_signature
        )
        attested = _collision_attested(form, folded_key, folded_groups, corpus.total)
        shipped = _collision_shipped(folded_key, dictionary)
        status = _collision_status(attested, shipped)
        risk_note = _apostrophe_risk(
            form, apostrophe_class, category, folded_key, attested, shipped, mark_variants
        )
        source_counts = {name: corpus.per_source[name][form] for name in SOURCE_ORDER}
        rows.append(
            {
                "rank": rank,
                "form": form,
                "apostrophe_class": apostrophe_class,
                "apostrophe_codepoint": codepoint,
                "script": category,
                "total_occurrences": corpus.total[form],
                "source_counts": source_counts,
                "case_variants": case_variants,
                "mark_variants": mark_variants,
                "nfc_lower_key": nfc_key,
                "apostrophe_removed_key": folded_key,
                "shipped_exact": dictionary.frequencies.get(form, 0),
                "shipped_parts": _shipped_parts(parts, dictionary),
                "shipped_folded": dictionary.frequencies.get(folded_key, 0),
                "collision_attested_forms": attested,
                "collision_shipped_forms": shipped,
                "collision_status": status,
                "proposed_handling": _apostrophe_handling(category, status),
                "risk_note": risk_note,
                "review": {field: "" for field in REVIEW_FIELDS},
            }
        )
    return rows


def _tsv_fields_compound(row: dict[str, object]) -> list[str]:
    source_counts = row["source_counts"]
    assert isinstance(source_counts, dict)
    return [
        str(row["rank"]),
        str(row["form"]),
        str(row["category"]),
        str(row["total_occurrences"]),
        *[str(source_counts[name]) for name in SOURCE_ORDER],
        "+".join(row["separator_codepoints"]),
        _format_parts(row["parts"]),
        _format_variants(row["case_variants"], "total"),
        _format_variants(row["mark_variants"], "total"),
        "" if row["base_form"] is None else str(row["base_form"]),
        str(row["base_occurrences"]),
        str(row["shipped_exact"]),
        _format_variants(
            [{"form": part, "frequency": frequency} for part, frequency in row["shipped_parts"].items()],
            "frequency",
        ),
        str(row["shipped_folded"]),
        str(row["folded_key"]),
        _format_variants(row["collision_attested_forms"], "total"),
        _format_variants(row["collision_shipped_forms"], "frequency"),
        str(row["collision_status"]),
        str(row["proposed_handling"]),
        str(row["risk_note"]),
        *["" for _ in REVIEW_FIELDS],
    ]


def _tsv_fields_apostrophe(row: dict[str, object]) -> list[str]:
    source_counts = row["source_counts"]
    assert isinstance(source_counts, dict)
    return [
        str(row["rank"]),
        str(row["form"]),
        str(row["apostrophe_class"]),
        str(row["apostrophe_codepoint"]),
        str(row["script"]),
        str(row["total_occurrences"]),
        *[str(source_counts[name]) for name in SOURCE_ORDER],
        _format_variants(row["case_variants"], "total"),
        _format_variants(row["mark_variants"], "total"),
        str(row["nfc_lower_key"]),
        str(row["apostrophe_removed_key"]),
        str(row["shipped_exact"]),
        _format_variants(
            [{"form": part, "frequency": frequency} for part, frequency in row["shipped_parts"].items()],
            "frequency",
        ),
        str(row["shipped_folded"]),
        _format_variants(row["collision_attested_forms"], "total"),
        _format_variants(row["collision_shipped_forms"], "frequency"),
        str(row["collision_status"]),
        str(row["proposed_handling"]),
        str(row["risk_note"]),
        *["" for _ in REVIEW_FIELDS],
    ]


def _write_tsv(
    path: Path, header: Sequence[str], rows: Sequence[dict[str, object]], field_function: Callable
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write("\t".join(header) + "\n")
        for row in rows:
            fields = field_function(row)
            if len(fields) != len(header):
                raise ReviewError(f"{path}: row {row['form']!r} has the wrong field count")
            if any("\t" in field or "\n" in field or "\r" in field for field in fields):
                raise ReviewError(f"{path}: row {row['form']!r} contains a TSV control character")
            stream.write("\t".join(fields) + "\n")
    temporary.replace(path)


def _write_json(path: Path, report: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def _dictionary_report(dictionary: DictionaryData) -> dict[str, object]:
    try:
        path_text = str(dictionary.path.relative_to(ROOT))
    except ValueError:
        path_text = str(dictionary.path)
    return {
        "path": path_text,
        "size": dictionary.size,
        "sha256": dictionary.asset_sha256,
        "entry_count": len(dictionary.frequencies),
        "lookup": "exact form for exact evidence; NFC-lowercase part and folded key for normalization evidence",
    }


def _collision_summary(rows: Sequence[dict[str, object]]) -> dict[str, int]:
    return dict(
        sorted(Counter(str(row["collision_status"]) for row in rows).items())
    )


def _report_base(
    artifact: str,
    pins: dict[str, dict[str, object]],
    dictionary: DictionaryData,
    corpus: CorpusData,
    selection: Selection,
    target: int,
    quotas: dict[str, int],
    definitions: dict[str, str],
) -> dict[str, object]:
    return {
        "schema_version": 1,
        "artifact": artifact,
        "generator": "research/corpus/make_compound_apostrophe_review.py",
        "corpus_role": "training only: tt_conv_train90, tat_mixed_2015 and tat_web_2018",
        "content_policy": "exact written forms and counts only; no corpus sentence is written",
        "corpora": [pins[name] for name in SOURCE_ORDER],
        "corpus_totals": {
            name: {"lines": corpus.lines[name], "tokens": corpus.tokens[name]}
            for name in SOURCE_ORDER
        },
        "dictionary": _dictionary_report(dictionary),
        "selection": {
            "target": target,
            "minimum_occurrences": MIN_OCCURRENCES,
            "quotas": quotas,
            "eligible_distinct": selection.eligible_count,
            "selected": len(selection.forms),
            "category_counts": selection.category_counts,
            "quota_fulfillment": selection.quota_fulfillment,
            "shortfall_reason": selection.shortfall_reason,
        },
        "normalization": NORMALIZATION_DEFINITIONS,
        "category_definitions": definitions,
        "review_fields": {field: "" for field in REVIEW_FIELDS},
        "written_form_policy": "One exact written form per row; counts and variants are never merged across rows.",
    }


def generate(
    sources: dict[str, Path],
    manifest_path: Path,
    dictionary_path: Path,
    output_dir: Path,
    target_compounds: int = TARGET_COMPOUNDS,
    target_apostrophes: int = TARGET_APOSTROPHES,
) -> dict[str, object]:
    pins = verify_corpora(sources, manifest_path)
    corpus = scan_corpora(sources)
    dictionary = load_dictionary(dictionary_path)

    compound_candidates = _candidate_compounds(corpus)
    compound_groups: dict[str, str] = {}
    compound_excluded: Counter[str] = Counter()
    for form in compound_candidates:
        category, reason = classify_compound(form, compound_candidates)
        if category is None:
            compound_excluded[reason] += 1
        else:
            compound_groups[form] = category
    compound_eligible = {form: compound_candidates[form] for form in compound_groups}
    compound_selection = select_by_quota(
        compound_eligible,
        lambda form: compound_groups[form],
        COMPOUND_QUOTAS,
        target_compounds,
        corpus.total,
    )
    compound_rows = build_compound_rows(
        compound_selection, compound_candidates, corpus, dictionary
    )

    apostrophe_eligible, apostrophe_excluded = _candidate_apostrophes(corpus)
    apostrophe_groups = {
        form: ("straight" if STRAIGHT in form else "curly")
        for form in apostrophe_eligible
    }
    apostrophe_selection = select_by_quota(
        apostrophe_eligible,
        lambda form: apostrophe_groups[form],
        APOSTROPHE_QUOTAS,
        target_apostrophes,
        corpus.total,
    )
    apostrophe_rows = build_apostrophe_rows(
        apostrophe_selection, corpus, dictionary
    )

    compound_report = _report_base(
        "tt-compounds",
        pins,
        dictionary,
        corpus,
        compound_selection,
        target_compounds,
        COMPOUND_QUOTAS,
        COMPOUND_DEFINITIONS,
    )
    compound_report["selection"]["excluded_by_reason"] = dict(sorted(compound_excluded.items()))
    compound_report["selection"]["candidate_distinct"] = len(compound_candidates)
    compound_report["selection"]["below_minimum_distinct"] = sum(
        1
        for form, total in corpus.total.items()
        if total < MIN_OCCURRENCES
        and any(char in HYPHENS for char in form)
        and not any(char in APOSTROPHES for char in form)
    )
    compound_report["collision_summary"] = _collision_summary(compound_rows)
    compound_report["category_counts_selected"] = dict(
        sorted(Counter(str(row["category"]) for row in compound_rows).items())
    )
    compound_report["rows"] = compound_rows

    apostrophe_report = _report_base(
        "tt-apostrophes",
        pins,
        dictionary,
        corpus,
        apostrophe_selection,
        target_apostrophes,
        APOSTROPHE_QUOTAS,
        APOSTROPHE_DEFINITIONS,
    )
    apostrophe_report["selection"]["excluded_by_reason"] = dict(sorted(apostrophe_excluded.items()))
    apostrophe_report["selection"]["eligible_by_mark"] = dict(
        sorted(Counter(apostrophe_groups[form] for form in apostrophe_eligible).items())
    )
    apostrophe_report["collision_summary"] = _collision_summary(apostrophe_rows)
    apostrophe_report["mark_counts_selected"] = dict(
        sorted(Counter(str(row["apostrophe_class"]) for row in apostrophe_rows).items())
    )
    apostrophe_report["rows"] = apostrophe_rows

    _write_tsv(
        output_dir / "tt-compounds.tsv",
        COMPOUND_HEADER,
        compound_rows,
        _tsv_fields_compound,
    )
    _write_tsv(
        output_dir / "tt-apostrophes.tsv",
        APOSTROPHE_HEADER,
        apostrophe_rows,
        _tsv_fields_apostrophe,
    )
    _write_json(output_dir / "tt-compounds.json", compound_report)
    _write_json(output_dir / "tt-apostrophes.json", apostrophe_report)
    return {"compounds": compound_report, "apostrophes": apostrophe_report}


def _read_tsv(path: Path, header: Sequence[str]) -> list[dict[str, str]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ReviewError(f"cannot read {path}: {error}") from error
    if not lines:
        raise ReviewError(f"{path}: empty table")
    actual_header = lines[0].split("\t")
    if actual_header != list(header):
        raise ReviewError(f"{path}: unexpected header")
    rows: list[dict[str, str]] = []
    for line_number, line in enumerate(lines[1:], start=2):
        fields = line.split("\t")
        if len(fields) != len(header):
            raise ReviewError(f"{path}:{line_number}: wrong column count")
        rows.append(dict(zip(header, fields, strict=True)))
    return rows


def _independent_tokenize(line: str) -> Iterator[str]:
    text = line.rstrip("\r\n")
    if "\t" in text:
        first, rest = text.split("\t", 1)
        if first.isdigit():
            text = rest
    for raw in text.split():
        token = raw.strip(TRIM_CHARS)
        if token:
            yield token


def _independent_counts(
    sources: dict[str, Path],
) -> tuple[dict[str, dict[str, int]], dict[str, int], dict[str, int]]:
    per_source = {name: Counter() for name in SOURCE_ORDER}
    total: Counter[str] = Counter()
    lines: dict[str, int] = {}
    for name in SOURCE_ORDER:
        count = 0
        with sources[name].open("r", encoding="utf-8", errors="replace", newline="") as stream:
            for line in stream:
                count += 1
                for token in _independent_tokenize(line):
                    per_source[name][token] += 1
                    total[token] += 1
        lines[name] = count
    return per_source, total, lines


def _independent_groups(
    sources: dict[str, Path],
    wanted_folded: set[str],
    wanted_nfc: set[str],
    wanted_mark: set[str],
    folded_function: Callable[[str], str],
    mark_function: Callable[[str], str],
) -> tuple[dict[str, list[str]], dict[str, list[str]], dict[str, list[str]]]:
    folded: dict[str, list[str]] = defaultdict(list)
    nfc: dict[str, list[str]] = defaultdict(list)
    mark: dict[str, list[str]] = defaultdict(list)
    for name in SOURCE_ORDER:
        with sources[name].open("r", encoding="utf-8", errors="replace", newline="") as stream:
            for line in stream:
                for token in _independent_tokenize(line):
                    key = folded_function(token)
                    if key in wanted_folded and token not in folded[key]:
                        folded[key].append(token)
                    nfc_key = nfc_lower(token)
                    if nfc_key in wanted_nfc and token not in nfc[nfc_key]:
                        nfc[nfc_key].append(token)
                    mark_key = mark_function(token)
                    if mark_key in wanted_mark and token not in mark[mark_key]:
                        mark[mark_key].append(token)
    return folded, nfc, mark


def _validate_review_fields(path: Path, rows: Sequence[dict[str, str]]) -> None:
    for row in rows:
        for field in REVIEW_FIELDS:
            if row.get(field, "") != "":
                raise ReviewError(f"{path}: review field {field} must be empty")


def _validate_common_row(path: Path, row: dict[str, str]) -> None:
    form = row["form"]
    if not form or any(char.isspace() for char in form):
        raise ReviewError(f"{path}: invalid form {form!r}")
    if len(form) > MAX_WORD_LENGTH:
        raise ReviewError(f"{path}: form is longer than {MAX_WORD_LENGTH}: {form!r}")
    for field, value in row.items():
        if "\t" in value or "\n" in value or "\r" in value:
            raise ReviewError(f"{path}: field {field} contains a control character")
    total = int(row["total_occurrences"])
    source_total = sum(int(row[f"{name}_occurrences"]) for name in SOURCE_ORDER)
    if total != source_total:
        raise ReviewError(f"{path}: total/source mismatch for {form!r}")
    if total < MIN_OCCURRENCES:
        raise ReviewError(f"{path}: {form!r} is below the occurrence floor")
    if row["collision_status"] not in {"none", "attested", "shipped", "attested_and_shipped"}:
        raise ReviewError(f"{path}: invalid collision status for {form!r}")


def validate_outputs(
    sources: dict[str, Path],
    manifest_path: Path,
    dictionary_path: Path,
    output_dir: Path,
) -> dict[str, object]:
    pins = verify_corpora(sources, manifest_path)
    dictionary = load_dictionary(dictionary_path)
    compound_rows = _read_tsv(output_dir / "tt-compounds.tsv", COMPOUND_HEADER)
    apostrophe_rows = _read_tsv(output_dir / "tt-apostrophes.tsv", APOSTROPHE_HEADER)
    _validate_review_fields(output_dir / "tt-compounds.tsv", compound_rows)
    _validate_review_fields(output_dir / "tt-apostrophes.tsv", apostrophe_rows)
    if len({row["form"] for row in compound_rows}) != len(compound_rows):
        raise ReviewError("compound table contains duplicate written forms")
    if len({row["form"] for row in apostrophe_rows}) != len(apostrophe_rows):
        raise ReviewError("apostrophe table contains duplicate written forms")
    if {row["form"] for row in compound_rows} & {row["form"] for row in apostrophe_rows}:
        raise ReviewError("compound and apostrophe tables overlap")
    for path, rows in ((output_dir / "tt-compounds.tsv", compound_rows),
                       (output_dir / "tt-apostrophes.tsv", apostrophe_rows)):
        for expected_rank, row in enumerate(rows, start=1):
            _validate_common_row(path, row)
            if int(row["rank"]) != expected_rank:
                raise ReviewError(f"{path}: ranks are not consecutive at {row['form']!r}")

    per_source, total, _lines = _independent_counts(sources)
    for path, rows in ((output_dir / "tt-compounds.tsv", compound_rows),
                       (output_dir / "tt-apostrophes.tsv", apostrophe_rows)):
        for row in rows:
            form = row["form"]
            if total[form] != int(row["total_occurrences"]):
                raise ReviewError(f"{path}: total changed for {form!r}")
            for name in SOURCE_ORDER:
                if per_source[name][form] != int(row[f"{name}_occurrences"]):
                    raise ReviewError(f"{path}: {name} count changed for {form!r}")

    compound_folded_keys = {
        "".join(char for char in nfc_lower(row["form"]) if char not in HYPHENS)
        for row in compound_rows
    }
    apostrophe_folded_keys = {
        "".join(char for char in nfc_lower(row["form"]) if char not in APOSTROPHES)
        for row in apostrophe_rows
    }
    compound_folded, compound_nfc, compound_mark = _independent_groups(
        sources,
        compound_folded_keys,
        {nfc_lower(row["form"]) for row in compound_rows},
        {_compound_mark_key(row["form"]) for row in compound_rows},
        _compound_folded_key,
        _compound_mark_key,
    )
    apostrophe_folded, apostrophe_nfc, apostrophe_mark = _independent_groups(
        sources,
        apostrophe_folded_keys,
        {nfc_lower(row["form"]) for row in apostrophe_rows},
        {_apostrophe_mark_key(row["form"]) for row in apostrophe_rows},
        _apostrophe_folded_key,
        _apostrophe_mark_key,
    )
    table_specs = (
        (
            output_dir / "tt-compounds.tsv",
            compound_rows,
            compound_folded,
            compound_nfc,
            compound_mark,
            HYPHENS,
            _compound_signature,
            _compound_mark_key,
        ),
        (
            output_dir / "tt-apostrophes.tsv",
            apostrophe_rows,
            apostrophe_folded,
            apostrophe_nfc,
            apostrophe_mark,
            (*APOSTROPHES, *HYPHENS),
            _apostrophe_signature,
            _apostrophe_mark_key,
        ),
    )
    for (path, rows, folded, nfc_groups, mark_groups, separators,
         signature_function, mark_function) in table_specs:
        for row in rows:
            form = row["form"]
            nfc_key = nfc_lower(form)
            signature = signature_function(form)
            expected_case = _format_variants(
                _variant_rows(
                    [
                        other
                        for other in nfc_groups.get(nfc_key, [])
                        if other != form and signature_function(other) == signature
                    ],
                    total,
                ),
                "total",
            )
            if row["case_variants"] != expected_case:
                raise ReviewError(f"{path}: case variants changed for {form!r}")
            expected_mark = _format_variants(
                _variant_rows(
                    [
                        other
                        for other in mark_groups.get(mark_function(form), [])
                        if other != form and signature_function(other) != signature
                    ],
                    total,
                ),
                "total",
            )
            if row["mark_variants"] != expected_mark:
                raise ReviewError(f"{path}: mark variants changed for {form!r}")
            if path.name == "tt-compounds.tsv":
                folded_key = "".join(char for char in nfc_lower(form) if char not in HYPHENS)
                if row["folded_key"] != folded_key:
                    raise ReviewError(f"{path}: folded key changed for {form!r}")
            else:
                folded_key = "".join(char for char in nfc_lower(form) if char not in APOSTROPHES)
                if row["apostrophe_removed_key"] != folded_key:
                    raise ReviewError(f"{path}: apostrophe key changed for {form!r}")
            other_forms = [
                other
                for other in folded.get(folded_key, [])
                if other != form and nfc_lower(other) != nfc_lower(form)
            ]
            expected_attested = ";".join(
                f"{other}={total[other]}"
                for other in sorted(other_forms, key=lambda item: (-total[item], item))
            ) or "-"
            if row["collision_attested_forms"] != expected_attested:
                raise ReviewError(f"{path}: attested collision changed for {form!r}")
            shipped = dictionary.frequencies.get(folded_key)
            expected_shipped = f"{folded_key}={shipped}" if shipped is not None else "-"
            if row["collision_shipped_forms"] != expected_shipped:
                raise ReviewError(f"{path}: shipped collision changed for {form!r}")
            expected_status = _collision_status(
                other_forms, [folded_key] if shipped is not None else []
            )
            if row["collision_status"] != expected_status:
                raise ReviewError(f"{path}: collision status changed for {form!r}")
            if int(row["shipped_exact"]) != dictionary.frequencies.get(form, 0):
                raise ReviewError(f"{path}: exact shipped evidence changed for {form!r}")
            if int(row["shipped_folded"]) != (shipped or 0):
                raise ReviewError(f"{path}: folded shipped evidence changed for {form!r}")
            if path.name == "tt-compounds.tsv":
                parts = _split_on(form, separators)
                expected_parts = _shipped_parts(parts, dictionary)
                expected_parts_text = _format_variants(
                    [{"form": part, "frequency": frequency}
                     for part, frequency in expected_parts.items()],
                    "frequency",
                )
                if row["shipped_parts"] != expected_parts_text:
                    raise ReviewError(f"{path}: part evidence changed for {form!r}")
                expected_separators = "+".join(_separator_codepoints(form, HYPHENS))
                if row["separator_codepoints"] != expected_separators:
                    raise ReviewError(f"{path}: separator evidence changed for {form!r}")
                if int(row["base_occurrences"]) < 0:
                    raise ReviewError(f"{path}: negative base evidence for {form!r}")
            else:
                if not {STRAIGHT, CURLY} & set(form):
                    raise ReviewError(f"{path}: apostrophe mark missing in {form!r}")
                if row["apostrophe_class"] not in APOSTROPHE_CLASSES:
                    raise ReviewError(f"{path}: invalid apostrophe class for {form!r}")
                if (row["apostrophe_class"] == "straight") != (STRAIGHT in form):
                    raise ReviewError(f"{path}: apostrophe class mismatch for {form!r}")
                expected_codepoint = "U+0027" if STRAIGHT in form else "U+2019"
                if row["apostrophe_codepoint"] != expected_codepoint:
                    raise ReviewError(f"{path}: apostrophe codepoint changed for {form!r}")
                if row["nfc_lower_key"] != nfc_lower(form):
                    raise ReviewError(f"{path}: NFC-lowercase key changed for {form!r}")
                parts = _split_on(form, separators)
                expected_parts = _shipped_parts(parts, dictionary)
                expected_parts_text = _format_variants(
                    [{"form": part, "frequency": frequency}
                     for part, frequency in expected_parts.items()],
                    "frequency",
                )
                if row["shipped_parts"] != expected_parts_text:
                    raise ReviewError(f"{path}: part evidence changed for {form!r}")

    for name, filename in (("compounds", "tt-compounds.json"), ("apostrophes", "tt-apostrophes.json")):
        try:
            report = json.loads((output_dir / filename).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ReviewError(f"cannot read {filename}: {error}") from error
        expected_rows = compound_rows if name == "compounds" else apostrophe_rows
        report_rows = report.get("rows", [])
        if len(report_rows) != len(expected_rows):
            raise ReviewError(f"{filename}: row count mismatch")
        for expected, actual in zip(expected_rows, report_rows, strict=True):
            if actual.get("form") != expected["form"]:
                raise ReviewError(f"{filename}: row order mismatch at {expected['form']!r}")
            if actual.get("total_occurrences") != int(expected["total_occurrences"]):
                raise ReviewError(f"{filename}: row total mismatch at {expected['form']!r}")
            if actual.get("review") != {field: "" for field in REVIEW_FIELDS}:
                raise ReviewError(f"{filename}: row review fields are not empty")
        selection = report.get("selection", {})
        if selection.get("selected") != len(expected_rows):
            raise ReviewError(f"{filename}: selected count mismatch")
        if selection.get("category_counts") != dict(
            sorted(Counter(
                row["category"] if name == "compounds" else row["apostrophe_class"]
                for row in expected_rows
            ).items())
        ):
            raise ReviewError(f"{filename}: category counts mismatch")
        if report.get("corpora") != [pins[source] for source in SOURCE_ORDER]:
            raise ReviewError(f"{filename}: corpus pin mismatch")
        if report.get("dictionary", {}).get("sha256") != dictionary.asset_sha256:
            raise ReviewError(f"{filename}: dictionary pin mismatch")
        if report.get("review_fields") != {field: "" for field in REVIEW_FIELDS}:
            raise ReviewError(f"{filename}: review fields are not empty")

    return {
        "valid": True,
        "compounds": len(compound_rows),
        "apostrophes": len(apostrophe_rows),
        "corpora": [pins[source]["sha256"] for source in SOURCE_ORDER],
        "dictionary_sha256": dictionary.asset_sha256,
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--manifest", type=Path, default=MANIFEST_PATH)
    parser.add_argument("--dictionary", type=Path, default=DICTIONARY_PATH)
    parser.add_argument("--output-dir", type=Path, default=OUTPUT_DIR)
    parser.add_argument("--corpus-dir", type=Path)
    parser.add_argument(
        "--corpus",
        action="append",
        default=[],
        metavar="NAME=PATH",
        help="override one pinned training corpus",
    )
    parser.add_argument("--target-compounds", type=int, default=TARGET_COMPOUNDS)
    parser.add_argument("--target-apostrophes", type=int, default=TARGET_APOSTROPHES)
    parser.add_argument("--validate", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        sources = resolve_sources(args.corpus_dir, args.corpus)
        if args.validate:
            summary = validate_outputs(
                sources, args.manifest, args.dictionary, args.output_dir
            )
            print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
            return 0
        reports = generate(
            sources,
            args.manifest,
            args.dictionary,
            args.output_dir,
            target_compounds=args.target_compounds,
            target_apostrophes=args.target_apostrophes,
        )
        compound_selection = reports["compounds"]["selection"]
        apostrophe_selection = reports["apostrophes"]["selection"]
        print(
            json.dumps(
                {
                    "compounds": compound_selection,
                    "apostrophes": apostrophe_selection,
                    "output_dir": str(args.output_dir),
                },
                ensure_ascii=False,
                sort_keys=True,
            )
        )
        return 0
    except ReviewError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
