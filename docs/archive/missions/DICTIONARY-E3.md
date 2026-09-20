# E3a — reproducible typo set and recovery@3 calibration

Evaluation date: 2026-07-27. This document is the deliverable record for the open E3a
sub-phase item: a **reproducible edit-class-#1 typo set** and the **recovery@3 calibration**
against the offline reference declared in `PROPOSALS.md`
(«E3a — калибровка порогов», recovery@3 **14,2%** for class #1). The E3a engine code is
already committed (HEAD `e3f9b1e`); nothing in its logic was touched here. This closeout
adds only an offline generator (`scripts/typo_pack.py`), its tests, a JVM calibration
test, and these docs.

The class #1 edit is the contract's «замена буквы на её long-press партнёра в любой
позиции».

---

## 1. Adjacency map (long-press pairs, as built in E3a)

The pairs are read from the keyboard layout resources — the `latin:moreKeys` attributes
of `res/xml/rowkeys_tatar1.xml`, `rowkeys_tatar2.xml`, `rowkeys_tatar3.xml`,
`rowkeys_tatar_extra.xml` — and symmetrized and de-duplicated exactly as
`KeyNeighborTable.build` does on the device. No pair is hard-coded: the offline
`scripts/typo_pack.py` reads the same XML, and the JVM test uses the E3a
`E3aTestFixtures.tatarNeighborTable()` that mirrors it (proven equal by
`KeyNeighborTableTest`). The layout stores each edge one-directionally and duplicated
(«ә» is declared on both «а» and «э»; «һ» on both «г» and «х»), so the reverse edge is
added explicitly and each node's partners are sorted by code point.

Ten undirected pairs (39 Tatar letters, six of them the fifth-row ә ө ү җ ң һ):

| # | pair | note |
|---:|---|---|
| 1 | а ↔ ә | U+0430 ↔ U+04D9 |
| 2 | о ↔ ө | U+043E ↔ U+04E9 |
| 3 | у ↔ ү | U+0443 ↔ U+04AF |
| 4 | ж ↔ җ | U+0436 ↔ U+0497 |
| 5 | н ↔ ң | U+043D ↔ U+04A3 |
| 6 | г ↔ һ | U+0433 ↔ U+04BB |
| 7 | х ↔ һ | U+0445 ↔ U+04BB (һ therefore has two bases: г, х) |
| 8 | е ↔ ё | U+0435 ↔ U+0451 |
| 9 | ь ↔ ъ | U+044C ↔ U+044A |
| 10 | э ↔ ә | U+044D ↔ U+04D9 (ә therefore has two bases: а, э) |

Symmetrized fan-out (partners per node, sorted): а→[ә]; ә→[а, э]; э→[ә]; о→[ө]; ө→[о];
у→[ү]; ү→[у]; ж→[җ]; җ→[ж]; н→[ң]; ң→[н]; г→[һ]; х→[һ]; һ→[г, х]; е→[ё]; ё→[е]; ь→[ъ]; ъ→[ь].

## 2. Thresholds and limits

These are the E3a engine constants (`TdictPrefixIndex`, unchanged here) plus the two
calibration knobs.

| Constant | Value | Where | Kind |
|---|---:|---|---|
| `MIN_FUZZY_PREFIX_CODE_POINTS` | 3 | `TdictPrefixIndex` | engine gate: the fuzzy pass runs only at ≥ 3 code points |
| `MAX_RESULTS` | 3 | `TdictPrefixIndex` | strip cells; fuzzy fills only cells left empty by the exact pass |
| `MAX_FUZZY_VARIANTS` | 24 | `TdictPrefixIndex` | fail-closed budget on variants per lookup |
| `MAX_FUZZY_VISITED` | 8192 | `TdictPrefixIndex` | fail-closed budget on visited dictionary entries |
| geometry overlap threshold | 35% | E3a `KeyNeighborTable` doc | **chosen constant, not a measured value** (recorded per contract; unused by class #1) |
| `PREFIX_CODE_POINTS` (calibration) | 3 | `scripts/typo_pack.py` / calibration test | typo-prefix window; see §4 |
| `TYPO_SEED` (calibration) | 20260727 | `scripts/typo_pack.py` / calibration test | fixed selection seed |

Measured variant counts over the typo set (§4), mirroring
`FuzzyPrefixVariants.generateLongPressVariants` (one variant per partner of every
position): **p50 = 2, p95 = 3, max = 6**. The contract's class #1 offline reference is
«p95 3 варианта, максимум 5»: the p95 matches exactly; the observed maximum is 6 (one
three-code-point prefix carries three multi-partner letters, e.g. an ә/ә/һ shape), one
above the reference — reported, not adjusted.

## 3. The reproducible typo set

Generator: `scripts/typo_pack.py` (Python standard library only, fail-closed, patterned
on `scripts/emoji_pack.py`). It has two committed inputs and writes one output that is
**not** committed:

- **Layout** (`res/xml/rowkeys_tatar*.xml`) → the adjacency map of §1.
- **Dictionary** (`app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib`), pinned
  by SHA-256 on both the compressed asset
  (`2d98ed35…7485cae`) and the inflated raw file (`798d3257…548f558`) and by its
  100 000-entry count — the same triple pin as
  `DictionaryArtifactSpec.TATAR_TOP100K_V1`.
- **Output**: one `original<TAB>typo_prefix` row per eligible word.

**Why nothing is committed.** The licensed source corpus (Leipzig, see
`docs/DICTIONARY-D1A.md`) was never in the repository, and the typo set itself is derived
data: it is fully reproducible from this generator and the two committed inputs, so
committing it would only duplicate the dictionary and risk drift — the same reasoning
that keeps the adjacency out of a separate XML in E3a.

**Selection (deterministic, language-portable).** For every dictionary word of at least
`PREFIX_CODE_POINTS` (= 3) code points whose first-three-code-point window holds at least
one letter with a long-press partner, exactly one `(position, partner)` pair is chosen and
applied inside that window. The choice is a pure function of `(TYPO_SEED, word)`:
`index = SplitMix64(TYPO_SEED xor FNV1a64(word_utf8)) mod eligible_count`, over the
eligible pairs listed in `(position, partner)` order. Both FNV-1a-64 and SplitMix64 are
implemented identically in Python and Kotlin (golden vectors asserted on both sides), so
the offline generator and the JVM test produce **the same** set.

**Why a 3-code-point prefix window.** Three code points is exactly the engine's
`MIN_FUZZY_PREFIX_CODE_POINTS` — the shortest prefix at which the fuzzy pass fires. It is
the conservative worst case (shortest context → largest candidate block → lowest
recovery) and it reproduces the class #1 variant signature (p95 3, §2), which a
full-word model would not.

**Recorded set identity** (from `python3 scripts/typo_pack.py build …`, two runs
byte-identical):

| Property | Value |
|---|---|
| seed | 20260727 |
| prefix window | 3 code points |
| words scanned (≥ 3 code points) | 99 659 |
| eligible words (set size) | **87 375** |
| output bytes | 2 230 638 |
| output SHA-256 | `6a61b48db87ac0bbff78af48ea597b3af19f81dd42ae8deaa2d4c00a6c81dfc3` |

The JVM test independently enumerates the same committed asset, rebuilds the set with the
same rule, and asserts the same size and the same SHA-256 — the cross-implementation
proof that both produce the identical reproducible set.

## 4. Measured recovery@3 (real dictionary)

Test: `app/src/test/java/rkr/simplekeyboard/inputmethod/latin/dictionary/engine/E3aRecoveryCalibrationTest.kt`.
It inflates the committed `tatar_top100k_v1.tdict.zlib` through
`TdictValidator().inflateAsset` (the same harness as `RealDictionaryPrefixIndexTest`),
opens `TdictPrefixIndex`, and for each `(word, typo_prefix)` row looks up the typo prefix
and counts the cases where the correct word is among the top three results. It is **not**
skipped and prints a raw line.

Raw line (from `app/build/test-results/testDebugUnitTest`):

```
E3a recovery@3 class#1 seed=20260727 prefix_cp=3 set=87375 recovered=6364 recovery@3=7.2835% baseline_exact_only=0.0000% fuzzy_fired=49155 recovery@3_when_fuzzy_fired=12.9468% variant_p50=2 variant_p95=3 variant_max=6 contract=14.2% delta=-6.9165pp within_1.0pp=false set_sha256=6a61b48db87ac0bbff78af48ea597b3af19f81dd42ae8deaa2d4c00a6c81dfc3
```

| Metric | Value | Definition |
|---|---:|---|
| recovery@3 (whole set) | **7.2835 %** (6364 / 87375) | «доля случаев» over every row of the set — the contract's literal methodology |
| baseline (exact only) | **0.0000 %** | a typo prefix carries the wrong letter, so the exact pass alone never surfaces the word |
| fuzzy actually fired | 49 155 / 87 375 (56.3 %) | rows where the exact pass returned < 3 continuations, so the cell-fill rule ran the fuzzy level |
| recovery@3 where fuzzy fired | **12.9468 %** (6364 / 49155) | recovery restricted to the fuzzy-eligible subset |

## 5. Сверка with the declared 14,2 %

**Verdict: the whole-set recovery@3 (7.2835 %) diverges from the contract's 14,2 % by
−6.9165 pp** — outside any reasonable tolerance (§6). It was **not** tuned: neither code,
test, generator, nor tolerance was adjusted to move it.

The divergence has a concrete, non-code cause and is not a defect of the E3a
implementation:

- The baseline is 0 % and the fuzzy pass strictly increases recovery, so the mechanism
  works as designed.
- 43.7 % of the typo prefixes (38 220 / 87 375) already have **three or more exact
  continuations of the mistyped prefix**. The contract's own cell-fill rule
  («неточные занимают только ячейки, пустые в D1») then leaves the fuzzy level switched
  off, so those rows can never recover — they drag the whole-set rate down.
- Restricting to the 49 155 prefixes where the fuzzy pass actually runs gives
  **12.9468 %**, only ~1.27 pp below the declared 14,2 %.

The most likely reconciliation is that the offline model that produced 14,2 % measured
recovery over the fuzzy-eligible subset (or used a marginally different word set / gate),
whereas this test reports the literal whole-set «доля случаев». Two defensible readings
therefore exist:

1. whole-set denominator → **7.28 %** (reported here as the primary number), or
2. fuzzy-eligible denominator → **12.95 %** (within ~1.3 pp of 14,2 %).

Per contract this is exactly the case the calibration exists to catch: «иначе корректная
реализация может провалить gate из-за ошибки офлайн-модели, а не кода». The decision on
which denominator is canonical — and hence whether the E3b acceptance threshold is
declared active — is left to the orchestrator. The JVM test consequently prints and
reports the number but does **not** assert equality with 14,2 %; it asserts only
denominator-independent invariants (set size, fraction range, fuzzy > baseline) so the
build stays green while the finding is surfaced.

## 6. Chosen tolerance and its justification

The contract calls the tolerance «согласованный» without giving a number. **Chosen
tolerance: ±1.0 percentage point (absolute)** around the declared 14,2 %, i.e.
recovery@3 ∈ [13.2 %, 15.2 %].

Justification, independent of the outcome:

- The feature's lift over the exact baseline is large (contract 14,2 % vs baseline 1,8 %,
  ~12.4 pp; measured baseline here 0 %). A ±1.0 pp band is ~7 % of the target and ~8 % of
  the lift — tight enough that a genuine reproduction of the offline model would pass and
  a real regression would fail, yet loose enough to absorb second-order noise
  (SplitMix64 selection, frequency ties broken by code point, and the exact-vs-fuzzy slot
  interaction).
- An absolute pp band, not a relative %, is used because recovery@3 is itself a
  percentage and the E3b gate reasons in percentage points.

Applied: the whole-set 7.28 % is **outside** this band; the fuzzy-eligible 12.95 % is also
just outside ±1.0 pp but inside ±1.5 pp. Widening the band to force a pass would be
tuning the tolerance to the result, which the contract forbids, so the band stays at
±1.0 pp and the mismatch is reported.

## 7. Tests on input / output

| | JVM (`:app:testDebugUnitTest`) | Python (`tests/typo_pack`) |
|---|---:|---:|
| before this deliverable | 413 | 0 |
| added here | 4 | 27 |
| after | **417** | **27** |
| failures / errors | 0 | 0 |
| skipped | **0** | **0** |

The four JVM tests (`E3aRecoveryCalibrationTest`): portable-primitive golden vectors;
set byte-identity with the generator; recovery@3; and the fuzzy delta on the 22 everyday
prefixes (§ TSV). None is `@Ignore`d and none is skipped — verified by summing the
`<testsuite>` `tests`/`skipped` attributes across
`app/build/test-results/testDebugUnitTest/*.xml` (417 / 0). The 27 Python tests run under
`python3 -m unittest`; the committed-asset smoke test runs (not skipped) because the asset
is present.

## 8. APK delta

Measured on this worktree with `./gradlew :app:assembleRelease --offline`
(`app/build/outputs/apk/release/app-release.apk`). This deliverable adds no `app/src/main`
source and no asset, so the release APK reflects only the already-committed E3a code
(HEAD `e3f9b1e`); the scripts, tests and docs added here ship nothing.

| Artifact | Bytes |
|---|---:|
| post-E2 baseline (given) | 1 478 015 |
| post-E3a release APK (measured) | 1 480 299 |
| **delta** | **+2 284** |

The +2 284 B is entirely the E3a engine code (`KeyNeighborTable`, `FuzzyPrefixVariants`,
the fuzzy pass in `TdictPrefixIndex`, and the `LatinIME`/`SuggestionsController`/`EngineHandle`
wiring). It is far below the 3 MiB dictionary-feature gate recorded in
`docs/DICTIONARY-D1A.md`.

## 9. Device-UAT matrix

No device is connected, and PSS measurement is deferred by the owner's decision (contract
amendment 2026-07-27). Every row is `NOT_COVERED`; **none is PASSED**, and no PSS number is
invented. Columns follow the cross-cutting "Доступ к реальному устройству" requirement.

