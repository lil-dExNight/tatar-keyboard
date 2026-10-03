# Roadmap

The mandatory development plan after 3.8.0. Every item here must be done, or
closed by an explicit decision recorded in this file, before new features are started. Work
goes in the order of the sections. Delete an item when it is done, since the change itself is
the record. Delete this file when it is empty and list it in `docs/HISTORY.md`.

Parked and rejected ideas are in `docs/BACKLOG.md`.

Tatar-first is the priority rule for every item below: Tatar quality and the Tatar layout
lead; Russian and English ride the shared mechanisms.

## 1. Publication

The steps listed in `HANDOFF.md` ("Open release steps") for 3.7.0 and 3.8.0.

## 2. The improvement program

The full program, its tiers and per-item gates live in `research/README.md`; the per-topic
evidence is in the documents it indexes. Work goes in this order:

1. **Prediction and glide quality, continued** — the follow-ups the measurements opened:
   a conv-heavier bigram remix, composition admission for the Russian dictionary tail,
   corpus.tatar frequency lists if the host ever answers. The wider-typo-classes question is
   closed by two bracketing measured rejections (`docs/BACKLOG.md`).
   (`research/prediction-engine.md`, `research/glide-typing.md`)
2. **The lab program** — the fifth-row A/B/C sessions per `docs/LAB-FIFTH-ROW.md` (the
   instrument landed); needs Tatar-speaking participants. (`research/ux.md`)

Decisions recorded for this program:

- The glide context rerank (A6) is closed: the operator confirmed the threshold, the patch
  measured below it, the code stays parked. The pair-conditional variant may still be tested
  as part of the glide bigram channel work.
- Voice input stays excluded entirely: no in-app recognition, and no delegation mic key
  (operator decision; the full analysis is in `research/voice-input.md`).
- Clipboard history stays excluded. In scope instead: text shortcuts and the in-memory
  recent-clip cell (never written to disk). A persistent clipboard pane is declined.
- Corpus licensing: all surveyed corpus and frequency-list sources may be used for now,
  license posture notwithstanding; the review is deferred and deliberately out of this plan.
  Personal-data scraping (social dumps) stays excluded — that is privacy, not licensing.
- Distribution and community work is descoped from this plan.

## 3. Device work

Checks that need a person or hardware not at hand:

- the new perf-ritual legs on the reference device: `suggest` (first real round-trip numbers
  against the 32 ms budget), `battery`, `uimode`, `fontscale` (`--legs`); the uimode probe
  answers the palette-staleness question of `research/ui.md` (UI3);
- the quick-wins device pass: edge-key balloons and edge-clamped more-keys slide selection in
  both themes; glide alternates/undo rescue in the default config; word-delete flick tuning
  (`TRIGGER_KEY_WIDTHS`/`MAX_FLICK_MS` in `WordDeleteFlick.kt`) plus its password-field
  refusal; the shift case-cycle editor matrix; the enter-key editing menu; animator-scale-0
  behavior; emoji long-press haptic against the app toggle; the onboarding auto-return on
  HyperOS;
- the features device pass: the dynamic theme on an API 31+ device across a wallpaper change
  (plus the HyperOS retoning look); one-handed mode in both hands and themes incl.
  `GlideUiDeviceTest` with the mode on and the perf legs; inline autofill against a real
  autofill service (none ships on a stock emulator — a password manager or the AOSP sample);
  the SAF backup round-trip on device; the recent-clip cell's clipboard-read toast behavior on
  Android 12+; the revert cell under TalkBack; a refused correction staying dead across
  sessions;
- the fifth-row lab sessions per `docs/LAB-FIFTH-ROW.md` (recruitment, scripted sessions, the
  analysis script);
- live Direct Boot (needs a screen-lock PIN and a reboot: type the PIN with this keyboard
  before the first unlock);
- Telegram (typing, suggestions, autocorrect undo, glide spacing, emoji panel; the app is not
  on the test device);
- TalkBack by ear (the spoken key descriptions, language announcement and digit popups; their
  text is verified, the speech is not);
- tablet layout on tablet hardware (verified on an emulator tablet profile only).
