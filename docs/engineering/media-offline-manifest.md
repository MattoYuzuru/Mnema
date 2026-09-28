# Deck media manifest (Epic #76)

`GET /decks/{deckId}/media-manifests/current` creates or returns the owner's
immutable inventory for the current deck head. `GET
/decks/{deckId}/media-manifests/{manifestId}` reads a pinned older snapshot
until its expiry. Both endpoints require the deck owner, return `private,
no-store`, and support `If-None-Match` with a strong ETag. A missing or foreign
deck or manifest returns the same 404 boundary.

The JSON document has `schemaVersion`, `manifestId`, `version`, `deckId`,
`deckRevisionId`, and ordered `assets`. Each asset carries its logical ID,
generation, state, and current item/exercise revision references. READY assets
list the original and every verified variant with purpose/profile, blob and
variant IDs, SHA-256, byte length, MIME, and available dimensions/duration.
Pending and failed assets have no downloadable variants and remain explicit in
the inventory. A changed deck revision or asset state/variant identity creates
a new version and ETag; earlier document bytes do not change.

The manifest carries no S3 object key or temporary URL. A client obtains a
short-lived URL just before transfer from `GET /media-assets/{assetId}/playback`
for the original or `GET /media-assets/{assetId}/variants/{variantId}/download`
for a derived variant. These routes recheck owner and reachability. It must
verify the downloaded byte length and SHA-256 against the pinned manifest,
write into a staging directory, then atomically publish a complete snapshot.
The reference verifier `backend/scripts/media_offline_install.py` and its
synthetic tests exercise retry, missing/corrupt bytes, immutable identity, and
pointer preservation. Native iOS/Android transport and storage are outside
this epic.

Snapshot retention defaults to 90 days (`learning.media.manifest.retention`,
allowed 1–365 days). `max-references` defaults to 50,000, `max-assets` to
10,000, and `max-document-bytes` to 8 MiB under the same
`learning.media.manifest` prefix; startup rejects values above the documented
bounds. Each active snapshot holds its verified blobs against GC and its logical
asset routes against unattended tombstoning, even after a content reference
and the seven-day owner hold end. Expired snapshots return 404; GC can release
their blob holds, then
delete unreachable bytes under its two-scan grace and fencing rules. Clients
must refresh the manifest before its expiry and must not assume old signed URLs
remain valid. No production bucket or deployment is involved.

Rollback is a protected code revert after stopping local workers. Keep V14 and
stored manifests if local writes occurred; dropping immutable history or
deleting objects is not part of a code rollback.
