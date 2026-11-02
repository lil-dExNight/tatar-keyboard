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
| `latin/suggestions/` | `SuggestionsController`, `SuggestionStripView`, `SuggestionStripState`, the recent-clip cell (`RecentClipCell`), the inline-autofill host (`InlineAutofillStripView`, `InlineAutofillBinder`, `InlineStripSpecs`), Tatar word utilities and suffix rules |
| `latin/dictionary/engine/` | `LatestOnlyPrefixEngine`, `MappedDictionaryEngine`, `CompositePrefixComputer`, `TdictPrefixIndex`, `TatBigrPrefixIndex`, typo recovery |
| `latin/dictionary/storage/` | asset publishing (`AtomicDictionaryStore`, `AtomicBigramStore`), validators, artifact specs and pins |
| `latin/dictionary/personal/` | personal file formats (`TpersFormat`, `TpersbFormat`, `TpersemFormat`, `TcutFormat`, `TrefFormat`), validators, snapshots |
| `latin/dictionary/personalstore/` | stores that own the personal files (words, pairs, emoji, sentence starts, refused corrections, text shortcuts), learning gates, pending counters, quarantine, erase-all (`PersonalErasure`, `PersonalEraseAll`) |
| `latin/glide/` | glide decoder, gesture detector, path and key geometry |
| `latin/emoji/` | emoji panel, search, skin tones, recents, emoji suggestions |
| `latin/settings/`, `latin/setup/` | settings screens, onboarding |
| `latin/settings/backup/` | the SAF backup: zip layer, manifest, settings XML, transfer orchestration |
| `accessibility/`, `compat/` | TalkBack delegates and key descriptions; platform shims (a forked `ExploreByTouchHelper`) |

## Input path

1. `MainKeyboardView.onTouchEvent` passes each `MotionEvent` to the pointer's `PointerTracker`.
2. `PointerTracker` asks `KeyDetector.detectHitKey(x, y)` for the key under the finger, handles
   sliding, long press, key repeat, the space-bar cursor swipe, the delete-key swipes (a drag
   selects text to delete, a fast left flick deletes the last word) and glide detection, and
   reports through `KeyboardActionListener` (`onCodeInput`, `onTextInput`, `onGlideInput`, ...).
   A space-bar trackpad that is still active when the keyboard closes or the field changes ends
   without restoring the selection it started from
   (`PointerTrackerQueue.Element.abandonSpaceTrackpad`), and every field start forgets the saved
   selection (`LatinIME.resetSpaceTrackpad`); a cancel inside the same field still restores it.
3. `LatinIME` implements that interface. `onCodeInput` builds an `Event` for `LatinIME.onEvent`,
   which first runs its hooks: emoji search (keys grow the search query instead of the text), undo
   autocorrect, glide whole-word undo, and autocorrect before a separator. Then the event goes to
   `InputLogic.onCodeInput`.
4. `InputLogic` edits text only through `RichInputConnection`, which wraps the platform
   `InputConnection` and caches the text around the cursor. Backspace, space-slide and delete-swipe
   share allocation-free emoji-cluster boundaries, including variation selectors, skin tones,
   regional-indicator pairs, keycaps, ZWJ sequences and tags.
5. After the transaction `LatinIME` updates the shift state, calls
   `SuggestionsController.onTextChanged()` and `KeyboardSwitcher.onEvent`.

The host app controls the other end of the `InputConnection` and may be buggy or hostile.
Protections (in `RichInputConnection` and `InputLogic` unless noted):

- every editor call catches `RuntimeException` (a dead host throws `DeadObjectException`) and
  degrades silently; the next cache reload re-syncs;
- outside a batch edit, every editor call first re-reads the bound field's connection from the
  service (`RichInputConnection.refreshConnection`, a local read with no IPC), so a call never
  reaches a field that is no longer bound; a batch keeps the connection it began on, so it opens
  and closes on one editor. The service side sits behind the package-private `EditorHost` seam;
