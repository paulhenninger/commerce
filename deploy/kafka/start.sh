#!/bin/bash
# Starts one Kafka node (KRaft, broker and controller in one process) with
# authentication and authorisation on.
#
# The image's own start-up builds its configuration from KAFKA_* variables but
# cannot seed the first SCRAM credential, which has to exist before any client
# can sign in to create the others. So this script writes the configuration,
# formats the storage with the administrator's credential in it (first start
# only), and starts the server.
#
#   CLIENT      :9092  services and tools — SASL/SCRAM-SHA-512, one principal each
#   BROKER      :9093  broker to broker   — SASL/PLAIN with the broker credential
#   CONTROLLER  :9095  the KRaft quorum   — SASL/PLAIN with the broker credential
#
# Nothing is allowed unless an ACL says so (deploy/kafka/init.sh creates them);
# only the brokers and the kafka-init job are super users.
set -euo pipefail

: "${NODE_ID:?}" "${CLUSTER_ID:?}" "${QUORUM_VOTERS:?}" "${BROKER_PASSWORD:?}" "${INIT_PASSWORD:?}"
HOST="${ADVERTISED_HOST:-$(hostname)}"
REPLICATION="${REPLICATION_FACTOR:-1}"
MIN_ISR="${MIN_INSYNC_REPLICAS:-1}"

# The passwords are written into JAAS strings below; keep them to characters that
# need no escaping there.
for secret in "$BROKER_PASSWORD" "$INIT_PASSWORD"; do
  [[ "$secret" =~ ^[A-Za-z0-9_.-]{12,}$ ]] || {
    echo "kafka: passwords must be 12+ characters from A-Z a-z 0-9 _ . -" >&2; exit 1; }
done

mkdir -p /tmp/kafka
CONFIG=/tmp/kafka/server.properties
PLAIN="org.apache.kafka.common.security.plain.PlainLoginModule required username=\"broker\" password=\"$BROKER_PASSWORD\" user_broker=\"$BROKER_PASSWORD\";"

cat > "$CONFIG" <<EOF
process.roles=broker,controller
node.id=$NODE_ID
controller.quorum.voters=$QUORUM_VOTERS
log.dirs=/var/lib/kafka/data

listeners=CLIENT://:9092,BROKER://:9093,CONTROLLER://:9095
advertised.listeners=CLIENT://$HOST:9092,BROKER://$HOST:9093
listener.security.protocol.map=CLIENT:SASL_PLAINTEXT,BROKER:SASL_PLAINTEXT,CONTROLLER:SASL_PLAINTEXT
inter.broker.listener.name=BROKER
controller.listener.names=CONTROLLER

sasl.enabled.mechanisms=SCRAM-SHA-512,PLAIN
sasl.mechanism.inter.broker.protocol=PLAIN
sasl.mechanism.controller.protocol=PLAIN
listener.name.client.sasl.enabled.mechanisms=SCRAM-SHA-512
listener.name.client.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required;
listener.name.broker.sasl.enabled.mechanisms=PLAIN
listener.name.broker.plain.sasl.jaas.config=$PLAIN
listener.name.controller.sasl.enabled.mechanisms=PLAIN
listener.name.controller.plain.sasl.jaas.config=$PLAIN

# Deny by default: a principal with no ACL can do nothing.
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
allow.everyone.if.no.acl.found=false
super.users=User:broker;User:kafka-init

# Topics exist only because kafka-init created them: a typo in a topic name is
# an error, not a new topic.
auto.create.topics.enable=false
delete.topic.enable=true
message.max.bytes=1048576
num.partitions=6
default.replication.factor=$REPLICATION
min.insync.replicas=$MIN_ISR
offsets.topic.replication.factor=$REPLICATION
transaction.state.log.replication.factor=$REPLICATION
transaction.state.log.min.isr=$MIN_ISR
share.coordinator.state.topic.replication.factor=$REPLICATION
share.coordinator.state.topic.min.isr=$MIN_ISR
# A clean leader only: never trade lost events for availability.
unclean.leader.election.enable=false
group.initial.rebalance.delay.ms=1000
log.retention.hours=168
EOF

# First start only (--ignore-formatted): the storage is stamped with the cluster
# id and the kafka-init credential, the one login that can create the rest.
/opt/kafka/bin/kafka-storage.sh format --ignore-formatted -t "$CLUSTER_ID" -c "$CONFIG" \
  --add-scram "SCRAM-SHA-512=[name=kafka-init,password=$INIT_PASSWORD]"

export LOG_DIR=/tmp/kafka/logs
export KAFKA_LOG4J_OPTS="-Dlog4j2.configurationFile=/etc/kafka/docker/log4j2.yaml"
export KAFKA_HEAP_OPTS="${KAFKA_HEAP_OPTS:--Xms256m -Xmx256m}"
exec /opt/kafka/bin/kafka-server-start.sh "$CONFIG"
