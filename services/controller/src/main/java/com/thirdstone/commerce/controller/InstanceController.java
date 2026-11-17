package com.thirdstone.commerce.controller;

import com.thirdstone.commerce.platform.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The instances API, for the ops service only (control network). An id is always
 * looked up in a fresh list of this deployment's containers — never trusted from
 * the request — and acted on only if its service is controllable.
 */
@RestController
class InstanceController {

    private static final Logger log = LoggerFactory.getLogger(InstanceController.class);

    private final DockerClient docker;

    InstanceController(DockerClient docker) {
        this.docker = docker;
    }

    @GetMapping("/instances")
    List<Instance> instances() {
        return docker.list();
    }

    @PostMapping("/instances/{id}/{action}")
    Instance act(@PathVariable String id, @PathVariable String action,
                 @RequestHeader(value = "X-Commerce-Actor", required = false) String actor) {
        if (!InstancePolicy.ACTIONS.contains(action)) {
            throw ApiException.notFound("Action");
        }
        Instance target = docker.list().stream().filter(i -> i.id().equals(id) || i.name().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Instance"));
        if (!target.controllable()) {
            throw new ApiException(HttpStatus.FORBIDDEN, target.service() + " may not be controlled: it is what "
                    + "lets you see and undo what happens", "NOT_CONTROLLABLE");
        }
        docker.act(target.id(), action);
        log.atWarn().addKeyValue("actor", actor).addKeyValue("action", action).addKeyValue("instance", target.name())
                .log("Instance controlled");
        return docker.list().stream().filter(i -> i.id().equals(target.id())).findFirst().orElse(target);
    }
}
