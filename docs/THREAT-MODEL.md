# THREAT-MODEL — security model and standing risk register for the offline Tatar keyboard

**Date:** 2026-09-29. **Status:** living document. All controls listed below are implemented. Canonical English.
The user-facing promises live in the root `PRIVACY.md`; this document is their
engineering counterpart: what we protect, from whom, with which control, and
which residual risks we carry on purpose. Sources merged: the three prior
audits (`docs/SECURITY-AUDIT-2026-09-25.md` + `-FIXES.md`,
`docs/AUDIT-2026-09-24.md` + `-FIXES.md`, `docs/FINAL-AUDIT-2026-09-02.md`)
and the PRIVACY.md limitations.

## 1. Scope premise

An offline input method editor inverts the usual mobile audit. The NETWORK
group — normally the largest — collapses into a single prove-absent
obligation: the app must never hold `android.permission.INTERNET`, and that
absence must be *demonstrated* (on the source manifest, on the built APK, and
at runtime), not asserted. The groups that remain expand to carry the whole
weight: STORAGE, because the learned personal data is the only persisted
sensitive data; PLATFORM, because an IME is a privileged system component
whose every InputConnection call has an unauthenticated peer — the host app —
on the other end; CODE, because binary parsers over on-device files are the
corruption axis; PRIVACY, because it is the product's reason to exist.

System under audit: a fork of Simple Keyboard / AOSP LatinIME (Java heritage,
new code in Kotlin), package `org.tatarkeyboard.ime`, pure JVM (no NDK), zero
third-party runtime dependencies, exactly one permission (`VIBRATE`), APK ≤
3 145 728 B.

## 2. Assets, in sensitivity order

1. **The keystroke stream in every app** — messages, passwords, 2FA codes.
   An IME is architecturally a keylogger position: every keystroke of every
   app passes through its process by design. The only security question is
   whether that data ever leaves the process. Every control in §5–§6
   ultimately serves this asset.
2. **The learned personal vocabulary** — saved words (≤ 2 000 per language),
   word pairs (≤ 1 000), learned word→emoji pairs (≤ 500), recent emoji (≤ 24).
   A behavioral fingerprint: names, addresses, slang, habits. This is the only
   *persisted* sensitive data. It is minimized at the schema level: usage
   counters, never clock times; words, never sentences; no host-app or field
   context recorded.
3. **The clipboard** — since Android 10 the *default* IME can read the
   clipboard at any time, exempt from the background-read restriction. This
   keyboard reads it exactly once: when the user taps the paste key
   (`RichInputConnection.pasteClipboard`). It is never stored, never learned
   from (§5, learning-integrity control), and even the transit is bounded (the
   64 Ki-char direct-commit threshold).
4. **SurroundingText / ExtractedText** — the window of the host field's text
   (here ≤ 1 024 chars before the cursor) that the platform hands the IME for
   predictions. Volatile by design: it lives only in the in-memory editor
   cache, dies on every session boundary, is never written to disk, and
   password fields are never re-read into it.
5. **User trust in the offline claim** — existential. The claim *is* the
   product; one observed byte of egress invalidates the project. Hence the
   evidence chain in §7 (NETWORK row), not a sentence on a store page.

## 3. Trust boundaries

1. **Host apps over InputConnection** — every `EditorInfo`, `SurroundingText`,
   and every IC method call comes from an unauthenticated peer. Treat as
   hostile: lying EditorInfo, malformed or null SurroundingText, throwing IC
   methods, rapid start/finish-input churn, oversized answers.
2. **Lock screen / Direct Boot** — before the first unlock after boot,
   credential-encrypted storage does not exist for the process. The code must
   still type (the IME starts with the system) while touching nothing
   personal.
3. **Backup channels** — cloud backup *and* device-to-device transfer are
   separate channels since Android 12; closing only one leaves the other open.
4. **Overlays / tapjacking** — other apps drawing over IME-owned UI to
   redirect or observe touches.