| Item | Status | Device (model, serial) | Build (vName/vCode, APK SHA-256) | Date | Raw numbers / path |
|---|---|---|---|---|---|
| recovery@3 on real device typing (≥ 50 hand-typed typos from the reproducible set) | NOT_COVERED — device not connected | — | — | — | — |
| Fuzzy suggestions visibly indistinguishable from exact ones (no colour/icon/suffix, no separate a11y node) | NOT_COVERED | — | — | — | — |
| Fuzzy pass never shifts or replaces an exact candidate, live | NOT_COVERED | — | — | — | — |
| Compute p95 on device on a 100%-fuzzy set ≤ 5 ms (E3b input-gate precondition) | NOT_COVERED — measured only on host JVM; not an Android device | — | — | — | — |
| PSS delta with the fuzzy level on vs off | NOT_COVERED — PSS deferred by owner (amendment 2026-07-27) | — | — | — | — |
| Absolute PSS within the (uncomputed) ceiling | NOT_COVERED — ceiling not recomputed; PSS deferred | — | — | — | — |
| Fuzzy level respects `PREF_TATAR_SUGGESTIONS` and password/no-suggestion fields on device | NOT_COVERED | — | — | — | — |
| No crash/ANR when the layout (and thus the neighbor table) changes live (rotation, theme, subtype) | NOT_COVERED | — | — | — | — |

