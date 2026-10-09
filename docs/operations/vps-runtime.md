---
artifact:
  id: vps-runtime
  type: operations-runbook
  title: "Mnema early production VPS runtime"
  status: current
  created_at: "2026-10-04"
  updated_at: "2026-10-08"
  owners: ["project-owner"]
---

# Production VPS runtime

Production serves https://mnema.app and https://auth.mnema.app on the Russian VPS
`mnema` / `135.106.175.30` (Ubuntu 24.04, 6 CPU / 12 GB; `ssh mnema`). Four Compose
containers run behind host Caddy; the minute health timer and the daily local backup
are active. The current release is whatever `mnema-deploy status` and the latest
successful `deploy-production` run report; do not assume a later main commit is
deployed (docs-only merges are intentionally not released). Public browser acceptance
remains separate #349 work; a saved Codex browser permission currently prevents that
surface's check.

The owner approved a new empty database; old databases, objects and backups are
preserved. Every release takes a verified pre-migration dump with an isolated restore
rehearsal on this host; an optional encrypted offsite copy is configured below. VPS
loss recovery depends on that offsite copy and is not otherwise provided.

## Runtime and network boundary

Host Caddy alone listens publicly on TCP 80/443; its admin uses a 0600 Unix socket under caddy-owned 0700 `/run/mnema-caddy`,
unreachable by the application UID 10001. The service RuntimeDirectory persists
this permission boundary across reboot.
Reviewed Compose uses Linux host networking with explicit 127.0.0.1 listeners:
frontend 18080, Identity 18081, Learning 18082, PostgreSQL 15432. No published Docker
ports/NAT, Docker socket mounts, or Docker-group access. Host networking reduces
network isolation between these trusted containers; it is chosen for this single
host and the existing loopback SSH egress tunnel, with capability, user, resource
and filesystem constraints. External port checks remain mandatory: UFW alone
does not protect Docker-published ports.

PostgreSQL 18 has one persistent volume mounted at `/var/lib/postgresql`, separate
non-superuser schema-owner roles, no cross-schema/PUBLIC grants, and scram TCP
authentication. The container-local Unix socket is an administrator boundary.
Identity alone sees its RSA 4096 signing key and DB password; Learning sees only
its DB password. Application images run UID 10001 without capabilities and with
read-only roots. Frontend master retains only CHOWN/SETUID/SETGID for nginx workers.
Memory/CPU/PID limits and 10 MB × 3 local log rotation bound resource use.

PostgreSQL is the fourth verified release image: the pinned official 18.6 Alpine
base retains its entrypoint with only the unique privilege-switch call changed
from gosu to Alpine's su-exec (same user/command direct-exec operation); the
vulnerable Go-based gosu binary is removed entirely. It passes
the same provenance, SBOM and HIGH/CRITICAL gate as the applications. No scan
exception is introduced. The local restore uses the exact administrator-owned
`/etc/mnema/production/postgres-image` digest and verifies the running source agrees.

Caddy rejects public actuator/internal/metrics/admin paths before proxying.
Same-origin `/api` goes to Learning with cookies removed and Set-Cookie stripped;
`auth.mnema.app` is Identity's separate HTTPS issuer/session origin. Forwarded
client address is overwritten from the actual peer; Identity trusts loopback only.
Auth/API responses are no-store. www redirects to the canonical root.

## Auth configuration

Production opened password and OAuth sign-in on 2026-10-08 by owner decision.
Identity runs Turnstile in `required` mode: `/api/accounts/register` and
`/login` verify a Cloudflare Siteverify token (exact hostname and action, age,
replay, timeout, fail closed) before any credential check. The owner submitted
the main RKN notification and the cross-border notification for Cloudflare and
accepts the part 11 article 12 waiting-period risk. The public
privacy policy ([source](../../frontend/src/app/privacy-page.component.html)) and
terms ([source](../../frontend/src/app/terms-page.component.html)) are published from the
frontend code. Foreign AI stays disabled; payments stay closed. Do not substitute
fixture keys, Stub providers or a production bypass.

Application configuration (the approved Google, Yandex and GitHub OAuth pairs, the
Turnstile keys, `MNEMA_IDENTITY_TURNSTILE_MODE`, the Postbox pair, the promo and
experiment secrets and `MNEMA_EVENTS_OWNER_ACCOUNT_ID`) is delivered by the deploy
job from the GitHub Environment `prod` secrets (`PROD_<NAME>`) into the root-owned
`/etc/mnema/production/app.env`, which the dispatcher passes to Compose as an extra
`--env-file`; the mechanism is in [production delivery](production-delivery.md).
Names map 1:1: Compose interpolates `${GOOGLE_CLIENT_ID:-}` and the like, so an
absent value leaves the feature off. Host-only secrets (database passwords) stay in
`runtime.env`, the signing key in `identity-signing.json`. There is no
`pending-auth.env`, no privacy flag and no frontend federation flag: Identity
registers a provider when its pair is present, and the SPA shows a button only for a
provider that `GET /api/accounts/providers` returns (an empty list shows none).

