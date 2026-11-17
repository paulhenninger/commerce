package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.events.CatalogEvents;
import com.thirdstone.commerce.platform.testing.CommerceContainers;
import com.thirdstone.commerce.platform.testing.Edge;
import com.thirdstone.commerce.platform.testing.EventBus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

abstract class OrderTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CommerceContainers.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    final EventBus bus = EventBus.shared();
    final JsonMapper json = JsonMapper.builder().build();

    /** A product the order service knows, as the catalog would announce it. */
    String product(long priceCents) {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        bus.publish(CatalogEvents.PRODUCT_UPSERTED, sku,
                new CatalogEvents.ProductUpserted(sku, "Product " + sku, priceCents, "USD", true));
        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.sql("SELECT COUNT(*) FROM products WHERE sku = ? AND price_cents = ?").params(sku, priceCents)
                        .query(Integer.class).single() == 1);
        return sku;
    }

    static String order(String sku, int quantity, long expectedTotal, String paymentMethod) {
        return """
                {"lines":[{"sku":"%s","quantity":%d}],
                 "shippingAddress":{"name":"Ada Example","line1":"1 Test Street","city":"Springfield",
                                    "postalCode":"12345","country":"US"},
                 "paymentMethod":"%s","expectedTotalCents":%d}""".formatted(sku, quantity, paymentMethod, expectedTotal);
    }

    ResultActions place(long userId, String body, String key) throws Exception {
        return mvc.perform(Edge.user(post("/api/v1/orders"), userId).header("Idempotency-Key", key)
                .contentType("application/json").content(body));
    }

    String placed(long userId, String body) throws Exception {
        String response = place(userId, body, UUID.randomUUID().toString()).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        return node.get("id").asString();
    }

    String statusOf(String orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = ?").param(orderId).query(String.class).single();
    }
}
