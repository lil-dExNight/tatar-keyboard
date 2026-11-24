# Threat model and residual-risk register

The user-facing privacy statements live in the root `PRIVACY.md`. This document is the
engineering side: what is protected, from whom, with which control, and which residual risks are
accepted. Keep it in sync with the code; when a control or a risk changes, edit the row.

## 1. Scope

An offline input method changes the usual mobile threat model. The network group reduces to one
obligation: the app never holds `android.permission.INTERNET`, and that absence is checked on the
source manifest, on the built APK, and at runtime on a device. The remaining groups are
storage (the learned personal data is the only persisted sensitive data), platform (an IME
is a privileged system component, and every InputConnection call has an unauthenticated host app
on the other end), code (binary parsers read on-device files), and privacy.

The app is a fork of Simple Keyboard / AOSP LatinIME (Java upstream code, new code in Kotlin), package
`org.tatarkeyboard.ime`, pure JVM (no NDK), no third-party runtime dependencies, exactly one
permission (`VIBRATE`).

## 2. Assets, in order of sensitivity

1. The keystroke stream in every app: messages, passwords, one-time codes. Every keystroke of
   every app passes through the IME process by design; the question is whether any of it leaves
   the process. Every control below serves this asset.
2. The personal dictionary: saved words, learned word pairs, learned emoji, refused
   corrections, the sentence-initial overlay, plus the recent emoji and the user's text shortcuts. It
   reveals names, addresses, slang and habits, and it is the only persisted sensitive data. It is
   minimized in the file formats: usage counters instead of timestamps, words instead of
   sentences, no host app or field context. Every store is capped (constants in `TpersFormat`,
   `TpersbFormat`, `TpersemFormat`, `TsentFormat`, `TrefFormat`, `TcutFormat` and
   `RecentEmojiList.MAX_ENTRIES`). The
   user-facing backup file
   (keyboard settings → "Backup") carries the words, pairs, learned emoji, refused corrections and
   text shortcuts plus the settings, minus the recency caches (recent emoji) and the relearnable
   derivatives (the sentence-initial overlay, pending counters and salts). It is either a plaintext
   ZIP or, by explicit user choice, a password-encrypted wrapper around the same ZIP;
   it is the only artifact of this data that leaves the app-private storage, and it moves only
   because the user picks a place for it.
3. The clipboard. Since Android 10 the default IME may read the clipboard at any time. This
   keyboard reads it in two places, both only while the keyboard window is shown and never while
   the keyguard is: the paste key, an explicit user action, and the recent-clip cell. A fresh
   text clip can be offered in the strip's first cell (`RecentClipCell`), held in memory with a
   short freshness window, never offered in password fields or where suggestions may not run —
   for the offer the clipboard is not even read until the field proves eligible
   (`LatinIME.capturePrimaryClipForOffer`) —
   never offered when the source app marked the clip sensitive
   (`ClipDescription.EXTRA_IS_SENSITIVE`, which password managers set); a clip whose set time is
   unprovable, future-stamped or already stale is refused before its text is read, and a commit
   marks the run
   non-learnable. Nothing from the clipboard is ever stored or learned from (see the learning row
   in §5).
4. Surrounding text: the window of the host field's text that the platform gives the IME for
   predictions, capped at `Constants.EDITOR_CONTENTS_CACHE_SIZE` characters around the cursor. It
   lives only in the in-memory editor cache, is cleared at every session boundary, is never written
   to disk, and is never read from password fields.
5. User trust in the offline claim. A single observed outbound connection would invalidate
   the product, hence the three-level evidence in §7.

## 3. Trust boundaries

1. Host apps over InputConnection. Every `EditorInfo`, every surrounding-text answer and every
   InputConnection call comes from an unauthenticated peer. It may lie, return malformed or null
   text, throw, churn start/finish-input rapidly, or return oversized answers.
2. Lock screen / Direct Boot. Before the first unlock after boot, credential-encrypted storage
   is unavailable. The keyboard must still type (it starts with the system) without touching
   anything personal. After the first unlock the stores are readable, but the keyguard can still
   be shown with the keyboard on top (a lock-screen quick reply): a person holding the locked
   device must not see or add to anything learned there.
3. Backup channels. Since Android 12, cloud backup and device-to-device transfer are separate
   channels; closing one leaves the other open. The user-facing SAF export is a third channel of a
   different kind: the document provider on the other side of the picker is an unauthenticated app
   that can serve any bytes, so a picked plaintext ZIP or encrypted container is untrusted input.
   An encrypted container is accepted only after AES-GCM authentication; encryption does not make
   the provider trusted or prevent it from modifying, replacing or deleting the file.
