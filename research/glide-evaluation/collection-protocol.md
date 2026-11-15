# Gesture collection protocol

## Scope and evidence

This protocol prepares later recordings. It does not add human coverage by itself. The only local human-recorded input in this wave is `research/glide-evaluation/datasets/session-1.jsonl`: 101 collector-passed traces, 35 prompts, one participant, Tatar layout, 360×155 board, density bucket 480. Its session/device ids, orientation and instructed speeds were not recorded; the evaluator marks the conservative legacy fallbacks and `unspecified` values. It is a frozen development set, never a final test set. `fixtures/synthetic.jsonl` is generator output, not human evidence.

Use `research/glide-collector/index.html` for the next sessions. The debug Android recorder currently receives only committed glides and its privacy-frozen schema has no session/device/geometry fields, so it cannot supply the required taps, canceled gestures and unintended starts. Do not represent Android-recorder-only collection as negative-case coverage.

## Before recording

1. Use an offline copy of the collector. Read the consent text, keep the random device id stable on that browser/device, and select **new session** at every collection sitting. Do not put names or free text in the export.
2. Reserve at least one physical device exclusively for final evaluation. Development and final manifests reject shared session or device ids. A different browser profile on the same device is not a different device.
3. Record at least two development sessions on development devices and at least one final session on each final-only device. Cover phone/tablet or available distinct board geometries, portrait and landscape, and every offered density/board size. Do not infer orientation from board dimensions; schema 2 records it explicitly.
4. Keep the instructed-speed selector accurate. At each geometry collect ordinary prompts at slow, normal and fast natural speeds. Derived speed bins are diagnostics, not instructions or decoder parameters.
5. Use `prompts.tsv` as the session checklist. The embedded collector pool supplies the words; `trial_kind` supplies the negative condition. Every prompt class must appear in every required speed/orientation stratum. On the Tatar layout every alphabet letter — including ё and ъ — has its own key on the eight-key extra row; on the Russian layout the six Tatar-only letters plus ё/ъ are aliases and are traced over their base key. Doubled letters get both loop-free and deliberate-loop/dwell attempts.

## Trial procedure

For each prompt, make one motion and lift. Keep every outcome, including retries. Do not delete a trace because it failed or decoded badly. The collector stores the raw outcome as a 50-point normalized trace, gate result and stable rejection reason; deletion buttons are for consent/privacy only.

- **ordinary swipe:** trace the displayed word normally. Keep start/end mistakes and missed-letter traces.
- **tap:** select `negative: tap`, touch and lift without swiping.
- **tremor:** select `negative: tremor`, make small local jitter without intending the word.
- **canceled:** select `negative: canceled`, start a trace and use **save active trace as canceled** with another pointer, or trigger a browser pointer cancellation. A resize interruption is also retained as `geometry_changed`.
- **short drag:** select `negative: short drag`, move briefly away from the start without tracing the word.
- **unintended start:** select `negative: unintended start`, reproduce an accidental touch/drag such as beginning between keys or on a spacer.

A negative control should normally produce no candidate. The evaluator reports non-empty decodes instead of silently treating them as successful word trials. Failed ordinary traces remain in the positive denominator; gate-accepted rates are reported separately and cannot replace the all-trace rates.

## Freeze and evaluate

Regenerate and replay the deterministic host fixture, or replay the existing development session:

```sh
python3 scripts/glide-benchmark.py make-synthetic \
  --output research/glide-evaluation/fixtures/synthetic.jsonl
python3 scripts/glide-benchmark.py evaluate --synthetic \
  --input research/glide-evaluation/fixtures/synthetic.jsonl \
  --output research/glide-evaluation/reports/synthetic.json
python3 scripts/glide-benchmark.py evaluate \
  --manifest research/glide-evaluation/datasets/session-1-development.json \
  --split development --output research/glide-evaluation/reports/session-1.json
```

Both commands use the offline JVM bridge and production `GlideDecoder`; no Android device or Python decoder mirror is involved. The bridge source is wired into the host test classpath only by `glide-benchmark.init.gradle` and is absent from the APK.

Freeze development inputs immediately after collection. Existing example:

```sh
python3 scripts/glide-benchmark.py freeze \
  --development-input research/glide-evaluation/datasets/session-1.jsonl \
  --output research/glide-evaluation/datasets/session-1-development.json
```

Before any decoder tuning, collect the reserved final device/session files and freeze both sides together:

```sh
python3 scripts/glide-benchmark.py freeze \
  --development-input /path/to/development-session.jsonl \
  --final-input /path/to/final-session.jsonl \
  --output research/glide-evaluation/datasets/tatar-gestures-v1.json
```

The command refuses invalid records, legacy identity fallbacks in final data, and session/device overlap. Store the manifest with the reports; do not edit its pins. Evaluate only the pinned split:

```sh
python3 scripts/glide-benchmark.py evaluate \
  --manifest research/glide-evaluation/datasets/tatar-gestures-v1.json \
  --split final --output research/glide-evaluation/reports/final-baseline.json
```

Compare decoder candidates on the same frozen inputs, dictionary and geometry:

```sh
python3 scripts/glide-benchmark.py compare \
  research/glide-evaluation/reports/final-baseline.json \
  research/glide-evaluation/reports/final-candidate.json \
  --output research/glide-evaluation/reports/final-comparison.json
```

Visible top-3 is the user-facing metric; engine top-4 is diagnostic only. Endpoint pruning, length pruning, scoring rejection and unmappable/out-of-vocabulary targets are separate outcomes. Reports contain no timestamps or host latency values, and comparison enforces identical ordered record fingerprints.

## Required future human coverage

No existing record proves orientation, multiple device geometries, instructed speeds or negative-case behavior. Those strata remain unmeasured until the protocol above is run on real devices. Do not tune decoder weights against the synthetic fixture or this single development session.
