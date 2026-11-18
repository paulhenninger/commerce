package com.thirdstone.commerce.testkit.shop;

import com.thirdstone.commerce.testkit.TestkitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * The storefront edge, called the way the SPA calls it: same paths, same
 * headers, real tokens. Each caller presents its own client address
 * ({@code X-Forwarded-For}), which the DEV edge believes from the testkit alone,
 * so per-IP limits apply per simulated shopper.
 *
 * Every call is timed as the customer sees it — through the edge, its cache and
 * its auth check — as {@code commerce_testkit_request_seconds{route, status}},
 * the measurement the latency targets are about (architecture §8, milestone 11).
 */
@Component
public class Storefront {

    /** A response: status and parsed JSON body (or null). */
    public record Answer(int status, JsonNode body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String text(String field) {
            return body == null ? null : body.path(field).asString(null);
        }
    }

    private final RestClient rest;
    private final JsonMapper json;
    private final MeterRegistry registry;

    Storefront(RestClient.Builder builder, TestkitProperties props, JsonMapper json, MeterRegistry registry) {
        this.rest = builder.clone().baseUrl(props.storefrontUrl()).build();
        this.json = json;
        this.registry = registry;
    }

    /** A fixed name for what a call is, never the path itself (ids would make unbounded labels). */
    static String route(String method, String path) {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (p.equals("/api/v1/catalog/products")) return "catalog.list";
        if (p.startsWith("/api/v1/catalog/")) return "catalog.other";
        if (p.equals("/api/v1/orders")) return method.equals("POST") ? "order.place" : "order.list";
        if (p.startsWith("/api/v1/orders/")) {
            return p.endsWith("/cancel") ? "order.cancel" : p.endsWith("/return") ? "order.return" : "order.get";
        }
        if (p.startsWith("/api/v1/auth/")) return "auth";
        if (p.startsWith("/api/v1/backoffice/")) return "backoffice";
        return "other";
    }

    public Answer call(String method, String path, String ip, String token, Object body, Map<String, String> headers) {
        long start = System.nanoTime();
        String status = "error";
        try {
            Answer answer = send(method, path, ip, token, body, headers);
            status = (answer.status() / 100) + "xx";
            return answer;
        } finally {
            Timer.builder("commerce.testkit.request").tag("route", route(method, path)).tag("status", status)
                    .publishPercentileHistogram().register(registry)
                    .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private Answer send(String method, String path, String ip, String token, Object body, Map<String, String> headers) {
        var request = rest.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .header("X-Forwarded-For", ip)
                .header("X-Commerce-Client", "web")
                .header("User-Agent", "commerce-testkit");
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        if (headers != null) {
            for (var h : headers.entrySet()) {
                request = request.header(h.getKey(), h.getValue());
            }
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
        }
        return request.exchange((req, res) -> {
            HttpStatusCode status = res.getStatusCode();
            byte[] bytes = res.getBody().readAllBytes();
            JsonNode node = null;
            if (bytes.length > 0 && (bytes[0] == '{' || bytes[0] == '[')) {
                try {
                    node = json.readTree(bytes);
                } catch (RuntimeException ignored) {
                    // Not JSON after all; keep the status.
                }
            }
            return new Answer(status.value(), node);
        });
    }

    public Answer get(String path, String ip, String token) {
        return call("GET", path, ip, token, null, null);
    }

    public Answer post(String path, String ip, String token, Object body) {
        return call("POST", path, ip, token, body, null);
    }

    public Answer put(String path, String ip, String token, Object body) {
        return call("PUT", path, ip, token, body, null);
    }
}