4. Overlays / tapjacking. Other apps drawing over IME-owned UI to redirect or observe touches.
5. Accessibility. A service holding the accessibility permission sees the rendered UI,
   including the suggestion strip.
6. logcat. Process logs are readable through adb, bug reports and, on old Android versions,
   other apps.
7. The asset pipeline. `scripts/*.py` turn corpora into the bundled dictionaries and bigram
   tables; a poisoned corpus or generator would reach every device.
8. The build supply chain. The Gradle distribution, AGP/KGP and their dependencies, GitHub
   Actions, the SDK build-tools, the signing key.
9. Text services (spell checker). While the user has selected this app's spell checker, the
   platform sends the text of the edited field of any app to the exported service, whichever
   keyboard is active; `BIND_TEXT_SERVICE` restricts binding to the system. The service does not
   see the field type or the host app, so every submitted word is unauthenticated input, and the
   boundary is crossed without the user opening anything. To answer, it reads bundled language
   assets and, only after the first unlock, immutable personal-dictionary snapshots. Before that
   unlock it uses the bundled dictionaries and acceptor without opening the personal dictionary.
   Submitted text and verdicts live only for the call: the service writes, learns, logs and caches
   none of them.

## 4. Attacker classes

1. Passive network eavesdropper (carrier, state firewall, compromised Wi-Fi). Defeated by
   design: nothing is transmitted. The obligation is to prove the absence (§7, network row).
2. Malicious host app. Controls its end of the InputConnection: hostile `EditorInfo` and
   surrounding text, throwing methods, session churn, very large pastes. Cannot read the IME's
   memory or files; can only stress the boundary.
3. Physical-access attacker. Holds the device, locked or unlocked; reads the screen, browses
   settings, attempts backup extraction.
4. Corrupted local storage. Bit rot, interrupted writes, or tampered store and dictionary
   files. Also the delivery route for malicious archive input (§9).
5. Compromised dependency or build tool. A poisoned artifact in the Gradle/AGP/SDK chain, a
   hijacked CI action, a swapped wrapper.

## 5. Controls

