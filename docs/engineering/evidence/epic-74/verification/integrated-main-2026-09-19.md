# Epic #74 integrated-main acceptance — 2026-09-19

## Decision

Epic #74 is complete within the owner-approved local-development delivery boundary.
The integrated implementation baseline is protected squash
`f5ae54d4f1dab4665b398290524017f0d3d4c656` on `main`. It provides the canonical
private Deck, LearningItem, EditingDraft and CaptureNote loop with immutable native
content, a shared safe renderer and the replacement Angular authoring experience.
All implementation children #171–#202 are closed and merged through protected pull
requests.

This is not a deployment or device-certification claim. The shared server is
unavailable, so no staging, production, SSH, restore or data-cutover operation was
run. Human VoiceOver/TalkBack, physical touch selection, a real Japanese OS IME,
Safari and Firefox remain explicitly unverified manual/device coverage. Chrome,
automated semantics, keyboard, responsive, reduced-motion and synthetic composition
evidence do not masquerade as those sessions. Those limitations do not conceal a
known functional, data-integrity or security failure in the accepted local MVP.

## Epic acceptance mapping

| # | Accepted outcome on integrated `main` | Evidence |
|---:|---|---|
| 1 | An authenticated owner creates a private Deck, captures multilingual content, converts it once, edits a native LearningItem, receives a server draft acknowledgement, reloads/restores, explicitly publishes and reads the result through Browse. Empty/list/error/conflict/retry behavior is covered at its owning layer. | [authoring browser](../authoring-browser/README.md), [authoring UI](../authoring-ui.md), [own Decks UI](../own-decks-ui.md) |
| 2 | The selected immutable UUID block/page representation has recorded 1k/10k/50k membership, 100k fork/fork-of-fork and 1,000-edit measurements. Metadata-only revisions allocate no membership/content pages; direct reads are independent of history length. Raw row, heap/TOAST/index/WAL and bounded-read summaries retain the synthetic-hardware qualification. | [storage choice and raw files](../storage/README.md), [storage kernel](../storage-kernel.md), [native storage](../native-storage.md), [counted pages](../counted-pages.md) |
| 3 | Publication binds deck-local identity and owner ACL to expected heads, receipts and durable roots. PostgreSQL tests cover CAS winners/losers, exact and changed replay, outer rollback/crash boundary, historical reads, stale/foreign identifiers and reachability races without partial publication. | [LearningItem API](../learning-item-api.md), [private Deck API](../private-deck-api.md), [storage review](storage-review.md) |
| 4 | Multiple bounded server EditingDrafts use row-version CAS and acknowledged restore; autosave never publishes. CaptureNote retains source/createdAt without idle expiry, and conversion atomically creates one material while preserving source and exact retry. The real browser reloads an acknowledged draft and re-reads conversion provenance. | [draft/Capture lifecycle](../authoring-lifecycle.md), [authoring browser](../authoring-browser/README.md) |
| 5 | LearningItem is the canonical material term and native content does not require front/back. Versioned projection capability and logical media-reference states form explicit #75/#76 seams without scheduler, StudyState, upload or media lifecycle implementation. | [content platform](../../../../architecture/content-platform-v2.md), [native format](../../../../architecture/learning-content-format-v2.md), [LearningItem API](../learning-item-api.md) |
| 6 | One Mnema-owned native-v1 golden corpus is validated by Java and Angular. Editor, preview and Browse use stable UUID identities and the shared renderer; supported nodes round-trip, while future/unknown nodes remain visible, inert and lossless. ProseMirror state is never the persisted format. | [native boundary review](content-boundary-review.md), [editor evidence](../editor/README.md), [renderer](../native-renderer/implementation.md) |
| 7 | Private routes fail closed through current Identity validation and Learning scopes. Two-account/direct-ID matrices, strict limits, unsafe paste/markup/URL/SVG cases, CSP/network observation and inert renderer behavior cover the adversarial boundary; bearer values and internals are not retained. | [Learning auth black box](../learning-auth-blackbox.md), [identity boundary](identity-and-boundaries.md), [renderer](../native-renderer/implementation.md), [authoring UI](../authoring-ui.md) |
| 8 | Chrome evidence covers native insertion, paste sanitation, ruby, mixed RTL/LTR, undo/redo, long multilingual content, keyboard/focus, 320/390/768/1440 responsive rules, 200% raster evidence, forced colors and reduced motion. Automated semantics are present. Manual/device gaps are listed in the Decision and are not reported as passes. | [authoring UI](../authoring-ui.md), [own Decks UI](../own-decks-ui.md), [environment inventory](environment-capabilities.md) |
| 9 | The rejected glass UI is replaced by the accepted paper/antiquity/indigo shell. Browse and Capture are separate lazy routes; the direct editor runtime is isolated to its guarded lazy chunk. The recorded final build is 591.99 kB raw / 146.68 kB estimated initial transfer; editor is 253.10 kB raw / 67.80 kB estimated and is not loaded by the first useful Deck/Browse screen. | [design direction](../../../../frontend/design-and-experience-2026-09.md), [authoring UI](../authoring-ui.md), [Angular migration](../angular-migration.md) |
| 10 | Canonical Deck/Browse/Capture/editor routes directly replace their v1 product paths: no `/v2`, dual read/write, template/front-back adapter or legacy renderer/builder route remains in the owning slices. Runtime-wide deletion and irreversible data work remain explicitly bounded by #146/#147. | [authoring UI](../authoring-ui.md), [own Decks UI](../own-decks-ui.md), [local delivery](../../../../operations/local-development-delivery.md) |
| 11 | Research and implementation children #171, #172, #173, #175, #176, #178, #179, #181, #183, #185, #187, #188, #192, #194, #200, #201 and #202 are closed. Each production change used protected squash; the final implementation candidate passed local and hosted quality/security gates. #203 owns this merged-main rerun and documentation reconciliation. | Git history at the baseline above; this file's execution record; GitHub Epic #74 and Project 4 |

