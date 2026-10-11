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
every existing deck, item, exercise, draft, capture, study, generation and media route stays owner-only and answers
the opaque 404 for any other account, whatever the deck's level.

| Operation | Request | Success |
|---|---|---|
| Summary | `GET /api/public/decks/{code}` | 200, title, description, counts, `access`, `ownerId`, `slug` (PUBLIC only) |
| Materials | `GET /api/public/decks/{code}/items?limit=&cursor=` | 200, page + nullable `nextCursor` |
| One material | `GET /api/public/decks/{code}/items/{memberKey}` | 200, native document as pinned by the published manifest |
| Exercises | `GET /api/public/decks/{code}/exercises?limit=&cursor=` | 200, page of learner-safe summaries + nullable `nextCursor` |

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
  keys, options, references, transcripts, bindings and evaluator policies never leave the server. Media is not served here
  (Share/9); the document is returned as stored and the owner-only media endpoints keep refusing everyone else.
- **Limits.** `429 RATE_LIMITED` with `Retry-After` (the real remaining time of the window that refused): a guest is counted per
  client network (IPv4 address, IPv6 /64) and, for IPv6, all guests of one /48 together; guests that find the guest table full share
  one overflow bucket; a signed-in viewer is counted per account in a table of its own, so a flood of guests never starves an
  account (defaults 120 / 600 / 600 / 600 a minute per instance). Every request counts, found or not.
- **Bulkhead.** At most 4 public reads run at once per instance; the next one is refused before any lookup with **503
  `PUBLIC_READ_BUSY`** and `Retry-After: 1` (house schema, member `retryAfter`). Clients retry after the delay.
- **Kill switch.** `learning.community.public-routes.enabled` is **false** by default: every route answers 404 as if it did
  not exist. (A bearer that is present and invalid is still 401, because authentication runs first.)
- Failure codes: `DECK_INVITE_ONLY` (403, new), `RESOURCE_NOT_FOUND` (404), `INVALID_REQUEST` (400), `VERSION_CONFLICT` (412),
  `RATE_LIMITED` (429), `PUBLIC_READ_BUSY` (503, new), `AUTHENTICATION_REQUIRED` (401), `ACCESS_DENIED` (403, a valid token without `learning.read`).

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
  an unknown account); whether the deck itself is then demoted is an **open owner decision**.
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
