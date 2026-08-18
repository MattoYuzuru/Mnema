---
artifact:
  id: database-recovery-runbook
  type: operations-runbook
  title: "Production PostgreSQL backup and isolated recovery drill"
  status: current
  created_at: "2026-08-19"
  updated_at: "2026-08-23"
  owners: ["project-owner"]
  implementation_issue: 93
---

# Production PostgreSQL backup and isolated recovery drill

This runbook defines the repository contract for production PostgreSQL backups, pre-migration evidence and a restore rehearsal. It does not authorize a production database restore. A real incident restore needs its own exact target, write freeze, data-loss boundary and approval.

## State and boundary

The production source is PostgreSQL 16 in namespace `prod`, StatefulSet `postgres`, backed by the single local-path claim `data-postgres-0`. A read-only host inventory on 2026-08-19 found the k3s host filesystem at 194 GiB total, 83 GiB used and 111 GiB available. That capacity observation is not backup evidence.

The implemented path is:

1. `mnema-postgres-backup` starts at minute 17 of every hour in `Etc/UTC`; the CronJob forbids overlap and the backup process also holds a PostgreSQL advisory lock shared by scheduled and manually cloned Jobs.
2. A PostgreSQL 18 client exports one read-only repeatable-read snapshot, creates a custom-format logical dump and computes reconciliation rows from the same snapshot.
3. The uploader verifies the bucket's expected KMS key and enabled lifecycle policy before writing anything.
4. The lifecycle rule must target the exact backup prefix and define a positive expiration period; versioned buckets must also expire non-current versions. Immutable objects are uploaded below a UTC timestamp plus the Kubernetes Pod UID, so duplicate controller starts cannot overwrite one another. The `latest.env` pointer is written last and only after each object is read back as `aws:kms` encrypted with the expected key.
5. A drill downloads one complete backup into a fresh PostgreSQL 18 instance in the fixed namespace `mnema-restore-drill`, restores it, and compares every application table's row count and two-part aggregate checksum plus sequence state.
6. The drill records measured RPO and RTO from timestamps. Its StatefulSet, Job, Services, generated ConfigMaps, Secrets and PVC are then deleted, while the owner-created namespace, quota, default-deny policies and scoped ServiceAccount remain. Production is never a restore target.

The source dump contains production data and is sensitive. Dump content, reconciliation CSVs and credentials must not be copied into GitHub issues, pull requests, Actions artifacts or chat. PostgreSQL dump/restore and reconciliation stderr stays only on the transient Pod volume; Actions receives a generic failure code. The only publishable evidence is the allowlisted JSON emitted by `validate_report.py`: UTC backup ID, timestamps, counts, hashes, sizes and version numbers.

This is an hourly logical backup, not continuous WAL archiving or point-in-time recovery. The maximum schedule interval is one hour, but an actual RPO is not claimed until a successful drill measures backup age. Cross-region recovery and object-storage recovery remain outside this issue.

## Storage and ownership contract

The GitHub `prod` Environment owns these names:

| Name | Purpose |
|---|---|
| `PROD_RECOVERY_KUBECONFIG_B64` | Bounded `mnema-recovery` ServiceAccount credential for the fixed restore namespace; it cannot create/delete namespaces or read Secrets |
| `PROD_BACKUP_S3_ENDPOINT` | HTTPS S3 endpoint; Yandex Object Storage uses `https://storage.yandexcloud.net` |
| `PROD_BACKUP_S3_REGION` | Object Storage region, normally `ru-central1` |
| `PROD_BACKUP_S3_ACCESS_KEY_ID` | Dedicated bucket-scoped recovery service account key ID |
| `PROD_BACKUP_S3_SECRET_ACCESS_KEY` | Matching secret key |
| `PROD_BACKUP_S3_BUCKET` | Dedicated off-host backup bucket |
| `PROD_BACKUP_S3_PREFIX` | Reserved Mnema backup prefix without leading/trailing slash |
| `PROD_BACKUP_RETENTION_POLICY_ID` | Exact enabled bucket lifecycle rule ID |
| `PROD_BACKUP_KMS_KEY_ID` | Exact KMS key identifier returned for bucket and objects |

