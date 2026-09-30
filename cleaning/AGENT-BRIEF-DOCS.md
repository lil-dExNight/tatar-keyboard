# Brief for documentation agents (phase 6)

Repository: `/Users/amirka/Projects/tatar-keyboard/android`. Read `cleaning/CLEANUP-PLAN.md`
sections 3 (writing rules), 4 (decisions), Phase 6 and Appendix D (terminology) first.

## Rules for every document you write

- Canonical English (US spelling, Appendix D terms, no calques from Russian). The only exception is
  the root `README.md`, which stays bilingual. Store changelogs under `metadata/ru-RU` and
  `metadata/tt` stay in Russian and Tatar.
- Short and current. No mission/phase/item codes (`F3`, `S8`, `P7-2`, `E5c`, `SIZE-3`, `T2`,
  `W1`, `Phase B`, `wave`…), no audit/finding numbers, no "operator", "agent", "handoff",
  "uncommitted", no gate tables, no test counts, no drifting byte counts, SHA values or timings
  unless the document is specifically a reference for that number (then say where the pin lives
  in code instead of copying it, when possible).
- No rhetoric ("not a promise but a verifiable property", "earns its place", "centerpiece").
- Facts must match the current code (release 3.6.0, versionCode 43; the suggestion strip has 3
  cells; glide typing shows suggestions on lift only; typo recovery = edit classes #1 long-press
  and #4 single substitution for Tatar, #1 only for Russian; no INTERNET permission). Verify a
  fact in code before writing it.
- Links: only to files that exist after Phase 6. The surviving documents are exactly:
  `README.md`, `PRIVACY.md`, `SECURITY.md`, `CHANGELOG.md`, `AGENTS.md`, `HANDOFF.md`, `BRIEF.md`,
  `docs/README.md`, `docs/ARCHITECTURE.md`, `docs/ASSET-FORMATS.md`, `docs/ASSET-PIPELINE.md`,
  `docs/THREAT-MODEL.md`, `docs/PERF-BUDGETS.md`, `docs/PUBLISH-CHECKLIST.md`, `docs/BACKLOG.md`,
  `docs/HISTORY.md`. Every other `docs/*.md`, all of `docs/archive/` and `research/*.md` are
  about to be deleted: you may READ them as sources, but never link to them.
  Links to code files (`scripts/…`, `app/src/…`) are fine.
- Edit only the files assigned to you. Do not commit, do not run Gradle.

When done, reply in under 250 words: files written, line counts, facts you verified in code, and
anything you were unsure about.