- batch edits close in `finally`;
- host answers are cut to `Constants.EDITOR_CONTENTS_CACHE_SIZE` characters; a `SurroundingText`
  with an out-of-range selection empties the cache; negative or inverted selections are normalized;
- cache reloads are coalesced (at most one in flight) and applied on the UI thread. Both
  `reloadTextCache` methods ask `EditorHost.mayReadEditorText()` (no password field) before any
  read, which covers the field-start fill, the coalesced follow-up and the reload a refused glide
  commit requests; a password field start binds the connection without reading
  (`RichInputConnection.onStartInput`). The background reader reads only the connection captured
  at that check, and clearing the caches also drops a follow-up request folded in before it;
- the end of an input session drops the cached text and the selection; a hide that keeps the
  session drops only the text, so the next show re-reads it from the editor instead of the
  session's first `EditorInfo`;
- clips of `MAX_DIRECT_PASTE_CHARS` or more are pasted through the editor's context menu, avoiding
  `TransactionTooLargeException`;
- non-finite coordinates are rejected in `GlidePath` and in `SuggestionStripState` hit testing.

Trust boundaries in full: [THREAT-MODEL.md](THREAT-MODEL.md).

## Layouts and languages

Layouts are XML in `app/src/main/res/xml/`: `keyboard_layout_set_<name>.xml` names the alphabet,
symbols, phone and number keyboards, `rows_<name>.xml` and `rowkeys_<name>*.xml` define the rows.
The Tatar layout (`rows_tatar.xml`) is ЙЦУКЕН with an extra top row of ә ө ү җ ң һ
(`rowkeys_tatar_extra.xml`). The Russian layout offers the same letters as long-press keys.
With the number row off, the first ten keys of the `й`–`х` row of both layouts carry the digits
1–0 on long press (a `<switch>` on `showNumberRow` in `rowkeys_tatar1.xml` and
`rowkeys_russian1.xml`); a key with a letter partner keeps that letter first.

Languages: Tatar (`tt_RU`, layout `tatar`), Russian (`ru`, layout `russian`) and English (`en_US`,
`qwerty`, with `qwertz` and `abc` as alternatives). The system sees one generic subtype
(`res/xml/method.xml`); the app manages its own language list. `SubtypeLocaleUtils` builds the
languages, `Settings.PREF_ENABLED_SUBTYPES` stores the enabled ones, `RichInputMethodManager` holds
the current one. The globe key (`Constants.CODE_LANGUAGE_SWITCH`) goes through `InputLogic` to
`LatinIME.switchToNextSubtype()` and `RichInputMethodManager.switchToNextInputMethod`, which cycles
the app's languages and, when the system allows it, moves to the next input method after the last.
On `ACTION_LOCALE_CHANGED`, `SystemBroadcastReceiver` reloads the language list only when the
manager is already initialized (`RichInputMethodManager.reloadSubtypesIfInitialized`); a process
the broadcast starts cold reads the list fresh at init.

## Suggestions

Suggestions for Tatar and Russian are on by default for new installs
(`Settings.PREF_TATAR_SUGGESTIONS`; the personal dictionary and autocorrection stay off until
enabled) and never run in password fields, in fields that disallow suggestions or personalized
learning, before the first user unlock, or while the keyguard is shown (also after the first
unlock). Field start and window show refresh the lock snapshot; learning, autocorrect, text
shortcuts, suggestion commits and glide re-check both lock states at their event and write
boundaries, so a mid-session lock invalidates pending work and locked-period typing is not learned
after unlock. Learning reads both states live; the other checks reuse one answer, which screen
and unlock broadcasts, field start and window show drop. Once it is older than
`LatinIME.PRIVACY_LOCK_CACHE_MS` a worker re-reads it while the old answer is still served; past
twice that age the check reads on the UI thread. A lock without a screen change (lockdown from
the power menu) is therefore seen within twice that time.

