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

1. **Measurement prerequisites** — the eval-harness upgrades (composite next-word hit
   measurement, typo-mutated held-out set, Russian held-out set, decontamination, bootstrap
   CIs, keystroke-savings simulator), the glide generator's recalibration against real
   gesture corpora, the device observability legs (PSS split, suggestion round trip, battery,
   uiMode/font-scale probes), and the dev-loop speedups. (`research/prediction-engine.md`,
   `research/glide-typing.md`, `research/optimization.md`)
2. **Quick wins** — default-config glide recovery (alternates and refusals visible with
   suggestions off; undo returns the candidate list; trail contrast per theme), the three
   contrast fixes with a contract test, balloon clamping, reduced-motion gating, onboarding
   polish, word-delete gesture, shift-cycles-case, the text-editing menu.
   (`research/ux.md`, `research/ui.md`, `research/competitor-features.md`)
3. **Prediction and glide quality** — wider typo classes (insertion/deletion/transposition),
   the stem-keyed bigram backoff (offline simulation first), corpus ingestion (HPLT, MADLAD,
   Wikipedia, Taiga, the corpus.tatar frequency lists — see the licensing note below), glide
   decoder work (confidence-aware commit, speed-adaptive weighting, the bigram channel,
   endpoint pruning, per-language constants). (`research/prediction-engine.md`,
   `research/glide-typing.md`)
4. **Features** — text shortcuts (abbreviation → expansion), the in-memory recent-clip cell
   (RAM only, never stored), dynamic-color theme variant, inline autofill, backup/export of
   learned data, one-handed mode, the inline autocorrect-revert cell, persistent refused
   corrections. (`research/competitor-features.md`, `research/ui.md`, `research/ux.md`)
5. **The lab program** — the fifth-row A/B/C protocol and the standing lab instrument; needs
   Tatar-speaking participants. (`research/ux.md`)

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

- live Direct Boot (needs a screen-lock PIN and a reboot: type the PIN with this keyboard
  before the first unlock);
- Telegram (typing, suggestions, autocorrect undo, glide spacing, emoji panel; the app is not
  on the test device);
- TalkBack by ear (the spoken key descriptions, language announcement and digit popups; their
  text is verified, the speech is not);
- tablet layout on tablet hardware (verified on an emulator tablet profile only).
