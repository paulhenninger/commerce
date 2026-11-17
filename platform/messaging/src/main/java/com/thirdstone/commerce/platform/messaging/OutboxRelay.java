package com.thirdstone.commerce.platform.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaOperations;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka, in id order, and deletes them once the broker
 * has acknowledged.
 *
 * <p>Every replica of a service runs one, but only one publishes at a time: each
 * pass first takes a MySQL advisory lock named after the service. Two relays
 * publishing concurrently could interleave two events about the same order; with
 * the lock, per-key order is the order of the outbox ids. If the holder dies its
 * connection closes, MySQL frees the lock, and another replica's next pass takes
 * over — no election, no coordinator.
 *
 * <p>Delivery is at-least-once: a crash after the broker acknowledged but before
 * the delete commits publishes the row again. Consumers absorb that
 * ({@link EventDispatcher}).
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    static final int BATCH = 200;
    private static final long POLL_MILLIS = 500;
    private static final long ACK_TIMEOUT_SECONDS = 10;
    private static final long MAX_BACKOFF_MILLIS = 5000;
    private static final long SAMPLE_MILLIS = 5000;

    private final DataSource dataSource;
    private final KafkaOperations<String, byte[]> kafka;
    private final OutboxMetrics metrics;
    private final String lockName;
    private final Semaphore wakeups = new Semaphore(0);

    private volatile boolean running;
    private Thread thread;

    public OutboxRelay(DataSource dataSource, KafkaOperations<String, byte[]> kafka, OutboxMetrics metrics,
                       String application) {
        this.dataSource = dataSource;
        this.kafka = kafka;
        this.metrics = metrics;
        // Advisory locks are global to the MySQL server, not to a database.
        this.lockName = "outbox:" + application;
    }

    /** Called after a transaction that wrote to the outbox commits. */
    public void wake() {
        wakeups.release();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().name("outbox-relay").daemon(true).start(this::run);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                // A pass in flight finishes or is abandoned; whatever it did not
                // delete is published again by the next relay to take the lock.
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        metrics.leader(false);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        long backoff = 0;
        long lastSample = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastSample >= SAMPLE_MILLIS) {
                    lastSample = now;
                    metrics.sample();
                }
                int published = pass();
                backoff = 0;
                if (published < BATCH) {
                    // Either a commit wakes us or the poll picks up what a crash,
                    // an outage or another replica's commit left behind.
                    if (wakeups.tryAcquire(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                        wakeups.drainPermits();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                metrics.leader(false);
                backoff = Math.min(MAX_BACKOFF_MILLIS, backoff == 0 ? 250 : backoff * 2);
                log.atWarn().addKeyValue("retryInMs", backoff)
                        .log("Outbox relay could not publish; events stay in the outbox: {}", e.toString());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private record Row(long id, String topic, String key, String type, byte[] payload, String traceparent) {
    }

    /** One batch. Returns how many rows were published; 0 if another replica holds the lock. */
    int pass() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            if (!lock(c)) {
                metrics.leader(false);
                return 0;
            }
            metrics.leader(true);
            boolean clean = false;
            try {
                c.setAutoCommit(false);
                List<Row> rows = read(c);
                if (!rows.isEmpty()) {
                    send(rows);
                    delete(c, rows);
                }
                c.commit();
                clean = true;
                return rows.size();
            } finally {
                release(c, clean);
            }
        }
    }

    private boolean lock(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            s.setString(1, lockName);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    private void release(Connection c, boolean clean) {
        try {
            if (!clean) {
                c.rollback();
            }
            c.setAutoCommit(true);
            try (PreparedStatement s = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                s.setString(1, lockName);
                s.execute();
            }
        } catch (SQLException e) {
            // The lock belongs to the connection, and a pooled connection outlives
            // this method: one that could not release must not go back to the pool
            // still holding it, or no replica could ever publish again.
            try {
                c.abort(Runnable::run);
            } catch (SQLException ignored) {
                // Already dead: MySQL released the lock with it.
            }
        }
    }

    private List<Row> read(Connection c) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT id, topic, record_key, event_type, payload, traceparent
                FROM outbox_events ORDER BY id LIMIT ?""")) {
            s.setInt(1, BATCH);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getBytes(5), rs.getString(6)));
                }
            }
        }
        return rows;
    }

    private void send(List<Row> rows) throws Exception {
        List<CompletableFuture<?>> acks = new ArrayList<>(rows.size());
        Map<String, Integer> perTopic = new HashMap<>();
        for (Row row : rows) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
            record.headers().add(new RecordHeader("event-type", row.type().getBytes(StandardCharsets.UTF_8)));
            if (row.traceparent() != null) {
                record.headers().add(new RecordHeader("traceparent",
                        row.traceparent().getBytes(StandardCharsets.UTF_8)));
            }
            acks.add(kafka.send(record));
            perTopic.merge(row.topic(), 1, Integer::sum);
        }
        // Nothing is deleted until the broker has every record of the batch.
        CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        perTopic.forEach(metrics::published);
    }

    private void delete(Connection c, List<Row> rows) throws SQLException {
        // By id, never by range: a transaction that took a lower id can commit
        // after this batch was read, and a range delete would erase its event
        // unpublished.
        try (PreparedStatement s = c.prepareStatement("DELETE FROM outbox_events WHERE id = ?")) {
            for (Row row : rows) {
                s.setLong(1, row.id());
                s.addBatch();
            }
            s.executeBatch();
        }
    }
}
