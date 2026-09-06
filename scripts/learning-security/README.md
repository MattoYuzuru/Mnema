# Identity → Learning black-box fixture

Runs both real Spring Boot applications against one disposable PostgreSQL 18 instance, with separate database roles/schemas. The only Learning target is the intentionally nonexistent `/api/_blackbox`: 404 with an authorized bearer proves the request passed authentication/authorization; the same request without a bearer returns 401. No production test endpoint is installed.

## Run

Prerequisites: Java 21, Python 3 stdlib, running Docker/Colima, locally cached `postgres:18`, and both boot JARs. The fixture does not install dependencies or pull images. Build the JARs using the ordinary repository gate/build workflow first:

```sh
cd backend
./gradlew :services:identity-account:bootJar :services:learning:bootJar
cd ..
python3 scripts/learning-security/run.py
```

Default envelope: 4 clients, 120 successful bearer requests paced over 30 seconds, plus bounded protocol/lifecycle checks and 16 repeated Learning denials after each revocation. Limits: 1–8 clients, 1–1000 paced requests, 0–120 seconds. Example longer local check:

```sh
python3 scripts/learning-security/run.py --clients 8 --requests 480 --duration-seconds 120
```

This is a bounded correctness/availability sequence, not a throughput benchmark or production soak. The two host Java processes each have a 384 MiB maximum heap. The PostgreSQL container has 2 CPUs and 512 MiB. All listeners bind literal loopback; ports are selected dynamically. Container names start with `mnema-r74-auth-`. A fresh RSA private JWK and synthetic passwords live only in a mode-0700 temporary directory / process environment.

On success, failure, Ctrl-C or SIGTERM, the runner terminates its own Java processes, removes only its uniquely named container and attached anonymous volume, and deletes its own temporary files. SIGKILL/host failure cannot run cleanup. `--keep-on-failure` retains private diagnostics intentionally; never publish that directory, and remove the exact printed directory after debugging. Standard output contains only sanitized JSON evidence, hashes, statuses and timings; failure messages omit response bodies, tokens, SQL and credentials.

## What is real and what is synthetic

Real HTTP: CSRF, registration, password login, S256 authorization-code exchange, code replay/wrong verifier rejection, scope matrix, Identity `/userinfo`, Learning bearer authentication, logout, password change, reset confirmation/replay, non-admin moderation rejection, admin ban/unban, pending deletion, recovery-only login restrictions, cancellation and fresh token issuance. Actual process SIGSTOP/resume/termination prove live dependency timeout, recovery and fail-closed behavior.

Explicit synthetic setup: initial database roles/schema grants, ephemeral signing key, initial administrator role, verified email plus a hashed one-use reset challenge (no mail delivery), isolated generation bump while retaining its grant, and isolated grant removal while preserving generation. All SQL targets disposable fixture data. Learning's own database role is explicitly denied a read of `app_identity.account`.

The client manually forwards the server's Secure session cookie over explicitly enabled local HTTP. The issuer claim remains HTTPS and Learning's loopback transport exception is explicit. This tests real application HTTP/OAuth composition, **not** browser Secure/SameSite behavior, TLS, reverse-proxy settings, frontend OAuth or external federation. Reset request/mail, deletion purge/fan-out, key rotation and production-scale availability remain outside this fixture. No storage choice or product schema is accepted here.

Definitive revocation must return 401 from both `/userinfo` and Learning. Dependency timeout/connection failure is deliberately different: Learning returns 503 `IDENTITY_UNAVAILABLE`, `Retry-After: 1`, `Cache-Control: no-store`, and never reaches the route. Resuming Identity restores access for a still-valid token; unban/deletion cancellation do not restore old tokens.

The PKCE flow follows [Spring Authorization Server's public-client guide](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html). The local token checks complement [Spring Security JWT resource-server behavior](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html); the fixture uses the repository's existing versions, not additional libraries.
