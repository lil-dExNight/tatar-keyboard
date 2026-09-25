# ROADMAP-P8 — phase 8 report: the accumulated release, the Apple-UX batches, the engineering backlog and the supply-chain round

Date: 2026-09-25. Plan: `docs/ROADMAP-P8-PLAN.md` (operator's answers recorded in its § Decision
register). Base: `main` @ `dd390316` = `origin/main`, tag `v3.0.2`.

Everything below is **uncommitted**; commits, the tag, the push and the publication are the
operator's (AGENTS.md). Each stage names what changed, what was checked and where the evidence is.

## Stage R — the accumulated wave released as 3.1.0 / versionCode 37

The tree held two layers: four commits past `v3.0.2` and 98 uncommitted files (the O1/O2
optimization wave and the two audit fix waves). They ship as one release, per the operator's
decision.

- **R1 gates on the pristine tree**: python 507 tests / 16 files / 0 failing;
  `./gradlew test --rerun-tasks` **1 633 tests, 174 suites, 0 failures**; `lintRelease` 0 errors
  (5 live warnings, 28 baselined); `rebuild_assets.py --check --allow-known-drift` → `ok: true`.
  The two JVM counts in the older HANDOFF entries (1 633 and 1 588) belong to different trees;
  1 633 is this tree's number.
- **R3**: `app/build.gradle` 36/3.0.2 → **37/3.1.0**; `CHANGELOG.md` gained a `[3.1.0]` section;
  store changelogs `metadata/{en-US,ru-RU,tt}/changelogs/37.txt` (352 / 434 / 367 B, all ≤ 500).
- **R4**: `release_pack.sh` run twice → byte-identical APK; `check-no-internet.sh` both levels OK;
  `release_check.sh --full` **OVERALL PASS 16/16**; `docs/APK-AUDIT-3.1.0.md` written with the
  per-entry CRC32 comparison against 3.0.2; `docs/PUBLISH-CHECKLIST.md` retargeted.
- **R5 (tag, push, publish)** — the operator's.

Because stages A–D landed after the first pack, the artifact was rebuilt at the end of the phase
and the changelog extended: the shipped `dist/tatar-keyboard-3.1.0.apk` contains everything in
this report. Final numbers: **1 694 282 B**, SHA-256
`f847e53e8635fa99354ba531fddb2032cd20dca1e4f861bc910fbda98f85b98e`, −152 282 B (−8.2 %) vs 3.0.2,
two packs byte-identical.

## Stage A — Apple-UX batch 1

