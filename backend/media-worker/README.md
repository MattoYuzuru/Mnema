# Mnema media worker (local development)

This one-shot process receives an immutable, sealed source file and a small manifest,
decodes the complete source, creates interoperable variants, decodes each variant,
hashes every output byte, then atomically publishes `result.json`. It never accesses
the application database, object store, or network. The caller keeps the original
source and owns the transition to `READY` after variant upload and a generation check.

Build the local image with `docker build -f Dockerfile.local -t mnema-media-worker:local .`
from this directory. The Ubuntu base digest and FFmpeg package version are pinned;
record the built image digest in the local integration configuration. Ubuntu 24.04
`arm64` uses `ubuntu-ports`, whose archive did not expose the APT snapshot option
in this verification, so transitive system packages can change between rebuilds.
On a legacy Docker builder on an arm64 host, the multiarch base digest may resolve
to arm64 even with `--platform linux/amd64`. For an amd64 test build, set
`--build-arg UBUNTU_BASE=ubuntu:24.04@sha256:496754492fb28b4d3049432f2ca787449331e23fb14f0dd3fffea86bf5a93eb4`
and `--platform linux/amd64`. The digest is the amd64 child of the Ubuntu 24.04
index and should be updated together with the default multiarch digest.
The worker supports Linux `amd64` and `arm64`; the build and fixture matrix must pass
on both platforms before a release image is considered. The image is **only for local
development**. Ubuntu FFmpeg is configured with `libx264`, making the FFmpeg build
GPL. Do not publish or redistribute this image until license counsel reviews the
specific build, corresponding source offer/source bundle, copyright notices, and
third-party patent exposure. Replacing `libx264` with `libopenh264` changes this
analysis; Cisco's patent terms apply to binaries downloaded from Cisco under their
conditions, not automatically to a server image built from the open-source library.

## Invocation contract

The caller writes `/work/source` and `/work/request.json` into a private job directory,
creates an empty `/work/output`, then runs the image as one job. The read-only source
should be a private regular file on disk, not a URL. Manifest keys are exact:

```json
{"formatVersion":1,"assetId":"3cbb01c5-a3c0-467d-8e87-ac3a4e7e19ba","generation":1,"kind":"video","expectedByteLength":123456,"expectedSha256":"64 lowercase hex digits","maxDurationMs":300000}
```

`maxDurationMs` is required for audio/video and must not exceed the worker ceiling;
it is `null` for images. Exit `0` plus `output/result.json` means success; exit `2`
plus a JSON `{status:"rejected",code}` on stderr means terminal invalid media; exit
`3` plus `{status:"retryable",code}` means runtime failure. The caller must verify
each listed output's full SHA-256 and size before writing to object storage. It must
reject unknown result version, path traversal, extra/missing variants, or mismatched
asset ID/generation; a worker success alone never makes an asset ready.

Example local invocation, with a private `$JOB` directory on a host path known to
be shared with the Docker VM and output owned/writable by UID 10001:

```sh
docker run --rm --network none --read-only --cap-drop ALL \
  --security-opt no-new-privileges --pids-limit 64 --memory 3g --memory-swap 3g \
  --cpus 2 --tmpfs /tmp:rw,size=64m,mode=1777 \
  -v "$JOB/source":/work/source:ro \
  -v "$JOB/request.json":/work/request.json:ro \
  -v "$JOB/output":/work/output:rw \
  mnema-media-worker:local \
  --manifest /work/request.json --source /work/source --output /work/output
```

The caller still needs a 30-minute wall watchdog and a 7-GiB disk-backed work
directory quota; Docker memory limits do not bound bind-mounted host disk usage.

Recommended local process boundary: one job per container, no network, non-root,
read-only root, Docker's default seccomp profile, all capabilities dropped,
2 CPUs, 3 GiB RAM, 7 GiB
disk-backed scratch with a filesystem quota, 30-minute wall-clock watchdog, one
job per container. Count source, output, and temporary files inside the quota.
`tmpfs` counts toward RAM and is unsuitable for the 4 GiB source ceiling. The caller
must delete the private work directory after committing or rejecting the result.
Make `/work/output` writable by the container's UID 10001 while keeping the source
read-only. On local Colima, `/tmp` on macOS was not bind-shared into Linux; use a
Docker named volume/`docker cp` or a verified shared host path for fixture staging.
The app's upload limits may be narrower than these independent safety ceilings.

## Learning service integration

The Java reconciler is disabled until `learning.media.processing.enabled=true`. Build
the local image above, configure the same `learning.media.upload.*` S3 credentials
used for uploads, and set `learning.media.processing.work-root` to a private host
directory shared with Docker (default `~/.mnema/media-processing`). The service
runs at most two local jobs at a time by default (`max-parallel`). It drains queued
jobs without an extra scan delay and prefers images and audio over video. The
container sees only a read-only source
and manifest plus a writable output directory; it has no object-store credentials
or network. The runner watches job disk usage and kills work above 7 GiB. Use an
operator-enforced filesystem quota and an immutable image digest for any external
deployment; the default `:local` tag is a development convenience.

