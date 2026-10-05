# Current state

This file is rewritten, not appended to; history lives in git.

## Release

The current release is **3.9.2** (versionCode 48). It ships:

- the Tatar bigram table retrained conv-heavier (the conversational stream counts tenfold):
  chain top-3 +1.31 pp on the held-out set — the table is rebuilt and re-pinned, so an update
  re-inflates it once on first use;
- the inline-autofill fix: the presentation specs now carry the canonical style handshake, so
  password managers render their chips in the strip (found and verified on the reference device
  with a probe autofill service);
- the fifth-row question closed by operator decision (alphabetical order final, `BRIEF.md`) with
  the study instrument removed (Developer screen, arm picker, session log, arm-B/C layouts).

The bundled dictionaries and emoji data are unchanged from 3.9.1. User-facing notes are in
`CHANGELOG.md` and `metadata/*/changelogs/48.txt`. The English GitHub Release notes with the
filled release record are in `dist/release-notes-3.9.2.md` on the packing machine.

The signed APK was packed on the tree of the release commit before that commit existed, so its
`META-INF/version-control-info.textproto` names the parent commit. To rebuild it byte for byte,
see "Reproducing a published APK" in `docs/PUBLISH-CHECKLIST.md`.

## State of `main`

`main` is the 3.9.2 release; nothing is unreleased. The `improvement` branch is merged and
closed out: its measurement foundation, quick-wins sprint, prediction/glide round and features
sprint shipped as 3.9.0 (see `CHANGELOG.md`). The measured rejections of the post-3.9.1 round
(Russian dictionary admission, Glot500 dictionary bonus, case-aware stem expansion,
pair-conditional glide rerank) are recorded in `research/prediction-engine.md` and
`research/glide-typing.md`.

Code-level notes on what 3.9.1 changed:

`main` is the 3.9.2 release; nothing is unreleased. The `improvement` branch is merged and
closed out: its measurement foundation, quick-wins sprint, prediction/glide round and features
sprint shipped as 3.9.0 (see `CHANGELOG.md`). The measured rejections of the post-3.9.1 round
(Russian dictionary admission, Glot500 dictionary bonus, case-aware stem expansion,
pair-conditional glide rerank) are recorded in `research/prediction-engine.md` and
`research/glide-typing.md`.

Also true of the current tree:

- The golden vectors of `GlideGoldenExportTest` (geometry with aliases, word-index digests, set
  identities, decodes) and the context `"ул китте\n"` of the suggestion exporter changed in
  3.8.0; re-export them when the parity suite on the other platform is next synced.
- Typo recovery as shipped: the Tatar engine runs edit classes #1 (long-press partner) and #4
  (single substitution); the Russian engine runs class #1 only.

Verified for 3.9.2:

- `release_check.sh --full`, then `--quick` and `check-no-internet.sh` on the signed APK, two
  byte-identical packs, and `text_hygiene_check.py`.
- On the reference device (POCO C71): the cold-start leg, median 367.6 ms over 5 runs
  (`build/device-perf-3.9.2-release/`).

## Open release steps

These are manual and have not been confirmed as done:

- the **GitHub Releases** through the web UI (the machine's gh token is read-only): 3.9.1 (tag
  `v3.9.1`, notes `dist/release-notes-3.9.1.md`, attach `dist/tatar-keyboard-3.9.1.apk` and
  `dist/release-check-3.9.1.txt`) and 3.9.2 (tag `v3.9.2`, notes `dist/release-notes-3.9.2.md`,
  attach `dist/tatar-keyboard-3.9.2.apk` and `dist/release-check-3.9.2.txt`);
- the **corpus.tatar letter**: ready in `dist/corpus-tatar-letter.md`, send to
  tatcorpus@gmail.com;
- the store uploads: 3.9.0 with `metadata/{en-US,ru-RU,tt}/changelogs/46.txt` (if still
  pending), 3.9.1 with `changelogs/47.txt`, 3.9.2 with `changelogs/48.txt`.

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
