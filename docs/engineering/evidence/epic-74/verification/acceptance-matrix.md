# Epic #74 acceptance and adversarial matrix

All rows are `NOT RUN` at revision
`33a71f814185a16e922923e034518e25baeadbb8`. `Accepted` means the expected behavior
is an accepted product/architecture input. `Decision` means a proposed detail needs
the named spike/owner decision before it becomes a defect oracle. `Seam` means #74
must expose and test the boundary but must not implement the adjacent epic.

Evidence for every executed row records candidate SHA, environment, fixture seed,
exact command/steps, expected and actual results, timestamps, relevant request IDs,
and artifact paths. Never retain tokens, private content, or production data.

## P0 end-to-end, ownership, and API behavior

| ID | Oracle | Scenario and fixture | Expected result | Evidence |
|---|---|---|---|---|
| E2E-01 | Accepted | Authenticated A creates private deck, creates a CaptureNote in “На потом”, converts it to a material draft, edits native content, saves, hard reloads, and continues editing. | Deck, note source/createdAt, saved revision, and acknowledged draft survive real API reload; capture converts once; input and save-state messages remain understandable. | Browser trace/video/screenshots + API/DB assertions on exact SHA. |
| E2E-02 | Accepted | Repeat E2E-01 with one network failure during capture, autosave, conversion, and publish. Retry each command with the same command ID/payload. | No duplicate deck/note/material/revision; unacknowledged text is not labelled saved; acknowledged state is recoverable. | Browser + server integration with deterministic fault points. |
| AUTH-01 | Accepted | Missing, malformed, ID token, expired token, wrong `typ`, issuer, audience, signature/kid, or non-UUID subject against every private route. | 401 in stable problem format; no DB query/mutation based on attacker identity; no token/internal details in body/log. | HTTP security parameterized tests and sanitized logs. |
| AUTH-02 | Accepted | Valid token with `learning.read` but not `learning.write` attempts a mutation; token with only `account.*` attempts content access; valid `learning.write` requests an owned resource. | Missing Learning scope is 403; account scope does not imply content scope; allowed Learning scope succeeds. Actor/owner always comes from authenticated `sub`, never body/query. | Learning security integration tests. |
| AUTH-03 | Accepted | A's valid token reads/updates B's deck, item, revision, draft, capture, or guessed opaque cursor/direct physical revision ID. | Access denied with the contract's non-enumerating response; no cross-owner content, metadata, hash, count, timing oracle, or mutation. | Two-account real-DB matrix for every resource shape. |
| AUTH-04 | Accepted | Password change, reset, logout, ban, admin/factor revocation, and account deletion occur after token issuance; old token retries read and write. | Old generation/grant is rejected immediately at the documented request boundary; unban does not resurrect it. | Cross-service real-HTTP tests, not mocked claims. |
| AUTH-05 | Accepted | Identity is unavailable, slow, malformed, or returns mismatched `sub` during authorization. | Private operation fails closed within configured deadline; no mutation; no stale-success cache silently extends revoked access. | Fault test + timeout metric/log without bearer token. |
| AUTH-06 | Accepted | Inspect Learning source, migrations, queries, and tests for Identity coupling; run with isolated synthetic roles where feasible. | No `app_identity` SQL or Identity source/module dependency; all current-account checks use the HTTP seam. Shared deployment credentials remain a separately owned launch-hardening risk. | Static boundary guard + synthetic DB denial test. |
| API-01 | Accepted | Create deck with valid name; replay identical command; reuse command ID with changed actor/scope/type/payload. | First is created, exact replay returns stored result, mismatched replay is `IDEMPOTENCY_CONFLICT`; one deck exists. | Contract + PostgreSQL test. |
| API-02 | Accepted | Empty/whitespace/oversized/invalid Unicode name, unknown fields, invalid UUID, oversized body, and unsupported media/node capability. | Bounded 4xx stable problem response; submitted UI input remains; no partial rows or internal leak. Exact limits are `Decision` until contract slice. | Shared negative fixtures backend/frontend. |
| API-03 | Accepted | List 0, 1, 100, 101, 10k, and 50k owned decks/items; traverse opaque cursor pinned to a snapshot while a concurrent save occurs. | Empty/list/loading/error states work; page bounded; no duplicates/gaps within pinned snapshot; summaries omit document bodies; no per-deck N+1. | SQL/query-count + API + browser tests. |
| API-04 | Accepted | Read item through `(deckId, memberKey,itemRevisionId)` and try bare physical revision ID, stale/deleted member, or revision from another deck/reuse scope. | Only authorized deck-local resolution succeeds; no unscoped latest-by-ID behavior. | Real-DB authorization tests. |

## Revision, storage, draft, and capture invariants

