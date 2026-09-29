# Leftover fixes plan — 2026-09-28

Scope: the three deliberate remainders recorded in `docs/BACKLOG-2026-09-28.md`
after the wave — A6 (emulator IME-window wedge), B7 (dead class #5), B9
(dependency-verification hardening). Section D of the backlog is blocked by
external conditions and is out of scope; older deliberate remainders are listed
in the appendix with their do-not-reopen rules.

## L1. A6 — IME window wedged after BACK from the emoji panel (emulator)

**Current state.** Observed 2026-09-28 on the `tt_suggest_a14` AVD during smoke
probe calibration: closing the emoji panel or emoji search with BACK can leave
the IME window drawn but dead to touch (WMS reports `mViewVisibility=GONE`,
`mHasSurface=false`, while IMMS still reports `mIsInputViewShown=true`; key
events are still delivered). Believed emulator-side; the `learned-emoji-tt`
probe sidesteps it by reopening SetupActivity per round. Unknown whether it
reproduces on physical hardware.

**Why deferred.** Single observation, mid-calibration, no evidence of impact on
a real device.

**Plan.**

1. Reproduce on the AVD with state capture: script the exact sequence (open
   panel → BACK → taps), dump `dumpsys window`, `dumpsys input_method` and the
   surface state before/after; 20 attempts for a rate.
2. Reproduce attempt on the POCO C71 (same build, same sequence, scripted and
   manual). **Decision point:** 0/20 on hardware → record as an emulator
   artifact (dated backlog note), close. Reproducible → continue.
3. Root-cause in our window lifecycle: the panel-close path (KeyboardSwitcher
   panel hide, LatinIME input-view visibility). The wedge shape (view GONE
   while the window is shown) points at a missing visibility/layout refresh of
   the main input view after the panel view detaches.
4. Fix: restore input-view visibility + request a layout on panel close,
   guarded to the detected wedge state (no behavior change on the healthy
   path).
5. Regression probe: `emulator-smoke.sh` gains `panel-back-reopen` — open the
   panel, BACK, type one key, assert the character lands in the field.

**Verification:** the probe green 20/20 on the AVD; manual check on the POCO;
full gates (JVM `--rerun-tasks`, python, lint, pack reproducibility).

**Risks.** The window show/hide path is regression-prone (panel/keyboard
switching) — the fix stays behind the wedge-state guard, the smoke probe
watches the healthy path.

**Effort:** 0.5–1 day, mostly reproduction.

**2026-09-29:** closed — verdict NOT A DEFECT. BACK hides the IME window by
platform design (framework `InputMethodService.handleBack`); Gboard shows the
byte-identical state; scripted reproduction 0/30 on the AVD + 0/20 on the POCO
C71; evidence `build/device-uat-2026-09-28/wedge/wedge-report.md`. No code fix
warranted. Landed instead: the smoke probe `panel-back-reopen` (green),
`keyboard_shown()` now reads `mInputShown` (the reliable field —
`mIsInputViewShown` is sticky by platform), the stale wedge comment corrected.

## L2. B7 — dead class #5 (~600 lines) in `TdictPrefixIndex`

**Current state.** The two-substitution recovery machinery sits in the tree
unwired (NO SHIP verdicts at `TdictPrefixIndex.kt:720` and `:880`), compiled
and tested. The rejection evidence lives in `docs/ROADMAP-P4.md` (P6: recovery
ceiling 9.79 % @3 vs the +10 pp gate; the fail-closed probe budget blown on
73.1 % of firing rows). "Keep" was re-confirmed 2026-09-28.

**Options.** Keep (costs compile/test time and ~600 lines of cognitive surface)
or delete (the record is the doc, not the code; git history keeps the code
forever).

**Chosen: delete.** Unwired code earns its place only while its evidence is
unique; here the evidence is fully extracted into ROADMAP-P4.

**Steps.**

1. Confirm the P6 section of `docs/ROADMAP-P4.md` is self-sufficient (numbers,
   gates, reasons) — it becomes the sole surviving record.
2. Delete the class-#5 machinery from `TdictPrefixIndex.kt` and its calibration
   test (`TwoSubstitutionCalibrationTest` and any helper used only by it);
   grep for stragglers.
3. Dated footnotes: `docs/ROADMAP-P4.md` (machinery deleted, commit hash,
   recoverable from history) and the backlog's B7 line.
4. Full gates.

**Rollback:** `git revert` of the deletion commit restores everything.

**Effort:** ~1 hour.

**2026-09-29:** done — the class-#5 machinery is deleted from
`TdictPrefixIndex.kt` (−709/+19) together with the `FuzzyEditPolicy.kt`
disjunct and `TwoSubstitutionCalibrationTest.kt`; the JVM delta is exactly −6
tests; the dated footnotes are in ROADMAP-P4 and the backlog's B7 line. The
python-side `--edit-class 5` generator in `scripts/typo_pack.py` is KEPT
deliberately (it keeps the ROADMAP-P4-pinned typo-set SHAs reproducible).
Independent verifier verdict: SHIP.

## L3. B9 — dependency verification beyond trust-on-first-record

**Current state.** `gradle/verification-metadata.xml` pins SHA-256 checksums
for every dependency (since 2026-09-25); no signature verification. TOFU risk:
an artifact compromised at first-record time would be pinned as "trusted". The
mechanism already proved its value once (the cold-cache CI gap found and fixed
2026-09-27).

**Chosen hardening:** add PGP signature verification with a committed keyring,
keeping checksums as the fallback for unsigned artifacts.

**Steps.**

1. Inventory: list all dependencies and which carry signatures (Google and
   JetBrains artifacts generally do; check every module in the metadata).
2. Regenerate metadata with signatures on a clean `GRADLE_USER_HOME`:
   `gradle --write-verification-metadata pgp,sha256`, then run the full
   build + test + lint cycle to catch every resolved artifact (the 2026-09-27
   gap came from artifacts resolved only through POMs — cover both paths).
3. Prune the trusted-key list to the minimum covering the tree; export the
   keyring (`gradle --export-keys`) and commit `gradle/verification-keyring.*`
   so CI needs no keyserver network.
4. CI: the build job already runs on a cold cache — it becomes the fail-closed
   proof; add a one-line note to the workflow.
5. Document the key-rotation/expiry ritual in `AGENTS.md`: expiry is the main
   operational risk; renewal = re-export the keyring + commit.

**Verification:** cold-cache CI green; a locally doctored metadata entry (wrong
key id) fails the build loudly.

**Risks.** Key expiry breaking builds (mitigated by the ritual and the
committed keyring); unsigned artifacts stay checksum-only, documented
per-artifact in the metadata.

**Effort:** 2–4 hours.

**2026-09-29:** done — `gradle/verification-metadata.xml` regenerated with PGP
signature verification (64 trusted keys; 350 component versions
signature-verified; 33 components / 61 artifacts genuinely unsigned stay
sha256-pinned; aapt2 pinned by decision); the keyring
`gradle/verification-keyring.{gpg,keys}` (70 keys) is committed, so neither
local builds nor CI need a keyserver; `.gitignore` negations, the ci.yml
comment and the AGENTS.md ritual row are in place. Fail-closed proven: a
corrupted checksum and a corrupted key id both fail the build; the cold-cache
full cycle is green in 8m43s. Independent verifier verdict: SHIP.

## Sequencing

1. **L2** — an hour, zero risk, any time.
2. **L3** — half a day, build-infra only, no device needed.
3. **L1** — schedule when the phone is free; the reproduction leg (step 2)
   decides whether there is a code fix at all.

## Appendix — older deliberate remainders (do not reopen without new numbers)

- **D3** (asset drift ru 2/0, tt 155/0): generator-rule residue pinned in
  `scripts/known_asset_drift.json`; reopening means corpus/packer work behind
  an eval gate. No new measurements since the rejection.
- **D4** (emoji under the cursor, variant B): deferred product decision
  touching the frozen text contract; needs a demand signal first.
- **D5** (lossy ~190 KB compression): unnecessary at 42.6 % APK headroom;
  revisit only under size pressure.
- **EmojiSuggestIndex re-measurement ritual:** conditional trigger (the shipped
  table crossing 4 096 entries), documented in the `MAX_RECORDS` KDoc — a
  ritual, not a plan item.
