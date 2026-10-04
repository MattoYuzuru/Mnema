# Dedicated production deployment identity

Status: prepared for #347. No runtime, release, credential placement or deployment
is implied by these files. Owner target is only `mnema` (135.106.175.30).

`mnema-deploy` receives its own CI Ed25519 key. The root-owned SSH Match and forced
command permit only `status`, `preflight <40-hex SHA>`, `deploy <40-hex SHA>` and
`rollback`. The account has no Docker group or unrestricted sudo; only the
root-owned, isolated-Python dispatcher is allowed. SSH forwarding/PTY/SFTP/shell
are denied. `yuzuru` administrative access is preserved.

## Root-owned bootstrap boundary

Install the reviewed `mnema-deploy.py` as `/usr/local/sbin/mnema-deploy` (root:root,
0755), wrapper as `/usr/local/bin/mnema-deploy-ssh` (root:root,0755), Match file as
`/etc/ssh/sshd_config.d/60-mnema-deploy.conf` and sudo rule as
`/etc/sudoers.d/mnema-deploy` (0440). Validate `visudo -cf` and `sshd -t` before reload.
Use a root-owned home/`.ssh`/authorized_keys for the new account, with
`restrict,command="/usr/local/bin/mnema-deploy-ssh"` on the dedicated public key.
Do not give the account sudo/Docker groups or ownership of these directories.

Place only this key in GitHub Environment `prod` as `PROD_DEPLOY_SSH_KEY` after
positive/negative identity tests. Pin the host key read over the verified admin
session in `PROD_DEPLOY_KNOWN_HOSTS`. `PROD_DEPLOY_HOST` and `PROD_DEPLOY_USER` are
Environment variables for this target. Preserve existing Environment protections
and legacy credential ownership; neither copy the personal key nor revoke old
keys without identifying consumers. Never disable StrictHostKeyChecking.

## Release admission and rollout

The administrator stages approved **data-only** manifests in
`/etc/mnema/production/releases/<sha>.json`; CI cannot upload or edit configuration.
The reviewed Compose file and private runtime.env are root-owned under that same
root-only tree (directories0700; runtime.env root:root0600). The dispatcher checks every ancestor for symlinks/unsafe ownership
or permissions; root-owned leaves inside a caller-writable directory are rejected.

The root-owned `admitted.json` pointer names the **one** SHA currently admitted for
`deploy`. Historical approved manifests remain accessible only to checked rollback;
CI cannot bypass schema compatibility by invoking `deploy <old-sha>`. #349 must
bind this pointer to the current verified main candidate.

Manifest contract (names only; this is not release acceptance evidence):

- `sha`: the exact current-main commit verified by CI/security and local gate;
- `images`: exactly `frontend`, `identity-account`, `learning`, `postgres`, each with the exact
  `ghcr.io/mattoyuzuru/mnema/<service>@sha256:<64-hex>` security-verified digest;
- `source_ci_verified`, `image_security_verified`, `backup_restore_verified`,
  `data_boundary_approved`, `auth_guard_verified`: true only after the corresponding
  evidence is reviewed and recorded, **never** as placeholders to unblock a deploy;
- `rollback_compatible`: whether this release's migrations/config permit returning
  to the previous recorded release. False requires a separately reviewed recovery
  or roll-forward; do not assume a Docker restart reverses schema changes.

#349 owns safe automatic staging/attestation verification of future release
manifests and the final production Compose topology. Until these root-owned inputs
and acceptance gates exist, deploy/preflight fail closed. A successful `status`
proves only CI identity and metadata access, not a working application.

A server-side nonblocking lock rejects concurrent operations. Caller environment,
Docker arguments, image namespaces and paths are not forwarded. Compose preflight
is quiet; rollout waits for health with a bounded deadline and retains all volumes.
Before mutation the dispatcher atomically persists `pending.json`. Only a successful
readiness operation atomically records its SHA and clears that marker. File and
directory fsync establish the pending/current boundary across host crashes. A
repeat of the same accepted SHA preserves the previous verified rollback SHA. Status reports
the pending/reconciliation boundary and the recorded verified SHA and explicitly says the live state is not probed.
Actual public-user acceptance remains the separate #345 smoke.

## Failure, rollback and revocation

A failed/timeout Compose operation or crash can leave a partly updated runtime.
The last verified record is retained and the persistent pending marker blocks all
ordinary deploy/preflight/rollback operations until root reconciles actual images,
migrations and health; CI cannot clear the marker or assume the old schema remains. Stop promotion/DNS switch and inspect from the admin
identity. Runtime/config migration compatibility and backup recovery must be proven
before invoking rollback; it selects only the previous approved manifest and checks
compatibility on the **current** release. Never delete volumes/backups or accept a
floating image to repair a failure.

Before the first accepted release there is no verified rollback image. Stop the
new runtime through the administrator while preserving data. To revoke CI access,
remove only the new authorized key and Environment secret, after confirming admin
SSH works. The root dispatcher/config should be replaced only from a reviewed PR.

Positive smoke: dedicated key `status`. Negative smoke: `id`, `sudo id`, `sh`,
SFTP, PTY, local/remote/Unix forwarding, injected/extra arguments, unknown SHA and
missing acceptance manifest. A rejected preflight is expected before #349, not
successful deployment evidence.

The VPS runtime extends the original three-image dispatcher with the reviewed
PostgreSQL release image. Replace the root dispatcher from the merged runtime PR
before first rollout; the earlier installed version deliberately rejects four
images. Root places its candidate PostgreSQL digest in `postgres-image` for the
local backup helper, which refuses a live source using any other image.

[OpenSSH forced-command/forwarding contract](https://man.openbsd.org/sshd_config.5),
[GitHub Environment protections](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/managing-environments-for-deployment).
