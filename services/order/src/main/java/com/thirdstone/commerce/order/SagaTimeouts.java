package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.events.OrderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * No order waits forever on a service that is down: an order still waiting for
 * stock or payment after the saga timeout is cancelled, and the cancellation
 * makes every service undo what it had done.
 */
@Component
class SagaTimeouts {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeouts.class);
    private static final int BATCH = 100;

    private final OrderRepository orders;
    private final OrderSaga saga;
    private final OrderProperties props;
    private final TransactionTemplate tx;

    SagaTimeouts(OrderRepository orders, OrderSaga saga, OrderProperties props, PlatformTransactionManager transactions) {
        this.orders = orders;
        this.saga = saga;
        this.props = props;
        this.tx = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${commerce.order.timeout-check-millis:5000}")
    void cancelExpired() {
        int cancelled;
        do {
            Integer count = tx.execute(s -> {
                List<String> expired = orders.lockExpired(Instant.now().minus(props.sagaTimeout()), BATCH);
                for (String id : expired) {
                    saga.cancel(id, OrderEvents.REASON_TIMEOUT, OrderStatus.IN_FLIGHT, "Timed out waiting for "
                            + "stock or payment");
                }
                return expired.size();
            });
            cancelled = count == null ? 0 : count;
            if (cancelled > 0) {
                log.atWarn().addKeyValue("count", cancelled).log("Cancelled orders that timed out");
            }
        } while (cancelled == BATCH);
    }
}
