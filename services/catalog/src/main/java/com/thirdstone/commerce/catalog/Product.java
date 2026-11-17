package com.thirdstone.commerce.catalog;

import java.time.Instant;

public record Product(String sku, String name, String description, String category, long priceCents,
                      String currency, String imageKey, boolean active, int available, Instant createdAt,
                      Instant updatedAt) {
}
