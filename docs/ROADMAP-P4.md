# ROADMAP-P4 — phase 4 report: prediction depth (batch A)

Status: batch A (T7, P5a, P5b) gated measurements — verdicts below; implementation only where a
written gate passed. Phase 4 of docs/ROADMAP.md ("Prediction depth"): asset work; every candidate
ships through written calibration gates, each written BEFORE measuring and amendable only with a
dated reason. The Russian table and dictionary stay untouched unless an item says otherwise.

Baseline before the batch: JVM 1 437 tests, python 484, release APK 1 851 944 B; Tatar bigram
table H = 10 132 + 75 extra heads → 10 204 heads, 40 735 pairs, 81 476 B compressed
(budget 250 000 B), eval metrics (TtSuggestEvalTest): head coverage of eval pairs 75.3623 %,
next-word top-3 9.5468 %, covered-conditional 12.6679 %.

## T7 — K=4 decision: the written gate (2026-09-23, BEFORE measuring)

The table stores 4 successors per head and shows 3. Facts to measure on the eval set, over the
eval pairs whose head is covered by the shipped table: the rank at which the TRUE next word sits
in the stored 4 — specifically the share sitting exactly at stored rank 4 (invisible today).

**Decision rule:** if the rank-4 hit share (of covered eval pairs) is ≥ 1.5 % AND a 4-cell strip
variant is judged acceptable within this batch's budget (a full strip-contract change —
SuggestionStripState, the view, casing, companion fill, every affected pin) → implement the
4-cell option in a follow-up item and keep K=4 storage; otherwise repack the Tatar table at K=3
in the same rebuild as P5a and reclaim the ~48.7 KB. A rank-4 share below 1.5 % makes the 4-cell
contract change unjustifiable by itself, and the byte cost of an invisible 4th successor is then
pure waste.

## P5a — more Tatar bigram heads: the written gate (2026-09-23, BEFORE measuring)

Options to measure on the eval set (same harness and metrics as the pinned TtSuggestEvalTest
counters):

- (a) blanket H raise to 15 000 and to 20 000 — table size vs the 250 000 B budget, eval
  head-coverage lift and unconditional next-word hit-rate lift;
- (b) surgical extra-heads: an expanded `bigram_extra_heads_tat.txt` — words frequent in the
  conversational train input (Tatoeba + OpenSubtitles) with in-vocabulary pairs but below the
  H cutoff (the сәләм class: present in the dictionary, not heads today);
- (c) a combination.

**Gate:** implement an option iff, against today's pins, its UNCONDITIONAL next-word hit-rate
lift is ≥ +1.0 pp AND its head-coverage lift is ≥ +2.0 pp on the eval set AND the packed table
fits the 250 000 B compressed budget. The best option is implemented (repack with the K chosen
by T7, pins recalculated, known-drift re-derived, affected JVM pins recalibrated, the on-device
one-time re-inflation consequence of the new table hash recorded). If no option passes, the table
stays as shipped and the numbers stand.

## P5b — trigram offline evaluation: the written gate (2026-09-23, BEFORE measuring)

Pure offline estimate, no runtime code: from the same three Tatar train inputs, build
(w1, w2) → successor counts, take the top ~5 000 pairs with up to 3 successors each (the
plausible table), and project the UNCONDITIONAL next-word hit-rate on the eval set that a
trigram-backed strip would add over the bigram table: eval pairs where the bigram table does not
hit (head uncovered or successor outside the shown top-3) but the trigram table has the true
successor in its top-3.

**Gate:** projected unconditional lift ≥ +2.0 pp at ≤ 250 KB estimated table size → STOP and
report (trigram implementation is a separate sub-phase, NOT this batch). Below that — the
rejection is recorded with numbers.

## Batch A measurements and verdicts (2026-09-23)

### T7 — K=4 decision: repack at K=3 (decided 2026-09-23)

Measured on the eval set over the shipped K=4 table (all 4 stored successors read per head;
4 347 pairs, 3 276 covered):

