package com.thirdstone.commerce.controller.experiments;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciles what the shoppers did (their journal) with what every service
 * recorded (the back-office read APIs), once the system has settled. Each
 * invariant names the failure it would catch (architecture, section 11).
 */
class LedgerAudit {

    /** The back office's read APIs: one resource (null if not found), or every page of a list. */
    interface BackOffice {
        JsonNode get(String path);

        List<JsonNode> all(String path);
    }

    private static final Set<String> CONFIRMED_OR_LATER = Set.of("CONFIRMED", "SHIPPED", "DELIVERED", "RETURN_REQUESTED",
            "RETURNED", "REFUNDED");
    private static final Set<String> SHIPPED_OR_LATER = Set.of("SHIPPED", "DELIVERED", "RETURN_REQUESTED", "RETURNED",
            "REFUNDED");

    private final BackOffice shop;
    private final Run run;
    private final List<String> problems = new ArrayList<>();
    private static final String SUPPORT = "support: ";
    private static final Set<String> LEFT = Set.of("DISPATCHED", "IN_TRANSIT", "DELIVERED");
    /** Where problems go: the audit's list, or one order's while it is being read. */
    private List<String> sink = problems;

    LedgerAudit(BackOffice shop, Run run) {
        this.shop = shop;
        this.run = run;
    }

    /** The orders the shoppers placed in the window: id → shopper. */
    static Map<String, String> placed(List<JsonNode> journal) {
        Map<String, String> out = new HashMap<>();
        for (JsonNode e : journal) {
            if (e.path("action").asString().equals("order.placed") && e.path("outcome").asString().equals("accepted")
                    && !e.path("orderId").isNull()) {
                out.put(e.path("orderId").asString(), e.path("shopper").asString());
            }
        }
        return out;
    }

    /**
     * @param placed the orders accepted in the window (id → shopper)
     * @param known  every order id the shoppers were told about, from a wider span
     */
    void audit(Map<String, String> placed, Set<String> known, Instant from, Instant to, Set<String> allowedReasons) {
        Map<String, JsonNode> orders = new HashMap<>();
        Map<String, List<JsonNode>> emails = new HashMap<>();
        // Each order the shoppers were told was accepted exists.
        for (String id : placed.keySet()) {
            JsonNode order = shop.get("/api/v1/backoffice/orders/" + id);
            if (order == null) {
                problem("order " + id + " was accepted but does not exist (lost request)");
            } else {
                orders.put(id, order);
            }
        }
        // ... and exactly once: every order recorded for these shoppers in the window is one they were told about.
        for (String shopper : new HashSet<>(placed.values())) {
            for (JsonNode o : shop.all("/api/v1/backoffice/orders?q=" + shopper)) {
                if (inWindow(o.path("createdAt").asString(), from, to) && !known.contains(o.path("id").asString())) {
                    problem(shopper + " has order " + o.path("id").asString()
                            + " that no checkout of theirs was answered with (duplicated request)");
                }
            }
            JsonNode sent = shop.get("/api/v1/backoffice/notifications?recipient=" + shopper);
            if (sent != null) {
                sent.forEach(n -> emails.computeIfAbsent(n.path("orderId").asString(), k -> new ArrayList<>()).add(n));
            }
        }
        for (JsonNode first : orders.values()) {
            // The services are read one after another, and a shopper may still ask
            // to return a delivered order meanwhile. An order whose status moved
            // while it was being read is read again, so a problem is only one that
            // holds of a single, consistent state.
            JsonNode order = first;
            List<String> found = new ArrayList<>();
            for (int attempt = 0; attempt < 3; attempt++) {
                found = new ArrayList<>();
                sink = found;
                List<JsonNode> mail = attempt == 0 ? emails.getOrDefault(order.path("id").asString(), List.of())
                        : emailsFor(placed.get(order.path("id").asString()), order.path("id").asString());
                auditOrder(order, mail, allowedReasons);
                sink = problems;
                JsonNode again = shop.get("/api/v1/backoffice/orders/" + order.path("id").asString());
                if (again == null || again.path("status").asString().equals(order.path("status").asString())) {
                    break;
                }
                order = again;
                Shop.sleep(2000);
            }
            problems.addAll(found);
        }
        // Nothing is still held for an order: at rest, every reservation was committed or released.
        for (JsonNode s : shop.all("/api/v1/backoffice/inventory/stock")) {
            if (s.path("reserved").asInt() != 0) {
                problem("stock " + s.path("sku").asString() + " still has " + s.path("reserved").asInt()
                        + " reserved with no order in flight (leaked reservation)");
            }
            if (s.path("onHand").asInt() < 0 || s.path("available").asInt() < 0) {
                problem("stock " + s.path("sku").asString() + " is negative (oversold)");
            }
        }
        List<String> support = problems.stream().filter(p -> p.startsWith(SUPPORT))
                .map(p -> p.substring(SUPPORT.length())).toList();
        problems.removeIf(p -> p.startsWith(SUPPORT));
        if (!support.isEmpty()) {
            run.check("cancelled after the parcel left: support cases, not ledger errors", true,
                    support.size() + ": " + String.join("; ", support.subList(0, Math.min(5, support.size()))));
        }
        run.check("ledger audit (" + orders.size() + " orders)", problems.isEmpty(),
                problems.isEmpty() ? "every invariant holds" : String.join("; ", problems.subList(0, Math.min(8, problems.size())))
                        + (problems.size() > 8 ? "; and " + (problems.size() - 8) + " more" : ""));
    }

