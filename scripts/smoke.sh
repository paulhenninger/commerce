#!/usr/bin/env bash
# End-to-end checks against a running DEV stack, through the storefront edge the
# way a browser reaches it, plus the checks that only make sense from inside
# (forged headers sent straight to a service, Kafka clients that must be refused).
#
#   scripts/stack.sh dev up -d --build --wait && scripts/smoke.sh
#
# Needs curl. Exits non-zero on the first failed check, naming it.
# Each milestone adds its checks here (implementation plan, section 6).
set -euo pipefail
# Git Bash on Windows would rewrite the container paths below (/opt/kafka/…) into
# Windows paths. No effect elsewhere.
export MSYS_NO_PATHCONV=1

root="$(cd "$(dirname "$0")/.." && pwd)"
env_file="$root/deploy/env/dev.env"
[ -f "$env_file" ] || { echo "smoke: $env_file not found; start the stack with scripts/stack.sh dev first" >&2; exit 1; }
setting() { grep -E "^$1=" "$env_file" | head -1 | cut -d= -f2-; }

BASE="${SMOKE_BASE_URL:-http://localhost:$(setting STOREFRONT_PORT)}"
MAILPIT="${SMOKE_MAILPIT_URL:-http://localhost:8025}"
stack() { "$root/scripts/stack.sh" dev "$@"; }

