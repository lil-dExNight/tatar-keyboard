# Documentation history

These documents were removed in the 2026-09-30 cleanup: release audits, plans, feature reports and early research.
Each row gives the path the document had and the last commit that contained it.
Recover any of them with `git show <commit>:<path>`.

## Release audits

| Path | What it was | Last commit |
|---|---|---|
| `docs/archive/apk-audits/APK-AUDIT-2026-08-18.md` | Rebuilt 1.2.0 artifact audit, signed with a new release key | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.3.0.md` | 1.3.0 artifact audit: emoji panel, personal dictionary, next-word prediction, autocorrect | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.4.0.md` | 1.4.0 artifact audit: emoji panel redesign, ?123 edge fix, bottom-row ergonomics | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.5.0.md` | 1.5.0 artifact audit: Telegram-style emoji panel and emoji search | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.6.0.md` | 1.6.0 artifact audit: emoji skin tones, section swipe, touch-slop tuning | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.6.1.md` | 1.6.1 artifact audit: two emoji search fixes | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.7.0.md` | 1.7.0 artifact audit: Russian dictionary and per-layout dictionary choice | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.8.0.md` | 1.8.0 artifact audit: Russian next-word prediction verified on the release build | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.8.1.md` | 1.8.1 artifact audit: fix for suggestion strip defects after deletion | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.8.2.md` | 1.8.2 artifact audit: personal dictionary no longer loses data silently | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.8.3.md` | 1.8.3 artifact audit: smaller bigram tables, recoverable quarantined personal dictionary | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.8.4.md` | 1.8.4 artifact audit: final UI polish pass | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.9.0.md` | 1.9.0 artifact audit: conversational words accepted into both dictionaries | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.9.1.md` | 1.9.1 artifact audit: widened dictionary acceptance | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.9.2.md` | 1.9.2 artifact audit: strip no longer stays empty after cursor moves | `c7f6c50b` |
| `docs/archive/apk-audits/APK-AUDIT-1.9.3.md` | 1.9.3 artifact audit: fix for the strip dying mid-word | `6a2fc290` |
| `docs/archive/apk-audits/APK-AUDIT-1.9.4.md` | 1.9.4 artifact audit: frequent Tatar imperatives added as bigram heads | `6a2fc290` |
| `docs/APK-AUDIT-1.9.5.md` | 1.9.5 artifact audit after the restructuring campaign (locales cut, smaller APK) | `e2b0ad9f` |
| `docs/APK-AUDIT-1.9.6.md` | 1.9.6 artifact audit: fixes from the 2026-08-31 full audit | `a3ee4a52` |
| `docs/APK-AUDIT-1.9.7.md` | 1.9.7 artifact audit: Russian bigram table repacked from the current dictionary | `d0fe00b4` |
| `docs/APK-AUDIT-1.9.8.md` | 1.9.8 artifact audit: both bigram tables retrained with conversational corpora | `098fc6c1` |
| `docs/APK-AUDIT-1.9.9.md` | 1.9.9 artifact audit: compact asset formats and zopfli repacking | `e07f6a9e` |
| `docs/APK-AUDIT-1.9.10.md` | 1.9.10 artifact audit: emoji suggestions in the strip | `d321753b` |
| `docs/APK-AUDIT-1.9.11.md` | 1.9.11 artifact audit: first next-word request no longer returns empty | `a7ff9245` |
| `docs/APK-AUDIT-1.9.12.md` | 1.9.12 artifact audit: fixes from the 2026-09-02 pre-release audit | `5e4d993b` |
| `docs/APK-AUDIT-1.9.13.md` | 1.9.13 artifact audit: tablet Enter key and emoji panel under navbar | `d88b0d1d` |
| `docs/APK-AUDIT-1.9.14.md` | 1.9.14 artifact audit: dp text sizes, emoji panel bottom padding and shrinking | `7529200a` |
| `docs/APK-AUDIT-1.9.15.md` | 1.9.15 artifact audit: error-prone findings triaged, one latent NPE fixed | `062102ee` |
| `docs/APK-AUDIT-2.0.0.md` | 2.0.0 artifact audit: Tatar word forms, sentence-start predictions, typo recovery | `f88f750b` |
| `docs/APK-AUDIT-2.0.1.md` | 2.0.1 artifact audit: strip filled with top words after a committed word | `4c13686c` |
| `docs/APK-AUDIT-3.0.0.md` | 3.0.0 artifact audit: roadmap phases including personal bigrams and glide typing | `b8de3916` |
| `docs/APK-AUDIT-3.0.1.md` | 3.0.1 artifact audit: supersedes 3.0.0, fixes privacy and license links | `8a6aff3e` |
| `docs/APK-AUDIT-3.0.2.md` | 3.0.2 artifact audit: glide typing polish, lift-commit | `97506908` |
| `docs/APK-AUDIT-3.1.0.md` | 3.1.0 artifact audit: optimization pass and two rounds of audit fixes | `180916a0` |
| `docs/APK-AUDIT-3.1.1.md` | 3.1.1 artifact audit: resources.arsc stored uncompressed again for Android 11+ | `a5b69776` |
| `docs/APK-AUDIT-3.3.0.md` | 3.3.0 artifact audit: four-cell strip, live glide preview, Tatar bigrams repacked | `33386c05` |
| `docs/APK-AUDIT-3.4.0.md` | 3.4.0 artifact audit: learned word-to-emoji suggestions | `6e83f0e2` |
| `docs/APK-AUDIT-3.5.0.md` | 3.5.0 artifact audit: emoji panel height setting, faster lookups | `36e84690` |
| `docs/APK-AUDIT-3.6.0.md` | 3.6.0 artifact audit: three-cell strip restored, live glide preview removed | `805155a4` |

