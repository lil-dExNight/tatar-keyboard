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
3. The **3.9.1 cold-start record**: the `cold` leg of `scripts/device-perf-ritual.sh` on the
   reference device, recorded in the release record (pending hardware).

## 2. Prediction and glide quality (the improvement program remainder)

The program, its tiers and the per-item gates live in `research/README.md`; every change here
ships only through the pre-registered measurement bars (eval harness in `scripts/suggest_eval.py`
and the JVM suites). Work goes in this order:

1. **Glot500 ingestion + conv-heavier bigram remix.** Glot500 `tat_Cyrl` (verified: ~4.7M
   sentence-level rows, openly downloadable from HuggingFace) is the next source: per-word bonus
   frequencies for the Tatar dictionary ranking with the Russian-bleed filter, and a
   sentence stream for the bigram training with a heavier conversational weight. Fallback
   sources if access breaks: CulturaX `tt` or the community OSCAR mirror (survey of
   2026-10-04); TatarNLPWorld v3 only with category filtering. Register diversity for bigrams:
   Common Voice `tt` validated sentences (CC0). Then re-measure the conv-heavier remix against
   its ship bar (the first attempt was measured and rejected; the archived register is
   in `docs/HISTORY.md`).
2. **Russian dictionary composition admission.** The rerank is saturated; admit new words to
   the Russian dictionary tail through the accept queue (`scripts/dict_accept.py`), measured on
   the held-out Russian eval set.
3. **corpus.tatar permission letter.** Recover the unsent draft from commit `c7f6c50b` (its
   archived path is listed in `docs/HISTORY.md`), update it, and hand it to the operator to
   send to tatcorpus@gmail.com; ingest the frequency lists if permission arrives. Nothing has
   ever been sent.
4. **Optional, only after 1–2 land**: the pair-conditional glide rerank variant and the
   case-aware stem expansion — both keep their pre-registered bars; the earlier rejections
   are archived in `docs/HISTORY.md`.

## 3. Device work

Checks that need a person or hardware not at hand (procedure: `docs/DEVICE-TEST-PLAN.md`):

- inline autofill against a real autofill service (install one, e.g. Bitwarden, on a test
  device; a stock device has none);
- the release-build perf legs and the 3.9.1 cold-start record (item 1.3);
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

- The fifth-row key order: fixed as alphabetical `ә ө ү җ ң һ` by operator decision, no A/B
  study; the lab instrument is removed (`BRIEF.md`).
- The settings/onboarding UX batch (3.9.1): direct-to-settings launch, the Classic theme name,
  the merged legal screen, the data-sources screen removed.
