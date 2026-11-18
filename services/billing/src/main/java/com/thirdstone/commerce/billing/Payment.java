package com.thirdstone.commerce.billing;

import java.time.Instant;

public record Payment(String id, String orderId, long userId, long amountCents, String currency, String status,
                      String providerRef, String refundRef, String failureReason, Instant createdAt,
                      Instant updatedAt, int refundAttempts, String refundLastError) {
}
