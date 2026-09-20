# Learning API runtime

`services:learning` is the standalone greenfield Learning API runtime. It has no
Gradle project dependency on legacy `core`, `media`, `import` or `ai`. Epic #74
added the canonical private Deck, deck-local LearningItem, native content,
EditingDraft and CaptureNote domains. Epic #75 now also owns immutable objectives,
P0 exercise revisions, explicit content bindings, bounded Study session snapshots,
deterministic attempts and the baseline Study state reducer. Progress, full mode
selection and retention cleanup are delivered by subsequent Study slices.
Greenfield media lifecycle remains #76.

## Runtime contract

- Canonical application context: `/api`; there is no `/v2` or legacy service alias.
- Health: `/api/actuator/health/liveness` and
  `/api/actuator/health/readiness`. Readiness includes PostgreSQL; liveness does
  not depend on external systems.
- Build identity: `/api/actuator/info` exposes reproducible Gradle build metadata
  plus `release.id` from `MNEMA_BUILD_ID` (`dev` only as a local default).
- Private routes authenticate only an `Authorization: Bearer` access token.
  GET/HEAD require `learning.read`; other methods require `learning.write`.
  Health/info remain public, including when Identity is unavailable.
- Database: fresh Flyway history at `classpath:db/learning/migration`, owned schema
  `app_learning`, `baseline-on-migrate=false`. It never scans a legacy migration
  directory.

## Shared platform contracts

- Entity identifiers are non-nil RFC 9562/IETF UUIDs stored as PostgreSQL `uuid`.
  New command identifiers are UUIDv4 or UUIDv7; the portable Java generator emits
  UUIDv4 without an additional dependency.
- Command identity is global by `command_id`. A retry must have the same actor,
  scope, type and canonical payload. An exact retry receives the stored JSON
  result; any mismatch returns `IDEMPOTENCY_CONFLICT` when exposed over HTTP.
  The action and receipt share one JDBC transaction, so failure leaves neither
  side effects nor an in-progress receipt.
- Payload canonicalization is a durable protocol: UTF-8 JSON with lexicographically
  sorted object fields, preserved array order, normalized finite numbers and a
  fixed escaping policy independent of application-wide Jackson configuration.
- Mutable rows use a non-negative `row_version`. Repository SQL performs an update
  guarded by the expected version, and `CompareAndSetExecutor` accepts exactly one
  changed row or raises `VERSION_CONFLICT`.
- API failures use `application/problem+json` (RFC 9457). Stable machine codes are
  `IDEMPOTENCY_CONFLICT`, `VERSION_CONFLICT`, `PRECONDITION_REQUIRED`, `INVALID_REQUEST`,
  `RESOURCE_NOT_FOUND`, `SESSION_EXPIRED`, `PRESENTATION_EXPIRED`, `METHOD_NOT_ALLOWED`
  and `INTERNAL_ERROR`. Public details
  never contain exception messages, SQL or stored command data.

PostgreSQL integration tests are fail-closed: Docker absence or container startup
  failure fails the build rather than skipping the suite.

## Identity boundary

Set `MNEMA_IDENTITY_ISSUER` to the exact Identity HTTPS issuer. Release templates
give both services the same environment-specific issuer. Learning fetches public
keys from `/oauth2/jwks` and validates RS256, `at+jwt`, issuer, `mnema-api` audience,
required timestamps, canonical non-nil UUID subject and generation shape.
Key caching/rotation never replaces the active-account check: after scope
authorization, every private request relays the same token to `/userinfo` and
requires a successful response with the same subject. No successful UserInfo
result is cached; Learning neither queries Identity tables nor imports its code.

The check is a request boundary, not a distributed transaction: already-authorized
in-flight work may finish while a concurrent logout/revocation commits. New requests
must consult Identity again. Domain ACL and actor-bound transactions remain the
owning content slice's responsibility; authentication alone does not authorize an ID.

Transport never follows redirects, shares no browser cookies, has a two-second
whole-response deadline, at most 32 simultaneous calls and bounded bodies (64 KiB
JWKS, 16 KiB UserInfo). Capacity exhaustion, timeout, malformed/mismatched UserInfo
or unexpected status fail closed with `IDENTITY_UNAVAILABLE` / 503. Identity 401/403
becomes `AUTHENTICATION_REQUIRED` / 401; insufficient Learning scope is
`ACCESS_DENIED` / 403. All use the same RFC 9457 vocabulary as MVC and `no-store`;
no token, response body or exception details are exposed. Local token/key validation
failure is 401. Missing issuer permits maintenance startup but never authentication.