An emulator run, had one been performed, would be a separate row marked `NOT_COVERED`
with "emulator, reference only"; none was performed for E3a.

## 10. Regeneration and cross-references

```
python3 scripts/typo_pack.py build \
  --dictionary app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib \
  --layout-dir app/src/main/res/xml \
  --output /tmp/e3a_typo_set.txt        # not committed; SHA-256 must equal §3
python3 -m unittest tests.typo_pack.test_typo_pack
./gradlew :app:testDebugUnitTest :app:lintVitalRelease
```

- Fuzzy-candidate review queue for the 22 everyday prefixes:
  `docs/DICTIONARY-E3-TYPO-REVIEW.tsv` (all `pending`; machine classification does not
  replace a human reviewer).
- One summary reference row added to `docs/TATAR-REVIEW-QUEUE.tsv`.

---

# E3b — geometric-neighbour and transposition classes

Evaluation date: 2026-07-27. This section is the deliverable record for sub-phase **E3b**:
edit **class #2** (substitute a letter with a geometric keyboard neighbour) and edit
**class #3** (swap two adjacent letters), the instrumental compute harness, and the
recovery@3 measurement of the full fuzzy engine. Nothing about class #1 (E3a) changed: the
class #1 set SHA-256 is byte-for-byte the same, so the E3a calibration stays valid. The
dictionary asset is untouched (SHA-256 `2d98ed35…7485cae`, still the v1.2.0 asset).

## 1. Neighbour rule — taken verbatim from the contract

Edit class #2 uses the geometric-neighbour relation defined in the E3 contract, word for
word: **keys of the same row that touch horizontally, plus keys of an adjacent row whose
horizontal overlap is MORE than 35% of the width of the narrower of the two.** The 35%
threshold is the contract's chosen constant, not a measured value.

It is derived by `KeyNeighborTable.build` from the raw geometry (`left/top/right/bottom`) of
the letter keys — geometry that comes only from the live layout (`Keyboard.getSortedKeys()`
on the device via `KeyNeighborTableBuilder`). Implementation detail, so the relation is
identical on every host and reproducible offline:

- "same row" = equal top rank (rank of distinct top coordinates); "adjacent row" = top rank
  differing by exactly one — so a dropped digit row or an empty row cannot make two
  non-adjacent letter rows neighbours;
