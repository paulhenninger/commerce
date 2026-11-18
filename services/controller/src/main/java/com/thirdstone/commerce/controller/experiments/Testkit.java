package com.thirdstone.commerce.controller.experiments;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

/** The simulated shoppers and the chaos lab: the traffic and the injected faults of an experiment. */
@Component
class Testkit {

    static final Map<String, Object> STANDARD_LOAD = Map.of("count", 20, "ordersPerMinute", 60, "cancelRate", 0.1,
            "returnRate", 0.1, "declineRate", 0.05, "outOfStockRate", 0.02);

    private final RestClient rest;

    Testkit(RestClient.Builder builder, ExperimentProperties props) {
        this.rest = Clients.timed(builder).baseUrl(props.testkitUrl()).build();
    }

    JsonNode status() {
        return rest.get().uri("/status").retrieve().body(JsonNode.class);
    }

    void shoppers(Map<String, Object> settings) {
        rest.put().uri("/shoppers").body(settings).retrieve().toBodilessEntity();
    }

    void pauseOrders() {
        Map<String, Object> paused = new java.util.HashMap<>(STANDARD_LOAD);
        paused.put("ordersPerMinute", 0);
        shoppers(paused);
    }

    String startActor(String kind, String type, String topic, double rate) {
        JsonNode view = rest.post().uri("/actors")
                .body(Map.of("kind", kind, "type", type, "topic", topic, "ratePerSecond", rate))
                .retrieve().body(JsonNode.class);
        return view == null ? null : view.path("id").asString();
    }

    JsonNode actors() {
        return rest.get().uri("/actors").retrieve().body(JsonNode.class);
    }

    void stopAllActors() {
        rest.delete().uri("/actors").retrieve().toBodilessEntity();
    }

    void fault(String target, boolean outage, int latencyMs, double errorRate) {
        rest.put().uri("/faults/{t}", target).body(Map.of("outage", outage, "latencyMs", latencyMs,
                "errorRate", errorRate)).retrieve().toBodilessEntity();
    }

    JsonNode journal(Instant from, Instant to) {
        return rest.get().uri(u -> u.path("/journal").queryParam("from", from.toString()).queryParam("to", to.toString())
                .queryParam("limit", 100000).build()).retrieve().body(JsonNode.class);
    }
}
