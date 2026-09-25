# SECURITY-AUDIT-2026-09-25 — consolidated audit, pipeline + privacy + robustness round

**Date:** 2026-09-25. **Base:** the post-3.0.2-prep tree (release 3.0.2 / vc 36
engineering uncommitted on top of `815a0491`). **Verdict:** no release blocker;
all findings are fixed by one of the two fix waves of this round, or accepted /
documented with the rationale below.

## Scope and method

Five angles over the current tree, with evidence per finding (file and behavior,
not speculation — every "fixed" row names the change and its pin):

1. **New code** — the code that landed since the 2026-09-24 audit (glide,
   suggestions engine, pack tooling, the O2/SAFE optimization waves).
2. **Privacy** — what the keyboard holds in memory, when it lets go of it, and
   what it learns from (the editor text cache, the clean-run machines, the
   personal stores).
3. **Robustness** — crash and corruption shapes: paste, selection inversion,
   dead editors, NaN geometry, worker races.
4. **Pipeline** — the build and release tooling itself: wrapper, CI, the
   pack/check scripts, signing.
5. **UI** — dialogs, windows, flags: what can be screenshotted, obscured or
   tap-jacked.

Two fix waves land this round, working disjoint file sets:

- **Wave A (pipeline + UI + privacy):** `scripts/release_pack.sh`,
  `scripts/release_check.sh`, `gradle/wrapper/gradle-wrapper.properties`,
  `.github/workflows/ci.yml`, `DialogUtils.java`, `SettingsHostActivity.kt`,
  `LatinIME.java` (cache lifecycle + password gating only),
  `CleanRunMachine.kt` (+ its tests), `KeyboardView.java` (the onDraw guard),
  `SuggestionsController.kt` (the showBand ordering).
- **Wave B (robustness):** `InputLogic`, `RichInputConnection`,
  `PointerTracker`, `GlidePath.kt`, `SuggestionStripState.kt`,
  `EmojiSuggestIndex.kt` and their tests — the F-series below.

## Verdicts per area

- **New code: PASS after this wave.** Three real defects (the pack script's
  read-by-name duplicate hazard, the onDraw null buffer at 0×0, the showBand
  publication ordering) are fixed with pins; the fork's RuntimeException
  subclassing is accepted as androidx-mirroring; AppLocale is clean.
- **Privacy: PASS after this wave.** The editor text cache now dies on every
  session boundary, password fields are never re-read into it, and a paste can
  no longer enroll a word or a pair as "typed". The accessibility exposure of
  the strip is the platform's design (accepted). The stores were re-verified
  clean (pass section below).
- **Robustness: PASS after wave B.** F1–F17 (the 14 robustness findings) are
  fixed in the parallel wave's files; see the table.
- **Pipeline: PASS after this wave.** Multi-signer acceptance, the narrow
  asset set-equality, the missing wrapper checksum, the fork-PR artifact
  upload and the symlink/stale output path are fixed; build-tools resolution,
  verification-metadata and the pack pipeline's coverage by the reproducible
  gate are documented; the gradlew delta and the vcs-info contents were
  verified and pass.
- **UI: PASS after this wave.** The three dialogs that render personal content
  carry FLAG_SECURE on their own windows now; the actionLabel and the
  layout-picker items are platform contracts (accepted); all 22 dialogs were
  re-verified to filter obscured touches.

## Findings

Status legend: **fixed (wave A)** / **fixed (wave B)** — code change in this
round; **accepted** — deliberate decision, rationale inline; **pass** —
checked, no defect.

### New code

| # | Finding | Status |
|---|---|---|
| N1 | `release_pack.sh` read entries by NAME (`zin.read(info.filename)`) — with duplicate entry names the read resolves last-wins and silently swaps an entry's bytes for its twin's; duplicates were not rejected; the post-write check compared only `resources.arsc`; a pre-existing symlink or stale file at the output path was written through / left in place | **fixed (wave A)** — reads by `ZipInfo` (`zin.read(info)`), duplicate entry names fail the pack loudly before any write, a full per-entry byte comparison runs after writing (every entry, positionally paired), and a symlink-or-existing `OUT` is removed up front (`[ -L "$OUT" ] || [ -e "$OUT" ]` → `rm -f`) |
| N2 | `KeyboardView.onDraw`: with a zero-sized view `maybeAllocateOffscreenBuffer()` refuses, `mOffscreenBuffer` stays null and the unconditional `canvas.drawBitmap(mOffscreenBuffer, …)` NPEs (KeyboardView.java:229-236 as audited) | **fixed (wave A)** — one-line `getWidth() == 0 || getHeight() == 0` early return at the top of `onDraw` |
| N3 | `SuggestionsController.showBand` published words → spoken labels → emphasis: the label lookup reads the emoji index and can fail, and sitting between words and emphasis it stranded the strip with new words, the emphasis cleared by `setSuggestions` and the view's one display rebuild never run | **fixed (wave A)** — the emphasis (which runs the publication's display rebuild in the view) is now published right after the words and before the labels; a label failure leaves a whole consistent band. Pin: ordering assertion in `SuggestionStripSourceContractTest` |
| N4 | The fork subclasses `RuntimeException` for its own unchecked failures | **accepted** — mirrors the androidx pattern of domain-specific unchecked exceptions; no swallowing, no catch-all added |
| N5 | AppLocale (the Tatar-default UI wrapper) re-audited | **pass** — clean |