5. **Accessibility** — any service the user granted the accessibility
   permission to sees the rendered UI, suggestion strip included.
6. **logcat** — process logs are readable via adb, bug reports, and (on old
   Android) other apps.
7. **The asset pipeline** — `scripts/*.py` turn corpora into the shipped
   dictionaries and bigram tables; a poisoned corpus or generator reaches
   every device.
8. **The build supply chain** — the Gradle distribution, AGP/KGP and their
   transitive closure, GitHub Actions, the SDK build-tools install, the
   signing key.

## 4. Attacker classes

1. **Passive network eavesdropper** (carrier, state firewall, compromised
   Wi-Fi). *Defeated by design* — nothing is transmitted. The audit obligation
   is proving the absence, at three depths (§7 NETWORK row).
2. **Malicious host app** — controls its end of the InputConnection: hostile
   EditorInfo/SurroundingText, throwing methods, session churn, megabyte
   pastes. Cannot read the IME's memory or files; can only stress the
   boundary.
3. **Physical-access attacker** — holds the device, locked or unlocked; reads
   screens, browses settings, attempts backup extraction.
4. **Corrupted local storage** — bit rot, interrupted writes, or tampered
   store/dictionary files. Also the delivery vehicle for the ancestral
   CVE-2015-4641/4642 class (§9).
5. **Compromised upstream dependency or build tool** — a poisoned artifact in
   the Gradle/AGP/SDK chain, a hijacked CI action, a swapped wrapper.

## 5. Controls map

