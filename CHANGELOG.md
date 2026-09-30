# Changelog

User-facing changes to Tatar Keyboard, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

These hold for every release and are not repeated in the entries below:

- The keyboard works fully offline. The app has no `INTERNET` permission; the only permission it
  requests is `VIBRATE`. Nothing you type leaves the device.
- Every release is signed with the same certificate. Its SHA-256 digest is pinned as
  `RELEASE_CERT_SHA256` in `scripts/release_check.sh`; compare it with the output of
  `apksigner verify --print-certs <apk>`.

## [Unreleased]

### Changed

- A comma, period or other punctuation typed right after a tapped suggestion replaces the added space, so you get "сүз, " instead of "сүз ,".
- Sentence-start suggestions also appear at the start of a new line.
- After you undo an autocorrection, the same word is not corrected again in that field.

### Fixed

- Glide typing under Caps Lock types the word in capitals.
- Sliding across keys in English and in fields without glide typing types letters again, instead of drawing a trail that typed nothing.
- The personal dictionary learns new Tatar words that are close to dictionary words.
- Learned word pairs are no longer formed across sentences, line breaks, numbers or emoji.

## [3.6.0] — 2026-09-29

### Changed

- The suggestion strip is back to three cells; the four-cell layout from 3.3.0 is reverted.

### Removed

- The live candidate preview during glide typing: suggestions appear when you lift your finger, as before 3.3.0.

## [3.5.0] — 2026-09-29

### Added

- A new Appearance setting, "Emoji panel height": Same as keyboard (default), Larger or Max.

### Changed

- The emoji panel has more room for emoji: the search row moved into a 🔍 cell at the end of the tab strip, and the floating keys are slightly smaller.
- Dictionary lookups are faster, most noticeably in Tatar typo recovery.
- Drawing the keyboard and the suggestion strip does less work per frame, and startup profiles are refreshed for installs from Google Play.

### Security

- Host apps that return oversized text, fail on editor calls or report impossible cursor positions can no longer crash or confuse the keyboard.
- Debug logging in the input pipeline can no longer record the letters you type, even if it is switched on.

## [3.4.0] — 2026-09-28

### Added

- Learned emoji: put the same emoji after the same word twice, and the strip's last cell suggests it after that word.
- Learned emoji are never inserted automatically, stay on the device, pause while "Incognito mode" is on, and can be managed on the "Saved words" screen.

### Fixed

- An emoji picked from the suggestion strip now appears in the recent row of the emoji panel.
- Switching input methods from the keyboard's picker no longer leaks a background thread.

## [3.3.0] — 2026-09-28

### Added

- The suggestion strip shows four cells instead of three (reverted in 3.6.0).
- Glide typing previews candidates while your finger is still moving (removed in 3.6.0).
- Words entered by glide typing count as uses in the personal dictionary, the same as tapped suggestions.

## [3.2.0] — 2026-09-26

### Added

- Glide typing suggests the words you taught the keyboard, ranked by shape first and your usage second; a clear dictionary match still wins.

## [3.1.1] — 2026-09-25

### Fixed

- The app installs again on Android 11 and newer; the 3.1.0 build was rejected by the system and was never published.

## [3.1.0] — 2026-09-25

### Changed

- The keyboard looks closer to iOS: three Shift states, a droplet-shaped key preview, blue action Return keys, a white long-press panel and a taller suggestion strip.
- Letter keys answer a tap with the key preview only; functional keys still change color, and letter keys keep their pressed look when the key preview is off.
- The spacebar language label no longer fades while you type, and the glide trail is light gray instead of blue.
- Settings screens slide in and out unless system animations are off, and dialogs are restyled.
- The app's own screens are in Tatar by default unless the system language is Russian.
- The app is 8.5% smaller, draws frames faster, starts quicker and uses less memory, including during glide typing and while loading emoji suggestions.

### Fixed

- On Android 7–9 the keyboard respects the system-wide haptics setting.
- Tapping a dimmed settings row explains which switch to turn on, or that your device administrator locked it.
- Pasted text no longer teaches the personal dictionary.
- Several crashes, freezes and silent prediction failures caused by very large pastes, unusual host app behavior and edge cases in glide typing and layout.

