# #173 content boundary prerequisite review

Reviewed on 2026-09-06 against lead-supplied candidate `2afcb1e`, branch
`epic-74/content-boundary` (identical source supplied in the shared integration
checkout). **No confirmed implementation/security defect found in the scoped
changes; CB-1 is closed by the independently checked followup. No open findings
block this prerequisite slice.** The original review was against `d588e73`;
`2afcb1e` adds the isolated UserInfo regression cases. This is not a
review or acceptance of a private Learning endpoint or the storage schema.

The complete `ContentJsonReader`, its test, Identity `AuthorizationConfiguration`,
`IdentitySecurityIntegrationTest` and Identity `guide.md` were read. Supporting
inspection covered `CanonicalJsonHasher`, security filter configuration,
`GenerationAuthorizations` and account revocation. SRE/finding-validation and
defensive-security-review guidance was used to distinguish actual defects from
coverage limitations. No source, dependency, Git/GitHub or runtime state changes
were made; only this report was written. No tests or Gradle gates were rerun.

## What the implementation supports

`ContentJsonReader.java:38` constructs a private mapper with strict duplicate
detection, bounded depth/token/string/number parsing, exact integer/decimal tree
values and rejection of trailing JSON. `read` checks UTF-8 byte length before
decoding; malformed byte sequences fail instead of being replaced. A non-object
top level fails, as required for the document ingress contract.

Scalar validation visits object keys as well as values. It rejects NUL, lone low
surrogates and unmatched high surrogates, while accepting valid pairs. Duplicate
escaped-equivalent field names are tested. Unicode normalization is intentionally
not imposed; preserving code points is appropriate at this semantic boundary.
Unknown keys/types are retained rather than routed through a closed DTO schema.
This does not replace native-node capability validation or safe rendering.

Numeric validation (`ContentJsonReader.java:101`) bounds precision/scale before
plain-decimal hashing, rejects unsafe integral values and compares the exact
decimal with the decimal representation obtained through binary64. Large positive
exponents, tiny exponents, excessively long numbers and rounded fractional values
have negative tests. Ordinary decimal values such as `0.1` remain valid; this is
a decimal round-trip policy, not a claim that every decimal is exactly represented
as a binary fraction. Signed zero and equivalent lexical representations may
normalize, consistently with the existing semantic command hash.

Jackson/decoder failures are converted to a fixed exception without an input-bearing
cause (`ContentJsonReader.java:73`); explicit scalar failures have the same message.
Null Java input is deliberately a programmer-error NPE, covered by a separate test.
The reader is currently a reusable prerequisite with no production call site;
there is no evidence that untrusted HTTP bodies already pass through this boundary.

`AuthorizationConfiguration.java:73` adds only `learning.read` and `learning.write`
to the existing registered public client. Code flow, required PKCE, exact redirect,
no public refresh grant and existing account scopes remain explicit. The new test
performs actual login, S256 authorization/code exchange, verifies the exact issued
scope set, obtains `/userinfo` with the access token, verifies canonical `sub`,
rejects that token at the account-read endpoint and rejects the ID token at
UserInfo. It does not use a mocked authenticated principal as its OAuth proof.

UserInfo goes through the authorization-server resource-server decoder. The
decoder checks signature/type/issuer/audience/subject, current account generation
and exact active stored access-token grant. `GenerationAuthorizations` additionally
filters stale grants. The inspected account scope gate requires `account.read`
or `account.write`, rather than treating learning scopes as aliases. The guide
correctly describes UserInfo as one future liveness dependency after local
Learning token/scope checks and explicitly leaves the receiving filter and real
cross-service tests to the owning slice.

## CB-1 — Independent UserInfo checks: closed in `2afcb1e`

Original classification: missing regression evidence, medium priority, high
confidence; not a confirmed authorization bypass. Status: **closed**.

