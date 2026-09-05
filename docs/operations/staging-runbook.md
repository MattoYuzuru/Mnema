---
artifact:
  id: staging-runbook
  type: runbook
  title: "Mnema staging bootstrap and secret contract"
  status: current
  created_at: "2026-08-18"
  updated_at: "2026-09-05"
  owners: ["project-owner"]
---

# Mnema staging bootstrap and secret contract

Staging is an isolated namespace on the shared main k3s host. Replacement releases ship only the digest-pinned Identity & Account and Learning runtimes in explicit maintenance. Production promotion is disabled until #147; production still has its previously applied topology. Staging owns separate PostgreSQL, Redis, MinIO, credentials and TLS hosts. It has a default recovery objective of RPO 24 hours / RTO 4 hours. Sharing the host remains a failure-domain limitation, not a claim of production-grade availability.

The PostgreSQL 18 PVC is mounted at `/var/lib/postgresql`, matching the official image's version-specific `PGDATA` and volume contract ([PostgreSQL official image](https://hub.docker.com/_/postgres#pgdata)). Do not restore the pre-18 `/var/lib/postgresql/data` mount convention.

## Owner prerequisites

The replacement workflow requires an existing initialized database/Secret and a
verified previous release. It does not perform the historical first-deploy Secret
initialization or data/bucket setup described below. Existing installations must
not reapply the whole bootstrap to enable route switching; use the narrow procedure
in the next section. In particular, do not switch routes before capturing the old
release or overwrite an initialized Secret with the empty bootstrap template.

Create DNS records for `staging.mnema.app`, `auth.staging.mnema.app` and `storage.staging.mnema.app` pointing to the main host. Do not point them at keykomi and do not reuse production data or object-storage credentials.

With the existing root/admin k3s context, preview and apply the Namespace first, then the complete policy/access boundary. The helper preflights every `kubectl diff` in a phase before its first apply; an operational diff status greater than `1` therefore produces zero applies in that phase. Inspect the preview, then opt in explicitly:

```bash
PHASE=namespace APPLY_CHANGES=false ./scripts/apply-staging-bootstrap.sh
PHASE=namespace APPLY_CHANGES=true ./scripts/apply-staging-bootstrap.sh
PHASE=boundary APPLY_CHANGES=false ./scripts/apply-staging-bootstrap.sh
PHASE=boundary APPLY_CHANGES=true ./scripts/apply-staging-bootstrap.sh
```

This creates `mnema-staging`, ResourceQuota, LimitRange, default-deny networking, three fixed staging routes, one empty Opaque `mnema-secrets` marked `uninitialized`, and a Role-bound `mnema-deployer` ServiceAccount. It creates no application workload or PVC. The first staging deployment accepts that empty Secret only while no StatefulSet or PVC exists, writes the initialized marker with the values, and admission prevents the marker moving backwards. A missing/empty production Secret always fails. The owner-installed `ValidatingAdmissionPolicy` objects are deliberately cluster-scoped but bind only to the owner-controlled staging Namespace label; the CI identity receives no cluster-scoped permission. Kubernetes v1.30+ is a hard prerequisite because the stable admission API is the fail-closed enforcement boundary ([Validating Admission Policy](https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/)).

