---
artifact:
  id: release-verification-runbook
  type: runbook
  title: "Mnema release smoke, diagnostics and rollback"
  status: current
  created_at: "2026-08-19"
  updated_at: "2026-08-29"
  owners: ["project-owner"]
---

# Mnema release smoke, diagnostics and rollback

Every hosted release is accepted as one six-image unit. Staging and production first snapshot the last verified complete manifest and the current application Secret, apply the candidate once, wait for all rollouts, run the black-box smoke from GitHub-hosted infrastructure and only then record the candidate. A failed rollout, smoke or release-state write rejects the candidate and, while automatic rollback is enabled, restores the prior Secret and reapplies the saved complete manifest.

`Main CI`, `Staging Deploy` and `Production Deploy` are separate workflows. The two privileged workflows run directly after a successful `workflow_run` predecessor so their jobs read only their own GitHub Environment secrets. Artifact downloads are bound to the predecessor run ID; staging verifies both manifests but relays the production manifest only after its smoke and release record succeed. Production verifies that relay again before preview. Every stage binds to the predecessor head SHA and rejects it if it is no longer current `main`.

AI is deliberately absent from this gate. The hosted runtime must report `aiEnabled = false`; no keykomi workload, model or data is read or mutated.

## Environment contract

The dedicated account must use an email login, must not be used by a person and must not contain persistent content beyond its identity. On the first authenticated smoke, an expected `401` causes the runner to create exactly that configured email through the normal local-auth registration endpoint and then retry login. Registration uses the same account-bound Turnstile bypass; a malformed login, duplicate identity, wrong password, unexpected status or second failure rejects the release. No direct database write or manual registration race is required.

Add these secrets to both `staging` and `prod`, using the environment prefix:

| Purpose | Staging | Production |
|---|---|---|
| Dedicated login | `STAGING_SMOKE_LOGIN` | `PROD_SMOKE_LOGIN` |
| Account password | `STAGING_SMOKE_PASSWORD` | `PROD_SMOKE_PASSWORD` |
| Random Turnstile bypass key, at least 32 characters | `STAGING_SMOKE_TURNSTILE_BYPASS_KEY` | `PROD_SMOKE_TURNSTILE_BYPASS_KEY` |

The password is passed only to the GitHub-hosted smoke process. It is not written to Kubernetes. The login and bypass key enter `mnema-secrets` because the auth Pod must recognize the exact dedicated identity. The bypass skips only Turnstile for that login/key pair during registration and login. Registration/login rate limits, input and uniqueness validation, password verification, account locking and moderation still execute. Rotate the password and bypass key together after suspected disclosure.

The optional GitHub Environment variable `AUTO_ROLLBACK_ENABLED` accepts only `true` or `false` and defaults to `true`. Set it to `false` only for a diagnosed rollback fault: the workflow will still reject the candidate, but an applied candidate may remain live and then requires an explicit operator rollback.

## What the smoke proves

Within a five-minute overall deadline and a 15-second per-request timeout, `release_smoke.py` checks:

1. public HTML contains one content-hashed main bundle and the bundle is reachable;
2. frontend runtime configuration has the expected release SHA and hosted AI is disabled;
3. auth, user, core, media and import report the same build identity;
4. the dedicated account can authenticate twice and receives distinct access tokens;
5. a uniquely named private deck and one card can be created;
6. that card is returned by the review queue and answered once with `GOOD`;
7. the deck is archived and hard-deleted in `finally`, including after a later smoke failure.

Hosted releases also run the response and headless-browser checks from the [browser security headers and CSP runbook](browser-security-headers.md). Staging must have the full policy in Report-Only with no observed browser violation; production must enforce that same policy and return the bounded host-only HSTS header. These checks share the release smoke outcome, so a failure rejects and rolls back the candidate.

The current local-login contract returns an access token but no refresh token. The `token_renewal` step therefore performs a second real password login and requires a different JWT `jti`; it does not claim to test an OAuth refresh grant.

The random fixture suffix prevents collisions and makes repeated runs operationally idempotent. The smoke report contains only step names, service names, durations, stable error codes and release identity. It never serializes the login, password, bypass key, access token, deck/card identifiers or response bodies.

For a controlled manual identity-only check, keep credentials out of command arguments:

```bash
python3 scripts/smoke/release_smoke.py \
  --environment staging-manual \
  --public-url https://staging.mnema.app \
  --auth-url https://auth.staging.mnema.app \
  --expected-release-sha 0123456789012345678901234567890123456789 \
  --identity-only \
  --report /tmp/mnema-staging-identity.json
```

Exit `0` means every selected step passed. Exit `1` means invalid configuration or a failed check; the report remains the canonical safe result.

## Release state and automatic rollback

`mnema-release-current` stores the verified manifest and release record. On the next successful release, its former value is copied to `mnema-release-previous`. Records contain the release SHA, exact digest reference for all six services, manifest SHA-256, GitHub run ID, verification time and the schema-compatibility warning.

The same secret-free successful record is retained as a 90-day Actions artifact (`staging-release-record-*` or `production-release-record-*`) for review without cluster access.

On the first guarded deployment, no state ConfigMap exists yet. The workflow reads the live `mnema-release` identity, downloads the exact matching retained Actions artifact, verifies its checksum and all six live image references, then adopts it for the rollback snapshot without mutating cluster state before preview. Any missing artifact, mismatch or incomplete record fails before candidate application. The only exception is the first-ever staging release: it may proceed without a rollback target only when both `mnema-release` and every Mnema application Deployment are absent. A failed first candidate is deleted back to that empty application boundary; production never permits this bootstrap exception.

