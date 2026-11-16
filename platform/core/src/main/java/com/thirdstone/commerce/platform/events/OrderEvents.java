package com.thirdstone.commerce.platform.events;

import java.time.Instant;
import java.util.List;

/**
 * What the order service announces, on {@link Topics#ORDER}, keyed by order id.
 * Each event carries what its consumers need to act (event-carried state
 * transfer), so none of them has to ask the order service anything.
 */
public final class OrderEvents {

    public static final String PLACED = "order.placed";
    public static final String PAYMENT_REQUESTED = "order.payment-requested";
    public static final String CONFIRMED = "order.confirmed";
    public static final String CANCELLED = "order.cancelled";
    public static final String RETURN_REQUESTED = "order.return-requested";
    public static final String RETURNED = "order.returned";
    public static final String REFUNDED = "order.refunded";

    public static final String REASON_CUSTOMER = "CUSTOMER";
    public static final String REASON_TIMEOUT = "TIMEOUT";

    public record Placed(String orderId, long userId, String email, List<Line> lines, long totalCents,
                         String currency) {
        public Placed {
            Contract.id(orderId, "orderId");
            Contract.positive(userId, "userId");
            Contract.text(email, "email", 254);
            lines = Contract.lines(lines);
            Contract.amount(totalCents, "totalCents");
            Contract.currency(currency);
        }
    }

    /**
     * @param expiresAt the moment the order gives up waiting; a request seen after
     *                  it must not be charged
     */
    public record PaymentRequested(String orderId, long userId, long amountCents, String currency,
                                   String paymentMethod, Instant expiresAt) {
        public PaymentRequested {
            Contract.id(orderId, "orderId");
            Contract.positive(userId, "userId");
            Contract.amount(amountCents, "amountCents");
            Contract.currency(currency);
            Contract.text(paymentMethod, "paymentMethod", 64);
            Contract.present(expiresAt, "expiresAt");
        }
    }

    public record Confirmed(String orderId, long userId, String email, List<Line> lines, long totalCents,
                            String currency, Address shippingAddress) {
        public Confirmed {
            Contract.id(orderId, "orderId");
            Contract.positive(userId, "userId");
            Contract.text(email, "email", 254);
            lines = Contract.lines(lines);
            Contract.amount(totalCents, "totalCents");
            Contract.currency(currency);
            Contract.present(shippingAddress, "shippingAddress");
        }
    }

    public record Cancelled(String orderId, long userId, String email, String reason, long totalCents,
                            String currency) {
        public Cancelled {
            Contract.id(orderId, "orderId");
            Contract.positive(userId, "userId");
            Contract.text(email, "email", 254);
            Contract.text(reason, "reason", 40);
            Contract.amount(totalCents, "totalCents");
            Contract.currency(currency);
        }
    }

    public record ReturnRequested(String orderId, long userId, String email, List<Line> lines,
                                  Address pickupAddress, String reason) {
        public ReturnRequested {
            Contract.id(orderId, "orderId");
            Contract.positive(userId, "userId");
            Contract.text(email, "email", 254);
            lines = Contract.lines(lines);
            Contract.present(pickupAddress, "pickupAddress");
            Contract.text(reason, "reason", 500);
        }
    }

    /** The returned goods are back: restock them and refund the customer. */
    public record Returned(String orderId, long userId, String email, List<Line> lines, long totalCents,
                           String currency) {
        public Returned {
            Contract.id(orderId, "orderId");
            Contract.positive(userId, "userId");
            Contract.text(email, "email", 254);
            lines = Contract.lines(lines);
            Contract.amount(totalCents, "totalCents");
            Contract.currency(currency);
        }
    }

    public record Refunded(String orderId, long userId, String email, long amountCents, String currency) {
        public Refunded {
            Contract.id(orderId, "orderId");
            Contract.positive(userId, "userId");
            Contract.text(email, "email", 254);
            Contract.amount(amountCents, "amountCents");
            Contract.currency(currency);
        }
    }

    private OrderEvents() {
    }
}
