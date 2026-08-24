#!/bin/sh
set -eu

umask 007

BACKUP_DIR=${BACKUP_DIR:-/backup}
UPDATE_LATEST_POINTER=${UPDATE_LATEST_POINTER:-true}
EXPECTED_AWS_ENDPOINT_URL=${EXPECTED_AWS_ENDPOINT_URL:-https://storage.yandexcloud.net}

required() {
  name="$1"
  value="$2"
  if [ -z "$value" ]; then
    echo "upload_error=missing_${name}" >&2
    exit 1
  fi
}

metadata_value() {
  key="$1"
  file="$2"
  value=$(awk -F= -v key="$key" '$1 == key { if (found) exit 2; found = 1; print substr($0, length(key) + 2) } END { if (!found) exit 3 }' "$file") || {
    echo "upload_error=invalid_metadata_${key}" >&2
    exit 1
  }
  printf '%s' "$value"
}

validate_metadata() {
  file="$1"
  if [ ! -f "$file" ] || ! awk '
    BEGIN { expected["FORMAT_VERSION"]; expected["BACKUP_ID"]; expected["BACKUP_SNAPSHOT_EPOCH"]; expected["BACKUP_DUMP_COMPLETED_EPOCH"]; expected["SOURCE_SERVER_VERSION_NUM"]; expected["ACCOUNT_COUNT"]; expected["DUMP_BYTES"]; expected["DUMP_SHA256"]; expected["RECONCILIATION_SHA256"]; expected["CAPACITY_SHA256"] }
    {
      separator = index($0, "=")
      key = separator ? substr($0, 1, separator - 1) : ""
      value = separator ? substr($0, separator + 1) : ""
      if (!(key in expected) || seen[key]++ || value !~ /^[A-Za-z0-9._:\/-]+$/) exit 1
    }
    END {
      if (NR != 10) exit 1
      for (key in expected) if (seen[key] != 1) exit 1
    }
  ' "$file"; then
    echo 'upload_error=invalid_metadata_file' >&2
    exit 1
  fi
}

validate_checksums() {
  file="$1"
  if ! awk '
    function valid_hash(value) { return length(value) == 64 && value ~ /^[0-9a-f]+$/ }
    NF != 2 || ! valid_hash($1) { exit 1 }
    $2 == "database.dump" { dump++ }
    $2 == "reconciliation.csv" { reconciliation++ }
    $2 == "capacity.csv" { capacity++ }
    $2 != "database.dump" && $2 != "reconciliation.csv" && $2 != "capacity.csv" { exit 1 }
    END { if (NR != 3 || dump != 1 || reconciliation != 1 || capacity != 1) exit 1 }
  ' "$file"; then
    echo 'upload_error=invalid_checksum_file' >&2
    exit 1
  fi
}

require_object_encryption() {
  key="$1"
  object_encryption=$(aws s3api head-object \
    --bucket "$S3_BUCKET" \
    --key "$key" \
    --endpoint-url "$AWS_ENDPOINT_URL" \
    --query ServerSideEncryption \
    --output text)
  object_kms_key=$(aws s3api head-object \
    --bucket "$S3_BUCKET" \
    --key "$key" \
    --endpoint-url "$AWS_ENDPOINT_URL" \
    --query SSEKMSKeyId \
    --output text)
  if [ "$object_encryption" != aws:kms ] || [ "$object_kms_key" != "$KMS_KEY_ID" ]; then
    echo 'upload_error=object_not_encrypted_with_expected_kms_key' >&2
    exit 1
  fi
}

require_private_object() {
  key="$1"
  anonymous_error="$BACKUP_DIR/anonymous-head.stderr"
  if aws s3api head-object \
    --bucket "$S3_BUCKET" \
    --key "$key" \
    --endpoint-url "$AWS_ENDPOINT_URL" \
    --no-sign-request \
    > /dev/null 2> "$anonymous_error"; then
    echo 'upload_error=object_anonymously_readable' >&2
    exit 1
  fi
  if ! grep -Eq '(403|Forbidden|AccessDenied)' "$anonymous_error"; then
    echo 'upload_error=anonymous_access_check_failed' >&2
    exit 1
  fi
  rm -f "$anonymous_error"
}

fail() {
  touch "$BACKUP_DIR/UPLOAD_FAILED" 2>/dev/null || true
}
trap fail EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

required AWS_ENDPOINT_URL "${AWS_ENDPOINT_URL:-}"
required AWS_REGION "${AWS_REGION:-}"
required AWS_ACCESS_KEY_ID "${AWS_ACCESS_KEY_ID:-}"
required AWS_SECRET_ACCESS_KEY "${AWS_SECRET_ACCESS_KEY:-}"
required S3_BUCKET "${S3_BUCKET:-}"
required S3_PREFIX "${S3_PREFIX:-}"
required RETENTION_POLICY_ID "${RETENTION_POLICY_ID:-}"
required KMS_KEY_ID "${KMS_KEY_ID:-}"

case "$UPDATE_LATEST_POINTER" in
  true | false) ;;
  *) echo 'upload_error=invalid_latest_pointer_mode' >&2; exit 1 ;;
