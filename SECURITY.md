# Security policy

## Supported versions

Only the latest release is supported with security fixes. There is no
back-port line: the fix ships as the next point release and users update.

## Reporting a vulnerability

This is a keyboard: it processes every keystroke. If you suspect a
vulnerability, **do not open a public issue**. Report it through GitHub
private vulnerability reporting on this repository (Security tab →
Advisories → *Report a vulnerability*). Otherwise, email the maintainer via
the contact listed on the GitHub profile of the repository owner.

Confirmed reports are credited (or kept anonymous, your choice) in the
release notes after the fix ships.

## Severity language (keyboard-specific)

- Critical: any confirmed keystroke or user-data egress (network,
  inter-process leak to another app, backup channel outside the whitelist).
  Response: embargo, an immediate point release, and disclosure only after
  the fix is available.
- High: learned personal data (personal dictionary, learned pairs, learned
  emoji) exposed across a context boundary: readable by another app, written
  from password/`NO_PERSONALIZED_LEARNING` fields, or restored through an OS
  backup or transfer channel onto a device it did not originate from. The
  backup file the user exports and imports through the settings screen is
  the intended path between devices, not such a channel.
- Normal: local robustness issues with no data exposure, such as parser
  crashes on corrupt files or denial of service against the IME itself.

## Disclosure

Disclosure is coordinated: at most ~90 days from report to publication,
shorter when a fix is ready earlier. Critical egress bugs stay embargoed
until the point release carrying the fix is out.

## Audit cadence

Every release runs the automated checks: `scripts/release_check.sh` (among
other things, the permission set is exactly `[VIBRATE]`, the APK has a
single signer with the release certificate, and the bundled dictionaries
and bigram tables match their pinned sizes and SHA-256) and
`scripts/check-no-internet.sh` (the source manifest and the built APK),
plus the privacy and parser test suites in `./gradlew test`. A maintainer
also reviews every change to the input pipeline, the personal dictionary,
the binary parsers, the manifest and the build.

A full audit happens once every six months, or immediately when Android
changes platform behavior the app depends on (backup and restore rules,
touch filtering, IME windowing). The threat model and the register of
accepted risks are in [docs/THREAT-MODEL.md](docs/THREAT-MODEL.md).

Android Security Bulletins are checked for CVEs in the inputmethod/LatinIME
code this app descends from; a relevant CVE triggers an extra review.

The app has no `INTERNET` permission; CI verifies this on the manifest and on the built APK.
