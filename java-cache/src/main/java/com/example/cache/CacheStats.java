package com.example.cache;

/**
 * Immutable snapshot of cache metrics.
 *
 * Using a Java record gives us equals/hashCode/toString for free
 * and clearly signals "this is pure data, not behavior."
 *
 * Maps directly to the Node.js getMetrics() return shape:
 *   { hits, misses, size }
 * We add {@code evictions} because it's a critical metric for
 * understanding LRU behaviour under load.
 */
public record CacheStats(
        long hits,
        long misses,
        long evictions,
        int  size
) {}
