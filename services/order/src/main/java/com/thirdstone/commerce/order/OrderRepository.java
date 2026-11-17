package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.data.Sql;
import com.thirdstone.commerce.platform.events.CatalogEvents;
import com.thirdstone.commerce.platform.events.Line;
import com.thirdstone.commerce.platform.web.PageRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
class OrderRepository {

    private static final RowMapper<OrderRow> ORDER = (rs, n) -> new OrderRow(rs.getString("id"),
            rs.getLong("user_id"), rs.getString("email"), rs.getString("status"), rs.getLong("total_cents"),
            rs.getString("currency"), rs.getString("payment_method"), rs.getString("shipping_address"),
            rs.getString("cancel_reason"), rs.getString("tracking_number"), rs.getString("return_tracking_number"),
            Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), Sql.instant(rs, "delivered_at"));

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ orders

    Optional<OrderRow> find(String id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = ?").param(id).query(ORDER).optional();
    }

    /** Another user's order is simply not found. */
    Optional<OrderRow> findForUser(String id, long userId) {
        return jdbc.sql("SELECT * FROM orders WHERE id = ? AND user_id = ?").params(id, userId).query(ORDER).optional();
    }

    Optional<OrderRow> findByIdempotencyKey(long userId, String key) {
        return jdbc.sql("SELECT * FROM orders WHERE user_id = ? AND idempotency_key = ?").params(userId, key)
                .query(ORDER).optional();
    }

    void insert(OrderRow o, String idempotencyKey) {
        jdbc.sql("""
                        INSERT INTO orders (id, user_id, email, status, total_cents, currency, payment_method,
                                            shipping_address, idempotency_key, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(o.id(), o.userId(), o.email(), o.status(), o.totalCents(), o.currency(), o.paymentMethod(),
                        o.shippingAddressJson(), idempotencyKey, Sql.param(o.createdAt()), Sql.param(o.createdAt()))
                .update();
    }

    void insertLines(String orderId, List<Line> lines) {
        int n = 1;
        for (Line line : lines) {
            jdbc.sql("""
                            INSERT INTO order_lines (order_id, line_no, sku, name, quantity, unit_price_cents)
                            VALUES (?, ?, ?, ?, ?, ?)""")
                    .params(orderId, n++, line.sku(), line.name(), line.quantity(), line.unitPriceCents()).update();
        }
    }

    List<Line> lines(String orderId) {
        return jdbc.sql("SELECT sku, name, quantity, unit_price_cents FROM order_lines WHERE order_id = ? ORDER BY line_no")
                .param(orderId)
                .query((rs, n) -> new Line(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4))).list();
    }

    void history(String orderId, String status, String detail) {
        jdbc.sql("INSERT INTO order_history (order_id, status, detail, at) VALUES (?, ?, ?, UTC_TIMESTAMP(6))")
                .params(orderId, status, detail.length() > 500 ? detail.substring(0, 500) : detail).update();
    }

    List<OrderView.HistoryEntry> history(String orderId) {
        return jdbc.sql("SELECT status, detail, at FROM order_history WHERE order_id = ? ORDER BY id")
                .param(orderId)
                .query((rs, n) -> new OrderView.HistoryEntry(rs.getString(1), rs.getString(2), Sql.instant(rs, "at")))
                .list();
    }

    /**
     * Moves an order on, but only from where the event expects it to be. Zero rows
     * means the event no longer applies (the order moved on, or never existed).
     */
    int transition(String id, String to, Collection<String> from) {
        return jdbc.sql("UPDATE orders SET status = :to, updated_at = UTC_TIMESTAMP(6) WHERE id = :id AND status IN (:from)")
                .param("to", to).param("id", id).param("from", from).update();
    }

    int cancel(String id, String reason, Collection<String> from) {
        return jdbc.sql("""
                        UPDATE orders SET status = 'CANCELLED', cancel_reason = :reason, updated_at = UTC_TIMESTAMP(6)
                        WHERE id = :id AND status IN (:from)""")
                .param("reason", reason).param("id", id).param("from", from).update();
    }

    int setTracking(String id, String trackingNumber) {
        return jdbc.sql("UPDATE orders SET tracking_number = ?, updated_at = UTC_TIMESTAMP(6) WHERE id = ?")
                .params(trackingNumber, id).update();
    }

    int markDelivered(String id) {
        return jdbc.sql("""
                        UPDATE orders SET status = 'DELIVERED', delivered_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                        WHERE id = ? AND status = 'SHIPPED'""")
                .param(id).update();
    }

    int requestReturn(String id, String reason, Instant deliveredAfter) {
        return jdbc.sql("""
                        UPDATE orders SET status = 'RETURN_REQUESTED', return_reason = ?, updated_at = UTC_TIMESTAMP(6)
                        WHERE id = ? AND status = 'DELIVERED' AND delivered_at >= ?""")
                .params(reason, id, Sql.param(deliveredAfter)).update();
    }

    int setReturnTracking(String id, String trackingNumber) {
        return jdbc.sql("UPDATE orders SET return_tracking_number = ?, updated_at = UTC_TIMESTAMP(6) WHERE id = ?")
                .params(trackingNumber, id).update();
    }

    List<OrderRow> listForUser(long userId, PageRequest page) {
        return jdbc.sql("SELECT * FROM orders WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(userId, page.size(), page.offset()).query(ORDER).list();
    }

    long countForUser(long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM orders WHERE user_id = ?").param(userId).query(Long.class).single();
    }

    List<OrderRow> search(String q, String status, PageRequest page) {
        List<Object> params = new ArrayList<>();
        String where = where(q, status, params);
        params.add(page.size());
        params.add(page.offset());
        return jdbc.sql("SELECT * FROM orders" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(params).query(ORDER).list();
    }

    long count(String q, String status) {
        List<Object> params = new ArrayList<>();
        return jdbc.sql("SELECT COUNT(*) FROM orders" + where(q, status, params)).params(params)
                .query(Long.class).single();
    }

    private static String where(String q, String status, List<Object> params) {
        List<String> conditions = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String term = q.strip();
            if (OrderController.ID.matcher(term).matches()) {
                conditions.add("id = ?");
                params.add(term);
            } else {
                // An email prefix: uses the index, and wildcards are taken literally.
                conditions.add("email LIKE ? ESCAPE '!'");
                params.add(term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            }
        }
        if (status != null && !status.isBlank()) {
            conditions.add("status = ?");
            params.add(status);
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    // ------------------------------------------------------------- saga upkeep

    long inFlight() {
        return jdbc.sql("SELECT COUNT(*) FROM orders WHERE status IN (:statuses)")
                .param("statuses", OrderStatus.IN_FLIGHT).query(Long.class).single();
    }

    Map<String, Long> countByStatus() {
        Map<String, Long> counts = new HashMap<>();
        jdbc.sql("SELECT status, COUNT(*) FROM orders GROUP BY status")
                .query(rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
        return counts;
    }

    /**
     * Orders that have waited too long, locked for this transaction. SKIP LOCKED:
     * with several replicas each takes a different batch instead of queueing on
     * the same rows.
     */
    List<String> lockExpired(Instant placedBefore, int limit) {
        return jdbc.sql("""
                        SELECT id FROM orders WHERE status IN (:statuses) AND created_at < :before
                        ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED""")
                .param("statuses", OrderStatus.IN_FLIGHT).param("before", Sql.param(placedBefore))
                .param("limit", limit).query(String.class).list();
    }

    // ---------------------------------------------------------- price snapshot

    Map<String, ProductSnapshot> products(Collection<String> skus) {
        Map<String, ProductSnapshot> found = new LinkedHashMap<>();
        if (skus.isEmpty()) {
            return found;
        }
        jdbc.sql("SELECT sku, name, price_cents, currency, active FROM products WHERE sku IN (:skus)")
                .param("skus", skus)
                .query((rs, n) -> new ProductSnapshot(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getBoolean(5)))
                .list().forEach(p -> found.put(p.sku(), p));
        return found;
    }

    void upsertProduct(CatalogEvents.ProductUpserted p) {
        jdbc.sql("""
                        INSERT INTO products (sku, name, price_cents, currency, active, updated_at)
                        VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE name = VALUES(name), price_cents = VALUES(price_cents),
                            currency = VALUES(currency), active = VALUES(active), updated_at = VALUES(updated_at)""")
                .params(p.sku(), p.name(), p.priceCents(), p.currency(), p.active()).update();
    }
}
