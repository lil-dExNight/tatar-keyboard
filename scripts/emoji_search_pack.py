#!/usr/bin/env python3
"""Build the emoji-search index from CLDR 44 annotations plus hand-written Tatar keywords.

Input: CLDR ``annotations{,Derived}/{ru,en}.xml`` (pinned SHA-256), the panel asset
``emoji_set_v1.txt`` and optionally ``--tt-extra`` (CLDR has no Tatar emoji annotations).
Output: ``assets/emoji/emoji_search_v1.txt``, one ``<sequence>\\t<ru name>\\t<keywords>`` line
per panel sequence with keywords: Russian first (search priority), then English, then Tatar.
Exits nonzero without writing output on a pin mismatch, unparseable input, a tab or newline in
a field, coverage below the floor, a breached guardrail, or a malformed ``--tt-extra`` file.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tempfile
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

# Pinned input identity: CLDR release-44 annotation files.
EXPECTED_INPUT_SHA256 = {
    "ru": "6de7170ec03f1d685b1c30d71c391d5893e366c57e9c9db37f687989893771cb",
    "en": "13db8bb0a85a1ab9c46dd70f6170d72e7938063eb04cb2ee65e46d0378cfebf6",
    "derived-ru": "c672c2fc917ea132f4a9358413e0b0dda6e3c292bc441bcf5b4b09683a7b391a",
    "derived-en": "cfcadefede165fdad566f32894d4a7975a0bce76ca3d6758a5f8f448e550e6b2",
}

CLDR_VERSION = "44"

# Guardrails on the output size.
MAX_ASSET_BYTES = 262144
MAX_LINES = 1400

# Coverage floor: how many of the panel's sequences must carry Russian keywords.
# The only expected gap is a sequence CLDR has no annotation for at all.
MIN_RUSSIAN_COVERAGE = 0.99

VARIATION_SELECTOR_16 = "️"

SECTION_HEADER_RE = re.compile(r"^#[a-z][a-z0-9-]*$")

ANNOTATION_RE = re.compile(
    r'<annotation cp="([^"]*)"(?:\s+type="(tts)")?\s*>([^<]*)</annotation>'
)

XML_ENTITIES = (
    ("&lt;", "<"),
    ("&gt;", ">"),
    ("&quot;", '"'),
    ("&apos;", "'"),
    ("&amp;", "&"),
)


class EmojiSearchPackError(ValueError):
    """A generator error; nothing is written (exit 2)."""


class EmojiSearchGuardrailError(EmojiSearchPackError):
    """A guardrail breach: too many bytes, too many lines, or too little coverage (exit 4)."""


@dataclass(frozen=True)
class SearchIndex:
    text: str
    data: bytes
    line_count: int
    russian_coverage: float
    sequence_count: int

    @property
    def byte_size(self) -> int:
        return len(self.data)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()


def unescape(value: str) -> str:
    """Undo the five XML entities CLDR uses. ``&amp;`` is undone last on purpose."""
    for entity, char in XML_ENTITIES:
        value = value.replace(entity, char)
    return value


def read_input_text(path: Path, *, expected_sha256: str) -> str:
    raw = path.read_bytes()
    actual_sha = hashlib.sha256(raw).hexdigest()
    if actual_sha != expected_sha256:
        raise EmojiSearchPackError(
            f"{path.name}: SHA-256 {actual_sha} does not match pinned {expected_sha256}"
        )
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise EmojiSearchPackError(f"{path.name}: input is not valid UTF-8") from error


def parse_annotations(text: str) -> tuple[dict[str, list[str]], dict[str, str]]:
    """Splits a CLDR annotation file into keyword lists and short names."""
    keywords: dict[str, list[str]] = {}
    names: dict[str, str] = {}
    for match in ANNOTATION_RE.finditer(text):
        cp = unescape(match.group(1))
        value = unescape(match.group(3)).strip()
        if not cp or not value:
            continue
        if match.group(2) == "tts":
            names[cp] = value
        else:
            keywords[cp] = [part.strip() for part in value.split("|") if part.strip()]
    if not keywords and not names:
        raise EmojiSearchPackError("no annotations found; input is not a CLDR annotation file")
    return keywords, names


def read_panel_sequences(path: Path) -> tuple[str, ...]:
    """Reads the panel asset and returns its sequences in file order."""
    text = path.read_text(encoding="utf-8")
    sequences: list[str] = []
    seen: set[str] = set()
    for raw_line in text.split("\n"):
        line = raw_line.rstrip("\r")
        if not line or SECTION_HEADER_RE.match(line):
            continue
        if line in seen:
            raise EmojiSearchPackError(f"duplicate sequence in the panel asset: {line!r}")
        seen.add(line)
        sequences.append(line)
    if not sequences:
        raise EmojiSearchPackError("the panel asset holds no sequences")
    return tuple(sequences)


def lookup_key(sequence: str) -> str:
    """CLDR strips U+FE0F from its cp attributes, so a lookup strips it too."""
    return sequence.replace(VARIATION_SELECTOR_16, "")


# The lowercase Tatar Cyrillic alphabet — the same set
# `PersonalSubtypes.TATAR_RU_ALPHABET` enforces on personal-dictionary words
# and `TdictValidator` on the packed dictionary. Hand-written Tatar keywords
# are checked against it, so a stray Russian-only or Latin letter fails the
# build.
TATAR_ALPHABET = frozenset("аәбвгдеёжҗзийклмнңоөпрстуүфхһцчшщъыьэюя")

# A Tatar synonym word: alphabet letters, spaces (a phrase is one keyword;
# the app's word-prefix matching still hits each word of it) and the hyphen
# of compounds like «ир-ат» / «хатын-кыз».
TATAR_WORD_CHARS = TATAR_ALPHABET | {" ", "-"}


def read_tt_extra(
    path: Path, panel_sequences: Sequence[str]
) -> dict[str, list[str]]:
    """Reads the hand-written Tatar keyword file; raises on any invalid line.

    One line per emoji: ``<sequence>\\t<synonym>,<synonym>,...`` with the
    sequence exactly as in the panel asset (U+FE0F included where the panel has
    it). Blank lines and ``#`` comment lines are skipped. Every sequence must
    belong to the panel and appear once; every synonym must be non-empty,
    lowercase, NFC and spelled from :data:`TATAR_WORD_CHARS`.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError as error:
        raise EmojiSearchPackError(f"{path.name}: input is not valid UTF-8") from error
    panel = set(panel_sequences)
    extra: dict[str, list[str]] = {}
    for lineno, raw_line in enumerate(text.split("\n"), start=1):
        line = raw_line.rstrip("\r")
        if not line or line.startswith("#"):
            continue
        where = f"{path.name}:{lineno}"
        if line.count("\t") != 1:
            raise EmojiSearchPackError(f"{where}: expected exactly one tab")
        sequence, synonyms = line.split("\t")
        if sequence not in panel:
            raise EmojiSearchPackError(f"{where}: sequence not in the panel asset")
        if sequence in extra:
            raise EmojiSearchPackError(f"{where}: duplicate sequence")
        words = [" ".join(word.strip().split()) for word in synonyms.split(",")]
        words = [word for word in words if word]
        if not words:
            raise EmojiSearchPackError(f"{where}: no synonyms")
        for word in words:
            if word != unicodedata.normalize("NFC", word) or word != word.lower():
                raise EmojiSearchPackError(
                    f"{where}: synonym {word!r} is not canonical (NFC lowercase)"
                )
            bad = sorted(set(word) - TATAR_WORD_CHARS)
            if bad:
                raise EmojiSearchPackError(
                    f"{where}: non-Tatar characters in {word!r}: {bad}"
                )
        extra[sequence] = words
    if not extra:
        raise EmojiSearchPackError(f"{path.name}: the Tatar keyword file is empty")
    return extra


