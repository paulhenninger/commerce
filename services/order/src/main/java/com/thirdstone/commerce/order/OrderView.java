package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.events.Address;
import com.thirdstone.commerce.platform.events.Line;

import java.time.Instant;
import java.util.List;

/** An order as its owner (or an administrator) sees it. The payment token is never shown. */
public record OrderView(String id, String status, long totalCents, String currency, Address shippingAddress,
                        String cancelReason, String trackingNumber, String returnTrackingNumber,
                        Instant createdAt, Instant updatedAt, Instant deliveredAt, List<Line> lines,
                        List<HistoryEntry> history) {

    public record HistoryEntry(String status, String detail, Instant at) {
    }

    /** One row of a list: enough to show and link to it. */
    public record Summary(String id, String status, long totalCents, String currency, int items, Instant createdAt) {
    }
}
