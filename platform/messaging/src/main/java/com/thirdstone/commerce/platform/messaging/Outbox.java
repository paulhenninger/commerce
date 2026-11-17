package com.thirdstone.commerce.platform.messaging;

import com.thirdstone.commerce.platform.events.EventCodec;
import com.thirdstone.commerce.platform.events.EventEnvelope;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only way a service announces that something happened.
 *
 * <p>Writing to the database and then to Kafka (or the other way round) leaves a
 * window where a crash produces a state change nobody hears about, or an event
 * about a change that was rolled back. So an event is not sent: it is inserted
 * here, in the same transaction as the change it describes — both commit or
 * neither does — and {@link OutboxRelay} publishes it afterwards, retrying until
 * the broker has it.
 */
public class Outbox {

    private final JdbcClient jdbc;
    private final EventCodec codec;
    private final ObjectProvider<Tracer> tracer;
    private final OutboxRelay relay;
    private final String source;

    public Outbox(JdbcClient jdbc, EventCodec codec, ObjectProvider<Tracer> tracer, OutboxRelay relay,
                  String source) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.tracer = tracer;
        this.relay = relay;
        this.source = source;
    }

    /**
     * Records an event to be published when the current transaction commits.
     *
     * @param aggregateId what the event is about; also the record key, so events
     *                    about one aggregate stay in order
     * @throws IllegalTransactionStateException outside a transaction — an event
     *                                          written on its own could announce a change that never committed
     */
    public EventEnvelope append(String topic, String type, String aggregateId, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                    "Outbox.append must run inside the transaction that changes state");
        }
        EventEnvelope event = codec.create(type, aggregateId, source, payload);
        jdbc.sql("""
                        INSERT INTO outbox_events (topic, record_key, event_type, payload, traceparent, created_at)
                        VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6))""")
                .params(topic, aggregateId, type, codec.serialize(event), traceparent())
                .update();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // The event leaves within milliseconds instead of on the next poll.
                relay.wake();
            }
        });
        return event;
    }

    /** The W3C trace context of whatever caused this event, so the trace continues in its consumers. */
    private String traceparent() {
        return TraceContinuation.current(tracer.getIfAvailable());
    }
}
