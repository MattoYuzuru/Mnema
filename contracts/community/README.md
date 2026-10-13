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
| Sitemap | `GET /api/public/sitemap.xml` | 200, XML, `/sitemap-decks.xml` for crawlers (the static `/sitemap.xml` keeps the static pages) |
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

## Clarifications of the backend (Community/2, #449; Community/6, #453; Community/7, #454)

These do not change a shape of [`catalog.json`](catalog.json); they fix what the examples and rules left open. The backend tests execute them.

- **Parameters.** An unknown parameter name is 400, like a repeated one. An EMPTY value (`topic=`, `sort=`, `cursor=`) is the same as an absent parameter, so a form may send its blank fields; a repeated
  parameter is 400 even when one occurrence is empty. `media` and `mechanics` take a comma-separated list without empty or repeated entries. `learn` is a language code of two or three lower-case letters
  (a language nobody teaches in is an empty list, not an error). The disabled catalogue answers 404 before any of this is looked at, so `?limit=0` on a disabled route is 404, not 400.
- **Facets order.** `topics` lists the top-level topics by count descending (then id), each followed by its children by count descending (then id): the order of the example. A top-level topic counts its own
  decks and its children's; a deck picks a leaf, so only a top-level topic without children (`other`) has its own.
- **Cards.** `updatedAt` is `content_updated_at` at whole seconds. `description` is cut to at most 300 code points INCLUDING the ellipsis `…` (299 code points of the text, trailing blanks dropped).
  `media` and `mechanics` are in the order of their vocabularies in `constants` (`image, audio, video`; `listening, matching, written, choice, order`), whatever the order of the example.
- **Browse.** `popular` and `learning` read the current rank generation: before the first generation exists (the first minute of a fresh database) they are empty with `total` 0; `new` and `updated` work at once.
  A deck the current generation does not know yet appears in `new`, `updated` and search only. The default sort is `popular`.
- **Search.** The text part of a deck that is in a topic the query names is at least the constant 0.3 (a title hit is about 0.4); the pieces of the relevance are computed in double precision and the list is
  ordered by the relevance rounded to millionths, then by public code in byte order. `total` is the number of candidates among the best 200, `totalCapped` is `true` when that number is 200.
  The query is also extended with the names and synonyms of at most three topics it names (a phrase of up to four words, or one word, is looked up among the topic synonyms).
  The language factor 0.85 applies only when the detector is SURE of the language of the query and it differs from the deck's content language; when it is not sure (a short or mixed text) no deck is lowered.
  A text without a letter or a digit answers an empty page (`total` 0) without a search; a text of fewer than three code points is not matched against titles by trigrams (it is matched by the text arms and by the topics it names), so a
  two-character Chinese, Japanese or Korean query finds nothing in v1. One way of matching contributes at most 2 000 candidates: a query that broad is not specific and the best 200 are chosen among those.
- **Cursors.** Base64url of a small JSON object with a version, the kind of list, a hash of the normalized query and filters, the rank generation (popular, learning, search) and the position: the integer rank
  (popular, learning), the relevance in millionths and the public code of the last deck (search), a time and a public code (new, updated, the author list). A cursor never holds a score, a popularity percentile or a
  deck id. A cursor is bound to the list it was issued for: another sort, text or filter is 400, a forged or re-encoded one is 400, a generation that was pruned is 412 `VERSION_CONFLICT`. A search cursor issued
  while no generation existed carries none and keeps working.
- **Author list.** `learningNowTotal` is `null` also when no generation exists yet. The viewer who is the author sees no invitation part (he has no grant for his own decks). The cursor knows which part it is in
  (public decks, then grants); the grant part lists the decks of that author that the viewer holds a grant for, newest publication first.
- **Sitemap.** `application/xml; charset=UTF-8`, `Cache-Control: public, max-age=3600`, no `Vary`; each instance builds it at most once an hour (the setting is capped at one hour), so a deck that moderation hides stays in a sitemap already built until the cache expires, and
  for as long as a crawler keeps its copy; the deck page and the search stop showing it at once; the community page plus at most 49 999 decks (the newest entrants), 50 000 URLs in all; a larger catalogue is
  truncated with a warning in the log. The base is `MNEMA_PUBLIC_BASE_URL` (blank is `https://mnema.app`).
