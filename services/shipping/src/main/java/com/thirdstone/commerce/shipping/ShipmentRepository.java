package com.thirdstone.commerce.shipping;

import com.thirdstone.commerce.platform.data.Sql;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Repository
class ShipmentRepository {

    static final List<String> ACTIVE = List.of("PENDING", "CREATED", "DISPATCHED", "IN_TRANSIT");

    private static final RowMapper<Shipment> SHIPMENT = (rs, n) -> new Shipment(rs.getString("id"),
            rs.getString("order_id"), rs.getString("direction"), rs.getString("status"), rs.getLong("user_id"),
            rs.getString("email"), rs.getString("address"), rs.getString("carrier"), rs.getString("tracking_number"),
            rs.getInt("attempts"), rs.getString("last_error"), Sql.instant(rs, "next_attempt_at"),
            Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    ShipmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 0 if the order already has a shipment in this direction (a repeated event). */
    int insert(String id, String orderId, String direction, long userId, String email, String addressJson) {
        return jdbc.sql("""
                        INSERT IGNORE INTO shipments (id, order_id, direction, status, user_id, email, address,
                                                      next_attempt_at, created_at, updated_at)
                        VALUES (?, ?, ?, 'PENDING', ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""")
                .params(id, orderId, direction, userId, email, addressJson).update();
    }

    int cancelOutbound(String orderId) {
        return jdbc.sql("""
                        UPDATE shipments SET status = 'CANCELLED', updated_at = UTC_TIMESTAMP(6)
                        WHERE order_id = ? AND direction = 'OUTBOUND' AND status IN ('PENDING', 'CREATED')""")
                .param(orderId).update();
    }

    Optional<Shipment> find(String id) {
        return jdbc.sql("SELECT * FROM shipments WHERE id = ?").param(id).query(SHIPMENT).optional();
    }

    List<Shipment> forOrder(String orderId) {
        return jdbc.sql("SELECT * FROM shipments WHERE order_id = ? ORDER BY created_at").param(orderId)
                .query(SHIPMENT).list();
    }

    /**
     * Due work, locked only long enough to lease it: each claimed row's next
     * attempt moves past the lease, so other workers (other replicas) skip it
     * while this one talks to the carrier outside any transaction. A worker that
     * dies mid-call simply lets the lease run out.
     */
    List<String> claimDue(int limit, Duration lease) {
        List<String> ids = jdbc.sql("""
                        SELECT id FROM shipments WHERE status IN (:active) AND next_attempt_at <= UTC_TIMESTAMP(6)
                        ORDER BY next_attempt_at LIMIT :limit FOR UPDATE SKIP LOCKED""")
                .param("active", ACTIVE).param("limit", limit).query(String.class).list();
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE shipments SET next_attempt_at = UTC_TIMESTAMP(6) + INTERVAL :lease SECOND WHERE id IN (:ids)")
                    .param("lease", lease.toSeconds()).param("ids", ids).update();
        }
        return ids;
    }

    /** Moves a shipment on from the state it was read in; 0 if it moved in the meantime (cancelled, say). */
    int advance(String id, String from, String to, String carrier, String trackingNumber, Duration nextIn) {
        return jdbc.sql("""
                        UPDATE shipments SET status = ?, carrier = COALESCE(?, carrier),
                            tracking_number = COALESCE(?, tracking_number), attempts = 0, last_error = NULL,
                            next_attempt_at = UTC_TIMESTAMP(6) + INTERVAL ? MICROSECOND, updated_at = UTC_TIMESTAMP(6)
                        WHERE id = ? AND status = ?""")
                .params(to, carrier, trackingNumber, nextIn.toNanos() / 1000, id, from).update();
    }

    void failed(String id, String error, Duration retryIn) {
        jdbc.sql("""
                        UPDATE shipments SET attempts = attempts + 1, last_error = ?,
                            next_attempt_at = UTC_TIMESTAMP(6) + INTERVAL ? SECOND, updated_at = UTC_TIMESTAMP(6)
                        WHERE id = ?""")
                .params(error.length() > 200 ? error.substring(0, 200) : error, retryIn.toSeconds(), id).update();
    }
}
