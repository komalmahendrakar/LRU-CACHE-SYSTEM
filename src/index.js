const cluster = require('cluster');
const os = require('os');
const startServer = require('./server');

const PORT = process.env.PORT || 3000;

if (cluster.isPrimary || cluster.isMaster) {
  // Master process handles the clustering
  const numCPUs = os.cpus().length;
  console.log(`Master process ${process.pid} is running`);
  console.log(`Forking ${numCPUs} workers...`);

  // Fork a worker for each CPU core
  for (let i = 0; i < numCPUs; i++) {
    cluster.fork();
  }

  // Handle worker crashes and instantly restart them
  cluster.on('exit', (worker, code, signal) => {
    console.log(`Worker ${worker.process.pid} died (code: ${code}). Restarting...`);
    cluster.fork();
  });

} else {
  // Worker processes execute the server logic
  startServer(PORT);
}
