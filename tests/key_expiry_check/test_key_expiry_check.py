#!/usr/bin/env python3
"""Tests for scripts/key_expiry_check.py.

The expiry math is checked on synthetic keyrings built with GnuPG in a temp home (a
never-expiring key passes, an expired or soon-expiring key fails, and a subkey expiry
governs when the trusted id names the subkey). Historical exceptions are tested with real
old, post-expiry, and invalid detached signatures plus missing evidence, all injected
without network access. Fixture metadata also covers missing keys and empty trust lists;
the real-tree structural test uses a historical clock to remain offline. The synthetic-key
tests SKIP when gpg cannot generate keys on the host.
"""

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KEYRING = ROOT / "gradle" / "verification-keyring.gpg"
METADATA = ROOT / "gradle" / "verification-metadata.xml"
SCRIPT = ROOT / "scripts" / "key_expiry_check.py"

SPEC = importlib.util.spec_from_file_location("key_expiry_check", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
KEY_CHECK = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = KEY_CHECK
SPEC.loader.exec_module(KEY_CHECK)

MINIMAL_METADATA = """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
      <trusted-keys>{trusted}
      </trusted-keys>
   </configuration>
   <components>{components}
   </components>
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
    return MINIMAL_METADATA.format(trusted=entries, components="")


def proof_metadata_for(key_id: str, sha256: str) -> str:
    """One globally trusted key with an exact PGP and SHA-256 artifact rule."""
    trusted = f'\n         <trusted-key id="{key_id}"/>'
    components = f'''
      <component group="androidx.test" name="proof" version="1.0">
         <artifact name="proof-1.0.jar">
            <pgp value="{key_id}"/>
            <sha256 value="{sha256}"/>
         </artifact>
      </component>'''
    return MINIMAL_METADATA.format(trusted=trusted, components=components)


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

    def quick_generate(self, user: str, days: str) -> tuple[str, int, str]:
        """One never-expiring primary plus one signing subkey with the given relative
        expiry; returns (primary fingerprint, subkey creation epoch, subkey fingerprint).
        GnuPG 2.4 reads a
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

    def prepare_proof(self, user: str) -> tuple[str, int, str, Path]:
        """Create a one-day signing subkey and one artifact to sign."""
        primary, created, subkey = self.quick_generate(user, "1d")
        artifact = Path(self.home.name) / "proof-1.0.jar"
        artifact.write_bytes(b"signed artifact\n")
        return primary, created, subkey, artifact

    def sign_artifact(self, artifact: Path, subkey: str, signed_at: int) -> None:
        """Create an armored detached signature at a controlled signing time."""
        result = subprocess.run(
            ["gpg", *self.gnupg_home, "--batch", "--pinentry-mode", "loopback",
             "--passphrase", "", "--faked-system-time", f"{signed_at}!",
             "--local-user", f"{subkey}!", "--armor", "--detach-sign",
             "--output", f"{artifact}.asc", str(artifact)],
            capture_output=True, text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def run_with_proof_sources(
        self, metadata: Path, now: int, subkey: str, sources: dict[str, bytes]
    ) -> tuple[int, str, str]:
        """Run in-process with a synthetic exception and no network access."""
        repository = "https://proof.invalid/maven2"
        policy = KEY_CHECK.HistoricalKeyPolicy(
            publisher_key_url="https://proof.invalid/linux_signing_key.pub",
            repository_url=repository,
        )

        def fetch(url: str) -> bytes:
            if url not in sources:
                raise OSError(f"no fixture for {url}")
            return sources[url]

        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = KEY_CHECK.main(
                ["--keyring", str(self.keyring), "--metadata", str(metadata),
                 "--now", str(now)],
                historical_policies={subkey: policy},
                fetch_url=fetch,
            )
        return code, stdout.getvalue(), stderr.getvalue()

    def run_signed_proof(
        self, artifact: Path, created: int, subkey: str
    ) -> tuple[int, str, str]:
        """Check the fixture's current bytes and detached signature as source evidence."""
        artifact_bytes = artifact.read_bytes()
        metadata = Path(self.home.name) / "meta.xml"
        metadata.write_text(
            proof_metadata_for(subkey, hashlib.sha256(artifact_bytes).hexdigest()),
            encoding="utf-8",
        )
        url = "https://proof.invalid/maven2/androidx/test/proof/1.0/proof-1.0.jar"
        sources = {
            "https://proof.invalid/linux_signing_key.pub": self.keyring.read_bytes(),
            url: artifact_bytes,
            f"{url}.asc": Path(f"{artifact}.asc").read_bytes(),
        }
        return self.run_with_proof_sources(
            metadata, created + 10 * 86400, subkey, sources
        )

    def test_expired_key_with_valid_pre_expiry_signature_passes(self) -> None:
        _, created, subkey, artifact = self.prepare_proof("old <old@example.com>")
        self.sign_artifact(artifact, subkey, created + 3600)
        code, out, err = self.run_signed_proof(artifact, created, subkey)
        self.assertEqual(0, code, err)
        self.assertIn("PROOF|OK", out)
        self.assertIn(f"KEY|EXPIRED_OK|{subkey}", out)

    def test_expired_key_with_post_expiry_signature_fails(self) -> None:
        primary, created, subkey, artifact = self.prepare_proof("new <new@example.com>")
        # Extend only the private key long enough to create a signature after the
        # original expiry. The exported public proof key retains its one-day expiry.
        extended = subprocess.run(
            ["gpg", *self.gnupg_home, "--batch", "--pinentry-mode", "loopback",
             "--passphrase", "", "--quick-set-expire", primary, "20d", subkey],
            capture_output=True, text=True,
        )
        self.assertEqual(0, extended.returncode, extended.stderr)
        self.sign_artifact(artifact, subkey, created + 5 * 86400)
        code, out, err = self.run_signed_proof(artifact, created, subkey)
        self.assertEqual(1, code, out)
        self.assertIn("outside key validity interval", err)
        self.assertIn("KEY|OVERALL|FAIL", out)

    def test_expired_key_with_invalid_signature_fails(self) -> None:
        _, created, subkey, artifact = self.prepare_proof("bad <bad@example.com>")
        self.sign_artifact(artifact, subkey, created + 3600)
        # Match the SHA-256 pin to the changed bytes so only PGP can detect tampering.
        artifact.write_bytes(b"tampered artifact\n")
        code, out, err = self.run_signed_proof(artifact, created, subkey)
        self.assertEqual(1, code, out)
        self.assertIn("invalid detached signature", err)
        self.assertIn("KEY|OVERALL|FAIL", out)

    def test_expired_key_without_signature_proof_fails(self) -> None:
        _, created, subkey = self.quick_generate("unproven <unproven@example.com>", "1d")
        metadata = Path(self.home.name) / "meta.xml"
        metadata.write_text(metadata_for(subkey), encoding="utf-8")
        code, out, err = self.run_with_proof_sources(
            metadata, created + 10 * 86400, subkey, sources={}
        )
        self.assertEqual(1, code, out)
        self.assertIn("no PGP plus SHA-256 artifact proof", err)
        self.assertIn("KEY|OVERALL|FAIL", out)


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
        # A historical clock keeps this structural test offline: no key is expired, so
        # the source-evidence downloader is not invoked. The production gate is run
        # separately against the real sources and current time.
        code, out, _ = run_gate(KEYRING, METADATA, now=0)
        self.assertIn(code, (0, 1), out)
        self.assertIn("KEY|OVERALL|", out)
        self.assertNotIn("KEY|MISSING", out)

    def test_warning_days_constant_is_the_documented_window(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("KEY_EXPIRY_WARNING_DAYS = 30", source)


if __name__ == "__main__":
    unittest.main()
