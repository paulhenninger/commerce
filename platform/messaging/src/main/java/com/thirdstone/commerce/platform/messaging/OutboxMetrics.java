package com.thirdstone.commerce.platform.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How far behind publishing is. An outbox that grows, or whose oldest row keeps
 * ageing, means events are not reaching Kafka — the alert that says "sagas have
 * stopped" before any customer notices.
 */
public class OutboxMetrics {

    private final DataSource dataSource;
    private final MeterRegistry registry;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeMillis = new AtomicLong();
    private final AtomicInteger leader = new AtomicInteger();

    public OutboxMetrics(DataSource dataSource, MeterRegistry registry) {
        this.dataSource = dataSource;
        this.registry = registry;
        Gauge.builder("commerce.outbox.pending", pending, AtomicLong::get)
                .description("Events waiting in the outbox").register(registry);
        Gauge.builder("commerce.outbox.oldest.age", oldestAgeMillis, v -> v.get() / 1000.0)
                .baseUnit("seconds").description("Age of the oldest event waiting in the outbox").register(registry);
        Gauge.builder("commerce.outbox.relay.leader", leader, AtomicInteger::get)
                .description("1 on the instance whose relay published last").register(registry);
    }

    void published(String topic, int count) {
        Counter.builder("commerce.outbox.published").tag("topic", topic).register(registry).increment(count);
    }

    void leader(boolean isLeader) {
        leader.set(isLeader ? 1 : 0);
    }

    /** Reads the table's size and oldest row; called every few seconds by the relay thread. */
    void sample() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT COUNT(*), COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(6)), 0)
                     FROM outbox_events""")) {
            rs.next();
            pending.set(rs.getLong(1));
            oldestAgeMillis.set(Math.max(0, rs.getLong(2) / 1000));
        }
    }
}