| `prod` secret (`PROD_` prefix) / env name | Identity container environment |
|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` / `_CLIENT_SECRET` |
| `YANDEX_CLIENT_ID`, `YANDEX_CLIENT_SECRET` | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_ID` / `_CLIENT_SECRET` |
| `GH_CLIENT_ID`, `GH_CLIENT_SECRET` | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID` / `_CLIENT_SECRET` |
| `TURNSTILE_SITE_KEY`, `TURNSTILE_SECRET_KEY` | Same names, Identity only |

**Before a release with the `required` default**, the `prod` Environment must
already hold `PROD_TURNSTILE_SITE_KEY` and `PROD_TURNSTILE_SECRET_KEY`. Identity
refuses to start in `required` mode without valid keys (and Learning waits for
Identity's health), so a release without them fails readiness and leaves the
dispatcher's pending marker for administrator reconciliation (restore
`app.env.previous`, see [production delivery](production-delivery.md)). The
dispatcher's `configure` already refuses `required` without both keys. The
emergency switch is `PROD_MNEMA_IDENTITY_TURNSTILE_MODE=blocked`
in the `prod` secrets: it starts Identity without keys, closes password sign-in and
leaves OAuth open.

`MNEMA_IDENTITY_TURNSTILE_MODE` defaults to `required`. `blocked` is the
operational kill switch: password login and registration answer 503 without any
Siteverify call and the browser loads no Cloudflare script, while OAuth sign-in
keeps working. `disabled` is accepted only in local environments; production turns
it into `blocked`. Test keys are refused outside local environments. To close
password sign-in, set the `PROD_MNEMA_IDENTITY_TURNSTILE_MODE` secret to `blocked`
and redeploy; never disable the check. An empty value means the default `required`.

Optional capabilities stay off until the deployed application configuration carries
their keys (Compose already passes the names; empty keys keep the feature off, and
the non-empty application defaults for endpoints, regions and the avatar bucket are
repeated explicitly because Spring reads a set-but-empty variable as an empty
string). Yandex Postbox mail (`MNEMA_POSTBOX_ACCESS_KEY`, `MNEMA_POSTBOX_SECRET_KEY`:
recovery and verification mail) and the Yandex Object Storage offsite backups are
disclosed in the public policy as current Russian processors. Avatar storage
(`MNEMA_AVATAR_ACCESS_KEY`, `MNEMA_AVATAR_SECRET_KEY`) and Learning media storage
(`LEARNING_MEDIA_UPLOAD_BUCKET`, `LEARNING_MEDIA_UPLOAD_ACCESS_KEY`,
`LEARNING_MEDIA_UPLOAD_SECRET_KEY`) are not announced as active: enabling them,
account deletion (`MNEMA_IDENTITY_DELETION_ENABLED`, currently `false`) or any
foreign recipient requires updating the privacy policy first. Media processing for
audio and video needs a separate worker that production does not run.

Provider callbacks are exactly
`https://auth.mnema.app/login/oauth2/code/google`, `/yandex`, `/github` under that
same prefix; the owner confirmed them and the Turnstile widget hostnames. The SPA
return URL `https://mnema.app/auth/callback` is a different step. Never print
expanded Compose or credential values.

## Deployment and inspection

Releases run in Main CI after one `prod` approval; follow
[production delivery](production-delivery.md) and the
[dispatcher contract](../../deploy/production/README.md). Host configuration and
tooling (Compose, nginx, Caddy, dispatcher, backup, health monitor, `runtime.env`)
are installed by the administrator before approval and checked for drift; bootstrap
scripts are for a new host only, never rerun here. Stop on failed readiness, image
mismatch, unsafe migration/rollback, uncertain `pending.json`, unexpected public ports
or sensitive responses/logs. CI cannot clear reconciliation state or install
configuration.

Read-only administrator checks:

```bash
ssh mnema 'sudo /usr/local/sbin/mnema-deploy status'   # sha, pending state, config hashes, backup age
ssh mnema 'sudo /usr/local/sbin/mnema-deploy verify'   # running images, readiness, no pending marker
ssh mnema 'sudo docker ps --format "{{.Names}} {{.Status}}"'
ssh mnema 'sudo systemctl list-timers mnema\* --no-pager'
ssh mnema 'sudo journalctl -t mnema-health -p err --no-pager'
ssh mnema 'sudo tail -n 5 /var/lib/mnema-release/admissions.jsonl'   # append-only admission audit
```

`status` is not a live probe; `verify` and the automatic public smoke are. Retain
firewall, log/privacy and backup evidence proportionate to the changes.

## Backup, monitoring and rollback

