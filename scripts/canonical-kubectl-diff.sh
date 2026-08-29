#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: $0 LIVE_PATH DESIRED_PATH" >&2
  exit 64
fi

live_path=$1
desired_path=$2
export LC_ALL=C

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/mnema-canonical-diff.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT HUP INT TERM
relative_paths="$work_dir/relative-paths"
live_paths="$work_dir/live-paths"
desired_paths="$work_dir/desired-paths"
canonical_live="$work_dir/live-object.yaml"
canonical_desired="$work_dir/desired-object.yaml"

canonicalize_object() {
  input=$1
  output=$2

  if [ ! -f "$input" ]; then
    : >"$output"
    return
  fi

  # kubectl's server-side dry-run assigns volatile metadata to resources that
  # do not exist yet. Controllers can also advance the corresponding live
  # metadata without changing declarative intent. Keep the approval bound to
  # the resource body while excluding only API-server-generated metadata.
  awk '
    /^metadata:[[:space:]]*$/ {
      in_metadata = 1
      print
      next
    }
    in_metadata && /^[^[:space:]]/ {
      in_metadata = 0
    }
    in_metadata && /^  (creationTimestamp|generation|resourceVersion|uid):/ {
      next
    }
    { print }
  ' "$input" >"$output"
}

diff_objects() {
  live_object=$1
  desired_object=$2
  live_label=$3
  desired_label=$4

  if ! canonicalize_object "$live_object" "$canonical_live"; then
    echo "Unable to canonicalize live kubectl object" >&2
    return 2
  fi
  if ! canonicalize_object "$desired_object" "$canonical_desired"; then
    echo "Unable to canonicalize desired kubectl object" >&2
    return 2
  fi
  diff -u -N \
    --label "$live_label" \
    --label "$desired_label" \
    "$canonical_live" \
    "$canonical_desired"
}

if [ ! -d "$live_path" ] || [ ! -d "$desired_path" ]; then
  diff_objects "$live_path" "$desired_path" LIVE DESIRED
  exit $?
fi

if ! (cd "$live_path" && find . -type f -print) >"$live_paths"; then
  echo "Unable to enumerate live kubectl diff files" >&2
  exit 2
fi
if ! (cd "$desired_path" && find . -type f -print) >"$desired_paths"; then
  echo "Unable to enumerate desired kubectl diff files" >&2
  exit 2
fi
if ! sort -u "$live_paths" "$desired_paths" >"$relative_paths"; then
  echo "Unable to order kubectl diff files" >&2
  exit 2
fi

diff_status=0
while IFS= read -r relative_path; do
  [ -n "$relative_path" ] || continue
  relative_path=${relative_path#./}

  set +e
  diff_objects \
    "$live_path/$relative_path" \
    "$desired_path/$relative_path" \
    "LIVE/$relative_path" \
    "DESIRED/$relative_path"
  resource_status=$?
  set -e

  if [ "$resource_status" -gt 1 ]; then
    exit "$resource_status"
  fi
  if [ "$resource_status" -eq 1 ]; then
    diff_status=1
  fi
done <"$relative_paths"

exit "$diff_status"
