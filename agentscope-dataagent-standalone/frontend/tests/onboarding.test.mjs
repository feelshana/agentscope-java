import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { refreshOnboarding, dismissOnboarding, useOnboarding } from '../src/api/onboarding.ts';

const stored = new Map();
globalThis.sessionStorage = { getItem: key => stored.get(key) ?? null };
globalThis.window = { location: new URL('https://example.test/chat'), dispatchEvent: () => {} };
const pending = [];
globalThis.fetch = (url, options) => new Promise(resolve => pending.push({ url, options, resolve }));
const token = value => stored.set('dataagent_token', value);
const status = (completed, stage = 'full') => ({
  stage, completed, daysLeft: 7, dismissed: stage === 'hidden', tipsSeen: [], starters: [],
  queryable: false, unpublished: [], tasks: [],
});
const finish = (request, value) => request.resolve({ status: 200, ok: true, json: async () => value });
function Probe() {
  const current = useOnboarding();
  return React.createElement('span', null, current ? `${current.stage}:${current.completed}` : 'missing');
}
const read = () => renderToStaticMarkup(React.createElement(Probe));

test('switching accounts starts a separate request and ignores the old response', async () => {
  token('account-a');
  const first = refreshOnboarding(true);
  const old = pending.at(-1);
  token('account-b');
  const second = refreshOnboarding(true);
  const current = pending.at(-1);
  assert.notEqual(old, current);
  assert.equal(current.options.headers.Authorization, 'Bearer account-b');
  assert.ok(String(current.url).includes(`${process.env.DATAAGENT_TEST_BASE_PATH}api/onboarding`));
  finish(current, status(2));
  await second;
  finish(old, status(1));
  await first;
  assert.match(read(), /full:2/);
});

test('an old request finishing cannot clear the new accounts in-flight request', async () => {
  token('account-c');
  const first = refreshOnboarding(true);
  const old = pending.at(-1);
  token('account-d');
  const second = refreshOnboarding(true);
  const current = pending.at(-1);
  finish(old, status(1));
  await first;
  const before = pending.length;
  const shared = refreshOnboarding(true);
  assert.equal(pending.length, before);
  assert.equal(shared, second);
  finish(current, status(3));
  await second;
  assert.match(read(), /full:3/);
});

test('a late status response cannot undo the users newer dismissal', async () => {
  token('account-e');
  const refresh = refreshOnboarding(true);
  const old = pending.at(-1);
  const dismiss = dismissOnboarding();
  finish(pending.at(-1), status(2, 'hidden'));
  await dismiss;
  finish(old, status(2));
  await refresh;
  assert.match(read(), /hidden:2/);
});

test('signed-out users cannot read the previous accounts cached onboarding', () => {
  stored.clear();
  assert.match(read(), /missing/);
});
