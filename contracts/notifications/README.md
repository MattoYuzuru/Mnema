# Notifications contract v1 (`notifications-v1`)

The durable notification center ("Inbox" and toasts). It is **general**, not AI-specific: generation, usage and media are its
first producers. **Status: contract only** — AI-07 ([#284](https://github.com/MattoYuzuru/Mnema/issues/284)) implements the
API, the bell and the toasts; AI-01 (#281) and AI-05 ([#288](https://github.com/MattoYuzuru/Mnema/issues/288)) are producers.

Authority: [AI generation platform §12](../../docs/architecture/ai-generation-platform.md) and the notification part of
[AI layer product contract §4](../../docs/product/ai-layer-2026-10.md). Machine-readable shapes and examples:
[`notifications.json`](notifications.json). Conventions follow the other contracts (private, decimal-string versions,
strong quoted ETag, RFC 9457).

## Model

`notification(notification_id, owner_id, kind, severity, params jsonb ≤4 KiB, route, dedupe_key, created_at, dismissed_at,
expires_at)` with `UNIQUE(owner_id, dedupe_key)`, and `notification_cursor(owner_id, read_upto)`.

- **No server-rendered prose, no personal data.** The client builds the sentence from `kind + params`; `params` holds only
  identifiers, counts, enums and timestamps (no email, names, titles, note or material text). Titles are fetched by the client.
- `seq` is a per-owner strictly increasing decimal string; ordering and the read watermark use it.
- **Producer** `NotificationPublisher.publish(...)` runs in the **same transaction** as the domain change, so there is no
  separate outbox. A second publish with an existing `(owner, dedupeKey)` is a no-op: the first stands and is never refreshed.
- **Retention** 30 days and 200 per account (the 201st evicts the oldest). Dismissed and expired rows are absent from reads.
- `route` is a key the client maps to its own URL: `WORKSHOP` (`deckId`, `sessionId`), `DECK` (`deckId`), `PLANS`, `NONE`.
  The server never sends a URL.
- A client ignores unknown kinds (shows nothing but they still count as unread).

## Kinds

Text is built by the client; severity drives the glyph and the toast duration (the text always names the outcome, never colour
alone).

| Kind | Severity | Route | Params | Producer |
|---|---|---|---|---|
| `GENERATION_READY` | INFO | WORKSHOP | `deckId`, `sessionId`, `sessionKind`, `artifactCount`, `readyCount` | generation |
| `GENERATION_PARTIAL` | WARNING | WORKSHOP | `deckId`, `sessionId`, `sessionKind`, `readyCount`, `failedCount` | generation |
| `GENERATION_FAILED` | ERROR | WORKSHOP | `deckId`, `sessionId`, `sessionKind`, `errorCode` | generation |
| `USAGE_LOW` | WARNING | PLANS | `percentUsed`, `remainingCredits`, `renewsAt`, `plan` | usage |
| `USAGE_EXHAUSTED` | WARNING | PLANS | `bucket`, `renewsAt`, `plan` | usage |
| `GENERATION_SESSION_EXPIRING` | WARNING | WORKSHOP | `deckId`, `sessionId`, `expiresAt`, `pendingCount` | generation retention |
| `MEDIA_PROCESSING_FAILED` | ERROR | DECK | `assetId`, `mediaKind`, `deckId`, `sessionId` | media processing |

`when` and `dedupeKey` per kind are in `notifications.json`: `GENERATION_READY`, `_PARTIAL` and `_FAILED` are keyed by session
and outcome; `USAGE_LOW` once per period; `USAGE_EXHAUSTED` once per bucket and period; `GENERATION_SESSION_EXPIRING` three days
before `expires_at`; `MEDIA_PROCESSING_FAILED` per asset.

## Operations

| Operation | Request | Success |
|---|---|---|
| List | `GET /api/notifications?limit=20&after=<seq>&cursor=…` + `If-None-Match` | `200` list envelope and `ETag`, or `304` without a body |
| Move the read watermark | `PUT /api/notifications/read-cursor` `{readUpto}` | `200 {readUpto, unreadCount}` |
| Dismiss | `DELETE /api/notifications/{notificationId}` | `204` (repeat is `204`; foreign or absent is the opaque `404`) |

- List envelope: `{items (newest first), unreadCount, readUpto, activeWork, nextCursor}`. `unreadCount` counts the whole
  center (items with `seq` greater than `readUpto`); the bell shows `99+` above 99. `activeWork` is the owner's generation
  sessions in `PLANNING` or `RUNNING`.
- `after=<seq>` returns only newer notifications for incremental polling; `cursor` pages to older ones (an extension: the
  architecture lists only `after`).
- The read cursor is a **monotonic maximum**: a value not above the stored one changes nothing; a value greater than the
  latest `seq` is `400 INVALID_REQUEST` (it could mark future notifications read). No `If-Match`, because the operation is
  commutative and idempotent.
- **Polling**: every 30-60 s, every 10 s while `activeWork > 0`, with `If-None-Match` so a `304` costs no body. The `ETag`
  is a strong opaque validator over the latest `seq`, `readUpto`, dismissals and `activeWork`. Toasts (6 s for "ready", 3 s
  for echoes, errors until closed; during Study only at a natural pause) are a client concern.

## Open questions

- Whether `activeWork` should also count media processing of generated assets.
- Per-account delivery preference ("during study: at pauses / at once / badge only") is a client setting; storing it on the
  server is not part of v1.
