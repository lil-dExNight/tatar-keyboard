# UI research

How to improve the keyboard's visual layer: iOS fidelity, theming, typography, motion,
platform fit and dark-theme behavior. Proof rules: `research/measurement-framework.md`.
Sources are linked inline in the findings.

## Current state

One iOS-style theme (light + dark via `values-night`), canvas-drawn keys with a 1 dp offset
shadow, balloon preview above pressed keys, instant panel swaps, a 53 ms balloon dismiss,
a fading glide trail, `EFFECT_CLICK` haptics on a prebuilt-effects HandlerThread. The palette
matches the reverse-engineered classic iOS values almost exactly (background off by Δ1 in one
channel). Zero allocations in the draw loop, pinned by `DrawAllocInstrumentationTest`.

## What the world does

**iOS fidelity.** Two iOS keyboard designs coexist: the classic opaque look (which we match)
and the iOS 26 Liquid Glass look (translucency; even Apple ships it side-by-side with the
legacy one, and its transparency collides with app surfaces). KeyboardKit's reverse
engineering pins the deltas: liquid mode raises the corner radius to 9 and drives the look
through opacity, not new colors. Community evidence says geometry changes are noticed
instantly and punished; feedback latency below the conscious threshold still changes the
"feel". The behavioral details that matter: pressed letter keys darken to the functional gray
when no balloon carries the feedback; callouts clamp at screen edges with a mirrored neck and
carry a *soft* shadow (unlike the hard key shadow); per-keystroke haptics are a first-class
iOS feature with measured benefit (Brewster et al., CHI 2007).

