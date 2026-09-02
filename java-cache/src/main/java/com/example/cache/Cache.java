package com.example.cache;

/**
 * Top-level cache contract.
 *
 * Design notes (interview talking points):
 *  - Generic {@code <K, V>} so the implementation is reusable for
 *    any key/value types, not just String/String.
 *  - {@code put} accepts a TTL in milliseconds; pass 0 for "no expiry."
 *  - The interface says nothing about eviction strategy, locking, or
 *    sharding — those are implementation concerns, which is exactly
 *    what OOP encapsulation is about.
 *  - A concrete implementation can be swapped via dependency injection
 *    (Spring wires the active bean automatically).
 */
public interface Cache<K, V> {

    /**
     * Retrieve the value mapped to {@code key}, or {@code null} if
     * absent or expired.  A successful lookup counts as a cache hit
     * and (for LRU) promotes the entry to most-recently-used.
     */
    V get(K key);

    /**
     * Insert or update an entry.
     *
     * @param key       lookup key
     * @param value     payload
     * @param ttlMillis time-to-live in ms; 0 means "never expire"
     */
    void put(K key, V value, long ttlMillis);

    /**
     * Remove the entry for {@code key}.
     *
     * @return {@code true} if the key was present and removed
     */
    boolean delete(K key);

    /** Current number of (non-expired) entries. */
    int size();

    /** Snapshot of hit/miss/eviction counters. */
    CacheStats stats();
}
