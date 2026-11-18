#!/usr/bin/env bash
# Deploys one release to this host's environment and rolls back if it does not
# come up healthy (architecture §14). CD (.github/workflows/deploy.yml) copies
# deploy/ and scripts/ here and runs it over SSH; it can also be run by hand:
#
#   scripts/deploy.sh <dev|int|qa|prod> <image tag>
#
# The images are the ones CD built and pushed once for that commit
# (IMAGE_REGISTRY, default ghcr.io/paulhenninger/), so every environment runs the
# same bytes. deploy/env/<env>.env must already be on the host (for dev it is
# generated on first use). The tag that last came up healthy is kept in
# deploy/env/.<env>.deployed; a release that fails its health wait — or, for a
# clean environment, scripts/smoke-clean.sh — is replaced by that tag again.
set -euo pipefail

env="${1:?usage: deploy.sh <dev|int|qa|prod> <image tag>}"
tag="${2:?usage: deploy.sh <dev|int|qa|prod> <image tag>}"
case "$env" in dev|int|qa|prod) ;; *) echo "deploy: unknown environment $env" >&2; exit 2 ;; esac
case "$tag" in *[!A-Za-z0-9._-]*|'') echo "deploy: bad image tag $tag" >&2; exit 2 ;; esac

root="$(cd "$(dirname "$0")/.." && pwd)"
state="$root/deploy/env/.$env.deployed"
previous="$(cat "$state" 2>/dev/null || true)"
export IMAGE_REGISTRY="${IMAGE_REGISTRY:-ghcr.io/paulhenninger/}"
stack() { "$root/scripts/stack.sh" "$env" "$@"; }

release() {  # <tag> → 0 when that release is up, healthy and (outside dev) passes the clean smoke
  export IMAGE_TAG="$1"
  stack pull --quiet &&
    stack up -d --no-build --remove-orphans --wait --wait-timeout 600 &&
    { [ "$env" = dev ] || "$root/scripts/smoke-clean.sh" "$env"; }
}

echo "deploy: $env → $tag (previous: ${previous:-none})"
if release "$tag"; then
  printf '%s\n' "$tag" > "$state"
  echo "deploy: $env runs $tag"
  exit 0
fi

stack ps -a || true
if [ -n "$previous" ] && [ "$previous" != "$tag" ]; then
  echo "deploy: $tag did not come up healthy; rolling back to $previous" >&2
  if release "$previous"; then
    echo "deploy: $env rolled back to $previous" >&2
  else
    echo "deploy: the rollback to $previous failed too; $env needs attention" >&2
  fi
else
  echo "deploy: $tag did not come up healthy and there is no earlier release to return to" >&2
fi
exit 1
