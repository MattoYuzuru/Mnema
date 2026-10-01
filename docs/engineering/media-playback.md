# Media playback and visible refresh (#239)

The Learning API exposes `GET /media-assets/{assetId}/playback` to the authenticated
asset owner. It returns `Cache-Control: private, no-store` and the logical asset
state. Only `READY` has signed `playback`, optional `poster`, and original
`download` sources with `url`, `expiresAt`, and verified `mimeType`. Pending and
failed states contain no storage URL. Another owner receives 404. The catalog
checks current-generation variants and content/draft/owner-hold reachability
before signing immutable blob keys. `learning.media.playback.url-ttl` defaults to
`PT1H` and accepts 5 minutes through 2 hours. The browser renews the URL before
expiry or after a media load failure. A signed URL is a bearer capability: do not
log, store in a document, or expose it in study answer data. The `download` URL
has attachment disposition; S3-compatible Range reads support seeking.

`NativeMediaSurfaceComponent` extracts asset IDs from the native document and
resolves them at the screen boundary. It checks unfinished assets with 2–15
second backoff only while the tab is visible and online. Focus, connectivity,
and visibility changes trigger a fresh check. It stops polling on terminal
states and route destruction. The library page checks its current server page
every 45 seconds while visible and on focus, retaining confirmed rows if that
request fails. This is a bounded read, not a push channel; a second device's
new deck appears on its first library page within the next visible check.

Images open in a native modal with keyboard zoom, scrollable pan, and authorized
download. GIF animation starts only on explicit request if the worker supplies a
static poster. Local audio/video use Mnema controls over browser media elements;
YouTube loads a privacy-enhanced iframe only after a viewer action and always
offers an external source link. The author stores only a validated YouTube ID.
The CSP permits that one frame origin. The author-provided transcript is shown
as a separate text alternative; it is not synchronized captions. Listening
exercise answer disclosure follows its separate evidence policy.

Listening Study resolves each pinned audio cue through the same owner-authorized
API. It renders the shared Mnema player, blocks answer submission until every
cue has a playable URL, renews before `expiresAt`, and rechecks on focus or media
error. A pending or failed source keeps the answer unavailable and retries with
a bounded visible timer. The signed source never enters the exercise snapshot
or local recovery record.

Local rollback: revert the playback controller, reader components, and the
YouTube native node in one protected squash change. Existing immutable media
objects remain reachable through the catalog and do not require deletion.

Tests: `./gradlew :services:learning:test --tests '*MediaPlaybackControllerTest'
--tests '*MediaUploadIntegrationTest'` with PostgreSQL/MinIO; `npm test` for
reader controls, URL refresh, and YouTube validation; full repository quality
gate. Yandex Object Storage browser/CORS parity and live visual inspection
remain local-environment acceptance checks, not deployment claims.
