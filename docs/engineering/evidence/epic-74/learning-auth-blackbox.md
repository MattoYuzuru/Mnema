# #176 — real Identity → Learning black-box evidence

Status: **passed in the bounded local envelope** on 2026-09-06. Both real boot JARs composed successfully over HTTP with PostgreSQL, actual browser-session/CSRF handling, S256 PKCE and real Identity-issued bearer tokens. This closes the missing real-service composition evidence; it does not certify browser TLS, external providers, production capacity or the rest of Epic #74.

## Reproduction and exact inputs

From the repository root, with the existing Java 21 boot JARs and cached `postgres:18`:

```sh
python3 scripts/learning-security/run.py
python3 scripts/learning-security/run.py --clients 8 --requests 480 --duration-seconds 120
```

Implementation and envelope: [fixture README](../../../../scripts/learning-security/README.md), [runner](../../../../scripts/learning-security/run.py), [disposable key generator](../../../../scripts/learning-security/FixtureKey.java). No new dependencies, production endpoints, application code, schema migrations or build changes were made by this harness task. Full quality gates and CI integration are owned by the implementation lead.

The application source checkpoint was reported by the lead as `cf07444ac2be12c93704da39f45a89505dcdcf8a`; the executed artifacts are pinned by their measured SHA-256 values below, rather than inferred from that checkpoint:

| Input | SHA-256 |
| --- | --- |
| Identity boot JAR | `e8c36943da1ccaab5a12bb059e229e645adcded5f8b2edec47b335bffa7bdc87` |
| Learning boot JAR | `659e2b13308be7d660e61090e86ce48438672f2ca2239b5a0f6c2713c9d8f641` |
| `run.py` | `ae3792d2c89c007eebe4f2f7c7cb721161283280ee5baf670144b8e6f2573262` |
| `FixtureKey.java` | `799b1a83d580238d54ecf17325f1f65ffb3d80a21a7287481fa2c69c72e91257` |

Environment: macOS/Colima ARM64, Temurin Java 21.0.11, existing PostgreSQL image reporting 18.4/aarch64/alpine. Both apps use host loopback ports, distinct schema-owning database roles and one uniquely named disposable PostgreSQL container (2 CPUs/512 MiB); each app has a 384 MiB maximum heap. Learning's actual database role was denied `SELECT` on `app_identity.account`. Issuer claim is `https://identity.mnema.test`; Learning's explicit loopback transport exception connects to the real local Identity server.

## Observed behavior

The runner emits 23 passing scenario records, followed by cleanup. `/api/_blackbox` is deliberately absent from production: authenticated/scoped requests reach its normal 404; anonymous or rejected tokens never reach it.

| Boundary | Observed evidence |
| --- | --- |
| Real S256 PKCE | Registration/login/CSRF → authorization code → real access and ID tokens; no refresh token. Code replay and wrong verifier reject with 400. |
| Learning access | Anonymous 401; genuine learning.read GET / learning.write POST 404; ID token and altered signature 401. |
| Least privilege | account.read-only token cannot access Learning (403); learning.read cannot POST (403); learning.write cannot GET (403); learning-only token cannot read Identity `/me` (403). |
| Account revocation | Real HTTP logout, password change, reset confirmation, admin ban and pending deletion each change previously working Identity `/userinfo` and Learning requests to 401. Each check includes 16 additional Learning denials at up to the configured client count. |
| Independent freshness checks | Synthetic generation bump leaves the grant present yet rejects the token; synthetic grant removal preserves generation yet rejects the token. |
| Lifecycle recovery | Real unban does not restore the old token. Recovery-only login cannot use ordinary account/OAuth routes. Real deletion cancellation leaves old token revoked and permits fresh PKCE issuance. |
| Dependency availability | SIGSTOP of the real Identity process gives Learning 503 `IDENTITY_UNAVAILABLE` after its 2-second timeout, with `Retry-After: 1` and `Cache-Control: no-store`. Resume restores the still-valid token. Process termination again gives 503 while Learning liveness stays 200. |