- "touch horizontally" = a shared vertical edge (`right == left`) — which is why two
  coincident rectangles (the degenerate geometry of the class #1 unit fixtures) produce **no**
  geometric neighbour, leaving the E3a tests unaffected;
- the 35% comparison is **exact integer arithmetic** (`100·overlap > 35·minWidth`), so no
  float rounding can diverge between the Kotlin engine and the offline generator.

No letter, pair or coordinate is hard-coded in the engine: `KeyNeighborTableGeometryTest`
and `E3bEngineSourceContractTest` assert the engine sources carry no Cyrillic literal, and
`scripts/typo_pack.py` reconstructs the same integer grid from `res/xml/rows_tatar.xml` +
`rowkeys_tatar*.xml`.

## 2. Measured geometric map

Reconstructed on the fixed integer grid (layout percent width ×1000) from the live layout:
the fifth row of six 16.667%p keys, two rows of eleven 9.091%p keys, and a bottom row of
nine 8.711%p letter keys offset by the 10.8%p shift key.

| Quantity | Value |
|---|---:|
| letter keys | **37** (= 6 + 11 + 11 + 9, `rowkeys_tatar*.xml`) |
| nodes (keys + more-key-only ё, ъ) | 39 |
| undirected geometric pairs | **65** |
| — same-row touching | 33 |
| — adjacent-row, added by the 35% rule | 32 |
| average fan-out (over 37 keys) | **3.51** |
| maximum fan-out | **5** |

Full sorted pair list (65):

```
ав ак ап ас бд бь бю ву вч вы гн го гш гҗ дж дл дщ ек ен еп еү жз жэ жю зх зщ зһ им ир ит
йф йц йә ку кө ло лш ль мп мс нр нҗ нү ор от пр сч ть уц уө фы хэ хһ цы цә чя шщ шң щң ыя
җң җү ңһ үө әө
```

The 35% rule is what keeps the wide fifth-row keys (ә ө ү җ ң һ) geometrically connected to
the narrower alphabet rows instead of being neighbours only of one another (e.g. ә↔й, ә↔ц,
ә↔ө; ү↔е, ү↔н; җ↔г, җ↔н). These numbers reproduce the geometric figures the E3a contract
recorded for the layout (37 keys, fan-out 3.51, max 5), which cross-checks the geometry model.

## 3. Reproducible sets — class #2 and class #3 (class #1 unchanged)

`scripts/typo_pack.py build --edit-class {1,2,3}` emits one deterministic
`original<TAB>typo_prefix` set per class, on the same `(seed=20260727, word)` selection
primitive as E3a and the same 3-code-point prefix window. The JVM
`E3bRecoveryCalibrationTest` rebuilds each set from the enumerated committed asset and asserts
the byte-identical SHA-256 — the cross-implementation proof that the offline model and the
engine derive the same geometry and selection.

| edit class | set size | SHA-256 |
|---|---:|---|
| #1 long-press partner (E3a) | 87 375 | `6a61b48db87ac0bbff78af48ea597b3af19f81dd42ae8deaa2d4c00a6c81dfc3` (**unchanged**) |
| #2 geometric neighbour | 99 659 | `8cd5b2b89663264d4bde505dfc80b0046951218e502dae997e1545105a8ed1cb` |
| #3 adjacent transposition | 99 647 | `914ae7cf66cc311ca86f49e146d511db4281dd1bf6cd70e23f7d1b69e1902197` |

The class #1 SHA is bit-for-bit the E3a value, verified both by the generator
(`--edit-class 1`) and by `E3bRecoveryCalibrationTest.everyExtendedTypoSetIsByteIdenticalToTheGeneratorRun`.

## 4. Measured variants and visited entries

Measured by the engine's per-lookup counters over the combined typo set (rows where the fuzzy
pass fired):

| Quantity | p50 | p95 | max | offline reference (contract) |
|---|---:|---:|---:|---|
| variants per lookup | 14 | **16** | 19 | p95 **33** |
| visited entries per lookup | 112 | **546** | 1 800 | p95 **133** |
| over-budget lookups | — | — | **0** | must never trip |

Reported, not tuned. The variant p95 (16) is below the offline reference (33) because the
recovery set uses 3-code-point prefixes (the shortest that fire the fuzzy pass), where the
combined class #1+#2+#3 fan-out is ~14; the offline "p95 33" reference was measured over a
different, longer prefix mix. The visited p95 (546) is above the offline reference (133)
because class #2's geometric variants scan common short-letter blocks, which are larger; this
is well inside `MAX_FUZZY_VISITED` (8192) and the budget never trips (`over_budget=0`), as
required by acceptance. Zero allocations per variant are preserved — the existing
`TdictPrefixIndexFuzzyTest.perLookupAllocationDoesNotDependOnTheNumberOfVariants` stays green.

## 5. recovery@3 after E3b and verdict

