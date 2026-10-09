---
artifact:
  id: production-delivery
  type: operations-runbook
  title: "Mnema development and production delivery"
  status: current
  updated_at: "2026-10-08"
  owners: ["project-owner"]
---

# Development and production delivery

Production is available at https://mnema.app, with Identity at
https://auth.mnema.app. Administrator access is `ssh mnema` to the Russian VPS
`135.106.175.30`. These are the current targets for development releases.

## Implementation and release

Feature branch → full local quality gate → hosted PR quality/security checks →
protected squash into main → **Main CI** (`deploy.yaml`) releases automatically:

```text
push to main → validate → backend-quality ‖ frontend-quality ‖ release-scope
  → build-and-push (4 images, provenance/SBOM/attestations/Trivy policy)
  → assemble-vps-candidate → deploy-production (Environment prod: ONE approval)
  → post-deploy-smoke → step summary
```

- `release-scope` compares the push with the newest successful `prod` GitHub
  Deployment that **Main CI** created (the status's run is resolved and must be
  `deploy.yaml`; the operations workflow creates none, because its job sets
  `deployment: false`, which keeps required reviewers and secrets). A merge that changes nothing under `backend/`, `frontend/` (outside
  `*.md`), `contracts/`, `deploy/production/` (outside `*.md`), `security/` or the
  release scripts/workflow (`scripts/release_scope.py`) skips build, approval and
  deployment. An unknown baseline releases. Manual dispatch of Main CI
  (`gh workflow run deploy.yaml --ref main`) always releases the current main.
- `deploy-production` waits for the `prod` Environment reviewer. After approval it
  re-verifies the candidate checksums and the GitHub attestations (provenance and
  SBOM) of all four digests, re-checks that the SHA is still the head of main, runs
  the host-drift check, admits the candidate, stages the application configuration,
  deploys and verifies over the forced-command SSH key, and writes the commit, digests, backup name, schema change
  and rollback compatibility to the step summary. Details:
  [dispatcher contract](../../deploy/production/README.md).
- `post-deploy-smoke` checks the public surface: HTML, `www` redirect, build identity
  equal to the commit, OIDC issuer, response-security headers (HSTS `max-age=31536000`, `nosniff` and a CSP on the
  frontend, HSTS on the auth origin), protected `/api` (401), blocked actuator (404).
- Time budget. A normal release takes about 5 to 8 minutes after approval (small database:
  dump and isolated restore take well under a minute; rollout waits for health). Every step
  is individually bounded; the worst case is the sum of those bounds, in seconds: `status`
  630 (10 min lock wait included), `admit` 630, `configure` 630, `deploy` 3570 (lock 600,
  preflight 240, pin check 120, schema before 240, stop 240, backup 930, rollout 420, schema
  after 240, image prune 210, offsite 330), `verify` 1095, in total 6555 s, about 109 min.
  The `deploy-production` job therefore allows 120 minutes. A lost CI job or SSH session
  does not stop a rollout (hang-up is ignored); before the pending marker the dispatcher
  restarts the current release, after it the marker blocks further operations.
- Re-running a failed `deploy-production` job is supported. An older run that was
  superseded by a newer main is refused after approval; approve only the newest run.
- **Host configuration and tooling are not deployed by CI.** Compose, nginx, Caddy,
  the dispatcher, backup and health tooling are installed by the administrator
  *before* approving; the drift check compares host hashes with the reviewed
  repository files and fails with the names of the drifted files. The compared set also
  covers the systemd units, the SSH forced-command wrapper, the sudoers rule and the sshd
  `Match` file, so the CI identity's restrictions cannot silently change.

Install after a PR that changes any of these (from a checkout of the exact main
commit; the first line creates a private staging directory on the host):

