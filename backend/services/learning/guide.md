# Learning API runtime

`services:learning` is the standalone greenfield Learning API runtime. It has no
Gradle project dependency on legacy `core`, `media`, `import` or `ai`. Epic #74
added the canonical private Deck, deck-local LearningItem, native content,
EditingDraft and CaptureNote domains. Study/exercises/scheduler remain Epic #75;
greenfield media lifecycle remains #76.

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
  `RESOURCE_NOT_FOUND`, `METHOD_NOT_ALLOWED` and `INTERNAL_ERROR`. Public details
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
- `/api/editing-drafts` owns bounded acknowledged server drafts; autosave never
  publishes.
- `/api/capture-notes` owns durable quick notes and idempotent conversion while
  retaining source/provenance.
- Native document v1, immutable block/page storage and counted structural edits are
  shared #75 inputs. They do not yet imply exercises, attempts or StudyState.

Fresh Learning migrations V1–V5 are the database source of truth. Do not append
Study tables to legacy `core` migrations or port old review algorithms.

Sources: [Spring Security 6.5 JWT](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)
for signature/claims/scope boundaries; the exact 6.5.11 source establishes claim
conversion behavior; [Java 21 HTTP](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html)
for request deadlines, supplemented by explicit bounded body completion/cancellation.
