#!/usr/bin/env python3
"""Gate over the expiry dates of the trusted dependency-verification keys.

The trusted keys of gradle/verification-metadata.xml must be present in
gradle/verification-keyring.gpg with a usable signature date: a key whose
matched primary key or signing subkey is expired, or expires within
KEY_EXPIRY_WARNING_DAYS days, fails the gate, because a cold-cache build would
reject every artifact signed by that key and the repo rule is to investigate,
not re-pin.

Legs: (1) parse the trusted-key ids from the metadata XML; (2) read the keyring
through `gpg --show-keys --with-colons` (gpg ships on the runners, and the
keyring is committed, so no keyserver is contacted); (3) for every trusted id,
find the keyring entry whose primary or subkey fingerprint equals the id and
resolve its effective expiry (a subkey never expires later than its primary, so
the earliest of the two governs). A trusted id missing from the keyring is an
error, not a pass.

Usage: key_expiry_check.py [--keyring PATH] [--metadata PATH] [--now EPOCH].
Exit: 0 every trusted key valid, 1 an expired or expiring key, 2 a structural
error (missing key, unreadable keyring, unparsable metadata, no gpg).
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_KEYRING = ROOT / "gradle" / "verification-keyring.gpg"
DEFAULT_METADATA = ROOT / "gradle" / "verification-metadata.xml"

# A key expiring within this many days fails the gate, so it is rotated before
# it breaks the cold-cache build, not after.
KEY_EXPIRY_WARNING_DAYS = 30

TRUSTED_KEY_RE = re.compile(r"<trusted-key\s+id=\"([0-9A-Fa-f]+)\"")


@dataclass
class KeyEntry:
    """One keyring record: a primary key or a subkey, from the colon format."""

    kind: str      # "pub" or "sub"
    fingerprint: str
    keyid: str
    expiry: int    # seconds since epoch; 0 when the key never expires
    usage: str     # capability letters, e.g. "sca" or "e"; "" when unknown

def parse_keyring(keyring: Path, gpg: str) -> list[KeyEntry]:
    """Parse `gpg --show-keys --with-colons` into entries.

    The colon format emits a pub or sub line followed by an fpr line whose
    field 10 holds the full fingerprint; the primary is grouped with its
    subkeys by order, which resolve_expiry() relies on.
    """
    result = subprocess.run(
        [gpg, "--show-keys", "--with-colons", str(keyring)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        sys.exit(f"ERROR: gpg cannot read {keyring}: {result.stderr.strip()}")
    entries: list[KeyEntry] = []
    pending: tuple[str, str, int, str] | None = None  # (kind, keyid, expiry, usage)
    for line in result.stdout.splitlines():
        fields = line.split(":")
        if fields[0] in ("pub", "sub"):
            # fields: type, validity, bits, algo, keyid, creation, expiry, ...,
            # usage (field 12, 1-based)
            expiry = int(fields[6]) if len(fields) > 6 and fields[6] else 0
            usage = fields[11] if len(fields) > 11 else ""
            pending = (fields[0], fields[4], expiry, usage)
        elif fields[0] == "fpr" and pending is not None and len(fields) > 9:
            entries.append(
                KeyEntry(pending[0], fields[9], pending[1], pending[2], pending[3])
            )
            pending = None
    if not entries:
        sys.exit(f"ERROR: no keys parsed from {keyring}")
    return entries


def resolve_expiry(trusted_id: str, entries: list[KeyEntry]) -> int | None:
    """Effective expiry of a trusted key, in seconds since epoch.

    A trusted-key id is the full v4 fingerprint of either the primary or a
    subkey, and the metadata names the exact key Gradle trusts: the effective
    expiry is the expiry of the matched entry, clamped by its primary (a
    subkey never outlives its primary). Sibling subkeys of the same primary —
    including long-expired historical ones — do not affect the verdict, and
    an expired encryption subkey never matters.
    Returns None when the id matches no entry.
    """
    matched = next(
        (e for e in entries if e.fingerprint.upper() == trusted_id.upper()), None
    )
    if matched is None:
        return None
    if matched.kind == "sub":
        index = entries.index(matched)
        primary = max(i for i, e in enumerate(entries[:index]) if e.kind == "pub")
        primary_expiry = entries[primary].expiry
        if primary_expiry and (matched.expiry == 0 or matched.expiry > primary_expiry):
            return primary_expiry
    return matched.expiry


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--keyring", type=Path, default=DEFAULT_KEYRING)
    parser.add_argument("--metadata", type=Path, default=DEFAULT_METADATA)
    parser.add_argument(
        "--now", type=int, default=None,
        help="epoch seconds the check runs at (defaults to the wall clock)",
    )
    args = parser.parse_args(argv)

    metadata = args.metadata.read_text(encoding="utf-8")
    trusted = TRUSTED_KEY_RE.findall(metadata)
    if not trusted:
        print(
            f"ERROR: no <trusted-key> element in {args.metadata}", file=sys.stderr
        )
        return 2

    gpg = "gpg"
    if subprocess.run([gpg, "--version"], capture_output=True).returncode != 0:
        print("ERROR: gpg is not installed", file=sys.stderr)
        return 2
    entries = parse_keyring(args.keyring, gpg)

    now = args.now if args.now is not None else int(time.time())
    horizon = now + KEY_EXPIRY_WARNING_DAYS * 86400

    failures: list[str] = []
    for trusted_id in trusted:
        expiry = resolve_expiry(trusted_id, entries)
        if expiry is None:
            print(f"KEY|MISSING|{trusted_id}|not in {args.keyring}")
            failures.append(f"{trusted_id} missing from the keyring")
            continue
        if expiry == 0:
            print(f"KEY|OK|{trusted_id}|never expires")
        elif expiry <= now:
            date = time.strftime("%Y-%m-%d", time.gmtime(expiry))
            print(f"KEY|EXPIRED|{trusted_id}|expired {date}")
            failures.append(f"{trusted_id} expired {date}")
        elif expiry <= horizon:
            date = time.strftime("%Y-%m-%d", time.gmtime(expiry))
            print(f"KEY|EXPIRING|{trusted_id}|expires {date}")
            failures.append(f"{trusted_id} expires {date}")
        else:
            date = time.strftime("%Y-%m-%d", time.gmtime(expiry))
            print(f"KEY|OK|{trusted_id}|valid until {date}")

    verdict = "FAIL" if failures else "PASS"
    print(
        f"KEY|OVERALL|{verdict}|keys={len(trusted)} "
        f"warning_days={KEY_EXPIRY_WARNING_DAYS}"
    )
    if failures:
        for failure in failures:
            print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
