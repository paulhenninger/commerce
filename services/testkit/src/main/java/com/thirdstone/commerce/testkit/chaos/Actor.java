package com.thirdstone.commerce.testkit.chaos;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** A problem producer or consumer: runs on its own thread until stopped, counting what happens. */
public abstract class Actor implements Runnable {

    public record View(String id, String kind, String type, String topic, double ratePerSecond, Instant startedAt,
                       boolean running, Map<String, Long> counts, String lastError) {
    }

    final String id;
    final String kind;
    final String type;
    final String topic;
    final double rate;
    final Instant startedAt = Instant.now();
    final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
    volatile boolean running = true;
    volatile String lastError;

    Actor(String id, String kind, String type, String topic, double rate) {
        this.id = id;
        this.kind = kind;
        this.type = type;
        this.topic = topic;
        this.rate = rate;
    }

    void count(String what) {
        counts.computeIfAbsent(what, k -> new AtomicLong()).incrementAndGet();
    }

    void stop() {
        running = false;
    }

    View view() {
        Map<String, Long> snapshot = new TreeMap<>();
        counts.forEach((k, v) -> snapshot.put(k, v.get()));
        return new View(id, kind, type, topic, rate, startedAt, running, snapshot, lastError);
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
