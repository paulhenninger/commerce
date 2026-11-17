package com.thirdstone.commerce.platform.testing;

import com.thirdstone.commerce.platform.events.Contract;
import com.thirdstone.commerce.platform.events.EventCodec;
import com.thirdstone.commerce.platform.events.EventEnvelope;
import com.thirdstone.commerce.platform.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.awaitility.Awaitility;

/**
 * The rest of the system, as seen from one service under test: publishes the
 * events other services would send, and records everything written to every
 * topic so a test can wait for what the service announces.
 */
public final class EventBus implements AutoCloseable {

    private static final EventBus INSTANCE = new EventBus();

    private final EventCodec codec = new EventCodec(JsonMapper.builder().build());
    private final KafkaProducer<String, byte[]> producer;
    private final List<ConsumerRecord<String, byte[]>> records = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    public static EventBus shared() {
        return INSTANCE;
    }

    private EventBus() {
        String servers = CommerceContainers.bootstrapServers();
        producer = new KafkaProducer<>(Map.of("bootstrap.servers", servers, "acks", "all"),
                new StringSerializer(), new ByteArraySerializer());
        List<String> topics = new ArrayList<>(Topics.ALL);
        Topics.ALL.forEach(t -> topics.add(Topics.dlt(t)));
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of("bootstrap.servers", servers,
                "group.id", "test-tap-" + UUID.randomUUID(), "auto.offset.reset", "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(topics);
        Thread.ofPlatform().daemon(true).name("test-tap").start(() -> {
            try (consumer) {
                while (running) {
                    consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                }
            }
        });
    }

    /** Publishes an event as its owning service would; returns it. */
    public EventEnvelope publish(String type, String aggregateId, Object payload) {
        EventEnvelope event = codec.create(type, aggregateId, "test", payload);
        publishRaw(Contract.topicOf(type), aggregateId, codec.serialize(event));
        return event;
    }

    public void publishRaw(String topic, String key, byte[] value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits for an event of this type about this aggregate, and returns its payload. */
    public <T> T await(String type, String aggregateId, Class<T> payloadType) {
        return awaitMatching(type, aggregateId, payloadType, p -> true);
    }

    public <T> T awaitMatching(String type, String aggregateId, Class<T> payloadType, Predicate<T> matching) {
        List<T> found = new ArrayList<>();
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            found.clear();
            found.addAll(events(type, aggregateId, payloadType).stream().filter(matching).toList());
            return !found.isEmpty();
        });
        return found.getLast();
    }

    /** Every event of this type about this aggregate seen so far, oldest first. */
    public <T> List<T> events(String type, String aggregateId, Class<T> payloadType) {
        List<T> out = new ArrayList<>();
        String topic = Contract.topicOf(type);
        for (ConsumerRecord<String, byte[]> record : records) {
            if (!record.topic().equals(topic) || !aggregateId.equals(record.key())) {
                continue;
            }
            EventEnvelope event = codec.parse(record.value());
            if (event.type().equals(type)) {
                out.add(codec.payload(event, payloadType));
            }
        }
        return out;
    }

    /** The types of every event about this aggregate on a topic, in order. */
    public List<String> types(String topic, String aggregateId) {
        List<String> out = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            if (record.topic().equals(topic) && aggregateId.equals(record.key())) {
                out.add(codec.parse(record.value()).type());
            }
        }
        return out;
    }

    public List<ConsumerRecord<String, byte[]>> deadLetters(String sourceTopic, String key) {
        return records.stream().filter(r -> r.topic().equals(Topics.dlt(sourceTopic)) && key.equals(r.key())).toList();
    }

    @Override
    public void close() {
        running = false;
        producer.close();
    }
}
