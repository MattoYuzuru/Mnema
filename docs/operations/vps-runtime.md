---
artifact:
  id: vps-runtime
  type: operations-runbook
  title: "Mnema early production VPS runtime"
  status: current
  created_at: "2026-10-04"
  updated_at: "2026-10-04"
  owners: ["project-owner"]
---

# First empty-DB deployment on mnema

Owner target: `mnema` / `135.106.175.30`, Ubuntu24.04,6CPU/12GB. This
reactivation is scoped to #349/#345. The owner confirms no users exist on either
target and authorizes an empty new database. Preserve old databases, objects and
backups; this is neither #147 recovery nor old-data cutover.

The owner defers offsite backup integration (#350) for this early release.
Local dump and isolated restore remain required; VPS-loss recovery is **not**
provided. Before general onboarding, finish #350 with separate private RU bucket,
credentials, encryption, retention and off-host restore evidence.

## Runtime and network boundary

Host Caddy alone listens publicly on TCP80/443; its admin uses a0600 Unix socket under caddy-owned0700 `/run/mnema-caddy`,
unreachable by the application UID10001. The service RuntimeDirectory persists
this permission boundary across reboot.
Reviewed Compose uses Linux host networking with explicit127.0.0.1 listeners:
frontend18080,Identity18081,Learning18082,PostgreSQL15432. No published Docker
ports/NAT, Docker socket mounts, or Docker-group access. Host networking reduces
network isolation between these trusted containers; it is chosen for this single
host and the existing loopback SSH egress tunnel, with capability, user, resource
and filesystem constraints. External port checks remain mandatory: UFW alone
does not protect Docker-published ports.

PostgreSQL18 has one persistent volume mounted at `/var/lib/postgresql`, separate
non-superuser schema-owner roles, no cross-schema/PUBLIC grants, and scram TCP
authentication. The container-local Unix socket is an administrator boundary.
Identity alone sees its RSA4096 signing key and DB password; Learning sees only
its DB password. Application images run UID10001 without capabilities and with
read-only roots. Frontend master retains only CHOWN/SETUID/SETGID for nginx workers.
Memory/CPU/PID limits and10MB×3 local log rotation bound resource use.

Caddy rejects public actuator/internal/metrics/admin paths before proxying.
Same-origin `/api` goes to Learning with cookies removed and Set-Cookie stripped;
`auth.mnema.app` is Identity's separate HTTPS issuer/session origin. Forwarded
client address is overwritten from the actual peer; Identity trusts loopback only.
Auth/API responses are no-store. www redirects to the canonical root.

## Intentional first-release limits

Production Turnstile is `blocked` with privacy approval false. Login/register
fail closed even by direct API, with no Cloudflare script/Siteverify call. No
production widget or operator filing is claimed. Foreign AI, federated login,
provider secrets, deletion and unconfigured media/email operations are disabled
or unavailable. Frontend/learning routes still ship the actual main product;
authenticated creation/Study cannot be claimed live while admission is blocked.
Do not substitute fixture keys, Stub providers or a production bypass.

## Administrator sequence and stop conditions

1. Review current main/local+hosted gates, independent PR review and
   [publication/security candidate](vps-image-publication.md). No dirty checkout,
   floating image or expired exception may be admitted.
2. Copy exact merged `deploy/production` inputs to a new root-only bootstrap
   directory on mnema. Preview `install-docker.sh` and `bootstrap-runtime.py`.
   Apply each once; existing/partial state stops for private inspection. Docker
   comes from the official stable apt repository; no conflicting runtime is
   removed. RSA/DB credentials are generated on the RU host and never printed.
3. Install reviewed `caddy-admin.conf` as the root-owned systemd drop-in
   `/etc/systemd/system/caddy.service.d/30-mnema-admin.conf`, daemon-reload, and
   create `/run/mnema-caddy` caddy:caddy0700. Validate and switch to
   `Caddyfile.runtime-maintenance` through the current admin address127.0.0.1:2019;
   later reloads use the Unix socket. Verify an application UID cannot connect.
   Run `scripts/vps_origin_fixture.py` with installed Caddy: actual private-path,
   cookie, forwarded-address, redirect and forced-error log checks use temporary loopback listeners
   only, without modifying the serving origin. Stage candidate image bindings in root-only inputs, pull exact verified
   digests, start only PostgreSQL, and verify separate roles/listeners. Preserve
   the maintenance Caddy configuration until application readiness passes.
4. Run root-only `mnema-local-backup rehearse`. It dumps the current DB and
   restores into a uniquely named network-none PostgreSQL container/volume,
   compares all app-table row counts/digests and role isolation in a quiet window,
   then removes only those newly created ephemeral resources. Original dumps
   remain. Retain checksum/location/RTO evidence on the host.
5. Record actual evidence for every dispatcher gate, including blocked auth and
   the owner-approved empty-data/offsite boundary. Only then stage admitted.json
   and invoke CI-key `preflight <sha>` / `deploy <sha>`. The publication candidate
   never supplies these flags automatically.
   Dispatch `vps-deploy.yaml` on main with that exact `release_sha`; retain existing
   `prod` Environment protection. This workflow invokes only status, preflight
   and deploy with the fixed-target CI key; the dispatcher also permits reviewed
   rollback. The workflow rejects historical reruns, non-main refs and stale main
   after approval, uploads no configuration and retries no failed operation.
6. On successful local readiness, validate and reload full Caddyfile, preserving
   maintenance rollback. Verify public TLS/frontend/SPA/config/build ID,
   blocked register/login, protected API, internal path rejection, no public
   DB/proxy/admin ports, no provider traffic or sensitive response/log content.
7. Repeat local dump/restore after migrations (new state), install root-owned
   backup/health scripts and units, enable timers, exercise a monitor failure
   and recovery, then observe health/logs. Never echo runtime.env or expanded
   Compose config. Keep current SHA/digests and outcome evidence in #349/#345.

Stop on failed gate, image mismatch, migration/readiness failure, unexpected
public listener, secrets in responses/logs, or uncertain pending marker. The CI
identity cannot reconcile/clear that marker or upload runtime configuration.

## Backup, monitoring and rollback

`sudo /usr/local/sbin/mnema-local-backup backup` creates private0600 dumps and
metadata under `/var/backups/mnema`0700. `rehearse` is the bounded quiet-window
verification; it never restores production. Daily03:30UTC timer is a local
recovery point, not an offsite durability promise. No automatic retention deletion
is enabled; disk reserve alert warns below10GB. Future larger datasets need a
bounded streaming reconciliation before this first-release full-table check.

Read alerts with `sudo journalctl -t mnema-health -p err`; inspect timer/service
states and backup failures with systemd. Checks cover both DB-backed readiness
endpoints, nginx, public HTTPS, disk and uncertain rollout. Alerts are local;
an external notification destination remains to be configured by the owner.
Monitor alerts contain component names only. Frontend access logging is disabled;
its request error log is discarded, and Caddy's operational error logger removes
the entire request object (URI/headers/address) while retaining fault diagnostics.
The routing fixture forces a502 containing a dummy OAuth query marker and verifies
it never appears in Caddy logs. Do not enable raw request/body logging later.

Before first release there is no prior verified application image. If unhealthy,
restore the saved runtime-maintenance Caddyfile (with the same Unix admin address) and stop only the new app containers;
retain PostgreSQL and every volume/dump. Do not claim Docker down reverses
migrations. Later dispatcher rollback selects only the recorded previous SHA,
requires current-release schema compatibility and keeps a durable pending marker
on uncertainty. Data recovery is a separate reviewed operation using the retained
dump; no production restore, volume removal or broad prune is authorized here.

Sources: [Docker Ubuntu installation](https://docs.docker.com/engine/install/ubuntu/),
[host networking](https://docs.docker.com/engine/network/drivers/host/),
[firewall boundary](https://docs.docker.com/engine/network/packet-filtering-firewalls/),
[PostgreSQL dump](https://www.postgresql.org/docs/current/app-pgdump.html),
[restore](https://www.postgresql.org/docs/current/app-pgrestore.html),
[Caddy reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy),
[RSA generation](https://cryptography.io/en/stable/hazmat/primitives/asymmetric/rsa/),
[JWK integer encoding](https://www.rfc-editor.org/rfc/rfc7518#section-6.3).
