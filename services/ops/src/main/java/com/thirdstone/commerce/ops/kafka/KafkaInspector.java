package com.thirdstone.commerce.ops.kafka;

import com.thirdstone.commerce.ops.OpsProperties;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.web.ApiException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Kafka as the console sees it, through the admin API and short-lived consumers
 * that read without joining any group (so they never disturb a service's group
 * or commit an offset).
 */
@Component
public class KafkaInspector implements DisposableBean {

    public record Broker(int id, String host, int port, boolean controller, int leaderOf) {
    }

    public record TopicInfo(String name, int partitions, long endOffset, long records, int underReplicated,
                            boolean deadLetter) {
    }

    public record GroupInfo(String group, String state, int members, Map<String, Long> lagByTopic,
                            Map<String, List<Integer>> partitionsByMember) {
    }

    public record Cluster(List<Broker> brokers, List<TopicInfo> topics, List<GroupInfo> groups) {
    }

    public record DeadLetter(String topic, int partition, long offset, Instant at, String key, String originalTopic,
                             String originalOffset, String consumerGroup, String exception, String reason,
                             String traceparent, String payload, boolean truncated) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_PAYLOAD = 8192;

    private final Admin admin;
    private final ConsumerFactory<String, byte[]> consumers;
    private final OpsProperties props;

    KafkaInspector(KafkaAdmin kafkaAdmin, ConsumerFactory<String, byte[]> consumers, OpsProperties props) {
        this.admin = Admin.create(kafkaAdmin.getConfigurationProperties());
        this.consumers = consumers;
        this.props = props;
    }

    @Override
    public void destroy() {
        admin.close(Duration.ofSeconds(2));
    }

    public static List<String> allTopics() {
        List<String> topics = new ArrayList<>(Topics.ALL);
        Topics.ALL.forEach(t -> topics.add(Topics.dlt(t)));
        return topics;
    }

    public Cluster cluster() {
        try {
            DescribeClusterResult cluster = admin.describeCluster();
            Collection<Node> nodes = cluster.nodes().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Node controller = cluster.controller().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Map<String, TopicDescription> topics = admin.describeTopics(allTopics()).allTopicNames()
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Map<TopicPartition, Long> end = offsets(topics, OffsetSpec.latest());
            Map<TopicPartition, Long> start = offsets(topics, OffsetSpec.earliest());

            Map<Integer, Integer> leaderOf = new HashMap<>();
            List<TopicInfo> topicInfos = new ArrayList<>();
            for (String name : allTopics()) {
                TopicDescription d = topics.get(name);
                if (d == null) {
                    continue;
                }
                long endSum = 0;
                long records = 0;
                int under = 0;
                for (TopicPartitionInfo p : d.partitions()) {
                    TopicPartition tp = new TopicPartition(name, p.partition());
                    endSum += end.getOrDefault(tp, 0L);
                    records += end.getOrDefault(tp, 0L) - start.getOrDefault(tp, 0L);
                    if (p.isr().size() < p.replicas().size()) {
                        under++;
                    }
                    if (p.leader() != null) {
                        leaderOf.merge(p.leader().id(), 1, Integer::sum);
                    }
                }
                topicInfos.add(new TopicInfo(name, d.partitions().size(), endSum, records, under,
                        name.endsWith(Topics.DLT_SUFFIX)));
            }
            List<Broker> brokers = new ArrayList<>();
            for (Node n : nodes) {
                brokers.add(new Broker(n.id(), n.host(), n.port(), controller != null && controller.id() == n.id(),
                        leaderOf.getOrDefault(n.id(), 0)));
            }
            brokers.sort((a, b) -> Integer.compare(a.id(), b.id()));
            return new Cluster(brokers, topicInfos, groups(end));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("Interrupted");
        } catch (Exception e) {
            throw ApiException.unavailable("Kafka is unavailable: " + e.getClass().getSimpleName());
        }
    }

