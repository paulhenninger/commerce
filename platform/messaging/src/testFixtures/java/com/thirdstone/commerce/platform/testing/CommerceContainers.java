package com.thirdstone.commerce.platform.testing;

import com.thirdstone.commerce.platform.events.Topics;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real MySQL and a real Kafka broker for a module's integration tests, started
 * once per test JVM and shared by its test classes. The same versions as the
 * deployed stack (CI checks they agree), with every topic the system uses.
 *
 * <pre>{@code
 * @DynamicPropertySource
 * static void properties(DynamicPropertyRegistry registry) {
 *     CommerceContainers.register(registry);
 * }
 * }</pre>
 */
public final class CommerceContainers {

    public static final String EDGE_SECRET = "test-edge-secret-0123456789abcdef";

    private static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:9.7.4").withCommand("--character-set-server=utf8mb4");
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0");

    static {
        MYSQL.start();
        KAFKA.start();
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : Topics.ALL) {
            topics.add(new NewTopic(topic, 3, (short) 1));
            topics.add(new NewTopic(Topics.dlt(topic), 1, (short) 1));
        }
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(topics).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create the test topics", e);
        }
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", MYSQL::getJdbcUrl);
        registry.add("DB_USER", MYSQL::getUsername);
        registry.add("DB_PASSWORD", MYSQL::getPassword);
        registry.add("DB_OWNER_USER", MYSQL::getUsername);
        registry.add("DB_OWNER_PASSWORD", MYSQL::getPassword);
        registry.add("KAFKA_BOOTSTRAP_SERVERS", KAFKA::getBootstrapServers);
        registry.add("KAFKA_SECURITY_PROTOCOL", () -> "PLAINTEXT");
        registry.add("KAFKA_USERNAME", () -> "unused");
        registry.add("KAFKA_PASSWORD", () -> "unused");
        registry.add("commerce.edge.shared-secret", () -> EDGE_SECRET);
        registry.add("management.server.port", () -> "0");
        registry.add("logging.structured.format.console", () -> "");
        // No collector in tests. Export stays on: switching it off also switches
        // off trace propagation.
        registry.add("logging.level.io.opentelemetry.exporter", () -> "OFF");
    }

    public static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    private CommerceContainers() {
    }
}
