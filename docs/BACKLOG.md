# Backlog

Open work only. When an item is done, delete it; the change itself is the record.

## Measurements

- **Release-build frame time and memory.** The frame p50 and total PSS of the current release
  APK have not been measured; the device script measures the debug build, and it can turn
  suggestions on only in a debuggable package. Needs an automated way to enable suggestions in
  the release build, then a release-scale PSS ceiling in `docs/PERF-BUDGETS.md`.
- **Budgets never measured.** Warm show (< 150 ms), touch handling in our code (< 5 ms) and janky
  frames while typing (~0 %) have no measurement or check yet.

## Blocked on the toolchain

- **Manifest memory budget.** Android 17 QPR2's `<memory-budget>` element (the IME is its
  canonical perceptible-state example) is rejected by the aapt2 in the current AGP. It needs
  AGP 9.3 or later and compileSdk on platform android-37.2; the numbers would come from the
  release-build PSS measurement above.

## Device tests blocked by external conditions

- **Interactive check of the 3.1 visual refresh** on a real device (settings, keyboard theme,
  suggestion strip, emoji panel in both themes).
- **Direct Boot, live:** reboot, then type the PIN with this keyboard before the first unlock.
- **Telegram:** typing, suggestions and emoji in Telegram's custom editor (not installed on the
  test device).
- **Tablet layout:** no tablet hardware available.
- **TalkBack by ear:** the spoken output, including the Tatar letter descriptions, needs a person
  listening on a device.
- **Full gesture navigation:** HyperOS ignores the adb toggle, so the mode has to be switched by
  hand and tested.

## Known bugs

- **Tatar glide dead after a language cycle.** After tt → ru → en → tt with the globe key, a
  Tatar glide arms but commits nothing until the field is refocused. Suspected cause:
  `LatinIME.updateKeyNeighbors` in `onCurrentSubtypeChanged` builds the glide geometry from the
  previous keyboard.
- **Double space after an auto-space.** A space typed right where an auto-space was appended
  (a tapped suggestion, or the space moved after a punctuation mark) gives two spaces; it should
  be swallowed.

## Researched, not done

- **Glide aliases for ъ and ё.** Let a glide over ь or е also decode words with ъ or ё; touches
  the glide goldens, the calibration pins and `scripts/glide_pack.py`.
- **Glide spacing.** A space before a glide that follows punctuation or a typed word, and a
  phantom space after it; must keep the one-backspace undo and autocorrect consistent.
- **Doubled letters without a twin.** Score a doubled letter on a glide path when the dictionary
  has no single-letter twin; needs a calibration run and a latency check.
- **Glide context rerank.** Rerank glide candidates by the previous word's bigram successors;
  calibration-heavy.
- **Digits on long-press** of the top letter row (tt, ru); needs a visual check of the popup and
  the `RowkeysSyncTest` parity.
- **Double-space period only in general text fields**, not in phone, number, email or URL fields.
- **Haptics without per-press allocation.** `AudioAndHapticFeedbackManager` allocates a lambda
  and a `VibrationEffect` per press and calls `View.performHapticFeedback` off the UI thread below
  API 29; the feel needs a physical device to check.

## Parked decisions

- **Emoji suggestion under the cursor without a trailing space.** Deferred; weigh against user
  demand after publication.
- **Lossy frequency compression in the dictionaries.** Not taken; reconsider only under APK size
  pressure.
- Rejected with measurements, do not reopen without new data: trigram prediction, two-edit typo
  recovery, geometric-neighbor typo recovery, extending autocorrect, sharding the emoji
  suggestion index.
