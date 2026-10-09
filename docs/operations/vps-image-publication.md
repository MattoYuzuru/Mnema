# VPS image publication

Main CI provides the current bounded **publication** path for the new Russian VPS, `mnema` (`135.106.175.30`).
It does not reactivate the old Kubernetes deployment, recovery or rollback jobs.
One VPS will use Compose through the [root-owned dispatcher](../../deploy/production/README.md);
Compose configuration, actual dependency/backup acceptance and public rollout are
separate required evidence. See [production delivery](production-delivery.md) for the complete release sequence.

## Source and images

Run **Main CI** (`deploy.yaml`) on branch `main`, selecting the boolean
`publish_production_candidate`. Default false and ordinary push runs keep publication
off. Both quality jobs run on the same revision before any image build. Assembly
checks that the revision is still current remote main; a newer main requires a fresh
run rather than deploying an older candidate.

The publication matrix has exactly four services: `identity-account`, `learning`,
`frontend`, `postgres`. Tags use the entire `sha-<40 hex commit>`; there is no `latest` publication.
Release identity is `ghcr.io/mattoyuzuru/mnema/<service>@sha256:<64 hex digest>`.
Build caches are isolated per service. The backend images use the current Java25
replacement modules; the frontend image builds the same source's Angular application.
No dirty checkout or source build is transferred to production.

For **every** digest, BuildKit creates maximal provenance and SPDX SBOM; GitHub
build/SBOM attestations are created and verified against this workflow, exact commit
and `refs/heads/main`, denying self-hosted runners. Trivy records all severities,
including unfixed findings. Unexcepted HIGH/CRITICAL findings block publication's
completion; controlled exceptions remain exact digest/package/finding, owner and
expiry scoped. The scanner DB/tool identity, full report/SARIF, SBOM and attestation
verification remain in the existing sanitized artifact boundary. No scan bypass or
new blanket exception is introduced.

## Candidate is not admission

`vps-candidate` retains the following sanitized files for 30 days:

- `vps-candidate.json`: exact SHA, four digest references, run identity, evidence hash.
- `vps-security-evidence.json`: complete four-image security evidence.
- `vps-candidate.sha256`: file checksums.

The renderer rejects missing/duplicate services, foreign repositories, mutable
references, inconsistent source/run/digests, expired exceptions, missing attestations,
unresolved blocking findings and inconsistent counts. It emits **none** of the
dispatcher's acceptance flags. Publication does not assert backup restore, data
cutover, live Turnstile acceptance or migration readiness. An administrator
must verify the current source/run, image evidence, reviewed runtime configuration
and actual acceptance records before installing a root-only release manifest and
admission on `mnema`. The CI user cannot write those files.

This workflow reads no deployment credentials and enters no GitHub Environment.
The existing `prod` reviewer and main-only policy remain required for a subsequent
reviewed deployment path. A failed/interrupted build may leave an unadmitted image
in GHCR; it cannot create a complete candidate or mutate a server. Do not infer
acceptance from the mere presence of a tag.

## Verification and rollback

The active root/auth/www A records resolve to `135.106.175.30`, DNS-only.
Production Caddy and four healthy Compose containers are applied. Current state,
credential/privacy limits and runtime acceptance are in [VPS runtime](vps-runtime.md).
Publication itself never mutates DNS, runtime configuration or old data.

Repository verifier tests mutate guards/dependencies/inputs and candidate evidence
to prove closed failures. The existing release evidence policy also gates frontend.
Hosted CI and a fresh manual publication run must succeed before claiming actual
image digests, SBOM/provenance or vulnerability results.

Rollback for publication is a protected code revert that removes the manual opt-in
while preserving quality jobs and dormant operational guards. It does not delete
images, data or backups, and does not roll back a deployed application. Runtime
rollback remains the dispatcher's reviewed schema-compatibility boundary.

References: GitHub [typed manual inputs](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#onworkflow_dispatchinputs),
[artifact attestations](https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations/using-artifact-attestations-to-establish-provenance-for-builds),
Docker [build attestations](https://docs.docker.com/build/metadata/attestations/),
and the existing [release security policy](release-security-evidence.md).
