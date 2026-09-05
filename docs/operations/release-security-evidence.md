# Release image security evidence

Status: **current**, updated 2026-09-05.

Every Main CI replacement candidate contains exactly two immutable GHCR image digests:
`identity-account` and `learning`. Both environment manifests describe the same images in
maintenance; production promotion is disabled until #147. A digest becomes a releasable artifact only after all of the following
checks pass for that exact reference:

1. Docker BuildKit publishes `mode=max` provenance and an SPDX 2.3 SBOM with the image.
2. GitHub Artifact Attestations creates independent provenance and SBOM attestations for the
   digest. The workflow immediately verifies the repository, signer workflow, source commit,
   source ref and hosted-runner boundary.
3. Trivy scans the digest, records its exact version and vulnerability database timestamps, and
   emits the full JSON report and SARIF. Unexcepted `HIGH` or `CRITICAL` findings stop Main CI before the
   digest artifact exists; `LOW` and `MEDIUM` findings remain visible in evidence without blocking.
4. `scripts/verify_release_security_evidence.py` binds the two digests, source commit, Main CI run
   and attempt, attestations, SBOM hashes, scanner identity, counts and any applied exceptions into
   `release-security-evidence.json`.

The compact evidence and its SHA-256 checksum travel with both release manifests. Staging checks
the original Main CI run identity and both manifests before reading staging credentials. Staging records successful maintenance smoke without relaying a production promotion artifact.
The production workflow reports the #147 gate and skips all protected `prod` jobs. Full SBOM, attestation verification and Trivy
JSON, SARIF and SBOM files stay in the 30-day `release-security-<service>` Actions artifacts; they must not be copied
to issue comments or logs.

This design follows the official [GitHub Artifact Attestations workflow and verification
model](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations),
Docker's [BuildKit SBOM](https://docs.docker.com/build/metadata/attestations/sbom/) and
[provenance](https://docs.docker.com/build/metadata/attestations/slsa-provenance/) attestations, and
the official [Trivy Action](https://github.com/aquasecurity/trivy-action). Action references are
immutable commit pins with adjacent release versions.

## Independent verification

Authenticate `gh` to GitHub and `docker` to GHCR, then use the exact digest from the release
manifest. The signer and source restrictions are mandatory:

```bash
image='ghcr.io/mattoyuzuru/mnema/identity-account@sha256:<64 lowercase hex characters>'
commit='<40 lowercase hex characters>'

gh attestation verify "oci://${image}" \
  --repo MattoYuzuru/Mnema \
  --signer-workflow MattoYuzuru/Mnema/.github/workflows/deploy.yaml \
  --source-digest "$commit" \
  --source-ref refs/heads/main \
  --deny-self-hosted-runners

gh attestation verify "oci://${image}" \
  --repo MattoYuzuru/Mnema \
  --signer-workflow MattoYuzuru/Mnema/.github/workflows/deploy.yaml \
  --source-digest "$commit" \
  --source-ref refs/heads/main \
  --deny-self-hosted-runners \
  --predicate-type https://spdx.dev/Document/v2.3
```

Download the matching `production-release-manifest` artifact and verify its checksums before local
inspection:

```bash
sha256sum --check production-release.yaml.sha256
sha256sum --check release-security-evidence.json.sha256
python3 scripts/verify_release_security_evidence.py verify-release \
  --evidence release-security-evidence.json \
  --manifest production-release.yaml \
  --expected-repository MattoYuzuru/Mnema \
  --expected-commit "$commit" \
  --trivy-ignore security/trivy-release-ignore
```

## Temporary exception contract

The default file, `security/release-image-exceptions.json`, has no exceptions. An emergency
exception may name only one finding, one exact image digest and explicit affected package names.
It also requires a rationale, a single GitHub owner, a creation date and an expiry no more than 30
days later:

```json
{
  "finding": "CVE-2026-12345",
  "image": "ghcr.io/mattoyuzuru/mnema/identity-account@sha256:<exact digest>",
  "packages": ["openssl"],
  "rationale": "No fixed package exists; ingress and network policy prevent the vulnerable path.",
  "owner": "@MattoYuzuru",
  "created": "2026-08-29",
  "expires": "2026-09-12"
}
```

Wildcards, missing fields, future, expired, longer-lived, duplicate, unused and non-release-digest
scopes fail closed. Remove the entry as soon as the finding is fixed. Updating the entry requires a
normal protected pull request and a new build because an exception is part of release policy, not a
runtime toggle.

Both Trivy invocations use the explicit comments-only `security/trivy-release-ignore` file. Active
ignore entries in that file are rejected by the evidence validator; `.trivyignore` is never part of
the release policy. This prevents scanner-native ignores from bypassing the owner, scope and expiry
contract above.

The retained, non-shipping frontend Dockerfile keeps its reviewed nginx base digest and pins Alpine `libcrypto3` and
`libssl3` to the first fixed build found by the initial shipping-image baseline. Do not replace
that repair with a scan exception while the fixed packages remain available.

The initial backend baseline was repaired by updating the existing Spring Boot 3.5 line to its
current patch, updating the existing PostgreSQL JDBC driver patch, and removing the unused
`/usr/bin/pebble` binary inherited from the JRE image. These are direct fixes, not release
exceptions; the full backend quality gate remains mandatory for future patch updates.

## Failure and retry

- A Trivy binary, registry, vulnerability database or attestation service outage is a scanner or
  verification failure, not a clean scan. Main CI stops and no staging workflow is dispatched.
- A finding failure names only severity, finding ID and package. Inspect the bounded raw artifact;
  never paste a complete SBOM or report into a public issue.
- Retry the failed Main CI run to rebuild from the same commit. Within one run, scanner and
  verification retries always address the already-pushed digest and never retag a different image.
- If a workflow change must be rolled back, revert the workflow and policy code through a pull
  request. Do not delete immutable registry or GitHub attestations. The last fully verified release
  remains the production rollback boundary.
