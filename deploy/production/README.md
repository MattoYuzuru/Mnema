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
`admit <sha>`, `pull <sha>`, `preflight <sha>`, `deploy <sha>`. CI calls them in the order
`status`, `admit`, `pull`, `configure`, `deploy`, `verify`. The root-owned dispatcher and its
installed siblings are updated only by the administrator (see
[production delivery](../../docs/operations/production-delivery.md)); CI compares
their hashes (`status.config`) with the reviewed repository files and refuses to
continue on drift, so CI never uploads host configuration.

**Admission.** `admit <sha>` reads at most 16 KiB of `vps-candidate.json` from stdin
and validates it strictly: schema 1, `sha` equal to the argument, exactly the five
services (`frontend`, `identity-account`, `learning`, `media-worker`, `postgres`), each `ghcr.io/mattoyuzuru/mnema/<service>@sha256:<64 hex>`, source
repository `mattoyuzuru/mnema`, commit equal to the SHA, workflow
`.github/workflows/deploy.yaml`, positive run identity. It then writes the root-only
`releases/<sha>.json` (`sha`, `images`, `source`, `admitted_at`; an existing manifest
with different images is refused), appends one line to the append-only audit log
`/var/lib/mnema-release/admissions.jsonl` and atomically points `admitted.json` at
the SHA. Only the one admitted SHA can be deployed.

Trade-off, stated plainly: admission is no longer a manual administrator review of
evidence flags. Its authority is (1) the protected `prod` Environment approval,
which requires the owner as reviewer, (2) re-verification of the provenance and SBOM
attestations of all five digests in the approved job (signer workflow, source commit,
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

**Media runner.** Media processing is not a Compose service. A small trusted root service,
`mnema-media-runner` (`mnema-media-runner.py` installed as `/usr/local/sbin/mnema-media-runner`, run by
`mnema-media-runner.service`; both are in the drift set), finds the jobs Learning leaves in its private spool and
runs each one in a fresh throw-away container of the `media-worker` image (no network, read-only root, no
capability, UID 10002, bounded pids/memory/cpus, `--pull never`, two mounts) from the release the dispatcher
recorded as current. Only root writes a job's verdict (`status.json`); Learning accepts nothing else. Design, threat
model and residual risks: [`backend/media-worker/README.md`](../../backend/media-worker/README.md). No container
mounts the Docker socket and Learning cannot reach the daemon.

The runner needs a work directory that is the root of its **own filesystem**, owned by root, with Learning's private
spool inside (`spool/`, 10001:10001, mode 0700) and the runner's root-only scratch (`.runner/`, created by the
runner). `preflight`, `deploy` and `rollback` refuse with `media work directory missing or unsafe` unless it is a real
directory (not a link), root-owned, not writable by group or others, a different device than its parent, with a
correct `spool/`; and with `media runner is not installed or not active` unless the service is active. `verify`
requires the service to be active for a release that carries the worker image. The runner itself refuses to start
jobs while the directory is not a separate mount (jobs stay queued and Learning retries), and the health monitor
reports `media_runner` (service active and heartbeat younger than 30 s), `media_work_mount` and `media_work_space`
(under 10 % free blocks or inodes) once the recorded release contains the worker. Compose mounts only
`/var/lib/mnema/media-work/spool` into Learning.

One-time administrator bootstrap, before the first release that contains the worker. The work directory is a
fixed-size, fixed-inode loopback ext4 (20 GiB, 131072 inodes) so a runaway or compromised job can fill only that
filesystem. The image must be **non-sparse**: `fallocate` reserves it and `mkfs.ext4 -E nodiscard` keeps the format
step from punching holes into it. **docker.service does not depend on the mount** (no `RequiresMountsFor` drop-in):
if the mount is absent, only media processing fails closed.

```bash
ssh mnema 'sudo install -d -m 0000 /var/lib/mnema/media-work'   # unmounted: the empty mount point is unusable
ssh mnema 'sudo fallocate -l 20G /var/lib/mnema/media-work.img && sudo chmod 0600 /var/lib/mnema/media-work.img \
  && sudo mkfs.ext4 -q -E nodiscard -N 131072 -m 0 -L mnema-media-work /var/lib/mnema/media-work.img'
ssh mnema 'sudo du -h /var/lib/mnema/media-work.img'            # expect 20G (a sparse file would show far less)
# the units and the runner are part of the drift set: install them from the exact main commit, like the other host files
cd deploy/production && scp media-work.mount mnema-media-runner.py mnema-media-runner.service mnema:/tmp/
ssh mnema 'sudo install -o root -g root -m 0644 /tmp/media-work.mount "/etc/systemd/system/var-lib-mnema-media\x2dwork.mount" \
  && sudo install -o root -g root -m 0755 /tmp/mnema-media-runner.py /usr/local/sbin/mnema-media-runner \
  && sudo install -o root -g root -m 0644 /tmp/mnema-media-runner.service /etc/systemd/system/mnema-media-runner.service \
  && rm /tmp/media-work.mount /tmp/mnema-media-runner.py /tmp/mnema-media-runner.service \
  && sudo systemctl daemon-reload && sudo systemctl enable --now "var-lib-mnema-media\x2dwork.mount" \
  && sudo install -d -o 10001 -g 10001 -m 0700 /var/lib/mnema/media-work/spool \
  && sudo chown root:root /var/lib/mnema/media-work && sudo chmod 0755 /var/lib/mnema/media-work \
  && sudo systemctl enable --now mnema-media-runner.service'
ssh mnema 'findmnt /var/lib/mnema/media-work && sudo stat -c "%U:%G %a %n" /var/lib/mnema/media-work /var/lib/mnema/media-work/spool \
  && systemctl is-active mnema-media-runner'   # expect root:root 755, 10001:10001 700 and active
```

