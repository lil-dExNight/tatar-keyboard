# Backlog

Parked and rejected ideas only. Open work is in `docs/ROADMAP.md`. When an idea here is taken up,
move it to the roadmap.

## Parked decisions

- **Emoji suggestion under the cursor without a trailing space.** Deferred; weigh against user
  demand after publication.
- **Lossy frequency compression in the dictionaries.** Not taken; reconsider only under APK size
  pressure.
- **Glide context rerank by bigram successors.** Measured below the decision rule's held-out
  gain; code not merged. The operator's decision on the threshold is item 1.2 of
  `docs/ROADMAP.md`.
- Rejected with measurements, do not reopen without new data: trigram prediction, two-edit typo
  recovery, geometric-neighbor typo recovery, extending autocorrect, sharding the emoji
  suggestion index.
