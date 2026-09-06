# Independent Learning authentication security review

## CodeQL CSRF finding validation

PR #177 comment `3944959238`, alert #13 (`java/spring-disabled-csrf-protection`)
flags the explicit CSRF disable at candidate `1f49cee`. Classification:
intentional bearer-only behavior, not a demonstrated CSRF vulnerability in this
Learning chain. Statelessness alone is not the justification: authentication is
accepted only from an explicit Authorization Bearer header, not browser-ambient
cookies, Basic credentials, form fields or query parameters. Identity's separate
browser/session CSRF boundary is unchanged.

Additional real Learning HTTP regressions send cross-site POST requests with a
valid write token in cookies, query or form, and with Basic authorization. All
return the stable 401, create no session cookie, call no Identity endpoint and
execute no private operation. The existing positive POST with a valid Bearer
header succeeds without a CSRF token. Full Learning tests and coverage pass.
The first local invocation omitted the required Colima test environment and
failed container initialization; rerunning with the documented environment passed.

The exact Spring Security 6.5.11 `OAuth2ResourceServerConfigurer` itself exempts
requests recognized by its Bearer resolver from CSRF checks. Restoring a generic
CSRF filter is not required to secure the present header-only boundary and would
change unauthenticated unsafe-request/session behavior. No query exclusion,
suppression annotation, protection-rule change or cosmetic API rewrite was used.

