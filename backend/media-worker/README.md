# Mnema media worker

The image contains the **one-shot CLI** (`python3 -m mnema_media_worker`, the entrypoint). It receives an
immutable, sealed source file and a small manifest, decodes the complete source, creates
interoperable variants, decodes each variant, hashes every output byte, then atomically
publishes `result.json`. It never accesses the application database, object store, or
network. It is started **once per job, in its own throw-away container**, by the trusted root
media runner (`deploy/production/mnema-media-runner.py`); no container of this image outlives
its job. The caller keeps the original source and owns the transition to `READY` after variant
upload and a generation check.

The same image is used by the local full stack (`compose.local-full-stack.yml`, `media-runner`) and by
production (the fifth release image, admitted and pulled by the dispatcher, run by the
`mnema-media-runner` service). Build it with
`docker build -t mnema-media-worker:local .` from this directory. The Ubuntu base digest
and the FFmpeg and Python package versions are pinned (`ARG`s in the `Dockerfile`; a pinned
package version leaves the archive when it is superseded, so a failing build means bumping
the FFmpeg pins together and rerunning the codec matrix). Ubuntu 24.04 `arm64` uses
`ubuntu-ports`, whose archive did not expose the APT snapshot option in this verification,
so transitive system packages can change between rebuilds. On a legacy Docker builder on
an arm64 host, the multiarch base digest may resolve to arm64 even with
`--platform linux/amd64`. For an amd64 test build, set
`--build-arg UBUNTU_BASE=ubuntu:24.04@sha256:496754492fb28b4d3049432f2ca787449331e23fb14f0dd3fffea86bf5a93eb4`
and `--platform linux/amd64`. The digest is the amd64 child of the Ubuntu 24.04
index and should be updated together with the default multiarch digest.
Production and CI use `linux/amd64` only; `linux/arm64` exists for local development on
Apple silicon. The build and the codec matrix were run on both; a release image is the amd64 one.

## Licensing

Ubuntu's FFmpeg is configured with `--enable-gpl` and links `libx264` and `libx265`, so
the whole binary is GPL, not only the video path. Mnema runs it on its own server and does
not convey it: that is why the image may be built and run privately. **Do not make the
registry package public and do not redistribute the image** until license counsel reviews
the specific build, the corresponding-source offer or bundle, the copyright notices and
third-party patent exposure. The owner approved this position: the publication workflow pushes
it to GHCR like the other images, and the package stays **private** (a new GHCR package is
private by default and a linked repository changes access, not visibility:
[GitHub: package visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility)).
The VPS stores no registry credential; the release job passes its short-lived token to the
dispatcher's `pull` (`deploy/production/README.md`, "Media worker"). Check visibility with
`gh api /user/packages/container/mnema%2Fmedia-worker --jq .visibility` (must print `private`).

What the pipeline actually encodes decides whether the GPL part can be avoided:
images encode WebP (`libwebp`, BSD) and GIF (native), audio encodes AAC with FFmpeg's
native `aac` encoder (LGPL), and **video encodes H.264 with `libx264`** (GPL). Decoders
(H.264, HEVC, VP8/VP9, Opus, MP3) are native. So images and audio alone could run on an
LGPL-only FFmpeg built from source; video cannot, unless `libx264` is replaced. Replacing
it with `libopenh264` changes this analysis (BSD code, but Cisco's patent terms apply to
the binaries downloaded from Cisco under their conditions, not automatically to a
self-built server image) and changes the output profile (`video_h264_aac_sdr_1080_v1`:
no CRF/preset rate control, different bytes), so it is a product and legal decision, not a
refactor.

## One-shot CLI contract

The runner executes this CLI as `--manifest /job/in/request.json --source /job/in/source --output /job/out`:
`/job/in` is a read-only, runner-made directory holding the source and a normalized request,
`/job/out` is the only writable path. The source should be a private regular file on disk, not
a URL. Manifest keys are exact:

