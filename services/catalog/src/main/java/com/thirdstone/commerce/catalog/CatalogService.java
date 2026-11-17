package com.thirdstone.commerce.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.thirdstone.commerce.platform.events.CatalogEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.Outbox;
import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import com.thirdstone.commerce.platform.web.PageRequest;
import com.thirdstone.commerce.platform.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    /** Back-office changes, on their own logger so they can be shipped and kept as an audit trail. */
    private static final Logger audit = LoggerFactory.getLogger("commerce.audit");

    private final ProductRepository products;
    private final Outbox outbox;
    /**
     * Browsing is nearly all of the traffic and tolerates being a few seconds old.
     * Five seconds here, on top of the edge's and the browser's five, means MySQL
     * sees each distinct listing at most once per replica every five seconds
     * however many shoppers ask. Bounded, so odd queries cannot grow it.
     */
    private final Cache<String, Object> cache = Caffeine.newBuilder()
            .maximumSize(2_000).expireAfterWrite(Duration.ofSeconds(5)).build();

    CatalogService(ProductRepository products, Outbox outbox) {
        this.products = products;
        this.outbox = outbox;
    }

    @SuppressWarnings("unchecked")
    public PageResponse<Product> browse(String category, String q, PageRequest page) {
        String key = "list|" + category + "|" + q + "|" + page.page() + "|" + page.size();
        return (PageResponse<Product>) cache.get(key, k ->
                PageResponse.of(products.list(true, category, q, page), page, products.count(true, category, q)));
    }

    @SuppressWarnings("unchecked")
    public Product product(String sku) {
        Optional<Product> product = (Optional<Product>) cache.get("sku|" + sku,
                k -> products.find(sku).filter(Product::active));
        // A withdrawn product is indistinguishable from one that never existed.
        return product.orElseThrow(() -> ApiException.notFound("Product"));
    }

    @SuppressWarnings("unchecked")
    public List<String> categories() {
        return (List<String>) cache.get("categories", k -> products.categories());
    }

    public PageResponse<Product> all(String q, PageRequest page) {
        return PageResponse.of(products.list(false, null, q, page), page, products.count(false, null, q));
    }

    /** Creates or replaces a product, and tells the other services in the same transaction. */
    @Transactional
    public Product upsert(Caller admin, String sku, ProductInput input) {
        products.upsert(sku, input);
        outbox.append(Topics.CATALOG, CatalogEvents.PRODUCT_UPSERTED, sku, new CatalogEvents.ProductUpserted(
                sku, input.name(), input.priceCents(), input.currency(), input.active()));
        cache.invalidateAll();
        audit.atInfo().addKeyValue("action", "PRODUCT_UPSERTED").addKeyValue("actor", admin.userId())
                .addKeyValue("sku", sku).addKeyValue("priceCents", input.priceCents())
                .addKeyValue("active", input.active()).log("Product saved");
        return products.find(sku).orElseThrow();
    }

    /** From inventory's stock stream. Returns false if this catalog has no such product. */
    @Transactional
    public boolean stockChanged(String sku, int available) {
        boolean known = products.setAvailable(sku, available) > 0;
        if (known) {
            cache.invalidate("sku|" + sku);
        } else {
            log.atWarn().addKeyValue("sku", sku).log("Stock level for a product the catalog does not have");
        }
        return known;
    }
}
