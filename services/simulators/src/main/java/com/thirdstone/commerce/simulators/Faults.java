package com.thirdstone.commerce.simulators;

import com.thirdstone.commerce.platform.web.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The switches the chaos lab flips: added latency, a share of requests answered
 * with 503, or a full outage, per simulated system.
 */
@Component
class Faults {

    record Setting(int latencyMs, double errorRate, boolean outage) {
        static final Setting NONE = new Setting(0, 0, false);

        Setting {
            if (latencyMs < 0 || latencyMs > 30_000 || errorRate < 0 || errorRate > 1) {
                throw ApiException.badRequest("latencyMs 0-30000, errorRate 0-1");
            }
        }
    }

    static final java.util.List<String> TARGETS = java.util.List.of("payment", "carrier");

    private final Map<String, Setting> settings = new ConcurrentHashMap<>();
    private final MeterRegistry registry;

    Faults(MeterRegistry registry) {
        this.registry = registry;
        TARGETS.forEach(t -> settings.put(t, Setting.NONE));
    }

    Map<String, Setting> all() {
        return Map.copyOf(settings);
    }

    void set(String target, Setting setting) {
        if (!TARGETS.contains(target)) {
            throw ApiException.notFound("Simulator");
        }
        settings.put(target, setting);
    }

    /** Applies the target's faults to one request: may sleep, may throw a 503. */
    void apply(String target, String operation) {
        Setting s = settings.getOrDefault(target, Setting.NONE);
        String outcome = "ok";
        try {
            if (s.latencyMs() > 0) {
                try {
                    Thread.sleep(s.latencyMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (s.outage() || ThreadLocalRandom.current().nextDouble() < s.errorRate()) {
                outcome = "injected_error";
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated outage", "SIMULATED_FAULT");
            }
        } finally {
            Counter.builder("commerce.simulator.requests").tag("target", target).tag("operation", operation)
                    .tag("outcome", outcome).register(registry).increment();
        }
    }
}
