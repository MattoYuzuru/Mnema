# Exact pinned CI image acquisition — 2026-09-06

Resolved the local Docker Hub pull blocker without changing repository pins,
Docker/Colima settings, proxies, registries or installed tools. Both required
images now resolve in the local Docker cache by their exact original RepoDigest
and support `linux/arm64`. The lead owns the subsequent purge/full quality gate;
image acquisition alone is not a successful integration-test claim.

| Image requested by the existing purge test | Verified original index digest | Verified arm64 manifest digest |
| --- | --- | --- |
| `redis:7.4.11-alpine` | `sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf` | `sha256:f8d15882ba108587477ce13c00ab0551933a84138427b7cc9abadfbe45ffd973` |
| `minio/minio` | `sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e` | `sha256:9966a92a734f9411e32f4f41d7d9d826fcdc0f68c4e20b70295bd4e7c11f8a2f` |

## Cause and bounded method

The lead observed repeated daemon-side outbound TCP/443 timeouts pulling Redis.
An independent host request to `https://registry-1.docker.io/v2/` returned HTTP
401 promptly, the normal anonymous registry authentication challenge. Local
Docker 29.5.2 uses the containerd snapshotter/image store; Colima is arm64.
This supports a host download and OCI archive import without altering networking.
No deeper network root cause or global network repair is claimed.

The DevOps skill guided the bounded local change and supply-chain verification.
Existing host `curl` and Python standard library were used by a temporary script:

`/Users/m.ryabushkin/Projects/personal/mnema-r74-images-6tnAso/download.py`

It requests an anonymous repository-scoped pull token from Docker Hub, downloads
the manifest by the exact pinned digest, selects the arm64 descriptor, and
downloads its config/layers from the same registry. Every downloaded manifest,
config and layer is checked against SHA-256; all descriptor-backed objects also
have their byte size checked. Config OS/architecture is checked before archive
creation. Registry bearer tokens remain in process memory and curl stdin; they
are not printed, saved or forwarded to a different redirect host. Only HTTPS
redirects are allowed. Four bounded download workers, connection/request deadlines
and a 1 GB aggregate selected-object ceiling limit the operation.

The original index bytes are preserved, rather than synthesizing a new platform
manifest under the old digest. OCI layout annotations name the Redis version tag
and a local `minio/minio:r74-pinned` alias. The latter is only a local alias for
the verified pinned index, not an alternative version. Archives contain the
selected platform's data; unrelated platform layers were not downloaded.

Redis's first import reported a missing arm64 attestation manifest. The downloader
was extended to include the original matching attestation/config/layers, with the
same digest/size verification. The second import succeeded. No digest was removed,
rewritten or substituted to accommodate that failure.

## Successful commands and verification

From the temporary directory, using the existing script:

```sh
python3 download.py library/redis sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf 7.4.11-alpine
python3 download.py minio/minio sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e r74-pinned
docker image load --platform linux/arm64 --input library-redis.tar
docker image load --platform linux/arm64 --input minio-minio.tar
docker image inspect redis:7.4.11-alpine@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf --format '{{json .RepoDigests}} {{.Os}}/{{.Architecture}}'
docker image inspect minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e --format '{{json .RepoDigests}} {{.Os}}/{{.Architecture}}'
```

Both imports and both inspections exited zero. Inspection returned respectively
`redis@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf`
and `minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e`,
each with `linux/arm64`. `scripts/test-purge-rehearsal-integration.sh` uses normal
`docker run` with these exact references, so its normal image lookup can use this
verified cache; no test bypass or script edit is required.

Temporary OCI objects, script and archives remain only under the uniquely created
`mnema-r74-images-6tnAso` directory above. The final Redis archive is 17,315,840 B;
MinIO is 57,579,520 B. No containers, images, volumes or user files were deleted.
No production/shared containers were started or changed. The imported image cache
and temporary artifacts are retained for the lead's tests and inspection.

Official sources consulted: [Docker image load](https://docs.docker.com/reference/cli/docker/image/load/)
for archive/platform import; [Distribution API V2](https://distribution.github.io/distribution/spec/api/)
for digest-addressed manifests/blobs; [OCI image layout](https://github.com/opencontainers/image-spec/blob/main/image-layout.md)
for content-addressed archive structure. Successful exact-digest inspection is the
local proof that this engine preserved the requested identity.
