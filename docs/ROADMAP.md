# Roadmap

The mandatory development plan after 3.9.1. Every item here must be done, or
closed by an explicit decision recorded in this file, before new features are started. Work
goes in the order of the sections. Delete an item when it is done, since the change itself is
the record. Delete this file when it is empty and list it in `docs/HISTORY.md`.

Parked and rejected ideas are in `docs/BACKLOG.md`.

Tatar-first is the priority rule for every item below: Tatar quality and the Tatar layout
lead; Russian and English ride the shared mechanisms.

## 1. Publication

The steps listed in `HANDOFF.md` ("Open release steps").

## 2. The improvement program

The full program, its tiers and per-item gates live in `research/README.md`; the per-topic
evidence is in the documents it indexes. The open follow-up:

1. **Prediction and glide quality, continued** — the follow-ups the measurements opened:
   a conv-heavier bigram remix, composition admission for the Russian dictionary tail,
   corpus.tatar frequency lists if the host ever answers. The wider-typo-classes question is
   closed by two bracketing measured rejections (`docs/BACKLOG.md`).
   (`research/prediction-engine.md`, `research/glide-typing.md`)

The fifth-row order question is closed by operator decision: the alphabetical order
`ә ө ү җ ң һ` is final, no A/B study is run (`BRIEF.md`).

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

- the device pass landed on the reference device (its results are in `HANDOFF.md`): the new
  perf-ritual legs ran (suggest round trip in budget, battery clean, palette flip live,
  font-scale labels pixel-identical), the instrumentation suites pass per class, and the
  feature probes verified the strip features, the gestures, the editing menu, one-handed mode,
  the dynamic theme, the lab arm switch and the SAF backup round trip — four real bugs were
  found and fixed by the pass. Still open on hardware: inline autofill against a real autofill
  service (none on a stock device), the release-build perf legs (the release APK cannot
  install next to the signed one), the revert cell under TalkBack, and the reduced-motion and
  haptic feel checks;
- live Direct Boot (needs a screen-lock PIN and a reboot: type the PIN with this keyboard
  before the first unlock);
- Telegram (typing, suggestions, autocorrect undo, glide spacing, emoji panel; the app is not
  on the test device);
- TalkBack by ear (the spoken key descriptions, language announcement and digit popups; their
  text is verified, the speech is not);
- tablet layout on tablet hardware (verified on an emulator tablet profile only).
