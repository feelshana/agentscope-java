import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

const source = readFileSync(new URL('../src/api/auth.ts', import.meta.url), 'utf8');
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
});
const auth = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`);
function setup(token = 'old') {
  const values = new Map(token ? [['claw_token', token]] : []);
  globalThis.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
  };
  let expired = 0;
  globalThis.window = { location: new URL('http://localhost/configure/datasets'), dispatchEvent: () => expired++ };
  return () => expired;
}
const headers = { Authorization: 'Bearer old' };

test('concurrent API 401 clears the current credential and emits one expiry event', async () => {
  const count = setup();
  globalThis.fetch = async () => new Response(null, { status: 401 });
  const results = await Promise.allSettled([
    auth.apiFetch('/api/dataset-groups/page', { headers }),
    auth.apiFetch('/api/datasources', { headers }),
  ]);
  assert.ok(results.every(r => r.status === 'rejected' && r.reason.message === auth.SESSION_EXPIRED_MESSAGE));
  assert.equal(auth.getToken(), null);
  assert.equal(count(), 1);
});
test('a late 401 for the previous token does not log out a new login', async () => {
  const count = setup();
  let finish;
  globalThis.fetch = () => new Promise(resolve => { finish = resolve; });
  const pending = auth.apiFetch('/api/sessions', { headers });
  auth.saveToken('new');
  finish(new Response(null, { status: 401 }));
  await assert.rejects(pending, /重新登录/);
  assert.equal(auth.getToken(), 'new');
  assert.equal(count(), 0);
});
test('403 and server failures preserve authentication', async () => {
  const count = setup();
  for (const status of [403, 500]) {
    globalThis.fetch = async () => new Response(null, { status });
    assert.equal((await auth.apiFetch('/api/dataset-groups/g', { headers })).status, status);
  }
  assert.equal(auth.getToken(), 'old');
  assert.equal(count(), 0);
});
test('login and foreign-origin 401 responses cannot invalidate the local session', async () => {
  const count = setup();
  globalThis.fetch = async () => new Response(null, { status: 401 });
  for (const url of ['/api/auth/login', 'https://other.example/api/data']) {
    assert.equal((await auth.apiFetch(url, { headers })).status, 401);
  }
  assert.equal(auth.getToken(), 'old');
  assert.equal(count(), 0);
});
test('network errors and aborts do not remove cached credentials', async () => {
  const count = setup();
  for (const error of [new TypeError('offline'), new DOMException('cancelled', 'AbortError')]) {
    globalThis.fetch = async () => { throw error; };
    await assert.rejects(auth.apiFetch('/api/sessions', { headers }), e => e === error);
  }
  assert.equal(auth.getToken(), 'old');
  assert.equal(count(), 0);
});
test('Request headers are honored and requests without a matching credential cannot log out', async () => {
  const count = setup();
  globalThis.fetch = async () => new Response(null, { status: 401 });
  await assert.rejects(auth.apiFetch('/api/sessions'), /重新登录/);
  assert.equal(auth.getToken(), 'old');
  await assert.rejects(auth.apiFetch(new Request('http://localhost/api/sessions', { headers })), /重新登录/);
  assert.equal(auth.getToken(), null);
  assert.equal(count(), 1);
});
