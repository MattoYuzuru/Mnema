# Private Deck metadata API

Learning context path `/api`; authenticated Identity subject is the only owner
input. This contract belongs to #188/#74, not the legacy core Deck API. JSON names
and examples in `metadata.json` are shared frontend/backend fixtures.

| Operation | Request | Success |
|---|---|---|
| Create | `POST /api/decks`, command body | 201, Location, acknowledgement |
| Own list | `GET /api/decks?limit=20&cursor=...` | 200, items + nullable nextCursor |
| Detail | `GET /api/decks/{deckId}` | 200, Deck + ETag |
| Replace metadata | `PATCH /api/decks/{deckId}`, command body + If-Match | 200, acknowledgement |
| Delete | `DELETE /api/decks/{deckId}` + If-Match | 204; tombstone, see below |

PATCH uses `application/json` and replaces **both** metadata fields; it is not
JSON Merge Patch and cannot modify identity, ownership, visibility or membership.
Requests reject unknown fields, duplicate keys, invalid UTF-8/Unicode/NUL, missing
fields and bodies over8192 bytes (stream read stops at8193). Title is nonblank,
at most200 Unicode code points/800 UTF-8 bytes; description at most4096 bytes.
Text is preserved, not trimmed or interpreted as HTML. Both fields are required.
Command IDs are canonical UUIDv4/v7 strings; entity IDs are canonical non-nil IETF
UUID strings with a registered version. Hex case is accepted and normalized for
identity/hash purposes, not treated as a different command.

If-Match is exactly one quoted canonical decimal version0..9223372036854775806.
No weak validator, wildcard, list, duplicate header or leading zeros. Missing428,
malformed400, stale412. `rowVersion` and `sequence` are decimal **strings**; the
ETag is the quoted rowVersion. Detail has one representation, private/no-store.

