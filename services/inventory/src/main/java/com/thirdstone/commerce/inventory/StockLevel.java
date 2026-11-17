package com.thirdstone.commerce.inventory;

import java.time.Instant;

public record StockLevel(String sku, int onHand, int reserved, int available, Instant updatedAt) {
}