## Phase reports and plans

| Path | What it was | Last commit |
|---|---|---|
| `docs/archive/PROPOSALS.md` | Frozen product plan for dictionary, suggestions, autocorrect and bigram phases | `6a2fc290` |
| `docs/archive/missions/MILESTONE-v1.1.md` | Milestone 1.1 plan and log: Tatar as a first-class language | `6a2fc290` |
| `docs/archive/missions/MILESTONE-v2.md` | Milestone 2 plan and log: dictionary and suggestions | `6a2fc290` |
| `docs/RESTRUCTURE-PLAN.md` | Plan for the audit, restructuring and cleanup campaign before 1.9.5 | `6a2fc290` |
| `docs/RESTRUCTURE.md` | Log of the restructuring campaign that produced 1.9.5 | `6a2fc290` |
| `docs/DEV-PLAN.md` | Developer tooling plan: asset orchestrator, CI, reproducible builds, error-prone | `6a2fc290` |
| `docs/BACKLOG.md` | Parked-and-rejected ideas register; the verdicts and parked items are recoverable here | `f4021aa4` |
| `docs/ROADMAP.md` | Roadmap of remaining prediction, UX and tech-debt work after 2.0.1 | `c74e8790` |
| `docs/ROADMAP-P1.md` | Phase 1 report: sentence-start casing, Russian sentence starts, predictions after commas | `93966d2b` |
| `docs/ROADMAP-P2.md` | Phase 2 report: personal bigrams, personal dictionary screen, incognito mode | `fb22da97` |
| `docs/ROADMAP-P3.md` | Phase 3 report: autocorrect visual contract; autocorrect widening rejected | `180916a0` |
| `docs/ROADMAP-P4.md` | Phase 4 report: prediction depth measurements (fourth cell, trigrams, extra heads) | `235ad7d5` |
| `docs/ROADMAP-P5.md` | Phase 5 report: keyboard height preference | `784d8991` |
| `docs/ROADMAP-P6.md` | Phase 6 report: splitting oversized classes into smaller units | `511e6fe0` |
| `docs/ROADMAP-P7.md` | Phase 7 report: glide typing decoder, integration and calibration | `88c1c6d7` |
| `docs/ROADMAP-P8-PLAN.md` | Plan to release the accumulated work and close the UX/engineering backlog | `180916a0` |
| `docs/ROADMAP-P8.md` | Phase 8 report: 3.1.0 release, Apple-style UX batches, supply-chain hardening | `34b897e2` |
| `docs/OPTIMIZE-2026-09-25.md` | Zero-quality-loss optimization pass: dead resources, startup, engine, rendering | `a5b69776` |
| `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md` | Optimization and security audit plan built from IME best-practice research | `cc3c1212` |
| `docs/BACKLOG-2026-09-28.md` | Development backlog snapshot after 3.3.0 | `2b4e0fd6` |
| `docs/LEFTOVERS-PLAN-2026-09-28.md` | Plan for three backlog leftovers: emulator IME wedge, dead code, dependency verification | `2b4e0fd6` |
| `docs/NEXT-RELEASE-PLAN.md` | Work plan for 3.8.0: glide spacing, aliases and twinless doubled letters, long-press digits, double-space rule, startup dex layout | `400a2906` |

