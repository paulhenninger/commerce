package com.thirdstone.commerce.testkit;

import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.testkit.chaos.Actor;
import com.thirdstone.commerce.testkit.chaos.ChaosLab;
import com.thirdstone.commerce.testkit.journal.Journal;
import com.thirdstone.commerce.testkit.kafka.ContractTap;
import com.thirdstone.commerce.testkit.shop.Seeder;
import com.thirdstone.commerce.testkit.shop.Shoppers;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The testkit's API. Reached only through the ops service's proxy (which checks
 * that the caller is an administrator, in dev): the testkit is on the control
 * network and nothing else can reach it. Everything that switches a simulation
 * on or off goes through {@link Switches}, which remembers it (ADR-033).
 */
@RestController
class TestkitController {

    private final Shoppers shoppers;
    private final ChaosLab lab;
    private final Journal journal;
    private final ContractTap tap;
    private final Seeder seeder;
    private final Switches switches;
    private final RestClient simulators;

    record TestData(boolean on) {
    }

    TestkitController(Shoppers shoppers, ChaosLab lab, Journal journal, ContractTap tap, Seeder seeder,
                      Switches switches, RestClient.Builder builder, TestkitProperties props) {
        this.shoppers = shoppers;
        this.lab = lab;
        this.journal = journal;
        this.tap = tap;
        this.seeder = seeder;
        this.switches = switches;
        this.simulators = builder.clone().baseUrl(props.simulatorsUrl()).build();
    }

    @GetMapping("/status")
    Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("adminReady", seeder.adminReady());
        status.put("testData", seeder.testData());
        status.put("seeded", seeder.seeded());
        status.put("switches", switches.state());
        status.put("shoppers", shoppers.status());
        status.put("actors", lab.list());
        status.put("contractViolations", tap.violations());
        status.put("eventsChecked", tap.checked());
        status.put("recent", journal.latest(30));
        return status;
    }

    @GetMapping("/switches")
    Switches.State switches() {
        return switches.state();
    }

    /** The whole state at once: what is on and not listed goes off. */
    @PutMapping("/switches")
    Switches.State switches(@RequestBody Switches.State state) {
        return switches.apply(state);
    }

    @PutMapping("/test-data")
    Switches.State testData(@RequestBody TestData body) {
        return switches.testData(body.on());
    }

    @PutMapping("/shoppers")
    Shoppers.Settings shoppers(@RequestBody Shoppers.Settings settings) {
        return switches.shoppers(settings).shoppers();
    }

    @GetMapping("/actors")
    List<Actor.View> actors() {
        return lab.list();
    }

    @PostMapping("/actors")
    ResponseEntity<Actor.View> start(@RequestBody ChaosLab.Start start) {
        return ResponseEntity.status(HttpStatus.CREATED).body(switches.startActor(start));
    }

    @DeleteMapping("/actors/{id}")
    ResponseEntity<Void> stop(@PathVariable String id) {
        return switches.stopActor(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/actors")
    ResponseEntity<Void> stopAll() {
        switches.stopAllActors();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/faults")
    Object faults() {
        return simulators.get().uri("/admin/faults").retrieve().body(Object.class);
    }

    @PutMapping("/faults/{target}")
    Object fault(@PathVariable String target, @RequestBody Map<String, Object> setting) {
        if (!Switches.FAULT_TARGETS.contains(target)) {
            throw ApiException.notFound("Simulator");
        }
        return switches.fault(target, setting);
    }

    @GetMapping("/journal")
    List<Journal.Entry> journal(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                @RequestParam(required = false) Integer limit) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofMinutes(15)) : from;
        return journal.between(start, end, limit == null ? 10_000 : Math.clamp(limit, 1, 100_000));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
