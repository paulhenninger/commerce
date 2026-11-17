package com.thirdstone.commerce.ops.instances;

import com.thirdstone.commerce.platform.web.ApiException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * DEV only: the controller, which can start and stop this deployment's
 * instances. Absent (null) anywhere else, and with no controller URL configured.
 */
@Component
public class ControllerClient {

    private final RestClient rest;

    ControllerClient(RestClient.Builder builder, Environment env) {
        String url = env.getProperty("commerce.controller.url", "");
        boolean dev = "dev".equalsIgnoreCase(env.getProperty("commerce.environment", "dev"));
        this.rest = dev && !url.isBlank() ? builder.clone().baseUrl(url).build() : null;
    }

    public boolean available() {
        return rest != null;
    }

    JsonNode instances() {
        if (rest == null) {
            return null;
        }
        try {
            return rest.get().uri("/instances").retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            return null;
        }
    }

    JsonNode act(String id, String action, String actor) {
        if (rest == null) {
            throw ApiException.notFound("Instance control");
        }
        try {
            return rest.post().uri("/instances/{id}/{action}", id, action).header("X-Commerce-Actor", actor)
                    .retrieve().body(JsonNode.class);
        } catch (HttpStatusCodeException e) {
            String message = e.getResponseBodyAsString();
            throw new ApiException(HttpStatus.valueOf(e.getStatusCode().value()),
                    message.contains("\"message\"") ? message.replaceAll(".*\"message\":\"([^\"]*)\".*", "$1")
                            : "The controller refused", "CONTROLLER_REFUSED");
        } catch (RestClientException e) {
            throw ApiException.unavailable("The controller is not answering");
        }
    }
}
