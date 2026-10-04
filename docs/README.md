# Documentation index

One line per document. All documents are in English except the root `README.md`, which is
bilingual.

## Repository root

- [README.md](../README.md) — user-facing overview, install and build instructions.
- [PRIVACY.md](../PRIVACY.md) — privacy policy; the app links to it.
- [SECURITY.md](../SECURITY.md) — how to report a vulnerability, supported versions, re-audit cadence.
- [CHANGELOG.md](../CHANGELOG.md) — user-facing changes per release.
- [AGENTS.md](../AGENTS.md) — build, test and release commands, hard constraints.
- [HANDOFF.md](../HANDOFF.md) — current state: release, work in progress, open release steps.
- [BRIEF.md](../BRIEF.md) — product vision and fixed decisions.

## docs/

- [ARCHITECTURE.md](ARCHITECTURE.md) — input path, suggestion engine, threads, personal stores, glide typing.
- [ASSET-FORMATS.md](ASSET-FORMATS.md) — binary formats of the bundled dictionaries, bigram tables and personal-dictionary files.
- [ASSET-PIPELINE.md](ASSET-PIPELINE.md) — how `scripts/rebuild_assets.py` builds the bundled data, pinned sizes and SHA-256, known drift.
- [THREAT-MODEL.md](THREAT-MODEL.md) — assets, trust boundaries, controls and the residual-risk register.
- [PERF-BUDGETS.md](PERF-BUDGETS.md) — performance budgets and the test or script that enforces each.
- [DEVICE-TEST-PLAN.md](DEVICE-TEST-PLAN.md) — end-to-end test of the keyboard on a connected phone.
- [PUBLISH-CHECKLIST.md](PUBLISH-CHECKLIST.md) — release procedure, from preflight to store upload.
- [ROADMAP.md](ROADMAP.md) — mandatory development plan: all open work, in order.
- [HISTORY.md](HISTORY.md) — removed documents, with the last commit that contains each.

## Other locations

- `data/` holds inputs of the asset pipeline and test data (dictionary review and acceptance tables).
- `docs/` holds Markdown only; run evidence (screenshots, logs, dumps) goes to `build/`.
