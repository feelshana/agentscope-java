import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import * as auth from '../src/api/auth.ts';
import { APP_BASE } from '../src/api/paths.ts';

const originalGlobals = Object.fromEntries(['window', 'localStorage', 'sessionStorage', 'fetch']
  .map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
afterEach(() => {
  for (const [key, descriptor] of Object.entries(originalGlobals)) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor);
    else delete globalThis[key];
  }
});
const expectedBase = process.env.DATAAGENT_TEST_BASE_PATH;
function storage(values) {
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
  };
}
function setup(token = 'old') {
  globalThis.sessionStorage = storage(new Map(token ? [['dataagent_token', token]] : []));
  globalThis.localStorage = storage(new Map([['claw_token', 'legacy-token']]));
  let expired = 0;
  globalThis.window = {
    location: new URL(`http://localhost${expectedBase}configure/datasets`),
    dispatchEvent: event => { assert.equal(event.type, auth.AUTH_EXPIRED_EVENT); expired++; },
  };
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
  await assert.rejects(pending, { message: auth.SESSION_EXPIRED_MESSAGE });
  assert.equal(auth.getToken(), 'new');
  assert.equal(count(), 0);
});
test('403 and server failures preserve authentication', async () => {
  const count = setup();
  for (const status of [403, 429, 500, 503]) {
    globalThis.fetch = async () => new Response(null, { status });
    assert.equal((await auth.apiFetch('/api/dataset-groups/g', { headers })).status, status);
  }
  assert.equal(auth.getToken(), 'old');
  assert.equal(count(), 0);
});
test('login and foreign-origin 401 responses cannot invalidate the local session', async () => {
  const count = setup();
  globalThis.fetch = async () => new Response(null, { status: 401 });
  for (const url of ['/api/auth/login', '/api/auth/bi-login', 'https://other.example/api/data']) {
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
  await assert.rejects(auth.apiFetch('/api/sessions'), { message: auth.SESSION_EXPIRED_MESSAGE });
  assert.equal(auth.getToken(), 'old');
  await assert.rejects(auth.apiFetch(new Request('http://localhost/api/sessions', { headers })), { message: auth.SESSION_EXPIRED_MESSAGE });
  assert.equal(auth.getToken(), null);
  assert.equal(count(), 1);
});

test('credentials use session storage and saving or clearing removes the legacy token', () => {
  setup(null);
  assert.equal(auth.getToken(), null);
  auth.saveToken('new');
  assert.equal(sessionStorage.getItem('dataagent_token'), 'new');
  assert.equal(localStorage.getItem('claw_token'), null);
  auth.clearToken();
  assert.equal(auth.getToken(), null);
});

test('same-origin requests use the deployment prefix exactly once and retain query and headers', async () => {
  setup();
  assert.ok(expectedBase === '/' || expectedBase === '/dataagent/');
  assert.equal(APP_BASE, expectedBase === '/' ? '' : '/dataagent');
  const calls = [];
  globalThis.fetch = async (input, init) => {
    calls.push(new Request(new URL(String(input), window.location.href), init));
    return new Response('{}');
  };
  await auth.apiFetch('/api/sessions?page=2', { headers });
  await auth.apiFetch(`${expectedBase}api/sessions?page=3`, { headers });
  assert.equal(calls[0].url, `http://localhost${expectedBase}api/sessions?page=2`);
  assert.equal(calls[1].url, `http://localhost${expectedBase}api/sessions?page=3`);
  assert.equal(calls[0].headers.get('Authorization'), 'Bearer old');
});

test('Request rewriting preserves method, body and headers', async () => {
  setup();
  let received;
  globalThis.fetch = async (input, init) => {
    received = new Request(input, init);
    return new Response('{}');
  };
  await auth.apiFetch(new Request('http://localhost/api/sessions?mode=create', {
    method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: '{"name":"测试"}',
  }));
  assert.equal(received.url, `http://localhost${expectedBase}api/sessions?mode=create`);
  assert.equal(received.method, 'POST');
  assert.equal(received.headers.get('Authorization'), 'Bearer old');
  assert.equal(await received.text(), '{"name":"测试"}');
});

test('foreign requests retain their destination and an init credential overrides Request headers', async () => {
  const count = setup('new');
  let received;
  globalThis.fetch = async (input, init) => {
    received = new Request(input, init);
    return new Response(null, { status: 401 });
  };
  await auth.apiFetch('https://other.example/api/data', { headers });
  assert.equal(received.url, 'https://other.example/api/data');
  assert.equal(auth.getToken(), 'new');
  await assert.rejects(auth.apiFetch(new Request('http://localhost/api/sessions', { headers }), {
    headers: { Authorization: 'Bearer new' },
  }), { message: auth.SESSION_EXPIRED_MESSAGE });
  assert.equal(received.headers.get('Authorization'), 'Bearer new');
  assert.equal(auth.getToken(), null);
  assert.equal(count(), 1);
});

test('login options use the deployment prefix and failed loads preserve the last valid options', async () => {
  setup();
  let received;
  globalThis.fetch = async (url, init) => {
    received = { url, init };
    return Response.json({ passwordLoginEnabled: false, biLoginEnabled: true });
  };
  await auth.loadEntryOptions();
  assert.equal(received.url, `${expectedBase}api/auth/options`);
  assert.equal(received.init.cache, 'no-store');
  assert.deepEqual(auth.getEntryOptions(), { passwordLoginEnabled: false, biLoginEnabled: true });
  for (const response of [new Response(null, { status: 503 }), Response.json({ passwordLoginEnabled: 'true' })]) {
    globalThis.fetch = async () => response;
    await assert.rejects(auth.loadEntryOptions(), /登录配置/);
    assert.deepEqual(auth.getEntryOptions(), { passwordLoginEnabled: false, biLoginEnabled: true });
  }
  globalThis.fetch = async () => { throw new TypeError('offline'); };
  await assert.rejects(auth.loadEntryOptions(), /offline/);
  assert.deepEqual(auth.getEntryOptions(), { passwordLoginEnabled: false, biLoginEnabled: true });
});
