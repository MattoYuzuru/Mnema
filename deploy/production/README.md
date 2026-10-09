# Dedicated production deployment identity

Status: applied on `mnema` (135.106.175.30); dedicated-key CI rollout and
negative identity tests passed. Releases run automatically in Main CI after one
`prod` approval. Current production and release sequence:
[production delivery](../../docs/operations/production-delivery.md),
[VPS runtime](../../docs/operations/vps-runtime.md).

`mnema-deploy` receives its own CI Ed25519 key. The root-owned SSH Match and forced
command permit only `status`, `verify`, `rollback`, `admit <40-hex SHA>`,
`preflight <40-hex SHA>` and `deploy <40-hex SHA>`. The account has no Docker group
or unrestricted sudo; only the root-owned, isolated-Python dispatcher is allowed.
SSH forwarding/PTY/SFTP/shell are denied. `yuzuru` administrative access is preserved.

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

Operations (single forced-command argument, validated by regex; callers supply no
paths, environment or Docker arguments): `status`, `verify`, `rollback`, `configure`,
`admit <sha>`, `preflight <sha>`, `deploy <sha>`. CI calls them in the order
`status`, `admit`, `configure`, `deploy`, `verify`. The root-owned dispatcher and its
installed siblings are updated only by the administrator (see
[production delivery](../../docs/operations/production-delivery.md)); CI compares
their hashes (`status.config`) with the reviewed repository files and refuses to
continue on drift, so CI never uploads host configuration.

**Admission.** `admit <sha>` reads at most 16 KiB of `vps-candidate.json` from stdin
and validates it strictly: schema 1, `sha` equal to the argument, exactly the four
services, each `ghcr.io/mattoyuzuru/mnema/<service>@sha256:<64 hex>`, source
repository `mattoyuzuru/mnema`, commit equal to the SHA, workflow
`.github/workflows/deploy.yaml`, positive run identity. It then writes the root-only
`releases/<sha>.json` (`sha`, `images`, `source`, `admitted_at`; an existing manifest
with different images is refused), appends one line to the append-only audit log
`/var/lib/mnema-release/admissions.jsonl` and atomically points `admitted.json` at
the SHA. Only the one admitted SHA can be deployed.

Trade-off, stated plainly: admission is no longer a manual administrator review of
evidence flags. Its authority is (1) the protected `prod` Environment approval,
which requires the owner as reviewer, (2) re-verification of the provenance and SBOM
attestations of all four digests in the approved job (signer workflow, source commit,
`refs/heads/main`, no self-hosted runners), (3) namespace and digest pinning in the
dispatcher, (4) the audit log (consider `chattr +a` on it), (5) a verified
pre-migration backup with restore rehearsal before every rollout, and (6) the
unchanged key restrictions below. The dispatcher cannot itself re-verify
attestations; a compromise of the approved CI job or of the CI key can admit any
image in the project's GHCR namespace, which is why the key stays forced-command
and the Environment keeps its reviewer.

The manifest no longer carries `data_boundary_approved`, `auth_guard_verified`,
`backup_restore_verified`, `source_ci_verified` or `image_security_verified`; their
role is now played by the controls above. Historical manifests with these keys remain
loadable (the keys are ignored). Their `current.json` records have no
`rollback_compatible` field, so rolling back to a release recorded before this
dispatcher is refused until a new release records the field.

