# Owner operations console

The authenticated console lives at `https://admin.mnema.app/manage`. Identity owns account facts;
Learning owns usage, study, generation, media and editorial facts. No service reads another
service's tables. Source host configuration is part of this change; production remains a separate rollout.

## Access and response boundaries

`MNEMA_ADMIN_OWNER_ACCOUNT_ID` configures the same canonical Identity UUID in both runtimes.
Empty denies all console/directory access; malformed configuration fails startup. The configured
UUID grants console **read access**, never an Identity administrator grant or editorial permission.
All Learning routes require a valid bearer, `learning.read` on GET, and live Identity `/userinfo`.
Identity routes retain `account.read`/`account.write`, active-account generation checks and cookie
CSRF. Every response below uses `Cache-Control: private, no-store`.

`GET /api/admin/console/access` returns `access.example.json`. `owner:true` means console access;
`permissions.events` uses the separate `MNEMA_EVENTS_OWNER_ACCOUNT_ID`; `promos` and `moderation`
require a current Identity admin grant. `support` means the server bridge is configured, not that
its remote service is healthy. Owner configuration never silently changes grants. Backend checks
are decisive; the browser permissions only choose visible actions. Existing promo endpoints
retain their independent live Identity-admin authorization; the new owner gate does not
silently revoke those pre-existing API permissions from another administrator. The console,
Identity directory/moderation HTTP and support routes independently require the configured owner.
Console role flags and promo authorization read `/api/accounts/me` without the standing cache.
Ordinary verified-email checks retain a bounded 60-second cache keyed by account UUID and validated
JWT generation; a relogin generation cannot reuse the prior standing.

## Directory and account operations

Identity: `GET /api/accounts/admin/directory?query=<literal>&status=ACTIVE|BANNED&after=<opaque>`.
All parameters are optional. Query is 1–100 characters, trimmed, case-insensitive literal substring
of email, profile username or display name; exact account UUID also matches. Wildcards have no special
meaning. Results exclude purged accounts, sort `createdAt DESC,accountId DESC`, and replace at most
50 rows per page. `next` is an opaque keyset cursor, null at the end. It is not a snapshot; changing
filters starts a new page. Malformed/duplicate/unknown parameters return HTTP 400.

`GET /api/accounts/admin/directory/{accountId}` returns one `Account` from `directory.example.json`,
or HTTP 404. List and detail use the same bounded fields, including nullable `bannedAt`, `banReason`,
`lastLoginAt`, `profileUsername`, `displayName`; no credentials, sessions, addresses, tokens or external
provider subjects are exposed.

Existing mutation paths now also require the exact console owner:

- `POST /api/accounts/admin/accounts/{id}/ban` body `{ "reason": "..." }` (nullable, maximum 280 characters).
- `POST /api/accounts/admin/accounts/{id}/unban` with no body.
- `POST /api/accounts/admin/accounts/{id}/admin` and `DELETE` on that path retain the admin graph constraints.

All return 204. Current admin role, self-target and subordinate constraints remain in Identity.
A failed/unknown network result is resolved by reloading the account; do not blindly repeat a ban.
The journal records successful state changes atomically; no reason/free text is duplicated there.

## Reporting

`GET /api/admin/console/report?from=YYYY-MM-DD&to=YYYY-MM-DD` returns `report.example.json`.
`GET /api/admin/console/users/{accountId}?from=...&to=...` returns `user.example.json`.
Dates are required, canonical calendar dates with year 0001–9999, a UTC **half-open** interval `[from,to)` of 1–90 days.
The exclusive end may be tomorrow UTC to include the ongoing current day. The UI displays an
inclusive end and converts it. Future ends, unknown/duplicate/missing parameters return HTTP 400.
Each response uses one read-only repeatable-read database snapshot with a ten-second transaction
limit. No provider call, allowance initialization or content disclosure occurs during reporting.
Unknown Learning account IDs return a zero-valued snapshot; account existence belongs to Identity.

Sources and formulas:

