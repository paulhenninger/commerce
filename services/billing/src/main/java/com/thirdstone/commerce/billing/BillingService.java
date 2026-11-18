package com.thirdstone.commerce.billing;

import com.thirdstone.commerce.platform.data.Ids;
import com.thirdstone.commerce.platform.events.BillingEvents;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.EventContext;
import com.thirdstone.commerce.platform.messaging.Outbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Charges and refunds. A charge is in two halves (see EventRouter): the call to
 * the provider happens first, outside any transaction and keyed by the order so
 * it is safe to repeat; then one transaction records the outcome and announces
 * it. A refund is queued in the event's transaction and made by RefundWorker,
 * which retries until the provider takes it. No database transaction is ever
 * held open across a network call.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    /** What the provider was asked and said, carried from the preparation into the transaction. */
    sealed interface ChargeDecision {
        record AlreadyHandled() implements ChargeDecision {
        }

        record Expired() implements ChargeDecision {
        }

        record Called(PaymentGatewayClient.ChargeResult result) implements ChargeDecision {
        }
    }

    private final PaymentRepository payments;
    private final PaymentGatewayClient gateway;
    private final Outbox outbox;
    private final MeterRegistry registry;

    BillingService(PaymentRepository payments, PaymentGatewayClient gateway, Outbox outbox, MeterRegistry registry) {
        this.payments = payments;
        this.gateway = gateway;
        this.outbox = outbox;
        this.registry = registry;
    }

    // ----------------------------------------------------------------- charge

    ChargeDecision prepareCharge(OrderEvents.PaymentRequested request) {
        if (payments.findByOrder(request.orderId()).isPresent()) {
            // Charged already, or cancelled before we got here: either way, not again.
            return new ChargeDecision.AlreadyHandled();
        }
        if (Instant.now().isAfter(request.expiresAt())) {
            // The order has given up waiting (it is being cancelled): charging now
            // would only mean refunding later.
            return new ChargeDecision.Expired();
        }
        return new ChargeDecision.Called(gateway.charge(request.orderId(), request.amountCents(), request.currency(),
                request.paymentMethod()));
    }

    void recordCharge(OrderEvents.PaymentRequested request, ChargeDecision decision) {
        String reason = switch (decision) {
            case ChargeDecision.AlreadyHandled ignored -> {
                EventContext.ignored("payment exists");
                yield null;
            }
            case ChargeDecision.Expired expired -> BillingEvents.REASON_EXPIRED;
            case ChargeDecision.Called(PaymentGatewayClient.ChargeResult.Approved approved) -> null;
            case ChargeDecision.Called(PaymentGatewayClient.ChargeResult.Declined declined) -> BillingEvents.REASON_DECLINED;
            case ChargeDecision.Called(PaymentGatewayClient.ChargeResult.Unavailable unavailable) ->
                    BillingEvents.REASON_UNAVAILABLE;
        };
        if (decision instanceof ChargeDecision.AlreadyHandled) {
            return;
        }
        String id = Ids.next();
        String providerRef = decision instanceof ChargeDecision.Called(PaymentGatewayClient.ChargeResult.Approved a)
                ? a.providerRef() : null;
        String status = providerRef != null ? "AUTHORIZED" : "FAILED";
        if (payments.insert(id, request.orderId(), request.userId(), request.amountCents(), request.currency(), status,
                providerRef, reason) == 0) {
            // Cannot happen in order (one partition, one consumer per order) — but
            // if it does, money may have moved with no record: shout.
            log.atError().addKeyValue("orderId", request.orderId()).addKeyValue("providerRef", providerRef)
                    .log("A payment row appeared while charging; check the provider for an unrecorded charge");
            EventContext.ignored("payment exists");
            return;
        }
        if (providerRef != null) {
            outbox.append(Topics.BILLING, BillingEvents.AUTHORIZED, request.orderId(), new BillingEvents.Authorized(
                    request.orderId(), id, request.amountCents(), request.currency(), providerRef));
        } else {
            outbox.append(Topics.BILLING, BillingEvents.FAILED, request.orderId(),
                    new BillingEvents.Failed(request.orderId(), reason));
        }
        Counter.builder("commerce.payments").tag("outcome", providerRef != null ? "authorized" : reason)
                .register(registry).increment();
        log.atInfo().addKeyValue("orderId", request.orderId()).addKeyValue("outcome", status)
                .addKeyValue("reason", reason).log("Payment recorded");
    }

    // ----------------------------------------------------------------- refund

    /**
     * The order was cancelled or returned: queue the refund of its charge, in
     * this transaction; {@link RefundWorker} makes it.
     *
     * @param cancellation true for {@code order.cancelled}: with no payment yet, a
     *                     CANCELLED marker makes the charge request that may still
     *                     be on its way a no-op
     */
    void requestRefund(String orderId, long userId, long amountCents, String currency, boolean cancellation) {
        if (payments.requestRefund(orderId) == 1) {
            log.atInfo().addKeyValue("orderId", orderId).log("Refund queued");
            return;
        }
        if (payments.findByOrder(orderId).isEmpty()) {
            if (cancellation && payments.insert(Ids.next(), orderId, userId, amountCents, currency, "CANCELLED",
                    null, null) == 1) {
                return;
            }
            EventContext.ignored("no payment");
            return;
        }
        EventContext.ignored("nothing to refund");
    }

    /** The provider took the refund: record it and announce it, once. */
    void refunded(Payment p, String refundRef) {
        if (payments.markRefunded(p.orderId(), refundRef) == 0) {
            return;
        }
        outbox.append(Topics.BILLING, BillingEvents.REFUNDED, p.orderId(),
                new BillingEvents.Refunded(p.orderId(), p.id(), p.amountCents(), p.currency()));
        Counter.builder("commerce.payments").tag("outcome", "refunded").register(registry).increment();
        log.atInfo().addKeyValue("orderId", p.orderId()).log("Payment refunded");
    }

    public java.util.List<Payment> search(String orderId, Instant from, Instant to) {
        return payments.search(orderId, from, to, 1000);
    }
}
