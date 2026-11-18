package com.thirdstone.commerce.controller.experiments;

import java.time.Duration;
import java.util.Set;

/**
 * One experiment from the catalogue (architecture, section 11).
 *
 * @param needsHa          only meaningful with two replicas and three brokers
 * @param observe          how long the failure lasts
 * @param allowedFailures  checkouts the shoppers may see fail (after their one retry)
 * @param allowedReasons   why an order in the window may end up cancelled
 * @param allowInvalidDeadLetters the experiment itself writes bad records
 */
public record Experiment(String name, String title, String hypothesis, boolean needsHa, Duration observe,
                         Action inject, Action restore, Verify verify, int allowedFailures, Set<String> allowedReasons,
                         boolean allowInvalidDeadLetters) {

    static final Set<String> NORMAL_REASONS = Set.of("CUSTOMER", "PAYMENT_DECLINED", "OUT_OF_STOCK");

    @FunctionalInterface
    public interface Action {
        void run(Context ctx) throws Exception;
    }

    /** Experiment-specific checks, after the failure is removed and the system has settled. */
    @FunctionalInterface
    public interface Verify {
        void run(Context ctx, Run run) throws Exception;
    }
}
