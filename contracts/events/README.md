# Product events

The public timeline contains owner-written release notes, ideas and experiment announcements.
Learning owns `app_learning.product_event` (migration `V43__product_events.sql`); Identity
continues to own accounts and authentication. This is editorial content, separate from the
private notification center and learning material.

## Reading

`GET /api/events` is public, including when Identity is unavailable. It returns only published
events, with `Cache-Control: public, max-age=60`:

```json
{
  "items": [{
    "eventId": "00000000-0000-4000-8000-000000000001",
    "title": "Обновление редактора",
    "bodyMarkdown": "Упростили создание материалов. [Подробнее](https://mnema.app/events)",
    "eventDate": "2026-10-07",
    "publishedAt": "2026-10-07T12:00:00Z"
  }],
  "nextCursor": null
}
```

Pages contain at most **50** events, ordered by `eventDate DESC, eventId DESC`. Pass the opaque
`nextCursor` as the single `cursor` query parameter for the next page. There is no offset,
page-size override or count query. Empty/malformed/duplicate cursors and other query parameters
return `400 INVALID_REQUEST`. A cursor is a keyset location, not a snapshot: publishing newer
events does not shift the next page; editing an existing event's date may move it between pages.
Reload the first page to reflect editorial changes.

`eventDate` is an explicit calendar date and may describe a planned event. Publication is
immediate when `published` is true; there is no scheduling behavior. `publishedAt` records the
first publication instant and survives unpublishing and republishing.

Markdown is retained as source text. Clients must render it safely: text/semantic elements,
escaped HTML and vetted link protocols. The API neither fetches links nor executes embedded
HTML. Public objects omit drafts, row versions and editorial timestamps.

## Editorial access

Set `MNEMA_EVENTS_OWNER_ACCOUNT_ID` to the owner's exact canonical Identity account UUID
(`learning.events.owner-id`). Empty configuration denies editorial access to every account;
malformed nonempty configuration fails startup. Mutable usernames, emails, Telegram handles
and delegable administrator claims confer no access.

Every `/api/admin/events` request requires a valid bearer access token, the existing operation
scope (`learning.read` for GET/HEAD, `learning.write` for mutations), successful current
Identity `/userinfo` verification and the exact configured owner UUID. There is no positive
liveness cache. Revoked grants, Identity outages and mismatched subjects fail closed before
reading a command receipt. Cookies never authenticate these routes. All editorial responses
use `Cache-Control: private, no-store`; errors are always `no-store`.

- `GET /api/admin/events/access`: `{ "allowed": true }` for the owner; `403 ACCESS_DENIED`
  for another valid account.
- `GET /api/admin/events?cursor=…`: the same page shape and ordering, including drafts.
  Each event also has `published`, decimal-string `rowVersion`, `createdAt` and `updatedAt`.
  `publishedAt` is null until the event is first published.
- `POST /api/admin/events`: create from the command below; `201` and `{commandId,event}`.
- `PUT /api/admin/events/{eventId}`: replace from the same command; requires the strong
  `If-Match: "<rowVersion>"` precondition; `200` and `{commandId,event}`.
- `DELETE /api/admin/events/{eventId}?commandId=<UUID>`: delete with the same `If-Match`
  precondition; `204`. There is no request body.

```json
{
  "commandId": "00000000-0000-4000-8000-000000000002",
  "title": "Обновление редактора",
  "bodyMarkdown": "Упростили создание материалов.",
  "eventDate": "2026-10-07",
  "published": false
}
```

The command has exactly these fields. `commandId` is a canonical UUIDv4 or UUIDv7. Title is
nonblank, already trimmed, at most 160 Unicode code points and contains no control characters.
Body is nonblank, at most 16,000 code points, with control characters limited to tab/CR/LF.
The JSON request is bounded to 65,536 UTF-8 bytes. Dates are valid `YYYY-MM-DD` in years
0001–9999. Duplicate JSON keys, malformed UTF-8, NUL and unpaired surrogates are refused.

Create, replace and delete use the platform's global atomic command receipts. Retrying an
identical command identifier, payload, target and precondition returns the committed
acknowledgement with `Idempotency-Replayed: true`; create/replace replays omit ETag because
that historical acknowledgement need not describe the current row. Fresh successful
create/replace responses include the new strong ETag. Reusing a command identifier for a
different operation/payload/target/precondition returns `409 IDEMPOTENCY_CONFLICT`.
Clients retain the exact command and precondition for retries after an uncertain network
result, then refresh the editorial list.

Missing preconditions return `428 PRECONDITION_REQUIRED`; malformed ones return
`400 INVALID_REQUEST`; concurrent stale writes return `412 VERSION_CONFLICT`; an absent
target returns `404 RESOURCE_NOT_FOUND`. Other security/error bodies are the platform's
RFC 9457 Problem Details with stable `code`; input, SQL and stack traces are never returned.

## Verification and references

`EventsHttpIntegrationTest` exercises the real Learning HTTP filters and PostgreSQL with
controlled signed Identity keys/current-grant responses: public availability and draft
isolation, deterministic 50-item pagination, owner-only access, scopes, publication,
command replays, concurrent retries/CAS writes, deletion, revocation, calendar-date round trips
and bounded invalid input. `EventAdminAccessTest` verifies deny-by-default configuration and
exact owner identity; `EventRequestsTest` checks ambiguous headers/cursors and parser failures.

The separate public filter chain and ordered request rules follow
[Spring Security 7.1](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html).
Pagination uses explicit unique ordering and tuple comparison from
[PostgreSQL 18](https://www.postgresql.org/docs/18/functions-comparisons.html), avoiding
[offset instability](https://www.postgresql.org/docs/18/queries-limit.html).
Calendar dates use the driver's native
[JDBC 4.2 LocalDate mapping](https://jdbc.postgresql.org/documentation/query/#using-java-8-date-and-time-classes).
Errors reuse the existing platform advice, consistent with
[Spring MVC RFC 9457 support](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html).