### Privacy

| # | Finding | Status |
|---|---|---|
| P1 | The surrounding-text cache survived `onFinishInput` / `onWindowHidden`: only `onFinishInputView` cleared it, so a lock screen or a home gesture over an unchanged field left up to a full cache window of the field's text in IME-process memory until the next field | **fixed (wave A)** — `mInputLogic.clearCaches()` added to `onFinishInputInternal()` and `onWindowHidden()` (the exact call `onFinishInputView` already made). Pin: `EditorTextCachePrivacySourceContractTest.everySessionBoundaryClearsTheEditorTextCache` |
| P2 | Password fields were re-read like any other field: `reloadTextCache` at all three LatinIME call sites (field start, external cursor move, space-slide release) pulled the password into the surrounding-text cache | **fixed (wave A)** — all three sites are gated on the password check (the `InputAttributes.mIsPasswordField` predicate computed from the live `EditorInfo`, so it also works before the settings reload); a password field takes clear-instead-of-reload at field start, which also evicts the previous field's text. Auto-caps is unaffected by construction: `getCursorCapsMode` reads only the local cache (no IPC), which the user's own typing keeps current. Pins: `EditorTextCachePrivacySourceContractTest.passwordFieldsAreNeverReReadFromTheEditor` / `.autoCapsReadsOnlyTheLocalCacheNeverTheEditor` |
| P3 | Unbounded cache append growth (local mutations could grow the before-cursor cache past the window size) | **fixed (wave B)** — RichInputConnection/InputLogic (F3) |
| P4 | A paste enrolled as a clean run: a word appearing whole in one text event (the clipboard's shape) satisfied the growth rule, so pasting «дөнья» after «сәләм » — twice, the learning threshold — enrolled the pair as if typed: the personal stores would learn clipboard contents | **fixed (wave A)** — CleanRunMachine: a fresh word's FIRST observation may carry at most one keystroke's worth of text (≤ 2 UTF-16 units; a surrogate pair is one key); 3+ units in one event marks both run machines dirty at birth. Pins in `PersonalBigramRunTest` / `PersonalLearningRunTest`: typing «сәләм дөнья» twice still learns the pair every time; pasting «дөнья» twice learns nothing; the 2-unit edge stays clean; a pasted start stays dirty even when the word is then grown by hand. The two run-test harnesses now feed typing one code point per event, as the editor cache actually moves |
| P5 | The suggestion strip and the emoji search expose shown words to accessibility services | **accepted** — that IS the accessibility contract: TalkBack must read the strip; any service the user granted the accessibility permission to is trusted by the platform to see what is on screen. No extra channel is opened beyond the rendered UI |
| P6 | The keyboard window itself does not set `filterTouchesWhenObscured` | **accepted** — the key previews and popups are child views of the same window and would be filtered with it; the audit found no overlay scenario against the IME window (pinned absence — `SettingsTapjackingSourceContractTest.imeWindowLayoutsStayUnflagged`) |
| P7 | Personal stores re-verified end to end: words and pairs stores, pending-hash counters, the salt, the quarantine copies | **pass** — no leak, no unchecked write path; the privacy suites (`PersonalStorePrivacyTest`, `PersonalDictionaryReadPathPrivacyTest`, `DictionaryEnginePrivacyTest`, package-privacy suites) stay green |

### Robustness (wave B files)

The per-fix detail (thresholds, pins, gate numbers) lives in wave B's own
report, `docs/SECURITY-AUDIT-2026-09-25-FIXES.md`; this table is the
consolidated registry.

| # | Finding | Status |
|---|---|---|
| F1 | Paste crash — a huge paste (≥ 64 Ki chars in one event) could crash the input path | **fixed (wave B)** — a 64 Ki-char paste threshold |
| F2 | Sticky `mCursorMoved` | **fixed (wave B)** |
| F3 | Unbounded cache append growth — local mutations could grow the before-cursor cache past the window size (also P3) | **fixed (wave B)** — the cache is capped |
| F4 | Unvalidated `SurroundingText` — selection offsets outside the returned text were trusted blindly | **fixed (wave B)** — the surrounding text is validated |
| F5 | Batch-pairing — a batch edit left unbalanced if a step threw | **fixed (wave B)** — try/finally on every batch edit |
| F6 | Dead-editor NPEs | **fixed (wave B)** — guards on a dead editor |
| F7 | Inverted selection (start > end) not normalized | **fixed (wave B)** — inverted selections are normalized |
| F8 | Cache race — a background reload could land over newer local state | **fixed (wave B)** — the background reload is applied on the UI thread |
| F10 | Reload coalescing | **fixed (wave B)** — reloads coalesce |
| F11 | `getUnicodeSteps` — ZWJ boundary handling and general hygiene | **fixed (wave B)** |
| F13 | Tracker-queue dedup — PointerTrackerQueue grew without dedup/eviction | **fixed (wave B)** |
| F14 | NaN glide points | **fixed (wave B)** — non-finite points are dropped |
| F16 | EmojiSuggestIndex cap | **fixed (wave B)** — the entry count is capped |
| F17 | `cellAt` NaN | **fixed (wave B)** — NaN guard on the strip's cell hit-test |

### Pipeline

| # | Finding | Status |
|---|---|---|
| T1 | `release_check.sh` signature check took the FIRST certificate (`head -1`) of `apksigner verify --print-certs`: an APK signed by the release key PLUS a second key passed the gate | **fixed (wave A)** — the gate now collects every `certificate SHA-256 digest:` line, counts DISTINCT digests, and fails when the count is not exactly 1 (one key signing via two schemes prints one digest twice and remains one signer). Verified against a hand-built two-signer APK (two throwaway test keys): the new gate fails it with "различных сертификатов: 2 (>1)" regardless of order (digests are sorted before counting); the old `head -1` logic compared only the first-listed digest, so a multi-signed APK with the release key leading would have passed |
| T2 | The set-equality asset check covered only `assets/emoji/`: an extra file under `assets/dictionaries/` or `assets/bigrams/` in the APK — content the engine reads from those directories — went unseen | **fixed (wave A)** — new `artifact.tree_assets` check: two-way set equality plus byte-for-byte content for both subtrees, NOTICE.txt and the sentstart tables included (the four zlib assets keep their deeper `artifact.asset_pins` check on top) |
| T3 | `gradle-wrapper.properties` had no `distributionSha256Sum` — the wrapper downloaded whatever the URL served | **fixed (wave A)** — pinned to `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`, verified twice independently: against `gradle.org/release-checksums` (9.6.0 `-bin`) and against the SHA-256 of a fresh `services.gradle.org` download |
| T4 | CI uploaded the debug APK artifact on `pull_request` runs too — a fork PR builds code its author chose, and the hosted artifact would be a maintainer-branded trojan candidate | **fixed (wave A)** — the upload step now carries `if: github.event_name != 'pull_request'` |
| T5 | The action pins are floating major tags (`@v4`/`@v5`) | **documented** — noted in `ci.yml`; not re-pinned to commit SHAs in this wave (the major tags are the upstream-supported contract); candidate for the supply-chain hardening round |
| T6 | `resolve_tool` in the pack/check scripts picks the NEWEST installed build-tools | **documented** — reproducibility of the pack pipeline therefore depends on the pinned build-tools install of the machine; accepted locally, noted in the release docs |
| T7 | No `gradle/verification-metadata.xml` | **documented** — recommended hardening; the dependency graph is pinned by other means (AGP/KGP versions, the wrapper JAR SHA-256 in CI, now the distribution SHA-256), so this is recorded as a recommendation, not a hole |
| T8 | The pack pipeline (arsc deflate → zipalign -z → v2 sign) sits outside the CI `reproducible` gate, which compares only the unsigned AGP outputs | **documented** — the pipeline's determinism is verified by the release ritual instead: two `release_pack.sh` runs of one tree must produce identical SHA-256, and this wave re-ran exactly that check (see Gates) |
| T9 | `release_pack.sh` wrote its output over a pre-existing path: a symlink would be followed (an arbitrary file overwritten), a stale file from a crashed run could pass for a fresh artifact | **fixed (wave A)** — folded into N1: the output path is removed up front when it exists or is a symlink |
| T10 | `gradlew` differs from the official Gradle 9.6.0 wrapper script by 3 lines | **accepted, verified harmless** — diffed against `gradlew` at `gradle/gradle@v9.6.0` in this round: two comment-only wording deltas and one `DEFAULT_JVM_OPTS` delta (the fork's script drops the `-Dfile.encoding=UTF-8` default; compilation encodings are pinned by the toolchain anyway). No executable-logic divergence |
| T11 | `vcs-info` contents of the build | **pass** — verified: nothing sensitive is stamped into the APK |

### UI

| # | Finding | Status |
|---|---|---|
| U1 | FLAG_SECURE was set on the settings ACTIVITY window only — dialog windows are separate windows, so the three dialogs that render personal content (the add-word field, the forget-word title naming the saved word, the forget-pair title naming the pair) could be screenshotted / screen-recorded / thumbnailed | **fixed (wave A)** — new `DialogUtils.securePersonalContent` (window flags set pre-`show()`, composable with the obscured-touch filter on the same dialog) applied at exactly those three sites; the generic confirmations (clear-all, erase, discard-quarantine) show no personal content and stay shareable. Pin: `DialogObscuredTouchContractTest.personalContentDialogsAreSecureFromCapture` |
| U2 | The enter-key actionLabel is painted from the host app's `EditorInfo` | **accepted** — platform contract: the label is the app's own text by design; every keyboard renders it |
| U3 | The layout picker lists other IMEs' display names | **accepted** — those labels are public system metadata (the same list the system settings show), not user content |
| U4 | All 22 dialogs re-checked for the obscured-touch filter | **pass** — 9 in LatinIME via `attachDialogToInputWindow`, the subtype picker in RichInputMethodManager, 10 in SettingsHostActivity, 1 in SettingsLanguagesScreens, 1 in SeekBarDialogHelper; pinned by `DialogObscuredTouchContractTest` |

## The fix wave in detail (wave A)

- `scripts/release_pack.sh` — read-by-`ZipInfo`, duplicate-name failure,
  full per-entry post-write verification, symlink/stale-output guard (N1, T9).
- `scripts/release_check.sh` — multi-signer rejection by distinct-digest count
  (T1); new `artifact.tree_assets` two-way set+content check for
  `assets/dictionaries/` and `assets/bigrams/` (T2); the non-quick gate set
  also runs `rebuild_assets.py --check --allow-known-drift`
  (`gates.asset_rebuild_check`) so the release automaton covers the
  pins-and-heads consistency check AGENTS.md mandates.
- `gradle/wrapper/gradle-wrapper.properties` — `distributionSha256Sum` (T3).
- `.github/workflows/ci.yml` — no artifact upload on `pull_request` (T4), the
  floating-tag note (T5).
- `LatinIME.java` — `mInputLogic.clearCaches()` in `onFinishInputInternal()`
  and `onWindowHidden()` (P1); `isPasswordField(EditorInfo)` /
  `isCurrentFieldPasswordField()` gating all three `reloadTextCache` call
  sites, password fields take clear-instead-of-reload at field start (P2).
- `CleanRunMachine.kt` — the paste rule, `MAX_FIRST_OBSERVATION_UNITS = 2`
  (P4); the two run-test harnesses type per code point, with four new pin
  tests.
- `DialogUtils.java` + `SettingsHostActivity.kt` — `securePersonalContent` and
  its three call sites (U1).
- `KeyboardView.java` — the 0×0 `onDraw` early return (N2).
- `SuggestionsController.kt` — `showBand` publishes the emphasis (and with it
  the view's display rebuild) before the spoken labels (N3); interface and
  view docs updated to the new order; ordering pinned in
  `SuggestionStripSourceContractTest`.

## Gates

_All green on the final tree of this round (both waves landed), 2026-09-25:_

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **507 tests, 16 files, 0 failing files** |
| `./gradlew test --rerun-tasks` | **1 633 tests, 174 suites, 0 failures/errors/skipped** |
| `./gradlew lintRelease` | green |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `assembleRelease -PskipReleaseSigning` size | **1 778 766 B** ≤ 3 145 728 B |
| `check-no-internet.sh` (source + packed APK) | both levels OK |
| `release_check.sh --quick` on the packed APK | **OVERALL PASS** — 1 690 074 B ≤ 3 145 728 B (46.3 % headroom), incl. the new `artifact.tree_assets` (8 files) and the single-signer `artifact.signature` |
| `release_pack.sh` run twice | identical SHA-256 `dd39c156dce6d1a48b90bdb86c7b3f113e71a350169337584daecf5c32c642bd` across both runs (determinism after the N1 rewrite) |

Negative checks exercised by hand, all failing loudly with the intended
messages: a duplicate-name zip against the pack step; a planted extra file
under `assets/dictionaries/` against `artifact.tree_assets`; a hand-built
two-signer APK against `artifact.signature`.

## Residual risks (accepted / parked)

- Floating CI action tags (T5), build-tools "newest installed" resolution (T6),
  no verification-metadata (T7) — the supply-chain hardening round's material.
- The strip's accessibility exposure (P5), the unfiltered keyboard window (P6),
  the host-painted actionLabel (U2), the picker's IME labels (U3) — platform
  contracts, revisited only if the platform changes them.
- The pack pipeline's byte-determinism is enforced by ritual (double-run SHA),
  not by CI (T8) — acceptable while packing stays a local, operator-run step.
