# Next release plan

Work plan for the release after 3.7.0: what to build, in which order, how each item is tested
and what must stay unchanged. It owns the items `docs/BACKLOG.md` listed as researched but not
done. When the release ships, delete this file, add it to `docs/HISTORY.md` and remove the
references to it from `docs/README.md`, `docs/BACKLOG.md` and `HANDOFF.md`.

Paths below are relative to the repository root; `…/` stands for
`app/src/main/java/rkr/simplekeyboard/inputmethod/` and `…test/` for
`app/src/test/java/rkr/simplekeyboard/inputmethod/`. Effort: S is under a day, M a few days,
L a week or more.

## Scope and order of work

Priorities:

- **P1, ships in this release:** A1 (space before a glide), A2, A3, B1, B2, B3, B4.
- **P2, ships if done and verified before the code freeze:** A1 phantom space, A4, A5, C3
  (haptics). An unfinished P2 item goes back to `docs/BACKLOG.md` with one line on its state.
- **P3, ships only if its measurement passes the decision rule in its section:** A6. Otherwise
  the result is recorded in `docs/BACKLOG.md` and the code is not merged.

Order:

1. B3, B4, B2: text only. B2 and B4 go first if the 3.7.0 store upload and GitHub Release are
   still pending (see "Open release steps" in `HANDOFF.md`).
2. B1 mechanism, so every later build has the startup-optimized dex layout. The profiles are
   regenerated at step 7.
3. A2, then A1 with its phantom space, then A3.
4. A4, then A5. Both change the glide calibration baseline; A4 lands first so A5 is measured
   against it.
5. A6.
6. C3 code, which ships only after its device test passes; without a device before the code
   freeze it goes back to `docs/BACKLOG.md` like any unfinished P2 item.
7. Code freeze: regenerate the baseline and startup profiles (B1), measure cold start on a device
   if one is available (B1 step 8 covers the case without one).
8. Device checks on the release candidate. This release touches the input path, for which
   `docs/DEVICE-TEST-PLAN.md` requires stages 1–5: run them when the reference device is
   available, together with the device parts of A4, A5 and A6 and the Track C items the hardware
   allows. Otherwise run the emulator smoke test (required anyway, see below) and record the
   device stages as not run on the `Device tests` line of the release record
   (`docs/PUBLISH-CHECKLIST.md`, "Release record"). Only
   C3 needs its device part to ship; A4, A5 and A6 ship on their JVM tests and measurements.
9. Release through `docs/PUBLISH-CHECKLIST.md`.

Release gates: after every change, the mandatory gates in `AGENTS.md` ("Mandatory gates"). For
the release candidate, steps 2–4 of `docs/PUBLISH-CHECKLIST.md`. This release changes input and
glide typing, so the emulator smoke test on the signed APK is required, not optional. No item
changes the bundled dictionaries, bigram tables or emoji data: `python3 scripts/rebuild_assets.py
--check --allow-known-drift` stays clean and an update re-inflates nothing.

## Track A: work without a phone

### A1. Glide spacing (P1; phantom space P2)

**Problem.** A glide word is glued to what precedes it. After punctuation, `сүз,` plus a glide
gives `сүз,дөнья`. After a typed word that the previous glide did not commit, the gesture types
nothing: `SuggestionsController.onGlideInput` returns when `editor.cachedWordBeforeCursor()` is
not empty and differs from `glideCommittedWord`, and `InputLogic.commitGlideWord` refuses any
other trailing word than `chainedAfter`. The reported case, a glide into a refocused field whose
text ends in a word and comes out glued to it, means the before-cursor cache did not hold that
word when the commit decided.

**Goal and acceptance.**

- `сүз,` plus a glide gives `сүз, дөнья`; the same after every mark of
  `TatarWordUtils.swapsWithAutoSpace` (`. , ; : ! ? ) ] }`).
- A typed word plus a glide gives `сүз дөнья`; consecutive glides still give `сәләм дөнья`.
- No space is added after whitespace, a line break, the field start, an opening bracket or
  quote, a hyphen or a dash.
- One backspace right after the glide removes the glide word and the space it added. A tapped
  alternative replaces the word and keeps the space.
- A glide into a refocused field that ends in a word gives `сүз дөнья`, never `сүздөнья`.
- After sentence-ending punctuation, with auto-capitalization on, the glide word is
  capitalized as if the space had been typed (`сүз. Дөнья`).
- Phantom space (P2): a letter or digit typed right after a glide commit gets a space before it
  (`дөнья а`); punctuation hugs the word as today (`дөнья,`); a typed space gives one space.

**Design.**

1. Reproduce first, with failing cases in `…test/latin/suggestions/GlideEndToEndTest.kt`: glide
   after punctuation, after a typed word, and with the cache empty at the gesture but filled at
   the commit. For the refocus case, check two paths by reading and, if needed, on the emulator:
   the before-cursor cache is empty while the reload in `RichInputConnection.reloadTextCache()`
   is in flight, or it still holds another field's text because
   `RichInputConnection.reloadTextCache(EditorInfo, boolean)` returns early when the expected
   selection is already known and `restarting` is false.
2. A pure rule in `…/latin/suggestions/TatarWordUtils.kt`:
   `glideNeedsLeadingSpace(textBeforeCursor: CharSequence?): Boolean`, allocation-free, reading
   the last code point: true for a word character, a digit or a `swapsWithAutoSpace` mark, false
   otherwise (empty text included).
