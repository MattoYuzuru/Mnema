# Media upload transport (#235)

The canonical Learning runtime owns direct S3-compatible transfers. This is the
transport boundary for [Epic #76](epic-76-refinement.md); verified bytes, codecs,
variants and processing jobs belong to #236. The former media service is not used.

## API and state

All routes are under `/api/media-assets`, require the current Learning bearer
identity, and return `Cache-Control: private, no-store`. The caller must treat
presigned URLs as short-lived bearer capabilities and never log or persist them.

| Request | Purpose |
| --- | --- |
| `POST /upload-intents` `{intentId,origin,kind,mime,byteLength}` | Reserve a stable asset and return its first transfer. Exact owner/intent replay returns the original attempt; changed body returns 409. |
| `GET /{assetId}/upload` | Current-generation upload state and live asset lifecycle state for bounded polling. |
| `POST /{assetId}/upload/url` `{generation}` | Reissue a signed single PUT URL while the owner's current session is `OPEN`, including after a tab reload. |
| `GET /{assetId}/upload/parts?generation=N` | Server-observed part numbers for multipart resume. |
| `POST /{assetId}/upload/part-urls` `{generation,firstPart,count}` | Issue up to 16 signed part URLs. |
| `POST /{assetId}/upload/finalize` `{generation,commandId}` | Freeze a complete source, then atomically mark the session `SEALED` and asset `VERIFYING`. |
| `DELETE /{assetId}/upload?generation=N` | Fence/cancel the current attempt and arrange cleanup. |
| `POST /{assetId}/upload/retry` `{commandId,kind,mime,byteLength}` | From retryable failure or rejection, increment generation and create a fresh transfer. |

Transfer states: `INITIATING → OPEN → FINALIZING → SEALED`; cancellation or
expiry enters `ABORTING`/`EXPIRED`, and cleanup records `ABORTED`. `SEALED` means
stable source bytes await #236 verification, **not** that the media is ready for
readers. Every response includes `state` for the transfer plus `assetState` and
`currentGeneration` from the owner-scoped asset. Poll `GET /{assetId}/upload`
while the asset is `VERIFYING` or `PROCESSING`; it can later show `READY`,
`REJECTED`, or `FAILED_RETRYABLE` without a page reload. The asset's
`PENDING_UPLOAD → VERIFYING` transition is in the same DB
transaction as session sealing. No S3 request runs inside that transaction.
The status response never includes a signed URL. A resumed client reads the
current generation from status, then requests `/upload/url` for `SINGLE` or
`/upload/parts` plus `/upload/part-urls` for `MULTIPART`. Renewal does not
change the asset generation and fails for another owner, a stale generation,
an expired session, or a finalized transfer.

The server signs `Content-Length` for each PUT; the browser must send exactly the
declared `Blob` or part slice. The server still checks actual object/part lengths
at finalize, because the first check is provider-dependent. `ETag` is an S3
version/part token, never a SHA-256 content claim. The client receives signed
headers and `urlExpiresAt` (or per-part `expiresAt`) alongside each URL and must
send any settable headers exactly as given. The server persists the latest actual
signed expiry before returning the URL; cleanup adds its grace period to that
timestamp.

Objects smaller than the multipart threshold use PUT to a unique staging key.
Since that URL can overwrite its target until expiry, finalize uses `HEAD` then
conditional server-side `CopyObject` to a key for which no write URL is ever
issued. The durable copy claim permits only one copy request per frozen key;
after an uncertain timeout, reconciliation uses HEAD. If the frozen object is
still absent after the finalization lease, the attempt becomes retryable rather
than risking a late overwrite. Larger objects use server-owned Create/ListParts/Complete/Abort; Complete
consumes the upload ID. On an uncertain Complete or Copy outcome, retry first
checks the frozen object. A stale generation cannot seal into the asset. The
internal `MediaUploadService.sealedSource(assetId,generation)` yields
`(sessionId,ownerId,kind,declaredMime,declaredLength,objectKey)` only for the
current generation with a sealed session and asset in
`VERIFYING`/`PROCESSING`/`READY`. #236 must verify the bytes before adding a
`media_blob`; after that, source retention is governed by its job and #242 GC.

The transfer table records URL issue expiry, session expiry, and finalization
lease. Cleanup waits for the last signed URL **plus a grace period**, then
retries abort/delete. Bucket lifecycle aborting incomplete multipart transfers
is still required: a crash between S3 Create and DB receipt can leave an upload
whose ID the DB never saw. A cancelled single PUT cannot be revoked; its old
staging key remains isolated and later cleanup removes it. Verified shared blobs
are never deleted by this worker.

## Scoped policy keys

Values are typed and validated in `MediaUploadSettings`. Bytes are binary units.
The defaults are an initial local policy, not measured production capacity.

| Key under `learning.media.upload.` | Default | Validation / effect |
| --- | --- | --- |
| `max-image-bytes` | 67,108,864 (64 MiB) | Positive; upload admission only. |
| `max-audio-bytes` | 536,870,912 (512 MiB) | Positive; upload admission only. |
| `max-video-bytes` | 4,294,967,296 (4 GiB) | Positive; upload admission only. Video duration is #236 policy. |
| `max-reserved-bytes` | 8,589,934,592 (8 GiB) | At least every per-kind cap; active owner reservations. |
| `max-active-uploads` | 3 | 1–100 per owner; includes unfinished cancelled transfers until cleanup. |
| `multipart-threshold` | 104,857,600 (100 MiB) | 5 MiB through video cap and at most 5 GB; single PUT below it. |
| `part-size` | 16,777,216 (16 MiB) | 5 MiB through 5 GB; each per-kind cap must fit in 10,000 parts. |
| `url-ttl` | PT15M | Greater than zero, at most 1 hour; renewable while session is open. |
| `session-ttl` | PT24H | Greater than URL TTL, at most 7 days. |
| `storage-timeout` | PT10M | 1–30 minutes for one S3 operation. |
| `finalize-lease` | PT15M | Longer than storage timeout, at most 1 hour. |
| `cleanup-grace` | PT15M | 0–2 hours after the last URL expires. |
| `cleanup-initial-delay` / `cleanup-interval` | PT1M / PT5M | Scheduler cadence; deployment tuning. |

`endpoint`, `region`, `bucket`, `access-key`, `secret-key` configure the storage
provider. The endpoint must be HTTPS, except explicit `allow-loopback-http=true`
for local MinIO. It must be the browser-reachable host: changing the host after
signing invalidates the signature. Storage credentials and object keys never
appear in the public JSON response. The bucket remains private. Configure its
CORS for the exact frontend origin and PUT with the signed headers; test this in
a browser before connecting the authoring UI. Configure incomplete multipart
lifecycle expiry as an infrastructure prerequisite.

Learning adds AWS SDK for Java 2.55.10 to its own Gradle module because Java's
HTTP client does not supply SigV4 presigning or S3 multipart protocol; this is
the same pinned SDK version already used by the identity-account avatar service.
No module or legacy media runtime is reused.

## Production object storage

Production uses Yandex Object Storage (`https://storage.yandexcloud.net`, region
`ru-central1`, path-style). The media bucket `mnema-prod-media-b1g0dnrijqn8` is
private, versioning off, default SSE-KMS, writable only by the `mnema-learning-media`
service account, with CORS allowing `PUT`, `GET`, `HEAD` and the headers
`content-type` and `x-amz-*` for `https://mnema.app` only, and a lifecycle rule that
aborts incomplete multipart uploads after 2 days. Learning stays disabled
(`LEARNING_MEDIA_UPLOAD_BUCKET` empty) until the processing worker exists (#380).

AWS SDK for Java 2.30+ adds `x-amz-sdk-checksum-algorithm` and an `aws-chunked`
`x-amz-trailer` CRC32 to uploads by default, which Yandex Object Storage does not
document. `MediaObjectStore` (and the Identity `AvatarStorage`) therefore build the
`S3Client` with `requestChecksumCalculation(WHEN_REQUIRED)` and
`responseChecksumValidation(WHEN_REQUIRED)`; `MediaObjectStoreWireTest` and
`AvatarStorageWireTest` assert on a loopback stub that PUTs and presigned URLs carry
no checksum header or parameter. The object-store operations are verified against the
real bucket by the opt-in live check in
[VPS runtime](../operations/vps-runtime.md#object-storage-live-check).

Local protocol check: from `backend/`, use Java 25 and an available Docker
daemon, then run
`./gradlew :services:learning:test --tests 'app.mnema.learning.media.MediaUploadIntegrationTest'`.
The fixture uses PostgreSQL 18 and the repository-pinned MinIO image. It covers
single freeze/replay, signed length, multipart resume/complete, generation
fencing, owner isolation, and reserved quota. Server-side Yandex parity for
conditional CopyObject, presigned `Content-Length` and multipart is covered by the
opt-in live check; browser CORS still needs a real-browser check before the
authoring UI is enabled.

References: [Yandex multipart](https://yandex.cloud/en/docs/storage/s3/api-ref/multipart),
[limits](https://yandex.cloud/en/docs/storage/concepts/limits),
[CopyObject](https://yandex.cloud/en/docs/storage/s3/api-ref/object/copy),
[CORS](https://yandex.cloud/en/docs/storage/operations/buckets/cors),
[AWS Java presigning](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html).
