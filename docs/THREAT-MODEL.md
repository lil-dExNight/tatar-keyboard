# Threat model and residual-risk register

The user-facing privacy statements live in the root `PRIVACY.md`. This document is the
engineering side: what we protect, from whom, with which control, and which residual risks we
accept. Keep it in sync with the code; when a control or a risk changes, edit the row.

## 1. Scope

An offline input method changes the usual mobile threat model. The network group reduces to one
obligation: the app never holds `android.permission.INTERNET`, and that absence is checked on the
source manifest, on the built APK, and at runtime on a device. The remaining groups carry the
weight: storage (the learned personal data is the only persisted sensitive data), platform (an IME
is a privileged system component, and every InputConnection call has an unauthenticated host app
on the other end), code (binary parsers read on-device files), and privacy.

System: a fork of Simple Keyboard / AOSP LatinIME (Java upstream code, new code in Kotlin), package
`org.tatarkeyboard.ime`, pure JVM (no NDK), no third-party runtime dependencies, exactly one
permission (`VIBRATE`).

## 2. Assets, in order of sensitivity

1. **The keystroke stream in every app**: messages, passwords, one-time codes. Every keystroke of
   every app passes through the IME process by design; the question is whether any of it leaves
   the process. Every control below serves this asset.
2. **The personal dictionary**: saved words, learned word pairs, learned emoji, recent emoji. It
   reveals names, addresses, slang and habits, and it is the only persisted sensitive data. It is
   minimized in the file formats: usage counters instead of timestamps, words instead of
   sentences, no host app or field context. Every store is capped (constants in `TpersFormat`,
   `TpersbFormat`, `TpersemFormat` and `RecentEmojiList.MAX_ENTRIES`).
3. **The clipboard.** Since Android 10 the default IME may read the clipboard at any time. This
   keyboard reads it only when the user taps the paste key (`RichInputConnection.pasteClipboard`).
   It is never stored and never learned from (see the learning row in §5), and a paste above
   `RichInputConnection.MAX_DIRECT_PASTE_CHARS` is handed to the editor's own paste action
   instead of being committed through the IME.
4. **Surrounding text**: the window of the host field's text that the platform gives the IME for
   predictions, capped at `Constants.EDITOR_CONTENTS_CACHE_SIZE` characters around the cursor. It
   lives only in the in-memory editor cache, is cleared at every session boundary, is never written
   to disk, and is never read from password fields.
5. **User trust in the offline claim.** A single observed outbound connection would invalidate
   the product, hence the three-level evidence in §7.

## 3. Trust boundaries

1. **Host apps over InputConnection.** Every `EditorInfo`, every surrounding-text answer and every
   InputConnection call comes from an unauthenticated peer. It may lie, return malformed or null
   text, throw, churn start/finish-input rapidly, or return oversized answers.
2. **Lock screen / Direct Boot.** Before the first unlock after boot, credential-encrypted storage
   is unavailable. The keyboard must still type (it starts with the system) without touching
   anything personal. After the first unlock the stores are readable, but the keyguard can still
   be shown with the keyboard on top (a lock-screen quick reply): a person holding the locked
   device must not see or add to anything learned there.
3. **Backup channels.** Since Android 12, cloud backup and device-to-device transfer are separate
   channels; closing one leaves the other open.
4. **Overlays / tapjacking.** Other apps drawing over IME-owned UI to redirect or observe touches.
5. **Accessibility.** A service holding the accessibility permission sees the rendered UI,
   including the suggestion strip.
6. **logcat.** Process logs are readable through adb, bug reports and, on old Android versions,
   other apps.
7. **The asset pipeline.** `scripts/*.py` turn corpora into the bundled dictionaries and bigram
   tables; a poisoned corpus or generator would reach every device.
8. **The build supply chain.** The Gradle distribution, AGP/KGP and their dependencies, GitHub
   Actions, the SDK build-tools, the signing key.

## 4. Attacker classes

1. **Passive network eavesdropper** (carrier, state firewall, compromised Wi-Fi). Defeated by
   design: nothing is transmitted. The obligation is to prove the absence (§7, network row).
