# ROADMAP-P5 — phase 5 report: UX polish & validation

Status: U6 implemented (2026-09-23). U1–U5 are device-verification items of the phase's final
batch and are NOT part of this item. Phase 5 of docs/ROADMAP.md ("UX polish & validation").

## U6 — Keyboard height preference (2026-09-23)

### What existed (investigation, before any design)

- `res/xml/kbd_tatar.xml` / `kbd_russian.xml` carry TWO row-count variants selected by a
  `<case latin:showNumberRow>` switch inside the keyboard XML — 6 rows with the number row
  (16.667%p rows) vs 5 rows without it (20%p rows). Both variants split the SAME occupied
  keyboard height differently; the roadmap's "existing 5/6-row variants are the starting
  point" therefore cannot be the height mechanism: a row count changes the key aspect, not
  the keyboard height, and the number row already has its own toggle on the same settings
  screen.
- The actual total height is `ResourceUtils.getKeyboardHeight(res, settingsValues)` =
  `config_default_keyboard_height` (clamped to the config min/max fractions) ×
  `SettingsValues.mKeyboardHeightScale`, a float read from `pref_keyboard_height`
  (default `DEFAULT_SIZE_SCALE = 1.0f`). `KeyboardSwitcher.loadKeyboard` hands it to
  `KeyboardLayoutSet.Builder.setKeyboardGeometry`; `KeyboardBuilder` then derives row
  heights, key hit boxes (the KeyDetector proximity grid), gaps and the bonus height from
  it; the emoji panel sizes itself to the resulting MainKeyboardView height; popup previews
  anchor to key bounds. A scale change propagates to all of them with zero per-site code,
  and the suggestion strip (a separate band of the input view) is untouched.
- That float was written by an inherited seek-bar row ("Keyboard height", 50–150 %, step 5)
  on the Appearance screen, and by the integer managed restriction of the same key.

### Design (decided 2026-09-23)

The row becomes three named presets — **Compact (0.85), Default (1.00), Tall (1.15)** — in a
one-tap picker dialog on the same Appearance row (reusing `row_value`):

