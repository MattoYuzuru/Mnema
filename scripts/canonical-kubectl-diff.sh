#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: $0 LIVE_PATH DESIRED_PATH" >&2
  exit 64
fi

live_path=$1
desired_path=$2
export LC_ALL=C

if [ ! -d "$live_path" ] || [ ! -d "$desired_path" ]; then
  diff -u -N --label LIVE --label DESIRED "$live_path" "$desired_path"
  exit $?
fi

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/mnema-canonical-diff.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT HUP INT TERM
relative_paths="$work_dir/relative-paths"

{
  (cd "$live_path" && find . -type f -print)
  (cd "$desired_path" && find . -type f -print)
} | sort -u >"$relative_paths"

diff_status=0
while IFS= read -r relative_path; do
  [ -n "$relative_path" ] || continue
  relative_path=${relative_path#./}

  set +e
  diff -u -N \
    --label "LIVE/$relative_path" \
    --label "DESIRED/$relative_path" \
    "$live_path/$relative_path" \
    "$desired_path/$relative_path"
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