> **История — до правки ранжирования (class-agnostic frequency).** Числа этого раздела
> (combined **4.8350 %**, class #1 под полным движком **4.6272 %**) измерены до поправки к
> контракту «внутри нечёткого уровня вводится порядок по классу правки» (2026-07-27, вторая к
> этому пункту). Пере-калибровка после правки — в разделе «# E3b — recovery@3 после правки
> ранжирования по классу правки» в конце документа. Раздел оставлен как есть для истории.

Test: `E3bRecoveryCalibrationTest.recoveryAtThreeAfterE3b`. Methodology as fixed by the
contract amendment: typo inside the 3-code-point prefix window; denominator = the **whole**
combined set (class #1 + #2 + #3 rows); the full engine (all three classes) looks up each typo
prefix and the word is recovered if it lands in the top three.

Raw line (grep target `E3b recovery@3`, from `app/build/test-results/testDebugUnitTest`):

```
E3b recovery@3 seed=20260727 prefix_cp=3 combined_set=286681 recovered=13861 recovery@3=4.8350% baseline_exact_only=0.0000% class1_set=87375 class1_recovery@3=4.6272% class2_set=99659 class2_recovery@3=4.7663% class3_set=99647 class3_recovery@3=5.0860% class1_reference=7.2835% threshold=2.4x=17.4804% verdict=BELOW variant_p50=14 variant_p95=16 variant_max=19 visited_p50=112 visited_p95=546 visited_max=1800 over_budget=0 offline_ref_variant_p95=33 offline_ref_visited_p95=133
```

| Metric | Value |
|---|---:|
| recovery@3 (whole combined set, 286 681 rows) | **4.8350 %** (13 861 / 286 681) |
| baseline (exact only) | **0.0000 %** |
| class #1 recovery under the full engine | 4.6272 % |
| class #2 recovery under the full engine | 4.7663 % |
| class #3 recovery under the full engine | 5.0860 % |
| threshold (2.4 × 7.2835 % class #1 reference) | **17.4804 %** |
| **verdict** | **BELOW** |

**Verdict: recovery@3 (4.8350 %) is BELOW the 17.4804 % threshold.** It was **not** tuned:
neither the code, the test, the generator, nor the threshold was adjusted, per the contract
prohibition. The finding is reported and the decision is left to the orchestrator.

**Diagnosis — not a defect of the engine (21 JVM tests prove class #2/#3 behaviour):**

1. **Displacement by the contract-mandated class-agnostic ranking.** The contract ranks all
   fuzzy candidates by frequency alone (edit class does not affect ranking) and fills only the
   ≤ 3 cells the exact pass leaves empty. With ~14 variants per prefix (p50) each scanning a
   block, the ≤ 3 cells are filled by the highest-frequency words across all of them, so a
   *specific* target word rarely ranks in the top three. **Direct evidence:** adding classes
   #2/#3 to the engine *drops* class #1-typo recovery from **7.2835 %** (E3a, class #1-only
   engine) to **4.6272 %** (full engine) — the extra classes crowd out class #1 recoveries,
   and their own recoveries (~4.8–5.1 %) do not compensate. This is the very failure mode the
   contract's ranking note describes; the contract accepted it for *ranking quality* ("чистая
   частота даёт правильный ответ") but it also caps single-word *recovery*.
2. **The offline model that produced the 41.2 % figure (and hence the 2.4× threshold) is
   unreliable.** The E3a calibration already demonstrated this: the offline model predicted
   14.2 % for class #1 but the faithful engine measured 7.28 %. The offline 41.2 % for the
   combined classes evidently did not model cross-class displacement in the shared
   frequency-ranked fuzzy pool, so the threshold derived from it (2.4× = 17.48 %) is not
   reachable with the contract's ranking design on 3-code-point typo prefixes.

No code, test, generator, threshold or tolerance was changed to move the number.

## 6. Instrumental compute harness (`app/src/androidTest`)

E3b introduces the project's first instrumental test:
`app/src/androidTest/java/.../engine/E3bComputeInstrumentationTest.kt`. It runs the same 22
prefixes as `RealDictionaryPrefixIndexTest` through the full fuzzy engine (with a
layout-derived neighbour table) and logs p50 / p95 / maximum `compute` in milliseconds.

- **Offline & debug-only.** `testInstrumentationRunner = android.test.InstrumentationTestRunner`
  (the SDK's legacy runner — no external artifact, resolves offline). The runner/base classes
  are an `androidTestCompileOnly` dependency on the SDK's `android.test.runner.jar` /
  `android.test.base.jar`, so nothing is added to the compile classpath of `main` and nothing
  is packaged into the release APK.
- **Proven to add zero bytes to the release APK.** `dexdump` of the release `classes.dex`
  finds no `E3bComputeInstrumentationTest`, `InstrumentationTestCase` or
  `InstrumentationTestRunner` reference; the same class **is** present in the androidTest APK.
  The release manifest carries no `<instrumentation>`, no `<uses-library>` and the same single
  `android.permission.VIBRATE` — no new permission.
- **Compiles and assembles.** `./gradlew :app:assembleDebugAndroidTest` is **SUCCESSFUL**
  (androidTest APK `app-debug-androidTest.apk`, 45 452 B).
- **Measurement is NOT_COVERED.** No device is connected in this environment, so the harness
  is not executed; the device compute p50/p95/max is NOT_COVERED (reason: no device).

## 7. APK delta

Measured with `./gradlew :app:assembleRelease --offline`
(`app/build/outputs/apk/release/app-release.apk`).

| Artifact | Bytes |
|---|---:|
| post-E2 baseline (given) | 1 478 015 |
| post-E3a release APK | 1 480 299 |
| post-E3b release APK (measured) | **1 481 235** |
| E3b delta (over E3a) | **+936** |
| **E3 phase delta (over post-E2)** | **+3 220** |
| E3 phase budget | ≤ 15 360 |
| remaining under the E3 budget | 12 140 |
| remaining under the hard limit (3 145 728) | 1 664 493 |

The +936 B is entirely the class #2/#3 engine code and the geometric computation; no new
asset, no new mmap region, no new file in device-protected storage, no new permission. The
androidTest dependency and sources contribute 0 B to the release APK (proven above).

## 8. PSS

NOT_COVERED. PSS measurement is deferred by the owner's decision (contract amendment
2026-07-27); no device is connected. No PSS number is invented. The E3 own-phase delta budget
(≤ 0.5 MB, factor = build ON/ON) and the absolute ceiling remain NOT_COVERED with that reason.

## 9. Device-UAT matrix

No device is connected. Every row is `NOT_COVERED`; **none is PASSED**, and no PSS number is
invented.

| Item | Status | Device (model, serial) | Build (vName/vCode, APK SHA-256) | Date | Raw numbers / path |
|---|---|---|---|---|---|
| Device compute p50/p95/p95-max on the review-prefix sample (E3b harness) | NOT_COVERED — no device connected; harness compiles & assembles but is not run | — | — | — | — |
| Device compute p95 on a 100%-fuzzy set ≤ 3.5 ms (E3c input-gate precondition) | NOT_COVERED — no device connected | — | — | — | — |
| recovery@3 on real device typing (≥ 50 hand-typed typos) | NOT_COVERED — no device connected | — | — | — | — |
| Fuzzy candidates visibly indistinguishable from exact ones (no colour/icon/suffix, no separate a11y node) | NOT_COVERED — no device | — | — | — | — |
| Fuzzy pass never shifts or replaces an exact candidate, live | NOT_COVERED — no device | — | — | — | — |
| Geometric neighbour relation correct on the real live keyboard geometry | NOT_COVERED — no device (host uses reconstructed geometry) | — | — | — | — |
| PSS delta with the fuzzy level on vs off (≤ 0.5 MB) | NOT_COVERED — PSS deferred by owner (amendment 2026-07-27) | — | — | — | — |
| Absolute PSS within the (uncomputed) ceiling | NOT_COVERED — ceiling not recomputed; PSS deferred | — | — | — | — |
| No crash/ANR on live layout/table change (rotation, theme, subtype) | NOT_COVERED — no device | — | — | — | — |

An emulator run, had one been performed, would be a separate row marked `NOT_COVERED` with
"emulator, reference only"; none was performed for E3b.

## 10. Tests on input / output (E3b)

| | JVM (`:app:testDebugUnitTest`) | Python (`tests/typo_pack`) |
|---|---:|---:|
| before E3b (E3a close-out) | 417 | 27 |
| added in E3b | **21** | **13** |
| after | **438** | **40** |
| failures / errors | 0 | 0 |
| skipped | **0** | **0** |

The 21 new JVM tests: `E3bRecoveryCalibrationTest` (3 — set byte-identity, recovery@3, fuzzy
delta on the 22 prefixes), `KeyNeighborTableGeometryTest` (4), `FuzzyPrefixVariantsE3bTest`
(6), `TdictPrefixIndexE3bTest` (6), `E3bEngineSourceContractTest` (2). None is `@Ignore`d or
skipped — verified by summing the `<testsuite>` `tests`/`skipped` attributes across
`app/build/test-results/testDebugUnitTest/*.xml` (438 / 0). `:app:assembleDebugAndroidTest`
is SUCCESSFUL. `lintVitalRelease` is BUILD SUCCESSFUL. `scripts/check-no-internet.sh` passes
on both the debug and release APKs.

## 11. Regeneration

```
python3 scripts/typo_pack.py build --edit-class 1 \
  --dictionary app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib \
  --layout-dir app/src/main/res/xml --output /tmp/e3_class1.txt   # SHA-256 must equal §3
python3 scripts/typo_pack.py build --edit-class 2 ... --output /tmp/e3_class2.txt
python3 scripts/typo_pack.py build --edit-class 3 ... --output /tmp/e3_class3.txt
python3 -m unittest tests.typo_pack.test_typo_pack
./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintVitalRelease :app:assembleRelease
```

The 22-everyday-prefix fuzzy deltas after E3b are in `docs/DICTIONARY-E3-TYPO-REVIEW.tsv` (all
`pending`; on all 22 correctly-typed prefixes the E3b fuzzy pass adds **zero** inexact
candidates — no noise on correct input). One summary reference row remains in
`docs/TATAR-REVIEW-QUEUE.tsv`.

---

# E3b — recovery@3 после правки ранжирования по классу правки

Дата: 2026-07-27. Раздел — пере-калибровка recovery@3 после **поправки к контракту (вторая
к этому пункту)**: «внутри нечёткого уровня вводится порядок по классу правки: сначала класс
№1 (long-press партнёр), затем №2 (геометрический сосед), затем №3 (перестановка); внутри
одного класса — прежний порядок frequency desc, затем Unicode code-point asc. Точный уровень
остаётся выше любого нечёткого и его правило не меняется.» Числа §5 выше — история (до правки).

## Что изменилось в коде

Единственный правленый production-файл — `TdictPrefixIndex.kt`. Внутри нечёткого уровня перед
частотой добавлен ключ **класс правки**, несомый примитивом `int` (`EDIT_CLASS_LONG_PRESS=1`,
`EDIT_CLASS_GEOMETRIC=2`, `EDIT_CLASS_TRANSPOSITION=3`; точные — `EDIT_CLASS_EXACT=0`). Класс
кладётся в параллельный `IntArray`, аллоцированный один раз в конструкторе; на вариант — ноль
аллокаций (существующий `perLookupAllocationDoesNotDependOnTheNumberOfVariants` остаётся
зелёным, `many <= 8` байт/lookup). `ranksBefore` теперь сравнивает класс (asc) → частоту (desc)
→ кодпоинт (asc). Точный уровень не тронут: точные кандидаты живут в отдельном массиве, все
несут `EDIT_CLASS_EXACT`, поэтому класс среди них — тождественная ничья, а точные всегда
сливаются перед нечёткими.

## Наборы, методика, seed, порог — НЕ менялись

Наборы, генератор, seed (20260727), окно 3 кодпоинта, порог 2,4× = **17,4804 %** и допуск
±1,0 п.п. взяты дословно из существующих тестов. Набор класса №1 остаётся байт-в-байт
**SHA-256 `6a61b48db87ac0bbff78af48ea597b3af19f81dd42ae8deaa2d4c00a6c81dfc3`** (проверено
`E3bRecoveryCalibrationTest.everyExtendedTypoSetIsByteIdenticalToTheGeneratorRun` и печатается
в сырой строке E3a: `set_sha256=6a61b48…`). Ничего не подгонялось.

## Сырые строки замеров (после правки)

`E3bRecoveryCalibrationTest` — recovery@3 по объединённому набору классов №1–№3 (порог
≥ 17,4804 %):

```
E3b recovery@3 seed=20260727 prefix_cp=3 combined_set=286681 recovered=13966 recovery@3=4.8716% baseline_exact_only=0.0000% class1_set=87375 class1_recovery@3=7.2835% class2_set=99659 class2_recovery@3=4.8556% class3_set=99647 class3_recovery@3=2.7728% class1_reference=7.2835% threshold=2.4x=17.4804% verdict=BELOW variant_p50=14 variant_p95=16 variant_max=19 visited_p50=112 visited_p95=546 visited_max=1800 over_budget=0 offline_ref_variant_p95=33 offline_ref_visited_p95=133
```

`E3aRecoveryCalibrationTest` — recovery@3 на опечатках ТОЛЬКО класса №1 (порог не ниже
7,2835 % в пределах ±1,0 п.п.):

```
E3a recovery@3 class#1 seed=20260727 prefix_cp=3 set=87375 recovered=6364 recovery@3=7.2835% baseline_exact_only=0.0000% fuzzy_fired=49155 recovery@3_when_fuzzy_fired=12.9468% variant_p50=2 variant_p95=3 variant_max=6 contract=14.2% delta=-6.9165pp within_1.0pp=false set_sha256=6a61b48db87ac0bbff78af48ea597b3af19f81dd42ae8deaa2d4c00a6c81dfc3
```

## Вердикт по каждому условию отдельно

| Условие | Порог | После правки | Вердикт |
|---|---|---:|---|
| (а) recovery@3 по объединённому набору №1–№3 | ≥ 17,4804 % | **4,8716 %** (13 966 / 286 681) | **BELOW** — не выполнено |
| (б) recovery@3 только на классе №1 | ≥ 7,2835 % (±1,0 п.п.) | **7,2835 %** (6 364 / 87 375), Δ = 0,0 п.п. | **PASS** — выполнено |

**ОБА условия одновременно — НЕ выполнены** (условие (а) провалено). Приёмка этой правки
строже обычной и требует ОБОИХ; следовательно правило контракта об этом случае вступает в
силу: «классы №2 и №3 из поставляемого нечёткого прохода исключаются, а вывод „расширение
классов правок под этим ранжированием пользы не даёт“ записывается письменно». Решение — за
оркестратором; здесь фиксируются числа и диагноз, ничего не подгоняется.

## Диагноз — почему объединённый набор не дотянул

Динамика по классам (полный движок, до → после правки ранжирования):

| Класс | Набор | recovery@3 до (class-agnostic) | recovery@3 после (class-ordered) | Δ |
|---|---:|---:|---:|---:|
| №1 long-press | 87 375 | 4,6272 % | **7,2835 %** | **+2,66 п.п.** |
| №2 геометрический | 99 659 | 4,7663 % | **4,8556 %** | +0,09 п.п. |
| №3 перестановка | 99 647 | 5,0860 % | **2,7728 %** | **−2,31 п.п.** |
| **объединённый** | 286 681 | 4,8350 % | **4,8716 %** | +0,04 п.п. |

Правка сделала ровно то, что обещала для класса №1: восстановление на опечатках класса №1
вернулось с 4,6272 % (полный движок, class-agnostic) к **7,2835 %** — в точности к значению
самой E3a, потому что кандидаты класса №1 теперь заполняют пустые ячейки первыми и не
вытесняются частыми словами из вариантов классов №2/№3. **Побочный эффект и есть причина
недобора:** порядок по классу фиксирован №1 > №2 > №3 независимо от того, к какому классу
принадлежит фактическая опечатка. Для опечатки класса №3 (перестановка) правильное слово
находится вариантом класса №3, но его ранг теперь ниже шумовых кандидатов классов №1 и №2 из
тех же ≤ 3 ячеек, поэтому его восстановление падает 5,0860 % → 2,7728 %. Выигрыш класса №1
(+2 320 слов) почти точно гасится потерей класса №3 (−2 305 слов), и объединённое число
остаётся ~4,87 %, на порядок ниже порога 17,4804 %.

Фундаментальная причина недостижимости порога — не ранжирование, а то, что офлайн-модель,
предсказавшая 41,2 % (и породившая порог 2,4×), не моделировала конкуренцию ~14 вариантов на
запрос за ≤ 3 ячейки; та же модель уже ошиблась на классе №1 (предсказывала 14,2 %, измерено
7,2835 %). Порядок по классу возвращает сигнал «ближайшего варианта» только когда приоритет
класса совпадает с фактическим классом опечатки — то есть помогает классу №1 и почти не
трогает №2, но вредит №3. Порог 17,4804 % под любым ранжированием, не имеющим сигнала
близости к набранному per-query, на 3-кодпоинтных префиксах не достигается.

Замечание для истории: на текущем HEAD *до* этой правки существующий `E3aRecoveryCalibrationTest`
печатал уже не 7,2835 %, а 6,6175 % — потому что в E3b в движок добавились классы №2/№3, и
перестановочный шум вытеснял часть восстановлений класса №1 под общей частотой. Правка
ранжирования вернула этот тест ровно к 7,2835 % (см. §4), что и служит независимым
подтверждением условия (б).

## APK delta

Измерено `./gradlew :app:assembleRelease --offline`
(`app/build/outputs/apk/release/app-release.apk`), `stat -f %z`.

| Артефакт | Байт |
|---|---:|
| post-E2 baseline (дано) | 1 478 015 |
| post-E3a | 1 480 299 |
| post-E3b (до правки ранжирования) | 1 481 235 |
| **post правки ранжирования (измерено)** | **1 481 399** |
| дельта над предыдущим E3b | **+164** |
| **дельта фазы E3 над post-E2** | **+3 384** |
| бюджет фазы E3 | ≤ 15 360 |

+164 Б — это ключ класса правки (два `IntArray` в конструкторе, поле-примитив, сравнение по
классу). Ни ассета, ни разрешения не добавлено.

## Тесты

| | JVM (`:app:testDebugUnitTest`) |
|---|---:|
| до этой правки | 438 |
| добавлено здесь | **4** |
| после | **442** |
| failures / errors | 0 |
| skipped | **0** |

Четыре теста — `TdictPrefixIndexEditClassRankingTest` (условие (3) процедуры, по одному на пункт
поправки): `class1CandidateAlwaysOutranksClass2CandidateAtAnyFrequency`,
`withinOneClassTheOrderIsFrequencyDescendingThenCodePointAscending`,
`anExactCandidateAlwaysOutranksAnyFuzzyCandidate`,
`withASingleEditClassTheOrderMatchesE3a`. Ни один не `@Ignore`d и не skipped — сумма
`<testsuite>` `tests`/`skipped` по `app/build/test-results/testDebugUnitTest/*.xml` = **442 / 0**.
Существующие тесты не менялись: аллокационный
`TdictPrefixIndexFuzzyTest.perLookupAllocationDoesNotDependOnTheNumberOfVariants`, четыре
поимённых E3a-теста ранжирования и все E3b-тесты остались зелёными без правок (в их фикстурах
конкурирующие нечёткие кандидаты принадлежат одному классу, поэтому новый ключ порядок не
меняет). `assembleDebug`, `lintVitalRelease`, `assembleRelease` — BUILD SUCCESSFUL, всё offline.

---

# Отключение классов №2 и №3 от поставляемого нечёткого прохода (2026-07-27)

Всё выше — история: калибровки E3a/E3b, правка ранжирования и её замеры остаются как есть.
Этот раздел фиксирует **поставляемое** решение по итогу вердикта оркестратора (PROPOSALS.md,
раздел «Контракт текста», строка «Итог, 2026-07-27»): оба условия приёмки E3b не выполнены,
поэтому **классы №2 (геометрический сосед) и №3 (перестановка) исключаются из поставляемого
нечёткого прохода; правка ранжирования остаётся**.

## Что именно поставляется

Нечёткий проход **только класса №1** (long-press партнёр). Поведение и число — ровно E3a:
recovery@3 = **7,2835 %** при базовой линии 0,0000 %. Класс №1 работает как раньше; правило
регистра, отсечения по три ячейки, порог 3 кодпоинта, exact-word exclusion и весь путь тапа не
затронуты.

## Одно место переключения

Переключатель — **одна** именованная константа в `TdictPrefixIndex`:

```kotlin
internal val SHIPPED_FUZZY_EDIT_CLASSES = intArrayOf(EDIT_CLASS_LONG_PRESS)
```

`collectFuzzy` запускает генератор класса только если его `EDIT_CLASS_*` входит в этот набор;
класс №1 входит, классы №2 и №3 — нет, поэтому они **недостижимы с живого пути `lookup()`**. Ни
нового состояния, ни пользовательского тумблера не заведено. Вернуть №2/№3 на живой путь — это
однострочное изменение этого набора. Source-contract тест
`TdictPrefixIndexShippedFuzzyClassesTest.theShippedFuzzyPassEnablesOnlyEditClassOne` читает эту
константу и утверждает, что включён ровно класс №1.

## Что осталось инфраструктурой (код не удалён, продолжает покрываться тестами)

- генераторы вариантов №2/№3 — `FuzzyPrefixVariants.generateGeometricVariants` /
  `generateTranspositionVariants` (прямые тесты `FuzzyPrefixVariantsE3bTest` — зелёные);
- геометрическая карта соседства — `KeyNeighborTable.geometricNeighborsOf`,
  `KeyNeighborTableBuilder`, фикстура `E3bTestFixtures` (тесты `KeyNeighborTableGeometryTest` —
  зелёные);
- порядок по классу правки в `ranksBefore` (класс asc → частота desc → кодпоинт asc) — сохранён
  без изменений; при единственном поставляемом классе это тождественная ничья, из-за чего
  восстановление класса №1 инвариантно к присутствию №2/№3 (см. ниже);
- инструментальный харнесс `app/src/androidTest` — `E3bComputeInstrumentationTest` (компилируется
  и пакуется в `:app:assembleDebugAndroidTest`; на устройстве по-прежнему меряет классы №1+№2+№3
  с живой геометрией — от вердикта по качеству не зависит);
- калибровочные наборы №2/№3 строятся напрямую из карты соседства и по-прежнему проверяются
  байт-в-байт (`E3bRecoveryCalibrationTest.everyExtendedTypoSetIsByteIdenticalToTheGeneratorRun`).

## Итоговые числа (перемерено существующими калибровочными тестами)

**Поставляемое поведение — recovery@3 на опечатках класса №1 (`E3aRecoveryCalibrationTest`):**

```
E3a recovery@3 class#1 seed=20260727 prefix_cp=3 set=87375 recovered=6364 recovery@3=7.2835% baseline_exact_only=0.0000% fuzzy_fired=49155 recovery@3_when_fuzzy_fired=12.9468% variant_p50=2 variant_p95=3 variant_max=6 contract=14.2% delta=-6.9165pp within_1.0pp=false set_sha256=6a61b48db87ac0bbff78af48ea597b3af19f81dd42ae8deaa2d4c00a6c81dfc3
```

recovery@3 = **7,2835 %** — ровно значение E3a, отклонение 0,0 п.п. Правка ранжирования
гарантирует эту инвариантность: кандидаты класса №1 всегда ранжируются выше любого №2/№3, поэтому
их удаление с живого пути не меняет ни одной ячейки, занятой кандидатом класса №1.

**Диагностическое — recovery@3 по объединённому набору №1–№3 через тот же поставляемый путь
(`E3bRecoveryCalibrationTest`):**

```
E3b recovery@3 seed=20260727 prefix_cp=3 combined_set=286681 recovered=6402 recovery@3=2.2331% baseline_exact_only=0.0000% class1_set=87375 class1_recovery@3=7.2835% class2_set=99659 class2_recovery@3=0.0381% class3_set=99647 class3_recovery@3=0.0000% class1_reference=7.2835% threshold=2.4x=17.4804% verdict=BELOW variant_p50=2 variant_p95=3 variant_max=6 visited_p50=0 visited_p95=168 visited_max=609 over_budget=0 offline_ref_variant_p95=33 offline_ref_visited_p95=133
```

Объединённое recovery@3 = **2,2331 %** (диагностическое, не критерий приёмки). Классы №2/№3 больше
не генерируются на живом пути, поэтому их наборы почти не восстанавливаются (class2 = 0,0381 %,
остаточное перекрытие через класс №1; class3 = 0,0000 %), а класс №1 остаётся 7,2835 %. Для
сравнения — историческое число полного движка до отключения было 4,8716 % (см. раздел «Сырые
строки замеров (после правки)» выше). `over_budget=0`.

## Тесты (это изменение)

| | JVM (`:app:testDebugUnitTest`) |
|---|---:|
| до этого изменения | 442 |
| добавлено здесь | **4** |
| после | **446** |
| failures / errors | 0 |
| skipped | **0** |

Добавлены (source-contract + функциональные), файл `TdictPrefixIndexShippedFuzzyClassesTest`:
`theShippedFuzzyPassEnablesOnlyEditClassOne` (в поставляемом наборе — только класс №1, видно в
одном названном месте), `onAClass2PrefixTheShippedResultEqualsTheClass1OnlyResult`,
`onAClass3PrefixTheShippedResultEqualsTheClass1OnlyResult` (на префиксе, где №2/№3 дали бы
совпадающий вариант, живой результат = результат только-№1), `class1StillRecoversWhileItsClass2SiblingIsDropped`.

Тесты, проверявшие живой путь с №2/№3 и приведённые к новому решению:
`TdictPrefixIndexE3bTest.geometricNeighbourTypoIsRecoveredIntoAnEmptyCell` →
`geometricNeighbourTypoIsNotRecoveredBecauseClass2IsOffTheShippedPath`;
`transpositionTypoIsRecoveredIntoAnEmptyCell` →
`transpositionTypoIsNotRecoveredBecauseClass3IsOffTheShippedPath`; а также реформулированы
`exactCandidatesAreNeverShiftedByTheGeometricOrTranspositionPasses`,
`aWordReachableByTwoClassesStillOccupiesOnlyOneCell`, `theResultIsDeterministicAcrossManyRepeats`;
`TdictPrefixIndexEditClassRankingTest.class1CandidateAlwaysOutranksClass2CandidateAtAnyFrequency` →
`theClass2CandidateNeverAppearsBecauseClass2IsOffTheShippedPath` (класс №2 больше не появляется на
живом пути — гарантия сильнее прежней). Прямые тесты генераторов №2/№3 и геометрии не менялись и
остались зелёными.

## APK delta

Измерено `./gradlew :app:assembleRelease --offline`, `stat -f %z`.

| Артефакт | Байт |
|---|---:|
| post-E2 baseline (дано) | 1 478 015 |
| post правки ранжирования (до отключения) | 1 481 399 |
| **post отключения классов №2/№3 (измерено)** | **1 481 503** |
| дельта над предыдущим | **+104** |
| **дельта над post-E2 baseline** | **+3 488** |
| бюджет фазы E3 | ≤ 15 360 |

+104 Б — набор `SHIPPED_FUZZY_EDIT_CLASSES` и три проверки принадлежности в `collectFuzzy`. Ни
ассета, ни разрешения не добавлено. `assembleDebug`, `assembleDebugAndroidTest`,
`lintVitalRelease`, `assembleRelease` — BUILD SUCCESSFUL, всё offline.

---

> **Сноска, 2026-09-20 (TT-TYPO-NEXT Phase B, docs/TT-TYPO-NEXT.md).** Офлайн-модель геометрии
> этого документа (и старая `E3bTestFixtures`) НЕ учитывала `horizontalGap` (1.739 %p,
> `res/values/config.xml`): на устройстве `KeyboardRow` вычитает зазор из ширины каждой клавиши,
> поэтому `right == left` для соседей по ряду не выполняется никогда — 33 «same-row» пары из
> таблицы §2 (65 пар) на устройстве не существуют, выживают ровно 32 кросс-рядные (включая ц↔ә).
> Равенство исправленной офлайн-модели устройству доказано дампом живой клавиатуры на POCO C71
> (32/32, побайтно). Замеры E3a/E3b выше сняты на 65-парной модели по обе стороны (движок через
> фикстуру и набор опечаток) — самосогласованы, но не device-true; это не меняет ни вердиктов
> (порог 17.48 % мёртв методологически независимо от модели), ни решения об отключении классов.
> С того же дня `SHIPPED_FUZZY_EDIT_CLASSES` заменён per-engine `FuzzyEditPolicy`
> (`TdictPrefixIndex.open`), а `E3bComputeInstrumentationTest` реально прогнан на устройстве.