## Feature missions

| Path | What it was | Last commit |
|---|---|---|
| `docs/archive/dictionary/DICTIONARY-D1C.md` | Three-cell suggestion strip view and window insets | `c7f6c50b` |
| `docs/archive/dictionary/DICTIONARY-D1D.md` | Memory-mapped prefix lookup engine for the dictionary | `c7f6c50b` |
| `docs/archive/dictionary/DICTIONARY-D1E.md` | Opt-in Tatar suggestions wired into input, 1.2.0 | `6a2fc290` |
| `docs/archive/dictionary/DICTIONARY-D3.md` | Autocorrect with backspace undo | `c7f6c50b` |
| `docs/archive/dictionary/DICTIONARY-E4.md` | Personal dictionary per active language: format, writes, learning, forgetting | `c7f6c50b` |
| `docs/archive/missions/DICTIONARY-E3.md` | Reproducible typo set and typo-recovery calibration | `bb0807bd` |
| `docs/archive/missions/TAP-REPRO.md` | Failing tests that pinned the cause of two 1.8.0 suggestion defects | `6a2fc290` |
| `docs/archive/missions/PERSONAL-DICT-FIX.md` | Fixes for silent personal dictionary failures | `6a2fc290` |
| `docs/archive/missions/QUARANTINE.md` | Quarantined personal dictionary made visible and recoverable, 1.8.3 | `6a2fc290` |
| `docs/archive/missions/PREFIX3-BUG.md` | Empty strip after cursor movement: diagnosis and fix, 1.9.2 | `c7f6c50b` |
| `docs/archive/missions/SUGGEST-DIES.md` | Strip going dark mid-word: handler message ID collision, 1.9.3 | `6a2fc290` |
| `docs/archive/missions/suggest-dies/evidence/README.md` | Index of emulator evidence for the strip-dies fix | `c7f6c50b` |
| `docs/NEXTWORD-RACE.md` | Empty strip on the first next-word request: two root causes, 1.9.11 | `2dd38204` |
| `docs/TT-SUGGESTIONS-PLAN.md` | Plan for Tatar word forms, word-form suggestions and sentence-start predictions | `873ef35e` |
| `docs/TT-SUGGESTIONS.md` | Report on Tatar word forms, word-form suggestions and sentence starts, 2.0.0 | `93966d2b` |
| `docs/TT-TYPO-NEXT-PLAN.md` | Plan for strip typo recovery and predictions after a suggestion tap | `bb0807bd` |
| `docs/TT-TYPO-NEXT.md` | Report on typo recovery and predictions after a suggestion tap, 2.0.0 | `bb0807bd` |
| `docs/TT-NEXTWORD-FILL-PLAN.md` | Plan for filling empty strip cells after a committed word | `9833892b` |
| `docs/TT-NEXTWORD-FILL.md` | Report on filling empty strip cells with top words, 2.0.1 | `7fea9797` |
| `docs/GLIDE-PLAN.md` | Work plan for glide (swipe) typing | `52f2664e` |
| `docs/GLIDE-PERSONAL.md` | Personal dictionary words as glide typing candidates, 3.2.0 | `ee8eec8c` |
| `docs/GLIDE-LIVE-STRIP4.md` | Live glide preview, glide learning and four-cell strip, 3.3.0 | `22b226ef` |

## Audits and security

