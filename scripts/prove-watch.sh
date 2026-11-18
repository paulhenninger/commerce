#!/usr/bin/env bash
# Follows a resilience run without an IDE: prints the latest verdict of each
# experiment every N seconds (default 60). Ctrl-C to stop; the run carries on
# in the controller either way.
#
#   scripts/prove-watch.sh [seconds]
set -uo pipefail
cd "$(dirname "$0")/.."
every="${1:-60}"
while true; do
  clear 2>/dev/null || true
  date -u '+%Y-%m-%dT%H:%M:%SZ'
  scripts/prove.sh --status || echo "(console not answering; retrying)"
  [ -f build/proofs/full-run.log ] && { echo; echo "--- build/proofs/full-run.log"; tail -n 15 build/proofs/full-run.log; }
  sleep "$every"
done