3. `…/latin/inputlogic/InputLogic.java`, `commitGlideWord`: the third parameter changes meaning
   from `chainedAfter` (the only trailing word allowed) to `expectedTrailingWord`, the trailing
   word captured at the gesture ("" when there is none). The commit is refused when the live
   `TatarWordUtils.extractTrailingWord(getCachedTextBeforeCursor())` differs from it. This check
   is what catches a letter typed between the lift and the decode result:
   `extractNextWordContext` returns "" for any text that does not end in spaces (`сүз`, `сүз,`, a
   chained `сәләм`), so the `expectedContextWord` check alone cannot. Add a fourth parameter,
   `expectedCursor`, the cursor captured at the gesture from a new `EditorSurface.cursorPosition()`
   (default -1, backed by `RichInputConnection.getExpectedSelectionStart()`); a different live
   value refuses the commit, which also catches an edit that keeps the trailing word (a mark typed
   after `сүз,`), and -1 skips the check. Then prepend `AUTO_SPACE` when `glideNeedsLeadingSpace`
   holds for the live text. The refusals for a selection, a letter after the cursor and a changed
   `expectedContextWord` stay. Apply the same signature to `EditorSurface.commitGlideWord`
   (`…/latin/suggestions/SuggestionSurfaces.kt`) and the `LatinIME` editor surface.
   `GLIDE_COMMIT_PREPENDED` keeps meaning "a space was added", so `maybeUndoGlideCommit`,
   `deleteGlideLiftedWord` and `replaceGlideLiftedWord` need no change.
4. `SuggestionsController.onGlideInput`: remove the refusal of a trailing word other than
   `glideCommittedWord`; keep `hasKnownCursor` and `hasLetterAfterCursor`. Store
   `editor.cachedWordBeforeCursor()` and `editor.cursorPosition()` in two new fields next to
   `pendingGlideContext` (`pendingGlideTrailingWord`, `pendingGlideCursor`), which
   `applyGlideResult` passes to `commitGlideWord` instead of `glideCommittedWord`.
   `glideCommittedWord` keeps its role for the undo and the alternatives.
5. Unknown text before the cursor: when the cursor is past the start of the text and the cache
   is empty without `cacheReachedTextStart()`, or the result of step 1 shows the cache belongs to
   another field, the commit is refused (no edit) and a cache reload is requested. This refusal
   lives in `InputLogic.commitGlideWord`, which reads `mConnection.cacheReachedTextStart()` and
   `mConnection.getExpectedSelectionStart()` on `RichInputConnection` directly; `EditorSurface`
   gets no new method for it. A refused gesture can be redone; glued text has to be fixed by
   hand. Apply the fix that the result of step 1 points to (for example, a reload on a changed
   field in `reloadTextCache(EditorInfo, boolean)`) if it is local.
6. Casing: the shift gate (`ShiftStateGate.glideCasing`, set in `LatinIME`) reads the keyboard
   state, and `CapsModeUtils.getCapsMode` needs whitespace before the cursor for sentence caps,
   so `сүз.` plus a glide stays lower case today. When the space is prepended, the gate is LOWER,
   `SettingsValues.mAutoCap` is on and `TatarWordUtils.isSentenceStartContext` holds for the text
   plus the space, the commit uses INITIAL_CAPS. Caps Lock and manual shift keep their meaning.
7. Phantom space (P2): a cursor field in `InputLogic`, armed by `commitGlideWord` and
   `replaceGlideLiftedWord` at the new cursor and cleared everywhere `mAutoSpaceCursor` is
   cleared (`startInput`, `onUpdateSelection`, `onKeyboardCursorMove`, `onTextInput`,
   backspace, separators, the other commit paths). `handleNonSeparatorEvent` commits the space
   and the code point in one batch edit when the cursor still matches and the code point is a
   letter or digit.
8. Update the docs of `onGlideInput`, `EditorSurface.commitGlideWord` and
   `InputLogic.commitGlideWord`, every comment and test message that says "chain space" (find
   them with `grep -rn "chain space\|chain's previous glide" app/src`; they sit in
   `LatinImeGlide`, the `GLIDE_COMMIT_*` docs in `SuggestionSurfaces.kt`, `SuggestionsController`,
   `InputLogic`, `GlideTouchIntegrationContractTest`, `GlideEndToEndTest` and the device test
   `app/src/androidTest/java/rkr/simplekeyboard/inputmethod/latin/glide/GlideUiDeviceTest.kt`),
   since the space can now follow punctuation or a typed word, and the glide paragraph of
   `docs/ARCHITECTURE.md`.

**Decisions on autocorrect and learning.** A glide does not autocorrect the typed word before
it: the space is added by the keyboard, not typed, and the one-backspace glide undo cannot also
undo a correction. An autocorrect preview on the strip is dropped at the gesture, as today
(`previewKeepTypedCell = null` in `onGlideInput`). The typed word before a glide is not learned:
`applyGlideResult` marks the run dirty before `onTextChanged` could report it, and that stays.

**Edge cases.** A digit before the cursor (`5` plus a glide gives `5 сүз`); a closing quote
(`»` is not in `swapsWithAutoSpace`, so no space: decide on the emulator and record it in the
test); an emoji before the cursor (not a word character, no space); text after the cursor that
starts with whitespace or punctuation (commit allowed, as today); a glide after an auto-space
of a tapped suggestion (the text ends in a space, no second space); the phantom space when the
host moves the cursor between the glide and the key (cleared by `onUpdateSelection`).

**Tests.** `…test/latin/suggestions/TatarWordUtilsTest.kt`: the rule for each character class.
`GlideEndToEndTest.kt`: the acceptance list, undo, alternative tap and refusal on an unknown
cache. Its `FakeEditor` gets the new `commitGlideWord` signature and models it: refuse when
`extractTrailingWord(text)` differs from `expectedTrailingWord` or the context differs, then
prepend a space when `glideNeedsLeadingSpace(text)` holds; `cursorPosition()` returns
`text.length`. A stale-gesture case: `RealBackedEngine.requestGlide` gains a mode that holds the
result until the test delivers it; the test lifts after `сүз,`, appends a letter to the editor
text, delivers the result and asserts that nothing was committed. The same case after a chained
glide (`сәләм`, then a letter). `…test/latin/glide/GlideTouchIntegrationContractTest.kt`: replace
the pins on `commitGlideWord(pendingGlideContext, committed, glideCommittedWord)`,
`trailingWord != glideCommittedWord`, the `EditorSurface.commitGlideWord` signature and
`!trailingWord.equals(chainedAfter)` with pins on the new arguments and the
`expectedTrailingWord` refusal. Phantom space: a source contract test next to
`…test/latin/inputlogic/AutoSpaceSwapSourceContractTest.kt` pinning which paths arm and which
clear the new cursor.

