# Release image security evidence

Status: **current**, updated 2026-10-05.

Current VPS publication contains exactly four immutable GHCR image digests:
`identity-account`, `learning`, `frontend`, `postgres`. Every image needs maximal
BuildKit provenance, SPDX SBOM, verified GitHub provenance/SBOM attestations bound
to repository/workflow/current main SHA, and a Trivy report with no unexcepted
HIGH/CRITICAL findings. Scanner/database identity and all severities remain visible.

The current aggregator `scripts/render_vps_candidate.py` binds the four services,
source/run/attempt, digests and verified security evidence into `vps-candidate`.
The existing evidence verifier supplies the individual image evidence policy.
See [VPS publication](vps-image-publication.md) for exact files and checks. Candidate
publication does not grant administrator admission or runtime acceptance.
Full scan/SBOM/attestation evidence stays in sanitized 30-day Actions artifacts.

The retained two-service Kubernetes release renderer is a dormant source contract,
not the current production publication or deployment path. Its manifests do not
represent the running VPS topology.

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

The retained, non-shipping frontend Dockerfile keeps its reviewed nginx base digest and requires Alpine `libcrypto3`
and `libssl3` **at least** at the first fixed build found by the initial shipping-image baseline (`>=3.5.8-r0`).
An exact pin broke every uncached rebuild once Alpine replaced 3.5.8-r0 with 3.5.9-r0 (2026-10-01), so the floor
accepts newer security patches and the release image scan remains the gate for what is installed. Do not replace
that repair with a scan exception.

The initial backend baseline was repaired by updating the existing Spring Boot 3.5 line to its
current patch, updating the existing PostgreSQL JDBC driver patch, and removing the unused
`/usr/bin/pebble` binary inherited from the JRE image. These are direct fixes, not release
exceptions; the full backend quality gate remains mandatory for future patch updates.

The replacement's first Main CI scan (2026-09-05, run `33973146293`) rejected
Tomcat 10.1.55 for CVE-2026-65182, CVE-2026-65905 and CVE-2026-68525.
The shared build pins the existing Tomcat family to 10.1.59 through Spring Boot's
[managed-version property](https://docs.spring.io/spring-boot/3.5/gradle-plugin/managing-dependencies.html#managing-dependencies.dependency-management-plugin.customizing-managed-versions).
[Apache's advisory](https://tomcat.apache.org/security-10.html#Fixed_in_Apache_Tomcat_10.1.59)
identifies 10.1.59 as the released fix: 10.1.58 did not pass its release vote.
Keep this override until an adopted Boot BOM provides that fix or newer; verify
both replacement JARs and the exact image scan before removing it.

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
