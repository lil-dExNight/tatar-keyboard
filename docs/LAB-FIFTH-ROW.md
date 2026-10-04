# Fifth-row A/B/C lab protocol

The standing protocol of the fifth-row order experiment (UX20 in [research/ux.md](../research/ux.md)).
Status: the instrument is landed (arm switch, lab session log, contract tests); sessions are
pending participants. This document is the operator's runbook; it holds no results.

## Arms

The Tatar layout's fifth row carries the six extra letters. The experiment compares three orders:

| Arm | Order | Row file |
|---|---|---|
| A | `ә ө ү җ ң һ` (shipped, pair order) | `app/src/main/res/xml/rowkeys_tatar_extra.xml` |
| B | `ә ү ң ө җ һ` (frequency order) | `app/src/main/res/xml/rowkeys_tatar_extra_b.xml` |
| C | `һ ө ә ү ң җ` (incumbent desktop scan order) | `app/src/main/res/xml/rowkeys_tatar_extra_c.xml` |

The arm is a developer setting: keyboard settings → **Developer** → **"Fifth-row study arm"**.
The default is A, and the shipped layout is arm A byte-identically — arms B and C only add
variant row files (`rows_tatar_b/c.xml`, `kbd_tatar_b/c.xml`) that the layout-set builder selects
for the Tatar alphabet element when the setting says so. Only the Tatar layout set is affected;
Russian and English never change. A switch applies to the next keyboard shown (the keyboard cache
is cleared when the setting changes). The orders are pinned by `FifthRowArmTest`.

## Lab session log

The second developer setting, **"Lab session log"** (off by default), is the measurement
instrument. While it is on — and only while it is on — the IME appends one ASCII line per event
to `files/lab-session.log`:

```
<epochMillis> <kind> <keyCode> <arm>
```

- `kind`: 1 = key down of a printable key, 2 = space, 3 = delete, 4 = keyboard shown,
  5 = keyboard hidden. Constants in `LabSessionLogWriter`.
- `keyCode`: the layout key's code — the Unicode code point for a printable key (e.g. `1241`
  for `ә`), -5 for delete, 32 for space, 0 for the shown/hidden lines.
- `arm`: 0/1/2 for A/B/C.

Example (letter `ә` on arm A, then a delete, then the keyboard hides under arm C):

```
1759500000123 1 1241 0
1759500000456 3 -5 0
1759500000789 5 0 2
```

Rules of the log:

- **Content-free by construction.** The writer's API takes numbers only — no string can pass
  through it (pinned by `LabSessionLogContractTest`). Note what the codes still are: in sequence,
  printable-key codes correspond to the letters pressed. Treat a pulled file as participant
  content.
- **Gated.** Nothing is written while the setting is off, before the first unlock after boot, or
  in password fields.
- **Capped.** One rotation to `lab-session.log.1` at `LabSessionLogWriter.MAX_BYTES`; the pair
  stays under about twice that.
- **Local.** The file lives in the app's internal folder, is excluded from backup like everything
  else, and never leaves the device on its own.
- **Erasable.** **"Clear lab session log"** on the Developer screen deletes both files; clearing
  the app's data does too.

## Participants

N=24, native and L2 Tatar writers recruited via the KFU and community channels (per UX20).
Record per participant: self-reported Tatar typing experience (daily / occasional / new), and
whether the incumbent desktop layout is their desktop reference — the tiebreak metric reads from
the new-typist subgroup.

## Assignment

Within-subject: every participant types under all three arms. The arm order follows a Latin
square over the three arms — six orderings (ABC, ACB, BAC, BCA, CAB, CBA), four participants per
ordering at N=24. The operator sets the arm on the device before each arm's first block and
verifies the log's arm column afterwards.

## Session script

One sitting per participant, on the reference device, on a **debuggable** build (see the pull
procedure below):

1. Consent and the profile questionnaire. The log toggle is shown to the participant and turned
   on together.
2. Warm-up: three minutes of free transcription under arm A (not analyzed).
3. Per arm, in the assigned order: five blocks. A block is the transcription of one sentence set,
   alternating the natural-frequency set and the extra-letter-dense set. The sentence sets are
   placeholders pending selection: `research/lab/fifth-row-sentences-natural.txt` and
   `research/lab/fifth-row-sentences-extra.txt`. Block 1 measures learnability, block 5 the
   ceiling. Between blocks the operator dismisses the keyboard, so the kind-5/kind-4 line pairs
   delimit blocks in the log.
4. After each arm: SUS and NASA-TLX questionnaires, and the operator pulls the log and copies the
   produced text out of the transcription field into the session record (MSD needs the produced
   text; see below).
5. After the third arm: the forced-choice ranking ("which order would you keep?").

## Metrics

- **WPM per block**: letter and space events per minute over five-char words, from the log's
  timestamps.
- **MSD error rate**: minimum string distance between the stimulus and the produced text, per
  block. Computed from the session record's produced text, not from the log.
- **KSPC**: letter+space+delete events (log) per character of the produced text.
- **SUS and NASA-TLX** per arm; **forced-choice ranking** at the end.
- **Tiebreak metric**: first-session success of new Tatar typists (block-1 completion without
  assistance).

## Pre-registered gate

Quoted from UX20: a challenger (B or C) wins only with median WPM ≥5% better than A in the final
block, MSD error not worse, and ≥60% of participants ranking it first; the tiebreak is
first-session success of new Tatar typists (the product's mission). No win, no change: arm A
stays.

## Pull-and-analyze procedure

The pull needs a debuggable build (`run-as` follows the debuggable flag), and the instrument
itself — the screen, the arm, the logging — exists only there, so sessions run on a debug build:

```
adb shell run-as org.tatarkeyboard.ime.debug cat files/lab-session.log   > participant-NN-arm-X.log
adb shell run-as org.tatarkeyboard.ime.debug cat files/lab-session.log.1 > participant-NN-arm-X.log.1
```

(The `.1` file exists only after a rotation.) After a successful pull, clear the log from the
Developer screen. Analysis expectations: a script or notebook (placeholder
`research/lab/fifth_row_analysis.py`, pending) parses the lines, splits blocks at the
shown/hidden pairs, and emits per-participant per-arm WPM and KSPC; MSD comes from the session
record's texts. Aggregate medians per arm go into the UX20 write-up; individual logs stay with
the operator and are deleted with the session records after the write-up.

## Privacy rules

- The log is opt-in, off by default, and content-free by construction (above). It records in no
  password field and before no first unlock.
- The file never leaves the device on its own; the only way off is the owner (or, in the study,
  the operator holding the participant's device) pulling it with adb, which needs a debuggable
  build.
- Consent is explicit: the toggle is flipped together with the participant, and the pulled files
  are pseudonymized by participant number.
- After analysis, the device copy is erased (Developer screen) and the pulled copies are deleted
  with the session records.
- The user-facing statement lives in `PRIVACY.md` ("Lab session log"); the risk register entry in
  `docs/THREAT-MODEL.md`.
