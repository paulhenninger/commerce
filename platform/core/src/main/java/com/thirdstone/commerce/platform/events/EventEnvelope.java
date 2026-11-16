package com.thirdstone.commerce.platform.events;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * What every event on every topic looks like, whatever it is about.
 *
 * @param eventId     unique per event; consumers remember it, so a redelivered or
 *                    duplicated event is applied once
 * @param type        what happened, e.g. {@code order.placed}; consumers ignore types
 *                    they don't know, so a producer can add new ones freely
 * @param version     the payload's contract version; additive changes keep it
 * @param occurredAt  when it happened, UTC
 * @param source      the service that wrote it
 * @param aggregateId what it happened to (the order id, the SKU) — also the record key
 * @param payload     the type-specific body
 */
public record EventEnvelope(String eventId, String type, int version, Instant occurredAt,
                            String source, String aggregateId, JsonNode payload) {
}