**Application configuration.** `configure` reads at most 32 KiB of a JSON object from
stdin and stages it as the root-only `app.env.next` (0600, atomic, fsynced); it changes
nothing that runs. `deploy`/`rollback` make it part of the release transaction: only after
`pending.json` exists, `app.env.next` replaces `app.env` (the old file stays as
`app.env.previous`; identical content changes nothing). An abort before the marker restarts
the current release with the unchanged `app.env`. After a rollout failure the pending marker
holds everything; to return to the old configuration the administrator copies
`app.env.previous` over `app.env`. `rollback` restores `app.env.previous` when
`current.json.config_changed` says the release being left changed the configuration. The
object is always sent by CI (possibly `{}`), so removing a secret removes the key; missing
keys fail closed through the `${NAME:-}` defaults in Compose. Names must be in the
hard-coded allowlist `APP_ENV_NAMES` (mirrored in `app-config.keys` for CI; Turnstile,
Google/Yandex/GitHub OAuth, Postbox, learning media upload and avatar storage keys,
promo/experiment secrets, events owner, Turnstile mode); values must be printable ASCII without whitespace, quotes, backslash, `$`,
backtick or a leading `#` and at most 4096 characters; an empty string means absent;
`MNEMA_IDENTITY_TURNSTILE_MODE` is only `blocked` or `required`, and `required` needs both
Turnstile keys.
It prints sorted names and a hash of the names only, never values. Compose receives
the env-files in the order `runtime.env`, `app.env`, release; containers whose effective
environment changed are recreated by the next `deploy`. Host-generated secrets (database
passwords, `identity-signing.json`, `offsite.env`) stay host-only and are never sent by
CI. Trade-off: this replaces the earlier "CI uploads no application configuration"
rule. The values are reachable only by the `deploy-production` job on `main` after the
required-reviewer approval, and CI still cannot change compose structure, image
namespace, privileges, mounts or host files. `status` reports `app_config_names` and
`app_config_next_names` (names only). Adding a name needs a reviewed PR and an administrator-installed
dispatcher.

**Input handling.** `admit` and `configure` read their stdin (size cap and 30 s deadline)
*before* taking the production lock, so a stalled client cannot block releases or backups.

**Deploy sequence** (`deploy <sha>`): admitted check, manifest load, 10 GB disk
reserve, static checks (backup tool present, root-owned, executable),
`compose config --quiet`, then, with writers quiesced:

1. align `postgres-image` with the running release (only if the running container
   equals the recorded manifest) and record the schema fingerprint before;
2. `compose stop identity-account learning`;
3. run `mnema-local-backup pre-deploy` (15 min bound, own session killed as a group):
   dump, isolated network-less restore rehearsal with row and role-isolation
   reconciliation, retention. No offsite upload happens here, so a slow network cannot
   hold the writers down;
4. write `pending.json` (fsynced, including the backup name),
   `compose up --detach --wait --wait-timeout 300`. **Any** failure between step 2 and
   this marker (a rejected or timed-out backup, an unexpected error, SIGTERM, loss of
   the SSH session) restarts the **current** release best-effort, leaves no pending
   marker when that succeeds, and rejects; nothing was migrated. The dispatcher ignores
   SIGHUP and a closed pipe, so a vanished CI job cannot abort a rollout midway;
5. record the schema fingerprint after and write `current.json`
   (`sha`, `previous`, `schema_before`, `schema_after`, `rollback_compatible`,
   `backup`); write `postgres-image` for the new release; clear the pending marker;
6. prune local `ghcr.io/mattoyuzuru/mnema/*` images whose digest is in neither the
   current nor the previous manifest (`docker image rm <ref>`; never
   `system prune`, never volumes, never other images; images in use are skipped);
7. best-effort `mnema-local-backup offsite <name>` (5 min bound, whole process group
   terminated on timeout); the result is reported as `offsite: uploaded|failed|disabled`
   and never changes the deployment result. The daily timer uploads as well.

Worst case of the `deploy` call alone (sum of its individual bounds): lock 10 min,
preflight 4, pin check 2, schema 2 x 4, stop 4, backup 15.5, rollout 7, prune 3.5, offsite
5.5, about 60 minutes. Across the whole CI sequence the bounds add up to about 109 minutes,
so the job allows 120; a normal release takes 5 to 8 minutes.