esac

if [ "$AWS_ENDPOINT_URL" != "$EXPECTED_AWS_ENDPOINT_URL" ]; then
  echo 'upload_error=unexpected_object_storage_endpoint' >&2
  exit 1
fi
case "$S3_BUCKET" in
  '' | *[!a-z0-9.-]* | .* | *. | *..*) echo 'upload_error=invalid_bucket' >&2; exit 1 ;;
esac
if [ "${#S3_BUCKET}" -lt 3 ] || [ "${#S3_BUCKET}" -gt 63 ]; then
  echo 'upload_error=invalid_bucket_length' >&2
  exit 1
fi
case "$S3_PREFIX" in
  '' | /* | */ | *..* | *//* | *[!A-Za-z0-9._/-]*) echo 'upload_error=invalid_prefix' >&2; exit 1 ;;
esac
case "$RETENTION_POLICY_ID" in
  '' | *[!A-Za-z0-9._-]*) echo 'upload_error=invalid_retention_policy_id' >&2; exit 1 ;;
esac
case "$KMS_KEY_ID" in
  '' | *[!A-Za-z0-9:/._-]*) echo 'upload_error=invalid_kms_key_id' >&2; exit 1 ;;
esac

export AWS_DEFAULT_REGION="$AWS_REGION"
export AWS_EC2_METADATA_DISABLED=true
export AWS_PAGER=''

encryption=$(aws s3api get-bucket-encryption \
  --bucket "$S3_BUCKET" \
  --endpoint-url "$AWS_ENDPOINT_URL" \
  --query 'ServerSideEncryptionConfiguration.Rules[0].ApplyServerSideEncryptionByDefault.SSEAlgorithm' \
  --output text)
if [ "$encryption" != aws:kms ]; then
  echo 'upload_error=bucket_default_encryption_must_be_aws_kms' >&2
  exit 1
fi
bucket_kms_key=$(aws s3api get-bucket-encryption \
  --bucket "$S3_BUCKET" \
  --endpoint-url "$AWS_ENDPOINT_URL" \
  --query 'ServerSideEncryptionConfiguration.Rules[0].ApplyServerSideEncryptionByDefault.KMSMasterKeyID' \
  --output text)
if [ "$bucket_kms_key" != "$KMS_KEY_ID" ]; then
  echo 'upload_error=bucket_default_kms_key_mismatch' >&2
  exit 1
fi

lifecycle=$(aws s3api get-bucket-lifecycle-configuration \
  --bucket "$S3_BUCKET" \
  --endpoint-url "$AWS_ENDPOINT_URL" \
  --query "Rules[?ID=='${RETENTION_POLICY_ID}'].Status | [0]" \
  --output text)
if [ "$lifecycle" != Enabled ]; then
  echo 'upload_error=retention_policy_not_enabled' >&2
  exit 1
fi
retention_prefix=$(aws s3api get-bucket-lifecycle-configuration \
  --bucket "$S3_BUCKET" \
  --endpoint-url "$AWS_ENDPOINT_URL" \
  --query "Rules[?ID=='${RETENTION_POLICY_ID}'].Filter.Prefix | [0]" \
  --output text)
