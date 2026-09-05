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

Rate-limit identities use the raw socket peer by default. `X-Forwarded-For` is
accepted only when that peer belongs to `MNEMA_IDENTITY_TRUSTED_PROXY_CIDRS`, and
the right-most untrusted address is selected from a bounded chain. Production
must bind this value to the actual ingress pod CIDRs; public callers cannot choose
their own bucket by sending forwarding headers directly.

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
| `PUT /me` | Full `{profileUsername,displayName,bio}` replacement; every field required, empty display/bio clears it |
| `POST /me/password` | `{currentPassword,newPassword}` → 204 and revoked sessions |
| `POST /me/deletion` | `{proof}` → 202 fixed operation/deadlines and immediate access revocation |
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
| `GET /deletion/recovery/{operationId}` | Recovery-only status/deadlines; no profile fields |
| `DELETE /deletion/recovery/{operationId}` | Explicit pre-deadline cancel and recovery-context consumption |
| `POST /deletion/recovery/logout` | Invalidate only the recovery context → 204 |
| `POST /deletion/recovery/federated` | Start bound-provider recovery proof without ordinary access |
| `POST /deletion/proof/password` | Password proof for deletion only; never creates an ordinary session |
| `POST /deletion/proof/federated` | Start a bound-provider deletion-only proof |
| `POST /deletion/confirmed` | `{proof}` → 202 for owners without ordinary access, including banned owners |

All paths in the table are below `/api/accounts`. Private profile fields are
`accountId,email,emailVerified,profileUsername,displayName,bio,admin,status,
avatarPresent,hasPassword`. Unknown DTO fields are rejected. Local login and
profile usernames allow 3–50 ASCII letters/digits/underscore/dot/hyphen, excluding
`@` to avoid email/login ambiguity. Passwords require 12–128 characters and at most
72 UTF-8 bytes. Preserved BCrypt hashes are accepted without transfer-time rehash.
Unknown, banned and otherwise non-public accounts produce the same public profile
and avatar 404 contracts.

## Account deletion and recovery

Deletion is disabled unless `MNEMA_IDENTITY_DELETION_ENABLED=true`. Enabling it also
requires an environment-owned `MNEMA_IDENTITY_DELETION_RECOVERY_PERIOD`; the empty
disabled value is not a production retention decision. A request consumes a
fresh `DELETE_ACCOUNT` ownership proof, fixes `deletion_requested_at`,
`recoverable_until` and `purge_after` from PostgreSQL transaction time, advances the
account security generation and changes `ACTIVE → PENDING_DELETION`. Concurrent
retries with the same still-unexpired confirmation return the same operation; a
different or expired proof is rejected and deadlines never move. Existing
sessions, grants and proofs are deleted; token/profile/avatar acceptance also checks
the lifecycle state. An already-running read may finish, while mutations recheck the
locked account before commit.

A banned owner cannot receive `ACCOUNT` authority. Dedicated password/provider proof
routes instead return only a UUID/generation/purpose-bound `DELETE_ACCOUNT` proof;
the separate confirmed command consumes it and never creates an ordinary session.
The same recovery-only flow below becomes available once that request is pending.

A correct password submitted to the normal login endpoint for a pending account, or
an exact previously linked provider through the dedicated federated start, produces
only a short `ACCOUNT_RECOVERY` session (five-minute default). Its response contains operation state
and deadlines but no profile/email. The session is account, purpose, security-
generation and expiry bound, has a rotated JDBC session ID and CSRF token, and can
only read its own operation, explicitly cancel before the deadline, or log out.
Account, Learning and OAuth/OIDC routes reject it. Unknown/wrong credentials retain
the ordinary `authentication_failed` response. Cancel consumes all recovery sessions,
advances security generation again and may create one new ordinary session only when
moderation status remains `ACTIVE`; it never restores a ban or removed admin grants.

The durable purge queue is the account row plus `account_deletion`; `@Scheduled` only
wakes a bounded scanner. PostgreSQL `FOR UPDATE SKIP LOCKED` claims overdue work.
Every claim increments a lease epoch, heartbeat/completion/retry updates are fenced by
operation, deletion generation, worker and epoch, and an expired lease is reclaimable.
Cancellation and the transition to `PURGING` serialize on the same account row; at or
after `recoverable_until`, and after `PURGING` begins, cancellation is unavailable.
External deletion is at-least-once: a stale worker may finish an object request, but
cannot commit database completion, so exact effects must be idempotent.

