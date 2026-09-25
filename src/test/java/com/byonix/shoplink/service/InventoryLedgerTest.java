package com.byonix.shoplink.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryLedgerTest {

    @Test
    void consecutiveTimestampsAlwaysAdvanceEvenWhenTheClockDoesNot() {
        Instant previous = InventoryLedger.nextInstant();
        for (int i = 0; i < 50_000; i++) {
            Instant next = InventoryLedger.nextInstant();
            assertTrue(next.isAfter(previous), "timestamp " + i + " did not advance: " + previous + " -> " + next);
            previous = next;
        }
    }

    @Test
    void timestampsFitTheMicrosecondColumnExactly() {
        for (int i = 0; i < 1_000; i++) {
            Instant t = InventoryLedger.nextInstant();
            assertEquals(0, t.getNano() % 1_000, "sub-microsecond digits would be lost by the database: " + t);
        }
    }

    @Test
    void concurrentWritersNeverShareATimestamp() throws Exception {
        int threads = 8;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<Instant>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<List<Instant>> job = () -> {
                    List<Instant> out = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        out.add(InventoryLedger.nextInstant());
                    }
                    return out;
                };
                futures.add(pool.submit(job));
            }
            Set<Instant> all = new HashSet<>();
            for (Future<List<Instant>> f : futures) {
                all.addAll(f.get());
            }
            assertEquals(threads * perThread, all.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