```bash
d=$(ssh mnema 'mktemp -d') && cd deploy/production
scp mnema-deploy.py local-backup.py health-monitor.py compose.yaml nginx.conf Caddyfile \
  mnema-health.service mnema-health.timer mnema-local-backup.service mnema-local-backup.timer \
  mnema-deploy-ssh mnema-deploy.sudoers 60-mnema-deploy.conf "mnema:$d/"
ssh mnema "cd $d && sudo install -o root -g root -m 0755 mnema-deploy.py /usr/local/sbin/mnema-deploy \
  && sudo install -o root -g root -m 0755 local-backup.py /usr/local/sbin/mnema-local-backup \
  && sudo install -o root -g root -m 0755 health-monitor.py /usr/local/sbin/mnema-health \
  && sudo install -o root -g root -m 0600 compose.yaml /etc/mnema/production/compose.yaml \
  && sudo install -o root -g root -m 0600 nginx.conf /etc/mnema/production/nginx.conf \
  && sudo install -o root -g root -m 0644 Caddyfile /etc/caddy/Caddyfile \
  && sudo install -o root -g root -m 0644 mnema-health.service mnema-health.timer \
       mnema-local-backup.service mnema-local-backup.timer /etc/systemd/system/ \
  && sudo install -o root -g root -m 0755 mnema-deploy-ssh /usr/local/bin/mnema-deploy-ssh \
  && sudo install -o root -g root -m 0440 mnema-deploy.sudoers /etc/sudoers.d/mnema-deploy \
  && sudo install -o root -g root -m 0644 60-mnema-deploy.conf /etc/ssh/sshd_config.d/60-mnema-deploy.conf \
  && sudo visudo -cf /etc/sudoers.d/mnema-deploy && sudo sshd -t \
  && sudo systemctl daemon-reload && sudo systemctl reload ssh \
  && sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile \
  && sudo systemctl reload caddy && cd / && rm -rf $d"
ssh mnema 'sudo /usr/local/sbin/mnema-deploy status'   # config hashes now equal the repository files
```

Install only the files the PR changed; unchanged files already match (the sshd and
sudoers files need an SSH test from a second session before the first one is closed).
`runtime.env` and `offsite.env` are private and never in the repository; edit them only
over `ssh mnema`. Changed Compose or Caddy behaviour
becomes live with the next release (Compose) or the reload above (Caddy); verify
Caddy changes publicly before approving.

### Agent protocol

After the protected squash merge of a runtime change (and only when the owner's task
names production delivery):

```bash
gh run list --workflow deploy.yaml --branch main --limit 1 --json databaseId,headSha,status
gh run watch <id> --exit-status        # start in the background; do not poll
```

When the run reaches `deploy-production` it waits for the `prod` Environment. If the
administrator install step above is done, approve once:

```bash
env_id=$(gh api repos/MattoYuzuru/Mnema/environments/prod --jq .id)
gh api -X POST repos/MattoYuzuru/Mnema/actions/runs/<id>/pending_deployments \
  -F "environment_ids[]=$env_id" -f state=approved -f comment='<owner task>'
```

When `gh run watch` exits, read the step summary (`gh run view <id>`); on failure read
the failing job (`gh run view <id> --log-failed`) and stop at the first rejected gate.
A rejected drift check, `admit`, backup/rehearsal, readiness, image or smoke failure
is not retried blindly: fix the cause, then re-run the failed job. A failed
`deploy-production` after the pending marker is written needs administrator
reconciliation (`status` reports `needs_admin_reconciliation`). Record the SHA and
verification on the owning work item. Docs-only merges have `release-scope` output
`deploy=false` and nothing to approve.

Two runs can be waiting at once. The concurrency group of `deploy-production` may cancel the
older one by itself; if it is still waiting, **reject** it and approve only the newest
(an older run is refused after approval anyway because its SHA is no longer the head of main):

```bash
gh api -X POST repos/MattoYuzuru/Mnema/actions/runs/<old-id>/pending_deployments \
  -F "environment_ids[]=$env_id" -f state=rejected -f comment='superseded by <new-id>'
```

The operations workflow uses its own concurrency group, so dispatching `status`, `verify`
or `rollback` never cancels a waiting release; the dispatcher's lock serializes the host.

### Application configuration

