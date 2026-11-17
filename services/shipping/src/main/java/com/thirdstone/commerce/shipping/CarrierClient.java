package com.thirdstone.commerce.shipping;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The carrier: every call bounded in time and behind a circuit breaker. Called
 * only from the shipment worker, which simply tries again on a later pass, so a
 * carrier outage delays parcels and nothing else.
 */
@Component
public class CarrierClient {

    private static final Logger log = LoggerFactory.getLogger(CarrierClient.class);

    public record Booking(String carrier, String trackingNumber) {
    }

    private final RestClient rest;
    private final CircuitBreaker breaker;
    private final MeterRegistry registry;

    public CarrierClient(ShippingProperties props, MeterRegistry registry, RestClient.Builder builder) {
        var carrier = props.carrier();
        HttpClient http = HttpClient.newBuilder().connectTimeout(carrier.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(carrier.readTimeout());
        // Boot's builder: calls are timed and traced like every other request.
        this.rest = builder.clone().baseUrl(carrier.url()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + carrier.apiKey()).build();
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(CarrierUnavailableException.class)
                .build());
        this.breaker = breakers.circuitBreaker("carrier");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(registry);
        this.registry = registry;
        breaker.getEventPublisher().onStateTransition(e -> log.atWarn().addKeyValue("breaker", "carrier")
                .addKeyValue("transition", e.getStateTransition().toString())
                .log("Carrier circuit breaker changed state"));
    }

    /** Idempotent on the order and direction: booking twice returns the first booking. */
    public Booking book(String orderId, String direction) {
        Map<?, ?> answer = call("book", () -> rest.post().uri("/carrier/v1/shipments")
                .header("Idempotency-Key", "ship-" + direction + "-" + orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reference", orderId, "direction", direction))
                .exchange((request, response) -> answered(response.getStatusCode().value(), response.bodyTo(Map.class))));
        return new Booking(String.valueOf(answer.get("carrier")), String.valueOf(answer.get("trackingNumber")));
    }

    /** The carrier's word on where a parcel is: BOOKED, DISPATCHED, DELIVERED, IN_TRANSIT or RECEIVED. */
    public String status(String trackingNumber) {
        Map<?, ?> answer = call("track", () -> rest.get().uri("/carrier/v1/shipments/{tracking}", trackingNumber)
                .exchange((request, response) -> answered(response.getStatusCode().value(), response.bodyTo(Map.class))));
        return String.valueOf(answer.get("status"));
    }

    private static Map<?, ?> answered(int status, Map<?, ?> body) {
        if (status < 200 || status >= 300 || body == null) {
            throw new CarrierUnavailableException("carrier answered " + status, null);
        }
        return body;
    }

    private <T> T call(String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try {
            T result = breaker.executeSupplier(() -> {
                try {
                    return call.get();
                } catch (ResourceAccessException e) {
                    throw new CarrierUnavailableException("no answer from the carrier", e);
                }
            });
            outcome = "ok";
            return result;
        } catch (CallNotPermittedException e) {
            outcome = "circuit_open";
            throw new CarrierUnavailableException("circuit open", e);
        } finally {
            sample.stop(Timer.builder("commerce.external.calls").tag("target", "carrier").tag("operation", operation)
                    .tag("outcome", outcome).publishPercentileHistogram().register(registry));
        }
    }
}
