package com.thirdstone.commerce.shipping;

import com.thirdstone.commerce.platform.data.Ids;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.ShippingEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.EventContext;
import com.thirdstone.commerce.platform.messaging.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

/**
 * Event handlers only record what is to be done; ShipmentWorker does it. A
 * handler that called the carrier would hold up its partition — every other
 * order's events behind it — for as long as the carrier was slow or down.
 */
@Service
public class ShippingService {

    private static final Logger log = LoggerFactory.getLogger(ShippingService.class);

    private final ShipmentRepository shipments;
    private final Outbox outbox;
    private final JsonMapper json;

    ShippingService(ShipmentRepository shipments, Outbox outbox, JsonMapper json) {
        this.shipments = shipments;
        this.outbox = outbox;
        this.json = json;
    }

    void orderConfirmed(OrderEvents.Confirmed order) {
        if (shipments.insert(Ids.next(), order.orderId(), "OUTBOUND", order.userId(), order.email(),
                json.writeValueAsString(order.shippingAddress())) == 0) {
            EventContext.ignored("shipment exists");
        }
    }

    void returnRequested(OrderEvents.ReturnRequested order) {
        if (shipments.insert(Ids.next(), order.orderId(), "RETURN", order.userId(), order.email(),
                json.writeValueAsString(order.pickupAddress())) == 0) {
            EventContext.ignored("return exists");
        }
    }

    void orderCancelled(OrderEvents.Cancelled order) {
        if (shipments.cancelOutbound(order.orderId()) == 0) {
            // Nothing booked yet, or already on its way: a parcel that has left is
            // handled as a return by support (architecture, section 6).
            EventContext.ignored("no cancellable shipment");
        } else {
            log.atInfo().addKeyValue("orderId", order.orderId()).log("Shipment cancelled");
        }
    }

    /** The carrier booked it: announce the tracking number. Runs in the worker's transaction. */
    void booked(Shipment s, CarrierClient.Booking booking, Duration pollIn) {
        if (shipments.advance(s.id(), "PENDING", "CREATED", booking.carrier(), booking.trackingNumber(), pollIn) == 0) {
            return;
        }
        if (s.outbound()) {
            outbox.append(Topics.SHIPPING, ShippingEvents.CREATED, s.orderId(), new ShippingEvents.Created(s.orderId(),
                    s.id(), booking.carrier(), booking.trackingNumber()));
        } else {
            outbox.append(Topics.SHIPPING, ShippingEvents.RETURN_LABEL_CREATED, s.orderId(),
                    new ShippingEvents.ReturnLabelCreated(s.orderId(), s.userId(), s.email(), booking.carrier(),
                            booking.trackingNumber()));
        }
        log.atInfo().addKeyValue("orderId", s.orderId()).addKeyValue("direction", s.direction()).log("Shipment booked");
    }

    /**
     * The carrier reports where the parcel is. Steps are announced in order even
     * if the carrier skipped one between two polls (booked, then already delivered).
     */
    void tracked(Shipment s, String carrierStatus, Duration pollIn) {
        List<String> path = s.outbound() ? List.of("CREATED", "DISPATCHED", "DELIVERED")
                : List.of("CREATED", "IN_TRANSIT", "RECEIVED");
        int from = path.indexOf(s.status());
        int to = path.indexOf(carrierStatus);
        if (from < 0 || to <= from) {
            shipments.advance(s.id(), s.status(), s.status(), null, null, pollIn);
            return;
        }
        if (shipments.advance(s.id(), s.status(), carrierStatus, null, null, pollIn) == 0) {
            return;
        }
        for (int step = from + 1; step <= to; step++) {
            announce(s, path.get(step));
        }
    }

    private void announce(Shipment s, String status) {
        switch (status) {
            case "DISPATCHED" -> outbox.append(Topics.SHIPPING, ShippingEvents.DISPATCHED, s.orderId(),
                    new ShippingEvents.Dispatched(s.orderId(), s.userId(), s.email(), s.carrier(), s.trackingNumber()));
            case "DELIVERED" -> outbox.append(Topics.SHIPPING, ShippingEvents.DELIVERED, s.orderId(),
                    new ShippingEvents.Delivered(s.orderId(), s.userId(), s.email(), s.trackingNumber()));
            case "RECEIVED" -> outbox.append(Topics.SHIPPING, ShippingEvents.RETURN_RECEIVED, s.orderId(),
                    new ShippingEvents.ReturnReceived(s.orderId(), s.trackingNumber()));
            default -> {
                // IN_TRANSIT: nobody needs telling.
            }
        }
        log.atInfo().addKeyValue("orderId", s.orderId()).addKeyValue("status", status).log("Shipment moved on");
    }

    public List<Shipment> forOrder(String orderId) {
        return shipments.forOrder(orderId);
    }
}