| ID | Oracle | Scenario and fixture | Expected result | Evidence |
|---|---|---|---|---|
| REV-01 | Accepted | Save deck metadata only at 1k/10k/50k membership. | Small deck revision only; no item revisions and no copied membership/exercise pages. | Row deltas, heap/TOAST/index/WAL, transaction trace. |
| REV-02 | Accepted + Decision | Edit one block, delete/reorder one member, bulk publish, and a single enormous paragraph/document. | Unchanged blocks/pages reused; work respects selected synchronous budget or explicit size/job result; no all-content transaction. Exact page/block limits follow R74-S. | R74-S raw benchmark + property/integration tests. |
| REV-03 | Accepted | Perform 1,000 sequential edits and read current plus early/middle/latest historical revisions. | Read work depends on result size/page depth, not history length; no unbounded delta replay. | Query plans, touched rows/pages, p50/p95/p99 under recorded hardware/config. |
| REV-04 | Accepted | Two publishers use same expected head; retry winner; retry loser with same/different command. Kill worker before final commit and during final transaction. | Exactly one head transition; stale publisher gets conflict; exact retry returns result; no partial visible revision/projection. | Deterministic concurrency/crash test and DB invariants. |
| REV-05 | Accepted | Create 100k-member synthetic fork and fork-of-fork; read first page and inherited item; edit same key independently. | Bounded synchronous writes/first page; no lineage-depth read penalty; namespaces and future progress keys remain independent. No fork UI is implied. | R74-S metrics and SQL assertions. |
| REV-06 | Accepted | GC races with new live head, retained history, fork/pull base, draft, or staged publication reference. | No reachable block/page is deleted; orphan staging reclaimed only after policy/grace; crash is retry-safe. | Real-DB concurrency/property test. |
| DRAFT-01 | Accepted | Acknowledged autosave, logout/relogin, hard reload, and multiple drafts across decks/documents. | All acknowledged drafts recover from PostgreSQL with target/base/row version and timestamps; browser memory/cache is not sole copy. | Real API/browser + DB assertions. |
| DRAFT-02 | Accepted | Two tabs update same draft version; separate drafts share one base; publish occurs between autosave and save. | Stale write conflicts without overwriting either input; UI offers explicit recovery; publish checks current base/head. | Multi-context browser + API concurrency test. |
| DRAFT-03 | Accepted + Decision | Draft count/size/expiry boundaries and autosave rate/debounce. | Configured defaults are enforced without deleting other drafts or blocking completion within limits; warning/error is accessible. Defaults in product doc remain configurable. | Boundary/property + fake-clock + browser tests. |
| CAP-01 | Accepted | Create CaptureNote, advance beyond EditingDraft expiry, restart/cache eviction, list by pages. | Note remains until owner action, retains createdAt/source/content, and has no draft idle TTL or Study eligibility. | Fake-clock real-DB test. |
| CAP-02 | Accepted | Convert capture; repeat same command concurrently and after response loss; fail before publication. | Exactly one material/source link and converted state atomically; failure preserves note/draft; retry cannot duplicate or lose source. | Real-DB concurrency/fault test. |

## Native document, renderer, and editor

| ID | Oracle | Scenario and fixture | Expected result | Evidence |
|---|---|---|---|---|
| DOC-01 | Accepted + Decision | Golden corpus: long Russian document, Japanese ruby/IME, mixed RTL/LTR, lists/table/quote, math/code/Mermaid placeholders, media refs, and deep/large boundaries. | Supported version validates and round-trips semantically; exact P0 schemas/limits come from contract gate. | Same serialized fixtures in Java and Angular tests. |
| DOC-02 | Accepted | Load-edit-save around unknown node/version with nested payload and stable IDs. | Unknown JSON meaning and ID survive; visible unsupported placeholder; no silent drop or execution. JSON byte order is not promised. | Golden round-trip + browser screenshot/accessibility tree. |
| DOC-03 | Accepted | Unrelated text edit around node referenced by future exercise projection; delete/change selected node. | Stable reference survives unrelated edit; removed/changed reference becomes explicit invalid configuration, not silent fallback. | Shared fixture test. No #75 exercise persistence required. |
| SEC-01 | Accepted | `<script>`, event attributes, forms/iframe, dangerous SVG, `javascript:`/`data:`/mixed-scheme URLs, CSS escapes, malformed/duplicate-key JSON, extreme numbers/depth/count. | Rejected or rendered inert through registered semantic nodes; no script, navigation, request, style escape, or silent parser ambiguity. | Renderer adversarial corpus, CSP report/console/network capture. |
| SEC-02 | Accepted | Mermaid click/link callback and hostile labels/source; paste malicious HTML; undo/redo after sanitation. | Strict inert rendering; pasted input is sanitized/converted; undo cannot resurrect executable state. | Unit + real-browser adversarial test. |
| SEC-03 | Accepted/Seam #76 | Guess asset hash/object URL, cross-owner asset ID, missing/processing/unsupported asset. | Hash/object key grants nothing; renderer uses authorized logical ref and safe accessible state. No upload/transcode/GC claim. | Stubbed #76 contract fixture + direct-ID negative test. |
| EDIT-01 | Decision | Approved editor candidate maps Mnema AST to/from editor state using stable IDs; long doc paste/undo/redo and reload. | Persisted JSON is Mnema AST, not private library state; semantic round-trip has no supported-node loss. | R74-E prototype then adapter contract tests. |
| EDIT-02 | Accepted + Decision | Desktop split editor/preview; mobile Material/View/Exercises sequence; Russian IME composition, Japanese IME, RTL caret/selection, ruby editing. | Composition is not prematurely saved/transformed; selection/focus/input survive view changes; limitations are recorded before choice. | Real browser/device/manual evidence. |

