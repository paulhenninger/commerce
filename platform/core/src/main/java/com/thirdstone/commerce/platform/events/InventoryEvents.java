package com.thirdstone.commerce.platform.events;

/**
 * What the inventory service announces: reservation outcomes on
 * {@link Topics#INVENTORY} (keyed by order id) and stock levels on
 * {@link Topics#STOCK} (keyed by SKU).
 */
public final class InventoryEvents {

    public static final String RESERVED = "inventory.reserved";
    public static final String REJECTED = "inventory.rejected";
    public static final String STOCK_CHANGED = "inventory.stock-changed";

    public static final String REASON_OUT_OF_STOCK = "OUT_OF_STOCK";

    /** Every line of the order is held for it. */
    public record Reserved(String orderId) {
        public Reserved {
            Contract.id(orderId, "orderId");
        }
    }

    /** Nothing is held: {@code sku} is the first line that could not be covered. */
    public record Rejected(String orderId, String reason, String sku) {
        public Rejected {
            Contract.id(orderId, "orderId");
            Contract.text(reason, "reason", 40);
            Contract.id(sku, "sku");
        }
    }

    /** How many units of a product can be ordered right now (on hand minus reserved). */
    public record StockChanged(String sku, Integer available) {
        public StockChanged {
            Contract.id(sku, "sku");
            Contract.present(available, "available");
        }
    }

    private InventoryEvents() {
    }
}
