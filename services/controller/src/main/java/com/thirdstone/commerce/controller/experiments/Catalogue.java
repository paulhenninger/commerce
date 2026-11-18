package com.thirdstone.commerce.controller.experiments;

import com.thirdstone.commerce.controller.Instance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The experiments of architecture section 11, as code. */
final class Catalogue {

    static final String ORDER_TOPIC = "commerce.order.events.v1";
    static final String INVENTORY_TOPIC = "commerce.inventory.events.v1";
    static final List<String> REPLICATED = List.of("identity", "catalog", "inventory", "order", "billing", "shipping",
            "notification");

    static List<Experiment> all() {
        List<Experiment> list = new ArrayList<>();
        for (String service : REPLICATED) {
            list.add(new Experiment("replica-crash-" + service, "Replica crash: " + service,
                    "If one of two " + service + " instances crashes under load, no checkout fails and nothing is lost.",
                    true, Duration.ofSeconds(45),
                    ctx -> ctx.act(first(ctx, service), "kill"),
                    ctx -> restart(ctx, service),
                    (ctx, run) -> { }, 0, Experiment.NORMAL_REASONS, false));
        }
        list.add(new Experiment("replica-hang-order", "Replica hang: order",
                "If an order instance hangs for 30 s, its work moves to the other and no checkout fails.",
                true, Duration.ofSeconds(30),
                ctx -> ctx.act(first(ctx, "order"), "pause"),
                ctx -> ctx.of("order").stream().filter(i -> i.state().equals("paused"))
                        .forEach(i -> ctx.act(i, "unpause")),
                (ctx, run) -> { }, 0, Experiment.NORMAL_REASONS, false));
        for (String service : List.of("inventory", "billing", "shipping")) {
            list.add(new Experiment("service-outage-" + service, "Whole service outage: " + service,
                    "With every " + service + " instance down for 60 s the site stays up, and affected orders wait, "
                            + "then complete or are cancelled with the right reason.",
                    false, Duration.ofSeconds(60),
                    ctx -> ctx.of(service).forEach(i -> ctx.act(i, "stop")),
                    ctx -> restart(ctx, service),
                    (ctx, run) -> { }, 0, with("TIMEOUT", "EXPIRED"), false));
        }
        list.add(new Experiment("catalog-outage", "Catalog outage",
                "With every catalog instance down, browsing continues from the edge's cache.",
                false, Duration.ofSeconds(45),
                ctx -> {
                    ctx.notes.put("browseFailuresBefore", browseFailures(ctx));
                    ctx.of("catalog").forEach(i -> ctx.act(i, "stop"));
                },
                ctx -> restart(ctx, "catalog"),
                (ctx, run) -> {
                    double during = browseFailures(ctx) - (Double) ctx.notes.get("browseFailuresBefore");
                    run.check("browsing continued", during == 0, during + " failed catalog requests");
                }, 0, Experiment.NORMAL_REASONS, false));
        list.add(new Experiment("broker-crash", "Broker crash",
                "If one of three brokers crashes, producers and consumers carry on after leader election; nothing is lost.",
                true, Duration.ofSeconds(60),
                ctx -> {
                    Instance broker = ctx.of("kafka-2").getFirst();
                    ctx.act(broker, "kill");
                },
                ctx -> restart(ctx, "kafka-2"),
                (ctx, run) -> { }, 0, Experiment.NORMAL_REASONS, false));
        list.add(new Experiment("kafka-outage", "Kafka outage",
                "With every broker down for 30 s, requests keep succeeding; outboxes fill, then drain in order.",
                false, Duration.ofSeconds(30),
                ctx -> {
                    for (String b : List.of("kafka-1", "kafka-2", "kafka-3")) {
                        ctx.of(b).forEach(i -> ctx.act(i, "stop"));
                    }
                },
                ctx -> {
                    for (String b : List.of("kafka-1", "kafka-2", "kafka-3")) {
                        ctx.of(b).stream().filter(i -> !i.state().equals("running")).forEach(i -> ctx.act(i, "start"));
                    }
                    for (String b : List.of("kafka-1", "kafka-2", "kafka-3")) {
                        waitFor(ctx, () -> ctx.instances.healthy(b, 1), 180, b + " healthy");
                    }
                },
                (ctx, run) -> run.check("events waited in the outbox", ctx.noted("outbox") > 0,
                        "at most " + (long) ctx.noted("outbox") + " events waiting"), 0, with("TIMEOUT", "EXPIRED"), false));
        list.add(new Experiment("database-outage", "Database outage",
                "With MySQL down for 20 s requests fail cleanly with 503, and everything recovers without help; "
                        + "nothing is lost.",
                false, Duration.ofSeconds(20),
                ctx -> ctx.of("mysql").forEach(i -> ctx.act(i, "stop")),
                ctx -> restart(ctx, "mysql"),
                (ctx, run) -> {
                    long unclean = ctx.journal().stream().filter(e -> e.path("action").asString().equals("order.placed")
                            && e.path("outcome").asString().startsWith("http_5")).count();
                    run.check("failures were clean 503s", unclean == 0, unclean + " checkouts failed with another 5xx");
                }, Integer.MAX_VALUE, with("TIMEOUT", "EXPIRED"), false));
        list.add(new Experiment("payment-outage", "Payment provider outage",
                "With the payment provider down, its circuit opens, orders are cancelled promptly and nobody is charged.",
                false, Duration.ofSeconds(45),
                ctx -> ctx.testkit.fault("payment", true, 0, 0),
                ctx -> ctx.testkit.fault("payment", false, 0, 0),
                (ctx, run) -> {
                    run.check("orders were cancelled as payment unavailable", ctx.noted("unavailable") > 0,
                            (long) ctx.noted("unavailable") + " payments failed as unavailable");
                }, 0, with("PAYMENT_UNAVAILABLE", "EXPIRED"), false));
        list.add(new Experiment("carrier-outage", "Carrier outage",
                "With the carrier down, orders still confirm, and shipments resume when it returns.",
                false, Duration.ofSeconds(60),
                ctx -> ctx.testkit.fault("carrier", true, 0, 0),
                ctx -> ctx.testkit.fault("carrier", false, 0, 0),
                (ctx, run) -> { }, 0, Experiment.NORMAL_REASONS, false));
        list.add(new Experiment("poison-data", "Poison data",
                "Malformed and contract-breaking records are dead-lettered without stalling any consumer.",
                false, Duration.ofSeconds(45),
                ctx -> {
                    ctx.testkit.startActor("producer", "MALFORMED", ORDER_TOPIC, 20);
                    ctx.testkit.startActor("producer", "INVALID_SCHEMA", INVENTORY_TOPIC, 10);
                },
                ctx -> ctx.testkit.stopAllActors(),
                (ctx, run) -> run.check("bad records were dead-lettered", ctx.noted("invalid") > 0,
                        (long) ctx.noted("invalid") + " invalid records dead-lettered"), 0, Experiment.NORMAL_REASONS, true));
        list.add(new Experiment("duplicate-storm", "Duplicate storm",
                "Real order events delivered again and again, byte for byte, are applied once: no second "
                        + "reservation, charge, shipment or email.",
                false, Duration.ofSeconds(45),
                // The testkit replays the last 200 real order events it saw; every service consumes them.
                ctx -> ctx.testkit.startActor("producer", "DUPLICATE", ORDER_TOPIC, 50),
                ctx -> ctx.testkit.stopAllActors(),
                (ctx, run) -> run.check("duplicates were recognised", ctx.noted("duplicates") > 0,
                        (long) ctx.noted("duplicates") + " duplicates skipped"), 0, Experiment.NORMAL_REASONS, false));
        list.add(new Experiment("flood", "Flood",
                "A flood of events raises lag, which recovers, while every checkout succeeds.",
                false, Duration.ofSeconds(30),
                ctx -> ctx.testkit.startActor("producer", "FLOOD", INVENTORY_TOPIC, 300),
                ctx -> ctx.testkit.stopAllActors(),
                (ctx, run) -> run.check("the flood showed as lag", ctx.noted("lag") > 0,
                        "peak service lag " + (long) ctx.noted("lag")), 0, with("TIMEOUT", "EXPIRED"), false));
        list.add(new Experiment("intruder", "Intruder",
                "A client with no rights is refused by the broker every time it writes, reads or joins a group.",
                false, Duration.ofSeconds(30),
                ctx -> ctx.notes.put("intruder", ctx.testkit.startActor("producer", "UNAUTHORISED", ORDER_TOPIC, 1)),
                ctx -> { },
                (ctx, run) -> {
                    var counts = ctx.testkit.actors();
                    ctx.testkit.stopAllActors();
                    StringBuilder seen = new StringBuilder();
                    boolean allowed = false;
                    boolean refused = false;
                    for (var a : counts) {
                        for (var c : a.path("counts").properties()) {
                            seen.append(c.getKey()).append('=').append(c.getValue().asLong()).append(' ');
                            allowed |= c.getKey().contains("allowed");
                            refused |= c.getKey().contains("refused");
                        }
                    }
                    run.check("every attempt was refused", refused && !allowed, seen.toString().trim());
                }, 0, Experiment.NORMAL_REASONS, false));
        list.add(new Experiment("bad-consumers", "Bad consumers",
                "Slow, crashing, flapping and stuck consumers in their own groups leave every service's lag unaffected.",
                false, Duration.ofSeconds(60),
                ctx -> {
                    for (String type : List.of("SLOW", "CRASHING", "FLAPPING", "STUCK")) {
                        ctx.testkit.startActor("consumer", type, ORDER_TOPIC, 1);
                    }
                },
                ctx -> ctx.testkit.stopAllActors(),
                (ctx, run) -> run.check("service lag stayed low", ctx.noted("lag") < 200,
                        "peak service lag " + (long) ctx.noted("lag")), 0, Experiment.NORMAL_REASONS, false));
        list.add(new Experiment("deploy-under-load", "Deploy under load",
                "Restarting every service's instances one at a time, as a rolling deploy does, fails no checkout.",
                true, Duration.ofSeconds(1),
                ctx -> {
                    for (String service : REPLICATED) {
                        for (Instance i : ctx.of(service)) {
                            ctx.act(i, "restart");
                            waitFor(ctx, () -> ctx.instances.healthy(service, 2), 180, service + " healthy again");
                            // As a rolling deploy must: give the edges time to take the instance back
                            // (they retry a failed upstream after 2 s) before taking the next one away.
                            Shop.sleep(5000);
                        }
                    }
                },
                ctx -> { },
                (ctx, run) -> { }, 0, Experiment.NORMAL_REASONS, false));
        return list;
    }