| Metric | Value |
|---|---:|
| top-3 hits (shown today) | 415 (9.5468 % unconditional, 12.6679 % of covered) |
| rank-1 / rank-2 / rank-3 hits | 241 / 100 / 74 |
| **rank-4 hits (invisible today)** | **68 (2.0757 % of covered, 1.5643 % unconditional)** |
| top-4 hits if shown | 483 (11.1111 % unconditional, 14.7436 % of covered) |

The rank-4 share (2.0757 %) clears the written 1.5 % threshold — the 4th stored successor carries
real value (+1.5643 pp of latent unconditional lift). The gate's second condition decides: a
4-cell strip is a full strip-contract change (`SuggestionStripState.CELL_COUNT`, the engine's
`MAX_RESULTS = 3` cap, the view layout, the merges' room computations, the autocorrect preview's
third-cell rule, companion fill, casing, eval pins, smoke coordinates) — genuinely a sub-phase of
its own, NOT acceptable within this batch's budget as written. Therefore, per the gate: **repack
the Tatar table at K=3 in the same rebuild as P5a**. The reclaim is measured exactly by the C0
candidate (identical H = 10 132 + the same 75 extra heads, identical eval metrics — proof the
shown top-3 is unaffected): **81 476 → 62 267 B compressed (−19 209 B), pairs 40 735 → 30 574,
heads unchanged at 10 204.** The roadmap's "~48.7 KB" estimate was wrong; the real reclaim is
19 209 B. The 4-cell UI keeps its roadmap place with the latent +1.5643 pp recorded here;
re-adding K=4 storage when that item lands is one rebuild with known pins.

### P5b — trigram offline evaluation: REJECTED (2026-09-23)

Trigram counts built from the same three train inputs (6 305 723 distinct (w1, w2) contexts);
the plausible table = top-N contexts by count with up to 3 successors each, bigram successors
first (the natural merge — a trigram hit counts only where the bigram table misses). The lift is
quoted on the PAIR denominator of the bigram next-word metric (4 347 pairs), the same
denominator the written gate's hit-rate uses:

| Top-N contexts | Extra hits (bigram missed, trigram hit) | Lift (pairs) | Estimated size |
|---|---:|---:|---:|
| 2 000 | 12 | +0.28 pp | ~26 KB raw |
| 5 000 | 28 | +0.64 pp | ~66 KB raw |
| 10 000 | 42 | +0.97 pp | ~132 KB raw |
| 15 000 | 50 | +1.15 pp | ~197 KB raw |
| 20 000 | 60 | +1.38 pp | ~263 KB raw |
| 30 000 | 71 | +1.63 pp | ~395 KB raw (~240 KB compressed) |

Gate: projected unconditional lift ≥ +2.0 pp at ≤ 250 KB. **BELOW at every feasible size** —
even 30 000 contexts (already past the budget raw) project +1.63 pp. The bottleneck is
structural: only ~17 % of bigram-missed eval pairs even have their (w0, w1) context in a 5 000-
or 30 000-context table (286–522 of 2 998). The rejection is recorded; no runtime code was
written and none is scheduled (a trigram mission would need a context-coverage story first).

### P5a — more Tatar bigram heads: option (b) SHIPPED (surgical EXPAND-1 extra-heads, 2026-09-23)

All candidates packed from the same three train inputs (2 × Leipzig 1M + tt_conv_train90) with
the T7-chosen K = 3, measured on the eval set (4 347 pairs) against today's pins:

| Option | Heads | Compressed B | Coverage (lift) | Hit % (lift) | Gate |
|---|---:|---:|---:|---:|---|
| current (K = 4, shipped) | 10 204 | 81 476 | 75.3623 (—) | 9.5468 (—) | — |
| C0: H = 10 132, today's 75 extras (the T7 baseline) | 10 204 | 62 267 | 75.3623 (0) | 9.5468 (0) | — |
| C1: H = 15 000 | 15 053 | 90 925 | 80.9294 (+5.57) | 10.3290 (+0.78) | FAIL (hit < +1.0) |
| C2: H = 20 000 | 20 021 | 120 499 | 83.5519 (+8.19) | 10.6740 (+1.13) | PASS |
| C4: surgical extras T = 20 | 11 170 | 67 863 | 80.8144 (+5.45) | 10.2139 (+0.67) | FAIL (hit < +1.0) |
| **C3b: surgical extras T = 10 (SHIPPED)** | **13 154** | **79 574** | **84.1730 (+8.81)** | **10.8351 (+1.29)** | **PASS** |
| C5: combination H = 20 000 + T = 10 | 21 772 | 130 618 | 86.9795 (+11.62) | 11.2031 (+1.66) | PASS |

