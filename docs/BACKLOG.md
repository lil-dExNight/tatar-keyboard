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
  suggestion index.
