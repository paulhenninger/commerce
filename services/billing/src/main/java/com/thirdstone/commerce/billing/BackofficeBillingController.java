package com.thirdstone.commerce.billing;

import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** Read-only payment lookup for support and the ledger audit. Administrators only. */
@RestController
@RequestMapping("/api/v1/backoffice/billing")
class BackofficeBillingController {

    private static final Pattern ORDER_ID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

    private final BillingService billing;

    BackofficeBillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/payments")
    List<Payment> payments(Caller caller, @RequestParam(required = false) String orderId,
                           @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        caller.requireAdmin();
        if (orderId != null) {
            if (!ORDER_ID.matcher(orderId).matches()) {
                throw ApiException.badRequest("Not an order id");
            }
            return billing.search(orderId, null, null);
        }
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(1)) : from;
        if (Duration.between(start, end).compareTo(Duration.ofDays(1)) > 0 || end.isBefore(start)) {
            throw ApiException.badRequest("Ask for at most one day at a time");
        }
        return billing.search(null, start, end);
    }
}
