package com.thirdstone.commerce.catalog;

import com.thirdstone.commerce.platform.events.CatalogEvents;
import com.thirdstone.commerce.platform.events.InventoryEvents;
import com.thirdstone.commerce.platform.testing.CommerceContainers;
import com.thirdstone.commerce.platform.testing.Edge;
import com.thirdstone.commerce.platform.testing.EventBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CommerceContainers.register(registry);
    }

    @Autowired MockMvc mvc;
    final EventBus bus = EventBus.shared();

    private static String product(String name, long priceCents, boolean active) {
        return """
                {"name":"%s","description":"A test product","category":"kitchen","priceCents":%d,
                 "currency":"USD","imageKey":"mugs/blue.svg","active":%s}""".formatted(name, priceCents, active);
    }

    private void save(String sku, String name, long priceCents, boolean active) throws Exception {
        mvc.perform(Edge.admin(put("/api/v1/backoffice/catalog/products/" + sku), 1)
                        .contentType("application/json").content(product(name, priceCents, active)))
                .andExpect(status().isOk());
    }

    @Test
    void savedProductIsAnnouncedAndBrowsable() throws Exception {
        save("MUG-ONE", "First mug", 1250, true);
        CatalogEvents.ProductUpserted event = bus.await(CatalogEvents.PRODUCT_UPSERTED, "MUG-ONE",
                CatalogEvents.ProductUpserted.class);
        assertThat(event).isEqualTo(new CatalogEvents.ProductUpserted("MUG-ONE", "First mug", 1250, "USD", true));

        mvc.perform(get("/api/v1/catalog/products/MUG-ONE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=5, public"))
                .andExpect(jsonPath("$.priceCents").value(1250))
                .andExpect(jsonPath("$.available").value(0));
        mvc.perform(get("/api/v1/catalog/products").param("q", "First mug"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("MUG-ONE"));
        mvc.perform(get("/api/v1/catalog/categories")).andExpect(jsonPath("$[0]").value("kitchen"));
    }

    @Test
    void priceChangeIsAnnouncedWithTheNewPrice() throws Exception {
        save("MUG-PRICE", "Priced mug", 1000, true);
        save("MUG-PRICE", "Priced mug", 1100, true);
        bus.awaitMatching(CatalogEvents.PRODUCT_UPSERTED, "MUG-PRICE", CatalogEvents.ProductUpserted.class,
                e -> e.priceCents() == 1100);
        assertThat(bus.events(CatalogEvents.PRODUCT_UPSERTED, "MUG-PRICE", CatalogEvents.ProductUpserted.class))
                .extracting(CatalogEvents.ProductUpserted::priceCents).containsExactly(1000L, 1100L);
    }

    @Test
    void withdrawnProductDisappearsFromTheShopButNotTheBackOffice() throws Exception {
        save("MUG-GONE", "Withdrawn mug", 900, false);
        mvc.perform(get("/api/v1/catalog/products/MUG-GONE")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/catalog/products").param("q", "Withdrawn"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(Edge.admin(get("/api/v1/backoffice/catalog/products"), 1).param("q", "Withdrawn"))
                .andExpect(jsonPath("$.items[0].sku").value("MUG-GONE"))
                .andExpect(jsonPath("$.items[0].active").value(false));
    }

    @Test
    void stockEventsUpdateWhatTheShopShows() throws Exception {
        save("MUG-STOCK", "Stocked mug", 1250, true);
        bus.publish(InventoryEvents.STOCK_CHANGED, "MUG-STOCK", new InventoryEvents.StockChanged("MUG-STOCK", 17));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(Edge.admin(get("/api/v1/backoffice/catalog/products"), 1).param("q", "Stocked mug"))
                        .andExpect(jsonPath("$.items[0].available").value(17)));
    }

    @Test
    void backOfficeIsClosedToCustomersAndToAnyoneNotComingThroughTheEdge() throws Exception {
        String body = product("Sneaky", 1, true);
        mvc.perform(Edge.user(put("/api/v1/backoffice/catalog/products/SNEAKY"), 7)
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        // Headers claiming to be an administrator, without the edge's token.
        mvc.perform(put("/api/v1/backoffice/catalog/products/SNEAKY")
                        .header("X-Auth-User-Id", "1").header("X-Auth-Role", "ADMIN").header("X-Auth-Strong", "true")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/backoffice/catalog/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/catalog/products/SNEAKY")).andExpect(status().isNotFound());
    }

    @Test
    void invalidProductsAreRejectedFieldByField() throws Exception {
        mvc.perform(Edge.admin(put("/api/v1/backoffice/catalog/products/BAD-ONE"), 1).contentType("application/json")
                        .content("{\"name\":\"\",\"category\":\"x\",\"priceCents\":-5,\"currency\":\"usd\",\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.priceCents").exists())
                .andExpect(jsonPath("$.fields.currency").exists());
        mvc.perform(Edge.admin(put("/api/v1/backoffice/catalog/products/bad sku"), 1).contentType("application/json")
                .content(product("Fine", 1, true))).andExpect(status().isBadRequest());
    }

    @Test
    void searchTreatsWildcardsLiterally() throws Exception {
        save("PCT-ONE", "100% cotton towel", 500, true);
        save("PCT-TWO", "Plain towel", 500, true);
        mvc.perform(get("/api/v1/catalog/products").param("q", "100%"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/catalog/products").param("q", "%").param("size", "1000"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.size").value(50));
    }
}
