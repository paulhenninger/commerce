package com.thirdstone.commerce.shipping;

import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/** Read-only shipment lookup for support and the ledger audit. Administrators only. Addresses are not shown. */
@RestController
@RequestMapping("/api/v1/backoffice/shipping")
class BackofficeShippingController {

    private static final Pattern ORDER_ID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

    record ShipmentView(String id, String orderId, String direction, String status, String carrier,
                        String trackingNumber, int attempts, String lastError, java.time.Instant createdAt,
                        java.time.Instant updatedAt) {
    }

    private final ShippingService shipping;

    BackofficeShippingController(ShippingService shipping) {
        this.shipping = shipping;
    }

    @GetMapping("/shipments")
    List<ShipmentView> shipments(Caller caller, @RequestParam String orderId) {
        caller.requireAdmin();
        if (!ORDER_ID.matcher(orderId).matches()) {
            throw ApiException.badRequest("Not an order id");
        }
        return shipping.forOrder(orderId).stream().map(s -> new ShipmentView(s.id(), s.orderId(), s.direction(),
                s.status(), s.carrier(), s.trackingNumber(), s.attempts(), s.lastError(), s.createdAt(),
                s.updatedAt())).toList();
    }
}
