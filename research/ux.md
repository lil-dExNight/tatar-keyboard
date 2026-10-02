# UX research

How to improve the keyboard's user experience: suggestion strip, autocorrect, glide
discoverability, onboarding, emoji and privacy UX, and the Tatar-specific layout questions.
Proof rules: `research/measurement-framework.md`. Sources are linked inline.

## Current state

iOS-style skin; a three-cell suggestion strip (fixed geometry, allocation-free canvas);
suggestions opt-in (master switch off by default) with a one-shot offer dialog; glide on by
default with commit-on-lift, alternates in the strip and whole-word backspace undo; emoji
panel (tabs + swipe, recents, search, skin tones); personal-dictionary review/erase screen
with quarantine cards; pause-learning switch; two-step setup activity; TalkBack support with
spoken Tatar letter names.

## What the evidence says

- **Autocorrect is the speed lever; prediction, as used, is not.** In the largest field study
  (Palin et al., MobileHCI 2019, N=37,370) autocorrect usage correlates with speed
  (r = +0.24, ~10 WPM over prediction users); prediction usage correlates *negatively*
  (r = −0.18). Prediction costs an attention shift; autocorrect costs nothing extra.
- **Suggestions are not cognitively free.** Quinn & Zhai (CHI 2016): always-on suggestions
  reduced keystrokes and were preferred, yet *impaired time performance* — the attend-and-
  evaluate cost is real. Quality gates attention: accurate lists get used (Trnka et al.:
  ~91% utilization), inaccurate ones teach strip-blindness (~73% and dropping).
- **Users glance at the strip at word boundaries, not continuously** (VelociTap, CHI 2015:
  intermediate visual feedback did not affect speed or errors; checking measurably slows
  typing — Li, Feit et al., IMWUT 2025 eye-tracking: 68% of strip checks end without a pick;
  in 43.6% of cases users typed the word manually despite fixating on the correct
  suggestion). Suggestion benefit is conditional on accuracy (Roy et al., CHI 2021).
- **Three fixed cells are right.** Choice time over word lists grows linearly unless the
  layout is ordered and stable (Landauer & Nachbar); lists beyond ~2 candidates can be a net
  loss with slow selection (MacKenzie 2002). No evidence supports a fourth cell.
- **Mobile users under-correct because correction is expensive** (2.34% uncorrected errors
  vs 1.17% desktop). The industry-standard undo (backspace reverts the correction — Gboard
  documents it; iOS 17 underlines corrected words for tap-revert) works only if users know
  it exists.
- **Trust is behavioral and fragile around retroactive changes.** SwiftKey community
  complaints concentrate on corrections of *correctly typed* text; the antidote is a
  visible, immediate revert. Reyal et al. (CHI 2015): users migrate to gesture typing over
  weeks despite its *higher* error rate — provided recovery is cheap. Their complaint list is
  entirely about unrecoverable glide errors.
- **Onboarding's chokepoint is the system's "Attention" warning dialog**, which no IME can
  remove; what is controllable: preparing the user for it, and pulling the user back out of
  the system settings afterwards (FlorisBoard's CLEAR_TOP relaunch). No published IME funnel
  numbers exist; general retention data says activation in the first visit carries the
  product.
- **Coach marks and modals are weak teaching tools** (NN/g): single focus, moment of
  relevance, never chained; a dismissed modal is permanently spent.
- **Glide specifics**: the visible trail is the learning mechanism (SHARK2's "transient
  ink"); live preview during the gesture measurably lowers error (Kristensson & Zhai CHI
  2007); SwiftKey Flow's "lift when you see the word" is the same idea. Rigid
  velocity/time-window gesture recognizers exclude users with tremor (Montague et al.,
  ASSETS 2014). Glide is a sighted-user feature by construction — TalkBack dispatches
  synthetic taps, so the accessible path is tap + spoken feedback, which we have.
- **Privacy UX**: DuckDuckGo's Fire Button lesson — a privacy control that lives only in
  settings does not build daily trust; Gboard shows an in-strip incognito icon. NN/g's
  credibility factors say claims need outside validation: open source, reproducible builds,
  F-Droid's zero anti-flags, Play's "no data collected" badge — all available to us free.
- **Fifth row**: three defensible orders exist, not two — our current pair order
  (`ә ө ү җ ң һ`), BRIEF's frequency order (`ә ү ң ө җ һ`, which puts the most frequent `ә`
  on the *edge* — position-cost models say center is cheapest), and the incumbent order
  (`һ ө ә ү ң җ`) carried by the desktop Windows Tatar layout and the official
  Tatarstan keyboard app (100k+ installs). An A/B of only the first two risks a winner that
  matches nobody's habits.