    private void auditOrder(JsonNode order, List<JsonNode> emails, Set<String> allowedReasons) {
        String id = order.path("id").asString();
        String status = order.path("status").asString();
        long total = order.path("totalCents").asLong();
        if (status.equals("PENDING") || status.equals("AWAITING_PAYMENT")) {
            problem(id + " is still " + status + " (stuck saga)");
            return;
        }
        if (status.equals("CANCELLED")) {
            String reason = order.path("cancelReason").asString("");
            if (!allowedReasons.contains(reason)) {
                problem(id + " was cancelled for " + reason + ", which this experiment does not expect");
            }
        }
        boolean confirmed = history(order).contains("CONFIRMED");

        // Payments: one of the right amount when charged; refunded when cancelled or returned after it.
        JsonNode payments = shop.get("/api/v1/backoffice/billing/payments?orderId=" + id);
        List<JsonNode> charged = new ArrayList<>();
        if (payments != null) {
            payments.forEach(p -> {
                String ps = p.path("status").asString();
                if (ps.equals("AUTHORIZED") || ps.equals("REFUNDED") || ps.equals("REFUND_PENDING")) {
                    charged.add(p);
                }
                if (ps.equals("REFUND_PENDING")) {
                    problem(id + "'s refund is still waiting for the provider (" + p.path("refundLastError").asString("")
                            + ")");
                }
            });
        }
        if (charged.size() > 1) {
            problem(id + " was charged " + charged.size() + " times (double charge)");
        }
        for (JsonNode p : charged) {
            if (p.path("amountCents").asLong() != total) {
                problem(id + " was charged " + p.path("amountCents").asLong() + " for a total of " + total);
            }
        }
        if (CONFIRMED_OR_LATER.contains(status) && !status.equals("REFUNDED") && !status.equals("RETURNED")) {
            if (charged.size() != 1 || !charged.getFirst().path("status").asString().equals("AUTHORIZED")) {
                problem(id + " is " + status + " but has no single authorised payment (missing charge)");
            }
        }
        if (status.equals("REFUNDED") && (charged.size() != 1 || !charged.getFirst().path("status").asString().equals("REFUNDED"))) {
            problem(id + " was returned but its payment is not refunded (missing refund)");
        }
        if (status.equals("CANCELLED") && charged.stream().anyMatch(p -> p.path("status").asString().equals("AUTHORIZED"))) {
            problem(id + " was cancelled but its payment was never refunded (charged for nothing)");
        }

        // Stock: what this order took was committed while it stands, and given back when it did not.
        JsonNode reservations = shop.get("/api/v1/backoffice/inventory/reservations?orderId=" + id);
        Map<String, Integer> lines = new HashMap<>();
        order.path("lines").forEach(l -> lines.merge(l.path("sku").asString(), l.path("quantity").asInt(), Integer::sum));
        if (reservations != null) {
            for (JsonNode r : reservations) {
                String rs = r.path("status").asString();
                int quantity = r.path("quantity").asInt();
                if (!Integer.valueOf(quantity).equals(lines.get(r.path("sku").asString()))) {
                    problem(id + " reserved " + quantity + " of " + r.path("sku").asString() + " for a line of "
                            + lines.get(r.path("sku").asString()));
                }
                boolean shouldHold = CONFIRMED_OR_LATER.contains(status) && !status.equals("REFUNDED");
                if (shouldHold && !rs.equals("COMMITTED")) {
                    problem(id + " is " + status + " but its " + r.path("sku").asString() + " stock is " + rs);
                }
                if (!shouldHold && !rs.equals("RELEASED")) {
                    problem(id + " is " + status + " but still holds " + r.path("sku").asString() + " stock (" + rs + ")");
                }
            }
            if (CONFIRMED_OR_LATER.contains(status) && reservations.size() != lines.size()) {
                problem(id + " is " + status + " with " + reservations.size() + " reservations for " + lines.size() + " lines");
            }
        }

        // Shipments: one outbound once shipped, one return once returned, none sent for a cancelled order.
        JsonNode shipments = shop.get("/api/v1/backoffice/shipping/shipments?orderId=" + id);
        int outbound = 0;
        int returns = 0;
        if (shipments != null) {
            for (JsonNode s : shipments) {
                if (s.path("direction").asString().equals("OUTBOUND")) {
                    outbound++;
                    String ss = s.path("status").asString();
                    if (status.equals("CANCELLED") && !ss.equals("CANCELLED")) {
                        if (confirmed && LEFT.contains(ss)) {
                            // The customer cancelled while the carrier was collecting it: the
                            // parcel had left before shipping saw the cancellation. A known race
                            // that support handles as a return (architecture, section 6).
                            problem(SUPPORT + id + " was cancelled after its parcel left (" + ss + ")");
                        } else {
                            problem(id + " was cancelled but its shipment is " + ss);
                        }
                    }
                } else {
                    returns++;
                }
            }
        }
        if (SHIPPED_OR_LATER.contains(status) && outbound != 1) {
            problem(id + " is " + status + " with " + outbound + " outbound shipments");
        }
        if (status.equals("REFUNDED") && returns != 1) {
            problem(id + " was returned with " + returns + " return shipments");
        }

        // Emails: one per customer-facing event, never two.
        Map<String, Integer> sent = new HashMap<>();
        emails.forEach(e -> sent.merge(e.path("template").asString(), 1, Integer::sum));
        sent.forEach((template, n) -> {
            if (n > 1) {
                problem(id + " got " + n + " '" + template + "' emails (duplicate)");
            }
        });
        if (confirmed && !sent.containsKey("order-confirmed")) {
            problem(id + " was confirmed but no confirmation email was sent");
        }
        if (status.equals("CANCELLED") && !sent.containsKey("order-cancelled")) {
            problem(id + " was cancelled but no cancellation email was sent");
        }
        if (status.equals("REFUNDED") && !sent.containsKey("order-refunded")) {
            problem(id + " was refunded but no refund email was sent");
        }
    }

    private static Set<String> history(JsonNode order) {
        Set<String> statuses = new HashSet<>();
        order.path("history").forEach(h -> statuses.add(h.path("status").asString()));
        return statuses;
    }

    private static boolean inWindow(String at, Instant from, Instant to) {
        Instant t = Instant.parse(at);
        return !t.isBefore(from) && !t.isAfter(to);
    }

    private List<JsonNode> emailsFor(String shopper, String orderId) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode sent = shopper == null ? null : shop.get("/api/v1/backoffice/notifications?recipient=" + shopper);
        if (sent != null) {
            sent.forEach(n -> {
                if (n.path("orderId").asString().equals(orderId)) {
                    out.add(n);
                }
            });
        }
        return out;
    }

    private void problem(String text) {
        sink.add(text);
    }
}
