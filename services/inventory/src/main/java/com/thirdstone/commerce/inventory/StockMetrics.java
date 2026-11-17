package com.thirdstone.commerce.inventory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Available units per product, for the business dashboard. Capped to the 200
 * lowest: those are the ones worth watching, and a label per SKU must not grow
 * without bound.
 */
@Component
class StockMetrics {

    private final InventoryRepository stock;
    private final MultiGauge available;

    StockMetrics(InventoryRepository stock, MeterRegistry registry) {
        this.stock = stock;
        this.available = MultiGauge.builder("commerce.stock.available").description("Units that can be ordered")
                .register(registry);
    }

    @Scheduled(fixedDelay = 15000)
    void refresh() {
        available.register(stock.lowest(200).stream()
                .map(s -> MultiGauge.Row.of(Tags.of("sku", s.sku()), s.available())).toList(), true);
    }
}
