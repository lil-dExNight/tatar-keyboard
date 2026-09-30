# Brief for comment-cleanup agents (phases 3 and 5)

Repository: `/Users/amirka/Projects/tatar-keyboard/android` (Android IME, Java + Kotlin, Python
asset pipeline). Read first: `cleaning/CLEANUP-PLAN.md` sections 3 (writing rules), 5 (hazards
H1, H2, H8) and Appendix D (terminology), plus the area report named in your task.

## What you do

Rewrite **comments only** — `//`, `/* */`, javadoc/KDoc, Python docstrings and `#` comments,
XML `<!-- -->` comments, shell `#` comments — in the files of your area, so that they follow the
writing rules:

1. A comment says what the code does and why. Remove history: dates (`2026-09-25`), release
   numbers, audit/finding numbers, mission/phase/item codes (`F3`, `S8`, `P7-2`, `E5c`, `O7`,
   `T2`, `SIZE-3`, `D1b`, `W1`, `Р-1`, `Phase B`, `ROADMAP-P4`, `TT-TYPO-NEXT`…), "operator",
   "agent", "handoff", "uncommitted", "mission", "audit", "field report", device-run logs,
   measured timings, test counts, SHA values and byte counts in prose.
2. No references to `docs/*.md` or `PROPOSALS.md` (those documents are being deleted). If the
   referenced rule matters, state it in one sentence.
3. Class docs ≤ 8 lines, member docs ≤ 3 lines, unless the text documents a binary format, an
   algorithm, a threading/privacy invariant or a platform quirk that cannot be read from the code.
   Keep such genuinely useful explanations, just shorter.
4. One explanation, one place: replace copies with `See [Canonical]` / `See {@link X}`.
5. Plain wording. No rhetoric ("earned its keep", "by construction rather than by review",
   "the worst possible kind of silent"). Use "fail-closed" only where it literally means
   "on error, do nothing / return empty".
6. **Canonical English** (US spelling, consistent terms from Appendix D, no calques). Translate
   every Russian comment. Tatar/Russian words may stay only as quoted language data
   (`сәләм`, `ә`) or in string literals.
7. Upstream AOSP / Simple Keyboard text (license headers, classic javadoc, upstream TODOs) stays as
   is. Upstream text is plain AOSP English with no dates/codes; project text has dates, mission
   codes, Tatar references. Use `git log -L` / `git blame` if unsure.
8. If a comment is just history with no current meaning, delete it.

## What you must NOT do

- Do not change code, identifiers, string literals, resource values (`<string>` contents),
  assets, or test assertions. Comments only. (Exception: see "Tests that pin comment text".)
- Do not introduce the H2 tokens into comments: `measureText(` and `HashSet` in
  `KeyboardView.java`; `Log.`, `println`, `System.out`, `java.net.` in `latin/emoji/*.kt`;
  `RecentEmojiStore`, `RecentEmojiList`, `noBackupFilesDir`, `deserialize`, `currentRecents` in
  `SettingsHostActivity.kt`; `data class`, `createDeviceProtectedStorageContext`,
  `android.util.Log`, `DeviceProtectedDirectoryProvider` in `personalstore/`; no Cyrillic in
  comments of `latin/glide/` and `latin/dictionary/engine/`; no `mIC.` inside comments of
  `RichInputConnection.java`; no `beginBatchEdit`/`endBatchEdit`/`mConnection.` inside comments of
  `InputLogic.java`.
- Keep these exact comment strings (tests pin them): `Do not log the returned value` in
  `RichInputConnection.java`; the AOSP sentence fragments `asynchronous binder. Also, batch edits`
  and `are ignored for key events` in `InputLogic.java`.
- Do not run Gradle, do not build, do not commit, do not push. The orchestrator runs the gates.
- Do not edit files outside your area.

## Tests that pin comment text

Some source-contract tests read main sources as text. Most pin code. If you find (grep
`app/src/test` for a phrase you are about to delete) that a test uses a comment line as an anchor
or asserts its presence, keep that exact phrase, and mention it in your final answer.

## When done

Run these checks over your area and report the numbers (target: 0 for each, except language data):

```
AREA="<your paths>"
grep -rnE 'docs/[A-Za-z0-9/_.-]+\.md|PROPOSALS' $AREA | wc -l
grep -rnE '20[0-9]{2}-[01][0-9]-[0-3][0-9]' $AREA | wc -l      # review: data pins in code are allowed
grep -rnE '^\s*(//|\*|/\*|#|<!--).*[А-Яа-яЁёӘәӨөҮүҖҗҢңҺһ]' $AREA | wc -l   # review: quoted Tatar examples are allowed
grep -rniE '\b(operator|uncommitted|handoff|mission)\b' $AREA | grep -v 'Handoff\b\|handoffCount\|ResultHandoff\|TimedHandoff' | wc -l
```

Then reply with: files changed, the numbers above before/after, any comment strings you kept
because a test pins them, and anything you were unsure about. Keep the reply under 300 words.
