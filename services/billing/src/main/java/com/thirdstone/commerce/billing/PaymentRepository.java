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
            rs.getString("failure_reason"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));

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

    int markRefunded(String orderId, String refundRef) {
        return jdbc.sql("""
                        UPDATE payments SET status = 'REFUNDED', refund_ref = ?, updated_at = UTC_TIMESTAMP(6)
                        WHERE order_id = ? AND status = 'AUTHORIZED'""")
                .params(refundRef, orderId).update();
    }

    List<Payment> search(String orderId, Instant from, Instant to, int limit) {
        if (orderId != null) {
            return jdbc.sql("SELECT * FROM payments WHERE order_id = ?").param(orderId).query(PAYMENT).list();
        }
        return jdbc.sql("SELECT * FROM payments WHERE created_at >= ? AND created_at < ? ORDER BY created_at LIMIT ?")
                .params(Sql.param(from), Sql.param(to), limit).query(PAYMENT).list();
    }
}
