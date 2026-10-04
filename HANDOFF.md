# Current state

This file is rewritten, not appended to; history lives in git.

## Release

The current release is **3.9.1** (versionCode 47). It ships the settings and onboarding UX
batch:

- a launcher tap on a set-up keyboard opens the settings directly (the wizard's done block
  shows only right after the wizard; explicit `am start -n` launches keep the full screen — the
  device scripts type into its try-it field);
- the fixed theme is named "Classic" (was "Tatar"), and picking a theme says the change lands
  the next time the keyboard opens;
- the privacy policy and the license sit on one "Legal information" screen; the data-sources
  screen is removed (the corpus attribution lives in `assets/dictionaries/NOTICE.txt`);
- the Developer screen and the fifth-row instrument exist in debuggable builds only: the root
  row, the layout arm and the session log are all gated on the debuggable flag;
- the settings back stack persists screen names, not enum ordinals.

The bundled dictionaries, bigram tables and emoji data are unchanged from 3.9.0, so an update
re-inflates nothing. User-facing notes are in `CHANGELOG.md` and `metadata/*/changelogs/47.txt`.
The English GitHub Release notes with the filled release record are in
`dist/release-notes-3.9.1.md` on the packing machine.

The signed APK was packed on the tree of the release commit before that commit existed, so its
`META-INF/version-control-info.textproto` names the parent commit. To rebuild it byte for byte,
see "Reproducing a published APK" in `docs/PUBLISH-CHECKLIST.md`.

## State of `main`

`main` carries the 3.9.1 release plus one unreleased change: the fifth-row question is closed
by operator decision (the alphabetical order `ә ө ү җ ң һ` is final, `BRIEF.md`) and the study
instrument is removed — the Developer screen, the arm picker, the session log and the arm-B/C
layout files are gone; the protocol text is listed in `docs/HISTORY.md`. The `improvement`
branch is merged and closed out: its measurement foundation, quick-wins sprint, prediction/glide
round and features sprint shipped as 3.9.0 (see `CHANGELOG.md`).

Code-level notes on what 3.9.1 changed:

- Setup: `SetupState.shouldForwardToSettings` gates the completed-setup forward, and
  `SetupActivity.openSettingsAndFinish` serves it and the done button.
- Settings: `Screen.LEGAL` replaces `Screen.DATA_SOURCES`; the legal screen opens the two
  documents in the browser. The removed screen's contract test became
  `LegalScreenSourceContractTest`; the NOTICE pins live in `DictionaryNoticeContractTest`.

Also true of the current tree:

- The golden vectors of `GlideGoldenExportTest` (geometry with aliases, word-index digests, set
  identities, decodes) and the context `"ул китте\n"` of the suggestion exporter changed in
  3.8.0; re-export them when the parity suite on the other platform is next synced.
- Typo recovery as shipped: the Tatar engine runs edit classes #1 (long-press partner) and #4
  (single substitution); the Russian engine runs class #1 only.

Verified for 3.9.1:

- `release_check.sh --full`, then `--quick` and `check-no-internet.sh` on the signed APK, two
  byte-identical packs, and `text_hygiene_check.py`.
- On the reference device (POCO C71), the release ritual (`build/device-perf-3.9.1-release/`):
  cold start median 343.1 ms, PSS within the 69 MB ceiling in all scenarios, frames p95 ≤
  12.3 ms with 0% janky, warm show median 69.1 ms, touch p95 4.5 ms, suggest round trip p95
  19.9 ms — every leg within budget.

## Open release steps

These are manual and have not been confirmed as done:

- the **3.9.1 GitHub Release** through the web UI: tag `v3.9.1`, title `Tatar Keyboard 3.9.1`,
  notes from `dist/release-notes-3.9.1.md`; attach `dist/tatar-keyboard-3.9.1.apk` and
  `dist/release-check-3.9.1.txt`;
- the store uploads: 3.9.0 with `metadata/{en-US,ru-RU,tt}/changelogs/46.txt` (if still
  pending) and 3.9.1 with `changelogs/47.txt`.

## Known risks and open items

See `docs/ROADMAP.md`: the improvement program built from `research/README.md`, and the
device checks that need a person or hardware not at hand (live Direct Boot, Telegram,
TalkBack by ear, tablet hardware). The glide context rerank decision is closed (threshold
confirmed).

## Where to look next

- `docs/README.md` — index of all documents.
- `docs/ROADMAP.md` — mandatory development plan, in order.
- `docs/ARCHITECTURE.md` — input path, suggestion engine, threads and stores.
- `AGENTS.md` — build, test and release commands, hard constraints.
