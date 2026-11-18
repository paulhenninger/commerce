#!/usr/bin/env bash
# Runs the resilience experiments against the running DEV stack (architecture §11).
#
#   scripts/prove.sh              the whole catalogue
#   scripts/prove.sh --quick      the subset CI runs
#   scripts/prove.sh <name>...    the named experiments
#   scripts/prove.sh --status     the latest verdict of each experiment (queues nothing)
set -euo pipefail
cd "$(dirname "$0")/.."
# The first interpreter that runs (on Windows "python3" can be the Store's stub).
for PYTHON in python3 python; do
  if "$PYTHON" -c 'import sys' >/dev/null 2>&1; then
    exec "$PYTHON" scripts/prove.py "$@"
  fi
done
echo "prove.sh needs Python 3" >&2
exit 1
