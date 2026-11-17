package com.thirdstone.commerce.ops.metrics;

import com.thirdstone.commerce.ops.OpsProperties;
import com.thirdstone.commerce.platform.web.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus's HTTP API. Every query is built here from a fixed template and
 * values checked against fixed lists: no PromQL from a client ever reaches
 * Prometheus.
 */
@Component
public class Prometheus {

    public record Sample(Map<String, String> labels, double value) {
        public String label(String name) {
            return labels.getOrDefault(name, "");
        }
    }

    public record Series(Map<String, String> labels, List<double[]> points) {
    }

    private final RestClient rest;

    public Prometheus(RestClient.Builder builder, OpsProperties props) {
        this.rest = builder.clone().baseUrl(props.prometheusUrl()).build();
    }

    public List<Sample> query(String promql) {
        JsonNode body = call(() -> rest.get().uri(u -> u.path("/api/v1/query").queryParam("query", "{q}").build(promql))
                .retrieve().body(JsonNode.class));
        List<Sample> out = new ArrayList<>();
        for (JsonNode result : body.path("data").path("result")) {
            out.add(new Sample(labels(result.path("metric")), number(result.path("value").path(1).asString())));
        }
        return out;
    }

    /** The value of a query that yields one number, or NaN. */
    public double scalar(String promql) {
        List<Sample> samples = query(promql);
        return samples.isEmpty() ? Double.NaN : samples.getFirst().value();
    }

    /** label value → number, for a query aggregated by one label. */
    public Map<String, Double> byLabel(String promql, String label) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Sample s : query(promql)) {
            out.put(s.label(label), s.value());
        }
        return out;
    }

    public List<Series> range(String promql, Duration range) {
        Instant end = Instant.now();
        Instant start = end.minus(range);
        // About 120 points whatever the range, and never finer than the scrape.
        long step = Math.max(5, range.toSeconds() / 120);
        JsonNode body = call(() -> rest.get().uri(u -> u.path("/api/v1/query_range").queryParam("query", "{q}")
                        .queryParam("start", start.getEpochSecond()).queryParam("end", end.getEpochSecond())
                        .queryParam("step", step).build(promql))
                .retrieve().body(JsonNode.class));
        List<Series> out = new ArrayList<>();
        for (JsonNode result : body.path("data").path("result")) {
            List<double[]> points = new ArrayList<>();
            for (JsonNode point : result.path("values")) {
                points.add(new double[]{point.path(0).asDouble(), number(point.path(1).asString())});
            }
            out.add(new Series(labels(result.path("metric")), points));
        }
        return out;
    }

    private static Map<String, String> labels(JsonNode metric) {
        Map<String, String> labels = new LinkedHashMap<>();
        metric.properties().forEach(e -> labels.put(e.getKey(), e.getValue().asString()));
        return labels;
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException | NullPointerException e) {
            return Double.NaN;
        }
    }

    private static JsonNode call(java.util.function.Supplier<JsonNode> call) {
        try {
            JsonNode body = call.get();
            if (body == null) {
                throw ApiException.unavailable("Prometheus gave no answer");
            }
            return body;
        } catch (RestClientException e) {
            // The console shows "metrics unavailable"; the store is unaffected.
            throw ApiException.unavailable("Prometheus is unavailable");
        }
    }
}
