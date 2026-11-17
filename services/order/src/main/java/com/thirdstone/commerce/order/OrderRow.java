package com.thirdstone.commerce.order;

import java.time.Instant;

record OrderRow(String id, long userId, String email, String status, long totalCents, String currency,
                String paymentMethod, String shippingAddressJson, String cancelReason, String trackingNumber,
                String returnTrackingNumber, Instant createdAt, Instant updatedAt, Instant deliveredAt) {
}
