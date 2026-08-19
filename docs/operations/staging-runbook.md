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

With the existing root/admin k3s context, preview and apply the namespace first, then its policy/access boundary. `kubectl diff` returns `1` when a diff exists; inspect that output before each apply:

```bash
kubectl diff -f k8s/staging/namespace.yaml || test $? -eq 1
kubectl apply -f k8s/staging/namespace.yaml
kubectl diff -f k8s/staging/bootstrap.yaml || test $? -eq 1
kubectl apply -f k8s/staging/bootstrap.yaml
kubectl diff -f k8s/staging/routes.yaml || test $? -eq 1
kubectl apply -f k8s/staging/routes.yaml
```

This creates `mnema-staging`, ResourceQuota, LimitRange, three fixed staging routes and a Role-bound `mnema-deployer` ServiceAccount. It creates no workload, PVC or application secret and grants no cluster-scoped permission. The CI Role deliberately cannot create or change Ingress objects: Kubernetes RBAC cannot restrict a top-level `create` request by resource name or constrain its host fields, so delegating it on the shared Traefik controller would allow a staging credential to claim a production hostname. Route changes remain an explicit owner bootstrap action. Kubernetes defines a `Role` as namespace-scoped and documents both the `create` limitation and LimitRange default injection before quota admission ([RBAC](https://kubernetes.io/docs/reference/access-authn-authz/rbac/), [LimitRange](https://kubernetes.io/docs/concepts/policy/limit-range/)). The values are sized for the accepted shared-host staging envelope: 3 requested CPU, 8 GiB requested memory, 14 GiB memory limit and 20 GiB requested storage.

Generate a bounded kubeconfig directly outside the repository; the script verifies that it can mutate staging workloads but cannot read production secrets, create namespaces or change shared-ingress routing:

```bash
OUTPUT=/root/mnema-staging.kubeconfig \
KUBE_API_SERVER=https://<main-cluster-api-host>:6443 \
TOKEN_DURATION=720h \
./scripts/create-staging-kubeconfig.sh
```

`KUBE_API_SERVER` must be the existing externally reachable TLS endpoint of the main cluster, with a certificate valid for that host; loopback endpoints from the default root k3s config are rejected. Do not expose a new endpoint or use keykomi for this bootstrap. The generated credential's own `kubectl auth can-i` checks must succeed through that exact endpoint before the file is accepted.

Base64-encode that file without printing it and store the result as the `STAGING_KUBECONFIG_B64` secret in the GitHub `staging` environment. Record the script's `token_expires_at` value and rotate to a new file/secret before that time; the script deliberately refuses to overwrite an existing credential. The API server may issue a shorter duration than requested. Kubernetes recommends bounded TokenRequest credentials over manually created non-expiring ServiceAccount token Secrets ([ServiceAccount tokens](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/)).

## Environment-owned secret names

Values are never copied to issues, pull requests, logs or repository files. Production and staging deliberately use different prefixes, and reusable deployment callers do not use `secrets: inherit`, so repository secrets are not passed into either deployment workflow. GitHub loads the environment-owned secrets on the called job that declares `environment` ([reusable workflow secret semantics](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows#using-inputs-and-secrets-in-a-reusable-workflow)).

| GitHub environment | Required names |
|---|---|
| `staging` | `STAGING_KUBECONFIG_B64`, `STAGING_AUTH_ISSUER`, `STAGING_AUTH_ISSUER_URI`, `STAGING_AUTH_JWT_PUBLIC_KEY`, `STAGING_AUTH_JWT_PRIVATE_KEY`, `STAGING_TURNSTILE_SITE_KEY`, `STAGING_TURNSTILE_SECRET_KEY`, `STAGING_GOOGLE_CLIENT_ID`, `STAGING_GOOGLE_CLIENT_SECRET`, `STAGING_GH_CLIENT_ID`, `STAGING_GH_CLIENT_SECRET`, `STAGING_YANDEX_CLIENT_ID`, `STAGING_YANDEX_CLIENT_SECRET`, `STAGING_POSTGRES_DB`, `STAGING_POSTGRES_USER`, `STAGING_POSTGRES_PASSWORD`, `STAGING_AWS_REGION`, `STAGING_AWS_ACCESS_KEY_ID`, `STAGING_AWS_SECRET_ACCESS_KEY`, `STAGING_AWS_BUCKET_NAME`, `STAGING_MEDIA_INTERNAL_TOKEN`, `STAGING_CORE_INTERNAL_TOKEN`, `STAGING_USER_INTERNAL_TOKEN` |
| `prod` | `PROD_KUBECONFIG_B64`, `PROD_AUTH_ISSUER`, `PROD_AUTH_ISSUER_URI`, `PROD_AUTH_JWT_PUBLIC_KEY`, `PROD_AUTH_JWT_PRIVATE_KEY`, `PROD_TURNSTILE_SITE_KEY`, `PROD_TURNSTILE_SECRET_KEY`, `PROD_GOOGLE_CLIENT_ID`, `PROD_GOOGLE_CLIENT_SECRET`, `PROD_GH_CLIENT_ID`, `PROD_GH_CLIENT_SECRET`, `PROD_YANDEX_CLIENT_ID`, `PROD_YANDEX_CLIENT_SECRET`, `PROD_POSTGRES_DB`, `PROD_POSTGRES_USER`, `PROD_POSTGRES_PASSWORD`, `PROD_AWS_REGION`, `PROD_AWS_ACCESS_KEY_ID`, `PROD_AWS_SECRET_ACCESS_KEY`, `PROD_AWS_BUCKET_NAME`, `PROD_MEDIA_INTERNAL_TOKEN`, `PROD_CORE_INTERNAL_TOKEN`, `PROD_USER_INTERNAL_TOKEN`, `PROD_GF_SECURITY_ADMIN_USER`, `PROD_GF_SECURITY_ADMIN_PASSWORD` |

For staging, both issuer values must be `https://auth.staging.mnema.app`; the bucket is namespace-local MinIO and should use a staging-only name. OAuth applications must allow the staging callbacks before federated login is considered verified. Empty values fail before any cluster mutation. Missing internal tokens also fail Spring Boot configuration binding through the documented `@Validated` configuration-properties mechanism, rather than silently disabling service authentication ([Spring Boot external configuration](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)).

After both environment copies are loaded and verified, remove the superseded unprefixed repository secrets. Keep `GITHUB_TOKEN` automatic. Do not delete the old repository secrets until both prefixed copies are confirmed because GitHub does not expose existing secret values for migration.

## Promotion and evidence

`Main CI` builds each image once, records its registry digest, and renders both environment manifests from those same six digest files. The non-cancellable staging deployment runs first. Production reaches its separate approval only after staging data services and all six application rollouts succeed. Post-deploy behavioral smoke and rollback evidence are owned by #91.

Before accepting this task, verify:

```bash
curl -fsS https://staging.mnema.app/
curl -fsS https://auth.staging.mnema.app/actuator/health/readiness
kubectl -n mnema-staging get resourcequota,limitrange,pods,pvc,ingress
kubectl auth can-i get secrets -n prod --as=system:serviceaccount:mnema-staging:mnema-deployer
```

The final command must return `no`. Also verify `kubectl auth can-i create ingresses.networking.k8s.io -n mnema-staging --as=system:serviceaccount:mnema-staging:mnema-deployer` returns `no`. Record names/statuses only; never copy Secret data.

## Rollback boundary

Disable staging deployment by reverting the delivery PR and remove the three DNS records. Namespace deletion also deletes the staging PVCs and data, so it is a separate destructive owner action requiring an exact-target confirmation. It does not affect production or keykomi.