2. **Malicious host app.** Controls its end of the InputConnection: hostile `EditorInfo` and
   surrounding text, throwing methods, session churn, very large pastes. Cannot read the IME's
   memory or files; can only stress the boundary.
3. **Physical-access attacker.** Holds the device, locked or unlocked; reads the screen, browses
   settings, attempts backup extraction.
4. **Corrupted local storage.** Bit rot, interrupted writes, or tampered store and dictionary
   files. Also the delivery route for the CVE-2015-4641/4642 class of the upstream code (§9).
5. **Compromised dependency or build tool.** A poisoned artifact in the Gradle/AGP/SDK chain, a
   hijacked CI action, a swapped wrapper.

## 5. Controls

| Threat | Control | Check |
|---|---|---|
| Keystroke egress / passive eavesdropper | No INTERNET permission; no third-party runtime dependencies (no SDK that could send data); no network code in the tree | `scripts/check-no-internet.sh` on two levels (source manifest, then `aapt2 dump permissions` on the built APK), run three times in CI (source, debug APK, release APK) and inside `scripts/release_check.sh`; the `artifact.permissions` check in `release_check.sh` pins the exact permission set `[VIBRATE]`; `scripts/device-netstats-proof.sh` asserts zero rx/tx bytes for the app's UID over a scripted mixed session on a device |
| Malicious host app | Surrounding text validated (out-of-range or inverted selection empties the caches; negative selection reports become `INVALID_CURSOR_POSITION`); host answers clamped to the cache window at every writer; editor calls that throw degrade silently (only `RuntimeException` is caught); large pastes go through the editor's `performContextMenuAction`; every batch edit in try/finally; dead-editor guards before any mutation; background reloads coalesced and applied on the UI thread; NaN/Infinity rejected in glide paths and strip hit-testing | `RichInputConnectionRobustnessTest`, `HostileHostRobustnessTest`, `HostileHostSuggestionChurnTest`, `BatchEditPairingContractTest`, `PointerTrackerQueueTest`, `GlidePathTest`, `SuggestionStripStateTest` |
| Physical access / locked device | `directBootAware="true"` on the IME service, gated by `UserManager.isUserUnlocked()` (unknown means locked): personal stores, recent emoji, pending counters and salts live in credential-protected `noBackupFilesDir` and are neither read nor written before unlock; the bundled dictionaries inflate into device-protected storage (`createDeviceProtectedStorageContext` in `AndroidDictionaryStorageFactory.kt` and `AndroidBigramStorageFactory.kt`), so typing works before unlock without exposing anything learned; while the keyguard is shown, also after the first unlock (`LatinIME.isKeyguardLocked`, unknown means locked), the suggestion strip, autocorrect and glide typing are off, nothing is learned (the learning predicate includes the strip's eligibility) and the Recent tab is empty and not added to (`RecentEmojiGateState.allowsDisplay`, `allowsRecording`); `FLAG_SECURE` on the settings activity and on the dialogs that show personal content (`DialogUtils.securePersonalContent`); obscured-touch filtering on every dialog (`DialogUtils.filterObscuredTouches`) | `DialogObscuredTouchContractTest`; the unlock checks in `PersonalStorePrivacyTest` and the other `*PrivacyTest` suites; `LockScreenPrivacySourceContractTest`; the keyguard cases in `RecentEmojiStoreTest` |
| Backup extraction | `android:allowBackup="false"` and `dataExtractionRules` (`app/src/main/res/xml/data_extraction_rules.xml`) as a whitelist: no `<include>` element, and both `cloud-backup` and `device-transfer` exclude all seven data domains whole; `fullBackupContent` must not return | `check-no-internet.sh` level 2 on the built APK: literal `allowBackup=false`, the rules file resolved through its resource id (survives release resource shrinking), no include, full domain set in both sections; `BackupWhitelistSourceContractTest` |
| Corrupted storage / hostile binary file | Validators that reject any malformed input for all five binary formats (`TdictValidator`, `TatBigrValidator`, `TpersValidator`, `TpersbValidator`, `TpersemValidator`); pinned size and SHA-256 of every bundled dictionary and bigram table in `DictionaryStorageContracts.kt` and `BigramStorageContracts.kt`, plus the dictionary-to-bigram-table SHA-256 link; an unreadable personal store is quarantined, never silently deleted, with bounded salvage reads; hard caps on every store and file | `artifact.asset_pins`, `artifact.tree_assets`, `artifact.emoji_assets` in `release_check.sh`; `rebuild_assets.py --check --allow-known-drift` (also run by `release_check.sh`); the `*ValidatorTest` suites; seeded parser fuzzing of all five validators (`SeededFuzzHarness` and the `*ValidatorFuzzTest` suites) |
| Compromised dependency / build tool | No third-party runtime dependencies: only this code and the framework run in the APK; PGP and SHA-256 verification of the build-time dependency graph with the public keys committed (`gradle/verification-metadata.xml`, `gradle/verification-keyring.gpg`, `gradle/verification-keyring.keys`); `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`; pinned SDK build-tools (`scripts/build-tools-pin.sh`); reproducible builds (`dependenciesInfo { includeInApk = false }` in `app/build.gradle`); CI actions pinned by commit SHA; no CI artifact upload on pull requests | CI job `reproducible` (byte-compares two clean unsigned builds); `scripts/release_pack.sh` packs twice and compares; `artifact.signature` in `release_check.sh` (exactly one signer, equal to the release certificate); `artifact.critical_resources` (resources read by reflection survive shrinking); `artifact.arsc_stored`; `artifact.no_secrets` (tracked tree and APK scanned for keystores, private keys and token patterns; also run in CI on the unsigned release APK) |
| logcat and other side channels | Logs carry metadata only: never typed text, field contents, cursor positions or the host package name; cursor traces sit behind the compile-time `TRACE` flag | `LogSafetySourceContractTest`: pins the reviewed set of log call sites in the input pipeline packages and fails on any new one |
| Learning from the wrong context | One predicate, `PersonalLearningGates.mayLearn`, gates every write to the three personal stores: field eligibility (password, visible password, e-mail, URI, filter, no-suggestions fields and `IME_FLAG_NO_PERSONALIZED_LEARNING`) and personal dictionary on and unlocked and not a postal address and not paused (incognito). Pasted text is not learned as a word or as the second word of a pair: the paste key marks the run dirty before it inserts the clipboard (`SuggestionsController.onClipboardPaste`), a paste through the editor's own menu is an unexpected selection change that breaks the run (`LatinIME.onUpdateSelection` → `SuggestionsController.onSelectionChanged`), and `CleanRunMachine` discards a word whose first observation is longer than one keystroke. Pause learning also stops recording recent emoji (`RecentEmojiGateState`). Only the keyboard's own emoji insertions teach learned emoji. The editor cache is cleared at every session boundary (`onFinishInputInternal`, `onWindowHidden` in `LatinIME.java`); password fields are never re-read (the three `reloadTextCache` call sites in `LatinIME` check for a password field and clear instead) | `PersonalLearningGatesTest`, `EditorTextCachePrivacySourceContractTest`, the paste cases in `PersonalLearningRunTest` and `PersonalBigramRunTest`, the incognito case in `RecentEmojiStoreTest`, `EditorInfoPrivacyMatrixTest` (field classes × learning, recents, strip and cache re-read) |
| Asset-pipeline poisoning | Corpora come from named, licensed sources (download and measurement scripts in `research/corpus/`); generation is deterministic, standard-library-only Python; every corpus file the rebuild reads has a pinned size and SHA-256 in `data/corpus-manifest.json`, checked before any build step; every bundled dictionary and bigram table has a pinned size and SHA-256, so a silent change anywhere in the pipeline fails before release | `rebuild_assets.py --check --allow-known-drift`, with known drift pinned to exact counts in `scripts/known_asset_drift.json`; the pipeline suites `tests/*/test_*.py` |

## 6. Deliberate decisions

- **`directBootAware="true"` with unlock gating.** The IME must load before the first unlock (it
  starts with the system, and the user may type a PIN). Bundled dictionaries sit in
  device-protected storage and are readable before unlock; everything learned sits in
  credential-protected `noBackupFilesDir` behind the `isUserUnlocked` check and is untouched until the first unlock.
  Dropping `directBootAware` is not planned.
- **The IME window has no `filterTouchesWhenObscured`.** Key previews and popups are child windows
  of the same window and would be filtered with it. Since Android 8.0 the platform blocks app
  overlays over the IME window. The absence is pinned by
  `SettingsTapjackingSourceContractTest.imeWindowLayoutsStayUnflagged`; every dialog does filter
  obscured touches.
- **`SetupActivity` and `SettingsActivity` are exported.** The launcher icon and the system
  IME-settings entry point require it; neither reads intent extras. `SettingsHostActivity`, the
  broadcast receiver and the IME service (behind `BIND_INPUT_METHOD`) are not exported. The exact
  exported set is checked by `artifact.exported_surface` in `scripts/release_check.sh`, in CI
  on every push and in the release check.
- **Logging is metadata-only, not absent.** `Log.e`/`Log.i` stay for diagnosis; typed text, field
  contents, cursor positions and host package names are never logged, enforced by
  `LogSafetySourceContractTest`.
- **The "Saved words" screen has no separate password.** `FLAG_SECURE` keeps it out of screenshots
  and the recent-apps thumbnail; it cannot keep out a person holding the unlocked phone. The
  physical-access attacker is accepted, and `PRIVACY.md` says so.
- **No `IME_FLAG_NO_PERSONALIZED_LEARNING` on Android 7.0/7.1.** The flag exists from API 26; on
  API 24/25 no app can set it, so those fields are protected only by the other `mayLearn`
  conditions (password and private field types, postal addresses, the personal dictionary being
  off by default). Disclosed in `PRIVACY.md`; the gap closes with `minSdk` 26.

## 7. OWASP MASVS mapping

| MASVS group | Priority | What it means for this app |
|---|---|---|
| STORAGE | High | The only persisted sensitive data are the personal stores: credential-protected `noBackupFilesDir`, unlock-gated, capped, quarantined instead of deleted; words not yet learned are kept only as salted, truncated SHA-256 counters (`PendingCounters.kt`) until they cross the learning threshold |
| PLATFORM | High | Hardening against hostile InputConnection peers, the backup whitelist on both channels, the Direct Boot split, the keyguard gate, the exported-component check, obscured-touch filtering and `FLAG_SECURE` |
| CODE | High | Binary parsers that reject malformed input, pinned bundled files, seeded parser fuzzing, source-contract tests for each invariant, error-prone and the lint baseline in the build |
| PRIVACY | High | Minimization in the formats (counters not timestamps, words not sentences, no app or field context), pause learning (incognito), per-language stores, everything visible and erasable on the "Saved words" screen |
| NETWORK | Not applicable; absence is proven | Two-level `check-no-internet.sh` (source and built APK, in CI and in `release_check.sh`), the exact-permission check `[VIBRATE]`, and the runtime check `scripts/device-netstats-proof.sh`: per-UID `dumpsys netstats detail` counters before and after a scripted session (Tatar and Russian typing, suggestion taps, emoji panel and search) must show zero rx and tx bytes |
| CRYPTO | Low to medium | Small surface, nothing custom: 16-byte `SecureRandom` salts, salted truncated SHA-256 for pending counters, platform file-based encryption at rest. No app-managed keys, no TLS |
| AUTH | Not applicable | No accounts, sessions or remote endpoints |
| RESILIENCE | Not applicable; reproducibility instead of anti-tamper | Root detection or anti-debugging adds nothing to an open-source offline IME. Integrity comes from reproducible builds (CI job `reproducible`), the single-signer check and the pinned bundled files: anyone can rebuild the tree and compare it with the published APK |

## 8. Residual-risk register

Every accepted risk, with the reason and the event that should trigger a review. Resolved risks
are listed after the table with the control that keeps them closed.

| Risk | Why accepted | Review when |
|---|---|---|
| Read-only build-tools consumers fall back to the newest installed version | The byte-producing pack steps use the single pinned version from `scripts/build-tools-pin.sh` with no fallback; the read-only consumers (`aapt2 dump`, `apksigner verify`) fall back when the pinned directory is missing, and their output does not depend on the version | A byte-producing step gains the fallback; the pinned version changes |
| Byte-determinism of the signed pack is checked locally, not in CI | Signing is a local maintainer step; `release_pack.sh` packs twice and compares, and CI compares the unsigned builds | A double-run mismatch; signing moves into CI |
| Suggestion strip and emoji search are visible to accessibility services | That is how TalkBack works; services with the accessibility permission are trusted by the platform to see the rendered UI | A change in the Android accessibility model |
| IME window does not filter obscured touches | Previews and popups are child windows and would break; the platform blocks overlays over the IME window since Android 8.0; the absence is pinned | A new top-level IME window; an overlay-policy change |
| The Enter key shows the host's `EditorInfo.actionLabel` | Platform contract: the label is the host app's own text; every keyboard shows it | A platform contract change |
| The layout picker lists other IMEs' display names | Public system metadata, the same list system settings shows | A platform metadata-policy change |
| Fork-specific `RuntimeException` subclasses | Follows the androidx pattern of domain-specific unchecked exceptions; nothing swallows them except the documented editor-call guard in `RichInputConnection` | A catch-all appears anywhere else |
| `gradlew` differs from the official Gradle wrapper script | Diffed against upstream: comment-only changes and one `DEFAULT_JVM_OPTS` change, no difference in executable logic | Any wrapper upgrade: diff against upstream again |
| Some frequent conversational words have no entry in the bigram tables | A data-coverage limit, not a defect: heads without an in-dictionary partner are dropped by the packer; the exact counts are pinned in `scripts/known_asset_drift.json` | Any change in the pinned counts (`rebuild_assets.py --check` fails) |
| No emoji suggestion under the cursor without a trailing space | Product decision: emoji are offered only after a finished word | User demand after publication |
| Lossy frequency compression in the dictionaries not used | Separate product decision; the APK is well within its size budget | APK size pressure |
| Android 7.0/7.1 cannot signal `IME_FLAG_NO_PERSONALIZED_LEARNING` | The flag exists from API 26; only the other `mayLearn` conditions protect those fields; disclosed in `PRIVACY.md` | `minSdk` 26: delete this row |
| The "Saved words" screen has no in-app lock | Physical-access attacker accepted; `FLAG_SECURE` covers capture, not a person holding the phone; stated in `PRIVACY.md` | A decision to add an in-app lock |
| Static state in `PointerTracker` | Upstream AOSP code; a rewrite risks more regressions than it removes | Evidence of a regression caused by it |
| TalkBack speech is checked by ear, manually | The mechanics are covered by tests; listening to the output needs a person and a device | Every release |
| `SettingsActivity` is exported | Required by the system IME-settings entry point; reads no extras | Pinned by `artifact.exported_surface`; any drift fails CI and the release check |
| Hard-disabled settings rows ignore taps without explanation | Rows disabled by a switch the user can flip explain themselves with a toast; hard-disabled rows (managed restriction, last remaining language) have no reason to show | The next change to settings strings |
| No scrollbars in settings | Style decision (`android:scrollbars="none"` in `settings_screen.xml`) | A design pass |
| error-prone `ClassInitializationDeadlock` on `KeyboardActionListener.EMPTY_LISTENER` | Unreachable in practice: both classes are initialized only on the UI thread | Initialization moves off the UI thread |
| Generic confirmation dialogs (clear all, erase, discard quarantined file) have no `FLAG_SECURE` | They show no personal content, so a capture reveals nothing | A dialog showing personal content without `DialogUtils.securePersonalContent` (pinned by `DialogObscuredTouchContractTest`) |
| A field session started while unlocked keeps its suggestion strip if the device locks without the editor restarting input | The strip's eligibility is computed at each field start and language change; learning and recent emoji re-check the keyguard at every write and read, so nothing is learned or shown from the Recent tab; the strip shows what the same field showed a moment earlier | A platform change that keeps an editor bound across the keyguard |
| No suggestions or glide typing on the lock screen, even from the bundled dictionaries | The strip would also carry learned words and pairs; separating bundled from learned candidates in every engine source is disproportionate for lock-screen replies | User demand for lock-screen suggestions |
| A pasted word can be the context of a learned pair typed after it | Like any text already in the field, the word before the cursor is a context, not a learned word; only the second word of a pair is shown as a suggestion | A change that shows pair contexts as suggestions |
| The release build is `profileable` by the shell | Needed for Macrobenchmark and the baseline profile on release builds. It lets an adb host the user authorized trace the process and take a heap dump (`am dumpheap`), which can hold text in memory at that moment; such a host can already read the screen and every log | A change in what `profileable` exposes; the benchmarks move to a separate build type |

Resolved, with the control that keeps them closed:

- **Keyboard-theme contrast below WCAG AA (the action-key labels and the dark hint text).**
  Every informative glyph/background pair of both palettes holds AA for text and the non-text
  floor for state icons; pinned by `ThemeContrastContractTest`.
- **Two resident glide word indexes (one per warm engine).** At most one index is resident across
  languages, and it is released when the keyboard is idle; pinned by
  `GlideIndexResidencySourceContractTest` and `MappedDictionaryEngineGlideTest`.
- **Floating CI action tags.** Every action in `.github/workflows/ci.yml` is pinned by commit SHA
  with a version comment; re-pin on each update.
- **No dependency verification.** `gradle/verification-metadata.xml` with committed public keys;
  regenerate on key expiry or rotation (procedure in `AGENTS.md`).
- **Cursor positions and host package name in logs.** Behind the `TRACE` flag or removed; enforced
  by `LogSafetySourceContractTest`.
- **Recent-emoji file read without a size cap.** `RecentEmojiStore.MAX_MEDIUM_BYTES`, checked
  before reading; `AtomicRecentEmojiFileOpsTest`.
- **`FLAG_SECURE` missing on personal-content dialogs.** `DialogUtils.securePersonalContent`;
  `DialogObscuredTouchContractTest.personalContentDialogsAreSecureFromCapture`.
- **Sentence-start assets outside the release checks.** `artifact.tree_assets` compares the full
  contents of `assets/dictionaries/` and `assets/bigrams/` with the tree.
- **`androidx.customview` runtime dependency.** Removed; the app has no runtime dependencies.
- **Signing keystore in the repository root.** Not in the tree; `artifact.no_secrets` keeps
  keystores and private keys out of the tracked tree and the APK.
- **Corpus inputs of the asset pipeline not pinned.** `data/corpus-manifest.json` pins the size
  and SHA-256 of every corpus file the rebuild reads; `rebuild_assets.py` verifies them before
  any step and stops without writing output on a mismatch; `tests/rebuild_assets/`.

## 9. Incidents behind these controls

| Incident | What happened | Control here |
|---|---|---|
| Citizen Lab, "The Not-So-Silent Type" (2024) | Eight of nine cloud keyboards analyzed sent keystrokes in a form a passive network eavesdropper could decrypt | No network at all, proven on three levels (§5, §7) |
| CVE-2015-4641 / CVE-2015-4642 (2015) | Remote code execution in LatinIME's native dictionary parser through a crafted dictionary; this fork descends from that code | Pure JVM (memory corruption becomes at most denial of service); validators that reject malformed input, pinned bundled files, quarantine instead of delete, seeded parser fuzzing |
| ai.type (2017) | A database of 31 million users' collected keyboard data was left exposed | Nothing is collected or sent; minimized formats (counters, words, no context) |
| GO Keyboard (2017) | Found sending user data contrary to its policy, through network-behavior analysis | The same analysis as a check: `scripts/device-netstats-proof.sh` |
| Kika / CM Keyboard (2018) | Keyboards shipped SDK code implicated in ad fraud | No third-party runtime code; verified build-time dependencies |
| SwiftKey (2016) | A sync bug showed one user's learned predictions to other users | No sync; stores are per Android user and per language; pause learning (incognito) stops every write; learning only through `mayLearn` |
