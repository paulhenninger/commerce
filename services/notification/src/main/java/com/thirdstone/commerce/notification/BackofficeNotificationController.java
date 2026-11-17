package com.thirdstone.commerce.notification;

import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Which emails went to whom, for support and the ledger audit. Administrators only; bodies are never shown. */
@RestController
@RequestMapping("/api/v1/backoffice/notifications")
class BackofficeNotificationController {

    private final NotificationRepository notifications;

    BackofficeNotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    List<NotificationRepository.Sent> search(Caller caller, @RequestParam(required = false) String recipient,
                                             @RequestParam(required = false) Instant from,
                                             @RequestParam(required = false) Instant to) {
        caller.requireAdmin();
        if (recipient != null) {
            if (recipient.length() > 254) {
                throw ApiException.badRequest("Not an email address");
            }
            return notifications.search(recipient.strip().toLowerCase(), null, null, 500);
        }
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(1)) : from;
        if (end.isBefore(start) || Duration.between(start, end).compareTo(Duration.ofDays(1)) > 0) {
            throw ApiException.badRequest("Ask for at most one day at a time");
        }
        return notifications.search(null, start, end, 1000);
    }
}
