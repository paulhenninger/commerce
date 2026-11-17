package com.thirdstone.commerce.platform.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Carries a trace across a gap the instrumentation cannot see: a row written now
 * and acted on later by a worker (an outbox event, an email to send). The row
 * stores the W3C {@code traceparent} of what caused it; the worker continues that
 * trace, so one trace still runs from the customer's click to the last effect.
 */
public class TraceContinuation {

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public TraceContinuation(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The current span as a {@code traceparent} value, or null outside a trace. */
    public String current() {
        return current(tracer.getIfAvailable());
    }

    static String current(Tracer t) {
        Span span = t == null ? null : t.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        if (context.traceId() == null || context.traceId().length() != 32 || context.spanId() == null) {
            return null;
        }
        return "00-" + context.traceId() + "-" + context.spanId()
                + (Boolean.FALSE.equals(context.sampled()) ? "-00" : "-01");
    }

    /** Runs {@code work} in a new span that is a child of {@code traceparent} (or a new trace without one). */
    public <T> T inSpan(String traceparent, String name, Supplier<T> work) {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null) {
            return work.get();
        }
        Span span = traceparent == null ? t.nextSpan().name(name).start()
                : p.extract(Map.of("traceparent", traceparent), (Map<String, String> c, String key) -> c.get(key))
                        .name(name).start();
        try (Tracer.SpanInScope ignored = t.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