    private List<GroupInfo> groups(Map<TopicPartition, Long> end) throws Exception {
        List<GroupInfo> groups = new ArrayList<>();
        // The services' groups always (even before they exist), then any other
        // group found on the cluster (the chaos lab's, say), so a misbehaving
        // consumer shows up with its own lag. Ops's own readers use no group.
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(props.consumerGroups());
        try {
            admin.listGroups().all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).stream()
                    .map(org.apache.kafka.clients.admin.GroupListing::groupId).sorted().forEach(names::add);
        } catch (java.util.concurrent.ExecutionException e) {
            // Listing needs Describe on the groups; without it, the known groups still show.
        }
        for (String group : names) {
            // One group at a time: a group that does not exist yet (its service
            // never started) must not hide the others.
            Map<String, ConsumerGroupDescription> described;
            try {
                described = admin.describeConsumerGroups(List.of(group)).all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                described = Map.of();
            }
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Map<String, Long> lag = new LinkedHashMap<>();
            committed.forEach((tp, offset) -> {
                if (offset != null && end.containsKey(tp)) {
                    lag.merge(tp.topic(), Math.max(0, end.get(tp) - offset.offset()), Long::sum);
                }
            });
            ConsumerGroupDescription d = described.get(group);
            Map<String, List<Integer>> byMember = new LinkedHashMap<>();
            if (d != null) {
                d.members().forEach(m -> byMember.put(m.host() + " " + m.consumerId().substring(
                                Math.max(0, m.consumerId().length() - 8)),
                        m.assignment().topicPartitions().stream().map(TopicPartition::partition).sorted().toList()));
            }
            groups.add(new GroupInfo(group, d == null ? "UNKNOWN" : d.groupState().toString(),
                    d == null ? 0 : d.members().size(), lag, byMember));
        }
        return groups;
    }

    private Map<TopicPartition, Long> offsets(Map<String, TopicDescription> topics, OffsetSpec spec) throws Exception {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        topics.values().forEach(d -> d.partitions().forEach(p ->
                request.put(new TopicPartition(d.name(), p.partition()), spec)));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> result = admin.listOffsets(request).all()
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        Map<TopicPartition, Long> out = new HashMap<>();
        result.forEach((tp, info) -> out.put(tp, info.offset()));
        return out;
    }

    /** The newest {@code limit} records of a dead-letter topic, newest first. */
    public List<DeadLetter> deadLetters(String topic, int limit) {
        checkDeadLetterTopic(topic);
        try (Consumer<String, byte[]> consumer = consumers.createConsumer(null, "ops-dlt-reader")) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, TIMEOUT).stream()
                    .map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, TIMEOUT);
            Map<TopicPartition, Long> starts = consumer.beginningOffsets(partitions, TIMEOUT);
            long wanted = 0;
            for (TopicPartition tp : partitions) {
                long from = Math.max(starts.get(tp), ends.get(tp) - limit);
                consumer.seek(tp, from);
                wanted += ends.get(tp) - from;
            }
            List<DeadLetter> out = new ArrayList<>();
            long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
            while (out.size() < wanted && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(300))) {
                    out.add(toDeadLetter(r));
                }
            }
            out.sort((a, b) -> Long.compare(b.offset(), a.offset()));
            return out.size() > limit ? out.subList(0, limit) : out;
        }
    }

    /** One dead letter, with its full payload, for replay. */
    public ConsumerRecord<String, byte[]> read(String topic, int partition, long offset) {
        checkDeadLetterTopic(topic);
        try (Consumer<String, byte[]> consumer = consumers.createConsumer(null, "ops-dlt-reader")) {
            TopicPartition tp = new TopicPartition(topic, partition);
            consumer.assign(List.of(tp));
            consumer.seek(tp, offset);
            long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(300))) {
                    if (r.offset() == offset) {
                        return r;
                    }
                }
            }
        }
        throw ApiException.notFound("Dead letter");
    }

    static void checkDeadLetterTopic(String topic) {
        if (topic == null || !topic.endsWith(Topics.DLT_SUFFIX)
                || !Topics.ALL.contains(topic.substring(0, topic.length() - Topics.DLT_SUFFIX.length()))) {
            throw ApiException.notFound("Dead-letter topic");
        }
    }

    static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static DeadLetter toDeadLetter(ConsumerRecord<String, byte[]> r) {
        byte[] value = r.value() == null ? new byte[0] : r.value();
        boolean truncated = value.length > MAX_PAYLOAD;
        String payload = new String(value, 0, Math.min(value.length, MAX_PAYLOAD), StandardCharsets.UTF_8);
        String message = header(r, "kafka_dlt-exception-message");
        String cause = header(r, "kafka_dlt-exception-cause-fqcn");
        return new DeadLetter(r.topic(), r.partition(), r.offset(), Instant.ofEpochMilli(r.timestamp()), r.key(),
                header(r, "kafka_dlt-original-topic"), header(r, "kafka_dlt-original-offset"),
                header(r, "kafka_dlt-original-consumer-group"),
                cause != null ? cause : header(r, "kafka_dlt-exception-fqcn"),
                message == null ? null : message.length() > 1000 ? message.substring(0, 1000) : message,
                header(r, "traceparent"), payload, truncated);
    }
}