def build_index(
    sequences: Sequence[str],
    sources: Sequence[tuple[dict[str, list[str]], dict[str, str]]],
    *,
    max_bytes: int,
    max_lines: int,
    min_russian_coverage: float,
    tt_extra: dict[str, list[str]] | None = None,
) -> SearchIndex:
    """Composes one index line per sequence that has at least one keyword.

    ``sources`` is ordered: Russian first (its name becomes the ranking name and
    its keywords come first), English after it. ``tt_extra`` maps panel
    sequences to hand-written Tatar synonyms; they are appended last, so they
    never displace a Russian or English keyword.
    """
    tt_extra = tt_extra or {}
    lines: list[str] = []
    with_russian = 0
    russian_keywords, russian_names = sources[0]
    for sequence in sequences:
        key = lookup_key(sequence)
        words: list[str] = []
        for keywords, names in sources:
            for word in [names.get(key, "")] + keywords.get(key, []):
                word = " ".join(word.lower().split())
                if word and word not in words:
                    words.append(word)
        for word in tt_extra.get(sequence, []):
            if word not in words:
                words.append(word)
        if not words:
            continue
        if key in russian_keywords or key in russian_names:
            with_russian += 1
        name = russian_names.get(key, words[0])
        line = f"{sequence}\t{name.lower()}\t{' '.join(words)}"
        if "\n" in line or line.count("\t") != 2:
            raise EmojiSearchPackError(f"malformed index line for {sequence!r}")
        lines.append(line)

    text = "\n".join(lines) + "\n"
    data = text.encode("utf-8")
    coverage = with_russian / len(sequences)
    if len(data) > max_bytes:
        raise EmojiSearchGuardrailError(f"asset is {len(data)} bytes, over the {max_bytes} guardrail")
    if len(lines) > max_lines:
        raise EmojiSearchGuardrailError(f"asset has {len(lines)} lines, over the {max_lines} guardrail")
    if coverage < min_russian_coverage:
        raise EmojiSearchGuardrailError(
            f"Russian coverage {coverage:.4f} is below the {min_russian_coverage} floor"
        )
    return SearchIndex(
        text=text,
        data=data,
        line_count=len(lines),
        russian_coverage=coverage,
        sequence_count=len(sequences),
    )


