# Research

Improvement research for the keyboard. Each document is self-contained: current state,
options with sources, constraint check, decision memos. The proof rules every proposal must
satisfy are in [measurement-framework.md](measurement-framework.md).

## Documents

- [measurement-framework.md](measurement-framework.md) — how an improvement is proven.
- [prediction-engine.md](prediction-engine.md) — word completion and next-word prediction:
  morphology backoff, corpus sources, evaluation upgrades, ranked decision memos.
- [glide-typing.md](glide-typing.md) — the gesture decoder: SHARK2 literature, patents,
  real-gesture data and collection protocols, ranked decision memos.
- [competitor-features.md](competitor-features.md) — feature landscape and demand evidence,
  the clipboard decision memo, distribution channels, feedback without telemetry.
- `corpus/` — corpus measurement scripts and manifests (OPUS data is not committed for
  licensing reasons).

The executed studies (ux, ui, voice-input, optimization) did their job and are retired; they
are listed in `docs/HISTORY.md`. The open work is in `docs/ROADMAP.md`; the standing decisions
are in `docs/ROADMAP.md` and `BRIEF.md`.
