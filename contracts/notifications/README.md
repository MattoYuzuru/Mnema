# Notifications contract v1 (`notifications-v1`)

The durable notification center ("Inbox" and toasts). It is **general**, not AI-specific: generation, usage and media are its
first producers. **Status: implemented by AI-07** ([#284](https://github.com/MattoYuzuru/Mnema/issues/284)): the API, the bell,
the inbox panel and the toasts; AI-01 (#281), AI-05 ([#288](https://github.com/MattoYuzuru/Mnema/issues/288)) and AI-14
([#295](https://github.com/MattoYuzuru/Mnema/issues/295)) are producers.

Authority: [AI generation platform §12](../../docs/architecture/ai-generation-platform.md) and the notification part of
[AI layer product contract §4](../../docs/product/ai-layer-2026-10.md). Machine-readable shapes and examples:
[`notifications.json`](notifications.json). Conventions follow the other contracts (private, decimal-string versions,
strong quoted ETag, RFC 9457).

## Model

`notification(notification_id, owner_id, seq, kind, severity, params jsonb ≤4 KiB, route, dedupe_key, created_at, dismissed_at,
expires_at)` with `UNIQUE(owner_id, dedupe_key)` and `UNIQUE(owner_id, seq)`, and
`notification_cursor(owner_id, last_seq, read_upto)`.

- **`seq` is allocated under the owner's `notification_cursor` row lock** (the row is created on first use):
  `UPDATE notification_cursor SET last_seq = last_seq + 1 WHERE owner_id = :id RETURNING last_seq`, in the publishing
  transaction. A global `bigserial` would be wrong: its values are assigned **before commit**, so a lower value can commit after
  a poller has already read a higher one, and that notification would never be returned.
- **No server-rendered prose, no personal data.** The client builds the sentence from `kind + params`; `params` holds only
  identifiers, counts, enums and timestamps (no email, names, titles, note or material text). Titles are fetched by the client.
- **Producer** `NotificationPublisher.publish(...)` runs in the **same transaction** as the domain change, so there is no
  separate outbox. A second publish with an existing `(owner, dedupeKey)` is a no-op: the first stands and is never refreshed.
- **Retention** 30 days and 200 per account (the 201st evicts the oldest). `expiresAt = createdAt + 30 days`, exactly. Dismissed and
  expired rows are absent from reads.
- `route` is a key the client maps to its own URL: `WORKSHOP` (`deckId`, `sessionId`), `DECK` (`deckId`), `PLANS`, `NONE`.
  The server never sends a URL. A kind whose producer picks one is `DYNAMIC` in the table below.
- A client ignores unknown kinds (shows nothing but they still count as unread).
- **Toast duration by severity:** `INFO` 6 s (the product's "ready" toast), `WARNING` 6 s, `ERROR` until closed. The 3 s toast of the
  product contract is the echo of the user's own action: a client toast, not a notification. During Study toasts wait for a natural
  pause. Severity is never the only signal: the text always names the outcome.

## Kinds

| Kind | Severity | Route | Params | Producer |
|---|---|---|---|---|
| `GENERATION_PLAN_READY` | INFO | WORKSHOP | `deckId`, `sessionId`, `sessionKind`, `plannedCount` | generation planner |
| `GENERATION_READY` | INFO | WORKSHOP | `deckId`, `sessionId`, `sessionKind`, `artifactCount`, `approvableCount` | generation |
| `GENERATION_PARTIAL` | WARNING | WORKSHOP | `deckId`, `sessionId`, `sessionKind`, `approvableCount`, `failedCount` | generation |
| `GENERATION_FAILED` | ERROR | WORKSHOP | `deckId`, `sessionId`, `sessionKind`, `errorCode` | generation |
| `USAGE_LOW` | WARNING | PLANS | `bucket`, `percent`, `unit`, `remaining`, `renewsAt`, `plan` | usage |
| `USAGE_EXHAUSTED` | WARNING | PLANS | `bucket`, `window`, `renewsAt`, `plan` | usage |
| `GENERATION_SESSION_EXPIRING` | WARNING | WORKSHOP | `deckId`, `sessionId`, `expiresAt`, `pendingCount` | generation retention |
| `MEDIA_PROCESSING_FAILED` | ERROR | DYNAMIC | `assetId`, `mediaKind`, `deckId`, `sessionId`, `artifactId`, `slotKey`, `reason` | media processing and generation media steps |

Semantics (details and dedupe keys per kind in `notifications.json`):

- **Approvable now.** `approvableCount` is the number of artifacts that can be approved at this moment: `PROPOSED` with every media
  slot `READY` or `REMOVED` (not `STALE`, not `REVISING`). The session summary and `GENERATION_READY`/`GENERATION_PARTIAL` use the
  same definition.
- `GENERATION_READY` waits for the media of a session: with `::image`/`::audio` slots it is published once, when the last slot ends (a failed slot counts as ended; `approvableCount` is then
  lower than `artifactCount`). A text-only session publishes it when the text settles.
- `GENERATION_FAILED` is produced when no artifact is approvable and at least one failed, or the plan failed; **never** for a user
  cancellation. Its `errorCode` is the most frequent artifact error code of the session.
- `USAGE_LOW` fires when a bucket's used share crosses 80, 90 and 100 percent (`percent` is the integer floor), once per bucket,
  period and threshold. `USAGE_EXHAUSTED` is keyed by bucket, window kind and **window instance** (its start), so the weekly Free credit
  window fires every week, including the last window that opens with the final portion. `GENERATION_SESSION_EXPIRING` fires three days before expiry and its key includes the `expiresAt` date.
- `MEDIA_PROCESSING_FAILED` is also produced for provider-step media failures of a generation slot; the route is `WORKSHOP` when
  `sessionId` is set, `DECK` when only `deckId` is set, else `NONE`.

## Operations

| Operation | Request | Success |
|---|---|---|
| List | `GET /api/notifications?limit=20&after=<seq>&cursor=…` + `If-None-Match` | `200` list envelope and `ETag`, or `304` without a body |
| Move the read watermark | `PUT /api/notifications/read-cursor` `{readUpto}` | `200 {readUpto, unreadCount}` |
| Dismiss | `DELETE /api/notifications/{notificationId}` | `204` (repeat is `204`; foreign or absent is the opaque `404`) |

- **List order.** Without `after` the list is the **newest-first** page (seq descending) and `cursor` pages to older ones. With
  `after=<seq>` it is the **catch-up** list: notifications with a greater `seq` in **ascending** order (oldest first), `limit` at
  a time, `nextCursor` continues. `after` together with `cursor` is `400 INVALID_REQUEST`.
- List envelope: `{items, unreadCount, readUpto, activeWork, nextCursor}`. `unreadCount` counts the whole center (items with `seq`
  greater than `readUpto`); the bell shows `99+` above 99. `activeWork` is the owner's generation sessions in `PLANNING` or
  `RUNNING` (the system is working): a `PLAN_READY` session waits on the owner, who is told with `GENERATION_PLAN_READY`, so it is not counted.
- The read cursor is a **monotonic maximum**: a value not above the stored one changes nothing; a value greater than the latest
  `seq` is `400 INVALID_REQUEST` (it could mark future notifications read). No `If-Match`, because the operation is commutative
  and idempotent.
- **Polling**: every 30-60 s, every 10 s while `activeWork > 0`, with `If-None-Match` so a `304` costs no body. The `ETag` is a
  strong opaque validator over the latest `seq`, `readUpto`, dismissals, `activeWork` and the **earliest expiry boundary**, so a
  notification that expires invalidates it.

## Open questions

- Whether `activeWork` should also count media processing of generated assets.
- Per-account delivery preference ("during study: at pauses / at once / badge only") is a client setting; storing it on the
  server is not part of v1.
