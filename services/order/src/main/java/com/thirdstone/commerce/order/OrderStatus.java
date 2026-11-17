package com.thirdstone.commerce.order;

import java.util.List;

/** The states of architecture section 6, as stored. */
public final class OrderStatus {

    public static final String PENDING = "PENDING";
    public static final String AWAITING_PAYMENT = "AWAITING_PAYMENT";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String SHIPPED = "SHIPPED";
    public static final String DELIVERED = "DELIVERED";
    public static final String RETURN_REQUESTED = "RETURN_REQUESTED";
    public static final String RETURNED = "RETURNED";
    public static final String REFUNDED = "REFUNDED";
    public static final String CANCELLED = "CANCELLED";

    /** Waiting on another service: these are what the saga timeout and load shedding count. */
    public static final List<String> IN_FLIGHT = List.of(PENDING, AWAITING_PAYMENT);
    /** What a customer may still cancel. */
    public static final List<String> CANCELLABLE = List.of(PENDING, AWAITING_PAYMENT, CONFIRMED);
    public static final List<String> ALL = List.of(PENDING, AWAITING_PAYMENT, CONFIRMED, SHIPPED, DELIVERED,
            RETURN_REQUESTED, RETURNED, REFUNDED, CANCELLED);

    private OrderStatus() {
    }
}
