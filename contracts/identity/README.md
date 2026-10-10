# Public profile and author cards (Identity)

Wire examples for the public-profile consent (Share/1, #423) and author cards (Share/2, #424).
Identity owns the data; Learning never reads it. The browser calls these endpoints directly.
Canonical behaviour: [community decks product contract](../../docs/product/community-decks.md#профиль-автора)
and [architecture §11](../../docs/architecture/community-decks.md#11-безопасность-пдн-модерация-cd-9).

All paths are below `/api/accounts`.

| Route | Auth | Answer |
|---|---|---|
| `GET /me/public-profile` | bearer | `consentDefault` / `consentGranted` shape |
| `PUT /me/public-profile` | bearer | body `consentUpdate`; 409 `consent_text_outdated`, 409 `profile_username_required` |
| `GET /me/avatar` | bearer | owner's own avatar bytes, `Cache-Control: no-store` |
| `GET /profiles/{id}` | none | `card`, or `notFound` |
| `GET /profiles/{id}/avatar` | none | bytes only when the card exists and the photo is shown (strong `ETag`, 304 on `If-None-Match`), or `notFound` |
| `GET /profiles?ids=…` | none | `batch.response`: request order, non-public ids silently omitted, 1–`batchMaxIds` ids |
| `GET /profiles/by-username/{u}` | none | `card`, or `notFound` |

- A card exists only for an active account with consent and a login. Unknown, banned, deleting,
  hidden and no-consent accounts give the same `notFound`, so the API is no existence oracle.
- `displayName` and `bio` are `null` unless the owner shows them; `avatarPresent` is `false` unless
  the owner shows the photo and has one.
- Withdrawal (`enabled:false`) ignores `textVersion`, clears every field flag and hides the card at once. Public reads are
  cached for at most 60 seconds.
- `publishReady` is true when consent is on and the account has a login: deck publication needs it.
- Batch and username lookups are rate limited (429 `try_later`).
