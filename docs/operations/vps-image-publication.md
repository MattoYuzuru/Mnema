# VPS image publication

Main CI publishes the five VPS images automatically for every push to `main` that
changes the production runtime, and on a manual dispatch of **Main CI**
(`deploy.yaml`) on `main`. One VPS runs Compose through the
[root-owned dispatcher](../../deploy/production/README.md); see
[production delivery](production-delivery.md) for the complete release sequence.

## Source and images

The `release-scope` job compares the pushed commit with the newest successful Main CI `prod`
GitHub Deployment and publishes only when `backend/`, `frontend/` (outside `*.md`),
`contracts/`, `deploy/production/` (outside `*.md`), `security/` or the release
workflow/scripts changed (`scripts/release_scope.py`); a manual dispatch always
publishes, and an unknown baseline publishes. Both quality jobs run on the same
revision before any image build. Assembly checks that the revision is still current
remote main; a newer main requires its own run rather than deploying an older
candidate.

The publication matrix has exactly five services: `identity-account`, `learning`,
`frontend`, `media-worker`, `postgres`. Tags use the entire `sha-<40 hex commit>`; there is no `latest` publication.
Release identity is `ghcr.io/mattoyuzuru/mnema/<service>@sha256:<64 hex digest>`.
Build caches are isolated per service. The backend images use the current Java25
replacement modules; the frontend image builds the same source's Angular application; the
`media-worker` image builds `backend/media-worker` (pinned Ubuntu base and FFmpeg packages)
and, containing a GPL-configured FFmpeg, must stay a private GHCR package
([licensing](../../backend/media-worker/README.md#licensing)); the image carries the OCI source
label that links the private package to the repository, and the release job pulls it with its own
short-lived token (`pull`, see the dispatcher contract). The PR and Main CI quality jobs
build that image and run its codec tests and the media runner and dispatcher suites, with no skip allowed, before any publication.
No dirty checkout or source build is transferred to production.

For **every** digest, BuildKit creates maximal provenance and SPDX SBOM; GitHub
build/SBOM attestations are created and verified against this workflow, exact commit
and `refs/heads/main`, denying self-hosted runners. Trivy records all severities,
including unfixed findings. Unexcepted HIGH/CRITICAL findings block publication's
completion; controlled exceptions remain exact digest/package/finding, owner and
expiry scoped. The scanner DB/tool identity, full report/SARIF, SBOM and attestation
verification remain in the existing sanitized artifact boundary. No scan bypass or
new blanket exception is introduced.

## Candidate, admission and deployment

`vps-candidate` retains the following sanitized files for 30 days:

- `vps-candidate.json`: exact SHA, five digest references, run identity, evidence hash.
- `vps-security-evidence.json`: complete five-image security evidence.
- `vps-candidate.sha256`: file checksums.

The renderer rejects missing/duplicate services, foreign repositories, mutable
references, inconsistent source/run/digests, expired exceptions, missing attestations,
unresolved blocking findings and inconsistent counts. Publication itself neither
admits nor deploys: it reads no deployment credentials and enters no GitHub
Environment. The next job, `deploy-production`, waits for the `prod` Environment
approval, re-verifies the checksums and every digest's attestations, and only then
sends the candidate to the dispatcher's `admit` operation, which validates form,
namespace and digests and records it. A failed or interrupted build may leave an
unadmitted image in GHCR; it cannot create a complete candidate or mutate a server.
Do not infer acceptance from the mere presence of a tag.

## Verification and rollback

The active root/auth/www A records resolve to `135.106.175.30`, DNS-only.
Production Caddy and the Compose containers are applied. Current state,
credential/privacy limits and runtime acceptance are in [VPS runtime](vps-runtime.md).
Publication itself never mutates DNS, runtime configuration or old data.

Repository verifier tests mutate guards/dependencies/inputs and candidate evidence
to prove closed failures. The existing release evidence policy also gates frontend.
Hosted CI and a fresh publication run must succeed before claiming actual
image digests, SBOM/provenance or vulnerability results.

Rollback for publication is a protected code revert; it does not delete images, data
or backups and does not roll back a deployed application. Runtime rollback is the
dispatcher's `rollback` operation under its schema-compatibility boundary.

References: GitHub
[artifact attestations](https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations/using-artifact-attestations-to-establish-provenance-for-builds),
Docker [build attestations](https://docs.docker.com/build/metadata/attestations/),
and the existing [release security policy](release-security-evidence.md).
