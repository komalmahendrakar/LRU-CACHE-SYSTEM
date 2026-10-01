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
 *       into an in-flight map via {@code putIfAbsent}.</li>
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
 * {@code ConcurrentHashMap.putIfAbsent} atomically selects one
 * placeholder future per key. The loader is scheduled only by the
 * caller that inserted that future, avoiding a check-then-act race.
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

        // 2. Slow path — publish a placeholder before scheduling work.
        // This keeps completion/cleanup out of a computeIfAbsent mapping
        // function, where an immediately failing task could recursively
        // update the same ConcurrentHashMap entry.
        CompletableFuture<V> candidate = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, candidate);
        CompletableFuture<V> future = existing;

        if (existing == null) {
            future = candidate;
            try {
                executor.execute(() -> {
                    V value;
                    try {
                        value = loader.apply(key);
                        // Store result in the real cache (default TTL = 0)
                        cache.put(key, value, 0);
                    } catch (Throwable error) {
                        inFlight.remove(key, candidate);
                        candidate.completeExceptionally(error);
                        return;
                    }

                    // Remove only this computation; a later retry must not
                    // be removed if it has already replaced this mapping.
                    inFlight.remove(key, candidate);
                    candidate.complete(value);
                });
            } catch (RuntimeException error) {
                inFlight.remove(key, candidate);
                candidate.completeExceptionally(error);
            }
        }

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