| Threat | Standing control | Gate / pin |
|---|---|---|
| Keystroke egress / passive eavesdropper | No INTERNET permission, ever; zero third-party runtime dependencies (no SDK that could phone home); no network code in the tree | `scripts/check-no-internet.sh` at two levels — source-manifest grep, then `aapt2 dump permissions` on the built APK — run in CI three times (`.github/workflows/ci.yml`: source, debug APK, release APK) and as `gates.no_internet` inside `scripts/release_check.sh`; `artifact.permissions` in `release_check.sh` pins the exact permission set `[VIBRATE]`. Dynamic leg (S7, landed 2026-09-29): `scripts/device-netstats-proof.sh` drives a scripted ~2-minute mixed session on the device and asserts a zero rx/tx delta in the before/after `dumpsys netstats detail` UID counters; re-run verdict `RESULT|PASS|netstats.zero-traffic`, evidence `build/device-uat-2026-09-29/netstats/` |
| Malicious host app | SurroundingText validated (out-of-range/inverted selection → empty caches); 64 Ki-char paste threshold with editor-side `performContextMenuAction` fallback; every batch edit in try/finally; dead-editor guards before any mutation; before-cursor cache capped at the 1 024-char window; background reloads coalesced and applied on the UI thread; NaN/Infinity rejected in glide paths and strip hit-testing (all `RichInputConnection.java`, `InputLogic`, `PointerTracker`, `GlidePath.kt`, `SuggestionStripState.kt`) | JVM suites: `RichInputConnectionRobustnessTest`, `BatchEditPairingContractTest`, `PointerTrackerQueueTest`, `GlidePathTest`, `SuggestionStripStateTest`; hostile-host tests `HostileHostRobustnessTest` and `HostileHostSuggestionChurnTest` |
| Physical access / pre-unlock | `directBootAware=true` **with** `UserManager.isUserUnlocked()` gating (fail-closed: unknown ⇒ locked): personal stores, recents, pending hashes and salts live in the credential-protected `noBackupFilesDir` and are neither read nor written pre-unlock; the static dictionaries inflate into *device-encrypted* storage (`createDeviceProtectedStorageContext` in `AndroidDictionaryStorageFactory.kt` / `AndroidBigramStorageFactory.kt`), so typing works pre-unlock without exposing anything learned; FLAG_SECURE on the settings host and on the three personal-content dialogs (`DialogUtils.securePersonalContent`); obscured-touch filtering on all 22 dialogs (`DialogUtils.filterObscuredTouches`) | `DialogObscuredTouchContractTest`; unlock-gate pins in the personal-store privacy suites (`PersonalStorePrivacyTest` family) |
| Backup exfiltration | Manifest: `android:allowBackup="false"` **and** `dataExtractionRules` (`app/src/main/res/xml/data_extraction_rules.xml`) as a *whitelist*: zero `<include>` elements, both sections (`cloud-backup` **and** `device-transfer`) excluding all seven data domains whole (regular + device-protected files, sharedprefs, databases, external); `fullBackupContent` is banned from returning | `check-no-internet.sh` level 2 proves it on the built APK: the literal `allowBackup=false`, the dataExtractionRules reference resolved through its resource id (survives release resource shrinking), the absent-include invariant, the full domain set in both sections — fail-closed |
| Corrupted storage / hostile binary asset | Fail-closed validators for all five binary readers (`TdictValidator`, `TatBigrValidator`, `TpersValidator`, `TpersbValidator`, `TpersemValidator`); size + SHA-256 pins in `DictionaryStorageContracts.kt` / `BigramStorageContracts.kt`, plus the dictionary↔bigram raw-SHA-256 link; an unreadable personal store is *quarantined*, never silently destroyed, with bounded salvage reads; hard caps on every store and file | `artifact.asset_pins`, `artifact.tree_assets`, `artifact.emoji_assets` in `release_check.sh`; `rebuild_assets.py --check --allow-known-drift` (also wired into `release_check.sh` as `gates.asset_rebuild_check`); corruption-class JVM tests; seeded parser fuzzing of all five validators (`SeededFuzzHarness`) |
| Compromised dependency / build tool | Zero third-party *runtime* dependencies — nothing executes in the APK but this code and the framework; PGP + SHA-256 verification of the dev-time graph with the public keys committed (`gradle/verification-metadata.xml`, `gradle/verification-keyring.gpg` + `.keys`); wrapper `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`; pinned SDK build-tools (`scripts/build-tools-pin.sh`); reproducible builds (`dependenciesInfo { includeInApk = false }` in `app/build.gradle`; the CI `reproducible` job byte-compares two clean unsigned builds; `scripts/release_pack.sh` passes a double-run identical-SHA-256 ritual); no CI artifact upload on fork PRs | CI `reproducible`; `artifact.signature` in `release_check.sh` — exactly one signer (distinct-digest count, multi-signature is FAIL) equal to the release certificate SHA-256; `artifact.critical_resources` (the `res/raw/keep.xml` reflective set must survive shrinking); `artifact.arsc_stored` |
| logcat / side surfaces | Metadata-only logging: never typed text, never field contents, never cursor positions or the host package name (both removed 2026-09-24) | Source-contract log-safety gate `LogSafetySourceContractTest`: a package scan over the input-pipeline packages that fails on any new log call site |
| Learning from the wrong context | The single predicate `PersonalLearningGates.mayLearn` gates every write path of all three personal stores: field eligibility (password / visible-password / e-mail / URI / filter / NO_SUGGESTIONS **and** `IME_FLAG_NO_PERSONALIZED_LEARNING`) ∧ personal-dictionary switch ∧ `isUserUnlocked` ∧ ¬postal-address ∧ ¬incognito. Paste cannot teach: `CleanRunMachine` marks a run dirty when a fresh word's first observation exceeds one keystroke (≤ 2 UTF-16 units), and only the keyboard's *own* emoji insertions teach the emoji store. The editor cache is cleared on every session boundary (`onFinishInputInternal`, `onWindowHidden` in `LatinIME.java`); password fields are never re-read (`isPasswordField` gates all three `reloadTextCache` sites, taking clear-instead-of-reload) | `PersonalLearningGatesTest`, `EditorTextCachePrivacySourceContractTest`, the paste pins in `PersonalLearningRunTest` / `PersonalBigramRunTest`; the full EditorInfo privacy matrix `EditorInfoPrivacyMatrixTest` |
| Asset-pipeline poisoning | Corpora come from named, licensed sources with manifests (`research/corpus/`); generation is deterministic stdlib-only Python; every shipped asset is pinned by size + SHA-256 and the pins are gated (above), so a silent change anywhere in the pipeline goes red before release | `rebuild_assets.py --check --allow-known-drift` with known drift pinned to exact counts in `scripts/known_asset_drift.json`; python pipeline suites (`tests/*/test_*.py`) |

