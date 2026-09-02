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
    @DisplayName("ShardedCache: concurrent put/get — no lost updates")
    void shardedCacheNoLostUpdates() throws Exception {
        ShardedCache<Integer, Integer> cache = new ShardedCache<>(1_000_000);
        int threadCount = 64;
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
                    // Immediately read back — value should be what we wrote
                    // (another thread might overwrite if keys collide, but
                    //  our keys are unique per thread so this should hold)
                    Integer value = cache.get(key);
                    assertNotNull(value, "Value should not be null immediately after put");
                    assertEquals(key, value, "Value should match what was written");
                }
            }));
        }

        startGun.countDown(); // fire!

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS); // propagate assertion errors
        }

        pool.shutdown();

        // Verify metrics consistency
        CacheStats stats = cache.stats();
        assertTrue(stats.hits() > 0, "Should have recorded some hits");
        assertTrue(stats.size() > 0, "Cache should contain entries");
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
}