The Namespace immediately enforces the Pod Security `baseline` profile and audits/warns against `restricted`; initial `latest` labels prevent an unlabeled gap before credential creation. The kubeconfig generator then pins all three policy versions to the actual k3s server minor and refuses to emit the credential unless scoped server-side probes prove that privileged, hostPath and hostNetwork Deployments are rejected specifically by Pod Security. Kubernetes warns that permission to create workloads is an escalation path; baseline enforcement supplies the necessary host boundary while the current images are brought toward the restricted profile ([Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/), [RBAC good practices](https://kubernetes.io/docs/concepts/security/rbac-good-practices/)).

The base CI Role cannot create or change Ingress objects. #143 adds only a separate named read/update/patch grant after proving a fail-closed route admission policy; see below. Kubernetes RBAC cannot restrict a top-level `create` by resource name, so generic Ingress creation remains forbidden on the shared Traefik controller. A fail-closed admission policy additionally restricts mutable Services to ClusterIP/headless forms without `externalIPs` or `nodePort`, so K3s ServiceLB cannot turn staging input into host-port listeners. Workloads cannot use the `mnema-deployer` identity and must explicitly disable token automounting. Secret access is limited to update/get/patch of the precreated `mnema-secrets`; admission keeps it Opaque, rejects ServiceAccount-token annotations and rejects undocumented data keys. These controls prevent both non-expiring token Secrets and workload-mounted rotating deployer tokens ([Service](https://kubernetes.io/docs/concepts/services-networking/service/), [K3s ServiceLB](https://docs.k3s.io/networking/networking-services), [ServiceAccount tokens](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/)).

Default-deny ingress/egress permits same-namespace traffic, kube-system Traefik ingress, kube-dns and public IPv4/IPv6 egress while excluding private, loopback, link-local, documentation and multicast ranges. This blocks direct staging access to production Pod/Service networks and `169.254.169.254`. Kubernetes deliberately permits Pod traffic to its resident node regardless of NetworkPolicy, so NetworkPolicy alone is not the host boundary ([NetworkPolicy behavior](https://kubernetes.io/docs/concepts/services-networking/network-policies/#network-traffic-filtering)).

On the single shared k3s node, install the owner-managed persistent host rule before generating any credential. It blocks every cluster Pod CIDR from node listeners except the shared public web ports, Kubernetes-Service traffic identified by its original ClusterIP destination, the current kube-system metrics-server Pod on kubelet `10250`, and the exact current production Prometheus Pod on node-exporter `9100` plus kubelet/cAdvisor `10250`; it always blocks the directly advertised Kubernetes API endpoint. Source addresses are exact Pod IPs, so staging receives none of these telemetry exceptions. The service fully builds and hooks the alternate A/B chain, then unhooks—but does not delete—the previous complete chain before requiring Prometheus scrapes and Metrics API node samples newer than that activation. This matters because an allowed rule uses `RETURN`: leaving the stale hook behind would evaluate it next and reject a newly allowed Pod IP. Fresh evidence deletes the unhooked previous chain; a failed probe restores its hooks before removing the candidate. Its one-minute timer rediscovers trusted Pod addresses after k3s starts.

```bash
sudo KUBE_API_SERVER=https://<main-cluster-api-host>:6443 \
  ./scripts/install-staging-host-firewall.sh
```

Stop if the host uses another authoritative firewall manager until the equivalent rules and persistence are reviewed; do not run two managers that overwrite each other. Each systemd reconciliation and the kubeconfig generator require Prometheus `node-exporter`, `kubelet` and `cadvisor` targets to remain `up`; refresh acceptance additionally requires fresh node samples from `metrics.k8s.io`. The generator also checks live iptables rules, enabled/active systemd units, exact API origin, every resident node InternalIP/ExternalIP on `2379`, `2380`, `6443`, `10250`, production Redis, the Kubernetes Service and metadata, plus same-namespace, DNS and public HTTPS egress. Any unhealthy production telemetry target, reachable sensitive endpoint or missing rule is a no-go. Exercise both A/B refreshes, force one post-activation telemetry failure and prove the previous chain remains active, interrupt one population attempt, wait for a timer refresh, reboot the node once, and rerun the probes before uploading the credential.

Kubernetes defines a `Role` as namespace-scoped and documents both the `create` limitation and LimitRange default injection before quota admission ([RBAC](https://kubernetes.io/docs/reference/access-authn-authz/rbac/), [LimitRange](https://kubernetes.io/docs/concepts/policy/limit-range/)). The quota is sized for the accepted ~113 GiB free-disk envelope: 3 requested CPU, 8 GiB requested memory, 14 GiB memory limit, 20 GiB PVC storage, 12 GiB requested / 40 GiB limited ephemeral storage, plus explicit counts for each writable API-object class. Before credential issuance, live `max(status.used.count/secrets, actual Secret count)` must leave at least five slots: three simultaneous cert-manager next-private-key Secrets and two bounded recovery slots. A hard limit of 12 with four used passes; 10 or 12 used fails. The six application Deployments retain only two old ReplicaSets each. The quota of 36 fits 18 steady-state ReplicaSets, simultaneous image and Secret-generation template changes for all six Deployments, and one recovery revision per Deployment. Each container receives 256 MiB requested / 2 GiB limited ephemeral storage by default and cannot exceed 4 GiB. Verify the live kubelet reports `ephemeral-storage` capacity and enforces filesystem accounting; otherwise add a host-level disk/eviction guard before provisioning staging.

The owner-applied `letsencrypt-prod` solver has a DNS-zone-specific `staging.mnema.app` rule using `ClusterIP`; cert-manager otherwise defaults HTTP-01 solver Services to `NodePort`, which the staging admission boundary correctly rejects ([cert-manager HTTP-01](https://cert-manager.io/docs/configuration/acme/http01/)). Current cert-manager solver Pods explicitly set `automountServiceAccountToken: false`, so they pass the same strict Pod identity policy without an exemption; an older incompatible controller fails closed. Before issuing the deployer credential, the generator requires the ClusterIssuer and all three Certificates to be `Ready`, verifies the TLS Secrets and scheduled renewal metadata, and checks Secret quota headroom. During live acceptance, run one owner-controlled `cmctl renew` cycle for the three certificate names and repeat the check; renewal failure is a no-go.

Generate a bounded kubeconfig directly outside the repository; the script verifies that it can mutate staging workloads but cannot read production secrets, create namespaces or change shared-ingress routing:

```bash
OUTPUT=/root/mnema-staging.kubeconfig \
KUBE_API_SERVER=https://<main-cluster-api-host>:6443 \
TOKEN_DURATION=720h \
./scripts/create-staging-kubeconfig.sh
```

`KUBE_API_SERVER` must be the existing externally reachable TLS origin of the main cluster, with a certificate valid for that host. The generator uses Python's standard URL/IP parsers to reject localhost, the full IPv4/IPv6 loopback classes, credentials, path/query/fragment ambiguity and malformed ports; the default root k3s loopback endpoint is never accepted. Do not expose a new endpoint or use keykomi for this bootstrap. The command pins Pod Security labels as its only persistent cluster mutation, then the generated credential's own authorization and forbidden-workload checks must succeed through that exact endpoint before the file is accepted.

Base64-encode that file without printing it and store the result as the `STAGING_KUBECONFIG_B64` secret in the GitHub `staging` environment. Never pipe an unchecked remote command directly into `gh secret set`: GitHub accepts an empty stdin as a valid secret update. Capture the base64 in shell memory with tracing disabled, reject an empty or implausibly short result, upload it through stdin, then unset it and delete the remote tmpfs file:

```bash
set +x
staging_kubeconfig_b64=$(ssh yandex \
  'sudo base64 -w0 /run/mnema-staging.kubeconfig')
if [ "${#staging_kubeconfig_b64}" -lt 500 ]; then
  unset staging_kubeconfig_b64
  echo 'Refusing an empty or truncated staging kubeconfig' >&2
  exit 1
fi
printf '%s' "$staging_kubeconfig_b64" | \
  gh secret set STAGING_KUBECONFIG_B64 --env staging -R MattoYuzuru/Mnema
unset staging_kubeconfig_b64
ssh yandex 'sudo rm -f /run/mnema-staging.kubeconfig'
```

Authenticate `sudo` through the owner's protected stdin or interactive mechanism; never put its password in an argument, log or copied command. `gh secret list --env staging` proves only that the name and update timestamp exist, not that the encrypted value is non-empty. After rotating a secret, start a fresh `Main CI` run from `main` with `workflow_dispatch`; a rerun of a workflow that began before the rotation can retain its original secret snapshot.

Record the script's `token_expires_at` value and rotate to a new file/secret before that time; the script deliberately refuses to overwrite an existing credential. The API server may issue a shorter duration than requested. Kubernetes recommends bounded TokenRequest credentials over manually created non-expiring ServiceAccount token Secrets ([ServiceAccount tokens](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/)). GitHub `staging` and `prod` environments must both keep a custom deployment-branch policy with the single exact branch `main`; `prod` must additionally keep the required reviewer. Read back the environment and branch-policy APIs before uploading any credential. This exact-main policy was installed and read back on 2026-08-19; changing it is a security-sensitive owner action ([GitHub deployment branch policies](https://docs.github.com/en/rest/deployments/branch-policies)).

## Replacement route access

Target: only `mnema-staging` on `yandex`. Protected: `prod`, MinIO's route and data,
TLS identities, namespace labels, existing workload/Secret permissions, DB/PVCs and
backups. The existing cluster was read back as `v1.34.3+k3s1` on 2026-09-05; staging
and drill clients use current `kubectl v1.35.8` within the supported one-minor skew
([Kubernetes version-skew policy](https://kubernetes.io/releases/version-skew-policy/)).
Cluster upgrade remains separate shared-host maintenance, not part of #143.

Using the admin context, preview exactly `application-route-boundary.yaml`, apply
only that policy/binding, and run the read-only positive/negative server probes:

```bash
kubectl diff -f k8s/staging/application-route-boundary.yaml
kubectl apply -f k8s/staging/application-route-boundary.yaml
python3 scripts/verify-staging-route-boundary.py
kubectl diff -f k8s/staging/application-route-access.yaml
kubectl apply -f k8s/staging/application-route-access.yaml
```

Diff exit `1` means reviewed changes; anything above `1` stops the operation.
Do not apply the access grant unless policy generation/type-checking is current
and all allowed/forbidden route probes pass. The verifier performs only reads and
server dry-runs. Read back the resulting Role/RoleBinding and prove named
get/patch/update on `mnema`/`mnema-auth` are allowed, while create/delete/list,
`minio`, and every production Ingress operation remain denied. Capture MinIO's
spec checksum before and after the transition/drill; it must not change.

The policy fixes host, TLS host/Secret, class and cert-manager issuer, rejects
default backends and routing annotations, and permits only one complete old or new
path/backend set. Last-applied bookkeeping is permitted but removed from stored
snapshots. New routes are `/api` → `mnema-learning:80` on `staging.mnema.app` and
`/` → `mnema-identity-account:80` on the dedicated `auth.staging.mnema.app` host,
so standard root OAuth/OIDC discovery, authorization, login and logout endpoints
remain reachable. No legacy aliases
exist in the replacement route set. The legacy alternative is only for restoring
the exact saved previous staging release.

Stop on unexpected route/annotation drift, policy warnings, a wrongly accepted
negative probe, or unknown resource inventory. Before any app transition, this
permission-only change can be reversed by removing just RoleBinding
`mnema-application-route-deployer`; leave the fail-closed policy in place. Never
remove enforcement while its access grant is active. No application or data is
deleted by this bootstrap procedure.

## Environment-owned secret names

Current Identity delivery also requires a fresh
`STAGING_IDENTITY_SIGNING_JWK_SET`/`STAGING_IDENTITY_SIGNING_ACTIVE_KID` pair.
The staging workflow validates and server-previews an add-only update to the existing
`mnema-secrets` object, then initializes it once under `resourceVersion`; matching
state is idempotent and partial/different state fails into the explicit rotation path.
The runtime mounts the private set read-only and reads existing database, provider
and avatar credentials by Secret reference.
The three `STAGING_SMOKE_*` inputs below are used only if a legacy rollback needs
its previously recorded authenticated smoke. They are not required by new shells.
The remaining table is the retained legacy/production credential inventory, not a
list of new replacement dependencies. #142 owns fresh Identity authentication and
mail configuration; legacy signing/session state must not silently carry over.

Values are never copied to issues, pull requests, logs or repository files. Production and staging deliberately use different prefixes. `Main CI` has no Environment and no deployment secrets. Successful main CI triggers direct `Staging Deploy`; accepted staging triggers the unprivileged `Production Deploy` gate, which keeps all production jobs disabled until #147. Each triggered workflow first runs an unprivileged predecessor/artifact gate: an unsuccessful or invalid predecessor becomes an actual failed workflow, never a successful skipped Environment job. The privileged jobs themselves declare `staging` or `prod`, and no workflow uses `secrets: inherit`, so repository secrets are not passed into either deployment workflow. GitHub documents that `workflow_run` may access secrets and predecessor artifacts; this repository therefore accepts only successful main-branch predecessors, verifies checksums and the exact predecessor SHA, and rejects stale releases before credential access ([workflow_run](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run)).

| GitHub environment | Required names |
|---|---|
| `staging` | `STAGING_KUBECONFIG_B64`, `STAGING_IDENTITY_SIGNING_JWK_SET`, `STAGING_IDENTITY_SIGNING_ACTIVE_KID`, `STAGING_AUTH_ISSUER`, `STAGING_AUTH_ISSUER_URI`, `STAGING_AUTH_JWT_PUBLIC_KEY`, `STAGING_AUTH_JWT_PRIVATE_KEY`, `STAGING_TURNSTILE_SITE_KEY`, `STAGING_TURNSTILE_SECRET_KEY`, `STAGING_GOOGLE_CLIENT_ID`, `STAGING_GOOGLE_CLIENT_SECRET`, `STAGING_GH_CLIENT_ID`, `STAGING_GH_CLIENT_SECRET`, `STAGING_YANDEX_CLIENT_ID`, `STAGING_YANDEX_CLIENT_SECRET`, `STAGING_POSTGRES_DB`, `STAGING_POSTGRES_USER`, `STAGING_POSTGRES_PASSWORD`, `STAGING_AWS_REGION`, `STAGING_AWS_ACCESS_KEY_ID`, `STAGING_AWS_SECRET_ACCESS_KEY`, `STAGING_AWS_BUCKET_NAME`, `STAGING_MEDIA_INTERNAL_TOKEN`, `STAGING_CORE_INTERNAL_TOKEN`, `STAGING_USER_INTERNAL_TOKEN`, `STAGING_SMOKE_LOGIN`, `STAGING_SMOKE_PASSWORD`, `STAGING_SMOKE_TURNSTILE_BYPASS_KEY` |
| `prod` | `PROD_KUBECONFIG_B64`, `PROD_RELEASE_BINDING_KEY`, `PROD_AUTH_ISSUER`, `PROD_AUTH_ISSUER_URI`, `PROD_AUTH_JWT_PUBLIC_KEY`, `PROD_AUTH_JWT_PRIVATE_KEY`, `PROD_TURNSTILE_SITE_KEY`, `PROD_TURNSTILE_SECRET_KEY`, `PROD_GOOGLE_CLIENT_ID`, `PROD_GOOGLE_CLIENT_SECRET`, `PROD_GH_CLIENT_ID`, `PROD_GH_CLIENT_SECRET`, `PROD_YANDEX_CLIENT_ID`, `PROD_YANDEX_CLIENT_SECRET`, `PROD_POSTGRES_DB`, `PROD_POSTGRES_USER`, `PROD_POSTGRES_PASSWORD`, `PROD_AWS_REGION`, `PROD_AWS_ACCESS_KEY_ID`, `PROD_AWS_SECRET_ACCESS_KEY`, `PROD_AWS_BUCKET_NAME`, `PROD_MEDIA_INTERNAL_TOKEN`, `PROD_CORE_INTERNAL_TOKEN`, `PROD_USER_INTERNAL_TOKEN`, `PROD_GF_SECURITY_ADMIN_USER`, `PROD_GF_SECURITY_ADMIN_PASSWORD`, `PROD_SMOKE_LOGIN`, `PROD_SMOKE_PASSWORD`, `PROD_SMOKE_TURNSTILE_BYPASS_KEY` |

For staging, both issuer values must be `https://auth.staging.mnema.app`; the bucket is namespace-local MinIO and must use a staging-only name. OAuth applications must allow the staging callbacks before federated login is considered verified. Public OAuth client IDs and the public Turnstile site key may intentionally be shared only when the provider configuration requires it; private OAuth/Turnstile secrets, JWT keypair, database/object-storage credentials and all three internal tokens must never equal production values. Empty values fail before any cluster mutation. Missing core/media tokens also fail AI Spring Boot configuration binding through the documented `@Validated` configuration-properties mechanism, rather than silently enabling end-user-token fallback ([Spring Boot external configuration](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)).

Before uploading values, load both environment copies from the owner's external source and run `./scripts/verify-environment-secret-separation.py --desired`. It reports only pass/fail and duplicate key names—never values, lengths or hashes. `PROD_RELEASE_BINDING_KEY` is a separate random value of at least 32 bytes used only as the HMAC key for an approved desired-Secret snapshot; it must not equal an application token. After the first rollout, run the same tool with an admin kubeconfig and `--live`; any forbidden equality is a no-go. Do not place exports or command output in an issue, pull request or shell history.

`POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` are bootstrap-only in both environments. The issuer identity and JWT keypair are protected from ordinary replacement as well: a single-key rolling change creates mixed sign/verify generations and invalidates sessions, so it needs a separately designed multi-key/two-phase auth migration. Staging MinIO's `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_BUCKET_NAME` are bootstrap-only too. Production `GF_SECURITY_ADMIN_USER`/`GF_SECURITY_ADMIN_PASSWORD` are protected: Grafana documents the default admin password as set once on first run, so changing its environment and restarting is not a password rotation ([Grafana configuration](https://grafana.com/docs/grafana/latest/setup-grafana/configure-grafana/#admin_password), [admin reset](https://grafana.com/docs/grafana/latest/administration/cli/#reset-admin-password)). Reset it through a separately reviewed Grafana CLI/API procedure, then synchronize the live Secret and GitHub Environment from the owner's external source before resuming delivery. All three `*_INTERNAL_TOKEN` values are also protected from generic in-place rotation: the current single-token clients and servers cannot accept old and new values concurrently during a rolling update. Rotate bootstrap values only through a separately reviewed two-phase data procedure. Until dual-token acceptance exists, rotate internal tokens only in an explicitly approved coordinated maintenance window (or a separately implemented two-phase protocol), never through the ordinary deploy. Other supported application Secret changes reconcile the five backend consumers by recording the live Secret `resourceVersion` in each Pod template on every run; the frontend is intentionally excluded. If a run stops after applying the Secret but before patching or completing rollouts, rerun the same release: it reads the already-live generation, patches every consumer idempotently and verifies the annotation instead of relying on desired-vs-live drift that is now empty.

After both environment copies are loaded and verified, remove the superseded unprefixed repository secrets. Keep `GITHUB_TOKEN` automatic. Do not delete the old repository secrets until both prefixed copies are confirmed because GitHub does not expose existing secret values for migration.

## Promotion and evidence

For current behavior, the [replacement release contract](release-verification-runbook.md#current-replacement-delivery--142)
supersedes the six-image promotion description below. The two maintenance images
retain all existing provenance/SBOM/vulnerability/quality gates. Staging leaves
data services and existing Secret values unchanged, initializes only the immutable
Identity signing pair, and performs no frontend/product smoke beyond the public
Identity protocol contract.
Production validation reports the #147 hard gate and skips every `prod` Environment
job; no maintenance artifact is promoted. Old production workflow internals remain
dormant until their owning cutover task.

The hosted release smoke includes the staged Report-Only response contract and headless Chrome inspection defined in the [browser security headers runbook](./browser-security-headers.md). Any missing header, unexpected broad source, Turnstile loading failure, or browser-observed CSP violation rejects the candidate inside the same automatic rollback boundary.

`Main CI` builds each image once, records its registry digest, and renders both environment manifests from those same six digest files. The unprivileged `Staging Deploy` gate downloads both checksummed artifacts by the exact predecessor run ID and proves both embedded release identities before the staging Environment is entered; the direct deployment job repeats those checks. Staging renders its Secret and server-side dry-runs/diffs that Secret, data services, bucket Job and complete release before the first apply/delete/restart, then performs one final stale-main check. It must complete all six rollouts, the behavioral release smoke and release-state recording before it relays the unchanged checksummed production manifest to its own run. The unprivileged `Production Deploy` gate validates only that staging-approved relay and the current SHA before either production approval; non-cancelling concurrency begins only on the mutating job, so obsolete preview approvals do not block a newer safe preview. A fresh staging database has no dedicated smoke identity; after the first expected login `401`, the smoke creates the exact configured email through the normal registration endpoint with the account-bound Turnstile bypass, then retries authentication. Conflicts, wrong credentials and all non-`401` failures stop the rollout. Production's preview artifact binds the application diff, exact desired Secret and smoke inputs, exact live application/Grafana Secret identities/data/resourceVersions, and the reconciliation marker with context-separated keyed HMACs; no values or unkeyed value hashes are exposed. The mutating job repeats all bindings, then conditionally replaces Secrets with the approved `resourceVersion`, so a live A→C change fails instead of being overwritten. Successful application, observability and smoke verification are recorded last; a failed rollout or smoke leaves reconciliation/release state stale and invokes the saved complete-release rollback path. Kubernetes documents that `resourceVersion` on replacement provides optimistic concurrency and rejects stale updates ([API concepts](https://kubernetes.io/docs/reference/using-api/api-concepts/#updates-to-existing-resources)). Release state, sanitized diagnostics, automatic rollback and the mandatory controlled drill are defined in the [release verification runbook](./release-verification-runbook.md).

For current maintenance acceptance, verify both shell identities with `release_smoke.py --mode maintenance` as described in the release runbook, and inspect:

```bash
curl -fsS https://staging.mnema.app/api/actuator/health/readiness
curl -fsS https://auth.staging.mnema.app/api/actuator/health/readiness
kubectl -n mnema-staging get resourcequota,limitrange,pods,pvc,ingress
kubectl get namespace mnema-staging --show-labels
kubectl auth can-i get secrets -n prod --as=system:serviceaccount:mnema-staging:mnema-deployer
```

The final command must return `no`. Also verify `kubectl auth can-i create ingresses.networking.k8s.io -n mnema-staging --as=system:serviceaccount:mnema-staging:mnema-deployer` returns `no`. Record names/statuses only; never copy Secret data.

## Rollback boundary

Rollback has two phases. Before legacy repository secrets are revoked, a reviewed code revert is available after validating that the target workflow still consumes names that exist. After revocation, never blindly revert this PR or any production secret-contract change: the base workflow consumes deleted unprefixed names and can fail or write incomplete Secrets. Preserve the `PROD_*` environment-owned contract and use a forward fix that disables the staging caller/promotion while leaving production credential consumers intact. A historical-code rollback is allowed only after every required legacy name is re-provisioned from the owner's external source and the exact target workflow passes the same pre-mutation validation.

Remove the three staging DNS records to stop public routing. Namespace deletion also deletes the staging PVCs and data, so it is a separate destructive owner action requiring an exact-target confirmation. It does not affect production or keykomi.

Removing the host boundary is also a separate root action: first revoke `STAGING_KUBECONFIG_B64`, wait for the issued token to expire or revoke the ServiceAccount, and remove public staging DNS/routes. Only then disable `mnema-staging-host-boundary.timer` and `.service`, remove their `INPUT`/`FORWARD` hooks and `MNEMA_POD_HOST_BOUNDARY_A`/`_B` chains for both iptables families, and delete the installed unit/script/environment file. Never remove the host rule while a staging credential or public workload remains active.
