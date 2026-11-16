package com.thirdstone.commerce.platform.events;

/** What the billing service announces, on {@link Topics#BILLING}, keyed by order id. */
public final class BillingEvents {

    public static final String AUTHORIZED = "payment.authorized";
    public static final String FAILED = "payment.failed";
    public static final String REFUNDED = "payment.refunded";

    public static final String REASON_DECLINED = "PAYMENT_DECLINED";
    public static final String REASON_UNAVAILABLE = "PAYMENT_UNAVAILABLE";
    /** The request arrived after its deadline; nothing was charged. */
    public static final String REASON_EXPIRED = "EXPIRED";

    public record Authorized(String orderId, String paymentId, long amountCents, String currency,
                             String providerRef) {
        public Authorized {
            Contract.id(orderId, "orderId");
            Contract.id(paymentId, "paymentId");
            Contract.amount(amountCents, "amountCents");
            Contract.currency(currency);
            Contract.text(providerRef, "providerRef", 100);
        }
    }

    public record Failed(String orderId, String reason) {
        public Failed {
            Contract.id(orderId, "orderId");
            Contract.text(reason, "reason", 40);
        }
    }

    public record Refunded(String orderId, String paymentId, long amountCents, String currency) {
        public Refunded {
            Contract.id(orderId, "orderId");
            Contract.id(paymentId, "paymentId");
            Contract.amount(amountCents, "amountCents");
            Contract.currency(currency);
        }
    }

    private BillingEvents() {
    }
}