| Path | What it was | Last commit |
|---|---|---|
| `docs/archive/missions/SILENT-AUDIT.md` | Read-only audit of silent failures and unrecoverable state in suggestions | `6a2fc290` |
| `docs/AUDIT-2026-08-31.md` | Full security, UX and optimization audit of 1.9.5 | `91cba52d` |
| `docs/FINAL-AUDIT-2026-09-02.md` | Pre-release audit of 1.9.11: security, bugs, performance, store readiness | `6a2fc290` |
| `docs/DEVICE-UAT-1.9.12.md` | First full check on a real device (POCO C71), 1.9.12 | `3b43fb8a` |
| `docs/DEVICE-RESEARCH-GEOMETRY.md` | Device research of keyboard geometry under system UI settings, 1.9.13 | `7529200a` |
| `docs/ERRORPRONE-TRIAGE.md` | Triage of all error-prone warnings; five closed, one real NPE | `5ed29442` |
| `docs/AUDIT-2026-09-24.md` | Consolidated audit of release 3.0.0 | `3c2e59ef` |
| `docs/AUDIT-2026-09-24-FIXES.md` | Code fixes for the 3.0.0 audit findings | `3c2e59ef` |
| `docs/SECURITY-AUDIT-2026-09-25.md` | Security audit of pipeline, privacy and input robustness after 3.0.2 | `4f5b0e22` |
| `docs/SECURITY-AUDIT-2026-09-25-FIXES.md` | Input-robustness fixes from the 2026-09-25 security audit | `9eb38bfc` |
| `docs/IC-BINDER-AUDIT-2026-09-29.md` | Inventory of InputConnection binder calls per keystroke; no fix needed | `2b4e0fd6` |

## Dictionary and corpus

| Path | What it was | Last commit |
|---|---|---|
| `docs/archive/dictionary/DICTIONARY-D0.md` | Tatar dictionary coverage study on Leipzig corpora | `c7f6c50b` |
| `docs/archive/dictionary/DICTIONARY-D1A.md` | Provenance and format of the Tatar top-100k dictionary asset | `c7f6c50b` |
| `docs/archive/dictionary/DICTIONARY-D1B.md` | Atomic device-protected storage for the unpacked dictionary | `c7f6c50b` |
| `docs/archive/dictionary/RUSSIAN-DICTIONARY.md` | Russian top-100k dictionary and per-layout dictionary choice, 1.7.0 | `6a2fc290` |
| `docs/archive/dictionary/CORPUS.md` | Conversational corpus candidates, licenses and measured gain | `6a2fc290` |
| `docs/archive/dictionary/CORPUS-OS.md` | OpenSubtitles adopted for both languages, with attribution and accepted risk | `6a2fc290` |
| `docs/archive/dictionary/CORPUS-TATAR-PERMISSION-DRAFT.md` | Unsent draft letter asking corpus.tatar for dictionary permission | `c7f6c50b` |
| `docs/archive/dictionary/REVIEW-BATCHES.md` | Word acceptance queues split into phone-sized review batches | `6a2fc290` |
| `docs/archive/dictionary/review-batches/README.md` | How to review the word acceptance batches | `c7f6c50b` |
| `docs/archive/dictionary/DICT-ACCEPT.md` | Machine acceptance rule for conversational words, 1.9.0 | `6a2fc290` |
| `docs/archive/dictionary/DICT-WIDEN.md` | Rejected words accepted except fragments, 1.9.1 | `6a2fc290` |
| `docs/SIZE-SCHEMA2.md` | Compact dictionary format: block front-coding and varint frequencies, 1.9.9 | `4312efae` |

## Bigrams

