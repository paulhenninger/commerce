package com.thirdstone.commerce.testkit.journal;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Everything the simulated shoppers did and saw, in order: the testkit's side of
 * the ledger, which the resilience proofs compare with what the services
 * recorded. Kept in memory, bounded (the last 200 000 entries).
 */
@Component
public class Journal {

    public record Entry(long seq, Instant at, String shopper, String action, String orderId, String outcome,
                        String detail) {
    }

    private static final int MAX = 200_000;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long seq;

    public synchronized void record(String shopper, String action, String orderId, String outcome, String detail) {
        entries.addLast(new Entry(++seq, Instant.now(), shopper, action, orderId, outcome, detail));
        while (entries.size() > MAX) {
            entries.removeFirst();
        }
    }

    public synchronized List<Entry> between(Instant from, Instant to, int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.at().isBefore(from) && e.at().isBefore(to)) {
                out.add(e);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    public synchronized List<Entry> latest(int n) {
        List<Entry> all = new ArrayList<>(entries);
        List<Entry> out = new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
        java.util.Collections.reverse(out);
        return out;
    }
}
