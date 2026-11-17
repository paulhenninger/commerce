package com.thirdstone.commerce.inventory;

import com.thirdstone.commerce.platform.data.Sql;
import com.thirdstone.commerce.platform.web.PageRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
class InventoryRepository {

    private static final RowMapper<StockLevel> STOCK = (rs, n) -> new StockLevel(rs.getString("sku"),
            rs.getInt("on_hand"), rs.getInt("reserved"), rs.getInt("on_hand") - rs.getInt("reserved"),
            Sql.instant(rs, "updated_at"));
    private static final RowMapper<Reservation> RESERVATION = (rs, n) -> new Reservation(rs.getString("order_id"),
            rs.getString("sku"), rs.getInt("quantity"), rs.getString("status"), Sql.instant(rs, "created_at"),
            Sql.instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void ensureStock(String sku) {
        jdbc.sql("INSERT IGNORE INTO stock (sku, on_hand, reserved, updated_at) VALUES (?, 0, 0, UTC_TIMESTAMP(6))")
                .param(sku).update();
    }

    Optional<StockLevel> stock(String sku) {
        return jdbc.sql("SELECT * FROM stock WHERE sku = ?").param(sku).query(STOCK).optional();
    }

    /**
     * Locks the stock rows of an order, always in SKU order: two orders for the
     * same products then queue on the first one instead of deadlocking.
     */
    Map<String, StockLevel> lock(Collection<String> skusInOrder) {
        Map<String, StockLevel> rows = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM stock WHERE sku IN (:skus) ORDER BY sku FOR UPDATE").param("skus", skusInOrder)
                .query(STOCK).list().forEach(s -> rows.put(s.sku(), s));
        return rows;
    }

    Optional<StockLevel> lock(String sku) {
        return jdbc.sql("SELECT * FROM stock WHERE sku = ? FOR UPDATE").param(sku).query(STOCK).optional();
    }

    void adjust(String sku, int onHandDelta, int reservedDelta) {
        jdbc.sql("""
                        UPDATE stock SET on_hand = on_hand + ?, reserved = reserved + ?, updated_at = UTC_TIMESTAMP(6)
                        WHERE sku = ?""")
                .params(onHandDelta, reservedDelta, sku).update();
    }

    void setOnHand(String sku, int onHand) {
        jdbc.sql("""
                        INSERT INTO stock (sku, on_hand, reserved, updated_at) VALUES (?, ?, 0, UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE on_hand = VALUES(on_hand), updated_at = VALUES(updated_at)""")
                .params(sku, onHand).update();
    }

    boolean hasReservations(String orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM reservations WHERE order_id = ?").param(orderId)
                .query(Integer.class).single() > 0;
    }

    void reserve(String orderId, String sku, int quantity) {
        jdbc.sql("""
                        INSERT INTO reservations (order_id, sku, quantity, status, created_at, updated_at)
                        VALUES (?, ?, ?, 'RESERVED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""")
                .params(orderId, sku, quantity).update();
    }

    List<Reservation> reservations(String orderId) {
        return jdbc.sql("SELECT * FROM reservations WHERE order_id = ? ORDER BY sku").param(orderId)
                .query(RESERVATION).list();
    }

    /** One reservation from one state to another; 0 if it was not in that state. */
    int moveReservation(String orderId, String sku, String from, String to) {
        return jdbc.sql("""
                        UPDATE reservations SET status = ?, updated_at = UTC_TIMESTAMP(6)
                        WHERE order_id = ? AND sku = ? AND status = ?""")
                .params(to, orderId, sku, from).update();
    }

    List<StockLevel> list(PageRequest page) {
        return jdbc.sql("SELECT * FROM stock ORDER BY sku LIMIT ? OFFSET ?").params(page.size(), page.offset())
                .query(STOCK).list();
    }

    long count() {
        return jdbc.sql("SELECT COUNT(*) FROM stock").query(Long.class).single();
    }

    List<StockLevel> lowest(int limit) {
        return jdbc.sql("SELECT * FROM stock ORDER BY on_hand - reserved, sku LIMIT ?").param(limit)
                .query(STOCK).list();
    }
}
