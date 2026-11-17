package com.thirdstone.commerce.ops.kafka;

import com.thirdstone.commerce.platform.events.Topics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What is happening on Kafka right now, streamed to every open console within a
 * second: events per type per second and the latest events' metadata (type,
 * aggregate, topic, time, trace — never payloads).
 *
 * <p>It reads every partition of every topic directly (assign, from the end, no
 * consumer group): each ops replica sees everything, and nothing it does can
 * affect a service's group.
 */
@Component
public class LiveFeed implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LiveFeed.class);
    private static final int MAX_CLIENTS = 50;

    public record LiveEvent(Instant at, String topic, String type, String key, String traceId) {
    }

    public record Tick(Instant at, Map<String, Integer> perSecond, List<LiveEvent> events) {
    }

    private final ConsumerFactory<String, byte[]> consumers;
    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
    private final Deque<LiveEvent> recent = new ArrayDeque<>();
    private volatile boolean running;
    private Thread thread;

    LiveFeed(ConsumerFactory<String, byte[]> consumers) {
        this.consumers = consumers;
    }

    public SseEmitter subscribe() {
        if (clients.size() >= MAX_CLIENTS) {
            throw com.thirdstone.commerce.platform.web.ApiException.unavailable("Too many live viewers");
        }
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(e -> clients.remove(emitter));
        clients.add(emitter);
        synchronized (recent) {
            send(emitter, new Tick(Instant.now(), Map.of(), new ArrayList<>(recent)));
        }
        return emitter;
    }

    @Override
    public synchronized void start() {
        running = true;
        thread = Thread.ofPlatform().daemon(true).name("ops-live-feed").start(this::run);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        clients.forEach(SseEmitter::complete);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            try (Consumer<String, byte[]> consumer = consumers.createConsumer(null, "ops-live")) {
                List<TopicPartition> partitions = new ArrayList<>();
                for (String topic : KafkaInspector.allTopics()) {
                    consumer.partitionsFor(topic, Duration.ofSeconds(10))
                            .forEach(p -> partitions.add(new TopicPartition(topic, p.partition())));
                }
                consumer.assign(partitions);
                consumer.seekToEnd(partitions);
                long nextTick = System.currentTimeMillis() + 1000;
                Map<String, Integer> counts = new TreeMap<>();
                List<LiveEvent> fresh = new ArrayList<>();
                while (running) {
                    for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(250))) {
                        LiveEvent event = toEvent(r);
                        counts.merge(event.type(), 1, Integer::sum);
                        if (fresh.size() < 50) {
                            fresh.add(event);
                        }
                        synchronized (recent) {
                            recent.addFirst(event);
                            while (recent.size() > 200) {
                                recent.removeLast();
                            }
                        }
                    }
                    if (System.currentTimeMillis() >= nextTick) {
                        Tick tick = new Tick(Instant.now(), Map.copyOf(counts), List.copyOf(fresh));
                        clients.forEach(c -> send(c, tick));
                        counts.clear();
                        fresh.clear();
                        nextTick += 1000;
                    }
                }
            } catch (org.apache.kafka.common.errors.InterruptException e) {
                return;
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                log.atWarn().log("Live feed lost Kafka; reconnecting: {}", e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    static LiveEvent toEvent(ConsumerRecord<String, byte[]> r) {
        String type = KafkaInspector.header(r, "event-type");
        if (r.topic().endsWith(Topics.DLT_SUFFIX)) {
            type = "dead-letter";
        } else if (type == null || !type.matches("^[a-z][a-z0-9.-]{0,63}$")) {
            // Whatever a misbehaving producer wrote, it is not shown verbatim.
            type = "unlabelled";
        }
        String traceparent = KafkaInspector.header(r, "traceparent");
        String traceId = traceparent != null && traceparent.length() >= 35 ? traceparent.substring(3, 35) : null;
        String key = r.key() == null ? null : r.key().length() > 64 ? r.key().substring(0, 64) : r.key();
        return new LiveEvent(Instant.ofEpochMilli(r.timestamp()), r.topic(), type, key, traceId);
    }

    private void send(SseEmitter emitter, Tick tick) {
        try {
            emitter.send(SseEmitter.event().name("tick").data(tick));
        } catch (IOException | IllegalStateException e) {
            clients.remove(emitter);
        }
    }
}
