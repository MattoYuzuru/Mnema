# Identity, authorization, and replacement boundaries

Inspected at `33a71f814185a16e922923e034518e25baeadbb8`.

## Current facts

- Identity & Account issues five-minute RS256 `at+jwt` access tokens for audience
  `mnema-api` with canonical UUID `sub`, string `generation`, and
  `account.read/account.write` scopes.
- Its internal `JwtDecoder` validates signature/type/issuer/audience/expiry, requires
  the current active account generation, and looks up an active access-token grant in
  its JDBC authorization store. Existing Identity integration tests cover wrong token
  attributes plus password-change, logout, ban, and restart behavior.
- `/userinfo` is exposed by Spring Authorization Server inside the protected
  authorization-server chain. The exact path through the custom decoder/current
  authorization service must be locked by a cross-service revocation test rather
  than assumed from configuration. `/oauth2/introspect` is also in the endpoint set,
  but production registration currently creates only the public `mnema-web` client;
  Learning has no confidential credentials.
- Learning has web/JDBC/actuator/validation only. It has no Spring Security resource
  server dependency, no Identity/JWKS/introspection/userinfo configuration, no
  authorization filter, and no tests for private routes.
- Learning and Identity use separate schemas but the checked-in local and release
  manifests supply the same PostgreSQL username/password, so the database does not
  currently enforce the logical ownership boundary.
- The checked-in frontend still asks for `user.read user.write`, redirects to `/`,
  and calls legacy auth/service routes. It cannot be treated as evidence for the
  implemented Identity contract or future #74 endpoints.

## Required contract decision

The recommended bounded #74 contract is:

1. Learning validates the JWT locally: exact HTTPS issuer, RS256 signature and key
   rotation, `at+jwt` type, `mnema-api` audience, expiry/not-before, canonical UUID
   subject, and endpoint-specific Learning scope.
2. Learning relays the same bearer token to existing Identity `/userinfo` on every
   private request. A successful response with the same `sub` is the current
   generation/active-grant check. Timeout, transport error, malformed response,
   non-2xx, or subject mismatch fails closed before domain mutation.
3. Successful liveness results are not cached. Connection/read deadlines and bounded
   connection concurrency protect Learning without turning Identity failure into
   authorization success. Telemetry never includes the bearer token.
4. Cross-service tests prove logout, password reset/change, ban, and account deletion
   reject the already-issued token, including the case where local JWT verification
   still succeeds.

This preserves the implemented Identity backend boundary and needs no Learning access
to `app_identity`. It does require a small configured-client integration extension:
add `learning.read` and `learning.write` scopes to the `mnema-web` registration and
request them from the replacement frontend. `account.read/account.write` authorize
account resources only; they must not silently become content permissions. This small
scope addition is in #74's Identity integration seam, not an Identity rewrite. It is
also necessary for future clients to receive least-privilege content access rather
than all account capabilities.

Learning needs Spring Security resource-server support. That is a dependency-file
change and remains behind the explicit dependency approval required by `AGENTS.md`;
include it in the lead's dependency proposal rather than adding it implicitly.
Spring's JWT resource-server guidance confirms that issuer, signature, timestamps,
audience, and scope require explicit resource-server support:
<https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html>.

OAuth token introspection is a viable alternative because Spring Security recommends
it when revocation must be consulted on every request and maps returned scopes to
authorities:
<https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/opaque-token.html>.
It is not the default #74 recommendation because it adds a confidential Learning
client, secret rotation, and environment configuration to Identity/operations.
Moreover, do not assume the standard endpoint invokes Identity's custom `JwtDecoder`;
prove with a real integration test that its authorization lookup enforces the current
generation and active JDBC grant after each revocation event. Spring Authorization
Server documents the endpoint but not Mnema's custom generation invariant:
<https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html#oauth2-token-introspection-endpoint>.

Do not accept any of these shortcuts:

- signature/expiry-only JWT validation for the whole five-minute lifetime;
- trusting `sub`, owner ID, generation, role, or scope from an unverified body/header;
- treating `account.*` as `learning.*`, or calling `/api/accounts/me` as a
  content/write-scope proof (GET requires only account.read);
- querying Identity tables or importing Identity source classes from Learning;
- accepting an ID token as API credentials;
- continuing on Identity timeout/error or logging the bearer token;
- using an unguessable deck/item/revision/asset ID as authorization.

## Request authorization sequence

For every private #74 request:

1. Authenticate token through the chosen contract and derive immutable actor UUID
   plus authorities.
2. Reject missing `learning.read`/`learning.write` before domain work; `account.*`
   alone is insufficient.
3. Resolve the requested deck in the actor's ownership/authorization namespace.
4. Resolve member/revision/draft/capture through that authorized deck, never by an
   unscoped physical ID.
5. For a mutation, bind actor and expected version/head inside the domain transaction;
   body owner fields are rejected or ignored by schema, never trusted.
6. Emit stable problem details and sanitized structured telemetry. Authentication
   failure is 401, known insufficient scope is 403; the contract slice must choose
   a consistent non-enumerating forbidden/not-found policy for чужие resource IDs.

The revocation check occurs at the documented request boundary. Cross-service HTTP
cannot atomically lock Identity state and a Learning transaction; tests must define
whether an already-authorized in-flight Learning mutation may finish, matching the
Identity guide's explicit allowance for already-running reads. This is a contract
clarification, not something verification should invent.

## Database isolation risk and evidence

Static `LearningBoundaryTest` prevents Java imports/project dependencies on legacy
modules, but it does not prevent SQL cross-access. Separate #74 evidence from the
launch-hardening follow-up:

- #74 source/migrations/tests contain no direct `app_identity` query or Identity
  module dependency; the HTTP seam is the only current-account authority.
- An isolated synthetic PostgreSQL test should run Learning with a role denied access
  to `app_identity` and prove ordinary #74 behavior still passes.
- Service-specific runtime/owner roles, opposite-schema denial, and distinct secret
  keys are recommended launch hardening. They need an explicitly owned infrastructure
  follow-up if not included in #74; this plan does not authorize that broad change.

Separate runtime database roles are a meaningful launch-hardening boundary, but are
not automatically an #74 acceptance blocker or authorization for broad infrastructure
changes. #74 must avoid cross-schema SQL and can prove denial with isolated synthetic
roles. Record production role/grant enforcement with an owner and bounded follow-up;
do not defer or silently declare it solved merely because both schemas share a server.

## #146 and #147 deletion checks

For every #74 PR, classify each deletion:

| Class | #74 action |
|---|---|
| Superseded deck/card/template API, Angular route/component/service, or unsafe v1 renderer directly replaced in the slice | Delete in the owning #74 slice and prove no route/import/bundle/schema dependency remains. |
| Whole legacy `core`, `media`, `import`, or `ai` module/build/deployment wiring | Preserve for #146 unless the exact later-epic ownership and replacement gates are satisfied. |
| Legacy/fresh databases, Redis/S3 objects, PVCs, backups/PITR/WAL, production routes/secrets | Never mutate in #74. Disposable purge rehearsal may run only through its test script. Production effect is #147 after separate go/no-go. |

The final source scan must reject `/v2`, legacy aliases, dual reads/writes, compatibility
adapters, old template/front-back persistence, and imports from old product modules.
It must also prove that #74 did not edit purge manifests or claim production deletion.