```json
{"formatVersion":1,"assetId":"3cbb01c5-a3c0-467d-8e87-ac3a4e7e19ba","generation":1,"kind":"video","expectedByteLength":123456,"expectedSha256":"64 lowercase hex digits","maxDurationMs":300000}
```

`maxDurationMs` is required for audio/video and must not exceed the worker ceiling;
it is `null` for images. Exit `0` plus `output/result.json` means success; exit `2`
plus a JSON `{status:"rejected",code}` on stderr means terminal invalid media; exit
`3` plus `{status:"retryable",code}` means runtime failure. The caller must verify
each listed output's full SHA-256 and size before writing to object storage. It must
reject unknown result version, path traversal, extra/missing variants, or mismatched
asset ID/generation; a worker success alone never makes an asset ready. SIGTERM unwinds
the CLI so its FFmpeg sessions are killed with it.

## Media runner and job protocol (v1)

**Threat model.** FFmpeg runs on hostile input, so any process that touches it is treated as compromised
(an FFmpeg remote-code-execution bug). A long-lived worker cannot be made safe: its codec children share
an identity with it, can forge its answers, leave undeletable directories and persist across jobs. So the
design gives an attacker nothing that outlives its job, and lets only root write a verdict:

1. **One container per job.** The runner (a root host service, `deploy/production/mnema-media-runner.py`,
   hardened by `mnema-media-runner.service`) runs `docker run --rm` with no network, a read-only root, all
   capabilities dropped, `no-new-privileges`, UID 10002, 64 PIDs, 3 GiB RAM without swap, 2 CPUs, a 64 MiB
   `noexec` tmpfs, `--pull never`, and exactly two mounts: the runner-made `in/` (read-only) and `out/`
   (owned by 10002). The image comes only from the root-owned release manifest of the release the dispatcher
   recorded as current (`ghcr.io/mattoyuzuru/mnema/media-worker@sha256:...`); nothing in the work directory
   can name an image. The container is killed and removed on timeout (30 minutes), on Learning's `cancel`
   marker, when the job directory disappears, when `out/` grows past its cap, and when the runner stops;
   the runner then checks that it is really gone.
2. **Only root writes the verdict.** After the container is gone the runner validates `out/` as root
   (regular files only, an exact allowlist of names, at most four entries, size and total caps), copies the
   files into Learning's `output/` (owned by Learning), and only then atomically writes `status.json`
   (owned by root). Learning accepts a verdict only from a root-owned file, so nothing unprivileged can forge
   one. Any invalid output is a retryable `output_invalid` and nothing is copied. The scratch tree is then
   removed as root with `rm -rf` (iterative, so a tree nested thousands deep cannot exhaust Python's recursion
   limit; directories with no permissions cannot persist) and only after Docker confirmed the container is gone: a
   container that cannot be killed or removed keeps its scratch and is retried every loop. A restart of the runner
   removes every leftover `mnema-media-job-*` container and, if Docker answered and none remains, every scratch
   directory; it never treats an unanswered Docker as "no containers".
3. **The runner trusts Learning's job only for its schema.** It reads `request.json` (O_NOFOLLOW, 4 KiB cap,
   exact keys, canonical UUID, size and duration ceilings) and `source` (O_NOFOLLOW, regular file, the declared
   size) through directory descriptors, so a link or FIFO is never followed or waited on, and it hands the
   container a *re-serialized* request and a root-made copy of the source. Only job directories owned by Learning
   (UID 10001) are considered. The scratch lives in a root-only directory (`.runner`) of the bounded filesystem
   that Learning cannot enter.
4. **Learning still trusts nothing it reads.** Each result file is read once, never through a link, only if
   regular and size-capped, and copied while hashed into `<spool>/.private/` (mode 0700) before upload.
