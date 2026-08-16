const http = require('node:http');

const host = process.env.HOST;
const port = Number(process.env.PORT);
const originalListen = http.Server.prototype.listen;

// Umi 4.4.5 passes HOST through its config but calls listen(port) without it.
http.Server.prototype.listen = function listen(...args) {
  const isUmiListenCall = typeof args[0] === 'number' && (args.length === 1 || typeof args[1] === 'function');

  if (host && Number.isInteger(port) && isUmiListenCall) {
    // Do not let Umi's portfinder silently move this fixed development endpoint.
    if (args[0] !== port) {
      throw new Error(`Umi selected fallback port ${args[0]}; configured port ${port} is unavailable`);
    }
    args.splice(1, 0, host);
  }

  return originalListen.apply(this, args);
};
