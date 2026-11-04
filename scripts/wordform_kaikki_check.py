#!/usr/bin/env python3
"""Dev-time check of wordform_gen.py paradigms against kaikki.org Tatar tables.

Input: the kaikki.org Tatar JSONL (CC BY-SA 4.0, from Wiktionary), fetched with
``--download`` or reused from the gitignored build/ cache; nothing is committed.
Output: per-stem, per-label agreement stats on stdout for noun and verb cells;
``--out`` additionally writes candidate ``override`` rows for the cells the
generator misses (review before merging into scripts/wordform_exceptions_tat.tsv).
Informational only, not a release check (needs the network on first run). Cells
the generator does not emit are filtered out; "extra" is a generated form absent
from the stem's kaikki forms. Exit 2 on missing input or bad rows.

Verb specifics: kaikki keys the Tatar verb tables by the masdar and leaves the
imperative rows tagged ``error-unrecognized-form`` (the conjugation template
declares no tags there), so the stem is recovered from the imperative 2sg row.
Every person cell lists the affirmative and the negative form under the same
tags; the polarity split happens at comparison time. kaikki's "indefinite past"
is our -GAн recent past, its "definite past" our -DI past.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path
from typing import Mapping, Sequence

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

# Form-list rows that are not paradigm cells.
SKIP_FORM_TAGS = {"romanization", "table-tags", "inflection-template"}

# The noun labels the generator emits (noun.pron excluded: suppletive only).
OUR_NOUN_LABELS = frozenset(gen.NOUN_LABELS) - {"noun.pron"}

# The verb labels the generator emits.
OUR_VERB_LABELS = frozenset(gen.VERB_LABELS)

# kaikki possessive cells also list plural-possessed forms (абаларым next to
# абам); the generator does not produce plural+possessive combos, so those
# forms are filtered out of the comparison. kaikki's two 3rd-person cells both
# mix our noun.p3 and noun.p3pl (абасы/абалары), which we do generate, so
# those cells compare against the union of the two labels.
PLURAL_POSSESSIVE_MARKERS = ("лар", "ләр", "нар", "нәр")
THIRD_PERSON_CELLS = {"noun.p3", "noun.p3pl"}


def is_plural_possessed(form: str) -> bool:
    return any(marker in form for marker in PLURAL_POSSESSIVE_MARKERS)


def is_possessive_label(label: str) -> bool:
    # noun.p1/p2/p3/p1pl/p2pl/p3pl and noun.p3.* are possessive; noun.pl and
    # noun.pl.* are the plural cases, which contain лар themselves.
    return label.startswith("noun.p") and not label.startswith("noun.pl")


# kaikki person/number tag pairs -> our person slot. The informal/formal
# distinction rides along (2sg is informal, 2pl formal) and is not kept.
VERB_PERSON_SLOTS = {
    ("first-person", "singular"): "1sg",
    ("second-person", "singular"): "2sg",
    ("third-person", "singular"): "3sg",
    ("first-person", "plural"): "1pl",
    ("second-person", "plural"): "2pl",
    ("third-person", "plural"): "3pl",
}

# kaikki tense tags -> (our affirmative tense, our negative tense). The
# negative definite future (язмаячак) maps to verb.deffut.neg.*, which the
# generator does not emit; it counts as out of scope downstream.
VERB_TENSE_TAGS = (
    (("present",), "verb.pres", "verb.pres.neg"),
    (("past", "indefinite"), "verb.rpast", "verb.rpast.neg"),
    (("past", "definite"), "verb.past", "verb.past.neg"),
    (("future", "indefinite"), "verb.fut", "verb.fut.neg"),
    (("future", "definite"), "verb.deffut", "verb.deffut.neg"),
    (("conditional",), "verb.cond", "verb.cond.neg"),
)

# The imperative 2sg row carries exactly these tags, once for the affirmative
# and once for the negative form (бар / барма); the affirmative is the shorter
# one, and its form is the stem the generator works from.
IMPERATIVE_2SG_TAGS = frozenset(
    {"error-unrecognized-form", "informal", "second-person", "singular"}
)

# The bare error-unrecognized-form bucket mixes the gerunds, the participles
# and participle-shaped finite forms; a form attributes to whichever of these
# generator labels produces it, else it is out of scope (язарга, язмак).
NONFINITE_SENTINEL = "verb.nonfinite"
NONFINITE_LABELS = (
    "verb.ger.ip",
    "verb.ger.gac",
    "verb.ger.ganci",
    "verb.ger.neg",
    "verb.part.gan",
    "verb.part.uci",
    "verb.part.asi",
)


class KaikkiError(ValueError):
    """A kaikki-check error: missing input or bad rows."""


def map_noun_tags(tags: list[str]) -> str | None:
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


def map_verb_tags(tags: list[str]) -> tuple[str, str | None] | None:
    """Map a kaikki verb tag set to an (affirmative, negative) label pair.

    The negative label is None where the generator emits no negative
    (imperatives, the masdar, the non-finite bucket); None overall for rows
    that are not paradigm cells.
    """
    tag_set = set(tags)
    if not tag_set or tag_set & SKIP_FORM_TAGS or "infinitive" in tag_set:
        return None
    person = next(
        (
            slot
            for (person_tag, number), slot in VERB_PERSON_SLOTS.items()
            if person_tag in tag_set and number in tag_set
        ),
        None,
    )
    if "error-unrecognized-form" in tag_set:
        if person is None:
            return (NONFINITE_SENTINEL, None)
        return (f"verb.imp.{person}", None)
    if "noun-from-verb" in tag_set:
        return ("verb.masdar", None)
    if person is None:
        return None
    for tense_tags, affirmative, negative in VERB_TENSE_TAGS:
        if all(tag in tag_set for tag in tense_tags):
            return (f"{affirmative}.{person}", f"{negative}.{person}")
    return None


def load_paradigms(
    path: Path,
) -> tuple[dict[str, dict[str, set[str]]], dict[str, dict[tuple[str, str | None], set[str]]]]:
    """Read the kaikki JSONL: nouns as {stem: {label: {forms}}}, verbs as
    {stem: {(affirmative, negative): {forms}}} keyed by the imperative 2sg."""
    nouns: dict[str, dict[str, set[str]]] = {}
    verbs: dict[str, dict[tuple[str, str | None], set[str]]] = {}
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError as error:
                raise KaikkiError(f"{path}:{line_number}: invalid JSON: {error}") from error
            pos = entry.get("pos")
            if pos == "noun":
                word = entry.get("word")
                if not isinstance(word, str):
                    raise KaikkiError(f"{path}:{line_number}: noun entry without a word")
                stem, _reason = coverage.normalize_word(word)
                if stem is None:
                    continue
                cell = nouns.setdefault(stem, {})
                for form_entry in entry.get("forms") or []:
                    label = map_noun_tags(form_entry.get("tags") or [])
                    if label is None:
                        continue
                    form, _ = coverage.normalize_word(form_entry.get("form") or "")
                    if form is not None:
                        cell.setdefault(label, set()).add(form)
            elif pos == "verb":
                forms = entry.get("forms") or []
                stem = None
                for form_entry in forms:
                    if frozenset(form_entry.get("tags") or []) != IMPERATIVE_2SG_TAGS:
                        continue
                    candidate, _ = coverage.normalize_word(form_entry.get("form") or "")
                    if candidate is not None and (stem is None or len(candidate) < len(stem)):
                        stem = candidate
                if stem is None:
                    continue  # fragment entry: no imperative row, no paradigm
                cells = verbs.setdefault(stem, {})
                for form_entry in forms:
                    mapped = map_verb_tags(form_entry.get("tags") or [])
                    if mapped is None:
                        continue
                    form, _ = coverage.normalize_word(form_entry.get("form") or "")
                    if form is not None:
                        cells.setdefault(mapped, set()).add(form)
    return nouns, verbs


def attribute_verb_form(
    stem: str,
    form: str,
    affirmative: str,
    negative: str | None,
    ours: Mapping[str, set[str]],
) -> str | None:
    """Pick the generator label a kaikki verb form belongs to; None if out of scope.

    Membership in the generated sets decides first (covers suppletives like
    укмыйм); a form in neither set counts as negative when it adds the -мA/-мY
    infix right after the stem (китмәдең vs киттең), or after the stem minus
    its final vowel (suppletive clusters like укы→укмасам), else affirmative.
    """
    if affirmative == NONFINITE_SENTINEL:
        for label in NONFINITE_LABELS:
            if form in ours.get(label, ()):
                return label
        return None
    if negative is not None and form in ours.get(negative, ()):
        return negative
    if form in ours.get(affirmative, ()):
        return affirmative
    cores = [stem]
    if len(stem) > 1 and stem[-1] in gen.VOWELS:
        cores.append(stem[:-1])
    if any(form.startswith(core) and form[len(core) :].startswith("м") for core in cores):
        return negative
    return affirmative


def compare(
    nouns: dict[str, dict[str, set[str]]],
    verbs: dict[str, dict[tuple[str, str | None], set[str]]],
    exceptions: gen.Exceptions,
) -> dict[str, object]:
    """Compare generator output with kaikki paradigms; return the stats report."""
    acc = {
        "stems_compared": 0,
        "stems_compared_noun": 0,
        "stems_compared_verb": 0,
        "stems_skipped_no_harmony": 0,
        "recall_forms": 0,
        "recall_covered": 0,
        "our_forms": 0,
        "our_extra": 0,
        "out_of_scope_forms": 0,
    }
    per_label: dict[str, dict[str, int]] = {}
    missed_examples: list[str] = []
    extra_examples: list[str] = []
    missed_rows: set[tuple[str, str, str]] = set()

    def accumulate(
        stem: str,
        ours: dict[str, set[str]],
        kaikki_map: dict[str, set[str]],
        scope_labels: frozenset[str],
        noun_cells: bool,
    ) -> bool:
        """One stem's recall/extra accounting; False when no cell is shared."""
        in_scope = {
            label: forms for label, forms in kaikki_map.items() if label in scope_labels
        }
        acc["out_of_scope_forms"] += sum(len(forms) for forms in kaikki_map.values()) - sum(
            len(forms) for forms in in_scope.values()
        )
        if not in_scope:
            return False
        acc["stems_compared"] += 1
        kaikki_all = {form for forms in kaikki_map.values() for form in forms}
        for label, kaikki_forms in sorted(in_scope.items()):
            cell = per_label.setdefault(label, {"kaikki": 0, "covered": 0, "extra": 0})
            if noun_cells and label in THIRD_PERSON_CELLS:
                reference = ours.get("noun.p3", set()) | ours.get("noun.p3pl", set())
            else:
                reference = ours.get(label, set())
                if noun_cells and is_possessive_label(label):
                    # Drop plural-possessed forms (абаларым): the generator does
                    # not emit them.
                    plural_possessed = {
                        form for form in kaikki_forms if is_plural_possessed(form)
                    }
                    acc["out_of_scope_forms"] += len(plural_possessed)
                    kaikki_forms = kaikki_forms - plural_possessed
            covered = {form for form in kaikki_forms if form in reference}
            cell["kaikki"] += len(kaikki_forms)
            cell["covered"] += len(covered)
            acc["recall_forms"] += len(kaikki_forms)
            acc["recall_covered"] += len(covered)
            for form in sorted(kaikki_forms - covered):
                missed_rows.add((stem, label, form))
                if len(missed_examples) < 10_000:
                    missed_examples.append(f"{stem} {label}: kaikki={form}")
        for label, our_forms_here in sorted(ours.items()):
            if label not in in_scope:
                continue  # kaikki has no such cell; nothing to be wrong against
            extra = {form for form in our_forms_here if form not in kaikki_all}
            per_label[label]["extra"] += len(extra)
            acc["our_forms"] += len(our_forms_here)
            acc["our_extra"] += len(extra)
            for form in sorted(extra):
                if len(extra_examples) < 10_000:
                    extra_examples.append(f"{stem} {label}: ours={form}")
        return True

    for stem in sorted(nouns):
        variants = gen.harmony_variants(stem)
        if not variants:
            acc["stems_skipped_no_harmony"] += 1
            continue
        ours: dict[str, set[str]] = {}
        for harmony in variants:
            for label, form in gen.generate_noun_forms(stem, harmony, exceptions):
                ours.setdefault(label, set()).add(form)
        if accumulate(stem, ours, nouns[stem], OUR_NOUN_LABELS, noun_cells=True):
            acc["stems_compared_noun"] += 1

    for stem in sorted(verbs):
        variants = gen.harmony_variants(stem)
        if not variants:
            acc["stems_skipped_no_harmony"] += 1
            continue
        ours = {}
        for harmony in variants:
            for label, form in gen.generate_verb_forms(stem, harmony, exceptions):
                ours.setdefault(label, set()).add(form)
        kaikki_map: dict[str, set[str]] = {}
        for (affirmative, negative), forms in sorted(verbs[stem].items()):
            for form in sorted(forms):
                label = attribute_verb_form(stem, form, affirmative, negative, ours)
                if label is None:
                    acc["out_of_scope_forms"] += 1
                else:
                    kaikki_map.setdefault(label, set()).add(form)
        if accumulate(stem, ours, kaikki_map, OUR_VERB_LABELS, noun_cells=False):
            acc["stems_compared_verb"] += 1

    recall_forms = acc["recall_forms"]
    recall_covered = acc["recall_covered"]
    our_forms = acc["our_forms"]
    our_extra = acc["our_extra"]
    return {
        "stems_in_kaikki_with_forms": len(nouns) + len(verbs),
        "stems_compared": acc["stems_compared"],
        "stems_compared_noun": acc["stems_compared_noun"],
        "stems_compared_verb": acc["stems_compared_verb"],
        "stems_skipped_no_harmony": acc["stems_skipped_no_harmony"],
        "kaikki_in_scope_forms": recall_forms,
        "kaikki_covered_forms": recall_covered,
        "recall_pct": round(100.0 * recall_covered / recall_forms, 2) if recall_forms else 0.0,
        "our_in_scope_forms_on_shared_labels": our_forms,
        "our_extra_forms": our_extra,
        "extra_pct": round(100.0 * our_extra / our_forms, 2) if our_forms else 0.0,
        "kaikki_out_of_scope_forms": acc["out_of_scope_forms"],
        "per_label": per_label,
        "missed_examples": missed_examples,
        "extra_examples": extra_examples,
        "missed_rows": sorted(missed_rows),
    }


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--jsonl", type=Path, default=DEFAULT_JSONL)
    parser.add_argument(
        "--download",
        action="store_true",
        help="download the kaikki extract if the cache is missing",
    )
    parser.add_argument(
        "--limit", type=int, default=0, help="compare at most N stems per part of speech"
    )
    parser.add_argument("--show", type=int, default=15, help="examples per direction")
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="write candidate override rows (override<TAB>stem<TAB>label=form) for "
        "the missed cells to this file, for review before merging into "
        "scripts/wordform_exceptions_tat.tsv",
    )
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
        nouns, verbs = load_paradigms(path)
        if args.limit:
            nouns = dict(sorted(nouns.items())[: args.limit])
            verbs = dict(sorted(verbs.items())[: args.limit])
        exceptions = gen.load_exceptions(gen.DEFAULT_EXCEPTIONS)
        report = compare(nouns, verbs, exceptions)
    except (KaikkiError, gen.WordformError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    per_label = report.pop("per_label")
    missed = report.pop("missed_examples")
    extra = report.pop("extra_examples")
    missed_rows = report.pop("missed_rows")
    if args.out is not None:
        lines = [
            "# Candidate exception rows: kaikki.org cells the generator misses.",
            "# Review before merging into scripts/wordform_exceptions_tat.tsv:",
            "# several rows for one stem+label are alternative spellings (keep at",
            "# most one), and overriding a 3sg row re-derives the forms built on it.",
        ]
        lines.extend(
            f"override\t{stem}\t{label}={form}" for stem, label, form in missed_rows
        )
        gen.write_atomic(args.out, ("\n".join(lines) + "\n").encode("utf-8"))
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
    if args.out is not None:
        print(f"candidate override rows written to {args.out}: {len(missed_rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