| Path | What it was | Last commit |
|---|---|---|
| `docs/archive/bigrams/DICTIONARY-E5A.md` | Bigram table size and usefulness measured before any code | `c7f6c50b` |
| `docs/archive/bigrams/DICTIONARY-E5B.md` | Provenance and format of the Tatar bigram table asset | `c7f6c50b` |
| `docs/archive/bigrams/DICTIONARY-E5C.md` | Next-word prediction engine: bigram reader and two-stage readiness | `c7f6c50b` |
| `docs/archive/bigrams/DICTIONARY-E5D.md` | Next-word prediction wired into input, commit path and settings | `8a153c0e` |
| `docs/archive/bigrams/RUSSIAN-BIGRAMS.md` | Russian bigram table and per-layout table choice, 1.8.0 | `6a2fc290` |
| `docs/archive/bigrams/LANG-PRIORITY.md` | Candidate priority rule when two languages compete | `6a2fc290` |
| `docs/archive/bigrams/BIGRAM-ADJACENCY.md` | Adjacency rule kept; successor cutoff reduced to four per head | `6a2fc290` |
| `docs/archive/bigrams/IMPERATIVE-HEADS.md` | Thirteen frequent Tatar imperatives added as bigram heads, 1.9.4 | `6a2fc290` |
| `docs/archive/bigrams/imperative-heads/evidence/DECISION-RULE-PRECOMMIT.md` | Selection rule for imperative heads, written before measuring | `c7f6c50b` |
| `docs/RUSSIAN-BIGRAMS-REPACK.md` | Russian bigram table repacked from the current dictionary, 1.9.7 | `6dc54901` |
| `docs/russian-bigrams-repack/evidence/DECISION-RULE-PRECOMMIT.md` | Acceptance rule for the Russian bigram repack, written before measuring | `6dc54901` |
| `docs/CORPUS-CONVERSATIONAL-RU.md` | Russian bigram table retrained with conversational corpora, 1.9.8 | `88c482cb` |
| `docs/corpus-conversational/evidence/DECISION-RULE-PRECOMMIT.md` | Acceptance rule for the Russian conversational retrain, written before measuring | `88c482cb` |
| `docs/CORPUS-CONVERSATIONAL-TT.md` | Tatar bigram table retrained with conversational corpora, 1.9.8 | `0b3d6949` |
| `docs/corpus-conversational/evidence/DECISION-RULE-PRECOMMIT-TT.md` | Acceptance rule for the Tatar conversational retrain, written before measuring | `0b3d6949` |
| `docs/SIZE-SCHEMA3.md` | Compact bigram format indexing into the dictionary, 1.9.9 | `389c8c3c` |

## Emoji

| Path | What it was | Last commit |
|---|---|---|
| `docs/archive/emoji/DICTIONARY-E2.md` | Provenance of the emoji panel data asset from Unicode emoji-test.txt | `c7f6c50b` |
| `docs/archive/emoji/EMOJI-PANEL-REDESIGN.md` | First emoji panel redesign: background, overlaps, clipped row, 1.4.0 | `6a2fc290` |
| `docs/archive/emoji/EMOJI-PANEL-TELEGRAM.md` | Telegram-style emoji panel and emoji search, 1.5.0 | `6a2fc290` |
| `docs/archive/emoji/EMOJI-SKIN-TONES.md` | Emoji skin tones and swiping between sections, 1.6.0 | `6a2fc290` |
| `docs/archive/emoji/EMOJI-SEARCH-FIXES.md` | Two emoji search defects found in 1.6.0, fixed in 1.6.1 | `6a2fc290` |
| `docs/archive/emoji/SMALL-FIXES.md` | Two emoji search rough edges and removal of tablet code | `6a2fc290` |
| `docs/EMOJI-SUGGEST-RESEARCH.md` | Research on suggesting emoji in the strip after a word | `39385000` |
| `docs/EMOJI-SUGGEST-PLAN.md` | Implementation plan for emoji suggestions in the strip | `de493453` |
| `docs/emoji-suggest/DATA.md` | Curated word-to-emoji asset for Tatar and Russian, 1.9.10 | `de493453` |
| `docs/emoji-suggest/ENGINE.md` | Emoji suggestion engine in the strip, 1.9.10 | `de493453` |
| `docs/emoji-suggest/FIXES.md` | Field defects in emoji suggestions and Tatar emoji search, 1.9.12 | `de493453` |
| `docs/EMOJI-PANEL-NAVBAR.md` | Emoji panel no longer hides under the Android 15 navigation bar, 1.9.13 | `3b43fb8a` |
| `docs/EMOJI-LEARN.md` | On-device learned word-to-emoji suggestions, 3.4.0 | `22c24255` |
| `docs/EMOJI-PANEL-SPACE-2026-09-28.md` | Emoji panel space analysis and plan behind the height setting, 3.5.0 | `dc7764b6` |

