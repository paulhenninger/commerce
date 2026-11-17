package com.thirdstone.commerce.platform.events;

/** What the shipping service announces, on {@link Topics#SHIPPING}, keyed by order id. */
public final class ShippingEvents {

    public static final String CREATED = "shipment.created";
    public static final String DISPATCHED = "shipment.dispatched";
    public static final String DELIVERED = "shipment.delivered";
    public static final String RETURN_LABEL_CREATED = "shipment.return-label-created";
    public static final String RETURN_RECEIVED = "shipment.return-received";

    /** The carrier has accepted the booking. */
    public record Created(String orderId, String shipmentId, String carrier, String trackingNumber) {
        public Created {
            Contract.id(orderId, "orderId");
            Contract.id(shipmentId, "shipmentId");
            Contract.text(carrier, "carrier", 40);
            Contract.text(trackingNumber, "trackingNumber", 64);
        }
    }

    public record Dispatched(String orderId, long userId, String email, String carrier, String trackingNumber) {
        public Dispatched {
            Contract.id(orderId, "orderId");
            Contract.positive(userId, "userId");
            Contract.text(email, "email", 254);
            Contract.text(carrier, "carrier", 40);
            Contract.text(trackingNumber, "trackingNumber", 64);
        }
    }

    public record Delivered(String orderId, long userId, String email, String trackingNumber) {
        public Delivered {
            Contract.id(orderId, "orderId");
            Contract.positive(userId, "userId");
            Contract.text(email, "email", 254);
            Contract.text(trackingNumber, "trackingNumber", 64);
        }
    }

    public record ReturnLabelCreated(String orderId, long userId, String email, String carrier,
                                     String trackingNumber) {
        public ReturnLabelCreated {
            Contract.id(orderId, "orderId");
            Contract.positive(userId, "userId");
            Contract.text(email, "email", 254);
            Contract.text(carrier, "carrier", 40);
            Contract.text(trackingNumber, "trackingNumber", 64);
        }
    }

    /** The returned parcel is back at the warehouse. */
    public record ReturnReceived(String orderId, String trackingNumber) {
        public ReturnReceived {
            Contract.id(orderId, "orderId");
            Contract.text(trackingNumber, "trackingNumber", 64);
        }
    }

    private ShippingEvents() {
    }
}
