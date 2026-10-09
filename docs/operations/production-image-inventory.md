# Production image inventory

Status: **current**. Build image registry verification: **2026-10-02**;
new VPS dependency registry verification: **2026-10-04**.

Every external image used to build Mnema or run it on the production VPS has a readable version tag and an immutable OCI index digest. The tag explains the intended version during review; the digest is the runtime identity, so a later tag move cannot change the deployed bytes.

## Enforced surface

`scripts/verify_production_image_pins.py` checks:

- every `FROM` in `backend/Dockerfile`, `frontend/Dockerfile` and `deploy/production/Dockerfile` (the PostgreSQL base; restore binds to the admitted derived PostgreSQL image);
- `deploy/production/compose.yaml`: exactly four administrator-admitted image bindings (`frontend`, `identity-account`, `learning`, `postgres`), each a digest placeholder filled only from the verified candidate;
- Dependabot Docker coverage for `/backend`, `/frontend` and `/deploy/production`;
- that this inventory lists the tag and digest of every pinned source image.

Current VPS publication and rollout use exactly `identity-account`, `learning`,
`frontend` and `postgres`, all admitted by immutable GHCR digest. See
[VPS runtime](vps-runtime.md) and [publication](vps-image-publication.md).

## Verified build images

The pinned digest is a multi-platform OCI index. The final column proves that it contains the project's `linux/amd64` target; actual rollout acceptance separately proves that the VPS can pull and run its derived release images.

| Source | Path | Readable tag | Pinned index digest | `linux/amd64` child |
| --- | --- | --- | --- | --- |
| Backend build | `backend/Dockerfile` | `gradle:9.8.0-jdk25` | `sha256:7086a4cd10d568b35cafd6d5d30323865f3ce1f23ccddc9570ff3e3c9c8cd6e9` | `sha256:b7b9164dcd19fea3ddc6ef143159b056591f898c3ae32d3151a0f0fe0f22fe47` |
| Backend runtime stages | `backend/Dockerfile` | `eclipse-temurin:25.0.4.1_1-jre-resolute` | `sha256:628f28c18211e8633d02cefb9698489abcbd43337c61fba67837e4ffb86d50d6` | `sha256:bcc1a99b4bc676717bdc9841c363072ce10e3f34462c4017f7df08e730f485b0` |
| Frontend build | `frontend/Dockerfile` | `node:24.21.0-alpine` | `sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1` | `sha256:83f1c388c31fb2e51f7cbd4dea949b96260798c98f206e8e4696bc93bd964e3a` |
| Frontend runtime | `frontend/Dockerfile` | `nginx:1.31.6-alpine` | `sha256:df221db836e1754089190208cee7eeda94f233197056426eda74a43ab1abeac2` | `sha256:0530961ff0592b58c10f767535cc0abdfccf9e389ff7cc90f87320c1bc7e8506` |

## Frontend runtime security floors

The frontend's pinned nginx base is supplemented with security version floors
from the stable Alpine 3.24 repository. Publication run `37217508523` found
`CVE-2026-93990` in `libexpat 2.8.4-r0` and `CVE-2026-103111` in `pcre2 10.48-r0`.
Publication run `37892843818` found `CVE-2026-4775` (HIGH) in `tiff 4.7.1-r0`.
The image requires `libexpat>=2.8.5-r0`, `pcre2>=10.49-r0` and `tiff>=4.7.2-r0`, the first fixed builds
in [Alpine's security database](https://secdb.alpinelinux.org/v3.24/main.json).
The existing OpenSSL floors remain. No edge repository or scan exception is added;
provenance/SBOM and the HIGH/CRITICAL gate bind the actual derived release digest.
Future stable security patches may satisfy these floors when old package builds
are retired. A missing floor or failed image scan blocks publication.

## Backend dependency security floor

[Publication run 37220726794](https://github.com/MattoYuzuru/Mnema/actions/runs/37220726794)
passed frontend/PostgreSQL but rejected both backend images: Spring Boot 4.1.1's
Jackson 3.1.5 had five HIGH findings. Jackson core was affected by
`CVE-2026-89407` and `CVE-2026-89425`; databind by `CVE-2026-68497`,
`CVE-2026-91776` and `CVE-2026-91777`.

The root Gradle build overrides `jackson-bom.version` to the published
[Jackson 3.1.7 BOM](https://repo.maven.apache.org/maven2/tools/jackson/jackson-bom/3.1.7/jackson-bom-3.1.7.pom),
which covers all five findings on the existing 3.1 patch line. All Jackson 3 modules
remain aligned through Spring Boot's
[managed-version customization](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html#managing-dependencies.dependency-management-plugin.customizing).
Jackson 2, Spring Boot and the runtime base images retain their existing versions.
Serialization, HTTP/auth behavior and the full quality gate must pass with this BOM;
published backend digests still require provenance/SBOM and fresh vulnerability scans.
Remove the override only after Boot manages a fixed version and scans pass.

## VPS PostgreSQL base

The new empty-DB VPS uses the following official image. Registry Content-Digest
was checked against the index body SHA256; both amd64 and arm64 platform entries
were present. Runtime acceptance and vulnerability evidence remain separate gates.

| Component | Path | Readable tag | Pinned index digest | `linux/amd64` child |
| --- | --- | --- | --- | --- |
| VPS PostgreSQL | `deploy/production/Dockerfile` | `postgres:18.6-alpine3.24` | `sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873` | `sha256:d8703cd7fba306b9fec9268ecedfa8a966846c053036a60e3635791957eb2f66` |

## Intentional exclusions

- Local-only stacks (`compose.local-*.yml`, `deploy/local-full-stack/`, `backend/media-worker/Dockerfile.local`) are not production images.
- Mnema application digests are release outputs, not base-image inventory entries.
  The [VPS publication](vps-image-publication.md) verifies current main images;
  admission happens in the approved `deploy-production` job (see [production delivery](production-delivery.md)).

## Update and rollback

Dependabot owns routine Docker patch/minor proposals. For each update:

1. keep the explicit version tag and update its index digest together;
2. verify that the index contains `linux/amd64` with `docker buildx imagetools inspect <tag>@<digest>`;
3. run `python3 scripts/verify_production_image_pins.py` and its unit tests;
4. require the normal local/hosted PR quality gates, fresh four-image publication
   and the administrator/runtime acceptance in [production delivery](production-delivery.md).

If a pinned image regresses, restore its previous reviewed `tag@digest` pair through the same protected PR and verified VPS release flow. Do not retag, edit a live workload, or approve production to work around the failure.

References used for the contract: [Docker image digests](https://docs.docker.com/dhi/core-concepts/digests/), and [GitHub Dependabot supported ecosystems](https://docs.github.com/en/code-security/reference/supply-chain-security/supported-ecosystems-and-repositories).
