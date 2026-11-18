package com.thirdstone.commerce.controller.experiments;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The resilience proofs API, for the ops console and scripts/prove.sh (through the ops service). */
@RestController
@RequestMapping("/experiments")
class ExperimentController {

    record Entry(String name, String title, String hypothesis, boolean needsHa, long observeSeconds) {
    }

    record RunRequest(List<String> names) {
    }

    private final ExperimentRunner runner;

    ExperimentController(ExperimentRunner runner) {
        this.runner = runner;
    }

    @GetMapping
    List<Entry> catalogue() {
        return runner.catalogue().stream().map(e -> new Entry(e.name(), e.title(), e.hypothesis(), e.needsHa(),
                e.observe().toSeconds())).toList();
    }

    @PostMapping("/{name}/run")
    List<Run> start(@PathVariable String name) {
        return runner.start(List.of(name));
    }

    /** Runs the named experiments in order, or the whole catalogue. */
    @PostMapping("/run-all")
    List<Run> runAll(@RequestBody(required = false) RunRequest request) {
        List<String> names = request == null || request.names() == null || request.names().isEmpty()
                ? runner.catalogue().stream().map(Experiment::name).toList() : request.names();
        return runner.start(names);
    }

    @GetMapping("/runs")
    Map<String, Object> runs() {
        Run current = runner.current();
        return Map.of("current", current == null ? "" : current.id, "runs", runner.runs());
    }

    @GetMapping("/runs/{id}")
    Run get(@PathVariable String id) {
        return runner.run(id);
    }

    @GetMapping(value = "/runs/{id}/report", produces = MediaType.TEXT_PLAIN_VALUE)
    String report(@PathVariable String id) {
        return runner.run(id).markdown();
    }
}
