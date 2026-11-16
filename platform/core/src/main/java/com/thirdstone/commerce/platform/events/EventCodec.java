package com.thirdstone.commerce.platform.events;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads and writes the event envelope, and is where bad data stops.
 *
 * <p>Anything can be written to a topic: a buggy producer, a replayed backup, a
 * hostile client. {@link #parse} accepts only a well-formed envelope within sane
 * bounds and {@link #payload} only a body that satisfies its contract; everything
 * else is an {@link InvalidEventException}, which listeners route to the dead-letter
 * topic without retrying. Unknown fields are ignored (a tolerant reader), so
 * producers can add fields without breaking consumers.
 */
public class EventCodec {

    /** Larger than any real event by an order of magnitude; the broker caps records too. */
    public static final int MAX_BYTES = 256 * 1024;

    private static final Pattern TYPE = Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    private final JsonMapper mapper;

    public EventCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** A new event, ready for the outbox. */
    public EventEnvelope create(String type, String aggregateId, String source, Object payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), type, 1, Instant.now(), source, aggregateId,
                mapper.valueToTree(payload));
    }

    public byte[] serialize(EventEnvelope event) {
        return mapper.writeValueAsBytes(event);
    }

    /** The envelope in these bytes, or an {@link InvalidEventException} saying what is wrong with them. */
    public EventEnvelope parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new InvalidEventException("empty record");
        }
        if (bytes.length > MAX_BYTES) {
            throw new InvalidEventException("record of " + bytes.length + " bytes exceeds " + MAX_BYTES);
        }
        EventEnvelope event;
        try {
            event = mapper.readValue(bytes, EventEnvelope.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("not a JSON event envelope: " + e.getOriginalMessage(), e);
        }
        if (event == null) {
            throw new InvalidEventException("null envelope");
        }
        require(event.eventId() != null && ID.matcher(event.eventId()).matches(), "eventId missing or malformed");
        require(event.type() != null && event.type().length() <= 64 && TYPE.matcher(event.type()).matches(),
                "type missing or malformed");
        require(event.version() >= 1, "version must be >= 1");
        require(event.occurredAt() != null, "occurredAt missing");
        require(event.aggregateId() != null && ID.matcher(event.aggregateId()).matches(),
                "aggregateId missing or malformed");
        require(event.payload() != null && event.payload().isObject(), "payload must be an object");
        return event;
    }

    /** The typed body of an event; an {@link InvalidEventException} if it breaks the contract. */
    public <T> T payload(EventEnvelope event, Class<T> type) {
        try {
            T payload = mapper.treeToValue(event.payload(), type);
            if (payload == null) {
                throw new InvalidEventException(event.type() + " has no payload");
            }
            return payload;
        } catch (JacksonException e) {
            throw new InvalidEventException(event.type() + " payload breaks its contract: " + e.getOriginalMessage(), e);
        }
    }

    private static void require(boolean ok, String problem) {
        if (!ok) {
            throw new InvalidEventException(problem);
        }
    }
}
