package com.example.cache;

/**
 * Extensibility seam for eviction strategies.
 *
 * Today we only implement LRU, but this interface makes it trivial to
 * add LFU, FIFO, or ARC later without modifying existing code (Open/Closed
 * Principle).
 *
 * Interview talking point:
 *   "I didn't over-engineer an LFU implementation we don't need yet,
 *    but I left a clean seam so adding one is a single-class change
 *    rather than a refactor."
 *
 * @param <K> key type
 */
public interface EvictionPolicy<K> {

    /** Called when a key is accessed (get or put).  The policy
     *  should promote/record the access so its ordering stays current. */
    void onAccess(K key);

    /** Called when a new key is inserted. */
    void onInsert(K key);

    /**
     * Ask the policy which key should be evicted next.
     *
     * @return the key to evict, or {@code null} if the policy has
     *         no opinion (e.g. the cache is empty)
     */
    K evict();

    /** Called when a key is explicitly deleted. */
    void onRemove(K key);
}
