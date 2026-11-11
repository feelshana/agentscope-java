import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { reopenOnboarding, resetOnboarding } from '../src/api/onboarding.ts';

const names = ['window', 'sessionStorage', 'fetch'];
const originals = new Map(names.map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
afterEach(() => { for (const [name, value] of originals) { if (value) Object.defineProperty(globalThis, name, value); else delete globalThis[name]; } });
function setup() {
  globalThis.window = { location: new URL(`http://localhost${process.env.DATAAGENT_TEST_BASE_PATH}more`) };
  globalThis.sessionStorage = { getItem: () => 'test-credential' };
}
for (const [label, action] of [['reopen', reopenOnboarding], ['reset', resetOnboarding]]) {
  test(`failed ${label} is observable by the help drawer instead of closing as a success`, async () => {
    setup(); globalThis.fetch = async () => new Response(null, { status: 503 });
    await assert.rejects(action(), /503/);
  });
}
test('reopen and reset preserve distinct authenticated actions in every deployment prefix', async () => {
  setup(); const calls = [];
  globalThis.fetch = async (url, init) => { calls.push([new URL(url), init]); return Response.json({ stage: 'full', tasks: [], completed: 0 }); };
  await reopenOnboarding(); await resetOnboarding();
  assert.deepEqual(calls.map(([url]) => url.pathname), ['reopen', 'reset'].map(action => `${process.env.DATAAGENT_TEST_BASE_PATH}api/onboarding/${action}`));
  for (const [, init] of calls) { assert.equal(init.method, 'POST'); assert.equal(new Headers(init.headers).get('Authorization'), 'Bearer test-credential'); }
});
