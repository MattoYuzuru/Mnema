# Identity & Account runtime

`services:identity-account` owns canonical account identity, credentials, browser
sessions, OAuth/OIDC grants, profiles, moderation and account avatars. It has no
source or Gradle dependency on legacy `auth` or `user` applications.

## Canonical API and security

The servlet context is `/`. Account routes are explicitly `/api/accounts/**`;
actuator probes remain `/api/actuator/health/{liveness,readiness}`. Spring
Authorization Server exposes its standard root OAuth/OIDC routes, including
`/.well-known/openid-configuration`, `/oauth2/authorize`, `/oauth2/token`,
`/oauth2/jwks`, `/userinfo` and `/connect/logout`. No `/api` protocol aliases exist.

`MNEMA_IDENTITY_ISSUER` is a required explicit HTTPS issuer, never reconstructed
from request or forwarded headers. Production is `https://auth.mnema.app`.
Subjects are exact canonical account UUID strings. Email, local login and mutable
profile username are separate fields; profile changes do not revoke credentials.

The public `mnema-web` client uses authorization code with S256 PKCE and one exact
`MNEMA_IDENTITY_REDIRECT_URI`. Its scopes are `openid profile account.read
account.write`. Public clients receive no refresh token; confidential clients
require explicit registration. Access tokens use RS256, type `at+jwt`, audience
`mnema-api`, a five-minute lifetime and a generation claim. Resource acceptance
requires the current active account generation and an active JDBC grant, plus the
scope for the requested read/write operation. ID tokens are not API credentials.

Every login rotates the Secure/HttpOnly/SameSite=Lax session cookie and CSRF token.
Sessions live in PostgreSQL with eight-hour inactivity and absolute limits.
Browser mutations require `X-CSRF-TOKEN`; fetch its value and header name from
`GET /api/accounts/csrf` with credentials. CORS permits only the exact configured
`MNEMA_IDENTITY_FRONTEND_ORIGIN`.

Password change/reset, logout (including authenticated OIDC logout), ban and
admin/factor revocation advance a separate security generation and invalidate
sessions, grants and proofs in one transaction. Unban does not restore them.
Protected requests recheck current state; already-running read requests may finish
at the revocation boundary. Mutations recheck under account row locks. The token
endpoint serializes code/refresh consumption and successor persistence using a
transaction and a token-hash advisory lock; responses are released after commit.

## Browser/account endpoints

| Endpoint | Request/result |
| --- | --- |
| `POST /register` | `{email,loginName,password,profileUsername}` → 201 profile, no session |
| `POST /login` | `{login,password}` → profile and rotated session |
| `POST /logout` | 204, revoke all current account access |
| `GET /session`, `GET /me` | Current private profile |
| `PATCH /me` | `{profileUsername,displayName,bio}` only |
| `POST /me/password` | `{currentPassword,newPassword}` → 204 and revoked sessions |
| `POST /email-verification/request` | `{email}` → uniform 202 |
| `POST /email-verification/confirm` | `{token}` → 204, verified email, no session |
| `POST /password-reset/request` | `{email}` → uniform 202 |
| `POST /password-reset/confirm` | `{token,newPassword}` → 204, no session |
| `POST /me/proofs` | `{password,purpose}` → `{token,expiresAt}` |
| `POST /me/proofs/federated` | `{provider,purpose}` → authorization URL; state-bound callback returns proof JSON |
| `GET /me/identities` | Owned `{identityId,provider}` entries, no provider subjects |
| `POST /me/identities/link` | `{provider,proof}` → authorization URL |
| `DELETE /me/identities/{id}` | `{proof}` → 204 and revoked access |
| `GET /profiles/{id}` | Public username/display name/bio/avatar presence only |
| `PUT`, `DELETE /me/avatar` | Multipart `file` upload / remove → 204 |
| `GET /profiles/{id}/avatar` | Verified owned bytes, no-store, nosniff |
| `POST /admin/accounts/{id}/ban` | `{reason}` → 204 |
| `POST /admin/accounts/{id}/unban` | 204 |
| `POST`, `DELETE /admin/accounts/{id}/admin` | Grant / revoke → 204 |

All paths in the table are below `/api/accounts`. Private profile fields are
`accountId,email,emailVerified,profileUsername,displayName,bio,admin,status,
avatarPresent,hasPassword`. Unknown DTO fields are rejected. Local login and
profile usernames allow 3–50 ASCII letters/digits/underscore/dot/hyphen, excluding
`@` to avoid email/login ambiguity. Passwords require 12–128 characters and at most
72 UTF-8 bytes. Preserved BCrypt hashes are accepted without transfer-time rehash.
No deletion endpoint is provided: deletion/recovery/purge and retention belong to
#157. `OwnershipProofs` and exact avatar cleanup receipts provide its hooks.

## Verification, reset and federation

Email verification is required before a local account can recover by email.
Verification and reset use distinct random 256-bit, SHA-256-hashed, ten-minute,
one-use challenges bound to account UUID, purpose and security generation.
Verification never authenticates the browser. Reset requires an active account,
a local credential and an already verified address, and never auto-logs in.
Expired, foreign, wrong-purpose and revoked challenges fail closed.

