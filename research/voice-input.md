# Voice input: feasibility and decision memo

`BRIEF.md` currently excludes voice input. This document is the evidence base for keeping,
narrowing, or lifting that exclusion. Proof rules: `research/measurement-framework.md`.

## Verdict up front

**Delegate; never embed.** Ship an optional microphone key that hands off to an installed
voice IME (with a `RecognizerIntent` fallback), or document-and-decline. Do not put
recognition in our process. The BRIEF exclusion narrows from "voice input" to "in-app voice
recognition" if the delegation key ships.

## The decisive facts

**No Tatar speech recognition exists in any installable form today.**

- Google's speech stack (cloud and on-device) has no Tatar locale — verified against the
  supported-languages list (`ru`, `kk`, `az` present; no `tt`).
- Vosk's model catalog covers `ru`, `kk`, `ky`, `uz`, `tg` — no Tatar model.
- FUTO Voice Input (Whisper-based, the de-facto offline voice IME) ships only languages with
  >1,000 Whisper training hours — Russian yes, Tatar no.
- Whisper nominally knows Tatar, but its Tatar training hours are effectively zero (Tatar is
  absent even from the paper's 1-hour tier); zero-shot quality is unmeasured and expected
  poor. The best published Tatar model (WER 10.3% on its own eval) is CC-BY-NC — unusable in
  a shipped product. Open training data: ~31 h of Common Voice Tatar (CC0) + ~70 h of
  single-speaker TTS audio (MIT) — research-grade, not product-grade.
- Yandex shipped Tatar voice input (2024, state-partnered program) — cloud-only, proprietary,
  locked inside Yandex's own apps. Yandex owns Tatar voice; we will not and should not match
  it.

**Embedding is structurally impossible anyway.** Every offline engine (whisper.cpp, Vosk,
sherpa-onnx) is native code (violates no-NDK), a third-party runtime dependency (violates
zero-deps), and 10–500× the entire APK budget in model size (violates 3 MiB). Three fixed
BRIEF decisions fall at once, before quality is even discussed.

**In-IME `SpeechRecognizer` spends the product's core asset for nothing.** The API requires
`RECORD_AUDIO` in *our* manifest (enforced against the caller in AOSP). That retires the
single-permission identity — "the only permission is VIBRATE" is pinned in `release_check.sh`
and promised in `PRIVACY.md`; Android 12+ lights the mic indicator attributed to our app; Play
Data safety moves from "no data collected" to a disclosable audio-sharing gray zone; on
pre-API-31 devices the default recognizer streams audio to Google. And it still cannot
recognize Tatar. The permission set is a structural, CI-verifiable proof ("we cannot listen,
even if coerced") — one-way: once RECORD_AUDIO ships, its later removal never restores
credibility. HeliBoard's open PR #2743 (in-IME SpeechRecognizer, manifest diff is exactly
`+RECORD_AUDIO`, sitting unmerged) is this dilemma playing out live in a sibling project.

## The delegation design (what ships, if approved)

The platform contract is old and production-proven — AnySoftKeyboard still ships Google's own
2011 `voiceime` module (Apache-2.0, usable as the reference):

1. **Voice-IME handoff (primary).** Voice IMEs register an auxiliary subtype with
   `imeSubtypeMode="voice"`. The mic key scans `getEnabledInputMethodList()` for one and
   switches via `InputMethodService.switchInputMethod(id, subtype)` (API 28+; deprecated
   `setInputMethodAndSubtype` below; `showInputMethodPicker()` as last resort — all plumbing
   we already have). The voice IME (FUTO Voice Input, Sayboard, whisperIME, Gboard's voice
   typing) opens in place of the keyboard and commits text through its own InputConnection —
   our commit invariants are untouched.
2. **`RecognizerIntent` fallback.** If no voice IME exists but a `RECOGNIZE_SPEECH` handler
   does: a translucent trampoline activity does `startActivityForResult`, the result is
   stashed, the keyboard re-shows itself, and the text commits in `onStartInputView` on a
   fresh InputConnection inside a batch edit (ASK's exact flow, including the
   capitalize-after-sentence heuristic).
3. **Nothing installed → the key hides itself** (or shows a "get a voice app" hint —
   discoverability choice). Suppressed on the lock screen and in private fields, same
   contract as the strip.

Cost: ~150–200 lines, zero new permissions, ~KB of size, minSdk-safe. Demand evidence exists
on our own upstream's tracker: Simple Keyboard issue #133 asks for exactly this, with users
pointing at FUTO/whisperIME; the FUTO README lists Simple Keyboard as "no voice button" —
shipping the key turns that into a supported configuration.

Companion-app guidance (README/FAQ/settings row): whisperIME (MIT, F-Droid, multilingual —
the only conceivable Tatar path, quality unverified), Sayboard (Vosk, F-Droid), FUTO Voice
Input (own repo/Play; Source First license, not F-Droid). State the language reality plainly:
Russian works offline today; Tatar does not, anywhere yet.

## Demand assessment (honest)

Voice matters, but it is not our battleground. The strongest case is accessibility — blind
users overwhelmingly prefer dictation (NN/g), speech is ~2.9× faster than touch typing (Ruan
et al. 2018), older adults rate voice easier. But: our core audience types code-switched
tt/ru messaging; Yandex owns Tatar voice; and the OSS demand evidence (HeliBoard #988/#1547,
FlorisBoard #195) asks for the *delegation key*, not for us to build ASR. The delegation key
captures most of the user value at near-zero cost.

## What we decline (recorded rationale)

- **In-app ASR engine** — violates no-NDK + zero-deps + 3 MiB simultaneously; no quality
  Tatar model exists to ship even then.
- **In-IME SpeechRecognizer** — RECORD_AUDIO retires the one-permission identity and the CI
  pin, moves the trust boundary into a closed network-capable Google process, and still lacks
  Tatar. The only scenario that would force re-evaluation: a future where on-device
  recognition with a Tatar model is common *and* delegation proves broken on target devices.
- **Companion APK of our own** (a separate voice app with its own permissions) — months of
  work, native code, a second release pipeline, plus a Tatar fine-tune research project on
  ~31 h of data; contradicts the one-developer reality.

## Device checks before shipping the key

1. Do budget RU-ROM devices (HyperOS Xiaomi, Samsung A) expose an enabled voice-subtype IME
   out of the box? Does `switchInputMethod` work reliably there (HyperOS kills/IME quirks)?
2. The return path after dictation (FlorisBoard's #2759 jank is the cautionary precedent).
3. Lock screen and password-field suppression of the key.
4. whisperIME's Tatar output quality — a spot check decides whether we mention Tatar at all
   in the companion docs.
5. The trampoline flow's commit reliability when the system kills the IME mid-dictation
   (ASK's stash-and-commit pattern covers it; verify).

## Risks and open questions

- Provider quality gets attributed to us; the settings copy must say the voice app is the
  user's chosen separate app.
- Dictated text bypasses our pipeline, so the personal dictionary learns nothing from it —
  one line in PRIVACY.md when the key ships.
- Google's developer-verification program threatens companion apps whose authors refuse to
  verify (whisperIME's stated position) — prefer FUTO/Sayboard in recommendations, re-check
  at ship time.
- Revisit trigger: a permissively licensed, quality-measured small Tatar ASR model appears
  (watch Common Voice Tatar hours and whisper.cpp fine-tunes), or FUTO adds Tatar — the
  delegation design absorbs both with zero changes.
