package com.thirdstone.commerce.platform.messaging;

import com.thirdstone.commerce.platform.events.EventCodec;
import com.thirdstone.commerce.platform.events.EventEnvelope;
import com.thirdstone.commerce.platform.messaging.MessagingTestApp.Applied;
import com.thirdstone.commerce.platform.messaging.MessagingTestApp.Happened;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The guarantees the rest of the system is built on, proved against real MySQL
 * and a real Kafka broker.
 */
@SpringBootTest(classes = MessagingTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MessagingIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.4").withCommand("--character-set-server=utf8mb4");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0");

    static {
        MYSQL.start();
        KAFKA.start();
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(MessagingTestApp.TOPIC, 3, (short) 1),
                    new NewTopic(MessagingTestApp.TOPIC + ".dlt", 1, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", MYSQL::getJdbcUrl);
        registry.add("DB_USER", MYSQL::getUsername);
        registry.add("DB_PASSWORD", MYSQL::getPassword);
        registry.add("DB_OWNER_USER", MYSQL::getUsername);
        registry.add("DB_OWNER_PASSWORD", MYSQL::getPassword);
        registry.add("KAFKA_BOOTSTRAP_SERVERS", KAFKA::getBootstrapServers);
        registry.add("KAFKA_SECURITY_PROTOCOL", () -> "PLAINTEXT");
        registry.add("KAFKA_USERNAME", () -> "unused");
        registry.add("KAFKA_PASSWORD", () -> "unused");
    }

    @Autowired Outbox outbox;
    @Autowired OutboxRelay relay;
    @Autowired OutboxMetrics metrics;
    @Autowired EventCodec codec;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired KafkaTemplate<String, byte[]> kafka;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MeterRegistry registry;
    @Autowired Tracer tracer;
    @Autowired ProcessedEventsPurge purge;
    @Autowired MessagingTestApp.Listener listener;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private EventEnvelope append(String key, int seq, String mode) {
        return tx().execute(s -> outbox.append(MessagingTestApp.TOPIC, MessagingTestApp.TYPE, key,
                new Happened(key, seq, mode)));
    }

    private List<Applied> appliedFor(String key) {
        return listener.applied.stream().filter(a -> a.key().equals(key)).toList();
    }

    @Test
    void eventIsPublishedOnceItsTransactionCommits() {
        String key = "commit-" + UUID.randomUUID();
        EventEnvelope event = append(key, 1, null);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(appliedFor(key)).hasSize(1));
        assertThat(appliedFor(key).getFirst().eventId()).isEqualTo(event.eventId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE record_key = ?").param(key)
                        .query(Integer.class).single()).isZero());
    }

    @Test
    void eventOfARolledBackTransactionIsNeverPublished() {
        String rolledBack = "rollback-" + UUID.randomUUID();
        tx().executeWithoutResult(s -> {
            outbox.append(MessagingTestApp.TOPIC, MessagingTestApp.TYPE, rolledBack, new Happened(rolledBack, 1, null));
            s.setRollbackOnly();
        });
        // A later event on the same key arriving proves the first was not merely slow.
        append(rolledBack, 2, null);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(appliedFor(rolledBack)).isNotEmpty());
        assertThat(appliedFor(rolledBack)).extracting(Applied::seq).containsExactly(2);
    }

    @Test
    void appendOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outbox.append(MessagingTestApp.TOPIC, MessagingTestApp.TYPE, "k", new Happened("k", 1, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void twoRelaysNeverPublishTheSameRowAndKeepOrder() {
        // A second replica's relay, competing for the same advisory lock.
        OutboxRelay second = new OutboxRelay(dataSource, kafka, new OutboxMetrics(dataSource, registry), "msgtest");
        second.start();
        try {
            String key = "order-" + UUID.randomUUID();
            int count = 500;
            double publishedBefore = published();
            for (int i = 1; i <= count; i++) {
                append(key, i, null);
            }
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(appliedFor(key)).hasSize(count));
            List<Integer> seqs = appliedFor(key).stream().map(Applied::seq).toList();
            assertThat(seqs).isSorted().doesNotHaveDuplicates();
            // Published exactly once each, by whichever relay held the lock.
            assertThat(published() - publishedBefore).isEqualTo(count);
        } finally {
            second.stop();
        }
    }

    private double published() {
        var counter = registry.find("commerce.outbox.published").tag("topic", MessagingTestApp.TOPIC).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void duplicateEventIsAppliedOnce() throws Exception {
        String key = "dup-" + UUID.randomUUID();
        EventEnvelope event = codec.create(MessagingTestApp.TYPE, key, "msgtest", new Happened(key, 1, null));
        byte[] bytes = codec.serialize(event);
        for (int i = 0; i < 3; i++) {
            kafka.send(MessagingTestApp.TOPIC, key, bytes).get();
        }
        EventEnvelope marker = codec.create(MessagingTestApp.TYPE, key, "msgtest", new Happened(key, 2, null));
        kafka.send(MessagingTestApp.TOPIC, key, codec.serialize(marker)).get();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(appliedFor(key)).extracting(Applied::seq).contains(2));
        assertThat(appliedFor(key)).extracting(Applied::seq).containsExactly(1, 2);
    }

    @Test
    void malformedRecordIsDeadLetteredWithoutRetriesAndTheNextRecordIsProcessed() throws Exception {
        String key = "bad-" + UUID.randomUUID();
        kafka.send(MessagingTestApp.TOPIC, key, "{not an event".getBytes(StandardCharsets.UTF_8)).get();
        EventEnvelope next = codec.create(MessagingTestApp.TYPE, key, "msgtest", new Happened(key, 1, null));
        kafka.send(MessagingTestApp.TOPIC, key, codec.serialize(next)).get();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(appliedFor(key)).hasSize(1));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(deadLettersFor(key)).hasSize(1));
        ConsumerRecord<String, byte[]> dead = deadLettersFor(key).getFirst();
        assertThat(new String(dead.value(), StandardCharsets.UTF_8)).isEqualTo("{not an event");
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(MessagingTestApp.TOPIC);
        assertThat(header(dead, "kafka_dlt-exception-fqcn")).contains("ListenerExecutionFailedException");
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("InvalidEventException");
        assertThat(registry.find("commerce.events.dead.lettered").tag("reason", "invalid").counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void contractBreakingPayloadIsDeadLettered() throws Exception {
        String key = "contract-" + UUID.randomUUID();
        // A well-formed envelope whose payload lacks the required field.
        String json = new String(codec.serialize(codec.create(MessagingTestApp.TYPE, key, "msgtest",
                Map.of("seq", 1))), StandardCharsets.UTF_8);
        kafka.send(MessagingTestApp.TOPIC, key, json.getBytes(StandardCharsets.UTF_8)).get();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(deadLettersFor(key)).hasSize(1));
        assertThat(appliedFor(key)).isEmpty();
    }

    @Test
    void handlerThatKeepsFailingIsRetriedThreeTimesThenDeadLettered() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        int before = listener.failingAttempts.get();
        EventEnvelope poison = codec.create(MessagingTestApp.TYPE, key, "msgtest", new Happened(key, 1, "fail"));
        kafka.send(MessagingTestApp.TOPIC, key, codec.serialize(poison)).get();
        EventEnvelope next = codec.create(MessagingTestApp.TYPE, key, "msgtest", new Happened(key, 2, null));
        kafka.send(MessagingTestApp.TOPIC, key, codec.serialize(next)).get();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(deadLettersFor(key)).hasSize(1));
        assertThat(listener.failingAttempts.get() - before).isEqualTo(1 + KafkaErrorHandling.RETRIES);
        // The partition moved on, and the failed attempts left no trace behind.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(
                () -> assertThat(appliedFor(key)).extracting(Applied::seq).containsExactly(2));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM processed_events WHERE event_id = ?").param(poison.eventId())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void anEventThatFailsBecauseTheDatabaseIsAwayIsRetriedUntilItAppliesNotDeadLettered() throws Exception {
        String key = "transient-" + UUID.randomUUID();
        EventEnvelope event = codec.create(MessagingTestApp.TYPE, key, "msgtest", new Happened(key, 1, "transient"));
        kafka.send(MessagingTestApp.TOPIC, key, codec.serialize(event)).get();
        // Five failures (more than the three retries a handler bug gets), then success.
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(appliedFor(key)).hasSize(1));
        assertThat(listener.transientAttempts.get()).isGreaterThan(KafkaErrorHandling.RETRIES + 1);
        assertThat(deadLettersFor(key)).isEmpty();
    }

    @Test
    void transientInfrastructureIsToldApartFromBadEventsAndBugs() {
        assertThat(KafkaErrorHandling.transientInfrastructure(new org.springframework.kafka.listener.ListenerExecutionFailedException(
                "x", new org.springframework.transaction.CannotCreateTransactionException("no connection")))).isTrue();
        assertThat(KafkaErrorHandling.transientInfrastructure(new IllegalStateException("bug"))).isFalse();
        assertThat(KafkaErrorHandling.transientInfrastructure(new com.thirdstone.commerce.platform.events.InvalidEventException(
                "bad"))).isFalse();
    }

    @Test
    void unknownEventTypeIsSkipped() throws Exception {
        String key = "unknown-" + UUID.randomUUID();
        kafka.send(MessagingTestApp.TOPIC, key, codec.serialize(
                codec.create("test.something-new", key, "msgtest", Map.of("a", 1)))).get();
        kafka.send(MessagingTestApp.TOPIC, key, codec.serialize(
                codec.create(MessagingTestApp.TYPE, key, "msgtest", new Happened(key, 1, null)))).get();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(appliedFor(key)).hasSize(1));
        assertThat(deadLettersFor(key)).isEmpty();
        assertThat(registry.find("commerce.events.consumed").tag("type", "unhandled").counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void traceOfTheAppendingThreadContinuesInTheConsumer() {
        String key = "trace-" + UUID.randomUUID();
        Span span = tracer.nextSpan().name("test-request").start();
        String traceId;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            traceId = span.context().traceId();
            append(key, 1, null);
        } finally {
            span.end();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(appliedFor(key)).hasSize(1));
        assertThat(appliedFor(key).getFirst().traceId()).isEqualTo(traceId);
    }

    @Test
    void outboxBacklogIsMeasured() throws Exception {
        relay.stop();
        try {
            String key = "metric-" + UUID.randomUUID();
            append(key, 1, null);
            Thread.sleep(50);
            metrics.sample();
            assertThat(registry.get("commerce.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(1);
            assertThat(registry.get("commerce.outbox.oldest.age").gauge().value()).isGreaterThan(0);
        } finally {
            relay.start();
        }
    }

    @Test
    void oldProcessedEventIdsArePurgedAndRecentOnesKept() {
        jdbc.sql("INSERT INTO processed_events VALUES ('msgtest', 'old-1', UTC_TIMESTAMP(6) - INTERVAL 9 DAY)").update();
        jdbc.sql("INSERT INTO processed_events VALUES ('msgtest', 'recent-1', UTC_TIMESTAMP(6) - INTERVAL 6 DAY)").update();
        purge.purge();
        assertThat(jdbc.sql("SELECT event_id FROM processed_events WHERE event_id IN ('old-1', 'recent-1')")
                .query(String.class).list()).containsExactly("recent-1");
    }

    private List<ConsumerRecord<String, byte[]>> deadLettersFor(String key) {
        return listener.deadLetters.stream().filter(r -> key.equals(r.key())).toList();
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
