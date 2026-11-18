package com.thirdstone.commerce.testkit.chaos;

import com.thirdstone.commerce.platform.data.Ids;
import com.thirdstone.commerce.platform.events.BillingEvents;
import com.thirdstone.commerce.platform.events.EventCodec;
import com.thirdstone.commerce.platform.events.EventEnvelope;
import com.thirdstone.commerce.platform.events.InventoryEvents;
import com.thirdstone.commerce.platform.events.Line;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.ShippingEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.testkit.kafka.KafkaClients;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Writes bad data, as the {@code testkit-chaos} principal: a producer the broker
 * trusts, gone wrong. Every record carries {@code commerce-chaos} so the contract
 * tap can tell the lab from a real bug.
 */
class ProblemProducer extends Actor {

    enum Type { MALFORMED, INVALID_SCHEMA, UNKNOWN_TYPE, DUPLICATE, ORPHAN, OVERSIZED, FLOOD }

    private final Type kindOf;
    private final KafkaClients clients;
    private final EventCodec codec;
    private EventEnvelope duplicate;
    /** DUPLICATE: real records to deliver again, byte for byte, in a loop. */
    private final List<com.thirdstone.commerce.testkit.kafka.ContractTap.Recent> replay;
    private int next;

    ProblemProducer(String id, Type type, String topic, double rate, KafkaClients clients, EventCodec codec,
                    List<com.thirdstone.commerce.testkit.kafka.ContractTap.Recent> replay) {
        super(id, "producer", type.name(), topic, rate);
        this.replay = replay;
        this.kindOf = type;
        this.clients = clients;
        this.codec = codec;
    }

    @Override
    public void run() {
        // Big enough to hand a 2 MiB record to the broker, so the broker (not this
        // client) is what refuses it.
        Map<String, Object> extra = kindOf == Type.OVERSIZED
                ? Map.of(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 4 * 1024 * 1024, ProducerConfig.BUFFER_MEMORY_CONFIG, 64L * 1024 * 1024)
                : Map.of();
        try (KafkaProducer<String, byte[]> producer = clients.producer(KafkaClients.Principal.CHAOS, extra)) {
            long interval = Math.max(1, Math.round(1000.0 / Math.max(rate, 0.01)));
            int perTick = rate > 100 ? (int) Math.ceil(rate / 100) : 1;
            long tick = rate > 100 ? 10 : interval;
            while (running) {
                for (int i = 0; i < perTick && running; i++) {
                    send(producer);
                }
                pause(tick);
            }
        } catch (RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            count("crashed");
        }
    }

    private void send(KafkaProducer<String, byte[]> producer) {
        String key = Ids.next();
        byte[] value = switch (kindOf) {
            case MALFORMED -> malformed();
            case INVALID_SCHEMA -> codec.serialize(codec.create(orphanType(), key, "chaos", Map.of("unexpected", true)));
            case UNKNOWN_TYPE -> codec.serialize(codec.create("chaos.something-new", key, "chaos", Map.of("x", 1)));
            case DUPLICATE -> {
                if (!replay.isEmpty()) {
                    // A real event the services have already applied, delivered again.
                    var again = replay.get(next++ % replay.size());
                    key = again.key();
                    yield again.value();
                }
                // Nothing real seen yet: the same harmless event, again and again.
                if (duplicate == null) {
                    duplicate = codec.create(orphanType(), key, "chaos", orphanPayload(key));
                }
                key = duplicate.aggregateId();
                yield codec.serialize(duplicate);
            }
            case ORPHAN, FLOOD -> codec.serialize(codec.create(orphanType(), key, "chaos", orphanPayload(key)));
            case OVERSIZED -> new byte[2 * 1024 * 1024];
        };
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
        record.headers().add(new RecordHeader("commerce-chaos", kindOf.name().getBytes(StandardCharsets.UTF_8)));
        try {
            producer.send(record, (metadata, error) -> {
                if (error == null) {
                    count("sent");
                } else {
                    count("refused " + error.getClass().getSimpleName());
                    lastError = error.getMessage();
                }
            });
        } catch (RuntimeException e) {
            count("refused " + e.getClass().getSimpleName());
            lastError = e.getMessage();
        }
    }

    private static byte[] malformed() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextBoolean()) {
            byte[] noise = new byte[16 + random.nextInt(200)];
            random.nextBytes(noise);
            return noise;
        }
        return "{\"eventId\":\"truncated\",\"type\":\"order.pla".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A valid event about an order that does not exist, of a type the topic's
     * consumers handle without side effects outside their own tables.
     */
    private String orphanType() {
        return switch (topic) {
            case Topics.ORDER -> OrderEvents.RETURNED;
            case Topics.INVENTORY -> InventoryEvents.RESERVED;
            case Topics.STOCK -> InventoryEvents.STOCK_CHANGED;
            case Topics.BILLING -> BillingEvents.FAILED;
            case Topics.SHIPPING -> ShippingEvents.RETURN_RECEIVED;
            default -> throw new IllegalArgumentException("No harmless orphan event for " + topic);
        };
    }

    private Object orphanPayload(String key) {
        return switch (topic) {
            case Topics.ORDER -> new OrderEvents.Returned(key, 999_999, "nobody@example.com",
                    List.of(new Line("NO-SUCH-SKU", "Nothing", 1, 100)), 100, "USD");
            case Topics.INVENTORY -> new InventoryEvents.Reserved(key);
            case Topics.STOCK -> new InventoryEvents.StockChanged("NO-SUCH-SKU-" + key.substring(20), 1);
            case Topics.BILLING -> new BillingEvents.Failed(key, "PAYMENT_DECLINED");
            case Topics.SHIPPING -> new ShippingEvents.ReturnReceived(key, "RET0");
            default -> throw new IllegalArgumentException("No harmless orphan event for " + topic);
        };
    }

    static void checkTopic(Type type, String topic) {
        if (!Topics.ALL.contains(topic)) {
            throw new IllegalArgumentException("Unknown topic " + topic);
        }
        if (List.of(Type.ORPHAN, Type.DUPLICATE, Type.FLOOD, Type.INVALID_SCHEMA).contains(type)
                && topic.equals(Topics.CATALOG)) {
            throw new IllegalArgumentException(type + " has no harmless event for the catalog topic; pick another");
        }
    }
}