The schema fingerprint is `count(*)`, `max(installed_rank)` and an md5 of
`version:checksum` over successful rows of `app_identity.flyway_schema_history` and
`app_learning.flyway_schema_history`. `rollback_compatible` is true only if the
fingerprint is unchanged by the release (a repeated deploy of the same SHA keeps an
earlier schema change on record). Rollback is refused unless the current record says
`true` and a previous manifest exists; it then follows the same quiesce, backup and
pending flow and records `previous: null`, so a second rollback cannot roll forward
onto the schema just left. Docker restart does not reverse migrations: after a schema change,
recover by roll-forward or by the retained pre-deploy dump in a reviewed operation.

`verify` compares the running images of the four containers with the recorded
manifest, checks the three local readiness URLs and the absence of a pending marker,
and exits non-zero on any difference. `status` additionally reports `admitted_sha`,
`rollback_compatible`, `pending_backup`, `app_config_names` and sha256 of the installed
Compose/nginx/Caddy files, host tools, systemd units, SSH forced-command wrapper, sudoers
rule and sshd `Match` file (null when missing, `unprotected` when ownership or mode is
unsafe), and `newest_backup_age_hours`, which comes from the backup tool's `newest`
operation: the backup tool is the only definition of a complete backup, shared with the
health monitor. It is not a live probe.

**Concurrency.** Every operation takes `/run/mnema-production.lock`, shared with the
03:30 UTC backup timer. A waiting caller polls every 5 s for up to 10 minutes before
rejecting. `pre-deploy` runs under the dispatcher's lock and itself requires that
lock to be held elsewhere and the application containers to be stopped.

Caller environment, Docker arguments, image namespaces and paths are not forwarded.
Docker and Compose failures are never echoed (they may contain interpolated
configuration); the dispatcher prints static, value-free reasons as
`deployment rejected: <reason>` (any other error is a generic value-free line, never a
traceback) and phase names (`phase=<name>`) on stderr.
Before mutation the dispatcher atomically persists `pending.json`. Only a successful
readiness operation atomically records its SHA and clears that marker. File and
directory fsync establish the pending/current boundary across host crashes. A repeat
of the same accepted SHA preserves the previous verified rollback SHA.

## Failure, rollback and revocation

A failed/timeout Compose operation or crash can leave a partly updated runtime.
The last verified record is retained and the persistent pending marker blocks all
ordinary deploy/preflight/rollback operations (and raises the `rollout_reconciliation`
health alert) until root reconciles actual images, migrations and health; CI cannot
clear the marker or assume the old schema remains. Stop promotion and inspect from
the admin identity. Never delete volumes/backups or accept a floating image to
repair a failure. The retained pre-deploy dump named in `pending.json`
(`pending.backup`, shown by `status` as `pending_backup`) is the recovery point for a
failed migration; `current.json.backup` is the one taken for the release now recorded.
Restoring either is a separate reviewed administrator operation.

To revoke CI access, remove only the dedicated authorized key and the Environment
secret, after confirming admin SSH works. The root dispatcher and other host tooling
are replaced only from a reviewed PR, by the administrator.

Positive smoke: dedicated key `status`. Negative smoke: `id`, `sudo id`, `sh`,
SFTP, PTY, local/remote/Unix forwarding, injected/extra arguments, unknown SHA,
a candidate for another SHA or namespace, and an oversize candidate on stdin. A
rejected preflight or admission is not successful deployment evidence.

Offsite bucket (verified live by the owner's operator): a second PUT of the same key
returns 412 (`If-None-Match: *` makes objects write-once), bucket versioning is on, and
the uploader identity can neither DELETE (403) nor write outside its prefix or without
SSE-KMS (403). Expiry is a bucket lifecycle rule, not this tooling.

The dispatcher admits four verified images, including PostgreSQL, and rewrites
`postgres-image` for every accepted release (the PostgreSQL image is rebuilt per
SHA). The local backup helper refuses a live source using any other image.
Application credential activation is separate root-owned configuration; see the
runtime's auth section.

[OpenSSH forced-command/forwarding contract](https://man.openbsd.org/sshd_config.5),
[GitHub Environment protections](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/managing-environments-for-deployment).