The written gate (lift ≥ +1.0 pp hit AND ≥ +2.0 pp coverage AND ≤ 250 000 B) passes for C2,
C3b and C5. **C3b is the best option and the shipped one**: it dominates C2 on every axis (more
hit lift, more coverage lift, SMALLER than the current table — the extra-heads mechanism exists
for exactly this genre gap); C5's further +0.37 pp hit / +2.81 pp coverage costs +51 044 B
(64 % over C3b) against the byte discipline and is recorded, not shipped. C1 and C4 failed the
hit side of the gate and are recorded.

The surgical rule (EXPAND-1, corpus-only — the eval set is never consulted): frequency rank ≥
10 132 in the shipped dictionary (exactly `bigram_pack.select_heads` order), ≥ 10 tokens in
`tt_conv_train90`, not already promoted by H or by the imperative rule — 3 102 words, appended
to `scripts/bigram_extra_heads_tat.txt` with the rule in the header, regenerated by
`scripts/bigram_extra_heads_conv.py` (committed). The packer's own drop rule removed 152 of the
3 177 address candidates (conversational negative/question forms whose pair-mates are
out-of-dictionary) alongside the same three -гәнчә converbs — known-drift 3/0 → **155/0**.

**Rule-bug incident, recorded honestly**: the first draft of `bigram_extra_heads_conv.py` ranked
words by the dictionary's alphabetical STORAGE order instead of frequency rank; the packer's
dedup silently reduced that list to a subset of the intended set. The error was caught by the
report arithmetic (promoted 2 814 ≠ listed 6 104), the rule was fixed to rank by frequency, the
candidate lists were regenerated and the candidates repacked — every number above is measured on
the CORRECTED rule. This is also why the two measurement rounds exist.

#### Implementation and recalibrations (old → new)

