package com.thirdstone.commerce.platform.messaging;

import com.thirdstone.commerce.platform.events.EventEnvelope;

import java.util.HashMap;
import java.util.Map;

/**
 * Which handler a consumer runs for which event type. A type with no handler is
 * skipped: producers can add event types without breaking anyone.
 *
 * <pre>{@code
 * EventRouter router = EventRouter.forConsumer("inventory")
 *         .on(OrderEvents.PLACED, OrderEvents.Placed.class, reservations::reserve);
 * }</pre>
 */
public final class EventRouter {

    /** Runs inside the transaction that also records the event as processed. */
    @FunctionalInterface
    public interface Handler<T> {
        void handle(EventEnvelope event, T payload);
    }

    /**
     * Runs before the transaction — the place for a call to an external system,
     * which must never happen with a database transaction open. It may run more
     * than once for the same event (a redelivery), so it must be idempotent.
     */
    @FunctionalInterface
    public interface Preparation<T, P> {
        P prepare(EventEnvelope event, T payload);
    }

    @FunctionalInterface
    public interface Application<T, P> {
        void apply(EventEnvelope event, T payload, P prepared);
    }

    record Route<T, P>(Class<T> payloadType, Preparation<T, P> preparation, Application<T, P> application) {
    }

    private final String consumer;
    private final Map<String, Route<?, ?>> routes = new HashMap<>();

    private EventRouter(String consumer) {
        this.consumer = consumer;
    }

    /** @param consumer the service name; also the name events are remembered under */
    public static EventRouter forConsumer(String consumer) {
        return new EventRouter(consumer);
    }

    public <T> EventRouter on(String type, Class<T> payloadType, Handler<T> handler) {
        return add(type, new Route<T, Void>(payloadType, null, (event, payload, none) -> handler.handle(event, payload)));
    }

    public <T, P> EventRouter onWithPreparation(String type, Class<T> payloadType, Preparation<T, P> preparation,
                                                Application<T, P> application) {
        return add(type, new Route<>(payloadType, preparation, application));
    }

    private EventRouter add(String type, Route<?, ?> route) {
        if (routes.putIfAbsent(type, route) != null) {
            throw new IllegalStateException("Two handlers for " + type + " in consumer " + consumer);
        }
        return this;
    }

    String consumer() {
        return consumer;
    }

    Route<?, ?> route(String type) {
        return routes.get(type);
    }
}