`sudo /usr/local/sbin/mnema-local-backup backup` creates private 0600 dumps and
metadata under `/var/backups/mnema` 0700 and validates the archive with
`pg_restore --list`; `rehearse` additionally restores it in an isolated network-less
container and reconciles rows and role isolation. `pre-deploy` (called only by the
dispatcher, writers stopped, lock held) does the full rehearsal and gates every
rollout; it never uploads, because the writers are down while it runs. Retention keeps the **two newest complete** dump+metadata pairs (a marker
of `verified: list|restore` is required) and removes older pairs only after the new
one is verified; failures (and SIGTERM) delete only their own partial files, rehearsal
container/volume and credentials file, and other files are never touched. The daily
03:30 UTC timer shares the production lock with deployments. `mnema-local-backup newest`
prints the newest complete backup; the dispatcher status and the health monitor use it,
so there is one definition of "complete".

Optional offsite copy (the dispatcher uploads the pre-deploy dump after a successful
rollout, bounded to 5 minutes, and the daily timer uploads its own; `mnema-local-backup
offsite <backup-name>` re-uploads one and exits 3 when offsite is not configured):
when root-only `/etc/mnema/production/offsite.env` (0600) exists with
`MNEMA_OFFSITE_ENDPOINT` (`https://storage.yandexcloud.net`), `_REGION`
(`ru-central1`), `_BUCKET`, `_PREFIX` (for example `mnema-vps/postgres`),
`_KMS_KEY_ID`, `_ACCESS_KEY_ID` and `_SECRET_ACCESS_KEY`, every backup is uploaded
with one `curl --aws-sigv4` PUT per object to
`<prefix>/<backup-name>/<backup-name>.dump` and `.json`. The bucket policy requires
server-side KMS encryption, `x-amz-acl: private` and write-once semantics, which the
upload sends (`x-amz-server-side-encryption: aws:kms`, the key id, `If-None-Match: *`);
an existing object counts as stored. Credentials are read from a 0600 temporary
`curl -K` file in `/run`, never from argv. The uploader identity must be write-only
(no read, list or delete); expiry is a bucket lifecycle rule, not this tool. Verified
against the real bucket: the same key a second time returns 412 (treated as stored),
versioning is enabled, the uploader gets 403 on DELETE, on writes outside the prefix and
on writes without SSE-KMS. Each curl call is bounded (`--max-time 600`, abort below
1 KiB/s for 60 s). Offsite
failure never fails a local backup or rollout; it is reported as `backup`/`offsite`
warnings and by the health alert below. Without `offsite.env` nothing leaves the host.

Read alerts with `sudo journalctl -t mnema-health -p err`; inspect timer/service
states and backup failures with systemd. Checks cover both DB-backed readiness
endpoints, nginx, public HTTPS, disk, uncertain rollout (`rollout_reconciliation`),
`backup_stale` (newest complete backup older than 26 h) and, when `offsite.env`
exists, `offsite_stale` (last successful upload older than 26 h). Alerts are local;
an external notification destination remains to be configured by the owner.
Monitor alerts contain component names only. Frontend access logging is disabled;
its request error log is discarded, and Caddy's operational error logger removes
the entire request object (URI/headers/address) while retaining fault diagnostics.
The routing fixture forces a 502 containing a dummy OAuth query marker and verifies
it never appears in Caddy logs. Do not enable raw request/body logging later.

The verified maintenance fallback restores the saved runtime-maintenance Caddyfile
(with the same Unix admin address) and stops only application containers;
retain PostgreSQL and every volume/dump. Do not claim Docker down reverses
migrations. Dispatcher rollback (`vps-deploy.yaml` operation `rollback`) selects only
the recorded previous SHA and is allowed only when the current release recorded an
unchanged schema fingerprint (`rollback_compatible`); it repeats the quiesce,
backup-with-rehearsal and pending-marker flow. After a schema change, recover by
roll-forward or from the pre-deploy dump in a separate reviewed operation; no
production restore, volume removal or broad prune is authorized here. Each deployment
prunes only local `ghcr.io/mattoyuzuru/mnema/*` images outside the current and
previous release.

Sources: [Docker Ubuntu installation](https://docs.docker.com/engine/install/ubuntu/),
[host networking](https://docs.docker.com/engine/network/drivers/host/),
[firewall boundary](https://docs.docker.com/engine/network/packet-filtering-firewalls/),
[PostgreSQL dump](https://www.postgresql.org/docs/current/app-pgdump.html),
[restore](https://www.postgresql.org/docs/current/app-pgrestore.html),
[Caddy reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy),
[RSA generation](https://cryptography.io/en/stable/hazmat/primitives/asymmetric/rsa/),
[JWK integer encoding](https://www.rfc-editor.org/rfc/rfc7518#section-6.3),
[su-exec operation](https://github.com/ncopa/su-exec),
[Spring OAuth registration/callback contract](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html).
