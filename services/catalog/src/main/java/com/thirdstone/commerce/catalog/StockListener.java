package com.thirdstone.commerce.catalog;

import com.thirdstone.commerce.platform.events.InventoryEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.EventContext;
import com.thirdstone.commerce.platform.messaging.EventDispatcher;
import com.thirdstone.commerce.platform.messaging.EventRouter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Keeps each product's "available" figure in step with what inventory announces. */
@Component
class StockListener {

    private final EventDispatcher dispatcher;
    private final EventRouter router;

    StockListener(EventDispatcher dispatcher, CatalogService catalog) {
        this.dispatcher = dispatcher;
        this.router = EventRouter.forConsumer("catalog")
                .on(InventoryEvents.STOCK_CHANGED, InventoryEvents.StockChanged.class, (event, stock) -> {
                    if (!catalog.stockChanged(stock.sku(), stock.available())) {
                        EventContext.ignored("unknown sku");
                    }
                });
    }

    @KafkaListener(topics = Topics.STOCK, groupId = "catalog")
    void onStock(ConsumerRecord<String, byte[]> record) {
        dispatcher.dispatch(record, router);
    }
}
