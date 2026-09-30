# Current state

This file is rewritten, not appended to; history lives in git.

## Release

The current release is **3.6.0** (versionCode 43), tagged `v3.6.0`. It reverts two changes from
3.3.0: the suggestion strip is back to three cells, and glide typing shows suggestions only when
the finger lifts (no live preview while the finger moves). Glide-triggered learning and the Tatar
bigram table with four successors per head stay; the strip reads three of them. The bundled
dictionaries, bigram tables and emoji data are unchanged from 3.5.0. User-facing notes are in
`CHANGELOG.md` and `metadata/*/changelogs/43.txt`.

## State of `main`

`main` is at the 3.6.0 release plus a text and repository cleanup started on 2026-09-30. It does
not change app behavior, apart from removing code that no shipped path used.

- Done (committed): comments in code, tests, scripts and build files rewritten in English, without
  history notes or links to documents; load-bearing data moved from `docs/archive/` to `data/`
  (dictionary review and acceptance tables read by the asset pipeline and tests); run evidence
  removed from `docs/`; dead code removed: typo-recovery edit classes #2 and #3 with the geometric
  neighbor table, constants and branches for unsupported layouts, the unshipped typo-recovery
  measurement tests, the class #5 generator in `scripts/typo_pack.py`, unused `InputAttributes`
  fields and `estimatedFileSize()` methods.
- In progress (not committed): documentation rewritten in English; closed reports, plans, audits
  and `docs/archive/` removed and listed in `docs/HISTORY.md`; store changelogs rewritten in plain
  user-facing language. Still to come: the writing rules in `AGENTS.md` and an automated text
  hygiene check in CI.

Typo recovery as shipped: the Tatar engine runs edit classes #1 (long-press partner) and #4
(single substitution); the Russian engine runs class #1 only.

## Open release steps for 3.6.0

These are manual and have not been confirmed as done:

- **GitHub Release** through the web UI (the `gh` CLI on the release machine is read-only). Attach
  the signed APK produced by `scripts/release_pack.sh`, not a rebuild. `dist/` is not present in
  this checkout; if the original file is lost, rerun the pack at tag `v3.6.0` (the pack is
  deterministic) and check that its SHA-256 matches the one recorded in
  `git show v3.6.0:HANDOFF.md`. Notes: the `[3.6.0]` section of `CHANGELOG.md`.
- **Store upload** with `metadata/{en-US,ru-RU,tt}/changelogs/43.txt`.
- **IzzyOnDroid** inclusion request or update note (see `docs/PUBLISH-CHECKLIST.md`).

## Known risks and open items

See `docs/BACKLOG.md`. The main ones: release-build frame time and memory are unmeasured (the
device script measures the debug build), several device tests wait on hardware or apps that are
not available (tablet, Telegram, live Direct Boot, TalkBack by ear), and the manifest memory
budget is blocked on the toolchain.

## Where to look next

- `docs/README.md` — index of all documents.
- `docs/ARCHITECTURE.md` — input path, suggestion engine, threads and stores.
- `AGENTS.md` — build, test and release commands, hard constraints.
