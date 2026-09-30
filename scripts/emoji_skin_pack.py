#!/usr/bin/env python3
"""Build the skin-tone asset: which panel cells accept a tone, and how to compose it.

Input: Unicode Emoji 15.1 ``emoji-test.txt`` (pinned as in ``emoji_pack.py``) and the
panel asset ``emoji_set_v1.txt``. Output: ``assets/emoji/emoji_skin_v1.txt``, one
``<panel sequence>\\t<prefix>\\t<suffix>`` line per base in panel order; the toned form
is ``prefix + modifier + suffix``. A tone replaces U+FE0F, so the prefix has no VS16.
Exits nonzero without writing output on a pin or version mismatch, a composed form that
is not a fully-qualified record, a base count out of range, or a breached guardrail.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

# The same pinned input identity scripts/emoji_pack.py uses; the two assets are
# generated from one file and must never drift apart.
EXPECTED_INPUT_SHA256 = (
    "d876ee249aa28eaa76cfa6dfaa702847a8d13b062aa488d465d0395ee8137ed9"
)
EXPECTED_UNICODE_VERSION = "15.1"

VERSION_PREFIX = "# Version:"

# Guardrails on the output size.
MAX_ASSET_BYTES = 8192
MAX_LINES = 400

# Sanity range on how many of the panel's neutral cells accept a tone; it catches
# a source swap that empties or explodes the list.
MIN_BASES = 100
MAX_BASES = 200

SKIN_TONE_RANGE = range(0x1F3FB, 0x1F400)
VARIATION_SELECTOR_16 = 0xFE0F

STATUS_FULLY_QUALIFIED = "fully-qualified"

RECORD_RE = re.compile(r"^([0-9A-F ]+);\s*([a-z-]+)\s*#")

SECTION_HEADER_RE = re.compile(r"^#[a-z][a-z0-9-]*$")


class EmojiSkinPackError(ValueError):
    """A generator error; nothing is written (exit 2)."""


class EmojiSkinGuardrailError(EmojiSkinPackError):
    """A guardrail breach: too many bytes, too many lines, or a wild base count (exit 4)."""


@dataclass(frozen=True)
class SkinToneSet:
    text: str
    data: bytes
    base_count: int
    panel_count: int

    @property
    def byte_size(self) -> int:
        return len(self.data)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()


def read_input_text(path: Path, *, expected_sha256: str, expected_version: str) -> str:
    raw = path.read_bytes()
    actual_sha = hashlib.sha256(raw).hexdigest()
    if actual_sha != expected_sha256:
        raise EmojiSkinPackError(
            f"input SHA-256 {actual_sha} does not match pinned {expected_sha256}"
        )
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise EmojiSkinPackError("input is not valid UTF-8") from error
    for line in text.split("\n"):
        if line.startswith(VERSION_PREFIX):
            declared = line[len(VERSION_PREFIX):].strip()
            if declared != expected_version:
                raise EmojiSkinPackError(
                    f"input declares Emoji version {declared!r}, expected {expected_version!r}"
                )
            return text
    raise EmojiSkinPackError("input declares no Emoji version")


def parse_fully_qualified(text: str) -> list[tuple[int, ...]]:
    """Every fully-qualified record of emoji-test.txt, as code-point tuples."""
    records: list[tuple[int, ...]] = []
    for line in text.split("\n"):
        if not line or line.startswith("#"):
            continue
        match = RECORD_RE.match(line)
        if match is None or match.group(2) != STATUS_FULLY_QUALIFIED:
            continue
        records.append(tuple(int(part, 16) for part in match.group(1).split()))
    if not records:
        raise EmojiSkinPackError("no fully-qualified records found; input is not emoji-test.txt")
    return records


def strip_variation_selectors(codepoints: Sequence[int]) -> tuple[int, ...]:
    return tuple(cp for cp in codepoints if cp != VARIATION_SELECTOR_16)


def build_tone_templates(
    records: Sequence[tuple[int, ...]],
) -> dict[tuple[int, ...], tuple[tuple[int, ...], tuple[int, ...]]]:
    """Maps a variation-selector-free base to the (prefix, suffix) a tone slots between.

    A record qualifies when it carries exactly one skin-tone modifier. Records with
    two of them (two-person sequences) describe a pair of bases, not one, and are
    skipped: the panel has no cell for them in the first place.
    """
    templates: dict[tuple[int, ...], tuple[tuple[int, ...], tuple[int, ...]]] = {}
    for codepoints in records:
        positions = [i for i, cp in enumerate(codepoints) if cp in SKIN_TONE_RANGE]
        if len(positions) != 1:
            continue
        index = positions[0]
        prefix = codepoints[:index]
        suffix = codepoints[index + 1:]
        key = strip_variation_selectors(prefix + suffix)
        templates.setdefault(key, (prefix, suffix))
    return templates


def read_panel_sequences(path: Path) -> tuple[str, ...]:
    text = path.read_text(encoding="utf-8")
    sequences: list[str] = []
    seen: set[str] = set()
    for raw_line in text.split("\n"):
        line = raw_line.rstrip("\r")
        if not line or SECTION_HEADER_RE.match(line):
            continue
        if line in seen:
            raise EmojiSkinPackError(f"duplicate sequence in the panel asset: {line!r}")
        seen.add(line)
        sequences.append(line)
    if not sequences:
        raise EmojiSkinPackError("the panel asset holds no sequences")
    return tuple(sequences)


def build_skin_tone_set(
    panel_sequences: Sequence[str],
    templates: dict[tuple[int, ...], tuple[tuple[int, ...], tuple[int, ...]]],
    fully_qualified: set[tuple[int, ...]],
    *,
    max_bytes: int,
    max_lines: int,
    min_bases: int,
    max_bases: int,
) -> SkinToneSet:
    lines: list[str] = []
    for sequence in panel_sequences:
        key = strip_variation_selectors([ord(char) for char in sequence])
        template = templates.get(key)
        if template is None:
            continue
        prefix, suffix = template
        # Every one of the five composed forms must itself be a fully-qualified
        # record: the panel may only offer sequences Unicode actually defines.
        for modifier in SKIN_TONE_RANGE:
            composed = prefix + (modifier,) + suffix
            if composed not in fully_qualified:
                raise EmojiSkinPackError(
                    f"composed form for {sequence!r} with U+{modifier:04X} is not fully-qualified"
                )
        prefix_text = "".join(chr(cp) for cp in prefix)
        suffix_text = "".join(chr(cp) for cp in suffix)
        line = f"{sequence}\t{prefix_text}\t{suffix_text}"
        if "\n" in line or line.count("\t") != 2:
            raise EmojiSkinPackError(f"malformed line for {sequence!r}")
        lines.append(line)

    text = "".join(line + "\n" for line in lines)
    data = text.encode("utf-8")
    if len(data) > max_bytes:
        raise EmojiSkinGuardrailError(f"asset is {len(data)} bytes, over the {max_bytes} guardrail")
    if len(lines) > max_lines:
        raise EmojiSkinGuardrailError(f"asset has {len(lines)} lines, over the {max_lines} guardrail")
    if not min_bases <= len(lines) <= max_bases:
        raise EmojiSkinGuardrailError(
            f"{len(lines)} modifier bases is outside the pinned {min_bases}..{max_bases} range"
        )
    return SkinToneSet(
        text=text,
        data=data,
        base_count=len(lines),
        panel_count=len(panel_sequences),
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
    build = subparsers.add_parser("build", help="build the skin-tone asset")
    build.add_argument("--input", type=Path, required=True, help="emoji-test.txt")
    build.add_argument("--panel-asset", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    return parser


def _print_json(payload: dict) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        if args.command == "build":
            text = read_input_text(
                args.input,
                expected_sha256=EXPECTED_INPUT_SHA256,
                expected_version=EXPECTED_UNICODE_VERSION,
            )
            records = parse_fully_qualified(text)
            skin = build_skin_tone_set(
                read_panel_sequences(args.panel_asset),
                build_tone_templates(records),
                set(records),
                max_bytes=MAX_ASSET_BYTES,
                max_lines=MAX_LINES,
                min_bases=MIN_BASES,
                max_bases=MAX_BASES,
            )
            write_atomic(args.output, skin.data)
            _print_json(
                {
                    "asset_bytes": skin.byte_size,
                    "asset_sha256": skin.sha256,
                    "base_count": skin.base_count,
                    "panel_count": skin.panel_count,
                    "unicode_version": EXPECTED_UNICODE_VERSION,
                }
            )
        else:  # pragma: no cover
            raise AssertionError(args.command)
    except EmojiSkinGuardrailError as error:
        print(f"error: {error}", file=sys.stderr)
        return 4
    except (EmojiSkinPackError, OSError, UnicodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