`learning.identity.transport-base` can specify a trusted alternate HTTPS transport
endpoint without changing the issuer claim. The explicit test-only
`learning.identity.allow-loopback-http=true` accepts plaintext solely at literal
127.0.0.1 or [::1]; it is not enabled in release templates. The Compose issuer still
needs a trusted local HTTPS endpoint for authenticated flows, as Identity does.

Security behavior is tested through actual Learning HTTP with real PostgreSQL and
a controlled Identity protocol fixture, including a stalled body after headers,
concurrency rejection/recovery, duplicate JSON fields and per-request revocation.
This fixture is complemented by the real packaged Identity/Learning black-box
harness in `scripts/learning-security` and the HTTPS browser authoring harness in
`scripts/browser-identity`. Those local checks are not deployment or production
capacity evidence.

## Content and authoring contract

- `/api/decks` owns private Deck creation, bounded listing/detail and CAS metadata
  updates with global command receipts.
- `/api/decks/{deckId}/items` owns deck-local logical identity, immutable revisions,
  current/historical reads and atomic publication.
- `/api/decks/{deckId}/exercises` owns owner-only bounded reads and atomic
  publication of stable objectives, immutable answer/exercise revisions, exact
  current item/node pins and one assessed binding. Supported P0 types are
  `SELF_CHECK`, `TYPED`, `CLOZE_SINGLE` and `SINGLE_CHOICE`. The optional
  `memberKey` list filter remains cursor-bounded and returns each current
  exercise with its current objective summary so authoring clients can reuse a
  direction without scanning every exercise or exposing identifiers for input.
- `/api/decks/{deckId}/study-sessions` starts and resumes owner-only
  `SCHEDULED`, `REPLAY` and `PRACTICE` snapshots. Candidate preparation reads at
  most 500 exercise rows per poll, selection scans at most 80 candidates and a
  response contains at most 20 immutable presentations. The authenticated
  `zoneinfo` claim determines the local study date; invalid or absent values fall
  back to UTC, and clients cannot submit a timezone. Resume returns only
  presentations without a terminal attempt and each presentation carries the
  answer-contract reference needed by the accessible Study feedback flow.
- `/api/decks/{deckId}/study-sessions/{sessionId}/attempts` terminalizes one
  server-issued presentation. All four P0 evaluators are deterministic;
  only `SCHEDULED` writes evidence and one versioned `mnema-baseline-v1`
  transition. `TYPED` and single-blank `CLOZE_SINGLE` normalize the pinned answer
  contract; a first-grapheme hint caps only a correct result at `MEDIUM`.
  `SINGLE_CHOICE` accepts only a server-issued `OPTION` binding matching the pinned
  focal target and always produces `LOW` recognition evidence. Exact retries
  return the durable outcome, conflicting attempt IDs
  never add transitions, and raw scheduled response JSON expires separately after
  30 days. The first terminal receipt atomically removes that presentation from
  the resumable batch; after its last presentation, the bounded session becomes
  `COMPLETE` in the same transaction.
- `/api/decks/{deckId}/study-restarts` starts a new learning epoch for objectives
  under explicitly selected current materials. It locks objectives in UUID order,
  keeps prior evidence/transitions and makes old presentations non-assessing.
- `/api/editing-drafts` owns bounded acknowledged server drafts; autosave never
  publishes.
- `/api/capture-notes` owns durable quick notes and idempotent conversion while
  retaining source/provenance.
- Native document v1, immutable block/page storage and counted structural edits
  back both material and exercise membership roots. Exercise writes advance the
  Deck CAS and receipt in the same transaction.

Fresh Learning migrations V1–V8 are the database source of truth. Do not append
Study tables to legacy `core` migrations or port old review algorithms.

Sources: [Spring Security 6.5 JWT](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)
for signature/claims/scope boundaries; the exact 6.5.11 source establishes claim
conversion behavior; [Java 21 HTTP](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html)
for request deadlines, supplemented by explicit bounded body completion/cancellation.
