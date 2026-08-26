#!/bin/sh
set -eu

if [ "$#" -ne 14 ]; then
  echo "usage: $0 METADATA DIFF MANIFEST_CHECKSUM RELEASE_SHA DIFF_SHA256 KUBECTL_VERSION RUN_ID RUN_ATTEMPT SECRET_DRIFT SECRET_SNAPSHOT_HMAC LIVE_SECRET_SNAPSHOT_HMAC APP_SECRET_GENERATION GRAFANA_SECRET_GENERATION HAS_CHANGES" >&2
  exit 64
fi

metadata_file=$1
diff_file=$2
manifest_checksum_file=$3
expected_release_sha=$4
expected_diff_sha256=$5
expected_kubectl_version=$6
expected_run_id=$7
expected_run_attempt=$8
expected_secret_drift=$9
expected_secret_snapshot_hmac=${10}
expected_live_secret_snapshot_hmac=${11}
expected_app_secret_generation=${12}
expected_grafana_secret_generation=${13}
expected_has_changes=${14}

for binding in \
  "$expected_secret_snapshot_hmac" \
  "$expected_live_secret_snapshot_hmac" \
  "$expected_app_secret_generation" \
  "$expected_grafana_secret_generation"
do
  if [ "${#binding}" -ne 64 ]; then
    echo "Approved Secret snapshot binding must be exactly 64 lowercase hexadecimal characters" >&2
    exit 1
  fi
  case "$binding" in
    *[!0-9a-f]*)
      echo "Approved Secret snapshot binding must be exactly 64 lowercase hexadecimal characters" >&2
      exit 1
      ;;
  esac
done

for required_file in "$metadata_file" "$diff_file" "$manifest_checksum_file"; do
  if [ ! -f "$required_file" ]; then
    echo "Approved production preview file is missing: $required_file" >&2
    exit 1
  fi
done

metadata_value() {
  key=$1
  awk -F= -v key="$key" '
    $1 == key {
      count += 1
      value = substr($0, length(key) + 2)
    }
    END {
      if (count != 1) {
        exit 1
      }
      print value
    }
  ' "$metadata_file"
}

require_metadata() {
  key=$1
  expected=$2
  actual=$(metadata_value "$key") || {
    echo "Approved production preview metadata must contain exactly one $key" >&2
    exit 1
  }
  if [ "$actual" != "$expected" ]; then
    echo "Approved production preview metadata mismatch for $key" >&2
    exit 1
  fi
}

manifest_sha256=$(awk 'NF {print $1; exit}' "$manifest_checksum_file")
diff_sha256=$(sha256sum "$diff_file" | awk '{print $1}')

require_metadata release_sha "$expected_release_sha"
require_metadata release_manifest_sha256 "$manifest_sha256"
require_metadata release_diff_sha256 "$expected_diff_sha256"
require_metadata release_diff_sha256 "$diff_sha256"
require_metadata secret_drift "$expected_secret_drift"
require_metadata secret_snapshot_hmac "$expected_secret_snapshot_hmac"
require_metadata live_secret_snapshot_hmac "$expected_live_secret_snapshot_hmac"
require_metadata app_secret_generation "$expected_app_secret_generation"
require_metadata grafana_secret_generation "$expected_grafana_secret_generation"
require_metadata has_release_changes "$expected_has_changes"
require_metadata kubectl_version "$expected_kubectl_version"
require_metadata run_id "$expected_run_id"
require_metadata run_attempt "$expected_run_attempt"

production_resource_changes=$(metadata_value production_resource_changes) || {
  echo "Approved production preview metadata must contain exactly one production_resource_changes" >&2
  exit 1
}
case "$production_resource_changes" in
  true | false) ;;
  *)
    echo "Approved production preview has an invalid production resource change state" >&2
    exit 1
    ;;
esac
if [ "$production_resource_changes" = true ] || [ "$expected_secret_drift" = true ]; then
  derived_has_changes=true
else
  derived_has_changes=false
fi
if [ "$derived_has_changes" != "$expected_has_changes" ]; then
  echo "Approved production preview change summary is inconsistent" >&2
  exit 1
fi

printf 'approved_release_preview=ok\n'