Before access is revoked, the transaction freezes current and orphan-cleanup avatar
rows into an immutable manifest. Each key must equal
`account-avatar/{accountId}/{assetId}`. Storage performs HEAD against the exact key and
recorded version and requires matching `account-id`/`asset-id` metadata on every data
version. The bounded exact-prefix listing rejects truncation, filters to equality,
preflights every version before mutation, deletes every exact version and delete
marker, then verifies that neither a version nor a current object remains. Absence is
success, while mismatch, an excessive version set or transport failure leaves the
operation in `PURGING` with a bounded backoff and non-sensitive error code. No email,
unchecked prefix-only or checksum-only deletion exists.

Identity completion removes credentials, provider subjects, profile fields, sessions,
grants, proofs and avatar metadata, then leaves a tombstone containing only the UUID,
creation/update and security/deletion generations, moderation status/actor timestamp
needed for FK integrity, operation timestamps/retry evidence, an aggregate avatar-
manifest hash and durable erasure receipts. Email becomes `NULL`, so registration may
reuse it only under a new UUID. The first receipt scope is `identity-account`; future
domain cleaners acknowledge the same operation/generation idempotently with their own
receipt UUID. This handoff is not a claim that Learning/Study/media data, provider
backups or platform-wide erasure completed.

No production policy, notification, backup expiry, deployment or destructive run is
performed by #157. Those remain explicit launch/change gates.

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

## Disposable account-only transfer

The `accountTransfer` Gradle task is an offline rehearsal tool for the accepted
PostgreSQL 16 → 18 reset. It requires `APP_ENV=rehearsal` (case-insensitive after
trimming) and `MNEMA_ACCOUNT_TRANSFER_DISPOSABLE_TARGET=true`; missing, development,
staging and production environment values fail before argument or connection
processing. Production execution and direct cloud-object orchestration remain #147
boundaries.

The source database connection is supplied only through
`MNEMA_ACCOUNT_TRANSFER_SOURCE_{URL,USERNAME,PASSWORD}` and the target through the
equivalent `TARGET_*` variables. No credential is accepted as a command argument.
`MNEMA_ACCOUNT_TRANSFER_SOURCE_AVATAR_ROOT` is a private, read-only filesystem
projection of the legacy bucket where each exact legacy `storage_key` resolves to
its blob. `MNEMA_ACCOUNT_TRANSFER_TARGET_AVATAR_ROOT` is an empty disposable target
projection. Export validates ownership, ready state, MIME, size, dimensions and
the actual blob; import writes the canonical
`account-avatar/{accountId}/{assetId}` key idempotently.

Artifacts are AES-256-GCM encrypted with the 32-byte base64 key in
`MNEMA_ACCOUNT_TRANSFER_ENCRYPTION_KEY_B64`, created mode `0600` and never
overwritten. Keep the key outside the artifact and repository. Import authenticates
the complete GCM ciphertext before parsing any ZIP entry; `CipherInputStream` is
deliberately not used because Java does not propagate all failed integrity checks
from that stream. The decrypted projection has a closed JSON schema and closed
archive entry set; duplicate, missing or unknown fields/entries fail. It contains
preserved password hashes and PII and must never be attached to GitHub evidence. The
separate reconciliation JSON contains only counts, byte totals and aggregate SHA-256
values.

With source writes stopped and exact avatar objects staged, run:

```text
./gradlew :services:identity-account:accountTransfer --args="export --artifact=/private/account-transfer.enc"
./gradlew :services:identity-account:accountTransfer --args="import --artifact=/private/account-transfer.enc --evidence=/private/import-evidence.json"
./gradlew :services:identity-account:accountTransfer --args="reconcile --artifact=/private/account-transfer.enc --evidence=/private/reconcile-evidence.json"
```

Export selects only the classified `auth.users`, `auth.accounts`,
`app_user.users` and conditional avatar fields. It never selects registered
clients, sessions, authorizations, consents, token values, uploads or application
data. Import requires a PostgreSQL 18 fresh schema with zero sessions, grants,
challenges and rate-limit state; registered clients remain configuration. A
repeated import must resolve to the same accounts, credentials, identities and
avatars. Any different or additional target row fails reconciliation. Avatar
receipts and compensating deletion prevent an unsuccessful import from silently
leaving an unowned blob.

`AccountTransferIntegrationTest` runs the real PostgreSQL 16 → 18 path, includes
forbidden session/token/grant fixtures, repeats import, validates encrypted archive
privacy and then smokes restored password login/replacement, federation, profile,
moderation and avatar behavior.

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
[Yandex PKCE](https://yandex.ru/dev/id/doc/en/codes/code-url),
[Java 21 AES-GCM parameters](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/spec/GCMParameterSpec.html),
and [authenticated-stream caveat](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/CipherInputStream.html).
