package com.thirdstone.commerce.controller.experiments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an experiment has broken and not yet put back, on disk. If the controller
 * dies mid-experiment, its next start restores everything listed here, so no
 * experiment can leave the system broken.
 */
@Component
class RecoveryLedger {

    private static final Logger log = LoggerFactory.getLogger(RecoveryLedger.class);

    private final Path file;
    private final JsonMapper json;
    private final Map<String, String> disturbed = new LinkedHashMap<>();
    private boolean faults;
    private boolean actors;

    RecoveryLedger(ExperimentProperties props, JsonMapper json) {
        this.file = Path.of(props.stateDir(), "recovery.json");
        this.json = json;
        try {
            if (Files.exists(file)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> saved = json.readValue(Files.readAllBytes(file), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, String> d = (Map<String, String>) saved.getOrDefault("disturbed", Map.of());
                disturbed.putAll(d);
                faults = Boolean.TRUE.equals(saved.get("faults"));
                actors = Boolean.TRUE.equals(saved.get("actors"));
            }
        } catch (IOException | RuntimeException e) {
            log.atWarn().log("Recovery ledger unreadable; starting empty: {}", e.getMessage());
        }
    }

    synchronized void disturbed(String id, String action) {
        disturbed.put(id, action);
        save();
    }

    synchronized void restored(String id) {
        disturbed.remove(id);
        save();
    }

    synchronized void faults(boolean on) {
        faults = on;
        save();
    }

    synchronized void actors(boolean on) {
        actors = on;
        save();
    }

    synchronized Map<String, String> outstanding() {
        return Map.copyOf(disturbed);
    }

    synchronized boolean faultsOutstanding() {
        return faults;
    }

    synchronized boolean actorsOutstanding() {
        return actors;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, json.writeValueAsBytes(Map.of("disturbed", disturbed, "faults", faults, "actors", actors)));
        } catch (IOException e) {
            log.atError().log("Could not write the recovery ledger: {}", e.getMessage());
        }
    }
}
