package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.events.BillingEvents;
import com.thirdstone.commerce.platform.events.CatalogEvents;
import com.thirdstone.commerce.platform.events.InventoryEvents;
import com.thirdstone.commerce.platform.events.ShippingEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.EventDispatcher;
import com.thirdstone.commerce.platform.messaging.EventRouter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Everything the order service hears from the other services. */
@Component
class OrderEventListener {

    private final EventDispatcher dispatcher;
    private final EventRouter router;

    OrderEventListener(EventDispatcher dispatcher, OrderSaga saga, OrderRepository orders) {
        this.dispatcher = dispatcher;
        this.router = EventRouter.forConsumer("order")
                .on(CatalogEvents.PRODUCT_UPSERTED, CatalogEvents.ProductUpserted.class, (e, p) -> orders.upsertProduct(p))
                .on(InventoryEvents.RESERVED, InventoryEvents.Reserved.class, (e, p) -> saga.stockReserved(p))
                .on(InventoryEvents.REJECTED, InventoryEvents.Rejected.class, (e, p) -> saga.stockRejected(p))
                .on(BillingEvents.AUTHORIZED, BillingEvents.Authorized.class, (e, p) -> saga.paymentAuthorized(p))
                .on(BillingEvents.FAILED, BillingEvents.Failed.class, (e, p) -> saga.paymentFailed(p))
                .on(BillingEvents.REFUNDED, BillingEvents.Refunded.class, (e, p) -> saga.paymentRefunded(p))
                .on(ShippingEvents.CREATED, ShippingEvents.Created.class, (e, p) -> saga.shipmentCreated(p))
                .on(ShippingEvents.DISPATCHED, ShippingEvents.Dispatched.class, (e, p) -> saga.shipmentDispatched(p))
                .on(ShippingEvents.DELIVERED, ShippingEvents.Delivered.class, (e, p) -> saga.shipmentDelivered(p))
                .on(ShippingEvents.RETURN_LABEL_CREATED, ShippingEvents.ReturnLabelCreated.class,
                        (e, p) -> saga.returnLabelCreated(p))
                .on(ShippingEvents.RETURN_RECEIVED, ShippingEvents.ReturnReceived.class, (e, p) -> saga.returnReceived(p));
    }

    @KafkaListener(topics = {Topics.INVENTORY, Topics.BILLING, Topics.SHIPPING, Topics.CATALOG}, groupId = "order")
    void onEvent(ConsumerRecord<String, byte[]> record) {
        dispatcher.dispatch(record, router);
    }
}