## UX

| Path | What it was | Last commit |
|---|---|---|
| `docs/archive/ux/FIXES-1.0.1.md` | Fix plan after the first device test of 1.0.0 | `6a2fc290` |
| `docs/archive/ux/UX-POLISH.md` | First UX polish pass based on keyboard UX research | `6a2fc290` |
| `docs/archive/ux/IOS-REDESIGN.md` | iOS-style redesign plan for settings and keyboard | `c7f6c50b` |
| `docs/archive/ux/LAYOUT-ERGONOMICS.md` | Bottom-row ergonomics: space bar width versus emoji key, 1.4.0 | `6a2fc290` |
| `docs/archive/ux/SYMBOL-KEY-EDGE-FIX.md` | ?123 key switching back when pressed near its edge, 1.4.0 | `6a2fc290` |
| `docs/archive/ux/TOUCH-SLOP-TUNING.md` | Shared touch hysteresis and path accumulator, 1.6.0 | `6a2fc290` |
| `docs/archive/ux/FINAL-POLISH.md` | Final UI pass: dead strip buttons, visible text in three languages, 1.8.4 | `6a2fc290` |
| `docs/TABLET-ENTER.md` | Enter key restored on Cyrillic layouts on tablets, 1.9.13 | `3b43fb8a` |
| `docs/RESEARCH-FIXES.md` | Keyboard text in dp, emoji panel padding and shrinking, 1.9.14 | `7529200a` |
| `docs/APPLE-UX-2026-09-25.md` | Apple-style UX audit of the current app and implementation plan | `2b4e0fd6` |
| `docs/LAB-FIFTH-ROW.md` | Fifth-row A/B/C study protocol; the order was fixed by decision, no sessions run | `78880620` |

## Research

| Path | What it was | Last commit |
|---|---|---|
| `research/00-itog-i-roadmap.md` | Initial research summary and project roadmap (July 2026) | `b3d894da` |
| `research/01-stek-i-arhitektura-ime.md` | Android IME tech stack and architecture research | `b3d894da` |
| `research/02-ui-rendering.md` | Keyboard UI rendering approaches on Android | `b3d894da` |
| `research/03-optimizaciya-slabye-ustroystva.md` | Optimizing the keyboard for low-end devices | `b3d894da` |
| `research/04-stil-apple.md` | iOS keyboard visual style and how to reproduce it on Android | `b3d894da` |
| `research/05-tatarskaya-raskladka.md` | Tatar alphabet, layouts, letter frequencies and existing Tatar keyboards | `b3d894da` |
| `research/06-fork-ili-s-nulya.md` | Open-source keyboards compared: fork or write from scratch | `b3d894da` |
| `research/07-funkcional-mvp-predikciya.md` | MVP scope, autocorrect and Tatar dictionary sources | `b3d894da` |
| `research/08-distribuciya.md` | Distribution, store policies and privacy for a keyboard app | `b3d894da` |
| `docs/SIZE-OPTIMIZATION-RESEARCH.md` | Research on lossless APK size reduction | `e07f6a9e` |

## Other

| Path | What it was | Last commit |
|---|---|---|
| `docs/CLEANUP.md` | Removal of a dead tablet config and stale references | `c7f6c50b` |
| `docs/SIZE-CAMPAIGN.md` | Summary of the lossless APK size campaign, 1.9.9 | `e07f6a9e` |

## Cleanup working files

| Path | What it was | Last commit |
|---|---|---|
| cleaning/CLEANUP-PLAN.md | The 2026-09-30 text and repository cleanup plan, decisions D1–D8 | 81b3e3c4 |
| cleaning/00-SUMMARY.md … cleaning/21-build-ci-repo.md | Per-area review reports that fed the cleanup plan | 81b3e3c4 |
| cleaning/baseline.txt, cleaning/after-phase7.txt, cleaning/metrics.sh | Before/after text metrics of the cleanup | 81b3e3c4 |
