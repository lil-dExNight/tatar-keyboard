# Architecture

Tatar Keyboard is an Android input method forked from Simple Keyboard, which is derived from AOSP
LatinIME. The keyboard core is the inherited Java code; suggestions, dictionaries, personal stores,
glide typing and the emoji panel are Kotlin. The application id is `org.tatarkeyboard.ime`, the
code namespace `rkr.simplekeyboard.inputmethod`. There are no runtime dependencies beyond the SDK.

## Package map

Paths are relative to `app/src/main/java/rkr/simplekeyboard/inputmethod/`.

| Package | Contents |
|---|---|
| `keyboard/` | `MainKeyboardView`, `PointerTracker`, `KeyDetector`, `KeyboardSwitcher`, `KeyboardLayoutSet`, long-press panel |
| `keyboard/internal/` | XML keyboard builder, key styles and texts, shift and keyboard state machines, key preview, `GlideTrail` |
| `event/` | `Event`, `InputTransaction` |
| `latin/` | `LatinIME` (the `InputMethodService`) with helpers `LatinImeAutocorrect`, `LatinImeGlide`, `LatinImeEmojiSearch`; `RichInputConnection`, `RichInputMethodManager`, `Subtype` |
| `latin/inputlogic/` | `InputLogic`: what a key press does to the text |
| `latin/suggestions/` | `SuggestionsController`, `SuggestionStripView`, `SuggestionStripState`, Tatar word utilities and suffix rules |
| `latin/dictionary/engine/` | `LatestOnlyPrefixEngine`, `MappedDictionaryEngine`, `CompositePrefixComputer`, `TdictPrefixIndex`, `TatBigrPrefixIndex`, typo recovery |
| `latin/dictionary/storage/` | asset publishing (`AtomicDictionaryStore`, `AtomicBigramStore`), validators, artifact specs and pins |
| `latin/dictionary/personal/` | personal file formats (`TpersFormat`, `TpersbFormat`, `TpersemFormat`), validators, snapshots |
| `latin/dictionary/personalstore/` | stores that own the personal files, learning gates, pending counters, quarantine |
| `latin/glide/` | glide decoder, gesture detector, path and key geometry |
| `latin/emoji/` | emoji panel, search, skin tones, recents, emoji suggestions |
| `latin/settings/`, `latin/setup/` | settings screens, onboarding |
| `accessibility/`, `compat/` | TalkBack delegates and key descriptions; platform shims (a forked `ExploreByTouchHelper`) |

## Input path

1. `MainKeyboardView.onTouchEvent` passes each `MotionEvent` to the pointer's `PointerTracker`.
2. `PointerTracker` asks `KeyDetector.detectHitKey(x, y)` for the key under the finger, handles
   sliding, long press, key repeat, the space-bar cursor swipe and glide detection, and reports
   through `KeyboardActionListener` (`onCodeInput`, `onTextInput`, `onGlideInput`, ...).
3. `LatinIME` implements that interface. `onCodeInput` builds an `Event` for `LatinIME.onEvent`,
   which first runs its hooks: emoji search (keys grow the search query instead of the text), undo
   autocorrect, glide whole-word undo, and autocorrect before a separator. Then the event goes to
   `InputLogic.onCodeInput`.
4. `InputLogic` edits text only through `RichInputConnection`, which wraps the platform
   `InputConnection` and caches the text around the cursor.
5. After the transaction `LatinIME` updates the shift state, calls
   `SuggestionsController.onTextChanged()` and `KeyboardSwitcher.onEvent`.

The host app controls the other end of the `InputConnection` and may be buggy or hostile.
Protections (in `RichInputConnection` and `InputLogic` unless noted):

- every editor call catches `RuntimeException` (a dead host throws `DeadObjectException`) and
  degrades silently; the next cache reload re-syncs;
- batch edits close in `finally`;
- host answers are cut to `Constants.EDITOR_CONTENTS_CACHE_SIZE` characters; a `SurroundingText`
  with an out-of-range selection empties the cache; negative or inverted selections are normalized;
- cache reloads are coalesced (at most one in flight) and applied on the UI thread;
- clips of `MAX_DIRECT_PASTE_CHARS` or more are pasted through the editor's context menu, avoiding
  `TransactionTooLargeException`;
- non-finite coordinates are rejected in `GlidePath` and in `SuggestionStripState` hit testing.

Trust boundaries in full: [THREAT-MODEL.md](THREAT-MODEL.md).

## Layouts and languages

Layouts are XML in `app/src/main/res/xml/`: `keyboard_layout_set_<name>.xml` names the alphabet,
symbols, phone and number keyboards, `rows_<name>.xml` and `rowkeys_<name>*.xml` define the rows.
The Tatar layout (`rows_tatar.xml`) is ЙЦУКЕН with an extra top row of ә ө ү җ ң һ
(`rowkeys_tatar_extra.xml`). The Russian layout offers the same letters as long-press keys.

