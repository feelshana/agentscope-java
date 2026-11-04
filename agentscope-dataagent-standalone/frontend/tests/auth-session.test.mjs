import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import * as auth from '../src/api/auth.ts';
import { APP_BASE } from '../src/api/paths.ts';

const originalGlobals = Object.fromEntries(['window', 'document', 'localStorage', 'sessionStorage', 'fetch']
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
  assert.ok(expectedBase === '/' || expectedBase === '/dataagent/' || expectedBase === '/dify/dataagent/');
  assert.equal(APP_BASE, expectedBase === '/' ? '' : expectedBase.replace(/\/$/, ''));
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

function fixtureToken(expiresAt, marker) {
  return 'header.' + Buffer.from(JSON.stringify({ exp: expiresAt / 1000, marker }))
    .toString('base64url') + '.signature';
}

function activityPage(token) {
  const expired = setup(token);
  const events = new Map();
  window.addEventListener = (name, listener) => events.set(name, listener);
  window.removeEventListener = name => events.delete(name);
  globalThis.document = { visibilityState: 'visible', addEventListener() {}, removeEventListener() {} };
  return { expired, fire: (name = 'pointerdown', isTrusted = true) => events.get(name)?.({ isTrusted }) };
}

const flushNetwork = () => new Promise(resolve => setImmediate(resolve));

test('only foreground user activity renews, and the renewed credential expires when idle', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1000 });
  const original = fixtureToken(Date.now() + 1_800_000, 'original');
  const page = activityPage(original);
  const requests = [];
  globalThis.fetch = async (url, init) => {
    requests.push({ url, init });
    return String(url).endsWith('/api/auth/renew')
      ? Response.json({ token: fixtureToken(Date.now() + 1_800_000, 'renewed') })
      : new Response('{}');
  };
  const stop = auth.watchSessionActivity();
  try {
    await auth.apiFetch('/api/sessions', { headers: { Authorization: `Bearer ${original}` } });
    page.fire('pointerdown', false);
    document.visibilityState = 'hidden';
    page.fire();
    document.visibilityState = 'visible';
    assert.equal(requests.length, 1);
    t.mock.timers.tick(1_700_000);
    page.fire();
    await flushNetwork();
    assert.equal(requests.length, 2);
    assert.ok(String(requests[1].url).endsWith('/api/auth/renew'));
    t.mock.timers.tick(100_000);
    assert.notEqual(auth.getToken(), original);
    assert.equal(page.expired(), 0);
    t.mock.timers.tick(1_700_000);
    assert.equal(auth.getToken(), null);
    assert.equal(page.expired(), 1);
    assert.equal(requests.length, 2);
  } finally { stop(); }
});

test('concurrent activity shares renewal and a late response cannot replace another login', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1000 });
  const original = fixtureToken(Date.now() + 5000, 'original');
  const page = activityPage(original);
  let finish;
  const requests = [];
  globalThis.fetch = (url, init) => {
    requests.push({ url, init });
    return String(url).endsWith('/api/auth/renew')
      ? new Promise(resolve => { finish = resolve; })
      : Promise.resolve(new Response('{}'));
  };
  const stop = auth.watchSessionActivity();
  try {
    page.fire();
    page.fire('keydown');
    const pending = auth.apiFetch('/api/sessions', { headers: { Authorization: `Bearer ${original}` } });
    assert.equal(requests.length, 1);
    const replacement = fixtureToken(Date.now() + 1_800_000, 'replacement');
    finish(Response.json({ token: replacement }));
    await pending;
    assert.equal(new Headers(requests[1].init.headers).get('Authorization'), `Bearer ${replacement}`);
    t.mock.timers.tick(5000);
    assert.equal(requests.length, 3);
    const otherLogin = fixtureToken(Date.now() + 1_800_000, 'other');
    auth.saveToken(otherLogin);
    finish(Response.json({ token: fixtureToken(Date.now() + 1_800_000, 'late') }));
    await flushNetwork();
    assert.equal(auth.getToken(), otherLogin);
  } finally { stop(); }
});

test('a renewal started before expiry can finish across the old deadline', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1000 });
  const original = fixtureToken(Date.now() + 1000, 'original');
  const page = activityPage(original);
  let finish;
  globalThis.fetch = () => new Promise(resolve => { finish = resolve; });
  const stop = auth.watchSessionActivity();
  try {
    page.fire();
    t.mock.timers.tick(1100);
    assert.equal(page.expired(), 0);
    const replacement = fixtureToken(Date.now() + 1_800_000, 'replacement');
    finish(Response.json({ token: replacement }));
    await flushNetwork();
    assert.equal(auth.getToken(), replacement);
    assert.equal(page.expired(), 0);
  } finally { stop(); }
});