## 6. Deliberate postures (do not re-litigate)

- **`directBootAware=true` with unlock gating is a feature, not a leak.** The
  IME must load pre-unlock (it starts with the system; the user may type at
  the lock screen). The split: static dictionaries sit in device-encrypted
  storage and work pre-unlock; everything *learned* sits in
  credential-protected `noBackupFilesDir` behind an `isUserUnlocked` gate and
  is untouched until first unlock. The plan records dropping `directBootAware`
  as explicitly not doing.
- **The IME window itself carries no `filterTouchesWhenObscured`.** Key
  previews and popups are child windows of the same window and would be
  filtered with it, breaking previews; the platform has blocked app overlays
  over the IME window since Android 8.0 anyway. The absence is pinned
  (`SettingsTapjackingSourceContractTest.imeWindowLayoutsStayUnflagged`); all
  22 *dialogs* do filter obscured touches.
- **SetupActivity and SettingsActivity are exported.** The launcher icon and
  the system IME-settings entry point are platform contracts; neither reads
  extras. SettingsHostActivity and the broadcast receiver are deliberately
  *not* exported (`app/src/main/AndroidManifest.xml`). The golden exported set
  is checked by the release gate `artifact.exported_surface` in `scripts/release_check.sh`.
- **Logging stays metadata-only, not zero.** The `Log.e`/`Log.i` severity
  structure is kept for operational diagnosis; typed text, field contents,
  cursor positions and host package names are never logged (the last two
  removed 2026-09-24). S3 turns the rule into a gate.
- **The saved-words screen is not behind a separate password.** FLAG_SECURE
  keeps it out of screenshots and the recent-apps thumbnail; it cannot keep
  out a person holding the unlocked phone. The physical-access attacker is
  accepted and *stated* in PRIVACY.md rather than left unsaid.
- **No `IME_FLAG_NO_PERSONALIZED_LEARNING` on Android 7.0/7.1.** The flag
  exists from API 26; on 24/25 no app can set it, so those fields are
  indistinguishable and are protected only by the other gates (password and
  private field types, postal addresses, the off-by-default learning switch).
  Disclosed in PRIVACY.md; the gap disappears with `minSdk ≥ 26`.

## 7. OWASP MASVS group mapping

| MASVS group | Priority here | What it means for this app |
|---|---|---|
| **STORAGE** | HIGH | The only persisted sensitive data are the learned stores: credential-protected `noBackupFilesDir`, unlock-gated, capped, quarantine-instead-of-delete, pending stage as salted truncated SHA-256 until a word earns its place |
| **PLATFORM** | HIGH | The IME is a platform component: hostile-InputConnection hardening, the backup whitelist across both channels, the Direct Boot split, the exported-surface contract, obscured-touch and FLAG_SECURE discipline |
| **CODE** | HIGH | Fail-closed binary parsers with pinned assets, the F-wave robustness fixes, source-contract pins for every invariant, error-prone and the lint baseline in the gates |
| **PRIVACY** | HIGH | Minimization as architecture: counters not timestamps, words not sentences, no app/field context, incognito pause, per-language stores, user-visible and user-erasable (the "Saved words" screen) |
| **NETWORK** | N/A — but prove absent | The whole group collapses to one obligation. Evidence: the two-level `check-no-internet.sh` (source + built APK, in CI and in `release_check.sh`), the exact-permissions `[VIBRATE]` gate, and the runtime zero-delta demonstration (S7, landed 2026-09-29): `dumpsys netstats detail` UID counters for the app before/after a scripted ~2-minute mixed session (typing tt/ru, suggestion accepts, emoji panel + search) — rx 0→0, tx 0→0, no traffic buckets for the UID on either side; transcript `build/device-uat-2026-09-29/netstats/` (repeatable via `scripts/device-netstats-proof.sh`; snapshots `netstats-before.txt`/`netstats-after.txt`, `diff.txt`) |
| **CRYPTO** | LOW-MED | Narrow surface, no homebrew: 16-byte `SecureRandom` salts, truncated salted SHA-256 for the pending stage (`PendingCounters.kt`), platform file-based encryption for at-rest data. No app-managed keys, no TLS (nothing to terminate) |
| **AUTH** | N/A | No accounts, no sessions, no remote endpoint |
| **RESILIENCE** | N/A — reproducibility instead of anti-tamper | An open-source offline IME gains nothing from root detection or anti-debug. Binary integrity is delivered differently: reproducible builds (CI `reproducible`), the exactly-one-signer gate, and the pinned-asset gates — anyone can rebuild the tree and compare against the shipped APK |

