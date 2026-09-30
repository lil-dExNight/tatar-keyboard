# Device test plan

How to test the keyboard end to end on a connected phone. The reference device is the POCO C71
(720x1640, Android 15, HyperOS): `GlideUiDeviceTest`, `DrawAllocInstrumentationTest` and
`scripts/device-perf-ritual.sh` are calibrated for its screen. Other phones can run stages 1, 2
(except those two tests), 4 and 5, with tap coordinates recomputed.

Run the stages in order. Stages 1–5 are required after changes that touch the input path, the
suggestion engine, drawing or storage; stage 6 is optional. Results go to
`build/device-test-<date>/`; summarize them in `HANDOFF.md` and turn failures into fixes or
`docs/BACKLOG.md` entries.

## 0. Prerequisites

Phone:

- Developer options → **USB debugging**, **Install via USB**, and **USB debugging (Security
  settings)** on. The last one is required for injected taps; HyperOS may ask for a Mi account.
- Screen unlocked and kept on (Developer options → Stay awake), phone idle and charging. Timing
  tests are only valid on an idle device.
- After plugging in: USB mode "File transfer", and accept the RSA fingerprint prompt.

Mac:

- Apple silicon Macs ask "Allow accessory to connect?" for a new USB device. If the phone does not
  appear in `system_profiler SPUSBDataType`, check System Settings → Privacy & Security → Allow
  accessories to connect.
- If USB does not work, use wireless debugging (same Wi-Fi): Developer options → Wireless
  debugging → Pair device with pairing code, then `adb pair <ip:pairing-port> <code>` and
  `adb connect <ip:port>`. All stages work over Wi-Fi.
- GNU tools first in `PATH` (the scripts use `stat -c`, `sha256sum`, `grep -P`), and ImageMagick
  for strip screenshots:
  `PATH="$(brew --prefix coreutils)/libexec/gnubin:$(brew --prefix grep)/libexec/gnubin:$PATH"`.
- `adb` from `<sdk>/platform-tools/`, where `<sdk>` is `sdk.dir` in `local.properties`. With more
  than one device attached, set `ANDROID_SERIAL`.

Safety rules for every stage:

- Tests use the debug package `org.tatarkeyboard.ime.debug`. The installed release (and its
  personal dictionary) is not touched.
- Never `am force-stop` the package whose keyboard is selected: HyperOS then switches the system
  keyboard. Use `adb shell run-as <pkg> kill -9 <pid>` instead; the device scripts already do.
- Record the current keyboard first and restore it at the end (stage 7).

## 1. Connect and record the device state

```
adb devices -l
adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell wm size; adb shell wm density
adb shell settings get secure default_input_method > build/device-test-<date>/ime-before.txt
adb logcat -b crash -c
```

Pass: one device in state `device`; `wm size` is `720x1640` for the calibrated tests.

## 2. Build, install and run the instrumentation tests

```
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class <class> \
  org.tatarkeyboard.ime.debug.test/android.test.InstrumentationTestRunner
```

Run one class at a time, correctness first, timing last, with the screen on and the phone
untouched. Classes are under `rkr.simplekeyboard.inputmethod`.

| Class | Checks | Pass |
|---|---|---|
| `latin.glide.GlidePointerDeviceTest` | Touch pipeline turns hold-then-swipe and immediate swipes into a glide; the trail is fed; a fired long press ends the gesture | all tests OK |
| `latin.glide.GlideUiDeviceTest` | Real swipe on the live keyboard commits the word on lift; an alternative from the strip replaces it; a loop on a doubled letter gives the doubled word | all OK (fails fast on a non-720x1640 screen) |
| `latin.glide.GlideDeviceInstrumentationTest` | `сәләм` decodes on the live geometry; decode latency; index rebuild cost after release | p95 ≤ 5 ms |
| `latin.dictionary.engine.E3bComputeInstrumentationTest` | Typo-recovery lookup latency over the review prefixes | p95 ≤ 5 ms |
| `latin.dictionary.engine.DictionaryIoStrategyInstrumentationTest` | Cold load, lookup latency and PSS of the mapped and heap read paths | p95 ≤ 5 ms on every leg |
| `latin.emoji.EmojiIndexReloadInstrumentationTest` | Cost of reloading the emoji search index after release | test OK; record the numbers |
| `keyboard.DrawAllocInstrumentationTest` | No allocations in our board and strip draw code; tap bursts stay within the allocation budget | all OK |

`E3bComputeInstrumentationTest` and `DictionaryIoStrategyInstrumentationTest` lost their
geometric-neighbor parts when typo edit classes #2/#3 were removed; confirm they still pass on the
device.

## 3. Performance script

```
bash scripts/device-perf-ritual.sh --outdir build/device-test-<date>/perf
```

It kills the process with `run-as kill -9`, turns suggestions on through `run-as` (debuggable
package only), measures cold start ×5, PSS in five scenarios and frame times over a fixed Tatar
typing script, and restores the device state on exit. Pass criteria (see `docs/PERF-BUDGETS.md`):

