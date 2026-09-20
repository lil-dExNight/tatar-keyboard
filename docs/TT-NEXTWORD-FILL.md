# TT-NEXTWORD-FILL — mission report: never-empty strip after a committed word

Status: phases A–C done (2026-09-20, post-2.0.0, uncommitted — no version bump; the release
decision is the operator's). Phase D (2026-09-20): all gates green, release APK packed —
device UAT BLOCKED (the POCO C71 was physically disconnected all session; the operator
scenario was replayed on the emulator instead, including the honest finding that the
literal `сэлэм` with two э cannot offer сәләм by single-edit design). Device UAT and
Phase E (independent re-verification) pending.
Plan: `docs/TT-NEXTWORD-FILL-PLAN.md` (annotated with dated footnotes, never rewritten).

Origin: operator bug report 2026-09-20 (device, 2.0.0): after accepting `сәләм` the strip offered
only `сәләмә`; after accepting `сәләмә` it went empty until the next letter. Diagnosis (verified
against the shipped assets): `сәләм` (freq 36) is not a bigram head → zero successors; its only
attested inflection is `сәләмә` (28); `сәләмә` has neither. The code behaved per contract — the
gap was data coverage for non-head words.

## Design that shipped (phases A–B)

**A. `TdictPrefixIndex.topFrequentWords(count)`** (`engine/TdictPrefixIndex.kt`) — the read API
behind the fallback: ONE linear frequency scan of the dictionary returning the N most frequent
words in the frozen ranking order (frequency descending, then code-point ascending — UTF-8 byte
order is code-point order, so the lookup path's comparator applies). Blocks decode sequentially
through the shared block cache (each block decoded exactly once), the selection state is two
fixed primitive arrays, and tie-breaks read words through the no-cache `decodeWordInto` — nothing
is allocated per entry. It runs at engine START (the fallback factory builds the pool there, on
the controller's background executor, before the engine's lookup worker exists), at most once per
engine; it is never on the lookup path. Contracts pinned by
`TdictPrefixIndexTopFrequentWordsTest`: ranking with ties, caps, the exact top-8 lists of both
shipped dictionaries, and the ≤ 8 B/lookup zero-allocation contract after the scan.

**B. The `FallbackWords` seam** (`engine/FallbackWords.kt`, `CompositePrefixComputer.predict`) —
mirrors the P3 after-word-forms seam: a per-engine `FallbackWords` built by a
`FallbackWordsFactory` against that engine's own dictionary. `GlobalTopFrequencyFallback` serves
a fixed pool (8 = 3 cells + slack for the excluded committed word and the ≤ 2 already-shown
candidates, so a fill never starves); the merge order is **bigram successors > after-word forms >
fallback**; the fallback excludes the committed word and anything already shown, caps at the free
cells, and never displaces. Fail-closed at both levels: a broken fallback leaves the
bigrams+forms answer intact (and broken forms never take the fallback down), and with no bigram
source attached the answer stays EMPTY — the NEXTWORD-RACE repair only re-asks while the band has
no active-language word, so a fallback band painted pre-attach would suppress the bigram
successors that outrank it. Wiring: `MappedDictionaryEngine.start` / `MappedEngineHandle.start`
gain the factory parameter; `LatinIME` passes `GlobalTopFrequencyFallbackFactory` for every
shipped-language engine (the artifact registry decides) — the factory builds the pool from the
engine's OWN dictionary, so the Tatar engine falls back to Tatar top words and the Russian one to
Russian top words with no call-site language check.

Recorded consequence: with the fallback wired, the post-commit NEXT_WORD band is always full, so
the companion-language NEXT_WORD fill (the audit-B4 empty-band repair) never fires after a commit
anymore — the own language's top words now win those cells deliberately. The companion PREFIX
fill (mid-word typing) is untouched.

## Measured (phase C)

Top-8 pools (real assets, pinned in `TdictPrefixIndexTopFrequentWordsTest`):

- tt: `һәм, белән, да, бу, дә, дип, ул, өчен`
- ru: `я, не, в, и, что, ты, на, это`

Engine-level e2e on the real assets with the production wiring
(`TtNextWordFillE2ETest`):

| Committed word | Strip before | Strip after |
|---|---|---|
| `сәләм` (no successors, one form) | `[сәләмә]` | `[сәләмә, һәм, белән]` |
| `сәләмә` (no successors, no forms) | `[]` (empty) | `[һәм, белән, да]` |
| `сәлам` (bigram head, 4 successors) | `[биреп, белән, бирү]` | unchanged — no fallback cell (белән is ALSO a top-8 word: the dedup pin) |
| `һәм` (top-1 word itself) | `[булып, ...]` | 3 cells, `һәм` never re-offered |
| `тюлень` (ru, non-head) | `[]` | `[я, не, в]` |

Eval metric (`TtSuggestEvalTest.stripEmptyAfterWordRateOnTheEvalSet`, 2 670 unique eval words,
printed before/after in one run):

```
EVAL|nextword_empty_words_before|889
EVAL|nextword_empty_words_after|0
EVAL|nextword_empty_before_pct|33.2959
EVAL|nextword_empty_after_pct|0.0000
```

The pre-fill strip was empty after committing **33.30 %** of unique eval words (889/2 670 — words
that are neither bigram heads nor form-bearing); after the fill it is **0**. Every existing
`TtSuggestEvalTest` counter is unchanged (the fill only adds cells to NEXT_WORD answers that were
not full; the prefix-side metrics are structurally untouched).

Controller level: the emoji tail keeps its pinned cell when the band arrives full from the
fallback (`SuggestionsControllerEmojiSuggestTest.theEmojiTailSurvivesAFallbackFilledBand`).

## Gates (2026-09-20)

| Gate | Result |
|---|---|
| `./gradlew test --rerun-tasks` | 1257 tests (was 1235; +22), 0 failures/errors/skips |
| python suites (`tests/*/test_*.py`) | 15 files, 474 tests, all OK (unchanged) |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `assembleRelease -PskipReleaseSigning` size | 1 869 779 B ≤ 3 145 728 B (+492 B over the TT-TYPO-NEXT C2 state) |
| `check-no-internet.sh` (unsigned release APK) | both levels OK |

## Files touched (phases A–C)

- `engine/FallbackWords.kt` — NEW: `TopFrequencySource`, `FallbackWords`, `FallbackWordsFactory`,
  `GlobalTopFrequencyFallback`, `GlobalTopFrequencyFallbackFactory` (pool = 8).
- `engine/TdictPrefixIndex.kt` — `topFrequentWords` (+ `TopFrequencySource` in the interface list).
- `engine/CompositePrefixComputer.kt` — the `fallbackWords` seam and the three-source merge in
  `predict`.
- `engine/MappedDictionaryEngine.kt`, `suggestions/EngineHandle.kt` — the factory parameter.
- `latin/LatinIME.java` — both shipped-language engines wired via the registry.
- Tests: `TdictPrefixIndexTopFrequentWordsTest` (NEW), `CompositePrefixComputerTest` (+8),
  `TtNextWordFillE2ETest` (NEW), `TtSuggestEvalTest` (+1 metric), `SuggestionsControllerEmojiSuggestTest` (+1).
- `docs/TT-NEXTWORD-FILL-PLAN.md` — dated footnote; this report.

## What remains (phases D–E)

> 2026-09-20 update: Phase D done except the device leg — gates, release APK and docs
> landed; the POCO C71 was off the cable all session, so the operator-scenario replay ran
> on the emulator (see the Phase D section). What remains: the on-device replay
> (install the packed APK, run the UAT table, cold start, crash buffer) the moment the
> phone is back, and the independent re-verification (Phase E). No version bump: this is
> post-2.0.0 work on main; the release decision is the operator's.

## Phase D — validation, device UAT, docs (2026-09-20)

### Gates (full rerun on the final tree, all 2026-09-20)

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **15 files, 474 tests, 0 failing files** |
| `./gradlew test --rerun-tasks` | **1 257 tests, 0 failures / 0 errors / 0 skipped** (136 suites) |
| `./gradlew lintRelease --rerun-tasks` | BUILD SUCCESSFUL (21 tasks executed; baseline 0 errors) |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` (tt 3/0, ru 2/0 as pinned) |
| `release_pack.sh` | unsigned **1 869 779 B** (byte-size identical to the phases A–C measurement — reproducible), signed zopfli **1 849 555 B** ≤ 3 145 728, SHA-256 **`d210f0c9e4538c12e733c952aec2fcadd4239a520c0ab61a0831c19226158a55`**, v2-only, cert `98ca6feb…42ad` |
| `check-no-internet.sh` (the signed APK) | both levels OK (manifest + aapt2; backup whitelist closed) |
| `release_check.sh --quick` (the signed APK) | **8/8 artifact checks PASS** — recorded exactly as asked: version 2.0.0/32 matches `app/build.gradle` (no bump), the changelog gate passes because `metadata/en-US/changelogs/32.txt` exists from the 2.0.0 release (it checks the metadata file, not the CHANGELOG.md top section), delta vs 1.9.15 +53 336 B. No post-release oddity observed |

### Device UAT — **BLOCKED (hardware absent), emulator fallback executed instead**

The POCO C71 was **physically disconnected** for this entire session: `adb devices` empty
across ~5 minutes of polling including an `adb kill-server`/restart cycle, and `lsusb`
shows no Xiaomi/POCO device at the USB layer (a software-unreachable state — no cable
event between sessions). Nothing was installed on the device; the device-side items
(install of the new APK, on-device cold start, on-device crash buffer) are **not done and
stay pending** — they need the phone back on the cable.

Fallback evidence (explicitly NOT the device UAT): the debug build of the **same tree**
(`assembleDebug` right after the release pack) on AVD `tt_suggest_a14` (Android 14,
1080×2280), suggestions enabled via the run-as pref. Evidence:
`build/device-uat-2026-09-20/emulator-fallback/`.

| # | Scenario (emulator, debug build of the release tree) | Result | Evidence |
|---|---|---|---|
| F1 | Operator scenario as literally written: type `сэлэм` (TWO э) — `сәләм` is **not offered** (strip at 5 cp: эләм · эдәм · слэм — the nearest single-edit survivors). **This is correct per design, not a bug**: сәләм is TWO substitutions away (э→ә at positions 2 and 4) and every shipped fuzzy class is single-edit. Verified offline too: сэлэм/сәлэм/сэләм are absent from the 110 000-entry dictionary. The operator's 2.0.0 tap of сәләм after typing сэлэм is attributable to their personal dictionary (edit-distance personal suggestions) — the plan's scenario text was imprecise about the typed word; the feature under test is the post-commit strip, which is what the rest of the chain verifies | PASS (as-designed) | `e14-strip.png`, `e15-strip.png` |
| F2 | Designed single-edit path: type `сэләм` (one э at position 2) → strip **сәләм · сәләмәтлек · сәләмәт** (correction in cell 1, class #1/#4 э→ә) | PASS | `e25-strip.png` |
| F3 | Tap `сәләм` → strip immediately **сәләмә · һәм · белән** (the pinned `[сәләмә, һәм, белән]`: one form + two fallback cells) — the operator's "only one cell" complaint is fixed | PASS | `e26-strip.png` (field readback «сәләм ») |
| F4 | Tap `һәм` → strip immediately **башка · аның · фән** — 3 cells again; matches the shipped bigram table's successors for һәм exactly (offline read: башка, аның, фән, ул) | PASS | `e27-strip.png` (field readback «сәләм һәм ») |
| F5 | Commit `сәләмә` (typed сәләм + space, tap cell 1) → strip **һәм · белән · да** — the pinned pure-fallback band | PASS | `e30-strip.png`, `e31-strip.png` (field readback «сәләм сәләмә ») |
| F6 | Fallback never displaces: `сакчы`+space → сакчысы · сакчылар · сакчысын (3 forms, 0 fallback cells) | PASS | `e32-strip.png` |
| F7 | Same-stem boost: prefix `татар` → татарлар · татарча · татарлары | PASS | `e33-strip.png` |
| F8 | Bigram priority: `татар`+space → теле · дәүләт · телен (no fallback cell) | PASS | `e34-strip.png` |
| F9 | ru unchanged + ru fallback: `майор` → майора · майором · майору; `тюлень`+space → **я · не · в** (the pinned ru top-frequency fill) | PASS | `e35-strip.png`, `e36-strip.png` |
| F10 | Typo regression: `сцләм` → **сәләм** in cell 1 (class #4 unchanged by the fill) | PASS | `e45-sclam-strip.png` |
| F11 | Sentence start at field start → бу · ул · ә (the sentstart table is a separate earlier slot; unaffected) | PASS | `e50-sentstart-strip.png` |
| F12 | Emoji tail kept with the fill live: `сәлам`+space → биреп · белән · 👋 (белән is both a successor and a top-8 word — the dedup pin visible; the tail cell survives) | PASS | `e51-emoji-tail-strip.png` |

### DONE-WHEN audit (mission level)

1. *Priority chain bigrams > forms > fallback; strip after a committed Tatar word never
   empty while suggestions are on* — **met**: JVM pins (`TtNextWordFillE2ETest`,
   `CompositePrefixComputerTest`) + emulator rows F3–F5 (3 cells after сәләм, after һәм,
   after сәләмә). The device leg rides with the unblocked UAT.
2. *Fallback never displaces successors/forms/emoji tail; committed word and shown words
   excluded* — **met**: JVM pins + emulator rows F6/F8/F12.
3. *Eval strip-empty-after-word 33.30 % → 0, other metrics unchanged* — **met** in phase C
   (pins green in the 1 257).
4. *All gates green; device UAT replays the operator's exact scenario* — **gates: met**
   (table above). **Device UAT: NOT DONE — the POCO C71 was physically disconnected all
   session** (see the BLOCKED note); the scenario was replayed on the emulator instead,
   including the honest finding that the literal `сэлэм` (two э) cannot offer сәләм by
   design and the single-э path `сэләм` can. The device replay — and with it item 4's
   device half — stays open until the phone is back on USB.

### Files touched (Phase D)

- `docs/TT-NEXTWORD-FILL.md` — this section; status header updated.
- `HANDOFF.md` — new top entry.
- `CHANGELOG.md` — new Unreleased section (the fallback fill).
- `docs/README.md` — index lines for the plan and this report.
- Evidence: `build/device-uat-2026-09-20/emulator-fallback/` (12 scenario rows) —
  device evidence pending the hardware.