## 8. Standing residual-risk register

One aggregated living table: every accepted or residual risk from the three
prior audits and the PRIVACY.md limitations, live rows first, then rows
retired since their audit (kept so no acceptance is ever silently dropped).
FINAL-AUDIT-2026-09-02 items A2 (publishing) and D1/D2 (device measurement /
test matrix) are operator-and-hardware *tasks*, not risk acceptances — they
are not register rows; D1 has since been measured (POCO C71, 2026-09-20) and
D2's TalkBack leg survives below as its own row.

| Risk | Origin (doc) | Why accepted | Watch trigger |
|---|---|---|---|
| Build-tools fallback for read-only consumers | SECURITY-AUDIT-2026-09-25 (T6), narrowed 2026-09-28 | A single pinned build-tools version (`scripts/build-tools-pin.sh`) now covers the byte-producing pack steps (no fallback allowed there); the read-only consumers (`aapt2 dump`, `apksigner verify`) still fall back to the newest installed when the pinned directory is absent — their dump/verify output is version-stable | A byte-producing step ever gaining the fallback; a pinned-version bump |
| Pack-pipeline byte-determinism enforced by ritual, not CI | SECURITY-AUDIT-2026-09-25 (T8) | Packing is a local, operator-run step; the double-run identical-SHA-256 ritual is proportionate | A double-run divergence; packing ever moving into CI |
| Suggestion strip and emoji search visible to accessibility services | SECURITY-AUDIT-2026-09-25 (P5) | That *is* the TalkBack contract; services holding the accessibility permission are platform-trusted to see the rendered UI | An Android accessibility-model change |
| IME window unfiltered against obscured touches | SECURITY-AUDIT-2026-09-25 (P6) | Previews/popups are child windows and would break; the platform blocks overlays over the IME window since Android 8.0; pinned absence | A new top-level IME window; an Android overlay-policy change |
| Enter-key actionLabel painted from the host's EditorInfo | SECURITY-AUDIT-2026-09-25 (U2) | Platform contract: the label is the host app's own text by design; every keyboard renders it | A platform contract change |
| Layout picker lists other IMEs' display names | SECURITY-AUDIT-2026-09-25 (U3) | Public system metadata — the same list system settings shows — not user content | A platform metadata-policy change |
| Fork-specific `RuntimeException` subclasses | SECURITY-AUDIT-2026-09-25 (N4) | Mirrors the androidx pattern of domain-specific unchecked exceptions; no swallowing, no catch-all | A catch-all ever appearing |
| `gradlew` delta vs the official Gradle wrapper | SECURITY-AUDIT-2026-09-25 (T10) | Verified harmless by diff against upstream at the audit (3 lines: comment-only deltas plus one `DEFAULT_JVM_OPTS` delta; no executable-logic divergence); the script was touched again by the same day's supply-chain wave | Any wrapper upgrade — re-diff against upstream |
| Silent-word drift in the conversational corpora (ru 2/0; tt 3 gerunds + 152 address candidates) | FINAL-AUDIT-2026-09-02 (D3) | Data-coverage remainder, not a defect; pinned to exact counts in `scripts/known_asset_drift.json`, fail-closed | Any change in the pinned counts — `rebuild_assets.py --check` goes red |
| Emoji-suggest variant B (suggest under cursor without space) deferred | FINAL-AUDIT-2026-09-02 (D4) | Product deferral, to be weighed against demand after publication | Post-publication demand review |
| Lossy frequency compression (~190 KB saving) not taken | FINAL-AUDIT-2026-09-02 (D5) | Separate product decision; the size budget is healthy (≈ 46 % headroom) | APK size pressure |
| Android 7.0/7.1 cannot signal `IME_FLAG_NO_PERSONALIZED_LEARNING` | PRIVACY.md (limitations) | The flag exists from API 26; on 24/25 no app sets it, so only the other gates protect those fields; disclosed | `minSdk ≥ 26` — delete this row |
| Saved-words screen not password-gated | PRIVACY.md (limitations) | Physical-access attacker accepted; FLAG_SECURE covers capture, not the person beside you; stated, not silent | A decision to add an in-app lock |
| Light-theme contrast 4.0:1 | AUDIT-2026-09-24 (L3) | Deliberate iOS-palette decision | A design pass |
| PointerTracker statics | AUDIT-2026-09-24 | Accepted AOSP legacy; the regression risk of a rewrite exceeds the gain | Regression evidence |
| TalkBack audible check is manual | AUDIT-2026-09-24 | The mechanics are pinned by tests and screenshots; the ears-on pass stays with the operator | Every release ritual |
| SettingsActivity exported | AUDIT-2026-09-24 | Required by the IME settings entry-point contract; reads no extras | The S2 exported-surface gate pins the golden set — drift fails the release |
| ~5.8 MB worst-case glide memory (two warm engines) | AUDIT-2026-09-24 | Freed on engine destroy / geometry swap (`docs/ROADMAP-P7.md` footnote) | The PSS ceiling (plan item O2) |
| Disabled settings rows swallow taps silently | AUDIT-2026-09-24 (L4) | Documented behavior; the informing toast needs a new string (wiring point marked in code) | The next strings touch |
| No scrollbars in settings | AUDIT-2026-09-24 (I3) | Style decision | A design pass |
| `ClassInitializationDeadlock` on `EMPTY_LISTENER` (error-prone) | AUDIT-2026-09-24 | Unreachable in practice: UI-thread-only initialization; triage recorded in `docs/ERRORPRONE-TRIAGE.md` | Initialization ever moving off the UI thread |
| Generic confirmation dialogs (clear-all, erase, discard-quarantine) stay shareable — no FLAG_SECURE | SECURITY-AUDIT-2026-09-25 (U1, inverse) | They render no personal content; capture shows nothing sensitive | A dialog ever rendering personal content without `DialogUtils.securePersonalContent` (pinned) |
| ~~Floating CI action tags (`@v4`/`@v5`)~~ | SECURITY-AUDIT-2026-09-25 (T5) | **Retired 2026-09-25** (supply-chain wave): all five actions SHA-pinned with version comments in `.github/workflows/ci.yml` | An action update — re-pin with a fresh SHA |
| ~~No dependency-verification metadata~~ | SECURITY-AUDIT-2026-09-25 (T7) | **Retired 2026-09-25** (supply-chain wave): `gradle/verification-metadata.xml` verifies PGP/SHA-256 for every dependency, public keys committed (`gradle/verification-keyring.gpg` + `.keys`) | Key expiry/rotation ritual in AGENTS.md |
| ~~Cursor positions / host package name in metadata logging~~ | AUDIT-2026-09-24 (residual) | **Retired 2026-09-24** (fix wave F5/F10): both lines behind the `TRACE` debug flag; `targetApp=` removed from `InputAttributes.toString()` | The S3 log-safety gate |
| ~~RecentEmoji medium read without a size cap~~ | AUDIT-2026-09-24 (INFO) | **Retired 2026-09-24** (fix F11): 4 096-byte cap, fail-closed, length checked before reading | `AtomicRecentEmojiFileOpsTest` |
| ~~No contract test for the languages-screen dialog~~ | AUDIT-2026-09-24 (INFO) | **Retired 2026-09-24** (fix F9): `DialogObscuredTouchContractTest` counts the site | — |
| ~~FLAG_SECURE missing on personal-content dialogs~~ | AUDIT-2026-09-24 (I2) | **Retired 2026-09-25** (U1): `DialogUtils.securePersonalContent` on the three personal-content dialogs | `DialogObscuredTouchContractTest.personalContentDialogsAreSecureFromCapture` |
| ~~sentstart assets outside the release gates~~ | AUDIT-2026-09-24 (INFO) | **Retired 2026-09-25** (T2): `artifact.tree_assets` covers `assets/dictionaries/` and `assets/bigrams/` (NOTICE + sentstart included), set-equality and content | — |
| ~~`androidx.customview` runtime dependency~~ | AUDIT-2026-09-24 (INFO) | **Retired 2026-09-25** (O2 wave): removed; "zero third-party runtime dependencies" is now literal, and the lint baseline lost its GradleDependency entry with it | Lint baseline |
| ~~Signing keystore in the repo root~~ | FINAL-AUDIT-2026-09-02 (C8) | **Retired** by operator action: `tatar-keyboard-release.jks` is absent from the tree | The S4 secrets-scan gate keeps it absent |

