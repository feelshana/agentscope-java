import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, writeFile, mkdir, rm, realpath, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname, basename, resolve } from 'node:path';
import { validateConfig, normalizeUser, entryUsername } from './bi-simulator.mjs';
import { createSimulatorServer } from './bi-simulator-server.mjs';

const config = { sharedKey: Buffer.alloc(32).toString('base64'), basePath: '/dataagent', users: [{ username: 'fixture-alice', label: '普通用户' }] };

test('Nginx explicitly serves module scripts and the remaining simulator assets with correct MIME types', async () => {
  const nginx = await readFile(new URL('./bi-simulator.nginx.conf', import.meta.url), 'utf8');
  const types = nginx.match(/types\s*\{([^}]+)\}/)?.[1];
  assert.ok(types);
  assert.match(types, /application\/javascript\s+js\s+mjs\s*;/);
  assert.match(types, /text\/html\s+html\s*;/);
  assert.match(types, /text\/css\s+css\s*;/);
  assert.match(types, /application\/json\s+json\s*;/);
});

test('configuration requires an explicit key, safe prefix and nonempty users', () => {
  assert.equal(validateConfig(config).basePath, '/dataagent');
  for (const bad of [{ ...config, sharedKey: '' }, { ...config, basePath: '//other.test' }, { ...config, basePath: '/a/../b' }, { ...config, users: [] }]) assert.throws(() => validateConfig(bad));
});

test('usernames are trimmed, bounded and never interpreted as markup', () => {
  assert.equal(normalizeUser('  fixture-alice  '), 'fixture-alice');
  for (const value of ['', ' '.repeat(3), 'a\nb', 'x'.repeat(129)]) assert.throws(() => normalizeUser(value));
  assert.equal(normalizeUser('<b>user</b>'), '<b>user</b>');
});

test('a reload retains its own tab identity while a new entry uses the selected BI user', () => {
  const args = { cached: 'alice', selected: 'bob', fallback: 'guest' };
  assert.equal(entryUsername({ ...args, reload: true }), 'alice');
  assert.equal(entryUsername({ ...args, reload: false }), 'bob');
  assert.equal(entryUsername({ ...args, selected: null, cached: null, reload: false }), 'guest');
});

test('duplicate users do not create ambiguous account options', () => {
  assert.equal(validateConfig({ ...config, users: [...config.users, ...config.users] }).users.length, 1);
});

async function listen(server) {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return `http://127.0.0.1:${server.address().port}`;
}
async function stop(server) { await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }); }
async function removeOwnedFixture(directory) {
  const actual = await realpath(directory);
  const parent = await realpath(tmpdir());
  assert.equal(dirname(actual).toLowerCase(), parent.toLowerCase());
  assert.ok(basename(actual).startsWith('bi-simulator-'));
  assert.equal(actual.toLowerCase(), resolve(directory).toLowerCase());
  await rm(actual, { recursive: true, force: true });
}

test('static host uses an explicit external config and the API proxy preserves method, headers and body', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bi-simulator-test-'));
  let upstream; let server;
  try {
    const file = join(directory, 'private.json'); await writeFile(file, JSON.stringify(config));
    await mkdir(join(directory, 'frontend')); await mkdir(join(directory, 'frontend/assets'));
    await writeFile(join(directory, 'frontend/index.html'), '<html>REAL-BUILD</html>');
    await writeFile(join(directory, 'frontend/assets/app.js'), 'export const fixture = true;');
    upstream = http.createServer(async (req, res) => {
      let body = ''; for await (const chunk of req) body += chunk;
      res.writeHead(req.url.includes('rejected') ? 401 : 200, { 'Content-Type': 'application/json', 'X-Frame-Options': 'SAMEORIGIN' });
      res.end(JSON.stringify({ path: req.url, method: req.method, authorization: req.headers.authorization, body }));
    });
    const backend = await listen(upstream);
    server = await createSimulatorServer({ backend, configFile: file, frontend: join(directory, 'frontend') });
    const base = await listen(server);
    assert.match(await (await fetch(base + '/bi-sim/')).text(), /数据分析智能体/);
    const settings = await fetch(base + '/bi-sim/bi-simulator.config.json');
    assert.equal(settings.headers.get('cache-control'), 'no-store');
    assert.equal((await settings.json()).sharedKey, config.sharedKey);
    const response = await fetch(base + '/dataagent/api/auth/bi-login?q=1', { method: 'POST', headers: { Authorization: 'Bearer fixture-token', 'Content-Type': 'application/json' }, body: '{"ticket":"fixture"}' });
    assert.deepEqual(await response.json(), { path: '/api/auth/bi-login?q=1', method: 'POST', authorization: 'Bearer fixture-token', body: '{"ticket":"fixture"}' });
    assert.equal(response.headers.get('x-frame-options'), 'SAMEORIGIN');
    assert.equal((await fetch(base + '/dataagent/api/rejected')).status, 401);
    assert.match(await (await fetch(base + '/dataagent/chat')).text(), /REAL-BUILD/);
    assert.match(await (await fetch(base + '/dataagent/assets/app.js')).text(), /fixture/);
    assert.equal((await fetch(base + '/bi-sim/bi-login.md')).status, 404);
    assert.equal((await fetch(base + '/bi-sim/private.json')).status, 404);
    assert.equal((await fetch(base + '/bi-sim/bi-simulator.html', { method: 'POST' })).status, 405);
    assert.equal((await fetch(base + '/dataagent/assets/..%5c..%5cprivate.json')).status, 404);
    assert.equal((await fetch(base + '/dataagent/assets/missing.js')).status, 404);
  } finally {
    if (server) await stop(server); if (upstream) await stop(upstream);
    await removeOwnedFixture(directory);
  }
});

test('the streaming proxy forwards the first SSE event before upstream completes', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bi-simulator-stream-'));
  let upstream; let server; let complete;
  try {
    const file = join(directory, 'private.json'); await writeFile(file, JSON.stringify(config));
    upstream = http.createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      res.write('event: token\ndata: first\n\n');
      complete = () => res.end('event: done\ndata: end\n\n');
    });
    const backend = await listen(upstream);
    server = await createSimulatorServer({ backend, configFile: file });
    const base = await listen(server); const response = await fetch(base + '/dataagent/api/chat');
    const reader = response.body.getReader();
    const first = await Promise.race([reader.read(), new Promise((_, reject) => { const timer = setTimeout(() => reject(new Error('SSE was buffered')), 2000); timer.unref(); })]);
    assert.match(new TextDecoder().decode(first.value), /data: first/);
    complete();
    const last = await reader.read(); assert.match(new TextDecoder().decode(last.value), /data: end/);
    await reader.cancel();
  } finally {
    complete?.(); if (server) await stop(server); if (upstream) await stop(upstream);
    await removeOwnedFixture(directory);
  }
});

test('the local helper rejects remote upstreams and relative base paths', async () => {
  await assert.rejects(createSimulatorServer({ backend: 'http://remote.invalid:8080', configFile: 'unused' }), /loopback/);
});

test('account mode supports the production prefix without an AES key', () => {
  const value = validateConfig({ accountLogin: true, basePath: '/dify/dataagent', users: config.users });
  assert.equal(value.accountLogin, true);
  assert.equal(value.basePath, '/dify/dataagent');
});