| Field | Source and meaning |
|---|---|
| AI calls/cost | `ai_provider_call.created_at` in range; each attempt is a call, including retries. `cost_micros` is configured **micro-USD estimate**, not invoice cash. |
| AI failures | Outcome other than `OK`/`PENDING`; pending is separate and must not be counted as failure. |
| Latency p50/p95/p99 | PostgreSQL `percentile_cont` over nonnull recorded latency, including completed failures. Empty sample is null, never zero. |
| Routes | At most 256 capability/provider/model groups ordered by estimated cost; `routeGroupsTruncated` signals more groups. Totals include every route. |
| AI coverage | Declared journal retention defaults 90 days; `retentionWindowStart` is the configured age cutoff, not an invoice ingestion watermark. `rangeIncludesExpiredData` warns of potential purge. Failure cost coverage is incomplete; lost/unreported usage may have zero cost. |
| Daily AI trend | UTC days present in the journal, at most 90 rows. Omitted days mean no recorded calls; this does not prove zero invoice charges. |
| Top usage users | At most 20 opaque account UUIDs ranked by gross credits then UUID, with DEBIT event counts. Names/emails are resolved through the owner-only Identity directory. |
| Usage-active cohort | Distinct `usage_ledger_entry.owner_id` with `kind=DEBIT` in range, including zero-credit fair-use entries. This is not all registered users or general DAU. |
| Credits and percentiles | Gross sum of `-credits` for DEBIT rows; refunds/adjustments are not netted. p50/p95/p99 distribute per-user gross sums within that cohort, including zero-credit-only users; `sample` names the denominator. Product credits are not money. |
| Features | `COALESCE(operation,bucket,'UNCLASSIFIED')`; distinct users/events/gross credits and recorded bucket units. `share=users/usage.activeUsers` in `[0,1]`; features overlap and shares need not total 100%. STT units are seconds; other buckets are counts. |
| Study | Attempts from `study_attempt_tombstone.submitted_at`; completed sessions by `completed_at` and `status=COMPLETE`. No answer or feedback text is read. |
| Generation | Users from currently retained generation sessions created in range (~30-day session retention); this is explicitly incomplete longitudinal history. Publications count distinct artifacts in durable `generation_provenance.created_at` within range. |
| Media | Current inventory at `generatedAt`, independent of date filter. `blobBytes` sums deduplicated catalog blobs, including GC candidates. `assets` excludes DELETED, state breakdown includes DELETED. It is not an S3 invoice, bandwidth or replica total. |
| Account snapshot | Current nondeleted decks and current head items; windowed study/publications/usage; distinct nondeleted source blob bytes, excluding variants. |
| Current entitlement | Always-present `currentEntitlement={plan,source,period,validUntil}` from existing `EntitlementSource.current(accountId,generatedAt)`, independent of the report range. Valid started inbox snapshots are selected by existing plan precedence; without one, CONFIG default/override applies. The existing port reports `YEAR` for BILLING snapshots spanning more than two months and `MONTH` otherwise. Every promo reports `MONTH` regardless of gift duration; allowances remain monthly for every source/period. The period is not proof of payment or renewal. CONFIG validity ends at the current usage-calendar month boundary (Europe/Moscow), then default access is resolved again. This read never creates or rebases an allowance. An unknown Identity account still receives the configured Learning fallback; existence must be checked in the directory. |
| Allowances | At most four stored monthly snapshots intersecting the interval, with materialized balances and `updatedAt`; not a promise of today's entitlement or unlock schedule. A never-used account has no stored snapshot. |
| Money unavailable | Revenue, provider invoices and infrastructure have no authoritative connected source in this revision. `UNAVAILABLE` is neither zero nor estimated profit. Never combine ledger micro-RUB costs with journal micro-USD. |

There is no click/page/session-event collection in this change. Registration funnels, D7 retention,
MRR, net revenue, payment/refund fees, churn, storage invoices and exact per-account provider cost
require their owning sources and separately accepted definitions. The provider journal has no
owner UUID; joining retained generation steps would omit other calls and purged associations.

## Journal