def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = tempfile.NamedTemporaryFile(
        dir=str(path.parent), delete=False, prefix=path.name, suffix=".tmp"
    )
    try:
        with handle:
            handle.write(data)
        Path(handle.name).replace(path)
    except BaseException:
        Path(handle.name).unlink(missing_ok=True)
        raise


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    build = subparsers.add_parser("build", help="build the emoji-search index asset")
    build.add_argument("--cldr-dir", type=Path, required=True,
                       help="directory holding ru.xml, en.xml, derived-ru.xml, derived-en.xml")
    build.add_argument("--panel-asset", type=Path, required=True)
    build.add_argument("--tt-extra", type=Path, default=None,
                       help="hand-written Tatar keywords: sequence<TAB>synonym,synonym,...")
    build.add_argument("--output", type=Path, required=True)
    return parser


def _print_json(payload: dict) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        if args.command == "build":
            parsed = {}
            for name, sha in EXPECTED_INPUT_SHA256.items():
                parsed[name] = parse_annotations(
                    read_input_text(args.cldr_dir / f"{name}.xml", expected_sha256=sha)
                )
            # Derived annotations only fill gaps (keycaps); the base file wins.
            merged = {}
            for lang in ("ru", "en"):
                keywords = dict(parsed[f"derived-{lang}"][0])
                keywords.update(parsed[lang][0])
                names = dict(parsed[f"derived-{lang}"][1])
                names.update(parsed[lang][1])
                merged[lang] = (keywords, names)
            panel_sequences = read_panel_sequences(args.panel_asset)
            tt_extra = (
                read_tt_extra(args.tt_extra, panel_sequences)
                if args.tt_extra is not None
                else None
            )
            index = build_index(
                panel_sequences,
                (merged["ru"], merged["en"]),
                max_bytes=MAX_ASSET_BYTES,
                max_lines=MAX_LINES,
                min_russian_coverage=MIN_RUSSIAN_COVERAGE,
                tt_extra=tt_extra,
            )
            write_atomic(args.output, index.data)
            _print_json(
                {
                    "asset_bytes": index.byte_size,
                    "asset_sha256": index.sha256,
                    "cldr_version": CLDR_VERSION,
                    "line_count": index.line_count,
                    "russian_coverage": round(index.russian_coverage, 4),
                    "sequence_count": index.sequence_count,
                    "tt_extra_sequences": len(tt_extra) if tt_extra else 0,
                }
            )
        else:  # pragma: no cover
            raise AssertionError(args.command)
    except EmojiSearchGuardrailError as error:
        print(f"error: {error}", file=sys.stderr)
        return 4
    except (EmojiSearchPackError, OSError, UnicodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
