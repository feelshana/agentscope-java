import http from 'node:http';
import { readFile, realpath, stat } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { dirname, resolve, sep, extname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { pipeline } from 'node:stream';
import { validateConfig } from './bi-simulator.mjs';

const directory = dirname(fileURLToPath(import.meta.url));
const staticFiles = new Set(['bi-simulator.html', 'bi-simulator-entry.html', 'bi-simulator.css', 'bi-simulator.mjs', 'bi-login.mjs']);
const mime = { '.html': 'text/html; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };

function reply(response, status, text) {
  response.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' }); response.end(text);
}

async function sendFile(request, response, file) {
  const info = await stat(file);
  if (!info.isFile()) return reply(response, 404, 'Not found');
  response.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream', 'Content-Length': info.size,
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'SAMEORIGIN' });
  if (request.method === 'HEAD') return response.end();
  pipeline(createReadStream(file), response, () => {});
}

export async function createSimulatorServer({ backend, configFile, frontend }) {
  const upstream = new URL(backend);
  if (upstream.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(upstream.hostname)
      || upstream.pathname !== '/' || upstream.search || upstream.hash || upstream.username || upstream.password)
    throw new Error('本地代理后端必须是loopback HTTP根地址，例如http://127.0.0.1:18084');
  const privateFile = await realpath(resolve(configFile));
  const config = validateConfig(JSON.parse(await readFile(privateFile, 'utf8')));
  if (!config.basePath) throw new Error('本地模拟服务需要独立子路径，例如/dataagent');
  const frontendRoot = frontend ? await realpath(resolve(frontend)) : null;
  const prefix = config.basePath;
  return http.createServer(async (request, response) => {
    try {
      const url = new URL(request.url, 'http://localhost');
      // Do not print requests or authorization data; retain headers required by the real backend.
      if (url.pathname.startsWith(`${prefix}/api/`) || url.pathname === `${prefix}/actuator/health`
          || (!frontendRoot && url.pathname.startsWith(`${prefix}/`))) {
        const target = new URL(upstream);
        target.pathname = url.pathname.slice(prefix.length);
        target.search = url.search;
        const headers = { ...request.headers, host: upstream.host, 'x-forwarded-proto': 'http' };
        delete headers.connection;
        const proxy = http.request(target, { method: request.method, headers }, result => {
          response.writeHead(result.statusCode || 502, result.headers);
          pipeline(result, response, () => {});
        });
        proxy.setTimeout(620_000, () => proxy.destroy());
        proxy.on('error', () => { if (!response.headersSent) reply(response, 502, '后端暂不可用，请检查本地DataAgent服务。'); else response.destroy(); });
        response.on('close', () => { if (!response.writableFinished) proxy.destroy(); });
        pipeline(request, proxy, () => {});
        return;
      }
      if (!['GET', 'HEAD'].includes(request.method)) return reply(response, 405, 'Method not allowed');
      if (url.pathname === '/') { response.writeHead(302, { Location: '/bi-sim/' }); return response.end(); }
      if (url.pathname === '/bi-sim/bi-simulator.config.json') {
        response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
        return response.end(request.method === 'HEAD' ? undefined : JSON.stringify(config));
      }
      if (url.pathname === '/bi-sim/') return await sendFile(request, response, resolve(directory, 'bi-simulator.html'));
      if (url.pathname.startsWith('/bi-sim/')) {
        const name = url.pathname.slice('/bi-sim/'.length);
        if (staticFiles.has(name)) return await sendFile(request, response, resolve(directory, name));
      }
      if (url.pathname === prefix) { response.writeHead(302, { Location: `${prefix}/` }); return response.end(); }
      if (frontendRoot && url.pathname.startsWith(`${prefix}/`)) {
        const name = decodeURIComponent(url.pathname.slice(prefix.length + 1));
        const file = resolve(frontendRoot, name);
        if (name.includes('\\') || name.includes('\0') || (file !== frontendRoot && !file.startsWith(frontendRoot + sep))) return reply(response, 404, 'Not found');
        if (name.startsWith('assets/')) {
          const actual = await realpath(file);
          if (!actual.startsWith(frontendRoot + sep)) return reply(response, 404, 'Not found');
          return await sendFile(request, response, actual);
        }
        return await sendFile(request, response, resolve(frontendRoot, 'index.html'));
      }
      reply(response, 404, 'Not found');
    } catch { if (!response.headersSent) reply(response, 404, 'Not found'); else response.destroy(); }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const args = new Map();
  for (let i = 2; i < process.argv.length; i += 2) args.set(process.argv[i], process.argv[i + 1]);
  if (!args.get('--config')) throw new Error('请提供--config指向外置bi-simulator.config.json');
  const port = Number(args.get('--port') || 18766);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('本地端口无效');
  const server = await createSimulatorServer({ backend: args.get('--backend') || 'http://127.0.0.1:18084', configFile: args.get('--config'), frontend: args.get('--frontend') });
  server.on('error', error => { console.error(`本地模拟服务启动失败：${error.code || 'unknown'}`); process.exitCode = 1; });
  server.listen(port, '127.0.0.1', () => console.log(`模拟BI入口：http://localhost:${port}/bi-sim/`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
}
