package com.thirdstone.commerce.platform.events;

/** What the catalog service announces, on {@link Topics#CATALOG}, keyed by SKU. */
public final class CatalogEvents {

    public static final String PRODUCT_UPSERTED = "product.upserted";

    /** The whole current state of a product, so a consumer can simply overwrite its copy. */
    public record ProductUpserted(String sku, String name, long priceCents, String currency, Boolean active) {
        public ProductUpserted {
            Contract.id(sku, "sku");
            Contract.text(name, "name", 200);
            Contract.amount(priceCents, "priceCents");
            Contract.currency(currency);
            Contract.present(active, "active");
        }
    }

    private CatalogEvents() {
    }
}
