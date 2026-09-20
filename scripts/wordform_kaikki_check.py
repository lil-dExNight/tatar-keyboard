#!/usr/bin/env python3
"""Dev-time validation of wordform_gen.py against kaikki.org Tatar paradigms.

Downloads (or reuses a cached copy of) the kaikki.org Tatar dictionary extract
and compares the generator's noun paradigms with the declension tables found
there, per stem and per label. Prints agreement stats; informational only —
this tool is NEVER part of a gate (it needs the network on first run).

Data source: https://kaikki.org/dictionary/Tatar/kaikki.org-dictionary-Tatar.jsonl
License: CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/); the
extract is derived from Wiktionary (https://www.wiktionary.org/). The download
is cached under build/ (gitignored); nothing from it is committed or shipped.

Usage:

    python3 scripts/wordform_kaikki_check.py [--jsonl PATH] [--download]
        [--limit N] [--show N]

Scope notes: kaikki possessive-case cells beyond the nominative (e.g. dative
of 1sg possessive татарыма), the genitive of the 3sg possessive (баласының)
and the plural-possessed variants inside possessive cells (абаларым next to
абам) are forms the P1 generator deliberately does not emit; they are filtered
out of the comparison, never counted as disagreements. kaikki's two
3rd-person possessive cells mix our noun.p3 and noun.p3pl (абасы/абалары), so
they compare against the union of both. "Extra" means a generated in-scope
form absent from the stem's whole kaikki form set — mostly our dual-harmony
loan variants (советләргә next to советларга), which P2's attestation filter
removes.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

import dictionary_coverage as coverage  # noqa: E402
import wordform_gen as gen  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JSONL = ROOT / "build" / "kaikki" / "kaikki.org-dictionary-Tatar.jsonl"
KAIKKI_URL = "https://kaikki.org/dictionary/Tatar/kaikki.org-dictionary-Tatar.jsonl"

CASE_TAGS = {
    "genitive": "gen",
    "dative": "dat",
    "accusative": "acc",
    "locative": "loc",
    "ablative": "abl",
}
PERSON_TAGS = {"first-person": "p1", "second-person": "p2", "third-person": "p3"}

# The noun labels the generator emits (noun.pron excluded: suppletive only).
OUR_NOUN_LABELS = frozenset(gen.NOUN_LABELS) - {"noun.pron"}

# kaikki possessive cells also list plural-possessed forms (абаларым next to
# абам); P1 deliberately does not generate plural+possessive combos, so those
# forms are filtered out of the comparison. kaikki's two 3rd-person cells both
# mix our noun.p3 and noun.p3pl (абасы/абалары), which we do generate, so
# those cells compare against the union of the two labels.
PLURAL_POSSESSIVE_MARKERS = ("лар", "ләр", "нар", "нәр")
THIRD_PERSON_CELLS = {"noun.p3", "noun.p3pl"}


def is_plural_possessed(form: str) -> bool:
    return any(marker in form for marker in PLURAL_POSSESSIVE_MARKERS)


def is_possessive_label(label: str) -> bool:
    # noun.p1/p2/p3/p1pl/p2pl/p3pl and noun.p3.* are possessive; noun.pl and
    # noun.pl.* are the plural cases, which of course contain лар themselves.
    return label.startswith("noun.p") and not label.startswith("noun.pl")


class KaikkiError(ValueError):
    """A fail-closed kaikki-check error (missing input, bad rows)."""


def map_tags(tags: list[str]) -> str | None:
    """Map a kaikki tag set to a generator noun label, or None if out of scope."""
    tag_set = set(tags)
    if not tag_set or "romanization" in tag_set or "table-tags" in tag_set:
        return None
    case = next((label for tag, label in CASE_TAGS.items() if tag in tag_set), None)
    person = next((label for tag, label in PERSON_TAGS.items() if tag in tag_set), None)
    if "possessive" in tag_set:
        if person is None:
            return None
        slot = person + ("pl" if "plural" in tag_set else "")
        return f"noun.{slot}" + (f".{case}" if case else "")
    if "nominative" in tag_set:
        # Nominative singular is the bare stem; nominative plural is our pl.
        return "noun.pl" if "plural" in tag_set else None
    if case is not None:
        return f"noun.pl.{case}" if "plural" in tag_set else f"noun.{case}"
    return None


def load_paradigms(path: Path) -> dict[str, dict[str, set[str]]]:
    """Read the kaikki JSONL into {stem: {label: {forms}}} for nouns."""
    paradigms: dict[str, dict[str, set[str]]] = {}
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError as error:
                raise KaikkiError(f"{path}:{line_number}: invalid JSON: {error}") from error
            if entry.get("pos") != "noun":
                continue
            word = entry.get("word")
            if not isinstance(word, str):
                raise KaikkiError(f"{path}:{line_number}: noun entry without a word")
            stem, _reason = coverage.normalize_word(word)
            if stem is None:
                continue
            cell = paradigms.setdefault(stem, {})
            for form_entry in entry.get("forms") or []:
                label = map_tags(form_entry.get("tags") or [])
                if label is None:
                    continue
                form, _ = coverage.normalize_word(form_entry.get("form") or "")
                if form is not None:
                    cell.setdefault(label, set()).add(form)
    return paradigms


def compare(
    paradigms: dict[str, dict[str, set[str]]], exceptions: gen.Exceptions
) -> dict[str, object]:
    """Compare generator output with kaikki paradigms; return the stats report."""
    stems_compared = 0
    stems_skipped_no_harmony = 0
    per_label: dict[str, dict[str, int]] = {}
    recall_forms = 0
    recall_covered = 0
    our_forms = 0
    our_extra = 0
    missed_examples: list[str] = []
    extra_examples: list[str] = []
    out_of_scope_forms = 0

    for stem in sorted(paradigms):
        kaikki_map = paradigms[stem]
        if not gen.harmony_variants(stem):
            stems_skipped_no_harmony += 1
            continue
        in_scope = {label: forms for label, forms in kaikki_map.items() if label in OUR_NOUN_LABELS}
        out_of_scope_forms += sum(len(forms) for label, forms in kaikki_map.items()) - sum(
            len(forms) for forms in in_scope.values()
        )
        if not in_scope:
            continue
        stems_compared += 1
        ours: dict[str, set[str]] = {}
        for harmony in gen.harmony_variants(stem):
            for label, form in gen.generate_noun_forms(stem, harmony, exceptions):
                ours.setdefault(label, set()).add(form)
        kaikki_all = {form for forms in kaikki_map.values() for form in forms}

        for label, kaikki_forms in sorted(in_scope.items()):
            cell = per_label.setdefault(label, {"kaikki": 0, "covered": 0, "extra": 0})
            if label in THIRD_PERSON_CELLS:
                reference = ours.get("noun.p3", set()) | ours.get("noun.p3pl", set())
            else:
                reference = ours.get(label, set())
                if is_possessive_label(label):
                    # Drop plural-possessed forms (абаларым): out of P1 scope.
                    plural_possessed = {form for form in kaikki_forms if is_plural_possessed(form)}
                    out_of_scope_forms += len(plural_possessed)
                    kaikki_forms -= plural_possessed
            covered = {form for form in kaikki_forms if form in reference}
            cell["kaikki"] += len(kaikki_forms)
            cell["covered"] += len(covered)
            recall_forms += len(kaikki_forms)
            recall_covered += len(covered)
            for form in sorted(kaikki_forms - covered):
                if len(missed_examples) < 10_000:
                    missed_examples.append(f"{stem} {label}: kaikki={form}")
        for label, our_forms_here in sorted(ours.items()):
            if label not in in_scope:
                continue  # kaikki has no such cell; nothing to be wrong against
            extra = {form for form in our_forms_here if form not in kaikki_all}
            per_label[label]["extra"] += len(extra)
            our_forms += len(our_forms_here)
            our_extra += len(extra)
            for form in sorted(extra):
                if len(extra_examples) < 10_000:
                    extra_examples.append(f"{stem} {label}: ours={form}")

    return {
        "stems_in_kaikki_with_forms": len(paradigms),
        "stems_compared": stems_compared,
        "stems_skipped_no_harmony": stems_skipped_no_harmony,
        "kaikki_in_scope_forms": recall_forms,
        "kaikki_covered_forms": recall_covered,
        "recall_pct": round(100.0 * recall_covered / recall_forms, 2) if recall_forms else 0.0,
        "our_in_scope_forms_on_shared_labels": our_forms,
        "our_extra_forms": our_extra,
        "extra_pct": round(100.0 * our_extra / our_forms, 2) if our_forms else 0.0,
        "kaikki_out_of_scope_forms": out_of_scope_forms,
        "per_label": per_label,
        "missed_examples": missed_examples,
        "extra_examples": extra_examples,
    }


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--jsonl", type=Path, default=DEFAULT_JSONL)
    parser.add_argument(
        "--download",
        action="store_true",
        help="download the kaikki extract if the cache is missing",
    )
    parser.add_argument("--limit", type=int, default=0, help="compare at most N stems")
    parser.add_argument("--show", type=int, default=15, help="examples per direction")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        path = args.jsonl
        if not path.is_file():
            if not args.download:
                raise KaikkiError(
                    f"{path} is missing; pass --download to fetch {KAIKKI_URL}"
                )
            path.parent.mkdir(parents=True, exist_ok=True)
            temp = path.with_suffix(path.suffix + ".part")
            try:
                with urllib.request.urlopen(KAIKKI_URL, timeout=120) as response:
                    temp.write_bytes(response.read())
                temp.replace(path)
            except BaseException:
                temp.unlink(missing_ok=True)
                raise
        paradigms = load_paradigms(path)
        if args.limit:
            paradigms = dict(sorted(paradigms.items())[: args.limit])
        exceptions = gen.load_exceptions(gen.DEFAULT_EXCEPTIONS)
        report = compare(paradigms, exceptions)
    except (KaikkiError, gen.WordformError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    per_label = report.pop("per_label")
    missed = report.pop("missed_examples")
    extra = report.pop("extra_examples")
    for key, value in report.items():
        print(f"{key}: {value}")
    print("per-label (kaikki forms / covered by us / our extra):")
    for label in sorted(per_label):
        cell = per_label[label]
        pct = 100.0 * cell["covered"] / cell["kaikki"] if cell["kaikki"] else 0.0
        print(f"  {label}: {cell['covered']}/{cell['kaikki']} ({pct:.1f}%) extra={cell['extra']}")
    print("missed examples:")
    for line in missed[: args.show]:
        print(f"  {line}")
    print("extra examples:")
    for line in extra[: args.show]:
        print(f"  {line}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
