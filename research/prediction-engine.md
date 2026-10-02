# Prediction engine research

How to improve word completion and next-word prediction for Tatar and Russian. Proof rules:
`research/measurement-framework.md`. Sources are linked inline; measured numbers are stated
with their harness and were true at research time — the pins in the repo are the current
truth.

## Current state

Engine: binary-search prefix lookup over the front-coded dictionary (TATDICT v2), at most one
personal word merged in, then typo recovery (edit class #1 for both languages, class #4 for
Tatar only). Next-word: form-keyed bigram table (TATBIGR v3, ≤4 successors per head) →
learned pairs → generated Tatar word forms → top-frequency fallback. Autocorrect on
unambiguous class-#1 typos above a frequency gate.

Measured baseline at research time (`scripts/suggest_eval.py`, pinned held-out Tatar set):

- Dictionary covers 94.3% of eval tokens but 88.9% of types — the 5.4 pp gap is the
  morphology tax.
- 45.1% of eval tokens carry a known inflectional suffix; 64.1% of the shipped Tatar
  dictionary entries are generated inflected forms.
- Bigram head coverage 84.2%; next-word top-3 hit 10.8% overall, 12.9% on covered heads —
  large headroom, concentrated in uncovered heads and form-fragmented successors.

## What the world does

Production keyboards:

- Gboard: Katz-smoothed interpolated 5-gram FST, ~1.25M n-grams over a 164k unigram vocab,
  plus *separate, interpolated* personal LMs from user history (Hard et al. 2018,
  arxiv.org/abs/1811.03604). Their neural CIFG replacement (~1.4 MB quantized) beat the
  n-gram baseline mostly thanks to training on real typed text — closed to us offline.
  Context is applied *jointly during* completion scoring, not as post-hoc rerank (Ouyang et
  al. 2017, arxiv.org/abs/1704.03987 §4.4).
- Samsung Keyboard for Korean: morpheme+syllable embeddings defeated the word-form OOV
  problem and measurably moved keystroke savings in a shipped product (Yu et al. 2017,
  aclanthology.org/W17-4113). The mechanism (neural) is closed to us; the problem framing is
  ours.
- GiellaLT/Divvun (Sámi and other minority agglutinative languages) is the closest living
  precedent to our architecture: linguist-maintained FST morphology compiled offline into
  binary mobile assets, running in an AOSP LatinIME fork (giellakbd-android, Apache-2.0).
  Their runtime is native (Rust/C++) and their data is GPL — pattern only, no borrowing.
- SwiftKey X (2011), Gboard (2016), iOS QuickType (iOS 10): all interleave multiple active
  languages in one strip. Our companion-language design (the second language only fills empty
  cells) is the most conservative of the documented ones.

Research literature:

- LM context is the single largest quality lever for *noisy full-word decoding* (Goodman et
  al. 2002: error ÷1.67–1.87; Fowler et al. 2015: 38.4% → 5.7% WER). But n-best rescoring
  gains are small when the base model matches the domain (Mikolov 2011, ~1% absolute), and a
  3-candidate rerank can only permute ranks — its ceiling is (top-3 − top-1 hit) × P(bigram
  evidence is right). This explains why our parked glide rerank patch (A6) measured below its
  gate: post-hoc rerank over ≤3 candidates with an 84%-coverage, form-fragmented bigram table
  is structurally weak. Joint scoring is where context pays.
- Morph-keyed statistics pool counts across paradigms: Hirsimäki et al. 2006 and Kurimo et
  al. 2006 built n-grams over morphs for Finnish ASR and eliminated OOV. Our 45% inflected
  token share is exactly the regime those papers address.
- Modified Kneser-Ney is the reference smoothing (Chen & Goodman, Harvard TR-10-98); its key
  insight for us is the *continuation count* (number of distinct left contexts) as the right
  backoff marginal — raw frequency over-promotes context-bound closed-class words. Pruning is
  nearly free on big models but dangerous on already-small ones (Chelba et al. 2010) — our
  tables are small, so cut carelessly and we lose smoothing mass.
