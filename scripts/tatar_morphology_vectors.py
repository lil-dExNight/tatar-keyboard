#!/usr/bin/env python3
"""Export deterministic golden vectors for the conservative Tatar acceptor."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Iterable, Sequence

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import dictionary_coverage as coverage  # noqa: E402
import stem_lexicon_pack as pack  # noqa: E402
import wordform_gen as gen  # noqa: E402

DEFAULT_STEM_LEXICON = (
    ROOT / "app/src/main/assets/dictionaries/tatar_stems_v1.tstem.zlib"
)
DEFAULT_EXCEPTIONS = gen.DEFAULT_EXCEPTIONS
DEFAULT_OUTPUT = ROOT / "app/src/test/resources/tatar_morphology_vectors_v1.tsv"

FORMAT_NAME = "tatar_morphology_vectors_v1"
SAMPLE_SEED = 0x54415441
SAMPLE_SIZE = 160
REJECTION_LIMIT = 64

AcceptedRow = tuple[str, str, str]
RejectedRow = tuple[str, str, str]


class VectorError(ValueError):
    """An input or generated vector set violates the exporter contract."""


def _sort_key(row: Sequence[str]) -> tuple[bytes, ...]:
    return tuple(field.encode("utf-8") for field in row)


def _exception_stems(exceptions: gen.Exceptions) -> set[str]:
    return (
        set(exceptions.harmonies)
        | set(exceptions.possessive_bases)
        | set(exceptions.pronouns)
        | set(exceptions.loanwords)
        | {stem for stem, _label in exceptions.overrides}
    )


def load_stem_entries(path: Path) -> tuple[pack.StemEntry, ...]:
    """Read and fully validate a shipped TATSTEM asset."""
    entries, _fields = pack.parse_raw(pack.decompress_asset(path.read_bytes()))
    return entries


def select_sample(
    entries: Sequence[pack.StemEntry], exceptions: gen.Exceptions
) -> tuple[pack.StemEntry, ...]:
    """Select a fixed-seed sample, covering every flag value and exception stem."""
    by_word = {entry.word: entry for entry in entries}
    if len(by_word) != len(entries):
        raise VectorError("stem entries are not unique")
    selected = {
        word: by_word[word]
        for word in sorted(_exception_stems(exceptions) & set(by_word))
    }
    pool = list(entries)
    random.Random(SAMPLE_SEED).shuffle(pool)
    for flags in sorted({entry.flags for entry in entries}):
        if any(entry.flags == flags for entry in selected.values()):
            continue
        match = next(
            (entry for entry in pool if entry.flags == flags), None
        )
        if match is None:
            raise VectorError(f"flag value {flags} has no sample candidate")
        selected[match.word] = match
    for entry in pool:
        if len(selected) >= SAMPLE_SIZE:
            break
        selected[entry.word] = entry
    return tuple(sorted(selected.values(), key=lambda entry: entry.word.encode("utf-8")))


def resolve_harmony(
    entry: pack.StemEntry, exceptions: gen.Exceptions
) -> str:
    """Apply forced-back, exception-table, and asset harmony in that order."""
    if entry.flags & pack.FLAG_FORCE_BACK:
        return gen.BACK
    exception = exceptions.harmonies.get(entry.word)
    if exception is not None:
        return exception
    return gen.BACK if entry.flags & pack.FLAG_BACK else gen.FRONT


def _generated_forms(
    entry: pack.StemEntry, harmony: str, exceptions: gen.Exceptions
) -> list[tuple[str, str]]:
    forms: list[tuple[str, str]] = []
    if entry.flags & pack.FLAG_NOUN_CHAIN:
        forms.extend(gen.generate_extended_noun_forms(entry.word, harmony, exceptions))
        forms.extend(gen.generate_deriv_forms(entry.word, harmony, exceptions))
        forms.extend(
            gen.generate_derived_verb_forms(
                entry.word, harmony, exceptions, noun_chain=True, verb_chain=False
            )
        )
    if entry.flags & pack.FLAG_VERB_CHAIN:
        forms.extend(gen.generate_verb_forms(entry.word, harmony, exceptions))
        forms.extend(gen.generate_nominalized_verb_forms(entry.word, harmony, exceptions))
        forms.extend(
            gen.generate_derived_verb_forms(
                entry.word, harmony, exceptions, noun_chain=False, verb_chain=True
            )
        )
    return gen.generate_assumptive_forms(forms)


def _base_forms(
    entry: pack.StemEntry, harmony: str, exceptions: gen.Exceptions
) -> list[tuple[str, str]]:
    """Forms whose complete paradigms are already practical golden vectors."""
    forms: list[tuple[str, str]] = []
    if entry.flags & pack.FLAG_NOUN_CHAIN:
        forms.extend(gen.generate_noun_forms(entry.word, harmony, exceptions))
        forms.extend(gen.generate_deriv_forms(entry.word, harmony, exceptions))
    if entry.flags & pack.FLAG_VERB_CHAIN:
        forms.extend(gen.generate_verb_forms(entry.word, harmony, exceptions))
    return forms


def _extension_family(label: str) -> str:
    if label.endswith(".assumptive"):
        return "assumptive"
    if ".loc.attr" in label:
        return "noun.loc.attr"
    if label == "noun.sim" or label.endswith(".noun.sim"):
        return "noun.sim"
    if label == "noun.term" or label.endswith(".noun.term"):
        return "noun.term"
    for prefix in (
        "verb.masdar.neg",
        "verb.masdar",
        "verb.part.gan.neg",
        "verb.part.gan",
        "verb.part.uci",
        "verb.deffut.3sg",
    ):
        if label == prefix or label.startswith(prefix + "."):
            return prefix
    if label.startswith("verb.deriv."):
        return ".".join(label.split(".")[:3])
    return "other"


def build_accepted_rows(
    sample: Iterable[pack.StemEntry], exceptions: gen.Exceptions
) -> tuple[AcceptedRow, ...]:
    rows: set[AcceptedRow] = set()
    for entry in sample:
        rows.add((entry.word, entry.word, "bare"))
        harmony = resolve_harmony(entry, exceptions)
        base = set(_base_forms(entry, harmony, exceptions))
        for label, form in base:
            rows.add((form, entry.word, label))
        extensions = sorted(set(_generated_forms(entry, harmony, exceptions)) - base)
        by_family: dict[str, list[tuple[str, str]]] = defaultdict(list)
        for label, form in extensions:
            by_family[_extension_family(label)].append((label, form))
        for family in sorted(by_family):
            for label, form in by_family[family][:2]:
                rows.add((form, entry.word, label))
    return tuple(sorted(rows, key=_sort_key))


class ReferenceAcceptor:
    """Recognize bare stems and only the chains enabled by each asset entry."""

    def __init__(
        self, entries: Sequence[pack.StemEntry], exceptions: gen.Exceptions
    ) -> None:
        self.entries = {entry.word: entry for entry in entries}
        if len(self.entries) != len(entries):
            raise VectorError("stem entries are not unique")
        self.exceptions = exceptions
        heads: dict[str, list[pack.StemEntry]] = defaultdict(list)
        for entry in entries:
            heads[entry.word[:-1]].append(entry)
        self._heads = {
            head: tuple(items) for head, items in heads.items()
        }
        self._exception_entries = tuple(
            self.entries[word]
            for word in sorted(_exception_stems(exceptions) & set(self.entries))
        )
        self._forms_cache: dict[str, frozenset[str]] = {}

    def _forms(self, entry: pack.StemEntry) -> frozenset[str]:
        cached = self._forms_cache.get(entry.word)
        if cached is not None:
            return cached
        harmony = resolve_harmony(entry, self.exceptions)
        forms = {entry.word}
        forms.update(
            form
            for _label, form in _generated_forms(
                entry, harmony, self.exceptions
            )
        )
        result = frozenset(forms)
        self._forms_cache[entry.word] = result
        return result

    def _candidates(self, word: str) -> tuple[pack.StemEntry, ...]:
        candidates: dict[str, pack.StemEntry] = {
            entry.word: entry for entry in self._exception_entries
        }
        for length in range(1, len(word) + 1):
            for entry in self._heads.get(word[:length], ()):
                if len(entry.word) <= len(word):
                    candidates[entry.word] = entry
        return tuple(candidates.values())

    def accepts(self, word: str) -> bool:
        if word in self.entries:
            return True
        if not word or any(char not in coverage.TATAR_ALPHABET for char in word):
            return False
        return any(word in self._forms(entry) for entry in self._candidates(word))


def _mutate_assimilation(stem: str, label: str, form: str) -> str | None:
    replacements = {
        "noun.dat": {"г": "к", "к": "г"},
        "noun.loc": {"д": "т", "т": "д"},
        "noun.abl": {"д": "т", "т": "д", "н": "д"},
        "deriv.das": {"д": "т", "т": "д"},
        "verb.past.3sg": {"д": "т", "т": "д"},
        "verb.ger.gac": {"г": "к", "к": "г"},
    }
    if not form.startswith(stem) or len(form) <= len(stem):
        return None
    suffix = form[len(stem) :]
    replacement = replacements.get(label, {}).get(suffix[:1])
    if replacement is None:
        return None
    return stem + replacement + suffix[1:]


def _rejection_candidates(
    sample: Sequence[pack.StemEntry],
    accepted: Sequence[AcceptedRow],
    exceptions: gen.Exceptions,
) -> list[RejectedRow]:
    by_stem_label: dict[tuple[str, str], str] = {}
    for form, stem, label in accepted:
        by_stem_label.setdefault((stem, label), form)
    candidates: list[RejectedRow] = []

    def add(form: str, stem: str, reason: str) -> None:
        candidates.append((form, stem, reason))

    for entry in sample:
        stem = entry.word
        harmony = resolve_harmony(entry, exceptions)
        wrong_harmony = gen.FRONT if harmony == gen.BACK else gen.BACK
        wrong_forms = dict(_generated_forms(entry, wrong_harmony, exceptions))
        for label in ("noun.gen", "verb.pres.1sg", "deriv.lik"):
            form = by_stem_label.get((stem, label))
            wrong = wrong_forms.get(label)
            if form is not None and wrong is not None and wrong != form:
                add(wrong, stem, "suffix_harmony")
        for label in (
            "noun.dat",
            "noun.loc",
            "noun.abl",
            "deriv.das",
            "verb.past.3sg",
            "verb.ger.gac",
        ):
            form = by_stem_label.get((stem, label))
            if form is not None:
                wrong = _mutate_assimilation(stem, label, form)
                if wrong is not None and wrong != form:
                    add(wrong, stem, "consonant_assimilation")
        for label in ("noun.dat", "noun.p1"):
            form = by_stem_label.get((stem, label))
            if form is not None:
                add(
                    gen.attach(form, "LAр"),
                    stem,
                    "illegal_continuation_order",
                )
        gerund = by_stem_label.get((stem, "verb.ger.ip"))
        if gerund is not None:
            add(
                gen.attach(gerund, "мIн"),
                stem,
                "illegal_continuation_order",
            )
        for label in ("noun.dat", "deriv.lik", "verb.ger.ip", "verb.pres.1sg.q"):
            form = by_stem_label.get((stem, label))
            if form is not None:
                add(gen.attach(form, gen.QUESTION_PATTERN), stem, "extra_clitic")

    add("лар", "-", "missing_or_unknown_stem")
    add("үҗәү", "үҗәү", "missing_or_unknown_stem")
    add("үҗәүләр", "үҗәү", "missing_or_unknown_stem")
    add("", "-", "empty_input")
    add("tatar", "-", "outside_alphabet")
    add("татар!", "-", "outside_alphabet")
    return candidates


def build_rejected_rows(
    sample: Sequence[pack.StemEntry],
    accepted: Sequence[AcceptedRow],
    entries: Sequence[pack.StemEntry],
    exceptions: gen.Exceptions,
) -> tuple[RejectedRow, ...]:
    """Keep only candidates rejected over the complete asset inventory."""
    acceptor = ReferenceAcceptor(entries, exceptions)
    rows: set[RejectedRow] = set()
    counts: Counter[str] = Counter()
    for form, stem, reason in _rejection_candidates(sample, accepted, exceptions):
        if counts[reason] >= REJECTION_LIMIT:
            continue
        row = (form, stem, reason)
        if row in rows:
            continue
        if not acceptor.accepts(form):
            rows.add(row)
            counts[reason] += 1
    required = {
        "suffix_harmony",
        "consonant_assimilation",
        "illegal_continuation_order",
        "extra_clitic",
        "missing_or_unknown_stem",
        "empty_input",
        "outside_alphabet",
    }
    missing = sorted(required - set(counts))
    if missing:
        raise VectorError(f"no rejection vectors survived for: {', '.join(missing)}")
    return tuple(sorted(rows, key=_sort_key))


def render_vectors(
    accepted: Sequence[AcceptedRow],
    rejected: Sequence[RejectedRow],
    sample_size: int,
    stem_sha256: str,
    exceptions_sha256: str,
) -> bytes:
    lines = [
        f"#format\t{FORMAT_NAME}",
        f"#sample_seed\t0x{SAMPLE_SEED:08x}",
        f"#sample_stems\t{sample_size}",
        f"#stem_lexicon_sha256\t{stem_sha256}",
        f"#exceptions_sha256\t{exceptions_sha256}",
        f"#accepted_rows\t{len(accepted)}",
        f"#rejected_rows\t{len(rejected)}",
        "#columns\tdecision\tform\tstem\tlabel_or_reason",
    ]
    lines.extend(f"A\t{form}\t{stem}\t{label}" for form, stem, label in accepted)
    lines.extend(f"R\t{form}\t{stem}\t{reason}" for form, stem, reason in rejected)
    return ("\n".join(lines) + "\n").encode("utf-8")


def generate(
    stem_path: Path = DEFAULT_STEM_LEXICON,
    exceptions_path: Path = DEFAULT_EXCEPTIONS,
) -> tuple[bytes, dict[str, object]]:
    stem_bytes = stem_path.read_bytes()
    entries, _fields = pack.parse_raw(pack.decompress_asset(stem_bytes))
    exceptions = gen.load_exceptions(exceptions_path)
    sample = select_sample(entries, exceptions)
    accepted = build_accepted_rows(sample, exceptions)
    rejected = build_rejected_rows(sample, accepted, entries, exceptions)
    data = render_vectors(
        accepted,
        rejected,
        len(sample),
        hashlib.sha256(stem_bytes).hexdigest(),
        hashlib.sha256(exceptions_path.read_bytes()).hexdigest(),
    )
    stats: dict[str, object] = {
        "accepted_rows": len(accepted),
        "rejected_rows": len(rejected),
        "rejected_by_reason": dict(sorted(Counter(row[2] for row in rejected).items())),
        "sample_stems": len(sample),
        "bytes": len(data),
    }
    return data, stats


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stem-lexicon", type=Path, default=DEFAULT_STEM_LEXICON)
    parser.add_argument("--exceptions", type=Path, default=DEFAULT_EXCEPTIONS)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--check",
        action="store_true",
        help="compare the output with fresh bytes without writing",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        data, stats = generate(args.stem_lexicon, args.exceptions)
        if args.check:
            try:
                current = args.output.read_bytes()
            except OSError as error:
                print(f"error: cannot read {args.output}: {error}", file=sys.stderr)
                return 1
            if current != data:
                print(f"error: stale vector resource: {args.output}", file=sys.stderr)
                return 1
        else:
            gen.write_atomic(args.output, data)
        stats["check"] = args.check
        print(json.dumps(stats, ensure_ascii=False, sort_keys=True))
        return 0
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
