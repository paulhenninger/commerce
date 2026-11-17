package com.thirdstone.commerce.ops;

import com.thirdstone.commerce.ops.incidents.Incident;
import com.thirdstone.commerce.ops.incidents.IncidentService;
import com.thirdstone.commerce.ops.kafka.DeadLetterReplay;
import com.thirdstone.commerce.ops.kafka.KafkaInspector;
import com.thirdstone.commerce.ops.kafka.LiveFeed;
import com.thirdstone.commerce.ops.metrics.Loki;
import com.thirdstone.commerce.ops.metrics.Prometheus;
import com.thirdstone.commerce.ops.metrics.SystemView;
import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import com.thirdstone.commerce.platform.web.PageRequest;
import com.thirdstone.commerce.platform.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ops console's API. Administrators only: the edge requires an administrator
 * with a two-factor session for /api/v1/ops, and every method checks the role
 * again.
 */
@RestController
@RequestMapping("/api/v1/ops")
class OpsController {

    private final SystemView system;
    private final KafkaInspector kafka;
    private final DeadLetterReplay replay;
    private final IncidentService incidents;
    private final LiveFeed live;
    private final OpsProperties props;
    private final Environment env;

    OpsController(SystemView system, KafkaInspector kafka, DeadLetterReplay replay, IncidentService incidents,
                  LiveFeed live, OpsProperties props, Environment env) {
        this.system = system;
        this.kafka = kafka;
        this.replay = replay;
        this.incidents = incidents;
        this.live = live;
        this.props = props;
        this.env = env;
    }

    @GetMapping("/config")
    Map<String, Object> config(Caller caller) {
        caller.requireAdmin();
        String environment = system.environment();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("environment", environment);
        config.put("testkit", "dev".equals(environment) && !env.getProperty("commerce.testkit.url", "").isBlank());
        config.put("grafanaUrl", props.grafanaUrl());
        config.put("services", props.services());
        config.put("replayRequiresMfa", props.replayRequiresMfa());
        return config;
    }

    @GetMapping("/overview")
    SystemView.Overview overview(Caller caller) {
        caller.requireAdmin();
        return system.overview();
    }

    @GetMapping("/services/{name}")
    SystemView.ServiceDetail service(Caller caller, @PathVariable String name) {
        caller.requireAdmin();
        return system.service(name);
    }

    @GetMapping("/services/{name}/series")
    List<Prometheus.Series> serviceSeries(Caller caller, @PathVariable String name, @RequestParam String metric,
                                          @RequestParam(required = false) String range) {
        caller.requireAdmin();
        return system.serviceSeries(name, metric, range);
    }

    @GetMapping("/services/{name}/errors")
    List<Loki.Line> errors(Caller caller, @PathVariable String name) {
        caller.requireAdmin();
        return system.errors(name);
    }

    @GetMapping("/business")
    Map<String, Object> business(Caller caller) {
        caller.requireAdmin();
        return system.business();
    }

    @GetMapping("/business/series")
    List<Prometheus.Series> businessSeries(Caller caller, @RequestParam String metric,
                                           @RequestParam(required = false) String range) {
        caller.requireAdmin();
        return system.businessSeries(metric, range);
    }

    @GetMapping("/kafka")
    KafkaInspector.Cluster kafka(Caller caller) {
        caller.requireAdmin();
        return kafka.cluster();
    }

    @GetMapping("/kafka/dead-letters/{topic}")
    List<KafkaInspector.DeadLetter> deadLetters(Caller caller, @PathVariable String topic,
                                                @RequestParam(required = false) Integer limit) {
        caller.requireAdmin();
        return kafka.deadLetters(topic, limit == null ? 50 : Math.clamp(limit, 1, 200));
    }

    record ReplayRequest(int partition, long offset) {
    }

    @PostMapping("/kafka/dead-letters/{topic}/replay")
    DeadLetterReplay.Replayed replay(Caller caller, @PathVariable String topic, @RequestBody ReplayRequest request) {
        return replay.replay(caller, topic, request.partition(), request.offset());
    }

    @GetMapping("/incidents")
    PageResponse<Incident> incidents(Caller caller, @RequestParam(required = false) String status,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        caller.requireAdmin();
        return incidents.list(status, PageRequest.of(page, size));
    }

    @GetMapping("/incidents/{id}")
    Incident incident(Caller caller, @PathVariable long id) {
        caller.requireAdmin();
        return incidents.get(id);
    }

    @PostMapping("/incidents/{id}/ack")
    Incident acknowledge(Caller caller, @PathVariable long id) {
        caller.requireAdmin();
        return incidents.acknowledge(caller, id);
    }

    record NoteRequest(@NotBlank @Size(max = 4000) String note) {
    }

    @PostMapping("/incidents/{id}/notes")
    Incident note(Caller caller, @PathVariable long id, @Valid @RequestBody NoteRequest request) {
        caller.requireAdmin();
        return incidents.note(caller, id, request.note().strip());
    }

    @GetMapping(path = "/live", produces = "text/event-stream")
    SseEmitter live(Caller caller) {
        caller.requireAdmin();
        return live.subscribe();
    }

    /** DEV only, from milestone 7: the testkit's API. Absent anywhere else. */
    @RequestMapping("/testkit/**")
    void testkit(Caller caller) {
        caller.requireAdmin();
        throw ApiException.notFound("Testkit");
    }
}
