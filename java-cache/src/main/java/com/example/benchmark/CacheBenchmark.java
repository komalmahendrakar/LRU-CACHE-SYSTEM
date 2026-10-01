package com.example.benchmark;

import com.example.cache.Cache;
import com.example.cache.CacheStats;
import com.example.concurrency.CoarseCache;
import com.example.concurrency.ShardedCache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Micro-benchmark comparing {@link CoarseCache} (single lock) vs
 * {@link ShardedCache} (16 segments) under concurrent load.
 *
 * <h3>Methodology</h3>
 * <ul>
 *   <li>Each implementation gets a separate warm-up using the measured
 *       thread count and operation mix.</li>
 *   <li>Each measured trial uses a fresh cache, and trial order alternates
 *       to reduce consistent first-run/order bias.</li>
 *   <li>Each thread performs a deterministic 50/50 put/get mix over the
 *       same pseudo-random key sequence for both implementations.</li>
 *   <li>This is an illustrative micro-benchmark, not a substitute for
 *       JMH or a controlled performance study.</li>
 * </ul>
 *
 * <h3>How to run</h3>
 * <pre>
 *   cd java-cache
 *   mvn compile exec:java -Dexec.mainClass="com.example.benchmark.CacheBenchmark"
 * </pre>
 * Or after packaging:
 * <pre>
 *   java -cp target/classes com.example.benchmark.CacheBenchmark
 * </pre>
 *
 */
public class CacheBenchmark {

    // ── Configuration ───────────────────────────────────────────────
    private static final int CACHE_CAPACITY    = 10_000;
    private static final int THREAD_COUNT      = 64;
    private static final int OPS_PER_THREAD    = 100_000;
    private static final int WARM_UP_OPS       = 50_000;
    private static final int MEASURED_RUNS     = 3;
    private static final int KEY_SPACE         = 20_000; // keys drawn from 0..KEY_SPACE-1

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║      LRU Cache Concurrency Benchmark            ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.printf( "║  Threads:        %-31d║%n", THREAD_COUNT);
        System.out.printf( "║  Ops/thread:     %-31d║%n", OPS_PER_THREAD);
        System.out.printf( "║  Total ops:      %-31d║%n", (long) THREAD_COUNT * OPS_PER_THREAD);
        System.out.printf( "║  Cache capacity: %-31d║%n", CACHE_CAPACITY);
        System.out.printf( "║  Key space:      %-31d║%n", KEY_SPACE);
        System.out.printf( "║  Measured runs:  %-31d║%n", MEASURED_RUNS);
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("Warm-up phase (not included in measurements)");
        runWorkload(new CoarseCache<>(CACHE_CAPACITY), THREAD_COUNT, WARM_UP_OPS);
        runWorkload(new ShardedCache<>(CACHE_CAPACITY), THREAD_COUNT, WARM_UP_OPS);

        List<Long> coarseRuns = new ArrayList<>();
        List<Long> shardedRuns = new ArrayList<>();
        System.out.println("\nMeasurement phase");
        for (int run = 1; run <= MEASURED_RUNS; run++) {
            if (run % 2 == 1) {
                measure("CoarseCache", new CoarseCache<>(CACHE_CAPACITY), run, coarseRuns);
                measure("ShardedCache", new ShardedCache<>(CACHE_CAPACITY), run, shardedRuns);
            } else {
                measure("ShardedCache", new ShardedCache<>(CACHE_CAPACITY), run, shardedRuns);
                measure("CoarseCache", new CoarseCache<>(CACHE_CAPACITY), run, coarseRuns);
            }
        }

        System.out.println("\nAverage across measured runs");
        printAverage("CoarseCache", coarseRuns);
        printAverage("ShardedCache", shardedRuns);
    }

    /**
     * Run a mixed put/get workload across {@code threadCount} threads,
     * each performing {@code opsPerThread} operations.
     *
     * @return elapsed wall-clock time in nanoseconds
     */
    private static long runWorkload(Cache<Integer, String> cache,
                                    int threadCount,
                                    int opsPerThread) throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int seed = t;
            futures.add(pool.submit(() -> {
                try {
                    startGun.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting to start", e);
                }

                // Simple pseudo-random (xorshift) to avoid ThreadLocalRandom overhead
                int rng = seed + 1;
                for (int i = 0; i < opsPerThread; i++) {
                    rng ^= (rng << 13);
                    rng ^= (rng >>> 17);
                    rng ^= (rng << 5);
                    int key = Math.abs(rng % KEY_SPACE);

                    if (i % 2 == 0) {
                        cache.put(key, "value-" + key, 0);
                    } else {
                        cache.get(key);
                    }
                }
            }));
        }

        try {
            long start = System.nanoTime();
            startGun.countDown();

            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
            return System.nanoTime() - start;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void measure(String name, Cache<Integer, String> cache,
                                int run, List<Long> durations) throws Exception {
        long nanos = runWorkload(cache, THREAD_COUNT, OPS_PER_THREAD);
        durations.add(nanos);
        System.out.printf("%s run %d/%d%n", name, run, MEASURED_RUNS);
        printResult(nanos, cache.stats());
    }

    private static void printResult(long nanos, CacheStats stats) {
        long totalOps = (long) THREAD_COUNT * OPS_PER_THREAD;
        double seconds = nanos / 1_000_000_000.0;
        double opsPerSec = totalOps / seconds;

        System.out.printf("  Operations:  %,d%n", totalOps);
        System.out.printf("  Elapsed:     %.3f s%n", seconds);
        System.out.printf("  Throughput:  %,.0f ops/sec%n", opsPerSec);
        System.out.printf("  Hits: %,d  |  Misses: %,d  |  Evictions: %,d  |  Size: %,d%n",
                stats.hits(), stats.misses(), stats.evictions(), stats.size());
    }

    private static void printAverage(String name, List<Long> durations) {
        long totalOps = (long) THREAD_COUNT * OPS_PER_THREAD;
        double averageNanos = durations.stream().mapToLong(Long::longValue).average().orElseThrow();
        double averageThroughput = durations.stream()
                .mapToDouble(nanos -> totalOps / (nanos / 1_000_000_000.0))
                .average()
                .orElseThrow();

        System.out.printf("  %s: average elapsed %.3f s, average throughput %,.0f ops/sec (%d runs)%n",
                name, averageNanos / 1_000_000_000.0, averageThroughput, durations.size());
    }
}
