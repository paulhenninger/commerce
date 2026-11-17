package com.thirdstone.commerce.inventory;

import com.thirdstone.commerce.platform.events.CatalogEvents;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.EventDispatcher;
import com.thirdstone.commerce.platform.messaging.EventRouter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class InventoryEventListener {

    private final EventDispatcher dispatcher;
    private final EventRouter router;

    InventoryEventListener(EventDispatcher dispatcher, InventoryService inventory) {
        this.dispatcher = dispatcher;
        this.router = EventRouter.forConsumer("inventory")
                .on(CatalogEvents.PRODUCT_UPSERTED, CatalogEvents.ProductUpserted.class,
                        (e, p) -> inventory.productKnown(p.sku()))
                .on(OrderEvents.PLACED, OrderEvents.Placed.class, (e, p) -> inventory.reserve(p))
                .on(OrderEvents.CONFIRMED, OrderEvents.Confirmed.class, (e, p) -> inventory.commit(p.orderId()))
                .on(OrderEvents.CANCELLED, OrderEvents.Cancelled.class, (e, p) -> inventory.release(p.orderId()))
                .on(OrderEvents.RETURNED, OrderEvents.Returned.class, (e, p) -> inventory.restock(p.orderId()));
    }

    @KafkaListener(topics = {Topics.ORDER, Topics.CATALOG}, groupId = "inventory")
    void onEvent(ConsumerRecord<String, byte[]> record) {
        dispatcher.dispatch(record, router);
    }
}