## Frontend experience, accessibility, and performance

| ID | Oracle | Scenario and fixture | Expected result | Evidence |
|---|---|---|---|---|
| UI-01 | Accepted | Anonymous landing and authenticated own-deck routes on 320/390/768/1440 CSS px, portrait/landscape, 200% zoom/text. | Paper/antiquity/indigo direction; no Liquid Glass; one main/h1, no horizontal clipping, primary action first, decoration ignored by AT. | Candidate screenshots + DOM/visual review. Prototype screenshots are not proof. |
| UI-02 | Accepted | Keyboard-only create/capture/edit/save/conflict/retry/leave flow. | Logical tab order; visible focus; semantic buttons/links; predictable back/close; skip link; focus moved/restored after route/dialog/status changes. | Key log + video/screenshots. |
| UI-03 | Accepted | VoiceOver reads loading, autosaving, saved, retry, validation, conflict, recovered draft, unsupported content, and pending save. | Names/roles/states are meaningful; async status announced without duplicate chatter; error linked to control; color is not sole signal. | Manual VoiceOver transcript. Automated a11y is supplemental. |
| UI-04 | Accepted | `prefers-reduced-motion`, forced colors/high contrast where supported, hover/focus/active/error/disabled states, contrast sampling. | Smooth/decorative motion removed; controls remain perceivable and operable; WCAG 2.2 AA outcomes recorded per state. | CSS/media emulation + manual checks. |
| UI-05 | Accepted | Empty/loading/partial/error/forbidden/offline/retry states for decks, item, draft, capture, and renderer. | Primary saved content is not hidden by optional failure; retry is bounded; user input preserved; permission loss exits safely. | Component plus connected browser tests. |
| PERF-01 | Accepted + Decision | Cold landing, decks, item, and editor route on declared network/device profile; editor/media/study chunks uncached. | First useful shell/text/action do not await editor/media/study; routes are lazy; request fan-out and route bytes measured. Thresholds beyond documented Core Web Vitals references require measured agreement. | Build stats, trace, LCP/INP/CLS and request waterfall. |
| PERF-02 | Accepted + Decision | Long item and 1k/10k/50k member deck navigation/edit on declared hardware. | Bounded page/document work and responsive input; no full deck/document preload or N+1; actual limits derived from evidence. | Browser/API/DB profiles with p50/p95/p99. |

## Adjacent seams, replacement, and final acceptance

| ID | Oracle | Scenario and fixture | Expected result | Evidence |
|---|---|---|---|---|
| SEAM-75 | Seam #75 | Serialize projection capability/node references and query whether saved item is eligible; inspect repo/routes/tables. | Versioned seam is stable and unfinished drafts/captures cannot be eligible. No StudyState, scheduler, session, attempt, replay/practice, mastery, or fake study data added by #74. | Contract fixtures + source/schema inventory. |
| SEAM-76 | Seam #76 | Serialize logical media refs and renderer states. | Bounded capability/authorization contract exists; no inline binary/public URL as ACL. No #76 upload/lifecycle/worker/offline implementation claimed. | Contract fixtures + source/schema inventory. |
| REPL-01 | Accepted | Scan routes, source, schema, frontend imports, generated bundle, and calls after each owning slice. | New canonical path has no `/v2`, legacy alias, dual read/write, compatibility adapter, template/front-back storage, or v1 renderer dependency. | Static guards + route/schema/bundle inventories. |
| REPL-02 | Accepted | Compare removed files to #146/#147 manifests and modules. | Only #74-owned replaced product code is removed. Runtime/build-wide and irreversible data deletion remain untouched. | Diff review and policy scripts. |
| FINAL-01 | Accepted | Run full gate, all P0 matrix cases, and manual evidence on exact integrated candidate and then merged main SHA. | Required checks pass without skips/threshold weakening; merged main reproduces authoring outcome; docs/project reflect facts. Deploy remains separately stated. | Evidence index by SHA. |

## Stop conditions

Stop acceptance and return the candidate to implementation if any P0 row loses
acknowledged data, permits cross-account access or executable content, exposes a
partial revision, duplicates an idempotent command, uses unbounded history/deck work,
skips required PostgreSQL tests, regresses keyboard/screen-reader use, reintroduces
legacy compatibility, or crosses #146/#147. A flaky retry is not a pass; preserve
the failing seed, request IDs, and sanitized artifacts.
