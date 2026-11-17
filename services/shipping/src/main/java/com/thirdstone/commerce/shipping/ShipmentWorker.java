package com.thirdstone.commerce.shipping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Books pending shipments with the carrier and follows booked ones until they
 * arrive. Replicas share the work (SKIP LOCKED leases); a failed call backs off,
 * doubling up to five minutes, and is tried again — a carrier outage delays
 * parcels and nothing else.
 */
@Component
class ShipmentWorker {

    private static final Logger log = LoggerFactory.getLogger(ShipmentWorker.class);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final ShipmentRepository shipments;
    private final ShippingService shipping;
    private final CarrierClient carrier;
    private final ShippingProperties props;
    private final TransactionTemplate tx;

    ShipmentWorker(ShipmentRepository shipments, ShippingService shipping, CarrierClient carrier,
                   ShippingProperties props, PlatformTransactionManager transactions) {
        this.shipments = shipments;
        this.shipping = shipping;
        this.carrier = carrier;
        this.props = props;
        this.tx = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${commerce.shipping.worker-interval-millis:2000}")
    void work() {
        List<String> due = tx.execute(s -> shipments.claimDue(20, LEASE));
        if (due == null) {
            return;
        }
        for (String id : due) {
            Shipment s = shipments.find(id).orElse(null);
            if (s == null) {
                continue;
            }
            try {
                // Outside any transaction: the carrier may be slow.
                if (s.status().equals("PENDING")) {
                    CarrierClient.Booking booking = carrier.book(s.orderId(), s.direction());
                    tx.executeWithoutResult(t -> shipping.booked(s, booking, props.shipping().pollInterval()));
                } else {
                    String status = carrier.status(s.trackingNumber());
                    tx.executeWithoutResult(t -> shipping.tracked(s, status, props.shipping().pollInterval()));
                }
            } catch (CarrierUnavailableException e) {
                Duration retry = backoff(s.attempts());
                shipments.failed(s.id(), e.getMessage(), retry);
                log.atWarn().addKeyValue("orderId", s.orderId()).addKeyValue("attempts", s.attempts() + 1)
                        .addKeyValue("retryInSeconds", retry.toSeconds()).log("Carrier call failed: {}", e.getMessage());
            }
        }
    }

    static Duration backoff(int attempts) {
        long seconds = 1L << Math.min(attempts, 9);
        return seconds > MAX_BACKOFF.toSeconds() ? MAX_BACKOFF : Duration.ofSeconds(seconds);
    }
}