- Same storage: the `pref_keyboard_height` float with the same default (1.0f = exactly
  today's behavior, pinned by test). No schema change, no migration: a seek-bar-era value
  (say 1.30) keeps applying verbatim, and the row shows it as a plain percent until the user
  picks a preset.
- The picker is `setItems`, not a radio dialog with OK/Cancel: the choice is reversible, so
  it applies on the tap itself — and an unnamed OK button is forbidden anywhere in
  SettingsHostActivity by the file-wide contract of EmojiRecentAndFlingSourceContractTest
  (the first OK/Cancel draft of this dialog was caught by exactly that pin).
- The managed restriction (`pref_keyboard_height`, integer percent) is untouched: a
  restricted row is disabled exactly like before, and an admin-forced value matching no
  preset shows as a percent — one display rule covers both.
- Live apply is the seam the slider already used: the Settings listener rebuilds
  SettingsValues on the change; the next `loadKeyboard` re-reads the scale; the height is
  part of `KeyboardId.equals`/`hashCode`, so a changed height never hits a stale
  keyboard-cache entry (no manual cache clear — unlike the emoji-key flag, which KeyboardId
  deliberately ignores).
- The seek-bar plumbing it replaces is removed: `keyboardHeightProxy()` and the three
  `config_min/max_keyboar_height` / `config_keyboar_height_step` integers (no other readers).
  The bottom-offset slider keeps the SeekBar machinery untouched.
- New pure holder `KeyboardHeightPresets` (latin/settings): the three scales, the default
  index and the scale→preset matching (epsilon-tolerant; -1 for values no preset owns).
  Deliberately Android-free so the mapping is JVM-tested directly.

### Choices exposed

Compact (0.85) / Default (1.00) / Tall (1.15) — strings en/ru/tt (Compact / Default / Tall;
Компактная / По умолчанию / Высокая; Кыска / Гадәти / Биек), the Tatar rows queued per
docs/archive/dictionary/TATAR-REVIEW-QUEUE.tsv (approved, dExNight). The presets sit well
inside the old 50–150 % slider range, so no choice produces a geometry the shipped layout
code has never rendered.

### Tests (JVM)

`KeyboardHeightPreferenceTest` (13 tests), split as usual for this Robolectric-free suite:

- pure logic: the exact scales and their ascending order, the default index sitting on
  1.0f == `SettingsValues.DEFAULT_SIZE_SCALE`, epsilon-tolerant matching (a seek-bar-era
  115/100f resolves to Tall), -1 for inter-preset values (a stored 1.30 belongs to no
  preset);
- source contracts: the storage key unchanged, the pinned default read in SettingsValues,
  the ResourceUtils multiplication, the KeyboardSwitcher geometry hand-off, the live-apply
  seam (SettingsValues rebuild on the change + mHeight inside KeyboardId equality — a
  changed height cannot hit a stale cache entry), the row/dialog wiring including the
  restricted path, the surviving managed restriction, the removed slider leftovers
  (proxy + integers), the three labels in all three locales.

Device verification (POCO C71: the three presets render, the dialog, the legacy-value
display, emoji-panel alignment at 0.85/1.15) belongs to the phase's final batch with U1–U5.

### Gates (2026-09-23)

| gate | result |
|---|---|
| python suites (`tests/*/test_*.py`) | 484 tests, all OK (unchanged) |
| `./gradlew test --rerun-tasks` | **1 456 tests, 0 failures / 0 errors / 0 skipped** (150 suites; 1 443 + 13) |
| `./gradlew lintRelease` | BUILD SUCCESSFUL — the identical finding signature as the pre-change tree (4 unbaselined: 2 × NewerVersionAvailable for the build-time error-prone deps, 2 × pre-existing UnusedResources; 32 baselined; verified by stashing the change) |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` (assets untouched) |
| `assembleRelease -PskipReleaseSigning` | **1 853 820 B** ≤ 3 145 728 (+872 B vs the P4 final tree's 1 852 948) |
| `check-no-internet.sh` (the unsigned release APK) | Level 1 + Level 2 OK, backup whitelist OK |

---

# Final block: full gates + device validation batch (2026-09-24)

## Full gates (final tree, all 2026-09-24)

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **15 files, 484 tests, 0 failing files** |
| `./gradlew test --rerun-tasks` | **1 456 tests, 0 failures / 0 errors / 0 skipped** (150 suites) |
| `./gradlew lintRelease --rerun-tasks` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `release_pack.sh` | unsigned **1 853 820 B** (= the U6 recorded size — reproducible) → signed zopfli **1 834 276 B** ≤ 3 145 728, SHA-256 **`3aa1746c170167dff7cc5cde0d30a8a96a57df5d702412d7d844dff8a8090e59`**, v2-only, cert `98ca6feb…42ad` |
| `check-no-internet.sh` (the signed APK) | both levels OK |
| `release_check.sh --quick` | **8/8 artifact checks PASS** (version 2.0.1/33, changelog on the existing 33.txt) |

## Device validation (POCO C71, 720×1640, Android 15 Go, SDK 35, dark theme)

APK installed with `adb install -r` (2.0.1/33 → 2.0.1/33, new build). Evidence:
`build/device-uat-2026-09-24/`.

### Per-item verdicts

| Item | Verdict | Evidence |
|---|---|---|
| **U6 keyboard height** | **PASS** — Appearance → "Keyboard height" row opens the setItems picker (Compact/Default/Tall, no OK button per the contract); measured keyboard content top: Compact y≈1053 / Default y≈980 / Tall y≈908; the emoji panel top matches each preset (1053/980/908); keys/strip/panel align at all three; restored to Default | `01-height-dialog.png`, `02-tall-kb.png`, `03-compact-kb.png`, `04-compact-emoji.png`, `05-default-kb.png`, `06-default-emoji.png`, `07-tall-kb2.png`, `08-tall-emoji.png` |
| **U1 TalkBack** | **PARTIAL-PASS** — coexistence proven on device: TalkBack enabled via adb, its first-run tutorial + notification-permission dialog navigated (double-tap activation), the field gets the a11y focus frame, typing works (the tap-typed «а»), the sentence-start band paints under TalkBack (`11-talkback-keyboard.png`: Бу · Ул · Ә with the focus frame), no crashes. The **audible content is not verifiable from adb** (same limitation the 1.9.12 UAT recorded) — the machinery is `KeyboardAccessibilityDelegate` (every key a virtual node with KeyDescriptionMapper descriptions, ACTION_CLICK synthesizing a real touch) + strip `announceForAccessibility` on band changes + shift/language announcements, all in-tree. One noted difference: the comma long-press under TalkBack opens the more-keys panel (activation semantics) rather than the emoji panel directly. TalkBack fully disabled after (services null, accessibility_enabled 0) | `09…16-*.png`, `a11y-*.txt` in `/tmp/uat-backup-2026-09-24/` |
| **U2 Direct Boot** | **STATIC PASS, live reboot operator-pending** — verified without rebooting the user's phone: the IME service is `directBootAware="true"`; dictionaries and bigram tables unpack into **device-encrypted** storage (`createDeviceProtectedStorageContext().filesDir`, available pre-unlock — typing works on the PIN screen); the personal stores (words, pairs, pending counters, salts) live in **credential-encrypted** `noBackupFilesDir` and are never touched before unlock (`isUserUnlocked` gates, incl. the emoji-recents gate). The live test would need a reboot with the user's PIN — forbidden by the session rules | manifest + storage factory sources |
| **U3 Telegram** | **BLOCKED** — Telegram is NOT installed on the device (`pm list packages`). Nothing to verify against; the rules forbid installing it | — |
| **U4 gesture navigation** | **PARTIAL** — HyperOS on this build ignores `settings put secure navigation_mode 2` AND `force_fsg_nav_bar` from adb (the navbar stays 3-button; a SystemUI restart doesn't change it). In the only producible configuration the keyboard's bottom row sits correctly above the navbar with no overlap. Full gesture-mode verification is operator-pending via the Settings UI toggle. Both written keys restored (`navigation_mode` 0) / deleted (`force_fsg_nav_bar` — was absent) | `17…24-nav-*.png` |
| **U5 tablet** | **BLOCKED** — no tablet exists; the 1.9.12-era Д-2 fix (Enter on tablets) is pinned by `EnterKeyPresenceTest` | — |
| **Phase-2 leftovers** | **PASS** — incognito deep-check on device: with incognito ON the learned pair «сәләм → дөнья» IS still suggested (read side; `25-strip.png`), the dictionary screen shows the «Learning is paused…» note (`27-dict-paused.png` text list), a pair typed twice under the pause is NOT learned (`26-strip.png`: дөнья shown, дустым absent), incognito OFF + two fresh observations → дустым learned (`28-strip.png`: дустым · дөнья · сәләмә). Cleanup: «Clear all word pairs» erased all three test pairs (screen empty; the strip back to сәләмә · һәм · белән — `31-strip.png`) | as named |

### Regression core (same session)

| Scenario | Result | Evidence |
|---|---|---|
| `сцләм` → сәләм in cell 1 | PASS | `32-strip.png` |
| tap сәләм → immediately сәләмә · һәм · белән (tap-followup + fallback) | PASS | `33-strip.png` |
| `татар` prefix → татарлар · татарча · татарлары (same-stem) | PASS | `34-strip.png` |
| `татар, ` → теле · дәүләт · телен (after-comma; the space-before-comma variant correctly paints nothing — fail-closed by design) | PASS | `38-strip.png` (35-strip.png records the empty case) |
| `сакчы`+space → булып · виталий · андрей (сакчы is a bigram head since P5a — successors, not forms; forms path proven by the сәләмә cell) | PASS | `36-strip.png` |
| `сәлам`+space → биреп · белән · 👋 (emoji tail) | PASS | `37-strip.png` |
| ru: майор → майора·майором·майору; тюлень+space → я·не·в | PASS | `52-strip.png`, `53-strip.png` |
| Emoji panel: open, recents/search/grid, tap 😀 commits, АБВ back | PASS | `58-emoji-panel2.png`, `59-emoji-committed2.png` |

### Cold start and stability

| Entry point | run 1 | run 2 | run 3 | median |
|---|---:|---:|---:|---:|
| SetupActivity | 253 | 254 | 246 | **253 ms** |
| SettingsActivity | 261 | 264 | 268 | **264 ms** |

Both medians < 400 ms (previous: 251/273, 257/270). Crash buffer EMPTY after the whole
cycle; full logcat: no FATAL EXCEPTION / ANR for the package. Evidence:
`61-coldstart.txt`, `62-logcat-crash.txt` (0 lines), `63-logcat-full.txt`.

### Bugs found

**None in the app.** Harness findings worth recording:

- The "ghost IME" state appeared twice under heavy navigation churn (settings↔setup↔BACK
  cycles): `mIsInputViewShown=true` while the keyboard window renders nothing. Recovered
  by `am force-stop` + IME re-select. Not reproduced in any user-shaped flow; noted for a
  possible robustness look, not filed as a defect.
- MIUI ignores both nav-mode keys from adb (see U4).
- The subtype globe cycle is MRU-ordered and my typed-probe commits re-order it —
  single-tap probes ping-pong; the working switch is double-tap-without-typing between
  (documented since the TT-TYPO-NEXT session).

### Device state restored

Default IME ours (as found); toggles suggestions/personal ON (never changed), incognito
ON (restored after the resume test); keyboard height Default (restored after U6); nav
mode 0; TalkBack off (services null, accessibility_enabled 0); rotation auto/portrait;
the learned test pairs erased (the dictionary screen shows the empty state).