## 9. Incident record — why these controls

- **Citizen Lab, "The Not-So-Silent Type" (2024).** Eight of nine cloud
  keyboards analyzed (Baidu, Honor, iFlytek, OPPO, Samsung, Tencent, Vivo,
  Xiaomi — only Huawei's passed) transmitted keystrokes in ways a *passive*
  network eavesdropper could decrypt. The lesson: the defense is not better
  crypto on the wire but no wire. This keyboard is on-device only, and the
  audit's job is to prove that posture holds (§5, three evidence depths) —
  not merely to claim it.
- **CVE-2015-4641 / CVE-2015-4642 (2015).** Remote code execution in
  LatinIME's *native* binary dictionary parser via a crafted dictionary — and
  this fork descends from that codebase. The fork is pure JVM (no NDK), so the
  memory-corruption class degrades to denial of service, but malformed or
  corrupted dictionary/store files remain a real axis: hence fail-closed
  validators, size + SHA-256 pins, quarantine-instead-of-delete, and the
  seeded parser fuzzing.
- **ai.type (2017).** A 577 GB database of collected data from 31 M users of
  the ai.type keyboard sat exposed. Collected data eventually leaks; the only
  reliable protection is never collecting it. Hence minimization at the schema
  level (§2, asset 2): counters not timestamps, words not sentences, no
  host-app context — and nothing leaving the device to be breached elsewhere.
- **GO Keyboard (2017).** Caught transmitting user data contrary to its
  claims — by *network-behavior analysis*, not by reading its policy. That is
  precisely the dynamic artifact plan item S7 produces for this keyboard as
  positive proof-of-absence, turning the same technique that exposed GO
  Keyboard into our evidence.
- **Kika / CM Keyboard (2018).** Cheetah Mobile keyboards shipped SDK payloads
  implicated in ad fraud. The class is third-party code executing inside the
  keyboard process; zero runtime dependencies neutralizes it structurally —
  there is no SDK to smuggle a payload through — and the PGP+SHA-256
  dependency verification covers the build-time side.
- **SwiftKey (2016).** A sync bug surfaced one user's learned predictions
  (e-mail addresses and other personal vocabulary) to *other* users. Learned
  data appearing in the wrong context is a breach without any malice. Hence:
  no sync exists at all; stores are per-Android-user by platform isolation and
  per-language by directory; the incognito pause halts every write; and
  learning fires only through the single `mayLearn` predicate.
