package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.events.InventoryEvents;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"commerce.order.saga-timeout=3s", "commerce.order.timeout-check-millis=500"})
@AutoConfigureMockMvc
@DirtiesContext
class SagaTimeoutIntegrationTest extends OrderTestSupport {

    @Test
    void anOrderNobodyAnswersIsCancelledAndCompensated() throws Exception {
        String sku = product(400);
        String id = placed(20, order(sku, 1, 400, "tok_visa"));
        OrderEvents.Cancelled cancelled = bus.await(OrderEvents.CANCELLED, id, OrderEvents.Cancelled.class);
        assertThat(cancelled.reason()).isEqualTo("TIMEOUT");
        // Stock arriving after the deadline does not revive it.
        bus.publish(InventoryEvents.RESERVED, id, new InventoryEvents.Reserved(id));
        Thread.sleep(1500);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(bus.types(Topics.ORDER, id)).doesNotContain(OrderEvents.PAYMENT_REQUESTED);
    }

    @Test
    void anOrderWaitingForPaymentTimesOutToo() throws Exception {
        String sku = product(400);
        String id = placed(21, order(sku, 1, 400, "tok_visa"));
        bus.publish(InventoryEvents.RESERVED, id, new InventoryEvents.Reserved(id));
        OrderEvents.PaymentRequested request = bus.await(OrderEvents.PAYMENT_REQUESTED, id,
                OrderEvents.PaymentRequested.class);
        OrderEvents.Cancelled cancelled = bus.await(OrderEvents.CANCELLED, id, OrderEvents.Cancelled.class);
        assertThat(cancelled.reason()).isEqualTo("TIMEOUT");
        // The deadline billing was given is the moment the order gave up.
        assertThat(request.expiresAt()).isBefore(java.time.Instant.now());
    }
}