5. **A bounded filesystem.** The work directory is the root of its own 20 GiB, fixed-inode loopback ext4
   (`deploy/production/media-work.mount`), owned by root; the runner refuses to run jobs, the dispatcher refuses to
   deploy and the health monitor alerts unless it is a separate mount (and when space or inodes run low). Only the
   media features depend on that mount: docker.service does not.

Protocol per job (Learning, in its private spool `<work>/spool/media-*`, mode 0700): download and hash
`source`; write `request.json` (created exclusively), an empty `output/`, then atomically rename
`submitted.tmp` to `submitted`. The runner writes a root-owned `claimed` file when it starts (Learning then
restarts its wait budget, so queue time does not count), and finally `status.json`:
`{"formatVersion":1,"exitCode":0|2|3,"code":null|"snake_case"}`. Exit 0 is success, exit 2 with a valid code is
terminal invalid media, everything else (exit 3, an unknown code, a malformed, linked, FIFO, oversized or
non-root status) is retryable. Learning waits `worker-timeout` (30 minutes by default) plus 60 seconds; on
timeout or interruption it writes `cancel`. The runner touches a root-owned heartbeat
(`/var/lib/mnema-media-runner/heartbeat`) that the health monitor checks. Learning removes `media-*`
directories older than two hours (`stale-job-age`) that none of its jobs owns and, at startup, every leftover
job and private copy, which also stops a job still running for it.

Residual risks, stated plainly: the runner is root and parses small, schema-checked input; a bug in it or in the
Docker daemon is a root compromise, which is why it is short, stdlib-only, and descriptor-based. A compromised
container can burn its own CPU and RAM for up to 30 minutes within the container limits, fill `out/` up to its
cap (bounded by the filesystem), and make its job fail or return wrong-but-schema-valid derivatives for that one
asset: its output is syntactically validated, not semantically (Learning verifies hashes and profiles, not that
the pixels are the user's). Kernel and container-runtime escapes are out of scope. The local development stack
runs the same script in a container with the Docker socket (a documented development-only exception).

## Learning service integration

The Java reconciler is disabled until `learning.media.processing.enabled=true`. Set
`learning.media.processing.work-root` to Learning's private spool (default `~/.mnema/media-processing`; production:
the `spool` subdirectory of the work filesystem, mounted at `/var/lib/mnema-media`); the runner finds the jobs there.
Configure the same `learning.media.upload.*` S3 credentials used for uploads. `SpoolMediaWorkerGateway` is the Learning
half of the protocol above. The service runs at most `max-parallel` jobs at a time (production: 1), drains queued
jobs without an extra scan delay and prefers images and audio over video. Only Learning holds object-store
credentials. The image is pinned by digest through the release manifest; the `:local` tag is a development convenience.

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
minutes), `stale-job-age` (2 hours), `max-attempts` (5), `max-parallel` (2), `max-audio-duration` (1 hour), `max-video-duration`
(5 minutes), and a 15-second scan interval. Upload byte ceilings live separately
under `learning.media.upload.*`; codec and frame ceilings are fixed worker policy.

The S3 choices follow the [AWS SDK Java 2 streaming guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/migration-streaming-ops.html),
[Amazon S3 conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html),
and [Yandex Object Storage PUT conditions](https://yandex.cloud/en/docs/storage/s3/api-ref/object/upload).
The container limit follows [Docker's resource constraint documentation](https://docs.docker.com/engine/containers/resource_constraints/).

The image carries no tests; CI mounts `tests/` read-only into it and runs the codec matrix with
`scripts/require_no_skips.py` (a skipped test fails the build). The runner is tested separately,
against a fake `docker` binary, in `scripts/tests/test_media_runner.py`. The
deterministic codec contract matrix is `python3 -m unittest discover -s tests -v`
inside the built image (tests mounted as above). For a bounded local run, supply `--network none --read-only
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
| MP3, M4A/AAC, WebM/Opus, WAV PCM s16le (synthesised speech; the Java caller accepts it only for assets it staged itself, origin `generated`) | M4A/AAC, stereo, 48 kHz | — |
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
