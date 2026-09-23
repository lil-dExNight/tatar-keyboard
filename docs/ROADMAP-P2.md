# ROADMAP-P2 — phase 2 personalization report: P1 personal bigrams, U7 screen, U8 incognito

Status: **P1 (personal bigrams) implemented 2026-09-23**; **U7 (personal dictionary screen) and U8
(incognito mode) implemented 2026-09-23**; gates green; device UAT remains separate work. Each
section names the design, the files, the pins and the numbers. No commits — the operator commits.

Baseline before the batch: JVM 1 282 tests, python 484 tests, release 2.1.0.

## P1 — personal bigrams (done 2026-09-23)

### What the item is

On-device learning of the user's word pairs that surfaces in the NEXT_WORD suggestion slot:
type a pair («сәләм дөнья») cleanly twice, and «дөнья» becomes a prediction after «сәләм » —
alongside the static bigram table, never displacing it. The privacy promise is unchanged:
everything lives on-device, in the same credential-protected `noBackupFilesDir/personal/`
directory the personal words store owns, and no pair is ever written in plaintext before it has
earned its place (salted-hash pending counters, exactly the words store's discipline).

### Design — the shape of the mirror

Every structural decision of the personal words design (E4a–E4d) is mirrored one class for one,
in the SAME two packages (`dictionary/personal/` for the format and the read model,
`dictionary/personalstore/` for the store and the learning wiring), so the words store's test
discipline, privacy scans and source contracts cover the bigram files from day one:

| words (E4) | pairs (P1) |
|---|---|
| `TpersFormat` (`personal-tt_RU-s1-f1.tpers`) | `TpersbFormat` (`personal-bigrams-tt_RU-s1-f1.tpersb`) |
| `TpersValidator` / `ValidatedPersonalDictionary` | `TpersbValidator` / `ValidatedPersonalBigrams` |
| `PersonalDictionary` (snapshot) | `PersonalBigramDictionary` (snapshot) |
| `PersonalCandidateSource` (prefix merge) | `PersonalBigramSource` (NEXT_WORD merge) |
| `PersonalEntries` (pure model) | `PersonalBigramEntries` (pure model) |
| `PersonalDictionaryStore` | `PersonalBigramStore` |
| `PersonalQuarantineSalvage` | `PersonalBigramQuarantineSalvage` |
| `PersonalDictionaries` (process owner) | `PersonalBigramDictionaries` (process owner) |
| `PersonalLearning` (sink factory) | `PersonalBigramLearning` (sink factory) |
| `WordCompletionSink` | `PairCompletionSink` |
| `PendingCounters` (salted hashes) | **the same class**, plus `keyOfPair` |
| `PersonalWordFilter` (3..24 cp) | `PersonalBigramWordFilter` (1..24 cp) |

Three deliberate deviations from a blind copy, each pinned by a test:

1. **Word length bounds are 1..24 code points, not 3..24.** A one-letter pair member is a
   legitimate word («а» is an ordinary Tatar conjunction), and the junk filter a length floor
   provides for single words is carried here by the context-membership gate (below).
2. **The salt is the store's OWN `salt-bigrams.bin`, not the words store's `salt.bin`.** Same
   salt discipline (16 random bytes, created on first use, destroyed by clear-all), but erasing
   one feature's data must not silently invalidate the other feature's pending progress.
3. **The pair key is a PAIR.** Ordering, dedup and the pending hash compare (context, successor)
   member by member — («аб», «вг») and («абв», «г») concatenate to the same bytes and are
   different pairs; the pending hash puts a zero byte between the halves for the same reason.

### Store format (`.tpersb`, schema 1 format 1)

The 72-byte header is the `.tpers` header's layout with a different magic (`TATPERSB`) and
`pairCount` in place of `entryCount`; checksum is SHA-256 over the whole file with the checksum
field zeroed. Records, strictly ascending by the pair key (context then successor, normalized,
unsigned UTF-8 bytes):

| size | field |
|-----:|-------|
| 1 | contextByteLength u8 |
| 1 | successorByteLength u8 |
| 2 | usageCount u16 (≥ 0; accepted-prediction taps) |
| 2 | frequencyCount u16 (≥ 1; clean typed observations) |
| 4 | lastUseSerial u32 (LRU) |
| N | context bytes, UTF-8, NORMALIZED form only (never displayed, so never stored raw) |
| M | successor bytes, UTF-8, ORIGINAL as-typed form (the cell shows the user's casing) |

Bounds (fail-closed in `TpersbValidator` and mirrored in `PersonalBigramWordFilter`):
≤ 1 000 pairs per subtype (`TpersbFormat.MAX_PERSONAL_BIGRAM_PAIRS`), file ≤ 65 536 B
(`MAX_FILE_SIZE`), pair words 1..24 code points each, per-subtype alphabet from the artifact
registry (`PersonalSubtypes.alphabetFor`), successor casing not MIXED, no leftover combining
marks after NFC. LRU eviction is by the monotonic last-use serial, never the clock.

Writes are whole-file and atomic, in the frozen sequence of the words store: exclusive temp in
the same directory → write → flush → fsync file → RE-VALIDATE the written bytes → atomic
replace → fsync directory. Corruption on open moves the file into the single per-language
`.quarantine` slot and raises the durable one-byte notice flag; the salvage reader
(`PersonalBigramQuarantineSalvage`) ignores the checksum and reads until the first record that
violates the per-record contract, reporting (count, readToEnd) — a partial recovery is never
presented as a whole one. `forget` and `clearAll` purge the quarantine copy too (delete-one
rewrites the copy without the pair, or deletes it when nothing is left — a restore must never
resurrect a deleted pair, pinned by `aForgottenPairIsNotResurrectedByARestore`).

Both stores are serialized on the ONE shared worker (`PersonalDictionaries.sharedStoreExecutor`),
so the two features can never race each other's in-flight temp files in the shared directory.

### Learning rules (pinned)

A pair (A, B) is a **candidate** when ALL of these hold:

1. **B completes a CLEAN run** — the E4c machine's own definition, carried transition for
   transition: the word grew one piece at a time and ended by the trailing word becoming empty,
   with no backspace, no shortening, no selection change, no cursor gesture, no field or subtype
   change and no accepted suggestion in between. There is deliberately NO "unknown to the
   dictionary" filter — a pair whose second half is an ordinary dictionary word is the common
   case. The one place the pair machine differs from the words machine: after an ACCEPTED
   suggestion the next typed word may still form a pair — the tapped word is a legitimate
   context ("typed or tapped") and the cursor sits provably right after it
   (`SuggestionsController.pairRunClean`, re-armed in `onTap` after a successful commit).
   A tapped B does NOT count (the tap dirties the run before it re-arms).
2. **A is the immediately preceding committed word in the editor context**, read LIVE from the
   editor cache at the completion moment — `EditorSurface.cachedWordBeforeTrailingWord()` /
   `TatarWordUtils.extractWordBeforeTrailingWord` — never from remembered state, so it can never
   go stale against what the editor holds. Typed or tapped is irrelevant; a word that opens the
   field or follows a boundary with no word before it yields "" and no pair is observed.
3. **Both words pass the pair filter** (alphabet of THIS subtype, 1..24 code points, no MIXED
   casing, no combining marks) — a mixed-language pair fails here by construction, per-subtype.
4. **A is a dictionary or personal word of the same language** (normalized): the
   `PersonalBigramContextMembership` gate, consulted on the store's worker AT GRADUATION — not
   at observation, so the check is off the UI thread and a word learned into the personal
   dictionary between two observations still counts as known. The personal half reads the words
   store's published snapshot; the dictionary half is the new thread-safe
   `TdictPrefixIndex.containsWordCold` — a cache-free binary search over the read-only mapping
   that never touches the lookup path's scratch — reached through
   `EngineHandle.containsWord` → `SuggestionsController.engineContainsWord` (the engine slots
   are `@Volatile` for exactly this read). A cold engine or a broken oracle answers false:
   fail-closed, the pair is dropped.

A candidate is **learned after N = 2 clean observations** (`PersonalBigramStore.LEARN_THRESHOLD`,
pinned). Until graduation only a salted truncated SHA-256 of the pair exists
(`PendingCounters.keyOfPair`, salt from `salt-bigrams.bin`), in memory between flushes and in
`pending-bigrams-<subtype>-s1-f1.bin` at the flush — never the plaintext
(`oneObservationWritesNoPairAnywhereAndNoPlaintextAtAll` scans every file of the directory for
the pair's bytes). The threshold is one lower than the words store's 3 because the
context-membership gate carries the junk-filter weight the third observation carries for single
words. Further observations of a learned pair bump its frequency and LRU serial IN MEMORY only;
accepted predictions bump usage the same way; both are flushed at the ONE boundary
(`onFinishInput`), never per keystroke.

The sink gates through the SAME five factors as E4c (`PersonalBigramLearning.sinkFor`, wired
beside the word sink in `LatinIME` with the same `mayLearnPersonalWords` predicate): suggestions
eligible (which already carries the field, the subtype, `IME_FLAG_NO_PERSONALIZED_LEARNING` and
the null-editorInfo case), the personal dictionary setting ON, the device unlocked at least
once, and the field not a postal address. The subtype is resolved per event — a pair completed
on the Russian layout reaches the Russian store and nothing else.

### Ranking chain (NEXT_WORD slot only, pinned)

`CompositePrefixComputer.predict` now runs: **static bigram successors > personal pairs > word
forms > fallback**, at most 3 cells:

- static successors are never displaced, however the personal pair ranks;
- personal pairs fill the cells the successors leave free, **at most
  `MAX_PERSONAL_BIGRAM_CELLS = 2`** (the leave-room pin: with all three cells free the personal
  half still leaves one for the forms/fallback half), in the pinned personal order — usage
  (accepted taps) descending, then frequency (clean observations) descending, then normalized
  ascending;
- a pair whose normalized form a static successor already shows is skipped: the duplicate is
  shown once and the STATIC spelling wins;
- forms and the fallback exclude everything already shown, exactly as before;
- personal pairs are NOT offered before the bigram source attaches (the NEXTWORD-RACE rule —
   the re-request on attach fires only while the band holds no active-language word), never for
  the other language (per-subtype stores and per-subtype sources), and never for words failing
  the personal filter (they cannot enter the store at all);
- the PREFIX merge is untouched: predict still never consults the prefix-path personal source
  (the two E5-era tests pinning that remain, their comments amended to name the new seam);
  with the source EMPTY the answer is byte-for-byte the pre-P1 one (`assertSame`).

### Tests

92 new JVM tests across 7 suites (1 282 → 1 374 total, plus one amended count in the dialog
contract):

- `TpersbValidatorTest` (15): format round-trip, every header field, ordering/dedup by the PAIR
  key (including the («аб», «вг») vs («абв», «г») boundary), alphabet, casing, length bounds,
  checksum, truncation, oversize, subtype tag, one-letter members, usage-0/frequency-≥1.
- `PersonalBigramEntriesTest` (11): insertion order, reinforcement, acceptance, removal, LRU
  eviction, counter saturation, the pair-key boundary, snapshot order (usage → frequency →
  normalized), serialization round-trip and size estimate.
- `PersonalBigramStoreWriteTest` (33): the whole-file contract sequence with a fault injected
  at every step; threshold 2 with the no-plaintext scan; graduation-time membership (unknown
  context dropped, membership evaluated at graduation not observation, broken oracle vetoes);
  in-memory counters + single boundary flush; LRU on disk; unlock gate; corruption → quarantine
  → salvage → restore (skip-existing) → forget-purges-copy (no resurrection) → discard;
  clear-all deletes file/pending/salt/copy/flag; per-language isolation.
- `PersonalBigramLearningGatesTest` (5): every sink path gated, subtype resolved per event,
  wired beside the word sink under the same predicate, the membership probe installed and
  cleared with the service.
- `PersonalBigramRunTest` (13, controller): clean pair reported with its live-cache context;
  first-boundary protection; empty context; raw forms; backspace/selection/subtype dirtiness;
  **tapped A counts as context while tapped B never counts**; consecutive pairs; the flush
  boundary; the NEXT_WORD acceptance hook; a tap with nothing bound.
- `CompositePrefixComputerTest` (+10, to 40): the full ranking chain — fill order, static never
  displaced, max-2 personal cells with leave-room, static-wins dedupe, casing shown for
  personal-only cells, not-before-attach, broken source fail-closed, forms/fallback exclusion,
  byte-for-byte EMPTY behavior.
- `TdictPrefixIndexContainsWordColdTest` (5): membership agreement with `frequencyOf`, input
  guards, and a concurrency smoke run against the live lookup path.
- `DialogObscuredTouchContractTest`: the pinned attach-site count 9 → 10 (the new unreadable-
  pairs dialog passes through the same filtering attach — the contract amendment this item
  needed, recorded here as the phase discipline requires).

Zero-allocation lookup-path contract untouched: the new code runs at graduation (store worker),
at prediction (which already allocates lists), and inside the NEXT_WORD merge behind an
`isEmpty()` guard that costs a disabled feature one boolean read — the per-keystroke PREFIX path
is byte-identical.

### Gates (2026-09-23)

| Gate | Result |
|---|---|
| python pipeline tests (`for f in tests/*/test_*.py`) | PASS (484) |
| `./gradlew test --rerun-tasks` | PASS — 1 374 tests, 0 failures |
| `./gradlew lintRelease` | PASS — 0 errors, 32 warnings filtered by baseline (the new `UsableSpace` instance in `createBigrams` baselined beside the words factory's), 4 pre-existing live warnings outside this change (2× `NewerVersionAvailable` on build.gradle, 2× `UnusedResources` on the 5-row fractions left by T4) |
| `rebuild_assets.py --check --allow-known-drift` | PASS (`ok: true`) |
| `./gradlew assembleRelease -PskipReleaseSigning` | PASS — unsigned APK **1 838 056 B** (≤ 3 145 728) |
| `check-no-internet.sh` (release APK) | PASS — both levels (manifest + aapt2), backup whitelist intact |

### What this item deliberately did NOT do

- The settings screen (view/forget/clear UI) is **U7**; the store-level `forget`/`clearAll`/
  `inspectQuarantine`/`restoreQuarantine`/`discardQuarantine` APIs it will call are implemented
  and pinned here.
- Incognito mode is **U8**; its toggle will sit next to `mayLearnPersonalWords` and the read
  gate, both of which already exist.
- PRIVACY.md is the final batch's task (per the phase plan); this section is its source.

## U7 — personal dictionary screen (done 2026-09-23)

### What the item is

The E4b "Saved words" screen becomes the management surface for BOTH personal stores, per
language: the learned words AND the P1 learned word pairs of every enabled subtype that carries a
personal alphabet (`tt_RU`, `ru`), with a usage count on every row, per-row delete, per-language
"clear all" for each store, and a global erase that now covers both stores. Quarantine gets the
second card: an unreadable pairs file is inspectable, restorable and discardable exactly like the
words copy — the P1 batch implemented and pinned those store APIs and left the UI to this item.

### Design — the shape of the extension

Everything rides the existing screen; nothing new is a screen of its own:

| piece | where |
|---|---|
| screen model (pure, JVM-tested): sections = words + pairs per language, true saved counts, search over words AND pairs (context or successor), the shared 200-row cap | `latin/settings/PersonalDictionaryScreenModel.kt` (`build` at :112, `MAX_MATERIALIZED_ROWS` at :99) |
| words controller, unchanged shape + per-language `clearWords` | `latin/settings/PersonalDictionaryScreenController.kt` |
| pairs controller — the deliberate mirror: sections, removePair, clearPairs, eraseAll, quarantines, restore/discard | `latin/settings/PersonalBigramScreenController.kt:44` |
| the screen itself: incognito note, words card + pairs card per language (each opened by its true count, closed by its own clear action), pair quarantine cards, the two-store global erase | `SettingsHostActivity.kt:476` (`usageRow` :583, pair cards :691, clear dialogs :865/:888, erase :910) |
| `PersonalDictionary.usageCountAt` — the one accessor the read model was missing for the rows | `latin/dictionary/personal/PersonalDictionary.kt:57` |

The rules that did not move: the screen does no file I/O — reads are the published snapshots of
the process-wide owners (`PersonalDictionaries.snapshotFor` /
`PersonalBigramDictionaries.snapshotFor`), primed and written only on the ONE shared personal-store
worker; mutations go through the stores' `forget`/`clearAll` so the quarantine copies are purged
with the list; every outcome is marshalled onto the UI thread and the screen repaints only from a
finished mutation; a store that is unreadable or still locked publishes an empty snapshot, so the
screen shows the empty state and the quarantine card (now for both stores) says why — fail-closed,
no crash. `FLAG_SECURE`, the transient search query and the three privacy flags on the text fields
are untouched and still pinned.

### The words store's resurrection hole, closed

P1 pinned `aForgottenPairIsNotResurrectedByARestore` for pairs; the WORDS store had the hole the
mission brief suspected: `forget` removed the word from the dictionary but left it in the
quarantine copy, and a later restore brought it back. `PersonalDictionaryStore.purgeFromQuarantine`
(`PersonalDictionaryStore.kt:242`, called from `removeOnWorker` on both branches :203/:231) is the
exact mirror of the pairs purge: the copy is rewritten without the word, deleted when empty,
deleted outright when the rewrite fails — fail-closed toward NOT resurrecting. Pinned by four new
recovery tests (below).

### Strings (three locales, review queue)

21 new keys + 2 reworded, all in `values/`, `values-ru/`, `values-tt/`:

- U8: `incognito_mode`, `incognito_mode_summary`, `personal_dictionary_learning_paused`.
- U7 core: plurals `personal_dictionary_words_count` / `_pairs_count` / `_usage_count` (ru carries
  one/few/many/other, tt the single `other` form; every form carries `%1$d`, which the
  argument-consistency contract requires), `personal_dictionary_clear_words`(+`_confirm`),
  `_clear_pairs`(+`_confirm`), `personal_dictionary_pair_forget_title` ("A → B", matching the
  row), `personal_dictionary_pair_delete_failed`.
- U7 pairs quarantine: `personal_bigrams_quarantine_title` / `_partial` / `_whole` / `_none` /
  `_restore` / `_discard` / `_discard_confirm` / `_restore_failed` / `_discard_failed` — the mirror
  of the words card's nine.
- Reworded: `personal_dictionary_erase_all` ("Erase everything saved") and
  `personal_dictionary_erase_confirm` (names words AND pairs) — the global erase now covers both
  stores, and the text had to stop under-claiming.

All 24 touched Tatar rows are in `docs/archive/dictionary/TATAR-REVIEW-QUEUE.tsv` (status `approved`,
reviewer `dExNight`, 2026-09-23, per the operator's 2026-08-20 rule): 21 new rows (P2-U7/P2-U8),
the two reworded rows updated in place with the change noted, and `personal_bigrams_unreadable`
backfilled — the P1 batch had landed that one Tatar string without its queue row (P2-P1).

### Tests

26 new JVM tests (1 374 → 1 400), zero failures:

- `PersonalQuarantineRecoveryTest` (+4): `aForgottenWordIsNotResurrectedByARestore`, the purge of a
  copy-only word, the last-word purge deleting the copy, and the unrewritable copy deleted
  outright — the resurrection hole above.
- `PersonalDictionaryScreenSourceContractTest` (10 → 16): the model now carries pairs — sections
  per language with words and pairs, a pairs-only language still gets its section, usage counts on
  every row and true totals on the sections, the 200 cap shared across both stores, the search
  covering pairs by context or successor; the Activity pins (counts row per card, per-store clear
  actions, the usage+delete summary) beside the untouched FLAG_SECURE/privacy-flags ones.
- `PersonalBigramScreenSourceContractTest` (7, new): the pairs controller wiring (owner reads, no
  file I/O, `forget`/`clearAll` for every deletion, notifyErased, 8 `uiPoster {` exits), the
  confirmed erasures at every level, the pairs quarantine card mirroring the words card, every new
  sentence translated into all three locales and naming no file/path/cause — with fail-capable
  counter-shapes.
- `IncognitoModeTest` (9, new): see the U8 section.
- Contract amendments, recorded per the phase discipline: `PersonalLearningGatesTest` (the
  UserManager null-check pin inverted to match the extracted conjunction; "five factors" → six);
  `PersonalDictionaryFeedbackSourceContractTest` (`uiPoster {` 8 → 9 for `clearWords`; the global
  erase lambda renamed `erased` → `wordsErased`); `PersonalQuarantineScreenSourceContractTest`
  (`personalQuarantines = null` 3 → 4 invalidations — the per-language clear also takes the copy).
  `DialogObscuredTouchContractTest` needed no amendment: the 4 new settings dialogs each pass
  through `filterObscuredTouches` (the balance the test enforces), and no new IME-attached dialog
  was added.

There is no UI-level settings harness in this project (Robolectric is rejected by design;
`SettingsHostActivity` cannot run off-device) — so the Activity is pinned by source-contract in
the established style, and every piece of logic that could be made pure was made pure and is
tested for real (the screen model, the gates object, the store mutations).

## U8 — incognito mode (done 2026-09-23)

### The semantics, as pinned

ONE switch — `PREF_INCOGNITO_MODE` (`Settings.java:107`, reader `readIncognitoModeEnabled` :350,
default OFF) — on the Preferences screen, right after the personal dictionary row and greyed with
it while suggestions are off (`SettingsHostActivity.kt:405`). While ON:

1. **Nothing new is learned.** No write reaches the personal words store, the personal bigrams
   store, or their pending counters — the pending hashes are written only from the completion
   event, and both sinks gate that event (and the acceptance bump, and the boundary flush) on the
   ONE predicate. The pause therefore freezes the pending counters too: no hash writes while it is
   on, nothing expires in either direction — the counters wait.
2. **What is already saved keeps surfacing.** The READ side never consults the pause: the gates
   the engines are built with read the personal-dictionary setting and nothing else. This is the
   documented choice, pinned by `whatIsAlreadySavedKeepsSurfacingWhileThePauseIsOn` — hiding the
   learned words would be a second feature ("forget for a while"), and the personal-dictionary
   switch already exists for that.
3. **Turning OFF resumes learning; nothing is retro-learned.** While the veto held, no observation
   reached any counter, so there is nothing to make up. Progress from BEFORE the pause is
   preserved, not erased — incognito is a pause, not a wipe.

### Wiring

The pause is the fifth factor of the ONE learning predicate. The conjunction itself moved into the
pure `PersonalLearningGates.mayLearn`
(`latin/dictionary/personalstore/PersonalLearningGates.kt`) — the established
`PersonalDictionaryRestriction` pattern: `LatinIME` computes the inputs, the decision is
arithmetic, and the arithmetic is covered by JVM tests. `LatinIME.mayLearnPersonalWords`
(`LatinIME.java:1324`) delegates to it, and BOTH sinks (words at :646, pairs at :652) are wired
with that very instance, so one switch pauses both stores. The personal screen says the state out
loud where the learned content is managed: a small "learning paused" note while the pause is on.
Deliberately NOT an enterprise restriction — it is a moment of the user's own privacy, not a
policy; and deliberately ONE toggle for both stores, the same "four states, three meanings"
argument that kept the personal dictionary a single switch.

### Tests

`IncognitoModeTest` (9): the full truth table of the pure gate (the pause vetoes every
combination; off, the decision is exactly the conjunction of the other five — this is the
"sink gating under incognito on/off" run for real), resume-with-nothing-to-make-up, the predicate
delegating and reading the key live, both sinks consulting the ONE predicate (pending counters
included, via the completion event), the read side NOT consulting the pause (fail-capable), the
key/default/reader, the row placement and greying, the screen note, and the summary naming both
halves of the contract.

## Gates (U7+U8, 2026-09-23)

| Gate | Result |
|---|---|
| python pipeline tests (`for f in tests/*/test_*.py`) | PASS (484) |
| `./gradlew test --rerun-tasks` | PASS — 1 400 tests, 0 failures |
| `./gradlew lintRelease` | PASS — 0 errors, 32 warnings filtered by baseline (unchanged), the same 4 pre-existing live warnings; nothing new landed |
| `rebuild_assets.py --check --allow-known-drift` | PASS (`ok: true`) |
| `./gradlew assembleRelease -PskipReleaseSigning` | PASS — unsigned APK **1 851 112 B** (≤ 3 145 728; +13 056 B over the P1 build) |
| `check-no-internet.sh` (release APK) | PASS — both levels (manifest + aapt2), backup whitelist intact |

AGENTS.md counters updated (JVM 1 374 → 1 400; the lint-baseline count is unchanged at 32, so no
edit there). PRIVACY.md stays the final batch's task per the phase plan; this section and the P1
one are its source. Device UAT (the screen on a real phone, learning observed pausing) remains
the phase's separate work, as the roadmap's working agreements require.

---

# Final block: PRIVACY.md + full gates + device UAT (2026-09-23)

## PRIVACY.md (1.4 → 1.5)

Updated to describe every user-data store that now exists, in the file's own structure and
honesty (both languages):

- **New section "Personal word pairs" / "Личные пары слов"**: the `.tpersb` store
  (`personal-bigrams-<subtype>-s1-f1.tpersb`, ≤ 1 000 pairs per language) — what is stored
  per pair (the context in NORMALIZED form only, the successor as typed, three counters —
  typed/tapped/last-use, never a clock time); the pending-hash counters
  (`pending-bigrams-<subtype>-s1-f1.bin`, salted truncated SHA-256 — no plaintext pair
  exists anywhere before graduation) and the store's own salt (`salt-bigrams.bin`, 16
  random bytes, destroyed by erase-all); the ≤ 2-of-3-cells ranking promise (static table
  first, learned pairs only into free cells, no duplicates); the `*.tpersb.quarantine`
  copy with restore/discard cards and the no-resurrection rule (a deleted pair is purged
  from the copy too); the erase paths (per-pair delete, clear-all pairs, the screen's
  erase-everything covering BOTH stores, app-data clear).
- **New section "Incognito mode" / "Режим инкогнито"**: the pause semantics exactly as
  pinned — no writes to either store and no pending-counter touches while ON; already
  saved words and pairs keep surfacing (a pause, not a wipe); resume picks up where it
  stopped, nothing is retro-learned.
- **Personal dictionary section**: the erase bullet now names the pairs listing and the
  erase-everything action covering both stores.
- Diff size: +2 sections ×2 languages, 3 bullets amended, version 1.4 → 1.5 dated
  2026-09-23.

## Full gates (2026-09-23, final tree)

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **15 files, 484 tests, 0 failing files** |
| `./gradlew test --rerun-tasks` | **1 400 tests, 0 failures / 0 errors / 0 skipped** (146 suites) |
| `./gradlew lintRelease --rerun-tasks` | BUILD SUCCESSFUL (21 tasks executed) |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `release_pack.sh` | unsigned **1 851 112 B** (= the U7+U8 recorded size — reproducible) → signed zopfli **1 834 276 B** ≤ 3 145 728, SHA-256 **`6dfdbab21c2321445a8d0294509be6f72ee7d7bfcc516f20d1f5c476c3f8973c`**, v2-only, cert `98ca6feb…42ad` |
| `check-no-internet.sh` (the signed APK) | both levels OK |
| `release_check.sh --quick` | **8/8 artifact checks PASS** — version 2.0.1/33 matches `app/build.gradle`; changelog gate passes on the existing `metadata/en-US/changelogs/33.txt` (348 B) |

## Device UAT (POCO C71, 720×1640, Android 15 Go, SDK 35, dark theme)

APK installed with `adb install -r` over the same-version build (2.0.1/33; data
preserved). Evidence: `build/device-uat-2026-09-23/`.

**The device disconnected physically mid-cycle** (USB, `adb` gone at the transport
level — not the 2026-09-20 clean-unplugged state but a drop during the run; ~4 minutes
of polling, server restart included, brought nothing back). What was verified BEFORE the
drop is genuinely verified; what was not is marked BLOCKED, not passed.

| # | Scenario | Result | Evidence |
|---|---|---|---|
| A1 | Personal bigram learning, probe pair «сәләм дөнья» (сәләм is NOT a bigram head → 0 static successors → any дөнья cell can only be personal; дөнья ∉ fallback top-8; the mission's «Айрат укытучы» was rejected by analysis: айрат is a head with 4 static successors, no free cell) — BEFORE: after `сәләм ` → сәләмә · һәм · белән | PASS | `11-strip.png` |
| A2 | …type the pair cleanly TWICE, hide the keyboard (the onFinishInput flush), probe again → **дөнья · сәләмә · һәм** — the learned pair in cell 1, ranked after static bigrams (none) and before forms and fallback, exactly the pinned chain | PASS | `13-strip.png` (field «сәләм ») |
| B1 | Dictionary screen: ТАТАРЧА section shows "1 word pair" — **сәләм → дөнья**, "0 uses · Delete", "Clear all word pairs", global "Erase everything saved" | PASS | `14-dict-screen.png` |
| B2 | Delete the pair via the row's "Forget «сәләм → дөнья»?" dialog → screen empty ("No saved words yet") | PASS | `15-pair-deleted.png` |
| B3 | …and it stops being suggested: after `сәләм ` → сәләмә · һәм · белән again (no дөнья) | PASS | `16-strip.png` |
| B4 | Re-learned the same pair afterwards (2 observations) — suggested again; used as the fixture for the incognito test | PASS | `18-strip.png` |
| C1 | Incognito toggle exists on the Preferences screen and flips ON | PASS | `19-incognito-on.png`, checked=true |
| C2–C5 | Incognito deep checks: "learning paused" note on the dictionary screen; existing pairs still suggested while ON; a pair typed twice while ON is NOT learned; turning OFF resumes learning | **BLOCKED** — device lost mid-sequence (see above); JVM `IncognitoModeTest` (9) pins every one of these semantics | — |
| D | Regression core on device (сцләм→сәләм, same-stem, sentence-start caps, after-comma, ru fallback, emoji tail) | **BLOCKED** — same cause; unchanged from the 2026-09-22 UAT on the previous build and untouched by this changeset's NEXT_WORD-slot edits (pinned in the 1 400) | — |
| E | Cold start ×3 + crash buffer on device | **BLOCKED** — same cause | — |

Notes for the record (harness, not app defects): the try-it field's BACK key exits
SetupActivity entirely (first BACK hides the keyboard only if it is up — a mistimed
sequence dumped the drive to the home screen once); the settings process was once
re-parented by an `am start` that did not take focus — both absorbed by
`--activity-clear-task` and FRESH_FIELD relaunches. No quarantine card was expected or
seen (no corrupt stores on the device).

### Device state left behind (to restore when the phone is back)

The cycle changed device state that could NOT be restored after the disconnect: default
IME is OURS (the user had Gboard); the toggles "Word suggestions", "Personal
dictionary" and "Incognito mode" are all ON (the user's state was all OFF). When the
phone returns: `ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`
and flip the three switches back OFF (or whatever the operator prefers). The pair
сәләм → дөнья remains learned in the tt personal-bigram store.

## DONE-WHEN audit (Phase 2 of `docs/ROADMAP.md`)

The roadmap's own "Done when": *learning observed on device (type a pair twice →
predicted) —* **met** (A1/A2); *deletion works —* **met** (B1–B3); *gates + UAT pass —*
**gates met** (table above); UAT met for the learning/screen halves, the remainder
BLOCKED by the hardware drop and listed above; *PRIVACY.md covers the new store —* **met**
(1.5, both languages). Item-level: P1 personal bigrams — **done** (learning + ranking
proven on device); U7 screen — **done on device** (words+pairs listed, per-row delete
works, empty state, clear-all paths present; the clear-all execution itself was not run
to protect the store used by the incognito sequence — the store-level clear-all is
JVM-pinned); U8 incognito — **done on the JVM side, toggle verified on device**, the
deep on-device sequence blocked (C2–C5).

What remains: the BLOCKED device items (C2–C5, D, E) the moment the phone is back, the
operator's commit/release decision (2.2.0), and the toggle/IME restoration listed above.
