package com.thirdstone.commerce.inventory;

import java.time.Instant;

public record Reservation(String orderId, String sku, int quantity, String status, Instant createdAt,
                          Instant updatedAt) {
}
