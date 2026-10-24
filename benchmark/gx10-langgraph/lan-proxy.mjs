import http from 'node:http';
import net from 'node:net';

const address = process.argv[2];
if (!net.isIPv4(address) || !address.startsWith('192.168.')) {
  throw new Error('Supply the GX10 private LAN IPv4 address');
}
const authority = `${address}:4141`;
const origin = `http://${authority}`;
const server = http.createServer((incoming, response) => {
  if (incoming.headers.host !== authority ||
      (incoming.headers.origin !== undefined && incoming.headers.origin !== origin) ||
      incoming.headers['sec-fetch-site'] === 'cross-site' ||
      !incoming.url.startsWith('/') || incoming.url.startsWith('//')) {
    response.writeHead(403);
    response.end('Forbidden');
    return;
  }
  const headers = { ...incoming.headers, host: '127.0.0.1:4141' };
  if (headers.origin !== undefined) {
    headers.origin = 'http://127.0.0.1:4141';
  }
  // This LAN endpoint can reach only this GX10's local LangGraph API.
  // Client-supplied upstreams and credentials cannot turn it into an open relay.
  headers['x-lgview-upstream'] = 'http://127.0.0.1:2024';
  delete headers['x-lgview-api-key'];
  const upstream = http.request({ hostname: '127.0.0.1', port: 4141,
    path: incoming.url, method: incoming.method, headers }, (received) => {
    response.writeHead(received.statusCode, received.headers);
    received.pipe(response);
  });
  upstream.on('error', () => {
    if (response.headersSent) {
      response.destroy();
      return;
    }
    response.writeHead(502);
    response.end('Local lgview is unavailable');
  });
  incoming.on('aborted', () => upstream.destroy());
  response.on('close', () => upstream.destroy());
  incoming.pipe(upstream);
});
server.requestTimeout = 0; // Keep streamed run updates alive.
server.listen(4141, address, () => console.log(`GX10 lgview LAN access: ${origin}`));
