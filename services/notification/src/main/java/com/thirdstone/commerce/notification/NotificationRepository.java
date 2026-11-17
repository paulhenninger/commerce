package com.thirdstone.commerce.notification;

import com.thirdstone.commerce.platform.data.Sql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
class NotificationRepository {

    record Pending(long id, String recipient, String template, String subject, String body, int attempts) {
    }

    /** What the back office may see: who was sent what, when — never the body. */
    record Sent(long id, String orderId, String recipient, String template, String subject, String status,
                int attempts, Instant createdAt, Instant sentAt) {
    }

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    int insert(String eventId, String orderId, Email email) {
        return jdbc.sql("""
                        INSERT IGNORE INTO notifications (event_id, order_id, recipient, template, subject, body, status,
                                                          next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""")
                .params(eventId, orderId, email.recipient(), email.template(), email.subject(), email.body()).update();
    }

    /** Due emails, leased to this worker (see ShipmentWorker for the pattern). */
    List<Long> claimDue(int limit, Duration lease) {
        List<Long> ids = jdbc.sql("""
                        SELECT id FROM notifications WHERE status = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6)
                        ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED""")
                .param(limit).query(Long.class).list();
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE notifications SET next_attempt_at = UTC_TIMESTAMP(6) + INTERVAL :lease SECOND WHERE id IN (:ids)")
                    .param("lease", lease.toSeconds()).param("ids", ids).update();
        }
        return ids;
    }

    Optional<Pending> pending(long id) {
        return jdbc.sql("SELECT id, recipient, template, subject, body, attempts FROM notifications "
                        + "WHERE id = ? AND status = 'PENDING'")
                .param(id).query((rs, n) -> new Pending(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6))).optional();
    }

    void sent(long id) {
        jdbc.sql("UPDATE notifications SET status = 'SENT', sent_at = UTC_TIMESTAMP(6), attempts = attempts + 1 "
                + "WHERE id = ? AND status = 'PENDING'").param(id).update();
    }

    void failed(long id, String error, Duration retryIn, boolean giveUp) {
        jdbc.sql("""
                        UPDATE notifications SET attempts = attempts + 1, last_error = ?, status = ?,
                            next_attempt_at = UTC_TIMESTAMP(6) + INTERVAL ? SECOND
                        WHERE id = ? AND status = 'PENDING'""")
                .params(error.length() > 200 ? error.substring(0, 200) : error, giveUp ? "FAILED" : "PENDING",
                        retryIn.toSeconds(), id).update();
    }

    List<Sent> search(String recipient, Instant from, Instant to, int limit) {
        String select = "SELECT id, order_id, recipient, template, subject, status, attempts, created_at, sent_at "
                + "FROM notifications ";
        var query = recipient != null
                ? jdbc.sql(select + "WHERE recipient = ? ORDER BY created_at DESC LIMIT ?").params(recipient, limit)
                : jdbc.sql(select + "WHERE created_at >= ? AND created_at < ? ORDER BY created_at LIMIT ?")
                        .params(Sql.param(from), Sql.param(to), limit);
        return query.query((rs, n) -> new Sent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "sent_at"))).list();
    }
}
