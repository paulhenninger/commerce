package com.thirdstone.commerce.notification;

import com.thirdstone.commerce.platform.events.EventEnvelope;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.ShippingEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.EventContext;
import com.thirdstone.commerce.platform.messaging.EventDispatcher;
import com.thirdstone.commerce.platform.messaging.EventRouter;
import com.thirdstone.commerce.platform.messaging.TraceContinuation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns the events customers care about into emails waiting to be sent. Nothing
 * is sent from here: NotificationWorker does that, so a slow or absent mail
 * server never holds up an event, let alone an order.
 */
@Component
class NotificationEventListener {

    private final EventDispatcher dispatcher;
    private final EventRouter router;
    private final NotificationRepository notifications;
    private final TraceContinuation traces;

    NotificationEventListener(EventDispatcher dispatcher, Templates templates, NotificationRepository notifications,
                              TraceContinuation traces) {
        this.dispatcher = dispatcher;
        this.notifications = notifications;
        this.traces = traces;
        this.router = EventRouter.forConsumer("notification")
                .on(OrderEvents.CONFIRMED, OrderEvents.Confirmed.class, (e, p) -> queue(e, templates.confirmed(p)))
                .on(OrderEvents.CANCELLED, OrderEvents.Cancelled.class, (e, p) -> queue(e, templates.cancelled(p)))
                .on(OrderEvents.REFUNDED, OrderEvents.Refunded.class, (e, p) -> queue(e, templates.refunded(p)))
                .on(ShippingEvents.DISPATCHED, ShippingEvents.Dispatched.class, (e, p) -> queue(e, templates.dispatched(p)))
                .on(ShippingEvents.DELIVERED, ShippingEvents.Delivered.class, (e, p) -> queue(e, templates.delivered(p)))
                .on(ShippingEvents.RETURN_LABEL_CREATED, ShippingEvents.ReturnLabelCreated.class,
                        (e, p) -> queue(e, templates.returnLabel(p)));
    }

    private void queue(EventEnvelope event, Email email) {
        if (notifications.insert(event.eventId(), event.aggregateId(), email, traces.current()) == 0) {
            EventContext.ignored("already queued");
        }
    }

    @KafkaListener(topics = {Topics.ORDER, Topics.SHIPPING}, groupId = "notification")
    void onEvent(ConsumerRecord<String, byte[]> record) {
        dispatcher.dispatch(record, router);
    }
}
