# ROADMAP-P8-PLAN — approved plan: ship the pending wave, then close the UX and engineering backlog

Date: 2026-09-25. Base: `main` @ `dd390316` (= `origin/main`), tag `v3.0.2`
released, five commits past the tag unreleased, 98 files uncommitted (the O1/O2
optimization wave and the two audit fix waves).

Scope: everything recorded as open in the `HANDOFF.md` state snapshot of
2026-09-25 (third entry). Sources: `docs/APPLE-UX-2026-09-25.md`,
`docs/OPTIMIZE-2026-09-25.md`, `docs/SECURITY-AUDIT-2026-09-25{,-FIXES}.md`,
`docs/GLIDE-PLAN.md`, `docs/ROADMAP-P4.md`, `docs/ROADMAP-P7.md`,
`docs/AUDIT-2026-09-24-FIXES.md`, `docs/ERRORPRONE-TRIAGE.md`.

Out of scope: items already rejected with numbers (P5b trigrams, P6 two-edit
recovery, P7 autocorrect widening) — they reopen only with new measurements;
operator-external actions (store publishing, IzzyOnDroid, relicensing letters).

## Working agreements (unchanged from `docs/ROADMAP.md`)

- Every stage ends with the full gate set: `./gradlew test`, the python suites,
  `./gradlew lintRelease`, `check-no-internet.sh` (source + built APK),
  `rebuild_assets.py --check --allow-known-drift`, release APK ≤ 3 145 728 B.
- Behaviour changes ship behind calibration gates written BEFORE measuring.
- New docs in canonical English. Commits are the operator's, in Russian,
  conventional, split by meaning.
- Every open question was answered by the operator on 2026-09-25; the answers
  are recorded verbatim in § Decision register and are the authority for scope.

## Stage order and rationale

| # | Stage | Content | Blocking? |
|---|---|---|---|
| R | Release the pending wave | gates, commit split, 3.1.0, pack, tag, publish | yes — everything else builds on a released base |
| A | Apple-UX batch 1 | W1, W2, W3, W6, W4, M1 | no |
| B | Apple-UX, all authorized | W5, M2, M3, M4, M5, S1, S2 | no |
| C | Engineering backlog | C3 index, C2 emoji shards, C1 toast, C5 personal glide | no (C4 dropped) |
| D | Supply-chain hardening | audit T5–T8, T10 | no |
| E | Device UAT | the blocked legs of P4–P7 | operator/hardware |
| F | Documentation hygiene | stale statuses, index gaps | no |

R first because a 98-file uncommitted tree makes every later diff unreviewable
and every later bisect useless. A before B because batch 1 is resource-only and
proves the visual direction on device before the expensive items are decided.
C after A because the 4-cell strip and the strip polish touch the same view.

## Stage R — release the pending wave

The tree holds two layers: five committed-but-unreleased commits (glide
doubled-letter fix, white-gray trail, Tatar-default app screens, docs) and the
uncommitted optimization + audit waves. They ship as ONE release.

- **R1. Re-run the gates on the exact current tree.** The last recorded counts
  belong to two different trees (JVM 1 633 in the audit-wave entry, 1 588 in
  the O2 report); the current number is unmeasured. Run
  `./gradlew test --rerun-tasks`, the python suites, `lintRelease`,
  `rebuild_assets.py --check --allow-known-drift`. Fix anything red before
  touching the version.
- **R2. Split the work into commits by meaning** (the operator commits): the
  optimization wave, the audit robustness wave, the audit privacy/UI wave, the
  pipeline/CI hardening, the docs. The two new `compat/` files and the vector
  launcher drawables belong to the optimization commit that deleted the ten
  WebP layers and the androidx dependency.
- **R3. Version and changelogs.** The wave carries behaviour changes (haptics
  path, cache clearing, paste rule, Tatar-default screens), so the next number
  is **3.1.0 / versionCode 37** (operator-confirmed). Move the `## Unreleased`
  section of `CHANGELOG.md` to `## [3.1.0] — <date>` and add the wave's entries;
  write `metadata/{en-US,ru-RU,tt}/changelogs/37.txt`, each ≤ 500 B.
- **R4. Pack and verify.** `scripts/release_pack.sh` twice (byte-identical
  SHA-256 required), `check-no-internet.sh` on the packed APK,
  `scripts/release_check.sh --full`. Write `docs/APK-AUDIT-3.1.0.md` with the
  per-entry CRC32 comparison against the 3.0.2 APK; retarget
  `docs/PUBLISH-CHECKLIST.md`.
- **R5. Tag, push, publish** — operator only.

DONE-WHEN: `release_check.sh --full` prints OVERALL PASS on a `dist/` APK named
for the new version; the tag exists; `git status` is clean.

## Stage A — Apple-UX batch 1 (resource-only and small Kotlin)

Per `docs/APPLE-UX-2026-09-25.md` § 4, batch 1 is W1 + W2 + W3 + W6, then W4,
then M1. Each item is independently revertible.

