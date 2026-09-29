# Emoji panel space — analysis and plan (2026-09-28)

Operator complaint (2026-09-28): the emoji panel is uncomfortable at the
Default/Compact keyboard-height presets — very little room is left for
scrolling/choosing emoji, and the bottom buttons ("back to ABC" etc.) feel
like they eat a noticeable share.

## Measured geometry (from source; densities: POCO C71 720×1640 @2.0, navbar
overlap 96px = 48dp on Android 15)

The panel's box IS the keyboard's box (deliberate invariant,
`EmojiPanelState.kt:225-228`): `InputView.showEmojiPanel` passes
`mKeyboardView.getHeight()` (+ 44dp if the suggestion strip was visible), minus
the navbar-overlap reserve. Inside that box the chrome takes: tab strip 44dp +
search row 50dp (both shrinkable to a 0.6 floor by the 1.9.14 Р-2 logic), and
the first in-grid section header takes 30dp. The "АБВ"/⌫ keys are FLOATING
(44dp, over the grid's bottom 52dp) — they cost the scroll zero rows but
visually overlay the bottom of the grid.

Rows visible below the first section header (POCO C71):

| Preset | Suggestions OFF (panel 411/484/556px) | Suggestions ON (+88px strip) |
|---|---|---|
| Compact 0.85 | **1.6 rows** (bands squeezed to the 0.6 floor) | 1.7 rows |
| Default 1.0 | **1.6 rows** (bands at 0.98 — the squeeze already engages) | 2.5 rows |
| Tall 1.15 | 2.4 rows | 3.3 rows |

So the complaint is exact: at Default/OFF the grid shows ~1.6 emoji rows. The
single biggest non-content item is the 48dp Android-15 navbar reserve —
untouchable. Second is the 50dp search row. The floating keys cost no rows
but cover the bottom 52dp until scrolled.

## Options

**A. Collapse the search row into the tab strip.** The 50dp search pill becomes
a 🔍 cell in the 44dp tab row; tapping it expands the search field in place of
the tabs (the search UI already exists; only its placement changes). Gain at
every preset: +100px ≈ **+1.1 rows** on the POCO (Default/ON: 2.5 → 3.6 rows
below the header). The same-box invariant is untouched; no window resize.
Bands already support zero-height (draw/hit-test guards at
`EmojiPanelState.kt:555,563`). Strings ×3 locales.

**B. A separate "Emoji panel height" Appearance setting.** Value row mirroring
`keyboardHeightRow()`: `Same as keyboard` (default — today's invariant) /
`Larger` (keyboard box × ~1.2, capped at the 46%p screen max) / `Max`.
Consumption seam: `InputView.showEmojiPanel` replaces the passed height; the
window then resizes on every keyboard↔panel switch (accepted, opt-in — Gboard
behaves this way). No platform cap binds the panel (the 46%p clamp lives in
`ResourceUtils.getDefaultKeyboardHeight`, which the panel never consults);
`EmojiSearchView` already changes the IME height live (precedent).
Untested territory to check: landscape fullscreen-extract mode.
Files: `Settings.java` key+reader, `SettingsHostActivity` row + restriction
entry (mirror the keyboard-height percent restriction), `InputView.java`,
strings ×3, contract tests (KeyboardHeightPreferenceTest pattern).

**C. Slim the floating keys (optional micro).** 44dp → 40dp keys and trailing
air 60dp → 56dp. Marginal; the keys cost no scroll rows, so this is polish
only. Do only if A+B land and the overlay still bothers.

**Rejected:** touching the 48dp navbar reserve (impossible on Android 15);
shrinking the tab strip below 44dp (touch-target floor — 44dp is already under
the 48dp a11y guidance, going lower is a regression); a per-panel height
*slider* (presets match the keyboard-height UX and stay testable).

## Recommendation

Do **A + B**: A is the quick structural win (+1 row everywhere, invariant
intact), B answers "I want the panel big while the keyboard stays compact" —
the actual operator scenario (Compact keyboard + roomy panel = Larger/Max
preset, giving Tall-or-better panel geometry without touching typing
geometry). C is optional polish after A+B.

## Test plan

- Unit/contract: panel-state geometry pins updated for the collapsed-search
  layout (gridTop without the search band; expanded state restores it);
  setting reader + restriction pins for the new pref; the existing
  EmojiPanel*/EmojiRecentAndFling contract suites stay green.
- Emulator smoke: screenshot delta of the panel at Default (grid taller by
  ~100px); a probe tapping the 🔍 cell and running a search end-to-end.
- Device UAT (POCO C71): all three keyboard presets × panel heights, verify
  the window resize jump (B) is acceptable, navbar reserve intact, floating
  keys reachable.

## Effort

A: ~2–3 h (EmojiPanelView/State/Drawing + strings + tests). B: ~2–3 h
(settings pipeline + InputView + strings + tests). C: ~0.5 h.

## Shipped (2026-09-29)

All three options landed (uncommitted on HEAD `0975ccfa`):

- **A — the search row collapsed into the tab strip.** The standalone 50dp
  search row is gone; a 🔍 cell at the tab strip's right end expands the
  search field in place of the tabs when tapped. +50dp of grid at every preset.
- **B — the "Emoji panel height" Appearance setting.** Three values: `Same as
  keyboard` (default — the pre-existing same-box invariant), `Larger`
  (keyboard box ×1.2), `Max` (46 % of the screen). Strings ×3 locales plus a
  managed-restriction entry mirroring the keyboard-height one. **Operator
  note:** under Larger/Max the IME window resizes when the panel opens —
  Gboard-style, opt-in; the default never resizes.
- **C — floating keys 44 → 40dp.**

Rows visible below the first section header (POCO C71, after the wave):
Default/OFF **1.6 → 2.7 rows**, Default/ON **2.5 → 3.6 rows**.

Verification on the merged tree (2026-09-29): JVM **1839 tests / 190 suites /
0 failures** (`--rerun-tasks`); python **507/16** (1 pre-existing skip);
lintRelease **0 errors / 29 baselined** (a wave-orphaned
`ios_keyboard_background_secondary` color removed to keep the baseline true);
`rebuild_assets.py --check` ok (tt 155/0, ru 2/0); baseline/startup profiles
regenerated and promoted (3430 → 3431 rules); two unsigned packs byte-identical
**1 798 202 B**; signed pack **1 804 874 B** (SHA-256
`445d90945dd5fca744669607c289f8830a12e9334b4b9d8177a6109fe679656a`, single
signer `98ca6feb…42ad`); `release_check.sh --quick` OVERALL PASS **12/12**;
check-no-internet green at both levels. Independent verifier verdict: P SHIP.

Emulator smoke on `tt_suggest_a14`: **23 PASS / 0 FAIL / 1 SKIP**, including
the new `panel-back-reopen` regression probe; evidence
`build/emulator-smoke-panel/`, screenshots in `build/emulator-smoke-panel/shots/`
(the search field over the keyboard, the Larger/Max window resize, the 40dp
floating keys).

Wave side-effects: the baseline-profile generator's `EMOJI_FIRST_CELL` tap pin
was re-pinned to the collapsed-search geometry (the panel grid moved up by the
removed search row), and the smoke's `keyboard_shown()` now reads `mInputShown`
(the reliable field — `mIsInputViewShown` is sticky by platform; see L1 in
`docs/LEFTOVERS-PLAN-2026-09-28.md`).