if [ "$retention_prefix" = None ]; then
  retention_prefix=$(aws s3api get-bucket-lifecycle-configuration \
    --bucket "$S3_BUCKET" \
    --endpoint-url "$AWS_ENDPOINT_URL" \
    --query "Rules[?ID=='${RETENTION_POLICY_ID}'].Prefix | [0]" \
    --output text)
fi
if [ "$retention_prefix" != "$S3_PREFIX/postgres/" ]; then
  echo 'upload_error=retention_policy_prefix_mismatch' >&2
  exit 1
fi
retention_days=$(aws s3api get-bucket-lifecycle-configuration \
  --bucket "$S3_BUCKET" \
  --endpoint-url "$AWS_ENDPOINT_URL" \
  --query "Rules[?ID=='${RETENTION_POLICY_ID}'].Expiration.Days | [0]" \
  --output text)
case "$retention_days" in
  '' | 0 | *[!0-9]*) echo 'upload_error=retention_policy_expiration_missing' >&2; exit 1 ;;
esac
multipart_days=$(aws s3api get-bucket-lifecycle-configuration \
  --bucket "$S3_BUCKET" \
  --endpoint-url "$AWS_ENDPOINT_URL" \
  --query "Rules[?ID=='${RETENTION_POLICY_ID}'].AbortIncompleteMultipartUpload.DaysAfterInitiation | [0]" \
  --output text)
case "$multipart_days" in
  '' | 0 | *[!0-9]*) echo 'upload_error=incomplete_multipart_expiration_missing' >&2; exit 1 ;;
esac

bucket_versioning=$(aws s3api get-bucket-versioning \
  --bucket "$S3_BUCKET" \
  --endpoint-url "$AWS_ENDPOINT_URL" \
  --query Status \
  --output text)
case "$bucket_versioning" in
  None | '') ;;
  Enabled | Suspended)
    noncurrent_days=$(aws s3api get-bucket-lifecycle-configuration \
      --bucket "$S3_BUCKET" \
      --endpoint-url "$AWS_ENDPOINT_URL" \
      --query "Rules[?ID=='${RETENTION_POLICY_ID}'].NoncurrentVersionExpiration.NoncurrentDays | [0]" \
      --output text)
    case "$noncurrent_days" in
      '' | 0 | *[!0-9]*) echo 'upload_error=noncurrent_version_expiration_missing' >&2; exit 1 ;;
    esac
    ;;
  *) echo 'upload_error=unexpected_bucket_versioning_state' >&2; exit 1 ;;
esac

attempt=0
while [ ! -f "$BACKUP_DIR/READY" ]; do
  if [ -f "$BACKUP_DIR/FAILED" ]; then
    echo 'upload_error=dump_failed' >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 1800 ]; then
    echo 'upload_error=dump_timeout' >&2
    exit 1
  fi
  sleep 1
done

validate_metadata "$BACKUP_DIR/metadata.env"
FORMAT_VERSION=$(metadata_value FORMAT_VERSION "$BACKUP_DIR/metadata.env")
BACKUP_ID=$(metadata_value BACKUP_ID "$BACKUP_DIR/metadata.env")
BACKUP_SNAPSHOT_EPOCH=$(metadata_value BACKUP_SNAPSHOT_EPOCH "$BACKUP_DIR/metadata.env")
BACKUP_DUMP_COMPLETED_EPOCH=$(metadata_value BACKUP_DUMP_COMPLETED_EPOCH "$BACKUP_DIR/metadata.env")
SOURCE_SERVER_VERSION_NUM=$(metadata_value SOURCE_SERVER_VERSION_NUM "$BACKUP_DIR/metadata.env")
ACCOUNT_COUNT=$(metadata_value ACCOUNT_COUNT "$BACKUP_DIR/metadata.env")
DUMP_BYTES=$(metadata_value DUMP_BYTES "$BACKUP_DIR/metadata.env")
DUMP_SHA256=$(metadata_value DUMP_SHA256 "$BACKUP_DIR/metadata.env")
RECONCILIATION_SHA256=$(metadata_value RECONCILIATION_SHA256 "$BACKUP_DIR/metadata.env")
CAPACITY_SHA256=$(metadata_value CAPACITY_SHA256 "$BACKUP_DIR/metadata.env")

