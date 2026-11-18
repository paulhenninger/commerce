package com.thirdstone.commerce.platform.messaging;

import com.thirdstone.commerce.platform.events.EventEnvelope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A minimal event-driven service: one topic, one handler whose behaviour the payload chooses. */
@SpringBootApplication
public class MessagingTestApp {

    public static final String TOPIC = "test.events.v1";
    public static final String TYPE = "test.happened";

    public record Happened(String key, int seq, String mode) {
        public Happened {
            if (key == null) {
                throw new IllegalArgumentException("key missing");
            }
        }
    }

    public record Applied(String eventId, String key, int seq, String traceId) {
    }

    @Component
    public static class Listener {

        public final List<Applied> applied = new CopyOnWriteArrayList<>();
        public final AtomicInteger failingAttempts = new AtomicInteger();
        public final AtomicInteger transientAttempts = new AtomicInteger();
        public final List<ConsumerRecord<String, byte[]>> deadLetters = new CopyOnWriteArrayList<>();

        private final EventDispatcher dispatcher;
        private final Tracer tracer;
        private final EventRouter router = EventRouter.forConsumer("msgtest").on(TYPE, Happened.class, this::handle);

        Listener(EventDispatcher dispatcher, Tracer tracer) {
            this.dispatcher = dispatcher;
            this.tracer = tracer;
        }

        private void handle(EventEnvelope event, Happened payload) {
            if ("fail".equals(payload.mode())) {
                failingAttempts.incrementAndGet();
                throw new IllegalStateException("handler cannot process this event");
            }
            if ("transient".equals(payload.mode()) && transientAttempts.incrementAndGet() <= 5) {
                // As if the database were away for a while.
                throw new org.springframework.jdbc.CannotGetJdbcConnectionException("database unavailable");
            }
            if ("ignore".equals(payload.mode())) {
                EventContext.ignored("test");
            }
            Span span = tracer.currentSpan();
            applied.add(new Applied(event.eventId(), payload.key(), payload.seq(),
                    span == null ? null : span.context().traceId()));
        }

        @KafkaListener(topics = TOPIC, groupId = "msgtest")
        void onEvent(ConsumerRecord<String, byte[]> record) {
            dispatcher.dispatch(record, router);
        }

        @KafkaListener(topics = TOPIC + ".dlt", groupId = "msgtest-dlt")
        void onDeadLetter(ConsumerRecord<String, byte[]> record) {
            deadLetters.add(record);
        }
    }
}
