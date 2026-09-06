# #176 — real Identity → Learning black-box evidence

Status: **passed in the bounded local envelope** on 2026-09-06. Both real boot JARs composed successfully over HTTP with PostgreSQL, actual browser-session/CSRF handling, S256 PKCE and real Identity-issued bearer tokens. This closes the missing real-service composition evidence; it does not certify browser TLS, external providers, production capacity or the rest of Epic #74.

Snapshot boundary: these are measured JAR snapshots, not a claim about the final release candidate. After the executable harness freeze, the lead identified a separate production decoder error-mapping edge (`JwtException` potentially surfacing as 500 instead of 401), owns that fix/JAR rebuild, and will run the fresh exact-candidate full gate. The recorded hashes and successful assertions below remain valid only for their executed inputs.

## Cancellation correction — current executable evidence

Independent review found a real harness defect after the original green runs: its executor context manager waited for sleeping paced workers, potentially delaying signal cleanup for 120 seconds; a repeated signal could then interrupt sequential resource cleanup. The old normal-completion runs below remain valid historical authentication evidence but are **not cancellation evidence**. The cancellation correction began only after the lead confirmed the frozen `4ffaefbb27b00702f75691004a7fc56e2372cceb` full gate had finished; that prior green gate did not waive the finding. No production or dependency files changed in this correction.

The current runner interrupts pacing through an Event, cancels queued futures, and never waits for their scheduled delays during executor shutdown. It terminates all owned app processes before a shared grace/kill budget, shields cleanup against repeated SIGINT/SIGTERM, isolates failures between cleanup operations, and reports incomplete cleanup plus actual retained-file state instead of false success. `pg_isready` has a two-second command timeout. Docker creation attempts are recorded before launch, allowing best-effort exact-name cleanup after uncertain acknowledgement.

The verifier also handles SIGINT/SIGTERM: it allows bounded graceful child cleanup before exact process-group/container/private-directory fallback. Private ownership metadata exists before startup work and is updated for container attempts and each app process. The CI-enabled outer test fixture likewise routes cancellation through bounded, shielded cleanup. Application ports are selected only after Docker binds and after the preceding app starts. The remaining bind-release-bind OS race is explicitly retained as a limitation: an unrelated process can still acquire a released app port before Spring binds, producing visible startup failure and cleanup rather than an allocation guarantee.

Current executable source SHA-256 values:

| Source | SHA-256 |
| --- | --- |
| `run.py` | `295904c983d5ac498dd1bc8c5d79d77b15846b19efa2a5900f196f4482fee862` |
| `verify_cancellation.py` | `92a2a952148516e8073a26f6efdf40a1142c1f71279da19ccce95ca46dbd7ae3` |
| `tests/test_cancellation.py` | `99758f7c15e92c0d98c2af7208e489230906468227805b5ce3ef99ae74b00e19` |
| `tests/test_verifier_cancellation.py` | `3f4803641fd0076e08d51e606491605645d09a020ff1d7c0d9af2d80e5dd1591` |

Reproduction uses the existing boot JARs. All commands are bounded local executions; the environment-enabled unittest command is the CI/full-gate version and does not skip the two real outer tests:

```sh
MNEMA_RUN_CANCELLATION_INTEGRATION=1 python3 -m unittest discover -s scripts/learning-security/tests -v
python3 scripts/learning-security/verify_cancellation.py
python3 scripts/learning-security/run.py
```

The ordinary developer unittest command without the environment flag intentionally runs only 12 stdlib cases and skips the two real-service cases; it is not equivalent to the full invocation. No dependency installation or external service is needed beyond the already-authorized local Docker/PostgreSQL and boot JARs.

On the current runner/verifier hashes, direct cancellation tests used two clients/two requests scheduled across 120 seconds and both real apps. SIGINT plus a repeated SIGTERM completed process/container/private-file cleanup and exited nonzero in **653.38 ms**; SIGTERM plus a repeated SIGINT did so in **670.51 ms**. Both are below GitHub's first 7.5-second cancellation grace. Raw proof: [cancellation-real-03.jsonl](../../../../scripts/learning-security/results/cancellation-real-03.jsonl). The threshold comes from GitHub's documented [workflow cancellation sequence](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-cancellation), not an assumed multi-minute CI cleanup window.

The corrected default run preserved all **23/23** application/protocol assertions, exit 0, in **49.10 seconds**. Its 120 successful requests spanned 30.044 seconds, with local p50/p95 20.68/34.84 ms; the real paused Identity still produced 503 after 2005.69 ms. Raw evidence: [default-fixed-02.jsonl](../../../../scripts/learning-security/results/default-fixed-02.jsonl). Its Identity and Learning JAR hashes match the historical table below exactly. These local timings were collected while other bounded cancellation probes were running, at a combined configured client cap of eight, and are not a production latency benchmark.

The final source-frozen unittest invocation passed **14/14 tests, zero skips**, exit 0, in **11.870 seconds**: 12 stdlib cases plus the two real outer-verifier cases. Outer SIGINT during `paced_wait` completed in **801.66 ms**. Outer SIGTERM during `app_starting` completed in **214.51 ms**; this check explicitly required an already-live app JVM and existing startup ownership, not merely the initial `allocated` marker. Both cases verified removal of the runner process group, recorded app PIDs, exact container and private directory. The final integration log is [cancellation-all-final.log](../../../../scripts/learning-security/results/cancellation-all-final.log).

| Final artifact | SHA-256 |
| --- | --- |
| `cancellation-real-03.jsonl` | `f88759168a33c5927163c0ae05013f4fcb940884340914f4d4b437e3dd534e59` |
| `default-fixed-02.jsonl` | `44c9def8479491f607f27e74a7d7e9ee1a9d9a8272e29f4b69840757167d1eca` |
| `cancellation-all-final.log` | `2fc13f214460d250fca2bf5183ed6d343fbc063a1770882f2b18d16877960868` |

Each command was captured with `set -o pipefail` and `tee`, preserving its nonzero failure behavior. Post-run exact-prefix container inspection returned no containers and boot-JAR process inspection returned no matches. Intermediate `cancellation-*-01/02`, `cancellation-all-03/04` and `default-fixed-01` artifacts are retained locally only; their older source hashes are not substituted for current executable proof. The original `default-01` / `sustained-01` files and hashes remain untouched below as historical evidence.

The measured healthy local environment satisfies the cancellation grace, but Docker-daemon/host/filesystem failure is not promised recoverable: cleanup commands have deadlines, remaining resources are reported explicitly, and SIGKILL cannot run finally blocks. No production auth defect was found by this correction; the confirmed cancellation defect was in the test harness and was not dismissed as a framework issue.

## Historical normal-completion reproduction and inputs

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

## Historical observed behavior

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
