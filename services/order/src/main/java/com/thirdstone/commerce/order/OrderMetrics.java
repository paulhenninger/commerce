package com.thirdstone.commerce.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MultiGauge;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The business view: how many orders, how they end, how long the saga takes, and
 * how much money moved. Also the in-flight count that load shedding reads, so
 * placing an order costs no extra query.
 */
@Component
class OrderMetrics {

    private final MeterRegistry registry;
    private final OrderRepository orders;
    private final MultiGauge byStatus;
    /** -1 until first measured: until then, nothing is shed. */
    private final AtomicLong inFlight = new AtomicLong(-1);

    OrderMetrics(MeterRegistry registry, OrderRepository orders) {
        this.registry = registry;
        this.orders = orders;
        this.byStatus = MultiGauge.builder("commerce.orders.by.status")
                .description("Orders in each status").register(registry);
        registry.gauge("commerce.orders.in.flight", inFlight);
    }

    long inFlight() {
        return inFlight.get();
    }

    void placed() {
        Counter.builder("commerce.orders").tag("event", "placed").register(registry).increment();
    }

    /** An order reached CONFIRMED or CANCELLED: how long it took from placement. */
    void settled(OrderRow order, String outcome, String reason) {
        Counter.builder("commerce.orders").tag("event", outcome.toLowerCase())
                .tag("reason", reason == null ? "none" : reason).register(registry).increment();
        Timer.builder("commerce.order.saga").tag("outcome", outcome).publishPercentileHistogram()
                .register(registry).record(Duration.between(order.createdAt(), Instant.now()));
    }

    void revenue(long cents, String currency) {
        Counter.builder("commerce.revenue.cents").tag("currency", currency).register(registry).increment(cents);
    }

    void refund(long cents, String currency) {
        Counter.builder("commerce.refunds.cents").tag("currency", currency).register(registry).increment(cents);
    }

    @Scheduled(fixedDelay = 2000)
    void refreshInFlight() {
        inFlight.set(orders.inFlight());
    }

    @Scheduled(fixedDelay = 5000)
    void refreshByStatus() {
        Map<String, Long> counts = orders.countByStatus();
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        for (String status : OrderStatus.ALL) {
            rows.add(MultiGauge.Row.of(Tags.of("status", status), counts.getOrDefault(status, 0L)));
        }
        byStatus.register(rows, true);
    }
}
