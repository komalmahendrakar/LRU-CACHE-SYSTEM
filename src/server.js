const http = require('http');
const LRUCache = require('./cache/lru');

// Initialize cache with a capacity (e.g., 1000 items)
const cache = new LRUCache(1000);

// simulated slow backend computation/database
const simulateBackendDb = async (key) => {
  return new Promise((resolve) => {
    setTimeout(() => {
      resolve(`Computed backend value for ${key}`);
    }, 100);
  });
};

const startServer = (port = 3000) => {
  const server = http.createServer(async (req, res) => {
    const urlParams = new URL(req.url, `http://${req.headers.host}`);
    const pathname = urlParams.pathname;

    // Handle GET /get?key=...
    if (req.method === 'GET' && pathname === '/get') {
      const key = urlParams.searchParams.get('key');
      if (!key) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Key is required' }));
      }
      
      const startTime = Date.now();
      let value = cache.get(key);
      let cacheStatus = "HIT";

      if (value === null) {
        cacheStatus = "MISS";
        
        // Anti-Stampede: Create computation promise
        const computationPromise = simulateBackendDb(key).then((resolvedValue) => {
          cache.set(key, resolvedValue); // replace the promise with the actual value once done
          return resolvedValue;
        });
        
        // Store the pending promise temporarily in the cache
        cache.set(key, computationPromise);
        
        // Await our computation
        value = await computationPromise;
      } else if (value && typeof value.then === 'function') {
        cacheStatus = "HIT (Pending Promise)";
        value = await value; 
      }
      
      const timeTaken = Date.now() - startTime;

      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ 
        data: value, 
        timeTaken: `${timeTaken} ms`, 
        cache: cacheStatus 
      }));
    }

    // Handle GET /set?key=...&value=...
    if (req.method === 'GET' && pathname === '/set') {
      const key = urlParams.searchParams.get('key');
      const value = urlParams.searchParams.get('value');
      const ttl = urlParams.searchParams.get('ttl'); // Optional explicit TTL
      
      if (!key || !value) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Key and value are required' }));
      }
      
      cache.set(key, value, ttl ? parseInt(ttl, 10) : null);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ success: true, key, value }));
    }

    // Handle GET /delete?key=...
    if (req.method === 'GET' && pathname === '/delete') {
      const key = urlParams.searchParams.get('key');
      if (!key) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Key is required' }));
      }
      
      const deleted = cache.delete(key);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ success: deleted, key }));
    }

    // Handle GET /stats
    if (req.method === 'GET' && pathname === '/stats') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify(cache.getMetrics()));
    }

    // Handle 404
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Not Found' }));
  });

  server.listen(port, () => {
    console.log(`Cache server (Worker ${process.pid}) listening on port ${port}`);
  });
};

// Exporting to allow index.js to manage the clustering
module.exports = startServer;
