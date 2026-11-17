package com.thirdstone.commerce.testkit.chaos;

import com.thirdstone.commerce.testkit.kafka.KafkaClients;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A consumer that behaves badly, each in a consumer group of its own
 * ({@code testkit-<type>-<id>}): whatever it does, the services' groups must not
 * notice.
 */
class ProblemConsumer extends Actor {

    enum Type { SLOW, CRASHING, FLAPPING, STUCK }

    private final Type kindOf;
    private final KafkaClients clients;

    ProblemConsumer(String id, Type type, String topic, KafkaClients clients) {
        super(id, "consumer", type.name(), topic, 0);
        this.kindOf = type;
        this.clients = clients;
    }

    String group() {
        return "testkit-" + kindOf.name().toLowerCase() + "-" + id;
    }

    @Override
    public void run() {
        Map<String, Object> config = Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10,
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30_000, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        while (running) {
            try (KafkaConsumer<String, byte[]> consumer = clients.consumer(KafkaClients.Principal.READER, group(), config)) {
                consumer.subscribe(List.of(topic));
                count("joined");
                switch (kindOf) {
                    case SLOW -> {
                        while (running) {
                            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                            for (var ignored : records) {
                                pause(500);
                                count("processed");
                            }
                            consumer.commitSync();
                        }
                    }
                    case CRASHING -> {
                        consumer.poll(Duration.ofSeconds(3));
                        count("crashed");
                        // Gone without committing: the next start reprocesses the same records.
                    }
                    case FLAPPING -> {
                        long until = System.currentTimeMillis() + 3000;
                        while (running && System.currentTimeMillis() < until) {
                            consumer.poll(Duration.ofMillis(300));
                        }
                        count("left");
                    }
                    case STUCK -> {
                        consumer.poll(Duration.ofSeconds(3));
                        count("stopped polling");
                        // Keeps its connection but never polls again: the broker
                        // evicts it once max.poll.interval.ms passes.
                        while (running) {
                            pause(1000);
                        }
                    }
                }
            } catch (RuntimeException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                count("error");
            }
            if (running && kindOf != Type.SLOW) {
                pause(kindOf == Type.CRASHING ? 1000 : 2000);
            }
        }
    }
}
