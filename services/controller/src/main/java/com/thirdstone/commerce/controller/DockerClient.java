package com.thirdstone.commerce.controller;

import com.thirdstone.commerce.platform.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Docker API, through docker-proxy (which refuses anything but listing and
 * the six lifecycle actions). Only this deployment's containers are ever listed.
 */
@Component
public class DockerClient {

    private final RestClient rest;
    private final ControllerProperties props;
    private final JsonMapper json;

    DockerClient(RestClient.Builder builder, ControllerProperties props, JsonMapper json) {
        this.rest = builder.clone().baseUrl(props.dockerUrl() + "/" + props.apiVersion()).build();
        this.props = props;
        this.json = json;
    }

    public List<Instance> list() {
        String filters = json.writeValueAsString(Map.of("label",
                List.of("com.docker.compose.project=" + props.project())));
        JsonNode containers;
        try {
            containers = rest.get().uri(u -> u.path("/containers/json").queryParam("all", "true")
                    .queryParam("filters", "{f}").build(filters)).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw ApiException.unavailable("Docker is not answering");
        }
        List<Instance> out = new ArrayList<>();
        if (containers == null) {
            return out;
        }
        for (JsonNode c : containers) {
            JsonNode labels = c.path("Labels");
            String service = labels.path("com.docker.compose.service").asString(null);
            if (labels.path("com.docker.compose.oneoff").asString("False").equals("True")) {
                continue;
            }
            Map<String, String> ips = new LinkedHashMap<>();
            c.path("NetworkSettings").path("Networks").properties().forEach(n -> {
                String ip = n.getValue().path("IPAddress").asString("");
                if (!ip.isEmpty()) {
                    // Network names carry the project prefix; the console wants the short one.
                    ips.put(n.getKey().replaceFirst("^" + java.util.regex.Pattern.quote(props.project()) + "_", ""), ip);
                }
            });
            String status = c.path("Status").asString("");
            String health = status.contains("(healthy)") ? "healthy" : status.contains("(unhealthy)") ? "unhealthy"
                    : status.contains("(health: starting)") ? "starting" : null;
            String name = c.path("Names").path(0).asString("").replaceFirst("^/", "");
            out.add(new Instance(c.path("Id").asString().substring(0, 12), name, service,
                    labels.path("com.docker.compose.container-number").asInt(1), c.path("State").asString(), status,
                    health, c.path("Created").asLong(), ips, InstancePolicy.controllable(service)));
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    public void act(String containerId, String action) {
        try {
            rest.post().uri(u -> u.path("/containers/{id}/{action}").queryParamIfPresent("t",
                            action.equals("stop") || action.equals("restart") ? java.util.Optional.of(10)
                                    : java.util.Optional.empty())
                    .build(containerId, action)).retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 304) {
                return; // Already in that state.
            }
            if (e.getStatusCode().value() == 409) {
                throw ApiException.conflict("Docker refused: " + e.getResponseBodyAsString().strip(), "DOCKER_CONFLICT");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Docker refused the action (" + e.getStatusCode().value() + ")",
                    "DOCKER_REFUSED");
        } catch (RestClientException e) {
            throw ApiException.unavailable("Docker is not answering");
        }
    }
}
