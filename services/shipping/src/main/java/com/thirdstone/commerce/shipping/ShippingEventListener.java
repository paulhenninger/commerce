package com.thirdstone.commerce.shipping;

import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.EventDispatcher;
import com.thirdstone.commerce.platform.messaging.EventRouter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class ShippingEventListener {

    private final EventDispatcher dispatcher;
    private final EventRouter router;

    ShippingEventListener(EventDispatcher dispatcher, ShippingService shipping) {
        this.dispatcher = dispatcher;
        this.router = EventRouter.forConsumer("shipping")
                .on(OrderEvents.CONFIRMED, OrderEvents.Confirmed.class, (e, p) -> shipping.orderConfirmed(p))
                .on(OrderEvents.RETURN_REQUESTED, OrderEvents.ReturnRequested.class, (e, p) -> shipping.returnRequested(p))
                .on(OrderEvents.CANCELLED, OrderEvents.Cancelled.class, (e, p) -> shipping.orderCancelled(p));
    }

    @KafkaListener(topics = Topics.ORDER, groupId = "shipping")
    void onEvent(ConsumerRecord<String, byte[]> record) {
        dispatcher.dispatch(record, router);
    }
}
