#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_DIR"
FETCH_MODE=${1:-}
verify_lock() {
  destination=$1
  lockfile=$2
  mkdir -p "$destination"
  while read -r checksum filename url; do
    case "$checksum" in ''|'#'*) continue ;; esac
    if [ ! -f "$destination/$filename" ]; then
      if [ "$FETCH_MODE" = --offline ]; then printf 'Missing pinned artifact: %s. Run scripts/fetch-deps.sh once online.\n' "$filename" >&2; exit 1; fi
      curl --fail --location --proto '=https' --tlsv1.2 "$url" -o "$destination/$filename.download"
      actual=$(shasum -a 256 "$destination/$filename.download" | cut -d ' ' -f 1)
      if [ "$actual" != "$checksum" ]; then rm -f "$destination/$filename.download"; printf 'Checksum mismatch: %s\n' "$filename" >&2; exit 1; fi
      mv "$destination/$filename.download" "$destination/$filename"
    fi
    actual=$(shasum -a 256 "$destination/$filename" | cut -d ' ' -f 1)
    if [ "$actual" != "$checksum" ]; then printf 'Checksum mismatch: %s\n' "$filename" >&2; exit 1; fi
  done < "$lockfile"
}
verify_lock lib dependencies.lock
verify_lock fixtures fixtures/upstream.lock
printf 'Verified pinned offline libraries and upstream fixtures.\n'
