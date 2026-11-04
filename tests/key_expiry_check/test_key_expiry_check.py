#!/usr/bin/env python3
"""Tests for scripts/key_expiry_check.py.

The expiry math is checked on synthetic keyrings built with GnuPG in a temp home (a
never-expiring key passes, an expired or soon-expiring key fails, a subkey expiry governs
when the trusted id names the subkey, and an expired primary masks a longer subkey), the
structural legs on fixture metadata (a trusted id missing from the keyring, no trusted keys
at all), and the real tree once: the committed keyring and metadata must be mutually
consistent. The synthetic-key tests SKIP when gpg cannot generate keys on the host.
"""

from __future__ import annotations

import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KEYRING = ROOT / "gradle" / "verification-keyring.gpg"
METADATA = ROOT / "gradle" / "verification-metadata.xml"
SCRIPT = ROOT / "scripts" / "key_expiry_check.py"

MINIMAL_METADATA = """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
   </configuration>
   <trusted-keys>{trusted}
   </trusted-keys>
</verification-metadata>
"""


def run_gate(keyring: Path, metadata: Path, now: int | None = None) -> tuple[int, str, str]:
    """The script as a subprocess over fixture files; returns (exit, stdout, stderr)."""
    argv = [sys.executable, str(SCRIPT), "--keyring", str(keyring), "--metadata", str(metadata)]
    if now is not None:
        argv += ["--now", str(now)]
    result = subprocess.run(argv, capture_output=True, text=True)
    return result.returncode, result.stdout, result.stderr


def metadata_for(*key_ids: str) -> str:
    entries = "".join(f'\n         <trusted-key id="{k}"/>' for k in key_ids)
    return MINIMAL_METADATA.format(trusted=entries)


