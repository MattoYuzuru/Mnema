# Deck-local LearningItem API

The Learning runtime context path is `/api`. Identity subject is the only owner
input. A `memberKey` identifies one logical item only inside its Deck; an
`itemRevisionId` identifies immutable content but never grants access by itself.
All responses are private/no-store and expose neither physical roots nor pins.

| Operation | Request | Success |
|---|---|---|
| Current page for Browse | `GET /api/decks/{deckId}/items?limit=20&cursor=...` | 200, summaries and nullable cursor |
| Current or exact historical item | `GET /api/decks/{deckId}/items/{memberKey}[?revisionId=...]` | 200, validated native-v1 document |
| Append/insert one item | `POST /api/decks/{deckId}/items` + `If-Match` | 201, Location and publication acknowledgement |
| Save one exact item | `PUT /api/decks/{deckId}/items/{memberKey}` + `If-Match` | 200, publication acknowledgement |
| Publish up to 100 changes | `POST /api/decks/{deckId}/items/publications` + `If-Match` | 200, one atomic acknowledgement |

`If-Match` is the quoted Deck row version defined by the Deck contract. Every
command also contains `expectedDeckRevisionId`; save/delete/reorder contains
`expectedItemRevisionId` and the source `expectedOrdinal` at that expected Deck
revision (all ordinals in one bulk command refer to that starting revision, not
to intermediate changes earlier in the array). Missing HTTP or body preconditions return 428, malformed
values return 400, and stale values return 412. A list cursor binds the current
Deck revision, so it returns 412 rather than mixing two Browse snapshots after a
publication.

The request boundary is strict JSON, at most 1 MiB, with duplicate keys rejected.
Only the shared [native-v1](../content/native-v1/README.md) document is persisted.
A save without `edit` may change values while preserving node IDs/topology. A
structural insert/delete/move supplies the corresponding explicit native edit
intent; this prevents an arbitrary final document from silently deleting or
reparenting content. Bulk has 1..100 changes with distinct member keys and supports
`create`, `save`, `delete` and `reorder`. Ordinals are zero-based; move destinations
are final ordinals after removal.

Receipts bind actor, Deck, both expected Deck values and the complete normalized
command. An exact retry returns the original acknowledgement with
`Idempotency-Replayed: true` and no ETag. A changed reuse of the command ID returns
409. Fresh results include the new Deck ETag. Clients reconcile any retry with GET.
Foreign or absent Deck/member/revision tuples share the opaque 404 boundary.

The immutable counted member root is the canonical order and serves bounded Browse
pages directly. The current SQL projection contains only the changed item heads, so
front insert/delete/reorder never renumbers an entire Deck. `expectedOrdinal` lets
the counted tree validate a member in logarithmic bounded work; Browse summaries
and publication acknowledgements expose ordinals, while direct current-item reads
return `ordinal: null` because they do not scan the tree to locate a key.
The current projection is rebuildable from the immutable member root.
Historical item reads decode the exact content root directly; neither current nor
historical reads replay prior revisions. A Deck metadata-only save reuses member
and exercise roots. Publication advances the Deck head, immutable revision,
projection, receipt and durable root pins in one transaction; rollback exposes no
partial head. Storage preparation remains synchronously bounded by native-v1,
100 changes and 100,000 members. There is no large-publication job API in this slice.

## Reserved exercise and media seams

This API stores native content only. It does not accept exercise answer specs,
scheduler state, media bytes or public URLs as authorization. Future exercise
bindings must use `(deckId, memberKey, itemRevisionId, nodeId)` against one pinned
Deck revision. Future media nodes must carry an authorized asset identity and
processing/missing capability state; upload and serving remain #76. Neither seam
changes the native-v1 preservation or opaque-node rule.

Exact examples are in `publication.json`. The full multilingual/RTL/ruby/future-node
golden document remains `contracts/content/native-v1/valid/mixed.json` and is used
by the PostgreSQL round-trip integration test.