- `scripts/rebuild_assets.py`: tat `successes_per_head` 4 → 3 (T7 note inline);
  `scripts/bigram_extra_heads_tat.txt`: +3 102 EXPAND-1 words (3 177 effective); rebuild via
  `python3 scripts/rebuild_assets.py --only tatar --baseline <1.8.4>` — the produced table is
  byte-for-byte the measured C3b candidate (79 574 B, SHA-256 `283661b4…`), the dictionary
  untouched, the Russian side byte-identical (checked by the rebuild's own gate).
- `app/src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib`: heads 10 204 → 13 154, pairs
  40 735 → 38 874, compressed 81 476 → 79 574 B, raw 134 938 → 135 889 B.
- `BigramStorageContracts.kt`: pins rewritten by the rebuild (size/SHAs/headCount) + a dated
  KDoc note; `TatBigrValidatorTest` provenance pins 10 204/134 938/40 735 → 13 154/135 889/38 874.
- `TtSuggestEvalTest` next-word pins: covered 3 276 → 3 659, hits 415 → 471, literals
  "75.3623" → "84.1730", "9.5468" → "10.8351", "12.6679" → "12.8724";
  `PIN_NEXTWORD_EMPTY_BEFORE` 889 → 684 (the new heads give 205 of the formerly-empty words
  successors; the after-fallback value stays 0). All other eval pins unchanged (dictionary-side
  metrics are untouched).
- `scripts/known_asset_drift.json`: tt missing 3 → 155 with the new reason; rebuild
  `--check --allow-known-drift` → `"ok": true`.
- `tests/rebuild_assets/test_rebuild_assets.py`: the canned argv pin `--successes-per-head 4 → 3`;
  `tests/bigram_asset_pack`: the shipped-list pin 75 → 3 177 (+ rule-sample membership).
- E2E pins unaffected and re-verified green: `TtNextWordFillE2ETest` (сәләм stays non-head —
  conv count of сәләм in the train input is 0, so no rule reaches it; the сцләм E2E path of
  TT-TYPO-NEXT is dictionary-side and untouched), `RealBigramPrefixIndexTest` (perf-only),
  every autocorrect/fuzzy pin (dictionary-side).
- Device consequence: the Tatar table's raw SHA-256 changed (`87af8ba3…`), so devices re-inflate
  it once on update (schema 3 re-links to the UNCHANGED dictionary hash — no dictionary
  re-inflation; a sub-second, one-time cost for ~80 KB of asset). The Russian table is
  byte-identical — no Russian consequence at all.

### Batch A gates (2026-09-23)

| gate | result |
|---|---|
| `./gradlew test --rerun-tasks` | **1 437 tests, 0 failures** (count unchanged — pins recalibrated in place) |
| python suites (`tests/*/test_*.py`) | 484 tests, all OK |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `assembleRelease -PskipReleaseSigning` | **1 850 040 B** ≤ 3 145 728 (−1 904 B — the table is 1 902 B smaller) |
| `check-no-internet.sh` (unsigned release APK) | Level 1 + Level 2 OK |

---

## Batch B — P6: two-edit typo recovery (gated), gates written 2026-09-23 BEFORE any code

Item P6 of Phase 4 (docs/ROADMAP.md): cover cases like the operator's `сэлэм` — two single
substitutions from `сәләм` — which class #4 (one substitution) structurally cannot recover
(verified: no one-edit variant of `сэлэм` has any dictionary continuation at all).

### Design (written before code)

New edit class #5 behind the FuzzyEditPolicy seam (Tatar only; DEFAULT untouched):

- **Edit rule**: TWO substitutions at two DISTINCT positions, any letters of the layout alphabet
  — the full two-edit class, NOT a confusion-restricted one (measured before writing this: a
  confusion-restricted class covers ~0.07 % of uniform two-edit typos — dead on arrival).
- **Pruned enumeration** (the (38n)² naive space is dead): per position i, seed the 37
  single-substitution prefixes and extend the surviving ones letter by letter (the chain dies
  the moment a prefix stops being a dictionary prefix — prefix property, never re-probed);
  per surviving seed chain and second position j, the SECOND substitution's letters are taken
  from the seed range's own continuation letters when the range is narrow (a bounded walk of the
  range, not 37 blind probes) or from 37 whole-word probes when it is broad; a full variant
  (length n) is probed only when its prefix chain is alive end to end. The base cost is 37
  per position (stage A) plus the chains; probes never touch the shared block cache (the C2
  no-cache machinery), and the per-position range narrowing of class #4 is reused.
- **Plausibility ranking key (required by the user's case)**: among class-#5 candidates, a
  candidate whose edits are ATTESTED confusion pairs (membership in the layout's long-press
  partner map — read from the table, never hard-coded) outranks arbitrary substitutions:
  rank = class → same-length bonus → number of attested-confusion edits (0..2, desc) →
  frequency (desc) → code point (asc). Measured motivation, pinned on the real asset: `сэлэм`
  has NINE edit-2 whole-word dictionary matches (салым 7 466, сәлам 886, салам 697, сәлим 321,
  белэм 45, сәләм 36, …) — plain frequency ranking puts the intended `сәләм` OUTSIDE the top
  three; the plausibility key orders сәләм (э→ә ×2, both attested) first.
- **Activation**: class #5 fires ONLY when the exact pass returned 0 results AND classes #1/#4
  produced no candidate (the strip is empty anyway) AND the prefix is ≥ 4 code points. A
  fail-closed probe budget (MAX_EDIT2_PROBES, pinned by measurement like MAX_FUZZY_PROBES)
  bounds the worst case; a trip drops the level whole and is counted and reported.
- **Autocorrect untouched** (P7 already rejected widening there).

### The written gates

- **G1 (recovery)**: new edit-2 typo set via typo_pack (`--edit-class 5`: two substitutions at
  distinct positions, alphabet from layout resources, 5-cp window, 3-cp reported). Under the
  exact activation condition, recovery@3 of {1, 4, 5} minus {1, 4} must be ≥ +10 percentage
  points on the edit-2 set (class #4's recovery there is 0 by construction — the original word
  is always two edits away).
- **G2 (precision)**: the activation ordering is pinned (edit-2 fires ONLY when exact==0 AND
  classes #1/#4 yielded nothing — pollution of a non-empty strip is impossible by construction
  and pinned). Fill rate on correct eval prefixes (≥ 4 cp, exact==0, classes 1+4 empty) is
  measured and reported; a candidate set for the operator's case is listed.
- **G3 (perf)**: host p95 ≤ 5 ms, zero-alloc ≤ 8 B/lookup, the per-lookup probe count is bounded
  and pinned (fail-closed budget). DEVICE p95 ≤ 3.5 ms is measured in the FINAL batch on the
  POCO C71 (NOT connected during this batch — recorded as pending, with the probe-count evidence
  this design produces for it).

Ship iff G1–G3 pass and the user's case lands сәләм in cell 1 (pinned) with one negative pin (a
word needing THREE edits stays empty). Otherwise class #5 stays unwired and the numbers stand.

