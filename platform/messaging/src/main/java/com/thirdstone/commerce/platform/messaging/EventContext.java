package com.thirdstone.commerce.platform.messaging;

/**
 * Lets a handler say "this event did not apply" — a payment for an order that was
 * already cancelled, an event about an order this service never saw. That is not
 * an error (nothing is retried or dead-lettered) but it is worth counting: a rise
 * in ignored events is how an ordering or contract problem first shows.
 */
public final class EventContext {

    private static final ThreadLocal<String> IGNORED = new ThreadLocal<>();

    /** Call from a handler whose conditional update matched no row. */
    public static void ignored(String reason) {
        IGNORED.set(reason == null ? "" : reason);
    }

    static void reset() {
        IGNORED.remove();
    }

    static String ignoredReason() {
        return IGNORED.get();
    }

    private EventContext() {
    }
}
