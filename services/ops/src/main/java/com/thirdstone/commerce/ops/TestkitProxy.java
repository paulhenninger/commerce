package com.thirdstone.commerce.ops;

import com.thirdstone.commerce.platform.web.ApiException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.regex.Pattern;

/**
 * DEV only: the console's way to the testkit (simulated shoppers, chaos lab).
 * Anywhere but dev, or with no testkit configured, the path does not exist (404),
 * so a deployed console cannot even tell it is missing something.
 */
@Component
class TestkitProxy {

    private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9/_.-]{0,200}$");
    private static final Pattern QUERY = Pattern.compile("^[A-Za-z0-9=&%._:+-]{0,500}$");

    private final RestClient rest;
    private final String base;

    TestkitProxy(RestClient.Builder builder, Environment env) {
        String url = env.getProperty("commerce.testkit.url", "");
        boolean dev = "dev".equalsIgnoreCase(env.getProperty("commerce.environment", "dev"));
        this.rest = dev && !url.isBlank() ? builder.clone().build() : null;
        this.base = url.replaceAll("/+$", "");
    }

    boolean available() {
        return rest != null;
    }

    ResponseEntity<byte[]> forward(String method, String path, String query, byte[] body) {
        if (rest == null) {
            throw ApiException.notFound("Testkit");
        }
        if (!PATH.matcher(path).matches() || path.contains("..") || (query != null && !QUERY.matcher(query).matches())) {
            throw ApiException.badRequest("Not a testkit path");
        }
        // The query string arrives already encoded: a URI, not a template, so it
        // is passed on as it is and not encoded a second time.
        java.net.URI uri = java.net.URI.create(base + path + (query == null || query.isEmpty() ? "" : "?" + query));
        try {
            var request = rest.method(HttpMethod.valueOf(method)).uri(uri);
            if (body != null && body.length > 0) {
                request = request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON).body(res.getBody().readAllBytes()));
        } catch (RestClientException e) {
            throw ApiException.unavailable("The testkit is not answering");
        }
    }
}
