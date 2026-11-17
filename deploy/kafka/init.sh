#!/bin/bash
# Creates, idempotently, everything a client needs before it can use Kafka: the
# services' credentials, the topics, and the access rules. Run once per
# deployment by the kafka-init service; every event-driven service waits for it.
#
# The rules implement "only the owner writes a topic" (architecture, section 5):
#
#   service        writes                reads (as group <service>)
#   catalog        catalog               stock
#   order          order                 inventory, billing, shipping, catalog
#   inventory      inventory, stock      order, catalog
#   billing        billing               order
#   shipping       shipping              order
#   notification   —                     order, shipping
#   ops            (replay only) all     everything, as groups ops-*
#
# A consumer may also write to the dead-letter topic of each topic it reads.
# With INIT_DEV=true (compose.dev.yml) the lab's principals are added: testkit
# reads everything, testkit-chaos may write every event topic (it plays a
# trusted producer gone wrong), and intruder may do nothing at all.
set -euo pipefail

: "${BOOTSTRAP:?}" "${INIT_PASSWORD:?}"
REPLICATION="${REPLICATION_FACTOR:-1}"
MIN_ISR="${MIN_INSYNC_REPLICAS:-1}"
BIN=/opt/kafka/bin
ADMIN=/tmp/admin.properties

cat > "$ADMIN" <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="kafka-init" password="$INIT_PASSWORD";
EOF

ORDER=commerce.order.events.v1
INVENTORY=commerce.inventory.events.v1
STOCK=commerce.inventory.stock.v1
BILLING=commerce.billing.events.v1
SHIPPING=commerce.shipping.events.v1
CATALOG=commerce.catalog.events.v1
ALL="$ORDER $INVENTORY $STOCK $BILLING $SHIPPING $CATALOG"

echo "kafka-init: waiting for the cluster at $BOOTSTRAP"
for attempt in $(seq 1 60); do
  if $BIN/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --command-config "$ADMIN" --list >/tmp/topics 2>/tmp/error; then
    break
  fi
  [ "$attempt" -lt 60 ] || { echo "kafka-init: cluster not reachable" >&2; cat /tmp/error >&2; exit 1; }
  sleep 2
done

# ---------------------------------------------------------------- credentials
credential() {  # <principal>
  local var="KAFKA_PASSWORD_$(printf '%s' "$1" | tr '[:lower:]-' '[:upper:]_')"
  local password="${!var:-}"
  [[ "$password" =~ ^[A-Za-z0-9_.-]{12,}$ ]] || {
    echo "kafka-init: $var must be 12+ characters from A-Z a-z 0-9 _ . -" >&2; exit 1; }
  $BIN/kafka-configs.sh --bootstrap-server "$BOOTSTRAP" --command-config "$ADMIN" --alter \
    --entity-type users --entity-name "$1" --add-config "SCRAM-SHA-512=[password=$password]" >/dev/null
  echo "kafka-init: credential for $1"
}

# --------------------------------------------------------------------- topics
topic() {  # <name> <partitions> <retention ms>
  if grep -qx "$1" /tmp/topics; then
    return
  fi
  $BIN/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --command-config "$ADMIN" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor "$REPLICATION" \
    --config "min.insync.replicas=$MIN_ISR" --config "retention.ms=$3" >/dev/null
  echo "kafka-init: topic $1"
}

# ----------------------------------------------------------------------- ACLs
acl() {
  $BIN/kafka-acls.sh --bootstrap-server "$BOOTSTRAP" --command-config "$ADMIN" --add --force "$@" >/dev/null
}

topics_args() {  # <suffix> <topic>... → --topic a --topic b
  local suffix="$1"; shift
  for t in "$@"; do printf -- '--topic %s%s ' "$t" "$suffix"; done
}

writes() {  # <principal> <topic>...
  local principal="$1"; shift
  # shellcheck disable=SC2046
  acl --allow-principal "User:$principal" --operation Write --operation Describe $(topics_args "" "$@")
  acl --allow-principal "User:$principal" --operation IdempotentWrite --cluster
}

reads() {  # <principal> <group> <literal|prefixed> <topic>...
  local principal="$1" group="$2" pattern="$3"; shift 3
  # shellcheck disable=SC2046
  acl --allow-principal "User:$principal" --operation Read --operation Describe $(topics_args "" "$@")
  acl --allow-principal "User:$principal" --operation Read --operation Describe \
    --group "$group" --resource-pattern-type "$pattern"
  # What it cannot process goes to the dead-letter topic of the topic it read.
  # shellcheck disable=SC2046
  acl --allow-principal "User:$principal" --operation Write --operation Describe $(topics_args ".dlt" "$@")
  acl --allow-principal "User:$principal" --operation IdempotentWrite --cluster
}

for principal in catalog order inventory billing shipping notification ops; do
  credential "$principal"
done

SEVEN_DAYS=604800000
THIRTY_DAYS=2592000000
topic "$ORDER" 6 $SEVEN_DAYS
topic "$INVENTORY" 6 $SEVEN_DAYS
topic "$STOCK" 6 $SEVEN_DAYS
topic "$BILLING" 6 $SEVEN_DAYS
topic "$SHIPPING" 6 $SEVEN_DAYS
topic "$CATALOG" 3 $SEVEN_DAYS
for t in $ALL; do
  topic "$t.dlt" 1 $THIRTY_DAYS
done

writes catalog "$CATALOG"
reads  catalog catalog literal "$STOCK"

writes order "$ORDER"
reads  order order literal "$INVENTORY" "$BILLING" "$SHIPPING" "$CATALOG"

writes inventory "$INVENTORY" "$STOCK"
reads  inventory inventory literal "$ORDER" "$CATALOG"

writes billing "$BILLING"
reads  billing billing literal "$ORDER"

writes shipping "$SHIPPING"
reads  shipping shipping literal "$ORDER"

reads  notification notification literal "$ORDER" "$SHIPPING"

# ops: sees everything (live feed, dead letters, lag), and may write an event
# topic only to replay a dead letter — the one audited exception to
# "only the owner writes".
# shellcheck disable=SC2046
acl --allow-principal User:ops --operation Read --operation Describe --operation DescribeConfigs \
  --topic commerce. --resource-pattern-type prefixed
acl --allow-principal User:ops --operation Read --operation Describe --group ops- --resource-pattern-type prefixed
acl --allow-principal User:ops --operation Describe --group '*'
acl --allow-principal User:ops --operation Describe --operation DescribeConfigs --cluster
writes ops $ALL

if [ "${INIT_DEV:-false}" = "true" ]; then
  for principal in testkit testkit-chaos intruder; do
    credential "$principal"
  done
  acl --allow-principal User:testkit --operation Read --operation Describe \
    --topic commerce. --resource-pattern-type prefixed
  acl --allow-principal User:testkit --operation Read --operation Describe \
    --group testkit- --resource-pattern-type prefixed
  writes testkit-chaos $ALL
  # intruder: a credential and nothing else.
  echo "kafka-init: dev principals ready"
fi

echo "kafka-init: done"
