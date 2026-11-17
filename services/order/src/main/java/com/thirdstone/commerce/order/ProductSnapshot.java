package com.thirdstone.commerce.order;

record ProductSnapshot(String sku, String name, long priceCents, String currency, boolean active) {
}