**Emulator check.** On `tt_suggest_a14`, draw gestures with `adb shell input motionevent`
(DOWN, MOVE along key centers, UP) after `сүз,`, after a typed `сүз`, and after leaving and
re-entering a field that ends in a word; then one backspace. Screenshots to `build/`.

**Risks.** Users who type a prefix and then glide to finish it now get two words instead of
nothing; this matches common keyboards. The refusal on an unknown cache can drop a gesture
right after focusing a field.

**Must not change.** Consecutive-glide output, the one-backspace undo, alternative replacement,
the refusal of a gesture whose text changed before the decode result, the auto-space and
punctuation swap after a tapped suggestion, the rule that a glide adds no trailing space
(without the phantom-space option), and glide eligibility.

**Effort.** M; phantom space S to M.

### A2. Double-space period only in general text fields (P1)

**Problem.** Two quick spaces give `. ` in any field except a password field:
`InputLogic.tryDoubleSpacePeriod` checks only `mInputAttributes.mIsPasswordField`. In phone,
number, email and URL fields a digit or letter followed by two spaces becomes `. `.

**Goal and acceptance.** The period is inserted only in general text fields: input class
`TYPE_CLASS_TEXT` whose variation is none of email, web email, URI, password, visible
password, web password, phonetic and filter. Phone, number, date/time and `TYPE_NULL` fields get
two spaces. Flags such as `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, `MULTI_LINE` or `CAP_SENTENCES` do not
change the answer.

**Design.**

- `…/latin/utils/InputTypeUtils.java`: `isGeneralTextInputType(int inputType)`, a pure int
  function beside `isAutoSpaceFriendlyType`, modeled on AOSP LatinIME's general-text attribute.
  The filter variation is excluded because a list filter is a query; confirm this choice.
  `isAutoSpaceFriendlyType` (and the `mShouldInsertSpacesAutomatically` it feeds) cannot be
  reused: its `SUPPRESSING_AUTO_SPACES_FIELD_VARIATION` list has email and the password
  variations but not URI, web email, phonetic or filter.
- `…/latin/InputAttributes.java`: a new final field `mIsGeneralTextInput`, assigned `false` in
  the non-text early-return branch next to `mShouldInsertSpacesAutomatically = false`, and
  `InputTypeUtils.isGeneralTextInputType(inputType)` on the text path next to the
  `isAutoSpaceFriendlyType` assignment.
- `InputLogic.tryDoubleSpacePeriod`: require `mIsGeneralTextInput` instead of
  `!mIsPasswordField` (password variations are not general text).

**Edge cases.** The space swallowed at an auto-space (`TatarWordUtils.swallowsSpaceAtAutoSpace`)
still arms `mLastSpaceDownTime`; the gate is in `tryDoubleSpacePeriod`, so the swallow path needs
no change. Backspace right after `. ` keeps restoring two spaces. Apps that report `TYPE_NULL`
lose the period.

**Tests.** `…test/latin/utils/InputTypeUtilsGeneralTextTest.kt`: every text variation, the
number, phone and date/time classes, `TYPE_NULL`, and the flags above. A source contract test in
`…test/latin/inputlogic/` asserting that `tryDoubleSpacePeriod` reads `mIsGeneralTextInput` and
that `InputAttributes` assigns it from `isGeneralTextInputType`. Keep
`…test/latin/EditorInfoPrivacyMatrixTest.kt` green; it pins the `mIsPasswordField` expression,
which does not change.

**Emulator check.** Double space in the setup screen's try-it field (period), in a phone field
of the Contacts app, an email field and the browser address bar (two spaces).

**Risks.** Small; a field that declares an email or URI variation for prose loses the period.

**Must not change.** Double-space period timing, its backspace revert, the password gates, and
the unused `mShouldInsertSpacesAutomatically` field (no cleanup in this item).

**Effort.** S.

### A3. Digits on long press of the top letter row (P1)

**Problem.** Without the number row, a digit needs a switch to the symbols page. On the English
layout the top row has digit hints on long press (`rowkeys_qwerty1.xml`); the Tatar and Russian
layouts do not.

**Goal and acceptance.** With the number row off, a long press on the first ten keys of the
`й ц у к е н г ш щ з х` row offers `1`–`0` in order (`х` gets none). Keys that already have a
letter partner (`у`→`ү`, `е`→`ё`, `н`→`ң`, `г`→`һ`) keep that letter as the first more key and as
the hint label, so long press and release still types the letter. With the number row on, the
row is exactly as today. Taps type the same letters.

**Design.**

- `app/src/main/res/xml/rowkeys_tatar1.xml` and `rowkeys_russian1.xml` stay byte-identical
  (`…test/keyboard/RowkeysSyncTest.kt`). Wrap the keys in `<switch>`: `<case
  latin:showNumberRow="true">` holds today's keys, `<default>` the keys with digits, the pattern
  of `rowkeys_qwerty1.xml`.
- A key without a letter partner: `latin:keyHintLabel="1"` and `latin:additionalMoreKeys="1"`.
- A key with a letter partner: keep `keyHintLabel`, write `latin:moreKeys="<letter>,%"` plus
  `latin:additionalMoreKeys="<digit>"`. Without a `%` marker,
  `MoreKeySpec.insertAdditionalMoreKeys` puts additional more keys at the head, which would make
  the digit the default.
- `KeyNeighborTable.build` and `GlideKeyGeometry.build` drop non-letters, so typo recovery and
  glide geometry do not see the digits.
- `scripts/typo_pack.py`: `_read_row_key_specs` and `_read_directed_pairs` walk every `<Key>`
  with `root.iter`, so a `<switch>` would double the row. Make both read only the `<default>`
  branch, which is the keyboard the geometry model describes. The third walker,
  `read_layout_alphabet`, can stay as it is: it collects into a set and drops non-letters through
  `_normalize_letter`, so a second branch and the digits add nothing. The typo and glide sets and
  their pins must come out unchanged; that is the proof the geometry did not move.

**Edge cases.** Shifted keyboard (digits stay digits); the Russian layout gets the same digits;
TalkBack reads the popup (C6); the popup of a key with two more keys must fit near the screen
edge (`й`, `з`).

**Tests.** `…test/keyboard/TopRowDigitMoreKeysTest.kt` parsing `rowkeys_tatar1.xml`: the default
branch carries `1`–`0` on the first ten keys, letter partners keep the first position and the hint
label, the number-row branch has no digits, and both branches have the same key specs in the
same order. `tests/typo_pack/test_typo_pack.py`: a row file with a `<switch>` reads as its
default branch.

**Emulator check.** Long press on `й`, `у`, `е`, `з` with the number row off and on, in Tatar
and Russian, light and dark theme; screenshots of each popup to `build/`. `probe_layout` in
`scripts/emulator-smoke.sh` taps the top-left key and must still pass.

**Risks.** A `<switch>` parsed differently by `KeyboardBuilder` than by the Python readers; the
unchanged typo and glide pins catch that on the Python side, the emulator on the device side.

**Must not change.** Tap output, the extra Tatar row, the long-press letters, the number-row
layout, `KeyNeighborTable` content, the typo and glide set pins.

**Effort.** S to M.

### A4. Glide aliases for `ъ` and `ё` (P2)

**Problem.** `ъ` and `ё` sit only on long press (`ь`, `е`), so `GlideWordIndex.build` finds no key
for them and skips every word that contains them (`skippedWordCount`). Such words cannot be
glide typed in Tatar or Russian.

**Goal and acceptance.** A glide over the `ь` and `е` keys also decodes words with `ъ` and `ё`,
committed with the right letter. Words without those letters keep their recovery within the
calibration tolerances.

**Design.**

- `…/latin/glide/GlideKeyGeometry.kt`: `RawKey` gains `moreKeyCodePoints: IntArray` (default
  empty). `build` makes every normalized more-key letter that has no key of its own an alias of
  its base key, stored as sorted parallel `aliasLetters`/`aliasKeys`. `keyIndexOfLetter` falls
  back to them; `sameLayoutAs` compares them. The rule reads the layout; on the Tatar layout it
  gives `ъ` (on `ь`) and `ё` (on `е`), each on one key, because the fifth row gives the Tatar
  letters keys of their own. On the Russian layout the Tatar long-press letters become aliases
  too, and two of them sit on two keys (`rowkeys_russian1.xml`, `rowkeys_russian2.xml`): `һ` on
  `г` and `х`, `ә` on `а` and `э`. Their base is not taken from the key order: a letter on more
  than one key is resolved by the explicit table `ALIAS_BASES` in the `GlideKeyGeometry`
  companion, which names `х` for `һ` and `а` for `ә`, the pairs `README.md` and the Russian
  layout row of `docs/DEVICE-TEST-PLAN.md` advertise. A letter on several keys without an
  entry, or whose entry names none of its keys, gets no alias. The Russian dictionary has no
  words with Tatar letters, so only personal words are affected.
- `…/latin/suggestions/GlideKeyGeometryBuilder.kt`: fill `moreKeyCodePoints` from `key.moreKeys`
  (`MoreKeySpec.mCode`), as `KeyNeighborTableBuilder` does.
- `GlideWordIndex`, `GlideIdealPaths` and `GlideDecoder` need no change: an alias maps to a
  real key index.
- `scripts/glide_pack.py`: `_letters_mappable` and `_ideal_vertices` resolve aliases read from
  the layout's long-press pairs (reuse `typo_pack._read_directed_pairs`). That reader keeps every
  single-character token, so after A3 it returns `%` as a partner of `moreKeys="<letter>,%"`;
  the alias resolution passes base and partners through `typo_pack._normalize_letter` and skips
  None, as `build_neighbor_map` does. `glide_pack.py` reads only the Tatar layout, where no alias
  letter sits on two keys; the alias resolution raises `GlidePackError` on such a letter, so a
  layout change cannot pick a base silently. Re-pin the set in
  `tests/glide_pack/test_glide_pack.py` and in `…test/latin/glide/GlideRecoveryCalibrationTest.kt`
  (`SET_SIZE`, `SET_BYTES`, `SET_SHA256`), whose mirror (`lettersMappable`, `selectWords`,
  gesture generation) follows the same rule. `GlideTestFixtures.tatarRawKeys()` and
  `russianRawKeys()` gain the more keys.
- In `gatesG1AndG2OnTheRealDictionary`, add a class for words with an alias letter, so the four
  existing classes keep their word membership and tolerances. The per-word seeded streams keep
  the existing rows byte-identical; results can still move where a new word competes.

**Edge cases.** A word and its alias twin share one key sequence (`все` and `всё`) and compete
by frequency only; the other appears in the strip, and with suggestions off only the more
frequent one can be glide typed. `е` followed by `ё` is the same key twice and follows the
doubled-letter rule (A5). Personal words with `ъ` or `ё` become glide candidates through
`CompositeGlideInventory`. Casing of `Ё` goes through `TatarWordUtils.applyCasing`. The A3 digits
are not letters and never become aliases. A personal word with `һ` on the Russian layout decodes
from a gesture over `х`, not over `г`.

**Tests.** `GlideKeyGeometryTest`: aliases resolve to the base key, a letter with its own key is
never aliased, `sameLayoutAs` differs when only aliases differ; on the Russian raw keys `һ`
resolves to `х` and `ә` to `а` whatever the key order, and a letter on two keys without an
`ALIAS_BASES` entry gets no alias. `GlideWordIndexTest`: a word with `ъ` is indexed.
`GlideEndToEndTest`: a Tatar word with `ъ` and a Russian word with `ё` from the bundled
dictionaries decode from a gesture over their base keys. `tests/glide_pack/test_glide_pack.py`:
a row file with a key `е` carrying `moreKeys="ё,%"` and a digit in `additionalMoreKeys` yields the
alias `ё` and none for `%` or the digit, and a row file where a letter without a key of its own
is the long press of two keys raises `GlidePackError`.

**Measurement.** The calibration run (G1, G2, G3 and the per-class tolerances hold; the new
class is reported). On the 720x1640 device, `GlideUiDeviceTest` and
`GlideDeviceInstrumentationTest`. A4 ships on its JVM tests and the calibration run; the device
part is not required to ship and runs with the release candidate's device stages (order step 8).

**Risks.** Larger index (more words indexed); the `все`/`всё` tie; the golden vectors of
`…test/latin/golden/GlideGoldenExportTest.kt` change (geometry tables, word-index digests) and
must be re-exported for the parity suite on the other platform.

**Must not change.** The typo set, `TOP_N`, zero allocations in the decode, the doubled-letter
rule for words with a twin.

**Effort.** M.

### A5. Doubled letters without a single-letter twin (P2)

**Problem.** A word with a doubled letter is scored only against its looped ideal path
(`GlideDecoder.scoreCandidate`, `loopedOnly`), so the user has to draw a loop or dwell. That is
right when the undoubled twin exists (the plain path belongs to the twin), but a word whose twin
is not in the dictionary gets no credit for the natural, loop-free gesture.

**Goal and acceptance.** A doubled word whose collapsed key sequence is no other indexed word
is scored against both its plain and its looped path and keeps the better confidence. In the
calibration, `doubled_nojog_twinless` improves; `plain`, `doubled_jog` and `doubled_nojog_twin`
stay within the tolerances asserted in `gatesG1AndG2OnTheRealDictionary`; G1, G2 and G3 hold.

**Design.**

- `…/latin/glide/GlideWordIndex.kt`, `build`: after the key sequences are known, hash every
  non-doubled entry's key sequence into a primitive open-addressing table, then probe each
  doubled entry's collapsed sequence and confirm hits by comparison. Store the result as a
  `LongArray` bit set with an accessor (`isTwinlessAt(entry)`) and count it in
  `retainedByteEstimate`. Key sequences rather than strings, so alias twins (A4) and personal
  words in `CompositeGlideInventory` count.
- `…/latin/glide/GlideDecoder.kt`, `scoreCandidate`: twinless doubled entries call
  `scoreVariant` for both variants; the others keep `loopedOnly`. The length pruner already
  keeps a candidate that matches either length.
- Add an assertion floor for the twinless class in the calibration test, set from the first
  passing run.

**Edge cases.** Several doubled runs in one word (collapse all); a twin present only in the
personal dictionary (the composite inventory rebuild recomputes the bits); a doubled letter
through an alias (`её`).

**Tests.** `GlideWordIndexTest`: the twin bit for synthetic words with and without a twin.
`GlideDecoderTest`: a twinless doubled word decodes from a loop-free path; a word with a twin
still loses that path to its twin.

**Measurement.** Calibration run; host decode p95 (G2) and the printout of
`indexBuildStatsAreMeasuredAndDocumented` against the previous run; device latency with
`GlideDeviceInstrumentationTest`. Twinless doubled words are scored twice, so check that the
scored-candidate count in the printout does not grow beyond that class. A5 ships on its JVM
tests, the calibration run and the host G2; the device latency is not required to ship and runs
with the release candidate's device stages (order step 8).

**Risks.** Index build time on each personal-dictionary rebuild; a twinless doubled word
winning a plain path that belongs to an unrelated word.

**Must not change.** Results for words without doubled letters beyond noise the tolerances
allow, the twin rule, zero allocations after warmup.

**Effort.** M.

### A6. Glide context rerank by bigram successors (P3)

**Problem.** Glide decoding ignores the previous word. Two shapes that score alike are ranked by
frequency alone, even when the bigram table says which one follows the previous word.

**Goal and acceptance.** When the gesture has a rerank context (defined below) and the bundled
bigram table lists successors for it, candidates among the decoder's top results that are
successors move up. Decision rule, fixed before the first measurement: before the first run, the
operator writes the minimum held-out top-1 gain on context rows into a test constant; the item
ships only if that gain is reached, rows without context are decoded identically, overall top-3
does not drop and G2 holds. Otherwise the result goes to `docs/BACKLOG.md` and the code is not
merged.

**Design.**

- Rerank context: `pendingGlideContext` (the `cachedNextWordContext()` of the gesture) is ""
  after a chained glide, a typed word or punctuation without a trailing space, which are the
  cases where A1 prepends a space. So the rerank uses its own context: when
  `glideNeedsLeadingSpace` holds, the NEXT_WORD context of the text plus that space, which is
  the trailing word (`сәләм` gives `сәләм`) or the word before non-final punctuation (`сүз,` gives
  `сүз`; `сүз.` gives ""); otherwise `cachedNextWordContext()`. A pure
  `TatarWordUtils.glideRerankContext(textBeforeCursor, cacheReachedTextStart)` computes it with
  the rules of `extractNextWordContext`, exposed as a new `EditorSurface.cachedGlideRerankContext()`
  (default "", implemented in the `LatinIME` editor surface like `cachedNextWordContext`).
  `onGlideInput` stores it in a new field, `pendingGlideRerankContext`; `pendingGlideContext`
  stays the staleness key for the commit and the companion check.
- Payload: the normalized UTF-8 of `pendingGlideRerankContext` (`TatarWordUtils.normalizeForLookup`,
  `toLookupBytes`) travels as a new `contextWordUtf8: ByteArray` parameter, the type
  `requestNextWord` takes, along the whole request chain: `EngineHandle.requestGlide` (the
  interface default in `…/latin/suggestions/EngineHandle.kt`), `MappedEngineHandle.requestGlide`
  (same file), `MappedDictionaryEngine.requestGlide`
  (`…/latin/dictionary/engine/MappedDictionaryEngine.kt`) and `LatestOnlyPrefixEngine.requestGlide`.
  The last one replaces a context that is not valid UTF-8 (`isValidUtf8Scalar`) or longer than
  `TatBigrPrefixIndex.MAX_WORD_BYTES` with the empty context and passes the result to
  `requestInternal`, which copies it into the token's `normalizedQuery`
  (`ImmutableUtf8Prefix.copyOf`) as for every kind. `requestInternal` keeps skipping, for GLIDE,
  the check that refuses the request and invalidates the generation for the other kinds, so a bad
  context never refuses or invalidates a gesture: it decodes without rerank. The `requestGlide`
  doc and the comment in `requestInternal`, which call the GLIDE query an empty sentinel, are
  updated, and the doc of `LookupToken.normalizedQuery` gains the GLIDE case.
- `…/latin/glide/GlideComputer.kt`: the `fun interface` becomes
  `decodeGlide(path: GlidePath, context: ImmutableUtf8Prefix)`, the type
  `NextWordComputer.predict` takes, so the worker hands over the token's query without a copy.
  The worker branch `LookupKind.GLIDE` in `LatestOnlyPrefixEngine.drain` passes
  `request.token.normalizedQuery`. Both implementations change:
  `GlideDecoderHost.decodeGlide(path, context)` ignores the context (the host knows no bigram
  table), and the host gains `decodeGlideScored(path): GlideResult?`, which returns its
  worker-confined `result` (words and `GlideResult.scores`) and which `decodeGlide` builds its list
  from.
- `…/latin/dictionary/engine/CompositePrefixComputer.kt`, `decodeGlide(path, context)`: decode
  through `GlideDecoderHost.decodeGlideScored`. With an empty context, return the decoded words
  unchanged. Otherwise ask `bigramSource?.predict(context)` directly, which gives the bundled
  successors only (not the learned word pairs, word forms and fallback that
  `CompositePrefixComputer.predict` adds). Decoder scores are confidences where lower is better
  (`GlideResult`), so the score of each successor within the decoded `TOP_N` is multiplied by one
  tuned factor in (0, 1), `GLIDE_SUCCESSOR_FACTOR` in the `CompositePrefixComputer` companion;
  the candidates are then sorted by score ascending with a stable sort, so ties keep decoder
  order. Scores and order are copied into worker-confined scratch arrays of `TOP_N`, so the
  host's `result` is not modified. The reordering itself is an internal pure function in the
  same file (`rerankGlideBySuccessors`: words, scores, count, successors, factor), so it can be
  tested with fixed scores. The rerank lives here because this class owns both the glide host
  and the bigram source; the glide package imports `ImmutableUtf8Prefix` but never reads a bigram
  table.
- First version uses bundled bigram tables only; learned word pairs are a follow-up.
- Calibration: context rows from `app/src/test/resources/tt_eval_sentences.txt` (previous token,
  word), limited to words in the set; the train/held-out split as today; the factor is tuned on
  the train split. Each row derives its context through `glideRerankContext` from the previous
  token as a chained glide leaves it (no trailing space), and a share of rows from the token
  followed by `, `, so the measured path is the one consecutive glides take.

**Edge cases.** Sentence start or empty context (no rerank, same result as today); a context
that is not valid UTF-8 or longer than `TatBigrPrefixIndex.MAX_WORD_BYTES` (replaced by the
empty context, so the same); a context word without a bigram head; a successor outside the
decoded top results (not added, only reordered); a chained glide, where the rerank context is
the previous glide's word while the staleness key is ""; the Russian engine has no calibration
set, so it runs the same code but is checked only by `GlideEndToEndTest` cases.

**Tests.** `TatarWordUtilsTest`: `glideRerankContext` for `сәләм`, `сүз,`, `сүз.`, `сүз `, the
field start and a cache that does not reach the text start. `GlideEndToEndTest`: a context pair
from the Tatar bigram table reorders a gesture after `<word> ` and after a chained glide of
`<word>`; the same gesture at a field start does not. `GlideDecoderHostGeometryReuseTest`:
`decodeGlideScored` gives the same words, in the same order, as `decodeGlide`.
`…test/latin/dictionary/engine/MappedDictionaryEngineGlideTest.kt`, next to
`aDegeneratePathIsRejectedWithoutInvalidatingAPendingPrefix`: a context of
`TatBigrPrefixIndex.MAX_WORD_BYTES + 1` bytes and a context with a malformed UTF-8 sequence each
give a non-null, current token with an empty query, and the delivered words equal those of the
same gesture with the empty context. A unit test of `rerankGlideBySuccessors` in
`…test/latin/dictionary/engine/CompositePrefixComputerTest.kt` with fixed decoder scores and a
fixed successor list: a successor below the top moves up, the non-successors keep their relative
order, two candidates with equal scores after the factor keep decoder order, and no successor
leaves the order unchanged; `decodeGlide` with an empty context returns the host's words
unchanged and does not call the bigram source. `GlideRecoveryCalibrationTest`: the context rows
and the decision rule.

**Measurement.** Calibration (train for tuning, held-out for the decision); G2 with the rerank
included; `GlideDeviceInstrumentationTest` on the device, which, as for A4 and A5, is not
required to ship.

**Risks.** Over-promoting frequent successors; the golden vectors change. The wider
`requestGlide` signature touches `GlideIndexResidencySourceContractTest`,
`GlideTouchIntegrationContractTest`, `MappedDictionaryEngineGlideTest` and the `requestGlide`
override of the `RealBackedEngine` fake in `GlideEndToEndTest`. The wider `decodeGlide` signature
touches the direct host call in `GlideDecoderHostGeometryReuseTest` (it passes the empty context,
`ImmutableUtf8Prefix.copyOf(ByteArray(0))`, and keeps its assertions) and the same fake, which
decodes through its own `GlideDecoderHost` today. For the rerank cases, the test builds the
`CompositePrefixComputer`s it hands to the fake with that host (constructor parameter
`glideHost`), and the fake decodes through
`composite.decodeGlide(path, ImmutableUtf8Prefix.copyOf(contextWordUtf8))`, so the end-to-end
cases run the production rerank.

**Must not change.** Decode output without context, next-word prediction, the token staleness
rules.

**Effort.** L.

## Track B: release follow-ups

### B1. Startup profile and dex layout (P1)

**Problem.** The dex layout depends on an untracked directory (see "Known risks and open items"
in `HANDOFF.md`): a startup profile in the git-ignored `app/src/release/generated/baselineProfiles/`
(ignored by the `release/` rule in `.gitignore`) gives a startup `classes.dex` plus
`classes2.dex`; the tracked tree gives a single `classes.dex`, although
`app/src/main/startup-prof.txt` is tracked, so AGP does not use that file for the layout. CI,
F-Droid and `scripts/release_pack.sh` build the tracked tree.

**Decision.** Track the profiles where the Baseline Profile Gradle plugin writes them and AGP
reads them for every variant. The consumer setting `mergeIntoMain = true` makes the generator
write `app/src/main/generated/baselineProfiles/` (outside every ignore rule) with one task,
`:app:generateBaselineProfile`, instead of the per-variant `:app:generateReleaseBaselineProfile`.
`automaticGenerationDuringBuild` stays at its default (off), because CI and F-Droid have no
device.

**Steps.**

1. `mkdir -p app/src/main/generated/baselineProfiles`, then
   `git mv app/src/main/baseline-prof.txt app/src/main/startup-prof.txt
   app/src/main/generated/baselineProfiles/` so there is one copy of each profile.
2. Add a new consumer block `baselineProfile { mergeIntoMain = true }` to `app/build.gradle`,
   after `apply plugin: 'androidx.baselineprofile'`. That file has no such block today, only the
   plugin and the `baselineProfile project(':baselineprofile')` dependency; the one existing
   block, in `baselineprofile/build.gradle`, is the producer side (`useConnectedDevices`) and
   stays as it is.
3. Build the unsigned release (`bash scripts/release_pack.sh --no-sign`) and check with
   `unzip -l` that the APK has `classes.dex` and `classes2.dex` and still has
   `assets/dexopt/baseline.prof`. If AGP does not pick the profile up, stop and record what it
   reads before trying another location.
4. Two `./gradlew clean assembleRelease --no-build-cache` runs give byte-identical APKs (CI job
   `reproducible`).
5. Update the text that names the old flow: the "Baseline profile" row of `AGENTS.md` (new task
   name, no copy step); in `baselineprofile/build.gradle`, the header comment (output directory,
   copy step) and the run command in the `useConnectedDevices` comment
   (`ANDROID_SERIAL=<emulator-serial> ./gradlew :app:generateReleaseBaselineProfile`); the comments
   in `settings.gradle` and `app/build.gradle` that name `app/src/main/baseline-prof.txt`; and the
   step 3 checkbox of `docs/PUBLISH-CHECKLIST.md` (keep the check that `app/src/release/generated/`
   is absent, because a profile there would still be merged in).
6. Optional: an `artifact.dex_layout` check in `scripts/release_check.sh` that fails when the
   release APK has a single dex, so a silent fallback is caught.
7. At the code freeze, regenerate both profiles on the emulator; they then cover the methods
   Track A added, and the `HANDOFF.md` note that the profiles were edited by hand goes away.
8. Measure cold start on the release build, the 3.7.0 APK against the candidate on the same
   device in one session (`docs/DEVICE-TEST-PLAN.md`, stage 6, with
   `bash scripts/device-perf-ritual.sh --pkg org.tatarkeyboard.ime`). Keep the split layout
   unless it measures slower; the numbers go into the release record, not into a document.
   Without a 720x1640 device before the release, ship the split layout (steps 3–4
   verify the build), record cold start as unmeasured in the release record and in
   `HANDOFF.md`, and do the measurement with C9.

**Risks.** APK size changes with a second dex (the size gate applies); the plugin changes the
generator task names. **Must not change.** The reproducible build and `dependenciesInfo`, the
absence of a profileinstaller dependency. **Effort.** M (steps 1–6 S, measurement needs a
device).

### B2. Store changelog wording and the incognito summary (P1)

- `metadata/tt/changelogs/44.txt`, third bullet: the English sliding fix is described with
  `суйрып язу`, the Tatar term for glide typing (`glide_typing` in `values-tt/strings.xml`), so it
  reads as glide typing in English, which does not exist. en and ru say "sliding"
  (`скольжение`). Reword that clause to describe sliding a finger across the keys without the
  glide term, checked by a Tatar speaker. F-Droid reads the copy at tag `v3.7.0`; the fix on
  `main` matters for the manual store upload if it is still pending, and as the model for the
  next `45.txt`.
- `incognito_mode_summary` in `app/src/main/res/values/strings.xml`, `values-ru` and `values-tt`
  names only new words and word pairs. Pause learning also stops learned emoji (every personal
  store write goes through `PersonalLearningGates.mayLearn`) and the recording of recent emoji
  (`RecentEmojiGateState`). Rewrite the summary in the three locales and the XML comments above
  `incognito_mode` and `incognito_mode_summary`. `ThreeLanguageStringsTest` keeps the keys in
  step.

Effort: S.

### B3. `gh` CLI on the release machine (P1)

`HANDOFF.md`, "Open release steps", says the `gh` CLI on the release machine is read-only; it
is not installed there (`command -v gh` finds nothing). Change the wording to "not installed".
Effort: S.

### B4. CHANGELOG startup line (P1)

`CHANGELOG.md` `[3.7.0]` says "each bundled dictionary is checked once instead of twice". The
change was not in the validators: `AtomicDictionaryStore` and `AtomicBigramStore` both reuse the
publication check for the first activation of a file (commit `dadea49f`), so it covers the
bigram tables as well. Change the line to "each bundled dictionary and bigram table", before the
notes are pasted into the GitHub Release. Effort: S.

## Track C: device work

Procedures are in `docs/DEVICE-TEST-PLAN.md`; evidence goes to `build/device-test-<date>/`.
Items marked "BACKLOG" keep their description in `docs/BACKLOG.md` ("Device tests blocked by
external conditions" and "Measurements"); this list adds the pass criteria for this release.

- **C1. Lock-screen quick reply** (BACKLOG). Pass: no suggestion strip, a slide types letters
  instead of a glide, no Recent tab in the emoji panel, "Saved words" unchanged after unlocking,
  empty `adb logcat -b crash -d`.
- **C2. Direct Boot, live** (BACKLOG; stage 6, "Needs a person"). Pass: the keyboard shows and
  types, no strip, glide or Recent tab, no crash; normal behavior after the unlock.
- **C3. Haptics without per-press allocation** (owned here, P2). `AudioAndHapticFeedbackManager`
  allocates a lambda per press (and per sound) plus a `VibrationEffect` per vibration, hands
  them to an `ExecutorService` whose `execute` also allocates a queue node, and, below API 29,
  calls `View.performHapticFeedback` on that background thread instead of the UI thread. Code:
  replace the executor with a `HandlerThread` and one `Handler`, created by the first
  `initInternal` and reused by later calls (`LatinIME` and `SettingsHostActivity` both call
  `init`, and today each call starts a new executor). `Handler.post` takes its `Message` from the
  platform pool, so a press allocates nothing in the keyboard's code. Create the click and tick
  `VibrationEffect` once, in `initInternal` under the API 29 check, and post two prebuilt
  `Runnable` fields that vibrate with them. Sound: one
  prebuilt `Runnable` per `AudioManager.FX_KEYPRESS_*` constant (`STANDARD`, `DELETE`, `RETURN`,
  `SPACEBAR`); each reads the key-press volume from a volatile field that `onSettingsChanged`
  sets from `mKeypressSoundVolume`, so `performAudioFeedback` posts the Runnable for its code and
  no longer goes through `playSoundEffect`. `playSoundEffect(effectType, volume)` stays for the
  volume preview in `SettingsKeyPressScreen`, which passes a different volume per call and is not
  a key press; it keeps posting a lambda. Below API 29, call
  `performHapticFeedback(KEYBOARD_TAP)` directly on the calling UI thread. JVM: a source contract
  test in `…test/latin/` that `performHapticFeedback`, `performTickFeedback` and
  `performAudioFeedback` contain no lambda, no `new Runnable`, no `VibrationEffect.create` and no
  `execute(`, that they post the prebuilt fields, and that the pre-29 branch is outside any
  `post`. Update the pins this breaks: the `KEYBOARD_TAP` call pinned with its current
  indentation in `…test/keyboard/AppleUxBatchOneContractTest.kt` (its other pin, the
  `!mSettingsValues.mVibrateOn || mVibrator == null` guard, stays), and the class doc of
  `…test/latin/RichInputMethodManagerExecutorSourceContractTest.kt`, which names this class as
  the other user of a single-thread executor. Device pass: the same click feel as 3.7.0 on an
  API 29+ phone, no missed or doubled pulse in fast typing, sound unchanged; on API 24–28 (an
  emulator is enough for this part) no crash and the key-press feedback still fires. The device
  part is required: C3 does not ship without it.
- **C4. Visual check** (BACKLOG, the 3.1 visual refresh). Stage 5 "Appearance" rows in both
  themes, plus the A3 popups on the real screen. Pass: nothing clipped, hints readable.
- **C5. Telegram** (BACKLOG). Typing, suggestions, autocorrect undo, glide with the A1 spacing,
  emoji panel. Pass: the stage 5 expectations hold in Telegram's editor, no crash.
- **C6. TalkBack by ear** (BACKLOG). Pass: keys, Tatar letter descriptions, the language
  announcement and the A3 digit popups are spoken.
- **C7. Tablet layout** (BACKLOG). Pass: stage 5 "Layout" and "Appearance" rows, Enter key
  present, emoji panel and strip sized to the screen.
- **C8. Full gesture navigation** (BACKLOG). Switch the mode by hand. Pass: the keyboard and the
  emoji panel stay above the gesture bar; the back gesture closes the panel.
- **C9. Release-build frame time and PSS** (BACKLOG, "Measurements"). Stage 6 "Release build
  performance" with suggestions enabled by hand. Pass: three repeatable runs recorded in the
  release record; if they are repeatable, propose a release-scale PSS ceiling in
  `docs/PERF-BUDGETS.md`. Shares the session with the B1 cold-start measurement.

## Out of scope

The parked and rejected items in `docs/BACKLOG.md` ("Parked decisions") stay out of scope, and
so do its other open sections, except the device tests and the release-build measurement that
Track C lists.

## Definition of done

- Every P1 item is merged; each P2 item is merged or back in `docs/BACKLOG.md` with one line; A6
  is merged or its result is recorded in `docs/BACKLOG.md`, following its decision rule.
- The mandatory gates of `AGENTS.md` pass on the final tree, `docs/PUBLISH-CHECKLIST.md` is
  complete, and the emulator smoke test passes on the signed APK.
- Device stages 1–5 of `docs/DEVICE-TEST-PLAN.md` passed on the reference device, or the release
  record lists them as not run (order step 8); C3 is merged only with its device pass.
- `app/build.gradle` carries the new `versionCode` and `versionName`; `CHANGELOG.md` has a section
  with one user-facing entry per shipped item; `metadata/{en-US,ru-RU,tt}/changelogs/<N>.txt` say
  the same thing in the three locales, within the checklist limits, and keep glide typing and
  sliding apart.
- The bundled data is unchanged, and the release record says nothing is re-inflated.
- The baseline and startup profiles are regenerated and tracked (B1); the APK has the
  startup-optimized dex layout; cold start is measured, or recorded as unmeasured when no
  720x1640 device was available (B1 step 8).
- Glide golden vectors are re-exported if the parity suite is synced; otherwise `HANDOFF.md`
  names the exporters whose output changed.
- `docs/ARCHITECTURE.md` describes the glide spacing, aliases, the twinless rule and the rerank
  that shipped, and the double-space field rule; `AGENTS.md` and `docs/PUBLISH-CHECKLIST.md`
  describe the B1 flow.
- `HANDOFF.md` is rewritten for the new release.
- This file is deleted and listed in `docs/HISTORY.md` ("Phase reports and plans"), and its
  lines in `docs/README.md`, `docs/BACKLOG.md` and `HANDOFF.md` are removed.
