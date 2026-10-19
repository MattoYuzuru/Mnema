---
artifact:
  id: engineering-evidence-index
  type: evidence-index
  title: "Engineering evidence index"
  status: current
  updated_at: "2026-10-19"
  owners: ["project-owner"]
---

# Engineering evidence

**Rule.** Evidence of a change (commands, results, screenshots, review notes, release and
gate receipts) lives in the pull request or the GitHub Issue. This directory keeps only files
that a canonical document, a contract or a test references, so an agent never has to read
history to learn the current behaviour. A file nothing references is deleted; Git history
keeps it. Do not add task ledgers, session handoffs or screenshots here.

Evidence describes one revision and environment. It does not override current code,
contracts or the [delivery boundary](../../operations/production-delivery.md). Records written
before #266 use the removed exercise names (`TYPED`, `LISTEN_TYPE`, `CLOZE_SINGLE`,
`SINGLE_CHOICE`, `LISTEN_CHOICE`, `AUDIO_TEXT_MATCH`) and older wire shapes; the current mechanics
are in [`contracts/study`](../../../contracts/study/README.md#exercise-mechanics-266).

## What is kept

| Evidence | Referenced by | Purpose |
|---|---|---|
| [Catalogue plans and extensions](./community-decks/epic-3/README.md), [`catalog-scale.sh`](./community-decks/epic-3/catalog-scale.sh), [10 000](./community-decks/epic-3/catalog-scale-10000.txt) and [100 000](./community-decks/epic-3/catalog-scale-100000.txt) | Learning guide, `CatalogScaleIntegrationTest`, community architecture | Reproducible `EXPLAIN` and timings of the community catalogue |
| [Ranker comparison](./community-decks/epic-4/README.md) with `evaluate-rankers.py`, fixture, synthetic logs, result, SQL export and [scale run](./community-decks/epic-4/scale-local-2026-10-13.txt) | `scripts/tests/test_ranker_evaluation.py`, community architecture | Reproducible comparison of `catalog-v1` and `recs-v1` and the scenario H sizes |
| [Lineage prototype](./community-decks/epic-1/share-3-lineage-prototype.md) with its two SQL files | Community architecture §4, §15 | The prototype that confirmed CD-1 and CD-2 |
| [Epic #74 integrated acceptance](./epic-74/verification/integrated-main-2026-09-19.md) and the reports it maps (storage, authoring, renderer, boundaries, environment) | Content contract, counted-page contract, revision-storage architecture, repository guide | Closure record of the content and authoring baseline |
| [Storage research](./epic-74/storage/README.md) with raw summaries and experiments, [storage kernel](./epic-74/storage-kernel.md), [native storage](./epic-74/native-storage.md), [counted pages](./epic-74/counted-pages.md), [storage review](./epic-74/verification/storage-review.md) | Revision-storage architecture | Measurements behind the immutable-page storage choice |
| [Image environment](./epic-74/verification/image-environment.md) | Agent runbook, local runtime guide | Why the pinned MinIO image must come from the local image cache |
| [Paper shell spike](./epic-74/editor/paper-shell-prototype/angular/paper-landing.component.html) | Repository guide, design direction | Design evidence of the paper landing, not runtime code |
| [Epic #75 integrated acceptance](./epic-75/verification/integrated-main-2026-09-24.md) | System overview, repository guide | Closure record of the Study baseline |
| [Epic #76 integrated browser run](./epic-76/integrated-browser/README.md), [component visuals](./epic-76/media-visual/README.md), [phone-derived worker run](./epic-76/phone-worker.md) | System overview, media worker README | Closure evidence of media (three listening types that #266 later replaced) |
| [AI-17 evaluation](./epic-77/run-5/ai-17/README.md) | Generation eval contract | Frozen corpus, candidates and quality limits of the AI evaluation |