**Threads.** `SuggestionsController` owns all strip state and runs on the UI thread only. Each
language has a `LanguageSlot` with one engine, and each engine runs lookups on its own single
worker thread. A separate executor starts engines and prepares dictionaries. A failed preparation
is retried once, at the next eligible input start (`LanguageSlot.preparationRetryUsed`); after a
second failure the language waits for the suggestions setting to go off and on. Switching language
idles the old engine instead of tearing it down. `LatinIME` publishes the new layout's key-neighbor
table and glide geometry before the switch, and the engine that becomes active is handed both
(`SuggestionsController.setActiveLanguage`).

**Tokens.** `LatestOnlyPrefixEngine` keeps only the latest request; a new one supersedes a pending
one. Each request gets a `LookupToken` (engine instance, serial, editor session, language, query,
dictionary identity, `LookupKind`). The worker passes the result to `ResultHandoff`; the controller
posts it to the UI thread and applies it only if `isCurrent(token)` still holds.

**Lookup kinds** (`LookupKind`): `PREFIX` (word completion), `NEXT_WORD` (next-word prediction
after a committed word), `GLIDE` (glide decoding). At a sentence start (the field start, a line
start, or '.', '!', '?', '…' followed by a space) the strip is filled synchronously: the personal
sentence-initial overlay (`SentStartOverlayStore`, learned from sentence-initial commits through
the shared learning gates) leads, then the static table (`SentStartIndex`,
`assets/dictionaries/*_sentstart_v1.txt`), deduplicated, at most one cell per word.

