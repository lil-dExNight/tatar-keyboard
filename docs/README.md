# Documentation index

All documents are in English except the root `README.md`, which is bilingual, and
`PRIVACY.ru.md` and `PRIVACY.tt.md`, the Russian and Tatar privacy policies.

## Repository root

- [README.md](../README.md): user-facing overview and install instructions. Build instructions
  live in [AGENTS.md](../AGENTS.md).
- [PRIVACY.md](../PRIVACY.md): privacy policy; the app links to it. [PRIVACY.ru.md](../PRIVACY.ru.md) and [PRIVACY.tt.md](../PRIVACY.tt.md) are its Russian and Tatar versions.
- [SECURITY.md](../SECURITY.md): how to report a vulnerability, supported versions, re-audit cadence.
- [CHANGELOG.md](../CHANGELOG.md): user-facing changes per release.
- [AGENTS.md](../AGENTS.md): build, test and release commands, hard constraints.

## docs/

- [ARCHITECTURE.md](ARCHITECTURE.md): input path, suggestion engine, threads, personal stores, glide typing.
- [ASSET-FORMATS.md](ASSET-FORMATS.md): binary formats of the bundled dictionaries, bigram tables, cold-companion lists and personal-dictionary files.
- [ASSET-PIPELINE.md](ASSET-PIPELINE.md): how `scripts/rebuild_assets.py` builds the bundled data, pinned sizes and SHA-256, known drift.
- [THREAT-MODEL.md](THREAT-MODEL.md): assets, trust boundaries, controls and the residual-risk register.

## Other locations

- `data/` holds inputs of the asset pipeline and test data (dictionary review and acceptance
  tables), the provenance shards of `data/provenance.d/`, the corpus-source comparison results
  and attribution of `data/corpus-sources/`, and the evaluation-split declarations of
  `data/evaluation/`.
- `docs/` holds Markdown only; run evidence (screenshots, logs, dumps) goes to `build/`.
- `research/corpus/` holds the corpus measurement scripts named in ASSET-PIPELINE.md; corpus
  data is not committed. `research/corpus-sources/` fetches and compares the licensed Tatar
  source candidates (TatSC, Common Voice); `research/glide-evaluation/` holds the gesture
  benchmark protocol, frozen manifests and reports; `research/text-donation/` is the offline
  page native speakers use to donate their own text for the evaluation set, read by
  `scripts/native_text_ingest.py`.
