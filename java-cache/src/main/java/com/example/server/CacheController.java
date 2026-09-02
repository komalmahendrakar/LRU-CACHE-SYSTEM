package com.example.server;

import com.example.cache.Cache;
import com.example.cache.CacheStats;
import com.example.concurrency.ShardedCache;
import com.example.stampede.StampedeGuard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spring Boot REST controller exposing the same endpoints as the
 * original Node.js HTTP server.
 *
 * <h3>Why Spring Boot instead of com.sun.net.httpserver.HttpServer?</h3>
 * <ul>
 *   <li>Spring Boot demonstrates production-readiness: embedded Tomcat,
 *       content negotiation, error handling, and actuator/metrics are a
 *       single dependency away.</li>
 *   <li>Interviewers at enterprise Java shops expect Spring familiarity.</li>
 *   <li>The controller is still < 100 lines — no over-engineering.</li>
 * </ul>
 *
 * <h3>Thread pool</h3>
 * We create a fixed-size {@link ExecutorService} for backend simulation
 * tasks.  The pool size matches available cores, mirroring the original
 * Node.js cluster design where each CPU core had its own worker.
 *
 * <b>Bonus — Virtual Threads (Java 21+):</b>
 * If running on JDK 21+, you can swap the fixed pool for
 * {@code Executors.newVirtualThreadPerTaskExecutor()} to get
 * lightweight, OS-thread-independent concurrency.  Virtual threads
 * are ideal for I/O-bound tasks like our simulated backend call,
 * and they map nicely onto what Node's worker processes were doing —
 * just far lighter-weight (a virtual thread ≈ a few KB vs. an OS
 * process ≈ a few MB).
 */
@RestController
public class CacheController {

    // ── Shared state ────────────────────────────────────────────────

    /** Primary cache — Stage 2 (sharded), capacity = 1000 entries. */
    private final Cache<String, String> cache = new ShardedCache<>(1000);

    /**
     * Fixed thread pool for backend simulation.
     * Size = number of available CPU cores, matching the original
     * Node.js cluster fork count.
     *
     * BONUS (Java 21+): Replace with:
     *   Executors.newVirtualThreadPerTaskExecutor()
     * for near-zero-cost per-task threads.
     */
    private final ExecutorService executor =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    /** Stampede guard wraps the cache with dedup logic. */
    private final StampedeGuard<String, String> stampedeGuard =
            new StampedeGuard<>(cache, executor);

    // ── Endpoints ───────────────────────────────────────────────────
    //    Contract is identical to the original Node.js server:
    //    all operations use GET with query parameters.

    /**
     * GET /set?key=...&value=...&ttl=...
     * Store a value in the cache with an optional TTL (milliseconds).
     */
    @GetMapping("/set")
    public Map<String, Object> set(
            @RequestParam String key,
            @RequestParam String value,
            @RequestParam(required = false, defaultValue = "0") long ttl) {

        cache.put(key, value, ttl);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("key", key);
        response.put("value", value);
        return response;
    }

    /**
     * GET /get?key=...
     * Retrieve a value.  On a cache miss, the stampede guard
     * triggers exactly one simulated backend call (~100 ms) and
     * shares the result with all concurrent requesters.
     */
    @GetMapping("/get")
    public Map<String, Object> get(@RequestParam String key) {
        long start = System.currentTimeMillis();

        // First try the cache directly
        String cached = cache.get(key);
        String cacheStatus;
        String result;

        if (cached != null) {
            cacheStatus = "HIT";
            result = cached;
        } else {
            cacheStatus = "MISS";
            // Stampede-safe backend call — only one computation per key
            result = stampedeGuard.getOrCompute(key, k -> {
                try {
                    // Simulate slow backend / DB lookup (~100 ms)
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "Computed backend value for " + k;
            });
        }

        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", result);
        response.put("timeTaken", elapsed + " ms");
        response.put("cache", cacheStatus);
        return response;
    }

    /**
     * GET /delete?key=...
     * Remove a key from the cache.
     */
    @GetMapping("/delete")
    public Map<String, Object> delete(@RequestParam String key) {
        boolean deleted = cache.delete(key);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", deleted);
        response.put("key", key);
        return response;
    }

    /**
     * GET /stats
     * Return cache metrics: hits, misses, evictions, current size.
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        CacheStats s = cache.stats();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("hits", s.hits());
        response.put("misses", s.misses());
        response.put("evictions", s.evictions());
        response.put("size", s.size());
        return response;
    }
}