    static Set<String> with(String... reasons) {
        Set<String> s = new HashSet<>(Experiment.NORMAL_REASONS);
        s.addAll(List.of(reasons));
        return s;
    }

    static Instance first(Context ctx, String service) {
        return ctx.of(service).stream().filter(i -> i.state().equals("running")).findFirst()
                .orElseThrow(() -> new IllegalStateException("no running " + service));
    }

    /** Brings back every instance of a service and waits until they are all healthy. */
    static void restart(Context ctx, String service) {
        for (Instance i : ctx.of(service)) {
            if (i.state().equals("paused")) {
                ctx.act(i, "unpause");
            } else if (!i.state().equals("running")) {
                ctx.act(i, "start");
            }
        }
        int expected = ctx.of(service).size();
        waitFor(ctx, () -> ctx.instances.healthy(service, expected), 240, service + " healthy");
    }

    static double browseFailures(Context ctx) {
        double failures = 0;
        var counts = ctx.testkit.status().path("shoppers").path("counts");
        for (var c : counts.properties()) {
            if (c.getKey().startsWith("browse.http_")) {
                failures += c.getValue().asDouble();
            }
        }
        return failures;
    }

    /**
     * Waits until {@code pending} answers null (nothing left), noting what is
     * still pending every 30 s; fails with the last answer.
     */
    static void waitUntilNone(Context ctx, java.util.function.Supplier<String> pending, int seconds, String what) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        long nextNote = System.currentTimeMillis() + 30_000;
        String last = "not yet looked";
        while (System.currentTimeMillis() < deadline) {
            try {
                last = pending.get();
                if (last == null) {
                    ctx.log(what);
                    return;
                }
            } catch (RuntimeException e) {
                last = "could not look: " + e.getMessage();
            }
            if (System.currentTimeMillis() >= nextNote) {
                ctx.log("still waiting: " + last);
                nextNote += 30_000;
            }
            Shop.sleep(2000);
        }
        // One last look: if the host was suspended mid-wait the clock jumps past the
        // deadline, and failing on a look taken before the suspension would be wrong.
        try {
            last = pending.get();
            if (last == null) {
                ctx.log(what);
                return;
            }
        } catch (RuntimeException e) {
            last = "could not look: " + e.getMessage();
        }
        throw new IllegalStateException("Not " + what + " after " + seconds + " s: " + last);
    }

    static void waitFor(Context ctx, java.util.function.BooleanSupplier condition, int seconds, String what) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    ctx.log(what);
                    return;
                }
            } catch (RuntimeException ignored) {
                // Still coming back.
            }
            Shop.sleep(2000);
        }
        throw new IllegalStateException("Not " + what + " after " + seconds + " s");
    }

    private Catalogue() {
    }
}