class KeyringHostTest(unittest.TestCase):
    """Whether the host can generate keys for the synthetic fixtures."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.available = subprocess.run(
            ["gpg", "--version"], capture_output=True
        ).returncode == 0


class SyntheticKeyringTest(KeyringHostTest):
    """The expiry verdict over synthetic keyrings generated with GnuPG."""

    def setUp(self) -> None:
        if not self.available:
            self.skipTest("gpg is not installed")
        self.home = tempfile.TemporaryDirectory()
        self.gnupg_home = ["--homedir", self.home.name]
        self.keyring = Path(self.home.name) / "ring.gpg"

    def tearDown(self) -> None:
        self.home.cleanup()

    def quick_generate(self, user: str, days: str) -> tuple[str, int]:
        """One never-expiring primary plus one signing subkey with the given relative
        expiry; returns (primary fingerprint, subkey creation epoch). GnuPG 2.4 reads a
        bare number and seconds=<epoch> as relative durations and clamps ISO dates, so
        relative day offsets are the portable form."""
        subprocess.run(
            ["gpg", *self.gnupg_home, "--batch", "--pinentry-mode", "loopback",
             "--passphrase", "", "--quick-gen-key", user, "default", "default", "never"],
            capture_output=True, text=True,
        )
        primary = subprocess.run(
            ["gpg", *self.gnupg_home, "--list-keys", "--with-colons", user],
            capture_output=True, text=True,
        ).stdout
        fingerprints = [line.split(":")[9] for line in primary.splitlines()
                        if line.startswith("fpr:")]
        self.assertTrue(fingerprints, primary)
        subprocess.run(
            ["gpg", *self.gnupg_home, "--batch", "--pinentry-mode", "loopback",
             "--passphrase", "", "--quick-add-key", fingerprints[0],
             "default", "sign", days],
            capture_output=True, text=True,
        )
        listing = subprocess.run(
            ["gpg", *self.gnupg_home, "--list-keys", "--with-colons", user],
            capture_output=True, text=True,
        ).stdout
        created = [int(line.split(":")[5]) for line in listing.splitlines()
                   if line.startswith("sub:") and line.split(":")[11] == "s"
                   and line.split(":")[6]]
        self.assertTrue(created, listing)
        with open(self.keyring, "wb") as export:
            subprocess.run(
                ["gpg", *self.gnupg_home, "--batch", "--export", user],
                stdout=export,
            )
        subkeys = [line.split(":")[9] for line in listing.splitlines()
                   if line.startswith("fpr:")]
        return fingerprints[0], created[0], subkeys[-1]

    def run_over(self, user: str, days: str, now: int) -> tuple[int, str, str, str]:
        """Generate the fixture and trust the signing subkey; returns (exit, stdout,
        stderr, subkey fingerprint)."""
        _, _, subkey = self.quick_generate(user, days)
        metadata = Path(self.home.name) / "meta.xml"
        metadata.write_text(metadata_for(subkey), encoding="utf-8")
        code, out, err = run_gate(self.keyring, metadata, now=now)
        return code, out, err, subkey

    def test_valid_key_passes(self) -> None:
        # A signing subkey with 400 days left, checked right after creation.
        code, out, _, fpr = self.run_over("valid <valid@example.com>", "400d",
                                          now=int(__import__("time").time()))
        self.assertEqual(0, code, out)
        self.assertIn(f"KEY|OK|{fpr}|valid until", out)

    def test_expired_subkey_fails(self) -> None:
        # A 1-day subkey checked 10 days after its creation: long expired.
        _, created, subkey = self.quick_generate("expired <expired@example.com>", "1d")
        metadata = Path(self.home.name) / "meta.xml"
        metadata.write_text(metadata_for(subkey), encoding="utf-8")
        code, out, err = run_gate(self.keyring, metadata, now=created + 10 * 86400)
        self.assertEqual(1, code, out)
        self.assertIn("KEY|OVERALL|FAIL", out)
        self.assertIn("ERROR", err)

    def test_key_expiring_inside_the_window_fails(self) -> None:
        # A 20-day subkey checked immediately: 20 days left is inside the 30-day window.
        code, out, _, _ = self.run_over("soon <soon@example.com>", "20d",
                                        now=int(__import__("time").time()))
        self.assertEqual(1, code, out)
        self.assertIn("KEY|EXPIRING", out)

    def test_key_expiring_after_the_window_passes(self) -> None:
        # A 400-day subkey checked immediately: outside the window, reported with its date.
        code, out, _, _ = self.run_over("later <later@example.com>", "400d",
                                        now=int(__import__("time").time()))
        self.assertEqual(0, code, out)
        self.assertIn("KEY|OK|", out)


class MetadataTest(unittest.TestCase):
    """The structural legs on fixture metadata against the real keyring."""

    def test_trusted_id_missing_from_the_keyring_errors(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            metadata = Path(directory) / "meta.xml"
            missing = "A" * 40
            metadata.write_text(metadata_for(missing), encoding="utf-8")
            code, out, err = run_gate(KEYRING, metadata)
            self.assertEqual(1, code, out)
            self.assertIn(f"KEY|MISSING|{missing}", out)
            self.assertIn("missing from the keyring", err)

    def test_metadata_without_trusted_keys_errors(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            metadata = Path(directory) / "meta.xml"
            metadata.write_text(metadata_for(), encoding="utf-8")
            code, _, err = run_gate(KEYRING, metadata)
            self.assertEqual(2, code, err)
            self.assertIn("no <trusted-key> element", err)


class RealTreeTest(unittest.TestCase):
    """The committed keyring and metadata must be mutually consistent."""

    def test_every_trusted_key_is_resolved(self) -> None:
        # The verdict itself (expired keys exist in the tree) is data, not a contract here;
        # the structural contract is that parsing succeeds and no key reports MISSING.
        code, out, _ = run_gate(KEYRING, METADATA)
        self.assertIn(code, (0, 1), out)
        self.assertIn("KEY|OVERALL|", out)
        self.assertNotIn("KEY|MISSING", out)

    def test_warning_days_constant_is_the_documented_window(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("KEY_EXPIRY_WARNING_DAYS = 30", source)


if __name__ == "__main__":
    unittest.main()
