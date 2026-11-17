#!/usr/bin/env bash
# Runs Docker Compose for one environment with the right files, project name and
# settings:
#
#   scripts/stack.sh dev up -d --build --wait     the DEV stack (HA topology)
#   scripts/stack.sh dev --lite up -d --wait      DEV with single instances
#   scripts/stack.sh dev logs -f order
#   scripts/stack.sh dev down                     stop (add -v to delete the data)
#   scripts/stack.sh prod config                  what PROD would run
#
# dev      compose.yml + compose.dev.yml + compose.ha.yml (unless --lite)
# int      compose.yml + compose.int.yml
# qa/prod  compose.yml + compose.ha.yml + compose.<env>.yml
#
# Settings come from deploy/env/<env>.env. For dev that file is created on first
# use from dev.env.example with freshly generated secrets; for the other
# environments you provide it (see deploy/env/<env>.env.example).
set -euo pipefail

usage() { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

[ $# -ge 1 ] || usage
env="$1"; shift
case "$env" in dev|int|qa|prod) ;; *) usage ;; esac

lite=false
if [ "${1:-}" = "--lite" ]; then
  lite=true; shift
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
# Docker on Windows (Git Bash) is a Windows program: give it C:/… paths, which
# also keeps working when a caller has switched off Git Bash's path rewriting.
if command -v cygpath >/dev/null; then root="$(cygpath -m "$root")"; fi
deploy="$root/deploy"
env_file="$deploy/env/$env.env"

random() {  # 40 characters, safe in JAAS strings, nginx config and URLs
  # A bounded read first: an endless one would end in SIGPIPE, which pipefail
  # treats as a failure.
  head -c 512 /dev/urandom | base64 | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c1-40
}

if [ ! -f "$env_file" ]; then
  if [ "$env" != dev ]; then
    echo "stack: $env_file does not exist. Copy deploy/env/$env.env.example and fill it in." >&2
    exit 1
  fi
  umask 077
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      *=__GENERATE_B64__) printf '%s=%s\n' "${line%%=*}" "$(head -c 32 /dev/urandom | base64 | tr -d '\n')" ;;
      *=__GENERATE__)     printf '%s=%s\n' "${line%%=*}" "$(random)" ;;
      *)                  printf '%s\n' "$line" ;;
    esac
  done < "$deploy/env/dev.env.example" > "$env_file"
  echo "stack: created $env_file with generated secrets" >&2
fi

files=(-f "$deploy/compose.yml")
ha="$deploy/compose.ha.yml"
case "$env" in
  dev)
    files+=(-f "$deploy/compose.dev.yml")
    if [ "$lite" = false ] && [ -f "$ha" ]; then files+=(-f "$ha"); fi
    ;;
  int)
    files+=(-f "$deploy/compose.int.yml")
    ;;
  qa|prod)
    files+=(-f "$ha" -f "$deploy/compose.$env.yml")
    ;;
esac

# Each environment's networks live in their own private /16 (see compose.yml),
# so environments side by side on one host never overlap. Override with
# COMMERCE_NET when running this script.
case "$env" in
  dev) default_net=10.77 ;; int) default_net=10.78 ;; qa) default_net=10.79 ;; prod) default_net=10.80 ;;
esac
export COMMERCE_NET="${COMMERCE_NET:-$default_net}"

exec docker compose -p "commerce-$env" --env-file "$env_file" "${files[@]}" "$@"
