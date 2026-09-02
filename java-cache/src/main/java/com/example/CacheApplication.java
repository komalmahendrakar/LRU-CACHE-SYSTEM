package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point — starts the embedded Tomcat server on port 3000
 * (configurable via application.properties or --server.port=XXXX).
 *
 * Architectural comparison with the original Node.js project:
 *
 *   Node.js (index.js):
 *     - Master process forks N OS workers (one per CPU core).
 *     - Each worker runs its OWN server + its OWN isolated LRU cache.
 *     - No shared state — workers can't see each other's caches.
 *
 *   Java (this project):
 *     - Single JVM process, one shared ShardedCache.
 *     - Tomcat's thread pool handles concurrent HTTP requests.
 *     - All threads read/write the same cache (with per-shard locking).
 *     - Result: true shared-memory concurrency instead of process isolation.
 *
 * This fundamental difference is the core interview talking point.
 */
@SpringBootApplication
public class CacheApplication {

    public static void main(String[] args) {
        SpringApplication.run(CacheApplication.class, args);
    }
}
