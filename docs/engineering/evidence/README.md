---
artifact:
  id: engineering-evidence-index
  type: evidence-index
  title: "Engineering evidence index"
  status: current
  updated_at: "2026-09-19"
  owners: ["project-owner"]
---

# Engineering evidence index

Evidence is historical proof for a specific revision and environment. It does not
override current code, accepted contracts or the local-only delivery boundary.

## Epic #74 closure

- [Integrated main acceptance](./epic-74/verification/integrated-main-2026-09-19.md)
  is the shortest authoritative closure record.
- [Verification index](./epic-74/verification/README.md) separates final acceptance
  from the retained pre-implementation strategy.
- [Acceptance matrix](./epic-74/verification/acceptance-matrix.md) maps adversarial
  cases; [quality gates](./epic-74/verification/quality-gates.md) records exact runs.

## Storage и content

- [Storage research index](./epic-74/storage/README.md) routes to measurements,
  raw summaries and reproducible experiments.
- [Storage kernel](./epic-74/storage-kernel.md),
  [native storage](./epic-74/native-storage.md) and
  [counted pages](./epic-74/counted-pages.md) are implementation evidence.
- [Content boundary review](./epic-74/verification/content-boundary-review.md) and
  [LearningItem API](./epic-74/learning-item-api.md) cover the #75-facing contracts.

## Frontend и browser

- [Angular migration](./epic-74/angular-migration.md),
  [editor evidence](./epic-74/editor/README.md) and
  [native renderer](./epic-74/native-renderer/implementation.md) retain decisions.
- [Browser Identity](./epic-74/browser-identity.md),
  [own Decks UI](./epic-74/own-decks-ui.md) and
  [authoring browser](./epic-74/authoring-browser/README.md) contain local browser
  results and screenshots. They do not claim unrun screen-reader/device sessions.

## Security и boundaries

- [Learning security composition](./epic-74/learning-auth-blackbox.md) and
  [identity/boundaries](./epic-74/verification/identity-and-boundaries.md) retain
  authentication, cancellation and direct-ID evidence.
- [Environment capabilities](./epic-74/verification/environment-capabilities.md)
  records what was and was not available for manual/device verification.

The remaining files under `epic-74/` are supporting raw evidence, prototypes or
slice reports linked by these indexes. Keep them out of the ordinary reading path;
do not delete them merely because the Epic is complete.
