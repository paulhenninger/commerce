package com.thirdstone.commerce.notification;

import com.thirdstone.commerce.platform.events.Line;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.ShippingEvents;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * The text of each email. Plain text, built only from the event: no lookups, so
 * an email can always be rendered, and nothing in it that the event did not
 * already carry. Addresses are never repeated in an email.
 */
@Component
class Templates {

    private final MailProperties props;

    Templates(MailProperties props) {
        this.props = props;
    }

    Email confirmed(OrderEvents.Confirmed e) {
        return new Email(e.email(), "order-confirmed", "Your order " + shortId(e.orderId()) + " is confirmed",
                greeting() + "Thank you for your order. We have your payment and are preparing it for dispatch.\n\n"
                        + lines(e.lines()) + "\nTotal: " + money(e.totalCents(), e.currency()) + "\n\n" + link(e.orderId()));
    }

    Email cancelled(OrderEvents.Cancelled e) {
        String why = switch (e.reason()) {
            case "CUSTOMER" -> "as you asked";
            case "OUT_OF_STOCK" -> "because we no longer have enough of an item in stock";
            case "PAYMENT_DECLINED" -> "because the payment was declined";
            case "PAYMENT_UNAVAILABLE" -> "because we could not reach the payment provider";
            case "TIMEOUT" -> "because we could not complete it in time";
            default -> "";
        };
        return new Email(e.email(), "order-cancelled", "Your order " + shortId(e.orderId()) + " is cancelled",
                greeting() + "Your order has been cancelled " + why + ". If you were charged, the money is on its way "
                        + "back to you.\n\n" + link(e.orderId()));
    }

    Email dispatched(ShippingEvents.Dispatched e) {
        return new Email(e.email(), "shipment-dispatched", "Your order " + shortId(e.orderId()) + " is on its way",
                greeting() + "Your order has left our warehouse with " + e.carrier() + ".\nTracking number: "
                        + e.trackingNumber() + "\n\n" + link(e.orderId()));
    }

    Email delivered(ShippingEvents.Delivered e) {
        return new Email(e.email(), "shipment-delivered", "Your order " + shortId(e.orderId()) + " has been delivered",
                greeting() + "Your order has been delivered. We hope you enjoy it.\n\nNot right? You can return it "
                        + "from your order page.\n\n" + link(e.orderId()));
    }

    Email returnLabel(ShippingEvents.ReturnLabelCreated e) {
        return new Email(e.email(), "return-label", "Your return label for order " + shortId(e.orderId()),
                greeting() + "We have booked the collection of your return with " + e.carrier() + ".\nReturn tracking "
                        + "number: " + e.trackingNumber() + "\n\nPlease have the parcel ready. We will refund you as soon "
                        + "as it reaches us.\n\n" + link(e.orderId()));
    }

    Email refunded(OrderEvents.Refunded e) {
        return new Email(e.email(), "order-refunded", "Your refund for order " + shortId(e.orderId()),
                greeting() + "Your return has arrived and we have refunded " + money(e.amountCents(), e.currency())
                        + ". It may take a few days to show on your statement.\n\n" + link(e.orderId()));
    }

    private static String greeting() {
        return "Hello,\n\n";
    }

    private String link(String orderId) {
        return "Your order: " + props.shopUrl() + "/orders/" + orderId + "\n\n-- The Commerce team\n";
    }

    private static String shortId(String orderId) {
        return orderId.substring(orderId.length() - 8);
    }

    private static String lines(List<Line> lines) {
        StringBuilder out = new StringBuilder();
        for (Line l : lines) {
            out.append("  ").append(l.quantity()).append(" × ").append(l.name()).append('\n');
        }
        return out.toString();
    }

    static String money(long cents, String currency) {
        return String.format(Locale.ROOT, "%s %d.%02d", currency, cents / 100, cents % 100);
    }
}
