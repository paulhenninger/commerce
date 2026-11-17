package com.thirdstone.commerce.platform.messaging;

import com.thirdstone.commerce.platform.events.EventCodec;
import com.thirdstone.commerce.platform.events.EventEnvelope;
import com.thirdstone.commerce.platform.events.InvalidEventException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies one Kafka record exactly once, whatever the record turns out to be.
 *
 * <ol>
 *   <li>Parse and validate. Garbage throws {@link InvalidEventException}, which the
 *       listener's error handler dead-letters without retrying.</li>
 *   <li>No handler for the type: skip.</li>
 *   <li>Already applied (a redelivery, a duplicate, a replay): skip.</li>
 *   <li>Run the handler's preparation, if it has one, outside any transaction.</li>
 *   <li>In one transaction: record the event id, then run the handler. If the
 *       handler throws, the id is rolled back with everything else and the record
 *       is retried; if two replicas race on the same event, the primary key lets
 *       exactly one of them through.</li>
 * </ol>
 */
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final EventCodec codec;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final MeterRegistry registry;

    public EventDispatcher(EventCodec codec, JdbcClient jdbc, TransactionTemplate transactions,
                           MeterRegistry registry) {
        this.codec = codec;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.registry = registry;
    }

    public void dispatch(ConsumerRecord<String, byte[]> record, EventRouter router) {
        String consumer = router.consumer();
        EventEnvelope event;
        try {
            event = codec.parse(record.value());
        } catch (InvalidEventException e) {
            count(consumer, "invalid", "invalid");
            throw e;
        }
        EventRouter.Route<?, ?> route = router.route(event.type());
        if (route == null) {
            // Not tagged with the type: an unknown type is whatever a producer
            // chose to write, and metric labels must not be attacker-controlled.
            count(consumer, "unhandled", "ignored");
            return;
        }
        Timer.Sample sample = Timer.start(registry);
        MDC.put("eventId", event.eventId());
        MDC.put("eventType", event.type());
        MDC.put("aggregateId", event.aggregateId());
        String outcome = "failed";
        try {
            outcome = apply(consumer, event, route);
        } catch (InvalidEventException e) {
            outcome = "invalid";
            throw e;
        } finally {
            count(consumer, event.type(), outcome);
            sample.stop(Timer.builder("commerce.event.processing").tag("consumer", consumer)
                    .tag("type", event.type()).register(registry));
            MDC.remove("eventId");
            MDC.remove("eventType");
            MDC.remove("aggregateId");
        }
    }

    private <T, P> String apply(String consumer, EventEnvelope event, EventRouter.Route<T, P> route) {
        T payload = codec.payload(event, route.payloadType());
        // Cheap early exit, so a replayed event does not repeat an external call.
        // The insert below is what actually guarantees once-only.
        if (alreadyProcessed(consumer, event.eventId())) {
            return "duplicate";
        }
        P prepared = route.preparation() == null ? null : route.preparation().prepare(event, payload);
        return transactions.execute(status -> {
            int inserted = jdbc.sql("""
                            INSERT IGNORE INTO processed_events (consumer, event_id, processed_at)
                            VALUES (?, ?, UTC_TIMESTAMP(6))""")
                    .params(consumer, event.eventId()).update();
            if (inserted == 0) {
                return "duplicate";
            }
            EventContext.reset();
            try {
                route.application().apply(event, payload, prepared);
                String ignored = EventContext.ignoredReason();
                if (ignored != null) {
                    log.atInfo().addKeyValue("reason", ignored).log("Event does not apply; ignored");
                    return "ignored";
                }
                return "processed";
            } finally {
                EventContext.reset();
            }
        });
    }

    private boolean alreadyProcessed(String consumer, String eventId) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_events WHERE consumer = ? AND event_id = ?")
                .params(consumer, eventId).query(Integer.class).single() > 0;
    }

    private void count(String consumer, String type, String outcome) {
        Counter.builder("commerce.events.consumed").tag("consumer", consumer).tag("type", type)
                .tag("outcome", outcome).register(registry).increment();
    }
}
