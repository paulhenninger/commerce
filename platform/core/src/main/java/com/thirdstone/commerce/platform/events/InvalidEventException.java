package com.thirdstone.commerce.platform.events;

/**
 * The record is not a usable event: not JSON, not an envelope, or a payload that
 * breaks its contract. Retrying cannot fix it, so listeners never retry these —
 * they go straight to the dead-letter topic and the partition moves on.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