`SEALED` plus `VERIFYING` is the durable queue. Migration V13 stores a claim token,
lease, attempt count and next attempt time. Heartbeats renew the lease. Database
publication checks the token, lease, asset generation and state. The backend
streams the frozen S3 object to a private file and checks its exact length and full
SHA-256 before invoking the worker. Java independently verifies the result's exact
profile set, output paths, sizes and hashes. Derived object keys include asset ID,
generation, processing token, profile and content hash, so a late GC delete cannot
target a later upload with the same SHA-256. Catalog blobs still deduplicate by
hash and byte length. Conditional `If-None-Match: *` PUT is reconciled by HEAD
with length, MIME and SHA metadata. One transaction stores source/variant blobs and
marks the asset READY. The sealed source remains available for retry.

After five transient attempts, the asset is `FAILED_RETRYABLE`. Its owner can call
`POST /media-assets/{assetId}/processing/retry` with `{ "generation": 0 }` to
requeue the same bytes. `REJECTED` is terminal for invalid media and requires a new
upload. Processing settings include `worker-timeout` (30 minutes), `lease` (2
minutes), `heartbeat` (30 seconds), `retry-base` (1 minute), `retry-maximum` (30
minutes), `max-attempts` (5), `max-parallel` (2), `max-audio-duration` (1 hour), `max-video-duration`
(5 minutes), and a 15-second scan interval. Upload byte ceilings live separately
under `learning.media.upload.*`; codec and frame ceilings are fixed worker policy.

The S3 choices follow the [AWS SDK Java 2 streaming guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/migration-streaming-ops.html),
[Amazon S3 conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html),
and [Yandex Object Storage PUT conditions](https://yandex.cloud/en/docs/storage/s3/api-ref/object/upload).
The container limit follows [Docker's resource constraint documentation](https://docs.docker.com/engine/containers/resource_constraints/).

The deterministic codec contract matrix is `python3 -m unittest discover -s tests -v`
inside the built image. For a bounded local run, supply `--network none --read-only
--cap-drop ALL --memory 3g --cpus 2 --tmpfs /tmp:rw,size=512m,mode=1777` to
`docker run` and override the entrypoint to `python3`. With `MNEMA_MEDIA_SLOW=1`,
`tests/test_slow_profiles.py` additionally checks a synthetic 4K HEVC Main10 HDR
clip and a synthetic five-minute 720p AV duration boundary. On the local arm64
2-CPU/3-GiB container they processed in 1.6 and 5.7–6.1 seconds respectively; these
low-motion synthetic inputs do **not** establish processing SLA for phone-recorded
five-minute 4K HDR/30–60 fps footage. A public iPhone 6 MOV fixture was also
verified under the same container limits; see
[`phone-worker.md`](../../docs/engineering/evidence/epic-76/phone-worker.md).
That fixture was stream-copied to approximately five minutes and does not prove
latency for an uninterrupted five-minute camera recording or for 4K HDR.

## Profiles

| Input | Playback | Auxiliary |
| --- | --- | --- |
| JPEG, PNG, WebP | WebP, longest edge at most 2048 | WebP thumbnail, 320 |
| GIF (animated, max 600 frames / 60 s) | GIF, longest edge at most 2048 | Static first-frame WebP poster/thumbnail, 320 |
| MP3, M4A/AAC, WebM/Opus | M4A/AAC, stereo, 48 kHz | — |
| MP4 H.264/AAC, MOV HEVC Main/Main10, WebM VP8/VP9+Opus | MP4 H.264/AAC, SDR, 1080p, max 30 fps | WebP poster |

Only file/pipe FFmpeg protocols are enabled. FFmpeg gets argv, never a shell.
MP3/M4A cover art and MOV timecode/data streams are ignored during playback
derivation; they do not replace the single required audio/video program stream.
Animated WebP is rejected explicitly by this FFmpeg 6.1 build because its decoder
does not preserve the animation; static WebP is supported.
Unexpected streams/codecs and probe metadata, duration/size/frame/pixel bounds,
full-decode errors, hash mismatches, and output profile mismatches fail closed. The
source remains unchanged for retry or download. The maximum source sizes are 64 MiB
image, 512 MiB audio, 4 GiB video; maximum audio/video durations are 1 h / 5 min.

Reference: [FFmpeg legal](https://ffmpeg.org/legal.html),
[Ubuntu snapshot service](https://ubuntu.com/server/docs/how-to/software/snapshot-service/),
[Docker resource constraints](https://docs.docker.com/engine/containers/resource_constraints/),
[FFmpeg protocol whitelist](https://ffmpeg.org/ffmpeg-protocols.html).
