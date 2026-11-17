package com.thirdstone.commerce.ops.incidents;

import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import com.thirdstone.commerce.platform.web.PageRequest;
import com.thirdstone.commerce.platform.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Alerts become incidents; people acknowledge them and write down what they did. */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    /** Alertmanager's "not ended". */
    private static final Instant ZERO = Instant.parse("0001-01-01T00:00:00Z");

    private final IncidentRepository incidents;
    private final JsonMapper json;

    IncidentService(IncidentRepository incidents, JsonMapper json) {
        this.incidents = incidents;
        this.json = json;
    }

    /** Alertmanager's webhook payload (version 4). Returns how many alerts it carried. */
    @Transactional
    public int record(JsonNode payload) {
        int n = 0;
        for (JsonNode alert : payload.path("alerts")) {
            JsonNode labels = alert.path("labels");
            JsonNode annotations = alert.path("annotations");
            String fingerprint = text(alert.path("fingerprint"), 64);
            String alertname = text(labels.path("alertname"), 100);
            if (fingerprint == null || alertname == null) {
                continue;
            }
            boolean resolved = "resolved".equals(alert.path("status").asString());
            Instant starts = Instant.parse(alert.path("startsAt").asString());
            Instant ends = parse(alert.path("endsAt").asString());
            incidents.upsert(fingerprint, alertname, orElse(text(labels.path("severity"), 20), "warning"),
                    text(labels.path("service"), 64), orElse(text(annotations.path("summary"), 500), alertname),
                    text(annotations.path("description"), 4000), text(annotations.path("runbook_url"), 500),
                    json.writeValueAsString(labels), resolved ? "RESOLVED" : "FIRING", starts,
                    resolved ? (ends == null ? Instant.now() : ends) : null);
            log.atInfo().addKeyValue("alertname", alertname).addKeyValue("status", resolved ? "resolved" : "firing")
                    .addKeyValue("service", labels.path("service").asString()).log("Alert recorded as incident");
            n++;
        }
        return n;
    }

    public PageResponse<Incident> list(String status, PageRequest page) {
        if (status != null && !List.of("FIRING", "RESOLVED").contains(status)) {
            throw ApiException.badRequest("status is FIRING or RESOLVED");
        }
        return PageResponse.of(incidents.list(status, page), page, incidents.count(status));
    }

    public Incident get(long id) {
        return incidents.find(id).orElseThrow(() -> ApiException.notFound("Incident"));
    }

    @Transactional
    public Incident acknowledge(Caller caller, long id) {
        Incident incident = get(id);
        if (incidents.acknowledge(id, caller.email()) == 1) {
            incidents.note(id, caller.email(), "Acknowledged");
        } else if (incident.acknowledgedBy() != null) {
            throw ApiException.conflict("Already acknowledged by " + incident.acknowledgedBy(), "ALREADY_ACKNOWLEDGED");
        }
        return get(id);
    }

    @Transactional
    public Incident note(Caller caller, long id, String note) {
        get(id);
        incidents.note(id, caller.email(), note);
        return get(id);
    }

    /** Something an operator did that changes the system: on the record, like an incident. */
    @Transactional
    public void operatorAction(String who, String action, String service, String what) {
        long id = incidents.insertResolved(action, service, what.length() > 500 ? what.substring(0, 500) : what, who);
        incidents.note(id, who, what);
    }

    @Scheduled(cron = "0 41 3 * * *")
    void purge() {
        int n = incidents.purgeResolvedBefore(Instant.now().minus(Duration.ofDays(90)));
        if (n > 0) {
            log.atInfo().addKeyValue("deleted", n).log("Purged incidents older than 90 days");
        }
    }

    private static String text(JsonNode node, int max) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asString();
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String orElse(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static Instant parse(String value) {
        try {
            Instant t = Instant.parse(value);
            return t.isAfter(ZERO) ? t : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
