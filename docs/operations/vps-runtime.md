---
artifact:
  id: vps-runtime
  type: operations-runbook
  title: "Mnema early production VPS runtime"
  status: current
  created_at: "2026-10-04"
  updated_at: "2026-10-05"
  owners: ["project-owner"]
---

# Production VPS runtime

Production serves https://mnema.app and https://auth.mnema.app on the Russian VPS
`mnema` / `135.106.175.30` (Ubuntu 24.04, 6 CPU / 12 GB; `ssh mnema`). All four
Compose containers are healthy; the minute health timer and daily local backup
are active. Actual CI rollout and recovery passed, including maintenance rollback,
isolated restore and external firewall/TLS checks. Latest release evidence:
[publication](https://github.com/MattoYuzuru/Mnema/actions/runs/37223845189),
[rollout](https://github.com/MattoYuzuru/Mnema/actions/runs/37225761472),
[recovery](https://github.com/MattoYuzuru/Mnema/actions/runs/37227222810).
Read the recorded/live SHA and readiness for each new delivery; do not assume a
later main commit is deployed. Public browser acceptance remains separate #349
work; a saved Codex browser permission currently prevents that surface's check.

The owner approved a new empty database; old databases, objects and backups are
preserved. Offsite backup is deferred in #350. Local dump/restore verification
works, but VPS-loss recovery is not provided.

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

Production Turnstile is `blocked`, privacy approval false: login/register fail
closed without a Cloudflare script or Siteverify call. The owner confirmed the
main RKN notification; the cross-border notification is not yet confirmed.
Foreign AI and federated login remain inactive. Media/email credentials and
account-deletion policy are separate unconfigured operations. Do not substitute
fixture keys, Stub providers or a production bypass.

All three OAuth pairs and Turnstile keys exist in the owner's local `.env` and
GitHub repo/prod Secrets. Their private VPS staging file is
`/etc/mnema/production/pending-auth.env` (root:root0600, root-only directory).
The dispatcher does **not** read that file; credentials are prepared for later
activation without making providers available now. GitHub deployment uploads no
application configuration. Existing secrets are preserved.

| Stored key names | Identity container environment |
|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` / `_CLIENT_SECRET` |
| `YANDEX_CLIENT_ID`, `YANDEX_CLIENT_SECRET` | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_ID` / `_CLIENT_SECRET` |
| `GH_CLIENT_ID`, `GH_CLIENT_SECRET` | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID` / `_CLIENT_SECRET` |
| `TURNSTILE_SITE_KEY`, `TURNSTILE_SECRET_KEY` | Same names, Identity only |

GitHub `prod` secret names prepend `PROD_` to those stored names. After the actual
recipient/privacy and production-widget gates pass, the administrator privately
places **only approved pairs** in root-owned `runtime.env`, sets
`MNEMA_IDENTITY_TURNSTILE_MODE=required`,
`MNEMA_IDENTITY_TURNSTILE_PRIVACY_APPROVED=true` and
`MNEMA_FEATURE_FEDERATED_AUTH_ENABLED=true` for approved OAuth providers, and
performs a reviewed current-main rollout with auth evidence. Putting a pair in
active runtime config registers that provider; the frontend toggle alone cannot
block the backend OAuth route. Main RKN confirmation alone is not authorization
for foreign recipients. Never print expanded Compose or credential values.

Provider callbacks are exactly
`https://auth.mnema.app/login/oauth2/code/google`, `/yandex`, `/github` under that
same prefix. The SPA return URL `https://mnema.app/auth/callback` is a different
step. Verify callbacks in each provider account before activation; their dashboard
configuration has not been independently confirmed.

## Deployment and inspection

Follow [production delivery](production-delivery.md) and the
[dispatcher contract](../../deploy/production/README.md): a fresh verified
four-image candidate, administrator admission and protected `vps-deploy.yaml` on
current main. Bootstrap scripts are for a new host only; never rerun them here.
Stop on failed readiness, image mismatch, unsafe migration/rollback, uncertain
`pending.json`, unexpected public ports or sensitive responses/logs. CI cannot
clear reconciliation state or install configuration.

Read-only administrator checks:

```bash
ssh mnema 'sudo /usr/local/sbin/mnema-deploy status'
ssh mnema 'sudo docker ps --format "{{.Names}} {{.Status}}"'
ssh mnema 'sudo systemctl list-timers mnema\* --no-pager'
ssh mnema 'sudo journalctl -t mnema-health -p err --no-pager'
```

Status records are not a live health probe. Verify actual readiness, HTTPS/build
identity, protected routes and auth policy after each rollout; retain firewall,
log/privacy and backup evidence proportionate to the changes.

## Backup, monitoring and rollback

`sudo /usr/local/sbin/mnema-local-backup backup` creates private 0600 dumps and
metadata under `/var/backups/mnema` 0700. `rehearse` is the bounded quiet-window
verification; it never restores production. Daily 03:30 UTC timer is a local
recovery point, not an offsite durability promise. No automatic retention deletion
is enabled; disk reserve alert warns below 10 GB. Future larger datasets need a
bounded streaming reconciliation before this first-release full-table check.

Read alerts with `sudo journalctl -t mnema-health -p err`; inspect timer/service
states and backup failures with systemd. Checks cover both DB-backed readiness
endpoints, nginx, public HTTPS, disk and uncertain rollout. Alerts are local;
an external notification destination remains to be configured by the owner.
Monitor alerts contain component names only. Frontend access logging is disabled;
its request error log is discarded, and Caddy's operational error logger removes
the entire request object (URI/headers/address) while retaining fault diagnostics.
The routing fixture forces a 502 containing a dummy OAuth query marker and verifies
it never appears in Caddy logs. Do not enable raw request/body logging later.

The verified maintenance fallback restores the saved runtime-maintenance Caddyfile
(with the same Unix admin address) and stops only application containers;
retain PostgreSQL and every volume/dump. Do not claim Docker down reverses
migrations. Dispatcher rollback selects only the recorded previous SHA,
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
[JWK integer encoding](https://www.rfc-editor.org/rfc/rfc7518#section-6.3),
[su-exec operation](https://github.com/ncopa/su-exec),
[Spring OAuth registration/callback contract](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html).
