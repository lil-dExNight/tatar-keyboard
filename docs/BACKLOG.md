# Backlog

Parked and rejected ideas only. Open work is in `docs/ROADMAP.md`. When an idea here is taken up,
move it to the roadmap.

## Parked decisions

- **Emoji suggestion under the cursor without a trailing space.** Deferred; weigh against user
  demand after publication.
- **Lossy frequency compression in the dictionaries.** Not taken; reconsider only under APK size
  pressure.
- **Glide context rerank by bigram successors.** Measured below the decision rule's held-out
  gain; the operator confirmed the threshold — closed, do not reopen without new data. A
  pair-conditional variant may still be tested as part of the glide bigram channel work
  (`research/glide-typing.md`).
- **Voice input, any form** (in-app recognition and the delegation mic key). Declined by the
  operator; the exclusion in `BRIEF.md` stands unchanged. Analysis in
  `research/voice-input.md`; revisit only if a quality Tatar ASR model ever exists.
- **Persistent clipboard history pane.** Declined (privacy identity); the in-memory
  recent-clip cell and text shortcuts are on the roadmap instead (`docs/ROADMAP.md`).
- Rejected with measurements, do not reopen without new data: trigram prediction, two-edit typo
  recovery, geometric-neighbor typo recovery, extending autocorrect, sharding the emoji
  suggestion index, stem-keyed bigram backoff (simulated at +0.46 pp chain top-3 against the
  +1.5 pp bar; the case-blind form expansion converts only 58% of stem hits —
  `research/corpus/sim_stem_backoff.py`; a case-aware expansion is a different experiment).
- **Wider typo classes ranked by (class, DL distance, frequency) below or between the existing
  classes.** Measured rejection: on the typo-mutated held-out set, ranking the wide classes
  below class #4 recovers deletion only to 51% (bar: 70%) — the empty-exact discipline and the
  three-cell strip structurally lock deletion out; ranking deletion between #1 and #4 recovers
  it to 77% but regresses substitution 91.2% → 88.1% (bar: no regression), because a DL-1
  deletion candidate and a DL-1 substitution candidate are rank-indistinguishable. The open
  follow-up (a different pre-registered experiment, not a rerun): a shared DL-1 tier for
  classes #2/#4 candidates ranked among themselves by frequency, continuations after.
- **Confidence-aware glide commit by the geometric score alone.** Measured rejection: on the
  held-out set the garbage-refusal / normal-refusal frontier never meets the pre-registered
  (≥ 80%, ≤ 5%) corner — about an eighth of garbage rows trace a real word too well, and the
  noisy tail of normal rows overlaps them; the runner-up margin carries no signal either
  (`research/glide-typing.md` G3). The dwell channel (G11) is the better signal.
- **Glide endpoint pruning n=2→3.** Measured rejection: held-out top-1/top-3 improve
  (+1.3/+2.7 pp) but the host p95 doubles past the 2 ms budget — the largest endpoint buckets
  hold thousands of entries; `research/glide-typing.md` G6 carries the tuning attempts.
- **Bigram retraining on the ingested corpora (HPLT/MADLAD/tt.wikipedia sentences).** Measured
  rejection: +0.16 pp chain top-3 against the +0.3 pp ship bar (paired CI contains zero; no
  regression), plus a domain-skew flag on a high-traffic head; the conv-heavier remix is the
  registered follow-up (`research/corpus/tt_extra_sentences.py` keeps the tooling).
- **Taiga social frequencies into the Russian dictionary.** Measured rejection: +0.018 pp
  held-out eval coverage against the +0.5 pp bar — the Russian dictionary is saturated
  (97.5% token coverage) and reranking inside a fixed composition cannot reach the uncovered
  tail; composition admission through the accept queue is the follow-up
  (`research/corpus/ru_merge_sim.py`).
