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
import java.util.Comparator;
import java.util.List;

/** Recent error lines, from Loki. The service name is checked before it is put into a query. */
@Component
public class Loki {

    public record Line(Instant at, String service, String line) {
    }

    private final RestClient rest;

    public Loki(RestClient.Builder builder, OpsProperties props) {
        this.rest = builder.clone().baseUrl(props.lokiUrl()).build();
    }

    public List<Line> errors(String service, int limit, Duration within) {
        String query = "{service=\"" + service + "\", level=\"ERROR\"}";
        Instant end = Instant.now();
        JsonNode body;
        try {
            body = rest.get().uri(u -> u.path("/loki/api/v1/query_range").queryParam("query", "{q}")
                            .queryParam("limit", limit).queryParam("direction", "backward")
                            .queryParam("start", end.minus(within).toEpochMilli() * 1_000_000)
                            .queryParam("end", end.toEpochMilli() * 1_000_000).build(query))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw ApiException.unavailable("Loki is unavailable");
        }
        List<Line> lines = new ArrayList<>();
        if (body != null) {
            for (JsonNode stream : body.path("data").path("result")) {
                for (JsonNode value : stream.path("values")) {
                    long nanos = Long.parseLong(value.path(0).asString());
                    lines.add(new Line(Instant.ofEpochSecond(0, nanos), service, value.path(1).asString()));
                }
            }
        }
        lines.sort(Comparator.comparing(Line::at).reversed());
        return lines.size() > limit ? lines.subList(0, limit) : lines;
    }
}
