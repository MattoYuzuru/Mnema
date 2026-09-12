# Delivery without hosted infrastructure

Status: current, owner-approved 2026-09-12. Supersedes the automatic rollout
instructions in the staging, release-verification and database-recovery runbooks
until a separately reviewed reactivation. The previous shared server is unavailable;
its earlier successful rollback is historical evidence, not current availability.

## Current completion boundary

Feature branch → full local quality gate → PR → hosted quality/security checks →
protected squash into main → integrated main checks. There is no staging or
production wait, SSH prerequisite, environment approval or deployment acceptance
for local product implementation. Keep backend/frontend gates, coverage floors,
CodeQL, dependency review, disposable DB tests and real local cross-service/E2E
checks. A merged/tested feature is not a deployed or production-verified feature.

Main CI keeps its quality jobs. Image build/push, attestations, image scanning and
release-manifest publication jobs are disabled together: no deployable image is
published without its image-security gate. Application builds and the existing
release/security/recovery contract tests still run. No image-level vulnerability
result is claimed for a release image that was not built.

Staging, production, rollback drill and hosted database recovery have only empty
workflow_call definitions with no callers, automatic events or manual Run buttons.
Every operational job also has a literal false guard: even an accidental caller
cannot allocate its runner or enter an Environment. Repository tests reject callers,
standalone triggers and removal of any guard. No variable, input or secret opens
them. Their existing job bodies are restoration blueprints, not an executable
local deployment path. Main CI can show two skipped release jobs without runners.

Do not replace the dormant trigger with workflow_dispatch: besides exposing an
unneeded entry point, it changes the old workflow_run cache trust boundary. CodeQL
detected that change in the initial candidate's artifact preflight. Removing the
standalone entry point addresses it without suppressing alerts or relaxing scans.
workflow_call is not inherently read-only: cache permissions depend on its caller.
Nor does the lack of declared secrets replace guards against Environment access.

Never rerun an older operational workflow: reruns use the older workflow revision.
Do not dispatch an old branch's operational workflows. This source change does not
revoke existing credentials or change repository-wide Actions permissions.
No server resources, secrets, GitHub Environment protections or branch rules are
changed by this mode switch.

## Reactivation is a separate infrastructure change

1. Establish ownership/access to the new host and the fate of old data/backups.
   Revoke/rotate credentials exposed on the lost host through an authorized plan.
2. Review new DNS/TLS, namespaces, database/object storage, network isolation,
   scoped CI identities, quotas, observability and backup/restore readiness.
3. Update endpoints/configuration and revalidate manifests and runtime versions.
   Do not just switch the SSH alias and reuse old credentials.
4. Re-enable image publication and its provenance/SBOM/vulnerability gates
   together; restore the validated predecessor triggers and job conditions.
   Restore reviewed recovery/rollback confirmation inputs before those operations.
   In particular hosted recovery previously used always() to finish bounded cleanup.
5. Prove fresh staging rollout, actual API smoke, rollback and data restoration.
   Production still requires the separate #147 product/security/data cutover gate.

Reactivation and the corresponding regression-test change require a protected PR.
A blind revert is unsafe because it could contact the old host. Keep the paused
state until the exact new target and rollback plan are approved.

Sources: GitHub [workflow triggers](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)
and [job conditions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-jobs-with-conditions)
define the removal of automatic workflow_run chaining and job-level false guards.
[Reusable workflow definitions](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows)
have no standalone trigger; the additional false guards remain essential.
[CodeQL cache trust guidance](https://codeql.github.com/codeql-query-help/actions/actions-cache-poisoning-poisonable-step/)
explains why an unnecessary manual trigger is not a neutral replacement.
