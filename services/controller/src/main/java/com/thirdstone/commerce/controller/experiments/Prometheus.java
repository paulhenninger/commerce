package com.thirdstone.commerce.controller.experiments;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Measurements for steady state and verdicts. */
@Component
class Prometheus {

    private final RestClient rest;

    Prometheus(RestClient.Builder builder, ExperimentProperties props) {
        this.rest = Clients.timed(builder).baseUrl(props.prometheusUrl()).build();
    }

    Map<String, Double> byLabel(String promql, String label) {
        JsonNode body = rest.get().uri(u -> u.path("/api/v1/query").queryParam("query", "{q}").build(promql))
                .retrieve().body(JsonNode.class);
        Map<String, Double> out = new LinkedHashMap<>();
        if (body != null) {
            for (JsonNode r : body.path("data").path("result")) {
                String v = r.path("value").path(1).asString("NaN");
                out.put(r.path("metric").path(label).asString(""), v.equals("NaN") ? Double.NaN : Double.parseDouble(v));
            }
        }
        return out;
    }

    double scalar(String promql) {
        Map<String, Double> v = byLabel(promql, "__none__");
        return v.isEmpty() ? 0 : v.values().iterator().next();
    }
}
