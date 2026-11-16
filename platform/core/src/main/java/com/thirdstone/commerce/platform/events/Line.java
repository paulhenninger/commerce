package com.thirdstone.commerce.platform.events;

/** One line of an order: the product as it was sold, so later events need no lookup. */
public record Line(String sku, String name, int quantity, long unitPriceCents) {

    public Line {
        Contract.id(sku, "sku");
        Contract.text(name, "name", 200);
        if (quantity < 1 || quantity > 1000) {
            throw new IllegalArgumentException("quantity out of range");
        }
        Contract.amount(unitPriceCents, "unitPriceCents");
    }
}
