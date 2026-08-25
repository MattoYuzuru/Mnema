#!/bin/sh
set -eu

umask 007

BACKUP_DIR=${BACKUP_DIR:-/restore}
EXPECTED_AWS_ENDPOINT_URL=${EXPECTED_AWS_ENDPOINT_URL:-https://storage.yandexcloud.net}

required() {
  name="$1"
  value="$2"
  if [ -z "$value" ]; then
    echo "download_error=missing_${name}" >&2
    exit 1
  fi
}

metadata_value() {
  key="$1"
  file="$2"
  value=$(awk -F= -v key="$key" '$1 == key { if (found) exit 2; found = 1; print substr($0, length(key) + 2) } END { if (!found) exit 3 }' "$file") || {
    echo "download_error=invalid_metadata_${key}" >&2
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
    echo 'download_error=invalid_metadata_file' >&2
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
    echo 'download_error=invalid_checksum_file' >&2
    exit 1
  fi
}

download_object() {
  key="$1"
  destination="$2"
  aws s3 cp \
    "s3://$S3_BUCKET/$key" \
    "$destination" \
    --endpoint-url "$AWS_ENDPOINT_URL" \
    --only-show-errors

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
    echo 'download_error=object_not_encrypted_with_expected_kms_key' >&2
    exit 1
  fi

  anonymous_error="$BACKUP_DIR/anonymous-head.stderr"
  if aws s3api head-object \
    --bucket "$S3_BUCKET" \
    --key "$key" \
    --endpoint-url "$AWS_ENDPOINT_URL" \
    --no-sign-request \
    > /dev/null 2> "$anonymous_error"; then
    echo 'download_error=object_anonymously_readable' >&2
    exit 1
  fi
  if ! grep -Eq '(403|Forbidden|AccessDenied)' "$anonymous_error"; then
    echo 'download_error=anonymous_access_check_failed' >&2
    exit 1
  fi
  rm -f "$anonymous_error"
}

fail() {
  touch "$BACKUP_DIR/DOWNLOAD_FAILED" 2>/dev/null || true
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
required KMS_KEY_ID "${KMS_KEY_ID:-}"
required BACKUP_ID "${BACKUP_ID:-}"

if [ "$AWS_ENDPOINT_URL" != "$EXPECTED_AWS_ENDPOINT_URL" ]; then
  echo 'download_error=unexpected_object_storage_endpoint' >&2
  exit 1
fi
case "$S3_BUCKET" in '' | *[!a-z0-9.-]* | .* | *. | *..*) echo 'download_error=invalid_bucket' >&2; exit 1 ;; esac
case "$S3_PREFIX" in '' | /* | */ | *..* | *//* | *[!A-Za-z0-9._/-]*) echo 'download_error=invalid_prefix' >&2; exit 1 ;; esac
case "$KMS_KEY_ID" in '' | *[!A-Za-z0-9:/._-]*) echo 'download_error=invalid_kms_key_id' >&2; exit 1 ;; esac

export AWS_DEFAULT_REGION="$AWS_REGION"
export AWS_EC2_METADATA_DISABLED=true
export AWS_PAGER=''

mkdir -p "$BACKUP_DIR"
rm -f "$BACKUP_DIR/READY" "$BACKUP_DIR/DOWNLOAD_FAILED"

requested_backup_id=${BACKUP_ID:-}
if [ "$requested_backup_id" = latest ]; then
  download_object "$S3_PREFIX/postgres/latest.env" "$BACKUP_DIR/pointer.env"
  validate_metadata "$BACKUP_DIR/pointer.env"
  requested_backup_id=$(metadata_value BACKUP_ID "$BACKUP_DIR/pointer.env")
fi
if ! printf '%s\n' "$requested_backup_id" | grep -Eq '^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'; then
  echo 'download_error=invalid_backup_id' >&2
  exit 1
fi

object_prefix="$S3_PREFIX/postgres/$requested_backup_id"
download_object "$object_prefix/metadata.env" "$BACKUP_DIR/metadata.env"
validate_metadata "$BACKUP_DIR/metadata.env"
actual_backup_id=$(metadata_value BACKUP_ID "$BACKUP_DIR/metadata.env")
if [ "$actual_backup_id" != "$requested_backup_id" ]; then
  echo 'download_error=backup_identity_mismatch' >&2
  exit 1
fi
if [ -f "$BACKUP_DIR/pointer.env" ] && ! cmp -s "$BACKUP_DIR/pointer.env" "$BACKUP_DIR/metadata.env"; then
  echo 'download_error=latest_pointer_mismatch' >&2
  exit 1
fi

for file in database.dump reconciliation.csv capacity.csv checksums.sha256; do
  download_object "$object_prefix/$file" "$BACKUP_DIR/$file"
done

validate_checksums "$BACKUP_DIR/checksums.sha256"
(
  cd "$BACKUP_DIR"
  sha256sum --check checksums.sha256 >/dev/null
)

expected_dump_sha=$(metadata_value DUMP_SHA256 "$BACKUP_DIR/metadata.env")
expected_reconciliation_sha=$(metadata_value RECONCILIATION_SHA256 "$BACKUP_DIR/metadata.env")
expected_capacity_sha=$(metadata_value CAPACITY_SHA256 "$BACKUP_DIR/metadata.env")
if [ "$(awk '$2 == "database.dump" { print $1 }' "$BACKUP_DIR/checksums.sha256")" != "$expected_dump_sha" ] || \
   [ "$(awk '$2 == "reconciliation.csv" { print $1 }' "$BACKUP_DIR/checksums.sha256")" != "$expected_reconciliation_sha" ] || \
   [ "$(awk '$2 == "capacity.csv" { print $1 }' "$BACKUP_DIR/checksums.sha256")" != "$expected_capacity_sha" ]; then
  echo 'download_error=metadata_checksum_mismatch' >&2
  exit 1
fi

expected_bytes=$(metadata_value DUMP_BYTES "$BACKUP_DIR/metadata.env")
actual_bytes=$(wc -c < "$BACKUP_DIR/database.dump" | tr -d ' ')
if [ "$actual_bytes" != "$expected_bytes" ]; then
  echo 'download_error=dump_size_mismatch' >&2
  exit 1
fi

touch "$BACKUP_DIR/READY"
trap - EXIT HUP INT TERM
echo "download_status=ready backup_id=$actual_backup_id dump_bytes=$actual_bytes" >&2