`project-owner` owns both scoped identities, lifecycle configuration, KMS key availability/rotation and access review. The Object Storage service account must be limited to the selected bucket/prefix and the object/config/KMS operations exercised by `upload.sh` and `download.sh`; it must not reuse the application's media credentials. The Kubernetes credential is issued only after `restore-boundary.yaml` exists and `create-recovery-kubeconfig.sh` proves the negative RBAC matrix. Static credentials in a protected GitHub Environment are the current provider-compatible implementation. Moving them to Yandex Lockbox or short-lived workload identity is a later hardening step.

Repository code and Environment secret names are not applied evidence. As of the 2026-08-23 audit, the protected `prod` Environment had no recovery values, and no dedicated KMS-encrypted backup bucket or scheduled backup had been verified. Keep Issue #93 open until the first scheduled upload and isolated restore drill are recorded below.

The uploader deliberately does not hard-code a retention duration. It proves that the exact configured lifecycle policy is enabled, targets `<prefix>/postgres/`, defines positive expiration and incomplete-multipart cleanup periods and, when bucket versioning is enabled or suspended, also expires non-current versions. The effective object duration is included in safe backup evidence. The duration remains governed by O-09 in [owner decisions](../decisions/owner-decisions-2026-08.md): the owner's preference of up to six months is not a lawful default for every data category. Before the destructive v2 reset, O-10 must also decide whether any full legacy snapshot is permitted and its forced destruction date. Disabling the lifecycle rule or changing the KMS key makes new backup jobs fail closed.

The bucket must use default `aws:kms` encryption with `PROD_BACKUP_KMS_KEY_ID`. Deleting or disabling that key makes encrypted backups unrecoverable; key lifecycle therefore needs the same recovery ownership as the bucket.

## Object set and consistency

Each successful backup writes:

```text
<prefix>/postgres/<YYYYMMDDTHHMMSSZ-pod-uid>/database.dump
<prefix>/postgres/<YYYYMMDDTHHMMSSZ-pod-uid>/reconciliation.csv
<prefix>/postgres/<YYYYMMDDTHHMMSSZ-pod-uid>/capacity.csv
<prefix>/postgres/<YYYYMMDDTHHMMSSZ-pod-uid>/checksums.sha256
<prefix>/postgres/<YYYYMMDDTHHMMSSZ-pod-uid>/metadata.env
<prefix>/postgres/latest.env
```

`database.dump` and reconciliation observe one exported PostgreSQL snapshot. Capacity is sampled during the same backup window, but PostgreSQL relation-size functions report physical storage and are not MVCC snapshot values. The pointer and metadata have a strict, non-executable key allowlist and are rejected on duplicate, missing or unexpected fields. Checksums and byte size are verified after download. `capacity.csv` records table, index and total relation bytes for trend inspection without row content.

The logical dump covers the full database. Reconciliation covers the hosted application schemas `auth`, `app_user`, `app_core`, `app_media` and `app_import`; hosted AI is intentionally excluded from this cluster path. Account count is the `auth.users` row count.

## Owner bootstrap

This is a separate owner-controlled platform change, not part of the application promotion workflow. That separation prevents backup credential rotation or observability drift from bypassing the production application's preview, approval, resourceVersion and rollback boundary.

Before any apply:

1. Provision a dedicated off-host bucket, bucket/prefix-scoped service account, default `aws:kms` encryption and an enabled lifecycle rule. Decide the exact retention duration under O-09 and the legacy-data destruction boundary under O-10; do not infer six months.
2. On `yandex`, require healthy production PostgreSQL/Prometheus, the default `local-path` StorageClass and at least 60 GiB free on `/var/lib/rancher/k3s/storage`. The 20 GiB backup `emptyDir`, 20 GiB restore download `emptyDir` and 20 GiB restore PVC share the host filesystem.
3. Create `prod/mnema-backup-secrets` from the owner secret store with keys `AWS_ENDPOINT_URL`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `S3_BUCKET`, `S3_PREFIX`, `RETENTION_POLICY_ID` and `KMS_KEY_ID`. Never print, commit, pipe through an issue/PR, or fetch the Secret as YAML/JSON for evidence.
4. From the exact reviewed repository revision, preview every non-secret cluster change:

   ```bash
   sudo env KUBECONFIG=/etc/rancher/k3s/k3s.yaml \
     APPLY_CHANGES=false ./scripts/apply-backup-platform.sh
   ```

   Inspect the fixed restore namespace/RBAC/network boundary, backup scripts ConfigMap, CronJob and Prometheus diff. Stop on any unexpected resource, unhealthy dependency, wrong host, missing Secret or insufficient disk.
