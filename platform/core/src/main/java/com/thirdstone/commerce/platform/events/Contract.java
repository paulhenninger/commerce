package com.thirdstone.commerce.platform.events;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The checks every payload record runs in its constructor, and the list of which
 * payload belongs to which event type.
 *
 * <p>A payload that Jackson can bind is not yet a payload a handler can trust:
 * a missing field binds as {@code null} or {@code 0}. Each record states what it
 * requires here, so a broken event fails at the door (and is dead-lettered without
 * retries) instead of as a {@code NullPointerException} halfway through a handler.
 * The bounds are generous — they exist to stop absurd input, not to validate
 * business rules, which belong to the service that owns the data.
 */
public final class Contract {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** Every event type in the system and the record that carries its payload. */
    public static final Map<String, Class<?>> PAYLOADS = Map.ofEntries(
            Map.entry(CatalogEvents.PRODUCT_UPSERTED, CatalogEvents.ProductUpserted.class),
            Map.entry(InventoryEvents.STOCK_CHANGED, InventoryEvents.StockChanged.class),
            Map.entry(InventoryEvents.RESERVED, InventoryEvents.Reserved.class),
            Map.entry(InventoryEvents.REJECTED, InventoryEvents.Rejected.class),
            Map.entry(OrderEvents.PLACED, OrderEvents.Placed.class),
            Map.entry(OrderEvents.PAYMENT_REQUESTED, OrderEvents.PaymentRequested.class),
            Map.entry(OrderEvents.CONFIRMED, OrderEvents.Confirmed.class),
            Map.entry(OrderEvents.CANCELLED, OrderEvents.Cancelled.class),
            Map.entry(OrderEvents.RETURN_REQUESTED, OrderEvents.ReturnRequested.class),
            Map.entry(OrderEvents.RETURNED, OrderEvents.Returned.class),
            Map.entry(OrderEvents.REFUNDED, OrderEvents.Refunded.class),
            Map.entry(BillingEvents.AUTHORIZED, BillingEvents.Authorized.class),
            Map.entry(BillingEvents.FAILED, BillingEvents.Failed.class),
            Map.entry(BillingEvents.REFUNDED, BillingEvents.Refunded.class),
            Map.entry(ShippingEvents.CREATED, ShippingEvents.Created.class),
            Map.entry(ShippingEvents.DISPATCHED, ShippingEvents.Dispatched.class),
            Map.entry(ShippingEvents.DELIVERED, ShippingEvents.Delivered.class),
            Map.entry(ShippingEvents.RETURN_LABEL_CREATED, ShippingEvents.ReturnLabelCreated.class),
            Map.entry(ShippingEvents.RETURN_RECEIVED, ShippingEvents.ReturnReceived.class));

    /** The topic each event type is published on. */
    public static String topicOf(String type) {
        if (type.equals(InventoryEvents.STOCK_CHANGED)) {
            return Topics.STOCK;
        }
        int dot = type.indexOf('.');
        return switch (dot < 0 ? type : type.substring(0, dot)) {
            case "order" -> Topics.ORDER;
            case "inventory" -> Topics.INVENTORY;
            case "payment" -> Topics.BILLING;
            case "shipment" -> Topics.SHIPPING;
            case "product" -> Topics.CATALOG;
            default -> throw new IllegalArgumentException("unknown event type " + type);
        };
    }

    static String id(String value, String field) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " missing or malformed");
        }
        return value;
    }

    static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " missing or longer than " + max);
        }
        return value;
    }

    static String currency(String value) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw new IllegalArgumentException("currency missing or not an ISO 4217 code");
        }
        return value;
    }

    static long positive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " missing or not positive");
        }
        return value;
    }

    static long amount(long cents, String field) {
        // The upper bound is far above any real order and far below overflow when
        // amounts are summed.
        if (cents < 0 || cents > 1_000_000_000_000L) {
            throw new IllegalArgumentException(field + " out of range");
        }
        return cents;
    }

    static <T> T present(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " missing");
        }
        return value;
    }

    static List<Line> lines(List<Line> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > 100) {
            throw new IllegalArgumentException("lines missing, empty or more than 100");
        }
        for (Line line : lines) {
            present(line, "line");
        }
        return List.copyOf(lines);
    }

    private Contract() {
    }
}