- **Multilingual**: Gboard's globe-cycle + long-press-space picker is the global default
  mental model; Yandex's spacebar-swipe-for-language conflicts with our cursor gesture
  (rejected); SwiftKey's merged multilingual predictions fit same-script pairs like tt+ru but
  iOS 18's auto-bilingual backlash shows wrong-language merges anger heavy code-switchers;
  iOS remembers language per conversation. Accidental globe taps are a mass-market complaint
  (Google added a globe-hiding option).

## Decision memos (ranked within each area)

### Strip and autocorrect

**UX1 — Inline revert cell while the autocorrect undo window is live.** Today undo is
backspace-only and invisible. While `RevertWindow` holds a revertable replacement, the strip
paints the typed word as a tappable "keep-typed" cell (the iOS 17 pattern adapted to an IME —
we cannot underline host text). Gate: lab task "undo the planted wrong correction" — fraction
reverting within 30 s, with vs without the cell (N≈12); JVM + device tests. The single
angriest failure case made recoverable.
**UX2 — Persist refused corrections.** Undo today suppresses re-correction for the field
session only. Persist a capped per-language refused list (Apple's documented "reject a few
times and we stop suggesting"). Guarded by the autocorrect false-trigger gate from
`research/prediction-engine.md` (harness prerequisite 2) so the negative signal cannot grow
false fires.
**UX3 — Confidence-gated next-word display; completion stays always-on.** Show idle-state
predictions only above a margin; the ambiverted design (Quinn & Zhai) applied per-frame.
Harness: extend `suggest_eval.py` with shown-vs-hidden simulation and strip precision
(fraction of shown frames with a usable candidate); pre-register the precision/coverage
trade.
**UX4 — Pin strip-order stability as a contract test.** The no-reorder rules (companion
appends only, blank-until-fresh) currently live in comments. JVM test: a surviving cell keeps
its position between keystrokes of one word.
**UX5 — Keep exactly three cells; style the typed-word cell in quotes** (iOS convention for
the reject option; decide `«»` vs `""` per locale; watch TalkBack reading).

### Glide UX

**UX6 — Show glide alternates and refused-glide candidates even with suggestions off.**
Today the default configuration (suggestions master switch off) commits glides with zero
visible alternates and only a haptic tick on refusal. The alternates are corrections of typed
text, not predictions — gate them on the glide switch instead. The biggest default-config UX
gap; small policy branch + tests.
**UX7 — Undo returns the rescue path.** After a whole-word glide undo, re-bind that gesture's
remaining candidates to the empty position. Answers Reyal's most-cited complaint ("have to
start from the beginning") with a two-tap recovery.
**UX8 — Trail contrast per theme.** The single light-trail color at reduced alpha reads well
on dark and poorly on light; SHARK2's learning-by-tracing theory says the ink is the primary
teacher and the strongest passive discovery signal. Per-theme color constants; pixel-contrast
probe in the device test; zero draw-loop changes.
**UX9 — Mid-gesture live candidate, throttled and speed-gated (do last).** Decode on speed
minima or a ≥100 ms cadence and preview top-1 in the strip (CHI 2007's measured error
reduction; SwiftKey's "lift when you see the word"). Gate on the decoder-confidence work
(`research/glide-typing.md` G1–G3) — a live preview of a *bad* decode teaches distrust — and
on device p95/frame budgets.
**UX10 — Motor-accessibility floor for the arming detector.** Test the decider against
synthetic slow/tremor traces (new generator class); relax the time window when the system's
touch-and-hold accessibility delay is long. Document that glide is not a TalkBack feature.

### Onboarding and discovery

**UX11 — Pre-arm the system warning.** Step 1 copy: Android warns about *every* third-party
keyboard; this one is fully offline. Nobody pre-explains the scariest dialog in the funnel.
Strings only; lab A/B on cancels-at-dialog (watch the backfire risk — naming data collection
may raise anxiety; measure before shipping).
**UX12 — Auto-return from system settings after enable** (FlorisBoard's lifecycle-bound poll
+ CLEAR_TOP relaunch), plus a status variant of the launcher screen when setup is already
complete. Must be device-tested on HyperOS (task-manager quirks).
**UX13 — Onboarding invites a glide, not just a tap.** The try-it field's hint currently
implies tapping; change the done-screen copy to invite sliding over `сәләм` (optional: a
small animated demo on the done card, activity-scoped). Pre-registered lab gate: does the
participant glide unprompted in the first session.
**UX14 — One-shot, non-modal discovery paths.** A recoverable "recommended" marker on the
settings suggestions row for users who dismissed the offer modal (never re-show the modal —
NN/g and our spent-flag discipline); a one-shot glide nudge for users with ~200 tap-only
words and zero glides; a first-autocorrect hint teaching backspace-undo. All reuse the
one-shot offer pattern (device-protected flag, written before show).
**UX15 — Store listing carries the discovery load** (Gboard's model): glide typing, the two
recovery tricks, and the offline claim in `metadata/{en-US,ru-RU,tt}`.

### Emoji and privacy UX

**UX16 — Show pause-learning state in the keyboard UI.** A small strip marker while incognito
is on (Gboard's pattern; DuckDuckGo's visible-control lesson). One tap opens the row. Off
state shows nothing — no nagging. Lab gate: ≥75% of participants can enable and verify
learning is paused, unaided (expected ~0% today).
**UX17 — Persist the skin-tone choice** as the grid default (iOS/Gboard parity); stored in
the excluded prefs, read during panel preparation, never on the cold-start path. PRIVACY.md
gains one line.
**UX18 — Group privacy controls into one settings destination** (pause learning, Saved
words, clear recents, policy link) with per-feature explanations; the transparency we already
exceed competitors at becomes findable.
**UX19 — Free trust signals:** Play Data safety "no data collected" matching PRIVACY.md,
F-Droid listing with zero anti-flags, a Saved-words screenshot (synthetic words only), a
reproducible-builds line in the listing. NN/g's fourth credibility factor — claims validated
outside our own UI.
**Keep as-is:** emoji cell add-only (never replace the word), parked no-trailing-space emoji
suggestion, tabs+swipe panel hybrid.

### Multilingual and layout

**UX20 — Fifth-row A/B/C lab protocol (closes the BRIEF open question).** Three arms: current
pair order, frequency order, incumbent/desktop scan order. N=24 (Latin-square), native/L2
Tatar writers recruited via the KFU/community channels; transcription of natural-frequency
sentences plus an extra-letter-dense set; block-1 learnability vs block-5 ceiling both
measured (WPM, MSD error, KSPC, SUS/TLX, forced-choice ranking). Lab-build instrumentation:
key codes + timestamps only, never text, pulled by adb. Pre-registered gate: challenger wins
only with median WPM ≥5% better in the final block, MSD not worse, ≥60% rank-first; tiebreak
is first-session success of new Tatar typists (the product's mission).
**UX21 — Per-app language memory.** Remember the last subtype per app (iOS's per-conversation
precedent); local, clearable from the personal-data screen, documented in PRIVACY.md;
`hintLocales` still wins when present. Lab: alternating tt/ru chat tasks.
**UX22 — Hide the globe key when only one subtype is enabled** (pre-empts the documented
accidental-globe complaints; AOSP/Gboard align).
**UX23 — Code-switched tt/ru eval set (research track).** Build the mixed set and measure
wrong-language strip service before *any* merged-prediction commitment (merged dictionaries
threaten the PSS ceiling and cold start; iOS 18's backlash is the cautionary tale).
**UX24 — Fold discoverability probes into the lab sessions**: digits long-press vs number
row, `ъ`/`ё` long-press, "switch to Russian" without instructions (globe tap vs long-press
space vs settings), and a small TalkBack arm for the six Tatar letters' spoken names.

## The standing instrument

Every memo above that touches human behavior gates on the same lab protocol (per the
measurement framework, which has no telemetry): moderated sessions on the reference device,
5–24 participants per study from the Tatar-speaking community, scripted transcription plus
free composition, screen recording for glance counting, success-rate and time metrics,
pre-registered pass rules. Recruitment is the binding constraint; language-light tasks can
run with Russian-speaking participants on the emulator profile.

## Risks and open questions

- All quantitative UX deltas above come from English-language studies; Tatar morphology
  shifts hit rates, not (presumably) attention economics — treat magnitudes as directional.
- No competitor store-review mining was possible from this network (Reddit/Play blocked); a
  manual pass over ru-language reviews of Gboard/Yandex/the official Tatar app remains open
  and needs a human.
- The undo-hint and incognito-marker designs must survive review against dark-pattern
  discipline: one-shot, dismissible, never nagging; the marker reveals state on a shared
  screen, so it stays small and keyboard-scoped.
- Open: what share of users keeps suggestions off (unknowable without telemetry — defines
  UX6's blast radius); whether the revert strip (UX1) displacing next-word predictions for
  the window's lifetime is a net win (lab decides, not taste).