**Strip.** `SuggestionStripView` is one Canvas view with up to four cells
(`SuggestionStripState.CELL_COUNT`): three are visible by default, the fourth-cell setting widens
the strip to all four (`SuggestionStripState.visibleCellCount`; the engines always compose four
candidates and the controller truncates the band). Cells fill the keyboard's dock content
rectangle. A right dock mirrors only ranked bands across the visible grid, putting the leading
candidate nearest the right thumb; undo, recent-clip and emoji-only offers keep their documented
order. Drawing, hit testing and accessibility use `SuggestionStripState`'s shared geometry. A tap
commits the word with a space; a punctuation mark that
attaches to a word (`. , ; : ! ? ) ] }`) typed right after takes that space's place ("сүз, ",
"сүз?! "), tracked by the cursor position in `InputLogic`. A space typed there is swallowed and
counts as the first space of a double-space period. Two quick spaces give a period only in a
general text field (`InputTypeUtils.isGeneralTextInputType`: the text class without the email,
URI, password, phonetic and filter variations). Cursor moves by the keyboard itself (space
slide, delete swipe, word-delete flick, the edit menu's arrows) drop this state
(`InputLogic.onKeyboardCursorMove`).

**Inline autofill** (API 30+, advertised in `method.xml`). A field that supports inline
suggestions takes the strip over: `LatinIME.onCreateInlineSuggestionsRequest` answers with one
presentation spec per cell (`InlineStripSpecs`, the cells' exact sizes, min equal to max). Specs
and hosted cells use the dock content rectangle and keep platform order.
`LatinIME.onInlineSuggestionsResponse` hosts the platform's content views in
`InlineAutofillStripView`, a container inflated only then (`InlineAutofillBinder` keeps every
autofill class behind the API gate). While a session is up the word strip is GONE but keeps
updating. An empty response, field finish or window hide empties the host and puts current words
back (`InputView.hideInlineAutofillStrip`). Responses require an unlocked user and no keyguard;
a response while hidden is refused, and after a return only a newly delivered response can host
content. A late inflate from an ended session is rejected. Password fields host chips like any
other field, because login forms are the feature's main use; a response arriving while the emoji
panel owns the surface is refused instead of hosted.

**Word completion** (`CompositePrefixComputer.lookup`): the dictionary's exact candidates, at
most one personal-dictionary word that the dictionary did not return, then typo-recovery
candidates. The exact pass carries the previous committed word (the `EditorSurface` cache read, by
the next-word context rule) and orders its candidates in three tracks, each by frequency: the
previous word's packed bigram successors (`TatBigrPrefixIndex.collectSuccessorIds` hands the ids
over without decoding strings), then same-stem forms (Tatar only, when the typed prefix is itself
a word of at least four code points), then the rest. With exact candidates the personal word
takes the second cell (`[exact0, personal, exact1, exact2]`). It takes the first cell when the
exact pass is empty, or when it is promoted: its usage is at least
`CompositePrefixComputer.PROMOTION_MIN_USAGE` and the dictionary's top exact candidate is below
`PROMOTION_TOP_FREQUENCY_GATE`. A personal word that duplicates a dictionary candidate only
changes how that cell is spelled.

Typo recovery (`TdictPrefixIndex.collectFuzzy`, configured per engine by `FuzzyEditPolicy`) runs
edit class #1 (long-press partner, from the layout's `moreKeys` via `KeyNeighborTable`) for every
language. When the exact pass is empty and the prefix has at least four code points, both shipped
engines also run the shared DL-1 tier (substitution, deletion, insertion and transposition under
one rank tag, ranked by frequency). Both apply the same-length bonus, which ranks a candidate as
long as its variant before that variant's continuations: the Tatar policy in class #1 and in the
tier, the Russian policy inside the tier only (`FuzzyEditPolicy.sameLengthBonusOnlyInDl1`).

**Next-word prediction** (`CompositePrefixComputer.predict`): the bundled bigram successors (at
most four), then learned word pairs in the cells left free (at most `MAX_PERSONAL_BIGRAM_CELLS`;
a duplicate keeps the bundled spelling), then the dictionary's most frequent words
(`FallbackWords`), excluding everything shown. One learned pair may displace a bundled successor:
with three or more successors, the first pair observed at least
`PERSONAL_BIGRAM_PRECEDENCE_MIN_FREQUENCY` times and not already in the first three cells takes
the third cell, and the fourth successor leaves the strip. Without a bigram table (not attached
yet, or the attach failed) predict still answers with learned pairs and the fallback; only the
bundled successors and the completion's context track are missing. The engine reports such an
answer (`EngineHandle.nextWordWithoutTable`), and when the table attaches later,
`SuggestionsController.onBigramTableReady` asks again if the strip still shows that NEXT_WORD
moment and holds no active-language word or only words predicted without the table. With emoji
suggestions on, the last cell holds an emoji for the context word: a learned emoji first, else the
static table (`EmojiSuggestIndex`).

**Companion language.** The layout the user chose owns the strip; the companion, the first other
language of `DictionaryArtifactSpec.ALL` whose engine is warm and not pending release, may fill
what the active language left. A companion engine is never prepared for this, so it answers only
for a language already used since the keyboard started. For PREFIX the companion is asked after
the active answer, when that answer's exact pass was empty or cells are free. Its candidates fill
the free cells from the end of the active ones, or from the first cell after an empty active
answer. After an empty active exact pass the lead rule is armed: a non-empty companion exact pass
leads and the active typo-recovery candidates fill the cells it leaves; an empty one only fills
free cells. For NEXT_WORD the companion is asked when cells are free and its words go before the
emoji cell. Companion PREFIX cells take the typed casing, duplicates are dropped by shown form,
and the companion gets no previous-word context; its typo recovery uses the active layout's
neighbor table. It is never asked for a glide, a sentence start, an autocorrect preview strip or a
text-shortcut strip.

**Autocorrect** (opt-in `PREF_TATAR_AUTOCORRECT`, requires suggestions). The lookup that fills the
strip also yields an `AutocorrectAdvice` from edit class #1. The engine withholds it, on its
worker, for a word in the personal dictionary and for an exact entry of the companion's dictionary
(`CompanionLexicon`, which reads the companion live, so it protects only while that engine is
warm). On a space or punctuation
`LatinImeAutocorrect` has the controller replace the word before the separator reaches
`InputLogic`; one backspace right after undoes it (`RevertWindow`). While the undo window is open,
the strip's first cell offers the typed word back in quotes (a tap reverts, like the backspace).
An undone correction is not repeated or previewed for that word again in the same field session.
Undoing the exact same correction (the same typed word replaced with the same word) a second time,
in any session, persists the pair in the refused-corrections store (`RefusedCorrectionStore`,
`.tref`, per language, capped and evicted oldest-first): that correction never fires and is never
previewed again. The match is the exact pair; a different replacement named for the same word still
fires. A text-shortcut expansion's undo shares only the session list: the pair is user-managed
content with its own screen.

**Text shortcuts** (managed on the "Text shortcuts" settings screen; one list for all layouts,
`TextShortcutStore`, `.tcut`). While the typed word is exactly a saved shortcut, the strip offers
its expansion in the first cell; a space or punctuation replaces the word through the same
separator-time commit path as autocorrect, so the same undo window covers it. A tap on the cell
commits like an accepted suggestion. An expansion is never learned from, and an undone expansion
is not offered again in the same field session. A settings row opens a prefilled expansion editor;
deletion and replacement of an existing normalized shortcut each require their own confirmation.

**Recent-clip cell.** While the strip is otherwise idle (an empty prefix and no next-word context)
and the clipboard holds a fresh text clip, the strip's first cell offers the clip's first line,
truncated; a tap commits the full clip text (`InputLogic.commitClipText`). The clip is held in
memory only (`RecentClipCell`), never written to disk or learned from. A generation-stamped timer
clears the text and removes the painted cell at the freshness deadline. The direct-paste size
limit rejects oversized clips before text materialization and before the editor commit. The
clipboard listener is registered only while the input view is shown, the clipboard is not read
while the session or the live keyguard check says locked (`LatinIME.capturePrimaryClipForOffer`),
and the cell is never offered where suggestions may not run (`LatinIME.onWindowShown`/
`onWindowHidden`) or for a clip its source app marked sensitive
(`ClipDescription.EXTRA_IS_SENSITIVE`, `RecentClipCell.mayOfferClip`).

## Bundled dictionaries

Each language has a dictionary (`*.tdict.zlib`, TATDICT schema 2) and a bigram table
(`*.tatbigr.zlib`, TATBIGR schema 3) in `app/src/main/assets/`. The bigram table stores dictionary
word indices and names the dictionary's raw SHA-256 in its header, so it opens only against its own
dictionary. Formats: [ASSET-FORMATS.md](ASSET-FORMATS.md); build: [ASSET-PIPELINE.md](ASSET-PIPELINE.md).

On first use `AtomicDictionaryStore` and `AtomicBigramStore` inflate the asset, validate it
(`TdictValidator`, `TatBigrValidator`) and publish it to device-protected storage: temp file,
fsync, validate, atomic rename, directory fsync. Every process start validates the published file
again before activating it; the first activation after that check or a publication reuses its
result while the file keeps the recorded length and modification time, and every later activation
validates again. The lease carries a process-local validation token (`StoreValidationToken`), so
the engine's open repeats only the header and section checks and skips the structural walk the
store already ran. `MappedDictionaryEngine` maps the file read-only
(`FileChannel.map`) and reads it in place. The bigram table attaches lazily: publication hands the
engine its catalog, and the engine maps and opens the table on its worker at the first next-word
lookup, so a session that never predicts a next word never maps it. Pinned sizes and SHA-256 values
are in `DictionaryStorageContracts.kt` and `BigramStorageContracts.kt`. Without a usable dictionary a
language has no suggestions; without its bigram table next-word prediction has no bundled
successors (see **Next-word prediction** above).

## Personal dictionary

Opt-in (`PREF_PERSONAL_DICTIONARY`). The per-language stores share one worker thread
(`PersonalDictionaries.sharedStoreExecutor()`): `PersonalDictionaryStore` (`.tpers`, learned words),
`PersonalBigramStore` (`.tpersb`, learned word pairs), `PersonalEmojiStore` (`.tpersem`, learned
emoji) and `SentStartOverlayStore` (`.tsent`, sentence-initial words). `RefusedCorrectionStore`
(`.tref`, per language) holds the refused corrections of the autocorrect undo; it has no pending
counters and no salt (a refusal is recorded only under the learning predicate, and it suppresses
instead of suggesting). Files live in the credential-protected `noBackupFilesDir/personal/`. The
text-shortcut store (`TextShortcutStore`, `.tcut`, one list for all layouts, managed on its own
settings screen) shares the same directory and worker but holds managed content: pairs enter only
from the settings screen, with no counters, no pending hashes and no learning.

- **Learning gates.** `PersonalLearningGates.mayLearn`: suggestions eligible for the field (which
  excludes `IME_FLAG_NO_PERSONALIZED_LEARNING`), personal dictionary on, user unlocked since boot,
  not a postal-address field, learning not paused. `CleanRunMachine` reports only cleanly typed
  words and pairs; the paste key and the edit-menu paste mark the run dirty
  (`SuggestionsController.onClipboardPaste`). A word counts as unknown to the dictionary when the
  exact pass for one of its proper prefixes was empty, whatever typo recovery showed
  (`CompositePrefixComputer.lastExactMissPrefix`). A pair is learned only where the first word
  would be the next-word context of the second, so never across a sentence end, a line break, a
  number or an emoji.
- **Pending counters.** An entry is saved in plain text only after
  `PendingCounters.LEARN_THRESHOLD` clean observations; until then only a truncated salted SHA-256
  is kept, and it expires.
- **Writes** rewrite the whole file (temp, fsync, validate, atomic replace, directory fsync). The
  engine reads an immutable snapshot through a `@Volatile` field. A failed whole-file write
  suppresses the learning path's retries until the next flush boundary or a successful write, so
  a full disk costs one failed write per input session instead of one per typed word; pending
  counters keep accumulating in memory meanwhile.
- **Loading.** A store opens on its worker after the unlock gate. Reading and validation fail
  apart (`PersonalFileReads`): a read that throws (an I/O error) leaves the store not loaded, with
  an empty snapshot, no learning and no writes, so the file on disk is never overwritten from
  empty memory; the next `prime()` tries again. Words and pairs prime at engine creation, on each
  repaint of the "Saved words" screen and before a backup; learned emoji and refused corrections
  on each strip query; sentence starts at each sentence start; text shortcuts once on the strip's
  first lookup, on their own settings screen and before a backup. A `clearAll` before the first unlock also leaves the store not loaded.
- **Quarantine.** Only a file that fails validation, or exceeds its size cap, is moved aside, not
  deleted; the settings screen tells the user once and can restore what is readable. A failed
  move preserves the original file, and a failed purge rewrite preserves the original quarantined
  copy. A pending-counter file that does not parse moves to its own slot
  (`pending-*.bin.quarantine`) with no notice: it holds only salted hashes that relearn by
  themselves, and it is neither listed nor restored.
- **Erase everything saved** (`PersonalErasure`, `PersonalEraseAll`). The settings screen first
  deletes the import journal and its temp (if either stays, the erase stops), then clears the recent
  emoji, then has every store of every supported language (words, pairs, emoji, sentence starts,
  refused corrections) and the text-shortcut store run its `clearAll` (memory, file and whichever
  pending counters, salt, quarantined copy and notice marks it keeps), and finally sweeps whatever
  is left in the personal directory, queued behind those erasures on the store worker and without
  following links out of it. Success means every store reported success, the directory is empty and
  the recent-emoji file is absent or empty. The row is offered whenever the directory holds any file
  (`PersonalErasure.hasSavedFiles`), also when the screen lists nothing.
- **Pause learning** (`PREF_INCOGNITO_MODE`) stops all writes; saved entries keep appearing.
- **Backup restore.** `replaceAll` swaps a store's file for bytes from a backup archive (or deletes
  it) on the store's worker, re-validating first, and re-publishes the snapshot, so an import takes
  effect in the live process. Import validates the archive and every recognized current file,
  writes a durable journal of the old and new states, then applies the new state through those
  owners. A failure rolls the old state back. An interrupted journal is rolled back when the
  keyboard starts or the user unlocks (`BackupCoordinator.recoverInterruptedImport`) and when
  settings open; a journal that fails validation is deleted, and erase-all deletes the journal
  before the stores. Export and import first prime the store of every recognized personal file
  on the store worker (`BackupTarget.openPersonalStore`), so a damaged file is quarantined by its
  own store and left out of the export; a store left not loaded makes export fail and import
  refuse before the journal is written. The archive holds the settings (minus the restriction
  cache), words, pairs, learned emoji, refused corrections and text shortcuts; sentence starts,
  recent emoji, pending counters, salts and quarantined copies stay out. Pending counters and the
  salt survive a restore, and quarantined copies stay in their own flow. Export also fails before
  writing if any recognized personal file cannot be read and validated. Import reports
  personal-dictionary and pause-learning switch changes.

