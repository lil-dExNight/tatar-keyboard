# EMOJI-LEARN — personal learned word→emoji co-usage

Date: 2026-09-28. Status: implemented, all gates green including device
verification (2026-09-28 — see the last sections).

## Motivation

Operator request, 2026-09-28: after «хәйерле иртә» the user prefers ☀️, while
the shipped static table answers 🌅. The static `emoji_suggest_v1.txt` table
is curated and frozen; respecting an individual habit means learning it
on-device. This mission adds the third personal store — word→emoji co-usage —
as a deliberate sibling of the words (`.tpers`) and pairs (`.tpersb`) stores,
with the same privacy architecture, the same executor, and the same gates.

## Design

- **Storage.** Per-language store `personal-emoji-<subtypeTag>-s1-f1.tpersem`
  (magic `TATPERSE`, schema 1), a SHA-256-checksummed binary with a
  fail-closed validator, atomic writes, and quarantine + 1-byte notice flag
  on an unreadable file. Side files: `pending-emoji-<tag>-s1-f1.bin` (salted
  truncated SHA-256 pending hashes) and `salt-emoji.bin` (a 16-byte
  SecureRandom salt, created on first use, destroyed by erase-all).
  Everything lives under `noBackupFilesDir/personal/` — excluded from backup
  by construction, credential-encrypted, unlock-gated, exactly like the words
  and pairs stores.
- **Caps.** 500 entries LRU per language, a 64 KiB file cap, word 1–24 code
  points (stored normalized — it is the lookup key), emoji cluster ≤ 32
  UTF-16 units (stored raw — a cluster has no casing to lose).
- **Threshold.** One observation writes only a salted pending hash; the
  second clean observation graduates the pair into the store.
- **Learning events — exactly three, all the keyboard's own insertions.**
  (1) an emoji picked in the emoji panel, (2) an emoji picked in emoji
  search, (3) a tap on the suggestion strip's emoji tail cell — each counted
  only when the emoji lands right after a committed word. Pasted text never
  reaches these paths: the clipboard teaches nothing. A tap on a *learned*
  cell additionally bumps its usage counter (the acceptance half of the
  ranking).
- **Offer.** The learned emoji replaces the static table entry only as the
  tail-cell leader; it is offered, never auto-inserted — it reaches the text
  only by tap. Gates, in order: master suggestions toggle → emoji-suggestions
  toggle → personal-dictionary toggle (read live on every lookup). Incognito
  pauses ALL learning writes — observations, pending hashes and the
  session-end flush — but never reads.
- **Ranking.** Learned entries first, then usage (accepted-cell taps)
  descending, then frequency (observations) descending.
- **UI.** The personal-dictionary screen («Шәхси сүзлек») gains a third card
  per language listing every learned word → emoji with its counters; per-row
  forget dialog, per-language «Clear all learned emoji», and erase-all now
  covers words + pairs + emoji with their pending files and salts. An
  unreadable file produces a quarantine card with restore/discard. Dialogs
  naming user content are FLAG_SECUREd.

## Deliberate non-goals

- No auto-replace: the learned emoji is only ever the tail cell's leader,
  never an automatic substitution.
- No writes while any gate in the chain is off; with the personal-dictionary
  toggle off the feature costs the suggestion path one boolean read.
- The clipboard never teaches: paste bypasses all three insertion points by
  construction.
- No sync, no export, no backup — like every personal store.

## Implementation notes

- The format (`TpersemFormat`, magic `TATPERSE`) mirrors `.tpersb` field for
  field in the header and the checksum convention, with a deliberately
  different magic, extension and file name: a reader written for one must
  never open the other. Record: u8 word length, u8 emoji length, u16
  usageCount (accepted taps), u16 frequencyCount (observations), u32
  lastUseSerial; payload strictly ascending by (normalized word, cluster).
- `PersonalEmojiDictionaries` is the one process-wide owner, serialized on
  the SAME single background executor as the words and pairs stores, so the
  three features cannot race each other's temp files in the shared
  `personal/` directory. Reads go through `SnapshotPersonalEmojiSource`,
  gated live by the personal-dictionary setting (off → `PersonalEmojiDictionary.EMPTY`).
- `PersonalEmojiLearning` is the emoji analogue of `PersonalBigramLearning`:
  the same six-factor predicate (suggestions eligible for the field and the
  subtype, personal dictionary on, device unlocked since boot, not a
  postal-address field, incognito off) gates every method of the sink, the
  flush included; the subtype is resolved per event, so a pick made after a
  mid-session layout switch lands in the right language's store.
