#!/usr/bin/env bash
# Proves that INT, QA and PROD contain no test tooling (ADR-014), from the Compose
# configuration each would run — no Docker daemon needed. CI runs it on every
# change; never weaken it.
#
#   scripts/verify-clean-deployments.sh
#
# For each environment:
#   1. with no settings, Compose refuses to render it (every secret and every
#      external system must be named explicitly);
#   2. with every setting filled in, the rendered configuration has none of the
#      DEV-only services or networks, no setting naming a test facility, the
#      right environment name everywhere, the Docker socket in Alloy only, and
#      no published port beyond the edges and Grafana.
set -euo pipefail
export MSYS_NO_PATHCONV=1

root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
# Docker on Windows (Git Bash) needs the Windows spelling of the path.
if command -v cygpath >/dev/null; then work="$(cygpath -m "$work")"; fi
trap 'rm -rf "$work"' EXIT

DEV_SERVICES="mailpit simulators testkit controller docker-proxy"
DEV_NETWORKS="sim control docker sandbox mail mail-ingress simulators-ingress tools-ingress"
DEV_WORDS="simulators testkit mailpit docker-proxy controller:8080 TESTKIT_URL INIT_DEV KAFKA_PASSWORD_TESTKIT KAFKA_PASSWORD_INTRUDER DEV_ADMIN_PASSWORD DEV_SHOPPER_PASSWORD"
ALLOWED_PORTS="8080 3000"   # the two edges (container port 8080) and Grafana

failures=0
fail() { printf '  FAIL %s: %s\n' "$env" "$1" >&2; failures=$((failures + 1)); }
pass() { printf '  ok   %s: %s\n' "$env" "$1"; }

random() { head -c 256 /dev/urandom | base64 | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c1-"${1:-40}"; }

# Fills an env example with plausible values that name no test facility.
fill() {
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in
      ''|'#'*|*=?*) printf '%s\n' "$line"; continue ;;
    esac
    key="${line%%=*}"
    case "$key" in
      APP_BASE_URL) value="https://shop.example.com" ;;
      GRAFANA_PUBLIC_URL) value="https://grafana.example.com" ;;
      PAYMENT_GATEWAY_URL) value="https://api.payments.example.com" ;;
      CARRIER_URL) value="https://api.carrier.example.com" ;;
      MAIL_HOST) value="smtp.example.com" ;;
      MAIL_FROM) value="Shop <shop@example.com>" ;;
      BOOTSTRAP_ADMIN_EMAIL) value="ops@example.com" ;;
      JWT_SECRET|DATA_ENCRYPTION_KEY) value="$(head -c 32 /dev/urandom | base64 | tr -d '\n')" ;;
      ALERTMANAGER_CONFIG|PASSKEY_EXTRA_ORIGINS|KAFKA_CLUSTER_ID|MAIL_USER|MAIL_PASS) value="" ;;
      *) value="$(random 40)" ;;
    esac
    printf '%s=%s\n' "$key" "$value"
  done < "$1"
}

stack() { STACK_ENV_FILE="$1" "$root/scripts/stack.sh" "$env" "${@:2}"; }

# Environments to check: int qa prod (by default). Passing "dev" shows the gate failing.
for env in ${*:-int qa prod}; do
  echo "$env"
  example="$root/deploy/env/$env.env.example"
  [ -f "$example" ] || { fail "deploy/env/$env.env.example is missing"; continue; }

  : > "$work/$env.empty"
  # Refused for the right reason: a required setting is missing (Compose names
  # it), not some other failure.
  if stack "$work/$env.empty" config -q 2> "$work/$env.empty.err"; then
    fail "renders with no settings (required values must be enforced)"
  elif grep -q 'required variable .* is missing a value' "$work/$env.empty.err"; then
    pass "refuses to render without its settings ($(grep -o 'required variable [A-Z_]*' "$work/$env.empty.err" | head -1))"
  else
    fail "fails without settings, but not because a setting is missing: $(tail -2 "$work/$env.empty.err")"
  fi

  fill "$example" > "$work/$env.env"
  if ! stack "$work/$env.env" config > "$work/$env.yml" 2> "$work/$env.err"; then
    fail "does not render with every setting filled in: $(tail -3 "$work/$env.err")"
    continue
  fi
  pass "renders with every setting filled in"

  services=" $(stack "$work/$env.env" config --services | tr '\n' ' ') "
  bad=""
  for s in $DEV_SERVICES; do case "$services" in *" $s "*) bad="$bad $s" ;; esac; done
  [ -z "$bad" ] && pass "no DEV-only service" || fail "DEV-only services present:$bad"

  networks=" $(stack "$work/$env.env" config --networks | tr '\n' ' ') "
  bad=""
  for n in $DEV_NETWORKS; do case "$networks" in *" $n "*) bad="$bad $n" ;; esac; done
  [ -z "$bad" ] && pass "no DEV-only network" || fail "DEV-only networks present:$bad"

  bad=""
  for w in $DEV_WORDS; do grep -qi -- "$w" "$work/$env.yml" && bad="$bad $w"; done
  [ -z "$bad" ] && pass "no setting names a test facility" || fail "test facilities named:$bad"

  wrong=$(grep -E '^\s+COMMERCE_ENV:' "$work/$env.yml" | grep -vE "COMMERCE_ENV: \"?$env\"?$" || true)
  count=$(grep -cE '^\s+COMMERCE_ENV:' "$work/$env.yml" || true)
  [ -z "$wrong" ] && [ "$count" -gt 0 ] && pass "COMMERCE_ENV is $env in all $count services" \
    || fail "COMMERCE_ENV is not $env everywhere: $wrong"

  sockets=$(grep -c 'docker.sock' "$work/$env.yml" || true)
  # The socket's only consumer is Alloy (read-only, for logs).
  alloy_socket=$(awk '/^  alloy:/{a=1} /^  [a-z]/{if($0!~/^  alloy:/)a=0} a && /docker.sock/' "$work/$env.yml" | grep -c 'docker.sock' || true)
  [ "$sockets" -gt 0 ] && [ "$sockets" = "$alloy_socket" ] && pass "the Docker socket is mounted in Alloy only" \
    || fail "Docker socket mounts: $sockets in total, $alloy_socket in Alloy"

  published=$(grep -E '^\s+target: [0-9]+' "$work/$env.yml" | awk '{print $2}' | sort -u | tr '\n' ' ')
  bad=""
  for p in $published; do case " $ALLOWED_PORTS " in *" $p "*) ;; *) bad="$bad $p" ;; esac; done
  [ -z "$bad" ] && pass "published ports: edges and Grafana only ($published)" || fail "unexpected published ports:$bad"
done

if [ "$failures" -gt 0 ]; then
  echo "verify-clean-deployments: $failures problems" >&2
  exit 1
fi
echo "verify-clean-deployments: INT, QA and PROD contain no test tooling"
