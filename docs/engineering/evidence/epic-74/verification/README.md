# Epic #74 independent verification plan

Status: pre-implementation strategy, not acceptance evidence

Target revision inspected: `33a71f814185a16e922923e034518e25baeadbb8`

Branch inspected: `epic-74/research-contracts`

Prepared: 2026-09-06

## Decision this plan supports

Decide whether a stable #74 candidate implements private own-deck authoring, native
LearningItem content, durable drafts/capture, bounded revisions, and the replacement
Angular experience without crossing into #75 Study or #76 media lifecycle. Passing
tests on another SHA, the standalone prototype, or an isolated spike are context,
not proof for the candidate.

No implementation candidate existed and no full quality gate was run while preparing
this plan. All matrix rows are therefore `NOT RUN` unless explicitly described as
existing baseline evidence.

## Actionable blockers and gaps

1. R74-S and the owner's storage-schema decision are prerequisites to C74-1. The
   current block/page layout, limits, indexes, transaction boundary, and GC policy
   remain proposed. No storage implementation may be accepted from prose or asymptotic
   claims alone.
2. R74-E and dependency approval are prerequisites to the production editor adapter.
   The persisted AST must remain Mnema-owned. The editor engine and exact initial
   editable node set are not yet accepted.
3. Learning has no authentication/security dependency, filter, issuer configuration,
   or Identity client. The current Identity implementation validates token type,
   signature, issuer, audience, active account generation, and an active JDBC grant
   only inside the Identity deployable. Private deck endpoints are blocked until an
   inter-service validation/revocation contract is selected and tested.
4. The recommended bounded #74 seam is local JWT verification plus fail-closed
   bearer-token relay to existing `/userinfo` on every private request. It preserves
   the Identity backend boundary, but needs an explicit `sub`/scope/timeout/outage/
   revocation contract and no cached successes. Add `learning.read/learning.write`
   to the configured web client and frontend request; `account.*` must not silently
   become content permission. OAuth introspection remains an alternative requiring
   a confidential resource client/secret and proof that current generation is
   enforced. Do not read `app_identity` from Learning.
5. Both replacement services currently receive the same PostgreSQL credentials in
   local/deployment manifests. Static source separation does not prove least privilege.
   Record this as a launch-hardening risk with an owner/follow-up; #74 can still run a
   synthetic least-privilege test and must not introduce cross-schema SQL.
6. The checked-in Angular client is still the v1 UI. It requests legacy
   `user.read user.write` scopes, uses legacy service URLs/routes, stores tokens in
   `sessionStorage`, and eagerly imports old route components. It is not current
   Identity/Learning integration evidence. Angular is 18.2.14, while the repository
   direction says latest stable; any required upgrade/dependency edit needs the
   mandated owner approval.
7. There is no #74 browser E2E suite or shared backend/frontend golden-contract corpus.
   Karma component tests cannot prove reload, real API persistence, multi-tab CAS,
   authentication revocation, or route-level accessibility.
8. The Learning and Identity Testcontainers bases fail closed with
   `disabledWithoutDocker = false`, but legacy core/media/import/AI suites still use
   `disabledWithoutDocker = true`. #74 tests must inherit the fail-closed Learning
   base. No checked-in container-skip verifier exists; use the proposed post-run audit
   in [quality-gates.md](quality-gates.md). Unrelated legacy skips are deletion evidence,
   not permission to skip new tests.
9. Local desktop Chrome, Safari, VoiceOver, cached Chromium, and an external one-off
   Python Playwright runtime are available. There is no repository-owned E2E harness,
   axe, or Lighthouse. Real Android/TalkBack, iOS hardware/simulator, and Firefox are
   unavailable locally. See [environment-capabilities.md](environment-capabilities.md).
10. `LearningApplicationIntegrationTest` currently asserts that Learning has no
    controllers and that `/api/decks` is absent, while `LearningBoundaryTest` treats
    `/decks` as a legacy prefix. The first canonical API slice must replace these
    foundation-only assertions with a precise no-legacy/no-`/v2` inventory; otherwise
    the correct #74 route is rejected by its own baseline test.
11. Learning's stable problem-code enum currently has no authentication or forbidden
    code. The auth slice must define consistent RFC 9457 401/403 responses and ensure
    Spring Security failures use them without exposing decoder/Identity internals.

## Scope boundary

### #74 must prove

- private deck ownership and direct-ID isolation;
- paged deck summaries/details and bounded item reads;
- deck-local `(deckId, memberKey)` logical identity;
- economical immutable revisions, atomic publication, expected-head CAS, and
  idempotent command retry;
- durable server EditingDraft and CaptureNote lifecycle;
- native versioned AST, stable node IDs, visible preservation of unknown nodes,
  and a non-executable renderer;
- paper/antiquity/indigo Angular replacement shell and the real authoring/Browse loop;
- projection and media-reference seams usable by later epics;
- removal of superseded v1 content/frontend paths owned by #74.

### #74 provides a seam, not an implementation

- #75: exercise/objective revision references, projection capability validation,
  and saved-content eligibility boundary. No scheduler, StudyState, real session,
  attempt reducer, replay, practice, mastery, or restart implementation is required.
- #76: typed authorized asset references plus missing/processing/unsupported renderer
  states. No upload, object lifecycle, transcoding, media GC, or offline package is
  required.
- Future forks: the storage spike must prove cheap fork/fork-of-fork locality and
  independent namespaces; catalog/fork/update/contribution UI and public ACLs are
  not #74 launch behavior.

### #146/#147 boundary

- #74 deletes only superseded canonical v1 deck/card/template/content/editor/renderer
  paths in its owning slices. It must not add `/v2`, dual reads/writes, aliases, or
  wrappers around those paths.
- #74 must not remove the complete legacy runtime/build topology, media/import/AI
  modules, manifests, backups, or data. Those exact-target deletions remain #146
  after sufficient #74-#76 replacement gates.
- #74 must not execute account/data cutover, purge production state, delete backups,
  or open replacement writes. Those irreversible actions remain #147 behind a
  separate go/no-go. The PR quality purge rehearsal is disposable policy evidence,
  not authorization or evidence of production cutover.

## Evidence set

- [acceptance-matrix.md](acceptance-matrix.md) — requirement and adversarial cases.
- [identity-and-boundaries.md](identity-and-boundaries.md) — Identity/Learning
  authentication, ownership, and deletion-boundary verification.
- [quality-gates.md](quality-gates.md) — exact current workflow commands, DB tests,
  skip verifier, evidence retention, and stop/cleanup rules.
- [environment-capabilities.md](environment-capabilities.md) — available local
  browser, screen-reader, device, and automation evidence.

## Sources inspected

The plan reconstructs requirements from `AGENTS.md`, the full Epic #74 end-to-end
prompt and refinement, the work-item standard, v2 delivery/owner decisions,
authoring workflows, all three canonical content/storage architecture documents,
the accepted frontend direction, and the prototype README/screenshots inventory.
Executable evidence came from the PR/dependency-review workflows, root/backend/
Learning build files, Learning guide/migration/platform tests, Identity guide and
security/authorization tests, current Angular config/auth/routes, compose, and the
Learning release manifest.

## Acceptance rule

Epic #74 is acceptable only when every P0 row in the matrix is `PASS` on the exact
integrated candidate, required owner decisions are recorded, `backend-quality` and
`frontend-quality` are green without weakened thresholds/skips, the full local gate
is reproduced as far as the documented environment permits, and every remaining
unverified item has an owner and does not contradict the Definition of Done. The
first create/capture/save/reload path is a milestone, not Epic completion.
