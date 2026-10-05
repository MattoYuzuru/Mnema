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

Authenticate `gh` to GitHub and `docker` to GHCR, then use the exact digest from `vps-candidate.json`. The signer and source restrictions are mandatory:

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

Download `vps-candidate` from the exact successful current-main publication run.
Set `commit`, `run_id` and `run_attempt` from verified GitHub run metadata, not from
untrusted artifact fields. In its download directory, check both files and
reconstruct the candidate with the reviewed repository verifier:

```bash
sha256sum --check vps-candidate.sha256
python3 /path/to/verified/Mnema/scripts/render_vps_candidate.py \
  --evidence vps-security-evidence.json \
  --repository MattoYuzuru/Mnema --sha "$commit" \
  --run-id "$run_id" --run-attempt "$run_attempt" \
  --trivy-ignore /path/to/verified/Mnema/security/trivy-release-ignore \
  --output candidate-checked.json
cmp vps-candidate.json candidate-checked.json
```

Repeat both attestation checks above for **all four** candidate digest references.
Checksum/renderer success alone does not authenticate registry attestations or
prove migrations, backup, auth/data admission or live health.

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

All release Trivy invocations use the explicit comments-only `security/trivy-release-ignore` file. Active
ignore entries in that file are rejected by the evidence validator; `.trivyignore` is never part of
the release policy. This prevents scanner-native ignores from bypassing the owner, scope and expiry
contract above.

Current frontend/backend/PostgreSQL remediation and base-image floors are in
[production image inventory](production-image-inventory.md), the Dockerfiles and
Gradle build. All four derived release images ship and require fresh scan evidence;
no old baseline version or scan exception substitutes for that gate.

## Failure and retry

- A Trivy binary, registry, vulnerability database or attestation service outage is a scanner or
  verification failure, not a clean scan. Publication stops and no complete candidate is admitted.
- A finding failure names only severity, finding ID and package. Inspect the bounded raw artifact;
  never paste a complete SBOM or report into a public issue.
- Dispatch a fresh current-main publication after investigating the failure; do not rerun historical operational revisions. Within one run, scanner and
  verification retries always address the already-pushed digest and never retag a different image.
- If a workflow change must be rolled back, revert the workflow and policy code through a pull
  request. Do not delete immutable registry or GitHub attestations. The last fully verified release
  remains the production rollback boundary.