Settings screens list, delete and erase entries for every supported language, including disabled
ones. Manual word addition still targets an enabled language.

## Glide typing

On by default (`PREF_GLIDE_TYPING`), for Tatar and Russian. `GlideGestureDecider` in
`PointerTracker` decides whether a touch is a glide; `GlideTrail` draws the trail. A glide arms only
on an alphabet keyboard where the field and layout can decode it
(`PointerTracker.setGlideAvailable`, set from the glide geometry); elsewhere, English and the
symbols pages included, a slide is ordinary sliding key input. On lift the path goes to
`SuggestionsController.onGlideInput` and to the engine as a `GLIDE` request; nothing is decoded
while the finger moves. `GlideDecoder` is a SHARK2-style statistical classifier (shape and location
channels plus a frequency weight), ported with attribution from FlorisBoard's
`StatisticalGlideTypingClassifier`. Its word index is built lazily on the engine worker
(`GlideDecoderHost`); only one language keeps an index in memory. The top word is committed on lift
(`InputLogic.commitGlideWord`); the other candidates appear in the strip and a tap replaces the
word. The decode's N-best is reranked by the previous committed word (`GlideBigramRerank`): the
successor list holds at most `CompositePrefixComputer.MAX_GLIDE_LEARNED_SUCCESSORS` learned pairs
ahead of the bundled successors, only its first `GlideBigramRerank.MAX_RANKED_SUCCESSORS` entries
rank, each rank applies the configured rank penalty once more, and an unranked word gets the penalty
of one rank past the last, so a long successor list can neither outweigh every shape score nor
overflow the penalty. The strip side is gated on the glide switch rather than the suggestions switch, because the
candidates are corrections of the gesture, so they also appear with suggestions off, while
typed-text suggestions stay off. The word takes the shift state: shift capitalizes it, Caps Lock
types it in capitals.