The default run passed all 23 scenarios, exit 0, in 47.60 seconds. Its 120 paced requests all returned 404 over 30.028 seconds; observed request p50/p95 were 23.73/38.10 ms. The nine revocation/recovery checks each produced the initial 401 plus 16 repeat denials; combined `/userinfo` + Learning + repeat-check durations were 31.12–44.31 ms. These are local observation durations, not a production revocation SLA or single-request latency. Paused Identity returned 503 after 2006.61 ms.

Raw sanitized default evidence: [default-01.jsonl](../../../../scripts/learning-security/results/default-01.jsonl), SHA-256 `06b63d818789d598554c06088dca1e6dff4a13095d6afaa07319f8736e0f79ea`. It was produced with `set -o pipefail` and the default command piped through `tee`; the shell exit status was 0. Python AST parsing and `--help` also passed.

The separately executed 120-second, 8-client envelope also passed all 23 scenarios, exit 0, in 137.29 seconds. All 480 paced requests returned 404 over 120.038 seconds; p50/p95 were 23.12/34.52 ms. The worker count is a concurrency cap, not a claim of eight continuously outstanding requests: the valid sequence is paced at approximately four requests/second, followed by the bounded concurrent revocation checks. Those nine checks completed in 20.34–39.30 ms each; Identity pause produced the expected 503 after 2005.10 ms. Raw evidence: [sustained-01.jsonl](../../../../scripts/learning-security/results/sustained-01.jsonl), SHA-256 `a6f71cb83272bdc854fea4996cd79729bcd6e37a84935e5c61981f70b21ba1b9`.

Both JSONL files were parsed and checked for exactly 23 passing scenario records plus successful cleanup, and screened for compact JWT artifacts. Post-run `docker ps -a --filter name=mnema-r74-auth-` returned no containers; matching boot-JAR process inspection returned no processes. The default and sustained runs executed identical harness/key-source and application-JAR hashes.

## Synthetic seams and unverified paths

Administrator bootstrap is an explicit local SQL role change; ban/unban themselves use real HTTP. Reset confirmation uses a locally inserted SHA-256 challenge plus verified-email fixture state; no email delivery/request flow is claimed. Generation-only and grant-only mutations are explicitly synthetic, scoped to the disposable account, and assert the other factor remains unchanged. Other accounts, login, authorization, revocation and deletion/cancellation are genuine HTTP operations.

The HTTP client deliberately forwards the server's Secure cookie over local HTTP; browser Secure/SameSite/TLS behavior and reverse-proxy deployment are not tested. Neither external federation, mail/S3, OIDC front-channel logout, final deletion purge/fan-out, key rotation, extended outage saturation, real frontend OAuth nor production load is covered. The 30/120-second sequences remain bounded correctness exercises, not multi-hour soaks. No conclusion here selects a storage scheme.

The existing Flyway runtime logs that PostgreSQL 18.4 is newer than its tested maximum 17; migrations completed successfully. This is a disclosed existing compatibility warning, not an introduced dependency upgrade or evidence of full PostgreSQL 18 certification.

Fixture development exposed and corrected harness assumptions, not production defects: PostgreSQL readiness must wait for its final TCP listener, a generation-only change leaves an invalid browser session that must be discarded before fresh login, forbidden recovery-session use intentionally invalidates that session, and transport failure is 503 rather than definitive-token-revocation 401. The final run uses these explicit semantics without weakening its expected outcomes.

All standard-run processes, uniquely named containers/volumes and temporary secrets are cleaned on exit. Temporary diagnostic directories retained during earlier fixture debugging were explicitly removed; final evidence contains no bearer tokens, cookies, private JWKs or passwords.

Protocol references: [Spring Authorization Server public-client PKCE](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html) informed the real public-client flow; [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html) informed the JWT/scope boundary checks. The fixture uses only repository-existing application dependencies.