- `EmojiTextUtils.extractContextBeforeEmoji` reads the co-usage word from the
  text before the cursor for panel/search picks: the trailing emoji RUN is
  peeled first («сәләм ☀️😊» → сәләм), then the word is read by the engine's
  own two rules — a whitespace ending means the NEXT_WORD context rule,
  anything else the plain trailing-word rule («сәләм☀️» → сәләм).
- Recents double-count fix (B2): the strip's emoji cell tap now records to
  the recent-emoji list, which it never did before. The strip tap is wired to
  a recents-only method (`onStripEmojiInserted`), NOT to `onEmojiInserted`:
  that method additionally reports the pick to the learning sink with a
  context extracted from the editor text, while the strip tap reaches the
  same sink through the controller's `PersonalEmojiSink` seam with the band's
  bound context word — routing one tap through both would count it twice
  whenever the commit appended no auto-space and the cache still ended with
  the emoji.
- The learned-vs-static decision for the tail cell is a live lookup at fill
  time (learned source first, static table as fallback) and again at tap
  time — stateless, so no stale remembered answer can bump the wrong entry.
- `EmojiSuggestIndex` cap raised 4096 → 8192 (headroom for the static table).

## Tests (full JVM suite 1 821, 0 failures)

- Storage, 63: `PersonalEmojiStoreWriteTest` (32) — caps, LRU eviction,
  threshold graduation, counters, atomic writes, quarantine;
  `PersonalEmojiEntriesTest` (12) — record codec; `TpersemValidatorTest`
  (19) — the fail-closed validator over the format matrix.
- Wiring / run / gates: `RichInputMethodManagerExecutorSourceContractTest`
  (4), `PersonalEmojiRunTest` (7), `PersonalEmojiLearningGatesTest` (7 — the
  six-factor predicate over every sink method, flush included).
- Controller: `SuggestionsControllerEmojiSuggestTest` +8 for feature C
  (learned leader over the static entry, hidden while the personal gate is
  off, observation+use on a learned tap, observation-only on a static tap,
  session-end flush) — plus +3 for the B2 recents seam.
- `EmojiTextUtilsTest` +13 — context extraction across the emoji-run peel
  matrix.
- Screen: `PersonalEmojiScreenSourceContractTest` (8) + 5 model tests in
  `PersonalDictionaryScreenSourceContractTest` (emoji in the per-language
  section, search covers the word half, the shared cap across the three
  stores, the row `toString` naming neither the word nor the emoji).

## Gates

- JVM suite: `./gradlew test --rerun-tasks` — 1 826 tests / 190 suites,
  0 failures (final tree, 2026-09-28).
- Python pipeline tests — 507 OK across 16 files (1 pre-existing skip).
- `lintRelease` — green; baseline stands at 0 errors / 29 warnings (the 29th:
  UsableSpace in the emoji-store factory, `PersonalEmojiDictionaries.kt:161`,
  the same class as the two existing entries).
- `scripts/check-no-internet.sh` — green (source manifest and built APK).
- Release size: 1 804 874 B signed (1 800 038 B unsigned, two packs
  byte-identical), budget ≤ 3 145 728 B; `release_check.sh --quick` OVERALL
  PASS 12/12 on the signed pack.

## Device verification (2026-09-28): DONE

**Emulator smoke (`tt_suggest_a14`): 22 PASS / 0 FAIL / 1 SKIP**, including the
new `learned-emoji-tt` probe, which exercises the feature end to end on the
Tatar layout: type «хәйерле иртә», then insert ☀️ twice via emoji search — on
the third round the suggestion strip's tail cell offers ☀️, the learned entry
overriding the static table's 🌅. The probe's epilogue restores the touched
preferences and recents and deletes the learned store, so the AVD is left
clean. Evidence: `build/emulator-smoke-emoji-learn/`.

## Side finding (2026-09-28, not this feature)

Observed during probe calibration: closing the emoji panel/search with BACK
can wedge the IME window on the AVD — the window stays drawn but is dead to
touch (WMS reports `mViewVisibility=GONE` while IMMS still reports it shown).
An emulator-side behaviour, NOT caused by this feature and not in our code
path; the probe sidesteps it by reopening SetupActivity per round. Recorded in
the backlog as item A6.

## Follow-ups (operator)

- Release decision: version bump and CHANGELOG entry are the operator's.
