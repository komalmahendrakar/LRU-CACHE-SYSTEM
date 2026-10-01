package com.example.concurrency;

import com.example.cache.CacheStats;
import com.example.stampede.StampedeGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency-specific tests.
 *
 * These tests hammer the cache from many threads simultaneously to
 * verify that:
 * <ul>
 *   <li>No updates are lost.</li>
 *   <li>The internal linked-list state is never corrupted.</li>
 *   <li>Stampede prevention triggers only one backend call per key.</li>
 * </ul>
 *
 * The {@code @RepeatedTest} annotation re-runs the test multiple
 * times because concurrency bugs are nondeterministic — a single
 * passing run is not sufficient evidence of correctness.
 */
class ConcurrencyTest {

    // ================================================================
    //  1. Multi-threaded put/get on ShardedCache
    // ================================================================

    @RepeatedTest(5)
    @DisplayName("ShardedCache: concurrent mixed operations stay within capacity")
    void shardedCacheConcurrentOperationsStayWithinCapacity() throws Exception {
        int capacity = 128;
        ShardedCache<Integer, Integer> cache = new ShardedCache<>(capacity);
        int threadCount = 64;
        int opsPerThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                try {
                    startGun.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting to start", e);
                }
                for (int i = 0; i < opsPerThread; i++) {
                    // A bounded, overlapping key range exercises contention
                    // and eviction instead of allowing every write to remain.
                    int key = (threadId * 31 + i) % (capacity * 4);
                    switch (i % 3) {
                        case 0 -> cache.put(key, threadId * opsPerThread + i, 0);
                        case 1 -> cache.get(key);
                        default -> cache.delete(key);
                    }
                }
            }));
        }

        startGun.countDown(); // fire!

        try {
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS); // propagate worker exceptions
            }
        } finally {
            pool.shutdownNow();
        }

        // Inspect repeatedly after workers stop so every assertion is a
        // stable snapshot and any structural corruption is surfaced.
        CacheStats stats = cache.stats();
        assertTrue(stats.size() >= 0, "Cache size cannot be negative");
        assertTrue(stats.size() <= capacity,
                "Cache must not exceed configured capacity, got: " + stats.size());
        assertEquals(stats.size(), cache.size(), "Reported size should match cache size");
    }

    // ================================================================
    //  2. CoarseCache under same load (correctness baseline)
    // ================================================================

    @RepeatedTest(3)
    @DisplayName("CoarseCache: concurrent put/get — no lost updates")
    void coarseCacheNoLostUpdates() throws Exception {
        CoarseCache<Integer, Integer> cache = new CoarseCache<>(10_000);
        int threadCount = 32;
        int opsPerThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                try { startGun.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int i = 0; i < opsPerThread; i++) {
                    int key = threadId * opsPerThread + i;
                    cache.put(key, key, 0);
                    Integer value = cache.get(key);
                    assertNotNull(value);
                    assertEquals(key, value);
                }
            }));
        }

        startGun.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    // ================================================================
    //  3. Same-shard contention (worst case)
    // ================================================================

    @RepeatedTest(5)
    @DisplayName("ShardedCache: threads hammering the SAME shard — linked list stays consistent")
    void sameShardContention() throws Exception {
        // Use keys that all hash to the same shard.
        // With SHARD_COUNT=16, keys 0, 16, 32, ... all map to shard 0.
        ShardedCache<Integer, Integer> cache = new ShardedCache<>(1000);
        int threadCount = 32;
        int opsPerThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(pool.submit(() -> {
                try { startGun.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int i = 0; i < opsPerThread; i++) {
                    // Keys that all land in the same shard (multiples of 16)
                    int key = i * 16;
                    cache.put(key, i, 0);
                    cache.get(key);        // promote
                    if (i % 3 == 0) {
                        cache.delete(key); // interleave deletes
                    }
                }
            }));
        }

        startGun.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // If the linked list were corrupted, size() would hang or
        // throw — reaching here without error is a strong signal.
        int size = cache.size();
        assertTrue(size >= 0 && size <= 1000,
                "Size should be within capacity bounds, got: " + size);
    }

    // ================================================================
    //  4. Stampede prevention — only ONE backend call per key
    // ================================================================

    @RepeatedTest(5)
    @DisplayName("StampedeGuard: concurrent requests for same key trigger only one computation")
    void stampedeOnlyOneComputation() throws Exception {
        ShardedCache<String, String> cache = new ShardedCache<>(100);
        ExecutorService computePool = Executors.newFixedThreadPool(4);
        StampedeGuard<String, String> guard = new StampedeGuard<>(cache, computePool);

        AtomicInteger backendCallCount = new AtomicInteger(0);

        int requesterCount = 50;
        ExecutorService requestPool = Executors.newFixedThreadPool(requesterCount);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();

        for (int i = 0; i < requesterCount; i++) {
            results.add(requestPool.submit(() -> {
                startGun.await();
                return guard.getOrCompute("shared-key", k -> {
                    backendCallCount.incrementAndGet();
                    try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return "result-for-" + k;
                });
            }));
        }

        startGun.countDown();

        // Collect all results
        Set<String> uniqueResults = ConcurrentHashMap.newKeySet();
        for (Future<String> f : results) {
            uniqueResults.add(f.get(10, TimeUnit.SECONDS));
        }

        requestPool.shutdown();
        computePool.shutdown();

        // Only ONE backend call should have been made
        assertEquals(1, backendCallCount.get(),
                "Stampede guard should coalesce concurrent requests into one backend call");

        // All threads should have received the same result
        assertEquals(1, uniqueResults.size(),
                "All requesters should receive the same value");
        assertTrue(uniqueResults.contains("result-for-shared-key"));
    }

    @Test
    @DisplayName("StampedeGuard: failed load is removed so a later request can retry")
    void stampedeFailedLoadCanBeRetried() {
        ShardedCache<String, String> cache = new ShardedCache<>(16);
        ExecutorService computePool = Executors.newSingleThreadExecutor();
        StampedeGuard<String, String> guard = new StampedeGuard<>(cache, computePool);
        AtomicInteger loaderCalls = new AtomicInteger();

        try {
            assertThrows(CompletionException.class, () -> guard.getOrCompute("retry-key", key -> {
                loaderCalls.incrementAndGet();
                throw new IllegalStateException("backend unavailable");
            }));

            assertNull(cache.get("retry-key"), "Failed loads must not populate the cache");
            assertEquals("recovered", guard.getOrCompute("retry-key", key -> {
                loaderCalls.incrementAndGet();
                return "recovered";
            }));
            assertEquals("recovered", guard.getOrCompute("retry-key", key -> {
                loaderCalls.incrementAndGet();
                return "unexpected second load";
            }), "The successful retry should now be cached");
            assertEquals(2, loaderCalls.get(), "One failed load and one successful retry are expected");
        } finally {
            computePool.shutdownNow();
        }
    }
}
