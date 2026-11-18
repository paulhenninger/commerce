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
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
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
    private final TestkitProxy testkit;
    private final com.thirdstone.commerce.ops.instances.InstancesView instancesView;
    private final com.thirdstone.commerce.ops.instances.ControllerClient controllerClient;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpsController.class);

    OpsController(SystemView system, KafkaInspector kafka, DeadLetterReplay replay, IncidentService incidents,
                  LiveFeed live, OpsProperties props, Environment env, TestkitProxy testkit,
                  com.thirdstone.commerce.ops.instances.InstancesView instancesView,
                  com.thirdstone.commerce.ops.instances.ControllerClient controllerClient) {
        this.testkit = testkit;
        this.instancesView = instancesView;
        this.controllerClient = controllerClient;
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
        config.put("testkit", testkit.available());
        config.put("instanceControl", controllerClient.available());
        config.put("experiments", controllerClient.available());
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

    @GetMapping("/instances")
    com.thirdstone.commerce.ops.instances.InstancesView.Instances instances(Caller caller) {
        caller.requireAdmin();
        return instancesView.instances();
    }

    /** DEV only: stop, start, kill, pause, unpause or restart one instance. Recorded as an incident note. */
    @PostMapping("/instances/{id}/{action}")
    tools.jackson.databind.JsonNode act(Caller caller, @PathVariable String id, @PathVariable String action) {
        return instancesView.act(caller, id, action);
    }

    // ------------------------------------------------- resilience proofs (DEV only)

    private static final java.util.regex.Pattern EXPERIMENT = java.util.regex.Pattern.compile("^[a-z0-9-]{1,64}$");
    private static final java.util.regex.Pattern RUN = java.util.regex.Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    record ExperimentNames(List<String> names) {
    }

    @GetMapping("/experiments")
    tools.jackson.databind.JsonNode experiments(Caller caller) {
        caller.requireAdmin();
        return controllerClient.experiments("");
    }

    @GetMapping("/experiments/runs")
    tools.jackson.databind.JsonNode experimentRuns(Caller caller) {
        caller.requireAdmin();
        return controllerClient.experiments("/runs");
    }

    @GetMapping("/experiments/runs/{id}")
    tools.jackson.databind.JsonNode experimentRun(Caller caller, @PathVariable String id) {
        caller.requireAdmin();
        return controllerClient.experiments("/runs/" + checked(RUN, id));
    }

    /** The run as Markdown, to attach to a ticket or a pull request. */
    @GetMapping(path = "/experiments/runs/{id}/report", produces = "text/markdown")
    String experimentReport(Caller caller, @PathVariable String id) {
        caller.requireAdmin();
        return controllerClient.experimentReport(checked(RUN, id));
    }

    @PostMapping("/experiments/{name}/run")
    tools.jackson.databind.JsonNode runExperiment(Caller caller, @PathVariable String name) {
        caller.requireAdmin();
        tools.jackson.databind.JsonNode runs = controllerClient.startExperiments("/" + checked(EXPERIMENT, name) + "/run",
                null, caller.email());
        incidents.operatorAction(caller.email(), "Experiment", null, caller.email() + " started experiment " + name);
        return runs;
    }

    /** Runs the named experiments in order, or with no names the whole catalogue. */
    @PostMapping("/experiments/run")
    tools.jackson.databind.JsonNode runExperiments(Caller caller, @RequestBody(required = false) ExperimentNames body) {
        caller.requireAdmin();
        List<String> names = body == null || body.names() == null ? List.of() : body.names();
        if (names.size() > 100) {
            throw ApiException.badRequest("At most 100 experiments at once");
        }
        names.forEach(n -> checked(EXPERIMENT, n));
        tools.jackson.databind.JsonNode runs = controllerClient.startExperiments("/run-all", Map.of("names", names),
                caller.email());
        incidents.operatorAction(caller.email(), "Experiment", null, caller.email() + " started "
                + (names.isEmpty() ? "the whole catalogue" : names.size() + " experiments: " + String.join(", ", names)));
        return runs;
    }

    private static String checked(java.util.regex.Pattern pattern, String value) {
        if (!pattern.matcher(value).matches()) {
            throw ApiException.notFound("Experiment");
        }
        return value;
    }

    /** DEV only: the testkit's API (shoppers, chaos lab). Absent (404) anywhere else. */
    @RequestMapping("/testkit/**")
    ResponseEntity<byte[]> testkit(Caller caller, HttpServletRequest request,
                                   @RequestBody(required = false) byte[] body) {
        caller.requireAdmin();
        String path = request.getRequestURI().substring("/api/v1/ops/testkit".length());
        if (!request.getMethod().equals("GET")) {
            log.atWarn().addKeyValue("actor", caller.userId()).addKeyValue("method", request.getMethod())
                    .addKeyValue("path", path).log("Testkit changed from the console");
        }
        return testkit.forward(request.getMethod(), path.isEmpty() ? "/status" : path, request.getQueryString(), body);
    }
}