An older production deployment can predate both release-state ConfigMaps and the
`mnema-release` marker. Do not bypass `live_release_state_missing` or allow an empty
production rollback target. Before moving its credential into the GitHub `prod`
Environment, use the owner/admin context to capture the exact healthy legacy state:

```bash
capture_dir=$(mktemp -d /dev/shm/mnema-live-release.XXXXXX)
chmod 700 "$capture_dir"
KUBECONFIG=/path/to/admin.kubeconfig \
  python3 scripts/smoke/release_state.py capture-live \
    --namespace prod \
    --environment production \
    --release-id '<full SHA matching every live sha-* image tag>' \
    --manifest "$capture_dir/manifest.yaml" \
    --record "$capture_dir/record.json"
```

`capture-live` is read-only apart from Kubernetes server-side dry-run. It requires all
six allowlisted Deployments to be fully observed/ready, every non-terminating Pod to
agree on immutable runtime image digests, the live tag prefix to match the supplied
full commit, all six Services and both application Ingresses to exist, and both release
markers to still be absent. It strips runtime metadata and Service-assigned addresses,
captures no Secret or arbitrary ConfigMap, and writes mode-`0600` files. Review the
resource names, release SHA, digest references, record capability and manifest checksum;
do not publish the manifest as an artifact because it is an operational rollback input.

Persisting the capture is a separate production mutation that requires an exact owner
confirmation immediately before it runs:

```bash
KUBECONFIG=/path/to/admin.kubeconfig \
  python3 scripts/smoke/release_state.py seed-live \
    --namespace prod \
    --manifest "$capture_dir/manifest.yaml" \
    --record "$capture_dir/record.json"
kubectl --kubeconfig=/path/to/admin.kubeconfig -n prod \
  get configmap mnema-release-current -o name
```

This atomically creates only `prod/mnema-release-current`; it fails if current, previous
or live release markers appear after capture, never rotates or overwrites them, and does
not apply the captured manifest or restart a workload. Delete the tmpfs capture after the ConfigMap read-back. The first
guarded deployment then snapshots that digest-pinned baseline normally. A failed candidate
may reapply it and verifies the public bundle plus all five backend readiness endpoints. This
pre-identity capability is reserved for the one captured baseline: a marker-based adopted
release still verifies build identity, and every release produced by the guarded workflow
requires the full authenticated/content smoke. Never run this bootstrap path after either
release marker exists, and never reconstruct the baseline from registry tags without matching
the runtime digest evidence.

The Secret snapshot exists only in a mode-`0600` runner file and is never uploaded as evidence. Restoration first proves that the Secret still has the same Kubernetes UID and exact candidate data, then uses the current `resourceVersion` for an atomic replacement; unexpected concurrent changes fail closed and prevent the old manifest from being applied. Staging also includes the snapshotted `resourceVersion` in candidate apply. `SMOKE_LOGIN` and `SMOKE_TURNSTILE_BYPASS_KEY` are delivery-control credentials, not release state: they deliberately remain at the current Environment version so the runner-only current password stays a consistent verification tuple. Every other application Secret key returns to its saved value. Rollback then verifies the saved manifest checksum, performs a server-side dry run, applies the complete manifest once, reconciles all Secret-consuming Deployments and waits for all six rollouts. Release records created by this mechanism declare authenticated-smoke capability v1 and must pass the full authenticated/content smoke after rollback. An artifact-adopted pre-capability release declares capability v0 and may pass identity-only rollback verification because its auth binary cannot consume the smoke bypass header. A captured release that predates build identity declares the explicit readiness-only capability `-1`. Once a v1 release is recorded, missing, unknown or unmarked downgraded capability is rejected rather than silently weakening verification. Rollback never reconstructs a release from mutable tags.

Binary rollback is safe only across forward-compatible expand/contract database migrations. A release with a destructive schema migration must use a separately verified data restore or roll-forward plan; automatic binary rollback alone is not an acceptable recovery boundary.

## Failure evidence

Before rollback, the workflow records bounded workload status, namespace events and the last 200 lines from only non-ready or smoke-affected services. The collector removes email addresses, UUIDs, IP addresses, bearer values and named identity/credential fields. It does not read Kubernetes Secrets.

The 30-day Actions artifact also contains the safe smoke report, rollback record and post-rollback smoke report. It never contains the rollback Secret snapshot. If those are insufficient, inspect the live cluster under the normal incident-access boundary; do not expand the artifact collector to dump arbitrary ConfigMaps, environment variables or full namespace logs.

## Mandatory staging rollback drill

Run the drill only after a normal staging release has passed and `mnema-release-current` exists:

```bash
gh workflow run staging-rollback-drill.yaml \
  --ref main \
  -f confirmation=RUN_STAGING_ROLLBACK_DRILL
```

The workflow is fixed to the `staging` Environment and `mnema-staging` namespace. It replaces only the saved frontend digest with an impossible SHA-256 value, confirms both rollout and black-box smoke fail, captures diagnostics, reapplies the saved complete manifest, verifies all six rollouts and checks the restored identity. It never records the broken candidate as current. The run is successful only when the complete failure-and-recovery sequence is observed; otherwise treat staging as unavailable and investigate before production promotion.

To remove this mechanism, revert its delivery change. Do not delete either release-state ConfigMap until no deployment or rollback drill is running; deletion removes the directly executable rollback target.