| Threat | Control | Check |
|---|---|---|
| Keystroke egress / passive eavesdropper | No INTERNET permission; no third-party runtime dependencies (no SDK that could send data); no network code in the tree | `scripts/check-no-internet.sh` on two levels (source manifest, then `aapt2 dump permissions` on the built APK), run three times in CI (source, debug APK, release APK) and inside `scripts/release_check.sh`; the `artifact.permissions` check in `release_check.sh` pins the exact permission set `[VIBRATE]`, in CI on every push and in the release check; `scripts/device-netstats-proof.sh` asserts zero rx/tx bytes for the app's UID over a scripted mixed session on a device |
| Malicious host app | Surrounding text validated (out-of-range or inverted selection empties the caches; negative selection reports become `INVALID_CURSOR_POSITION`); host answers clamped to the cache window at every writer; editor calls that throw degrade silently (only `RuntimeException` is caught); outside a batch edit every editor call re-reads the bound field's connection (`RichInputConnection.refreshConnection`), so no call reaches a field that is no longer bound, and a batch keeps the connection it began on; large pastes go through the editor's `performContextMenuAction`; every batch edit in try/finally; dead-editor guards before any mutation; background reloads coalesced and applied on the UI thread, the reader using only the connection captured at the reload's gate check; a space-bar trackpad ends without restoring its selection when the keyboard closes or the field changes (`PointerTrackerQueue.Element.abandonSpaceTrackpad`, `LatinIME.resetSpaceTrackpad`); NaN/Infinity rejected in glide paths and strip hit-testing | `RichInputConnectionRobustnessTest`, `RichInputConnectionBindingTest`, `HostileHostRobustnessTest`, `HostileHostSuggestionChurnTest`, `BatchEditPairingContractTest`, `PointerTrackerQueueTest`, `SpaceTrackpadIntegrationContractTest`, `GlidePathTest`, `SuggestionStripStateTest`, `RecentClipCellTest` and `RecentClipControllerTest` (the clip offer window), `RecentClipSourceContractTest`, `InlineAutofillSourceContractTest` |
| Text-services input (spell checker) | The exported service keeps no request state: for each bundled language it acquires a read-only lease on an already published dictionary, validates and maps that file, or falls back to the pinned APK asset when the file is unavailable or invalid. It never publishes, cleans or quarantines catalog files; the IME is the only writer. Device-protected dictionary data, reviewed proper names and the process-wide acceptor are available before unlock. Only when `UserManager.isUserUnlocked` confirms the first unlock (an unavailable answer means locked) does it read immutable personal-dictionary snapshots; before that unlock it does not touch the personal store. Read-only buffers and a monitor around each prefix index's mutable scratch make concurrent binder calls safe. Submitted text and verdicts are neither retained nor cached beyond the call; the service performs no file, preference or personal-store writes, learns and logs nothing, and starts no keyboard-view, editor or learning machinery. Turning the Tatar spell-checker switch off accepts every word without loading a dictionary. `directBootAware="true"` permits the bundled-only path during Direct Boot; `BIND_TEXT_SERVICE` is the service's only binding guard, and only the system holds that permission | `artifact.exported_surface` pins the service, its permission and its filter; `SpellCheckerManifestSourceContractTest` pins the component and Direct Boot declaration; the spell-checker stateless source-contract test rejects logging, file and preference writes, personal-store mutations and keyboard-view, editor or learning construction; Direct Boot and concurrent-request JVM tests cover the unlock gate and read-only snapshots; the platform does not send password fields to spell checkers |
| Physical access / locked device | `directBootAware="true"` on the IME service, gated by `UserManager.isUserUnlocked()` (unknown means locked): personal stores, recent emoji, pending counters and salts live in credential-protected `noBackupFilesDir` and are neither read nor written before unlock; the bundled dictionaries inflate into device-protected storage (`createDeviceProtectedStorageContext` in `AndroidDictionaryStorageFactory.kt` and `AndroidBigramStorageFactory.kt`), so typing works before unlock without exposing anything learned; while the keyguard is shown, also after the first unlock (`LatinIME.isKeyguardLocked`, unknown means locked), the suggestion strip, autocorrect and glide typing are off, nothing is learned (the learning predicate includes the strip's eligibility), the Recent tab is empty and not added to (`RecentEmojiGateState.allowsDisplay`, `allowsRecording`), the paste key and the edit-menu paste do nothing (`LatinIME.onBeforeClipboardPaste`), and the one-shot "turn on suggestions" offer, the only path to that setting outside the settings screen, does not appear (`OfferEnvironment.isKeyguardLocked`, pinned by `SuggestionsOfferControllerTest`); `FLAG_SECURE` on the settings activity and on the dialogs that show personal content (`DialogUtils.securePersonalContent`); obscured-touch filtering on every dialog (`DialogUtils.filterObscuredTouches`) | `DialogObscuredTouchContractTest`; the unlock checks in `PersonalStorePrivacyTest` and the other `*PrivacyTest` suites; `LockScreenPrivacySourceContractTest`; the keyguard cases in `RecentEmojiStoreTest`; `PasswordKeyObscuringTest` (TalkBack reads password keys as a dot on Android 7.x); `AtomicRecentEmojiFileOpsTest`; `ThemeContrastContractTest` (personal screens meet contrast) |
| Backup extraction | `android:allowBackup="false"` and `dataExtractionRules` (`app/src/main/res/xml/data_extraction_rules.xml`) as a whitelist: no `<include>` element, and both `cloud-backup` and `device-transfer` exclude the `file`, `database`, `sharedpref`, `external`, `device_file`, `device_database` and `device_sharedpref` domains whole, with no path attribute; the personal stores and recent emoji live in `noBackupFilesDir`, which the platform never backs up; `fullBackupContent` must not return; the Android 16 QPR2 cross-platform mode has no rules section and is tracked in §8 | `check-no-internet.sh` level 2 on the built APK: literal `allowBackup=false`, the rules file resolved through its resource id (survives release resource shrinking), no include, the same domain list in both sections; `BackupWhitelistSourceContractTest` |
| User-carried backup file (SAF export/import) | Both directions run only from the settings screen through the system picker, with no new permission; the user explicitly chooses a plaintext zip or a password-encrypted wrapper around the same zip, whose manifest pins the size and SHA-256 of every entry. Exact magic selects the encrypted import path, and only that path requests a password. PBKDF2-HMAC-SHA256 derives an AES-256-GCM key, all 48 header bytes are authenticated as AAD, and authentication and decryption finish before archive validation or any store mutation; a missing password, wrong password or modified container writes nothing. A password-supplied import accepts only an encrypted container: plaintext bytes on that path are rejected before recovery, and a picker result must be a `content://` URI. The container parameters are specified in [ARCHITECTURE.md](ARCHITECTURE.md#personal-dictionary). Passwords and derived keys remain in memory only, are neither persisted nor logged, and are wiped where applicable. The importer never resolves archive paths against the filesystem: an entry classifies into a store identity (kind + subtype) and the destination file name is re-derived. The importer also demands canonical relative paths (no `..`, absolute, backslash, NUL or empty segments), no duplicates, no unknown paths, per-kind and total size caps read from the actual stream, exact manifest↔zip set equality, per-entry size and SHA-256 match, the supported format version, this app's package, a parseable settings document, and every personal file passing its store's strict binary validator; any failure rejects the whole file with nothing written. Personal files are written through the stores' own temp → fsync → atomic replace sequence on the store's worker, which republishes the live snapshots; settings values, including policy-managed values, cross as a parsed map (a strict XML subset: no DOCTYPE, no entities beyond the predefined five), applied through the SharedPreferences editor so every listener reloads; the restriction cache is neither exported nor imported; after every successful preference replacement during import, rollback or recovery, policy is re-applied before the main-thread subtype reload and notification, so restrictions retain precedence and restored languages take effect. Two privacy switches are combined instead of replaced (`BackupTransfer.combinePrivacySwitches`): the personal dictionary ends up on only when it was on before AND the archive keeps it on, and pause learning ends up on when either side had it paused, so a restore can never silently switch learning on or resume it; rollback reinstalls the exact pre-import state, not a recombined one. The pending counters, salts, quarantined copies, the recent-emoji list and the sentence-initial overlay are not part of a backup. Export and import first open the store of every recognized personal file on the store worker (`BackupTarget.openPersonalStore`), so a damaged file is quarantined by its own store before anything reads it and stays out of the export; a store left not loaded makes the export fail and the import refuse before the journal is written. An export reports success only after a checked write and close, a re-read of the completed document, a byte-for-byte comparison and full archive verification (an encrypted export verifies with the task-held password, then wipes both copies); a failed document write is deleted best effort and reported as a possible partial document, with no atomic-replacement claim across providers. An import first writes a journal with the old and the new state; a process death mid-import is rolled back when the keyboard next starts or the user unlocks (`BackupCoordinator.recoverInterruptedImport`) and when the settings screen opens. The import commits through a durable commit record carrying the SHA-256 of the exact journal bytes, written before any journal byte is swept, so a cleanup whose directory fsync fails cannot lose the rollback evidence of an uncommitted import; a journal covered by a matching record is swept, never rolled back. Recovery fails closed: a journal or commit record that cannot be read or validated stays untouched as the exact evidence and recovery reports failure instead of discarding it, and a later backup is refused until "Erase everything saved" discards the journal, its commit record and their temps before the stores, behind any queued rollback, so a rollback cannot bring erased data back | `BackupArchiveTest` (the path/integrity rejections), `BackupTransferTest` (round-trip byte-equality, restore-replaces, fail-closed, rollback after every mutation, journal discard and fail-closed damaged-journal recovery, store priming and the not-loaded refusal), `EncryptedBackupTransferTest` (encrypted round-trip, wrong-password/tamper rejection and the plaintext-password mismatch), `BackupRollbackMatrixTest` (the full death/refusal matrix on a fake device), `BackupImportJournalFilesTest` (the durable commit record under directory-fsync failures), `PersonalDictionaryErasureTest` (the erase order and the recovery call sites), `PersonalEraseAllTest` (the full-folder sweep), `BackupJsonTest`, `BackupPreferencesXmlTest`, `PersonalReplaceAllTest` (the store swap and live republish) |
| Corrupted storage / hostile binary file | Validators that reject any malformed input for every shipped binary format (`TdictValidator`, `TatBigrValidator`, `TpersValidator`, `TpersbValidator`, `TpersemValidator`, `TcutValidator`, `TrefValidator`, `TsentValidator`), in two tiers for the bundled files: publication and any verify mismatch run the full structural walk (the mismatch deletes the bad file and republishes fresh bytes), while a steady-state activation that finds the published file already present runs the pinned-content tier (file size, header constants, section arithmetic, one streaming SHA-256 pass against the pinned raw SHA-256 — the pinned match authenticates the embedded checksum, so it is not re-verified there) — a size and SHA-256 match against the pinned artifact implies the walk's outcome, so the walk adds value only on never-before-seen bytes; pinned size and SHA-256 of every bundled dictionary and bigram table in `DictionaryStorageContracts.kt` and `BigramStorageContracts.kt`, plus the dictionary-to-bigram-table SHA-256 link; a personal file that fails validation or exceeds its size cap is quarantined, never silently deleted, with bounded salvage reads; a read that fails with an I/O error leaves the file untouched and the store not loaded (no learning, no writes, so nothing overwrites the file from empty memory) until the next prime retries; a `clearAll` before the first unlock leaves the store not loaded the same way; a pending-counter file that does not parse goes to its own quarantine slot without a user notice; recent-emoji replacement uses temp fsync → rename → directory fsync, preserves the previous file on pre-rename failure and keeps the list `dirty` for a later hide retry on post-rename failure; recent-emoji erase succeeds only when the file is durably absent or empty and no temp remains; hard caps on every store and file | `artifact.asset_pins`, `artifact.tree_assets`, `artifact.emoji_assets` in `release_check.sh`; `rebuild_assets.py --check --allow-known-drift` (also run by `release_check.sh`); the `*ValidatorTest` suites, whose pinned-content tier tests reject a tampered published file at activation; `PersonalStoreLoadFailureTest`, `PendingCountersTest`; seeded parser fuzzing of the dictionary, bigram and counter-carrying personal validators (`SeededFuzzHarness` and the `*ValidatorFuzzTest` suites) |
| Compromised dependency / build tool | No third-party runtime dependencies: only this code and the framework run in the APK; PGP and SHA-256 verification of the build-time dependency graph with the public keys committed (`gradle/verification-metadata.xml`, `gradle/verification-keyring.gpg`, `gradle/verification-keyring.keys`); `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`; local release scripts verify the pinned wrapper JAR digest before invoking Gradle; pinned SDK build-tools (`scripts/build-tools-pin.sh`); reproducible builds (`dependenciesInfo { includeInApk = false }` in `app/build.gradle`); CI actions pinned by commit SHA; no CI artifact upload on pull requests. At most one glide word index stays resident across languages and it is released when the keyboard is idle (`GlideIndexResidencySourceContractTest`, `MappedDictionaryEngineGlideTest`) | CI job `reproducible` (byte-compares two clean unsigned builds); `release_pack.sh` verifies the single signed output, and a second local pack is a manual check; `artifact.signature` in `release_check.sh` (exactly one signer, equal to the release certificate); `artifact.critical_resources` (every resource looked up by name survives shrinking: the keyboard XML families, every `!string/` key text in the keyboard XML and the text tables, and the locale names, all kept by `res/raw/keep.xml`); `artifact.arsc_stored`; `artifact.no_secrets` (tracked tree and APK scanned for keystores, private keys and token patterns; also run in CI on the unsigned release APK); `artifact.no_reflection` (the sources carry no reflection or serialization entry point, so the R8 build needs no keep rules; each `Resources.getIdentifier` call site is listed in `scripts/no_reflection_allowlist.txt` with the family it resolves); `artifact.freshness` (the build inputs of `scripts/release-build-inputs.sh` match HEAD with no untracked file, and the APK's recorded commit is HEAD or an ancestor with no build-input change since; `release_pack.sh` refuses to pack a dirty tree); CI runs `size`, `asset_pins`, `emoji_assets`, `tree_assets`, `exported_surface`, `no_secrets`, `permissions`, `critical_resources`, `no_reflection`, `arsc_stored`, `dex_layout` and `profiles` on the unsigned release APK, and `rebuild_assets.py --check --allow-known-drift` as a separate step; `signature` and `freshness` run only locally |
| logcat and other side channels | Logs carry metadata only: never typed text, field contents, cursor positions or the host package name; cursor traces sit behind the compile-time `TRACE` flag | `LogSafetySourceContractTest`: pins the reviewed set of log call sites in the input pipeline packages and fails on any new one |
| Learning from the wrong context | One predicate, `PersonalLearningGates.mayLearn`, gates every write to the personal stores (words, pairs, emoji, refused corrections, the sentence-initial overlay): field eligibility (password, visible password, e-mail, URI, filter, no-suggestions fields and `IME_FLAG_NO_PERSONALIZED_LEARNING`) and personal dictionary on and unlocked and not a postal address and not paused (incognito). Pasted text is not learned as a word or as either side of a pair: the paste key and the edit-menu paste mark the run dirty before the clipboard is inserted (`SuggestionsController.onClipboardPaste`), a paste through the editor's own menu is an unexpected selection change that breaks the run (`LatinIME.onUpdateSelection` → `SuggestionsController.onSelectionChanged`), and `CleanRunMachine` discards a word whose first observation is longer than one keystroke. A typed successor cannot complete a pair with a pasted context; at its own boundary it becomes a trusted context for the following pair. Pause learning also stops recording recent emoji (`RecentEmojiGateState`). Only the keyboard's own emoji insertions teach learned emoji. The editor cache is cleared at every session boundary (`onFinishInputInternal`, `onWindowHidden` in `LatinIME.java`); text typed through the IME can remain in that local memory cache until then, but existing password-field text is never read from the host: both `reloadTextCache` methods in `RichInputConnection` ask `EditorHost.mayReadEditorText()` before any read, which covers the coalesced follow-up and the reload a refused glide commit requests; of the two call sites in `LatinIME`, the field-start fill clears the caches for a password field and binds its connection without a read (`RichInputConnection.onStartInput`), and the selection-driven reload checks for a password field as well; clearing the caches drops a follow-up request folded in before it | `PersonalLearningGatesTest`, `EditorTextCachePrivacySourceContractTest`, `RichInputConnectionBindingTest`, `RichInputConnectionRobustnessContractTest`, the paste cases in `PersonalLearningRunTest` and `PersonalBigramRunTest`, the incognito case in `RecentEmojiStoreTest`, `EditorInfoPrivacyMatrixTest` (field classes × learning, recents, strip and cache re-read) |
| Asset-pipeline poisoning | Corpora come from named sources; the asset notices record their license status (download and measurement scripts in `research/corpus/`); generation is deterministic, standard-library-only Python; every corpus file the rebuild reads has a pinned size and SHA-256 in `data/corpus-manifest.json`, checked before any build step; every bundled dictionary and bigram table has a pinned size and SHA-256, so a silent change anywhere in the pipeline fails before release | `rebuild_assets.py --check --allow-known-drift`, with known drift pinned to exact counts in `scripts/known_asset_drift.json`; the pipeline suites `tests/*/test_*.py` |

## 6. Deliberate decisions

- `directBootAware="true"` with unlock gating. The IME must load before the first unlock (it
  starts with the system, and the user may type a PIN). Bundled dictionaries sit in
  device-protected storage and are readable before unlock; everything learned sits in
  credential-protected `noBackupFilesDir` behind the `isUserUnlocked` check and is untouched until the first unlock.
  Dropping `directBootAware` is not planned.
- The IME window has no `filterTouchesWhenObscured`. Key previews and popups are child windows
  of the same window and would be filtered with it. Since Android 8.0 the platform blocks app
  overlays over the IME window. The absence is pinned by
  `SettingsTapjackingSourceContractTest.imeWindowLayoutsStayUnflagged`; every dialog does filter
  obscured touches.
- `SetupActivity`, `SettingsActivity` and `SpellCheckerSettingsActivity` are exported, and the
  spell checker service is exported behind `BIND_TEXT_SERVICE`. The launcher icon, the system
  IME-settings entry point and the system spell-checker list require it; none of them reads
  intent extras beyond the screen name the host consumes. `SettingsHostActivity`, the
  broadcast receiver and the IME service (behind `BIND_INPUT_METHOD`) are not exported. The exact
  exported set is checked by `artifact.exported_surface` in `scripts/release_check.sh`, in CI
  on every push and in the release check.
- Logging is metadata-only, not absent. `Log.e`/`Log.i` stay for diagnosis; typed text, field
  contents, cursor positions and host package names are never logged, enforced by
  `LogSafetySourceContractTest`.
- The "Saved words" screen has no separate password. `FLAG_SECURE` keeps it out of screenshots
  and the recent-apps thumbnail; it cannot keep out a person holding the unlocked phone. The
  physical-access attacker is accepted, and `PRIVACY.md` says so.
- No `IME_FLAG_NO_PERSONALIZED_LEARNING` on Android 7.0/7.1. The flag exists from API 26; on
  API 24/25 no app can set it, so those fields are protected only by the other `mayLearn`
  conditions (password and private field types, postal addresses, the personal dictionary
  switch). The personal dictionary is on by default on every API level. Disclosed in
  `PRIVACY.md`; the gap closes with `minSdk` 26.
- Backup encryption is optional. Plaintext remains an explicit user choice for carrying the file
  between devices and apps; the settings screen says before export that the file holds learned
  words and that anyone who can open a plaintext backup can read them. Encrypted export uses a
  user-supplied password. There is no password recovery, and neither the password nor the derived
  key is persisted or logged. AES-GCM authentication detects a wrong password or a modified file
  before any store changes. Encrypting an exported file does not protect the app's personal stores
  from a person holding the unlocked device. OS-level backup stays off; the user-carried file is
  the only backup.

## 7. OWASP MASVS mapping

| MASVS group | Priority | What it means for this app |
|---|---|---|
| STORAGE | High | The only persisted sensitive data are the personal stores: credential-protected `noBackupFilesDir`, unlock-gated, capped, quarantined instead of deleted; words not yet learned are kept only as salted, truncated SHA-256 counters (`PendingCounters.kt`) until they cross the learning threshold; deleting an entry also purges it from the quarantined copy and fails closed — a failed cleanup rolls the active file and the snapshot back and reports failure, so a reported deletion can never be undone by a later quarantine restore |
| PLATFORM | High | Hardening against hostile InputConnection peers, the backup whitelist on both channels, the Direct Boot split, the keyguard gate, the exported-component check, obscured-touch filtering and `FLAG_SECURE` |
| CODE | High | Binary parsers that reject malformed input, pinned bundled files, seeded parser fuzzing, source-contract tests for each invariant, error-prone and the lint baseline in the build |
| PRIVACY | High | Minimization in the formats (counters not timestamps, words not sentences, no app or field context), pause learning (incognito), per-language stores, everything erasable at once with "Erase everything saved" on the "Saved words" screen, where words, pairs and learned emoji are also listed: the import journal first, then recent emoji, then every personal store of every supported language and the text shortcuts, then a sweep of the personal directory, reported as a success only when nothing is left (the sentence-initial overlay, capped at `TsentFormat.MAX_SENTSTART_ENTRIES` words per language, and the refused corrections are not listed and are erased with the rest) |
| NETWORK | Not applicable; absence is proven | Two-level `check-no-internet.sh` (source and built APK, in CI and in `release_check.sh`), the exact-permission check `[VIBRATE]`, and the runtime check `scripts/device-netstats-proof.sh`: per-UID `dumpsys netstats detail` counters before and after a scripted session (Tatar and Russian typing, suggestion taps, emoji panel and search) must show zero rx and tx bytes |
| CRYPTO | Low to medium | An app-defined container for optional SAF backups uses platform PBKDF2-HMAC-SHA256 (the platform MAC supplies the standard derivation on API 24 and 25, where the PBKDF2 factory is absent) and AES-256-GCM, with a fresh `SecureRandom` salt and IV for each export; all header bytes are GCM AAD. Pending counters use 16-byte `SecureRandom` salts and salted truncated SHA-256; platform file-based encryption protects data at rest. Backup passwords and derived keys are memory-only, are not logged and are wiped where applicable; there is no password recovery. No TLS |
| AUTH | Not applicable | No accounts, sessions or remote endpoints |
| RESILIENCE | Not applicable; reproducibility instead of anti-tamper | Root detection or anti-debugging adds nothing to an open-source offline IME. Integrity comes from reproducible builds (CI job `reproducible`), the single-signer check and the pinned bundled files: anyone can rebuild the tree and compare it with the published APK |

## 8. Residual-risk register

Every accepted risk is listed with the reason it was accepted and the event that should
trigger a review. Risks that were accepted and then closed live in git history; the tests
that pin them are named in the Check column of section 5.

| Risk | Why accepted | Review when |
|---|---|---|
| Read-only build-tools consumers fall back to the newest installed version | The byte-producing pack steps use the single pinned version from `scripts/build-tools-pin.sh` with no fallback; the read-only consumers (`aapt2 dump`, `apksigner verify`) fall back when the pinned directory is missing, and their output does not depend on the version | A byte-producing step gains the fallback; the pinned version changes |
| Byte-determinism of the signed pack is checked locally, not in CI | Signing is a local maintainer step; CI compares two unsigned builds, and a second local pack is a manual check | A double-run mismatch; signing moves into CI |
| Suggestion strip and emoji search are visible to accessibility services | That is how TalkBack works; services with the accessibility permission are trusted by the platform to see the rendered UI | A change in the Android accessibility model |
| The selected spell checker receives the text of other apps' fields | The platform decides what reaches a spell checker and withholds password fields; the service keeps nothing, so no word it saw can be replayed from the app's storage | A change to the platform's text-services contract |
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
| A plaintext exported backup is readable by anyone who can open it | Plaintext remains an explicit user choice (§6), the file moves only where the user puts it, and the export screen states the exposure before the picker opens | A change to plaintext availability or the export warning |
| Encrypted-backup confidentiality depends on password strength, and a forgotten password has no recovery | The user chooses the password; the app keeps no recovery material and does not persist or log the password or derived key. AES-GCM rejects wrong passwords and modified containers before any store changes, but it cannot strengthen a weak password | A change to the KDF or cipher parameters, password handling or recovery design |
| Static state in `PointerTracker` | Upstream AOSP code; a rewrite risks more regressions than it removes | Evidence of a regression caused by it |
| TalkBack speech is checked by ear, manually | The mechanics are covered by tests; listening to the output needs a person and a device | Every release |
| `SettingsActivity` is exported | Required by the system IME-settings entry point; reads no extras | Pinned by `artifact.exported_surface`; any drift fails CI and the release check |
| Hard-disabled settings rows ignore taps without explanation | Rows disabled by a switch the user can flip explain themselves with a toast; hard-disabled rows (managed restriction, last remaining language) have no reason to show | The next change to settings strings |
| No scrollbars in settings | Style decision (`android:scrollbars="none"` in `settings_screen.xml`) | A design pass |
| Generic confirmation dialogs (clear all, erase, discard quarantined file) have no `FLAG_SECURE` | They show no personal content, so a capture reveals nothing | A dialog showing personal content without `DialogUtils.securePersonalContent` (pinned by `DialogObscuredTouchContractTest`) |
| A host that blocks `getSurroundingText` parks the single cache-reload thread | Typing and local edit tracking keep working; the reload is off the UI thread so there is no ANR; the next answer re-syncs | A reload watchdog |
| The setup wizard's step 1 launches the system input-method settings with an implicit intent | A malicious app can only add a chooser entry next to the system one; nothing is learned and the wizard keeps no state | A decision to launch it package-explicit |
| No suggestions or glide typing on the lock screen, even from the bundled dictionaries | The strip would also carry learned words and pairs; separating bundled from learned candidates in every engine source is disproportionate for lock-screen replies | User demand for lock-screen suggestions |
| The release build is `profileable` by the shell | Needed for Macrobenchmark and the baseline profile on release builds. It lets an adb host that the user authorized trace the process and take a heap dump (`am dumpheap`), which can hold text in memory at that moment; such a host can already read the screen and every log | A change in what `profileable` exposes; the benchmarks move to a separate build type |
| The backup rules do not name the `root` and `device_root` domains | Every file the app writes is in device-protected `files/`, device-protected shared preferences or `noBackupFilesDir`: the first two sit in excluded domains, the platform never backs up the third, and `allowBackup="false"` closes cloud backup on every API level | A new storage location outside those three; a platform change to what `root` covers |
| Android 16 QPR2 cross-platform transfer is not explicitly excluded | `allowBackup="false"` and the existing rules cover cloud backup and device transfer; learned data sits in `noBackupFilesDir`, which the platform excludes, and the new mode has not been device-tested | A platform behavior change or a new file outside the no-backup and excluded domains |
| Cold-companion autocorrect protection covers only the bundled frequency list | The companion check (`CompanionLexicon`) uses the full dictionary once the other language's engine is warm. While it is cold, only the shipped cold-companion list protects its top-frequency words; a valid companion word outside that list can still be corrected, and there is no cold protection before the list loads or if loading fails. Preparing a second full dictionary for every verdict would cost memory and start time. One backspace undoes the correction; with the personal dictionary on, a second undo of the same pair stops it for good | User reports of cross-language corrections; a full-dictionary membership check that needs no warm engine |

## 9. Incidents behind these controls

| Incident | What happened | Control here |
|---|---|---|
| Citizen Lab, "The Not-So-Silent Type" (2024) | Eight of nine cloud keyboards analyzed sent keystrokes in a form a passive network eavesdropper could decrypt | No network at all, proven on three levels (§5, §7) |
| SwiftKey language-pack update (CVE-2015-4640/4641, 2015) | HTTP language-pack delivery and a ZIP directory traversal in the Samsung SwiftKey update path allowed arbitrary file writes and privileged execution; CVE-2015-4642 is an unrelated PHP Windows bug | No language-pack download or update channel; the only archive importer derives every destination from a classified entry, rejects non-canonical paths, and validates every entry before mutation; `BackupArchiveTest` |
| ai.type (2017) | A database of 31 million users' collected keyboard data was left exposed | Nothing is collected or sent; minimized formats (counters, words, no context) |
| GO Keyboard (2017) | Found sending user data contrary to its policy, through network-behavior analysis | The same analysis as a check: `scripts/device-netstats-proof.sh` |
| Kika / CM Keyboard (2018) | Keyboards shipped SDK code implicated in ad fraud | No third-party runtime code; verified build-time dependencies |
| SwiftKey (2016) | A sync bug showed one user's learned predictions to other users | No sync; stores are per Android user and per language; pause learning (incognito) stops every write; learning only through `mayLearn` |
| HeliBoard backup zip-slip | A crafted backup archive could write outside the app-private directory through `../` entry names in its restore | The importer never turns an archive path into a file path: entries classify into store identities and the destination name is re-derived from them; on top of that, non-canonical paths are rejected outright; fail-closed on any mismatch; pinned by `BackupArchiveTest` |
