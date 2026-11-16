package com.thirdstone.commerce.platform.events;

import java.util.List;

/**
 * The event streams services share — the only coupling between them.
 *
 * <p>One topic per owning service, named {@code commerce.<owner>.<stream>.v<major>}.
 * Only the owner may write to its topic (the broker's ACLs enforce it). The record
 * key is the aggregate id (the order id, or the SKU on the product and stock
 * streams), so everything about one order lands on one partition and is consumed in
 * the order it was written. A breaking change to a stream is a new topic
 * ({@code .v2}) run alongside the old one, never an edit in place.
 *
 * <p>What a consumer cannot process — malformed bytes, a failed contract, a handler
 * that still fails after its retries — is moved to {@code <topic>.dlt} with the
 * reason in its headers, so one bad record never blocks a partition.
 */
public final class Topics {

    public static final String ORDER = "commerce.order.events.v1";
    public static final String INVENTORY = "commerce.inventory.events.v1";
    /** Stock levels by SKU: high-volume and only interesting to read models. */
    public static final String STOCK = "commerce.inventory.stock.v1";
    public static final String BILLING = "commerce.billing.events.v1";
    public static final String SHIPPING = "commerce.shipping.events.v1";
    public static final String CATALOG = "commerce.catalog.events.v1";

    public static final String DLT_SUFFIX = ".dlt";

    public static final List<String> ALL = List.of(ORDER, INVENTORY, STOCK, BILLING, SHIPPING, CATALOG);

    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }

    private Topics() {
    }
}