5. Apply that exact preview once:

   ```bash
   sudo env KUBECONFIG=/etc/rancher/k3s/k3s.yaml \
     APPLY_CHANGES=true ./scripts/apply-backup-platform.sh
   ```

6. Generate the bounded recovery credential to tmpfs only, upload it to `prod/PROD_RECOVERY_KUBECONFIG_B64`, record only its expiry, then unlink the local/remote file:

   ```bash
   sudo env KUBECONFIG=/etc/rancher/k3s/k3s.yaml \
     OUTPUT=/run/mnema-recovery.kubeconfig \
     KUBE_API_SERVER=https://<externally-reachable-api>:6443 \
     TOKEN_DURATION=720h ./scripts/create-recovery-kubeconfig.sh
   ```

7. Upload the eight Object Storage/KMS values to the protected `prod` Environment without command-line bodies. Read back names and timestamps only. GitHub cannot return secret values for comparison.

The fixed restore namespace is deliberately retained. Its ResourceQuota, restricted Pod Security labels and public-HTTPS policy excluding private, loopback, link-local and metadata ranges constrain the recovery credential even when no drill is running. The credential may create drill workloads and write transient Secrets there, but cannot read/list any Secret, mutate NetworkPolicies, create/delete namespaces or access `prod`.

## Scheduled backup verification

After the first deployment, verify without printing secret values:

```bash
kubectl -n prod get cronjob mnema-postgres-backup
kubectl -n prod get jobs -l app.kubernetes.io/name=mnema-postgres-backup
kubectl -n prod logs job/<exact-job-name> -c uploader
```

The uploader log must be exactly one schema-valid `kind: backup`, `status: uploaded` JSON document. Validate a saved copy with:

```bash
python3 scripts/backup/validate_report.py --kind backup --report <safe-report.json>
```

Stop if the job reports missing KMS/lifecycle configuration, checksum mismatch, upload failure, a zero-byte dump or no successful scheduled job within two hours. Do not mark the backup usable based only on an S3 object listing.

## Pre-migration backup

Immediately before a destructive migration or account-only cutover:

1. Confirm there is no active migration, production PostgreSQL is healthy, the host has at least 60 GiB free and no backup Job is active.
2. From an owner/root kubeconfig on `yandex`, render the exact Job locally and set the uploader's pointer flag before creation:

   ```bash
   job=mnema-postgres-backup-premigration-$(date -u +%Y%m%d%H%M%S)
   kubectl -n prod create job "$job" --from=cronjob/mnema-postgres-backup \
     --dry-run=client -o yaml | \
     kubectl -n prod set env --local -f - --containers=uploader \
       UPDATE_LATEST_POINTER=false -o yaml | \
     kubectl create -f -
   ```

3. Wait for that exact Job and save only the uploader's allowlisted JSON report. The PostgreSQL advisory lock makes overlap with a scheduled Job fail closed with `backup_error=lock_contended`; do not retry until the other backup is understood and complete.
4. Validate the report, record its exact backup ID, snapshot time, account count and reconciliation hash in the migration change record, then restore that exact ID in the isolated drill.
5. Do not cross the destructive boundary unless O-09/O-10 define whether the full logical backup may still exist and its forced expiration/deletion date.

This operation reads production PostgreSQL and writes a new immutable object set. It never writes the database and deliberately does not replace `latest.env`; the exact backup ID from its report is mandatory for the drill. It is owner-controlled instead of using the GitHub recovery credential because creating Jobs in `prod` would materially broaden that credential.

## Isolated restore drill

The drill is an authorized L3 mutation only inside the pre-created `mnema-restore-drill` namespace on the main k3s cluster. It reads encrypted backup objects. Persistent default-deny ingress/egress isolates the namespace; the restore Job may reach cluster DNS, public HTTPS with private/link-local/metadata exclusions and only the drill PostgreSQL service on port 5432. It never reads or writes namespace `prod`.