### Security

- The cached text around the cursor is cleared when the input session ends or the keyboard hides, and password fields are never re-read.
- Dialogs that show your saved words are protected from screenshots.

## [3.0.2] — 2026-09-25

### Added

- Lifting your finger after a glide types the best word at once; the other candidates stay in the strip, and tapping one replaces the word.
- One backspace right after a glide deletes the whole word; consecutive glides are joined with exactly one space.
- A fading trail follows your finger while gliding, and the key under your fingertip lights up.

### Fixed

- Glide typing tells doubled-letter words from their plain twins (`сәләм` vs `сәлләм`).
- Glide typing works with suggestions turned off; it has its own switch.
- Glide typing works mid-sentence after a space placed before punctuation.
- Resting your finger on the first letter before gliding no longer prevents the glide.

## [3.0.1] — 2026-09-24

### Fixed

- The privacy policy and license links open the current repository over https.
- Undoing an autocorrection with backspace is reliable, and a corrected word can again be learned as part of a word pair.
- Tapping a word from your personal dictionary counts toward its usage.
- Notices about unreadable saved data are shown per language, and several Tatar and Russian strings are corrected.

## [3.0.0] — 2026-09-24

### Added

- Glide typing for Tatar and Russian, on by default: slide over the letters and lift; turn it off in Preferences → "Glide typing".
- Learned word pairs: type a pair twice and the second word is predicted after the first. Part of the personal dictionary, off by default.
- Learned word pairs never outrank the built-in predictions and are stored only as salted hashes until confirmed.
- The "Saved words" screen lists learned words and word pairs per language, with usage counts, delete and clear actions, and restore for a quarantined file.
- An "Incognito mode" switch pauses all personal learning; what is already saved still appears in suggestions.
- Russian sentence-start suggestions, and next-word predictions after a comma, semicolon or colon.
- Keyboard height presets: Compact, Default and Tall.

### Changed

- Sentence-start suggestions are capitalized.
- With autocorrect on, the strip shows the correction before it happens, with your word in the typed-word cell; tap your word to keep it.
- Many more Tatar words offer next-word predictions.

## [2.0.1] — 2026-09-21

### Changed

- After a committed word, empty cells are filled with the language's most frequent words, so the strip is never blank.

## [2.0.0] — 2026-09-20

### Added

- Word forms: after a Tatar word and a space, free cells offer its inflected forms (`сакчы` → `сакчысы`, `сакчылар`).
- Tatar sentence-start suggestions at the start of a field and after sentence-ending punctuation.
- Tatar typo recovery: when a prefix of four or more letters matches nothing, the strip offers dictionary words one letter off (`сцләм` → `сәләм`).

### Changed

- A complete Tatar word of four or more letters ranks its own forms first (`татар` → `татарлар`, `татарча`).
- The Tatar dictionary grew from 100,000 to 110,000 entries, mostly word forms found in the corpus.
- Next-word predictions appear right after you tap a suggestion, without waiting for the next keystroke.

## [1.9.15] — 2026-09-05

### Fixed

- A managed-configuration keyboard color of the wrong type no longer stops all other managed settings from applying.
- The language label on the spacebar is centered exactly.

## [1.9.14] — 2026-09-04

### Fixed

- Suggestions stay readable at large system font sizes; keyboard text no longer scales with the system font setting.
- The emoji panel respects the "Bottom offset" setting and keeps room for its grid at small keyboard heights.

## [1.9.13] — 2026-09-04

### Fixed

- On tablets (600dp and wider) the Tatar and Russian layouts have an Enter key again.
- On Android 15 the emoji panel no longer slides under the navigation bar, so its "АБВ" and delete keys work again.

## [1.9.12] — 2026-09-02

### Changed

- Tatar emoji search covers almost the whole emoji set (`этэч` finds 🐓).
- Emoji suggestions are on by default and also match common misspellings (`йорэк` → ❤️).

### Fixed

- Emoji search resets when the keyboard hides, instead of sending letters to the app afterwards.
- Next-word predictions are more reliable right after the prediction table loads and after cursor swipes or long backspaces.

### Security

- App dialogs reject taps passed through overlaying windows (tapjacking).