**Theming.** Dynamic color is table stakes: Gboard (headline feature, redesigned as recently
as Gboard 14.9), SwiftKey (wallpaper-adaptive), Samsung (an entire Good Lock module exists
just for keyboard themes), HeliBoard and FlorisBoard (theme engines; theming was
FlorisBoard's #1 community contribution category). Android 12+ covers ~79% of active devices.
Verified on the local SDK: the Material You palette is readable with **zero dependencies** —
`android.R.color.system_accent*/system_neutral*` are public framework resources since API 31,
and `WallpaperManager.getWallpaperColors()` since API 27. Vendoring material-color-utilities
is unnecessary. Palette swaps are load-time events in our renderer, so the zero-allocation
draw loop is structurally unaffected.

**Typography.** Apple ships a keyboard-specific font cut (SF Compact) — small-size legibility
is apertures/spacing/weight, not just size; we use the system font deliberately (OEM-font
familiarity, no APK cost). Our dp-not-sp choice for key labels is validated: key geometry is
fixed in dp, so sp labels could only overflow and shrink back; the iOS keyboard likewise
ignores Dynamic Type. Measured WCAG ratios over our palette: everything passes AA except
three pairs — the light action-key accent label (4.02:1, the accepted THREAT-MODEL row), the
**dark action-key accent label (3.65:1 — worse, and currently undocumented)**, and the dark
hint labels (3.52:1). Roboto covers all six Tatar letters (Cyrillic Supplement), but the
Android Roboto build has no glyph hints, and OEM fonts (MiSans, SamsungOne, user-installed
fonts) are the real risk: per-glyph Noto fallback can render `ү` in a different cut than `у`.
The 37 dp balloon preview is the real legibility fallback on press.

**Motion.** The big keyboards run *no* per-press animation (Apple HIG: avoid motion in
frequent interactions); our instant balloon show and 53 ms dismiss already match, and sit
inside Material's duration bands. Refresh-rate switching is platform-managed — a View-based
IME must do nothing. The one real gap: platform-pushed animator scaling (the "Remove
animations" accessibility toggle and Battery Saver) reaches `ValueAnimator`s automatically —
our balloon dismiss and settings transitions comply for free — but our **hand-rolled
animations ignore it**: the glide trail's post-lift fade and the emoji panel's OverScroller
section jumps keep animating. WCAG 2.3.3 wants interaction-triggered non-essential animation
disableable. Haptics: the industry consolidates on one subtle per-press tick under a system
toggle (Android 15 added a global keyboard-vibration switch; Gboard *removed* its strength
slider). One inconsistency of ours: the emoji skin-tone popup haptic bypasses the app's own
vibrate toggle.

**Platform fit (targetSdk 37).** Edge-to-edge is enforced: the IME window extends under the
navigation bar since Android 15 — our emoji panel measures the overlap dynamically, but the
main keyboard relies on a static bottom padding, so the spacebar row can sit in the gesture-
pill zone (Gboard/iOS lift it clear). Display cutouts now default to ALWAYS for non-floating
windows — landscape corner keys can render under a side cutout. `setNavigationBarColor` is
deprecated but still effective for 3-button nav (the only place it matters). Stylus
handwriting is undeclared — a stylus currently behaves as a precise finger, an implicit
accident worth testing and documenting. Desktop-windowing IME anchoring is undocumented by
the platform — needs a hardware check.

**Dark theme.** OLED battery: dark-vs-light matters at full brightness (~39–47% display power,
Purdue MobiSys'21); at indoor brightness it is 3–9%; true-black vs our dark-gray `#2C2C2C`
is a placebo (≲0.5% of display power, first-principles). Our dark palette is byte-identical
to the iOS reverse engineering. Two robustness holes found: (a) on API ≥ 31 the input view is
deliberately not recreated on configuration change, so a system dark-mode flip may leave
stale colors until the IME restarts — untested; (b) Android 16 QPR2's "expanded dark theme"
inverts apps at the display-list level, and a canvas keyboard is in scope — our settings
screens already flip `isLightTheme` correctly, but the IME window declares no explicit theme.

## Decision memos (ranked)

**UI1 — landed.** The action accents, dark hints and every other failing pair pass WCAG AA in
both themes; `ThemeContrastContractTest` pins the pair list (13 text pairs at 4.5:1, 4 icon
pairs at 3:1). The THREAT-MODEL accepted-risk row is resolved. The same audit's app-screen
findings (setup link, filled-button labels, secondary text) are fixed and pinned by
`AppScreenContrastContractTest`: the light accent and secondary text stepped one notch darker,
and the dark filled button takes its own fill color, darker than the dark link accent.

**UI2 — landed.** The Dynamic (Material You) theme reuses the iOS geometry with colors routed
through framework `system_*` roles on API 31+ (wholesale fallback to the Tatar palette below
or when a role fails to resolve); `DynamicThemeContractTest` gates the per-uiMode pairs
against the canonical framework tones; wallpaper changes re-resolve through
`OnColorsChangedListener`. The OEM retoning variance probe stays on the device pass; the
default Tatar theme is byte-untouched.

**UI3 — Close the uiMode-flip staleness hole (probe first).** Device leg: `cmd uimode night
yes/no` with the keyboard visible, screencap, assert the palette crossed. If stale on
API 31+, drop the `< S` condition in `KeyboardSwitcher.onConfigurationChanged` so the view
is always recreated. Eliminates the FlorisBoard-#3034 bug class.

**UI4 — landed.** The balloon clamps to the key grid's side band and the neck mirrors the
shift (`KeyPreviewClampTest` pins the math; the draw loop allocates nothing new). The two
device verifications (more-keys panel border, emoji-panel header inset) stay open, plus the
new edge-panel slide-selection check.

**UI5 — landed.** The glide trail fade and the emoji fling/section jumps are gated on the
system animator scale via `MotionPolicy` (read once per gesture); the live trail and the drag
scroll stay (direct manipulation, not animation). The wiring is contract-tested.

**UI6 — landed.** The emoji long-press haptic goes through the app's vibrate toggle
(`AudioAndHapticFeedbackManager.performLongPressHapticFeedback`); contract-tested.

**UI7 — Dynamic nav-bar-overlap reservation for the main keyboard.** Port the emoji panel's
`getWindowVisibleDisplayFrame` overlap measurement to the keyboard surface so the spacebar
row clears the gesture pill on Android 15+; identical behavior on older APIs (overlap
measures 0). Ripples through `KeyboardId.mBottomOffset` and the device-test calibration —
medium cost, real ergonomics win on gesture-nav devices.

**UI8 — Display-cutout side padding in landscape.** Read the cutout safe inset, pad the
keyboard sides when a side cutout exists, mirror in `onComputeInsets`. Emulator probe with a
simulated cutout; zero effect on cutout-free devices.

**UI9 — Tatar-glyph distinguishability device test.** Render the six letters and their
confusion pairs (`ж`/`җ`, `н`/`ң`, `у`/`ү`, `х`/`һ`, `о`/`ө`, `а`/`ә`, plus `у`/`ў`) at the
letter and hint sizes on the device matrix (Pixel + Xiaomi + Samsung, including one run under
a third-party OEM font); assert pairwise bitmap differences. Turns "Roboto covers Cyrillic
Supplement" and "OEM fonts draw these well at hint size" from assumptions into pins. If it
fails on hdpi, bump the hint ratio (one config line).

**UI10 — Explicit IME-window theme + OEM-forcing probes.** Set an explicit `android:theme` on
the service (removes reliance on the platform default for `isLightTheme` resolution; verify
rendering unchanged); add device legs for expanded-dark (QPR2 emulator) and HyperOS per-app
forcing. Immunity verified on exactly the vectors Google warns about.

**UI11 — Frame-pacing leg in the perf ritual.** P90/P99 present-to-present from
`dumpsys SurfaceFlinger --timestats` during a typing+glide burst; protects the 120 Hz budget
(8.3 ms) empirically. Also: `KEYBOARD_PRESS` on API 30+ instead of `KEYBOARD_TAP` —
device-dependent gain, blind A/B on the reference device, defer on any ambiguity.

**UI12 — Soft balloon shadow (optional polish).** iOS callouts carry a soft shadow; ours
reuses the hard key shadow. A cached `BlurMaskFilter` — verify it survives the hardware-
accelerated path and the frames budget. Hours; subtle but the balloon is the most-seen
animation in the product.

## Decisions to record (no code)

- **Skip Liquid Glass** — translucency cannot be reproduced faithfully within our constraints
  (window blur needs API 31+ gating, costs GPU and cold start), the classic look is what
  "iOS-style" recognizably means, and our contrast would *drop* with translucency. Revisit
  only if the classic look disappears from current iOS.
- **No true-black ("AMOLED") variant** — placebo battery delta, breaks iOS parity, doubles
  the contrast-test surface; deliberately one color line away if store reviews ever demand it.
- **No per-key press animations, no strip crossfades, no panel slide transitions, no
  vibration-strength slider** (Gboard removed its own; the system toggle won). Pin the
  existing motion budget in contract tests.
- **No full theme engine / user color pickers / background images** — maintenance surface
  disproportionate to the demand evidence; the `app_restrictions.xml` theme hook stays as the
  enterprise escape hatch. Revisit on user-feedback signal.
- **Do not bundle a font** — APK cost plus loss of OEM-font familiarity; the UI9 fallback
  detection is the correct mitigation.
- **Stylus handwriting stays undeclared** — declaring it without an ink surface is false
  advertising; add device legs proving tap/glide/password-field coexistence with a stylus.

## Risks and open questions

- iOS 26 keyboard specifics rest on KeyboardKit (whose product is fidelity) and news
  coverage; no primary Apple source was fetchable. Verify reference screenshots against a
  real iOS version before UI4 lands.
- OEM palette variance may make the dynamic theme look near-static on HyperOS/MIUI —
  unverified; UI2 needs the device-matrix spot check.
- Whether "Bold text" / outline-text settings reach canvas-drawn key labels is unknown;
  add a font-scale/bold pixel probe to the emulator smoke (labels must stay pixel-identical).
- The dark-accent fix deviates from Apple's exact value — refresh store screenshots and
  re-check light-theme symmetry when it lands.
- Desktop-windowing IME anchoring is undocumented; claim nothing until observed on hardware.
