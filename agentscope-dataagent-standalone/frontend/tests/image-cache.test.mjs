import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { acquireImage, cachedImageUrl, clearImageCache } from '../src/api/imageCache.ts';
import { clearToken, saveToken } from '../src/api/auth.ts';

afterEach(clearImageCache);

test('concurrent previews and reopening reuse the download and object URL', async () => {
  let downloads = 0;
  const load = async () => { downloads++; return new Blob(['image']); };
  const body = acquireImage('/image', load);
  const preview = acquireImage('/image', load);
  const [first, second] = await Promise.all([body.promise, preview.promise]);
  assert.equal(first, second);
  assert.equal(downloads, 1);
  body.release();
  preview.release();
  const reopened = acquireImage('/image', load);
  assert.equal(await reopened.promise, first);
  assert.equal(downloads, 1);
  reopened.release();
});

test('releasing during a download allows a new view to reuse the pending request', async () => {
  let finish;
  let signal;
  const first = acquireImage('/image', value => {
    signal = value;
    return new Promise(resolve => { finish = resolve; });
  });
  await Promise.resolve();
  first.release();
  const second = acquireImage('/image', () => { throw new Error('Unexpected download'); });
  assert.equal(signal.aborted, false);
  finish(new Blob(['image']));
  assert.equal(await first.promise, await second.promise);
  second.release();
});

test('failed downloads can be retried', async () => {
  const failed = acquireImage('/image', async () => { throw new Error('offline'); });
  await assert.rejects(failed.promise, /offline/);
  failed.release();
  const retry = acquireImage('/image', async () => new Blob(['image']));
  assert.equal(await retry.promise, cachedImageUrl('/image'));
  retry.release();
});

test('a previous login response cannot repopulate or remove the new login cache', async () => {
  let finish;
  let signal;
  const old = acquireImage('/image', value => {
    signal = value;
    return new Promise(resolve => { finish = resolve; });
  });
  await Promise.resolve();
  clearImageCache();
  assert.equal(signal.aborted, true);
  const current = acquireImage('/image', async () => new Blob(['new login']));
  const url = await current.promise;
  finish(new Blob(['old login']));
  await assert.rejects(old.promise, { name: 'AbortError' });
  old.release();
  assert.equal(cachedImageUrl('/image'), url);
  current.release();
});

test('cache pressure releases unused images while keeping displayed images available', async t => {
  const revoked = [];
  t.mock.method(URL, 'revokeObjectURL', url => revoked.push(url));
  const pinned = acquireImage('/displayed', async () => new Blob(['displayed']));
  const pinnedUrl = await pinned.promise;
  for (let index = 0; index < 32; index++) {
    const image = acquireImage(`/old-${index}`, async () => new Blob(['old']));
    await image.promise;
    image.release();
  }
  assert.equal(cachedImageUrl('/old-0'), undefined);
  assert.equal(cachedImageUrl('/displayed'), pinnedUrl);
  assert.ok(revoked.length > 0);
  assert.ok(!revoked.includes(pinnedUrl));
  pinned.release();
});

test('an oversized image is released after its last view closes', async t => {
  const revoked = [];
  t.mock.method(URL, 'revokeObjectURL', url => revoked.push(url));
  const image = acquireImage('/large', async () => new Blob([new Uint8Array(25 * 1024 * 1024)]));
  const url = await image.promise;
  assert.equal(cachedImageUrl('/large'), url);
  image.release();
  assert.equal(cachedImageUrl('/large'), undefined);
  assert.deepEqual(revoked, [url]);
});

test('saving or clearing login credentials releases cached image URLs', async t => {
  const revoked = [];
  t.mock.method(URL, 'revokeObjectURL', url => revoked.push(url));
  const previousSession = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
  const previousLocal = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  globalThis.sessionStorage = { setItem() {}, removeItem() {} };
  globalThis.localStorage = { removeItem() {} };
  try {
    for (const changeLogin of [() => saveToken('new-login'), clearToken]) {
      const image = acquireImage('/image', async () => new Blob(['image']));
      const url = await image.promise;
      changeLogin();
      assert.equal(cachedImageUrl('/image'), undefined);
      assert.ok(revoked.includes(url));
      image.release();
    }
  } finally {
    for (const [name, descriptor] of [['sessionStorage', previousSession], ['localStorage', previousLocal]]) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    }
  }
});
