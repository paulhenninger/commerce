#!/usr/bin/env bash
# The smoke test for a clean environment (INT, QA, PROD): no test data, no test
# accounts, no simulators, so it only reads and only checks what must hold before
# anyone uses the release. Run on the host, after scripts/deploy.sh has started it:
#
#   scripts/smoke-clean.sh int
#
# Exits non-zero on the first failed check, naming it. The full end-to-end proof
# is scripts/smoke.sh against DEV, which CI runs on every change.
set -euo pipefail

env="${1:?usage: smoke-clean.sh <int|qa|prod>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
env_file="$root/deploy/env/$env.env"
setting() { grep -E "^$1=" "$env_file" | tail -1 | cut -d= -f2- | tr -d '\r'; }
shop="http://127.0.0.1:$(setting STOREFRONT_PORT)"
console="http://127.0.0.1:$(setting OPS_CONSOLE_PORT)"

passed=0
fail() { printf '  FAIL %s\n' "$1" >&2; exit 1; }
expect() {  # <name> <status> <url> [grep pattern for the body]
  local body code
  body=$(mktemp)
  code=$(curl -s -o "$body" -w '%{http_code}' --max-time 15 "$3") || code=000
  [ "$code" = "$2" ] || fail "$1: $3 answered $code, expected $2"
  if [ -n "${4:-}" ] && ! grep -q "$4" "$body"; then rm -f "$body"; fail "$1: $3 did not contain $4"; fi
  rm -f "$body"
  passed=$((passed + 1)); printf '  ok   %s\n' "$1"
}

expect "storefront serves the shop"             200 "$shop/"                               '<div id="root"'
expect "storefront edge is healthy"             200 "$shop/healthz"
expect "identity answers through the edge"      200 "$shop/api/v1/auth/config"             '{'
expect "the catalog answers through the edge"   200 "$shop/api/v1/catalog/products?size=1" '"items"'
expect "an order without a token is refused"    401 "$shop/api/v1/orders"
expect "back office without a token is refused" 401 "$shop/api/v1/backoffice/orders"
expect "the actuator is not exposed"            404 "$shop/actuator/health"
expect "ops console is served"                  200 "$console/"                            '<div id="root"'
expect "ops API without a token is refused"     401 "$console/api/v1/ops/config"

echo "smoke-clean ($env): $passed checks passed"
