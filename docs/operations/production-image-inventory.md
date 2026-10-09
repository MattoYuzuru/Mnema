# Production image inventory

Status: **current**. Build/legacy support registry verification: **2026-10-02**;
new VPS dependency registry verification: **2026-10-04**.

Every external image used to build Mnema or applied by the hosted production workflow has a readable version tag and an immutable OCI index digest. The tag explains the intended version during review; the digest is the runtime identity. Kubernetes accepts `tag@digest` and resolves by digest, so a later tag move cannot change the deployed bytes.

## Enforced surface

`scripts/verify_production_image_pins.py` derives the policy boundary from the sources used by `.github/workflows/production-deploy.yaml`:

- every `FROM` in `backend/Dockerfile` and `frontend/Dockerfile`;
- `k8s/postgres.yaml`, `k8s/redis.yaml`, and every manifest in `k8s/observability/`;
- the `identity-account` and `learning` release templates consumed by `scripts/render-release-manifest.sh`;
- the exact literal `kubectl apply` surface in the production workflow;
- Dependabot Docker coverage for `/backend`, `/frontend`, `/k8s`, and `/k8s/observability`.
- the new VPS `deploy/production/compose.yaml`: five administrator-admitted
  image bindings (the media worker's pinned Ubuntu base is checked in
  `backend/media-worker/Dockerfile`); the PostgreSQL Dockerfile base is pinned, and restore binds to
  the admitted derived PostgreSQL image; Dependabot also covers `/deploy/production`.

Current VPS publication and rollout use exactly `identity-account`, `learning`,
`frontend`, `media-worker` and `postgres`, all admitted by immutable GHCR digest. See
[VPS runtime](vps-runtime.md) and [publication](vps-image-publication.md). Kubernetes
manifests below remain source-pin contracts for the dormant legacy path, not the
current VPS topology.

VPS Compose takes exactly five administrator-admitted digest references. The
legacy Kubernetes renderer separately replaces its two application placeholders
and rejects unpinned manifests; this is a source contract, not a staging gate for VPS.

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

## VPS media worker base

