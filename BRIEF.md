# Project brief: Tatar Keyboard for Android

The product vision and the decisions treated as fixed. A decision changes only by an explicit
choice, recorded here. Items marked "open question" are not fixed.

## Vision

A native Android keyboard (IME) with a Tatar layout, visually close to the iOS system keyboard, as
light and responsive as possible on weak and budget devices. Privacy is a feature: fully offline,
no INTERNET permission, open source.

Audience: Tatar speakers in Tatarstan and across Russia, mostly on budget Android phones
(Xiaomi/Redmi, Samsung A series). Developed by one person.

## Fixed technical decisions

- **Base: a fork of Simple Keyboard (rkkr)**, Apache-2.0, Java, itself derived from AOSP LatinIME.
  Not written from scratch.
- **Language:** new code is Kotlin, called from the Java base through interop. The Java base is
  not converted wholesale.
- **UI:** the keyboard is one custom View drawn on a Canvas. Not allowed: Jetpack Compose in the
  IME process, Flutter or React Native, new code on the deprecated `KeyboardView`. Compose is
  allowed only in the settings Activity.
- **No NDK/C++, no third-party runtime dependencies, no INTERNET permission** (checked in CI).
- **SDK:** minSdk 24, targetSdk 37, compileSdk 37 on platform android-37.2.
- **IME:** `InputMethodService`, `directBootAware`, no fullscreen mode; three languages: tt_RU, ru,
  en_US, switched with the globe key. Locales and layouts are limited to these three.
- **Input:** characters are committed immediately, without composing text. Backspace deletes one
  code point, or a whole emoji cluster.

## Performance budgets

- Release APK ≤ 3 MB (3 145 728 bytes). This is the only size limit.
- Cold start to the visible keyboard < 400 ms on a budget device. This is the main metric.
- Zero allocations in the draw loop.
- Memory (PSS): the ceiling and the measurement procedure live in `docs/PERF-BUDGETS.md`.

Where the numbers come from. Budget devices, and MIUI/HyperOS in particular, kill background
processes aggressively. The system restarts the IME on the next tap in a text field, so the user
sees a cold start far more often than on stock Android; cold start therefore matters more than
average speed. Google's guidance for apps is a cold start ≤ 500 ms; a keyboard needs a tighter
bound, hence 400 ms. On weak devices, garbage-collection pauses, not heap size, cause visible jank,
hence the zero-allocation rule for `onDraw` and touch handling. Keyboards without an ML engine or
native code fit in 1–3 MB, hence the size limit.

## Layout

- Tatar: the standard Russian ЙЦУКЕН plus a separate, always-visible fifth row for `ә ө ү җ ң һ`,
  with the same letters also on long-press of the related Russian letters (`а→ә`, `о→ө`, `у→ү`, `ж→җ`,
  `н→ң`, `х→һ`).
- Why the fifth row: in the Written Corpus of the Tatar Language (corpus.tatar, letter unigram
  list), `ә` is the 5th most frequent letter (6.65 %), ahead of `л`, `ы`, `т` and `к`. The six extra letters
  together make up 10.6 % of all letters, about one letter in nine. A long press costs 300–500 ms
  plus aiming in a popup, which is too slow for letters this common. The other five are rarer
  (`ү` 1.21 %, `ң` 1.01 %, `ө` 0.90 %, `җ` 0.46 %, `һ` 0.40 %), so for them long-press alone would be
  tolerable, and a dedicated key is a comfort.
- Layouts are data (XML), not code. A Latin Tatar layout (Zamanälif) is not planned, but the format
  must allow it.
- Fifth-row key order: alphabetical `ә ө ү җ ң һ` — fixed by decision; the frequency order
  (`ә ү ң ө җ һ`) was considered and not taken, no A/B study is run.

## iOS style: limits

Apple publishes no specification of its keyboard; the values below are community reverse
engineering (KeyboardKit color assets, screenshot measurements), with 1 pt taken as 1 dp.

- Reproduced: the geometry (key corner radius 5 dp, gaps, row proportions), the palette (light:
  background #D4D6DD, letter keys #FFFFFF, function keys about #B3B7C0; dark: #2C2C2C / #6B6B6B /
  #474747), a sharp 1 dp shadow under each key (offset only, no blur, unlike Material elevation),
  the key preview balloon above the pressed key, reaction on `ACTION_DOWN` with `KEYBOARD_TAP`
  haptics, light and dark themes, a three-cell suggestion strip. The exact values in use live in
  `app/src/main/res/values/colors.xml`, `values-night/colors.xml` and the `ios_key_*` drawables.
- Not allowed: the SF Pro font (its license forbids use outside Apple platforms and embedding in
  software; the keyboard uses the system font, Roboto), SF Symbols icons (same license), Apple
  sounds, and the words iPhone/iOS in marketing and store listings. The shared patterns (gray
  background, white keys, gray function keys, rounded corners, balloon preview) are common across
  the industry; copied Apple assets are not.

## Scope

Shipped:

1. Layouts tt (with the fifth row), ru, en; number and symbol layers.
2. Shift and Caps Lock, auto-capitalization, backspace with auto-repeat, Enter by `imeOptions`,
   space-bar swipe to move the cursor, multi-touch.
3. The iOS-style skin: key preview, long-press alternatives panel, light and dark themes.
4. Haptics and key-click sound, both optional.
5. Two-step onboarding and the settings screens.
6. Accessibility: explore-by-touch, spoken descriptions of the Tatar letters.
7. Correct behavior in password fields, with edge-to-edge insets (API 35+), in WebView, in
   landscape.
8. Word completion and next-word prediction for Tatar and Russian in a three-cell suggestion
   strip, with typo recovery and optional autocorrect; bundled dictionaries and bigram tables built
   by the Python pipeline in `scripts/`. Suggestions are opt-in.
9. Glide typing for Tatar and Russian (`latin/glide/`); the word is committed on lift, and other
   candidates appear in the strip when suggestions are on.
10. The emoji panel (categories, search, recent emoji) and emoji suggestions in the strip.
11. The opt-in personal dictionary: learned words, learned word pairs and learned emoji, with a
    pause-learning (incognito) switch and a screen to review and erase them.

Excluded: voice input, custom C++ code, clipboard history.

## Verification

- JVM unit tests for the logic (layouts, the shift state machine, `InputConnection` handling,
  engines, stores). No Robolectric.
- Manual matrix: Telegram, Chrome/WebView, a password field, MIUI/HyperOS, One UI, landscape.
- Budget measurements on a real budget device: `adb shell dumpsys meminfo`, cold-start time,
  frame times; a baseline profile ships with the app.

## Distribution

Channels: GitHub Releases, IzzyOnDroid and F-Droid (reproducible build); the procedure is in
`docs/PUBLISH-CHECKLIST.md`. RuStore and Google Play closed testing (12 testers for 14 days) were
planned as later steps. Privacy policy: `PRIVACY.md`.

## Risks and planning constraints

- `InputConnection` behavior differs from app to app: test at every stage, not at the end.
- MIUI/HyperOS kill the IME process, so cold start matters more than average performance.
- One developer: keep changes small, each one producing a working build.
