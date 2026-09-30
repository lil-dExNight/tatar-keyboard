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

`main` is the 3.6.0 release plus a text and repository cleanup (see `docs/HISTORY.md`) and a set
of input fixes not yet released. They are listed under `[Unreleased]` in `CHANGELOG.md`:

- Glide typing under Caps Lock commits the word in capitals (`ShiftStateGate.glideCasing`).
- A glide arms only where it can be decoded (`PointerTracker.setGlideAvailable`, set from the
  glide geometry in `LatinIME.updateKeyNeighbors`); in English and in fields without glide typing a
  slide is ordinary sliding key input again.
- Learned word pairs use the next-word context rule (`TatarWordUtils.extractWordBeforeTrailingWord`),
  so no pair is formed across a sentence end, a line break, a number or an emoji.
- The personal dictionary's "unknown word" proof reads the empty exact pass
  (`CompositePrefixComputer.lastExactMissPrefix`) instead of an empty strip, which typo recovery
  almost always filled for Tatar.
- Punctuation typed right after a tapped suggestion takes the auto-space's place ("сүз, ";
  `InputLogic.mAutoSpaceCursor`, `TatarWordUtils.swapsWithAutoSpace`).
- A line break is a sentence start for the suggestion strip.
- An undone autocorrection is not repeated for that word in the same field session
  (`SuggestionsController.refusedCorrections`).
- Glide typing follows the number row setting: `LatinIME.updateKeyNeighbors` keeps the glide
  geometry only while a rebuilt one has the same content (`GlideKeyGeometry.sameLayoutAs`), instead
  of caching it by `KeyboardId`, whose equality ignores the number row.

Verified: JVM tests, python tests, `lintRelease`, `check-no-internet.sh` on source and APKs,
release APK size and `text_hygiene_check.py`. On the `tt_suggest_a14` emulator: the smoke test
passes its typing and suggestion probes (its two emoji-panel probes fail on this AVD because the
recents tab holds a non-😀 emoji; the emoji is committed), a tapped suggestion followed by a comma
gives "татар дәүләт, " and a second comma "татар дәүләт,, ", an English slide types a letter, and a
Tatar glide still commits a word. The golden vectors exported by the exporter in
`app/src/test/.../golden/` (run with `GOLDEN_OUT` set) change for the context `"ул китте\n"`, now a
sentence start; re-export them when the parity suite on the other platform is next synced. Two
known bugs found on the emulator are listed in `docs/BACKLOG.md`.

Performance and build changes on the same `main`, with no change in behavior:

- Bundled file validation reads each file once into one array and walks it without per-word
  objects (`TdictValidator`, `TatBigrValidator`); `ValidatorAllocationTest` holds the ceiling.
- The activation right after a publication check reuses that validation while the file keeps its
  length and modification time (`AtomicDictionaryStore`, `AtomicBigramStore`), so a cold start
  validates each bundled file once instead of twice.
- The glide word index survives keyboard id changes that do not move keys (shift state, editor
  action), since the kept geometry instance keeps the decoder.
- An idle reload of the emoji suggestion table reuses the process's glyph verdicts
  (`EmojiGlyphVerdicts`) instead of probing the font again.
- Gradle: local build cache and a larger daemon heap; release builds pass `--no-build-cache`.
  JVM tests run in two forks on hosts with at least 8 CPUs.
- The baseline and startup profiles were edited by hand for the changed method signatures; they
  were not regenerated.

Verified for these and for the number row fix above: JVM tests (repeated full runs, host timing
tests inside their budgets), python tests, `lintRelease`, `check-no-internet.sh` on source and
APKs, release APK size, `text_hygiene_check.py`, two byte-identical
`clean assembleRelease --no-build-cache` builds (and the same bytes from the build cache). On the
emulator: the smoke test gives the same result as above, and a Tatar glide typed right after
turning the number row on decodes correctly.

Earlier cleanup of the same `main`:

- Comments in code, tests, scripts, resources and build files are in English and describe the
  code, not its history. Documents are rewritten in English (the root `README.md` stays
  bilingual); closed reports, plans, audits, `docs/archive/` and `research/*.md` are removed and
  listed in `docs/HISTORY.md`. Store changelogs are rewritten in plain language.
- Data read by the asset pipeline and tests moved from `docs/archive/` to `data/`; `docs/` holds
  Markdown only.
- Dead code removed: typo-recovery edit classes #2 and #3 with the geometric neighbor table,
  constants and branches for unsupported layouts, unshipped typo-recovery measurement tests, the
  class #5 generator in `scripts/typo_pack.py`, unused `InputAttributes` fields and
  `estimatedFileSize()` methods.
- The writing rules are in `AGENTS.md`; `scripts/text_hygiene_check.py` enforces them in CI.
- Known leftovers: a few data-file headers generated by pipeline templates (for example
  `app/src/test/resources/tt_eval_sentences.txt`, whose SHA-256 is pinned) still name removed
  documents; they change only when that data is regenerated.

Cleanup metrics (before → after; raw grep counts, so the remaining Cyrillic is quoted language
data and the remaining dates and doc paths are data or pinned file headers):

| Metric | Before | After |
|---|---|---|
| `docs/*.md` references in `app/src/main` | 312 | 0 |
| Dated notes in `app/src/main` | 202 | 0 |
| Tracked files under `docs/` | 651 | 9 |
| `*.md` files other than `README.md` with Cyrillic | 153 | 5 (language data) |
| "fail-closed" occurrences | 436 | 35 |
| `HANDOFF.md` / `CHANGELOG.md` / `docs/PUBLISH-CHECKLIST.md` lines | 2254 / 915 / 1939 | 75 / 428 / 120 |

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