- **A1 (W1).** Key shadow alpha light `#40000000` → `#4D000000`
  (`values/colors.xml`). *Check:* pixel-probe the 1 dp band under a key in an
  emulator screenshot.
- **A2 (W2).** Functional key light `#B3B7C0` → `#ABB1BA` (`values/colors.xml`),
  re-pinning to KeyboardKit master. *Check:* A/B screenshot. Subtle by design;
  shipped by operator decision.
- **A3 (W3).** Spacebar language label alpha 128 → 255
  (`values/config-common.xml`). *Check:* type five characters on the tt layout;
  the label stays opaque in the screenshot.
- **A4 (W6).** Drop `FLAG_IGNORE_GLOBAL_SETTING` in
  `AudioAndHapticFeedbackManager.java` so API < 29 respects the system haptics
  switch, matching the API 29+ branch. *Check:* existing JVM contracts for the
  class; manual — system haptics off → no vibration. Note this in the changelog
  as a behaviour change.
- **A5 (W4).** Strip polish in `SuggestionStripView.onDraw`: separators inset to
  `height*0.22 … height*0.78`; pressed cell becomes a rounded rect (3 dp inset,
  5 dp radius); `TEXT_SIZE_DP` 17 → 18. Zero new allocations — one cached
  `RectF` field, reuse `decorationPaint`. *Check:* strip JVM contracts green;
  screenshot shows inset separators and a rounded pressed cell.
- **A6 (M1).** Three iOS-faithful shift states: new
  `drawable/sym_keyboard_shift_on.xml` (filled arrow, no bar), registered as
  `shift_key_on` in `KeyboardIconsSet`, wired to the
  `alphabetManualShifted|alphabetAutomaticShifted` case of
  `key_styles_common.xml` with `backgroundType="stickyOn"`; the `stickyOn` item
  of `ios_key_functional.xml` flips to the white fill. *Check:* screenshot the
  off → shift → caps cycle in both themes; TalkBack shift announcement
  unchanged; a11y string pins green.

DONE-WHEN: full gates green; an `emulator-smoke.sh` screenshot pass in both
themes (the device check the original iOS redesign never received); each item's
check recorded in a `docs/APPLE-UX-2026-09-25` follow-up section or a new report.

## Stage B — Apple-UX, operator-gated — ALL AUTHORIZED (operator, 2026-09-25)

All seven items are authorized, including the three that reactivate the wave-K
items cancelled on 2026-07-20. Order below is cheapest first; S1 (B6) is the
expensive one and runs last.

- **B1 (M5).** 200 ms slide+fade on `SettingsHostActivity.showScreen`, disabled
  when the animator duration scale is 0. Cheap, large perceived gain.
- **B2 (M4).** Accent Enter key: a `state_active` item in `ios_key_normal.xml`
  plus white icon tint for that state (touches the `KeyDrawParams` icon colour
  path).
- **B3 (M3).** More-keys panel in iOS colours: white/key-grey panel, accent
  selected cell. If a per-state label colour is not reachable through the
  existing attrs, keep the glyph dark on accent rather than adding rendering
  code.
- **B4 (W5).** Strip 40 → 44 dp. Raises IME height by 4 dp and forces
  recalibration of the coordinate-based strip taps in `emulator-smoke.sh`.
- **B5 (M2).** Balloon-only letter feedback. **Conflict:** deleting the pressed
  state also kills the P7-5 glide key highlight, which rides the same
  `onKeyPressed` path. Authorized as **option (b)**: remove the pressed state for
  taps AND re-implement the highlight as a Canvas overlay inside the existing
  glide trail draw pass (zero-alloc, ~30 lines). Option (a), skipping the item,
  is no longer on the table — the operator authorized the whole stage.
- **B6 (S1).** True droplet key-preview balloon — a path-drawn `Drawable` or
  moving the preview into the keyboard Canvas. Runs last in the stage; if the
  path work threatens the zero-allocation draw budget, the item stops and the
  numbers are recorded instead of shipping a regression.
- **B7 (S2).** iOS-style alert dialogs through `platformDialogTheme` (centered
  title, full-width stacked buttons). Authorized despite the low value/effort
  ratio recorded in the audit.

B2, B3 and B5 reactivate wave-K items the operator cancelled on 2026-07-20, so
each needs explicit sign-off.

## Stage C — engineering backlog

- **C1 (F15a).** Disabled dependent settings rows currently swallow taps
  silently (`SettingsHostActivity.buildPreferencesScreen`, wiring point marked
  `TODO(F2)`). Add the explaining toast plus its string in all three locales,
  and a source-contract pin. Small, self-contained.
- **C2. Emoji index sharding.** The residue of O2-4, which only added idle
  release: shard `assets/emoji/emoji_suggest_v1.txt` by first letter so a lookup
  inflates one shard instead of the whole table. Touches
  `scripts/emoji_suggest_pack.py`, the reader, and the `tests/emoji_*` pins.
  Gate: peak RSS during an emoji-suggest lookup drops measurably on the POCO
  C71 and cold start does not regress; otherwise reject with numbers.
