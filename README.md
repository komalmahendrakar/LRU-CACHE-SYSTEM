# Java LRU Cache — Thread-Safe, Sharded, with Stampede Prevention

A production-style **in-memory LRU cache** ported from a Node.js clustered implementation, redesigned to showcase **Java OOP fundamentals** and **real multithreading**.

---

## Architectural Shift: Node.js → Java

| Aspect | Node.js (Original) | Java (This Project) |
|--------|-------------------|-------------------|
| **Concurrency model** | `cluster.fork()` — N OS processes, one per CPU core | Single JVM, shared memory, thread pool |
| **Cache isolation** | Each worker has its **own** isolated LRU cache | **One shared cache** accessed by all threads |
| **Shared state** | None — workers can't see each other's caches | Full shared state with fine-grained locking |
| **Coordination** | OS load-balancer distributes requests across workers | Shard-level `ReentrantLock` coordinates access |
| **Stampede prevention** | JS `Promise` stored directly in cache | `CompletableFuture` + `ConcurrentHashMap.computeIfAbsent` |
| **Scaling** | Add more processes (more RAM per worker) | Add more threads (near-zero memory overhead) |

**Why this matters for interviews:** Java's shared-memory threading model lets us do something fundamentally better than Node's process-per-core approach — all threads see the same cache, so there's no data duplication and no cache-consistency problem across workers.

---

## Concurrency Evolution: Coarse Lock → Sharded Lock

### Stage 1: `CoarseCache` — Single Global Lock

```
Thread A ──→ [LOCK] ──→ cache.get("x") ──→ [UNLOCK]
Thread B ────────wait────────→ [LOCK] ──→ cache.put("y",...) ──→ [UNLOCK]
Thread C ──────────────wait──────────────→ [LOCK] ──→ ...
```

**One `ReentrantLock`** protects the entire cache. Correct, but serializes all operations — under high contention, threads queue up.

### Stage 2: `ShardedCache` — 16 Independent Segments

```
Thread A ──→ shard[hash("x") % 16] ──→ [LOCK shard 3] ──→ get("x") ──→ [UNLOCK]
Thread B ──→ shard[hash("y") % 16] ──→ [LOCK shard 7] ──→ put("y") ──→ [UNLOCK]  ← runs in parallel!
Thread C ──→ shard[hash("z") % 16] ──→ [LOCK shard 3] ──→ wait... (same shard as A)
```

The keyspace is **partitioned across 16 `LRUCache` instances**, each with its own `ReentrantLock`. Threads on different shards **never contend**. This is the same segment-based approach used by early versions of `java.util.concurrent.ConcurrentHashMap`.

**Why per-shard and not per-node locking?** Moving a node in a doubly-linked list mutates three nodes (the node itself + its old neighbors). Per-node locking would require acquiring multiple fine-grained locks in a consistent order to avoid deadlocks — notoriously error-prone and slower in practice due to lock overhead.

---

## Cache Stampede Prevention

When multiple threads request the same missing key simultaneously:

```
Thread 1: cache.get("user:42") → MISS → computeIfAbsent → starts backend call
Thread 2: cache.get("user:42") → MISS → computeIfAbsent → finds existing Future → waits
Thread 3: cache.get("user:42") → MISS → computeIfAbsent → finds existing Future → waits
...
Backend call completes → all threads receive the same result → stored in cache
```

Uses `ConcurrentHashMap.computeIfAbsent()` (atomic, no check-then-act race) to ensure **exactly one backend computation per key**, regardless of how many threads request it concurrently.

---

## Project Structure

