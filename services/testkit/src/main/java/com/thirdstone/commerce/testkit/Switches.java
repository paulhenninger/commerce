package com.thirdstone.commerce.testkit;

import com.thirdstone.commerce.testkit.chaos.Actor;
import com.thirdstone.commerce.testkit.chaos.ChaosLab;
import com.thirdstone.commerce.testkit.shop.Seeder;
import com.thirdstone.commerce.testkit.shop.Shoppers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The DEV data simulations, each off until someone switches it on (ADR-033):
 * test data, simulated shoppers, chaos actors and the simulators' faults. Every
 * change goes through here, so what is on is always what is on disk; a restart
 * re-applies it, and a fresh volume starts with everything off. Switching off
 * stops a simulation and keeps what it made.
 */
@Component
public class Switches {

    private static final Logger log = LoggerFactory.getLogger(Switches.class);
    public static final List<String> FAULT_TARGETS = List.of("payment", "carrier");
    static final Map<String, Object> NO_FAULT = Map.of("outage", false, "latencyMs", 0, "errorRate", 0.0);

    /** Everything that is switched on. Also the body of {@code PUT /switches}: the whole state at once. */
    public record State(boolean testData, Shoppers.Settings shoppers, List<ChaosLab.Start> actors,
                        Map<String, Map<String, Object>> faults) {

        public static State off() {
            Map<String, Map<String, Object>> faults = new LinkedHashMap<>();
            FAULT_TARGETS.forEach(t -> faults.put(t, NO_FAULT));
            return new State(false, Shoppers.Settings.OFF, List.of(), faults);
        }
    }

    private final Seeder seeder;
    private final Shoppers shoppers;
    private final ChaosLab lab;
    private final RestClient simulators;
    private final JsonMapper json;
    private final Path file;
    private boolean testData;
    private final Map<String, Map<String, Object>> faults = new LinkedHashMap<>(State.off().faults());

    Switches(Seeder seeder, Shoppers shoppers, ChaosLab lab, RestClient.Builder builder, TestkitProperties props,
             JsonMapper json) {
        this.seeder = seeder;
        this.shoppers = shoppers;
        this.lab = lab;
        this.simulators = builder.clone().baseUrl(props.simulatorsUrl()).build();
        this.json = json;
        this.file = Path.of(props.stateDir(), "switches.json");
    }

    public synchronized State state() {
        List<ChaosLab.Start> actors = lab.list().stream().filter(Actor.View::running)
                .map(a -> new ChaosLab.Start(a.kind(), a.type(), a.topic(), a.ratePerSecond())).toList();
        return new State(testData, shoppers.settings(), actors, Map.copyOf(faults));
    }

    public synchronized State testData(boolean on) {
        testData = on;
        seeder.testData(on);
        log.atInfo().addKeyValue("on", on).log("Test data switched");
        return saved();
    }

    public synchronized State shoppers(Shoppers.Settings settings) {
        shoppers.update(settings);
        return saved();
    }

    public synchronized Actor.View startActor(ChaosLab.Start start) {
        Actor.View view = lab.start(start);
        saved();
        return view;
    }

    public synchronized boolean stopActor(String id) {
        boolean stopped = lab.stop(id);
        saved();
        return stopped;
    }

    public synchronized void stopAllActors() {
        lab.stopAll();
        saved();
    }

    /** Sets a simulator's faults; the simulator's answer is returned. */
    public synchronized Object fault(String target, Map<String, Object> setting) {
        if (!FAULT_TARGETS.contains(target)) {
            throw new IllegalArgumentException("Unknown simulator " + target);
        }
        Object answer = simulators.put().uri("/admin/faults/{t}", target).body(setting).retrieve().body(Object.class);
        faults.put(target, Map.copyOf(setting));
        saved();
        return answer;
    }

    /**
     * Makes the given state the current one: what is on and not listed goes off.
     * The controller uses it to put the switches back after an experiment.
     */
    public synchronized State apply(State wanted) {
        testData(wanted.testData());
        shoppers.update(wanted.shoppers() == null ? Shoppers.Settings.OFF : wanted.shoppers());
        lab.stopAll();
        for (ChaosLab.Start start : wanted.actors() == null ? List.<ChaosLab.Start>of() : wanted.actors()) {
            try {
                lab.start(start);
            } catch (RuntimeException e) {
                log.atWarn().addKeyValue("type", start.type()).log("Could not start a remembered actor: {}", e.getMessage());
            }
        }
        for (String target : FAULT_TARGETS) {
            Map<String, Object> setting = wanted.faults() == null ? null : wanted.faults().get(target);
            fault(target, setting == null ? NO_FAULT : setting);
        }
        return saved();
    }

    /** Puts back what was switched on before this testkit started (nothing, on a fresh volume). */
    @EventListener(ApplicationReadyEvent.class)
    void restore() {
        State remembered = load();
        if (remembered == null) {
            return;
        }
        Thread.ofVirtual().name("testkit-switches").start(() -> {
            // The simulators and Kafka may still be starting: try until they answer.
            for (int attempt = 1; ; attempt++) {
                try {
                    apply(remembered);
                    log.atInfo().addKeyValue("testData", remembered.testData())
                            .addKeyValue("shoppers", remembered.shoppers().count())
                            .addKeyValue("actors", remembered.actors().size()).log("Simulation switches restored");
                    return;
                } catch (RuntimeException e) {
                    log.atWarn().addKeyValue("attempt", attempt).log("Switches not restored yet: {}", e.getMessage());
                    try {
                        Thread.sleep(Math.min(30_000, 2_000L * attempt));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    State load() {
        try {
            return Files.exists(file) ? json.readValue(Files.readAllBytes(file), State.class) : null;
        } catch (IOException | RuntimeException e) {
            // Unreadable means unknown: everything stays off, which is the safe default.
            log.atWarn().log("Simulation switches unreadable; everything stays off: {}", e.getMessage());
            return null;
        }
    }

    private State saved() {
        State state = state();
        try {
            Files.createDirectories(file.getParent());
            // Written whole and moved into place, so a crash never leaves half a file.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, json.writeValueAsBytes(state));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.atError().log("Could not save the simulation switches: {}", e.getMessage());
        }
        return state;
    }
}
