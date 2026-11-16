package com.thirdstone.commerce.platform.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Forgets events too old to come back. Topics keep events for 7 days, so an event
 * id older than that can never be redelivered and remembering it only grows the
 * table. Deleted in small batches so the purge never holds a long lock; safe to
 * run on every replica at once.
 */
public class ProcessedEventsPurge {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventsPurge.class);
    private static final int BATCH = 5000;

    private final JdbcClient jdbc;

    public ProcessedEventsPurge(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${commerce.messaging.purge-cron:0 17 3 * * *}")
    public void purge() {
        long total = 0;
        int deleted;
        do {
            deleted = purgeBatch(8);
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.atInfo().addKeyValue("deleted", total).log("Purged old processed-event ids");
        }
    }

    int purgeBatch(int olderThanDays) {
        return jdbc.sql("DELETE FROM processed_events WHERE processed_at < UTC_TIMESTAMP(6) - INTERVAL ? DAY LIMIT ?")
                .params(olderThanDays, BATCH).update();
    }
}
