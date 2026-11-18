package com.thirdstone.commerce.billing;

import com.thirdstone.commerce.platform.data.Ids;
import com.thirdstone.commerce.platform.events.BillingEvents;
import com.thirdstone.commerce.platform.events.EventEnvelope;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.testing.CommerceContainers;
import com.thirdstone.commerce.platform.testing.Edge;
import com.thirdstone.commerce.platform.testing.EventBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BillingIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CommerceContainers.register(registry);
        registry.add("commerce.payment.gateway-url", FakeProvider::url);
        registry.add("commerce.payment.api-key", () -> "test-key");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    final EventBus bus = EventBus.shared();

    private OrderEvents.PaymentRequested request(String orderId, String method, Instant expiresAt) {
        return new OrderEvents.PaymentRequested(orderId, 5, 2500, "USD", method, expiresAt);
    }

    private OrderEvents.PaymentRequested requestPayment(String method) {
        String orderId = Ids.next();
        var request = request(orderId, method, Instant.now().plusSeconds(120));
        bus.publish(OrderEvents.PAYMENT_REQUESTED, orderId, request);
        return request;
    }

    private String paymentStatus(String orderId) {
        return jdbc.sql("SELECT status FROM payments WHERE order_id = ?").param(orderId).query(String.class)
                .optional().orElse(null);
    }

    @Test
    void approvedChargeIsRecordedAndAnnounced() throws Exception {
        var request = requestPayment("tok_visa");
        BillingEvents.Authorized authorized = bus.await(BillingEvents.AUTHORIZED, request.orderId(),
                BillingEvents.Authorized.class);
        assertThat(authorized.amountCents()).isEqualTo(2500);
        assertThat(authorized.providerRef()).startsWith("ch_");
        assertThat(paymentStatus(request.orderId())).isEqualTo("AUTHORIZED");
        assertThat(FakeProvider.callsFor(request.orderId())).singleElement()
                .satisfies(c -> assertThat(c.idempotencyKey()).isEqualTo("charge-" + request.orderId()));
        mvc.perform(Edge.admin(get("/api/v1/backoffice/billing/payments"), 1).param("orderId", request.orderId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("AUTHORIZED"));
    }

    @Test
    void declinedChargeFailsTheOrder() {
        var request = requestPayment("tok_decline");
        assertThat(bus.await(BillingEvents.FAILED, request.orderId(), BillingEvents.Failed.class).reason())
                .isEqualTo("PAYMENT_DECLINED");
        assertThat(paymentStatus(request.orderId())).isEqualTo("FAILED");
    }

    @Test
    void providerThatCannotAnswerFailsTheOrderAfterOneRetry() {
        var request = requestPayment("tok_down");
        assertThat(bus.await(BillingEvents.FAILED, request.orderId(), BillingEvents.Failed.class).reason())
                .isEqualTo("PAYMENT_UNAVAILABLE");
        assertThat(FakeProvider.callsFor(request.orderId())).hasSize(2);
    }

    @Test
    void expiredRequestIsNeverCharged() {
        String orderId = Ids.next();
        bus.publish(OrderEvents.PAYMENT_REQUESTED, orderId, request(orderId, "tok_visa", Instant.now().minusSeconds(1)));
        assertThat(bus.await(BillingEvents.FAILED, orderId, BillingEvents.Failed.class).reason()).isEqualTo("EXPIRED");
        assertThat(FakeProvider.callsFor(orderId)).isEmpty();
    }

    @Test
    void theSameRequestTwiceChargesOnce() {
        String orderId = Ids.next();
        EventEnvelope first = bus.publish(OrderEvents.PAYMENT_REQUESTED, orderId,
                request(orderId, "tok_visa", Instant.now().plusSeconds(120)));
        // A redelivery (same event) and a second request (new event id) for the same order.
        bus.publishRaw(Topics.ORDER, orderId, JsonMapper.builder().build().writeValueAsBytes(first));
        bus.publish(OrderEvents.PAYMENT_REQUESTED, orderId, request(orderId, "tok_visa", Instant.now().plusSeconds(120)));
        bus.await(BillingEvents.AUTHORIZED, orderId, BillingEvents.Authorized.class);
        String marker = requestPayment("tok_visa").orderId();
        bus.await(BillingEvents.AUTHORIZED, marker, BillingEvents.Authorized.class);
        assertThat(FakeProvider.callsFor(orderId)).hasSize(1);
        assertThat(bus.events(BillingEvents.AUTHORIZED, orderId, BillingEvents.Authorized.class)).hasSize(1);
    }

    @Test
    void cancellationBeforeTheChargeRequestMeansNoCharge() {
        String orderId = Ids.next();
        bus.publish(OrderEvents.CANCELLED, orderId, new OrderEvents.Cancelled(orderId, 5, "a@example.com", "TIMEOUT", 2500, "USD"));
        await().atMost(Duration.ofSeconds(30)).until(() -> "CANCELLED".equals(paymentStatus(orderId)));
        bus.publish(OrderEvents.PAYMENT_REQUESTED, orderId, request(orderId, "tok_visa", Instant.now().plusSeconds(120)));
        String marker = requestPayment("tok_visa").orderId();
        bus.await(BillingEvents.AUTHORIZED, marker, BillingEvents.Authorized.class);
        assertThat(FakeProvider.callsFor(orderId)).isEmpty();
        assertThat(bus.types(Topics.BILLING, orderId)).isEmpty();
    }

    @Test
    void cancellationAfterTheChargeRefundsIt() {
        var request = requestPayment("tok_visa");
        bus.await(BillingEvents.AUTHORIZED, request.orderId(), BillingEvents.Authorized.class);
        bus.publish(OrderEvents.CANCELLED, request.orderId(), new OrderEvents.Cancelled(request.orderId(), 5,
                "a@example.com", "CUSTOMER", 2500, "USD"));
        BillingEvents.Refunded refunded = bus.await(BillingEvents.REFUNDED, request.orderId(), BillingEvents.Refunded.class);
        assertThat(refunded.amountCents()).isEqualTo(2500);
        assertThat(paymentStatus(request.orderId())).isEqualTo("REFUNDED");
        assertThat(FakeProvider.callsFor(request.orderId())).extracting(FakeProvider.Call::idempotencyKey)
                .containsExactly("charge-" + request.orderId(), "refund-" + request.orderId());
    }

    @Test
    void aRefundTheProviderCannotTakeIsQueuedAndMadeWhenItIsBack() {
        var request = requestPayment("tok_visa");
        bus.await(BillingEvents.AUTHORIZED, request.orderId(), BillingEvents.Authorized.class);
        // The provider is away for the first attempt (the client's call and its one quick retry).
        FakeProvider.REFUNDS_DOWN.set(2);
        bus.publish(OrderEvents.RETURNED, request.orderId(), new OrderEvents.Returned(request.orderId(), 5,
                "a@example.com", java.util.List.of(new com.thirdstone.commerce.platform.events.Line("LAMP", "Lamp", 1, 2500)), 2500, "USD"));
        await().atMost(java.time.Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT refund_attempts FROM payments WHERE order_id = ?").param(request.orderId())
                .query(Integer.class).single() >= 1);
        assertThat(paymentStatus(request.orderId())).isEqualTo("REFUND_PENDING");
        BillingEvents.Refunded refunded = bus.await(BillingEvents.REFUNDED, request.orderId(), BillingEvents.Refunded.class);
        assertThat(refunded.amountCents()).isEqualTo(2500);
        assertThat(paymentStatus(request.orderId())).isEqualTo("REFUNDED");
        assertThat(FakeProvider.callsFor(request.orderId())).extracting(FakeProvider.Call::idempotencyKey)
                .containsExactly("charge-" + request.orderId(), "refund-" + request.orderId(),
                        "refund-" + request.orderId(), "refund-" + request.orderId());
        assertThat(bus.types(Topics.BILLING, request.orderId())).containsExactly(BillingEvents.AUTHORIZED,
                BillingEvents.REFUNDED);
    }

    @Test
    void cancellationOfAFailedPaymentRefundsNothing() {
        var request = requestPayment("tok_decline");
        bus.await(BillingEvents.FAILED, request.orderId(), BillingEvents.Failed.class);
        bus.publish(OrderEvents.CANCELLED, request.orderId(), new OrderEvents.Cancelled(request.orderId(), 5,
                "a@example.com", "PAYMENT_DECLINED", 2500, "USD"));
        String marker = requestPayment("tok_visa").orderId();
        bus.await(BillingEvents.AUTHORIZED, marker, BillingEvents.Authorized.class);
        assertThat(paymentStatus(request.orderId())).isEqualTo("FAILED");
        assertThat(bus.types(Topics.BILLING, request.orderId())).containsExactly(BillingEvents.FAILED);
    }
}
