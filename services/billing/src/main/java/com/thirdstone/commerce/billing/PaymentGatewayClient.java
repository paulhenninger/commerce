package com.thirdstone.commerce.billing;

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
 * The payment provider, as billing sees it: every call bounded in time, retried
 * once if it never got an answer, and behind a circuit breaker so that a provider
 * that is down is not hammered — and its customers are told quickly — instead of
 * every order waiting out the timeouts.
 *
 * <p>A decline is an answer, not a failure: it does not count against the breaker.
 * Every call carries an idempotency key derived from the order, so a retry, a
 * redelivered event or a second billing replica can never charge twice.
 */
@Component
public class PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);

    public sealed interface ChargeResult {
        record Approved(String providerRef) implements ChargeResult {
        }

        record Declined(String code) implements ChargeResult {
        }

        record Unavailable(String why) implements ChargeResult {
        }
    }

    private final RestClient rest;
    private final CircuitBreaker breaker;
    private final MeterRegistry registry;

    public PaymentGatewayClient(PaymentProperties props, MeterRegistry registry) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        this.rest = RestClient.builder().baseUrl(props.gatewayUrl()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + props.apiKey()).build();
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(ProviderUnavailableException.class)
                .build());
        this.breaker = breakers.circuitBreaker("payment");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(registry);
        this.registry = registry;
        breaker.getEventPublisher().onStateTransition(e -> log.atWarn()
                .addKeyValue("breaker", "payment").addKeyValue("transition", e.getStateTransition().toString())
                .log("Payment provider circuit breaker changed state"));
    }

    public ChargeResult charge(String orderId, long amountCents, String currency, String paymentMethod) {
        try {
            return breaker.executeSupplier(() -> timed("charge", () -> withOneRetry(() -> {
                Map<String, Object> body = Map.of("orderId", orderId, "amountCents", amountCents, "currency", currency,
                        "paymentMethod", paymentMethod);
                return rest.post().uri("/payments/v1/charges").header("Idempotency-Key", "charge-" + orderId)
                        .contentType(MediaType.APPLICATION_JSON).body(body)
                        .exchange((request, response) -> {
                            int status = response.getStatusCode().value();
                            if (status == 200 || status == 201) {
                                Map<?, ?> answer = response.bodyTo(Map.class);
                                return (ChargeResult) new ChargeResult.Approved(String.valueOf(answer.get("id")));
                            }
                            if (status >= 500 || status == 429) {
                                throw new ProviderUnavailableException("provider answered " + status);
                            }
                            Map<?, ?> answer = status == 402 ? response.bodyTo(Map.class) : Map.of();
                            Object code = answer == null ? null : answer.get("code");
                            return new ChargeResult.Declined(code == null ? "REJECTED_" + status : code.toString());
                        });
            })));
        } catch (CallNotPermittedException e) {
            return new ChargeResult.Unavailable("circuit open");
        } catch (ProviderUnavailableException e) {
            return new ChargeResult.Unavailable(e.getMessage());
        }
    }

    /**
     * Gives the money back. Throws {@link ProviderUnavailableException} if the
     * provider cannot be reached: the event is then retried and, failing that,
     * dead-lettered for replay — a refund is never silently dropped.
     */
    public String refund(String orderId, String chargeRef, long amountCents) {
        try {
            return breaker.executeSupplier(() -> timed("refund", () -> withOneRetry(() ->
                    rest.post().uri("/payments/v1/refunds").header("Idempotency-Key", "refund-" + orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("chargeId", chargeRef, "amountCents", amountCents))
                            .exchange((request, response) -> {
                                int status = response.getStatusCode().value();
                                if (status == 200 || status == 201) {
                                    return String.valueOf(response.bodyTo(Map.class).get("id"));
                                }
                                throw new ProviderUnavailableException("refund answered " + status);
                            }))));
        } catch (CallNotPermittedException e) {
            throw new ProviderUnavailableException("circuit open", e);
        }
    }

    private <T> T withOneRetry(Supplier<T> call) {
        try {
            return attempt(call);
        } catch (ProviderUnavailableException first) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw first;
            }
            return attempt(call);
        }
    }

    private <T> T attempt(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            // Connect refused, timed out, reset: no answer at all.
            throw new ProviderUnavailableException("no answer from the provider: " + e.getMessage(), e);
        }
    }

    private <T> T timed(String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try {
            T result = call.get();
            outcome = result instanceof ChargeResult.Declined ? "declined" : "ok";
            return result;
        } finally {
            sample.stop(Timer.builder("commerce.external.calls").tag("target", "payment").tag("operation", operation)
                    .tag("outcome", outcome).publishPercentileHistogram().register(registry));
        }
    }
}
