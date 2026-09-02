class Node {
  constructor(key, value, ttl) {
    this.key = key;
    this.value = value;
    // Calculate expiration time if ttl (in milliseconds) is provided
    this.expiresAt = ttl ? Date.now() + ttl : null;
    this.prev = null;
    this.next = null;
  }
}

class LRUCache {
  constructor(capacity) {
    this.capacity = capacity;
    this.cache = new Map();
    this.cacheHits = 0;
    this.cacheMisses = 0;
    
    this.head = new Node(null, null);
    this.tail = new Node(null, null);
    this.head.next = this.tail;
    this.tail.prev = this.head;
  }

  _remove(node) {
    node.prev.next = node.next;
    node.next.prev = node.prev;
  }

  _add(node) {
    node.prev = this.head;
    node.next = this.head.next;
    this.head.next.prev = node;
    this.head.next = node;
  }

  get(key) {
    if (!this.cache.has(key)) {
      this.cacheMisses++;
      return null;
    }

    const node = this.cache.get(key);
    
    // Check TTL (Passive Expiration)
    if (node.expiresAt && Date.now() > node.expiresAt) {
      this._remove(node);
      this.cache.delete(key);
      this.cacheMisses++;
      return null;
    }

    this.cacheHits++;
    this._remove(node);
    this._add(node);
    
    return node.value;
  }

  set(key, value, ttl = null) {
    if (this.cache.has(key)) {
      const node = this.cache.get(key);
      node.value = value;
      node.expiresAt = ttl ? Date.now() + ttl : null;
      this._remove(node);
      this._add(node);
    } else {
      if (this.cache.size >= this.capacity) {
        const lru = this.tail.prev;
        this._remove(lru);
        this.cache.delete(lru.key);
      }
      const newNode = new Node(key, value, ttl);
      this._add(newNode);
      this.cache.set(key, newNode);
    }
  }

  delete(key) {
    if (this.cache.has(key)) {
      const node = this.cache.get(key);
      this._remove(node);
      this.cache.delete(key);
      return true;
    }
    return false;
  }

  getMetrics() {
    return {
      hits: this.cacheHits,
      misses: this.cacheMisses,
      size: this.cache.size
    };
  }
}

module.exports = LRUCache;
