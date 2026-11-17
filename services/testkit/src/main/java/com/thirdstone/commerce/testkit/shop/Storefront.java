package com.thirdstone.commerce.testkit.shop;

import com.thirdstone.commerce.testkit.TestkitProperties;
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

    Storefront(RestClient.Builder builder, TestkitProperties props, JsonMapper json) {
        this.rest = builder.clone().baseUrl(props.storefrontUrl()).build();
        this.json = json;
    }

    public Answer call(String method, String path, String ip, String token, Object body, Map<String, String> headers) {
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
