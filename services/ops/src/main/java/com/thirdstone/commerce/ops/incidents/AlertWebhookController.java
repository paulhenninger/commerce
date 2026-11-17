package com.thirdstone.commerce.ops.incidents;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Alertmanager's webhook. Under /internal, which no edge proxies; only the
 * telemetry network (Alertmanager, Prometheus, the services) can reach it.
 */
@RestController
class AlertWebhookController {

    private final IncidentService incidents;

    AlertWebhookController(IncidentService incidents) {
        this.incidents = incidents;
    }

    @PostMapping("/internal/alerts")
    Map<String, Integer> alerts(@RequestBody JsonNode payload) {
        return Map.of("recorded", incidents.record(payload));
    }
}
