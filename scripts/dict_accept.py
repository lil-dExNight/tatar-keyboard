"""Machine acceptance of new words from the queues `data/dictionary/*-conv-review.tsv`.

`select` labels each queue word with the original rule: accepted if a second independent source
confirms it (two-corpora: both OpenSubtitles and Tatoeba; shipped-word; shipped-paradigm, Russian
only: the stem has other forms in the shipped dictionary) and cap_ratio is below MAX_CAP_RATIO.
Words that fail it are accepted too, except formal fragments and EXCLUDED_WORDS; rejected words
stay in rejected-*.tsv with the reason. `pack` builds the dictionary assets from the SHA-256
checked 1.8.4 baseline plus accepted words, with written plus conversational frequencies plus
the per-word bonus of `bonus-freq-*.tsv` when that file exists.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "research/corpus"))

QUEUE = {
    "rus": ROOT / "data/dictionary/ru-conv-review.tsv",
    "tat": ROOT / "data/dictionary/tt-conv-review.tsv",
}
OUT_DIR = ROOT / "data/dictionary/dict-accept"
SUFFIX = {"rus": "ru", "tat": "tt"}

# Share of capitalized occurrences outside sentence start at which a word no longer counts as
# ordinary. The corpus filter (`research/corpus/filters.py`) cuts at 0.80 before the queue;
# acceptance cuts at 0.50 after it, because a wrongly accepted word is worse than a wrongly
# rejected one.
MAX_CAP_RATIO = 0.50

# Minimum stem length for the shipped-paradigm branch. Without it `ме` would split into
# `м` + `е`, and a one-letter stem always has forms in the dictionary.
MIN_STEM = 4
# How many OTHER forms of the same stem must be in the shipped dictionary.
MIN_PARADIGM_SIBLINGS = 3

# ── fragments and exclusions ───────────────────────────────────────────────────────────────
# Words rejected at any frequency, listed by hand; the rule never adds to this list. `можна`
# looks like a typo next to `можно` at the prefix `можн`.
EXCLUDED_WORDS = frozenset({"можна"})

# Minimum word length below which a word is a formal fragment. Tatar has no length threshold:
# Tatar words are shorter, and a length cut would drop real words (`ох`, `фу`, `оһ`).
MIN_WORD_LEN = {"rus": 4, "tat": 1}

# Vowels. A word without a single vowel is a recognition fragment in both languages.
VOWELS = {
    "rus": frozenset("аеёиоуыэюя"),
    "tat": frozenset("аәеёиоөуүыэюя"),
}


def fragment_reason(word: str, tag: str) -> str:
    """Why the word is a formal fragment; an empty string if it is not."""
    if len(word) < MIN_WORD_LEN[tag]:
        return f"shorter than {MIN_WORD_LEN[tag]} letters ({len(word)})"
    if not (set(word) & VOWELS[tag]):
        return "no vowel"
    return ""


def fragment_rule_text(tag: str) -> str:
    """One-line description of the fragment rule for this language, for file headers."""
    vowels = "".join(sorted(VOWELS[tag]))
    if MIN_WORD_LEN[tag] > 1:
        return (f"shorter than {MIN_WORD_LEN[tag]} letters or without a vowel "
                f"({vowels})")
    return (f"no vowel ({vowels}); Tatar has no length threshold, because a length cut "
            "removes real words")

# Russian inflectional endings. The list is broad: it only has to find a stem. Precision comes
# from requiring several attested forms, which an invented stem lacks.
RUSSIAN_ENDINGS = frozenset({
    "",
    # noun and adjective inflection
    "а", "е", "и", "о", "у", "ы", "й", "ь", "я", "ю", "ё", "э",
    "ам", "ами", "ах", "ев", "ей", "ем", "ов", "ом", "ой", "ою", "ую",
    "ая", "ое", "ые", "ый", "ым", "ых", "ыми", "его", "его", "ему", "ого", "ому",
    "ий", "ия", "ии", "ию", "ием", "иях", "иям", "иями", "ими", "их",
    "ок", "ка", "ко", "ки", "ек", "ец", "ца", "цу", "цы", "цев",
    # verb inflection
    "ть", "ти", "л", "ла", "ло", "ли", "в", "вши",
    "ешь", "ет", "ете", "ут", "ют", "ит", "им", "ите", "ат", "ят", "ишь", "йте",
    "ся", "сь", "ась", "ись", "лся", "лась", "лось", "лись", "ться", "тся",
    "ешься", "ется", "емся", "етесь", "утся", "ются", "ится", "имся", "итесь",
    "атся", "ятся",
    # participles and short forms
    "ущий", "ющий", "ащий", "ящий", "вший", "нный", "тый", "мый",
    "ен", "на", "но", "ны", "ена", "ено", "ены",
})

HEADER = ["word", "heldout_hits", "train_freq", "train_freq_clean", "sources",
          "license_status", "cap_ratio", "enters_top100k", "approved", "reviewer",
          "review_date", "note"]


class Row:
    __slots__ = ("word", "heldout", "freq", "freq_clean", "sources", "license",
                 "cap_ratio", "enters_top100k")

    def __init__(self, fields):
        self.word = fields[0]
        self.heldout = int(fields[1])
        self.freq = int(fields[2])
        self.freq_clean = int(fields[3])
        self.sources = fields[4]
        self.license = fields[5]
        self.cap_ratio = float(fields[6])
        self.enters_top100k = fields[7] == "yes"

    def source_set(self):
        return set(self.sources.split("+"))


def read_queue(tag: str) -> list[Row]:
    rows = []
    with QUEUE[tag].open(encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if fields[0] == "word":
                if fields[: len(HEADER)] != HEADER:
                    raise SystemExit(f"{QUEUE[tag]}: unexpected columns {fields}")
                continue
            if not fields[0]:
                continue
            rows.append(Row(fields))
    return rows


def paradigm_siblings(word: str, shipped: frozenset[str]) -> tuple[int, str, str]:
    """Count OTHER forms of the word's stem in the shipped dictionary, using the best split.

    Returns (count, stem, ending). Every split `word = stem + ending` with a listed ending and a
    stem of at least MIN_STEM is tried; the split with most forms wins. `word` itself never counts.
    """
    best, best_stem, best_ending = 0, "", ""
    for ending in RUSSIAN_ENDINGS:
        if ending and not word.endswith(ending):
            continue
        stem = word[: len(word) - len(ending)] if ending else word
        if len(stem) < MIN_STEM:
            continue
        count = 0
        for other in RUSSIAN_ENDINGS:
            if other == ending:
                continue
            form = stem + other
            if form != word and form in shipped:
                count += 1
        if count > best:
            best, best_stem, best_ending = count, stem, ending
    return best, best_stem, best_ending


def prior_verdict(row: Row, tag: str, shipped: frozenset[str]) -> tuple[bool, str, str]:
    """Verdict of the original rule: a second source and no proper-noun evidence.

    It no longer decides acceptance but is still recorded in the `rule` column (or the detail of
    a widened word), so the origin of every dictionary row is visible in the file.
    """
    if row.cap_ratio >= MAX_CAP_RATIO:
        return False, "proper-noun-evidence", f"cap_ratio={row.cap_ratio:.2f}>={MAX_CAP_RATIO:.2f}"
    sources = row.source_set()
    if "OpenSubtitles" in sources and "Tatoeba" in sources:
        return True, "two-corpora", row.sources
    if row.word in shipped:
        return True, "shipped-word", "already in the shipped dictionary"
    if tag == "rus":
        count, stem, ending = paradigm_siblings(row.word, shipped)
        if count >= MIN_PARADIGM_SIBLINGS:
            return True, "shipped-paradigm", f"{stem}|{ending} +{count} forms in the dictionary"
    return False, "single-source", row.sources


def decide(rows: list[Row], tag: str, shipped: frozenset[str]):
    """Apply the rule; return (accepted, rejected), each a list of (Row, rule, detail).

    Everything is accepted except formal fragments and EXCLUDED_WORDS. A word that passes the
    original rule keeps its label; the others get the widened label and the original reason.
    """
    accepted, rejected = [], []
    for row in rows:
        passed, rule, detail = prior_verdict(row, tag, shipped)
        if row.word in EXCLUDED_WORDS:
            rejected.append((row, "operator-excluded",
                             f"excluded by name (previous rule: {rule})"))
            continue
        if passed:
            accepted.append((row, rule, detail))
            continue
        reason = fragment_reason(row.word, tag)
        if reason:
            rejected.append((row, "fragment", f"{reason}; previous rule: {rule}"))
            continue
        accepted.append((row, "operator-widened", f"previous rule: {rule} ({detail})"))
    return accepted, rejected


PREAMBLE = """\
# Words {kind} by the machine acceptance rule.
#
# The rule accepts every queue word except formal fragments and words excluded by hand:
#   fragment          - a formal fragment: {frag}
#   operator-excluded - named by hand: {excl}
# The older second-source bar no longer decides a word but is still computed and recorded in
# the rule column, so every row keeps its origin:
#   two-corpora       - seen in both OpenSubtitles and Tatoeba
#   shipped-word      - already in the shipped dictionary (the queue holds none by construction)
#   shipped-paradigm  - Russian: the stem has {sib} or more forms in the shipped dictionary
#   operator-widened  - the older bar refused it (single source, or cap_ratio >= {cap}) and the
#                       word was accepted by hand; the older reason is in rule_detail
#
# The columns are the queue columns plus rule and rule_detail. Rejected rows stay in
# rejected-*.tsv: change the rule and rerun `python3 scripts/dict_accept.py select`.
"""


def write_rows(path: Path, kind: str, decided, frag: str):
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(PREAMBLE.format(kind=kind, cap=f"{MAX_CAP_RATIO:.2f}",
                                     sib=MIN_PARADIGM_SIBLINGS, frag=frag,
                                     excl=", ".join(sorted(EXCLUDED_WORDS))))
        handle.write("\t".join(["word", "heldout_hits", "train_freq", "train_freq_clean",
                                "sources", "license_status", "cap_ratio", "enters_top100k",
                                "rule", "rule_detail"]) + "\n")
        for row, rule, detail in decided:
            handle.write("\t".join([
                row.word, str(row.heldout), str(row.freq), str(row.freq_clean),
                row.sources, row.license, f"{row.cap_ratio:.2f}",
                "yes" if row.enters_top100k else "no", rule, detail]) + "\n")


SAMPLE_HEAD = """\
# {n} random {kind} words ({lang}) as a spot sample.
# The draw is reproducible: random.Random({seed}).sample over the whole list, in draw order
# rather than frequency order, because the question is about the whole set.
# Format: word <TAB> occurrences in the training part of the conversational corpora <TAB> rule.
# Full list: data/dictionary/dict-accept/{file}
"""

SAMPLE_SEED = 20260824
SAMPLE_SIZE = 100


def write_sample(path: Path, kind: str, lang: str, decided, source_file: str):
    picked = random.Random(SAMPLE_SEED).sample(decided, min(SAMPLE_SIZE, len(decided)))
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(SAMPLE_HEAD.format(n=len(picked), kind=kind, lang=lang,
                                        seed=SAMPLE_SEED, file=source_file))
        for row, rule, _detail in picked:
            handle.write(f"{row.word}\t{row.freq}\t{rule}\n")


def load_shipped(tag: str):
    import corpuslib as CL
    freqs, boundary = CL.load_shipped(tag)
    return freqs, boundary


# `select` requires --baseline instead of reading the asset from the tree. The shipped-word and
# shipped-paradigm branches depend on the shipped dictionary, but the tree holds the rebuilt
# dictionary, which already contains the accepted words: reading it would mislabel rows and make
# each rerun give a different answer. The baseline is named explicitly and checked by SHA-256
# with the same function as in `pack`.
def shipped_for_rule(tag: str, baseline: Path | None) -> frozenset[str]:
    if baseline is None:
        freqs, _boundary = load_shipped(tag)
        return frozenset(freqs)
    freqs, _asset = load_baseline(tag, baseline)
    return frozenset(freqs)


def select(args) -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    report = {"rule": {"max_cap_ratio": MAX_CAP_RATIO, "min_stem": MIN_STEM,
                       "min_paradigm_siblings": MIN_PARADIGM_SIBLINGS,
                       "sample_seed": SAMPLE_SEED, "sample_size": SAMPLE_SIZE,
                       "excluded_words": sorted(EXCLUDED_WORDS),
                       "min_word_len": MIN_WORD_LEN,
                       "vowels": {t: "".join(sorted(v)) for t, v in VOWELS.items()}},
              "languages": {}}
    baseline = Path(args.baseline) if args.baseline else None
    report["rule"]["baseline_dir"] = str(baseline) if baseline else "tree (NOT PINNED)"
    for tag in ("rus", "tat"):
        shipped = shipped_for_rule(tag, baseline)
        rows = read_queue(tag)
        accepted, rejected = decide(rows, tag, shipped)
        suffix = SUFFIX[tag]
        frag = fragment_rule_text(tag)
        write_rows(OUT_DIR / f"accepted-{suffix}.tsv", "ACCEPTED", accepted, frag)
        write_rows(OUT_DIR / f"rejected-{suffix}.tsv", "REJECTED", rejected, frag)
        lang_name = "Russian" if tag == "rus" else "Tatar"
        write_sample(OUT_DIR / f"sample-accepted-{suffix}.txt", "ACCEPTED", lang_name,
                     accepted, f"accepted-{suffix}.tsv")
        write_sample(OUT_DIR / f"sample-rejected-{suffix}.txt", "REJECTED", lang_name,
                     rejected, f"rejected-{suffix}.tsv")

        def tally(decided):
            out = {}
            for _row, rule, _detail in decided:
                out[rule] = out.get(rule, 0) + 1
            return out

        report["languages"][tag] = {
            "queue_rows": len(rows),
            "accepted": len(accepted),
            "rejected": len(rejected),
            "accepted_by_rule": tally(accepted),
            "rejected_by_rule": tally(rejected),
            "accepted_entering_top100k": sum(1 for r, _, _ in accepted if r.enters_top100k),
            "rejected_entering_top100k": sum(1 for r, _, _ in rejected if r.enters_top100k),
            "accepted_tokens": sum(r.freq for r, _, _ in accepted),
            "rejected_tokens": sum(r.freq for r, _, _ in rejected),
            "accepted_heldout_hits": sum(r.heldout for r, _, _ in accepted),
            "rejected_heldout_hits": sum(r.heldout for r, _, _ in rejected),
            # Words accepted beyond the original rule, and what they add.
            "widened": sum(1 for _r, rule, _d in accepted if rule == "operator-widened"),
            "widened_heldout_hits": sum(r.heldout for r, rule, _d in accepted
                                        if rule == "operator-widened"),
            "widened_entering_top100k": sum(1 for r, rule, _d in accepted
                                            if rule == "operator-widened" and r.enters_top100k),
            "fragments": sum(1 for _r, rule, _d in rejected if rule == "fragment"),
            "fragment_heldout_hits": sum(r.heldout for r, rule, _d in rejected
                                         if rule == "fragment"),
            "fragment_top": [
                [r.word, r.freq, d] for r, rule, d in
                sorted((x for x in rejected if x[1] == "fragment"),
                       key=lambda x: -x[0].freq)[:20]
            ],
            "excluded": [[r.word, r.freq] for r, rule, _d in rejected
                         if rule == "operator-excluded"],
        }
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
    print()
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                       encoding="utf-8")
    return 0


def read_accepted(tag: str) -> dict[str, int]:
    """{word: train_freq} from data/dictionary/dict-accept/accepted-*.tsv."""
    path = OUT_DIR / f"accepted-{SUFFIX[tag]}.tsv"
    out = {}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if fields[0] == "word" or not fields[0]:
                continue
            out[fields[0]] = int(fields[2])
    return out


CONV_FREQ_HEAD = """\
# Conversational frequency of the words the dictionary may carry.
# One row is a word and its count in the training part of the conversational corpora after
# filtering (`research/corpus/filters.py`). This is the value in the queue's train_freq column;
# the file also covers words outside the queue, namely the ones already in the shipped
# dictionary.
#
# Why the file is committed: composition and frequency answer different questions. Composition
# is cut by the second independent source, while frequencies come from the whole corpus,
# OpenSubtitles included. Shipped words therefore need the conversational count as well, or the
# two scales diverge between a new word and an old one. Without this file a rebuild would need
# the corpora on disk.
#
# Rows cover only words that can enter the asset: the shipped dictionary plus the queue. Words
# with a zero conversational count are left out.
# Source: {sources}
# Baseline asset: {baseline_sha}
# Regenerate: python3 research/corpus/measure_accept.py <tag> out/shipped-1.8.4 <corpora>
"""


def conv_freq_path(tag: str) -> Path:
    return OUT_DIR / f"conv-freq-{SUFFIX[tag]}.tsv"


def write_conv_freq(tag: str, conv: dict[str, int], sources, baseline_sha: str) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    path = conv_freq_path(tag)
    names = " ".join(Path(p).name for p in sources)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(CONV_FREQ_HEAD.format(sources=names, baseline_sha=baseline_sha))
        handle.write("word\tconv_freq\n")
        for word in sorted(conv):
            handle.write(f"{word}\t{conv[word]}\n")


def read_conv_freq(tag: str) -> dict[str, int]:
    path = conv_freq_path(tag)
    if not path.is_file():
        raise SystemExit(
            f"{path} does not exist. Build it in one pass over the corpora:\n"
            f"  cd research/corpus && python3 measure_accept.py {tag} out/shipped-1.8.4 <corpora>"
        )
    out = {}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if fields[0] == "word" or not fields[0]:
                continue
            out[fields[0]] = int(fields[1])
    return out


def bonus_freq_path(tag: str) -> Path:
    return OUT_DIR / f"bonus-freq-{SUFFIX[tag]}.tsv"


def read_bonus_freq(tag: str) -> dict[str, int]:
    """Per-word integer bonus frequencies from the extra corpora; {} when no file exists.

    A missing file means "no bonuses", not an error: a rebuild without it is caught by the
    contract pins, which the bonus changes. Bonus words outside the composition are ignored
    (the file covers the pool of one build; the composition is recomputed every rebuild).
    """
    path = bonus_freq_path(tag)
    if not path.is_file():
        return {}
    out = {}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if fields[0] == "word" or not fields[0]:
                continue
            out[fields[0]] = int(fields[1])
    return out


# The 1.8.4 assets are the only valid rebuild base. The pin keeps `pack` from running on an
# already rebuilt file, which would add the conversational frequencies a second time. The match
# must be exact, and a mismatch stops the run. Extract the base with
# git show <1.8.4 commit>:app/src/main/assets/dictionaries/<file>
BASELINE_SHA256 = {
    "rus": "f4b91cef2a4e10c096997f358811b71cdb17d0a10097b03ab3b9de9324c2c48f",
    "tat": "2d98ed359aa11261a5042a13c5ca9459c6e365c6ab4bf0563d0e3604a7485cae",
}


def load_baseline(tag: str, directory: Path):
    """The 1.8.4 shipped dictionary from an explicit directory, checked by SHA-256."""
    import hashlib
    import corpuslib as CL
    import dictionary_coverage as cov
    import dictionary_pack as dp
    language = cov.language_for(tag)
    path = directory / CL.SHIPPED[tag].name
    asset = path.read_bytes()
    digest = hashlib.sha256(asset).hexdigest()
    if digest != BASELINE_SHA256[tag]:
        raise SystemExit(
            f"{path}: SHA-256 {digest} is not the 1.8.4 asset ({BASELINE_SHA256[tag]}). "
            "A rebuild on top of a rebuilt asset would add the conversational frequency twice.")
    parsed = dp.validate_raw(dp.decompress_asset(asset, language), language=language)
    return dict(zip(parsed.words, parsed.frequencies)), asset


def read_extra_entries(path: Path, tag: str) -> dict[str, int]:
    """Extra word<TAB>frequency entries merged into the composition, such as the admitted Tatar
    word forms from rebuild_assets.py. Each word must pass `normalize_word` unchanged and each
    frequency must be a positive u32; a duplicate or malformed row stops the build.
    """
    import dictionary_coverage as cov
    language = cov.language_for(tag)
    out: dict[str, int] = {}
    with path.open(encoding="utf-8", newline="") as handle:
        for line_number, line in enumerate(handle, start=1):
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if len(fields) != 2 or not fields[0] or not fields[1]:
                raise SystemExit(
                    f"{path}:{line_number}: expected a word<TAB>frequency row, "
                    f"got {len(fields)} fields")
            word, reason = cov.normalize_word(fields[0], language.alphabet)
            if reason is not None or word != fields[0]:
                raise SystemExit(
                    f"{path}:{line_number}: word {fields[0]!r} is not canonical "
                    f"({reason or 'not NFC/lowercase'})")
            try:
                frequency = int(fields[1])
            except ValueError:
                raise SystemExit(
                    f"{path}:{line_number}: frequency {fields[1]!r} is not a number")
            if not 0 < frequency <= 0xFFFF_FFFF:
                raise SystemExit(
                    f"{path}:{line_number}: frequency {frequency} is not a positive u32")
            if word in out:
                raise SystemExit(f"{path}:{line_number}: duplicate word {word!r}")
            out[word] = frequency
    return out


TATAR_CONV_SELECTION_WEIGHT = 9


def merged_entries(tag: str, baseline: Path, top: int = 100_000,
                   extra: dict[str, int] | None = None):
    """Composition and frequencies of the new asset.

    Composition: shipped plus accepted words plus `extra`, nothing else. Frequency: written plus
    conversational for every word, shipped ones included; only the composition is filtered. An
    `extra` entry for an existing word keeps the existing frequency. The bonus file, when it
    exists, adds its per-word integers on top without changing the composition. The Tatar cutoff
    adds nine times the conversational frequency to the selection score only; stored frequencies
    remain unchanged. Other languages use the stored frequency as their selection score.
    """
    shipped, _asset = load_baseline(tag, baseline)
    accepted = read_accepted(tag)
    conv = read_conv_freq(tag)
    bonus = read_bonus_freq(tag)
    composition = set(shipped) | set(accepted)
    merged = {word: shipped.get(word, 0) + conv.get(word, 0) for word in composition}
    for word, frequency in (extra or {}).items():
        if word not in merged:
            merged[word] = frequency
    for word, extra_frequency in bonus.items():
        if word in merged:
            merged[word] += extra_frequency
    selection_weight = TATAR_CONV_SELECTION_WEIGHT if tag == "tat" else 0
    top_entries = sorted(
        merged.items(),
        key=lambda kv: (-(kv[1] + selection_weight * conv.get(kv[0], 0)), kv[0]),
    )[:top]
    return shipped, accepted, sorted(top_entries, key=lambda kv: kv[0])


def pack(args) -> int:
    import hashlib
    import dictionary_pack as dp
    import dictionary_coverage as cov
    import corpuslib as CL
    baseline = Path(args.baseline)
    tags = (args.only,) if args.only else ("rus", "tat")
    result = {}
    for tag in tags:
        language = cov.language_for(tag)
        extra = None
        if args.extra_entries is not None:
            # --extra-entries/--top require --only: they are per language, and without --only
            # both languages would get the same extra entries.
            extra = read_extra_entries(Path(args.extra_entries), tag)
        shipped, before = load_baseline(tag, baseline)
        _shipped, accepted, entries = merged_entries(
            tag, baseline, top=args.top, extra=extra)
        raw = dp.serialize_entries(entries, schema=dp.SCHEMA_ID_V2)
        asset = dp.compress_raw(raw)
        target = CL.SHIPPED[tag]
        before_raw = dp.decompress_asset(before, language)
        words = {w for w, _ in entries}
        result[tag] = {
            "asset": str(target.relative_to(ROOT)),
            "entries": len(entries),
            "accepted_offered": len(accepted),
            "accepted_that_entered": len((words & set(accepted)) - set(shipped)),
            "shipped_words_displaced": len(set(shipped) - words),
            "asset_bytes_before": len(before),
            "asset_bytes_after": len(asset),
            "asset_bytes_delta": len(asset) - len(before),
            "raw_bytes_before": len(before_raw),
            "raw_bytes_after": len(raw),
            "raw_bytes_delta": len(raw) - len(before_raw),
            "fits_raw": len(raw) <= dp.MAX_UNCOMPRESSED_BYTES,
            "sha256_before": hashlib.sha256(before).hexdigest(),
            "sha256_after": hashlib.sha256(asset).hexdigest(),
            "raw_sha256_after": hashlib.sha256(raw).hexdigest(),
        }
        if extra is not None:
            result[tag]["extra_entries_offered"] = len(extra)
            result[tag]["extra_entries_that_entered"] = len(words & set(extra))
        if args.write:
            dp.validate_asset(asset, language=language)
            _atomic_write(target, asset)
            result[tag]["written"] = True
        else:
            result[tag]["written"] = False
    json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
    print()
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                                       encoding="utf-8")
    return 0


def _atomic_write(path: Path, data: bytes) -> None:
    """Write the asset like `dictionary_pack._write_outputs`: a temp file in the same directory,
    fsync, then atomic os.replace, so a crash never leaves a broken asset in the tree."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", dir=path.parent, prefix=f".{path.name}.", delete=False
        ) as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
            temporary = Path(stream.name)
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--json-out", default=None, help="write the same numbers to this file")
    sub = parser.add_subparsers(dest="command", required=True)
    sel = sub.add_parser("select", help="run the rule and write the accepted/rejected tables")
    sel.add_argument("--baseline", required=True,
                     help="directory with the 1.8.4 assets, the base of the shipped-* branches; "
                          "the SHA-256 is checked exactly. Reading the asset from the tree is "
                          "refused: since 1.9.0 it holds a rebuilt dictionary, and the origin "
                          "labels would drift")
    sel.set_defaults(func=select)
    pk = sub.add_parser("pack", help="build the assets from the accepted tables")
    pk.add_argument("--baseline", required=True,
                    help="directory with the 1.8.4 assets; the SHA-256 is checked exactly")
    pk.add_argument("--write", action="store_true",
                    help="write the assets to app/src/main/assets (without the flag, only measures)")
    pk.add_argument("--only", choices=("rus", "tat"), default=None,
                    help="build one language only; the other asset is neither read nor written")
    pk.add_argument("--extra-entries", default=None, metavar="TSV",
                    help="extra word<TAB>frequency entries merged into the composition "
                         "(the admitted Tatar word forms); requires --only, because the "
                         "entries are per language")
    pk.add_argument("--top", type=int, default=100_000,
                    help="composition cutoff size (default %(default)s); requires --only "
                         "when it differs from the default")
    pk.set_defaults(func=pack)
    args = parser.parse_args(argv)
    if args.command == "pack":
        if args.extra_entries is not None and args.only is None:
            parser.error("--extra-entries requires --only (the entries are per language)")
        if args.top != 100_000 and args.only is None:
            parser.error("--top other than the default requires --only")
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