passed=0
pass() { passed=$((passed + 1)); printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1" >&2; [ -n "${2:-}" ] && printf '       %s\n' "$2" >&2; exit 1; }

# status <method> <path> [curl args…] → the HTTP status. It runs in a
# subshell ($(…)), so the body goes to a file, read back by expect into $body.
body=""
body_file=$(mktemp)
# curl on Git Bash is a Windows program: hand it the Windows spelling of the path.
if command -v cygpath >/dev/null; then body_file=$(cygpath -m "$body_file"); fi
trap 'rm -f "$body_file"' EXIT
status() {
  local method="$1" path="$2"; shift 2
  curl -s -X "$method" -o "$body_file" -w '%{http_code}' "$@" "$BASE$path"
}
expect() {  # <name> <expected status> <method> <path> [curl args…]
  local name="$1" want="$2"; shift 2
  local got
  got=$(status "$@")
  body=$(cat "$body_file")
  [ "$got" = "$want" ] || fail "$name" "expected HTTP $want, got $got: ${body:0:300}"
  pass "$name"
}
json() {  # <field> → the first string value of "field" in $body
  printf '%s' "$body" | grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

echo "Edge"
expect "storefront page is served" 200 GET /
expect "public catalog answers without signing in" 200 GET /api/v1/catalog/products
printf '%s' "$body" | grep -q '"items":' || fail "catalog list has the paged shape" "$body"
pass "catalog list has the paged shape"
expect "the edge's auth check is not reachable from outside" 404 GET /internal/authz
expect "management endpoints are not reachable from outside" 404 GET /actuator/health
expect "unrouted API paths are 404" 404 GET /api/v1/nothing-here

echo "Sign-up through the edge, verified by email (Mailpit)"
email="smoke-$(date +%s)-$RANDOM@example.com"
password="smoke-passphrase-$RANDOM-$RANDOM"
expect "sign-up is accepted" 201 POST /api/v1/auth/signup -H 'Content-Type: application/json' \
  --data "{\"email\":\"$email\",\"password\":\"$password\"}"
token=""
for _ in $(seq 1 20); do
  id=$(curl -s "$MAILPIT/api/v1/search?query=to:%22$email%22" | grep -o '"ID":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
  if [ -n "$id" ]; then
    token=$(curl -s "$MAILPIT/api/v1/message/$id" | grep -o 'verify-email#token=[A-Za-z0-9_-]*' | head -1 | cut -d= -f2 || true)
    [ -n "$token" ] && break
  fi
  sleep 1
done
[ -n "$token" ] || fail "verification email arrives in Mailpit"
pass "verification email arrives in Mailpit"
expect "verification link signs the new account in" 200 POST /api/v1/auth/verify-email \
  -H 'Content-Type: application/json' --data "{\"token\":\"$token\",\"password\":\"$password\"}"
access=$(json accessToken)
[ -n "$access" ] || fail "verification returns an access token" "$body"
expect "the new account can read itself" 200 GET /api/v1/users/me -H "Authorization: Bearer $access"

echo "Forged identity"
forged=(-H 'X-Auth-User-Id: 1' -H 'X-Auth-Role: ADMIN' -H 'X-Auth-Strong: true' -H 'X-Auth-Email: admin@example.com')
product='{"name":"Forged","category":"x","priceCents":1,"currency":"USD","active":true}'
expect "forged admin headers without a token are refused at the edge" 401 PUT \
  /api/v1/backoffice/catalog/products/FORGED -H 'Content-Type: application/json' --data "$product" "${forged[@]}"
expect "a customer claiming to be an administrator is refused" 403 PUT \
  /api/v1/backoffice/catalog/products/FORGED -H 'Content-Type: application/json' --data "$product" \
  -H "Authorization: Bearer $access" "${forged[@]}"
expect "the forged product does not exist" 404 GET /api/v1/catalog/products/FORGED
# From inside the edge's network, straight to the service: no edge token, so
# the headers are not believed even though nothing stood in the way.
direct=$(stack exec -T storefront-web wget -q -S -O /dev/null \
  --header 'X-Auth-User-Id: 1' --header 'X-Auth-Role: ADMIN' --header 'X-Auth-Strong: true' \
  http://catalog:8080/api/v1/backoffice/catalog/products 2>&1 | grep -o 'HTTP/1.1 [0-9]*' | head -1 || true)
[ "$direct" = "HTTP/1.1 401" ] || fail "a service ignores X-Auth-* without the edge token" "got: ${direct:-no answer}"
pass "a service ignores X-Auth-* without the edge token"

echo "Kafka access control"
kafka() {  # <principal or ''> <command…>: runs a Kafka CLI tool inside the broker container as that principal
  local principal="$1"; shift
  local var password config=/tmp/smoke-client.properties
  if [ -n "$principal" ]; then
    var="KAFKA_PASSWORD_$(printf '%s' "$principal" | tr '[:lower:]-' '[:upper:]_')"
    password=$(setting "$var")
    stack exec -T kafka-1 bash -c "cat > $config" <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="$principal" password="$password";
EOF
  else
    stack exec -T kafka-1 bash -c "printf 'security.protocol=PLAINTEXT\n' > $config"
  fi
  stack exec -T -e KAFKA_HEAP_OPTS=-Xmx64m kafka-1 timeout 40 "$@" 2>&1 || true
}
out=$(kafka '' /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --command-config /tmp/smoke-client.properties --list --request-timeout-ms 5000 --command-timeout-ms 8000)
# Refused, not merely empty: the tool must have run and failed to get an answer.
if printf '%s' "$out" | grep -q 'commerce\.' || ! printf '%s' "$out" | grep -qi 'timeout\|timed out\|disconnect'; then
  fail "a client without credentials learns nothing" "$out"
fi
pass "a client without credentials learns nothing"
out=$(kafka catalog /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --command-config /tmp/smoke-client.properties --list)
printf '%s' "$out" | grep -qx 'commerce.catalog.events.v1' || fail "catalog can see its own topic" "$out"
pass "catalog can see its own topic"
out=$(printf 'smoke\n' | kafka catalog /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --producer.config /tmp/smoke-client.properties --topic commerce.order.events.v1 \
  --producer-property max.block.ms=8000 --producer-property delivery.timeout.ms=10000 \
  --producer-property request.timeout.ms=5000)
printf '%s' "$out" | grep -q 'TopicAuthorizationException\|Not authorized' \
  || fail "catalog's credentials cannot write the order topic" "$out"
pass "catalog's credentials cannot write the order topic"
out=$(kafka intruder /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --command-config /tmp/smoke-client.properties --list)
# Signed in (no authentication error), shown nothing.
if printf '%s' "$out" | grep -q 'commerce\.\|Exception\|rror'; then
  fail "the intruder principal signs in but sees no topics" "$out"
fi
pass "the intruder principal signs in but sees no topics"

echo "smoke: $passed checks passed"
