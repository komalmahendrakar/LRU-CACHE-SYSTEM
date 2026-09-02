package com.example.concurrency;

import com.example.cache.Cache;
import com.example.cache.CacheStats;
import com.example.cache.LRUCache;

import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <b>Stage 2 — Sharded (segmented) locking.</b>
 *
 * The keyspace is partitioned across {@code SHARD_COUNT} independent
 * {@link LRUCache} instances, each guarded by its own
 * {@link ReentrantLock}.  A key is mapped to a shard via
 * {@code spread(key.hashCode()) & (SHARD_COUNT - 1)}.
 *
 * <h3>Why sharding works</h3>
 * Threads operating on keys that hash to <em>different</em> shards
 * never contend with each other.  Under a uniform key distribution,
 * contention drops by a factor of {@code SHARD_COUNT} compared to
 * {@link CoarseCache}.
 *
 * <h3>Why per-shard locking and NOT per-node locking</h3>
 * In a doubly-linked list, moving a node on {@code get()} mutates
 * three nodes (the node itself, its old neighbors' pointers, and
 * the head sentinel).  Per-node locking would require acquiring
 * multiple fine-grained locks in a consistent order to avoid
 * deadlock — this is notoriously error-prone and slower in practice
 * due to lock overhead.  Shard-level locking is the sweet spot:
 * coarse enough to be simple and deadlock-free, fine enough to
 * allow real parallelism.
 *
 * <h3>Design mirrors ConcurrentHashMap</h3>
 * Early versions of {@code java.util.concurrent.ConcurrentHashMap}
 * used exactly this segment-based approach (before JDK 8 moved to
 * a more complex lock-striping + CAS scheme).  This is a great
 * interview comparison.
 *
 * <h3>Capacity distribution</h3>
 * Total capacity is divided evenly across shards:
 * {@code perShardCapacity = totalCapacity / SHARD_COUNT}.
 * This means each shard evicts independently — a hot shard might
 * evict more aggressively than a cold one.  An alternative would
 * be a global LRU across shards, but that reintroduces a global
 * lock, defeating the purpose.
 */
public class ShardedCache<K, V> implements Cache<K, V> {

    /**
     * Number of shards.  Must be a power of 2 so we can use
     * bitwise AND instead of modulo for shard selection.
     */
    private static final int SHARD_COUNT = 16;

    private final LRUCache<K, V>[]  shards;
    private final ReentrantLock[]    locks;

    @SuppressWarnings("unchecked")
    public ShardedCache(int totalCapacity) {
        this(totalCapacity, Clock.systemUTC());
    }

    @SuppressWarnings("unchecked")
    public ShardedCache(int totalCapacity, Clock clock) {
        int perShard = Math.max(1, totalCapacity / SHARD_COUNT);
        shards = new LRUCache[SHARD_COUNT];
        locks  = new ReentrantLock[SHARD_COUNT];
        for (int i = 0; i < SHARD_COUNT; i++) {
            shards[i] = new LRUCache<>(perShard, clock);
            locks[i]  = new ReentrantLock();
        }
    }

    // ── Shard selection ─────────────────────────────────────────────

    /**
     * Spread bits of the hashCode so that keys whose hashes differ
     * only in upper bits still land in different shards.
     * Same technique used by ConcurrentHashMap internally.
     */
    private int shardIndex(K key) {
        int h = key.hashCode();
        h ^= (h >>> 16);           // spread upper bits into lower bits
        return h & (SHARD_COUNT - 1);
    }

    // ── Cache interface ─────────────────────────────────────────────

    @Override
    public V get(K key) {
        int idx = shardIndex(key);
        locks[idx].lock();
        try {
            return shards[idx].get(key);
        } finally {
            locks[idx].unlock();
        }
    }

    @Override
    public void put(K key, V value, long ttlMillis) {
        int idx = shardIndex(key);
        locks[idx].lock();
        try {
            shards[idx].put(key, value, ttlMillis);
        } finally {
            locks[idx].unlock();
        }
    }

    @Override
    public boolean delete(K key) {
        int idx = shardIndex(key);
        locks[idx].lock();
        try {
            return shards[idx].delete(key);
        } finally {
            locks[idx].unlock();
        }
    }

    /**
     * Aggregate size across all shards.
     * Note: this is NOT a strongly consistent snapshot — another
     * thread could be modifying shard N while we read shard N+1.
     * For metrics/monitoring purposes this is acceptable.
     */
    @Override
    public int size() {
        int total = 0;
        for (int i = 0; i < SHARD_COUNT; i++) {
            locks[i].lock();
            try {
                total += shards[i].size();
            } finally {
                locks[i].unlock();
            }
        }
        return total;
    }

    /**
     * Aggregate stats across all shards.
     * Same weak-consistency caveat as {@link #size()}.
     */
    @Override
    public CacheStats stats() {
        long hits = 0, misses = 0, evictions = 0;
        int  size = 0;
        for (int i = 0; i < SHARD_COUNT; i++) {
            locks[i].lock();
            try {
                CacheStats s = shards[i].stats();
                hits      += s.hits();
                misses    += s.misses();
                evictions += s.evictions();
                size      += s.size();
            } finally {
                locks[i].unlock();
            }
        }
        return new CacheStats(hits, misses, evictions, size);
    }
}
