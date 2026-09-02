package com.example.cache;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * LRU cache backed by a {@link HashMap} + a doubly-linked list.
 *
 * <h3>Data-structure invariants</h3>
 * <ul>
 *   <li>{@code map.get(key).key == key} for every entry.</li>
 *   <li>The linked list is ordered most-recent → least-recent
 *       (head.next is MRU, tail.prev is LRU).</li>
 *   <li>head and tail are sentinel nodes that are never removed.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * This class is <b>not</b> thread-safe on its own.  External
 * synchronization is provided by the wrapping classes:
 * <ul>
 *   <li>{@link com.example.concurrency.CoarseCache} — single global lock</li>
 *   <li>{@link com.example.concurrency.ShardedCache} — per-shard lock</li>
 * </ul>
 *
 * <h3>TTL (passive / lazy expiration)</h3>
 * Expired entries are evicted only when accessed via {@link #get}.
 * This mirrors the original Node.js design: no background sweep thread,
 * so we trade a small amount of wasted memory for zero timer-thread CPU
 * overhead.  In an interview, this is a great tradeoff to discuss.
 *
 * <h3>Clock injection</h3>
 * The cache accepts a {@link Clock} so tests can use a fixed/offset
 * clock instead of {@code Thread.sleep()}.
 */
public class LRUCache<K, V> implements Cache<K, V> {

    // ── Internal doubly-linked-list node ────────────────────────────
    //    Package-private so tests in the same package can inspect it
    //    for structural assertions, but NOT public API.
    static class Node<K, V> {
        final K key;          // immutable — we never re-key a node
        V      value;
        long   expiresAt;     // epoch millis; 0 = no expiry
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value, long expiresAt) {
            this.key       = key;
            this.value     = value;
            this.expiresAt = expiresAt;
        }

        /** Sentinel constructor (head / tail). */
        Node() {
            this(null, null, 0);
        }
    }

    // ── Fields ──────────────────────────────────────────────────────
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;    // sentinel — MRU end
    private final Node<K, V> tail;    // sentinel — LRU end
    private final Clock clock;

    // Counters — not volatile because callers synchronize externally
    private long hits;
    private long misses;
    private long evictions;

    // ── Constructors ────────────────────────────────────────────────

    public LRUCache(int capacity) {
        this(capacity, Clock.systemUTC());
    }

    /**
     * @param capacity max entries before eviction kicks in
     * @param clock    injectable clock (use {@code Clock.fixed(...)} in tests)
     */
    public LRUCache(int capacity, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.capacity = capacity;
        this.clock    = clock;
        this.map      = new HashMap<>(capacity);

        // Sentinel nodes simplify add/remove — no null checks needed
        this.head = new Node<>();
        this.tail = new Node<>();
        head.next = tail;
        tail.prev = head;
    }

    // ── Cache interface ─────────────────────────────────────────────

    @Override
    public V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) {
            misses++;
            return null;
        }

        // Lazy TTL check — same passive-expiration logic as the JS version
        if (node.expiresAt > 0 && clock.millis() > node.expiresAt) {
            removeNode(node);
            map.remove(key);
            misses++;
            return null;
        }

        // Promote to MRU position
        hits++;
        removeNode(node);
        addToFront(node);
        return node.value;
    }

    @Override
    public void put(K key, V value, long ttlMillis) {
        long expiresAt = (ttlMillis > 0) ? clock.millis() + ttlMillis : 0;

        Node<K, V> existing = map.get(key);
        if (existing != null) {
            // Update in place and promote — avoids a map remove + put
            existing.value     = value;
            existing.expiresAt = expiresAt;
            removeNode(existing);
            addToFront(existing);
            return;
        }

        // Evict LRU entry if at capacity
        if (map.size() >= capacity) {
            Node<K, V> lru = tail.prev;       // least-recently-used
            removeNode(lru);
            map.remove(lru.key);
            evictions++;
        }

        Node<K, V> newNode = new Node<>(key, value, expiresAt);
        addToFront(newNode);
        map.put(key, newNode);
    }

    @Override
    public boolean delete(K key) {
        Node<K, V> node = map.remove(key);
        if (node != null) {
            removeNode(node);
            return true;
        }
        return false;
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hits, misses, evictions, map.size());
    }

    // ── Linked-list helpers (private) ───────────────────────────────

    /** Unlink a node from wherever it sits in the list. O(1). */
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /** Insert node right after the head sentinel (MRU position). O(1). */
    private void addToFront(Node<K, V> node) {
        node.prev      = head;
        node.next      = head.next;
        head.next.prev = node;
        head.next      = node;
    }
}