- PSS ≤ 114 000 kB in every scenario (debug-build ceiling).
- Frame p50 well under the 16.7 ms deadline; compare p90/p95 with the previous run.
- Cold start is informational on the debug build (about 2× the release build); the < 400 ms
  budget applies to release builds (stage 6).

## 4. No network traffic

```
bash scripts/device-netstats-proof.sh --outdir build/device-test-<date>/netstats
```

Pass: the per-UID rx/tx delta over the scripted session is exactly zero bytes, on both the
netstats and the eBPF counters.

## 5. Functional scenarios

Driven with `adb shell input tap/swipe/text` on the try-it field of the setup screen
(`adb shell am start -n org.tatarkeyboard.ime.debug/rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity`)
and on a normal app field, with a screenshot (`adb exec-out screencap -p`) for every row.
Turn on suggestions, autocorrect and the personal dictionary in the keyboard settings first
(`pref_tatar_suggestions`, `pref_tatar_autocorrect`, `pref_personal_dictionary`).

| Area | Scenario | Expected |
|---|---|---|
| Layout | Tatar layout | ЙЦУКЕН plus the fifth row `ә ө ү җ ң һ`, every letter one tap |
| Layout | Russian layout, long press а/о/у/ж/н/х, е, ь | `ә ө ү җ ң һ`, `ё`, `ъ` in the popup |
| Layout | Globe key | tt → ru → en → tt; TalkBack announces the new language |
| Keys | Shift once / twice, auto-capitalization after `. ` | one capital / caps lock / capital at sentence start |
| Keys | Double space | `. ` inserted; backspace right after reverts it |
| Keys | Swipe on the space bar; hold backspace | cursor moves; delete repeats; an emoji is deleted whole |
| Keys | Enter in a search field, a multi-line field, a "Next" field | the field's action / new line / focus moves |
| Suggestions | Type `мин` | strip with three cells of completions |
| Suggestions | Tap a completion | word plus space committed; next-word predictions appear without typing |
| Suggestions | Type `сакчы` + space | word forms `сакчысы · сакчылар · …` in the strip |
| Suggestions | Type `сцләм` | `сәләм` offered (typo recovery) |
| Autocorrect | Type a word with a single long-press typo, then space | replaced; the typed-word cell shows the original; backspace right after restores it |
| Suggestions | Start of field, and after `. ` | sentence-start suggestions, strip not empty |
| Glide | Swipe `сәләм` (tt) and a Russian word | word committed on lift; alternatives in the strip; nothing repaints while the finger moves |
| Glide | Glide typing off in settings | swipes act as taps; space swipe still moves the cursor |
| Emoji | Emoji key / long press on comma (with the globe key shown) | panel opens; tabs, recents, 🔍 search (Tatar and Russian queries), skin-tone popup |
| Emoji | Emoji panel height: Same / Larger / Max | panel height changes; BACK closes the panel, the keyboard reopens normally |
| Emoji | Word with an emoji suggestion + space | emoji in the last cell; tap appends it, the word stays |
| Learning | Type a new word three times; a word pair twice; the same emoji after a word twice | word, pair and learned emoji offered afterwards; all visible under Saved words |
| Learning | Forget one entry, clear all, incognito on | entry gone; nothing learned while incognito |
| Privacy | Password field, URL/e-mail field | no suggestions, nothing learned, no cache reads |
| Appearance | Light and dark theme; keyboard height Compact / Default / Tall; number row; bottom offset | layout and panel match; nothing clipped under the navigation bar |
| Settings | Every screen of the settings app | opens; screenshots of the settings window come out blank (`FLAG_SECURE`) |
| Stability | `adb logcat -b crash -d` after all rows | empty |

## 6. Optional stages

- **Release build performance** (open item in `docs/BACKLOG.md`). Sign with
  `scripts/release_pack.sh`, install over the release package only with the owner's consent
  (it replaces the everyday keyboard; data stays because the signature matches), enable
  suggestions in the app's settings by hand, then run stage 3 with `--pkg org.tatarkeyboard.ime`.
  Reinstall the published APK afterwards.
- **Baseline profile.** Generate it on the emulator, as `AGENTS.md` describes. The generator
  refuses a physical device unless the instrumentation argument `ttAllowPhysicalDevice=true` is
  passed; profiles recorded on the phone are not comparable with the committed ones.
- **Needs a person:** Direct Boot (reboot, type the PIN with this keyboard before the first
  unlock), TalkBack by ear, Telegram if installed, full gesture navigation switched by hand.

## 7. Restore and clean up

```
adb shell ime set "$(cat build/device-test-<date>/ime-before.txt)"
adb shell settings get secure default_input_method       # must match ime-before.txt
adb uninstall org.tatarkeyboard.ime.debug.test           # optional
adb uninstall org.tatarkeyboard.ime.debug                # optional
```

Pass: the original keyboard is selected again and the release package is unchanged
(`adb shell dumpsys package org.tatarkeyboard.ime | grep versionName`).
