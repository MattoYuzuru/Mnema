# Persistent local replacement runtime

Current revisions permit private personal use by one natural person on owned or
controlled devices. Shared or organizational use needs a
[separate written license](../../COMMERCIAL-LICENSING.md).

## Supported full-stack launcher — #220

The supported personal-development path is `scripts/mnema-local-full-stack.sh` plus
`compose.local-full-stack.yml`. It builds the production Angular image and the real
Identity and Learning images, starts PostgreSQL 18 with a retained named volume, and
publishes only two loopback TLS listeners:

- `https://localhost:3443` — the URL to open; static production frontend and
  same-origin `/api` Learning traffic;
- `https://localhost:3444` — the separate OAuth/OIDC issuer required by the browser
  PKCE boundary. Identity and Learning themselves have no host HTTP ports.

The Learning-to-Identity call also uses TLS. Its private truststore contains only the
generated local CA; the launcher never enables the test-only plaintext transport.
The disposable `scripts/browser-identity` and `scripts/learning-security` harnesses
keep their own temporary processes, database and keys and do not consume this volume.

### First start

Prerequisites are Docker Engine with the Compose plugin, Java/JDK 21 (`java` and
`keytool`), OpenSSL, Python 3 and curl. The local backend Dockerfile mirrors the
pinned release build/runtime stages without its optional BuildKit cache mount, so the
workflow also works with a Compose installation that has no buildx plugin. Run from
the repository root:

```bash
./scripts/mnema-local-full-stack.sh start
```

`start` invokes the bounded bootstrap automatically. Running `bootstrap` separately
is optional when you want to inspect/trust the CA before building images. Bootstrap
creates a random PostgreSQL password, RSA Identity signing JWKSet, local
CA, localhost/server certificate and Learning truststore under ignored
`.mnema/local-full-stack/`. Private files are owner-only and are reused on ordinary
starts. A missing, partial, permissive, mismatched or expiring set fails before
Compose is invoked; there is no HTTP or anonymous-signing fallback.

Import `.mnema/local-full-stack/local-ca.crt` into the current user's OS/browser
trust store, explicitly as a local development root, then open
`https://localhost:3443`. Browsers with a separate certificate store need the same
one-time import there. Do not trust the private key, reuse this CA outside Mnema, or
commit anything under `.mnema`. The launcher prints the exact CA path until curl sees
it as trusted.

Ports can be selected during the first bootstrap and are then retained with the
local security/database configuration:

```bash
MNEMA_LOCAL_WEB_PORT=4443 MNEMA_LOCAL_IDENTITY_PORT=4444 \
  ./scripts/mnema-local-full-stack.sh bootstrap
```

The retained OAuth redirect and issuer then use those ports.

### Verify, stop and restart

```bash
./scripts/mnema-local-full-stack.sh smoke
./scripts/mnema-local-full-stack.sh status
./scripts/mnema-local-full-stack.sh logs 100
./scripts/mnema-local-full-stack.sh stop
./scripts/mnema-local-full-stack.sh start
./scripts/mnema-local-full-stack.sh smoke
```

The smoke creates one private random local account, completes real S256 PKCE through
the HTTPS issuer, and creates/reloads a Deck and Capture through the frontend's
same-origin `/api`. Its owner-only credentials remain beside the other local state so
the second smoke proves restart persistence. It also publishes one retained native
material and typed exercise, completes a real scheduled attempt and observes material
progress. The smoke then restarts that material and runs replay plus introduced-only
practice, requiring both feedback-only modes to report `canonicalEffects: false` and
leave the restarted progress projection unchanged. Three additional retained fixture
Decks exercise `SELF_CHECK`, `CLOZE_SINGLE` and `SINGLE_CHOICE` through real scheduled
API attempts, exact retries and progress reads; the original Deck covers `TYPED`.
Conservative self-check/choice evidence may leave progress in `LEARNING`. Anonymous
Study start still has to fail closed with `401`.

`stop` retains PostgreSQL, accounts, content, JWK and certificates. A clean data reset
is destructive and requires the exact opt-in:

```bash
./scripts/mnema-local-full-stack.sh reset --confirm-delete-local-data
```

It deletes only the `mnema-local-v2` containers/volume and the synthetic smoke-account
state; local certificates and signing JWK remain. Local certificate rotation is
separate and preserves the database credentials, Identity signing key and smoke
account:

```bash
./scripts/mnema-local-full-stack.sh reset-certificates --confirm
```

Stop the stack first, remove the old CA from browser/OS trust, and trust the newly
printed CA path. Neither reset touches legacy v1 projects or hosted infrastructure.

`docker-compose.yml` remains the backend-only maintenance runtime from #143. Use it
only when frontend/HTTPS login is intentionally unnecessary. PostgreSQL 18 mounts
`/var/lib/postgresql` according to the
[official image contract](https://hub.docker.com/_/postgres); readiness ordering uses
Compose [health dependencies](https://docs.docker.com/compose/how-tos/startup-order/),
and private files are mounted through Compose
[secrets](https://docs.docker.com/compose/how-tos/use-secrets/).

## Historical v1 self-host reference

The old local and public launchers were removed from this checkout in #146. Their
matching source, Compose files and runbooks remain available in the
[`v1-apache-final`](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final)
tag and Git history. They are not supported by the replacement runtime.
