package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.events.Line;

import java.util.List;

/** The total the customer saw is not what the order would cost now. Nothing was ordered. */
class PriceChangedException extends RuntimeException {

    final List<Line> lines;
    final long totalCents;

    PriceChangedException(List<Line> lines, long totalCents) {
        super("Prices have changed since the cart was filled");
        this.lines = lines;
        this.totalCents = totalCents;
    }
}
