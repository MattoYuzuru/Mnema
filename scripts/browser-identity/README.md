# Disposable HTTPS browser Identity check

`run.py` composes the **built** Angular frontend, real Identity Account and Learning JARs,
a disposable PostgreSQL18 database, two loopback HTTPS proxies, and an isolated headless
Chrome profile. `browser.mjs` uses Node 22's built-in WebSocket and Chrome DevTools Protocol;
there is no Playwright/npm/pip dependency or package installation.

## Run

Prerequisites: existing Java 21 on `PATH`, Node 22, Chrome, OpenSSL with `req -addext`, Docker
with a locally cached `postgres:18`, built backend `bootJar`s and the built Angular browser
directory. Build/gates belong to the implementation workflow; this harness does not build
or download dependencies/images. On Colima set the existing local `DOCKER_HOST` normally.

```sh
python3 -m unittest discover -s scripts/browser-identity -p 'test_*.py' -v
node --check scripts/browser-identity/browser.mjs
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend --node /absolute/path/to/node22
```

Use `--chrome` for another existing Chrome executable. The page contract defaults to
`[data-testid="identity-profile"]`, `[data-testid="logout"]`, and `[role="alert"]`;
matching CLI selector options are available. Registration uses `#email`, `#username`,
`#password` (also `#login-name` if present); login uses `#login-name`, `#password`.
After the successful callback returns to `/decks`, the harness opens `/login` to inspect
the current profile/logout controls; it does not claim the legacy Deck screen is replaced.

## Assertions and envelope

Two synthetic accounts and two same-profile browser tabs exercise:

- An unsolicited wrong-state callback is rejected without a token exchange.
- Real registration, automatic password login, exact callback, S256 authorization-code
  exchange, real profile, and a Secure/HttpOnly/SameSite=Lax session cookie.
- Logout followed by explicit password login and another PKCE flow.
- Authenticated reload revalidates the sessionStorage access token at Identity `/me`;
  no new authorization or bearer in localStorage. Short-lived sessionStorage access is
  intentional; this is not an assertion that all tokens stay solely in memory.
- Browser logout clears stored access; the prior bearer is rejected by real `/userinfo`.
- Account B logs in through the second tab, changing the shared Identity cookie while
  tab A retains account A's sessionStorage bearer. Tab A's logout must revoke A (401)
  without revoking B (200); its authorization must not follow the ambient shared cookie.
  Preconditions compare real server subjects and the cookie-authenticated session.
  The HTTPS proxy independently records only header-presence booleans and requires
  each logout to carry bearer authorization, without a Cookie or CSRF header.
- A fresh login's real callback is redirected with a deliberately wrong `state`; the
  pending real PKCE transaction is rejected without exchanging its code.
- Replaying a consumed callback is rejected client-side without another token exchange.

The PKCE verifier is checked against the observed S256 challenge. Network interception
blocks page requests outside the two exact origins; at most 300 page requests and
100 Identity requests are allowed (full SPA navigations reload several bundled assets).
Global deadline 180 seconds (CLI 30–300), individual CDP/HTTP/readiness deadlines, 1 MiB proxy
request/response cap, 16 MiB static asset cap. Database has a 512 MiB/two-CPU limit; each JVM
has a 384 MiB heap cap. This is behavioral smoke evidence, not load/soak evidence.

All listeners bind literal `127.0.0.1`, including Docker's published DB port. The backend
leg is explicit loopback HTTP; **the browser leg is real HTTPS**. A fresh self-signed
certificate is trusted only by its exact SHA-256 SPKI in this fixture's private Chrome
profile. There is no global trust-store modification or blanket certificate bypass.
The two HTTPS ports are cross-origin but same-site/same-IP; this checks real browser CORS
and cookie flags, not production DNS/subdomain cookie isolation, public CA/TLS termination,
deployment headers/CSP, third-party cookies, other browsers, assistive technology or mobile UX.

## Cleanup and evidence

The fixture borrows DB/JWK setup and cleanup from `scripts/learning-security/run.py` without
modifying it. SIGINT, SIGTERM, deadline and assertion failures all terminate owned JVMs,
Node and the entire owned Chrome process group, remove the exact disposable container and
private temporary files. A second cancellation signal cannot interrupt cleanup. No broad
process/container deletion is used. An incomplete cleanup fails the run and reports only
owned resource identifiers for manual recovery. Never kill unrelated resources.

Private TLS key, signing JWK, Chrome cookies/profile, token/callback data and child logs are
never exported or retained for debugging. The separate 0700 evidence directory contains
sanitized scenario/count results, artifact SHA-256 fingerprints, and on success only an
empty login-form screenshot. It intentionally remains for reviewer inspection. Failure
evidence contains only controlled failure/scenario labels and counts, not response bodies,
console logs, URLs, credentials or stack traces. `--control-file` is a private optional
cancellation-test synchronization file; do not publish it.

Fixture unit tests validate its safety mechanisms only. They do not substitute for a
passing browser run, the full repository gates, an independent review or production proof.

References: [Chromium certificate SPKI switch](https://chromium.googlesource.com/chromium/src/+/main/services/network/public/cpp/network_switches.cc)
and [Chrome DevTools Network protocol](https://chromedevtools.github.io/devtools-protocol/tot/Network/).
