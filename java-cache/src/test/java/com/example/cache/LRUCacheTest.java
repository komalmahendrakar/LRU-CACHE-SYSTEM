package com.example.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LRUCache}.
 *
 * These mirror the original Jest test suite in __tests__/lru.test.js,
 * adapted to JUnit 5 conventions.
 *
 * <b>Fake clock:</b>  Instead of {@code Thread.sleep()} or Jest's
 * {@code jest.advanceTimersByTime()}, we inject a {@link Clock} that
 * we can advance instantly.  This makes TTL tests deterministic and
 * fast — no flaky timing-dependent assertions.
 */
class LRUCacheTest {

    // ── Helper: mutable clock for TTL tests ─────────────────────────

    /**
     * A clock whose {@link #millis()} can be manually advanced.
     * Equivalent to Jest's useFakeTimers / advanceTimersByTime.
     */
    static class FakeClock extends Clock {
        private long currentMillis;

        FakeClock(long initialMillis) {
            this.currentMillis = initialMillis;
        }

        void advance(long millis) {
            currentMillis += millis;
        }

        @Override public long millis()        { return currentMillis; }
        @Override public ZoneId getZone()     { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant()    { return Instant.ofEpochMilli(currentMillis); }
    }

    // ================================================================
    //  1. Basic get / set
    // ================================================================

    @Nested
    @DisplayName("Basic get/set")
    class BasicOps {

        @Test
        @DisplayName("1. Should set and get a value correctly")
        void setAndGet() {
            LRUCache<String, Integer> cache = new LRUCache<>(2);
            cache.put("a", 1, 0);

            assertEquals(1, cache.get("a"));
            assertNull(cache.get("b"), "Non-existent key should return null");
        }
    }

    // ================================================================
    //  2. Eviction
    // ================================================================

    @Nested
    @DisplayName("Eviction")
    class Eviction {

        @Test
        @DisplayName("2. Should evict oldest item when capacity is exceeded")
        void evictOldest() {
            LRUCache<String, Integer> cache = new LRUCache<>(2);
            cache.put("a", 1, 0);
            cache.put("b", 2, 0);
            cache.put("c", 3, 0); // "a" should be evicted

            assertNull(cache.get("a"), "'a' was LRU and should have been evicted");
            assertEquals(2, cache.get("b"));
            assertEquals(3, cache.get("c"));
        }

        @Test
        @DisplayName("3. Should update value and move to most-recent on set")
        void updatePromotes() {
            LRUCache<String, Integer> cache = new LRUCache<>(2);
            cache.put("a", 1, 0);
            cache.put("b", 2, 0);
            cache.put("a", 10, 0);  // update "a" → becomes MRU
            cache.put("c", 3, 0);   // "b" should be evicted (not "a")

            assertNull(cache.get("b"), "'b' should be evicted after 'a' was updated");
            assertEquals(10, cache.get("a"));
            assertEquals(3, cache.get("c"));
        }

        @Test
        @DisplayName("LRU reordering: get() promotes entry to MRU")
        void getPromotes() {
            LRUCache<String, Integer> cache = new LRUCache<>(2);
            cache.put("a", 1, 0);
            cache.put("b", 2, 0);

            // Access "a" — this promotes it to MRU
            assertEquals(1, cache.get("a"));

            // Now insert "c" — "b" (currently LRU) should be evicted
            cache.put("c", 3, 0);
            assertNull(cache.get("b"), "'b' was LRU after 'a' was accessed");
            assertEquals(1, cache.get("a"));
            assertEquals(3, cache.get("c"));
        }
    }

    // ================================================================
    //  3. TTL (passive / lazy expiration)
    // ================================================================

    @Nested
    @DisplayName("TTL support")
    class TTL {

        @Test
        @DisplayName("4. Should expire an entry at its TTL deadline")
        void ttlExpiry() {
            FakeClock clock = new FakeClock(1_000_000);
            LRUCache<String, Integer> cache = new LRUCache<>(2, clock);

            cache.put("a", 1, 1000); // TTL = 1 second
            assertEquals(1, cache.get("a"), "Within TTL — should return value");

            clock.advance(1000); // exactly the expiration deadline
            assertNull(cache.get("a"), "At the TTL deadline — should be expired");
            assertEquals(1, cache.stats().misses(), "Expired lookups count as misses");
            assertEquals(0, cache.size(), "Expired entry should be removed on access");
        }

        @Test
        @DisplayName("Entry without TTL never expires")
        void noTtlNeverExpires() {
            FakeClock clock = new FakeClock(0);
            LRUCache<String, Integer> cache = new LRUCache<>(2, clock);

            cache.put("a", 1, 0); // ttl=0 → no expiry
            clock.advance(999_999_999);
            assertEquals(1, cache.get("a"), "No TTL — should not expire");
        }

        @Test
        @DisplayName("Expired entry is lazily evicted and does not count towards capacity")
        void expiredEntryFreesSlot() {
            FakeClock clock = new FakeClock(1_000_000);
            LRUCache<String, Integer> cache = new LRUCache<>(2, clock);

            cache.put("a", 1, 500);
            cache.put("b", 2, 0);

            clock.advance(501); // "a" expired

            // Access "a" to trigger lazy eviction
            assertNull(cache.get("a"));
            assertEquals(1, cache.size(), "Expired entry should be removed");

            // Now we can add two more without evicting "b"
            cache.put("c", 3, 0);
            assertEquals(2, cache.get("b"));
            assertEquals(3, cache.get("c"));
        }
    }

    // ================================================================
    //  4. Metrics
    // ================================================================

    @Nested
    @DisplayName("Metrics")
    class Metrics {

        @Test
        @DisplayName("5. Should track cache hits and misses correctly")
        void hitMissTracking() {
            LRUCache<String, String> cache = new LRUCache<>(2);

            cache.put("key", "value", 0);
            cache.get("key");            // hit
            cache.get("non-existent");   // miss
            cache.get("key");            // hit

            CacheStats stats = cache.stats();
            assertEquals(2, stats.hits());
            assertEquals(1, stats.misses());
            assertEquals(1, stats.size());
        }

        @Test
        @DisplayName("Eviction counter increments on capacity overflow")
        void evictionCounter() {
            LRUCache<String, Integer> cache = new LRUCache<>(2);
            cache.put("a", 1, 0);
            cache.put("b", 2, 0);
            cache.put("c", 3, 0); // evicts "a"
            cache.put("d", 4, 0); // evicts "b"

            assertEquals(2, cache.stats().evictions());
        }
    }

    // ================================================================
    //  5. Edge cases
    // ================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Delete returns true for existing key, false otherwise")
        void deleteReturnValue() {
            LRUCache<String, Integer> cache = new LRUCache<>(2);
            cache.put("a", 1, 0);

            assertTrue(cache.delete("a"));
            assertFalse(cache.delete("a"), "Already deleted");
            assertFalse(cache.delete("never-existed"));
        }

        @Test
        @DisplayName("Capacity of 1 still works correctly")
        void capacityOne() {
            LRUCache<String, Integer> cache = new LRUCache<>(1);
            cache.put("a", 1, 0);
            assertEquals(1, cache.get("a"));

            cache.put("b", 2, 0); // evicts "a"
            assertNull(cache.get("a"));
            assertEquals(2, cache.get("b"));
        }

        @Test
        @DisplayName("Invalid capacity throws IllegalArgumentException")
        void invalidCapacity() {
            assertThrows(IllegalArgumentException.class, () -> new LRUCache<>(0));
            assertThrows(IllegalArgumentException.class, () -> new LRUCache<>(-1));
        }
    }
}
