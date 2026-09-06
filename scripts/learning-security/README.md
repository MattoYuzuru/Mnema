# Identity → Learning black-box fixture

Runs both real Spring Boot applications against one disposable PostgreSQL 18 instance, with separate database roles/schemas. The only Learning target is the intentionally nonexistent `/api/_blackbox`: 404 with an authorized bearer proves the request passed authentication/authorization; the same request without a bearer returns 401. No production test endpoint is installed.

## Run

Prerequisites: Java 21, Python 3 stdlib, running Docker/Colima, locally cached `postgres:18`, and both boot JARs. The fixture does not install dependencies or pull images. Build the JARs using the ordinary repository gate/build workflow first:

```sh
cd backend
./gradlew :services:identity-account:bootJar :services:learning:bootJar
cd ..
python3 -m unittest discover -s scripts/learning-security/tests -v
python3 scripts/learning-security/run.py
python3 scripts/learning-security/verify_cancellation.py
```

Default envelope: 4 clients, 120 successful bearer requests paced over 30 seconds, plus bounded protocol/lifecycle checks and 16 repeated Learning denials after each revocation. Limits: 1–8 clients, 1–1000 paced requests, 0–120 seconds. Example longer local check:

```sh
python3 scripts/learning-security/run.py --clients 8 --requests 480 --duration-seconds 120
```

This is a bounded correctness/availability sequence, not a throughput benchmark or production soak. The two host Java processes each have a 384 MiB maximum heap. The PostgreSQL container has 2 CPUs and 512 MiB. All listeners bind literal loopback; ports are selected dynamically. Container names start with `mnema-r74-auth-`. A fresh RSA private JWK and synthetic passwords live only in a mode-0700 temporary directory / process environment.

Cancellation uses an Event to interrupt pacing and cancels queued futures without waiting for their original schedules. SIGINT/SIGTERM trigger cleanup; repeated signals cannot abort it. Both owned Java processes receive termination before a shared 2-second grace; remaining processes are killed/reaped within a 3-second process budget. Docker removal has a 2.5-second command timeout, including uncertain creation acknowledgement. Each cleanup step is failure-isolated. Failed cleanup reports `cleanup: incomplete`, failed steps, exact owned resources and whether private files actually remain; it never silently reports success.

The actual cancellation verifier runs two short cases, each with two clients/two requests scheduled 120 seconds apart, and interrupts after pacing begins. It requires exit and verified cleanup before the first 7.5-second GitHub Actions cancellation grace, and sends a repeated opposite signal during cleanup. The verifier itself handles cancellation: it gives its child up to 4 seconds for graceful cleanup before a scoped process-group/container/directory fallback. The private control file records ownership before startup work and updates container-attempt/app-process/pacing phases; it carries no tokens or keys. GitHub's subsequent termination/kill stages are described in its [workflow cancellation reference](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-cancellation).

The developer unit-only unittest command runs 12 isolated stdlib tests and skips two explicitly gated real outer-verifier tests. CI/full-gate invocation must enable the real envelope below: 14 tests, zero skips. The actual outer SIGTERM startup test waits for `app_starting` with an existing container and live JVM; the outer SIGINT case waits for the paced workload:

```sh
MNEMA_RUN_CANCELLATION_INTEGRATION=1 python3 -m unittest discover -s scripts/learning-security/tests -v
```

SIGKILL/host failure cannot execute cleanup; an unavailable Docker daemon or filesystem can defeat resource removal, which is reported as incomplete and requires operator action on the exact listed resources. `--keep-on-failure` retains private diagnostics intentionally; never publish that directory, and remove the exact printed directory after debugging. Standard output contains sanitized evidence, hashes, statuses and timings; failure messages omit response bodies, tokens, SQL and credentials. App ports are now selected after Docker binds and after the previous app starts. A small OS bind-release-bind race remains: an unrelated process can claim a released port before Spring binds; startup then fails visibly and cleanup runs, rather than claiming guaranteed collision-free allocation.

## What is real and what is synthetic

Real HTTP: CSRF, registration, password login, S256 authorization-code exchange, code replay/wrong verifier rejection, scope matrix, Identity `/userinfo`, Learning bearer authentication, logout, password change, reset confirmation/replay, non-admin moderation rejection, admin ban/unban, pending deletion, recovery-only login restrictions, cancellation and fresh token issuance. Actual process SIGSTOP/resume/termination prove live dependency timeout, recovery and fail-closed behavior.

Explicit synthetic setup: initial database roles/schema grants, ephemeral signing key, initial administrator role, verified email plus a hashed one-use reset challenge (no mail delivery), isolated generation bump while retaining its grant, and isolated grant removal while preserving generation. All SQL targets disposable fixture data. Learning's own database role is explicitly denied a read of `app_identity.account`.

The client manually forwards the server's Secure session cookie over explicitly enabled local HTTP. The issuer claim remains HTTPS and Learning's loopback transport exception is explicit. This tests real application HTTP/OAuth composition, **not** browser Secure/SameSite behavior, TLS, reverse-proxy settings, frontend OAuth or external federation. Reset request/mail, deletion purge/fan-out, key rotation and production-scale availability remain outside this fixture. No storage choice or product schema is accepted here.

Definitive revocation must return 401 from both `/userinfo` and Learning. Dependency timeout/connection failure is deliberately different: Learning returns 503 `IDENTITY_UNAVAILABLE`, `Retry-After: 1`, `Cache-Control: no-store`, and never reaches the route. Resuming Identity restores access for a still-valid token; unban/deletion cancellation do not restore old tokens.

The PKCE flow follows [Spring Authorization Server's public-client guide](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html). The local token checks complement [Spring Security JWT resource-server behavior](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html); the fixture uses the repository's existing versions, not additional libraries.