Languages: Tatar (`tt_RU`, layout `tatar`), Russian (`ru`, layout `russian`) and English (`en_US`,
`qwerty`, with `qwertz` and `abc` as alternatives). The system sees one generic subtype
(`res/xml/method.xml`); the app manages its own language list. `SubtypeLocaleUtils` builds the
languages, `Settings.PREF_ENABLED_SUBTYPES` stores the enabled ones, `RichInputMethodManager` holds
the current one. The globe key (`Constants.CODE_LANGUAGE_SWITCH`) goes through `InputLogic` to
`LatinIME.switchToNextSubtype()` and `RichInputMethodManager.switchToNextInputMethod`, which cycles
the app's languages and, when the system allows it, moves to the next input method after the last.

## Suggestions

Suggestions for Tatar and Russian are off by default (`Settings.PREF_TATAR_SUGGESTIONS`) and never
run in password fields or fields that disallow suggestions or personalized learning.

**Threads.** `SuggestionsController` owns all strip state and runs on the UI thread only. Each
language has a `LanguageSlot` with one engine, and each engine runs lookups on its own single
worker thread. A separate executor starts engines and prepares dictionaries. Switching language
idles the old engine instead of tearing it down.

**Tokens.** `LatestOnlyPrefixEngine` keeps only the latest request; a new one supersedes a pending
one. Each request gets a `LookupToken` (engine instance, serial, editor session, language, query,
dictionary identity, `LookupKind`). The worker passes the result to `ResultHandoff`; the controller
posts it to the UI thread and applies it only if `isCurrent(token)` still holds.

**Lookup kinds** (`LookupKind`): `PREFIX` (word completion), `NEXT_WORD` (next-word prediction
after a committed word), `GLIDE` (glide decoding). At a sentence start the strip is filled
synchronously from a static table (`SentStartIndex`, `assets/dictionaries/*_sentstart_v1.txt`).

**Strip.** `SuggestionStripView` is one Canvas view with three cells
(`SuggestionStripState.CELL_COUNT`). A tap commits the word with a space.

