const LRUCache = require('../src/cache/lru');

describe('LRU Cache', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  test('1. Should set and get a value correctly', () => {
    const cache = new LRUCache(2);
    cache.set('a', 1);
    expect(cache.get('a')).toBe(1);
    expect(cache.get('b')).toBeNull();
  });

  test('2. Should evict oldest item when capacity is exceeded', () => {
    const cache = new LRUCache(2);
    cache.set('a', 1);
    cache.set('b', 2);
    cache.set('c', 3); // 'a' should be evicted

    expect(cache.get('a')).toBeNull();
    expect(cache.get('b')).toBe(2);
    expect(cache.get('c')).toBe(3);
  });

  test('3. Should update value and move it to most recent on set', () => {
    const cache = new LRUCache(2);
    cache.set('a', 1);
    cache.set('b', 2);
    cache.set('a', 10); // 'a' becomes most recent
    cache.set('c', 3); // 'b' should be evicted instead of 'a'

    expect(cache.get('b')).toBeNull();
    expect(cache.get('a')).toBe(10);
    expect(cache.get('c')).toBe(3);
  });

  test('4. Should return null after TTL expires', () => {
    const cache = new LRUCache(2);
    cache.set('a', 1, 1000); // 1 sec TTL

    expect(cache.get('a')).toBe(1);

    // Fast forward time by 1001 ms
    jest.advanceTimersByTime(1001);

    expect(cache.get('a')).toBeNull();
  });

  test('5. Should track cache hits and misses correctly', () => {
    const cache = new LRUCache(2);
    
    cache.set('key', 'value');
    cache.get('key'); // Hit
    cache.get('non-existent'); // Miss
    cache.get('key'); // Hit

    const metrics = cache.getMetrics();
    expect(metrics.hits).toBe(2);
    expect(metrics.misses).toBe(1);
    expect(metrics.size).toBe(1);
  });
});
