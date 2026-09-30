#!/usr/bin/env python3
"""Build the emoji panel asset from Unicode Emoji 15.1 ``emoji-test.txt``.

Input: a locally downloaded ``emoji-test.txt`` (pinned SHA-256). Output:
``app/src/main/assets/emoji/emoji_set_v1.txt``, a deterministic UTF-8/LF asset. Only
``fully-qualified`` records are kept, minus every sequence with a skin-tone modifier, a ZWJ,
a regional indicator or a tag code point (cut by code point, never by a literal list).
Exits nonzero without writing output on a pin or version mismatch, a duplicate sequence,
an unknown group, invalid UTF-8, or a breached size/entry guardrail.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence, TextIO


# Pinned input identity: Unicode Emoji 15.1 emoji-test.txt.
# The SHA-256 is the check; the version string is a readable cross-check.
EXPECTED_INPUT_SHA256 = (
    "d876ee249aa28eaa76cfa6dfaa702847a8d13b062aa488d465d0395ee8137ed9"
)
EXPECTED_UNICODE_VERSION = "15.1"

# Guardrails on the output size.
MAX_ASSET_BYTES = 65536
MAX_ENTRIES = 1400

# Excluded code point ranges (inclusive), matched against every code point of a
# candidate sequence. Cutting is by code point, never by a literal list.
SKIN_TONE_RANGE = (0x1F3FB, 0x1F3FF)
ZERO_WIDTH_JOINER = 0x200D
REGIONAL_INDICATOR_RANGE = (0x1F1E6, 0x1F1FF)
TAG_RANGE = (0xE0020, 0xE007F)

STATUS_FULLY_QUALIFIED = "fully-qualified"

VERSION_PREFIX = "# Version:"
GROUP_PREFIX = "# group:"

# Pinned allowlist of emoji-test.txt top-level groups, in canonical file order.
# An input group outside this set is an unknown category and stops the build, so a
# future Unicode version cannot add a group without review.
KNOWN_GROUPS = (
    "Smileys & Emotion",
    "People & Body",
    "Component",
    "Animals & Nature",
    "Food & Drink",
    "Travel & Places",
    "Activities",
    "Objects",
    "Symbols",
    "Flags",
)

# A section header is '#' immediately followed by an ASCII-lowercase slug. No
# emoji sequence can match this: the only fully-qualified sequence starting with
# U+0023 is the number-sign keycap '#\uFE0F\u20E3', whose second unit is U+FE0F,
# not an ASCII letter. The generator asserts this invariant so the asset stays
# unambiguously parseable line by line.
SECTION_HEADER_RE = re.compile(r"^#[a-z][a-z0-9-]*$")


class EmojiPackError(ValueError):
    """A generator error; nothing is written (exit 2)."""


class EmojiGuardrailError(EmojiPackError):
    """A guardrail breach: too many bytes or too many entries (exit 4)."""


@dataclass(frozen=True)
class Section:
    slug: str
    group: str
    sequences: tuple[str, ...]


@dataclass(frozen=True)
class EmojiSet:
    text: str
    data: bytes
    sections: tuple[Section, ...]

    @property
    def entry_count(self) -> int:
        return sum(len(section.sequences) for section in self.sections)

    @property
    def byte_size(self) -> int:
        return len(self.data)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()


def slug_for_group(group: str) -> str:
    """Derive a section slug from a group name.

    Rule: lowercase the (ASCII) group name, replace every maximal run of
    characters outside ``[a-z0-9]`` with a single hyphen, and strip leading and
    trailing hyphens. Examples: ``Smileys & Emotion`` -> ``smileys-emotion``,
    ``People & Body`` -> ``people-body``, ``Flags`` -> ``flags``.
    """
    slug = re.sub(r"[^a-z0-9]+", "-", group.lower()).strip("-")
    if not slug or not (slug[0].isascii() and slug[0].isalpha()):
        raise EmojiPackError(f"group produces an invalid slug: {group!r}")
    return slug


def read_input_text(path: Path, *, expected_sha256: str) -> str:
    raw = path.read_bytes()
    actual_sha = hashlib.sha256(raw).hexdigest()
    if actual_sha != expected_sha256:
        raise EmojiPackError(
            f"input SHA-256 {actual_sha} does not match pinned {expected_sha256}"
        )
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise EmojiPackError(f"input is not valid UTF-8: {error}") from error


def parse_version(text: str, *, expected_version: str) -> str:
    for line in text.splitlines():
        if line.startswith(VERSION_PREFIX):
            version = line[len(VERSION_PREFIX):].strip()
            if version != expected_version:
                raise EmojiPackError(
                    f"input declares Emoji version {version!r}; "
                    f"expected {expected_version!r}"
                )
            return version
    raise EmojiPackError("input does not declare a '# Version:' line")


def _is_excluded(codepoints: Sequence[int]) -> bool:
    for cp in codepoints:
        if SKIN_TONE_RANGE[0] <= cp <= SKIN_TONE_RANGE[1]:
            return True
        if cp == ZERO_WIDTH_JOINER:
            return True
        if REGIONAL_INDICATOR_RANGE[0] <= cp <= REGIONAL_INDICATOR_RANGE[1]:
            return True
        if TAG_RANGE[0] <= cp <= TAG_RANGE[1]:
            return True
    return False


def _sequence_from_codepoints(codepoints: Sequence[int]) -> str:
    for cp in codepoints:
        if cp < 0 or cp > 0x10FFFF or 0xD800 <= cp <= 0xDFFF:
            raise EmojiPackError(f"code point U+{cp:04X} is not a Unicode scalar value")
    sequence = "".join(chr(cp) for cp in codepoints)
    try:
        sequence.encode("utf-8")
    except UnicodeEncodeError as error:
        raise EmojiPackError(
            f"sequence is not encodable as UTF-8: {error}"
        ) from error
    return sequence


def parse_sections(text: str) -> tuple[Section, ...]:
    known = set(KNOWN_GROUPS)
    order: list[str] = []
    by_group: dict[str, list[str]] = {}
    seen: set[str] = set()
    current_group: str | None = None

    for line in text.splitlines():
        if line.startswith(GROUP_PREFIX):
            group = line[len(GROUP_PREFIX):].strip()
            if group not in known:
                raise EmojiPackError(f"unknown category: {group!r}")
            current_group = group
            continue
        if not line.strip() or line.startswith("#"):
            continue
        if ";" not in line:
            continue
        codepart, rest = line.split(";", 1)
        status = rest.split("#", 1)[0].strip()
        if status != STATUS_FULLY_QUALIFIED:
            continue
        try:
            codepoints = [int(token, 16) for token in codepart.split()]
        except ValueError as error:
            raise EmojiPackError(f"malformed code point in line: {line!r}") from error
        if not codepoints:
            raise EmojiPackError(f"fully-qualified line has no code points: {line!r}")
        if _is_excluded(codepoints):
            continue
        sequence = _sequence_from_codepoints(codepoints)
        if SECTION_HEADER_RE.match(sequence):
            raise EmojiPackError(
                f"sequence {sequence!r} collides with the section-header format"
            )
        if current_group is None:
            raise EmojiPackError("fully-qualified entry appears before any group")
        if sequence in seen:
            raise EmojiPackError(f"duplicate sequence: {sequence!r}")
        seen.add(sequence)
        if current_group not in by_group:
            by_group[current_group] = []
            order.append(current_group)
        by_group[current_group].append(sequence)

    sections: list[Section] = []
    used_slugs: set[str] = set()
    for group in order:
        slug = slug_for_group(group)
        if slug in used_slugs:
            raise EmojiPackError(f"duplicate section slug: {slug!r}")
        used_slugs.add(slug)
        sections.append(
            Section(slug=slug, group=group, sequences=tuple(by_group[group]))
        )
    return tuple(sections)


def render(sections: Sequence[Section]) -> str:
    lines: list[str] = []
    for section in sections:
        lines.append(f"#{section.slug}")
        lines.extend(section.sequences)
    return "\n".join(lines) + "\n" if lines else ""


def build_emoji_set(
    input_path: Path,
    *,
    expected_sha256: str = EXPECTED_INPUT_SHA256,
    expected_version: str = EXPECTED_UNICODE_VERSION,
    max_bytes: int = MAX_ASSET_BYTES,
    max_entries: int = MAX_ENTRIES,
) -> EmojiSet:
    text = read_input_text(input_path, expected_sha256=expected_sha256)
    parse_version(text, expected_version=expected_version)
    sections = parse_sections(text)
    rendered = render(sections)
    data = rendered.encode("utf-8")
    entry_count = sum(len(section.sequences) for section in sections)
    if entry_count > max_entries:
        raise EmojiGuardrailError(
            f"emoji set has {entry_count} entries; limit is {max_entries}"
        )
    if len(data) > max_bytes:
        raise EmojiGuardrailError(
            f"emoji asset is {len(data)} bytes; limit is {max_bytes}"
        )
    return EmojiSet(text=rendered, data=data, sections=sections)


def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=str(path.parent), prefix=f".{path.name}.")
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build", help="generate the emoji asset")
    build.add_argument("--input", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    return parser


def _print_json(value: object, stream: TextIO | None = None) -> None:
    if stream is None:
        stream = sys.stdout
    json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
    stream.write("\n")


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        if args.command == "build":
            emoji_set = build_emoji_set(
                args.input,
                expected_sha256=EXPECTED_INPUT_SHA256,
                expected_version=EXPECTED_UNICODE_VERSION,
                max_bytes=MAX_ASSET_BYTES,
                max_entries=MAX_ENTRIES,
            )
            write_atomic(args.output, emoji_set.data)
            _print_json(
                {
                    "asset_bytes": emoji_set.byte_size,
                    "asset_sha256": emoji_set.sha256,
                    "entry_count": emoji_set.entry_count,
                    "sections": [
                        {"count": len(section.sequences), "slug": section.slug}
                        for section in emoji_set.sections
                    ],
                    "unicode_version": EXPECTED_UNICODE_VERSION,
                }
            )
        else:  # pragma: no cover
            raise AssertionError(args.command)
    except EmojiGuardrailError as error:
        print(f"error: {error}", file=sys.stderr)
        return 4
    except (EmojiPackError, OSError, UnicodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