**Word completion** (`CompositePrefixComputer.lookup`): exact dictionary candidates by frequency,
then at most one personal-dictionary word not already shown, then typo-recovery candidates. Typo
recovery (`TdictPrefixIndex.collectFuzzy`, configured by `FuzzyEditPolicy`) uses edit class #1
(long-press partner, from the layout's `moreKeys` via `KeyNeighborTable`) for every language, plus
class #4 (single substitution, only on an empty exact pass and a long enough prefix) for Tatar.

**Next-word prediction** (`CompositePrefixComputer.predict`): bigram successors, then learned word
pairs (at most `MAX_PERSONAL_BIGRAM_CELLS`), then word forms of the context word (Tatar,
`TatarSuffixRules`), then the dictionary's most frequent words (`FallbackWords`). A later source
never displaces or duplicates an earlier one. With emoji suggestions on, the last cell holds an
emoji for the context word: a learned emoji first, else the static table (`EmojiSuggestIndex`).

**Autocorrect** (opt-in `PREF_TATAR_AUTOCORRECT`, requires suggestions). The lookup that fills the
strip also yields an `AutocorrectAdvice` from edit class #1. On a space or punctuation
`LatinImeAutocorrect` has the controller replace the word before the separator reaches
`InputLogic`; one backspace right after undoes it (`RevertWindow`).

## Bundled dictionaries

Each language has a dictionary (`*.tdict.zlib`, TATDICT schema 2) and a bigram table
(`*.tatbigr.zlib`, TATBIGR schema 3) in `app/src/main/assets/`. The bigram table stores dictionary
word indices and names the dictionary's raw SHA-256 in its header, so it opens only against its own
dictionary. Formats: [ASSET-FORMATS.md](ASSET-FORMATS.md); build: [ASSET-PIPELINE.md](ASSET-PIPELINE.md).

On first use `AtomicDictionaryStore` and `AtomicBigramStore` inflate the asset, validate it
(`TdictValidator`, `TatBigrValidator`) and publish it to device-protected storage: temp file,
fsync, validate, atomic rename, directory fsync. `MappedDictionaryEngine` maps the file read-only
(`FileChannel.map`) and reads it in place. Pinned sizes and SHA-256 values are in
`DictionaryStorageContracts.kt` and `BigramStorageContracts.kt`. Without a usable dictionary a
language has no suggestions; without its bigram table only next-word prediction is empty.

## Personal dictionary

Opt-in (`PREF_PERSONAL_DICTIONARY`). Three stores per language share one worker thread
(`PersonalDictionaries.sharedStoreExecutor()`): `PersonalDictionaryStore` (`.tpers`, learned
words), `PersonalBigramStore` (`.tpersb`, learned word pairs) and `PersonalEmojiStore` (`.tpersem`,
learned emoji). Files live in the credential-protected `noBackupFilesDir`.

- **Learning gates.** `PersonalLearningGates.mayLearn`: suggestions eligible for the field (which
  excludes `IME_FLAG_NO_PERSONALIZED_LEARNING`), personal dictionary on, user unlocked since boot,
  not a postal-address field, learning not paused. `CleanRunMachine` reports only cleanly typed
  words and pairs.
- **Pending counters.** An entry is saved in plain text only after
  `PendingCounters.LEARN_THRESHOLD` clean observations; until then only a truncated salted SHA-256
  is kept, and it expires.
- **Writes** rewrite the whole file (temp, fsync, validate, atomic replace, directory fsync). The
  engine reads an immutable snapshot through a `@Volatile` field.
- **Quarantine.** A file that fails validation is moved aside, not deleted; the settings screen
  tells the user and can restore what is readable.
- **Pause learning** (`PREF_INCOGNITO_MODE`) stops all writes; saved entries keep appearing.

Settings screens list, delete and erase entries.

## Glide typing

On by default (`PREF_GLIDE_TYPING`), for Tatar and Russian. `GlideGestureDecider` in
`PointerTracker` decides whether a touch is a glide; `GlideTrail` draws the trail. On lift the path
goes to `SuggestionsController.onGlideInput` and to the engine as a `GLIDE` request; nothing is
decoded while the finger moves. `GlideDecoder` is a SHARK2-style statistical classifier (shape and
location channels plus a frequency weight), ported with attribution from FlorisBoard's
`StatisticalGlideTypingClassifier`. Its word index is built lazily on the engine worker
(`GlideDecoderHost`); only one language keeps an index in memory. The top word is committed on
lift; with suggestions on, the other candidates appear in the strip and a tap replaces the word.
The word takes the shift state: shift capitalizes it, Caps Lock types it in capitals. One
backspace right after a glide deletes the whole word (`LatinImeGlide`).

## Emoji panel

`EmojiPanelView` is a Canvas view that replaces `MainKeyboardView` while shown; `KeyboardSwitcher`
swaps them and `EmojiPanelController` loads data in the background from `assets/emoji/`: the set
(`EmojiSet`), search names (`EmojiSearchIndex`, shown in `EmojiSearchView` in place of the strip)
and skin tones (`EmojiSkinTones`). No emoji font is shipped; `GlyphProbe` drops entries the system
font cannot draw. Recents (`RecentEmojiStore`) are credential-protected. The panel opens from the
emoji key (`Constants.CODE_EMOJI`) or a long press on comma.

## Settings

`SettingsHostActivity` builds every screen from plain Views (no `android.preference`, no Compose)
with a manual back stack; `SettingsActivity` only forwards to it and keeps the component name used
by `method.xml`. Preferences are device-protected (`PreferenceManagerCompat`). `FLAG_SECURE` keeps
saved words out of screenshots and recent apps. Enterprise restrictions
(`res/xml/app_restrictions.xml`) can disable rows. `SetupActivity` is the onboarding.

## Privacy and security

- No `INTERNET` permission (`VIBRATE` is the only one); `scripts/check-no-internet.sh` checks the
  manifest and the built APK.
- `allowBackup="false"` and `data_extraction_rules.xml` keep all data out of backup and transfer.
- `LatinIME` is `directBootAware`: bundled dictionaries and preferences are device-protected and
  work before the first unlock; personal data and recents are credential-protected.
- Every binary reader validates before use and rejects bad input; personal words are never logged.

Details and the risk register: [THREAT-MODEL.md](THREAT-MODEL.md).

## Tests

- `app/src/test/`: JVM tests (JUnit 4, no Robolectric). Android types sit behind small interfaces
  (`EditorSurface`, `EngineHandle`, storage seams), so logic runs with fakes. `*SourceContractTest`
  files read `app/src/main` and pin identifiers and call sites a unit test cannot reach.
  `latin/golden/` exports golden vectors for a parity suite on another platform.
- `tests/<script>/test_*.py`: `unittest` tests of the Python asset pipeline in `scripts/`.
- `app/src/androidTest/`: device tests (glide on a real screen, dictionary I/O, draw allocations,
  emoji index reload, engine timing).
- `baselineprofile/`: generator of the baseline and startup profiles.

Budgets and how they are measured: [PERF-BUDGETS.md](PERF-BUDGETS.md).
