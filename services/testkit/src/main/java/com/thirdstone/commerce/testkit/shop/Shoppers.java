package com.thirdstone.commerce.testkit.shop;

import com.thirdstone.commerce.testkit.TestkitProperties;
import com.thirdstone.commerce.testkit.journal.Journal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulated shoppers: real accounts that browse, buy, cancel and return through
 * the storefront edge, at a rate and mix set from the console. Every action and
 * every outcome they see goes into the journal.
 */
@Component
public class Shoppers {

    private static final Logger log = LoggerFactory.getLogger(Shoppers.class);

    public record Settings(int count, double ordersPerMinute, double cancelRate, double returnRate,
                           double declineRate, double outOfStockRate) {
        public Settings {
            if (count < 0 || count > 200 || ordersPerMinute < 0 || ordersPerMinute > 1200) {
                throw new IllegalArgumentException("count 0-200, ordersPerMinute 0-1200");
            }
            for (double rate : new double[]{cancelRate, returnRate, declineRate, outOfStockRate}) {
                if (rate < 0 || rate > 1) {
                    throw new IllegalArgumentException("rates are between 0 and 1");
                }
            }
        }
    }

    public record Status(Settings settings, int ready, int creating, int inFlight, Map<String, Long> counts) {
    }

    static final class Shopper {
        final int n;
        final String email;
        final String ip;
        volatile String token;

        Shopper(int n) {
            this.n = n;
            this.email = "sim-" + n + "@example.com";
            // TEST-NET style benchmark addresses (198.18.0.0/15): one per shopper.
            this.ip = "198.18." + (n / 250) + "." + (n % 250 + 1);
        }
    }

    record Tracked(String orderId, Shopper shopper, boolean mayCancel, boolean mayReturn, long since) {
    }

    private final Accounts accounts;
    private final Storefront storefront;
    private final Journal journal;
    private final TestkitProperties props;
    private final MeterRegistry registry;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /** No more than this many shopper requests at once, whatever the rate asks for. */
    private final Semaphore concurrency = new Semaphore(64);
    private final List<Shopper> ready = new CopyOnWriteArrayList<>();
    private final AtomicInteger creating = new AtomicInteger();
    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    private final Map<String, Long> counts = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger();
    private volatile Settings settings = new Settings(0, 0, 0.1, 0.1, 0.05, 0.02);
    private double owed;
    private volatile JsonNode catalog;
    private volatile long catalogAt;

    Shoppers(Accounts accounts, Storefront storefront, Journal journal, TestkitProperties props, MeterRegistry registry,
             Seeder seeder) {
        this.accounts = accounts;
        this.storefront = storefront;
        this.journal = journal;
        this.props = props;
        this.registry = registry;
        if (props.shoppersAtStart() > 0) {
            settings = new Settings(props.shoppersAtStart(), props.shoppersAtStart() * 3, 0.1, 0.1, 0.05, 0.02);
        }
    }

    public Settings settings() {
        return settings;
    }

    public synchronized Status status() {
        return new Status(settings, ready.size(), creating.get(), tracked.size(), Map.copyOf(counts));
    }

    public synchronized Settings update(Settings wanted) {
        settings = wanted;
        log.atInfo().addKeyValue("count", wanted.count()).addKeyValue("ordersPerMinute", wanted.ordersPerMinute())
                .log("Simulated shoppers updated");
        return settings;
    }

    /** Creates accounts for new shoppers (a few at a time: sign-up hashes passwords) and retires extra ones. */
    @Scheduled(fixedDelay = 1000)
    void staff() {
        Settings s = settings;
        while (ready.size() > s.count()) {
            ready.removeLast();
        }
        int missing = s.count() - ready.size() - creating.get();
        for (int i = 0; i < Math.min(missing, 4); i++) {
            creating.incrementAndGet();
            Shopper shopper = new Shopper(next.incrementAndGet());
            workers.submit(() -> {
                try {
                    shopper.token = accounts.signIn(shopper.email, props.shopperPassword(), shopper.ip);
                    ready.add(shopper);
                    journal.record(shopper.email, "signed-in", null, "ok", null);
                } catch (RuntimeException e) {
                    count("account", "failed");
                    journal.record(shopper.email, "signed-in", null, "failed", e.getMessage());
                    next.decrementAndGet();
                } finally {
                    creating.decrementAndGet();
                }
            });
        }
    }

