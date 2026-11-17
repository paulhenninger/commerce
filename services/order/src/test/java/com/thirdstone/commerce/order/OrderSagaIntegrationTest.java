package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.events.BillingEvents;
import com.thirdstone.commerce.platform.events.InventoryEvents;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.testing.Edge;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class OrderSagaIntegrationTest extends OrderTestSupport {

    @Test
    void orderIsConfirmedWhenStockIsReservedAndPaymentAuthorized() throws Exception {
        String sku = product(1250);
        String id = placed(7, order(sku, 2, 2500, "tok_visa"));

        OrderEvents.Placed placed = bus.await(OrderEvents.PLACED, id, OrderEvents.Placed.class);
        assertThat(placed.totalCents()).isEqualTo(2500);
        assertThat(placed.lines()).singleElement().satisfies(l -> {
            assertThat(l.sku()).isEqualTo(sku);
            assertThat(l.quantity()).isEqualTo(2);
            assertThat(l.unitPriceCents()).isEqualTo(1250);
        });

        bus.publish(InventoryEvents.RESERVED, id, new InventoryEvents.Reserved(id));
        OrderEvents.PaymentRequested request = bus.await(OrderEvents.PAYMENT_REQUESTED, id,
                OrderEvents.PaymentRequested.class);
        assertThat(request.amountCents()).isEqualTo(2500);
        assertThat(request.paymentMethod()).isEqualTo("tok_visa");
        assertThat(request.expiresAt()).isAfter(Instant.now());

        bus.publish(BillingEvents.AUTHORIZED, id,
                new BillingEvents.Authorized(id, "01JD3K8R000000000000000001", 2500, "USD", "ch_1"));
        OrderEvents.Confirmed confirmed = bus.await(OrderEvents.CONFIRMED, id, OrderEvents.Confirmed.class);
        assertThat(confirmed.shippingAddress().city()).isEqualTo("Springfield");

        mvc.perform(Edge.user(get("/api/v1/orders/" + id), 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.paymentMethod").doesNotExist());
        mvc.perform(Edge.user(get("/api/v1/orders"), 7))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].items").value(2));
    }

    @Test
    void outOfStockCancelsTheOrder() throws Exception {
        String sku = product(500);
        String id = placed(8, order(sku, 1, 500, "tok_visa"));
        bus.publish(InventoryEvents.REJECTED, id, new InventoryEvents.Rejected(id, "OUT_OF_STOCK", sku));
        OrderEvents.Cancelled cancelled = bus.await(OrderEvents.CANCELLED, id, OrderEvents.Cancelled.class);
        assertThat(cancelled.reason()).isEqualTo("OUT_OF_STOCK");
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(bus.types(Topics.ORDER, id)).doesNotContain(OrderEvents.PAYMENT_REQUESTED);
    }

    @Test
    void failedPaymentCancelsTheOrderWithTheProvidersReason() throws Exception {
        String sku = product(500);
        String id = placed(8, order(sku, 1, 500, "tok_decline"));
        bus.publish(InventoryEvents.RESERVED, id, new InventoryEvents.Reserved(id));
        bus.await(OrderEvents.PAYMENT_REQUESTED, id, OrderEvents.PaymentRequested.class);
        bus.publish(BillingEvents.FAILED, id, new BillingEvents.Failed(id, "PAYMENT_DECLINED"));
        assertThat(bus.await(OrderEvents.CANCELLED, id, OrderEvents.Cancelled.class).reason())
                .isEqualTo("PAYMENT_DECLINED");
    }

    @Test
    void eventsThatNoLongerApplyChangeNothing() throws Exception {
        String sku = product(500);
        String id = placed(8, order(sku, 1, 500, "tok_visa"));
        bus.publish(InventoryEvents.REJECTED, id, new InventoryEvents.Rejected(id, "OUT_OF_STOCK", sku));
        bus.await(OrderEvents.CANCELLED, id, OrderEvents.Cancelled.class);
        // A late payment for a cancelled order, and events about an order that never existed.
        bus.publish(BillingEvents.AUTHORIZED, id, new BillingEvents.Authorized(id, "01JD3K8R000000000000000002", 500, "USD", "ch_2"));
        String orphan = "01JD3K8Q2M7XW4T9V6B5N1ZZZZ";
        bus.publish(InventoryEvents.RESERVED, orphan, new InventoryEvents.Reserved(orphan));
        String marker = placed(8, order(sku, 1, 500, "tok_visa"));
        bus.await(OrderEvents.PLACED, marker, OrderEvents.Placed.class);
        Thread.sleep(1500);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(bus.types(Topics.ORDER, id)).doesNotContain(OrderEvents.CONFIRMED);
        assertThat(bus.types(Topics.ORDER, orphan)).isEmpty();
    }

    @Test
    void retriedCheckoutReturnsTheFirstOrder() throws Exception {
        String sku = product(300);
        String key = UUID.randomUUID().toString();
        String first = json.readTree(place(9, order(sku, 1, 300, "tok_visa"), key)
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asString();
        place(9, order(sku, 1, 300, "tok_visa"), key).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(first));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE user_id = 9 AND idempotency_key = ?").param(key)
                .query(Integer.class).single()).isEqualTo(1);
        // Another customer's identical key is their own checkout.
        place(10, order(sku, 1, 300, "tok_visa"), key).andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(first)));
    }

    @Test
    void stalePriceIsRefusedWithTheCurrentOnes() throws Exception {
        String sku = product(1000);
        place(11, order(sku, 2, 1800, "tok_visa"), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.totalCents").value(2000))
                .andExpect(jsonPath("$.lines[0].unitPriceCents").value(1000));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE user_id = 11").query(Integer.class).single()).isZero();
    }

    @Test
    void unknownProductsAndBadQuantitiesAreRejected() throws Exception {
        place(12, order("NO-SUCH-SKU", 1, 100, "tok_visa"), UUID.randomUUID().toString())
                .andExpect(status().isBadRequest());
        String sku = product(100);
        place(12, order(sku, 21, 2100, "tok_visa"), UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        place(12, order(sku, 0, 0, "tok_visa"), UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        place(12, order(sku, 1, 100, "4111111111111111"), UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        mvc.perform(Edge.user(post("/api/v1/orders"), 12).contentType("application/json")
                .content(order(sku, 1, 100, "tok_visa"))).andExpect(status().isBadRequest());
    }

    @Test
    void ordersBelongToTheirCustomer() throws Exception {
        String sku = product(100);
        String id = placed(13, order(sku, 1, 100, "tok_visa"));
        mvc.perform(Edge.user(get("/api/v1/orders/" + id), 14)).andExpect(status().isNotFound());
        mvc.perform(Edge.user(post("/api/v1/orders/" + id + "/cancel"), 14)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(Edge.user(get("/api/v1/backoffice/orders/" + id), 14)).andExpect(status().isForbidden());
        mvc.perform(Edge.admin(get("/api/v1/backoffice/orders/" + id), 1)).andExpect(jsonPath("$.id").value(id));
        mvc.perform(Edge.admin(get("/api/v1/backoffice/orders"), 1).param("q", id))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void customerCanCancelAConfirmedOrderButNotTwice() throws Exception {
        String sku = product(700);
        String id = placed(15, order(sku, 1, 700, "tok_visa"));
        bus.publish(InventoryEvents.RESERVED, id, new InventoryEvents.Reserved(id));
        bus.await(OrderEvents.PAYMENT_REQUESTED, id, OrderEvents.PaymentRequested.class);
        bus.publish(BillingEvents.AUTHORIZED, id, new BillingEvents.Authorized(id, "01JD3K8R000000000000000003", 700, "USD", "ch_3"));
        await().atMost(Duration.ofSeconds(30)).until(() -> statusOf(id).equals("CONFIRMED"));

        mvc.perform(Edge.user(post("/api/v1/orders/" + id + "/cancel"), 15))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(bus.await(OrderEvents.CANCELLED, id, OrderEvents.Cancelled.class).reason()).isEqualTo("CUSTOMER");
        mvc.perform(Edge.user(post("/api/v1/orders/" + id + "/cancel"), 15))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));
    }
}
