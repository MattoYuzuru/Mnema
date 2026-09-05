# Production image inventory

Status: **current**. Last registry verification: **2026-08-30**.

Every external image used to build Mnema or applied by the hosted production workflow has a readable version tag and an immutable OCI index digest. The tag explains the intended version during review; the digest is the runtime identity. Kubernetes accepts `tag@digest` and resolves by digest, so a later tag move cannot change the deployed bytes.

## Enforced surface

`scripts/verify_production_image_pins.py` derives the policy boundary from the sources used by `.github/workflows/production-deploy.yaml`:

- every `FROM` in `backend/Dockerfile` and `frontend/Dockerfile`;
- `k8s/postgres.yaml`, `k8s/redis.yaml`, and every manifest in `k8s/observability/`;
- the `identity-account` and `learning` release templates consumed by `scripts/render-release-manifest.sh`;
- the exact literal `kubectl apply` surface in the production workflow;
- Dependabot Docker coverage for `/backend`, `/frontend`, `/k8s`, and `/k8s/observability`.

Since #143, Main CI ships only Identity & Account and Learning in maintenance; production promotion is disabled until #147. The production support image inventory below describes retained deployed infrastructure, not an enabled replacement production rollout.

The application images are the only source placeholders allowed. The renderer replaces each one with the staging-approved GHCR digest and rejects the rendered release if any `image:` is not digest-pinned.

## Verified build images

The pinned digest is a multi-platform OCI index. The final column proves that it contains the project's `linux/amd64` target; the exact staging rollout then proves that the real cluster can pull and run it.

| Source | Path | Readable tag | Pinned index digest | `linux/amd64` child |
| --- | --- | --- | --- | --- |
| Backend build | `backend/Dockerfile` | `gradle:8.14.5-jdk21` | `sha256:94452354d9218922457d82e85a343391bab351e7f518f6f5ab1db996967d238b` | `sha256:1bc1e5f75910f1af39a110d78abdf47e5ff7fd07d074b737e02b0d8a6a9b91a5` |
| Backend runtime stages | `backend/Dockerfile` | `eclipse-temurin:21.0.12_8-jre-resolute` | `sha256:097b5c0e8b5c9cc402e871a87a35f20e9413af9159410db2b1bdd8b78dcca7ed` | `sha256:d1d7f43094ea37e9ae77ea3ca40026bc1de1bf1dc2cad37b08d66b9a016a2ea6` |
| Frontend build | `frontend/Dockerfile` | `node:22.23.2-alpine` | `sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32` | `sha256:76789712cd1ae89a1225eac9077010d68987a423588042dac30446f502f1858c` |
| Frontend runtime | `frontend/Dockerfile` | `nginx:1.31.4-alpine` | `sha256:db35bfc6b2951e7f8a72db5db120288c127ffaeeb4a6d4b95a26fead017d5913` | `sha256:1f25fedd50aec27413031afb3a4f8ee4effcc9d843f6a76e81bfa92245ac5c06` |

## Verified production support images

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
- Mnema application digests are release outputs, not base-image inventory entries. Staging creates and validates the complete release artifact before production can preview it.

Adding any new literal production apply path fails CI until its image-bearing sources are added to this policy. A mutable image in an excluded path does not weaken the production contract.

## Update and rollback

Dependabot owns routine Docker patch/minor proposals. For each update:

1. keep the explicit version tag and update its index digest together;
2. verify that the index contains `linux/amd64` with `docker buildx imagetools inspect <tag>@<digest>`;
3. run `python3 scripts/verify_production_image_pins.py`, its unit tests, and `./scripts/test-render-release-manifest.sh`;
4. require the normal PR quality gates and a successful staging rollout/smoke on the exact merged commit before any production approval.

If a pinned image regresses, restore its previous reviewed `tag@digest` pair through the same protected PR and staging flow. Do not retag, edit a live workload, or approve production to work around the failure.

References used for the contract: [Docker image digests](https://docs.docker.com/dhi/core-concepts/digests/), [Kubernetes image names and digest precedence](https://kubernetes.io/docs/concepts/containers/images/), and [GitHub Dependabot supported ecosystems](https://docs.github.com/en/code-security/reference/supply-chain-security/supported-ecosystems-and-repositories).
