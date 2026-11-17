package com.thirdstone.commerce.shipping;

import com.thirdstone.commerce.platform.data.Ids;
import com.thirdstone.commerce.platform.events.Address;
import com.thirdstone.commerce.platform.events.Line;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.ShippingEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.testing.CommerceContainers;
import com.thirdstone.commerce.platform.testing.Edge;
import com.thirdstone.commerce.platform.testing.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {"commerce.shipping.worker-interval-millis=200", "commerce.shipping.poll-interval=200ms"})
@AutoConfigureMockMvc
class ShippingIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CommerceContainers.register(registry);
        registry.add("commerce.carrier.url", FakeCarrier::url);
        registry.add("commerce.carrier.api-key", () -> "test-key");
    }

    private static final Address ADDRESS = new Address("Ada", "1 Street", "Springfield", "12345", "US");
    private static final List<Line> LINES = List.of(new Line("SKU-1", "Thing", 1, 100));

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    final EventBus bus = EventBus.shared();

    @AfterEach
    void carrierUp() {
        FakeCarrier.down.set(false);
    }

    private String confirmed() {
        String orderId = Ids.next();
        bus.publish(OrderEvents.CONFIRMED, orderId, new OrderEvents.Confirmed(orderId, 5, "a@example.com", LINES, 100,
                "USD", ADDRESS));
        return orderId;
    }

    @Test
    void aConfirmedOrderIsBookedDispatchedAndDeliveredInThatOrder() throws Exception {
        String orderId = confirmed();
        ShippingEvents.Created created = bus.await(ShippingEvents.CREATED, orderId, ShippingEvents.Created.class);
        assertThat(created.carrier()).isEqualTo("FakeShip");
        bus.await(ShippingEvents.DELIVERED, orderId, ShippingEvents.Delivered.class);
        assertThat(bus.types(Topics.SHIPPING, orderId)).containsExactly(ShippingEvents.CREATED,
                ShippingEvents.DISPATCHED, ShippingEvents.DELIVERED);
        assertThat(FakeCarrier.bookings).containsOnlyOnce("ship-OUTBOUND-" + orderId);
        mvc.perform(Edge.admin(get("/api/v1/backoffice/shipping/shipments"), 1).param("orderId", orderId))
                .andExpect(jsonPath("$[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$[0].address").doesNotExist());
    }

    @Test
    void aReturnGetsALabelAndIsReceived() {
        String orderId = Ids.next();
        bus.publish(OrderEvents.RETURN_REQUESTED, orderId, new OrderEvents.ReturnRequested(orderId, 5, "a@example.com",
                LINES, ADDRESS, "Too small"));
        ShippingEvents.ReturnLabelCreated label = bus.await(ShippingEvents.RETURN_LABEL_CREATED, orderId,
                ShippingEvents.ReturnLabelCreated.class);
        assertThat(label.trackingNumber()).startsWith("RET");
        bus.await(ShippingEvents.RETURN_RECEIVED, orderId, ShippingEvents.ReturnReceived.class);
        assertThat(bus.types(Topics.SHIPPING, orderId)).containsExactly(ShippingEvents.RETURN_LABEL_CREATED,
                ShippingEvents.RETURN_RECEIVED);
    }

    @Test
    void aCarrierOutageDelaysTheShipmentAndNothingElse() throws Exception {
        FakeCarrier.down.set(true);
        String orderId = confirmed();
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql(
                "SELECT COALESCE(MAX(attempts), 0) FROM shipments WHERE order_id = ?").param(orderId)
                .query(Integer.class).single() >= 2);
        assertThat(bus.types(Topics.SHIPPING, orderId)).isEmpty();
        // Backoff grows: the next attempt is further away than the poll interval.
        FakeCarrier.down.set(false);
        jdbc.sql("UPDATE shipments SET next_attempt_at = UTC_TIMESTAMP(6) WHERE order_id = ?").param(orderId).update();
        bus.await(ShippingEvents.DELIVERED, orderId, ShippingEvents.Delivered.class);
    }

    @Test
    void cancellingBeforeDispatchCancelsTheShipment() throws Exception {
        FakeCarrier.down.set(true);
        String orderId = confirmed();
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql(
                "SELECT COUNT(*) FROM shipments WHERE order_id = ?").param(orderId).query(Integer.class).single() == 1);
        bus.publish(OrderEvents.CANCELLED, orderId, new OrderEvents.Cancelled(orderId, 5, "a@example.com", "CUSTOMER",
                100, "USD"));
        await().atMost(Duration.ofSeconds(30)).until(() -> "CANCELLED".equals(jdbc.sql(
                "SELECT status FROM shipments WHERE order_id = ?").param(orderId).query(String.class).single()));
        FakeCarrier.down.set(false);
        jdbc.sql("UPDATE shipments SET next_attempt_at = UTC_TIMESTAMP(6) WHERE order_id = ?").param(orderId).update();
        Thread.sleep(1500);
        assertThat(bus.types(Topics.SHIPPING, orderId)).isEmpty();
    }

    @Test
    void backoffDoublesUpToFiveMinutes() {
        assertThat(ShipmentWorker.backoff(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(ShipmentWorker.backoff(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(ShipmentWorker.backoff(20)).isEqualTo(Duration.ofMinutes(5));
    }
}
