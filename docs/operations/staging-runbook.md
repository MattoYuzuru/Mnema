---
artifact:
  id: staging-runbook
  type: runbook
  title: "Mnema staging bootstrap and secret contract"
  status: current
  created_at: "2026-08-18"
  updated_at: "2026-08-19"
  owners: ["project-owner"]
---

# Mnema staging bootstrap and secret contract

Staging is an isolated namespace on the shared main k3s host. It uses the same digest-pinned Mnema application images as production, but owns separate PostgreSQL, Redis, MinIO, credentials and TLS hosts. It has a default recovery objective of RPO 24 hours / RTO 4 hours. Sharing the host remains a failure-domain limitation, not a claim of production-grade availability.

The PostgreSQL 18 PVC is mounted at `/var/lib/postgresql`, matching the official image's version-specific `PGDATA` and volume contract ([PostgreSQL official image](https://hub.docker.com/_/postgres#pgdata)). Do not restore the pre-18 `/var/lib/postgresql/data` mount convention.

## Owner prerequisites

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

The CI Role also deliberately cannot create or change Ingress objects: Kubernetes RBAC cannot restrict a top-level `create` request by resource name or constrain its host fields, so delegating it on the shared Traefik controller would allow a staging credential to claim a production hostname. Route changes remain an explicit owner bootstrap action. A fail-closed admission policy additionally restricts mutable Services to ClusterIP/headless forms without `externalIPs` or `nodePort`, so K3s ServiceLB cannot turn staging input into host-port listeners. Workloads cannot use the `mnema-deployer` identity and must explicitly disable token automounting. Secret access is limited to update/get/patch of the precreated `mnema-secrets`; admission keeps it Opaque, rejects ServiceAccount-token annotations and rejects undocumented data keys. These controls prevent both non-expiring token Secrets and workload-mounted rotating deployer tokens ([Service](https://kubernetes.io/docs/concepts/services-networking/service/), [K3s ServiceLB](https://docs.k3s.io/networking/networking-services), [ServiceAccount tokens](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/)).

Default-deny ingress/egress permits same-namespace traffic, kube-system Traefik ingress, kube-dns and public IPv4/IPv6 egress while excluding private, loopback, link-local, documentation and multicast ranges. This blocks direct staging access to production Pod/Service networks and `169.254.169.254`. Kubernetes deliberately permits Pod traffic to its resident node regardless of NetworkPolicy, so NetworkPolicy alone is not the host boundary ([NetworkPolicy behavior](https://kubernetes.io/docs/concepts/services-networking/network-policies/#network-traffic-filtering)).

On the single shared k3s node, install the owner-managed persistent host rule before generating any credential. It blocks every cluster Pod CIDR from node listeners except the shared public web ports, Kubernetes-Service traffic identified by its original ClusterIP destination, the current kube-system metrics-server Pod on kubelet `10250`, and the exact current production Prometheus Pod on node-exporter `9100` plus kubelet/cAdvisor `10250`; it always blocks the directly advertised Kubernetes API endpoint. Source addresses are exact Pod IPs, so staging receives none of these telemetry exceptions. The service builds the alternate A/B chain completely before swapping hooks, then its one-minute timer rediscovers trusted Pod addresses after k3s starts.

```bash
sudo KUBE_API_SERVER=https://<main-cluster-api-host>:6443 \
  ./scripts/install-staging-host-firewall.sh
```

Stop if the host uses another authoritative firewall manager until the equivalent rules and persistence are reviewed; do not run two managers that overwrite each other. Each systemd reconciliation and the kubeconfig generator require Prometheus `node-exporter`, `kubelet` and `cadvisor` targets to remain `up`. The generator also checks live iptables rules, enabled/active systemd units, exact API origin, every resident node InternalIP/ExternalIP on `2379`, `2380`, `6443`, `10250`, production Redis, the Kubernetes Service and metadata, plus same-namespace, DNS and public HTTPS egress. Any unhealthy production telemetry target, reachable sensitive endpoint or missing rule is a no-go. Exercise both A/B refreshes, interrupt one population attempt, wait for a timer refresh, reboot the node once, and rerun the probes before uploading the credential.

Kubernetes defines a `Role` as namespace-scoped and documents both the `create` limitation and LimitRange default injection before quota admission ([RBAC](https://kubernetes.io/docs/reference/access-authn-authz/rbac/), [LimitRange](https://kubernetes.io/docs/concepts/policy/limit-range/)). The quota is sized for the accepted ~113 GiB free-disk envelope: 3 requested CPU, 8 GiB requested memory, 14 GiB memory limit, 20 GiB PVC storage, 12 GiB requested / 40 GiB limited ephemeral storage, plus explicit counts for each writable API-object class. Before credential issuance, live `max(status.used.count/secrets, actual Secret count)` must leave at least five slots: three simultaneous cert-manager next-private-key Secrets and two bounded recovery slots. A hard limit of 12 with four used passes; 10 or 12 used fails. Each container receives 256 MiB requested / 2 GiB limited ephemeral storage by default and cannot exceed 4 GiB. Verify the live kubelet reports `ephemeral-storage` capacity and enforces filesystem accounting; otherwise add a host-level disk/eviction guard before provisioning staging.

The owner-applied `letsencrypt-prod` solver has a DNS-zone-specific `staging.mnema.app` rule using `ClusterIP`; cert-manager otherwise defaults HTTP-01 solver Services to `NodePort`, which the staging admission boundary correctly rejects ([cert-manager HTTP-01](https://cert-manager.io/docs/configuration/acme/http01/)). Current cert-manager solver Pods explicitly set `automountServiceAccountToken: false`, so they pass the same strict Pod identity policy without an exemption; an older incompatible controller fails closed. Before issuing the deployer credential, the generator requires the ClusterIssuer and all three Certificates to be `Ready`, verifies the TLS Secrets and scheduled renewal metadata, and checks Secret quota headroom. During live acceptance, run one owner-controlled `cmctl renew` cycle for the three certificate names and repeat the check; renewal failure is a no-go.

Generate a bounded kubeconfig directly outside the repository; the script verifies that it can mutate staging workloads but cannot read production secrets, create namespaces or change shared-ingress routing:

```bash
OUTPUT=/root/mnema-staging.kubeconfig \
KUBE_API_SERVER=https://<main-cluster-api-host>:6443 \
TOKEN_DURATION=720h \
./scripts/create-staging-kubeconfig.sh
```

`KUBE_API_SERVER` must be the existing externally reachable TLS origin of the main cluster, with a certificate valid for that host. The generator uses Python's standard URL/IP parsers to reject localhost, the full IPv4/IPv6 loopback classes, credentials, path/query/fragment ambiguity and malformed ports; the default root k3s loopback endpoint is never accepted. Do not expose a new endpoint or use keykomi for this bootstrap. The command pins Pod Security labels as its only persistent cluster mutation, then the generated credential's own authorization and forbidden-workload checks must succeed through that exact endpoint before the file is accepted.

Base64-encode that file without printing it and store the result as the `STAGING_KUBECONFIG_B64` secret in the GitHub `staging` environment. Record the script's `token_expires_at` value and rotate to a new file/secret before that time; the script deliberately refuses to overwrite an existing credential. The API server may issue a shorter duration than requested. Kubernetes recommends bounded TokenRequest credentials over manually created non-expiring ServiceAccount token Secrets ([ServiceAccount tokens](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/)). GitHub `staging` and `prod` environments must both keep a custom deployment-branch policy with the single exact branch `main`; `prod` must additionally keep the required reviewer. Read back the environment and branch-policy APIs before uploading any credential. This exact-main policy was installed and read back on 2026-08-19; changing it is a security-sensitive owner action ([GitHub deployment branch policies](https://docs.github.com/en/rest/deployments/branch-policies)).

## Environment-owned secret names

Values are never copied to issues, pull requests, logs or repository files. Production and staging deliberately use different prefixes, and reusable deployment callers do not use `secrets: inherit`, so repository secrets are not passed into either deployment workflow. GitHub loads the environment-owned secrets on the called job that declares `environment` ([reusable workflow secret semantics](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows#using-inputs-and-secrets-in-a-reusable-workflow)).

| GitHub environment | Required names |
|---|---|
| `staging` | `STAGING_KUBECONFIG_B64`, `STAGING_AUTH_ISSUER`, `STAGING_AUTH_ISSUER_URI`, `STAGING_AUTH_JWT_PUBLIC_KEY`, `STAGING_AUTH_JWT_PRIVATE_KEY`, `STAGING_TURNSTILE_SITE_KEY`, `STAGING_TURNSTILE_SECRET_KEY`, `STAGING_GOOGLE_CLIENT_ID`, `STAGING_GOOGLE_CLIENT_SECRET`, `STAGING_GH_CLIENT_ID`, `STAGING_GH_CLIENT_SECRET`, `STAGING_YANDEX_CLIENT_ID`, `STAGING_YANDEX_CLIENT_SECRET`, `STAGING_POSTGRES_DB`, `STAGING_POSTGRES_USER`, `STAGING_POSTGRES_PASSWORD`, `STAGING_AWS_REGION`, `STAGING_AWS_ACCESS_KEY_ID`, `STAGING_AWS_SECRET_ACCESS_KEY`, `STAGING_AWS_BUCKET_NAME`, `STAGING_MEDIA_INTERNAL_TOKEN`, `STAGING_CORE_INTERNAL_TOKEN`, `STAGING_USER_INTERNAL_TOKEN` |
| `prod` | `PROD_KUBECONFIG_B64`, `PROD_RELEASE_BINDING_KEY`, `PROD_AUTH_ISSUER`, `PROD_AUTH_ISSUER_URI`, `PROD_AUTH_JWT_PUBLIC_KEY`, `PROD_AUTH_JWT_PRIVATE_KEY`, `PROD_TURNSTILE_SITE_KEY`, `PROD_TURNSTILE_SECRET_KEY`, `PROD_GOOGLE_CLIENT_ID`, `PROD_GOOGLE_CLIENT_SECRET`, `PROD_GH_CLIENT_ID`, `PROD_GH_CLIENT_SECRET`, `PROD_YANDEX_CLIENT_ID`, `PROD_YANDEX_CLIENT_SECRET`, `PROD_POSTGRES_DB`, `PROD_POSTGRES_USER`, `PROD_POSTGRES_PASSWORD`, `PROD_AWS_REGION`, `PROD_AWS_ACCESS_KEY_ID`, `PROD_AWS_SECRET_ACCESS_KEY`, `PROD_AWS_BUCKET_NAME`, `PROD_MEDIA_INTERNAL_TOKEN`, `PROD_CORE_INTERNAL_TOKEN`, `PROD_USER_INTERNAL_TOKEN`, `PROD_GF_SECURITY_ADMIN_USER`, `PROD_GF_SECURITY_ADMIN_PASSWORD` |

For staging, both issuer values must be `https://auth.staging.mnema.app`; the bucket is namespace-local MinIO and must use a staging-only name. OAuth applications must allow the staging callbacks before federated login is considered verified. Public OAuth client IDs and the public Turnstile site key may intentionally be shared only when the provider configuration requires it; private OAuth/Turnstile secrets, JWT keypair, database/object-storage credentials and all three internal tokens must never equal production values. Empty values fail before any cluster mutation. Missing core/media tokens also fail AI Spring Boot configuration binding through the documented `@Validated` configuration-properties mechanism, rather than silently enabling end-user-token fallback ([Spring Boot external configuration](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)).

Before uploading values, load both environment copies from the owner's external source and run `./scripts/verify-environment-secret-separation.py --desired`. It reports only pass/fail and duplicate key names—never values, lengths or hashes. `PROD_RELEASE_BINDING_KEY` is a separate random value of at least 32 bytes used only as the HMAC key for an approved desired-Secret snapshot; it must not equal an application token. After the first rollout, run the same tool with an admin kubeconfig and `--live`; any forbidden equality is a no-go. Do not place exports or command output in an issue, pull request or shell history.

`POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` are bootstrap-only in both environments. The issuer identity and JWT keypair are protected from ordinary replacement as well: a single-key rolling change creates mixed sign/verify generations and invalidates sessions, so it needs a separately designed multi-key/two-phase auth migration. Staging MinIO's `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_BUCKET_NAME` are bootstrap-only too. Production `GF_SECURITY_ADMIN_USER`/`GF_SECURITY_ADMIN_PASSWORD` are protected: Grafana documents the default admin password as set once on first run, so changing its environment and restarting is not a password rotation ([Grafana configuration](https://grafana.com/docs/grafana/latest/setup-grafana/configure-grafana/#admin_password), [admin reset](https://grafana.com/docs/grafana/latest/administration/cli/#reset-admin-password)). Reset it through a separately reviewed Grafana CLI/API procedure, then synchronize the live Secret and GitHub Environment from the owner's external source before resuming delivery. All three `*_INTERNAL_TOKEN` values are also protected from generic in-place rotation: the current single-token clients and servers cannot accept old and new values concurrently during a rolling update. Rotate bootstrap values only through a separately reviewed two-phase data procedure. Until dual-token acceptance exists, rotate internal tokens only in an explicitly approved coordinated maintenance window (or a separately implemented two-phase protocol), never through the ordinary deploy. Other supported application Secret changes reconcile the five backend consumers; the frontend is intentionally excluded.

After both environment copies are loaded and verified, remove the superseded unprefixed repository secrets. Keep `GITHUB_TOKEN` automatic. Do not delete the old repository secrets until both prefixed copies are confirmed because GitHub does not expose existing secret values for migration.

## Promotion and evidence

`Main CI` builds each image once, records its registry digest, and renders both environment manifests from those same six digest files. Staging renders its Secret and server-side dry-runs/diffs that Secret, data services, bucket Job and complete release before the first apply/delete/restart, then performs one final stale-main check. Production reaches its separate approval only after staging succeeds. Its artifact binds the application diff, exact desired Secret values, exact live application/Grafana Secret identities/data/resourceVersions, and the reconciliation marker with context-separated keyed HMACs; no values or unkeyed value hashes are exposed. The mutating job repeats all bindings, then conditionally replaces Secrets with the approved `resourceVersion`, so a live A→C change fails instead of being overwritten. Successful application and Grafana rollouts are recorded last in `mnema-secret-reconciliation`; a failed rollout leaves the marker stale, so an identical corrected rerun performs and verifies reconciliation instead of skipping as a no-op. Kubernetes documents that `resourceVersion` on replacement provides optimistic concurrency and rejects stale updates ([API concepts](https://kubernetes.io/docs/reference/using-api/api-concepts/#updates-to-existing-resources)). Post-deploy behavioral smoke and rollback evidence are owned by #91.

Before accepting this task, verify:

```bash
curl -fsS https://staging.mnema.app/
curl -fsS https://auth.staging.mnema.app/actuator/health/readiness
kubectl -n mnema-staging get resourcequota,limitrange,pods,pvc,ingress
kubectl get namespace mnema-staging --show-labels
kubectl auth can-i get secrets -n prod --as=system:serviceaccount:mnema-staging:mnema-deployer
```

The final command must return `no`. Also verify `kubectl auth can-i create ingresses.networking.k8s.io -n mnema-staging --as=system:serviceaccount:mnema-staging:mnema-deployer` returns `no`. Record names/statuses only; never copy Secret data.

## Rollback boundary

Rollback has two phases. Before legacy repository secrets are revoked, a reviewed code revert is available after validating that the target workflow still consumes names that exist. After revocation, never blindly revert this PR or any production secret-contract change: the base workflow consumes deleted unprefixed names and can fail or write incomplete Secrets. Preserve the `PROD_*` environment-owned contract and use a forward fix that disables the staging caller/promotion while leaving production credential consumers intact. A historical-code rollback is allowed only after every required legacy name is re-provisioned from the owner's external source and the exact target workflow passes the same pre-mutation validation.

Remove the three staging DNS records to stop public routing. Namespace deletion also deletes the staging PVCs and data, so it is a separate destructive owner action requiring an exact-target confirmation. It does not affect production or keykomi.

Removing the host boundary is also a separate root action: first revoke `STAGING_KUBECONFIG_B64`, wait for the issued token to expire or revoke the ServiceAccount, and remove public staging DNS/routes. Only then disable `mnema-staging-host-boundary.timer` and `.service`, remove their `INPUT`/`FORWARD` hooks and `MNEMA_POD_HOST_BOUNDARY_A`/`_B` chains for both iptables families, and delete the installed unit/script/environment file. Never remove the host rule while a staging credential or public workload remains active.
