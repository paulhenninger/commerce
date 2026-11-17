package com.thirdstone.commerce.shipping;

import java.time.Instant;

public record Shipment(String id, String orderId, String direction, String status, long userId, String email,
                       String addressJson, String carrier, String trackingNumber, int attempts, String lastError,
                       Instant nextAttemptAt, Instant createdAt, Instant updatedAt) {

    public boolean outbound() {
        return "OUTBOUND".equals(direction);
    }
}