- **Editorial shelf.** `rowVersion` starts at `"0"` and grows by one with every effective change; a `PUT` that leaves the list as it is changes nothing (no version, no audit row). The answer of a `PUT` and of
  a `GET` is the same state and carries the version as `ETag`. A code is accepted when it is a PUBLIC published deck that is not deleted, hidden or below the threshold included (the entry then shows
  `listed: false`). A code that is not one is 400 with the member `codes` (the offending codes, in the order sent); a code of another shape and a repeated code are plain 400. The audit row is
  `COMMUNITY_EDITORIAL_UPDATE`, its resource is the shelf (a constant id), its command the command id.
- **Busy and slow.** A text search is the expensive read: an instance runs at most `max-concurrent-searches` (default 2) at once, and the next is `503 PUBLIC_READ_BUSY` with `Retry-After: 1` before it takes a connection; a read that exceeds the
  query timeout (3 seconds) is the same 503. The client retries with a pause; the cheap reads (browse, facets, shelves) are not affected.
- **Cursor validity.** The public code in a cursor must be a base58 code of ten characters in every kind of list and the time must lie between the year 2000 and a day from now; otherwise 400. The cursor is bound to the text as typed (the
  operators and quotes of the search syntax count) and to its normalization, and a browse or search is read in one snapshot, so a cursor of a pruned generation is always 412 and never an empty page.
- **Sort with a text.** With `q` the sort is optional: absent is the relevance (cursor: the relevance in millionths and the public code of the last deck); `popular`, `learning`, `new` or `updated` re-orders the best 200 candidates by
  that key (the current generation's rank, its learning rank, the first listing, the last publication; a candidate the generation does not know is last, newest entrant first; the public code is the last tie-break). The cursor
  of a sorted search is the position in that ordered list (1..200), bound to the generation and to the question including the sort, and holds no score, rank or deck id. `total` and `totalCapped` are those of the candidates.
- **Typos.** A query word of five code points or more that is word-similar (pg_trgm, the same threshold as the titles) to a topic synonym names that topic and extends the query with its names (so «испанскй» and «spansh» find the
  Spanish decks). Typos in descriptions and tags are not tolerated in v1; titles are, by trigram similarity.
- **Ties of the ranks.** Equal scores are ordered by `first_listed_at` newest first and then by an internal key, so at zero engagement «Популярные» is «newest first»; the same for the learning rank. A deck that has just become
  listed is ranked within about a minute (the projector asks for a generation with a one-per-minute key), not at the next hourly one.
- **`learningNowTotal`** counts DISTINCT learners (non-owner accounts with a study row in the last 30 days) across the author's public decks, listed or not; one person who studies two of his decks counts once. It is not a sum of the
  numbers of the cards. Below 10 it is `null`, above it is floored to two significant digits. It is read live, so it may be fresher than the cards' numbers (which are those of the hourly generation).

## Recommendations (epic 4, #422)

[`recommendations.json`](recommendations.json) owns the authenticated interest profile, reset, client event and aggregate impression commands and the recommendation page. Both sides execute its examples. The events and for-you switches default to false. All new routes live under `/api/community`, in the existing authenticated chain. `for-you` is a frontend sorting value routed to `GET /api/community/recommendations`; ordinary public catalogue routes keep their own wire contract.

The parent writes events only for authenticated accounts, without search text; client events describe opens/search clicks, while copy, study start and drop are server transitions. Daily aggregate exposures carry no account. Interest reset clears the previous signals and records a new boundary; anonymous exposures remain. Weights and exact ranking values stay server-side.

Executable requests and replies are the `profile`, `select`, `reset`, `event`, `exposure`, `recommendations` and `emptyRecommendations` entries; `invariants` define validation, privacy, retention, jobs, load boundaries and the ranking rules. The single-card recommendation example exercises the parser; a real shelf is omitted below four cards.
