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
