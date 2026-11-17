package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.events.Address;
import com.thirdstone.commerce.platform.events.BillingEvents;
import com.thirdstone.commerce.platform.events.InventoryEvents;
import com.thirdstone.commerce.platform.events.Line;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.ShippingEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.EventContext;
import com.thirdstone.commerce.platform.messaging.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.List;

/**
 * The order's side of the saga (architecture, section 6): each event from another
 * service moves an order on — one conditional update, a history row and, where
 * the next step needs it, an event, all in the caller's transaction. An event that
 * finds the order somewhere else (already cancelled, never placed here) changes
 * nothing and is counted as ignored.
 */
@Component
class OrderSaga {

    private static final Logger log = LoggerFactory.getLogger(OrderSaga.class);

    private final OrderRepository orders;
    private final Outbox outbox;
    private final OrderMetrics metrics;
    private final OrderProperties props;
    private final JsonMapper json;

    OrderSaga(OrderRepository orders, Outbox outbox, OrderMetrics metrics, OrderProperties props, JsonMapper json) {
        this.orders = orders;
        this.outbox = outbox;
        this.metrics = metrics;
        this.props = props;
        this.json = json;
    }

    void stockReserved(InventoryEvents.Reserved event) {
        if (!move(event.orderId(), OrderStatus.AWAITING_PAYMENT, List.of(OrderStatus.PENDING), "Stock reserved")) {
            return;
        }
        OrderRow order = orders.find(event.orderId()).orElseThrow();
        outbox.append(Topics.ORDER, OrderEvents.PAYMENT_REQUESTED, order.id(), new OrderEvents.PaymentRequested(
                order.id(), order.userId(), order.totalCents(), order.currency(), order.paymentMethod(),
                // The deadline travels with the request: billing will not charge
                // after the moment this order gives up waiting.
                order.createdAt().plus(props.sagaTimeout())));
    }

    void stockRejected(InventoryEvents.Rejected event) {
        cancel(event.orderId(), event.reason(), List.of(OrderStatus.PENDING), "Not enough stock of " + event.sku());
    }

    void paymentAuthorized(BillingEvents.Authorized event) {
        if (!move(event.orderId(), OrderStatus.CONFIRMED, List.of(OrderStatus.AWAITING_PAYMENT), "Payment authorized")) {
            // E.g. the order timed out while the charge was in flight: the
            // cancellation that already went out makes billing refund it.
            return;
        }
        OrderRow order = orders.find(event.orderId()).orElseThrow();
        outbox.append(Topics.ORDER, OrderEvents.CONFIRMED, order.id(), new OrderEvents.Confirmed(order.id(),
                order.userId(), order.email(), orders.lines(order.id()), order.totalCents(), order.currency(),
                address(order)));
        metrics.settled(order, OrderStatus.CONFIRMED, null);
        metrics.revenue(order.totalCents(), order.currency());
    }

    void paymentFailed(BillingEvents.Failed event) {
        cancel(event.orderId(), event.reason(), List.of(OrderStatus.AWAITING_PAYMENT), "Payment failed: " + event.reason());
    }

    void paymentRefunded(BillingEvents.Refunded event) {
        String detail = "Refund of " + event.amountCents() + " " + event.currency() + " issued";
        if (move(event.orderId(), OrderStatus.REFUNDED, List.of(OrderStatus.RETURNED), detail)) {
            OrderRow order = orders.find(event.orderId()).orElseThrow();
            outbox.append(Topics.ORDER, OrderEvents.REFUNDED, order.id(), new OrderEvents.Refunded(order.id(),
                    order.userId(), order.email(), event.amountCents(), event.currency()));
            metrics.refund(event.amountCents(), event.currency());
            metrics.reached("refunded");
            return;
        }
        // A cancelled order's money coming back: worth a line in its history.
        OrderRow order = orders.find(event.orderId()).orElse(null);
        if (order == null) {
            EventContext.ignored("unknown order");
            return;
        }
        orders.history(order.id(), order.status(), detail);
        metrics.refund(event.amountCents(), event.currency());
    }

    void shipmentCreated(ShippingEvents.Created event) {
        if (orders.setTracking(event.orderId(), event.trackingNumber()) == 0) {
            EventContext.ignored("unknown order");
            return;
        }
        OrderRow order = orders.find(event.orderId()).orElseThrow();
        orders.history(order.id(), order.status(), "Shipment booked with " + event.carrier() + ": " + event.trackingNumber());
    }

    void shipmentDispatched(ShippingEvents.Dispatched event) {
        move(event.orderId(), OrderStatus.SHIPPED, List.of(OrderStatus.CONFIRMED), "Dispatched by " + event.carrier());
    }

    void shipmentDelivered(ShippingEvents.Delivered event) {
        if (orders.markDelivered(event.orderId()) == 0) {
            EventContext.ignored("order not shipped");
            return;
        }
        orders.history(event.orderId(), OrderStatus.DELIVERED, "Delivered");
    }

    void returnLabelCreated(ShippingEvents.ReturnLabelCreated event) {
        if (orders.setReturnTracking(event.orderId(), event.trackingNumber()) == 0) {
            EventContext.ignored("unknown order");
            return;
        }
        orders.history(event.orderId(), OrderStatus.RETURN_REQUESTED, "Return label created: " + event.trackingNumber());
    }

    void returnReceived(ShippingEvents.ReturnReceived event) {
        if (!move(event.orderId(), OrderStatus.RETURNED, List.of(OrderStatus.RETURN_REQUESTED), "Return received")) {
            return;
        }
        metrics.reached("returned");
        OrderRow order = orders.find(event.orderId()).orElseThrow();
        outbox.append(Topics.ORDER, OrderEvents.RETURNED, order.id(), new OrderEvents.Returned(order.id(),
                order.userId(), order.email(), orders.lines(order.id()), order.totalCents(), order.currency()));
    }

    /**
     * Cancels an order that is still in one of {@code from}, and tells every service
     * to undo its part. Returns false if the order was elsewhere.
     */
    boolean cancel(String orderId, String reason, Collection<String> from, String detail) {
        if (orders.cancel(orderId, reason, from) == 0) {
            EventContext.ignored("order not in " + from);
            return false;
        }
        OrderRow order = orders.find(orderId).orElseThrow();
        orders.history(orderId, OrderStatus.CANCELLED, detail);
        outbox.append(Topics.ORDER, OrderEvents.CANCELLED, orderId, new OrderEvents.Cancelled(orderId,
                order.userId(), order.email(), reason, order.totalCents(), order.currency()));
        metrics.settled(order, OrderStatus.CANCELLED, reason);
        log.atInfo().addKeyValue("orderId", orderId).addKeyValue("reason", reason).log("Order cancelled");
        return true;
    }

    private boolean move(String orderId, String to, Collection<String> from, String detail) {
        if (orders.transition(orderId, to, from) == 0) {
            EventContext.ignored("order not in " + from);
            return false;
        }
        orders.history(orderId, to, detail);
        log.atInfo().addKeyValue("orderId", orderId).addKeyValue("status", to).log("Order moved on");
        return true;
    }

    Address address(OrderRow order) {
        return json.readValue(order.shippingAddressJson(), Address.class);
    }

    List<Line> lines(String orderId) {
        return orders.lines(orderId);
    }
}
