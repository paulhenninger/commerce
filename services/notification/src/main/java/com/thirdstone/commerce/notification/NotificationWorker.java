package com.thirdstone.commerce.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Sends queued emails. A failure is retried with a doubling delay — a minute, two,
 * four … up to an hour — for about a day, then the email is marked FAILED and
 * counted, which raises an alert. The send happens outside any transaction.
 */
@Component
class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);
    private static final Duration LEASE = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 30;

    private final NotificationRepository notifications;
    private final JavaMailSender mail;
    private final MailProperties props;
    private final MeterRegistry registry;
    private final TransactionTemplate tx;

    NotificationWorker(NotificationRepository notifications, JavaMailSender mail, MailProperties props,
                       MeterRegistry registry, PlatformTransactionManager transactions) {
        this.notifications = notifications;
        this.mail = mail;
        this.props = props;
        this.registry = registry;
        this.tx = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${commerce.notification.worker-interval-millis:2000}")
    void work() {
        List<Long> due = tx.execute(s -> notifications.claimDue(20, LEASE));
        if (due == null) {
            return;
        }
        for (long id : due) {
            notifications.pending(id).ifPresent(this::send);
        }
    }

    private void send(NotificationRepository.Pending n) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(props.from());
        message.setTo(n.recipient());
        message.setSubject(n.subject());
        message.setText(n.body());
        try {
            mail.send(message);
            notifications.sent(n.id());
            count(n.template(), "sent");
            log.atInfo().addKeyValue("notificationId", n.id()).addKeyValue("template", n.template()).log("Email sent");
        } catch (MailException e) {
            boolean giveUp = n.attempts() + 1 >= MAX_ATTEMPTS;
            notifications.failed(n.id(), e.getClass().getSimpleName() + ": " + e.getMessage(), backoff(n.attempts()), giveUp);
            count(n.template(), giveUp ? "failed" : "retry");
            var event = giveUp ? log.atError() : log.atWarn();
            event.addKeyValue("notificationId", n.id()).addKeyValue("template", n.template())
                    .addKeyValue("attempts", n.attempts() + 1)
                    .log(giveUp ? "Email given up after repeated failures" : "Email not sent; will retry");
        }
    }

    static Duration backoff(int attempts) {
        long minutes = 1L << Math.min(attempts, 6);
        return Duration.ofMinutes(Math.min(minutes, 60));
    }

    private void count(String template, String outcome) {
        Counter.builder("commerce.notifications").tag("template", template).tag("outcome", outcome)
                .register(registry).increment();
    }
}