## Integrated execution record

The baseline was checked on the fresh workstation with JDK 21.0.12.1, Node
22.23.2/npm 10.9.8, Chrome 154, Colima/Docker and disposable PostgreSQL 18.6. The
Colima run requires `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` so
Ryuk mounts the VM socket rather than the host path. A first attempt without that
override failed before application tests with Docker mount status 500; it is an
environment setup failure, not accepted evidence.

- Backend `clean quality`: pass in 2m34s, all 41 tasks executed. Line coverage is
  AI 80.19%, Core 90.48%, Identity 92.25%, Import 80.18%, Learning 95.68% and
  Media 91.01%, all above their configured baselines. No separate backend
  lint/static-analysis task is configured.
- Frontend `npm ci`, lint, test and production build: pass; 162 ChromeHeadless tests,
  zero failures. Production `npm audit --omit=dev --audit-level=low`: zero findings.
- Repository workflow parity: 120 policy tests, 61 smoke tests, every maintained
  release/security/deployment contract, PostgreSQL 16→18 backup recovery and the
  disposable purge rehearsal pass. The purge fixture is policy evidence only,
  never a production cutover.
- Real Identity/Learning security composition: 25 scenarios pass against packaged
  services and disposable PostgreSQL 18.6, including ACL, CAS/retry, revocation and
  fail-closed timeout/outage. Fourteen opt-in cancellation tests plus separate real
  SIGINT/SIGTERM cases pass and remove both processes, their exact container and
  their private directory.
- Real HTTPS authoring composition: Chrome 154 completes Deck → Capture → conversion
  → server draft acknowledgement → hard reload/restore → explicit publish → Browse
  in 2.444 seconds inside the fixture. Unsafe markup stays inert, source/conversion
  provenance survives, and the full browser run reports zero runtime errors and
  zero external requests; cleanup retains no private files.
- Hosted PR #207 checks passed before the implementation squash: `backend-quality`,
  `frontend-quality`, dependency review and CodeQL. Post-merge
  [Main CI run 35454994443](https://github.com/MattoYuzuru/Mnema/actions/runs/35454994443)
  and [Push on main run 35454994297](https://github.com/MattoYuzuru/Mnema/actions/runs/35454994297)
  both succeeded on the exact baseline SHA.

The #203 documentation commit changes no executable code or dependency. Its own exact
pre-push gate and hosted protected-PR checks are required before this assessment can
land.

## Residual boundary and rollback

No acceptance result here implies production capacity, a production SLO or a live
backup. Storage timings describe their recorded synthetic host/configuration. The
remaining screen-reader/device/browser sessions should be performed before claiming
those platforms as certified; a discovered defect is fixed in a bounded follow-up,
not hidden by this closure record.

Rollback is a protected revert of the relevant squash. There was no deployment,
shared database mutation or destructive data operation to reverse. Reactivating a
new VPS and any #147 data transition require separate reviewed work.
