# Gesture collection protocol

## Scope and evidence

This protocol prepares later recordings. It does not add human coverage by itself. Human-recorded evidence has two frozen splits. `datasets/session-1.jsonl` is the development set: one participant, Tatar layout, one board geometry; its session/device ids, orientation and instructed speeds were not recorded, so the evaluator marks the conservative legacy fallbacks and `unspecified` values. `session-1-development.json` pins it alone, and it is never a final test set. `datasets/session-2-*.jsonl` is the final set from one final-only device: Tatar portrait and landscape, Russian portrait, schema 2 session/device/geometry/orientation fields and instructed slow, normal and fast speeds. The Tatar sessions include taps, tremor, canceled, short-drag and unintended-start negative trials; the Russian session has ordinary traces only. `datasets/tatar-gestures-v1.json` pins both splits; it stays immutable and v1 is left unchanged.

`datasets/session-3-ru-portrait.jsonl` adds 148 new schema-2 Russian records. The downloaded
mixed file also contained 101 legacy Tatar records; only the 148 new records were committed as
this session file. It comes from device `2d3e8e57`, session `c392955a`, geometry
`fae93627d76c92da`, board 360x117 and density 480, a second final-only device whose geometry is
distinct from `92508a06`. The session is portrait-only: 133 ordinary records plus three each of
tap, tremor, canceled, short_drag and unintended_start; instructed speeds are slow 49, normal 49
and fast 50, and all seven Russian aliases appear at all three speeds.
`datasets/tatar-gestures-v2.json` freezes 101 development and 576 final records across both final
devices and is evaluated as `reports/final-v2.json`. The new device's ordinary records report
top1 81/133 and visible top3 107/133; all 15 negative controls returned candidates. The three
records labeled `canceled` have `trace_status=completed` and `bad_end`, so they are kept as
collected but do not satisfy real pointer-cancellation coverage.

`fixtures/synthetic.jsonl` is generator output, not human evidence.

Use `research/glide-collector/index.html` for further sessions. The debug Android recorder currently receives only committed glides and its privacy-frozen schema has no session/device/geometry fields, so it cannot supply the required taps, canceled gestures and unintended starts. Do not represent Android-recorder-only collection as negative-case coverage.

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

Both commands use the offline JVM bridge and production `GlideDecoder`; no Android device or Python decoder mirror is involved. The bridge lives in the ordinary app test source set, so standard tests compile it; `glide-benchmark.init.gradle` only registers replay execution. It is absent from both APKs.

Freeze development inputs immediately after collection. The development-only manifest keeps the first session's pin:

```sh
python3 scripts/glide-benchmark.py freeze \
  --development-input research/glide-evaluation/datasets/session-1.jsonl \
  --output research/glide-evaluation/datasets/session-1-development.json
```

The final manifest freezes the same development input with every session from the final-only device, so no session or device identity crosses the splits:

```sh
python3 scripts/glide-benchmark.py freeze \
  --development-input research/glide-evaluation/datasets/session-1.jsonl \
  --final-input research/glide-evaluation/datasets/session-2-tt-portrait.jsonl \
  --final-input research/glide-evaluation/datasets/session-2-tt-landscape.jsonl \
  --final-input research/glide-evaluation/datasets/session-2-ru-portrait.jsonl \
  --output research/glide-evaluation/datasets/tatar-gestures-v1.json
```

The command refuses invalid records, legacy identity fallbacks in final data, and session/device overlap. Store the manifest with the reports; do not edit its pins. Evaluate only the pinned split:

```sh
python3 scripts/glide-benchmark.py evaluate \
  --manifest research/glide-evaluation/datasets/tatar-gestures-v1.json \
  --split final --output research/glide-evaluation/reports/final-baseline.json
```

The v2 manifest adds `research/glide-evaluation/datasets/session-3-ru-portrait.jsonl` as another
`--final-input` to the freeze command and was evaluated with
`--manifest research/glide-evaluation/datasets/tatar-gestures-v2.json --split final --output
research/glide-evaluation/reports/final-v2.json`. The v1 inputs and reports stay unchanged.

Compare decoder candidates on the same frozen inputs, dictionary and geometry:

```sh
python3 scripts/glide-benchmark.py compare \
  research/glide-evaluation/reports/final-baseline.json \
  research/glide-evaluation/reports/final-candidate.json \
  --output research/glide-evaluation/reports/final-comparison.json
```

Visible top-3 is the user-facing metric; engine top-4 is diagnostic only. Endpoint pruning, length pruning, scoring rejection and unmappable/out-of-vocabulary targets are separate outcomes. Reports contain no timestamps or host latency values, and comparison enforces identical ordered record fingerprints.

## Operator-accepted coverage closure and limitations

The operator accepted cautious extrapolation instead of further landscape or real
pointer-cancellation recording. `reports/final-v2-extrapolation.json` is the closure evidence for
the human-coverage planning item. It is deterministic, pins `final-v2.json`, the v2 manifest and
all four final inputs by SHA-256, and labels its top-level evidence as `extrapolation`. The decision
does not turn any projected value into a human measurement.

The measured orientation support is Tatar-only, on device `92508a06`: 148 portrait and 142
landscape ordinary records form 114 fully matched normalized-prompt/instructed-speed strata (38
prompts at each of slow, normal and fast), with no unmatched stratum. Equal-stratum weighting gives
a landscape-minus-portrait top-1 delta of +0.040936 and visible-top-3 delta of +0.011696; pooled
attempt weighting gives +0.028359 and -0.011896 respectively. The report retains all speed-specific
macro and micro deltas rather than hiding this weighting sensitivity.

For Russian landscape, the report adds the minimum and maximum of those eight measured Tatar
orientation deltas to each device's measured Russian portrait rate. On `2d3e8e57`, the resulting
scenario envelopes are top-1 0.570015–0.723058 and visible top-3 0.765061–0.835622. On `92508a06`,
they are top-1 0.649066–0.802108 and visible top-3 0.951375–1.0. These are not confidence intervals
or guarantees, and no Russian landscape point estimate is reported. **No Russian landscape gesture
was measured on either device.**

All 44 v2 negative controls returned non-empty decodes. The nine records labeled `canceled`
comprise three Tatar portrait (`completed`, `bad_end`), three Tatar landscape (`completed`,
`missed_letter`) and three Russian portrait (`completed`, `bad_end`) records; none has
`trace_status=canceled`. They measure decoder output for those completed traces only. They do not
measure real pointer cancellation, gesture arming or editor commit, and the report gives no numeric
Russian landscape negative-control or real-cancellation estimate. **No real Russian
pointer-cancellation trace was measured.**

No additional landscape or real-cancellation collection remains open under the operator decision.
If optional future recording occurs, use the procedure above and freeze it as new evidence; never
mix it with these frozen inputs or describe the extrapolated envelopes as observed outcomes. The
development session still lacks explicit identity, orientation and instructed-speed metadata and
cannot replace final evidence. Do not tune decoder weights against the synthetic fixture or reuse
final records for tuning. The v2 session figures and extrapolated envelopes are not a basis for
broader quality or safety claims in the unmeasured strata.
