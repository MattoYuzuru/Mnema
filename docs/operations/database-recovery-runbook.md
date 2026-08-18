---
artifact:
  id: database-recovery-runbook
  type: operations-runbook
  title: "Production PostgreSQL backup and isolated recovery drill"
  status: current
  created_at: "2026-08-19"
  updated_at: "2026-08-19"
  owners: ["project-owner"]
  implementation_issue: 93
---

# Production PostgreSQL backup and isolated recovery drill

This runbook defines the repository contract for production PostgreSQL backups, pre-migration evidence and a restore rehearsal. It does not authorize a production database restore. A real incident restore needs its own exact target, write freeze, data-loss boundary and approval.

## State and boundary

The production source is PostgreSQL 16 in namespace `prod`, StatefulSet `postgres`, backed by the single local-path claim `data-postgres-0`. A read-only host inventory on 2026-08-19 found the k3s host filesystem at 194 GiB total, 83 GiB used and 111 GiB available. That capacity observation is not backup evidence.

The implemented path is:

1. `mnema-postgres-backup` starts at minute 17 of every hour in `Etc/UTC`; overlapping schedules are forbidden.
2. A PostgreSQL 18 client exports one read-only repeatable-read snapshot, creates a custom-format logical dump and computes reconciliation rows from the same snapshot.
3. The uploader verifies the bucket's expected KMS key and enabled lifecycle policy before writing anything.
4. The lifecycle rule must target the exact backup prefix and define a positive expiration period; versioned buckets must also expire non-current versions. Immutable objects are uploaded below a UTC timestamp plus the Kubernetes Pod UID, so duplicate controller starts cannot overwrite one another. The `latest.env` pointer is written last and only after each object is read back as `aws:kms` encrypted with the expected key.
5. A drill downloads one complete backup into a fresh PostgreSQL 18 instance in the fixed namespace `mnema-restore-drill`, restores it, and compares every application table's row count and two-part aggregate checksum plus sequence state.
6. The drill records measured RPO and RTO from timestamps. The namespace and its PVC are then deleted. Production is never a restore target.

The source dump contains production data and is sensitive. Dump content, reconciliation CSVs and credentials must not be copied into GitHub issues, pull requests, Actions artifacts or chat. PostgreSQL dump/restore and reconciliation stderr stays only on the transient Pod volume; Actions receives a generic failure code. The only publishable evidence is the allowlisted JSON emitted by `validate_report.py`: UTC backup ID, timestamps, counts, hashes, sizes and version numbers.

This is an hourly logical backup, not continuous WAL archiving or point-in-time recovery. The maximum schedule interval is one hour, but an actual RPO is not claimed until a successful drill measures backup age. Cross-region recovery and object-storage recovery remain outside this issue.

## Storage and ownership contract

The GitHub `prod` Environment owns these names:

| Name | Purpose |
|---|---|
| `PROD_BACKUP_S3_ENDPOINT` | HTTPS S3 endpoint; Yandex Object Storage uses `https://storage.yandexcloud.net` |
| `PROD_BACKUP_S3_REGION` | Object Storage region, normally `ru-central1` |
| `PROD_BACKUP_S3_ACCESS_KEY_ID` | Dedicated bucket-scoped recovery service account key ID |
| `PROD_BACKUP_S3_SECRET_ACCESS_KEY` | Matching secret key |
| `PROD_BACKUP_S3_BUCKET` | Dedicated off-host backup bucket |
| `PROD_BACKUP_S3_PREFIX` | Reserved Mnema backup prefix without leading/trailing slash |
| `PROD_BACKUP_RETENTION_POLICY_ID` | Exact enabled bucket lifecycle rule ID |
| `PROD_BACKUP_KMS_KEY_ID` | Exact KMS key identifier returned for bucket and objects |

`project-owner` owns the service account, lifecycle configuration, KMS key availability/rotation and access review. The service account must be limited to the selected bucket/prefix and the object/config/KMS operations exercised by `upload.sh` and `download.sh`; it must not reuse the application's media credentials. Static credentials in a protected GitHub Environment are the current provider-compatible implementation. Moving them to Yandex Lockbox or short-lived workload identity is a later hardening step.

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

1. Confirm there is no active database migration and production PostgreSQL is healthy.
2. Open **Actions → Database Recovery → Run workflow** on `main`.
3. Select `pre-migration-backup`, leave `backup_id` unchanged and type `BACKUP_PRODUCTION_DATABASE`.
4. Wait for the job and evidence artifact to succeed.
5. Validate the JSON report and record its exact backup ID, snapshot time, account count, reconciliation hash and Actions run URL in the migration change record.
6. Re-check the selected backup with an isolated restore drill before the destructive point of no return.

This operation reads production PostgreSQL and writes a new immutable object set. It does not write the database and deliberately does not replace `latest.env`, so a manual run cannot race the scheduled pointer; use the exact backup ID from its report for the drill. If another backup is active, the workflow stops before creating the manual Job.

## Isolated restore drill

The drill is an authorized L3 mutation of only the temporary `mnema-restore-drill` namespace on the main k3s cluster. It reads encrypted backup objects. Default-deny ingress and egress isolate the namespace; the restore Job may reach cluster DNS, HTTPS for the S3 endpoint and only the drill PostgreSQL service on port 5432. It never writes namespace `prod`.

1. Ensure `mnema-restore-drill` does not exist. If it exists, inspect ownership and remove it deliberately; the workflow refuses to reuse or overwrite it.
2. Open **Actions → Database Recovery → Run workflow** on `main`.
3. Select `restore-drill`.
4. Use the exact pre-migration backup ID, or `latest` for a routine drill.
5. Type `RESTORE_IN_ISOLATED_NAMESPACE`.
6. Observe PostgreSQL readiness, restore completion, reconciliation and artifact upload.
7. Confirm the workflow deleted only the namespace labelled with its own `recovery-run-id`.
8. Record the run date, backup ID, measured RPO/RTO and run URL in the evidence table below.

Stop conditions are: pre-existing restore namespace, non-main or stale workflow revision, missing protected secret, unexpected KMS key, invalid pointer/checksum, non-empty target database, restore error, checksum/account mismatch, or namespace ownership mismatch. A failed drill is evidence that the backup is not proven recoverable.

If automatic cleanup fails, first confirm both labels:

```bash
kubectl get namespace mnema-restore-drill \
  -o jsonpath='{.metadata.labels.mnema\.app/purpose}{" "}{.metadata.labels.mnema\.app/recovery-run-id}{"\n"}'
```

Delete only after the purpose is `restore-drill` and the run ID matches the failed workflow. Never generalize the cleanup command to another namespace.

## Capacity and alerts

Prometheus evaluates these repository rules:

- warning when `data-postgres-0` remains below 20% free for 30 minutes;
- critical when it remains below 10% free for 10 minutes;
- warning when PVC capacity metrics disappear for 30 minutes;
- critical when no scheduled backup success exists for two hours or the newest success is older than two hours.

The rules are visible in Prometheus. Routing to an external pager/contact point is not currently configured in this repository, so the owner must inspect firing alerts during operations until a notification destination is approved. Do not claim paging delivery from the rule definitions alone.

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