Letters and doubled letters: `GlideKeyGeometry` is built from the live keys and their long-press
keys. A long-press letter without a key of its own is an alias of its base key, so a word with it
is decoded from a gesture over that key and committed with the right letter: on the Tatar layout
`ъ` on `ь` and `ё` on `е`; on the Russian layout the Tatar letters too. A letter that is the long
press of two keys takes its base from `ALIAS_BASES` (`һ` on `х`, `ә` on `а`) or gets no alias;
digits and other non-letters never become aliases. Words that share a key sequence (`все` and
`всё`) compete by frequency. A word with a doubled letter is scored against its looped ideal
path, so the gesture needs a loop or a dwell there, because the plain path belongs to the
undoubled twin. Two different letters that share a key through an alias (`её`) draw no loop and
score against the plain path. When no indexed word has the
collapsed key sequence (the twin bit of `GlideWordIndex`, recomputed with the personal words),
the doubled word is also scored against the plain path and keeps the better score.

Spacing: a glide adds no space after the word. It prepends one space when the text before the
cursor ends in a letter, a digit, a mark that attaches to a word (`. , ; : ! ? ) ] }`) or a
closing quote: "сүз," plus a glide gives "сүз, дөнья", a typed or glided "сүз" gives
"сүз дөнья", and "«сүз»" gives "«сүз» дөнья". Closing quotes are `»`, `”` and a straight `"`
after a character that is not whitespace, an opening bracket or an opening quote. After
whitespace, an opening bracket, an opening quote (`«`, `“`, `„`, a straight `"` after a space or
at the field start), a dash, an emoji or at the field start it prepends nothing.
A letter or digit typed right after the commit gets a space before it; punctuation attaches to the
word. One backspace right after a glide deletes the whole word with the space it added
(`LatinImeGlide`), and the gesture's remaining candidates re-bind to the emptied position, so a
tap commits one of them instead of re-gliding.