## [1.9.11] — 2026-09-01

### Fixed

- The first word in an empty field now gets next-word predictions and emoji suggestions.
- On a cold start, predictions no longer stay empty when the text appears before the prediction table finishes loading.

## [1.9.10] — 2026-09-01

### Added

- Emoji suggestions for Russian and Tatar: after a word and a space, a matching emoji appears in the last cell; a tap adds it and never changes your word.
- The word-to-emoji table is curated by hand; ambiguous words deliberately suggest nothing, and TalkBack reads the emoji by its short name.
- Off by default, with its own "Emoji suggestions" switch; not shown in password fields or on the English layout.

## [1.9.9] — 2026-09-01

### Changed

- The app is about 15% smaller thanks to denser packing of the dictionaries and prediction tables; suggestions and predictions are identical.
- Each prediction table is tied to its dictionary; on a mismatch predictions stay off and typing is unaffected.

## [1.9.8] — 2026-08-31

### Changed

- Next-word prediction learned everyday conversational language on both layouts; the dictionaries are unchanged.
- Tatar: common imperatives such as `шалтырат`, `сөйлә`, `утыр`, `эшлә` and `укы` get predictions, and `кил` and `кит` offer `әле`.
- Russian: dozens of conversational words such as `позвонишь`, `прощай` and `погоди` get predictions.

## [1.9.7] — 2026-08-31

### Fixed

- Russian next-word predictions are rebuilt against the current dictionary, dropping stale words; most predictions are unchanged.

## [1.9.6] — 2026-08-31

### Added

- On the Tatar layout: a ruble sign ₽ on the currency key's long-press, an "АБВ" label on the key back to letters, and „“ and «» quotes on the symbols layer.
- TalkBack announces the language name when you switch layouts.
- Emoji search understands Tatar for 200 common emoji (`көлү` finds 😄).

### Changed

- Key previews and the first suggestions after an install from Google Play are faster, and the app is slightly smaller.

### Security

- Settings and setup screens reject taps passed through overlaying windows (tapjacking), and the personal dictionary checks file sizes before reading.

## [1.9.5] — 2026-08-30

### Changed

- The app is about 17% smaller, and a startup profile lets installs from Google Play precompile the most-used code.

### Removed

- Layouts and interface translations for languages other than Tatar, Russian and English; if you used one, the keyboard switches to one of these three and keeps your settings.

## [1.9.4] — 2026-08-25

### Changed

- Thirteen common Tatar imperatives get next-word predictions: `кил` offers `әле · дә · син`, and `кит`, `тукта`, `кайт` and others work too.

## [1.9.3] — 2026-08-24

### Fixed

- Suggestions no longer vanish mid-word during normal typing (for example at `прив`); found suggestions were being discarded before they reached the strip.

## [1.9.2] — 2026-08-24

### Fixed

- The suggestion strip no longer stays empty after the cursor moves (a tap in the text, a spacebar slide, a swipe delete) until the next keystroke.

## [1.9.1] — 2026-08-24

### Changed

- Many more conversational Russian and Tatar word forms are in the dictionaries (`чертова`, `дамочка`, `кебекме`, `ярармы`).
- Subtitle recognition fragments (very short or vowel-less strings) and the colloquial spelling `можна` are kept out.

## [1.9.0] — 2026-08-24

### Added

- Conversational words such as imperatives, second-person forms and forms of address join both dictionaries (`позвони`, `послушай`, `хәтерлисеңме`).

## [1.8.4] — 2026-08-24

### Fixed

- A suggestion shown in the strip is always tappable; stale candidates are cleared as soon as you type another letter.
- Long-pressing a suggestion always responds: it explains that the personal dictionary is off, or that built-in words cannot be forgotten.
- The emoji key, emoji search and the privacy policy and license rows say so when they cannot open anything, instead of doing nothing.
- The autocorrect settings row is in Russian again, and the empty "Saved words" screen no longer asks you to turn on a switch that is already on.

## [1.8.3] — 2026-08-23

### Added

- Words from a damaged personal dictionary can be restored on the "Saved words" screen, which says how many survived; the damaged copy can be deleted separately.

### Fixed

