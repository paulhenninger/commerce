package com.thirdstone.commerce.testkit.chaos;

import com.thirdstone.commerce.testkit.kafka.KafkaClients;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * UNAUTHORISED: a client that should not be here. It signs in (as
 * {@code intruder}, which has no rights) and tries to write a topic, read one,
 * and join a service's consumer group. Every attempt should be refused by the
 * broker; anything that gets through is counted as "allowed" — a security bug.
 */
class Intruder extends Actor {

    private final KafkaClients clients;

    Intruder(String id, String topic, double rate, KafkaClients clients) {
        super(id, "producer", "UNAUTHORISED", topic, rate);
        this.clients = clients;
    }

    @Override
    public void run() {
        long interval = Math.max(500, Math.round(1000.0 / Math.max(rate, 0.01)));
        while (running) {
            write();
            read(null);
            read("order");
            pause(interval);
        }
    }

    private void write() {
        try (KafkaProducer<String, byte[]> producer = clients.producer(KafkaClients.Principal.INTRUDER,
                Map.of("max.block.ms", 3000))) {
            producer.send(new ProducerRecord<>(topic, "intruder", "{}".getBytes(StandardCharsets.UTF_8)))
                    .get(5, TimeUnit.SECONDS);
            count("write allowed");
        } catch (Exception e) {
            count("write refused");
        }
    }

    private void read(String group) {
        String what = group == null ? "read" : "join group " + group;
        try (KafkaConsumer<String, byte[]> consumer = clients.consumer(KafkaClients.Principal.INTRUDER, group,
                Map.of("default.api.timeout.ms", 3000))) {
            consumer.subscribe(List.of(topic));
            consumer.poll(Duration.ofSeconds(2));
            // Reaching here without an exception may only mean nothing was
            // returned yet; an authorisation failure is thrown by poll.
            count(what + " not refused yet");
        } catch (Exception e) {
            count(what + " refused");
        }
    }
}