if [ "$FORMAT_VERSION" != 1 ] || ! printf '%s\n' "$BACKUP_ID" | grep -Eq '^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'; then
  echo 'upload_error=invalid_backup_identity' >&2
  exit 1
fi
for value in "$BACKUP_SNAPSHOT_EPOCH" "$BACKUP_DUMP_COMPLETED_EPOCH" "$SOURCE_SERVER_VERSION_NUM" "$ACCOUNT_COUNT" "$DUMP_BYTES"; do
  case "$value" in '' | *[!0-9]*) echo 'upload_error=invalid_numeric_metadata' >&2; exit 1 ;; esac
done
for value in "$DUMP_SHA256" "$RECONCILIATION_SHA256" "$CAPACITY_SHA256"; do
  case "$value" in
    *[!0-9a-f]* | '') echo 'upload_error=invalid_checksum_metadata' >&2; exit 1 ;;
  esac
  if [ "${#value}" -ne 64 ]; then
    echo 'upload_error=invalid_checksum_length' >&2
    exit 1
  fi
done

validate_checksums "$BACKUP_DIR/checksums.sha256"
(
  cd "$BACKUP_DIR"
  sha256sum --check checksums.sha256 >/dev/null
)
if [ "$(awk '$2 == "database.dump" { print $1 }' "$BACKUP_DIR/checksums.sha256")" != "$DUMP_SHA256" ] || \
   [ "$(awk '$2 == "reconciliation.csv" { print $1 }' "$BACKUP_DIR/checksums.sha256")" != "$RECONCILIATION_SHA256" ] || \
   [ "$(awk '$2 == "capacity.csv" { print $1 }' "$BACKUP_DIR/checksums.sha256")" != "$CAPACITY_SHA256" ]; then
  echo 'upload_error=metadata_checksum_mismatch' >&2
  exit 1
fi

object_prefix="$S3_PREFIX/postgres/$BACKUP_ID"
for file in database.dump reconciliation.csv capacity.csv checksums.sha256 metadata.env; do
  aws s3 cp \
    "$BACKUP_DIR/$file" \
    "s3://$S3_BUCKET/$object_prefix/$file" \
    --endpoint-url "$AWS_ENDPOINT_URL" \
    --only-show-errors
  require_object_encryption "$object_prefix/$file"
  require_private_object "$object_prefix/$file"
done

latest_pointer_updated=false
if [ "$UPDATE_LATEST_POINTER" = true ]; then
  cp "$BACKUP_DIR/metadata.env" "$BACKUP_DIR/latest.env"
  aws s3 cp \
    "$BACKUP_DIR/latest.env" \
    "s3://$S3_BUCKET/$S3_PREFIX/postgres/latest.env" \
    --endpoint-url "$AWS_ENDPOINT_URL" \
    --only-show-errors
  require_object_encryption "$S3_PREFIX/postgres/latest.env"
  require_private_object "$S3_PREFIX/postgres/latest.env"
  latest_pointer_updated=true
fi

touch "$BACKUP_DIR/UPLOADED"
trap - EXIT HUP INT TERM
uploaded_epoch=$(date -u +%s)
printf '{"schemaVersion":1,"kind":"backup","status":"uploaded","backupId":"%s","snapshotEpoch":%s,"dumpCompletedEpoch":%s,"uploadedEpoch":%s,"sourceServerVersionNum":%s,"accountCount":%s,"dumpBytes":%s,"retentionDays":%s,"latestPointerUpdated":%s,"dumpSha256":"%s","reconciliationSha256":"%s","capacitySha256":"%s"}\n' \
  "$BACKUP_ID" \
  "$BACKUP_SNAPSHOT_EPOCH" \
  "$BACKUP_DUMP_COMPLETED_EPOCH" \
  "$uploaded_epoch" \
  "$SOURCE_SERVER_VERSION_NUM" \
  "$ACCOUNT_COUNT" \
  "$DUMP_BYTES" \
  "$retention_days" \
  "$latest_pointer_updated" \
  "$DUMP_SHA256" \
  "$RECONCILIATION_SHA256" \
  "$CAPACITY_SHA256"
