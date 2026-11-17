package com.thirdstone.commerce.ops.incidents;

import com.thirdstone.commerce.platform.data.Sql;
import com.thirdstone.commerce.platform.web.PageRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class IncidentRepository {

    private static final RowMapper<Incident> INCIDENT = (rs, n) -> new Incident(rs.getLong("id"),
            rs.getString("alertname"), rs.getString("severity"), rs.getString("service"), rs.getString("summary"),
            rs.getString("description"), rs.getString("runbook_url"), rs.getString("status"),
            Sql.instant(rs, "starts_at"), Sql.instant(rs, "ends_at"), rs.getString("acknowledged_by"),
            Sql.instant(rs, "acknowledged_at"), Sql.instant(rs, "updated_at"), List.of());

    private final JdbcClient jdbc;

    IncidentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void upsert(String fingerprint, String alertname, String severity, String service, String summary,
                String description, String runbookUrl, String labelsJson, String status, Instant startsAt,
                Instant endsAt) {
        jdbc.sql("""
                        INSERT INTO incidents (fingerprint, alertname, severity, service, summary, description, runbook_url,
                                               labels, status, starts_at, ends_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE status = VALUES(status), ends_at = VALUES(ends_at),
                            summary = VALUES(summary), description = VALUES(description), labels = VALUES(labels),
                            updated_at = VALUES(updated_at)""")
                .params(fingerprint, alertname, severity, service, summary, description, runbookUrl, labelsJson, status,
                        Sql.param(startsAt), Sql.param(endsAt))
                .update();
    }

    long insertResolved(String alertname, String service, String summary, String author) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO incidents (fingerprint, alertname, severity, service, summary, labels, status,
                                               starts_at, ends_at, acknowledged_by, acknowledged_at, updated_at)
                        VALUES (?, ?, 'info', ?, ?, '{}', 'RESOLVED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?,
                                UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""")
                .params("operator-" + java.util.UUID.randomUUID(), alertname, service, summary, author)
                .update(keys);
        return keys.getKey().longValue();
    }

    Optional<Incident> find(long id) {
        return jdbc.sql("SELECT * FROM incidents WHERE id = ?").param(id).query(INCIDENT).optional()
                .map(i -> new Incident(i.id(), i.alertname(), i.severity(), i.service(), i.summary(), i.description(),
                        i.runbookUrl(), i.status(), i.startsAt(), i.endsAt(), i.acknowledgedBy(), i.acknowledgedAt(),
                        i.updatedAt(), notes(id)));
    }

    List<Incident.Note> notes(long incidentId) {
        return jdbc.sql("SELECT id, author, note, at FROM incident_notes WHERE incident_id = ? ORDER BY id")
                .param(incidentId)
                .query((rs, n) -> new Incident.Note(rs.getLong(1), rs.getString(2), rs.getString(3), Sql.instant(rs, "at")))
                .list();
    }

    List<Incident> list(String status, PageRequest page) {
        if (status == null) {
            return jdbc.sql("SELECT * FROM incidents ORDER BY status = 'FIRING' DESC, starts_at DESC LIMIT ? OFFSET ?")
                    .params(page.size(), page.offset()).query(INCIDENT).list();
        }
        return jdbc.sql("SELECT * FROM incidents WHERE status = ? ORDER BY starts_at DESC LIMIT ? OFFSET ?")
                .params(status, page.size(), page.offset()).query(INCIDENT).list();
    }

    long count(String status) {
        return status == null ? jdbc.sql("SELECT COUNT(*) FROM incidents").query(Long.class).single()
                : jdbc.sql("SELECT COUNT(*) FROM incidents WHERE status = ?").param(status).query(Long.class).single();
    }

    int acknowledge(long id, String who) {
        return jdbc.sql("""
                        UPDATE incidents SET acknowledged_by = ?, acknowledged_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                        WHERE id = ? AND acknowledged_by IS NULL""")
                .params(who, id).update();
    }

    void note(long incidentId, String author, String note) {
        jdbc.sql("INSERT INTO incident_notes (incident_id, author, note, at) VALUES (?, ?, ?, UTC_TIMESTAMP(6))")
                .params(incidentId, author, note).update();
    }

    public int firingCount() {
        return jdbc.sql("SELECT COUNT(*) FROM incidents WHERE status = 'FIRING'").query(Integer.class).single();
    }

    public Map<String, Integer> firingByService() {
        Map<String, Integer> out = new HashMap<>();
        jdbc.sql("SELECT service, COUNT(*) FROM incidents WHERE status = 'FIRING' AND service IS NOT NULL GROUP BY service")
                .query(rs -> {
                    out.put(rs.getString(1), rs.getInt(2));
                });
        return out;
    }

    int purgeResolvedBefore(Instant before) {
        jdbc.sql("""
                        DELETE n FROM incident_notes n JOIN incidents i ON i.id = n.incident_id
                        WHERE i.status = 'RESOLVED' AND i.ends_at < ?""").param(Sql.param(before)).update();
        return jdbc.sql("DELETE FROM incidents WHERE status = 'RESOLVED' AND ends_at < ?").param(Sql.param(before)).update();
    }
}
