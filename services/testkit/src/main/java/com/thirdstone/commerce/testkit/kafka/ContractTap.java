package com.thirdstone.commerce.testkit.kafka;

import com.thirdstone.commerce.platform.events.Contract;
import com.thirdstone.commerce.platform.events.EventCodec;
import com.thirdstone.commerce.platform.events.EventEnvelope;
import com.thirdstone.commerce.platform.events.InvalidEventException;
import com.thirdstone.commerce.platform.events.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks every event on every topic against its contract, in a consumer group of
 * its own. An invalid event from a real service is a bug (ContractViolations
 * alerts on it); one from the chaos lab is the lab working. They are told apart
 * by the header every chaos actor sets.
 */
@Component
public class ContractTap implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ContractTap.class);

    private final KafkaClients clients;
    private final EventCodec codec;
    private final MeterRegistry registry;
    private final Map<String, Long> violations = new ConcurrentHashMap<>();
    private final Map<String, Long> checked = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread thread;

    ContractTap(KafkaClients clients, EventCodec codec, MeterRegistry registry) {
        this.clients = clients;
        this.codec = codec;
        this.registry = registry;
    }

    public Map<String, Long> violations() {
        return Map.copyOf(violations);
    }

    public Map<String, Long> checked() {
        return Map.copyOf(checked);
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().daemon(true).name("contract-tap").start(this::run);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            try (KafkaConsumer<String, byte[]> consumer = clients.consumer(KafkaClients.Principal.READER,
                    "testkit-tap", Map.of())) {
                consumer.subscribe(Topics.ALL);
                while (running) {
                    for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                        check(r);
                    }
                    consumer.commitAsync();
                }
            } catch (org.apache.kafka.common.errors.InterruptException e) {
                return;
            } catch (RuntimeException e) {
                log.atWarn().log("Contract tap lost Kafka; reconnecting: {}", e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    void check(ConsumerRecord<String, byte[]> r) {
        String producer = r.headers().lastHeader("commerce-chaos") != null ? "chaos" : "real";
        String type = "unparseable";
        String problem = null;
        try {
            EventEnvelope event = codec.parse(r.value());
            type = event.type();
            Class<?> payload = Contract.PAYLOADS.get(type);
            if (payload == null) {
                problem = "unknown type";
                type = "unknown";
            } else {
                codec.payload(event, payload);
                if (!Contract.topicOf(type).equals(r.topic())) {
                    problem = "on the wrong topic";
                }
            }
        } catch (InvalidEventException e) {
            problem = e.getMessage();
        }
        checked.merge(r.topic(), 1L, Long::sum);
        if (problem != null) {
            violations.merge(producer + " " + r.topic() + " " + type, 1L, Long::sum);
            Counter.builder("commerce.contract.violations").tag("topic", r.topic()).tag("type", type)
                    .tag("producer", producer).register(registry).increment();
            if (producer.equals("real")) {
                log.atError().addKeyValue("topic", r.topic()).addKeyValue("type", type)
                        .log("A service wrote an event that breaks its contract: {}", problem);
            }
        }
    }
}