```
java-cache/
├── pom.xml                                          # Maven build (Spring Boot 3.3.2, JUnit 5)
├── src/main/java/com/example/
│   ├── CacheApplication.java                        # Spring Boot entry point
│   ├── cache/
│   │   ├── Cache.java                               # Interface: get, put, delete, stats
│   │   ├── CacheStats.java                          # Immutable metrics record
│   │   ├── EvictionPolicy.java                      # Extensibility seam (LRU → LFU later)
│   │   └── LRUCache.java                            # HashMap + doubly-linked list, TTL
│   ├── concurrency/
│   │   ├── CoarseCache.java                         # Stage 1: single ReentrantLock
│   │   └── ShardedCache.java                        # Stage 2: 16-segment sharding
│   ├── stampede/
│   │   └── StampedeGuard.java                       # CompletableFuture dedup
│   ├── server/
│   │   └── CacheController.java                     # Spring Boot REST endpoints
│   └── benchmark/
│       └── CacheBenchmark.java                      # nanoTime benchmark with warm-up
└── src/test/java/com/example/
    ├── cache/
    │   └── LRUCacheTest.java                        # Unit tests (mirrors Jest suite)
    └── concurrency/
        └── ConcurrencyTest.java                     # Multi-threaded correctness tests
```

---

## Quick Start

### Prerequisites
- **JDK 17+** (tested with 17 and 21)
- **Maven 3.9+**

### Build & Test
```bash
cd java-cache
mvn clean test
```

### Run the Server
```bash
mvn spring-boot:run
```
Server starts on **http://localhost:3000**.

### API Endpoints

| Endpoint | Parameters | Example |
|----------|-----------|---------|
| `GET /set` | `key`, `value`, `ttl` (optional, ms) | `/set?key=name&value=Alice&ttl=5000` |
| `GET /get` | `key` | `/get?key=name` |
| `GET /delete` | `key` | `/delete?key=name` |
| `GET /stats` | (none) | `/stats` |

### Run the Benchmark
```bash
mvn compile exec:java -Dexec.mainClass="com.example.benchmark.CacheBenchmark"
```

---

## Benchmark Results

Results from running on the local machine (results will vary by hardware):

```
Run the benchmark with:
  mvn compile exec:java -Dexec.mainClass="com.example.benchmark.CacheBenchmark"

Config: 64 threads · 100,000 ops/thread · 6,400,000 total ops
        Cache capacity: 10,000  |  Key space: 20,000

Stage 1 – CoarseCache (single ReentrantLock)
  Throughput: 2,585,793 ops/sec
  Time: 2.475 s
  Hits: 1,597,738 | Misses: 1,602,262 | Evictions: 1,592,861 | Size: 10,000

Stage 2 – ShardedCache (16 segments)
  Throughput: 4,568,832 ops/sec
  Time: 1.401 s
  Hits: 1,595,620 | Misses: 1,604,380 | Evictions: 1,592,392 | Size: 10,000

Speedup (sharded / coarse): 1.77×
```

---

## Key Design Decisions (Interview Talking Points)

1. **`Cache<K,V>` interface** — Decouples the API from implementation. You can swap CoarseCache for ShardedCache (or a future LFU cache) without changing any client code.

2. **`EvictionPolicy` seam** — Open/Closed Principle. Adding LFU is a single-class addition, not a refactor.

3. **`ReentrantLock` over `synchronized`** — Explicit lock/unlock makes critical sections visible in code review; `tryLock()` enables non-blocking patterns if needed.

4. **Passive TTL expiration** — No background sweep thread. Expired entries are cleaned up lazily on access. Tradeoff: saves CPU cycles at the cost of briefly retaining expired data.

5. **`Clock` injection** — Tests use a `FakeClock` for deterministic TTL assertions instead of `Thread.sleep()` (which makes tests slow and flaky).

6. **Spring Boot** — Chosen over `com.sun.net.httpserver.HttpServer` because it demonstrates production-readiness (embedded Tomcat, auto-config, actuator support) while still being minimal code.

7. **Fixed thread pool with virtual-thread bonus** — `Executors.newFixedThreadPool(cores)` is the primary executor. On JDK 21+, this can be swapped for `Executors.newVirtualThreadPerTaskExecutor()` for near-zero-cost per-task threads — noted in the code as a one-line change.