### Batch B measurements and verdict (2026-09-23)

**VERDICT: NO SHIP — G1 fails below the theoretical ceiling.** Class #5 stays unwired
(`FuzzyEditPolicy.TATAR` remains {1, 4}); the machinery, the calibration test
(`TwoSubstitutionCalibrationTest`) and these numbers stand as the documented evidence.

**Typo sets** (`scripts/typo_pack.py build --edit-class 5` on the committed 110 000-entry asset;
the JVM mirror is byte-identical, pinned by SHA-256 of the `word<TAB>typo\n` render):

| window | rows | SHA-256 |
|---|---|---|
| 5 cp | 104 955 | `03159a49d645026653cf6da9a6b982a3dac7d6f77b90a1ad96b012298520fb68` |
| 3 cp | 109 649 | `d955151e1e00fed3e85e88f6a2187f431ab9bc5eebc932cde1041c6ee6775899` |

**G1 — BELOW (the gate fails):**

| frame | {1, 4} recovery@3 | {1, 4, 5} recovery@3 | lift |
|---|---|---|---|
| activation subset (76 399 rows = 72.8 % of the set: exact==0 AND classes #1/#4 empty) | 0 (structural) | **0** | **+0.00 pp** (gate ≥ +10 pp) |
| whole set (104 955 rows, context) | 0 | 0 | — |
| 3-cp window (inertness) | — | — | 109 649 / 109 649 rows identical |

The candidate's recovery is zero because the fail-closed probe budget (512) trips on
**1 448 / 1 981 firing sample rows (73.1 %)** — fertile prefixes (common letters) explode the
chained enumeration and the level is dropped whole, exactly where recovery would matter.

**G1 ceiling diagnostic** (the decisive evidence; brute-force perfect recall on a 4 015-row
sample of the activation subset, 113.6 average edit-2 competitors per row):

| ranking | ceiling recovery@3 |
|---|---|
| engine key (same-length → plausibility → frequency) | **9.79 %** — below the +10 pp gate |
| without the same-length bonus | 10.24 % |
| frequency only | 10.26 % |

The original word's median rank among its edit-2 competitors is **28** (p95 206). Even a perfect
engine with an infinite probe budget cannot reach the gate; the frequency-only ranking that
marginally clears it is vetoed by the operator's case (салым 7 466 would outrank сәләм 36).
The direction is closed, not just this implementation.

**G2 — PASS (structural):** eval words 2 670; 304 prefixes meet the activation condition; class
#5 issued probes on all 304 (100 % of empty strips) and on **zero** non-empty strips (pinned
per-word: any earlier-class cell ⇒ no edit-#5 probe at all).

**G3 — host PASS / device pending:** candidate p95 **0.193 ms** (current 0.097 ms) ≤ 5 ms;
edit-2 probes p95 = max = 512 (the fail-closed budget holds exactly); zero-alloc path
(scratch-resident chains/variants). Device p95 ≤ 3.5 ms remains for the FINAL batch on the POCO
C71 (not connected during this batch); the probe evidence for it: 512 probes × ~6 µs ≈ 3 ms on
top of the class-#4 pass on the same lookup — with the measured 73 % trip rate the whole point
is moot.

**Operator's case (pinned as measured):** `сэл` → [сәламәтлек, сәләтле, сәламәт] (class #1,
unchanged); `сэлэ` → [сэбэпле, сэбэп, фэлэн] (class #4 fills — class #5 correctly silent);
`сэлэм` → **[]** (the 512-probe budget trips — fail-closed — the level drops whole); `сөлүкә`
(three edits) → [] (negative pin holds).

**Bugs found by the calibration itself** (the first measurement — 299 recovered rows, garbage
strips — was invalid and is superseded): `walkEntries` reported the block-first word's pieces
for any mid-block `fromIndex` (shifted entries — false `startsWith` passes produced phantom
scans like `щапов` for `сэлэм`); `edit2UpperBound` returned `high0` blindly when every remaining
block started with the query; the probe counter could read 513 > 512 (check-then-count now).
All three fixed with the calibration as the proof.

### Batch B gates (2026-09-23)

| gate | result |
|---|---|
| `./gradlew test --rerun-tasks` | **1 443 tests, 0 failures** (1 437 + 6: the P6 calibration) |
| python suites (`tests/*/test_*.py`) | all OK |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `assembleRelease -PskipReleaseSigning` | **1 852 948 B** ≤ 3 145 728 (+2 908 B — the unwired class-#5 machinery) |
| `check-no-internet.sh` (unsigned release APK) | Level 1 + Level 2 OK |

---

# Final block: full gates + UAT (2026-09-23)

## Full gates (final tree, all 2026-09-23)

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **15 files, 484 tests, 0 failing files** |
| `./gradlew test --rerun-tasks` | **1 443 tests, 0 failures / 0 errors / 0 skipped** (149 suites) |
| `./gradlew lintRelease --rerun-tasks` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `release_pack.sh` | unsigned **1 852 948 B** (= the Batch-B recorded size — reproducible; the K=3 reclaim of −19 209 B was already inside Batch A's 1 850 040, the net vs the P3 tree is +1 004 B for the unwired P6 machinery) → signed zopfli **1 834 276 B** ≤ 3 145 728, SHA-256 **`8e9a437d70905fb0b6f1fedc2445512b94496321f822e66883abdffdc0224d71`**, v2-only, cert `98ca6feb…42ad` |
| `check-no-internet.sh` (the signed APK) | both levels OK |
| `release_check.sh --quick` | **8/8 artifact checks PASS** — crucially `asset_pins` 16/16 with the REWRITTEN pins of the new 13 154-head / K=3 Tatar table (the gate the mission flagged); version 2.0.1/33, changelog on the existing 33.txt |

## UAT — **device BLOCKED; emulator fallback executed**

The POCO C71 was verified absent (`adb devices` empty before the session — the 2026-09-23
P3 state never changed). Device rows (on-hardware UAT, device cold start, the P6 G3
device p95 probe) stay **BLOCKED**. Fallback: debug build of the SAME tree on
`tt_suggest_a14` (Android 14, 1080×2280), suggestions via run-as prefs. Evidence:
`build/device-uat-2026-09-23/p4/` (31 files). Emulator off at the end.

| # | Scenario (emulator) | Result | Evidence |
|---|---|---|---|
| U1 | Sentence start, empty field → **Бу · Ул · Ә** (capitalized) | PASS | `p01-sentstart-strip.png` |
| U2 | **NEW head coverage**: `автобуска` (EXPAND-1, freq rank 10 699 ≥ 10 132 — provably NOT a head before; index 85 of the extras file) + space → **утырып · кереп · 🚌** — the shipped table's successors (offline read: утырып, кереп, килеп; the third display cell is the pre-existing emoji-tail rule, exactly as сәлам's 👋) | PASS | `p02-avtobuska-strip.png` |
| U3 | Old head unchanged: `татар` + space → **теле · дәүләт · телен** | PASS | `p03-tatar-strip.png` |
| U4 | Fallback merge unchanged: `сәләм`+space → **сәләмә · һәм · белән** (сәләм still NOT a head — offline-verified) | PASS | `p04-salam-strip.png` |
| U5 | `сакчы`+space → **булып · виталий · андрей** — сакчы is now a NEW head itself (offline-verified: successors булып/виталий/андрей). The P3-era expectation "forms" is superseded BY DESIGN: successors keep priority and occupy all three cells; the forms path itself is proven intact by U4 (сәләмә = the form of the non-head сәләм in cell 1) | PASS (as redesigned) | `p05-sakcy-strip.png` |
| U6 | After-comma: `татар, ` → **теле · дәүләт · телен** | PASS | `p06-comma-strip.png` |
| U7 | Typo regression: `сцләм` → **сәләм** in cell 1 (сәләмәтлек · сәләмәт after) | PASS | `p07-sclam-strip.png` |
| U8 | Emoji tail: `сәлам`+space → **биреп · белән · 👋** (fresh field redo — see the harness note) | PASS | `p09-salam-tail-strip.png` |
| U9 | ru: `майор` prefix → **майора · майором · майору** (unchanged); `тюлень`+space → **я · не · в** (ru fallback) | PASS | `p13-ru-mayor-strip.png`, `p14-ru-tyulen-strip.png` |
| U10 | Cold start ×3, emulator + debug build, INFORMATIONAL only (device numbers pending): SetupActivity 410/413/393 → median 410 ms; SettingsActivity 474/528/552 → median 528 ms — the debug build is unminified and the emulator is not the device; the POCO's &lt;400 ms budget was 251–273 ms in recent UATs and is NOT re-verified here | measured, informational | `p15-coldstart-emu.txt` |
| U11 | Crash buffer after the whole session: EMPTY (0 lines); full logcat: no FATAL EXCEPTION / ANR for the package | PASS | `p16-logcat-crash.txt`, `p17-logcat-full.txt` |

Harness notes (not app defects): `release_pack.sh`'s gradle clean wiped the p4 evidence
dir mid-session once (restored from /tmp; taps with missing coords iterate zero times —
a miss that reads as a black screen, not a FAIL, caught by the field readback); one run
typed сәләм right after сцләм without the space and glued them (the strip probes were
already taken; the emoji-tail check was redone on a fresh field); an `am start` once
landed on SettingsHostActivity instead of SetupActivity — absorbed by focus checks.

## DONE-WHEN audit (Phase 4 of `docs/ROADMAP.md`)

- **T7 (K decision)** — **done via the written gate**: rank-4 latent value measured
  (+1.5643 pp unconditional), the 4-cell strip change judged out of this batch's budget,
  repack at K=3 in the P5a rebuild (−19 209 B reclaimed; the roadmap's "~48.7 KB"
  estimate was wrong, the real number stands).
- **P5a (more heads)** — **shipped with numbers**: C3b surgical EXPAND-1 T=10, 13 154
  heads, 79 574 B ≤ 250 000, coverage 75.3623 → 84.1730 (+8.81 pp ≥ +2.0), hit 9.5468 →
  10.8351 (+1.29 pp ≥ +1.0); the C2/C5 PASS alternatives and the C1/C4 FAILs are
  recorded; the rule-bug incident (alphabetical-vs-frequency ranking) is recorded and
  fixed with the measurement proof. Device U2 confirms a new head predicting on screen.
- **P5b (trigrams)** — **rejected with numbers** (offline projection caps at +1.63 pp
  even at 30 000 contexts / ~240 KB compressed vs the +2.0 pp gate; context coverage is
  the structural bottleneck). No runtime code written.
- **P6 (two-edit recovery)** — **rejected with numbers**: the ceiling diagnostic says
  even perfect recall tops at 9.79 % recovery@3 < the +10 pp gate (median rank 28 of the
  original word); the pruned enumeration trips the fail-closed budget on 73.1 % of
  firing rows. Class #5 stays unwired; calibration + numbers pinned as the evidence.
- Device legs (P5a on-device inflation + new heads on hardware, P6 G3 device p95, cold
  start) — **blocked** (phone absent), pending its return.

What remains: the blocked device items, the operator's commit/release decision
(2.4.0), roadmap Phase 5+.
