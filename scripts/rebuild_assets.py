#!/usr/bin/env python3
"""Rebuild the bundled assets in one step: dictionaries, bigram tables, pins, check.

Inputs not in the repo: --baseline DIR (the two 1.8.4 dictionary assets, extracted from git)
and --corpus-dir DIR (the corpus files named in BIGRAMS and WORDFORM_FREQUENCY_SOURCES, whose
size and SHA-256 are pinned in data/corpus-manifest.json and verified before any step). Steps:
Tatar word forms, dict_accept pack, bigram_asset_pack pack, pin rewrite in the Kotlin storage
contracts (between the `// <generated pins>` markers), regeneration of the Tatar calibration
sets (typo recovery, glide) with a pin rewrite in their python and JVM tests, then --check.
--only tatar|russian rebuilds one side; the other must stay identical.
--check compares assets with pins, bigram heads with dictionaries, and the corpus manifest with
the registry (and with the files, if --corpus-dir is given); head drift passes only with
--allow-known-drift and an exact match in known_asset_drift.json. Exit: 0 ok, 1 mismatch,
2 missing input, failed step or unparsable contract or manifest.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence, TextIO

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import bigram_asset_pack  # noqa: E402
import dict_accept  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack  # noqa: E402
import wordform_gen  # noqa: E402
from bigram_pack import select_heads  # noqa: E402
import companion_list_pack  # noqa: E402

STORAGE_DIR = Path("app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage")
DICT_CONTRACT = STORAGE_DIR / "DictionaryStorageContracts.kt"
BIGRAM_CONTRACT = STORAGE_DIR / "BigramStorageContracts.kt"
COMPANION_CONTRACT = STORAGE_DIR / "CompanionListStorageContracts.kt"

DEFAULT_KNOWN_DRIFT = Path("scripts/known_asset_drift.json")
DEFAULT_CORPUS_DIR = Path.home() / "corpora-leipzig"
DEFAULT_WORK_DIR = Path("build/rebuild_assets")

@dataclass(frozen=True)
class DictionaryAsset:
    tag: str  # dictionary_coverage tag: "tat" / "rus"
    spec: str  # constant name in DictionaryStorageContracts.kt
    asset: str  # path relative to the repository root
    top: int = 100_000  # composition cutoff used by the rebuild


@dataclass(frozen=True)
class BigramAsset:
    tag: str
    spec: str  # constant name in BigramStorageContracts.kt
    asset: str
    dictionary: str  # tag of the dictionary the table ships with
    heads: int
    successes_per_head: int
    extra_heads: str | None  # path relative to the root, if any
    train: tuple[str, ...]  # *-sentences.txt names in the corpus directory
    recover_punctuation: bool = False


@dataclass(frozen=True)
class CompanionListAsset:
    tag: str
    spec: str  # constant name in CompanionListStorageContracts.kt
    asset: str
    dictionary: str  # tag of the dictionary the list derives from
    top: int  # words kept, by stored frequency


# Packing parameters of the shipped lists (words kept per language). Changing a
# parameter changes the asset, so change it only together with a rebuild. The
# lists derive from the committed dictionaries alone: no corpus, no baseline.
COMPANIONS = (
    CompanionListAsset(
        tag="tat",
        spec="TATAR_COMPANION_V1",
        asset="app/src/main/assets/dictionaries/tatar_companion_v1.tcomp.zlib",
        dictionary="tat",
        top=20_000,
    ),
    CompanionListAsset(
        tag="rus",
        spec="RUSSIAN_COMPANION_V1",
        asset="app/src/main/assets/dictionaries/russian_companion_v1.tcomp.zlib",
        dictionary="rus",
        top=20_000,
    ),
)

# Tatar dictionary cutoff; at 100 000 no admitted word form makes the cut. The release APK size
# limit is the only size limit, so run release_check.sh after changing it.
TATAR_DICTIONARY_TOP = 110_000

DICTIONARIES = (
    DictionaryAsset(
        tag="tat",
        spec="TATAR_TOP100K_V1",
        asset="app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
        top=TATAR_DICTIONARY_TOP,
    ),
    DictionaryAsset(
        tag="rus",
        spec="RUSSIAN_TOP100K_V1",
        asset="app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
    ),
)

# Packing parameters of the shipped tables (H heads, K successors per head, extra heads,
# training corpora). Changing any of them changes the table, so change them only together
# with a rebuild.
BIGRAMS = (
    BigramAsset(
        tag="tat",
        spec="TATAR_BIGRAMS_V1",
        asset="app/src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib",
        dictionary="tat",
        heads=10_132,
        # Up to eight successors per head. The strip shows three; the rest feed the
        # context track of the prefix engine (successor ids boost matching completions).
        successes_per_head=8,
        # Heads below the H cutoff, added by the rule described in the file header.
        extra_heads="scripts/bigram_extra_heads_tat.txt",
        # Two Leipzig corpora plus the conversational input: deduplicated Tatoeba +
        # OpenSubtitles tt lines with id % 10 != 1 (the rest is left out of bigram training).
        # The conversational file is listed ten times — a weight, not an error:
        # repetition scales its pair counts, lifting its share of the training token mass
        # from a few percent to about a tenth. The file is built with
        # research/corpus/make_conv_train.py and an id filter and placed in --corpus-dir by
        # hand; without it the rebuild stops before any step runs.
        train=(
            "tat_mixed_2015_1M-sentences.txt",
            "tat_web_2018_1M-sentences.txt",
            *(["tt_conv_train90-sentences.txt"] * 10),
        ),
        # Edge punctuation retains its sentence boundaries but no longer discards whole words.
        recover_punctuation=True,
    ),
    BigramAsset(
        tag="rus",
        spec="RUSSIAN_BIGRAMS_V1",
        asset="app/src/main/assets/bigrams/russian_bigrams_v1.tatbigr.zlib",
        dictionary="rus",
        heads=10_000,
        # Up to eight successors per head, as in the Tatar table: the strip shows
        # three; the rest feed the context track of the prefix engine.
        successes_per_head=8,
        extra_heads=None,
        # Five Leipzig corpora plus the conversational input: deduplicated Tatoeba +
        # OpenSubtitles ru, thinned to 1/60 (lines with id % 60 == 0). Built with
        # research/corpus/make_conv_train.py and an id filter and placed in --corpus-dir by
        # hand; without it a full rebuild stops before any step runs. rus_news_2023_1M
        # and rus_news_2024_1M were appended after the 2019/2022 news files and the 2021
        # wikipedia file: additive refresh, the dictionary composition stays on the
        # 1.8.4 baseline.
        train=(
            "rus_news_2022_1M-sentences.txt",
            "rus_news_2019_1M-sentences.txt",
            "rus_wikipedia_2021_1M-sentences.txt",
            "rus_news_2023_1M-sentences.txt",
            "rus_news_2024_1M-sentences.txt",
            "rus_conv_thinned60-sentences.txt",
        ),
        # Edge punctuation retains its sentence boundaries but no longer discards whole
        # words; the five-file measurement found the recovered in-vocabulary pairs at
        # about a third of the canonical total, far above the enable threshold.
        recover_punctuation=True,
    ),
)


# --- word-form stage --------------------------------------------------------------------------
#
# The Tatar dictionary includes generated word forms (scripts/wordform_gen.py) that are attested
# in the pipeline frequency sources: the Leipzig *-words.txt of the two corpora that train the
# Tatar bigram table, plus the committed conv-freq-tt.tsv. tat_news_2015_1M is not read: its word
# frequencies are already in the baseline dictionary, and research/corpus/tt_merge_sim.py uses its
# sentences as a development set. The held-out Tatar set is
# app/src/test/resources/tt_eval_sentences.txt.
WORDFORM_EXCEPTIONS = Path("scripts/wordform_exceptions_tat.tsv")
WORDFORM_FREQUENCY_SOURCES = (
    "tat_mixed_2015_1M-words.txt",
    "tat_web_2018_1M-words.txt",
)


# --- corpus manifest --------------------------------------------------------------------------
#
# The corpora are not committed, so their bytes are pinned in a committed manifest: one entry per
# corpus file the rebuild reads, keyed by file name, with "size", "sha256" and a free-text
# "source". The entry set must equal corpus_names(); a rebuild verifies the files of the selected
# languages before any step runs.
CORPUS_MANIFEST = Path("data/corpus-manifest.json")


class CorpusManifestError(ValueError):
    """The corpus manifest is missing, malformed, or does not match the corpus registry."""


def corpus_names(tags: frozenset[str] = frozenset({"tat", "rus"})) -> list[str]:
    """Corpus file names the rebuild reads for the languages in `tags`, sorted."""
    names = {name for bigram in BIGRAMS if bigram.tag in tags for name in bigram.train}
    if "tat" in tags:
        names.update(WORDFORM_FREQUENCY_SOURCES)
    return sorted(names)


def load_corpus_manifest(path: Path) -> dict[str, tuple[int, str]]:
    """Read the manifest as {name: (size, sha256)}; raise CorpusManifestError if it is
    unreadable, malformed, or its entry set differs from corpus_names()."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise CorpusManifestError(f"{path}: unreadable: {error}") from error
    if not isinstance(data, dict):
        raise CorpusManifestError(f"{path}: expected a top-level JSON object")
    manifest: dict[str, tuple[int, str]] = {}
    for name, entry in data.items():
        if not (
            isinstance(entry, dict)
            and isinstance(entry.get("size"), int)
            and not isinstance(entry.get("size"), bool)
            and entry["size"] >= 0
            and isinstance(entry.get("sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", entry["sha256"])
            and isinstance(entry.get("source"), str)
        ):
            raise CorpusManifestError(
                f"{path}: entry {name!r} must have size (an int >= 0), "
                "sha256 (64 hex) and source"
            )
        manifest[name] = (entry["size"], entry["sha256"])
    expected = set(corpus_names())
    absent = sorted(expected - set(manifest))
    stale = sorted(set(manifest) - expected)
    if absent or stale:
        raise CorpusManifestError(
            f"{path}: entries do not match the rebuild corpora; "
            f"missing: {absent or '-'}, unexpected: {stale or '-'}"
        )
    return manifest


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_corpus(
    corpus_dir: Path, manifest: dict[str, tuple[int, str]], names: Sequence[str]
) -> dict[str, str]:
    """Verdict per corpus file: "ok", "missing", or a size or SHA-256 mismatch description.

    The size is compared first, so a truncated file is reported without hashing it.
    """
    verdicts: dict[str, str] = {}
    for name in names:
        path = corpus_dir / name
        size, sha256 = manifest[name]
        if not path.is_file():
            verdicts[name] = "missing"
            continue
        actual_size = path.stat().st_size
        if actual_size != size:
            verdicts[name] = f"size: manifest {size}, file {actual_size}"
            continue
        actual_sha256 = _file_sha256(path)
        verdicts[name] = (
            "ok" if actual_sha256 == sha256
            else f"sha256: manifest {sha256}, file {actual_sha256}"
        )
    return verdicts


def build_admitted_wordforms(
    root: Path, baseline: Path, corpus_dir: Path, work_dir: Path
) -> Path:
    """Write the admitted Tatar word forms (word<TAB>freq TSV) to work_dir; return its path.

    Stems are the composition before the cutoff (baseline plus accepted words). A candidate not
    already in the composition is admitted if its total count in the sources is above zero, and
    that count is its frequency. Raises WordformError / MalformedRowError on bad input.
    """
    language = coverage.language_for("tat")
    shipped, _asset = dict_accept.load_baseline("tat", baseline)
    accepted = dict_accept.read_accepted("tat")
    stems = sorted(set(shipped) | set(accepted))

    frequencies: dict[str, int] = dictionary_pack.CheckedFrequencyCounter()
    for name in WORDFORM_FREQUENCY_SOURCES:
        path = corpus_dir / name
        with path.open("r", encoding="utf-8-sig", newline="") as stream:
            coverage.read_source(
                stream, str(path), frequencies,
                skip_malformed=False, alphabet=language.alphabet,
            )
    conversational = dict_accept.read_conv_freq("tat")
    exceptions = wordform_gen.load_exceptions(root / WORDFORM_EXCEPTIONS)

    composition = set(stems)
    admitted: dict[str, int] = {}
    stats: dict[str, object] = {
        "stems": 0,
        "stems_skipped_no_harmony": 0,
        "dual_harmony_stems": 0,
        # Per-row counters: a form generated by two stems or two labels counts on each row.
        "generated_rows": 0,
        "rows_already_in_composition": 0,
        "rows_unattested": 0,
    }
    for stem in stems:
        variants = wordform_gen.harmony_variants(stem)
        if not variants:
            stats["stems_skipped_no_harmony"] += 1
            continue
        stats["stems"] += 1
        if len(variants) > 1:
            stats["dual_harmony_stems"] += 1
        for _label, form in wordform_gen.generate_all(stem, exceptions):
            if form == stem:
                continue  # the bare stem (verb.imp.2sg) is already in the composition
            stats["generated_rows"] += 1
            if form in composition:
                stats["rows_already_in_composition"] += 1
                continue
            count = frequencies.get(form, 0) + conversational.get(form, 0)
            if count == 0:
                stats["rows_unattested"] += 1
                continue
            admitted[form] = count
    stats["admitted_forms"] = len(admitted)

    data = "".join(f"{form}\t{admitted[form]}\n" for form in sorted(admitted)).encode("utf-8")
    out = work_dir / "wordforms-admitted-tt.tsv"
    wordform_gen.write_atomic(out, data)
    report = {
        **stats,
        "frequency_sources": list(WORDFORM_FREQUENCY_SOURCES)
        + ["data/dictionary/dict-accept/conv-freq-tt.tsv"],
        "output": str(out),
        "output_bytes": len(data),
        "output_sha256": hashlib.sha256(data).hexdigest(),
    }
    wordform_gen.write_atomic(
        work_dir / "wordforms-admitted-tt.json",
        (json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8"),
    )
    print(
        f"wordform stage: {stats['stems']} stems, admitted "
        f"{stats['admitted_forms']} of {stats['generated_rows']} generated rows",
        file=sys.stderr,
    )
    return out


# --- pins: reading and writing the Kotlin contracts -------------------------------------------


@dataclass(frozen=True)
class Pins:
    """The values a contract pins for an asset. Dictionary pins also carry the precomputed
    next-word fallback; schema 3 bigram pins carry the linked dictionary's raw SHA-256."""

    compressed_size: int
    compressed_sha256: str
    raw_size: int
    raw_sha256: str
    count: int
    dictionary_raw_sha256: str = ""
    fallback_words: tuple[str, ...] = ()


class ContractError(ValueError):
    """The Kotlin contract file does not have the expected structure."""


# The pinned spec blocks in the contracts sit between these marker lines; the rewrite touches
# only the pin literals inside a pair, so reformatting outside or inside the markers (indents,
# line breaks, trailing commas) is harmless. A missing or duplicated marker fails closed.
PINS_MARKER_OPEN = "// <generated pins>"
PINS_MARKER_CLOSE = "// </generated pins>"


def _pin_regions(text: str) -> list[tuple[int, int]]:
    """[start, end) spans between the marker lines. Markers must pair up without nesting."""
    regions: list[tuple[int, int]] = []
    open_end: int | None = None
    offset = 0
    for line in text.splitlines(keepends=True):
        stripped = line.strip()
        if stripped == PINS_MARKER_OPEN:
            if open_end is not None:
                raise ContractError("nested pin marker")
            open_end = offset + len(line)
        elif stripped == PINS_MARKER_CLOSE:
            if open_end is None:
                raise ContractError("closing pin marker without an opening one")
            regions.append((open_end, offset))
            open_end = None
        offset += len(line)
    if open_end is not None:
        raise ContractError("opening pin marker without a closing one")
    return regions


def _spec_block(text: str, spec: str, kind: str) -> tuple[int, int]:
    """Return [start, end) of the marked region holding the `val <spec> = <kind>(...)` block.

    Fails closed: the val must appear exactly once and sit inside a marker pair, and every
    marker pair must wrap exactly one block.
    """
    targets = list(re.finditer(rf"val {spec} = {kind}\(", text))
    if len(targets) != 1:
        raise ContractError(f"{spec}: expected exactly one {kind} block, found {len(targets)}")
    regions = _pin_regions(text)
    enclosing = [region for region in regions if region[0] <= targets[0].start() < region[1]]
    if len(enclosing) != 1:
        raise ContractError(f"{spec}: the {kind} block is not between pin markers")
    for start, end in regions:
        blocks = re.findall(r"val \w+ = \w+\(", text[start:end])
        if len(blocks) != 1:
            raise ContractError(
                f"expected exactly one block between pin markers, found {len(blocks)}"
            )
    return enclosing[0]


def _read_number(block: str, field: str) -> int:
    matches = re.findall(rf"\b{field}\s*=\s*([\d_]+)", block)
    if len(matches) != 1:
        raise ContractError(f"field {field}: expected exactly one match, found {len(matches)}")
    return int(matches[0].replace("_", ""))


def _read_sha(block: str, field: str) -> str:
    matches = re.findall(rf'\b{field}\s*=\s*"([0-9a-f]{{64}})"', block)
    if len(matches) != 1:
        raise ContractError(f"field {field}: expected exactly one match, found {len(matches)}")
    return matches[0]


def _read_words(block: str, field: str) -> tuple[str, ...]:
    matches = re.findall(rf"\b{field}\s*=\s*listOf\((.*?)\)", block, re.DOTALL)
    if len(matches) != 1:
        raise ContractError(f"field {field}: expected exactly one list, found {len(matches)}")
    literals = re.findall(r'"(?:\\.|[^"\\])*"', matches[0])
    try:
        return tuple(json.loads(literal) for literal in literals)
    except json.JSONDecodeError as error:
        raise ContractError(f"field {field}: invalid string literal") from error


def read_pins(
    contract_path: Path, spec: str, kind: str, count_field: str, linked: bool = False
) -> Pins:
    text = contract_path.read_text(encoding="utf-8")
    start, end = _spec_block(text, spec, kind)
    block = text[start:end]
    return Pins(
        compressed_size=_read_number(block, "expectedCompressedSize"),
        compressed_sha256=_read_sha(block, "expectedCompressedSha256"),
        raw_size=_read_number(block, "expectedRawSize"),
        raw_sha256=_read_sha(block, "expectedRawSha256"),
        count=_read_number(block, count_field),
        dictionary_raw_sha256=(
            _read_sha(block, "expectedDictionaryRawSha256") if linked else ""
        ),
        fallback_words=(
            _read_words(block, "fallbackWords")
            if kind == "DictionaryArtifactSpec" else ()
        ),
    )


def _replace_number(block: str, field: str, value: int) -> str:
    # Only the literal is rewritten; whitespace and line breaks around it are kept. The Kotlin
    # underscore grouping of the new value follows Python's f-string style (1_311_438).
    block, count = re.subn(
        rf"(\b{field}\s*=\s*)[\d_]+", rf"\g<1>{value:_}", block
    )
    if count != 1:
        raise ContractError(f"field {field} was not replaced in the spec block")
    return block


def _replace_sha(block: str, field: str, value: str) -> str:
    # Only the literal is rewritten; whatever whitespace or line break stands between `=` and
    # the hash string is kept.
    block, count = re.subn(
        rf'(\b{field}\s*=\s*)"[0-9a-f]{{64}}"', rf'\g<1>"{value}"', block
    )
    if count != 1:
        raise ContractError(f"field {field} was not replaced in the spec block")
    return block


def _replace_words(block: str, field: str, words: tuple[str, ...]) -> str:
    literals = ", ".join(json.dumps(word, ensure_ascii=False) for word in words)
    block, count = re.subn(
        rf"(\b{field}\s*=\s*)listOf\(.*?\)",
        lambda match: f"{match.group(1)}listOf({literals})",
        block,
        flags=re.DOTALL,
    )
    if count != 1:
        raise ContractError(f"field {field} was not replaced in the spec block")
    return block


def _atomic_write_text(path: Path, text: str) -> None:
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(text, encoding="utf-8", newline="\n")
    os.replace(temp, path)


def write_pins(
    contract_path: Path,
    updates: dict[str, Pins],
    kind: str,
    count_field: str,
) -> None:
    """Rewrite the pins of the `updates` blocks in one atomic write.

    The file is read back afterwards, and every block must return exactly the written values.
    """
    text = contract_path.read_text(encoding="utf-8")
    for spec, pins in updates.items():
        start, end = _spec_block(text, spec, kind)
        block = text[start:end]
        block = _replace_number(block, "expectedCompressedSize", pins.compressed_size)
        block = _replace_sha(block, "expectedCompressedSha256", pins.compressed_sha256)
        block = _replace_number(block, "expectedRawSize", pins.raw_size)
        block = _replace_sha(block, "expectedRawSha256", pins.raw_sha256)
        if pins.dictionary_raw_sha256:
            block = _replace_sha(
                block, "expectedDictionaryRawSha256", pins.dictionary_raw_sha256
            )
        block = _replace_number(block, count_field, pins.count)
        if kind == "DictionaryArtifactSpec":
            block = _replace_words(block, "fallbackWords", pins.fallback_words)
        text = text[:start] + block + text[end:]
    _atomic_write_text(contract_path, text)
    for spec, pins in updates.items():
        if read_pins(contract_path, spec, kind, count_field, linked=bool(pins.dictionary_raw_sha256)) != pins:
            raise ContractError(f"{spec}: pins read back different after writing")


# --- asset measurement ------------------------------------------------------------------------


def measure_dictionary(asset_path: Path, tag: str) -> Pins:
    language = coverage.language_for(tag)
    asset = asset_path.read_bytes()
    parsed = dictionary_pack.validate_asset(asset, language=language)
    raw = parsed.raw
    return Pins(
        compressed_size=len(asset),
        compressed_sha256=hashlib.sha256(asset).hexdigest(),
        raw_size=len(raw),
        raw_sha256=hashlib.sha256(raw).hexdigest(),
        count=parsed.entry_count,
        fallback_words=tuple(
            word for word, _ in sorted(
                zip(parsed.words, parsed.frequencies, strict=True),
                key=lambda entry: (-entry[1], entry[0]),
            )[:8]
        ),
    )


def measure_bigram(asset_path: Path, dictionary_path: Path, tag: str) -> Pins:
    """Pins of a bigram table. Schema 3 is validated together with its dictionary: heads and
    successors are indices into it, and the pins include its raw SHA-256 (the link stored in
    the table header). Schema 2 is still read for older assets."""
    language = coverage.language_for(tag)
    asset = asset_path.read_bytes()
    raw = bigram_asset_pack.decompress(asset)
    schema_id = bigram_asset_pack.HEADER.unpack_from(raw)[1]
    if schema_id == bigram_asset_pack.SCHEMA_ID:
        parsed = bigram_asset_pack.validate_raw(raw)
        head_count = len(parsed.head_words)
        dictionary_raw_sha256 = ""
    elif schema_id == bigram_asset_pack.SCHEMA_ID_V3:
        parsed_dictionary = dictionary_pack.validate_asset(
            dictionary_path.read_bytes(), language=language
        )
        dictionary_raw_sha256 = hashlib.sha256(parsed_dictionary.raw).hexdigest()
        parsed = bigram_asset_pack.validate_raw_v3(
            raw, parsed_dictionary.words, bytes.fromhex(dictionary_raw_sha256)
        )
        head_count = len(parsed.head_words)
    else:
        raise ContractError(f"unknown bigram table schema id: {schema_id}")
    return Pins(
        compressed_size=len(asset),
        compressed_sha256=hashlib.sha256(asset).hexdigest(),
        raw_size=len(raw),
        raw_sha256=hashlib.sha256(raw).hexdigest(),
        count=head_count,
        dictionary_raw_sha256=dictionary_raw_sha256,
    )


def measure_companion(asset_path: Path) -> Pins:
    """Pins of a companion list: the compressed/raw sizes and SHA-256 values plus
    the word count. The raw file is fully parsed back, so a truncated or corrupt
    asset is a check verdict, not a crash."""
    asset = asset_path.read_bytes()
    raw = companion_list_pack.decompress_asset(asset)
    words, _fields = companion_list_pack.parse_raw(raw)
    return Pins(
        compressed_size=len(asset),
        compressed_sha256=hashlib.sha256(asset).hexdigest(),
        raw_size=len(raw),
        raw_sha256=hashlib.sha256(raw).hexdigest(),
        count=len(words),
    )


# --- consistency check ------------------------------------------------------------------------

@dataclass(frozen=True)
class Drift:
    """Head drift between a bigram table and its dictionary, counted in both directions.

    missing: words of the current top H (plus extra heads) absent from the table. Heads with no
    pair in training are dropped by the packer, and --check cannot tell them apart without the
    corpus, so nonzero counts are accepted only through known_asset_drift.json.
    unexpected: table heads outside the current top H and the extra heads.
    outside: heads not in the dictionary at all. This is corruption, never allowed.
    """

    missing: int
    unexpected: int
    outside: int
    missing_examples: tuple[str, ...]
    unexpected_examples: tuple[str, ...]
    outside_examples: tuple[str, ...]


def bigram_drift(
    table_path: Path,
    dictionary_path: Path,
    language: coverage.Language,
    heads: int,
    extra_heads: Sequence[str],
) -> Drift:
    vocabulary, frequencies = bigram_asset_pack.read_shipped_vocabulary(
        dictionary_path, language
    )
    raw = bigram_asset_pack.decompress(table_path.read_bytes())
    schema_id = bigram_asset_pack.HEADER.unpack_from(raw)[1]
    if schema_id == bigram_asset_pack.SCHEMA_ID_V3:
        ordered_words = dictionary_pack.validate_asset(
            dictionary_path.read_bytes(), language=language
        ).words
        parsed = bigram_asset_pack.validate_raw_v3(raw, ordered_words)
    else:
        parsed = bigram_asset_pack.validate_raw(raw)
    actual = set(parsed.head_words)
    expected = set(select_heads(frequencies, heads)) | set(extra_heads)
    missing = sorted(expected - actual)
    unexpected = sorted(actual - expected)
    outside = sorted(actual - vocabulary)
    return Drift(
        missing=len(missing),
        unexpected=len(unexpected),
        outside=len(outside),
        missing_examples=tuple(missing[:10]),
        unexpected_examples=tuple(unexpected[:10]),
        outside_examples=tuple(outside[:10]),
    )


def _pin_problems(measured: Pins, pinned: Pins) -> list[str]:
    problems = []
    fields = ["compressed_size", "compressed_sha256", "raw_size", "raw_sha256", "count"]
    # The schema 3 dictionary link is compared if either side carries it.
    if measured.dictionary_raw_sha256 or pinned.dictionary_raw_sha256:
        fields.append("dictionary_raw_sha256")
    if measured.fallback_words or pinned.fallback_words:
        fields.append("fallback_words")
    for field in fields:
        actual, expected = getattr(measured, field), getattr(pinned, field)
        if actual != expected:
            problems.append(f"{field}: contract {expected}, asset {actual}")
    return problems


def load_known_drift(path: Path) -> dict[str, dict[str, object]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise SystemExit(f"{path}: expected a top-level JSON object")
    for key, entry in data.items():
        if not isinstance(entry, dict) or not {
            "missing_top_heads",
            "unexpected_heads",
            "reason",
        } <= set(entry):
            raise SystemExit(
                f"{path}: entry {key!r} must have missing_top_heads, "
                "unexpected_heads and reason"
            )
    return data


def run_check(
    root: Path,
    known_drift_path: Path | None,
    stream: TextIO = sys.stdout,
    corpus_dir: Path | None = None,
) -> int:
    """Check assets against pins and dictionaries, and the corpus manifest. Writes nothing.

    The manifest itself is always checked; the corpus files only when corpus_dir is given.
    Returns 1 on a mismatch and 2 on missing inputs or an unparsable contract or manifest.
    """
    dict_contract = root / DICT_CONTRACT
    bigram_contract = root / BIGRAM_CONTRACT
    companion_contract = root / COMPANION_CONTRACT
    for path in (dict_contract, bigram_contract, companion_contract):
        if not path.is_file():
            print(f"error: no contract file {path}", file=sys.stderr)
            return 2
    try:
        corpus_manifest = load_corpus_manifest(root / CORPUS_MANIFEST)
    except CorpusManifestError as error:
        print(f"error: corpus manifest: {error}", file=sys.stderr)
        return 2

    known: dict[str, dict[str, object]] = {}
    if known_drift_path is not None:
        if not known_drift_path.is_file():
            print(f"error: no known-drift file {known_drift_path}", file=sys.stderr)
            return 2
        known = load_known_drift(known_drift_path)

    failed = False
    report: dict[str, object] = {"dictionaries": {}, "bigrams": {}, "companions": {}}
    corpus_entry: dict[str, object] = {"manifest": str(CORPUS_MANIFEST)}

    for dictionary in DICTIONARIES:
        asset_path = root / dictionary.asset
        entry: dict[str, object] = {"asset": dictionary.asset, "spec": dictionary.spec}
        if not asset_path.is_file():
            entry["verdict"] = "missing"
            failed = True
        else:
            try:
                pinned = read_pins(
                    dict_contract, dictionary.spec, "DictionaryArtifactSpec", "expectedEntryCount"
                )
            except ContractError as error:
                print(f"error: unparsable contract: {error}", file=sys.stderr)
                return 2
            try:
                problems = _pin_problems(
                    measure_dictionary(asset_path, dictionary.tag), pinned
                )
            except Exception as error:  # a broken asset is a check verdict, not a crash
                problems = [f"asset unreadable: {error}"]
            entry["verdict"] = "mismatch" if problems else "ok"
            entry["problems"] = problems
            failed = failed or bool(problems)
        report["dictionaries"][dictionary.tag] = entry  # type: ignore[index]

    used_drift_keys: set[str] = set()
    for bigram in BIGRAMS:
        asset_path = root / bigram.asset
        asset_key = str(Path(bigram.asset).relative_to("app/src/main/assets"))
        entry: dict[str, object] = {"asset": bigram.asset, "spec": bigram.spec}
        dictionary_path = root / next(
            d.asset for d in DICTIONARIES if d.tag == bigram.dictionary
        )
        known_entry = known.get(asset_key)
        if known_entry is not None:
            used_drift_keys.add(asset_key)
        if not asset_path.is_file() or not dictionary_path.is_file():
            entry["verdict"] = "missing"
            failed = True
        else:
            try:
                pinned = read_pins(
                    bigram_contract, bigram.spec, "BigramArtifactSpec", "expectedHeadCount",
                    linked=True,
                )
            except ContractError as error:
                print(f"error: unparsable contract: {error}", file=sys.stderr)
                return 2
            try:
                problems = _pin_problems(
                    measure_bigram(asset_path, dictionary_path, bigram.dictionary), pinned
                )
                extra: list[str] = []
                if bigram.extra_heads is not None:
                    extra = bigram_asset_pack.read_extra_heads(
                        root / bigram.extra_heads,
                        bigram_asset_pack.read_shipped_vocabulary(
                            dictionary_path, coverage.language_for(bigram.dictionary)
                        )[0],
                    )
                drift = bigram_drift(
                    asset_path,
                    dictionary_path,
                    coverage.language_for(bigram.dictionary),
                    bigram.heads,
                    extra,
                )
            except Exception as error:  # a broken asset is a check verdict, not a crash
                entry["verdict"] = "mismatch"
                entry["problems"] = [f"asset unreadable: {error}"]
                failed = True
                report["bigrams"][bigram.tag] = entry  # type: ignore[index]
                continue
            entry["drift"] = {
                "missing_top_heads": drift.missing,
                "unexpected_heads": drift.unexpected,
                "heads_outside_dictionary": drift.outside,
                "missing_examples": list(drift.missing_examples),
                "unexpected_examples": list(drift.unexpected_examples),
            }
            if drift.outside:
                problems.append(
                    f"{drift.outside} heads outside the dictionary: "
                    + ", ".join(drift.outside_examples)
                )
            entry["problems"] = problems
            failed = failed or bool(problems)

            if drift.missing == 0 and drift.unexpected == 0:
                if known_entry is not None:
                    entry["verdict"] = "stale-known-drift"
                    entry["problems"] = problems + [
                        "the drift is gone; the known_asset_drift.json "
                        "entry is stale, remove it"
                    ]
                    failed = True
                else:
                    entry["verdict"] = "ok" if not problems else "mismatch"
            elif known_entry is not None and known_entry[
                "missing_top_heads"
            ] == drift.missing and known_entry["unexpected_heads"] == drift.unexpected:
                entry["verdict"] = "known-drift"
                entry["known_drift_reason"] = known_entry["reason"]
            else:
                entry["verdict"] = "drift"
                hint = (
                    "does not match known_asset_drift.json"
                    if known_entry is not None
                    else "no entry in known_asset_drift.json"
                    if known_drift_path is not None
                    else "rerun with --allow-known-drift if the drift is known"
                )
                entry["problems"] = problems + [
                    f"heads diverged from the dictionary: missing {drift.missing}, "
                    f"unexpected {drift.unexpected} ({hint})"
                ]
                failed = True
        report["bigrams"][bigram.tag] = entry  # type: ignore[index]

    for key in sorted(set(known) - used_drift_keys):
        # An entry for an asset no longer in the registry is stale too.
        print(f"error: {known_drift_path}: entry {key!r} matches no asset",
              file=sys.stderr)
        failed = True

    for companion in COMPANIONS:
        asset_path = root / companion.asset
        entry: dict[str, object] = {"asset": companion.asset, "spec": companion.spec}
        dictionary_path = root / next(
            d.asset for d in DICTIONARIES if d.tag == companion.dictionary
        )
        if not asset_path.is_file() or not dictionary_path.is_file():
            entry["verdict"] = "missing"
            failed = True
        else:
            try:
                pinned = read_pins(
                    companion_contract, companion.spec, "CompanionListArtifactSpec",
                    "expectedWordCount",
                )
            except ContractError as error:
                print(f"error: unparsable contract: {error}", file=sys.stderr)
                return 2
            try:
                problems = _pin_problems(
                    measure_companion(asset_path), pinned
                )
                # The list must still equal the top words of its dictionary: a rebuilt
                # dictionary without a re-packed list is a divergence the check reports.
                expected = companion_list_pack.select_words(
                    dictionary_pack.validate_asset(
                        dictionary_path.read_bytes(),
                        language=coverage.language_for(companion.tag),
                    ),
                    companion.top,
                )
                actual, _fields = companion_list_pack.parse_raw(
                    companion_list_pack.decompress_asset(asset_path.read_bytes())
                )
                # The body stores words in byte order, the selection is ranked; the word
                # sets must be exactly equal.
                if sorted(actual) != sorted(expected):
                    problems = problems + [
                        "the words are not the current top "
                        f"{companion.top} of the dictionary"
                    ]
            except Exception as error:  # a broken asset is a check verdict, not a crash
                problems = [f"asset unreadable: {error}"]
            entry["verdict"] = "mismatch" if problems else "ok"
            entry["problems"] = problems
            failed = failed or bool(problems)
        report["companions"][companion.tag] = entry  # type: ignore[index]

    if corpus_dir is None:
        corpus_entry["verdict"] = "not-checked"
    else:
        files = verify_corpus(corpus_dir, corpus_manifest, corpus_names())
        corpus_entry["files"] = files
        corpus_entry["corpus_dir"] = str(corpus_dir)
        corpus_entry["verdict"] = (
            "ok" if all(v == "ok" for v in files.values())
            else "missing" if all(v in ("ok", "missing") for v in files.values())
            else "mismatch"
        )
        failed = failed or corpus_entry["verdict"] != "ok"
    report["corpus"] = corpus_entry

    report["ok"] = not failed
    report["known_drift_file"] = str(known_drift_path) if known_drift_path else None
    json.dump(report, stream, ensure_ascii=False, indent=2, sort_keys=True)
    stream.write("\n")

    for section in ("dictionaries", "bigrams", "companions"):
        for tag, entry in sorted(report[section].items()):  # type: ignore[union-attr]
            line = f"{section}/{tag}: {entry['verdict']}"
            if entry["verdict"] == "known-drift":
                drift = entry["drift"]
                line += (f" (missing {drift['missing_top_heads']}, "
                         f"unexpected {drift['unexpected_heads']})")
            print(line, file=sys.stderr)
    if corpus_entry["verdict"] == "not-checked":
        print("corpus: manifest is consistent, files not checked (no --corpus-dir)",
              file=sys.stderr)
    else:
        print(f"corpus: {corpus_entry['verdict']}", file=sys.stderr)
        for name, verdict in sorted(corpus_entry["files"].items()):  # type: ignore[union-attr]
            if verdict != "ok":
                print(f"  {name}: {verdict}", file=sys.stderr)
    return 1 if failed else 0


# --- calibration set pins ---------------------------------------------------------------------

# The typo-recovery and glide calibration sets are deterministic functions of the Tatar
# dictionary, the layout and the seeds, so a Tatar dictionary rebuild changes their identities.
# The identities are pinned in tests/typo_pack/, tests/glide_pack/ and the JVM calibration
# tests; the rebuild regenerates the sets and rewrites the pins, leaving no hand step.

LAYOUT_DIR = Path("app/src/main/res/xml")
GLIDE_EVAL_SENTENCES = Path("app/src/test/resources/tt_eval_sentences.txt")

# The (edit class, prefix window) sets the python tests and the JVM calibration tests pin.
CALIBRATION_TYPO_SETS = ((1, 3), (2, 3), (3, 3), (4, 3), (1, 5), (2, 5), (4, 5))

# Calibration test files the rebuild rewrites, relative to the repository root.
TYPO_PACK_TEST = Path("tests/typo_pack/test_typo_pack.py")
GLIDE_PACK_TEST = Path("tests/glide_pack/test_glide_pack.py")
JVM_TESTS_DIR = Path("app/src/test/java/rkr/simplekeyboard/inputmethod/latin")
LONG_PRESS_CALIBRATION_TEST = (
    JVM_TESTS_DIR / "dictionary/engine/LongPressRecoveryCalibrationTest.kt")
FULL_SUBSTITUTION_CALIBRATION_TEST = (
    JVM_TESTS_DIR / "dictionary/engine/TtFullSubstitutionCalibrationTest.kt")
GLIDE_CALIBRATION_TEST = JVM_TESTS_DIR / "glide/GlideRecoveryCalibrationTest.kt"
GLIDE_GOLDEN_TEST = JVM_TESTS_DIR / "golden/GlideGoldenExportTest.kt"


@dataclass(frozen=True)
class CalibrationPins:
    """Identities of the deterministic calibration sets over the Tatar dictionary.

    typo maps (edit class, prefix window) to (row count, set SHA-256). variant_p95 belongs to
    the class-1, 3-code-point set. The glide triple is the selected word count, the set byte
    count and its SHA-256.
    """

    typo: dict[tuple[int, int], tuple[int, str]]
    variant_p95: int
    glide_words: int
    glide_bytes: int
    glide_sha256: str


def measure_calibration_pins(root: Path, dictionary: Pins) -> CalibrationPins:
    """Regenerate the Tatar calibration sets over the fresh dictionary and return their pins.

    The dictionary pins come from the caller (the just-measured asset), not from the
    generators' defaults, so the check works on any --root.
    """
    # Imported lazily: the generators read the contract of their own checkout at import time,
    # which is pointless on a fake --root, and --check never needs them.
    import glide_pack
    import typo_pack

    asset = root / next(d.asset for d in DICTIONARIES if d.tag == "tat")
    layout = root / LAYOUT_DIR
    expected = {
        "expected_asset_sha256": dictionary.compressed_sha256,
        "expected_raw_sha256": dictionary.raw_sha256,
        "expected_entry_count": dictionary.count,
    }
    typo: dict[tuple[int, int], tuple[int, str]] = {}
    variant_p95 = 0
    for edit_class, window in CALIBRATION_TYPO_SETS:
        typo_set, _ = typo_pack.generate(
            asset, layout, prefix_code_points=window, edit_class=edit_class, **expected
        )
        typo[(edit_class, window)] = (typo_set.size, typo_set.sha256)
        if (edit_class, window) == (1, 3):
            variant_p95 = typo_set.variant_p95
    words = typo_pack.read_dictionary_words(asset, **expected)
    rects = glide_pack.read_glide_geometry(layout)
    aliases = glide_pack.read_layout_aliases(layout, rects)
    letters = frozenset(glide_pack.letters_by_code_point(rects, aliases))
    eval_sentences = root / GLIDE_EVAL_SENTENCES
    selected = glide_pack.select_words(words, eval_sentences, letters)
    context_pairs = glide_pack.select_context_pairs(words, eval_sentences, letters)
    _rendered, data = glide_pack.generate_set(
        selected, rects, aliases=aliases, context_pairs=context_pairs
    )
    return CalibrationPins(
        typo=typo,
        variant_p95=variant_p95,
        glide_words=len(selected),
        glide_bytes=len(data),
        glide_sha256=hashlib.sha256(data).hexdigest(),
    )


def _reformat_like(old: str, value: int) -> str:
    """The integer in the style of the literal it replaces: underscores iff it had them."""
    return f"{value:_}" if "_" in old else str(value)


def _function_region(text: str, name: str) -> tuple[int, int]:
    """[start, end) of the `    def <name>(` method through the line before the next def/class."""
    matches = list(re.finditer(rf"\n    def {name}\(", text))
    if len(matches) != 1:
        raise ContractError(f"{name}: expected exactly one method, found {len(matches)}")
    start = matches[0].start()
    following = re.search(r"\n    def |\nclass ", text[start + 1:])
    end = start + 1 + following.start() if following is not None else len(text)
    return start, end


def _rewrite_pin_literals(
    path: Path, text: str, region: str | None, literals: Sequence[tuple[str, object]]
) -> str:
    """`text` with group 1 of every pattern replaced; each pattern must match exactly once
    (inside the named python method when `region` is given). Pure: writes nothing."""
    start, end = _function_region(text, region) if region is not None else (0, len(text))
    body = text[start:end]
    for pattern, value in literals:
        matches = list(re.finditer(pattern, body))
        if len(matches) != 1:
            raise ContractError(
                f"{path}: calibration template {pattern!r}: {len(matches)} matches"
            )
        old = matches[0].group(1)
        new = _reformat_like(old, value) if isinstance(value, int) else value
        if old != new:
            body = body[: matches[0].start(1)] + new + body[matches[0].end(1):]
    return text[:start] + body + text[end:]


def _calibration_pin_sites(
    pins: CalibrationPins,
) -> list[tuple[Path, str | None, list[tuple[str, object]]]]:
    """Every pinned literal of the calibration sets: (file, scoping method, patterns)."""
    size = {key: pins.typo[key][0] for key in pins.typo}
    sha = {key: pins.typo[key][1] for key in pins.typo}

    def typo_literals(one: str, two: str, key1: tuple[int, int], key2: tuple[int, int]):
        return [
            (rf"{one}\.size, (\d+)", size[key1]),
            (rf'{one}\.sha256,\s*"([0-9a-f]{{64}})"', sha[key1]),
            (rf"{two}\.size, (\d+)", size[key2]),
            (rf'{two}\.sha256,\s*"([0-9a-f]{{64}})"', sha[key2]),
        ]

    return [
        (
            TYPO_PACK_TEST,
            "test_real_build_matches_recorded_set_identity",
            [
                (r"typo_set\.size, (\d+)", size[(1, 3)]),
                (r'typo_set\.sha256,\s*"([0-9a-f]{64})"', sha[(1, 3)]),
                (r"typo_set\.variant_p95, (\d+)", pins.variant_p95),
            ],
        ),
        (
            TYPO_PACK_TEST,
            "test_class1_class2_class3_recorded_identities",
            typo_literals("one", "two", (1, 3), (2, 3))
            + [
                (r"three\.size, (\d+)", size[(3, 3)]),
                (r'three\.sha256,\s*"([0-9a-f]{64})"', sha[(3, 3)]),
            ],
        ),
        (
            TYPO_PACK_TEST,
            "test_class1_and_class2_five_code_point_window_identities",
            typo_literals("one", "two", (1, 5), (2, 5)),
        ),
        (
            TYPO_PACK_TEST,
            "test_class4_recorded_identities",
            typo_literals("four3", "four5", (4, 3), (4, 5)),
        ),
        (
            GLIDE_PACK_TEST,
            "test_real_build_matches_recorded_set_identity",
            [
                (r"len\(selected\), (\d+)", pins.glide_words),
                (r"len\(data\), (\d+)", pins.glide_bytes),
                (r'sha256_bytes\(data\), "([0-9a-f]{64})"', pins.glide_sha256),
            ],
        ),
        (
            LONG_PRESS_CALIBRATION_TEST,
            None,
            [
                (r"GENERATOR_SET_SIZE = ([\d_]+)", size[(1, 3)]),
                (r'GENERATOR_SET_SHA256 =\s*"([0-9a-f]{64})"', sha[(1, 3)]),
            ],
        ),
        (
            FULL_SUBSTITUTION_CALIBRATION_TEST,
            None,
            [
                (r"assertEquals\(([\d_]+), class4w3\.rows\.size\)", size[(4, 3)]),
                (r'assertEquals\("([0-9a-f]{64})", class4w3\.sha256\)', sha[(4, 3)]),
                (r"assertEquals\(([\d_]+), class4w5\.rows\.size\)", size[(4, 5)]),
                (r'assertEquals\("([0-9a-f]{64})", class4w5\.sha256\)', sha[(4, 5)]),
            ],
        ),
        (
            GLIDE_CALIBRATION_TEST,
            None,
            [
                (r"SET_SIZE = ([\d_]+)", pins.glide_words),
                (r"SET_BYTES = ([\d_]+)", pins.glide_bytes),
                (r'SET_SHA256 =\s*"([0-9a-f]{64})"', pins.glide_sha256),
            ],
        ),
        (
            GLIDE_GOLDEN_TEST,
            None,
            [
                (r"assertEquals\((\d+), words\.size\)", pins.glide_words),
                (r"assertEquals\((\d+), bytes\.size\)", pins.glide_bytes),
                (r'assertEquals\("([0-9a-f]{64})", sha\)', pins.glide_sha256),
            ],
        ),
    ]


def calibration_pin_updates(root: Path, pins: CalibrationPins) -> dict[Path, str]:
    """The new text of every calibration test file whose pinned literals differ from `pins`,
    keyed by its repo-relative path. Reads only; an empty result means every pin matches."""
    texts: dict[Path, str] = {}
    for relative, region, literals in _calibration_pin_sites(pins):
        current = texts.get(relative)
        if current is None:
            current = (root / relative).read_text(encoding="utf-8")
        texts[relative] = _rewrite_pin_literals(root / relative, current, region, literals)
    return {
        relative: text
        for relative, text in texts.items()
        if text != (root / relative).read_text(encoding="utf-8")
    }


def propagate_calibration_pins(root: Path, dictionary: Pins) -> list[str]:
    """Rewrite the pinned calibration-set identities; return the changed repo-relative paths.
    Only the rebuild calls this; the tests use calibration_pin_updates, which writes nothing."""
    updates = calibration_pin_updates(root, measure_calibration_pins(root, dictionary))
    for relative, text in updates.items():
        _atomic_write_text(root / relative, text)
    return [str(relative) for relative in updates]


# --- rebuild ----------------------------------------------------------------------------------


def bigram_pack_argv(root: Path, corpus_dir: Path, work_dir: Path, bigram: BigramAsset) -> list[str]:
    """Command line that repacks one table; a separate function so tests can inspect it."""
    argv = [
        sys.executable,
        str(root / "scripts/bigram_asset_pack.py"),
        "pack",
        "--train",
        *[str(corpus_dir / name) for name in bigram.train],
        "--asset",
        str(root / next(d.asset for d in DICTIONARIES if d.tag == bigram.dictionary)),
        "--heads",
        str(bigram.heads),
        "--successes-per-head",
        str(bigram.successes_per_head),
        "--schema",
        "3",
        "--out-raw",
        str(work_dir / Path(bigram.asset).name.removesuffix(".zlib")),
        "--out-compressed",
        str(root / bigram.asset),
        "--report",
        str(work_dir / f"{bigram.tag}-pack.generated.json"),
        "--language",
        bigram.tag,
    ]
    if bigram.recover_punctuation:
        argv += ["--recover-punctuation"]
    if bigram.extra_heads is not None:
        argv += ["--extra-heads", str(root / bigram.extra_heads)]
    return argv


def companion_pack_argv(root: Path, companion: CompanionListAsset) -> list[str]:
    """Command line that repacks one list; a separate function so tests can inspect it."""
    return [
        sys.executable,
        str(root / "scripts/companion_list_pack.py"),
        "build",
        "--dictionary",
        str(root / next(d.asset for d in DICTIONARIES if d.tag == companion.dictionary)),
        "--language",
        companion.tag,
        "--top",
        str(companion.top),
        "--output",
        str(root / companion.asset),
    ]


def dict_accept_argv(
    root: Path,
    baseline: Path,
    work_dir: Path,
    dictionary: DictionaryAsset,
    extra_entries: Path | None,
) -> list[str]:
    """Command line that rebuilds one dictionary; a separate function so tests can inspect it."""
    argv = [
        sys.executable,
        str(root / "scripts/dict_accept.py"),
        "--json-out",
        str(work_dir / f"dict-accept-pack-{dictionary.tag}.json"),
        "pack",
        "--baseline",
        str(baseline),
        "--write",
        "--only",
        dictionary.tag,
        "--top",
        str(dictionary.top),
    ]
    if extra_entries is not None:
        argv += ["--extra-entries", str(extra_entries)]
    return argv


def _run_step(argv: list[str], cwd: Path) -> None:
    print("+ " + " ".join(argv[1:]), file=sys.stderr)
    completed = subprocess.run(argv, cwd=cwd, check=False)
    if completed.returncode != 0:
        raise SystemExit(f"rebuild step failed with code {completed.returncode}: {argv[1]}")


def _side_snapshot(root: Path, tags: frozenset[str]) -> dict[str, object]:
    """Asset SHA-256 values and contract pins for the languages in `tags`.

    Taken before the first rebuild step and compared after the pins are written, so that
    `--only` can prove the unselected side is unchanged, both files and contract blocks.
    """
    snapshot: dict[str, object] = {}
    for dictionary in DICTIONARIES:
        if dictionary.tag not in tags:
            continue
        asset = root / dictionary.asset
        snapshot[f"asset:{dictionary.asset}"] = (
            hashlib.sha256(asset.read_bytes()).hexdigest() if asset.is_file() else None
        )
        snapshot[f"pins:{dictionary.spec}"] = read_pins(
            root / DICT_CONTRACT, dictionary.spec, "DictionaryArtifactSpec",
            "expectedEntryCount",
        )
    for bigram in BIGRAMS:
        if bigram.tag not in tags:
            continue
        asset = root / bigram.asset
        snapshot[f"asset:{bigram.asset}"] = (
            hashlib.sha256(asset.read_bytes()).hexdigest() if asset.is_file() else None
        )
        snapshot[f"pins:{bigram.spec}"] = read_pins(
            root / BIGRAM_CONTRACT, bigram.spec, "BigramArtifactSpec",
            "expectedHeadCount", linked=True,
        )
    for companion in COMPANIONS:
        if companion.tag not in tags:
            continue
        asset = root / companion.asset
        snapshot[f"asset:{companion.asset}"] = (
            hashlib.sha256(asset.read_bytes()).hexdigest() if asset.is_file() else None
        )
        snapshot[f"pins:{companion.spec}"] = read_pins(
            root / COMPANION_CONTRACT, companion.spec, "CompanionListArtifactSpec",
            "expectedWordCount",
        )
    return snapshot


def run_rebuild(
    root: Path,
    baseline: Path,
    corpus_dir: Path,
    work_dir: Path,
    known_drift_path: Path | None,
    only: str | None = None,
    stream: TextIO = sys.stdout,
) -> int:
    # `--only tatar|russian` rebuilds one side: its dictionary, bigram table and pins. The
    # other side needs none of its inputs and must stay byte-identical (snapshot before/after).
    selected = frozenset(
        ("tat", "rus") if only is None else ({"tatar": "tat", "russian": "rus"}[only],)
    )
    untouched = _side_snapshot(
        root, frozenset(d.tag for d in DICTIONARIES) - selected
    )

    # Collect all missing inputs of the selected languages first, so a long rebuild does not
    # fail late on a file that was missing from the start.
    missing = []
    for dictionary in DICTIONARIES:
        if dictionary.tag not in selected:
            continue
        name = Path(dictionary.asset).name
        if not (baseline / name).is_file():
            missing.append(str(baseline / name))
    for bigram in BIGRAMS:
        if bigram.tag not in selected:
            continue
        for name in bigram.train:
            if not (corpus_dir / name).is_file():
                missing.append(str(corpus_dir / name))
        if bigram.extra_heads is not None and not (root / bigram.extra_heads).is_file():
            missing.append(str(root / bigram.extra_heads))
    if "tat" in selected:
        for name in WORDFORM_FREQUENCY_SOURCES:
            if not (corpus_dir / name).is_file():
                missing.append(str(corpus_dir / name))
        if not (root / WORDFORM_EXCEPTIONS).is_file():
            missing.append(str(root / WORDFORM_EXCEPTIONS))
    if missing:
        print("error: missing rebuild inputs:", file=sys.stderr)
        for path in missing:
            print(f"  {path}", file=sys.stderr)
        print("the origin of each input is in the script docstring", file=sys.stderr)
        return 2

    # The corpus bytes of the selected languages must match the committed manifest before
    # anything is written.
    try:
        corpus_manifest = load_corpus_manifest(root / CORPUS_MANIFEST)
    except CorpusManifestError as error:
        print(f"error: corpus manifest: {error}", file=sys.stderr)
        return 2
    corpus_verdicts = verify_corpus(corpus_dir, corpus_manifest, corpus_names(selected))
    bad_corpora = {name: v for name, v in corpus_verdicts.items() if v != "ok"}
    if bad_corpora:
        print(f"error: corpora do not match {CORPUS_MANIFEST}:", file=sys.stderr)
        for name, verdict in sorted(bad_corpora.items()):
            print(f"  {corpus_dir / name}: {verdict}", file=sys.stderr)
        return 1

    work_dir.mkdir(parents=True, exist_ok=True)

    # 1. Dictionaries of the selected languages. dict_accept verifies the baseline SHA-256 and
    # refuses an already rebuilt asset. The Tatar side first runs the word-form stage; the
    # admitted forms are passed as --extra-entries.
    wordforms: Path | None = None
    if "tat" in selected:
        wordforms = build_admitted_wordforms(root, baseline, corpus_dir, work_dir)
    for dictionary in DICTIONARIES:
        if dictionary.tag not in selected:
            continue
        _run_step(
            dict_accept_argv(
                root, baseline, work_dir, dictionary,
                wordforms if dictionary.tag == "tat" else None,
            ),
            cwd=root,
        )

    # 2. Bigram tables of the selected languages, built against the dictionaries of step 1.
    for bigram in BIGRAMS:
        if bigram.tag not in selected:
            continue
        _run_step(bigram_pack_argv(root, corpus_dir, work_dir, bigram), cwd=root)
    # 2b. Companion lists of the selected languages, derived from the dictionaries of
    # step 1 alone: no corpus and no baseline input.
    for companion in COMPANIONS:
        if companion.tag not in selected:
            continue
        _run_step(companion_pack_argv(root, companion), cwd=root)

    # 3. Pins of the selected assets, one write per contract file.
    dict_updates = {
        d.spec: measure_dictionary(root / d.asset, d.tag)
        for d in DICTIONARIES
        if d.tag in selected
    }
    write_pins(
        root / DICT_CONTRACT, dict_updates, "DictionaryArtifactSpec", "expectedEntryCount"
    )
    bigram_updates = {
        b.spec: measure_bigram(
            root / b.asset,
            root / next(d.asset for d in DICTIONARIES if d.tag == b.dictionary),
            b.dictionary,
        )
        for b in BIGRAMS
        if b.tag in selected
    }
    write_pins(
        root / BIGRAM_CONTRACT, bigram_updates, "BigramArtifactSpec", "expectedHeadCount"
    )
    companion_updates = {
        c.spec: measure_companion(root / c.asset)
        for c in COMPANIONS
        if c.tag in selected
    }
    write_pins(
        root / COMPANION_CONTRACT, companion_updates, "CompanionListArtifactSpec",
        "expectedWordCount",
    )
    print("pins rewritten in all three contracts", file=sys.stderr)

    # 3b. With --only, the unselected side must be byte-identical.
    if only is not None:
        after = _side_snapshot(
            root, frozenset(d.tag for d in DICTIONARIES) - selected
        )
        moved = [key for key, before in untouched.items() if after[key] != before]
        if moved:
            print(
                f"error: --only {only}: the unselected side changed:",
                file=sys.stderr,
            )
            for key in moved:
                print(f"  {key}", file=sys.stderr)
            return 2

    # 3c. The Tatar calibration sets (typo recovery, glide) derive from the dictionary, so
    # their pinned identities in the python tests and the JVM calibration tests are
    # regenerated and rewritten too.
    if "tat" in selected:
        try:
            moved = propagate_calibration_pins(root, dict_updates["TATAR_TOP100K_V1"])
        except ContractError:
            raise
        except Exception as error:
            raise SystemExit(f"calibration pin update step failed: {error}") from error
        if moved:
            print("calibration pins rewritten:", file=sys.stderr)
            for path in moved:
                print(f"  {path}", file=sys.stderr)
        else:
            print("calibration pins already match", file=sys.stderr)

    # 4. Verify the result with the --check procedure over all four assets, including the
    # untouched side.
    result = run_check(root, known_drift_path, stream)
    print(
        "next, by hand: run the gates (JVM + python + lintRelease + check-no-internet) and "
        "update scripts/known_asset_drift.json if the head drift changed",
        file=sys.stderr,
    )
    return result


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--check",
        action="store_true",
        help="consistency check only: rebuilds and writes nothing",
    )
    parser.add_argument(
        "--only",
        choices=("tatar", "russian"),
        default=None,
        help="rebuild one side only: its dictionary (for Tatar, with the wordform "
        "stage), its bigram table and its pins; the other side's inputs are not needed, "
        "while its assets and pins must stay byte-identical (a SHA-256 snapshot before "
        "and after). The final check verifies all four assets. Not valid with --check",
    )
    parser.add_argument(
        "--allow-known-drift",
        nargs="?",
        const=str(DEFAULT_KNOWN_DRIFT),
        default=None,
        metavar="FILE",
        help="accept head drift only when it matches the known-drift file exactly "
        "(default %(const)s); any other count, or a stale entry, fails",
    )
    parser.add_argument(
        "--baseline",
        type=Path,
        help="directory with the 1.8.4 assets; required for a rebuild",
    )
    parser.add_argument(
        "--corpus-dir",
        type=Path,
        default=None,
        help="directory holding the corpora listed in data/corpus-manifest.json "
        f"(rebuild default {DEFAULT_CORPUS_DIR}); with --check they are verified "
        "against the manifest",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=None,
        help="where to write raw tables and reports (default <root>/build/rebuild_assets)",
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=ROOT,
        help="tree root (default: the script's repository; for tests)",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    root = args.root
    known_drift = (
        Path(args.allow_known_drift)
        if args.allow_known_drift is not None
        else None
    )
    if known_drift is not None and not known_drift.is_absolute():
        known_drift = root / known_drift
    if args.check:
        if args.only is not None:
            print("error: --only applies to rebuilds; --check always verifies all four assets",
                  file=sys.stderr)
            return 2
        return run_check(root, known_drift, corpus_dir=args.corpus_dir)
    if args.baseline is None:
        print("error: a rebuild needs --baseline (or run --check)",
              file=sys.stderr)
        return 2
    work_dir = args.work_dir if args.work_dir is not None else root / DEFAULT_WORK_DIR
    corpus_dir = args.corpus_dir if args.corpus_dir is not None else DEFAULT_CORPUS_DIR
    try:
        return run_rebuild(
            root, args.baseline, corpus_dir, work_dir, known_drift, only=args.only
        )
    except ContractError as error:
        print(f"error: unparsable contract: {error}", file=sys.stderr)
        return 2
    except wordform_gen.WordformError as error:
        print(f"error: wordform stage: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
