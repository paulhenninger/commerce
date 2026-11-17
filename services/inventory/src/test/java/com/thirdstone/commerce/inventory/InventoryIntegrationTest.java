package com.thirdstone.commerce.inventory;

import com.thirdstone.commerce.platform.events.Address;
import com.thirdstone.commerce.platform.events.CatalogEvents;
import com.thirdstone.commerce.platform.events.InventoryEvents;
import com.thirdstone.commerce.platform.events.Line;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.testing.CommerceContainers;
import com.thirdstone.commerce.platform.testing.Edge;
import com.thirdstone.commerce.platform.testing.EventBus;
import com.thirdstone.commerce.platform.data.Ids;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InventoryIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CommerceContainers.register(registry);
    }

    private static final Address ADDRESS = new Address("Ada", "1 Street", "Springfield", "12345", "US");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    final EventBus bus = EventBus.shared();

    private String stocked(int onHand) throws Exception {
        String sku = "INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        bus.publish(CatalogEvents.PRODUCT_UPSERTED, sku, new CatalogEvents.ProductUpserted(sku, "Thing", 100, "USD", true));
        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.sql("SELECT COUNT(*) FROM stock WHERE sku = ?").param(sku).query(Integer.class).single() == 1);
        mvc.perform(Edge.admin(put("/api/v1/backoffice/inventory/stock/" + sku), 1).contentType("application/json")
                .content("{\"onHand\":" + onHand + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(onHand));
        bus.awaitMatching(InventoryEvents.STOCK_CHANGED, sku, InventoryEvents.StockChanged.class,
                s -> s.available() == onHand);
        return sku;
    }

    private String placeOrder(String sku, int quantity) {
        String orderId = Ids.next();
        bus.publish(OrderEvents.PLACED, orderId, new OrderEvents.Placed(orderId, 5, "a@example.com",
                List.of(new Line(sku, "Thing", quantity, 100)), 100L * quantity, "USD"));
        return orderId;
    }

    private int[] counts(String sku) {
        return jdbc.sql("SELECT on_hand, reserved FROM stock WHERE sku = ?").param(sku)
                .query((rs, n) -> new int[]{rs.getInt(1), rs.getInt(2)}).single();
    }

    @Test
    void anOrderThatFitsIsReservedAndAnnounced() throws Exception {
        String sku = stocked(10);
        String orderId = placeOrder(sku, 3);
        bus.await(InventoryEvents.RESERVED, orderId, InventoryEvents.Reserved.class);
        bus.awaitMatching(InventoryEvents.STOCK_CHANGED, sku, InventoryEvents.StockChanged.class, s -> s.available() == 7);
        assertThat(counts(sku)).containsExactly(10, 3);
        mvc.perform(Edge.admin(get("/api/v1/backoffice/inventory/reservations"), 1).param("orderId", orderId))
                .andExpect(jsonPath("$[0].status").value("RESERVED"))
                .andExpect(jsonPath("$[0].quantity").value(3));
    }

    @Test
    void anOrderThatDoesNotFitHoldsNothing() throws Exception {
        String sku = stocked(2);
        String orderId = placeOrder(sku, 3);
        InventoryEvents.Rejected rejected = bus.await(InventoryEvents.REJECTED, orderId, InventoryEvents.Rejected.class);
        assertThat(rejected.sku()).isEqualTo(sku);
        assertThat(rejected.reason()).isEqualTo("OUT_OF_STOCK");
        assertThat(counts(sku)).containsExactly(2, 0);
    }

    @Test
    void twoOrdersCannotSellTheSameUnit() throws Exception {
        String sku = stocked(5);
        String first = placeOrder(sku, 3);
        String second = placeOrder(sku, 3);
        bus.await(InventoryEvents.RESERVED, first, InventoryEvents.Reserved.class);
        bus.await(InventoryEvents.REJECTED, second, InventoryEvents.Rejected.class);
        assertThat(counts(sku)).containsExactly(5, 3);
    }

    @Test
    void confirmationTakesUnitsOffTheShelfAndCancellationPutsThemBack() throws Exception {
        String sku = stocked(10);
        String orderId = placeOrder(sku, 4);
        bus.await(InventoryEvents.RESERVED, orderId, InventoryEvents.Reserved.class);
        bus.publish(OrderEvents.CONFIRMED, orderId, new OrderEvents.Confirmed(orderId, 5, "a@example.com",
                List.of(new Line(sku, "Thing", 4, 100)), 400, "USD", ADDRESS));
        await().atMost(Duration.ofSeconds(30)).until(() -> counts(sku)[0] == 6);
        assertThat(counts(sku)).containsExactly(6, 0);

        bus.publish(OrderEvents.CANCELLED, orderId, new OrderEvents.Cancelled(orderId, 5, "a@example.com",
                "CUSTOMER", 400, "USD"));
        await().atMost(Duration.ofSeconds(30)).until(() -> counts(sku)[0] == 10);
        bus.awaitMatching(InventoryEvents.STOCK_CHANGED, sku, InventoryEvents.StockChanged.class, s -> s.available() == 10);
    }

    @Test
    void cancellingAnUnpaidOrderReleasesItsReservationOnce() throws Exception {
        String sku = stocked(10);
        String orderId = placeOrder(sku, 4);
        bus.await(InventoryEvents.RESERVED, orderId, InventoryEvents.Reserved.class);
        var cancelled = new OrderEvents.Cancelled(orderId, 5, "a@example.com", "TIMEOUT", 400, "USD");
        bus.publish(OrderEvents.CANCELLED, orderId, cancelled);
        bus.publish(OrderEvents.CANCELLED, orderId, cancelled);
        await().atMost(Duration.ofSeconds(30)).until(() -> counts(sku)[1] == 0);
        Thread.sleep(1000);
        assertThat(counts(sku)).containsExactly(10, 0);
    }

    @Test
    void stockCannotBeSetBelowWhatIsReserved() throws Exception {
        String sku = stocked(5);
        String orderId = placeOrder(sku, 4);
        bus.await(InventoryEvents.RESERVED, orderId, InventoryEvents.Reserved.class);
        mvc.perform(Edge.admin(put("/api/v1/backoffice/inventory/stock/" + sku), 1).contentType("application/json")
                .content("{\"onHand\":3}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BELOW_RESERVED"));
        mvc.perform(Edge.user(put("/api/v1/backoffice/inventory/stock/" + sku), 2).contentType("application/json")
                .content("{\"onHand\":50}")).andExpect(status().isForbidden());
    }
}
