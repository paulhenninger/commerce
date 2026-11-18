package com.thirdstone.commerce.controller.experiments;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

/** The testkit: the switches, the simulated shoppers and the chaos lab — an experiment's traffic and injected faults. */
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

    /** What is switched on now: test data, shoppers, actors, faults (ADR-033). */
    JsonNode switches() {
        return rest.get().uri("/switches").retrieve().body(JsonNode.class);
    }

    /** Makes that the state again: what is on and not in it goes off. */
    void restoreSwitches(JsonNode state) {
        rest.put().uri("/switches").body(state).retrieve().toBodilessEntity();
    }

    void testData(boolean on) {
        rest.put().uri("/test-data").body(Map.of("on", on)).retrieve().toBodilessEntity();
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