Capitals: with auto-capitalization on and a field that asks for sentence caps
(`TYPE_TEXT_FLAG_CAP_SENTENCES`), a word whose prepended space follows '.', '!' or '?' starts
with a capital. In a field without that flag the word stays lower case, as typed letters do there:
the field flags decide for both.

Refused glide: the commit is refused when a letter follows the cursor, when the trailing word or
the cursor changed between the gesture and the decode result, or when the before-cursor cache is
empty although the cursor is past the text start (it then requests a reload). A refusal gives one
haptic tick (`GlideRefusalFeedback`, wired to `AudioAndHapticFeedbackManager.performTickFeedback`,
which honors the vibrate setting and vibrates on API 29+ only). A letter after the cursor, like an
unknown cursor, refuses before the decode and leaves the strip as it was; one that appears by the
decode result shows no candidates either, since a tap on one would be refused the same way. For a
stale or unknown cache the strip shows the decoded candidates (`REFUSED_GLIDE` binding); a tap
commits one through the same glide commit path against the live text, with the same spacing and
undo, and is refused with another tick and reload request while the cache is still unknown. Like
the alternates, this strip is gated on the glide switch and also appears with suggestions off.

## Emoji panel

`EmojiPanelView` is a Canvas view that replaces `MainKeyboardView` while shown; `KeyboardSwitcher`
swaps them and `EmojiPanelController` loads data in the background from `assets/emoji/`: the set
(`EmojiSet`), search names (`EmojiSearchIndex`, shown in `EmojiSearchView` in place of the strip)
and skin tones (`EmojiSkinTones`). No emoji font is shipped; `GlyphProbe` drops entries the system
font cannot draw. Skin-tone bases are probed once per process in the background
(`EmojiToneVerdicts`, shared with the strip's emoji cell): a base whose toned form the font cannot
draw gets no tone picker, and none of its toned forms is recorded in or shown from recents or
offered as a learned emoji. Recents (`RecentEmojiStore`) record only sequences the panel's set
holds, are credential-protected, hidden while the keyguard is shown and not recorded while
learning is paused (`RecentEmojiGateState`). The panel opens from
the emoji key (`Constants.CODE_EMOJI`) or a long press on comma.

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
  readable before the first unlock; personal data and recents are credential-protected. While the
  keyguard is shown, before or after the first unlock, there is no suggestion strip, no glide
  typing, no Recent tab, no clipboard paste from the paste key or the edit menu, no suggestions
  offer and no learning. The learning predicate reads the keyguard live once per input event
  (`LearningKeyguardScope`).
- On Android 7.x, TalkBack hears password-field character keys as a dot unless spoken passwords
  are allowed or headphones are on (`KeyDescriptionMapper.shouldObscurePasswordKey`).
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
- `baselineprofile/`: generator of the baseline and startup profiles
  (`app/src/main/generated/baselineProfiles/`, read by every build). ART applies the bundled
  profiles only on API 28+; on API 24–27 profile compilation needs androidx.profileinstaller,
  which the zero-dependency rule forbids, so there the profiles only shape the dex layout.
