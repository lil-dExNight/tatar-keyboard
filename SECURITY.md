# Security Policy

## Supported versions

Only the latest release is supported with security fixes. There is no
back-port line: the fix ships as the next point release and users update.

## Reporting a vulnerability

This is a keyboard: it processes every keystroke. If you suspect a
vulnerability, **do not open a public issue**.

- Preferred: GitHub **private vulnerability reporting** on this repository
  (Security tab → Advisories → *Report a vulnerability*).
- Otherwise: email the maintainer via the contact listed on the GitHub
  profile of the repository owner.

Confirmed reports are credited (or kept anonymous, your choice) in the
release notes after the fix ships.

## Severity language (keyboard-specific)

- **Critical** — any confirmed keystroke or user-data egress (network,
  inter-process leak to another app, backup channel outside the whitelist).
  Response: embargo, immediate point release, disclosure only after the fix
  is available.
- **High** — learned personal data (personal dictionary, learned pairs,
  learned emoji) exposed across a context boundary: readable by another app,
  written from password/`NO_PERSONALIZED_LEARNING` fields, or restored onto a
  device it did not originate from.
- **Normal** — local robustness issues with no data exposure (parser
  crashes on corrupt files, denial of service against the IME itself).

## Disclosure

Coordinated disclosure, ~90 days from report to publication at most, shorter
when a fix is ready earlier. Critical egress bugs stay embargoed until the
point release carrying the fix is out.

## Re-audit ritual

This project keeps its security posture executable rather than aspirational:

- **Every release** runs the gate suite: `scripts/release_check.sh`
  (artifact gates include permissions exactly `[VIBRATE]`, a single-signer
  release certificate, and asset pins) plus `scripts/check-no-internet.sh`
  (two levels: manifest and the built APK) plus the security contract tests
  in `./gradlew test` (`PersonalLearningGatesTest`,
  `EditorTextCachePrivacySourceContractTest`,
  `BackupWhitelistSourceContractTest`, the `*ValidatorTest` fail-closed
  parser suites, the `*PrivacyTest` package suites) — plus a human delta
  review of changes to the input pipeline, personal stores, binary parsers,
  the manifest and the build.
- **Full audit pass** once per calendar half-year, or immediately when
  Android platform behavior changes under the app (backup/restore rules,
  touch filtering, IME windowing). Past passes: `docs/FINAL-AUDIT-2026-09-02.md`,
  `docs/AUDIT-2026-09-24.md`, `docs/SECURITY-AUDIT-2026-09-25.md`.
- **Event-driven watch**: Android Security Bulletins are checked for CVEs in
  the inputmethod/LatinIME lineage this tree descends from; a relevant CVE
  triggers an out-of-cycle review.

The app holds no `INTERNET` permission by design; the offline claim is a
build-time gate, not a promise.