| Item | What changed | Evidence |
|---|---|---|
| W1 | `ios_key_shadow` `#40000000` → `#4D000000` (light) | `AppleUxBatchOneContractTest`; the dark 0.70 value pinned unchanged |
| W2 | `ios_key_functional` `#B3B7C0` → `#ABB1BA` | same pin; visible in `01-keyboard.png` |
| W3 | `config_language_on_spacebar_final_alpha` 128 → 255 | same pin; the spacebar reads «татар» at full opacity in every screenshot |
| W4 | strip separators inset to 22 %…78 %, pressed cell an inset rounded rect (3dp / 5dp), text 17 → 18dp | `SuggestionStripDecorationContractTest` (including "no allocation inside `onDraw`"); `05-shift-down.png` shows the rounded highlight on cell 1 |
| W6 | `performHapticFeedback` no longer overrides the system haptics switch below API 29 | `AppleUxBatchOneContractTest` asserts the override constant is absent from the whole file |
| M1 | three shift states: new `sym_keyboard_shift_on.xml` (filled arrow, no bar), `NAME_SHIFT_KEY_ON`, the one-shot case switched to `stickyOn`, new `ios_key_sticky_on` (#FFFFFF / #6B6B6B) | `09-shift-on.png` — white key, filled barless arrow, uppercase letters |

TalkBack is unaffected: the shift announcement comes from `KeyDescriptionMapper` by key code, not
from the icon.

## Stage B — all seven items, authorized by the operator

| Item | What changed | Evidence |
|---|---|---|
| B1 (M5) | `SettingsHostActivity` slides + fades the content column 24dp / 200 ms on push and pop; still when the system animator scale is 0 | `AppleUxStageBContractTest` (direction, duration, scale, and "a rebuild without navigation animates nothing") |
| B2 (M4) | new `actionKeyTextColor` attr → `KeyVisualAttributes` → `KeyDrawParams`; `Key.BACKGROUND_TYPE_ACTION` declared; `selectTextColor` answers the action colour BEFORE the functional one; `state_active` = `app_accent`; the icon is tinted by a cached `PorterDuffColorFilter`; `defaultEnterKeyStyle` became functional and the seven imeAction styles declare `action` | `01-keyboard.png` — blue Return with a white glyph |
| B3 (M3) | panel surface = key surface, selection = accent, selected glyph white via the new `KeyboardView.selectLabelColor` hook overridden in `MoreKeysKeyboardView` | stage-B pin; `Key.isPressed()` added for it |
| B4 (W5) | strip 40 → 44dp in `SuggestionStripState` and both `input_view.xml`; smoke test recalibrated `STRIP_CELL2` 0.5980 → 0.5954 (1080×2280: the strip grows upward, so the key coordinates do not move) | stage-B pin + the recalculated comment in `scripts/emulator-smoke.sh` |
| B5 (M2) | **deviation from the plan, see below** | stage-B pin (both halves) |
| B6 (S1) | new `KeyPreviewBalloonDrawable`: a path droplet — rounded body over the VISIBLE height, a 5dp neck down to the key's top edge, 1dp hard shadow, nothing painted below | before: `07-balloon-real.png` (a 122dp white bar across two key rows); after: `11-droplet.png` |
| B7 (S2) | `platformDialogTheme` in both palettes: `app_dialog_bg` (16dp inset, 13dp radius, card colour), accent, centered 17sp bold title, non-caps accent buttons | stage-B pin; `06-letter-down.png` shows the restyled forget-word dialog |

**B5 deviation.** The plan authorized option (b): remove the pressed state for taps and
re-implement the glide key highlight as a Canvas overlay. Implemented instead as a gate inside
`MainKeyboardView.onKeyPressed`: a letter key (`isNormalBackground`) skips the pressed state
exactly when the balloon will really appear (`withPreview && !noKeyPreview && isPopupEnabled`).
Reason: the overlay existed only to resolve a conflict with the P7-5 glide highlight, and the
glide highlight arrives with `withPreview == false`, so the gate never touches it — the conflict
does not exist. The gate also keeps the pressed fill when the user has switched the balloon off,
which the overlay variant would have lost, leaving those keys with no feedback at all.

**B7 partial, deliberately.** iOS full-width stacked dialog buttons live in the framework's dialog
layout and cannot be restyled through a theme; getting them means replacing every `AlertDialog` in
the app with a custom view and re-pinning their source contracts (including the FLAG_SECURE ones).
Recorded as an open item rather than attempted.

**Visual verification** ran on the `tt_suggest_a14` emulator with the debug build
(`org.tatarkeyboard.ime.debug`), screenshots in `/tmp/p8-shots/`. The operator's phone was
connected during part of the session and was deliberately **not** touched: switching the default
IME on someone's daily driver is not a verification step. After about ten screenshots the
emulator's GPU started failing (`Failed to find ColorBuffer`, `screencap` returning empty files),
which is why stage C's toast has a pin but no screenshot.

## Stage C — engineering backlog

- **C1 (F15a)** — a dimmed settings row now explains itself. `setRowEnabled(row, enabled,
  reasonRes)` introduces *soft-disabled*: the row keeps its tap target (so the tap can answer),
  the switch inside it stays disabled, and the accessibility node still reports "disabled". Every
  row's tap now goes through the single `rowClick` gate — link, action, switch and value rows
  alike. Three strings in all three locales; an MDM restriction gets its own wording. Pin:
  `DisabledRowExplanationSourceContractTest` (7 tests, including "no raw row click listener
  survives outside the gate").
- **C3 — one resident glide index.** The 3.0.0 audit accepted ~5.8 MB worst case, both warm
  engines holding an index. `SuggestionsController.onGlideInput` now calls
  `releaseGlideIndexesExcept(activeLanguage)` **before** the request, so the worst case is one
  index (~2.9 MB, halved). Each drop is posted to its owner's serialized worker by the existing
  `releaseGlideIndex` seam, so no foreign worker-confined state is touched; the language you
  return to rebuilds lazily on its next gesture, exactly as after the O2-3 idle release. Pin:
  `GlideIndexResidencySourceContractTest` (invariant, order, and "the idle release still drops
  everything").
- **C2 — letter sharding REJECTED with numbers; the real peak closed instead.** Measured: the
  emoji-suggestion table is 3 976 records (ru 2 501 + tt 1 475), about **557 KB resident**, and it
  is only built when the user turns word suggestions on, while O2-4 already releases it on idle.
  Sharding by first letter would buy that 557 KB at the price of a new asset format, a new packer
  mode and new pins in both the python and the JVM suites — the wrong trade at this size. What WAS
  wrong is the load **peak**: the table was parsed whole, then filtered to the emoji the device can
  draw into a SECOND table, so the peak held two copies (~1.1 MB). `EmojiSuggestIndex.parse(stream,
  emojiFilter)` now filters during the parse, with the glyph verdicts memoized per emoji, so the
  peak equals the steady state. Pin: `EmojiSuggestIndexFilteringTest` (5 tests, including "the
  probe is asked once per distinct emoji").
- **C4 — dropped** by the operator (no four-cell strip; the Tatar table stays at K=3).
- **C5 — decided under the budget-device criterion.** *Live per-MOVE scoring: rejected, no code* —
  one decode per gesture already measures p95 **50.03 ms** on the POCO C71 against the plan's
  recorded ≤ 5 ms gate (`docs/ROADMAP-P7.md` § P7-4), and a MOVE stream would multiply that by two
  orders of magnitude on exactly the hardware this product targets. *Glide-triggered learning:
  rejected, nothing to learn* — the decoder scores against `GlideWordInventory`, whose only
  implementation is `TdictGlideInventory` (the shipped dictionary), so a glide can only ever commit
  a word that is already in the dictionary, while the personal store exists for words that are not.
  *Personal-dictionary glide candidates: accepted, gated, not started* — it is sequenced after C3
  by the plan and carries written gates (device p95 within 5 % of the P7-4 baseline, worst-case
  resident bytes +256 KiB at most, and a glided personal word visible in the alternatives).

## Stage D — supply-chain hardening

| Item | What changed | How it was checked |
|---|---|---|
| T5 | all five CI actions pinned to full-length commit SHAs, tag names kept in trailing comments; the SHAs were resolved from the GitHub API (annotated tags dereferenced) and are listed in `ci.yml`'s header | YAML parses; the CI leg itself runs on the operator's next push |
| T6 | `release_pack.sh` resolves `zipalign`/`apksigner` from a **pinned** build-tools version (37.0.0, overridable by `TT_BUILD_TOOLS_VERSION`) instead of "the newest installed" — a new SDK package could otherwise change the packed bytes | a full pack run reports the pin and still produces a byte-identical pair |
| T7 | `gradle/verification-metadata.xml` added: a SHA-256 for every build dependency. The first two generations were incomplete — AGP resolves `aapt2` through a DETACHED configuration that Gradle's writer does not walk — so that component's two checksums are recorded by hand with an explanatory comment | `clean assembleRelease testDebugUnitTest lintRelease` all green **with verification on**; a missing checksum fails loudly and locally |
| T8 | `release_pack.sh --no-sign` stops after the zipalign step (CI has no keystore), and the `reproducible` CI job now packs twice and compares the bytes — the arsc-deflate and zopfli steps were previously outside every automated gate | two local `--no-sign` runs: identical SHA-256 `cf0ae24d…a438cb`, 1 687 892 B |
| T10 | the local `gradlew` is byte-identical to the official 9.6.0 script again. The delta was not cosmetic: besides two comment lines, the local copy had **lost `-Dfile.encoding=UTF-8`** from `DEFAULT_JVM_OPTS`, which is exactly the kind of environment dependence a reproducible build must not have | `diff` against the official script is empty; `./gradlew --version` and the full JVM suite green |

## Gates (final tree)

| Gate | Result |
|---|---|
| python suites | **507 tests, 16 files, 0 failing** |
| `./gradlew test` | **1 670 tests, 180 suites, 0 failures/errors** (1 633 + 37 new pins) |
| `./gradlew lintRelease` | 0 errors, 5 live warnings, 28 baselined |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| dependency verification | on, and every build task passes with it |
| `release_pack.sh` × 2 | byte-identical, **1 694 282 B**, SHA-256 `f847e53e…5b98e` |
| `release_pack.sh --no-sign` × 2 | byte-identical, 1 687 892 B |
| `check-no-internet.sh` (packed APK) | both levels OK, backup closed as a whitelist |
| `release_check.sh --full` | **OVERALL PASS — 16/16**, delta vs 3.0.2 −152 282 B (−8.2 %) |

## What stays open after this phase

1. The operator's commit split, tag, push and publication of 3.1.0.
2. Device UAT of the whole wave on the POCO C71, plus the P5 legs that need the owner's hands
   (live Direct Boot reboot, Telegram interop, full gesture navigation, audible TalkBack) and the
   tablet leg that has no hardware. The emulator carried the visual proof here, minus stage C's
   toast (GPU failure, see above).
3. Personal-dictionary glide candidates (C5, accepted and gated, not started).
4. iOS full-width stacked dialog buttons (B7's remainder).
5. The standing debt recorded in the `HANDOFF.md` snapshot: 60 open error-prone findings, the
   inherited AOSP TODO/HACK markers, two files above the 1 500-line ceiling, two live lint
   warnings, and the pinned asset drift.

## Post-release device check (2026-09-25, evening): the 3.1.0 artifact was uninstallable

The operator connected the POCO C71 and asked for the release to be verified on it. The very
first step failed:

```
adb: failed to install dist/tatar-keyboard-3.1.0.apk: Failure [-124: ... Targeting R+ (version
30 and above) requires the resources.arsc of installed APKs to be stored uncompressed and
aligned on a 4-byte boundary]
```

Cause: **O2-1 of the optimization wave** (`resources.arsc` STORED → DEFLATED, −73 728 B in the
archive). For `targetSdk` 30+ the platform mmaps that table and refuses a package where it is
compressed. The item had been verified as archive bytes only — the packed artifact was never
installed — and `zipalign -c 4`, the packer's own check, prints `OK - compressed` and exits 0 in
exactly this case, so every gate stayed green.

Fixed forward as **3.1.1 / versionCode 38** (the 3.1.0 tag stays; no 3.1.0 artifact was ever
published, the GitHub Release object was never created):

- the deflate step removed from `scripts/release_pack.sh`, replaced by a comment stating why it
  must not return, and the alignment step now asserts STORED explicitly;
- new gate **`artifact.arsc_stored`** in `scripts/release_check.sh` — STORED plus a 4-byte-aligned
  data offset. Verified against both artifacts: FAIL on 3.1.0, PASS on 3.1.1;
- `docs/OPTIMIZE-2026-09-25.md` carries a dated footnote recording the revert and its cost.

3.1.1: **1 763 914 B** (+69 632 B vs the broken 3.1.0, headroom 43.9 %), two packs byte-identical,
`release_check --full` **OVERALL PASS 17/17**, installed on the POCO C71 — the SHA-256 of the
installed `base.apk` matches the built artifact.

**Lesson for the gate set**: a size optimization that touches the archive layout must be proven by
an INSTALL on a device, not by byte counting. Archive-level wins are exactly where the platform's
loader constraints live.

