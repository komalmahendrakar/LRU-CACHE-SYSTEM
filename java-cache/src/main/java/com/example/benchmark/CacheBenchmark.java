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
 *   <li>Warm-up phase: run the workload once without measuring, so the
 *       JIT compiler has a chance to optimize hot paths.</li>
 *   <li>Measurement phase: run the workload and record elapsed time
 *       using {@code System.nanoTime()}.</li>
 *   <li>Workload: each thread performs a mix of put (50%) and get (50%)
 *       operations using a uniform random key distribution.</li>
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
 * <h3>Interview talking point</h3>
 * "The benchmark shows that sharding reduces lock contention and
 *  improves throughput roughly linearly with shard count — the same
 *  principle behind ConcurrentHashMap's design.  Under low contention
 *  the difference is small, but under high thread counts the sharded
 *  version clearly wins."
 */
public class CacheBenchmark {

    // ── Configuration ───────────────────────────────────────────────
    private static final int CACHE_CAPACITY    = 10_000;
    private static final int THREAD_COUNT      = 64;
    private static final int OPS_PER_THREAD    = 100_000;
    private static final int WARM_UP_OPS       = 50_000;
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
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();

        // ── Stage 1: Coarse-grained (single lock) ──────────────────
        System.out.println("── Stage 1: CoarseCache (single ReentrantLock) ──");
        CoarseCache<Integer, String> coarse = new CoarseCache<>(CACHE_CAPACITY);

        System.out.print("  Warm-up... ");
        runWorkload(coarse, 1, WARM_UP_OPS);
        System.out.println("done.");

        // Reset with a fresh cache for the real measurement
        coarse = new CoarseCache<>(CACHE_CAPACITY);
        System.out.print("  Measuring... ");
        long coarseNanos = runWorkload(coarse, THREAD_COUNT, OPS_PER_THREAD);
        CacheStats coarseStats = coarse.stats();
        printResult(coarseNanos, coarseStats);

        System.out.println();

        // ── Stage 2: Sharded (16 segments) ──────────────────────────
        System.out.println("── Stage 2: ShardedCache (16 segments) ──");
        ShardedCache<Integer, String> sharded = new ShardedCache<>(CACHE_CAPACITY);

        System.out.print("  Warm-up... ");
        runWorkload(sharded, 1, WARM_UP_OPS);
        System.out.println("done.");

        sharded = new ShardedCache<>(CACHE_CAPACITY);
        System.out.print("  Measuring... ");
        long shardedNanos = runWorkload(sharded, THREAD_COUNT, OPS_PER_THREAD);
        CacheStats shardedStats = sharded.stats();
        printResult(shardedNanos, shardedStats);

        System.out.println();

        // ── Comparison ──────────────────────────────────────────────
        double speedup = (double) coarseNanos / shardedNanos;
        System.out.println("══════════════════════════════════════════════════");
        System.out.printf( "  Speedup (sharded / coarse): %.2fx%n", speedup);
        System.out.println("══════════════════════════════════════════════════");
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
                try { startGun.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

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

        long start = System.nanoTime();
        startGun.countDown();

        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        long elapsed = System.nanoTime() - start;

        pool.shutdown();
        return elapsed;
    }

    private static void printResult(long nanos, CacheStats stats) {
        long totalOps = (long) THREAD_COUNT * OPS_PER_THREAD;
        double seconds = nanos / 1_000_000_000.0;
        double opsPerSec = totalOps / seconds;

        System.out.printf("done in %.3f s%n", seconds);
        System.out.printf("  Throughput:  %,.0f ops/sec%n", opsPerSec);
        System.out.printf("  Hits: %,d  |  Misses: %,d  |  Evictions: %,d  |  Size: %,d%n",
                stats.hits(), stats.misses(), stats.evictions(), stats.size());
    }
}