- **C3. Shared or demand-paged glide word index.** Today both engines may hold
  an index — an accepted ~5.8 MB worst case (3.0.0 audit, ROADMAP-P7 footnote).
  Gate: worst-case resident bytes at least halved, glide decode p95 on device
  unchanged within noise.
- **C4. Four-cell suggestion strip — DROPPED** (operator, 2026-09-25). The
  product answer is no, so the +1.5643 pp of latent rank-4 value stays unclaimed
  and the Tatar table stays at K=3. Do not reopen without a new product decision.
- **C5. Glide follow-ups — decided by the agent on the operator's delegation
  (budget-device criterion, 2026-09-25):**
  - **Live per-MOVE scoring — REJECTED, no code.** One decode at lift already
    measures **p95 50.03 ms on the POCO C71** against the plan's recorded ≤ 5 ms
    gate (`docs/ROADMAP-P7.md` § P7-4; the host figure is 1.19 ms, a ~40× device
    ratio consistent with E3b). A MOVE stream delivers tens of events per second,
    so per-MOVE scoring multiplies an already-over-budget cost by two orders of
    magnitude on exactly the hardware this product targets. Physically
    incompatible with the budget-device constraint; the alternative would be
    partial-path scoring, which is a research mission, not a follow-up.
  - **Glide-triggered learning — REJECTED, nothing to learn.** The decoder scores
    against `GlideWordInventory`, whose only implementation is
    `TdictGlideInventory` — the shipped dictionary. A glide can therefore only
    ever commit a word that is already in the dictionary, and the personal store
    exists for words that are NOT. Learning from glide commits could only bump
    usage counters while adding decode errors to the personal store. Verified by
    reading the inventory interface and its single implementation.
  - **Personal-dictionary glide candidates — ACCEPTED, gated.** A second
    inventory over the bounded personal store, merged into the decode result
    under the existing "at most one personal candidate per request" rule. The
    personal store is orders of magnitude smaller than the 110 000-entry
    dictionary, so the added scoring is noise. Gates, written before measuring:
    device glide p95 must not regress by more than 5 % over the P7-4 baseline,
    worst-case resident bytes must not grow by more than 256 KiB, and a glided
    personal word must appear in the alternatives on device. Runs AFTER C3 —
    C3 reduces the memory this item then adds to.

## Stage D — supply-chain hardening round (audit residue)

One small wave, no product impact: T5 (pin the CI actions to commit SHAs instead
of floating major tags), T6 (pin the build-tools version the release scripts
resolve), T7 (add `gradle/verification-metadata.xml`), T8 (bring the pack
pipeline under the CI reproducibility gate), T10 (record or eliminate the
three-line `gradlew` delta from the official 9.6.0 script). Gate: CI green,
`release_pack.sh` still byte-reproducible.

## Stage E — device UAT (hardware-gated)

When the POCO C71 is free: the interactive legs blocked across P4–P7 (glide UAT,
P5a on-device inflation, cold start), plus the P5 legs that need the operator's
own hands — live Direct Boot reboot (U2), Telegram interop (U3, app not
installed), full gesture-navigation mode (U4, HyperOS ignores the adb toggle),
audible TalkBack (U1). The tablet leg (U5) stays blocked — no hardware.

## Stage F — documentation hygiene

- `docs/ROADMAP-P3.md` header says P7 is "NOT started" while its own § P7 holds
  the gate numbers and the rejection — add a dated footnote (history is not
  rewritten).
- `docs/README.md` has no index line for `APPLE-UX-2026-09-25.md`; add it and a
  line for this plan.
- After each stage: a report doc plus a `HANDOFF.md` entry, per the existing
  discipline.

## Decision register — ANSWERED (operator, 2026-09-25)

1. **Release** — 3.1.0 / versionCode 37, ONE release covering both layers (the
   five unreleased commits and the uncommitted waves). Agent recommendation
   accepted.
2. **W2** (functional-key grey re-pin to `#ABB1BA`) — **ship**.
3. **Stage B** — **all seven items authorized**, wave-K reactivations included;
   B5 therefore takes option (b), the Canvas-overlay glide highlight.
4. **Four-cell strip (C4)** — **not wanted**, dropped.
5. **Glide follow-ups (C5)** — delegated to the agent under the budget-device
   criterion: per-MOVE scoring rejected (device p95 already 50 ms at one decode
   per gesture), glide-triggered learning rejected (the decoder can only emit
   shipped-dictionary words, so there is nothing for the personal store to
   learn), personal-dictionary glide candidates accepted behind written gates
   and sequenced after C3.

Standing constraint: the device UAT legs wait for the phone, and the emulator
carries the proof in the meantime.

## Ordering note (budget devices)

Under the operator's budget-device criterion, **C3 outranks everything else in
stage C**: the two numbers that hurt a POCO-class phone are the ~5.8 MB
worst-case glide index and the 50 ms decode p95, and C3 is the only item that
attacks the first of them. C2 (emoji sharding) is second for the same reason.
C1 is a one-string fix that rides along.
