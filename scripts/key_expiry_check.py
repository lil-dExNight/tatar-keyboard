#!/usr/bin/env python3
"""Gate over the expiry dates of the trusted dependency-verification keys.

The trusted keys of gradle/verification-metadata.xml must be present in
gradle/verification-keyring.gpg. A key whose matched primary key or signing
subkey expires within KEY_EXPIRY_WARNING_DAYS days fails the gate. An expired
key also fails unless it has an explicit historical-artifact policy and every
artifact proof succeeds.

A historical proof is an exact artifact-level PGP rule plus one SHA-256 pin in
the metadata. The checker downloads that artifact and its detached signature
from the reviewed repository, checks the digest, and verifies the signature
with gpgv and the publisher's official full public key. The OpenPGP signature
creation time is signed data: it must be within the committed subkey's validity
interval. Thus a valid old signature is accepted, while a new signature made
after expiry, a bad signature, a changed artifact, or missing evidence fails.
Only the fingerprints in HISTORICAL_KEY_POLICIES are eligible for this
exception. Source downloads are used only for those exceptions; no keyserver is
contacted.

The keyring is read through `gpg --show-keys --with-colons`. A trusted id
missing from that keyring is an error, not a pass. For a subkey, the earliest of
its expiry and its primary key's expiry governs.

Usage: key_expiry_check.py [--keyring PATH] [--metadata PATH] [--now EPOCH].
Exit: 0 every key is valid or has only justified historical signatures, 1 an
expired, expiring, missing, or unproven key, 2 a structural error (unreadable
keyring, unparsable metadata, or no gpg).
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_KEYRING = ROOT / "gradle" / "verification-keyring.gpg"
DEFAULT_METADATA = ROOT / "gradle" / "verification-metadata.xml"

# A key expiring within this many days fails the gate, so it is rotated before
# it breaks the cold-cache build, not after.
KEY_EXPIRY_WARNING_DAYS = 30

METADATA_NAMESPACE = {"v": "https://schema.gradle.org/dependency-verification"}
GOOGLE_EXPIRED_SUBKEY = "A5F483CD733A4EBAEA378B2AE88979FB9B30ACF2"


@dataclass(frozen=True)
class HistoricalKeyPolicy:
    """Source locations for one reviewed, expired signing subkey."""

    publisher_key_url: str
    repository_url: str


HISTORICAL_KEY_POLICIES = {
    GOOGLE_EXPIRED_SUBKEY: HistoricalKeyPolicy(
        publisher_key_url="https://dl.google.com/linux/linux_signing_key.pub",
        repository_url="https://dl.google.com/dl/android/maven2",
    ),
}


@dataclass(frozen=True)
class KeyEntry:
    """One keyring record: a primary key or a subkey, from the colon format."""

    kind: str      # "pub" or "sub"
    fingerprint: str
    keyid: str
    creation: int  # seconds since epoch
    expiry: int    # seconds since epoch; 0 when the key never expires
    usage: str     # capability letters, e.g. "sca" or "e"; "" when unknown


@dataclass(frozen=True)
class ArtifactRule:
    """The artifact coordinates and verification pins from one metadata rule."""

    group: str
    name: str
    version: str
    file: str
    pgp_keys: tuple[str, ...]
    sha256_pins: tuple[str, ...]

    @property
    def coordinates(self) -> str:
        return f"{self.group}:{self.name}:{self.version}:{self.file}"


def download(url: str) -> bytes:
    """Download one proof input without consulting a keyserver."""
    request = urllib.request.Request(url, headers={"User-Agent": "key-expiry-check/1"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def parse_metadata(metadata: Path) -> tuple[list[str], list[ArtifactRule]]:
    """Read globally trusted key ids and exact artifact-level signature rules."""
    root = ET.parse(metadata).getroot()
    trusted = [
        element.attrib["id"].upper()
        for element in root.findall(
            "./v:configuration/v:trusted-keys/v:trusted-key", METADATA_NAMESPACE
        )
    ]
    artifacts: list[ArtifactRule] = []
    for component in root.findall("./v:components/v:component", METADATA_NAMESPACE):
        for artifact in component.findall("v:artifact", METADATA_NAMESPACE):
            pgp_keys = tuple(
                element.attrib["value"].upper()
                for element in artifact.findall("v:pgp", METADATA_NAMESPACE)
            )
            if not pgp_keys:
                continue
            artifacts.append(
                ArtifactRule(
                    group=component.attrib["group"],
                    name=component.attrib["name"],
                    version=component.attrib["version"],
                    file=artifact.attrib["name"],
                    pgp_keys=pgp_keys,
                    sha256_pins=tuple(
                        element.attrib["value"].lower()
                        for element in artifact.findall("v:sha256", METADATA_NAMESPACE)
                    ),
                )
            )
    return trusted, artifacts


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
    # (kind, keyid, creation, expiry, usage)
    pending: tuple[str, str, int, int, str] | None = None
    for line in result.stdout.splitlines():
        fields = line.split(":")
        if fields[0] in ("pub", "sub"):
            # fields: type, validity, bits, algo, keyid, creation, expiry, ...,
            # usage (field 12, 1-based)
            creation = int(fields[5]) if len(fields) > 5 and fields[5] else 0
            expiry = int(fields[6]) if len(fields) > 6 and fields[6] else 0
            usage = fields[11] if len(fields) > 11 else ""
            pending = (fields[0], fields[4], creation, expiry, usage)
        elif fields[0] == "fpr" and pending is not None and len(fields) > 9:
            entries.append(
                KeyEntry(
                    pending[0], fields[9], pending[1], pending[2], pending[3], pending[4]
                )
            )
            pending = None
    if not entries:
        sys.exit(f"ERROR: no keys parsed from {keyring}")
    return entries


def find_key(trusted_id: str, entries: list[KeyEntry]) -> KeyEntry | None:
    """Find the exact primary or subkey fingerprint named by the metadata."""
    return next(
        (entry for entry in entries if entry.fingerprint.upper() == trusted_id.upper()),
        None,
    )


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
    matched = find_key(trusted_id, entries)
    if matched is None:
        return None
    if matched.kind == "sub":
        index = entries.index(matched)
        primary = max(i for i, entry in enumerate(entries[:index]) if entry.kind == "pub")
        primary_expiry = entries[primary].expiry
        if primary_expiry and (matched.expiry == 0 or matched.expiry > primary_expiry):
            return primary_expiry
    return matched.expiry


def artifact_url(policy: HistoricalKeyPolicy, artifact: ArtifactRule) -> str:
    """Construct the canonical Maven URL for an exact artifact rule."""
    segments = [*artifact.group.split("."), artifact.name, artifact.version, artifact.file]
    path = "/".join(urllib.parse.quote(segment, safe="") for segment in segments)
    return f"{policy.repository_url.rstrip('/')}/{path}"


def verify_historical_key(
    trusted_id: str,
    entries: list[KeyEntry],
    expiry: int,
    artifacts: list[ArtifactRule],
    policy: HistoricalKeyPolicy,
    gpg: str,
    fetch_url: Callable[[str], bytes],
) -> list[str]:
    """Verify all exact artifact proofs for one explicitly allowed expired key."""
    matched = find_key(trusted_id, entries)
    if matched is None:
        return [f"{trusted_id} missing from the keyring"]
    proofs = [artifact for artifact in artifacts if trusted_id in artifact.pgp_keys]
    if not proofs:
        return [f"{trusted_id} has no PGP plus SHA-256 artifact proof"]
    missing_pins = [artifact.coordinates for artifact in proofs if len(artifact.sha256_pins) != 1]
    if missing_pins:
        return [
            f"{trusted_id} proof requires exactly one SHA-256 pin for {coordinates}"
            for coordinates in missing_pins
        ]
    if shutil.which("gpgv") is None:
        return [f"{trusted_id} proof requires gpgv"]

    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="key-expiry-proof-") as directory:
        temporary = Path(directory)
        publisher_key = temporary / "publisher-key.pub"
        publisher_keyring = temporary / "publisher-keyring.gpg"
        try:
            publisher_key.write_bytes(fetch_url(policy.publisher_key_url))
            publisher_entries = parse_keyring(publisher_key, gpg)
        except OSError as error:
            return [f"{trusted_id} cannot obtain publisher key evidence: {error}"]
        publisher_expiry = resolve_expiry(trusted_id, publisher_entries)
        if publisher_expiry != expiry:
            return [
                f"{trusted_id} publisher key expiry {publisher_expiry} does not match "
                f"committed expiry {expiry}"
            ]
        dearmor = subprocess.run(
            [gpg, "--batch", "--yes", "--dearmor", "--output",
             str(publisher_keyring), str(publisher_key)],
            capture_output=True, text=True,
        )
        if dearmor.returncode != 0:
            return [f"{trusted_id} cannot decode publisher key: {dearmor.stderr.strip()}"]

        artifact_path = temporary / "artifact"
        signature_path = temporary / "artifact.asc"
        for artifact in proofs:
            url = artifact_url(policy, artifact)
            try:
                artifact_bytes = fetch_url(url)
                signature_bytes = fetch_url(f"{url}.asc")
            except OSError as error:
                failures.append(f"{artifact.coordinates}: missing source proof: {error}")
                print(f"PROOF|FAIL|{trusted_id}|{artifact.coordinates}|source unavailable")
                continue
            digest = hashlib.sha256(artifact_bytes).hexdigest()
            if digest != artifact.sha256_pins[0]:
                failures.append(f"{artifact.coordinates}: source artifact does not match SHA-256 pin")
                print(f"PROOF|FAIL|{trusted_id}|{artifact.coordinates}|SHA-256 mismatch")
                continue
            artifact_path.write_bytes(artifact_bytes)
            signature_path.write_bytes(signature_bytes)
            result = subprocess.run(
                ["gpgv", "--keyring", str(publisher_keyring), "--status-fd", "1",
                 str(signature_path), str(artifact_path)],
                capture_output=True, text=True,
            )
            valid_signatures = [
                line.split()
                for line in result.stdout.splitlines()
                if line.startswith("[GNUPG:] VALIDSIG ")
            ]
            if result.returncode != 0 or not valid_signatures:
                detail = result.stderr.strip().splitlines()
                failures.append(
                    f"{artifact.coordinates}: invalid detached signature"
                    + (f" ({detail[-1]})" if detail else "")
                )
                print(f"PROOF|FAIL|{trusted_id}|{artifact.coordinates}|invalid signature")
                continue

            signature_failure: str | None = None
            for signature in valid_signatures:
                if len(signature) < 5 or signature[2].upper() != trusted_id:
                    signature_failure = "signer fingerprint does not match the PGP rule"
                    break
                try:
                    signed_at = int(signature[4])
                except ValueError:
                    signature_failure = "gpgv returned an invalid signature timestamp"
                    break
                if not matched.creation <= signed_at < expiry:
                    signature_failure = (
                        f"signature timestamp {signed_at} is outside key validity interval "
                        f"[{matched.creation}, {expiry})"
                    )
                    break
            if signature_failure is not None:
                failures.append(f"{artifact.coordinates}: {signature_failure}")
                print(f"PROOF|FAIL|{trusted_id}|{artifact.coordinates}|{signature_failure}")
                continue
            signed_at = int(valid_signatures[0][4])
            date = time.strftime("%Y-%m-%d", time.gmtime(signed_at))
            print(f"PROOF|OK|{trusted_id}|{artifact.coordinates}|signed {date}")
    return failures


def main(
    argv: list[str] | None = None,
    *,
    historical_policies: dict[str, HistoricalKeyPolicy] = HISTORICAL_KEY_POLICIES,
    fetch_url: Callable[[str], bytes] = download,
) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--keyring", type=Path, default=DEFAULT_KEYRING)
    parser.add_argument("--metadata", type=Path, default=DEFAULT_METADATA)
    parser.add_argument(
        "--now", type=int, default=None,
        help="epoch seconds the check runs at (defaults to the wall clock)",
    )
    args = parser.parse_args(argv)

    try:
        trusted, artifacts = parse_metadata(args.metadata)
    except (ET.ParseError, KeyError, OSError) as error:
        print(f"ERROR: cannot parse {args.metadata}: {error}", file=sys.stderr)
        return 2
    if not trusted:
        print(
            f"ERROR: no <trusted-key> element in {args.metadata}", file=sys.stderr
        )
        return 2

    gpg = shutil.which("gpg")
    if gpg is None:
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
            policy = historical_policies.get(trusted_id)
            if policy is None:
                print(f"KEY|EXPIRED|{trusted_id}|expired {date}")
                failures.append(f"{trusted_id} expired {date}")
                continue
            proof_failures = verify_historical_key(
                trusted_id, entries, expiry, artifacts, policy, gpg, fetch_url
            )
            if proof_failures:
                print(f"KEY|EXPIRED|{trusted_id}|expired {date}; proof failed")
                failures.extend(f"{trusted_id}: {failure}" for failure in proof_failures)
            else:
                print(
                    f"KEY|EXPIRED_OK|{trusted_id}|expired {date}; "
                    "all artifact signatures predate expiry"
                )
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
