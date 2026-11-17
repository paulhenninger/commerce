package com.thirdstone.commerce.simulators;

import com.thirdstone.commerce.platform.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A payment provider: charges and refunds, idempotent on {@code Idempotency-Key}
 * like a real one. Approves everything except the test tokens
 * {@code tok_decline} and {@code tok_insufficient}.
 */
@RestController
@RequestMapping("/payments/v1")
class PaymentSimulator {

    record ChargeRequest(String orderId, Long amountCents, String currency, String paymentMethod) {
    }

    record RefundRequest(String chargeId, Long amountCents) {
    }

    private final Faults faults;
    private final ApiKeyCheck apiKey;
    /** Idempotency key → the answer first given. */
    private final Map<String, ResponseEntity<Map<String, Object>>> answers =
            Collections.synchronizedMap(new Bounded<>(100_000));
    private final Map<String, Long> charges = Collections.synchronizedMap(new Bounded<>(100_000));

    PaymentSimulator(Faults faults, ApiKeyCheck apiKey) {
        this.faults = faults;
        this.apiKey = apiKey;
    }

    @PostMapping("/charges")
    ResponseEntity<Map<String, Object>> charge(@RequestHeader(value = "Authorization", required = false) String auth,
                                               @RequestHeader("Idempotency-Key") String key,
                                               @RequestBody ChargeRequest request) {
        apiKey.check(auth);
        faults.apply("payment", "charge");
        if (request.amountCents() == null || request.amountCents() < 0 || request.paymentMethod() == null) {
            throw ApiException.badRequest("amountCents and paymentMethod are required");
        }
        return answers.computeIfAbsent("charge|" + key, k -> {
            String method = request.paymentMethod();
            if (method.equals("tok_decline")) {
                return declined("DECLINED");
            }
            if (method.equals("tok_insufficient")) {
                return declined("INSUFFICIENT_FUNDS");
            }
            String id = "ch_" + UUID.randomUUID().toString().replace("-", "");
            charges.put(id, request.amountCents());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("status", "APPROVED");
            body.put("amountCents", request.amountCents());
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        });
    }

    @PostMapping("/refunds")
    ResponseEntity<Map<String, Object>> refund(@RequestHeader(value = "Authorization", required = false) String auth,
                                               @RequestHeader("Idempotency-Key") String key,
                                               @RequestBody RefundRequest request) {
        apiKey.check(auth);
        faults.apply("payment", "refund");
        return answers.computeIfAbsent("refund|" + key, k -> {
            Long charged = charges.get(request.chargeId());
            if (charged == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "NO_SUCH_CHARGE"));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", "re_" + UUID.randomUUID().toString().replace("-", ""));
            body.put("chargeId", request.chargeId());
            body.put("amountCents", request.amountCents() == null ? charged : request.amountCents());
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        });
    }

    private static ResponseEntity<Map<String, Object>> declined(String code) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(Map.of("code", code));
    }
}
