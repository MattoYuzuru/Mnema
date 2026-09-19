# Acknowledged authoring lifecycle

The Learning runtime context path is `/api`. All resources are private to the JWT
subject, all responses use `Cache-Control: private, no-store`, and foreign or absent
identifiers share the opaque 404 boundary. `If-Match` is a quoted decimal row version.
Missing write preconditions return 428, malformed input returns 400, stale versions
return 412, changed command reuse returns 409, and exhausted account quotas return 422.

## EditingDraft

| Operation | Request | Success |
|---|---|---|
| List active drafts | `GET /api/editing-drafts?limit=20&cursor=...` | 200 bounded metadata page |
| Restore one | `GET /api/editing-drafts/{draftId}` | 200 with native-v1 document and ETag |
| Start draft | `POST /api/editing-drafts` | 201 acknowledgement, Location and ETag |
| Autosave | `PUT /api/editing-drafts/{draftId}` + `If-Match` | 200 acknowledgement and new ETag |
| Discard | `DELETE /api/editing-drafts/{draftId}` + `If-Match` | 204 |

Create accepts a `commandId`, `deckId`, native-v1 `document`, and either both
`memberKey`/`baseRevisionId` for an existing item or neither for a new item. Context
and base are immutable. An update accepts only `commandId` and `document`. The server
advances `rowVersion`, `acknowledgedAt`, and `expiresAt` only after PostgreSQL commits;
the expiry is 30 days after the last acknowledgement. Separate `draftId` values may
share one base. Two tabs writing one version cannot overwrite each other: exactly one
CAS succeeds and the stale write receives 412. Autosave never calls item publication.

Limits are 1 MiB stored native JSON per draft, 200 active drafts and 20 MiB of active
draft content per account. Expired drafts are absent from reads and are removed under
the owner quota lock when another draft is created. Reaching a limit never deletes an
active draft. List pages contain metadata; the full document is restored separately.

## CaptureNote (“На потом”)

| Operation | Request | Success |
|---|---|---|
| List notes | `GET /api/capture-notes?limit=20&cursor=...` | 200 bounded page |
| Read note | `GET /api/capture-notes/{noteId}` | 200 and ETag |
| Capture | `POST /api/capture-notes` | 201 acknowledgement, Location and ETag |
| Edit | `PUT /api/capture-notes/{noteId}` + `If-Match` | 200 and new ETag |
| Archive/unarchive | `POST /api/capture-notes/{noteId}/archive` + `If-Match` | 200 and new ETag |
| Convert | `POST /api/capture-notes/{noteId}/conversions` + `If-Match` | 200 atomic result |
| Delete explicitly | `DELETE /api/capture-notes/{noteId}` + `If-Match` | 204 |

A note contains immutable `createdAt`, mutable source/text until conversion, an
independent archive flag, and no expiry, due date, objective or StudyState. Source is
1..2048 UTF-8 bytes and text is 1..32 KiB. The protective account defaults are 10,000
notes and 64 MiB combined source/text; they are engineering safety limits, not a
commercial plan. Lists are keyset-paginated by immutable creation order.

Conversion accepts `commandId`, decimal-string `expectedDeckVersion`,
`expectedDeckRevisionId`, optional final `ordinal`, and a strict native-v1 document.
The note ETag independently protects the note. The service invokes the canonical
LearningItem create boundary through a narrow port; item receipt, Deck head/revision,
pins/projection and the note's immutable conversion reference commit in one database
transaction. Native/K3 preparation runs before that short transaction and holds no
note row lock; the note CAS joins only after the item receipt is stored. The command
identity binds the note ETag as well as the body. Exact retry returns the stored conversion outcome and cannot create a
second item. Changed retry is rejected. Publication failure leaves both the note and
all drafts untouched. Conversion retains source/text/createdAt and makes source/text
immutable; archive and explicit deletion remain owner actions.

The full document rules and adversarial boundary remain
[`native-v1`](../content/native-v1/README.md). Example envelopes are in
[`lifecycle.json`](lifecycle.json). Media bytes/references, audio transcription, AI,
offline sync, scheduler state and deployment are outside this contract.