- The notice about an unreadable personal dictionary waits until it can be shown, and a failure while confirming a saved word can no longer crash the keyboard.

## [1.8.2] — 2026-08-22

### Fixed

- Forgetting a saved word can no longer crash the keyboard, and failures to save, forget or erase words show a message.
- A personal dictionary that cannot be read is no longer wiped silently: you get a one-time notice and the data stays until you erase it.
- "Erase all words" removes everything, and an erased word can no longer reappear in the strip.
- Turning on suggestions while the dictionary loads, or tapping a suggestion after the field has closed, no longer fails silently.

## [1.8.1] — 2026-08-22

### Fixed

- Suggestions come back after you delete a mistyped letter, and tapping a suggestion right after a backspace inserts the word.

## [1.8.0] — 2026-08-22

### Added

- Next-word prediction for Russian, with its own table chosen by the layout; Tatar predictions never appear on the Russian layout.

### Fixed

- In emoji search, a query of only spaces counts as empty, and the cursor no longer overlaps the hint text.

## [1.7.0] — 2026-08-21

### Added

- Russian word completion from a 100,000-word dictionary, switching automatically with the layout.
- `ё` and `е` spellings are kept apart, so you get exactly the one you pick.
- Personal dictionary words are kept per language.

## [1.6.1] — 2026-08-20

### Fixed

- In emoji search the cursor sits right after the query, and the result row stays hidden until you type.

## [1.6.0] — 2026-08-20

### Added

- Swipe sideways in the emoji panel to move between sections.
- Long-press an emoji with people to pick one of five skin tones; your choice goes into recent emoji.

### Fixed

- A tap near the edge of a letter key no longer types the neighboring letter.

## [1.5.0] — 2026-08-20

### Added

- Emoji search in Russian and English, fully offline; the query never reaches the app's text field.

### Changed

- The emoji panel is redesigned: categories on top, one continuous scrolling list with section headers, larger emoji, and floating "АБВ" and delete keys.

## [1.4.0] — 2026-08-20

### Changed

- The spacebar is wider; while the language key is shown, the emoji panel opens with a long-press on the comma.
- The emoji panel uses the keyboard background, and its bottom bar no longer overlaps categories or the last row.

### Fixed

- The `?123` key switches layouts reliably when pressed near its edge, instead of typing a comma.

## [1.3.0] — 2026-08-19

### Added

- Emoji panel with categories and a recent row of up to 24 emoji; only emoji the system can draw are shown.
- Personal dictionary, off by default: learns Tatar words you type three times, with a screen to review, search and erase them.
- Next-word prediction: after a word and a space, the strip offers three likely next words; suggestions also tolerate a typo in the current word.
- Autocorrect with undo, off by default: fixes a clearly misspelled Tatar word on space or punctuation; backspace right after restores what you typed.

### Security

- App backup is disabled: saved words, recent emoji and settings never go to the cloud or to a new device.
- Personal data is kept in credential-protected storage, unavailable before the first unlock.

## [1.2.0] — 2026-07-24

### Added

- Optional offline Tatar word completion, off by default: a strip with three frequent completions of the current word.
- Suggestions are hidden in password, email, address and other private fields; a tapped word keeps your capitalization and adds a space.
- TalkBack announces suggestions once when they appear.

## [1.1.0] — 2026-07-21

### Added

- Full Tatar translation of settings, onboarding and TalkBack strings.
- TalkBack support for long-press alternatives, the spacebar language and Shift state changes.

### Changed

- Onboarding and settings have a new card design with light and dark themes.

## [1.0.1] — 2026-07-20

First public release.

### Fixed

- Settings and the language picker no longer crash.
- The selected language is saved immediately and is no longer overwritten by a field's language hint.

## [1.0.0] — 2026-07-19

Not published: critical bugs were found in device testing, and 1.0.1 became the first public release.

### Added

- Tatar layout: the standard `ЙЦУКЕН` plus a visible fifth row `ә ө ү җ ң һ`; Russian and English layouts with a globe key to switch.
- Tatar letters on long-press on the Russian layout, symbol layers, double-space period, spacebar cursor slide and multi-touch typing.
- Light and dark themes, key preview, sound and vibration, full TalkBack support, two-step onboarding and direct boot support.
