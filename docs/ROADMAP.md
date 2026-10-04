# Roadmap

The mandatory development plan after 3.9.1. Every item here must be done, or closed by an
explicit decision recorded in this file, before new features are started. Work goes in the
order of the sections. Delete an item when it is done, since the change itself is the record.
Delete this file when it is empty and list it in `docs/HISTORY.md`.

When every section below is done or closed, there are no open debts: the release is published
everywhere it is meant to be, the prediction program is finished, and the store submissions are
unblocked.

Tatar-first is the priority rule for every item below: Tatar quality and the Tatar layout
lead; Russian and English ride the shared mechanisms.

## 1. Publication of 3.9.x

Manual steps that close the current releases (details in `HANDOFF.md` → "Open release steps",
procedure in `docs/PUBLISH-CHECKLIST.md`):

1. The **3.9.1 GitHub Release** through the web UI: tag `v3.9.1`, title `Tatar Keyboard 3.9.1`,
   notes from `dist/release-notes-3.9.1.md`, attachments `dist/tatar-keyboard-3.9.1.apk` and
   `dist/release-check-3.9.1.txt` (the verified files, never a rebuild).
2. The **store uploads**: 3.9.0 with `metadata/{en-US,ru-RU,tt}/changelogs/46.txt` (if still
   pending) and 3.9.1 with `changelogs/47.txt`.

## 2. Prediction and glide quality (the improvement program remainder)

The program, its tiers and the per-item gates live in `research/README.md`; every change here
ships only through the pre-registered measurement bars (eval harness in `scripts/suggest_eval.py`
and the JVM suites). Work goes in this order:

1. **corpus.tatar permission letter.** The letter is prepared and awaits the operator's send to
   tatcorpus@gmail.com (`dist/corpus-tatar-letter.md`; drafted from the unsent draft archived in
   commit `c7f6c50b`). Ingest the frequency lists if permission arrives.
2. **Optional follow-ups**: the case-aware stem expansion keeps its pre-registered bar; a bigger
   conversational stream (Common Voice `tt`, CC0) is the natural next bigram arm now that the
   conv-upweight landed; the earlier rejections are archived in `docs/HISTORY.md`.

## 3. Device work

Checks that need a person or hardware not at hand (procedure: `docs/DEVICE-TEST-PLAN.md`):

- inline autofill against a real autofill service (install one, e.g. Bitwarden, on a test
  device; a stock device has none);
- the revert cell under TalkBack, the reduced-motion and haptic feel checks;
- live Direct Boot (type the screen-lock PIN with this keyboard before the first unlock);
- Telegram (typing, suggestions, autocorrect undo, glide spacing, emoji panel);
- TalkBack by ear (the spoken key descriptions, language announcement and digit popups);
- tablet layout on tablet hardware (verified on an emulator tablet profile only).

## 4. Store readiness (RuStore, Google Play)

Blocked until sections 1–3 are done; the operator drives these:

- **Corpus-license review.** Use of all surveyed sources was accepted for now with the review
  explicitly deferred (`research/prediction-engine.md`); the review must happen before any
  store publication. Any new source from section 2 joins the review.
- **Store listings.** `metadata/{en-US,ru-RU,tt}/` review and a screenshot refresh if the
  settings screens changed since the shots.
- **Submissions.** RuStore; Google Play closed testing (12 testers for 14 days) per `BRIEF.md`.

## Closed since the last plan

- The pair-conditional glide rerank (the A6 follow-up): measured and rejected — the mined-pair
  firing rule is a no-op (it fires on almost every context row and moves zero rows against the
  blanket channel at equal penalty, on the calibration set and on the real-gesture slice), and
  the held-out gain stays at the blanket's one row against the +1.0 pp bar. The record, with the
  matched-domain ceiling finding for the bigram channel, is in `research/glide-typing.md`.
- The fifth-row key order: fixed as alphabetical `ә ө ү җ ң һ` by operator decision, no A/B
  study; the lab instrument is removed (`BRIEF.md`).
- The settings/onboarding UX batch (3.9.1): direct-to-settings launch, the Classic theme name,
  the merged legal screen, the data-sources screen removed.
- Russian dictionary composition admission: measured and rejected — +0.22 pp held-out coverage
  against the +0.5 pp bar, and the format byte budgets bind before the candidate pool runs out
  (the register entry is in `research/prediction-engine.md`, the tooling in
  `research/corpus/ru_tail_admission.py`).
- Tatar bigram training went conv-heavier (the conversational stream counts tenfold): chain
  top-3 +1.31 pp — landed, the table is rebuilt and re-pinned. The Glot500 arms passed the ship
  bar but were dominated by the conv-only arm and are not landed; the Glot500 dictionary bonus
  frequencies are rejected (verdicts in `research/prediction-engine.md`).