Residual boundary: adding cookie/session/Basic authentication to this chain, or
introducing state-changing GET handlers, invalidates this conclusion and requires
a new security design/review. These HTTP tests are not browser OAuth/TLS or XSS
verification. See [Spring 6.5 CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html),
[exact resource-server source](https://github.com/spring-projects/spring-security/blob/6.5.11/config/src/main/java/org/springframework/security/config/annotation/web/configurers/oauth2/server/resource/OAuth2ResourceServerConfigurer.java)
and [CodeQL rule](https://codeql.github.com/codeql-query-help/java/java-spring-disabled-csrf-protection/).

## Follow-up correction to the original review

The original no-findings verdict below applies only to its bounded review at
`cf07444ac2be12c93704da39f45a89505dcdcf8a`; it was not a final-candidate verdict.
The lead subsequently reproduced an error-mapping defect with four failure-first
regressions: absent issuer configuration, an oversized token, and cold JWKS failure
threw generic `JwtException`; the real oversized HTTP request returned 500.
Spring Security 6.5.11 converts generic decoder failures to
`AuthenticationServiceException`, which its bearer failure handler rethrows.

The correction uses sanitized `BadJwtException` for local token/key rejection,
preserving the documented 401 path. UserInfo availability failures remain 503.
The four added regressions now pass with the full Learning suite and coverage gate.
The larger connector header limit exists only in the HTTP test to exercise the
independent application token limit; production limits did not change.
Final independent delta review and the full exact-candidate repository gate are
still required before delivery. Cancellation harness findings are tracked separately
in [black-box evidence](learning-auth-blackbox.md).

## Original source review

Reviewed `epic-74/learning-auth` commit
`cf07444ac2be12c93704da39f45a89505dcdcf8a` for #176. The review was read-only for
production code and tests. It covered the new Learning security package,
`ApiSecurityErrors`, security tests, Learning guide/configuration, and the deployment
issuer seam. Native content ACLs are deliberately outside #176 and were not treated
as implemented by this authentication boundary.

## Trust boundary and result

The protected request path is fail closed in the inspected candidate:

1. Spring Resource Server accepts only a bearer token from the request header.
2. Nimbus restricts the JOSE type to `at+jwt` and the algorithm to RS256, then Spring
   validates timestamps and the exact configured issuer.
3. The Nimbus processor requires raw `iat` and `exp` before claim conversion;
   `LearningTokenValidator` then requires the `mnema-api` audience, converted timestamp
   presence, a canonical non-nil RFC UUID subject, and a non-negative string generation.
4. Method authorization requires `learning.read` for GET/HEAD and `learning.write`
   otherwise. Account scopes do not imply Learning access.
5. Only after scope authorization, `CurrentIdentityFilter` relays the same bearer to
   the configured Identity `/userinfo` endpoint for every private request and compares
   the returned `sub` to the locally authenticated subject.

No confirmed or probable security defect was found in this bounded surface.

## Validated controls

- Token validation: `LearningSecurityConfiguration.java:55-76` uses a bounded JWKS
  retriever, exact `at+jwt`, RS256, raw timestamp presence, timestamp skew validation,
  issuer validation, and a 16 KiB token limit. `LearningTokenValidator.java:13-25`
  validates audience, subject, timestamps, and generation shape.
- Scope and request ordering: `LearningSecurityConfiguration.java:92-107` makes the
  API stateless, disables cookie/form login state, applies read/write authorities, and
  places the current-Identity check after `AuthorizationFilter`. Missing scope therefore
  fails before an Identity call or controller operation.
- Current grant/account check: `CurrentIdentityFilter.java:29-49` has no positive-result
  cache, forwards `authentication.getToken().getTokenValue()` unchanged, accepts only
  HTTP 200 with the same subject, maps Identity 401/403 to 401, and maps transport,
  malformed-body, mismatch, and unexpected-status failures to 503 before domain work.
- Transport bounds: `IdentityHttp.java:26-54` rejects unsafe timeout/concurrency
  configuration, does a non-blocking semaphore acquisition, applies connect/request and
  whole-future deadlines, never follows redirects, cancels unfinished requests, and
  releases permits in `finally`. `LimitedBody` cancels above the caller's byte limit.
- Endpoint and secret boundary: `IdentityEndpoints.java:8-29` accepts HTTPS only by
  default, rejects credentials/query/fragment/non-normal paths, and permits HTTP only
  for literal loopback with an explicit test-only switch. Endpoints come from trusted
  deployment configuration, not JWT claims or request data. The inspected security
  code has no token/body logging, redirect following, Identity database access, or
  imported Identity implementation dependency.
- Error contract: `ApiSecurityErrors.java:23-46` emits stable RFC 9457-style bodies,
  `no-store`, a minimal `WWW-Authenticate: Bearer` challenge for 401, and no exception,
  token, or upstream response details. Availability errors carry a bounded retry hint.
- Public and unknown routes: the order-1 chain exposes only GET/HEAD health and info.
  The main chain protects all other paths, so an authenticated unknown route remains a
  normal 404 only after authorization/current-Identity checks rather than becoming a
  legacy or bypass route.

## Regression evidence reviewed

`LearningSecurityHttpIntegrationTest` exercises the real Learning HTTP/filter chain
and PostgreSQL with a controlled Identity HTTP fixture. Its 26 resolved cases cover:
valid read/write requests; per-request UserInfo checks and revocation; distinct scopes;
query/cookie rejection; wrong issuer/audience/signature/type; expired/future/missing
timestamps; invalid subject/generation; malformed bearer; Identity 401/403/302/429/500;
malformed, duplicate, mismatched, non-object, and oversized UserInfo; stalled bodies;
concurrency rejection and recovery; public health/info; and authenticated unknown
`/v2` behavior. The fixture also asserts same-bearer relay, no session cookie, no
redirect following, no controller operation after rejection, `no-store`, and sanitized
problem bodies.

`IdentityConfigurationTest` covers endpoint normalization, unsafe schemes and URI
components, literal-loopback-only HTTP, transport bounds, canonical/non-nil subjects,
and generation parsing. The preliminary full backend quality gate passed at this
commit: all six coverage checks passed, Learning ran 98 tests with zero skips, and
Learning line coverage was 96.85%. This review did not start another Gradle process
because that exact-candidate gate was already running during review.

## Rejected hypotheses and remaining evidence boundaries

- The five-minute JWKS cache is not a revocation cache. Every authorized private
  request still performs UserInfo; only public verification keys are cached.
- A failed or saturated Identity request cannot fall through to the handler: all such
  paths return 503, while Identity's explicit 401/403 returns 401.
- Additional authentication implementations cannot bypass UserInfo in the deployed
  chain: the only configured private authenticator is JWT Resource Server, and scope
  authorization rejects anonymous authentication before the filter.
- This review does not claim real Identity lifecycle composition, production TLS/DNS,
  ingress behavior, or native deck/item ACL verification. The separate black-box
  lifecycle checks and the exact-candidate full backend/repository gates remain the
  required evidence for those boundaries.

## Sources that influenced the review

- Spring Security 6.5 JWT Resource Server documentation informed validation of the
  issuer/signature/timestamp/scope split and bearer error semantics.
- Java 21 `HttpClient`/`HttpRequest` documentation informed the separate assessment of
  connect, request, body-completion, cancellation, redirect, and concurrency bounds.
- `docs/engineering/evidence/epic-74/verification/identity-and-boundaries.md` supplied
  the accepted local-validation plus per-request UserInfo contract and its explicit
  in-flight-operation limitation.