Learning: `GET /api/admin/console/audit?before=<auditId>`.
Identity: `GET /api/accounts/admin/audit?before=<auditId>`.
Both return `audit.example.json`: 50 entries maximum ordered by PostgreSQL-generated UUIDv7 descending,
`next` points at the last emitted ID when more exist. Each source has its own cursor. Identity
`commandId` is null; Learning editorial/create entries carry their command IDs. Successful actions
append in the domain transaction; receipt replay does not duplicate an action. Entries have only
actor/resource UUIDs, enums, time and optional command UUID; no content, promo plaintext, emails or external identity fields. UUIDs remain account-linked pseudonymous data.
Database updates are rejected. Deletion/retention must be owned by the account-purge/legal workstream;
this console does not implement an export or destructive purge.
Neither journal has a foreign key to account rows, so moderation history cannot block existing
Identity purge. The current purge does not erase these new account-linked journal UUIDs.
TODO(#409; owner: account-purge/legal workstream): decide the journal retention basis and include
both actor/resource links, Learning command receipts and bot note/command/audit actor UUIDs in the
purge and backup inventory before enabling the corresponding deletion claim.

## Promo creation safety

`POST /api/admin/promo-codes` requires `Idempotency-Key` (canonical command UUID v4/v7) and an explicit
`code` field plus the existing fields. The UI generates 12 unambiguous characters using browser
`crypto.getRandomValues` with rejection sampling, or accepts a typed 8–24 character normalized code.
All other tier/discount/date/limit validation remains unchanged. The payload receipt replaces the exact submitted code text
with a purpose-prefixed keyed HMAC fingerprint before canonical hashing; receipts store a code-free response.
Exact same actor/key/payload replay reconstructs `code` **from the caller's request** and returns the
same created object. Conflicting actor or payload returns 409. Plaintext is absent from the database,
list, audit and logs. The browser retains the exact in-memory command after an unknown result and
permits only its retry; a fresh command must not be started until it resolves. Refresh loses the
one-time display, so explicitly save the code after successful creation. Existing GET/PATCH retain
200-row promo keyset pages and idempotent `{enabled:boolean}` setter; prior redemptions remain valid.

Official sources: [Spring Security authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
for independent endpoint authorization and fail-closed configuration;
[PostgreSQL 18 aggregates](https://www.postgresql.org/docs/18/functions-aggregate.html)
for continuous percentiles and null empty samples.

## Telegram support bridge

`/api/admin/support` is a Learning proxy authorized against the exact console owner on every
request. Bot SQLite owns tickets, messages, private notes, versions, command receipts, audit
metadata and the existing Telegram outbox. Learning does not duplicate that state or poll/send
Telegram. The bot-side [activation and transport contract](https://github.com/MattoYuzuru/Mnema-Telegram-Bot)
is source work pending its own deployment. The bridge is disabled when endpoint/credential
configuration is empty. `permissions.support` means configured, not remotely healthy.

- `GET /api/admin/support/tickets`: optional `status=open|working|waiting|closed`,
  `category=bug|idea|question|other`, `delivery=queued|sending|sent|failed|uncertain`, `userId`,
  `accountId`, `q`, `before`, `limit`. IDs/cursors are positive decimal **strings**. Pages descend
  by ticket ID, default 30, maximum 100: `{entries,nextCursor}`. `q` is 1–100 characters matching
  ticket/user ID or literal substring of Telegram username/first name; it does not search message
  bodies. Drafts are excluded. Unknown/duplicate filters fail 400.
- `GET /api/admin/support/tickets/{id}`: `afterMessage`, `limit` (default 50, maximum 100).
  `{ticket,messages,nextMessageCursor}` is a snapshot of the version and conversation, ascending
  from the oldest global timeline entry. Missing/draft tickets fail 404. A note gets its own
  timeline ID from a separate private notes table; Telegram user queries never read notes.
- `POST /api/admin/support/tickets/{id}/commands`: exact body
  `{commandId,expectedVersion,type,text}` for `reply|note`, or
  `{commandId,expectedVersion,type:"status",status}`. Command ID is canonical UUIDv4/v7; version
  is a safe nonnegative integer. Text is nonblank, at most 3500 Unicode code points. Learning
  injects the verified actor UUID; a browser-supplied actor or unknown field fails 400.

Ticket fields: `{id,version,category,status,userId,username,firstName,accountId,createdAt,
submittedAt,updatedAt,latestDelivery}`. Message fields: `{id,direction:in|out|note,text,createdAt,
attachment,delivery}`. Attachment metadata is `{kind,name,size,mimeType}` only; file IDs,
Telegram token URLs and automatic downloads are excluded. Account association is optional,
not proof of legal identity or privileged app access. Account linking remains inactive.

Reply returns 202; note/status returns 200. Acknowledgement:
`{commandId,ticketId,version,messageId,outboxId,delivery}`. Reply has queued delivery and message/
outbox IDs; note has a message ID and null outbox/delivery; status has all three null. Bot commands
check the global receipt before CAS. Same exact actor/ID/payload replays the original receipt with
`Idempotency-Replayed: true`; ID reuse or stale version fails 409 `SUPPORT_CONFLICT`. The mutation,
version, metadata-only audit, receipt and outbox commit atomically. A missing trusted result uses
503 `SUPPORT_UNAVAILABLE`; retry exactly that command, never silently create another one.

Delivery is independent of status: the existing queue sets `waiting` before dispatch. Queued/
sending are pending; sent means Telegram accepted the message, not read it. Failed/uncertain
remain actionable. An ambiguous Telegram send is never repeated automatically. This web API
has no retry-outbox operation; recovery remains an explicit privileged operator action after
checking the Telegram chat. Refresh and pagination preserve the operator's unsent text.

Configuration: `MNEMA_ADMIN_SUPPORT_ENDPOINT` and `MNEMA_ADMIN_SUPPORT_SECRET` are both empty
or both set. The endpoint is fixed HTTPS, exact `/internal/support` path, no credentials/query/
fragment/redirect. Secret is 32–256 base64url characters and distinct from Telegram's token.
`MNEMA_ADMIN_SUPPORT_ALLOW_LOOPBACK_HTTP` defaults false and permits only literal loopback for
local fixtures; production requires HTTPS. Timeout is 1–10 seconds (`MNEMA_ADMIN_SUPPORT_TIMEOUT`,
default `PT6S`); concurrency is 1–8 (`MNEMA_ADMIN_SUPPORT_CONCURRENCY`, default 4); response cap
is 1 MiB with strict known envelopes. No browser sees the machine credential. Private TLS transport
installation and bot rollout require separately authorized administrator work.

[Shared support fixtures](support.json) exercise both sides, including decimal IDs beyond the
JavaScript integer range. Official [Telegram API](https://core.telegram.org/bots/api#sendmessage)
decides acceptance semantics; [Java 25 HTTP client](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html)
decides resource reuse/cancellation and [OWASP REST guidance](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html)
decides HTTPS and per-endpoint authorization.
