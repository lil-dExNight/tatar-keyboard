# Documentation index

All documents are in English except the root `README.md`, which is bilingual, and
`PRIVACY.ru.md`, the Russian privacy policy for RuStore.

## Repository root

- [README.md](../README.md): user-facing overview, install and build instructions.
- [PRIVACY.md](../PRIVACY.md): privacy policy; the app links to it. [PRIVACY.ru.md](../PRIVACY.ru.md) is its Russian version.
- [SECURITY.md](../SECURITY.md): how to report a vulnerability, supported versions, re-audit cadence.
- [CHANGELOG.md](../CHANGELOG.md): user-facing changes per release.
- [AGENTS.md](../AGENTS.md): build, test and release commands, hard constraints.

## docs/

- [ARCHITECTURE.md](ARCHITECTURE.md): input path, suggestion engine, threads, personal stores, glide typing.
- [ASSET-FORMATS.md](ASSET-FORMATS.md): binary formats of the bundled dictionaries, bigram tables, cold-companion lists and personal-dictionary files.
- [ASSET-PIPELINE.md](ASSET-PIPELINE.md): how `scripts/rebuild_assets.py` builds the bundled data, pinned sizes and SHA-256, known drift.
- [THREAT-MODEL.md](THREAT-MODEL.md): assets, trust boundaries, controls and the residual-risk register.

## Other locations

- `data/` holds inputs of the asset pipeline and test data (dictionary review and acceptance tables).
- `docs/` holds Markdown only; run evidence (screenshots, logs, dumps) goes to `build/`.
- `research/corpus/` holds the corpus measurement scripts named in ASSET-PIPELINE.md; corpus
  data is not committed.
