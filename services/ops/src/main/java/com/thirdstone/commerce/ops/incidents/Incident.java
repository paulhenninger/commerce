package com.thirdstone.commerce.ops.incidents;

import java.time.Instant;
import java.util.List;

public record Incident(long id, String alertname, String severity, String service, String summary,
                       String description, String runbookUrl, String status, Instant startsAt, Instant endsAt,
                       String acknowledgedBy, Instant acknowledgedAt, Instant updatedAt, List<Note> notes) {

    public record Note(long id, String author, String note, Instant at) {
    }
}
