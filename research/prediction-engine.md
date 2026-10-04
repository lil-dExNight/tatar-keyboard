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

## Harness prerequisites (all landed)

The harness now covers every prerequisite the memos below reference:

- Composite next-word hit measurement: `scripts/suggest_eval.py` + `TtSuggestEvalTest` measure
  top-1/top-3 of the full chain (bigrams + after-word forms + fallback); the python mirror of
  the chain lives in `scripts/suggest_chain.py`.
- Typo-mutated held-out set: `scripts/typo_eval_pack.py` generates deterministic
  substitution/deletion/insertion/transposition mutations of the eval words, pinned in
  `tests/typo_eval_pack/` and mirrored by `TypoMutatedEvalTest`, which also pins the
  autocorrect false-trigger rate on correctly typed words.
- Russian held-out set: `scripts/make_ru_eval_set.py` builds
  `app/src/test/resources/ru_eval_sentences.txt` (decontaminated against all four Russian
  training corpora); `RuSuggestEvalTest` pins the baseline.
- Decontamination: the Tatar eval set excludes the normalized sentences of all training
  corpora (conv train90 + both Leipzig tt sets, manifest-verified).
- Statistics: sentence-level paired bootstrap CI95 and top-1 pins in both harnesses, plus a
  minimum-detectable-effect line; a sub-noise delta is not a win.
- Lemma stratification: every eval token is labeled seen-form / new-form-of-seen-stem /
  unseen-stem with per-stratum rates.
- Keystroke-savings simulator: both harnesses replay the eval set with strip taps at cost 1
  and report KS% with the vocabulary-oracle bound next to it.

## Decision memos (ranked)

Gates below are proposals to pre-register before any experiment, per the framework.

**P1 — Stem-keyed bigram backoff (morphology).** Measured and rejected: the offline
simulation (`research/corpus/sim_stem_backoff.py`, run over the training corpora and the
pinned eval set) gained +0.46 pp chain top-3 (paired CI95 [+0.21, +0.73]) against the
pre-registered +1.5 pp bar. The named risk materialized: the stage engages on 15.8% of pairs,
the case-blind expansion converts only 58% of stem hits, and correct after-word forms lose
hits to displacement. Rejected and recorded (archived in `docs/HISTORY.md`); a case-aware expansion is a
different experiment, not a rerun.

**P2 — Wider typo classes (insertion/deletion/transposition) ranked by edit distance.**
Measured and rejected under the pre-registered ranking (the two configurations and their
numbers are archived in `docs/HISTORY.md`): with the wide classes below #4 the deletion class cannot
reach its bar (the empty-exact discipline plus the three-cell strip locks it out); with
deletion between #1 and #4 the substitution class regresses, because DL-1 deletion and DL-1
substitution candidates are rank-indistinguishable and class priority must pick a loser. The
live follow-up is the shared DL-1 tier (classes #2/#4 candidates at distance 1 ranked among
themselves by frequency, continuations after) — a new pre-registered experiment.

**P3 — corpus.tatar + HPLT/MADLAD ingestion.** Landed for HPLT 2.0 `tat_Cyrl`, MADLAD-400 tt
and tt.wikipedia: per-source extraction with a Russian-bleed filter, merged as integer bonus
frequencies (`data/dictionary/dict-accept/bonus-freq-tt.tsv`), re-rank only at the fixed
dictionary size. Measured: +0.96 pp held-out conversational token coverage (bar +0.5), chain
top-3 flat (paired CI contains zero), keystroke savings +1.22 pp. corpus.tatar stayed
unobtainable (host unreachable) and remains the best untapped lever. The bigram table was
repacked against the new dictionary but not retrained on the new corpora — that is the
natural next step.

**P4 — Pack-time Kneser-Ney successor re-ranking.** Rank each head's kept successors by the
interpolated KN score (continuation counts from one extra pass; discount and λ fixed from
leave-one-out on the training side, never on eval); schema and runtime unchanged — zero
bytes, zero milliseconds. Gate: ≥ +0.3 pp unconditional next-word top-3. Honestly uncertain
(successors are already count-ordered); cheap to falsify. Decide first whether continuation
counts cover in-vocabulary pairs only or all token transitions, and record the choice.

**P5 — Continuation-count fallback pool.** Replace the raw-frequency fallback pool with
top-N by continuation count N₁₊(•,w) (a small shipped text asset). Gate: ≥ +0.3 pp
unconditional top-3 on the composite chain metric. Risk: high-context particles
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
  clusters forms after stems. Parked.
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