1. Confirm the fixed boundary marker is version 1 and that the namespace contains no Pods, Jobs, StatefulSets, Services, PVCs or mutable ConfigMaps. Do not delete/recreate an unexpected boundary.
2. Confirm at least 60 GiB host filesystem headroom and the protected Environment's required names.
3. Open **Actions → Database Recovery → Run workflow** on exact current `main`.
4. Use the exact pre-migration backup ID, or `latest` for a routine drill, and type `RESTORE_IN_ISOLATED_NAMESPACE`.
5. Observe PostgreSQL readiness, restore completion, reconciliation and artifact upload.
6. Confirm the workflow removed only `postgres`, `mnema-postgres-restore`, their two generated ConfigMaps, two generated Secrets and `data-postgres-0`. The boundary namespace, marker, quota, NetworkPolicies, ServiceAccount and Role remain.
7. Record the run date, backup ID, measured RPO/RTO, account/checksum result and run URL below.

Stop conditions are: missing/changed boundary marker, busy restore namespace, non-main or stale workflow revision, missing protected value, unexpected KMS key, invalid pointer/checksum, non-empty target database, restore error, checksum/account mismatch, insufficient disk or cleanup of any non-fixed resource. A failed drill is evidence that the backup is not proven recoverable.

If automatic cleanup fails, inspect exact resource names and the boundary marker. Remove only resources named in `k8s/backup/restore-drill.yaml` plus PVC `data-postgres-0` and the four generated ConfigMap/Secret names. Never delete the namespace as workflow cleanup and never generalize a command to `prod`.

## Capacity and alerts

Prometheus evaluates these repository rules:

- warning when `data-postgres-0` remains below 20% free for 30 minutes;
- critical when it remains below 10% free for 10 minutes;
- warning when PVC capacity metrics disappear for 30 minutes;
- critical when no scheduled backup success exists for two hours or the newest success is older than two hours.

The rules are visible in Prometheus. Routing to an external pager/contact point is not configured in this repository, so Issue #93 treats this as **active Prometheus rules manually queried**, not delivered paging. The owner must inspect firing alerts during operations until a notification destination is approved. Do not claim paging delivery from the rule definitions alone.

After apply, query Prometheus rule health and both source metrics. Stop acceptance if `kubelet_volume_stats_capacity_bytes` for `prod/data-postgres-0` or `kube_job_status_completion_time` for the scheduled backup is absent, or if any rule reports a load/evaluation error.

Use each backup's `capacity.csv` to compare relation growth. Increase storage or reduce growth before the critical threshold; deleting a PVC is never a capacity response.

## Disable/rollback boundary

If scheduled backups impose unacceptable production load, suspend only future schedules:

```bash
kubectl -n prod patch cronjob mnema-postgres-backup \
  --type=merge -p '{"spec":{"suspend":true}}'
```

This does not stop a running Job. Inspect it before any separate cancellation decision. Keep existing bucket objects, lifecycle configuration, KMS key and credentials available while recovery evidence is under investigation. Re-enable with `suspend: false` only after the fault is understood and one manual backup plus restore drill succeeds.

## Recovery evidence

No production backup/restore run is recorded merely because the repository implementation exists. Add a row only after the exact Actions run and JSON artifact have been checked.

| Drill date UTC | Backup ID | Source → target | Measured RPO | Measured RTO | Account/checksum result | Evidence |
|---|---|---|---:|---:|---|---|
| Pending first applied drill | — | PostgreSQL 16 → 18 | — | — | — | Issue #93 |

## Design sources

- PostgreSQL 18 [`pg_dump`](https://www.postgresql.org/docs/current/app-pgdump.html) and [`pg_restore`](https://www.postgresql.org/docs/current/app-pgrestore.html): custom archive, exported snapshot and restore behavior.
- Kubernetes [CronJob](https://kubernetes.io/docs/concepts/workloads/controllers/cron-jobs/): `Forbid`, `startingDeadlineSeconds` and stable `.spec.timeZone` semantics.
- Yandex Object Storage [AWS CLI setup](https://yandex.cloud/en/docs/storage/tools/aws-cli), [bucket encryption](https://yandex.cloud/en/docs/storage/operations/buckets/encrypt) and [object lifecycle](https://yandex.cloud/en/docs/storage/concepts/lifecycles): endpoint/region, KMS default encryption and lifecycle ownership.