    /** Places orders at the configured rate, each by a random ready shopper. */
    @Scheduled(fixedRate = 250)
    void shop() {
        Settings s = settings;
        if (ready.isEmpty() || s.ordersPerMinute() <= 0) {
            owed = 0;
            return;
        }
        owed += s.ordersPerMinute() / 240.0;
        while (owed >= 1) {
            owed -= 1;
            Shopper shopper = ready.get(ThreadLocalRandom.current().nextInt(ready.size()));
            if (concurrency.tryAcquire()) {
                workers.submit(() -> {
                    try {
                        placeOrder(shopper, s);
                    } finally {
                        concurrency.release();
                    }
                });
            } else {
                count("order", "skipped_busy");
            }
        }
    }

    /** Follows each open order and acts on it as the shopper decided: cancel once confirmed, return once delivered. */
    // Every check is an authenticated request (an auth check in identity); every
    // few seconds is plenty for steps that take seconds.
    @Scheduled(fixedDelay = 5000)
    void follow() {
        for (Tracked t : List.copyOf(tracked.values())) {
            if (!concurrency.tryAcquire()) {
                return;
            }
            workers.submit(() -> {
                try {
                    followUp(t);
                } finally {
                    concurrency.release();
                }
            });
        }
    }

    private void placeOrder(Shopper shopper, Settings s) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        JsonNode products = browse(shopper);
        if (products == null) {
            return;
        }
        List<JsonNode> buyable = new ArrayList<>();
        JsonNode soldOut = null;
        for (JsonNode p : products) {
            if (p.path("sku").asString().equals(Seeder.SOLD_OUT)) {
                soldOut = p;
            } else if (p.path("available").asInt() > 5) {
                buyable.add(p);
            }
        }
        if (buyable.isEmpty()) {
            count("order", "nothing_to_buy");
            return;
        }
        Map<String, Integer> lines = new LinkedHashMap<>();
        int n = 1 + random.nextInt(3);
        for (int i = 0; i < n; i++) {
            JsonNode p = buyable.get(random.nextInt(buyable.size()));
            lines.merge(p.path("sku").asString(), 1 + random.nextInt(2), Integer::sum);
        }
        boolean wantsSoldOut = soldOut != null && random.nextDouble() < s.outOfStockRate();
        if (wantsSoldOut) {
            lines.put(Seeder.SOLD_OUT, 1);
        }
        long total = 0;
        List<Map<String, Object>> body = new ArrayList<>();
        for (var line : lines.entrySet()) {
            JsonNode p = line.getKey().equals(Seeder.SOLD_OUT) ? soldOut : find(buyable, line.getKey());
            total += p.path("priceCents").asLong() * line.getValue();
            body.add(Map.of("sku", line.getKey(), "quantity", line.getValue()));
        }
        boolean declined = random.nextDouble() < s.declineRate();
        Map<String, Object> order = Map.of("lines", body,
                "shippingAddress", Map.of("name", "Sim Shopper " + shopper.n, "line1", shopper.n + " Simulation Road",
                        "city", "Testville", "postalCode", String.format("%05d", shopper.n), "country", "US"),
                "paymentMethod", declined ? "tok_decline" : "tok_visa",
                "expectedTotalCents", total);
        String key = UUID.randomUUID().toString();
        java.util.function.Supplier<Storefront.Answer> checkout = () -> {
            try {
                return storefront.call("POST", "/api/v1/orders", shopper.ip, shopper.token, order,
                        Map.of("Idempotency-Key", key));
            } catch (RuntimeException e) {
                return new Storefront.Answer(0, null);   // No answer at all (connection lost).
            }
        };
        Storefront.Answer answer = authed(shopper, checkout);
        if (answer.status() == 0 || answer.status() == 502 || answer.status() == 503 || answer.status() == 504) {
            // As a browser's checkout does: one retry with the same key, which
            // returns the first order if the first attempt did get through.
            journal.record(shopper.email, "order.placed", null, "retrying", "first attempt: " + answer.status());
            Accounts.sleep(1000);
            answer = authed(shopper, checkout);
        }
        if (answer.status() == 202) {
            String id = answer.text("id");
            count("order", "placed");
            journal.record(shopper.email, "order.placed", id, "accepted",
                    "total=" + total + (declined ? " card=declined" : "") + (wantsSoldOut ? " sold-out" : ""));
            tracked.put(id, new Tracked(id, shopper, random.nextDouble() < s.cancelRate(),
                    random.nextDouble() < s.returnRate(), System.currentTimeMillis()));
        } else {
            String outcome = answer.status() == 409 ? "price_changed" : answer.status() == 503 ? "shed"
                    : answer.status() == 429 ? "rate_limited" : "http_" + answer.status();
            count("order", outcome);
            journal.record(shopper.email, "order.placed", null, outcome, String.valueOf(answer.body()));
        }
    }

    private void followUp(Tracked t) {
        Storefront.Answer answer = authed(t.shopper(), () -> storefront.get("/api/v1/orders/" + t.orderId(),
                t.shopper().ip, t.shopper().token));
        if (!answer.ok()) {
            return;
        }
        String status = answer.text("status");
        switch (status) {
            case "CONFIRMED" -> {
                if (t.mayCancel()) {
                    Storefront.Answer c = authed(t.shopper(), () -> storefront.post("/api/v1/orders/" + t.orderId()
                            + "/cancel", t.shopper().ip, t.shopper().token, null));
                    count("cancel", c.ok() ? "ok" : "refused");
                    journal.record(t.shopper().email, "order.cancel", t.orderId(), c.ok() ? "accepted" : "refused",
                            c.ok() ? null : String.valueOf(c.body()));
                    finish(t, c.ok() ? "CANCELLED" : status);
                }
            }
            case "DELIVERED" -> {
                if (t.mayReturn()) {
                    Storefront.Answer r = authed(t.shopper(), () -> storefront.post("/api/v1/orders/" + t.orderId()
                            + "/return", t.shopper().ip, t.shopper().token, Map.of("reason", "Simulated return")));
                    count("return", r.ok() ? "ok" : "refused");
                    journal.record(t.shopper().email, "order.return", t.orderId(), r.ok() ? "accepted" : "refused",
                            r.ok() ? null : String.valueOf(r.body()));
                    if (!r.ok()) {
                        finish(t, status);
                    }
                } else {
                    finish(t, status);
                }
            }
            case "CANCELLED", "REFUNDED" -> finish(t, status + (answer.text("cancelReason") == null ? ""
                    : " " + answer.text("cancelReason")));
            default -> {
                // Still moving (or a cancelled order's refund still to come).
            }
        }
        // Nothing should take this long; stop following rather than grow forever.
        if (System.currentTimeMillis() - t.since() > 15 * 60_000L) {
            finish(t, "gave-up-following " + status);
        }
    }

    private void finish(Tracked t, String outcome) {
        if (tracked.remove(t.orderId()) != null) {
            journal.record(t.shopper().email, "order.settled", t.orderId(), outcome.split(" ")[0], outcome);
            count("settled", outcome.split(" ")[0].toLowerCase());
        }
    }

    private JsonNode browse(Shopper shopper) {
        // Browsing is cached for a few seconds, as the shop's own caches do.
        if (catalog == null || System.currentTimeMillis() - catalogAt > 5000) {
            Storefront.Answer answer = storefront.get("/api/v1/catalog/products?size=50", shopper.ip, null);
            if (!answer.ok()) {
                count("browse", "http_" + answer.status());
                return null;
            }
            catalog = answer.body().path("items");
            catalogAt = System.currentTimeMillis();
        }
        count("browse", "ok");
        return catalog;
    }

    private Storefront.Answer authed(Shopper shopper, java.util.function.Supplier<Storefront.Answer> call) {
        Storefront.Answer answer = call.get();
        if (answer.status() == 401) {
            // Access tokens last 15 minutes: sign in again, as the SPA's refresh would.
            shopper.token = accounts.signIn(shopper.email, props.shopperPassword(), shopper.ip);
            answer = call.get();
        }
        return answer;
    }

    private static JsonNode find(List<JsonNode> products, String sku) {
        return products.stream().filter(p -> p.path("sku").asString().equals(sku)).findFirst().orElseThrow();
    }

    private void count(String action, String outcome) {
        counts.merge(action + "." + outcome, 1L, Long::sum);
        Counter.builder("commerce.testkit.shopper.actions").tag("action", action).tag("outcome", outcome)
                .register(registry).increment();
    }
}
