package com.example.concurrency;

import com.example.cache.Cache;
import com.example.cache.CacheStats;
import com.example.cache.LRUCache;

import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <b>Stage 1 — Coarse-grained locking.</b>
 *
 * Wraps a single {@link LRUCache} with one {@link ReentrantLock}.
 * Every public operation acquires the same lock, so only one thread
 * can touch the cache at a time.
 *
 * <h3>Why a ReentrantLock instead of {@code synchronized}?</h3>
 * <ul>
 *   <li>{@code lock.tryLock()} enables non-blocking patterns later.</li>
 *   <li>Fair-mode option prevents starvation under heavy contention.</li>
 *   <li>Explicit lock/unlock makes the critical section visually obvious
 *       in code review — important for an interview walkthrough.</li>
 * </ul>
 *
 * <h3>Interview talking point</h3>
 * "This version is correct but scales poorly: under high concurrency,
 *  all threads serialize on a single lock.  That's why Stage 2
 *  ({@link ShardedCache}) shards the keyspace — same idea as
 *  ConcurrentHashMap's internal segmentation."
 */
public class CoarseCache<K, V> implements Cache<K, V> {

    private final LRUCache<K, V> delegate;
    private final ReentrantLock lock = new ReentrantLock();

    public CoarseCache(int capacity) {
        this.delegate = new LRUCache<>(capacity);
    }

    public CoarseCache(int capacity, Clock clock) {
        this.delegate = new LRUCache<>(capacity, clock);
    }

    @Override
    public V get(K key) {
        lock.lock();
        try {
            return delegate.get(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(K key, V value, long ttlMillis) {
        lock.lock();
        try {
            delegate.put(key, value, ttlMillis);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean delete(K key) {
        lock.lock();
        try {
            return delegate.delete(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return delegate.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CacheStats stats() {
        lock.lock();
        try {
            return delegate.stats();
        } finally {
            lock.unlock();
        }
    }
}