- Higher-order word-level n-grams pay off only with dense data (Gboard's 5-gram is cut to
  1.25M n-grams from Google's corpus). Our own parked trigram experiment agrees: context
  coverage, not successor ranking, was the bottleneck. Reopen only as stem-level units.
- Personalization: Fowler et al. 2015 found optimum at background 0.8 / personal cache 0.2
  with an infinite window, and — decisively for us — *exponential recency decay measured no
  better than a uniform cache*. Our LRU + usage counters already sit at the published
  optimum; do not build decay.
- Evaluation: Gboard gates on top-1 recall (the center cell is what users read); Smart
  Compose (Chen et al. 2019, KDD) compares models at equal suggestion coverage because
  perplexity-style gains in low-confidence regions do not move task metrics; perplexity/WER
  correlation is unreliable for small deltas (Klakow & Peters 2002). Our assets store counts,
  not probabilities — perplexity is not even computable; top-k recall and keystroke savings
  are the honest signals. Keystroke-savings numbers need oracle framing (Trnka & McCoy 2008)
  and overstate real gains because visual search of the strip costs attention (MacKenzie
  2002: lists beyond 2 candidates can be a net loss with slow selection).
- Split hygiene: random splits are optimistic and can flip system rankings (Gorman & Bedrick
  2019; Søgaard et al. 2021); near-duplicate leakage inflates LM evals (Lee et al. 2022);
  for agglutinative languages, sentence-level splits leak lemmas across train/held-out — the
  honest eval stratifies by seen-form / new-form-of-seen-stem / unseen-stem.

Open-source engines (license check: project is Apache-2.0, no GPL code, no NDK):

| Project | License | Engine | Verdict |
|---|---|---|---|
| AOSP LatinIME | Apache-2.0 | C++ Patricia trie, proximity model, per-node bigrams | Ideas borrowable; engine is C++/frozen; our format is already better pinned |
| AnySoftKeyboard | Apache-2.0 | Java orchestration + JNI trie | **Borrowable**: allocation-free Damerau–Levenshtein (~40 lines, `IMEUtil.editDistance`), per-position fuzzy key alternatives (`WordComposer`), abbreviation/expansion dictionary |
| FlorisBoard | Apache-2.0 | none shipped (predictive text is milestone 0.6) | Nothing to take; re-survey at 0.6 |
| OpenBoard, HeliBoard | GPL-3.0 | LatinIME C++ | No code; HeliBoard's glide needs a closed Google library — validates our own decoder's value |
| FUTO Keyboard | Source First 1.1 (non-OSI) | GGML transformer rescoring, NDK, downloaded models | Triple-disqualified: license, NDK, network |
| Simple Keyboard | Apache-2.0 | none (removed by design) | Our ancestor; prediction layer is entirely ours |

## Corpus sources (build-time only; the APK ships frequencies, never text)

| Source | Size | License | Verdict |
|---|---|---|---|
| corpus.tatar wordform frequency lists | counted over 500M+ edited words | No terms stated | **Best single Tatar ranking lever; needs written permission (tatcorpus@gmail.com)** |
| HPLT v2/v3 `tat_Cyrl` | ~297M words, v3 has register labels | CC0 packaging | Top unblocked source for unigrams *and* bigrams; filter Russian bleed |
| MADLAD-400 tt | ~60M tokens | CC BY 4.0 | Clean cross-validation source; keep words attested in both |
| tt.wikipedia | ~216M words | CC BY-SA 4.0 | Usable with attribution; SA-on-frequency-list is a gray zone — needs an explicit decision |
| Taiga (social/subtitles segments) | large | CC BY-SA 3.0 | The only clearly licensed conversational Russian beyond OpenSubtitles |
| Common Voice tt | small | CC0 | Spoken register; better as an extra *eval* set than training data |
| RNC, Lyashevskaya–Sharov lists | — | research-only | Excluded (incompatible with shipping) |
| azatliq.org, tatar-inform, kitaphane, Telegram/VK dumps | — | all-rights-reserved / personal data | Excluded |

## Harness prerequisites (build these first — they unblock all gates below)

1. **Composite next-word hit measurement**: today the harness pins chain emptiness, not hit
   rate; extend `suggest_eval.py` + `TtSuggestEvalTest` to measure top-3 of the full chain
   (bigrams + pairs + forms + fallback).
2. **Typo-mutated held-out set**: deterministic mutations (adjacent-key substitution,
   deletion, insertion, transposition) of eval tokens; without it, wider typo classes cannot
   be measured. Also gives the autocorrect false-trigger gate (run the autocorrect path over
   correctly typed words; pin the rate).
3. **Russian held-out set**: mirror `make_eval_set.py` for ru (untrained Tatoeba-ru rows,
   `RUSSIAN_ALPHABET` normalization, dedup against all training corpora). Record the baseline;
   future changes gate on its delta. Closes measurement-framework gap #3.
4. **Decontamination**: extend the eval-set exclusion to normalized sentences of *all*
   training corpora (today only the conv-train split is excluded; Leipzig overlap leaks).
5. **Statistics**: sentence-level paired bootstrap CIs and top-1 recall pins in both
   harnesses; minimum-detectable-effect table so sub-noise "wins" stop reopening measured
   rejections.
6. **Lemma stratification**: label every eval token seen-form / new-form-of-seen-stem /
   unseen-stem; the same-stem boost must win on the middle stratum specifically.
7. **Keystroke-savings simulator**: replay eval sentences with strip taps at cost 1; report
   KS% with the vocabulary-oracle bound next to it.

## Decision memos (ranked)

Gates below are proposals to pre-register before any experiment, per the framework.

**P1 — Stem-keyed bigram backoff (morphology).** Pipeline: lemmatize the dictionary into
stem clusters (`wordform_gen.py group` already computes form→stem maps offline), retrain
bigrams keyed by (stem, stem) with counts summed over forms, pack as a second TATBIGR-schema
table. Runtime: when the form-keyed pass fills fewer than 3 cells, query the stem table and
expand each stem-successor to its most frequent attested form. Sits between bigrams and
after-word forms. Gate: ≥ +1.5 pp overall next-word top-3 on the pinned harness (run the
stem-table simulation offline first; touch the engine only if the simulated gate passes), no
completion regression, asset delta within the table budget, next-word p95 unchanged. Risk:
the stem does not carry the successor's case — measure the expansion's hit rate, not just the
stem hit rate.

**P2 — Wider typo classes (insertion/deletion/transposition) ranked by edit distance.**
Re-implement AnySoftKeyboard's allocation-free Damerau–Levenshtein in Kotlin as the ranker;
new edit classes alongside #1/#4, hard beam cap. Gate: top-3 on the typo-mutated held-out set
(prerequisite 2), device p95 within the existing budget. Expected: the largest realistic
top-3 gain — substitutions are a minority of real typos. Watch: candidate-count blowup needs
fail-fast caps (ASK's `GestureTypingDetector` pattern).

**P3 — corpus.tatar + HPLT/MADLAD ingestion.** Ingest the corpus.tatar frequency lists
(counted over 500M edited words) and HPLT v2 `tat_Cyrl` (CC0) with per-word Russian-bleed
filtering and per-source merge weights. Licensing decision (operator): use all surveyed
sources for now, review deferred. Gate: ≥ +0.5 pp held-out conversational coverage at fixed
dictionary size; top-3 on the pinned harness must not regress (register skew is the risk —
news/web frequencies can hurt a conversational metric if merged naively).

**P4 — Pack-time Kneser-Ney successor re-ranking.** Rank each head's kept successors by the
interpolated KN score (continuation counts from one extra pass; discount and λ fixed from
leave-one-out on the training side, never on eval); schema and runtime unchanged — zero
bytes, zero milliseconds. Gate: ≥ +0.3 pp unconditional next-word top-3. Honestly uncertain
(successors are already count-ordered); cheap to falsify. Decide first whether continuation
counts cover in-vocabulary pairs only or all token transitions, and record the choice.

**P5 — Continuation-count fallback pool.** Replace the raw-frequency fallback pool with
top-N by continuation count N₁₊(•,w) (a small shipped text asset). Gate: ≥ +0.3 pp
unconditional top-3 on the extended harness (prerequisite 1). Risk: high-context particles
crowding out rarer correct words — review the produced list as product, not only as metric.

**P6 — Bigram head expansion at K=4, funded by successor singleton cutoffs.** The head
expansion was measured but not shipped (byte discipline); the byte argument is weaker now.
Gate: ≥ +0.3 pp unconditional over current pins, within the compressed table budget. Lesson
from the last round: surgical conversational heads beat blanket frequency heads — domain
match beats head count.

**P7 — Letter-set language detection for companion priority.** When the active prefix is
impossible in the active language (contains `ә җ ң ө ү һ` under ru, or `щ` under tt) and its
exact pass is empty, the companion language's exact candidates may lead instead of only
filling. Guard: zero order change on the monolingual evals; effect size needs on-device
validation (no tt–ru mixing corpus exists). Aligns with what SwiftKey/Gboard/iOS ship.

**P8 — Score-based promotion of high-usage personal words.** A learned word whose usage
crosses a threshold may take the top cell when the dictionary's top exact candidate is below
a frequency gate (both numbers already exist in memory). Requires a Fowler-style
learn-then-type simulation harness (prime the store from one half of the eval stream, measure
the other) — that harness is the real deliverable and unlocks all future personalization
work. Guard: dictionary-only users see zero churn; strip order stays stable within a word's
lifetime (each reorder re-incurs the attend-and-evaluate cost of a suggestion — Quinn & Zhai,
CHI 2016).

**P9 — Tap-completion context rerank (prefix path), python-first.** Never measured (A6 was
glide-only, and A6 is now closed — the operator confirmed the threshold). Simulate before
touching app code: boost exact-pass candidates that are bigram successors of the previous
word. Expected below the P1 bar — exact-prefix candidates are already constrained and
successor mass is form-fragmented — but cheap to falsify.

**P10 — Abbreviation / quick-fix dictionary (product feature).** User-defined
abbreviation→expansion candidates (AnySoftKeyboard precedent, Apache-2.0) over the existing
personal-store plumbing; possibly a small shipped tt/ru table. No hit-rate movement expected;
gate on feature tests, not eval.

## Rejected / parked with reasons

- Runtime neural LM, SentencePiece/BPE at runtime, FUTO-style rescoring: size, NDK, network,
  or license — each disqualifies alone. Their measured value comes substantially from typed-
  text training data, which offline projects cannot have.
- KenLM code (LGPL): ideas only. Our sorted-array/CSR formats are already at the small-and-
  fast-enough end for our scale.
- Trigram prediction: stays rejected (measured; consistent with the density literature). The
  only honest reopen path is stem-level units — covered by P1.
- Exponential recency decay for personal stores: measured no better than uniform cache
  (Fowler 2015); our LRU + counters are at the published optimum.
- Stem+affix dictionary repack (Hunspell-style): no size pressure; front-coding already
  clusters forms after stems. Park in `docs/BACKLOG.md`.
- Apertium-tat / GiellaLT / kaikki / UniMorph as *shipped* data: GPL / CC BY-SA. Dev-time
  validation only (the kaikki precedent: `scripts/wordform_kaikki_check.py`). Option: an
  offline coverage report of our wordform paradigms against Apertium's analyzer — nothing
  ships, so the license is untouched.

## Risks and open questions

- **License posture (decision recorded)**: the operator accepted use of all surveyed sources
  for now, including CC BY-SA (Wikipedia, Taiga) and the terms-free corpus.tatar frequency
  lists; the review is deferred and out of the work plan. Revisit before any store
  publication. Shipped assets still must never carry GPL analyzer data (Apertium/GiellaLT) —
  that stays dev-time only (the kaikki precedent).
- **Eval domain**: the pinned Tatar set is Tatoeba-register; the conversational decile exists
  by construction but is not committed as an eval set. All absolute hit rates above are
  Tatoeba-domain numbers; OpenSubtitles held-out can be used privately, never committed
  (license).
- **Web-crawl contamination**: Tatar slices of web corpora contain Russian and machine
  translation; the alphabet filter alone is insufficient (plain Russian words have none of
  the six Tatar-specific letters). Per-word language checks or min-count thresholds are
  mandatory, or coverage rises while top-3 silently degrades.
- **Transfer risk**: every published quantitative delta is English/Korean/Mandarin. With 45%
  inflected tokens and ~11% next-word top-3, Tatar ceilings are lower; treat literature
  numbers as direction, not magnitude.
- **Sample size**: 1,000 sentences resolve ~1 pp deltas at best; smaller expected effects
  need the bigger Russian set or multiple-split designs.
