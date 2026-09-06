---
artifact:
  id: product-refinement-2026-09
  type: research-handoff
  status: reviewed-research-handoff
  created_at: "2026-09-06"
  updated_at: "2026-09-06"
---

# Product refinement: handoff before implementation

Owner clarification is captured in canonical documents, not only this report.
This local change updates requirements/architecture and provides an interactive
design prototype. It does not implement Learning API domains, change production
or alter dependencies. The owner subsequently authorized publishing this bounded
docs/prototype change through a protected PR and squash merge; it does not mark the
epics implemented or update their board status. Exact-head checks and merge evidence
belong to that PR. Research branch:
`research/product-contracts-2026-09-06`, based on `2c5443f`.

## Where to start

1. [Authoring and study workflows](../product/authoring-and-study-workflows.md):
   own-deck launch; recoverable editing drafts versus durable quick notes;
   arbitrary materials and custom exercise projections; material-level progress;
   preserved scheduling after answer edits; explicit restart; three session modes;
   future community, native offline and paid-limit hypotheses. WF-01–10 give
   observable implementation scenarios.
2. [Revision storage and runtime boundaries](../architecture/revision-storage-and-runtime-boundaries.md):
   logical snapshots versus physical copying, Git/TOAST evidence, shared semantic
   blocks and paged roots, cheap fork locality, selective merge ancestry, bounded
   bulk publication/read paths, independently scalable workers and GC safety.
   Physical schemas/limits are proposals that need an implementation spike.
3. [Design and experience](../frontend/design-and-experience-2026-09.md):
   accepted paper/antiquity/indigo direction, Russian landing, typography, repeated
   motifs, authoring/study states, accessible interaction, lazy route/API needs
   and production boundaries.
4. [Interactive prototype](../../design/prototype/README.md): start with
   `node design/prototype/serve.mjs`, then open `http://127.0.0.1:4173`.
   [Illustration provenance and prompt](../../design/prototype/assets/mnemosyne-provenance.md).
5. [Delivery sequence](../engineering/v2-delivery-plan-2026-08.md): #74 content/UI,
   #75 Study, #76 media boundaries; frontend can proceed against agreed fixtures.
   #146/#147 remain gated by replacement behavior, not by launching community/AI/native apps.
6. [Next #74 refinement tasks](../engineering/epic-74-refinement.md): storage/editor
   spikes with timeboxes and acceptance evidence, contract gate and slice dependencies.

## Reconciliation performed

Updated owner decisions, product direction, content-platform overview, native
format, exercise catalog, operations/offline plan, delivery plan, frontend audit
supersession, economic-research status, historical review and navigation/AGENTS.
Removed active contradictions around full snapshot copies, 1:1 exercise bindings,
cross-deck logical identity, automatic answer revalidation, mandatory launch
sharing, permanent no-upstream policy and an undecided visual direction.

Earlier AI Starter economics remain historical scoped evidence, not a committed
deck/offline subscription. Manual selective pull is recommended, not falsely
recorded as an owner choice over automatic tracking. The proposed 30-day draft
recovery, size limits and compact matching labels are engineering defaults, not
approved commercial tariffs or measured capacity guarantees.

Two bounded delegated tasks handled the architecture detail and the design/prototype.
The lead read both complete reports and their changed documents/source, reconciled
the main product contracts and made small post-handoff corrections: explicit
manual-pull proposal status, production split-preview requirement, SVG MIME type
and frontend lazy-loading/API details. The original Downloads prototype/reference
images were preserved.

## Initial research verification on 2026-09-06

- Documentation: relative local link targets exist; fenced code blocks are closed;
  all three JSON examples parse; `git diff --check` passes. Mermaid relations were
  reviewed conceptually, not validated by an installed Mermaid compiler.
- Prototype: Node syntax checks pass; delegated Chromium checks cover connected
  creation/editing/study/replay flows, keyboard interaction, four AA text contrast
  pairs and no horizontal overflow at 320/390/768/1440 px. Lead independently
  checked a mobile landing anchor, literal HTML preview, save/revision, practice
  and matching, image/font loading and SVG MIME; no page errors. Screenshots are
  under `design/prototype/screenshots/`.
- Frontend: lint, 51 ChromeHeadless tests and production build pass using installed
  dependencies. Build reports 1.68 MB initial raw / about 290 kB estimated transfer
  for the old Angular application; this is not the new prototype's network budget
  or a measured LCP. Dependency freshness warnings were not resolved by changing packages.
- Backend: direct `./gradlew quality --rerun-tasks --console=plain` passes with the
  local Colima/Testcontainers settings. Every configured coverage threshold passes:
  AI 80.19%, core 90.48%, Identity & Account 92.12%, import 80.18%, Learning 94.81%,
  media 91.01%. Docker-required verifier: 26 suites, zero skipped/failures/errors.
  The build config has compilation/tests/coverage, but no separate backend lint
  or static-analysis task in its aggregate quality gate.
- Initial attempts are not hidden: the wrapper stopped on deleted
  `:services:auth:test`; an unconfigured direct run could not start containers;
  a later incremental run reused skipped core results and reported 89.10% coverage.
  The final forced run with correct Docker settings supersedes those backend
  results. Stale ignored auth/user build reports are not evidence of active modules.
- The complete `scripts/dev/run-local-quality.sh` wrapper is still blocked by its
  stale auth probe. Its later deployment/recovery script suites were not reached
  or all rerun separately. Do not describe the entire repository wrapper as green.
  No push or merge was performed during that initial research pass. The local harness owner should replace the probe
  with a current runtime suite without weakening the no-skipped-container gate.

The prototype is memory-only/plain-text with no real auth, scheduler, media upload,
durable server drafts, rich editor or offline sync. It demonstrates interaction,
not implementation completion. VoiceOver/TalkBack, Safari/Firefox and real device
verification remain production gates. Storage amplification, 50k/100k deck behavior,
GC races and native multi-device ordering are specified future tests, not measured
results from this research.

## Remaining implementation decisions

Select and prove the storage LLD/page bounds and editor engine; freeze exact DTOs,
node schemas and projection validation; choose versioned reducer and an explainable
material aggregate. Test stale/offline attempts across explicit learning restart.
Community update policy, billing numbers and paid offline can wait for their product
phase. These are named decisions, not reasons to reopen accepted deck-local behavior
or to mark the entire #74–#76 scope Ready at once.
