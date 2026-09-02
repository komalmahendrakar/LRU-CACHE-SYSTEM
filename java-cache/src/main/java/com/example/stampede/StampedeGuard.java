package com.example.stampede;

import com.example.cache.Cache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * Prevents cache stampedes (a.k.a. thundering-herd) by coalescing
 * concurrent requests for the same missing key into a single
 * backend computation.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>On a cache miss, we atomically place a {@link CompletableFuture}
 *       into an in-flight map via {@code computeIfAbsent}.</li>
 *   <li>Only the thread that "wins" the {@code computeIfAbsent} race
 *       triggers the backend call; every other thread receives the
 *       same future and simply waits on it.</li>
 *   <li>When the backend call completes, the result is stored in the
 *       real cache, the future is completed, and the in-flight entry
 *       is removed.</li>
 * </ol>
 *
 * <h3>Why CompletableFuture instead of the JS Promise approach</h3>
 * The original Node.js code stores the Promise directly in the LRU
 * cache.  That works in JS because the cache value type is dynamic
 * ({@code any}).  In Java, storing a {@code CompletableFuture<V>}
 * inside a {@code Cache<K, V>} would break the type contract.
 * Instead, we use a separate {@link ConcurrentHashMap} for in-flight
 * computations — cleaner typing, same deduplication semantics.
 *
 * <h3>Thread safety</h3>
 * {@code ConcurrentHashMap.computeIfAbsent} is atomic — the lambda
 * runs exactly once per key, even under concurrent access.  A naive
 * "check-then-act" pattern (if !map.containsKey → map.put) would be
 * a race condition and is explicitly avoided.
 */
public class StampedeGuard<K, V> {

    private final Cache<K, V> cache;
    private final ExecutorService executor;

    /**
     * In-flight computations keyed by cache key.
     * This map is separate from the cache itself to keep
     * the Cache<K,V> type clean (no Futures stored as values).
     */
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight =
            new ConcurrentHashMap<>();

    public StampedeGuard(Cache<K, V> cache, ExecutorService executor) {
        this.cache    = cache;
        this.executor = executor;
    }

    /**
     * Get a value from the cache, or compute it exactly once if
     * missing — even if many threads request the same key
     * simultaneously.
     *
     * @param key     the lookup key
     * @param loader  function that computes the value on a cache miss
     *                (e.g. a simulated slow backend call)
     * @return the cached or freshly-computed value
     */
    public V getOrCompute(K key, Function<K, V> loader) {
        // 1. Fast path — cache hit
        V cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        // 2. Slow path — miss.  Use computeIfAbsent to guarantee
        //    only ONE future is created per key.
        CompletableFuture<V> future = inFlight.computeIfAbsent(key, k ->
            CompletableFuture.supplyAsync(() -> {
                V value = loader.apply(k);
                // Store result in the real cache (with default TTL = 0)
                cache.put(k, value, 0);
                return value;
            }, executor)
            .whenComplete((result, error) -> {
                // Always clean up the in-flight entry so subsequent
                // misses trigger a fresh computation.
                inFlight.remove(key);
            })
        );

        // 3. Block until the value is ready.
        //    join() is appropriate here because we want the calling
        //    thread to wait synchronously (the HTTP handler will
        //    return the result to the client).
        return future.join();
    }

    /** Expose the underlying cache for direct operations. */
    public Cache<K, V> getCache() {
        return cache;
    }
}
