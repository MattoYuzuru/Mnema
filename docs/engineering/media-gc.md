# Media reachability and physical GC

Epic #76 uses two independent lifetimes. A `media_asset` is a logical, owner
scoped identity; a `media_blob` is an immutable SHA-256-verified object. Upload
staging and incomplete multipart transfers remain under the upload transport TTL
cleanup. V15 tracks sealed source objects and every derived PUT attempt in
`media_gc_object`; this also catches output uploaded before a failed DB commit.
Cataloged keys from before V15 are discovered incrementally.

## Holds and deletion sequence

An object is held while its blob belongs to any non-DELETED asset, an owner hold
is active, a published item or exercise revision references its asset, an active
draft references the asset, an unexpired generation session holds it
(`generation_media_ref`, #287: the asset of a proposal in the Workshop is held like a
draft until `generation_session.expires_at`), or an unexpired offline manifest pins the
blob. The
manifest also retains the logical asset's download route until its expiry.
sealed source of a current VERIFYING, PROCESSING or FAILED_RETRYABLE generation
also stays for worker or owner retry. A derived intent is held while its matching
worker token has a live lease. Expired manifest refs are removed only when the
blob is finalized for deletion; manifest metadata stays as an immutable audit
record. The normal `READY` owner hold lasts seven days; `expireUnattached`
tombstones an unreferenced asset only after that hold ends.

The scanner visits a bounded keyset page, checking all live roots for each key.
It stores the first successful unheld scan, requires a later completed scan epoch
and at least one hour before the second, and waits at least one day from the
first scan before deletion. A new manifest pin or verified-blob dedup resets the
candidate. Deletion starts with a DB claim: lock the blob row `FOR UPDATE`,
recheck all holds, and set a durable DELETING token. S3 DELETE happens outside
the transaction. A second transaction locks and rechecks again, removes only
DELETED asset/expired manifest references, deletes the blob row, and records a
DELETED receipt. Missing S3 objects and repeated DELETE are harmless. On storage
failure, DELETING remains fenced and is retried after the configured delay.

The manifest creator locks the blob `FOR SHARE`; V15's insert trigger rejects a
pin after DELETING begins. A processor locks the blob `FOR UPDATE` before SHA
dedup and refuses a DELETING blob. Derived keys contain asset ID, generation,
attempt token, profile and hash. Therefore a delayed DELETE for an old attempt
cannot erase a newly uploaded object with the same hash. Blob dedup remains a
catalog decision behind the logical asset ACL.

## Local configuration

`learning.media.gc.enabled` defaults to `false`, matching the explicitly
configured local S3/worker setup. Enable it only after the upload bucket and
credentials are configured. Other settings are scoped under `learning.media.gc`:

| Setting | Default | Meaning |
| --- | --- | --- |
| `grace` | `P1D` | Minimum time from first unheld scan to S3 DELETE; allowed 1–30 days |
| `scan-gap` | `PT1H` | Minimum time between the two scans; at least one hour, below grace |
| `scan-batch` | `32` | Maximum ledger keys examined per scan, 1–100 |
| `delete-batch` | `4` | Maximum S3 deletes per scheduled run, 1–20 |
| `delete-lease` | `PT15M` | Claim lease, 15–60 minutes; exceeds the 10-minute S3 timeout |
| `retry-delay` | `PT1H` | Delay after uncertain storage deletion, 15 minutes–1 day |
| `scan-interval` | `PT10M` | Scheduler interval; a complete epoch may take multiple runs |

The S3 delete and read decisions follow [Amazon S3 delete semantics](https://docs.aws.amazon.com/AmazonS3/latest/userguide/DeletingObjects.html)
and [Yandex Object Storage object deletion](https://yandex.cloud/en/docs/storage/s3/api-ref/object/delete).
Physical reclamation assumes an unversioned bucket: a key-only DELETE against a
versioned S3 bucket creates a delete marker and leaves older object versions.
If bucket versioning is enabled, configure a separate reviewed lifecycle policy
for noncurrent versions before treating the GC receipt as proof of reclaimed
storage bytes.
Local integration tests use PostgreSQL and MinIO, including a stale hash-dedup
worker, an active retry source, orphan output, published content/draft holds,
manifest expiry and a new pin racing deletion. The same code path has not been
exercised against Yandex Object Storage. Monitor `media_gc_*` structured events
and the count/age of FIRST, SECOND and DELETING rows. Do not manually clear a
DELETING ledger row while an S3 request may still be in flight.