At `IdentitySecurityIntegrationTest.java:312`, the new test calls
`accounts.revoke`. `AccountStore.java:65` both increments `security_generation`
and deletes OAuth grants. Its subsequent 401 would still pass if either the
generation check or the grant-existence check stopped working independently.
The existing wrong-generation JWT helper also avoids saving a grant for a stale
generation (`IdentitySecurityIntegrationTest.java:533` in `d588e73`), so it did not isolate
that condition for UserInfo. The implementation contains both checks; the gap is
in the strength of the claimed regression proof.

The new parameterized `userInfoRequiresGrantAndGenerationIndependently` at
`IdentitySecurityIntegrationTest.java:320` closes that gap. Each case creates a
fresh account and real PKCE access token and first verifies UserInfo returns 200.
The grant case removes the exact authorization, then `accounts.require(account,
false)` proves the account is still active with the token's original generation.
The generation case updates only the account generation and explicitly checks that
the same authorization ID remains in the JDBC table. Both require UserInfo 401.
The full-revocation test remains. These tests prove grant existence and current
generation independently; no separate inactive-token-metadata execution is claimed.

The shared and isolated-worktree test files compare byte-for-byte equal. The
targeted XML contains both parameterized cases, two tests, and zero
failures/errors/skips. This correction changes only regression evidence and does
not alter the inspected authorization implementation.

## Integration budgets and limits, not additional blockers

The numeric limits prevent unbounded exponent expansion but do not make canonical
bytes equal to ingress bytes. For example, a scalar `1e-128` expands from six ASCII
characters to 130 in `toPlainString`. Nearly 100,000 such array elements fit the
test reader's 1 MiB/100,000-token envelope but produce roughly 13 MB of canonical
output, before byte-buffer copies/tree memory. This is a source-derived finite
bound, not a measured allocation or demonstrated denial of service. The endpoint
owner should budget canonical output/concurrency separately or enforce a canonical
byte ceiling if the synchronous publication limit applies to that representation.

Similarly, accepting `byte[]` checks length after the caller has materialized the
request body. The future HTTP boundary must bound body reading itself; the absence
of that caller in this prerequisite is intentional, not a finding against this
change. Shared browser round-trip golden fixtures are appropriate in the subsequent
native contract/editor work. Java-side reparse tests alone should not be described
as an executed cross-browser interoperability corpus.

## Evidence and handoff

Read the existing test-result headers without rerunning or exposing test logs:

- `ContentJsonReaderTest`: 31 tests, zero failures/errors/skips,
  timestamp `2026-09-06T13:19:42.318Z`.
- Targeted `IdentitySecurityIntegrationTest`: one test, zero
  failures/errors/skips, timestamp `2026-09-06T13:23:07.382Z` (original review).
- CB-1 targeted followup: both `grant` and `generation` parameterized cases,
  two tests, zero failures/errors/skips, timestamp `2026-09-06T13:27:57.060Z`.

These are the lead's targeted run artifacts, not proof of a full class or full
repository gate at candidate `2afcb1e`. The lead owns the isolated exact-candidate
quality gate. No private Learning route, cross-service
revocation race, full browser corpus or production integration was executed here.

Reviewed source fingerprints:

| File | SHA-256 |
| --- | --- |
| ContentJsonReader.java | `5ef55e9e30170e7ab264b73c9878cad4fcf5f52f82de258f0b165ad60133f6f5` |
| ContentJsonReaderTest.java | `96b534060db04d55515716858a30f3dcd0bc0886651f20c33aeb61fa30a078d6` |
| AuthorizationConfiguration.java | `78c520393cb25557caac62b4824aa819636c4d80256286a66679839c119e5783` |
| IdentitySecurityIntegrationTest.java | `0500cff1aac33215266ec61f39b1d1d738c64526ed4bcde638af2597eaba9c84` |
| Identity guide.md | `d648baa3a17eed5b03a5dc33c6d2d0f599861346134a0150cf454d9f2461a614` |

Official sources checked:
[RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) for duplicate-name and numeric
interoperability boundaries; [Java 21 BigDecimal](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html)
for decimal round-trip and plain output; [Spring Authorization Server UserInfo](https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html#oidc-user-info-endpoint)
for the access-token/decoder boundary. No new framework feature or dependency was
introduced by this review.
