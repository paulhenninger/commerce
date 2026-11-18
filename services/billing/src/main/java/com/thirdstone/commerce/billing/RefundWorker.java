package com.thirdstone.commerce.billing;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Makes the refunds the event handlers queued (ADR-031): leases due ones, asks
 * the provider outside any transaction (keyed by the order, so a repeat is
 * safe), then records each in its own transaction. A refund the provider cannot
 * take is tried again — 5 s, doubling to 5 min — for as long as it takes.
 */
@Component
class RefundWorker {

    private static final Logger log = LoggerFactory.getLogger(RefundWorker.class);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    static final int BATCH = 50;

    private final PaymentRepository payments;
    private final BillingService billing;
    private final PaymentGatewayClient gateway;
    private final TransactionTemplate tx;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldest = new AtomicLong();

    RefundWorker(PaymentRepository payments, BillingService billing, PaymentGatewayClient gateway,
                 PlatformTransactionManager transactions, MeterRegistry registry) {
        this.payments = payments;
        this.billing = billing;
        this.gateway = gateway;
        this.tx = new TransactionTemplate(transactions);
        Gauge.builder("commerce.refunds.pending", pending, AtomicLong::get)
                .description("Refunds queued and not yet taken by the provider").register(registry);
        Gauge.builder("commerce.refunds.oldest.pending.age", oldest, AtomicLong::get).baseUnit("seconds")
                .description("How long the oldest queued refund has waited").register(registry);
    }

    @Scheduled(fixedDelayString = "${commerce.billing.refund-interval-millis:1000}")
    void work() {
        List<Payment> due;
        do {
            due = tx.execute(s -> payments.claimDueRefunds(BATCH, LEASE));
            if (due == null) {
                return;
            }
            for (Payment p : due) {
                refund(p);
            }
        } while (due.size() == BATCH);
        long[] waiting = payments.pendingRefunds();
        pending.set(waiting[0]);
        oldest.set(waiting[1]);
    }

    private void refund(Payment p) {
        String ref;
        try {
            // Outside any transaction: the provider may be slow or away.
            ref = gateway.refund(p.orderId(), p.providerRef(), p.amountCents());
        } catch (RuntimeException e) {
            int attempts = payments.refundAttempts(p.id());
            Duration retry = backoff(attempts);
            payments.refundFailed(p.id(), e.getClass().getSimpleName() + ": " + e.getMessage(), retry);
            log.atWarn().addKeyValue("orderId", p.orderId()).addKeyValue("attempts", attempts + 1)
                    .addKeyValue("retryInSeconds", retry.toSeconds()).log("Refund not taken yet: {}", e.getMessage());
            return;
        }
        tx.executeWithoutResult(s -> billing.refunded(p, ref));
    }

    static Duration backoff(int attempts) {
        long seconds = 5L << Math.min(attempts, 6);
        return seconds > MAX_BACKOFF.toSeconds() ? MAX_BACKOFF : Duration.ofSeconds(seconds);
    }
}
