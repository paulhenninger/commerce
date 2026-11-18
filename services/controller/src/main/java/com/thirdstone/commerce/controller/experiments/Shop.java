package com.thirdstone.commerce.controller.experiments;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The back office, as the seeded administrator, through the storefront edge —
 * the same read-only APIs support uses. Paced, so an audit stays well inside the
 * edge's per-address rate limit.
 */
@Component
class Shop implements LedgerAudit.BackOffice {

    private static final long PACE_MILLIS = 40;

    private final RestClient rest;
    private final ExperimentProperties props;
    private volatile String token;
    private long last;

    Shop(RestClient.Builder builder, ExperimentProperties props) {
        // No client metrics: every audited order is its own URI, which would be its own tag.
        this.rest = Clients.timed(builder).baseUrl(props.storefrontUrl()).defaultHeader("X-Commerce-Client", "web")
                .observationRegistry(io.micrometer.observation.ObservationRegistry.NOOP).build();
        this.props = props;
    }

    @Override
    public synchronized JsonNode get(String path) {
        long wait = PACE_MILLIS - (System.currentTimeMillis() - last);
        if (wait > 0) {
            sleep(wait);
        }
        last = System.currentTimeMillis();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (token == null) {
                signIn();
            }
            var answer = rest.get().uri(path).header("Authorization", "Bearer " + token)
                    .exchange((req, res) -> new Object[]{res.getStatusCode().value(),
                            res.getStatusCode().is2xxSuccessful() ? res.bodyTo(JsonNode.class) : null});
            int status = (int) answer[0];
            if (status == 401) {
                token = null;
                continue;
            }
            if (status == 429 || status >= 500) {
                sleep(1000);
                continue;
            }
            if (status == 404) {
                return null;
            }
            return (JsonNode) answer[1];
        }
        throw new IllegalStateException("The back office did not answer " + path);
    }

    @Override
    public List<JsonNode> all(String path) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 0; page < 200; page++) {
            JsonNode body = get(path + (path.contains("?") ? "&" : "?") + "size=50&page=" + page);
            if (body == null) {
                break;
            }
            body.path("items").forEach(out::add);
            if (body.path("items").size() < 50) {
                break;
            }
        }
        return out;
    }

    private void signIn() {
        JsonNode body = rest.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", props.adminEmail(), "password", props.adminPassword()))
                .retrieve().body(JsonNode.class);
        token = body == null ? null : body.path("accessToken").asString(null);
        if (token == null) {
            throw new IllegalStateException("The administrator could not sign in");
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
