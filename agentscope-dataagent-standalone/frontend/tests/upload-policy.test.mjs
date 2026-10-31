import test from 'node:test';
import assert from 'node:assert/strict';
import { getUploadPolicy } from '../src/api/datasets.ts';

test('an unavailable policy can retry and an older backend uses the current upload limits', async t => {
  const originals = Object.fromEntries(['window', 'sessionStorage', 'fetch']
    .map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  t.after(() => {
    for (const [key, descriptor] of Object.entries(originals)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  });
  globalThis.window = { location: new URL('http://localhost/') };
  globalThis.sessionStorage = { getItem: () => null };
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return calls === 1
      ? new Response('temporarily unavailable', { status: 503 })
      : new Response(null, { status: 404 });
  };
  await assert.rejects(getUploadPolicy());
  const policy = await getUploadPolicy();
  assert.equal(policy.maxRows, 500000);
  assert.equal(policy.maxCells, 10000000);
  assert.equal(policy.statusAvailable, false);
  assert.equal(await getUploadPolicy(), policy);
  assert.equal(calls, 2);
});