Mail is sent only after challenge creation commits and outside account locks.
HTTPS requests have bounded connection/request timeouts and no redirects. Explicit
failure or timeout invalidates the challenge. A provider may have accepted a
request before a timeout; such a received link is intentionally unusable. There
is no plaintext secret outbox, response, retry log or token query parameter.
Links use the configured frontend origin and `/verify-email#token=...` or
`/reset-password#token=...`. Public request endpoints use the same four-second
minimum completion envelope for eligible, ineligible, throttled and failed sends;
unexpected database outages can exceed it. No guarantee of identical network
latency is implied.

Login failures use generic `authentication_failed`; unknown/ineligible email
requests return empty 202. Registration/normalized uniqueness conflicts use generic
`account_conflict` and therefore reveal that supplied registration data conflicts,
without identifying the field or account. Local password failures commit bounded
counters, including a fifteen-minute lock after five failures. Fifteen-minute
rate windows bound registration (10/address), login (100/address and 20/login),
email requests (20/address and 3/email), and proof/password attempts (10/account).
Buckets and secrets are hashed; expired runtime state is removed in bounded batches.

Google, GitHub and Yandex use exact provider plus opaque case-sensitive subject.
There is no email auto-link. GitHub creation uses a primary verified email from
its emails endpoint; Yandex email is not assumed verified. Existing bindings
survive provider email drift. Linking requires a fresh local/provider ownership
proof, and its intent is bound into that exact session-held authorization request.
State has a five-minute independent expiry, one-use consumption and exact provider
callback matching. S256 PKCE is used for each provider; OIDC additionally validates
nonce, issuer, audience and signing keys. The last authentication factor cannot be
unlinked. Provider pictures are never imported as owned account avatars.

Moderation reads current database authority. Administrators cannot moderate
themselves, ban administrators or revoke bootstrap administrators. Only the grantor
can revoke a subordinate, and an administrator with active subordinates cannot be
revoked. A transaction advisory lock serializes changes to that hierarchy.

## Database, storage and configuration

Flyway owns only `app_identity` under `classpath:db/identity/migration`. V1 remains
unchanged; V2 adds fresh runtime state. PostgreSQL 18 constraints enforce normalized
uniqueness and account/credential/provider/avatar ownership.

The exhaustive legacy-field classification is in
[`legacy-field-classification.md`](legacy-field-classification.md). Its denylist
is an **import denylist**: old sessions, grants, clients, secrets, transient
challenges and signing keys must never be transferred by #144. Fresh JDBC sessions,
authorizations/consents/clients, proof challenges and rate-limit tables are necessary
runtime state and are never account-export data.

Signing requires `MNEMA_IDENTITY_SIGNING_JWK_SET_FILE` (mounted private RSA JWKSet)
and `MNEMA_IDENTITY_SIGNING_ACTIVE_KID`. Keys are provisioned offline per environment;
the runtime never creates a key on restart or accepts a legacy-key fallback. The
active key must contain private material and be at least 2048 bits. Other configured
keys are verification-only, and duplicate kids are rejected. Rotation requires a
new active kid plus an explicit retained verification set; removing a kid invalidates
its tokens. Protect the file as a runtime secret and retain it across normal restarts.

Postbox uses dedicated `MNEMA_POSTBOX_ACCESS_KEY`/`MNEMA_POSTBOX_SECRET_KEY` and
AWS SigV4 for region `ru-central1`, service `ses`, fixed HTTPS SendEmail endpoint
and sender `noreply@mnema.app`. API-key SMTP authentication is not reused for HTTPS.
Missing mail configuration cannot count as successful delivery.

Avatars use `MNEMA_AVATAR_ENDPOINT`, `MNEMA_AVATAR_REGION`,
`MNEMA_AVATAR_BUCKET`, `MNEMA_AVATAR_ACCESS_KEY` and `MNEMA_AVATAR_SECRET_KEY`.
Credentials must be scoped to the identity-owned bucket. Uploads decode supported
images before storage, with 10 MiB and 1024×1024 bounds. Keys are generated from
account/asset UUIDs. The object is written before the reference is atomically
replaced; exact old/orphan key receipts survive storage failures and are retried
in bounded five-minute batches. Reads check stored size and SHA-256. Imported exact
assets are governed by their account-owned database reference; arbitrary URL fetches
and caller-supplied storage keys are unavailable.

Missing mail/avatar credentials permit core startup but the affected operation
fails closed. Tests may opt into HTTP only for literal loopback fixture endpoints;
this is not a production HTTP fallback. Federation credentials are optional through
`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_{GOOGLE,GITHUB,YANDEX}_{CLIENT_ID,CLIENT_SECRET}`;
missing pairs leave that provider unavailable. Callback URLs are fixed to the
configured issuer plus `/login/oauth2/code/{provider}`.

## Evidence and references

Run `cd backend && ./gradlew quality`; the identity line-coverage threshold remains
90%. Tests use real PostgreSQL 18, session cookies, actual PKCE exchanges, OIDC
callbacks with a fixture JWKS, and disposable HTTP mail/S3 providers. They do not
send real external mail or prove environment credentials/provider registration.

Important implementation sources:
[Spring Authorization Server configuration](https://docs.spring.io/spring-authorization-server/reference/configuration-model.html),
[public-client PKCE and no refresh tokens](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html),
[Spring Session JDBC](https://docs.spring.io/spring-session/reference/3.5/configuration/jdbc.html),
[Spring JWT validation](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html),
[Postbox HTTPS signing](https://yandex.cloud/en/docs/postbox/operations/send-email#curl),
[GitHub PKCE](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps),
[Yandex PKCE](https://yandex.ru/dev/id/doc/en/codes/code-url).
