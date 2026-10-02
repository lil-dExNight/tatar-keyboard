# Competitor features and distribution research

What other keyboards ship, what their users demand, where to distribute, and how to learn
from users without telemetry. Proof rules: `research/measurement-framework.md`. Sources are
linked inline in the findings.

## The feature landscape (demand evidence)

What the majors ship and what users demonstrably care about:

| Feature | Demand signal | Fits us? |
|---|---|---|
| Autocorrect + easy undo | The most emotionally charged keyboard feature on iOS (the "ducking" saga; iOS 17's fix celebrated) | **Shipped** (undo via backspace; UX1 in `research/ux.md` adds the visible revert) |
| Swipe typing | Mainstreamed by SwiftKey Flow and iOS QuickPath | **Shipped** |
| Emoji search / recents | Top-voted HeliBoard (#259, 140 reactions) and FlorisBoard (#45, 80) requests | **Shipped** |
| Next-word prediction | FlorisBoard's #325 (83 reactions) | **Shipped** |
| Clipboard history | Headline Gboard feature (Google is extending retention); SwiftKey headline; HeliBoard/FlorisBoard ship it; **12+ issues — the most requested feature in Simple Keyboard, our own upstream** | Fits technically; **excluded in `BRIEF.md`** — see the decision memo below |
| Text-editing mode | Gboard/SwiftKey/Samsung all ship | Fits (pure `InputConnection` work) |
| Word-delete gesture | HeliBoard #1289/#535 (47 combined reactions) | Fits (same gesture family as our spacebar swipe) |
| One-handed mode | Gboard since 2016, iOS since iOS 11; our audience types on 6.5"+ budget phones | Fits (layouts are data) |
| Themes | SwiftKey 100+, Samsung Keys Cafe is a whole product, Gboard dynamic color | Fits narrowly: the zero-dep dynamic-color path in `research/ui.md` (UI2), no theme store |
| Voice typing | The current industry battleground (Pixel, SwiftKey "offline AI voice") | **Excluded in `BRIEF.md`** — feasibility study in `research/voice-input.md` |
| Translation, GIF/sticker search, Emoji Kitchen, proofread AI, handwriting | Headline features of the majors | **Never fit** (network, size, IP, or model cost) — do not schedule |
| Inline autofill (password managers) | HeliBoard #163/#274/#1065/#1471, FlorisBoard #2728/#2978 — the #1 complaint class against offline keyboards | Fits (platform API 30+, system-mediated) |
| Backup/export of learned data | HeliBoard #2585/#2576/#1639/#2562, ASK #2552; FlorisBoard shipped it | Fits (SAF file, `java.util.zip`) — and with no sync, a local file is the *only* migration story |

Useful negative evidence: HeliBoard's toolbar has as many reactions asking to *remove* it as
to extend it — borrow the cheap 80% (a long-press menu on an existing key), not the full
toolbar. Unexpected Keyboard added INTERNET for downloadable dictionaries and triggered a
privacy backlash — every borrowed feature must work offline, and the no-INTERNET stance is
worth protecting as identity.

## The incumbents our audience actually uses

**Yandex Keyboard** (50M+ Play installs, RU rating 4.5) is the real incumbent, not the
official Tatar app. Its praised features: clipboard history (20 fragments + favorites),
translator, emoji placement, theme variety. Its complaint list maps 1:1 onto our strengths:
lags and skipped letters, ~200 MB weekly updates, cold-start failures ("doesn't open on the
first tap"), weak personal learning, privacy distrust ("the most spyware keyboard"), and even
a complaint that its clipboard is kept *too long* — a retention spec handed to us by the
incumbent's own users. Yandex announced Tatar voice input (July 2024); if Yandex ever ships
a serious Tatar *typing* layout, our differentiation narrows to privacy/weight/reliability —
durable, but worth watching.

**The official Tatarstan keyboard** (ru.ttkeyboard, 100K+ installs, ~3.8★): its top
complaints are discoverability failures — Tatar letters hidden behind a "…" row (51 votes
from a user who deleted it), space bar misbehaving (29 votes). Exactly what our always-visible
fifth row and onboarding answer. Its install count at that rating shows both the demand and
the ceiling of mediocre execution. Web-based Tatar keyboards (speak.tatar et al.) prove
people still copy-paste Tatar text — unmet demand on mobile.

**Conversion thesis**: lead every listing and screenshot with the fifth row `ә ө ү җ ң һ`
plus "no INTERNET permission, ~1.8 MB, opens instantly on weak phones". All claims stay
verifiable (CI checks the manifest; `release_check.sh` checks the size).

## Feature decision memos (ranked)

1. **Text-editing actions** (select all / cut / copy / paste / cursor arrows) — pure
   `InputConnection`; all three Android majors ship it. Entry point: a long-press menu on an
   existing key (the cheap 80% of a toolbar). Lab metric: time for a scripted
   move/select/copy/paste task.
2. **Word-delete swipe-left from backspace** (distance-proportional), with the same undo
   affordance discipline as autocorrect; disambiguation against auto-repeat pinned by device
   tests.
3. **One-handed (compact) mode** — width scale + left/right alignment; layouts are data.
   Guard the fifth row's minimum key size; measure the perf legs with the mode on. Floating
   and split stay parked (tablet-centric, highest draw-loop risk).
4. **Inline autofill in the strip** (API 30+) — system-mediated, offline by construction;
   the custom-drawn strip needs a small view-hosting path; cold-start delta measured on the
   reference device.
5. **Backup/export of settings + learned data via SAF** (one user-picked file, zip with
   canonical-path validation — HeliBoard's zip-slip bug is the cautionary precedent). Your
   words, your file: strengthens the privacy story. Round-trip tested (JVM + instrumentation).
6. **Shift cycles case of the selection / last word** (capitalize after the fact — FlorisBoard
   #759). Low cost; `InputConnection` divergence risk needs the manual matrix.
7. **Text shortcuts (abbreviation → expansion)** over the personal-store plumbing; covers the
   pinned-clip use case *without touching the clipboard*; Tatar value: long suffixes and
   fixed phrases. No tracker groundswell — honest weak signal, but near-zero cost.
8. **Undo/redo actions** built on existing commit tracking; gate to well-behaved editors.

## Clipboard: decision memo (BRIEF-level)

`BRIEF.md` excludes clipboard history. The research finding: it is the strongest-demand
unbuilt feature in our lineage, and our upstream's only objection (no UI place without a
permanent bar) does not apply — the emoji-pane pattern solves it. The privacy cost is real
too: a clipboard history converts the most secret-laden transient channel on the phone into
an at-rest dataset, and "never stores the clipboard" is a maximally simple promise to spend.

Compatible shapes, in escalating order:

- **Text shortcuts** (above) — no clipboard involvement; do regardless.
- **In-memory recent-clip cell** — offer a fresh clip in the strip, RAM only, suppressed in
  password/private fields and on the keyguard; "never stored" stays literally true.
- **Opt-in clipboard pane** — toggled like the emoji panel; text-only (no images); default
  retention 60 min (the Gboard mental model), pin = keep until unpinned; listener active only
  while enabled and unlocked; `noBackupFilesDir`; insertions routed so clips are never
  learned; lock-screen and private-field contract tests. Requires editing the BRIEF exclusion
  and adding a plain-language residual-risk note to `PRIVACY.md` (we cannot detect sensitive
  clips from other apps — mitigations are behavioral).

The real argument against: a single "the privacy keyboard stored my password" incident costs
more than the feature adds.

**Decision (operator)**: the staged shape up to the in-memory cell — text shortcuts and the
in-memory recent-clip cell are in scope (see `docs/ROADMAP.md`); the persistent pane is
declined and stays excluded in `BRIEF.md`.

## Distribution channels

- **F-Droid**: we meet the inclusion policy; reproducible-builds path can ship *our* signed
  APK — but three hazards need a dry run: `baseline.prof` is a listed nondeterminism source,
  apksigner from build-tools ≥ 35 may defeat their signature-copy verification (we pin 37),
  and their build is default-compressed while we zopfli-align. Upstream Simple Keyboard is
  listed and healthy — the precedent works.
- **IzzyOnDroid**: requirements met (Fastlane metadata, tagged GitHub releases, size). One
  written risk: their AI policy rejects apps "created in part by generative AI" — our repo is
  openly agent-assisted; the operator decides the framing before applying.
- **RuStore**: free for individuals (VK ID), moderation up to 3 days, preinstalled by law on
  phones sold in Russia — the audience's home turf. Russian-only listing; ≤5 search tags;
  keep the APK-only integration (no RuStore SDKs) so exit is free; post-sale governance worth
  watching.
- **Google Play**: closed test with ≥12 testers × 14 days before production for new personal
  accounts; $25 fee (payment from Russia needs a workable card — operator dependency); no
  Tatar listing locale (ru-RU + en-US only; the tt metadata pays off on F-Droid). The tester
  pool is recruitable from the same Tatar community channels.
- **Skip for now**: AppGallery (low RU Huawei share), NashStore/RuMarket, paid UA.
- **Community seeding**: tatar-inform, Azatliq Radiosy (idelreal.org), Бизнес Online, KFU,
  the Ibragimov Institute, corpus.tatar (already a warm contact), Tatar Telegram/VK channels
  (the operator compiles the actual list — blocked from the research network), and ЦЦТ РТ
  itself (both competitor and potential ally).
- **Case studies**: Keyman (2,300+ languages) proves the institutional/NGO growth path; the
  Alef/ziipin factory (500K+ installs on its flagship) proves "X-language keyboard" store
  search demand — monetized via data collection, our anti-model and our contrast.
- **Listing gap found**: localized screenshots exist only for en-US; ru-RU/tt have no
  `images/` — fixable in hours.

## Learning from users without telemetry

Current state: zero feedback channels, 0–4 downloads per GitHub release — the installed base
is within one order of magnitude of a lab panel, so **qualitative channels dominate for the
foreseeable future**. Ranked:

1. **GitHub issue forms + public triage funnel** (FlorisBoard's model: `proposal` /
   `proposal-accepted|maybe|rejected`; required fields include install source and "tested on
   latest"; an AI-policy checkbox from day one). Hours, free.
2. **Discussions with a Polls category** — seed with the fifth-row key-order question (a real
   open BRIEF decision that suits a poll); upvotes are the standing demand counter.
3. **In-app "Send feedback" row composing an email** (HeliBoard's shipped proof that mailto
   works for a no-INTERNET app; prefill only version/device/Android — never typed content;
   needs a dedicated mailbox and one PRIVACY.md sentence).
4. **Kano micro-surveys** for genuinely open roadmap choices: ≤3 features, functional/
   dysfunctional pairs, 15+ respondents per segment, tt+ru pretested wording; results recorded
   as decision memos resolving parked BACKLOG items.
5. **Beta ring**: GitHub prereleases + a documented Obtainium config; Play closed testing
   later per the checklist.
6. **Store review mining protocol** (activates with the first listing): weekly pass, fixed
   taxonomy, reply to every actionable review, CSV archive into `dist/`.
7. **Opt-in gesture-collection mode** (only if `research/glide-typing.md` G13 is taken up):
   HeliBoard's design — active mode only (prompted words), visible indicator, discard by
   default, private-field and incognito suppression, review before share-sheet export.
8. **Release download counts** in the release record — the only fleet-wide quantitative
   signal pre-store; treat as an upper bound.

Silence is the default for months; the protocol pre-commits to not pivoting on zero signal,
and the lab sessions (`research/ux.md`) stay the primary evidence until channel volume exists.

## Risks and open questions

- Review mining was partly blocked from the research network; Yandex/Samsung claims rest on
  coverage and listings, not full review corpora — a manual pass remains open.
- Gboard's Tatar support is user-reported, not verified against an official list — verify on
  a device before writing comparative claims in listings.
- Play's Russia billing status and identity-verification details need operator-side
  confirmation.
- Whether the BRIEF clipboard exclusion covers a *stateless* pane (show/pin the current clip,
  no history) is an open scoping question for the operator.
- FlorisBoard/HeliBoard ratios (proposals outnumber bugs ~4:1) say what arrives once channels
  open: mostly feature requests — install the triage funnel before the volume.
