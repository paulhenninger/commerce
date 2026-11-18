package com.thirdstone.commerce.billing;

import com.thirdstone.commerce.platform.data.Sql;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
class PaymentRepository {

    private static final RowMapper<Payment> PAYMENT = (rs, n) -> new Payment(rs.getString("id"),
            rs.getString("order_id"), rs.getLong("user_id"), rs.getLong("amount_cents"), rs.getString("currency"),
            rs.getString("status"), rs.getString("provider_ref"), rs.getString("refund_ref"),
            rs.getString("failure_reason"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"),
            rs.getInt("refund_attempts"), rs.getString("refund_last_error"));

    private final JdbcClient jdbc;

    PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Payment> findByOrder(String orderId) {
        return jdbc.sql("SELECT * FROM payments WHERE order_id = ?").param(orderId).query(PAYMENT).optional();
    }

    /** 0 if the order already has a payment row (of any kind). */
    int insert(String id, String orderId, long userId, long amountCents, String currency, String status,
               String providerRef, String failureReason) {
        return jdbc.sql("""
                        INSERT IGNORE INTO payments (id, order_id, user_id, amount_cents, currency, status, provider_ref,
                                                     failure_reason, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""")
                .params(id, orderId, userId, amountCents, currency, status, providerRef, failureReason).update();
    }

    /** Queues the refund of a charged order; 0 if there is no charge to refund (none, or already refunding). */
    int requestRefund(String orderId) {
        return jdbc.sql("""
                        UPDATE payments SET status = 'REFUND_PENDING', refund_attempts = 0,
                            refund_next_attempt_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                        WHERE order_id = ? AND status = 'AUTHORIZED'""")
                .param(orderId).update();
    }

    /**
     * Due refunds, locked only long enough to lease them (as shipping's worker
     * does): other replicas skip a leased row, and a worker that dies mid-call
     * lets the lease run out.
     */
    List<Payment> claimDueRefunds(int limit, java.time.Duration lease) {
        List<Payment> due = jdbc.sql("""
                        SELECT * FROM payments WHERE status = 'REFUND_PENDING' AND refund_next_attempt_at <= UTC_TIMESTAMP(6)
                        ORDER BY refund_next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED""")
                .param(limit).query(PAYMENT).list();
        if (!due.isEmpty()) {
            jdbc.sql("UPDATE payments SET refund_next_attempt_at = UTC_TIMESTAMP(6) + INTERVAL :lease SECOND WHERE id IN (:ids)")
                    .param("lease", lease.toSeconds()).param("ids", due.stream().map(Payment::id).toList()).update();
        }
        return due;
    }

    int markRefunded(String orderId, String refundRef) {
        return jdbc.sql("""
                        UPDATE payments SET status = 'REFUNDED', refund_ref = ?, refund_last_error = NULL,
                            refund_next_attempt_at = NULL, updated_at = UTC_TIMESTAMP(6)
                        WHERE order_id = ? AND status = 'REFUND_PENDING'""")
                .params(refundRef, orderId).update();
    }

    void refundFailed(String id, String error, java.time.Duration retryIn) {
        String e = error == null ? "unknown" : error.length() > 200 ? error.substring(0, 200) : error;
        jdbc.sql("""
                        UPDATE payments SET refund_attempts = refund_attempts + 1, refund_last_error = ?,
                            refund_next_attempt_at = UTC_TIMESTAMP(6) + INTERVAL ? SECOND
                        WHERE id = ? AND status = 'REFUND_PENDING'""")
                .params(e, retryIn.toSeconds(), id).update();
    }

    int refundAttempts(String id) {
        return jdbc.sql("SELECT refund_attempts FROM payments WHERE id = ?").param(id).query(Integer.class).single();
    }

    /** Refunds waiting, and how long the oldest has waited (seconds; 0 if none). */
    long[] pendingRefunds() {
        return jdbc.sql("""
                        SELECT COUNT(*) AS n,
                               COALESCE(TIMESTAMPDIFF(SECOND, MIN(updated_at), UTC_TIMESTAMP(6)), 0) AS oldest
                        FROM payments WHERE status = 'REFUND_PENDING'""")
                .query((rs, n) -> new long[]{rs.getLong("n"), rs.getLong("oldest")}).single();
    }

    List<Payment> search(String orderId, Instant from, Instant to, int limit) {
        if (orderId != null) {
            return jdbc.sql("SELECT * FROM payments WHERE order_id = ?").param(orderId).query(PAYMENT).list();
        }
        return jdbc.sql("SELECT * FROM payments WHERE created_at >= ? AND created_at < ? ORDER BY created_at LIMIT ?")
                .params(Sql.param(from), Sql.param(to), limit).query(PAYMENT).list();
    }
}