The fifth VPS image, `media-worker` (`backend/media-worker/Dockerfile`), is built from the
Ubuntu base below, passed through the `UBUNTU_BASE` build argument that the pin verifier
reads (Dependabot cannot follow an `ARG`, so this pair is updated by hand together with the
FFmpeg package pins in the same Dockerfile and the codec matrix is rerun). Its `linux/amd64`
child was run on the Colima VM (`uname -m` reported `x86_64`) when the pin was recorded.
The image contains a GPL-configured FFmpeg: keep its registry package private
(see [licensing](../../backend/media-worker/README.md#licensing)).

Release scan policy checked locally before the first publication (2026-10-09, `aquasec/trivy:0.70.0`,
`image --scanners vuln --ignore-unfixed=false` on the `linux/amd64` build, same flags as the release
job): the pinned base alone had one HIGH (`CVE-2026-84782`, `libssl3t64 3.0.13-0ubuntu3.15`, fixed in
`3.0.13-0ubuntu3.16`), so the Dockerfile pins `libssl3t64` to the fixed version (`LIBSSL_VERSION`).
With it the image has 0 CRITICAL, 0 HIGH, 553 MEDIUM and 28 LOW findings (all MEDIUM/LOW are
`affected`/unfixed Ubuntu `universe` and base findings that the gate does not block), i.e. no release
exception is needed. A Debian base was not adopted: Ubuntu passes, and a different FFmpeg major would
change every encoded byte of the stored derivatives. The hosted publication run remains the gate;
drop `LIBSSL_VERSION` once the base digest includes the fix, and bump all pins together when one leaves
the archive.

| Component | Path | Readable tag | Pinned index digest | `linux/amd64` child |
| --- | --- | --- | --- | --- |
| VPS media worker | `backend/media-worker/Dockerfile` | `ubuntu:24.04` | `sha256:008173c23f95b170204355c12626cb5a965d779a7e1283b09e9cffbb1bf33ca3` | `sha256:496754492fb28b4d3049432f2ca787449331e23fb14f0dd3fffea86bf5a93eb4` |

## Legacy Kubernetes support pins

The following support images belong to the retained, disabled Kubernetes flow.

| Component | Path | Readable tag | Pinned index digest | `linux/amd64` child |
| --- | --- | --- | --- | --- |
| PostgreSQL | `k8s/postgres.yaml` | `postgres:16.15-alpine3.24` | `sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685` | `sha256:075f7ba66bc9b3ce7d6b8b635208ff61cd7cf1a67d71ec530eec5d7ae0cbe571` |
| Redis | `k8s/redis.yaml` | `redis:7.4.11-alpine` | `sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf` | `sha256:1db42ccef14898aa29bae778452d567534b59c107129cbc1163fb552de184d3c` |
| Prometheus | `k8s/observability/11-prometheus.yaml` | `prom/prometheus:v2.55.1` | `sha256:2659f4c2ebb718e7695cb9b25ffa7d6be64db013daba13e05c875451cf51b0d3` | `sha256:b1935d181b6dd8e9c827705e89438815337e1b10ae35605126f05f44e5c6940f` |
| Loki | `k8s/observability/21-loki.yaml` | `grafana/loki:2.9.8` | `sha256:8b5bd7748d0e4da66cd741ac276e485517514af0bea32167e27c0e1a95bcf8aa` | `sha256:101829cadac82fe8caef54319f46c2e72812834e7e934e830f729fdcc120cbf3` |
| Alloy | `k8s/observability/31-alloy-daemonset.yaml` | `grafana/alloy:v1.3.1` | `sha256:e5a674ee6b90d8d25d1adcdbcf885fa3bf6b592f3e2ab358b47431f3ca0e771f` | `sha256:2381097248235e37c34a727103cf9ad0e11767defdab6f37646cff09acd2dbaf` |
| Grafana | `k8s/observability/42-grafana.yaml` | `grafana/grafana:11.2.0` | `sha256:408afb9726de5122b00a2576763a8a57a3c86d5b0eff5305bc994ceb3eb96c3f` | `sha256:37a5d8860aef847dfa09f5f8947f010f6479f98cf7820b5186f9c6314b44be60` |
| kube-state-metrics | `k8s/observability/50-kube-state-metrics.yaml` | `registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.13.0` | `sha256:639a1e2da549210adddc0391ff91e270e83f7873014aec53258462812f741e6f` | `sha256:cfef7d6665aab9bfeecd9f738a23565cb57f038a4dfb2fa6b36e2d80a8333a0a` |
| node-exporter | `k8s/observability/51-node-exporter.yaml` | `prom/node-exporter:v1.8.1` | `sha256:fa7fa12a57eff607176d5c363d8bb08dfbf636b36ac3cb5613a202f3c61a6631` | `sha256:e91be75cf2b242f73fc28a609c4a09f5f0409e03c03456e2bfc224b98730d286` |
| Tempo | `k8s/observability/60-tempo.yaml` | `grafana/tempo:2.6.0` | `sha256:f55a8a1937fff0af3a760d376b476c8327fb30e432d5e7630d7938b67691e822` | `sha256:535a54902bf029b13795432866666b336a54c8ac3065aeb4002ee648fcc7b3ae` |

## Intentional exclusions

- `k8s/ai/` and local audio/image/AI gateways are not applied by the hosted production workflow.
- `k8s/staging/` and `k8s/backup/` have independent deployment contracts and remain covered by their own tests and Dependabot directories.
- Mnema application digests are release outputs, not base-image inventory entries.
  The new [VPS publication](vps-image-publication.md) verifies current main images;
  administrator admission and protected manual deployment remain separate.

Adding any new literal production apply path fails CI until its image-bearing sources are added to this policy. A mutable image in an excluded path does not weaken the production contract.

## Update and rollback

Dependabot owns routine Docker patch/minor proposals. For each update:

1. keep the explicit version tag and update its index digest together;
2. verify that the index contains `linux/amd64` with `docker buildx imagetools inspect <tag>@<digest>`;
3. run `python3 scripts/verify_production_image_pins.py`, its unit tests, and `./scripts/test-render-release-manifest.sh`;
4. require the normal local/hosted PR quality gates, fresh five-image publication
   and the administrator/runtime acceptance in [production delivery](production-delivery.md).

If a pinned image regresses, restore its previous reviewed `tag@digest` pair through the same protected PR and verified VPS release flow. Do not retag, edit a live workload, or approve production to work around the failure.

References used for the contract: [Docker image digests](https://docs.docker.com/dhi/core-concepts/digests/), [Kubernetes image names and digest precedence](https://kubernetes.io/docs/concepts/containers/images/), and [GitHub Dependabot supported ecosystems](https://docs.github.com/en/code-security/reference/supply-chain-security/supported-ecosystems-and-repositories).
