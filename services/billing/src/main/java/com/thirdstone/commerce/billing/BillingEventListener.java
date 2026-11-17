package com.thirdstone.commerce.billing;

import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.EventDispatcher;
import com.thirdstone.commerce.platform.messaging.EventRouter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class BillingEventListener {

    private final EventDispatcher dispatcher;
    private final EventRouter router;

    BillingEventListener(EventDispatcher dispatcher, BillingService billing) {
        this.dispatcher = dispatcher;
        this.router = EventRouter.forConsumer("billing")
                .onWithPreparation(OrderEvents.PAYMENT_REQUESTED, OrderEvents.PaymentRequested.class,
                        (e, p) -> billing.prepareCharge(p), (e, p, decision) -> billing.recordCharge(p, decision))
                .onWithPreparation(OrderEvents.CANCELLED, OrderEvents.Cancelled.class,
                        (e, p) -> billing.prepareRefund(p.orderId()),
                        (e, p, decision) -> billing.recordRefund(p.orderId(), p.userId(), p.totalCents(), p.currency(),
                                decision, true))
                .onWithPreparation(OrderEvents.RETURNED, OrderEvents.Returned.class,
                        (e, p) -> billing.prepareRefund(p.orderId()),
                        (e, p, decision) -> billing.recordRefund(p.orderId(), p.userId(), p.totalCents(), p.currency(),
                                decision, false));
    }

    @KafkaListener(topics = Topics.ORDER, groupId = "billing")
    void onEvent(ConsumerRecord<String, byte[]> record) {
        dispatcher.dispatch(record, router);
    }
}