Receipts bind actor, command type, deck target, expected version and metadata.
Identical retries return the original acknowledgement/status without publishing
again, after current authorization. Changed input with a used commandId returns409.
Fresh writes include the returned version's ETag. Replayed writes omit ETag and
set `Idempotency-Replayed: true`: the original acknowledgement is not a current
Deck read. Refresh with GET before further editing; never apply an old receipt
over a newer client version. [RFC9110 conditional semantics](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1)
informs this replay distinction; [RFC5789](https://www.rfc-editor.org/rfc/rfc5789.html)
informs conditional partial-resource updates.

Delete requires the strong Deck version in `If-Match` (428/412 as above) and tombstones
the Deck: deck-scoped reads then return 404, while immutable revisions, study evidence
and account-owned media are retained. Physical purge is a separate retention operation.

Absent and foreign private Deck IDs both return the same404. Reads and writes
require existing learning.read/learning.write scopes and fresh Identity validation.
No response exposes reuse scopes, physical roots or pins.

List defaults to20/max100 and returns `limit+1`-derived continuation in descending
`(createdAt, deckId)` order. Cursor is opaque, bounded and preserves microseconds;
it never grants access. Metadata changes do not reorder entries. Pagination is
not a snapshot: refresh to see newer creations before the current cursor.
Creation starts sequence/rowVersion0 and two empty content roots. Each metadata
save produces a new immutable revision, reuses both roots and preserves old history.
Each POST allocates a fresh physical scope. The schema also permits separately
authorized Deck namespaces to retain the same lineage's roots; scope is not a
unique Deck identity and never grants read/write permission. No fork API is exposed.

Standard failures use the existing ProblemDetail `type/title/status/detail/instance/code`.
Relevant codes: INVALID_REQUEST400, AUTHENTICATION_REQUIRED401, ACCESS_DENIED403,
RESOURCE_NOT_FOUND404, IDEMPOTENCY_CONFLICT409, VERSION_CONFLICT412,
PRECONDITION_REQUIRED428, IDENTITY_UNAVAILABLE503. Error text never echoes input.

Implementation status is recorded in the #188 evidence, not implied by these fixtures.

## Deck hub (#285)

The Deck page is a hub: description, five statistics widgets, the material list and
bulk actions. Exact examples and executable invariants are in [`hub.json`](hub.json). All
routes are private/no-store, owner-scoped (absent and foreign Deck, member or revision are the
same opaque 404) and need `learning.read` (GET) or `learning.write` (POST). The hub loads Deck
detail, the item list and insights as **three independent requests**: a failed insights request
shows an inline "statistics unavailable, retry" state and never hides the Deck, the list or any
action. There are no vanity metrics (opens, streak, time, mastery percent); every widget ends in
an action.

| Operation | Request | Success |
|---|---|---|
| Insights | `GET /api/decks/{deckId}/insights` | 200, one snapshot |
| Ordered/sorted list | `GET /api/decks/{deckId}/items?sort=ordinal\|exerciseCount&include=exerciseCount&limit=&cursor=` | 200, page + nullable cursor |
| Set or clear «Эталон» | `POST /api/decks/{deckId}/items/{memberKey}/exemplar` | 200, acknowledgement |
| Consequences of a deletion | `POST /api/decks/{deckId}/items/deletions/preview` | 200, counts (no state change) |
| Bulk delete | `POST /api/decks/{deckId}/items/deletions` + `If-Match` | 200 `COMPLETED` or `PARTIAL` |

### Counting rules

- **Material** = a current deck-local `LearningItem` (a head item); deleted ones never count.
- **exerciseCount** of a material = current-head exercises that **assess** that material
  (`ASSESSED` binding) and are `enabled`. Disabled exercises are authored but never issued by Study,
  so they do not count as coverage. Exercises that only quote the material as `CONTEXT` do not count.
- **State** of a material is exactly the `study-progress` state
  ([`contracts/study`](../study/README.md#progress-and-retention)): `NOT_STARTED`, `LEARNING`, `DUE`,
  `ON_TRACK`, computed at the read clock with the same rule (a material without enabled exercises is
  `NOT_STARTED`). Insights never defines a second state vocabulary.

### Insights

`GET /api/decks/{deckId}/insights` has no parameters and returns, for the current Deck head:

| Field | Meaning | Widget action |
|---|---|---|
| `deckId`, `deckRevisionId`, `deckVersion`, `asOf` | the snapshot it describes; `asOf` is the read clock (UTC) | the client compares `deckRevisionId` with the list to notice staleness |
| `timezone` | IANA zone used for day boundaries | none |
| `coverage {total, withExercises, withoutExercises}` | materials with at least one enabled exercise | «Показать» (list `sort=exerciseCount`) and AI exercises |
| `states {NOT_STARTED, LEARNING, DUE, ON_TRACK}` | count of materials per state; always all four keys; the sum is `coverage.total` | «Учить» (scheduled session) |
| `dueByDay[7]` | `{date, materials}` for seven consecutive local dates; a material falls in the day of its nearest `nextDue` (as in `study-progress`). Entry 0 is today and also includes everything already due, so it counts materials that can be studied now | start a session now or name the next useful day |
| `exercisesByMechanic` | all seven mechanics as keys (zero included), enabled current exercises each | add or generate an exercise of an unused mechanic |
| `captures {open, oldestOpenCreatedAt}` | this Deck's notes that are neither archived nor converted; `oldestOpenCreatedAt` is `null` when none | open the unprocessed notes |

Materials with no scheduled due time (`NOT_STARTED`) are not in `dueByDay`. The sum of
`exercisesByMechanic` equals the sum of the per-material `exerciseCount`. Everything derives
from current projections; nothing reads attempts or evidence, so insights add no learner metrics.

**Time zone.** The Learning service already receives the account zone in the authenticated
`zoneinfo` claim (used by Study for the local study date; clients never submit a zone). Insights
uses the same claim; when it is absent or invalid it falls back to `Europe/Moscow`, the owner-chosen
calendar zone of the usage contract ([`contracts/usage`](../usage/README.md)), and always echoes the zone it used.
Study's own session-date fallback is UTC; aligning the two is a separate follow-up (no Study change here).
Day boundaries here only bucket the widget and never decide what a session issues.

**Budget.** One request, one read-only transaction under the existing 10 s timeout. The target is a
deck of 10 000 materials and 30 000 exercises answered in well under one second on the test
database; the integration test records the measured time.

### Item list: sort and exercise counts

The list of the [Item contract](../items/README.md) is extended additively:

- `sort=ordinal` (default) is the existing authoring order and cursor.
- `sort=exerciseCount` orders by `exerciseCount` **ascending** (materials without exercises first),
  ties by `ordinal` ascending, so the order is total and stable. Every entry still carries its true
  `ordinal` (the number the hub shows) and `total` is unchanged.
- `include=exerciseCount` (a comma list, today only that token) adds `exerciseCount` to every
  summary without a per-item request; `sort=exerciseCount` always includes it. Unknown or duplicate
  tokens, unknown `sort` values and repeated parameters are 400.
- Every summary carries `exemplar: boolean`, and the list carries `exemplars {count, limit}` so a
  client can disable the star at the limit. The direct item read also carries `exemplar`.
- Cursor: opaque and bounded, it names the sort and binds the Deck revision. A cursor used with another
  sort is 400; with a stale revision 412, exactly like the ordered list, so pages never mix two
  snapshots (exercise changes advance the Deck revision, so counts cannot drift mid-scan). Toggling
  «Эталон» is **not** a revision and never invalidates a cursor. Page size defaults to 20, max 100.
  The sorted page is derived with one bounded statement over the Deck's current projections;
  cost grows with the Deck size (limit 100 000) but never with the page number.

### «Эталон»

A deck-local, non-revision flag on a `LearningItem`: setting it creates no Deck revision and no
publication, does not change `itemVersion` or `updatedAt`, and does not reorder anything. At most
**10** materials per Deck are exemplars; deleting a material drops its flag.

`POST .../items/{memberKey}/exemplar` with `{commandId, expectedItemRevisionId, exemplar}` where
`exemplar` is the **desired** value (a set, not a toggle, so retries and double clicks are
harmless). Setting the value the item already has is a successful no-op (`changed: false`).

- **Precondition.** `expectedItemRevisionId` is the item revision the user saw when starring. The
  star endorses that content (the style card is derived from it), so an item saved in the meantime is
  412 `VERSION_CONFLICT` and the client refreshes. A Deck `If-Match` is deliberately not used: an
  unrelated edit elsewhere in the Deck, or another star, must not fail a star click. Missing field
  428, malformed 400.
- **Limit.** Setting `true` on a non-exemplar when the Deck already has 10 is
  422 `EXEMPLAR_LIMIT_REACHED` with `limit: 10`. The count is enforced under a per-Deck lock, so
  concurrent requests cannot exceed it. Clearing never fails on the limit.
- **Receipts.** Bound to actor, command type, Deck, member, desired value and expected revision. An
  exact retry returns the original acknowledgement with `Idempotency-Replayed: true`; a changed
  body with a used `commandId` is 409 `IDEMPOTENCY_CONFLICT`. A replayed `exemplarCount` is a
  historical value; clients re-read the list. The response has no ETag (no Deck version changes).
- Check order: authentication and ACL (404), receipt replay, validation (400), 428/412, 422.

### Bulk delete

`POST .../items/deletions/preview` takes the **selection** `{expectedDeckRevisionId, itemIds | allInDeck + except}`
and returns `{deckId, deckRevisionId, deckVersion, materialCount, affectedExerciseCount}`: exactly what the
hold-to-delete text needs («удалит N материалов и M упражнений, история занятий сохранится»).
It is a POST only to carry the selection; it writes nothing, stores no receipt and is repeatable.
`affectedExerciseCount` is deliberately a different count from the list's `exerciseCount`: it counts ALL current
exercises (enabled **and** disabled) that assess a selected material, because the user is told what the
deletion affects. The selection is resolved against the Deck
revision named by `expectedDeckRevisionId`; a stale revision is 412.

`POST .../items/deletions` with `If-Match` (Deck row version) and body
`{commandId, expectedDeckRevisionId, itemIds | allInDeck + except}`:

- Exactly one selection form. `itemIds`: 1..100 distinct member keys. `allInDeck: true`: optional
  `except` of up to 500 distinct member keys; the resolved selection (members of that revision
  minus `except`) must not be empty. Any key outside the named revision's membership is the opaque 404.
  A resolved selection above **500** is 422 `BULK_SELECTION_TOO_LARGE` with `limit: 500`
  (the preview returns the same, so the client can disable the action before the hold).
- The server deletes through the existing `items/publications` machinery, in snapshot ordinal order,
  in chunks of at most **100** `delete` changes. **Each chunk is one atomic publication**: it
  deletes all of its materials or none. It never applies half a chunk. It uses the server-read
  current item revisions and ordinals, so the client sends neither; the Deck revision pin guarantees
  that nothing it did not see is deleted.
- Chunk 1 expects the client's `If-Match` and `expectedDeckRevisionId`; each later chunk expects the revision the
  previous chunk produced. A stale precondition on chunk 1 is the whole request failing with 412
  `VERSION_CONFLICT`: **nothing was deleted**. A foreign publication after chunk 1 stops the run
  and is reported as partial.
- Result `{commandId, deckId, status, requested, deleted, notDeleted[], stopReason, deckRevisionId, deckVersion,
  memberCount}`. `status` is `COMPLETED` (`notDeleted` empty, `stopReason: null`) or `PARTIAL`
  (`0 < deleted < requested`, `stopReason: "VERSION_CONFLICT"`). `notDeleted` lists the member keys
  that were **not attempted**; they still exist unchanged. HTTP 200 in both cases, because
  the command had effects; fresh results carry the ETag of the last applied Deck version. A selection of at
  most 100 materials can therefore never be partial.
- **Client reporting.** `COMPLETED`: «Удалено N материалов». `PARTIAL`: «Удалено K материалов. M не
  тронуты: колода изменилась в другой вкладке. Обновите список.» and keep the `notDeleted`
  materials selected after the refresh. 412: «Колода изменилась в другой вкладке. Ничего не удалено.»
  After `PARTIAL` a retry needs a **new** `commandId` and the returned `deckRevisionId`/`deckVersion`.
- **Receipts and crashes.** The receipt binds actor, Deck, the version/revision pins and the normalized
  selection; the full result, including a `PARTIAL` one, is stored once at the end and replayed
  verbatim with `Idempotency-Replayed: true` and no ETag. Every chunk uses a publication command ID
  derived deterministically from `(commandId, chunk index)` over a selection fixed by the named
  revision, so a retry after a crash between chunks replays the finished chunks and continues,
  and never deletes twice or conflicts with its own earlier chunks.
- **Consequences.** Same as deleting one material through publications: materials leave Browse and
  current progress; exercises assessing them are no longer issued; their exemplar flags are dropped;
  exercise definitions, immutable material revisions, attempts and study history are retained.

### Codes

Existing codes keep their status/title/detail. New: `EXEMPLAR_LIMIT_REACHED` (422) and
`BULK_SELECTION_TOO_LARGE` (422), each with the numeric extension member `limit` (the typed
`ProblemExtension` in `platform.api`). They are registered here and in `ApiErrorCode`, not in
[`contracts/generation/errors.json`](../generation/errors.json): that registry is scoped to the operations
of `contracts/generation/http.json`. A missing `expectedDeckRevisionId` or `expectedItemRevisionId` is 428.
Problem text never echoes titles, content or identifiers.

## Public read of a shared deck (Share/7, #429)

Someone else's deck is read through its **public code** (10 characters of base58, never the deck id) on a
read-only path that serves the **published revision**. Exact examples and executable invariants are in
[`public-read.json`](public-read.json); the architecture is
[community decks §6](../../docs/architecture/community-decks.md#6-доступ-и-публикация-cd-3-cd-4). Nothing else changes:
every existing deck, item, exercise, draft, capture, study and generation route stays owner-only and answers
the opaque 404 for any other account, whatever the deck's level. The private media routes keep their owner rule and add exactly one
reader: the owner of a copy, for the media his own deck's heads reference ([media of a shared deck](#media-of-a-shared-deck-share9-431)).

| Operation | Request | Success |
|---|---|---|
| Summary | `GET /api/public/decks/{code}` | 200, title, description, counts, `access`, `ownerId`, `slug` (PUBLIC only) |
| Materials | `GET /api/public/decks/{code}/items?limit=&cursor=` | 200, page + nullable `nextCursor` |
| One material | `GET /api/public/decks/{code}/items/{memberKey}` | 200, native document as pinned by the published manifest |
| Exercises | `GET /api/public/decks/{code}/exercises?limit=&cursor=` | 200, page of learner-safe summaries + nullable `nextCursor` |
| Media | `GET /api/public/decks/{code}/media/{assetId}` | 200, `{assetId, state, playback, poster}`: signed links of derived variants ([below](#media-of-a-shared-deck-share9-431)) |

- **Bearer is optional.** No token is a guest; a valid token (scope `learning.read`) identifies the account and is checked
  against Identity exactly like the private API; a present but invalid Bearer token (invalid, expired or revoked) is 401 and is
  never treated as a guest. Only GET and HEAD exist; no cookie, query or form token is read. Answers are `Cache-Control: no-store` and
  `Vary: Authorization` (the `access` field depends on who asks).
- **Who sees what** (`DeckAccess`, by code): the owner at any level (`access: OWNER`); `PUBLIC` and `LINK` decks are readable
  by guests and any account (`PUBLIC` / `LINK`); an `INVITE` deck by accounts with a grant (`GRANTEE`), and everybody else gets
  **403 `DECK_INVITE_ONLY`** with a body that says nothing about the deck. A private deck, an unknown code, a code that was
  rotated away (the level was lowered), a deleted deck and a deck that was never published are one and the same **404**
  `RESOURCE_NOT_FOUND`. The code of a deck is changed on ANY move to a more restrictive level (`PUBLIC` > `LINK` > `INVITE` >
  `PRIVATE`, `PUBLIC` to `LINK` included); raising the level keeps it. Old links stop working at once.
- **`ownerId` is exposed on purpose.** The UI needs it to ask Identity for the author's card; the card (name, avatar) is
  consent-gated in Identity (Share/1), Learning exposes only the opaque account id.
- **Published, not head.** Title, description, materials, exercises and counts are those of the published revision.
  Edits the owner makes afterwards are invisible here until the next publication; a member that is not in the published
  manifest is 404 even when the deck's head has it. A cursor is bound to the published revision: after a republication it
  is 412 `VERSION_CONFLICT` and the client restarts from the first page. Page size defaults to 20, maximum 100; cursors are
  opaque, malformed ones are 400.
- **No editing metadata, no answers.** Responses carry no deck id, deck revision or version, draft ids, journal or origin
  data. An exercise is `{exerciseId, exerciseRevisionId, ordinal, type, enabled, prompt}`: `prompt` is a plain-text summary of the question
  (≤ 200 code points, `null` if the question has no plain text); the objective's authoring title is not part of the page, and answer
  keys, options, references, transcripts, bindings and evaluator policies never leave the server. The document is returned as stored, so its
  image, audio and video nodes name asset ids; the media itself is read through the media route below, never through the owner's routes.
- **Limits.** `429 RATE_LIMITED` with `Retry-After` (the real remaining time of the window that refused): a guest is counted per
  client network (IPv4 address, IPv6 /64) and, for IPv6, all guests of one /48 together; guests that find the guest table full share
  one overflow bucket; a signed-in viewer is counted per account in a table of its own, so a flood of guests never starves an
  account, and an account is admitted before Identity is asked about its token (one token cannot drive unlimited Identity round trips) (defaults 120 / 600 / 600 / 600 a minute per instance). Every request counts, found or not.
- **Bulkhead.** At most 3 public reads run at once per instance (guests at most 2 of them, so one place stays free for a signed-in viewer); the next one is refused before any lookup with **503
  `PUBLIC_READ_BUSY`** and `Retry-After: 1` (house schema, member `retryAfter`). Clients retry after the delay.
- **Kill switch.** `learning.community.public-routes.enabled` is **false** by default: every route answers 404 as if it did
  not exist (the link previews, the access requests, `POST /api/invites:redeem` and the creation of copies `POST /api/decks/forks` are children of the same switch). (A bearer that is present and invalid is still 401, because authentication runs first.)
- Failure codes: `DECK_INVITE_ONLY` (403, new), `RESOURCE_NOT_FOUND` (404), `INVALID_REQUEST` (400), `VERSION_CONFLICT` (412),
  `RATE_LIMITED` (429), `PUBLIC_READ_BUSY` (503, new), `AUTHENTICATION_REQUIRED` (401), `ACCESS_DENIED` (403, a valid token without `learning.read`).

### Media of a shared deck (Share/9, #431)

`GET /api/public/decks/{code}/media/{assetId}` serves the media of the MATERIALS of the PUBLISHED revision, to guests and to any account, on the same
optional-bearer chain as the other public routes. Examples and executable shapes: the `media` section of [`public-read.json`](public-read.json).

```json
{ "assetId": "3c9f1a52-…", "state": "READY",
  "playback": { "url": "https://…", "expiresAt": "2026-10-11T10:30:00Z", "mimeType": "image/webp" },
  "poster":   { "url": "https://…", "expiresAt": "2026-10-11T10:30:00Z", "mimeType": "image/webp" } }
```

- **Exactly four members.** `state` is `READY`, the state of an upload that is still going (`PENDING_UPLOAD`, `VERIFYING`, `PROCESSING`) or `UNAVAILABLE` (a rejected, failed
  or deleted asset: a non-owner is not told which); `playback` and `poster` are `null` unless `state` is `READY`, and `poster` is `null` for an asset with no poster or thumbnail
  variant (audio). There is **no** download member, no
  original, no object key and no EXIF: a non-owner receives **derived variants only** (playback, poster, thumbnail). The media worker re-encodes every variant with
  `-map_metadata -1` and the pinned FFmpeg 6.1.1 carries no EXIF/GPS from a JPEG into WebP; the worker's tests prove it on a JPEG with GPS, an MP4 with a location and an
  MP3 with tags. A link is signed offline for `learning.community.public-media.url-ttl` (default ten minutes, shorter than the owner's one-hour playback links), is not stored, and the answer is `Cache-Control: private, no-store`
  with `Vary: Authorization`.
- **Who gets a link** is one rule, fail closed: `DeckAccess` grants a level for the deck behind the code (a published revision at the level: `PUBLIC`, `LINK`, an
  invitation with a grant, the owner at any level) **and** the asset is in the deck's **published set** (`published_media`: the assets that the *materials* of the published
  revision reference, maintained by the publication command). The media of **exercises is not offered**: the public view shows exercises as plain-text prompts, and an exercise pin
  does not say whether an asset belongs to the question or to the answer.
  Everything else is the same `404 RESOURCE_NOT_FOUND`: an unknown or rotated code, a private deck, an invitation-only deck for a viewer without a grant (404 here, unlike the
  reads), a deck without a published revision, an asset the owner added after the last publication, an asset a republication removed, an exercise-only asset, an asset of another deck, and an
  unknown asset id. A malformed asset id is 400. Republishing changes the set in the same transaction as the published pointer; a downgrade to `PRIVATE` keeps it.
- **Limits.** `429 RATE_LIMITED` with the real `Retry-After`. The limits count **requests for signed links, not bytes**: the object store serves the bytes after a link is handed
  out, so they bound how fast links are minted, not the egress itself (a CDN for derived variants stays a separate decision). A guest is counted per client network (IPv4
  address, IPv6 /64; unknown codes and assets count too) at 240 a minute, for IPv6 all guests of one /48 together at 1200 (a request must pass both), and all guests
  together per deck at 3000 a minute; a signed-in viewer is limited per account by the public chain and is never charged to the deck. The bulkhead of the other public reads
  applies (`503 PUBLIC_READ_BUSY`).
- **Kill switch.** `learning.community.public-media.enabled` is **false** by default: the route answers 404 as if it did not exist. It also needs
  `learning.community.public-routes.enabled`. Outcomes are counted on `mnema_public_media_requests_total{outcome}`.
- **The owner of a copy** reads the media of his own deck on the private routes (`GET /api/media-assets/{assetId}/playback`, `…/variants/{variantId}/download`
  and the offline manifest `GET /api/decks/{deckId}/media-manifests/current`) for every asset his deck's **head** revisions reference (materials and exercises), whoever owns the asset: derived
  variants only, `download` is `null` and the manifest lists no `original` variant for an asset he does not own. A deck that is deleted, or a head that no longer holds
  the asset, stops serving it. Editing a material of a copy may keep an inherited asset (an asset the deck's heads already reference); any other asset must be the
  editor's own, and an asset of another account that no head holds is `404` on save.

## Link previews (Share/14, #436)

The web server puts the card of a deck into the head of `index.html` for `/d/{code}/{slug}` (nginx SSI, no SSR), so Telegram, VK
and search engines see the title, description and author of a PUBLIC deck. Exact examples and executable invariants are in
[`public-meta.json`](public-meta.json); operations: [link previews](../../docs/operations/link-previews.md); the decision is
[community decks §9](../../docs/architecture/community-decks.md#9-ссылки-og-rate-limits-cd-10).

| Operation | Request | Success |
|---|---|---|
| Deck card | `GET\|HEAD /api/public/meta/decks/{code}` | 200, `{kind, title, description, canonical, robots, image, author}` |
| Deck head | `GET\|HEAD /api/public/meta/decks/{code}/head` | 200, `text/plain;charset=UTF-8` fragment; `X-Mnema-Preview`, and `X-Mnema-Canonical` for a PUBLIC deck |
| Author card | `GET\|HEAD /api/public/meta/profiles/{username}` and `/head` | 200, always the generic card (`kind: GENERIC`) and no call to Identity until the author page `/u/` exists (epic 3) |

- **Same chain, same switch.** The routes are in the optional-bearer public chain of the deck reads with the same limiter, bulkhead and kill
  switch (`404` while public routes are off); the viewer is always a guest, a token changes nothing, and a present but invalid one is 401 like there.
  Success is `Cache-Control: public, max-age=300`; problem answers stay `no-store`.
- **Not an oracle.** Only a PUBLIC deck with a published revision gets its own card. EVERY profile route is the generic card too, whatever the login (a known public profile, a hidden or unknown one): Identity is not called, because its by-username lookup has one 15-minute allowance per caller address that grants by login and profile complaints share, and a guest-reachable route must not spend it. LINK, INVITE, private, rotated, deleted, unknown, malformed
  and never-published codes all get **the same** `GENERIC` card (`noindex`, `canonical: null`, `author: null`), byte for byte, with `200`; its `/head`
  answer also carries `X-Robots-Tag: noindex`, which the web server puts on the page.
- **Text, not markup.** Every JSON string is plain text on one line (whitespace and control characters become one space, bidirectional formatting
  characters are dropped; title at most 120 code points, description at most 200, cut on a word boundary with `…`; blank falls back to `Колода` /
  `Колода на Mnema.`). The `/head` fragment is the only markup: fixed `title`, `meta` and `link` tags with every value escaped (`& < > " '`), nothing
  that can open a tag, a comment or an SSI directive. It is served as `text/plain;charset=UTF-8` with `Content-Security-Policy: default-src 'none';
  sandbox` because it is reachable on the application origin: a browser shows it as text and never runs it, while the web server includes its bytes.
- **Author.** `author {username, displayName|null}` only when Identity publishes the card (the owner's consent, fields per consent; `bio` is for the profile card of epic 3 and unused today). Learning asks Identity with a 150 ms deadline of its own and caches a card or its absence 60 s and a failure 5 s; a failure
  leaves `author: null` and never blocks the card.
- **Canonical and redirect.** `canonical = {origin}/d/{code}/{slug}` (`origin` from `MNEMA_PUBLIC_BASE_URL`). The `/head` answer of a PUBLIC deck carries
  `X-Mnema-Canonical: /d/{code}/{slug}`; the web server compares it with the path it was asked for and answers one `301` on a difference (a missing or
  wrong slug), keeping the query string. No query parameter changes the answer, so a cache in front keys it by the code alone and one cached answer
  serves both the redirect decision and the card. Every `/head` answer has `X-Mnema-Preview: card|generic`; the generic card also has `X-Robots-Tag: noindex`.
- Failure codes are those of the public reads: `RESOURCE_NOT_FOUND` (404, switched off), `RATE_LIMITED` (429), `PUBLIC_READ_BUSY` (503), `AUTHENTICATION_REQUIRED` (401).

## Publication and the topic directory (Share/8, #430)

The owner decides **who may read the published revision** of a deck, **when** the readers' state moves, and
describes the deck for the catalog. Exact examples and executable invariants are in
[`publication.json`](publication.json); the architecture is
[community decks §6](../../docs/architecture/community-decks.md#6-доступ-и-публикация-cd-3-cd-4). Everything is owner-only
(absent, foreign and deleted decks are the same opaque 404), private/no-store, and needs `learning.read` (GET) or
`learning.write` (PUT). **Publishing never creates a deck revision and never changes `deck.row_version`**: an
open editor with its own `If-Match` still saves (no 412) after the owner publishes or changes the level.

| Operation | Request | Success |
|---|---|---|
| State | `GET /api/decks/{deckId}/publication` | 200, state + ETag |
| Save | `PUT /api/decks/{deckId}/publication` + `If-Match`, command body | 200, acknowledgement |
| Topic directory | `GET /api/topics` | 200, two-level tree, `Cache-Control: private, max-age=3600` |

### State

`{visibility, publicCode, link, publishedRevisionId, publishedAt, headRevisionId, unpublishedChanges, metadata,
suggested, releaseNote, requestsEnabled, checklist, rowVersion}`.

- `visibility` is `PRIVATE | INVITE | LINK | PUBLIC` (the deck JSON of `metadata.json` carries the same level in
  lowercase). A deck that never left «Приватная» has no row and reports `PRIVATE`, null code and link,
  `unpublishedChanges: null`.
- `link` is `/d/{code}/{slug}` for `PUBLIC`, `/d/{code}` for `LINK` and `INVITE`, `null` for `PRIVATE`
  (`publicCode` is `null` for `PRIVATE` too: the code is the credential of a shared link).
- `unpublishedChanges` = distinct materials + distinct exercises changed in the deck journal after the published
  revision. `headRevisionId ≠ publishedRevisionId` with `0` changes means the title or description changed only.
  The hub text is «Изменения для учеников не опубликованы (N) — Опубликовать обновление».
- `metadata` = `{topicId, contentLanguage, targetLanguage, level, tags}`. `topicId` is a leaf of the topic directory
  (a top-level topic without children, «Другое», is a leaf). Languages are the lowercase BCP 47 primary subtag
  (`^[a-z]{2,3}$`): `contentLanguage` is the language of the deck's text, `targetLanguage` the language being learned.
  `level` is `null` or one of the single closed list `A1 A2 B1 B2 C1 C2 BEGINNER INTERMEDIATE ADVANCED` (CEFR for
  languages and exams, the three words for the rest; the server does not tie a level to a topic). `tags`: at most 5, each
  1–32 code points, trimmed, NFKC, lowercase, inner whitespace collapsed, letters/digits/space/`-_+#.` only; duplicates
  after normalization are 400. The response carries the normalized form.
- `suggested` = `{contentLanguage, topicIds}` is the server's guess, never stored: a dependency-free detector over the
  title, the description and the cached titles of up to 20 materials (script ranges plus small stopword lists for
  `ru en es de fr it pt`; kana → `ja`, Hangul → `ko`, Han → `zh`; `null` when unsure) and alias matches of the title and
  description against the directory (at most 3 leaf topics, best first). «Язык — определяется автоматически, можно
  поправить»: the client pre-fills the form with it.
- `releaseNote` is the «Что нового» of the last publication (at most 500 code points). `requestsEnabled` is the
  author's switch for access requests (default `true`; Share/13 reads it).
- `checklist` = `{description, topic, language, publicProfile, catalogThreshold, blockedMedia}`: booleans for the items
  of the «Публичная» checklist as the stored state stands, `catalogThreshold {met, materials, exercises, needMaterials: 10,
  needExercises: 1}` (informational: below it a public deck is still public by link and in the profile; the catalog epic
  owns the ranking rule) and `blockedMedia`, the materials (`memberKey`) and exercises (`exerciseId`) of the head
  that use stock media under a non-commercial license (`reason: "NC_LICENSE"`, at most 50 entries).
  `publicProfile` mirrors the Identity claim `mnema_public_profile` (consent plus a login) read from the same
  `/userinfo` call that authorizes the request; Learning reads no Identity table.
- `rowVersion` is a decimal **string**: the stored row version plus one, and `"0"` when the deck has no publication row yet.
  The ETag is the quoted `rowVersion`.

### Save

`PUT` with exactly one `If-Match` (house rules: missing 428, malformed 400, stale 412 `VERSION_CONFLICT`; `"0"` is the
version of a deck without a row) and a strict body, all fields required:
`{commandId, visibility, metadata{topicId, contentLanguage, targetLanguage, level, tags}, requestsEnabled, publish}`
where `publish` is `null` or `{expectedHeadRevisionId, releaseNote}`. Unknown fields, duplicate keys, bodies over 8192
bytes and invalid Unicode are 400. The state after the command is **exactly the body** (not a merge).

- **Receipts** (house rules): bound to actor, command type, deck, expected version and the whole body; an exact retry
  returns the original acknowledgement with `Idempotency-Replayed: true` and no ETag (a historical state: re-read with
  GET); a used `commandId` with other input is 409 `IDEMPOTENCY_CONFLICT`. Fresh writes carry the ETag of the new version.
- **Publish.** `publish` sets the published revision to the deck's current head. `expectedHeadRevisionId` is the head the
  owner saw; any other head is 412 `VERSION_CONFLICT` and nothing is written. `releaseNote` (nullable, blank is `null`)
  is stored with it. No deck revision is created.
- **First publication.** Leaving `PRIVATE` for the first time requires `publish`: otherwise 400 `PUBLICATION_REQUIRED`.
  `publish` with `visibility: "PRIVATE"` is 400 `INVALID_REQUEST`. A deck that is `PRIVATE` again keeps its published
  revision, so it can be shared again without publishing.
- **«Публичная».** A resulting level `PUBLIC` is accepted only when the revision readers will get has a non-empty
  description, `topicId` and `contentLanguage` are set, Identity reports the public profile ready and the deck head has
  no non-commercial stock media (license containing an `NC` token or "non-commercial", any case); otherwise 409
  `PUBLICATION_REQUIREMENTS` with the member `failed` (a list of checklist keys: `description`, `topic`, `language`,
  `publicProfile`, `blockedMedia`) and nothing is written. It applies to every effective write that leaves a deck `PUBLIC`,
  with one relaxation: the public profile and the media items gate only the **exposure**, i.e. becoming `PUBLIC` and a
  `publish` while `PUBLIC`. A benign edit of a deck that is already public (tags, `requestsEnabled`, topic, language, level)
  is not blocked by a withdrawn consent. Withdrawing the consent hides the author at once (Identity: the card answers like
  an unknown account); the deck itself stays public without a byline (owner decision 2026-10-11).
- **Becoming public needs the head.** The media check reads the deck head while readers get the published revision. A
  command that makes the deck `PUBLIC` (from any other level) while the published revision is not the head must therefore
  carry `publish` for that head; otherwise it is 400 `PUBLICATION_REQUIRED` and nothing is written. `GET` still lists the
  `blockedMedia` of the head, so the form can say what to fix.
- **Rotation.** Any step to a more restrictive level rotates the public code (`PUBLIC` > `LINK` > `INVITE` > `PRIVATE`);
  raising the level keeps it. Old links answer the same 404 as an unknown code.
- **Unchanged state.** A command whose result equals the stored state writes nothing (`changed: false`, same `rowVersion`).
- **Outbox.** Every effective change of the level or of the published revision, and every effective write whose resulting
  level is `PUBLIC` (metadata, tags and `requestsEnabled` included, so the catalog projector never misses an edit), appends
  one `deck_publication_event` row (`eventId` uuidv7, deck, published revision, level, time) for the catalog epic. A command
  that changes nothing appends none. Nothing consumes it yet.
- **Acknowledgement:** `{commandId, changed, publication}` where `publication` is the state above after the command.
- Check order (as the controller and service run): authentication (401/403), path, `If-Match` and body shape (400/428,
  including `publish` with `PRIVATE`), the deck being the owner's (404), receipt replay, the topic being a leaf of the
  directory (400), the version (412), the first-publication rule (400 `PUBLICATION_REQUIRED`), the head pin (412), the
  becoming-public-needs-the-head rule (400 `PUBLICATION_REQUIRED`), then the checklist (409).

### Topic directory

`GET /api/topics` returns `{topics: [{topicId, nameRu, nameEn, ordinal, children: [{topicId, nameRu, nameEn, ordinal}]}]}`,
two levels, siblings ordered by `ordinal`. It is static data seeded by the migration (names and synonyms in Russian,
English and the languages' own names); the owner can edit it as data later. Synonyms are matched after the alias
normalization (lowercase, NFKC, `ё`→`е`, diacritics stripped from Latin/Greek/Arabic letters, spaces collapsed).

### Codes

New: `PUBLICATION_REQUIRED` (400: the command must publish the head, for the first publication or for becoming public) and `PUBLICATION_REQUIREMENTS` (409, member `failed`: list of strings). Existing
codes keep their status/title/detail. Problem text never echoes titles, descriptions, tags or identifiers.

## Access: invitations, requests and grants (Share/13, #435)

The level of a deck is changed only by the publication `PUT` above. This section is what lets people **in** to an
«По приглашению» deck: an invitation link (one-time or multi-use, with an expiry, revocable), a request from the
«нет доступа» screen and a grant by login. Exact examples and executable invariants are in
[`access.json`](access.json); the architecture is
[community decks §6, §9](../../docs/architecture/community-decks.md#6-доступ-и-публикация-cd-3-cd-4). Owner routes are owner-only
(a foreign, absent or deleted deck is the opaque 404 for everybody else, before anything else is read), private/no-store,
`learning.read` for GET and `learning.write` for the rest. Nothing here changes `deck.row_version`.

| Operation | Request | Success |
|---|---|---|
| Create an invitation | `POST /api/decks/{deckId}/invites` `{maxUses, expiresInDays}` | 201 `{invite, token, link}` (the token **once**) |
| Invitations | `GET /api/decks/{deckId}/invites?limit&cursor` | 200 page of invites (no token) |
| Revoke an invitation | `POST /api/decks/{deckId}/invites/{inviteId}:revoke` | 200 invite |
| Redeem | `POST /api/invites:redeem` `{token}` (any signed-in account; 404 while `learning.community.public-routes.enabled` is off, decided before the body is read) | 200 `{code}` |
| Ask for access | `POST /api/public/decks/{code}/access-requests` (signed in) | 202 `{status: PENDING}` |
| Take it back | `POST /api/public/decks/{code}/access-requests:withdraw` (signed in) | 204 |
| Open requests | `GET /api/decks/{deckId}/access-requests?limit&cursor` | 200 page, PENDING only |
| Approve / reject | `POST /api/decks/{deckId}/access-requests/{requesterId}:approve` / `:reject` | 200 `{requesterId, status, decidedAt}` |
| People with access | `GET /api/decks/{deckId}/grants?limit&cursor` | 200 page |
| Grant by login | `POST /api/decks/{deckId}/grants` `{username}` | 200 grant |
| Revoke a person | `DELETE /api/decks/{deckId}/grants/{granteeId}` | 204 |

- **Invitation links.** The token is 32 random bytes, base64url without padding (43 characters). It is returned **once**,
  with the ready link `/d/{code}?invite={token}`; only its SHA-256 digest is stored, so lists never contain it and a lost
  link is revoked and replaced. The token never appears in a log or in the path of any API call: the client sends it in the
  body of `POST /api/invites:redeem`. `maxUses` is `null` (unlimited) or `1..1000` (`1` is a one-time link),
  `expiresInDays` is `null` or `1..365`; both fields are required, unknown fields are 400. There is no `commandId`: an answer
  that carries a secret cannot be replayed. A deck can hold 20 active invitations and 200 in all (invitations that died more than 30 days ago are pruned
  when a new one is created; stale ones are not active); more is 422 `RESOURCE_LIMIT_EXCEEDED` with `limit`. A deck that is private or has never been
  shared is 400 `PUBLICATION_REQUIRED`.
- **Invite `status`:** `REVOKED`, else `STALE`, else `EXPIRED`, else `EXHAUSTED` (`uses == maxUses`), else `ACTIVE`. **`STALE`**: the
  invitation is tied to the code generation it was created under; any move to a more restrictive level rotates the code (the
  canon: «при понижении видимости ссылка меняется, и старые ссылки перестают работать»), and every older invitation is
  stale for good, also after the level is raised again. The owner creates new ones; raising the level keeps the code.
- **Redeem** runs in one transaction under a lock of the invitation row, in this order: a deleted, private or never published deck
  is 404; an invitation of an older code generation (`STALE`) is 404 for everybody, the owner and grantees included; the owner and an
  account that already holds the grant are answered with the code and consume nothing, even if the invitation was used up or revoked
  since (opening the same link twice must not turn a one-time link into a 404); anybody else needs an invitation that is not
  revoked, not expired and has uses left; on a `LINK` or `PUBLIC` deck a valid invitation is a no-op answer with the code (nothing to
  grant, no use consumed); on an `INVITE` deck the grant is written first and a use is consumed only if a grant was created; a
  person the owner revoked in the last 30 days is refused (below). **Every failure is the same 404 `RESOURCE_NOT_FOUND`**: unknown,
  malformed, revoked, expired, exhausted, stale, a deleted/private/unpublished deck, a revoked person. The one exception is a valid
  token for a deck that already holds 1000 people: 422 `RESOURCE_LIMIT_EXCEEDED`, `limit: 1000`. The answer is the **current**
  code of the deck; the client opens `/d/{code}`. Revoking an invitation stops new redemptions only; the people it already let in
  keep their grants until the owner revokes them one by one.
- **Access requests.** Only for an `INVITE` deck the signed-in caller cannot read: an unknown, private or rotated code is
  404; the owner, a grantee and the readers of a `LINK`/`PUBLIC` deck have nothing to ask for (400 `INVALID_REQUEST`); an
  author who turned the request switch off (`requestsEnabled` of the publication) answers **403 `DECK_INVITE_ONLY`**, the
  same body as the «Колода доступна по приглашению» screen, so a client shows the same neutral failure. The answer to a
  request is always `202 {"status":"PENDING"}`; a first request, a repeat while it is open and a repeat within 30 days of a
  rejection look identical (a rejection is silent), and only the first writes anything. Limits of a requester: 20 open
  requests (422 `RESOURCE_LIMIT_EXCEEDED`, `limit: 20`) and 5 new requests per rolling 24 hours (429 `RATE_LIMITED`,
  `Retry-After` = the time the oldest one leaves the window); the silent repeats are answered before the limits. After 30
  days a rejected request can be repeated and is a new request. The routes live on the public chain (optional bearer
  everywhere else, but required here, scope `learning.write`) and follow the kill switch
  `learning.community.public-routes.enabled`. **`:withdraw`** takes an open request back (`PENDING` to `WITHDRAWN`), which frees one
  of the 20 open slots: idempotent and uniform, 204 whether the request was open, already decided or never made; an unknown,
  private or rotated code is the opaque 404; the request can be made again at once (the daily limit still counts it).
- **Decisions.** `approve` creates the `VIEWER` grant, marks the request `APPROVED` and notifies the requester
  (`DECK_ACCESS_GRANTED`); `reject` marks it `REJECTED` and tells nobody. Repeating the same decision is the same 200; the opposite
  decision of a decided request, or a requester with no request, is 404. The list shows `PENDING` requests only.
  `approve` is 422 `RESOURCE_LIMIT_EXCEEDED` (`limit: 1000`) when the deck is full; the request stays open.
- **Notifications** ([`notifications-v1`](../notifications/README.md)): `DECK_ACCESS_REQUESTED` to the author at most once per
  deck per UTC day (dedupe key by deck and date, `pendingCount` = open requests, capped at 1000, at the time) and
  `DECK_ACCESS_GRANTED` to the requester (route `DECK`, params `{code}`). Params never name people: the panel shows
  requesters through Identity author cards, which are consent-gated.
- **Grant by login.** The deck must be a **published `INVITE` deck** (any other level, or private, is 400 `PUBLICATION_REQUIRED` and
  costs no lookup: a grant means nothing there). `{username}` is resolved server-side through the Identity public-profile lookup;
  only profiles with consent resolve, and an unknown login, a malformed one and a hidden profile are the same 404. The owner's own
  login is 400. A grant is idempotent (the existing one is returned), closes a pending request of that person as `APPROVED` without a
  notification, and, being the owner's deliberate act, lifts the block a revocation puts on that person's invitation links. A deck
  holds at most 1000 people (422 `RESOURCE_LIMIT_EXCEEDED`, `limit: 1000`, for every way in). **Lookup budget:** one in-process budget in Identity's own unit, a fixed
  **15-minute window**, shared with the profile complaints: at most 10 per owner, and 40 per instance together with the complaints (complaints take at most 10 of them, so they cannot starve grants)
  (429 `RATE_LIMITED`, `Retry-After` = the seconds until the window ends, before Identity is asked). Identity limits this public lookup per *network* (60 per 15 minutes) and every Learning call comes from one address, so all owners
  of all instances behind it share that allowance; with N instances behind one address set `learning.community.profile-lookups.instance-per-window` to about 40 / N. Identity answering 429 itself spends the whole budget until the end of the current window and is answered
  429 with that remaining time (never a fixed guess); being unreachable is 503; neither grants anything.
- **Revoking a person** (`DELETE …/grants/{granteeId}`) closes access at once and records a `REJECTED` request of that person marked
  as a revocation (created if they never asked). For 30 days a live invitation link cannot let them back in (the same 404) and
  asking again is the silent answer; the owner can add them by login at once. Copies the person already made stay (Share/10).
  Revoking a grant that does not exist is the same 204 and records nothing.
- **Lists** are keyset pages: `limit` 1–100 (default 20), opaque `cursor`, `nextCursor` null on the last page; a malformed
  cursor is 400. Invitations, requests and grants are newest first.

### Codes

No new codes: `RESOURCE_NOT_FOUND`, `INVALID_REQUEST`, `DECK_INVITE_ONLY`, `RESOURCE_LIMIT_EXCEEDED` (with the member `limit`),
`RATE_LIMITED` (with `Retry-After` and `retryAfter`), `PUBLICATION_REQUIRED`, `IDENTITY_UNAVAILABLE`. These routes carry no
`If-Match` (every write is idempotent by state), so there is no `VERSION_CONFLICT` and no `IDEMPOTENCY_CONFLICT`. Problem text never
echoes tokens, logins or identifiers.

## «Добавить к себе»: a copy of somebody's deck (Share/10, #432)

A signed-in account copies a deck it may read into its own library in **O(1)**; a background job then fills the copy's
heads from the **published** manifest. Exact examples and executable invariants are in [`forks.json`](forks.json); the
architecture is [community decks §5](../../docs/architecture/community-decks.md#5-копия-cd-2). The copy is the caller's own
private deck from the first answer on (it appears in `GET /api/decks`), in the source's storage lineage, so nothing is
duplicated and edits never cross in either direction.

| Operation | Request | Success |
|---|---|---|
| Copy | `POST /api/decks/forks`, `{commandId, sourceCode}` | 201 `{commandId, deckId, preparing: true, existing: false}` + `Location`; 200 with `existing: true` when a live copy of this source already exists |
| Retry a failed copy | `POST /api/decks/{deckId}/fork:retry` (no body) | 200 `{deckId, preparing, failed}` |
| Detach from the author | `POST /api/decks/{deckId}/fork:detach` (no body) | 200 `{deckId, detached: true}` |
| Auto-update setting | `PUT /api/decks/{deckId}/fork:auto-update`, `{autoUpdate: boolean}` | 200 `{deckId, autoUpdate}` (idempotent) |
| State of the copy | the existing `GET /api/decks/{deckId}` and `GET /api/decks` | `fork` member (below) |
| «Открыть мою копию» | `GET /api/public/decks/{code}` | `myCopy` member for a signed-in viewer (below) |

- **Who may copy** is `DeckAccess` by the code in the body, the same decision as the public read: `PUBLIC` and `LINK` decks by any
  signed-in account, an `INVITE` deck by a grantee (403 `DECK_INVITE_ONLY` without a grant). A private deck, an unknown, malformed or
  rotated code, a deleted deck and a deck that was never published are the same 404 `RESOURCE_NOT_FOUND`. The owner of the source gets
  **409 `FORK_OWN_DECK`**. The kill switches `learning.community.forks.enabled` and `learning.community.public-routes.enabled` are **false** by default: creation needs BOTH, otherwise it answers 404 as if the
  route did not exist, before the body is read (forks on with public routes off is the same 404; existing copies are unaffected).
- **Idempotent twice.** By `commandId` (house receipts: an exact retry returns the original acknowledgement and status with
  `Idempotency-Replayed: true`; a used `commandId` with another body is 409 `IDEMPOTENCY_CONFLICT`), and by **(owner, source)**: a second request
  with any `commandId` while a live copy of this source exists returns that copy (`existing: true`, 200). A deleted copy does not count.
  A copy of a copy is allowed (its own published revision; a copy can be published once it is ready).
- **What the copy is.** The title, description and counts of the **published** revision; the manifests of that revision are pinned for the copy
  (its own durable pins), so the source can later change, be deleted or go private without touching the copy. Anything the author did after
  publishing, including a new image only an unpublished revision uses, is not in the copy and is 404 on every media route.
- **`fork` in the owner's deck JSON.** Detail and every list item carry `fork`: `null` for an ordinary deck, otherwise
  `{upstreamCode, upstreamTitle, sourceAvailable, preparing, failed, detached, updateAvailable, autoUpdate}`.
  - `preparing`: the heads are not complete yet (true from the answer of `POST` until the job ends successfully). `failed` (implies `preparing`):
    the job ended `FAILED`/`CANCELLED` or its record is gone; show «Повторить» (`fork:retry`), the copy can also be deleted. Poll `GET` at a bounded
    backoff while `preparing && !failed`.
  - `sourceAvailable`, `upstreamCode`, `upstreamTitle`: computed at read for the copier through `DeckAccess` with the code he copied by, so they
    go dark when the source is deleted, made private, hidden by moderation, or its link is rotated away (lowering the level rotates the code, and a copier gets no new
    code; he gets the copy back by opening the new link and pressing «Добавить к себе» again, see *Re-linking* below). Nothing is written on read. Unavailable means `sourceAvailable` is `false` and the other two are `null`: show the approved text «Автор закрыл исходную колоду. Ваша
    копия остаётся с вами, но обновлений больше не будет.»
  - `detached`: the owner pressed «Отвязать от автора» (`fork:detach`); idempotent, never changes the content.
  - `updateAvailable` (the badge «Есть обновление», Updates/6): computed at read, nothing is written. True exactly when the copy is ready, attached, `sourceAvailable` is true, and the base
    revision the copy stands on is not the source's published revision **or** a unit the learner left for later (a `PENDING` row of the decisions table) is still on offer. After an apply
    that accepted or declined everything it is false until the author publishes again. It is never true for a preparing, failed, detached or dark copy. Open the plan
    ([`updates.json`](updates.json)) to see what the update contains.
  - `autoUpdate`: the stored setting, `false` by default, reported as stored in every state.
- **While preparing.** Every mutation of the copy and Study answer **409 `DECK_PREPARING`** (every non-GET method under `/api/decks/{deckId}/…` except `fork:retry`,
  `fork:detach` and `DELETE /api/decks/{deckId}`; `POST /api/capture-notes` and `POST /api/editing-drafts` that name the copy). The hub reads normally:
  `memberCount`/`exerciseCount` are the counts being prepared and the material list is empty (its `total` names the count being prepared). Deleting a preparing or failed
  copy is allowed. Only the owner ever sees 409; a stranger gets the opaque 404.
- **`myCopy` in the public summary.** `GET /api/public/decks/{code}` for a signed-in viewer carries `myCopy`: `null` or `{deckId, preparing}` for his live copy
  of exactly this deck («Открыть мою копию»); the guest's summary is unchanged («Войдите, чтобы добавить колоду к себе»).
- **Limits.** At most `learning.community.forks.max-preparing` (10) live copies per account may be unfinished at once: one more is 422
  `RESOURCE_LIMIT_EXCEEDED` with the member `limit`. At most `learning.community.forks.max-per-window` (20) copies may be **created** per account in a sliding `learning.community.forks.window` (one hour), deleted copies included: one more is 429
  `RATE_LIMITED` with `Retry-After` (and the member `retryAfter`), the time until the oldest creation leaves the window; answering an existing copy never counts. Across all accounts at most `learning.community.forks.max-unfinished-total` (200) copies may be unfinished (the queue of the job is shared): one more is **503 `FORKS_BUSY`** with `Retry-After: 30` (and the member `retryAfter`), nothing is written and the same command may be sent again; answering an existing copy never counts. The job runs one copy per account at a time (`learning.jobs` per-account limit) in slices of at most 200 entries.

- **Auto-update setting** (`PUT /api/decks/{deckId}/fork:auto-update`, Updates/6). Owner only, `learning.write`, strict body `{autoUpdate: boolean}` (no `commandId`: a state, not a command;
  unknown or duplicate members, non-booleans and bodies over 256 bytes are 400). Answers 200 `{deckId, autoUpdate}` with the stored value; the same value again is answered without a write.
  A deck that is not the caller's live copy is the opaque 404; a copy that is still preparing is 409 `DECK_PREPARING`; a **detached** copy is 409 `CAPABILITY_UNAVAILABLE` with
  `capability: "forkAutoUpdate"` and `reason: "DETACHED"` (a detached copy never gets updates and keeps the value it had). The kill switch `learning.updates.auto.enabled` (default false)
  decides whether the stored choice is acted on; the choice itself is always stored.
- **Re-linking.** `POST /api/decks/forks` with the NEW code of a source the caller already holds a live copy of answers the existing copy as before (200, `existing: true`) and, when the
  copy stored a different code, stores the one that now opens the source, so the copy is available again and its updates resume. Only a code that `DeckAccess` resolves for the caller to the
  copy's **own** source does this: the code of another deck, a private, deleted or unknown code, a guest and another account never re-link it, and a detached copy keeps its code.
  Nothing re-links on a read, and an exact retry of the command writes nothing.

### Codes

New: `DECK_PREPARING` (409), `FORK_OWN_DECK` (409) and `FORKS_BUSY` (503, member `retryAfter`, header `Retry-After`). Existing codes keep their status/title/detail
(`RATE_LIMITED` 429, `RESOURCE_NOT_FOUND` 404, `DECK_INVITE_ONLY` 403, `IDEMPOTENCY_CONFLICT` 409, `INVALID_REQUEST` 400, `RESOURCE_LIMIT_EXCEEDED` 422, `CAPABILITY_UNAVAILABLE` 409 with `capability` and `reason` members for the detached auto-update setting). Problem text never echoes
codes, titles or identifiers.

## The update plan of a copy (Updates/3, #442)

What the author published since the copy was made, unit by unit, against what the copy holds now: the plan the review screen shows and the apply command (next section) consumes.
Exact examples and executable invariants are in [`updates.json`](updates.json); the architecture is [community decks §7](../../docs/architecture/community-decks.md#7-обновления-cd-5).
Both routes only **read**: they create no revision, row, pin or job, whatever the size of the deck (the apply command is the one that writes). Private chain, `learning.read`, owner of the copy only, every answer
`Cache-Control: private, no-store`.

| Operation | Request | Success |
|---|---|---|
| The plan | `GET /api/decks/{forkDeckId}/update-plan?cursor=&limit=` (`limit` 1..100, default 50) | 200 `{state, forkDeckId, forkHeadRevisionId, upstreamRevisionId, baseRevisionId, planVersion, releaseNote, summary, units, nextCursor}` |
| The two sides of one unit | `GET /api/decks/{forkDeckId}/update-plan/units/{kind}/{key}?planVersion=` | 200 `{unitId, kind, key, class, reason, planVersion, mine, author, changedNodeIds}` |

- **States.** `READY` lists units; `UP_TO_DATE` has the pins and no units; `PREPARING` (the copy is not materialized), `DETACHED` and `SOURCE_UNAVAILABLE` (the source is deleted, private,
  hidden by moderation, or its code was rotated away: resolved by the code the copier used, like `fork.sourceAvailable`) answer `200 {state, forkDeckId, units: [], nextCursor: null}`
  and nothing else, nothing about the source. A deck that is not the caller's live copy is the opaque 404.
- **Units.** `MATERIAL` (key = member key), `EXERCISE` (exercise id; `enabled` is part of the revision), `OBJECTIVE` (objective id), `METADATA` and `ORDER` (the copy's own deck id: the
  title and description, and the order of materials). A unit has `class` (`NEW`, `SAFE`, `CONFLICT`, `DELETED`, `ASK_AGAIN`), a `reason` for a conflict, a `title` of at most 160 code points
  (the text of the revision shown: the author's, or the copy's for a unit the author removed; `null` for the order), the lineage revisions of the three sides (`authorRevisionId`,
  `mineRevisionId`, `baseRevisionId`; for `METADATA` and `ORDER` deck revisions of the source and the copy; `null` is "does not exist there"), `dependsOn` (unit ids that must be accepted
  with it; an objective that a NEW exercise introduces and the copy has no head for is not a unit at all, it comes with the exercise: it is not listed, not counted in `summary` and not in `dependsOn`) and `defaultChoice` (`ACCEPT`, `KEEP_MINE`, `KEEP`).
- **Classes.** The author added what the copy has not got: `NEW`. The author changed it and the learner did not, or both ended with the same content: `SAFE`. Both changed it differently,
  or the learner deleted what the author changed, or an exercise cannot work with the learner's materials or objective: `CONFLICT` (the learner's version is the default). The author removed
  what the copy still has: `DELETED` (asked, never applied by «accept all safe»). A decision was recorded and the author changed the unit after it: `ASK_AGAIN`. Units equal on both sides,
  units only the learner changed and units the author did not change are not listed.
- **Pins and staleness.** `planVersion` is the digest of the copy's head, the source's published revision, the base and the recorded decisions. A cursor embeds it: after any change of
  the copy, the source or a decision the cursor is `409 UPDATE_PLAN_STALE` and the plan is read again; so is a unit request with another `planVersion`. A malformed or foreign cursor
  and a missing or malformed `planVersion` are 400. `summary` counts the whole plan. A plan reads documents (to tell equal contents from different ones, and for the node check) only inside a time and pair budget; when it is spent the rest is judged cautiously (a conflict) and the plan is still answered. The order of the pages is fixed: class, kind, the author's manifest order, key.
- **A unit's sides** are the owner-level forms the editor reads: a material's native document, an exercise revision with its content, answer key and evaluator policy (the copier owns
  the copy and the author published it), an objective's title, the deck's title and description, and the moved materials of the order with their places. `changedNodeIds` is the stable
  node ids whose text, attributes, type or children differ between the two documents of a material. Only a unit of the **current** plan is readable (the opaque 404 otherwise): the route
  never reads a revision by an id from the request.
- **Decisions** (migration V61, written by the apply command): the table `fork_unit_decision` keeps what the learner decided per unit (`KEEP_MINE`, `DECLINE_NEW`, `KEEP_DELETED`, or
  `PENDING` for a unit left for later, which keeps its own old base when the base of the copy moves to the published revision). The plan reads it; the apply command (next section) writes it.

### Codes

New: `UPDATE_PLAN_STALE` (409) and `UPDATE_PLAN_BUSY` (503, member `retryAfter`, header `Retry-After: 1`: the instance computes as many plans as `learning.updates.plan.max-concurrent` allows; nothing was computed, send the request again). Existing codes keep their status/title/detail (`RESOURCE_NOT_FOUND` 404, `INVALID_REQUEST` 400, `AUTHENTICATION_REQUIRED` 401, `ACCESS_DENIED` 403).
Problem text never echoes titles, codes or identifiers.

## Applying an update (Updates/4, #443)

`POST /api/decks/{forkDeckId}/update-plan:apply` takes the owner's choices on the plan he read and makes **one** new revision of the copy from the author's published revisions, moves the
copy's base to the published revision of the plan and records the decisions, all in one transaction. Private chain, `learning.write`, owner of the copy only, no `If-Match` (the
`planVersion` of the body is the precondition), every answer `Cache-Control: private, no-store`. Exact examples and executable invariants are in [`updates.json`](updates.json); the
architecture is [community decks §7](../../docs/architecture/community-decks.md#7-обновления-cd-5).

```json
{ "commandId": "…", "planVersion": "sha256:…", "acceptSafe": true,
  "choices": [ { "unitId": "MATERIAL:…", "choice": "ACCEPT" }, { "unitId": "MATERIAL:…", "choice": "DELETE" } ] }
```

| Answer | When | Body |
|---|---|---|
| 200 | applied in the request (up to `learning.updates.sync-max-units`, 100, units) | `{commandId, state: "APPLIED", deckRevisionId, applied, declined, pending, alsoAccepted, baseRevisionId}`; `Idempotency-Replayed: true` when it is the stored answer of an earlier identical request |
| 202 | more units: a job applies them | `{commandId, state: "APPLYING"}`; the plan route answers `APPLYING` until the job ends, then the same command answers 200 |

- **The client names units; the server builds the operations.** The body has `commandId` (UUIDv4/v7), `planVersion` and nothing else but the two optional members; at most 64 KiB; a unit is
  named at most once; a `unitId` is `KIND:key` (kind in capitals, key in lowercase). No revision id and no ordinal is ever sent: the server recomputes the plan and builds every operation
  from the author's revisions in the source's published manifests. A different current `planVersion` is `409 UPDATE_PLAN_STALE` and nothing is written (also when the copy is edited, the
  source republished or a decision changed in the instant between the recomputation and the write: the pins are checked again under a lock in the same transaction).
- **Choices per class** (`constants.choicesPerClass`): `NEW` and `SAFE` take `ACCEPT` (the author's version) or `DECLINE`; `CONFLICT` and `ASK_AGAIN` take `ACCEPT` (the author's) or
  `KEEP_MINE`; `DELETED` takes `DELETE` (remove it from the copy) or `KEEP`. `METADATA`, `ORDER` and `OBJECTIVE` follow their class. Any other combination, a unit the current plan does not
  list and a unit named twice are `400 INVALID_REQUEST` with `reason` and the `unitId`. An exercise that needs a material the copy deleted (`MATERIAL_DELETED`) cannot be `ACCEPT`ed
  (`exercise_needs_material`).
- **«Принять всё безопасное»** is `acceptSafe: true`: every `NEW` and `SAFE` unit that `choices` does not name is taken, and nothing else (never a `CONFLICT`, a `DELETED` or an `ASK_AGAIN`
  unit, so what the learner changed stays his and what the author removed stays). Units neither named nor taken stay **pending**.
- **Dependencies.** Taking a unit takes its `dependsOn` units, whatever their class (`alsoAccepted` counts those taken only for that reason; it is a part of `applied`). A unit taken by name
  whose dependency was declined by name is `400 INVALID_REQUEST` with `reason: "dependency_declined"`, `unitId` and `dependency`. A unit taken only by `acceptSafe` whose dependency was
  declined by name waits instead (it stays pending). **An objective moves only with the exercises that evidence it:** taken by name it takes every exercise of the plan that lists it in
  `dependsOn` (`dependency_declined` if one was declined by name); taken only by `acceptSafe` it waits unless every such exercise is taken (or one is taken by name). **`DELETE` of a material**
  takes the removal of the exercises of the copy that are about it when the plan lists them as removals; kept by name, or not removals in the plan, it is `400` `material_still_used`
  (`unitId` the material, `dependency` the exercise when the plan lists it). No `400` of the command is without a `reason`: what the checks above did not foresee and the combined publication
  refuses is `publication_refused`.
- **What is written.** One revision of the copy (`deckRevisionId`; `null` when the command had nothing to adopt: it only declined or left pending), the base moved to `baseRevisionId` (the
  published revision of the plan, always), and the decisions: a unit taken loses its row; a unit declined gets `KEEP_MINE`, `DECLINE_NEW` (an addition) or `KEEP_DELETED` (a unit the learner had
  deleted) with the author's state it was declined at (an *absent* author state for «the author removed it and I keep it»); a unit left pending gets `PENDING` with its **own old base** and the
  author's state offered in this plan. So the next plan lists exactly the pending units (as they were), the units the author changes again (a declined one as `ASK_AGAIN`), and nothing the
  command took or declined. A pending `ASK_AGAIN` unit comes back as a `CONFLICT` (judged from its own old base; the learner's version is the default).
- **Idempotent by `commandId`.** The same command (the same body, the choices in any order) answers the stored answer with `Idempotency-Replayed: true`, also when the plan has changed since
  and also after the copy was detached or the source went away. The same id with another body is `409 IDEMPOTENCY_CONFLICT` (also while it runs as a job). A job that ended `FAILED` or `CANCELLED`
  is not restarted: sending its command again is `409 UPDATE_PLAN_STALE`; read the plan and send a new command. While a job of the copy runs, another command is `409 UPDATE_PLAN_STALE`.
- **The plan while a job runs.** `GET …/update-plan` answers `200 {state: "APPLYING", forkDeckId, units: [], nextCursor: null, commandId}` while the job is queued or running (a malformed
  query is still `400`). After a failed job, and until the next successful apply of the copy (also one that creates no revision) or the job is purged (7 days), the plan is answered as usual with the extra member
  `lastApplyFailed: {code}` (`stale_head` when the copy was edited meanwhile, `plan_invalid`, `taken_down`, …, `cancelled`; a short code, never a message).
- **Limits.** The body is at most 64 KiB, about 650 units named in `choices` (`acceptSafe` covers the rest). At most `learning.updates.max-units` (10 000) operations (units taken, plus the
  moves the order unit needs) and 20 000 units written as decisions per command, else `422 RESOURCE_LIMIT_EXCEEDED` with `limit`. The order unit scans both manifests: copies of at most 20 000
  materials (the same 422 above that). A job that never runs (no worker, the queue switched off) keeps the copy `APPLYING`: there is no cancel route in this version, and every other command
  of the copy is `409 UPDATE_PLAN_STALE` until it ends. The route takes a place of the plan routes' bulkhead
  (`learning.updates.plan.max-concurrent`): the excess is `503 UPDATE_PLAN_BUSY`.
- **The rest of the product.** Study uses the new heads from the next issue; a presentation already shown keeps its snapshot; an exercise that changed keeps the schedule of its objective; the
  media of an adopted material is readable by the copier (the `COPY` grant is decided by his heads).

### Codes

New: `UPDATE_SOURCE_UNAVAILABLE` (409: the source can no longer be read by the owner; nothing is said about it) and `FORK_DETACHED` (409: the copy was detached). Used: `DECK_PREPARING` (409, a
preparing copy), `UPDATE_PLAN_STALE` (409), `IDEMPOTENCY_CONFLICT` (409), `INVALID_REQUEST` (400, with the members `reason`, `unitId` and, for `dependency_declined`, `dependency`),
`RESOURCE_LIMIT_EXCEEDED` (422, `limit`), `UPDATE_PLAN_BUSY` (503), `RESOURCE_NOT_FOUND` (404: not the caller's live copy), `AUTHENTICATION_REQUIRED` (401), `ACCESS_DENIED` (403: no write scope).
Nothing was written by any refusal. Problem text never echoes titles, codes or identifiers other than the unit ids the request named.