The unit was written for systemd's sandboxing options but has not been exercised on this host by the repository's
tests; after installing, run `systemd-analyze security mnema-media-runner.service` and check
`journalctl -u mnema-media-runner` once. Disk: a job holds the source (at most 1 GiB video) in Learning's spool,
the runner's copy of it in `.runner`, the container's output and Learning's private copies of the verified result in
`spool/.private`, so 20 GiB leaves room for one job at a time; the 10 GB free-space reserve of the rollout applies
to the host. **Install order:** the health monitor only expects the runner once the recorded release contains the
worker image, so the new monitor, dispatcher and Compose can be installed before the first five-image release
without an alert; the mount, `spool/` and the active runner must exist before that release is approved.

The `media-worker` image is **GPL-configured FFmpeg: its GHCR package must stay private** (see the licensing
section of the worker README). The VPS holds no registry credential and gets none: the other four
images are public, and for the private one the `deploy-production` job sends its own short-lived
Actions token (`packages: read`) to `pull <sha>`.

**Pull.** `pull <sha>` reads at most 4 KiB from stdin *before* taking the lock (like `admit` and
`configure`) and accepts only a token of at least 16 characters from `[A-Za-z0-9_.-]` (the 4 KiB stdin bound is the length limit). It requires the
admitted SHA and a five-image manifest, first removes any `/run/mnema-docker-*` a killed earlier pull left behind (the lock is held), creates a 0700 `DOCKER_CONFIG` under `/run`, runs
`docker --config <tmp> login ghcr.io --username x-access-token --password-stdin` (the token only on
Docker's stdin, never in argv, a file of ours or any output) and pulls *exactly* the five digests of
that manifest (10 min per image, 15 min in total). The temporary config is deleted in a `finally`;
SIGTERM becomes `SystemExit`, so it is removed then too, and Docker's own output is never echoed.
Afterwards all five digests must be in the local store. `deploy` and `rollback` run
`docker compose up --pull never` and `preflight`, `deploy` and `rollback` first check that the five
digests are present (`release images are not present locally`), so a missing image can never trigger a
pull with a stale or absent credential. The forced-command wrapper and the sudoers rule are unchanged:
`pull <sha>` is the same single-argument form.

One-time owner check (GitHub, not in the repository): after the first publication open the package
`media-worker` of the account that owns the repository (Package settings → *Manage Actions access*)
and confirm `MattoYuzuru/Mnema` has at least *Read*; the image's OCI source label links the package
to the repository, which normally inherits that. Confirm it stays private:
`gh api /user/packages/container/mnema%2Fmedia-worker --jq .visibility` must print `private`
(run it as the owner; `/users/<owner>/packages/...` and an anonymous `docker pull` must be denied).
If the first `pull` fails with `registry operation failed`, this setting is the first thing to check.

Memory limits add up to 7.25 GiB for the four Compose services (PostgreSQL 3, Identity 2, Learning 2, frontend 0.25) plus
the 3 GiB of the one running job container on the 12 GB host; real use is far lower.

Releases admitted before the worker existed carry four images. They stay readable
(`status`, `verify`, pruning, the recorded previous-release pointer) but cannot be deployed
or rolled back to: the dispatcher answers `release predates the media worker`. The first
release that contains the worker therefore cannot be undone by `rollback` either. If it aborts
between stopping the writers and the pending marker, nothing has been migrated and no restore is
involved: the dispatcher first tries to bring the recorded release up through Compose and, when
Compose refuses (a pre-worker manifest, or none recorded), falls back to
`docker start mnema-prod-identity-account-1 mnema-prod-learning-1`, which starts the same stopped
containers in place, and says which way worked. Only if both fail does it ask the administrator to
start those two containers by hand (still nothing to restore).

**Input handling.** `admit`, `configure` and `pull` read their stdin (size cap and 30 s deadline)
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

`verify` compares the running images of the four Compose containers with the recorded
manifest, checks the three local readiness URLs, that the media runner service is `active`
(for a release that carries the worker image) and the absence of a pending marker,
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

The dispatcher admits five verified images, including PostgreSQL and the media worker (which Compose never runs), and rewrites
`postgres-image` for every accepted release (the PostgreSQL image is rebuilt per
SHA). The local backup helper refuses a live source using any other image.
Application credential activation is separate root-owned configuration; see the
runtime's auth section.

[OpenSSH forced-command/forwarding contract](https://man.openbsd.org/sshd_config.5),
[GitHub Environment protections](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/managing-environments-for-deployment).
