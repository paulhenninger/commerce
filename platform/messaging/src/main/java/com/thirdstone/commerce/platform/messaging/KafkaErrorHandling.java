package com.thirdstone.commerce.platform.messaging;

import com.thirdstone.commerce.platform.events.InvalidEventException;
import com.thirdstone.commerce.platform.events.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * What happens to a record a listener could not process: retry it in place a few
 * times (most failures are a deadlock or a database blip), then move it to
 * {@code <topic>.dlt} and carry on with the partition.
 *
 * <p>Without this one unprocessable record would be redelivered forever and block
 * everything behind it on its partition. A record that is simply not a valid event
 * ({@link InvalidEventException}) will not get better by waiting, so it skips the
 * retries. The dead letter keeps the original key and value and gains headers
 * naming the exception, the topic, partition and offset it came from and the
 * consumer group, so it can be inspected and replayed from the ops console.
 */
public final class KafkaErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandling.class);

    static final int RETRIES = 3;

    public static DefaultErrorHandler errorHandler(KafkaOperations<String, byte[]> kafka, MeterRegistry registry,
                                                   String consumer) {
        var publisher = new DeadLetterPublishingRecoverer(kafka,
                // Partition -1: the producer picks one by key; dead-letter topics
                // have a single partition whatever the source topic has.
                (record, e) -> new TopicPartition(Topics.dlt(record.topic()), -1));
        // 200 ms, 800 ms, 3.2 s.
        var backOff = new ExponentialBackOffWithMaxRetries(RETRIES);
        backOff.setInitialInterval(200);
        backOff.setMultiplier(4);
        backOff.setMaxInterval(5000);
        var handler = new DefaultErrorHandler((record, e) -> {
            String reason = invalid(e) ? "invalid" : "failed";
            Counter.builder("commerce.events.dead.lettered").tag("consumer", consumer)
                    .tag("topic", record.topic()).tag("reason", reason).register(registry).increment();
            log.atError().addKeyValue("topic", record.topic()).addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset()).addKeyValue("reason", reason)
                    .log("Record moved to the dead-letter topic: {}", rootMessage(e));
            // If this throws (the broker is away) the record is not skipped: the
            // container seeks back and tries again, so nothing is lost.
            publisher.accept(record, e);
        }, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        // The service's own infrastructure is away (no database connection, no
        // transaction): the event is fine and will apply once it is back, so it
        // is retried — 1 s, doubling to 30 s — for as long as that takes, never
        // dead-lettered. Without this a 20-second database outage would park
        // every event in flight on the dead-letter topic.
        handler.setBackOffFunction((record, e) -> transientInfrastructure(e) ? untilBack() : null);
        return handler;
    }

    static org.springframework.util.backoff.BackOff untilBack() {
        var backOff = new org.springframework.util.backoff.ExponentialBackOff(1000, 2);
        backOff.setMaxInterval(30_000);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        return backOff;
    }

    /** True when the failure is the service's own infrastructure, not the event or the handler's logic. */
    static boolean transientInfrastructure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InvalidEventException) {
                return false;
            }
            if (t instanceof org.springframework.dao.TransientDataAccessException
                    || t instanceof org.springframework.dao.DataAccessResourceFailureException
                    || t instanceof org.springframework.transaction.CannotCreateTransactionException
                    || t instanceof java.sql.SQLTransientException
                    || t instanceof java.sql.SQLRecoverableException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static boolean invalid(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InvalidEventException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private KafkaErrorHandling() {
    }
}
