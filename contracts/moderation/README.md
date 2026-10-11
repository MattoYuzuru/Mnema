# Complaints, hiding and legal takedown (Share/15, #437)

Wire contract of the complaint flow of the community decks. The machine-readable fixtures are
[`complaints.json`](complaints.json) (constants, executable `invariants`, examples with the property sets the integration tests
compare with real answers). Architecture: [community decks §11](../../docs/architecture/community-decks.md#11-безопасность-пдн-модерация-cd-9),
product rules: [«Правила и ПДн»](../../docs/product/community-decks.md), owner console: [`contracts/admin`](../admin/README.md).
Runtime notes and the enforcement statements: [`backend/services/learning/guide.md`](../../backend/services/learning/guide.md).

There are three audiences, and each sees only its own part:

| Who | What |
|---|---|
| Any signed-in account that can read a public deck or profile | **File** a complaint (`POST …/complaints`). The only answer is `202 {status: "RECEIVED"}`; nothing is sent about the decision. |
| The console owner (admin client token) | The **queue**, the detail, the unit picker, the **decisions** (`REJECTED`, `HIDDEN`, `TAKEN_DOWN`, `ACCOUNT_REFERRED`), `:unhide` and the journal. |
| The owner of a deck (a source or a copy) | `GET /api/decks/{deckId}/moderation`: whether the deck is hidden and how many of its units are taken down, to draw a notice on the hub. |

## Filing

`POST /api/public/decks/{code}/complaints` and `POST /api/public/profiles/{username}/complaints`, body
`{"reason": "COPYRIGHT|PERSONAL_DATA|ILLEGAL|SPAM|OTHER", "text": "optional, ≤ 1000 characters"}`, scope `learning.write`
(a guest is 401: the client sends the guest to sign-in with the return URL). The deck route is on the public chain, so the code is a credential
and is redacted from logs like the other `/api/public/decks/{code}/…` routes; the profile route is on the private chain.

- The reporter must be able to read the target now. An unknown, private, rotated, deleted, **hidden** or not-yet-published deck, and an invitation
  deck without a grant, are the same `404 RESOURCE_NOT_FOUND`. Reporting your own deck or profile is `400`.
- A profile is resolved through Identity (public profiles only) under a lookup budget of its own (never the grants' budget); an unknown or hidden login is the same 404, and a repeat of an open complaint about the login costs no lookup.
- Idempotent: one OPEN complaint per (reporter, target). A repeat is the same `202`, changes nothing and the first reason stands.
- At most 10 new complaints per reporter per rolling 24 hours (`429 RATE_LIMITED` with `Retry-After`).
- `learning.community.complaints.enabled` (default `true`) switches both routes off (404). It must be on before the public routes go live: the
  deck route is a child of `learning.community.public-routes.enabled`, so a deployment that opens the public routes has the complaint button working.

Client: «Пожаловаться» in the public deck «⋯» menu; the profile page later. The toast is a plain-text «Жалоба отправлена». A `404` after the
user pressed the button means the deck is gone (say «Колода больше недоступна»), `429` says to try later, anything else is the generic error.

## Console

All routes: `GET`/`POST` under `/api/admin/complaints`, the console owner **and** a `mnema-admin-web` token (anything else is `403`), `private, no-store`.
The console navigation entry follows `GET /api/admin/console/access` → `owner: true`.

- **Queue** `GET /api/admin/complaints?status=OPEN|DECIDED&limit&cursor` (`status` default `OPEN`). OPEN: oldest `dueAt` first; DECIDED: newest decision first.
  Keyset pages of 50 (max 100), `nextCursor` null at the end; the list is loaded automatically (shared auto-load component), no page buttons.
  `overdue` is a server flag; say «Просрочена» / «Срок через N дн.» in words next to the date.
- **Detail** `GET /api/admin/complaints/{id}`: the complaint, the reporter's opaque account id, the live deck (owner id, level, whether it is published
  and hidden, counts, other open complaints about it) or the profile account id, and the takedowns this complaint made (first 200). Resolve the account
  ids through the Identity directory (`contracts/admin`, the directory accepts an exact account UUID).
- **Unit picker** `GET /api/admin/complaints/{id}/units?kind=ITEM|EXERCISE&limit&cursor`: the materials or exercises of the deck's PUBLISHED revision in
  manifest order, with `takenDown`. `revisionId` of a unit is the revision readers get; the decision sends `{kind, key, revisionId}` (or `revisionId: null`
  for "the published one").
- **Decide** `POST /api/admin/complaints/{id}:decide`:
  - `{"decision":"REJECTED"}`: closes the complaint, nothing else changes (deck or profile).
  - `{"decision":"HIDDEN"}` (deck): sets `hidden_by_moderation`. The deck answers like an unknown code to everyone but its owner and leaves the catalog; copies stay.
  - `{"decision":"TAKEN_DOWN","units":[…],"hide":false}` (deck): the legal takedown by lineage, below. `hide: true` also hides the deck.
  - `{"decision":"ACCOUNT_REFERRED"}` (profile): records that the operator continues in the Identity directory (ban / hide the profile there); Learning writes nothing to Identity.
  - A complaint has **one** decision. The same decision again is `200` with `changed: false` (a `TAKEN_DOWN` may add more units); another decision on a
    DECIDED complaint is `409 COMPLAINT_DECIDED`. Confirmation of a destructive decision is the client's job (house confirmation pattern); the API acts at once.
- **Unhide** `POST /api/admin/complaints/{id}:unhide` (no body): clears the flag of the complaint's deck. Only a complaint **decided `HIDDEN`** can do it, and only **while the deck is hidden**; an open complaint, one decided `REJECTED` or `TAKEN_DOWN`, and a deck that is not hidden answer `409 COMPLAINT_NOT_HIDDEN`. To lift a hide made by `TAKEN_DOWN` with `hide`, decide `HIDDEN` on a complaint of the deck (recorded even though the deck is already hidden), then unhide with it.
- **Journal** `GET /api/admin/complaints/journal?limit&cursor`: the append-only record of every decision (time, actor, action, target snapshot, number of units).
  The same decisions also appear in the console audit (`/api/admin/console/audit`) as `COMPLAINT_DECIDE` / `COMPLAINT_UNHIDE`.

Every decision answers with the complaint detail plus `changed`.

## Hiding (`HIDDEN`)

A hidden deck is invisible to everybody except its owner: `DeckAccess` answers `NOT_FOUND` for a non-owner at every level, so the public summary,
items, exercises, media, access requests, invitation redemption and complaint filing all answer the opaque 404. The owner keeps full access and
sees `GET /api/decks/{deckId}/moderation` → `hiddenByModeration: true` and a `DECK_HIDDEN` notification. Copies other people already made are not touched.
Changing the level or republishing never clears the flag; only `:unhide` does.

## Legal takedown (`TAKEN_DOWN`)

A takedown names **revisions** of units of the deck's storage lineage. One predicate, "this revision of this unit of this lineage is taken down",
is applied wherever a revision would be served, to the revision actually served:

| Reader | Behaviour on a blocked revision |
|---|---|
| Public item read `GET /api/public/decks/{code}/items/{memberKey}` | `410 CONTENT_UNAVAILABLE` |
| Public items / exercises pages | the entry stays with `unavailable: true`, title / prompt `""` |
| Public media of the deck | not served (the same 404 as an asset that is not in the deck) |
| Owner reads (source deck and copies): item read, item list, exercise read, exercise list | read is `410`; list rows keep their identity with `unavailable: true` and `title: ""` |
| Study (sessions) | the exercise is never issued if its revision, or the material revision it quotes, is blocked |
| AI context (outline, similar titles, pinned and revised materials) | the unit is left out |
| Copy owner's private media routes and the offline manifest | assets of a blocked unit are not served |

Media is blocked by **asset and lineage**: the decision records the assets of the named revisions, and from then on no revision of that lineage serves them (public media, a
copy owner's media, the offline manifest), so an edit that keeps the disputed image does not bring it back; their owner does not play them either. A learner's own upload is another asset.
Signed media URLs already issued live out their TTL (10 minutes public, up to 2 hours for the owner). **Accepted risk:** text trivially re-saved by the owner is a new revision and is not
blocked; the remedy for a repeat offender is `HIDDEN`. Follow-ups: a takedown lift ledger and a per-target cap on complaints.

Because a copy shares the lineage rows of its source, **the source deck and every copy whose head is still that revision are blocked together**;
a copy whose learner edited the unit holds a different revision and is unaffected, as is every unit the decision did not name. A later edit of the
source also produces a new revision that is not blocked: the operator names it in a new decision if it still carries the disputed content.
The owner of a blocked unit cannot read it (`410`) and so cannot open it in the editor; he can **replace or delete it without reading it**: the list row
still gives `memberKey`, `ordinal` and `itemRevisionId` (the revision he holds), and a save with those as `expectedItemRevisionId` / `expectedOrdinal` writes a new revision. The
client shows «Материал недоступен» with «Заменить» and «Удалить» for such a row. A unit that quotes a taken-down material (a CONTEXT binding, the text a presentation shows) is
skipped by Study wherever it is held, and an issued (frozen) presentation of such an exercise is no longer shown, resumed, replayed, revealed, answered or graded (an answer being assessed ends as `UNAVAILABLE` on the next sweep); an exercise only *assessed* on a taken-down material shows none of it and is not skipped unless the decision names it.
There is no lift route in this revision.

## Notifications

`DECK_HIDDEN {deckId}` and `DECK_CONTENT_TAKEN_DOWN {deckId, materialCount, exerciseCount}` (both WARNING, route DECK) to the owner of the deck when a
decision changed something. The client needs a sentence for each in `notification-presenter` (see `contracts/notifications`). The reporter is never notified.

## Strings the client needs (placeholders for the approved texts)

«Пожаловаться» (menu item and dialog title), reason labels for `COPYRIGHT`, `PERSONAL_DATA`, `ILLEGAL`, `SPAM`, `OTHER`, optional text label, send,
«Жалоба отправлена», «Колода больше недоступна» (404 after sending), «Попробуйте позже» (429); console: queue, «Просрочена», decisions and their
confirmations, unit picker, journal; hub notice «Колода скрыта из Сообщества» and «Материалы сняты по жалобе» for owners; the item placeholder
«Материал недоступен» for `unavailable` entries and the `410` screen.
