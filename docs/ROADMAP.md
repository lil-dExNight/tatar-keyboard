# Roadmap

The mandatory development plan after 3.8.0. Every item here must be done, or
closed by an explicit decision recorded in this file, before new features are started. Work
goes in the order of the sections. Delete an item when it is done, since the change itself is
the record. Delete this file when it is empty and list it in `docs/HISTORY.md`.

Parked and rejected ideas are in `docs/BACKLOG.md`.

## 1. Open decisions and publication

1. **Decision on the glide context rerank (A6).** The implementation exists as
   `A6-glide-context-rerank.patch` in `tatar-keyboard-parked/` next to the repository. It
   measured below the decision rule. The threshold was set without the operator, who now either
   confirms it (the item stays parked in `docs/BACKLOG.md`) or sets a new threshold. With a new
   threshold, the calibration test runs again and the patch ships only if it passes.
2. **Publication of 3.7.0 and 3.8.0:** the steps listed in `HANDOFF.md` ("Open release steps").

## 2. Device work

1. **Checks that need a person or hardware not at hand:**
   - live Direct Boot (needs a screen-lock PIN and a reboot: type the PIN with this keyboard
     before the first unlock);
   - Telegram (typing, suggestions, autocorrect undo, glide spacing, emoji panel; the app is not
     on the test device);
   - TalkBack by ear (the spoken key descriptions, language announcement and digit popups; their
     text is verified, the speech is not);
   - tablet layout on tablet hardware (verified on an emulator tablet profile only).

## 3. Product decisions

1. **Order of the fifth-row keys** (alphabetical, as today, or by frequency). This is the open
   question in `BRIEF.md`, to be decided by user testing; record the result there.
