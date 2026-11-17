package com.thirdstone.commerce.simulators;

import com.thirdstone.commerce.platform.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A carrier: books outbound and return shipments, idempotent on
 * {@code Idempotency-Key}, and reports a status that advances with time —
 * outbound BOOKED → DISPATCHED → DELIVERED, return BOOKED → IN_TRANSIT → RECEIVED.
 */
@RestController
@RequestMapping("/carrier/v1")
class CarrierSimulator {

    record BookRequest(String reference, String direction) {
    }

    record Shipment(String trackingNumber, String direction, Instant bookedAt) {
    }

    public static final String CARRIER = "SimShip";

    private final Faults faults;
    private final ApiKeyCheck apiKey;
    private final SimulatorProperties props;
    private final Map<String, Shipment> byKey = Collections.synchronizedMap(new Bounded<>(100_000));
    private final Map<String, Shipment> byTracking = Collections.synchronizedMap(new Bounded<>(100_000));

    CarrierSimulator(Faults faults, ApiKeyCheck apiKey, SimulatorProperties props) {
        this.faults = faults;
        this.apiKey = apiKey;
        this.props = props;
    }

    @PostMapping("/shipments")
    ResponseEntity<Map<String, Object>> book(@RequestHeader(value = "Authorization", required = false) String auth,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @RequestBody BookRequest request) {
        apiKey.check(auth);
        faults.apply("carrier", "book");
        String direction = "RETURN".equals(request.direction()) ? "RETURN" : "OUTBOUND";
        Shipment shipment = byKey.computeIfAbsent(key, k -> {
            String prefix = direction.equals("RETURN") ? "RET" : "SIM";
            Shipment s = new Shipment(prefix + ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L),
                    direction, Instant.now());
            byTracking.put(s.trackingNumber(), s);
            return s;
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("trackingNumber", shipment.trackingNumber(),
                "carrier", CARRIER, "status", status(shipment)));
    }

    @GetMapping("/shipments/{tracking}")
    Map<String, Object> track(@RequestHeader(value = "Authorization", required = false) String auth,
                              @PathVariable String tracking) {
        apiKey.check(auth);
        faults.apply("carrier", "track");
        Shipment shipment = byTracking.get(tracking);
        if (shipment == null) {
            throw ApiException.notFound("Shipment");
        }
        return Map.of("trackingNumber", tracking, "carrier", CARRIER, "status", status(shipment));
    }

    private String status(Shipment s) {
        Duration age = Duration.between(s.bookedAt(), Instant.now());
        boolean outbound = s.direction().equals("OUTBOUND");
        if (age.compareTo(props.carrier().deliverAfter()) >= 0) {
            return outbound ? "DELIVERED" : "RECEIVED";
        }
        if (age.compareTo(props.carrier().dispatchAfter()) >= 0) {
            return outbound ? "DISPATCHED" : "IN_TRANSIT";
        }
        return "BOOKED";
    }
}
