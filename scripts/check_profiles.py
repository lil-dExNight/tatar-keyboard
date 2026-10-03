#!/usr/bin/env python3
"""Gate for the tracked baseline and startup text profiles.

Legs: (1) parse both tracked profiles in app/src/main/generated/baselineProfiles/ with a strict
parser of the profgen human-readable format — a line that does not parse fails, because a typo'd
rule silently becomes a dead rule; (2) resolve the class rules against the release APK: each rule
is translated through the R8 mapping of the same build (the tracked profile names pre-R8 classes)
and looked up in the class descriptors of the APK's classes*.dex, read by a minimal DEX parser
(header, string_ids, type_ids, class_defs) — the resolved ratio is gated against
RESOLVED_RATIO_FLOOR; (3) when the SDK cmdline-tools profgen binary is found, `profgen validate`
runs on both profiles as a bonus leg — its exit code is always 0, so stderr output is a failure.
Usage: check_profiles.py [--apk PATH] [--mapping PATH] [--profgen PATH]. Exit: 0 pass, 1 failure.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parents[1]
PROFILE_PATHS = (
    ROOT / "app/src/main/generated/baselineProfiles/baseline-prof.txt",
    ROOT / "app/src/main/generated/baselineProfiles/startup-prof.txt",
)
DEFAULT_APK = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
# The mapping must come from the same build as the APK; a stale one shifts the ratio.
DEFAULT_MAPPING = ROOT / "app/build/outputs/mapping/release/mapping.txt"

# Resolved-ratio floor for the class rules. R8 inlines or removes many profiled classes, so the
# ratio sits well below 1 by design; its downward trend is the staleness signal, not the value.
# The floor pins the current level with headroom for ordinary profile churn: a drop below it
# means the tracked profile stopped matching the shipped code.
RESOLVED_RATIO_FLOOR = 0.35

DEX_ENTRY_RE = re.compile(r"classes\d*\.dex")
WILDCARD_CHARS = "*?"


class ProfileError(Exception):
    """A profile line that does not parse, with its coordinates."""

    def __init__(self, line_no: int, column: int, message: str) -> None:
        super().__init__(message)
        self.line_no = line_no
        self.column = column
        self.message = message

    def format(self, source: str) -> str:
        return f"{source}:{self.line_no}:{self.column}: {self.message}"


class DexError(Exception):
    """A dex file or APK that does not match the DEX structures this reader relies on."""


@dataclass(frozen=True)
class Rule:
    """One parsed rule; a class rule has an empty method_name and must not carry flags."""

    flags: str
    class_descriptor: str
    method_name: str
    params: str
    return_type: str

    @property
    def is_class_rule(self) -> bool:
        return not self.method_name

    @property
    def is_fuzzy(self) -> bool:
        text = self.class_descriptor + self.method_name + self.params + self.return_type
        return any(c in text for c in WILDCARD_CHARS)


# ----------------------------------------------------------------------------------------------
# Profile parser: the profgen human-readable format, as written by the profile generator
# (profgen printExact): class rules `Lpkg/Class;`, method rules `[HSP]+Lpkg/Class;->name(P)R`.
# Like profgen, blank lines, `#` comments, lines starting with `[` (array class lines from
# Android S+) and rules with a `+` inline-cache suffix are skipped, and `*`/`?` wildcards are
# accepted syntax; unlike profgen, params and the return type are scanned as strict type
# descriptors, so a hand edit that breaks a signature fails instead of becoming a dead rule.


def _fail(line_no: int, column: int, message: str) -> None:
    raise ProfileError(line_no, column, message)


def _scan_object(text: str, i: int, line_no: int) -> int:
    """Scans `L<content>;` starting at i, returns the index after `;`."""
    if i >= len(text) or text[i] != "L":
        _fail(line_no, i + 1, f"expected a class descriptor starting with 'L', got {text[i:i+10]!r}")
    end = i + 1
    while end < len(text) and text[end] != ";":
        c = text[end]
        if c.isspace() or c in "()#<>":
            _fail(line_no, end + 1, f"illegal character {c!r} in a class descriptor")
        end += 1
    if end >= len(text):
        _fail(line_no, i + 1, "unterminated class descriptor, missing ';'")
    if end == i + 1:
        _fail(line_no, i + 1, "empty class descriptor")
    return end + 1


def _scan_type(text: str, i: int, line_no: int, allow_void: bool) -> int:
    """Scans one type descriptor (arrays, primitives, objects) at i; `V` only when allow_void."""
    start = i
    while i < len(text) and text[i] == "[":
        i += 1
    if i >= len(text):
        _fail(line_no, i + 1, "missing type descriptor")
    c = text[i]
    if c == "L":
        return _scan_object(text, i, line_no)
    if c in "ZBCSIJFD":
        return i + 1
    if c == "V" and allow_void and i == start:
        return i + 1
    _fail(line_no, i + 1, f"illegal type descriptor {c!r}")
    return i  # unreachable


def parse_rule(line: str, line_no: int) -> Rule | None:
    """Parses one profile line into a Rule; None for a line the format skips."""
    text = line.strip()
    if not text or text.startswith("#") or text.startswith("["):
        return None
    i = 0
    flags = ""
    while i < len(text) and text[i] in "HSP":
        flags += text[i]
        i += 1
    class_start = i
    i = _scan_object(text, i, line_no)
    class_descriptor = text[class_start:i]
    if i == len(text):
        if flags:
            _fail(line_no, 1, f"flags {flags!r} are not allowed on a class rule")
        return Rule("", class_descriptor, "", "", "")
    if not text.startswith("->", i):
        _fail(line_no, i + 1, "expected '->' or the end of the line after the class descriptor")
    i += 2
    name_end = text.find("(", i)
    if name_end < 0:
        _fail(line_no, i + 1, "missing '(' after the method name")
    method_name = text[i:name_end]
    if not method_name:
        _fail(line_no, i + 1, "empty method name")
    if any(c.isspace() or c in ";)" for c in method_name):
        _fail(line_no, i + 1, f"illegal character in the method name {method_name!r}")
    i = name_end + 1
    params_start = i
    while i < len(text) and text[i] != ")":
        i = _scan_type(text, i, line_no, allow_void=False)
    params = text[params_start:i]
    if i >= len(text):
        _fail(line_no, params_start + 1, "missing ')' after the parameters")
    i += 1
    return_start = i
    i = _scan_type(text, i, line_no, allow_void=True)
    return_type = text[return_start:i]
    rest = text[i:].strip()
    if rest.startswith("+"):
        return None
    if rest:
        _fail(line_no, i + 1, f"unexpected text {rest!r} after the rule")
    if not flags:
        _fail(line_no, 1, "a method rule must carry at least one of the H, S, P flags")
    return Rule(flags, class_descriptor, method_name, params, return_type)


class ProfileErrors(Exception):
    """Several parse errors of one profile file."""

    def __init__(self, errors: list[ProfileError]) -> None:
        super().__init__(f"{len(errors)} parse errors")
        self.errors = errors


def parse_profile(path: Path) -> list[Rule]:
    """Parses a tracked profile; every error of the file is collected before raising."""
    errors = []
    rules = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        try:
            rule = parse_rule(line, line_no)
        except ProfileError as error:
            errors.append(error)
            continue
        if rule is not None:
            rules.append(rule)
    if len(errors) == 1:
        raise errors[0]
    if errors:
        raise ProfileErrors(errors)
    return rules


# ----------------------------------------------------------------------------------------------
# Minimal DEX reader. Only the structures needed to list the class descriptors, all u32 fields
# little-endian: the header (magic "dex\n0NN\0", file_size at 0x20, header_size 0x70 at 0x24,
# endian tag 0x12345678 at 0x28) points to string_ids (size at 0x38, offset at 0x3C; each item
# is a u32 offset of uleb128 utf16 length followed by MUTF-8 bytes terminated by NUL), type_ids
# (size at 0x40, offset at 0x44; each item is a u32 descriptor string index) and class_defs
# (size at 0x60, offset at 0x64; each item is 32 B, the type_ids class index at +0).


def _u32(data: bytes, offset: int, what: str) -> int:
    if offset + 4 > len(data):
        raise DexError(f"{what} at {offset:#x} is outside the file")
    return int.from_bytes(data[offset : offset + 4], "little")


def _uleb128_size(data: bytes, offset: int) -> int:
    """Skips the uleb128 utf16 length of a string item, returns the offset of the MUTF-8 bytes."""
    for shift in range(5):
        if offset + shift >= len(data):
            raise DexError(f"uleb128 at {offset:#x} runs outside the file")
        if not data[offset + shift] & 0x80:
            return offset + shift + 1
    raise DexError(f"uleb128 at {offset:#x} is longer than 5 bytes")


def dex_class_descriptors(data: bytes) -> set[str]:
    if len(data) < 0x70 or data[:4] != b"dex\n" or data[4] != ord("0") or data[5] != ord("3") \
            or not data[6:7].isdigit() or data[7] != 0:
        raise DexError("bad magic, not a dex file")
    if _u32(data, 0x24, "header_size") != 0x70:
        raise DexError("unexpected header_size, not a dex file")
    if _u32(data, 0x28, "endian_tag") != 0x12345678:
        raise DexError("unexpected endian tag")
    if _u32(data, 0x20, "file_size") > len(data):
        raise DexError("file_size is larger than the file, truncated dex")
    string_ids_size, string_ids_off = _u32(data, 0x38, "string_ids_size"), _u32(data, 0x3C, "string_ids_off")
    type_ids_size, type_ids_off = _u32(data, 0x40, "type_ids_size"), _u32(data, 0x44, "type_ids_off")
    class_defs_size, class_defs_off = _u32(data, 0x60, "class_defs_size"), _u32(data, 0x64, "class_defs_off")
    for what, off, size, item in (
        ("string_ids", string_ids_off, string_ids_size, 4),
        ("type_ids", type_ids_off, type_ids_size, 4),
        ("class_defs", class_defs_off, class_defs_size, 32),
    ):
        if size and (off < 0x70 or off + size * item > len(data)):
            raise DexError(f"{what} region is outside the file")
    strings = []
    for i in range(string_ids_size):
        start = _uleb128_size(data, _u32(data, string_ids_off + 4 * i, "string offset"))
        try:
            end = data.index(0, start)
        except ValueError:
            raise DexError(f"string {i} is not NUL-terminated") from None
        strings.append(data[start:end].decode("utf-8", "replace"))
    types = [_u32(data, type_ids_off + 4 * i, "type_ids item") for i in range(type_ids_size)]
    descriptors = set()
    for i in range(class_defs_size):
        class_idx = _u32(data, class_defs_off + 32 * i, "class_def class_idx")
        if class_idx >= type_ids_size:
            raise DexError(f"class_def {i} has a class_idx outside type_ids")
        if types[class_idx] >= string_ids_size:
            raise DexError(f"type_ids item {class_idx} has a descriptor index outside string_ids")
        descriptors.add(strings[types[class_idx]])
    return descriptors


def apk_class_descriptors(apk_path: Path) -> tuple[set[str], int]:
    """Union of the class descriptors of every classes*.dex entry of the APK, and their count."""
    try:
        with zipfile.ZipFile(apk_path) as apk:
            names = sorted(n for n in apk.namelist() if DEX_ENTRY_RE.fullmatch(n))
            if not names:
                raise DexError(f"no classes*.dex entries in {apk_path}")
            descriptors: set[str] = set()
            for name in names:
                try:
                    descriptors |= dex_class_descriptors(apk.read(name))
                except DexError as error:
                    raise DexError(f"{apk_path}!{name}: {error}") from None
    except zipfile.BadZipFile:
        raise DexError(f"{apk_path} is not a zip file") from None
    return descriptors, len(names)


# ----------------------------------------------------------------------------------------------
# R8 mapping and resolution.


def load_r8_mapping(path: Path) -> dict[str, str]:
    """Class-descriptor translation from mapping.txt; only the class lines (column 0) are read.

    A class line is `original.binary.Name -> obfuscated:`; absent classes keep their name,
    removed ones map to a `R8$$REMOVED$$CLASS$$N` target that never resolves, as expected.
    """
    mapping = {}
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw or raw[0] in " \t#":
            continue
        left, sep, right = raw.partition(" -> ")
        if not sep or not right.endswith(":"):
            raise DexError(f"{path}:{line_no}: malformed R8 mapping class line: {raw!r}")
        mapping[f"L{left.replace('.', '/')};"] = f"L{right[:-1].replace('.', '/')};"
    if not mapping:
        raise DexError(f"{path}: no class lines found, not an R8 mapping file")
    return mapping


def resolved_ratio(class_rules: set[str], mapping: dict[str, str], descriptors: set[str]) -> tuple[int, float]:
    """Translated class rules found in the dex: the count and the ratio over all rules."""
    if not class_rules:
        return 0, 0.0
    resolved = sum(1 for rule in class_rules if mapping.get(rule, rule) in descriptors)
    return resolved, resolved / len(class_rules)


# ----------------------------------------------------------------------------------------------
# profgen bonus leg: `profgen validate` always exits 0, so stderr output is the failure signal.


def find_profgen(explicit: str | None) -> Path | None:
    """The cmdline-tools profgen binary; None disables the leg (also with an empty --profgen)."""
    if explicit is not None:
        candidate = Path(explicit)
        return candidate if explicit and os.access(candidate, os.X_OK) else None
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    root = Path(sdk) if sdk else Path.home() / "Android/Sdk"
    candidate = root / "cmdline-tools/latest/bin/profgen"
    return candidate if os.access(candidate, os.X_OK) else None


def profgen_validate(profgen: Path | None, profiles: Sequence[Path]) -> tuple[str, str]:
    """Runs `profgen validate` per profile; returns the RESULT status and detail."""
    if profgen is None:
        return "SKIP", "profgen binary not found, the pure-python parse is the gate"
    for profile in profiles:
        try:
            proc = subprocess.run([str(profgen), "validate", str(profile)],
                                  capture_output=True, text=True, timeout=120)
        except (OSError, subprocess.TimeoutExpired) as error:
            return "FAIL", f"profgen validate could not run on {profile.name}: {error}"
        if proc.returncode != 0 or proc.stderr.strip():
            detail = proc.stderr.strip().splitlines()[0] if proc.stderr.strip() else \
                f"exit code {proc.returncode}"
            return "FAIL", f"profgen validate rejected {profile.name}: {detail}"
    return "PASS", "profgen validate accepted both profiles"


# ----------------------------------------------------------------------------------------------
# Legs and output.


def report(status: str, name: str, detail: str) -> None:
    print(f"RESULT|{status}|{name}|{detail}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK,
                        help="release APK under test (default: %(default)s)")
    parser.add_argument("--mapping", type=Path, default=DEFAULT_MAPPING,
                        help="R8 mapping.txt of the same build (default: %(default)s)")
    parser.add_argument("--profgen", default=None, metavar="PATH",
                        help="profgen binary; an empty value disables the leg")
    args = parser.parse_args(argv)

    failures = 0
    rules: list[Rule] = []
    for path in PROFILE_PATHS:
        if not path.is_file():
            report("FAIL", "parse", f"{path.name}: file not found ({path})")
            failures += 1
            continue
        try:
            parsed = parse_profile(path)
        except ProfileErrors as multi:
            for error in multi.errors:
                report("FAIL", "parse", error.format(path.name))
            failures += 1
            continue
        except ProfileError as error:
            report("FAIL", "parse", error.format(path.name))
            failures += 1
            continue
        classes = sum(1 for rule in parsed if rule.is_class_rule)
        report("PASS", "parse",
               f"{path.name}: {len(parsed)} rules ({classes} class, {len(parsed) - classes} method)")
        rules.extend(parsed)
    if failures:
        return 1

    try:
        descriptors, dex_count = apk_class_descriptors(args.apk)
    except (DexError, OSError) as error:
        report("FAIL", "dex", str(error))
        return 1
    if not descriptors:
        report("FAIL", "dex", f"{args.apk}: no class descriptors in the dex files")
        return 1
    report("PASS", "dex", f"{args.apk.name}: {dex_count} dex files, {len(descriptors)} class descriptors")

    profgen = find_profgen(args.profgen)
    status, detail = profgen_validate(profgen, [p for p in PROFILE_PATHS if p.is_file()])
    report(status, "profgen", detail)
    if status == "FAIL":
        failures += 1

    class_rules = {rule.class_descriptor for rule in rules if rule.is_class_rule}
    fuzzy = sorted(rule.class_descriptor for rule in rules if rule.is_class_rule and rule.is_fuzzy)
    exact = class_rules - set(fuzzy)
    if not exact:
        report("FAIL", "resolved", "no class rules in the tracked profiles, nothing to resolve")
        return 1
    try:
        mapping = load_r8_mapping(args.mapping)
    except (DexError, OSError) as error:
        report("FAIL", "mapping", f"{error} (the mapping must come from the same build as the APK)")
        return 1
    resolved, ratio = resolved_ratio(exact, mapping, descriptors)
    extra = f", {len(fuzzy)} wildcard rules excluded" if fuzzy else ""
    detail = (f"{resolved}/{len(exact)} class rules resolve in the APK dex "
              f"(ratio {ratio:.1%}, floor {RESOLVED_RATIO_FLOOR:.1%}){extra}")
    if ratio < RESOLVED_RATIO_FLOOR:
        report("FAIL", "resolved", detail + " — the tracked profile went stale or lost classes")
        failures += 1
    else:
        report("PASS", "resolved", detail)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
