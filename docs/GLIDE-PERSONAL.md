# GLIDE-PERSONAL — personal-dictionary glide candidates

Date: 2026-09-26. Status: implemented, all gates green on the host; the device
latency leg (C5 re-measurement on the POCO C71) is the operator's.

## Motivation

Until this mission the glide decoder scored candidates from the shipped
dictionary only; personal-dictionary words (the TPERS store) were invisible to
glide — a documented MVP exclusion (`docs/GLIDE-PLAN.md` scope line,
`docs/ROADMAP-P7.md` P7-3 section, both corrected by dated footnotes). The
prefix path has merged personal candidates since E4b (the three-class merge),
and the words a user saves — names, slang, rare forms — are exactly the words
they glide-type. This mission closes the gap with no algorithm change.

## Design

One new inventory, zero decoder changes:

- `latin/glide/CompositeGlideInventory.kt` wraps the base
  `GlideWordInventory` and appends the personal snapshot's entries after the
  dictionary entries. `GlideWordIndex.build` indexes them untouched — the CSR
  buckets, the frequency-first visit order and the fail-fast bounds are all
  unchanged.
- `GlideDecoderHost` reads the personal source's current immutable snapshot
  once per decode (one `@Volatile` hop) and rebuilds the decoder when the
  geometry OR the snapshot identity changed — one rebuild per learning event,
  paid on the engine worker, exactly like the existing geometry rebuild.
- Gating is inherited, not re-implemented: the production source
  (`PersonalDictionaries.sourceFor`) already answers
  `PersonalDictionary.EMPTY` when the personal-dictionary setting is off (and
  incognito pauses the learning that would republish snapshots), and an empty
  snapshot keeps the base inventory unwrapped — byte-identical to the
  pre-personal decode. `PersonalCandidateSource` gains a default
  `glideSnapshot()` so the seam stays a `fun interface` and sources written
  before this mission keep compiling.
- Wiring: `MappedDictionaryEngine.start` passes the same
  `personalCandidates` seam the prefix merge already reads, plus
  `index::containsWordCold` as the duplicate check. No new `start()` parameter,
  no `EngineHandle`/`LatinIME` change.

## Ranking invariants

- Personal words never change the relative ranking of dictionary words. They
  append after the base entries, and their synthetic frequencies are capped at
  the dictionary maximum, so `GlideWordIndex.maxFrequency` — the frequency
  channel's normalizer — is unchanged.
- Per personal word: `frequency = max(1, baseMax × usageCount / maxUsageCount)`.
  `baseMax` is learned during the base walk of the same `forEachWord` pass; the
  user's most-used word ties the dictionary's strongest prior, and a word seen
  once ranks on its shape alone. Shape dominates by construction: the frequency
  channel can lift a personal word only among same-bucket candidates, never
  past a clear shape match (pinned by
  `GlideDecoderPersonalTest.anUnrelatedHighUsagePersonalWordDoesNotHijackAClearDictionaryGesture`).
- Duplicates: a personal entry whose normalized form is already a dictionary
  word is not indexed twice. When the saved raw form differs (the user's own
  casing), a `normalized → rawForm` override map makes `wordAt` of the
  DICTIONARY entry return the saved casing — one candidate cell, the user's
  spelling, mirroring E4b's «словарное гүзәл + личное Гүзәл → one cell Гүзәл».
- The index walk (`forEachWord`) feeds the normalized form; `wordAt` serves
  the raw saved spelling, so the display-time casing pass of `applyGlideResult`
  (shift-gated INITIAL_CAPS/LOWER) treats a personal word like any candidate.