The `prod` Environment secrets `PROD_<NAME>` are the single source of truth for
application configuration. The names are the allowlist in
[`app-config.keys`](../../deploy/production/app-config.keys) (Turnstile keys and mode,
Google/Yandex/GitHub OAuth pairs, Postbox pair, promo and experiment secrets, events owner
account, learning media upload and avatar storage key pairs). After admission the `deploy-production` job maps exactly these secrets into
one step, builds a JSON object from the non-empty ones and sends it on stdin to the
dispatcher's `configure`, which validates names and values (Turnstile mode only `blocked`
or `required`, and `required` needs both Turnstile keys) and stages the root-only
`app.env.next`; values are never printed. The dispatcher promotes it to `app.env` only
inside `deploy`/`rollback`, after `pending.json` is written, and keeps the replaced file as
`app.env.previous`. A failure before the marker therefore restarts the current release with
the unchanged configuration; after a failed rollout the marker blocks everything and the
administrator decides (restoring the old configuration means copying
`/etc/mnema/production/app.env.previous` over `app.env` before the reconciling redeploy).
`rollback` swaps `app.env.previous` back in when the release it leaves had changed the
configuration (`current.json.config_changed`).

The object is **always** sent, even when empty: the Environment is the single source of
truth, so a secret that is deleted or unset removes its key from the host on the next
release (no "skip when empty" special case, which would make removal impossible). That
cannot open anything: every key has a closed default in Compose (`${NAME:-}`, Turnstile mode
`blocked`), so missing configuration fails closed (providers unavailable, login blocked),
and the step summary lists the delivered names so a shrinking set is visible.

Host-only secrets are **not** delivered by CI: database passwords (`runtime.env`),
`identity-signing.json`, `offsite.env`. The trade-off against the earlier rule that CI
uploads no application configuration is described in the
[dispatcher contract](../../deploy/production/README.md): the values are reachable only
by the `deploy-production` job on `main` after the reviewer approval, and CI cannot
change compose structure, images, privileges, mounts or host files.

Rotate or add a value: update the local dotenv file, run
`python3 scripts/sync_prod_secrets.py --env-file <dotenv> --dry-run`, then without
`--dry-run` (values go to `gh secret set` on stdin; only names are printed). Apply it with
the next release or, without a code change, by **re-running the `deploy-production` job
of the latest successful Main CI run for the current main SHA** (same artifact and image
digests, secrets are read when the job starts, so the fresh values are used):

```bash
run=$(gh run list --workflow deploy.yaml --branch main --status success --limit 1 --json databaseId,headSha --jq '.[0].databaseId')
job=$(gh run view "$run" --json jobs --jq '.jobs[] | select(.name=="deploy-production") | .databaseId')
gh run rerun "$run" --job "$job"       # then approve the prod Environment as usual
```

Do not dispatch Main CI for this: a rebuild of an already admitted SHA yields different image
digests and `admit` refuses that by design. Dispatching is right only when the current main
SHA has no admitted release yet (for example a docs-only commit after the last release).
Containers whose effective environment changed are recreated.
Adding a new name needs a reviewed PR (dispatcher allowlist, `app-config.keys`, workflow
environment entry) and an administrator-installed dispatcher.

Manual operations (`vps-deploy.yaml`, "VPS production operations", also behind the
`prod` approval): `status`, `verify` (live images, readiness, no pending marker) and
`rollback` (only to the recorded previous release when its schema was unchanged).
Deployments themselves happen only in Main CI.

A newer main requires its own run. Do not change an old branch's workflow or bypass
a failed check. Do not bootstrap an existing VPS again, clear uncertain rollout state
from CI, restore a production DB or delete volumes as a deployment shortcut.

## Authority and current limits

An explicit end-to-end implementation request covers branch, gates, PR and the
protected merge when requested. A request to deploy/ship to Mnema production also
covers this finite automatic sequence and the single Environment approval. Agents
can use the working server and these jobs; the target and effect must remain within
the owner's task. Unrelated data destruction and restoration
require their own concrete boundary.

Production has a new database; old databases, objects and backups are preserved.
Each deployment takes a verified pre-migration backup with an isolated restore
rehearsal on the host; offsite copy is optional and configured by the administrator
([VPS runtime](vps-runtime.md#backup-monitoring-and-rollback)).
Turnstile/public password auth and foreign providers remain restricted by the
[actual legal/configuration status](../product/russia-legal-launch-checklist-2026.md).
The former Kubernetes staging/deploy/recovery workflows and manifests were removed
(see Git history before the removal commit); the VPS path above is the only
delivery path.

References: GitHub [Environment protection](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/managing-environments-for-deployment),
[approving a pending deployment](https://docs.github.com/en/rest/actions/workflow-runs#review-pending-deployments-for-a-workflow-run)
and [`gh attestation verify`](https://cli.github.com/manual/gh_attestation_verify).
