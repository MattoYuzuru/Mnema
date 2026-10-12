# Community catalogue (epic 3, #421)

Wire contract of the community catalogue: facets, shelves, browse and search, the decks of an author, the sitemap and the
owner's editorial shelf «Выбор Мнемы». Exact examples and executable invariants are in [`catalog.json`](catalog.json); the
architecture is [community decks §9–§10](../../docs/architecture/community-decks.md#10-каталог-поиск-ранг-рекомендации-cd-7-cd-8),
the behaviour [the product contract](../../docs/product/community-decks.md) («Сообщество», «Поиск», «Профиль автора»).

| Operation | Request | Success |
|---|---|---|
| Facets | `GET /api/public/community/facets` | 200, `listedCount`, topics, languages and levels with counts of listed decks |
| Shelves | `GET /api/public/community/shelves` | 200, `listedCount` and the shelves that have at least 4 decks |
| Browse and search | `GET /api/public/community/decks?q=&topic=&learn=&level=&size=&media=&mechanics=&fresh=&sort=&limit=&cursor=` | 200, cards, `total`, `totalCapped`, nullable `nextCursor` |
| Decks of an author | `GET /api/public/community/authors/{accountId}/decks?limit=&cursor=` | 200, public decks of the author and, for a viewer with a grant, his invitation-only decks |
| Sitemap | `GET /api/public/sitemap.xml` | 200, XML, `/sitemap.xml` for crawlers |
| Editorial shelf (owner console) | `GET`/`PUT /api/admin/community/editorial` | 200, the ordered list |
| Anomaly flags (owner console) | `GET /api/admin/community/anomalies`, `POST …/{code}:dismiss` | 200 list, 204 |

- **Same public rules as the public read** ([`decks/public-read.json`](../decks/public-read.json)): GET/HEAD only, optional bearer
  (an invalid one is 401), the same rate limits (429 `RATE_LIMITED`) and bulkhead (503 `PUBLIC_READ_BUSY`), `no-store` with
  `Vary: Authorization`. The kill switch `learning.community.catalog.enabled` (default false) makes every public route here 404.
- **Only the catalogue projection is read**: PUBLIC decks with a published revision, not hidden by moderation, not deleted.
  Facets, shelves, browse and search show listed decks only (the threshold: 10 materials, an exercise, a description); the
  decks of an author show his public decks below the threshold too. A LINK deck never appears; an INVITE deck only for a
  viewer with a grant, in the author's list.
- **Honest numbers.** «Добавили» and «Учат сейчас» never count the author and only count direct copies; below 10 they are
  `null`, above they are floored to two significant digits. No score, rank, deck id or revision leaves the server.
- **Cursors** are opaque, bound to the query, the filters and the rank generation (kept 24 hours): another query with an old
  cursor is 400, an expired generation 412 `VERSION_CONFLICT` (the client restarts the list).
- Failure codes: `INVALID_REQUEST` (400), `AUTHENTICATION_REQUIRED` (401), `RESOURCE_NOT_FOUND` (404, also the kill switch),
  `VERSION_CONFLICT` (412), `RATE_LIMITED` (429), `PUBLIC_READ_BUSY` (503); the editorial `PUT` adds `PRECONDITION_REQUIRED`
  (428), `VERSION_CONFLICT` (412) and `IDEMPOTENCY_CONFLICT` (409).
