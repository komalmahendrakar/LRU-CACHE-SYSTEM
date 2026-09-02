const LRUCache = require('./src/cache/lru');

console.log('--- LRU Cache Benchmark ---\n');
const cache = new LRUCache(10000); // 10,000 items capacity

// 1. Benchmark SET performance
console.time('SET 1,000,000 items (triggers 990,000 evictions)');
for (let i = 0; i < 1000000; i++) {
  cache.set(`key-${i}`, `value-${i}`);
}
console.timeEnd('SET 1,000,000 items (triggers 990,000 evictions)');

// 2. Benchmark GET performance (Mix of mostly misses due to eviction)
console.time('GET 1,000,000 items');
let hits = 0;
for (let i = 0; i < 1000000; i++) {
  const val = cache.get(`key-${i}`);
  if (val) hits++;
}
console.timeEnd('GET 1,000,000 items');

console.log(`\nMetrics:`, cache.getMetrics());
console.log(`Total hits matched manual validation: ${hits === cache.getMetrics().hits}`);
