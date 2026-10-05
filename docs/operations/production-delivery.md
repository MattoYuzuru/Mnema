---
artifact:
  id: production-delivery
  type: operations-runbook
  title: "Mnema development and production delivery"
  status: current
  updated_at: "2026-10-05"
  owners: ["project-owner"]
---

# Development and production delivery

Production is available at https://mnema.app, with Identity at
https://auth.mnema.app. Administrator access is `ssh mnema` to the Russian VPS
`135.106.175.30`. These are the current targets for development releases.

## Implementation and release

Feature branch → full local quality gate → hosted PR quality/security checks →
protected squash into main → main checks. Preserve coverage floors, CodeQL,
dependency review and proportional integration/browser evidence. A merged feature
becomes live only after publication, admission, deployment and verification below.

For an owner-authorized production delivery, use the current main workflows:

1. Read current remote main and run **Main CI** (`deploy.yaml`) on `main` with
   `publish_production_candidate=true`. Ordinary pushes run quality checks;
   publication is an explicit manual input, default false.
2. Require successful quality and four-image security evidence for that exact SHA.
   Download `vps-candidate` and validate its checksums, provenance, SBOM, attestations
   and vulnerability findings. See [image publication](vps-image-publication.md).
3. Through administrator SSH, inspect migrations and reviewed configuration, take
   the local backup and run an isolated restore rehearsal. Review actual auth/data
   boundaries and rollback compatibility. Stage the exact four-image manifest and
   `admitted.json` under `/etc/mnema/production`; follow the
   [dispatcher contract](../../deploy/production/README.md). Admission is currently
   an administrator step; a successful build cannot supply its evidence flags.
4. Run **VPS production deployment** (`vps-deploy.yaml`) on `main`, supplying that
   admitted `release_sha`. Retain the `prod` Environment reviewer and exact main
   branch restriction. CI has a dedicated forced-command key and uploads no
   runtime configuration or application credentials.
5. Check actual container readiness, HTTPS/build identity, protected API and auth
   policy, local monitoring and backup. Follow [VPS runtime](vps-runtime.md).
   Record the SHA and verification on the owning work item. Failed gates stop
   rollout; use the recorded compatible previous release or maintenance fallback.

A newer main requires a fresh candidate and admission. Do not rerun historical
operational jobs, change an old branch's workflow or bypass a failed check. Do not
bootstrap an existing VPS again, clear uncertain rollout state from CI, restore a
production DB or delete volumes as a deployment shortcut.

## Authority and current limits

An explicit end-to-end implementation request covers branch, gates, PR and the
protected merge when requested. A request to deploy/ship to Mnema production also
covers this finite publication/admission/deployment sequence and normal Environment
approval. Agents can use the working server and these jobs; the target and effect
must remain within the owner's task. Unrelated data destruction and restoration
require their own concrete boundary.

Production has a new database; old databases, objects and backups are preserved.
Same-host dumps and restore rehearsals work; offsite backup is deferred in #350.
Turnstile/public password auth and foreign providers remain restricted by the
[actual legal/configuration status](../product/russia-legal-launch-checklist-2026.md).
The legacy Kubernetes staging/deploy/recovery workflows are dormant source
contracts, not the VPS delivery path. No Kubernetes reactivation is needed for
this release sequence.

References: GitHub [manual workflow inputs](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#onworkflow_dispatchinputs)
and [Environment protection](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/managing-environments-for-deployment).
