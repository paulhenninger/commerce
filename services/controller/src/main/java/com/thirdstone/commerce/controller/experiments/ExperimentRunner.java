package com.thirdstone.commerce.controller.experiments;

import com.thirdstone.commerce.platform.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs experiments, one at a time: steady state, inject, observe, restore (always),
 * settle, verdict. A run is stored as soon as it ends, and anything an
 * interrupted run left broken is put back when the controller next starts.
 */
@Service
public class ExperimentRunner {

    private static final Logger log = LoggerFactory.getLogger(ExperimentRunner.class);
    private static final List<String> SERVICES = List.of("identity", "catalog", "inventory", "order", "billing",
            "shipping", "notification", "ops");

    private final Map<String, Experiment> catalogue = new LinkedHashMap<>();
    private final Prometheus prometheus;
    private final Testkit testkit;
    private final Shop shop;
    private final Instances instances;
    private final RecoveryLedger ledger;
    private final ExperimentProperties props;
    private final JsonMapper json;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "experiments"));
    private final List<Run> runs = Collections.synchronizedList(new ArrayList<>());
    private volatile Run current;

    ExperimentRunner(Prometheus prometheus, Testkit testkit, Shop shop, Instances instances, RecoveryLedger ledger,
                     ExperimentProperties props, JsonMapper json) {
        this.prometheus = prometheus;
        this.testkit = testkit;
        this.shop = shop;
        this.instances = instances;
        this.ledger = ledger;
        this.props = props;
        this.json = json;
        Catalogue.all().forEach(e -> catalogue.put(e.name(), e));
        loadRuns();
    }

    public List<Experiment> catalogue() {
        return List.copyOf(catalogue.values());
    }

    public List<Run> runs() {
        synchronized (runs) {
            List<Run> copy = new ArrayList<>(runs);
            Collections.reverse(copy);
            return copy;
        }
    }

    public Run run(String id) {
        return runs.stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Run"));
    }

    /** Queues the experiments; returns their runs (RUNNING until they have their verdict). */
    public synchronized List<Run> start(List<String> names) {
        List<Run> queued = new ArrayList<>();
        for (String name : names) {
            Experiment e = catalogue.get(name);
            if (e == null) {
                throw ApiException.notFound("Experiment " + name);
            }
            Run run = new Run(Instant.now().toString().replace(":", "").substring(0, 15) + "-" + UUID.randomUUID()
                    .toString().substring(0, 6), e);
            run.log("queued");
            runs.add(run);
            queued.add(run);
            worker.submit(() -> execute(e, run));
        }
        return queued;
    }

    /** Puts back anything a previous controller left broken. */
    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        Map<String, String> outstanding = ledger.outstanding();
        for (var entry : outstanding.entrySet()) {
            try {
                instances.act(instances.of(serviceOf(entry.getKey())).stream()
                        .filter(i -> i.id().equals(entry.getKey())).findFirst().orElseThrow(),
                        entry.getValue().equals("pause") ? "unpause" : "start");
                log.atWarn().addKeyValue("instance", entry.getKey()).log("Restored an instance an interrupted experiment left down");
            } catch (RuntimeException e) {
                ledger.restored(entry.getKey());
            }
        }
        if (ledger.faultsOutstanding()) {
            safely(() -> {
                testkit.fault("payment", false, 0, 0);
                testkit.fault("carrier", false, 0, 0);
            });
            ledger.faults(false);
        }
        if (ledger.actorsOutstanding()) {
            safely(testkit::stopAllActors);
            ledger.actors(false);
        }
    }

    private String serviceOf(String containerId) {
        return SERVICES.stream().filter(s -> instances.of(s).stream().anyMatch(i -> i.id().equals(containerId)))
                .findFirst().orElseGet(() -> List.of("kafka-1", "kafka-2", "kafka-3", "mysql", "storefront-web").stream()
                        .filter(s -> instances.of(s).stream().anyMatch(i -> i.id().equals(containerId))).findFirst()
                        .orElse(""));
    }

    private void execute(Experiment e, Run run) {
        current = run;
        run.startedAt = Instant.now();
        run.log("started");
        Context ctx = new Context(run, prometheus, testkit, shop, instances);
        boolean injected = false;
        try {
            if (e.needsHa() && !haTopology()) {
                run.verdict = Run.Verdict.NOT_APPLICABLE;
                run.log("needs two instances of each service and three brokers (the HA topology)");
                return;
            }
            prepare(ctx);
            String refusal = steadyState();
            if (refusal != null) {
                run.verdict = Run.Verdict.NOT_RUN;
                run.log("not in steady state: " + refusal);
                return;
            }
            ctx.windowStart = Instant.now();
            ledger.faults(true);
            ledger.actors(true);
            injected = true;
            run.log("inject");
            e.inject().run(ctx);
            observe(ctx, e.observe());
        } catch (Exception ex) {
            run.check("experiment ran", false, ex.getMessage());
        } finally {
            if (injected) {
                try {
                    run.log("restore");
                    e.restore().run(ctx);
                    ledger.faults(false);
                    ledger.actors(false);
                } catch (Exception ex) {
                    run.check("failure removed", false, ex.getMessage());
                }
            }
        }
        if (run.verdict == Run.Verdict.NOT_APPLICABLE || run.verdict == Run.Verdict.NOT_RUN) {
            finish(run);
            return;
        }
        try {
            ctx.windowEnd = Instant.now().plusSeconds(15);
            observe(ctx, Duration.ofSeconds(15));
            run.log("pause new orders; let everything in flight settle");
            testkit.pauseOrders();
            settle(ctx);
            verdict(ctx, e, run);
            e.verify().run(ctx, run);
        } catch (Exception ex) {
            run.check("verdict reached", false, ex.getMessage());
        } finally {
            safely(() -> testkit.shoppers(Testkit.STANDARD_LOAD));
            finish(run);
        }
    }

    private void finish(Run run) {
        if (run.verdict == Run.Verdict.RUNNING) {
            run.verdict = run.checks.stream().allMatch(Run.Check::passed) && !run.checks.isEmpty()
                    ? Run.Verdict.PASSED : Run.Verdict.FAILED;
        }
        run.endedAt = Instant.now();
        run.log("verdict: " + run.verdict);
        log.atInfo().addKeyValue("experiment", run.experiment).addKeyValue("verdict", run.verdict.name())
                .log("Experiment finished");
        current = null;
        saveRuns();
    }

    private void prepare(Context ctx) {
        testkit.shoppers(Testkit.STANDARD_LOAD);
        Catalogue.waitFor(ctx, () -> testkit.status().path("shoppers").path("ready").asInt() >= 20, 300,
                "20 shoppers ready");
        // Traffic for a little while before anything is broken, so steady state is measured, not assumed.
        Shop.sleep(20_000);
    }

    /** Null if the system is healthy enough to experiment on, else why not. */
    private String steadyState() {
        for (String service : SERVICES) {
            if (prometheus.scalar("count(up{job=\"services\", service=\"" + service + "\"} == 1)") < 1) {
                return service + " is not up";
            }
        }
        double errors = prometheus.scalar("sum(rate(http_server_requests_seconds_count{job=\"services\", status=~\"5..\"}[1m]))"
                + " / sum(rate(http_server_requests_seconds_count{job=\"services\"}[1m]))");
        if (errors > 0.02) {
            return String.format("%.1f%% of requests are failing", errors * 100);
        }
        if (prometheus.scalar("sum(increase(commerce_testkit_shopper_actions_total{action=\"order\", outcome=\"placed\"}[1m]))") < 3) {
            return "the shoppers are not ordering";
        }
        return null;
    }

    private void observe(Context ctx, Duration duration) {
        long until = System.currentTimeMillis() + duration.toMillis();
        while (System.currentTimeMillis() < until) {
            safely(() -> {
                ctx.sampleMax("outbox", "sum(commerce_outbox_pending)");
                ctx.sampleMax("lag", "sum(max by (group, topic) (commerce_kafka_consumer_lag{group=~\"catalog|order|inventory|billing|shipping|notification\"}))");
            });
            Shop.sleep(2000);
        }
    }

    /** Waits until nothing is in flight: no order mid-saga, outboxes empty, consumers caught up, emails queued. */
    private void settle(Context ctx) {
        Catalogue.waitUntilNone(ctx, this::inFlight, (int) props.sagaTimeout().toSeconds() + 240, "everything settled");
        // The last Kafka gauges are sampled every 15 s: one more look after a pause.
        Shop.sleep(16_000);
    }

    /** What is still in flight, or null when nothing is. */
    private String inFlight() {
        List<String> waiting = new ArrayList<>();
        for (String status : List.of("PENDING", "AWAITING_PAYMENT", "RETURN_REQUESTED", "RETURNED")) {
            JsonNode page = shop.get("/api/v1/backoffice/orders?status=" + status + "&size=1");
            long n = page == null ? -1 : page.path("total").asLong();
            if (n != 0) {
                waiting.add((n < 0 ? "?" : n) + " orders " + status);
            }
        }
        double outbox = prometheus.scalar("sum(commerce_outbox_pending)");
        double refunds = prometheus.scalar("max(commerce_refunds_pending)");
        double lag = prometheus.scalar("sum(max by (group, topic) (commerce_kafka_consumer_lag{group=~\"catalog|order|inventory|billing|shipping|notification\"}))");
        double email = prometheus.scalar("max(commerce_notifications_oldest_pending_age_seconds)");
        if (outbox != 0) {
            waiting.add((long) outbox + " events in outboxes");
        }
        if (refunds != 0) {
            waiting.add((long) refunds + " refunds queued");
        }
        if (lag != 0) {
            waiting.add("consumer lag " + (long) lag);
        }
        if (email >= 60) {
            waiting.add("an email waiting " + (long) email + " s");
        }
        return waiting.isEmpty() ? null : String.join(", ", waiting);
    }

    private void verdict(Context ctx, Experiment e, Run run) {
        // This run's own window: counters from before it (an earlier experiment's poison) do not count.
        String w = "[" + (java.time.Duration.between(ctx.windowStart, Instant.now()).toSeconds() + 15) + "s]";
        List<JsonNode> journal = ctx.journal();
        long failed = journal.stream().filter(j -> j.path("action").asString().equals("order.placed")
                && !j.path("outcome").asString().equals("accepted") && !j.path("outcome").asString().equals("retrying")
                && !j.path("outcome").asString().equals("price_changed")).count();
        long accepted = journal.stream().filter(j -> j.path("action").asString().equals("order.placed")
                && j.path("outcome").asString().equals("accepted")).count();
        run.check("customer impact", failed <= e.allowedFailures(),
                failed + " of " + (failed + accepted) + " checkouts failed" + (e.allowedFailures() == Integer.MAX_VALUE
                        ? " (failures allowed while the database is down)" : ""));
        ctx.notes.put("unavailable", prometheus.scalar("sum(increase(commerce_payments_total{outcome=\"PAYMENT_UNAVAILABLE\"}" + w + "))"));
        ctx.notes.put("invalid", prometheus.scalar("sum(increase(commerce_events_dead_lettered_total{reason=\"invalid\"}" + w + "))"));
        ctx.notes.put("duplicates", prometheus.scalar("sum(increase(commerce_events_consumed_total{outcome=\"duplicate\"}" + w + "))"));
        double failedLetters = prometheus.scalar("sum(increase(commerce_events_dead_lettered_total{reason=\"failed\"}" + w + "))");
        double invalidLetters = (Double) ctx.notes.get("invalid");
        run.check("no event was dead-lettered by a failing handler", failedLetters < 0.5,
                String.format("%.0f handler failures dead-lettered", failedLetters));
        if (!e.allowInvalidDeadLetters()) {
            run.check("no invalid event was produced", invalidLetters < 0.5,
                    String.format("%.0f invalid records dead-lettered", invalidLetters));
        }
        run.check("outboxes drained and consumers caught up", prometheus.scalar("sum(commerce_outbox_pending)") == 0,
                "outbox empty");
        List<JsonNode> wide = new ArrayList<>();
        testkit.journal(ctx.windowStart.minusSeconds(180), Instant.now()).forEach(wide::add);
        new LedgerAudit(shop, run).audit(LedgerAudit.placed(journal), LedgerAudit.placed(wide).keySet(),
                ctx.windowStart, ctx.windowEnd, e.allowedReasons());
    }

    private boolean haTopology() {
        return instances.of("order").size() >= 2 && instances.of("kafka-3").size() == 1;
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.atWarn().log("Best-effort step failed: {}", e.getMessage());
        }
    }

    private Path runsFile() {
        return Path.of(props.stateDir(), "runs.json");
    }

    private void saveRuns() {
        try {
            Files.createDirectories(runsFile().getParent());
            List<Run> keep;
            synchronized (runs) {
                keep = new ArrayList<>(runs.subList(Math.max(0, runs.size() - 200), runs.size()));
            }
            Files.write(runsFile(), json.writeValueAsBytes(keep));
        } catch (IOException | RuntimeException e) {
            log.atWarn().log("Could not save runs: {}", e.getMessage());
        }
    }

    private void loadRuns() {
        try {
            if (Files.exists(runsFile())) {
                Run[] saved = json.readValue(Files.readAllBytes(runsFile()), Run[].class);
                for (Run r : saved) {
                    if (r.verdict == Run.Verdict.RUNNING) {
                        r.verdict = Run.Verdict.FAILED;
                        r.log("interrupted: the controller stopped during the run");
                    }
                    runs.add(r);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.atWarn().log("Could not read saved runs: {}", e.getMessage());
        }
    }

    public Run current() {
        return current;
    }
}