- Learning semantics are unchanged: a glide commit moves no usage counter
  (P7-3's "glide is not a learning event" stands; `docs/ROADMAP-P7.md`).

## Privacy and contracts

- `CompositeGlideInventory` is not a data class and declares no `toString`:
  the arrays carry the user's words, which must never reach a log.
- `latin/glide/` stays Android-free, log-free and Cyrillic-literal-free —
  `GlideSourceContractTest` covers the new file automatically (it walks the
  package).
- No new assets, no pipeline change, no pins touched: personal words are
  runtime data. `scripts/glide_pack.py` is untouched.

## Memory and latency

- Memory gate: ≤ +256 KiB worst case — the format cap of 2 000 entries at the
  maximum 24 code points, all mappable, adds exactly 88 000 B (~44 B of flat
  arrays per entry) to the word index, measured and pinned by
  `CompositeGlideInventoryTest.aFullPersonalDictionaryStaysWithinTheMemoryBudget`.
- Latency: the per-gesture cost of an unchanged personal dictionary is one
  reference read and an identity comparison; the rebuild (a composite walk of
  ≤ 2 000 entries plus the word-index rebuild) happens once per learning event
  on the engine worker. The C5 device leg — re-measure the per-gesture decode
  on the POCO C71 (p95 ≤ 52.5 ms, the operator-accepted bound of the P7-4
  iteration) with a warm personal dictionary — is the operator's.

## Tests (+21 JVM)

- `latin/glide/CompositeGlideInventoryTest` (10): append order, personal
  order (usage-descending), the exact frequency formula (cap, scaling, floor
  of 1), unchanged `maxFrequency`, duplicate skip + casing override via
  `wordAt`, duplicate saved exactly as the dictionary form, raw-vs-normalized
  serving, unmappable-letter personal words skipped fail-closed through a real
  `GlideWordIndex.build`, empty snapshot adds nothing, the 2 000 × 24-cp
  memory pin.
- `latin/glide/GlideDecoderPersonalTest` (6): a personal-only word decodes
  top-1 on its ideal path, the result carries the saved casing, an unrelated
  high-usage personal word does not hijack a clear dictionary gesture, a close
  low-usage personal word rides the results without passing the dictionary
  word, the dictionary relative order is unchanged with a personal tail,
  determinism across repeated decodes and rebuilds.
- `MappedDictionaryEngineGlideTest` (+4): a learned word decodes through the
  engine worker; a snapshot swap is seen by the next glide; an emptied
  snapshot removes the word; an idle index release rebuilds with the current
  snapshot.
- `GlideEndToEndTest` (+1): over the real shipped assets, a learned word's
  gesture lift-commits it top-1 and the strip shows the decode tail as
  tappable alternatives; a membership guard
  (`assertFalse(tatarIndex.containsWordCold("сәлинә"))`) keeps the pin honest
  across future dictionary rebuilds.
- Kept green: `GlideRecoveryCalibrationTest`, `GlideSourceContractTest`,
  `PersonalDictionaryReadPathPrivacyTest`, `CompositePrefixComputerTest`, the
  full JVM suite.

## Gates

- JVM suite: `gradle test` — 1 691 tests (1 670 + 21 new), 0 failures.
- Python pipeline tests: 16 files, 502 tests, all OK. (Observed drift:
  `AGENTS.md` documents 507; the tree runs 502 today, unchanged by this
  mission — no `scripts/` or `tests/` file was touched.)
- `lintRelease` — green (baseline stands at its 0 errors / 28 warnings).
- `scripts/check-no-internet.sh` — green at BOTH levels (source manifest and
  the built debug APK, backup whitelist included).
- `python3 scripts/rebuild_assets.py --check --allow-known-drift` — `"ok": true`;
  asset pins untouched.
- Release size: `assembleRelease` unsigned APK = 1 766 196 B, budget
  ≤ 3 145 728 B — 1 379 532 B of headroom.

## Draft CHANGELOG entry (for the operator to paste at release time)

```
- Glide typing now knows your personal dictionary: learned words (names,
  slang, rare forms) appear as glide candidates, ranked by shape first and
  your usage count second, never outranking a clear dictionary word. Your own
  casing is kept, and words already in the built-in dictionary are not
  duplicated.
```

## Follow-ups (operator)

- Device leg: C5 re-measurement on the POCO C71 with a learned personal
  dictionary (p95 ≤ 52.5 ms).
- Release decision: version bump and CHANGELOG paste are the operator's.
