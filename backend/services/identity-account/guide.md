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
account.write learning.read learning.write`. Learning scopes are distinct from
account permissions; a Learning-only token does not authorize account APIs.
Public clients receive no refresh token; confidential clients
require explicit registration. Access tokens use RS256, type `at+jwt`, audience
`mnema-api`, a five-minute lifetime and a generation claim. Resource acceptance
requires the current active account generation and an active JDBC grant, plus the
scope for the requested read/write operation. ID tokens are not API credentials.

The standard `/userinfo` endpoint validates the same current generation and active
grant on each bearer request and returns the canonical account UUID in `sub`.
Learning's integration can use this endpoint as a liveness check after local
signature/type/issuer/audience/expiry and Learning-scope validation. It must compare
`sub`, fail closed on errors/timeouts, and never cache a successful result as proof
that a later request remains authorized. The receiving Learning filter, private
content APIs and real cross-service tests are implemented; this Identity-side
contract still does not replace their independent authorization tests.

`/userinfo` also carries one product claim, `mnema_public_profile` (boolean): the account may publish a
public deck, i.e. the public-profile consent is enabled and the account is active with a profile
username (`PublicProfiles.publishReady`, the same gate as the author card). It is computed on every request, so a
withdrawn consent is visible at once, and it is the only way Learning learns it (Learning reads no Identity
table). It is added through the Spring Authorization Server `userInfoMapper`
([OIDC UserInfo endpoint configuration](https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html#oidc-user-info-endpoint)):
the answer is `{sub, mnema_public_profile}`; the ID token carries no profile claims, so nothing the
default mapper returned is dropped.

Every login rotates the Secure/HttpOnly/SameSite=Lax session cookie and CSRF token.
Sessions live in PostgreSQL with eight-hour inactivity and absolute limits.
Cookie-authenticated browser mutations require `X-CSRF-TOKEN`; fetch its value and header name from
`GET /api/accounts/csrf` with credentials. CORS permits only the exact configured
`MNEMA_IDENTITY_FRONTEND_ORIGIN`.

Explicit bearer requests follow Spring Resource Server's existing CSRF exemption
and still require the operation's scope/current generation. The replacement web client
uses its verified account bearer with `account.write` for logout/password changes,
without ambient cookies; login/register remain cookie/CSRF flows. Identity must have
a distinct origin from the frontend so the retained XHR transport can omit credentials.
This prevents another tab's shared cookie from selecting a different account to revoke.
An expired/failed logout is not proof of server-session revocation.

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
| `GET`, `PUT /me/public-profile` | Own public-profile consent, see "Public profile consent and author cards" |
| `PUT`, `DELETE /me/avatar` | Multipart `file` upload / remove → 204 |
| `GET /me/avatar` | The owner's own verified bytes (independent of consent), `Cache-Control: no-store`, nosniff; 404 `avatar_not_found` when absent |
| `GET /profiles/{id}` | Public author card; 404 `profile_not_found` unless consent and a login exist |
| `GET /profiles/{id}/avatar` | Verified owned bytes only when the card exists and the owner shows the photo; strong `ETag` (stored SHA-256), `If-None-Match` → 304 from the database row alone; `public, max-age=60` on 200 and 304, nosniff; else 404 `avatar_not_found` |
| `GET /profiles?ids=a,b,…` | Batch of cards, 1–50 ids, request order, non-public ids omitted |
| `GET /profiles/by-username/{u}` | Card by profile username (case-insensitive), same 404 |
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

`bio` is plain Unicode text, capped at 200 UTF-16 code units on input and six lines
after layout normalization (blank lines count). CRLF/CR become LF; horizontal spaces
and tabs collapse to one space, line edges are trimmed, outer blank lines disappear
and consecutive blank lines collapse to one. Other ASCII control characters and DEL
are rejected with HTTP 400. Invalid edits leave the saved profile untouched. Reads
accept existing multiline descriptions without imposing the new edit-only line cap.
No HTML/Markdown rendering is enabled. Single-line identity/protocol fields keep
their existing validation. Server enforcement and field-specific rules follow
[OWASP input validation](https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html);
the form supplies immediate, associated errors using
[Angular form validation](https://angular.dev/guide/forms/form-validation).

All paths in the table are below `/api/accounts`. Private profile fields are
`accountId,email,emailVerified,profileUsername,displayName,bio,admin,status,
avatarPresent,hasPassword`. Unknown DTO fields are rejected. Local login and
profile usernames allow 3–50 ASCII letters/digits/underscore/dot/hyphen, excluding
`@` to avoid email/login ambiguity. Passwords require 12–128 characters and at most
72 UTF-8 bytes. Preserved BCrypt hashes are accepted without transfer-time rehash.
Unknown, banned and otherwise non-public accounts produce the same public profile
and avatar 404 contracts.

## Public profile consent and author cards

Wire examples and the shared test fixture: [`contracts/identity`](../../../contracts/identity/README.md)
(`public-profile.json`; `PublicProfileIntegrationTest` checks it against the real responses).
Product wording: community decks contract, "Профиль автора" and "Правила и ПДн". The consent
text is approved; `PublicProfiles.TEXT_VERSION` (`2026-10-10`) is its date.

Public reads exist only for a *card*: account `ACTIVE`, deletion state `ACTIVE`, consent
`enabled` and a profile username. Unknown, banned, deleting, no-consent, withdrawn and
no-username accounts return the same 404 (`profile_not_found` for cards, `avatar_not_found`
for photos), so there is no existence oracle. Card JSON is
`{accountId,profileUsername,displayName,bio,avatarPresent}`; `displayName`/`bio` are `null`
unless their flag is on (blank values are `null` too) and `avatarPresent` is `false` unless
`showAvatar` is on and a photo exists. Public answers carry `Cache-Control: public, max-age=60`
(404s are not cacheable), so a withdrawal is visible within a minute.

`GET /me/public-profile` (bearer or session) → `{enabled,showDisplayName,showAvatar,showBio,
textVersion,publishReady,updatedAt}`; no row yet is all `false` with `updatedAt:null`.
`publishReady` = enabled and a profile username (the account is active by authentication);
future deck publication requires it. `PUT` replaces all five fields
`{enabled,showDisplayName,showAvatar,showBio,textVersion}`; the body is parsed strictly (exact
field set, JSON booleans/string, no coercion; otherwise 400 `invalid_request`).

| Case | Answer |
| --- | --- |
| `enabled:true` and `textVersion` differs from `TEXT_VERSION` | 409 `consent_text_outdated`, nothing written |
| `enabled:true` and no profile username | 409 `profile_username_required`, nothing written |
| `enabled:false` | Withdrawal: every stored `show*` flag becomes `false`; the supplied `textVersion` is ignored (never 409) and the journal records the current one |
| PUT equal to the stored state | 200, no write, no journal row, `updatedAt` unchanged |
| Re-consent to a new text version with equal flags | An effective change (`CHANGE`) |

Tables (migration `V5`): `public_profile_consent` (one row per account; `CHECK` not enabled ⇒
all `show_*` false) and `public_profile_consent_event`, the append-only journal
(`GRANT` when enabling from none/disabled, `CHANGE` while enabled, `WITHDRAW`; the four flags,
`text_version`, `occurred_at`). A trigger refuses `UPDATE` and `TRUNCATE` always and `DELETE`
unless the account is `PURGING`. The account row lock taken by `PUT` serializes concurrent
changes, profile edits and moderation. Account purge deletes both tables' rows for the
account in the purge transaction (data minimisation) before the tombstone is written; a
deleting account (`PENDING_DELETION`) is already hidden because the card needs deletion state
`ACTIVE`, and cancelling the deletion restores the previous consent. Unban likewise restores
the card. The disposable account transfer does not carry consent: the target schema is fresh,
imported accounts have no consent row and therefore stay hidden until their owners
re-consent.

Rate limits use the existing `RateLimits` DB buckets (15-minute window) and answer 429
`try_later`. The client key is `ClientAddresses.rateKey`: the IPv4 address, or the /64 network
for IPv6 (a subscriber holds a whole /64). The batch is limited per client key (600).
By-username always spends the client-key bucket (60) and, when the caller is authenticated,
additionally a per-account bucket (60), so alternating anonymous and signed-in calls cannot
double the budget. `ExpiredStateCleanup` repeats its bounded 1000-row deletes (at most 50 per
table per run) so the extra rate-limit rows cannot outgrow it. The public avatar is decided in
one statement (card gate, `show_avatar`, stored photo row) so a withdrawal cannot fall between a
check and the read. Malformed batch requests (missing,
empty, more than 50, duplicate or non-canonical ids; 400 `invalid_request`) are rejected
before they count. A malformed username is the same 404 and does count. Cost: one PK lookup
per card, one `account_id = ANY(...)` query per batch and one unique-index lookup per
username; no consent rows means every public read is a single lookup returning 404.

## Abuse protection and sign-in availability

`POST /register` and `POST /login` verify a Cloudflare Turnstile token before any
credential work (`TurnstileGuard`; contract and production operation:
[turnstile.md](../../../docs/operations/turnstile.md)). `identity.turnstile.mode`
is `required` in production, `blocked` is the operational kill switch (503 for
password auth, OAuth unaffected) and `disabled` exists only for the local
environments listed in `TurnstilePolicy`; any other environment turns it into
`blocked`. There is no legal-approval property: the policy decision lives in the
public documents and the deployment configuration, not in a runtime flag. The
hosted `/login` page repeats the notice that continuing accepts the terms and
links the policy on the frontend origin (`identity.frontend-origin`).

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
grants, proofs, avatar metadata and public-profile consent with its journal, then leaves a tombstone containing only the UUID,
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
The bucket has no application default: avatars are configured only when bucket and
both keys are present, otherwise the operation fails closed with 503. Production
uses `mnema-prod-avatars-b1g0dnrijqn8` on Yandex Object Storage (versioning on,
default SSE-KMS, noncurrent versions expire after 7 days); the client sends no
default checksum trailer (`WHEN_REQUIRED`) because the provider does not document
it. Ownership metadata (`account-id`, `asset-id`) is read case-insensitively because
Yandex returns user-metadata keys capitalised. Credentials must be scoped to the identity-owned bucket. Uploads decode supported
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

`GET /api/accounts/providers` is a public list of configured provider names only.
The SPA starts `/oauth2/authorization/{provider}?mnema_state=...` with a random,
bounded browser correlation. Identity stores it inside the expiring, session-bound
upstream authorization request; it is echoed as `federation_state` to the fixed
frontend `/auth/callback` only after provider authentication succeeds. Link/proof/
recovery intents take precedence and cannot inherit an ordinary-login correlation.
The SPA consumes its matching transaction, starts a new Mnema authorization-code
request with S256 PKCE, exchanges that code, then verifies `/api/accounts/me`.
The provider cookie or correlation alone never grants Learning API access. Callback
failure, replay, missing correlation and mixed protocol responses fail closed.
Direct backend-only login resumes `/login/continue`; explicit linking returns to
`/profile`. Provider errors return to the frontend callback without provider diagnostics.

Yandex scopes are separate `login:email` and `login:info` values, encoded with a
space as required by the [Yandex authorization-code API](https://yandex.ru/dev/id/doc/ru/codes/code-url).
The existing [Spring OAuth2 login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html)
handlers own upstream state, nonce validation and token exchange; Mnema does not
implement another provider-token protocol or retain provider tokens.


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
[Spring Session JDBC](https://docs.spring.io/spring-session/reference/4.1/configuration/jdbc.html),
[Spring JWT validation](https://docs.spring.io/spring-security/reference/7.1/servlet/oauth2/resource-server/jwt.html),
[Postbox HTTPS signing](https://yandex.cloud/en/docs/postbox/operations/send-email#curl),
[GitHub PKCE](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps),
[Yandex PKCE](https://yandex.ru/dev/id/doc/en/codes/code-url),
[Java 25 AES-GCM parameters](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/spec/GCMParameterSpec.html),
and [authenticated-stream caveat](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/CipherInputStream.html).

## Owner operations directory

The [admin wire contract](../../../contracts/admin/README.md) owns the owner-only directory,
account snapshot and moderation journal. `identity.admin.owner-id` reads
`MNEMA_ADMIN_OWNER_ACCOUNT_ID`: an exact immutable account UUID, empty denies access.
Directory and journal reads require this owner, current account generation **and** a bearer token issued to
the `mnema-admin-web` client (`client_id` claim, added to every access token by the token customizer;
`AdminOwnerAccess`); configuration does not grant `is_admin`. That client is registered only while
`identity.admin-origin` (`MNEMA_IDENTITY_ADMIN_ORIGIN`) is set. Existing moderation HTTP endpoints require
the same once an owner is configured and are otherwise unchanged, while `Moderation` retains its actual
administrator/self/subordinate checks. Successful moderation inserts a journal entry in the same
transaction (outcome `SUCCESS`, plus the ban reason on `BAN`); a refused attempt by a current administrator is
journaled `DENIED` afterwards. `V4__admin_directory_and_audit.sql` adds the journal and keyset index; it does
not change credentials or role grants. The journal rejects `UPDATE`, `DELETE` and `TRUNCATE`.
The journal has no account foreign keys, so it cannot block the existing purge worker. The worker
does not erase journal actor/resource UUIDs. TODO(#409; owner: account-purge/legal workstream):
decide retention and include those links in the deletion and backup inventory; journal UUIDs
are pseudonymous account-linked data, not anonymous data.

`GET /api/accounts/admin/directory` searches literal bounded account fields and emits 50 rows maximum,
`/directory/{id}` resolves one nonpurged account, and `/audit` emits a separate 50-row UUIDv7 keyset.
All responses are private/no-store. No account facts are copied into Learning. Source OAuth/CORS
configuration admits only explicitly configured application/admin origins, as defined by the
[admin architecture](../../../docs/architecture/admin-console.md); deployment remains separate.
