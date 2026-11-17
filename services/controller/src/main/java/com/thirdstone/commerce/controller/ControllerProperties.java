package com.thirdstone.commerce.controller;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("commerce.controller")
public record ControllerProperties(String dockerUrl, String apiVersion, String project) {

    public ControllerProperties {
        if (project == null || project.isBlank()) {
            throw new IllegalStateException("commerce.controller.project (COMPOSE_PROJECT) is not set: "
                    + "the controller must know which deployment it may touch");
        }
    }
}
