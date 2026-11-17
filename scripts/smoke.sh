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
number() {  # <field> → the first numeric value of "field" in $body
  printf '%s' "$body" | grep -o "\"$1\":-\{0,1\}[0-9]*" | head -1 | cut -d: -f2
}
verification_token() {  # <email> → the token in the newest verification email to it (waits up to 20 s)
  local id token
  for _ in $(seq 1 20); do
    id=$(curl -s "$MAILPIT/api/v1/search?query=to:%22$1%22" | grep -o '"ID":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ -n "$id" ]; then
      token=$(curl -s "$MAILPIT/api/v1/message/$id" | grep -o 'verify-email#token=[A-Za-z0-9_-]*' | head -1 \
        | cut -d= -f2 || true)
      if [ -n "$token" ]; then printf '%s' "$token"; return; fi
    fi
    sleep 1
  done
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

echo "Back office (administrator)"
admin_email="$(setting BOOTSTRAP_ADMIN_EMAIL)"
admin_password="$(setting DEV_ADMIN_PASSWORD)"
[ -n "$admin_email" ] && [ -n "$admin_password" ] || fail "dev.env names the administrator" \
  "BOOTSTRAP_ADMIN_EMAIL and DEV_ADMIN_PASSWORD (scripts/stack.sh dev adds missing settings)"
admin_login='{"email":"'"$admin_email"'","password":"'"$admin_password"'"}'
if [ "$(status POST /api/v1/auth/login -H 'Content-Type: application/json' --data "$admin_login")" != 200 ]; then
  # First run on this stack: the administrator signs up like anyone else and is
  # promoted on verification (BOOTSTRAP_ADMIN_EMAIL).
  expect "the administrator signs up" 201 POST /api/v1/auth/signup -H 'Content-Type: application/json' \
    --data "$admin_login"
  token=$(verification_token "$admin_email")
  [ -n "$token" ] || fail "the administrator's verification email arrives"
  expect "the administrator verifies" 200 POST /api/v1/auth/verify-email -H 'Content-Type: application/json' \
    --data '{"token":"'"$token"'","password":"'"$admin_password"'"}'
fi
expect "the administrator signs in" 200 POST /api/v1/auth/login -H 'Content-Type: application/json' --data "$admin_login"
admin=$(json accessToken)
admin_auth=(-H "Authorization: Bearer $admin")
shopper_auth=(-H "Authorization: Bearer $access")

sku="SMOKE-$(date +%s)-$RANDOM"
name="Smoke mug $sku"
expect "an administrator adds a product" 200 PUT "/api/v1/backoffice/catalog/products/$sku" "${admin_auth[@]}" \
  -H 'Content-Type: application/json' \
  --data '{"name":"'"$name"'","category":"smoke","priceCents":1250,"currency":"USD","active":true}'
expect "an administrator sets its stock" 200 PUT "/api/v1/backoffice/inventory/stock/$sku" "${admin_auth[@]}" \
  -H 'Content-Type: application/json' --data '{"onHand":5}'

available() {  # → the catalog's "available" figure for the smoke product (the back office reads it uncached)
  status GET "/api/v1/backoffice/catalog/products?q=$(printf '%s' "$name" | sed 's/ /%20/g')" "${admin_auth[@]}" >/dev/null
  body=$(cat "$body_file")
  number available
}
wait_available() {  # <name> <units>
  for _ in $(seq 1 30); do
    [ "$(available)" = "$2" ] && { pass "$1"; return; }
    sleep 1
  done
  fail "$1" "expected $2 available, saw $(available)"
}
wait_available "stock reaches the shop window" 5

order_body() {  # <quantity> <payment method> <expected total>
  printf '{"lines":[{"sku":"%s","quantity":%s}],"shippingAddress":{"name":"Smoke Test","line1":"1 Test Street","city":"Springfield","postalCode":"12345","country":"US"},"paymentMethod":"%s","expectedTotalCents":%s}' \
    "$sku" "$1" "$2" "$3"
}
place() {  # <name> <quantity> <payment method> → order id in $order_id. Waits for the order
           # service to have heard of the product (it learns prices from catalog events).
  local key got
  key="smoke-$(date +%s)-$RANDOM$RANDOM"
  for _ in $(seq 1 30); do
    got=$(status POST /api/v1/orders "${shopper_auth[@]}" -H 'Content-Type: application/json' \
      -H "Idempotency-Key: $key" --data "$(order_body "$2" "$3" $(( $2 * 1250 )))")
    body=$(cat "$body_file")
    [ "$got" = 202 ] && break
    [ "$got" = 400 ] || fail "$1" "expected HTTP 202, got $got: ${body:0:300}"
    sleep 1
  done
  [ "$got" = 202 ] || fail "$1" "the order service never learned the product: ${body:0:300}"
  order_id=$(json id)
  pass "$1"
}
settles() {  # <name> <order id> <status> [cancel reason] — waits up to 90 s
  local seen=""
  for _ in $(seq 1 90); do
    status GET "/api/v1/orders/$2" "${shopper_auth[@]}" >/dev/null
    body=$(cat "$body_file")
    seen=$(json status)
    if [ "$seen" = "$3" ]; then
      if [ -n "${4:-}" ] && [ "$(json cancelReason)" != "$4" ]; then
        fail "$1" "cancelled with $(json cancelReason), expected $4"
      fi
      pass "$1"; return
    fi
    sleep 1
  done
  fail "$1" "still $seen after 90 s"
}

echo "Orders"
place "a customer places an order" 2 tok_visa
happy="$order_id"
settles "the order is confirmed" "$happy" CONFIRMED
wait_available "confirmed units leave the shop window" 3

expect "an order priced at a stale total is refused" 409 POST /api/v1/orders "${shopper_auth[@]}" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-stale-$RANDOM$RANDOM" --data "$(order_body 1 tok_visa 999)"
[ "$(json code)" = PRICE_CHANGED ] || fail "the refusal says the price changed" "$body"
pass "the refusal says the price changed"

place "an order for more than is in stock" 4 tok_visa
settles "it is cancelled as out of stock" "$order_id" CANCELLED OUT_OF_STOCK
wait_available "nothing was held for it" 3

place "an order paid with a declined card" 1 tok_decline
settles "it is cancelled as declined" "$order_id" CANCELLED PAYMENT_DECLINED
wait_available "its reservation is released" 3

SIMULATORS="http://localhost:$(setting SIMULATORS_PORT)"
fault() {  # <target> <json>
  curl -s -o /dev/null -w '%{http_code}' -X PUT "$SIMULATORS/admin/faults/$1" -H 'Content-Type: application/json' --data "$2"
}
heal() { fault payment '{"latencyMs":0,"errorRate":0,"outage":false}' >/dev/null || true; }
trap 'heal; rm -f "$body_file"' EXIT
[ "$(fault payment '{"latencyMs":0,"errorRate":0,"outage":true}')" = 200 ] || fail "the payment provider can be taken down"
place "an order while the payment provider is down" 1 tok_visa
settles "it is cancelled as payment unavailable" "$order_id" CANCELLED PAYMENT_UNAVAILABLE
heal
wait_available "its reservation is released" 3

expect "the customer cancels the confirmed order" 200 POST "/api/v1/orders/$happy/cancel" "${shopper_auth[@]}"
[ "$(json status)" = CANCELLED ] || fail "the cancelled order says so" "$body"
wait_available "the cancelled units are back on sale" 5
for _ in $(seq 1 30); do
  status GET "/api/v1/backoffice/billing/payments?orderId=$happy" "${admin_auth[@]}" >/dev/null
  body=$(cat "$body_file")
  [ "$(json status)" = REFUNDED ] && break
  sleep 1
done
[ "$(json status)" = REFUNDED ] || fail "the customer's payment is refunded" "$body"
pass "the customer's payment is refunded"
expect "a cancelled order cannot be cancelled again" 409 POST "/api/v1/orders/$happy/cancel" "${shopper_auth[@]}"
expect "an anonymous caller cannot see the order" 401 GET "/api/v1/orders/$happy"

echo "Fulfilment and returns"
place "a customer orders one more" 1 tok_visa
kept="$order_id"
settles "it is confirmed" "$kept" CONFIRMED
settles "the carrier dispatches it" "$kept" SHIPPED
settles "the carrier delivers it" "$kept" DELIVERED
[ -n "$(json trackingNumber)" ] || fail "the order shows its tracking number" "$body"
pass "the order shows its tracking number"
wait_available "the delivered unit is no longer on sale" 4
expect "the customer asks to return it" 200 POST "/api/v1/orders/$kept/return" "${shopper_auth[@]}"   -H 'Content-Type: application/json' --data '{"reason":"Changed my mind"}'
[ "$(json status)" = RETURN_REQUESTED ] || fail "the return is recorded" "$body"
pass "the return is recorded"
settles "the return reaches the warehouse and is refunded" "$kept" REFUNDED
[ -n "$(json returnTrackingNumber)" ] || fail "the order shows its return tracking number" "$body"
pass "the order shows its return tracking number"
wait_available "the returned unit is back on sale" 5
status GET "/api/v1/backoffice/billing/payments?orderId=$kept" "${admin_auth[@]}" >/dev/null
body=$(cat "$body_file")
[ "$(json status)" = REFUNDED ] || fail "the returned order's payment is refunded" "$body"
pass "the returned order's payment is refunded"
expect "a refunded order cannot be returned again" 409 POST "/api/v1/orders/$kept/return" "${shopper_auth[@]}"   -H 'Content-Type: application/json' --data '{"reason":"Again"}'

echo "Customer emails (Mailpit)"
short="${kept: -8}"
for kind in "is confirmed" "is on its way" "has been delivered" "Your return label" "Your refund" ; do
  found=""
  for _ in $(seq 1 30); do
    if curl -s "$MAILPIT/api/v1/search?query=to:%22$email%22" | grep -q "\"Subject\":\"[^\"]*$kind[^\"]*$short\|\"Subject\":\"[^\"]*$short[^\"]*$kind"; then
      found=yes; break
    fi
    sleep 1
  done
  [ -n "$found" ] || fail "email \"$kind\" arrives for the returned order"
  pass "email \"$kind\" arrives for the returned order"
done
curl -s "$MAILPIT/api/v1/search?query=to:%22$email%22" | grep -q "\"Subject\":\"[^\"]*${happy: -8} is cancelled"   || fail "email \"is cancelled\" arrives for the cancelled order"
pass "email \"is cancelled\" arrives for the cancelled order"

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
