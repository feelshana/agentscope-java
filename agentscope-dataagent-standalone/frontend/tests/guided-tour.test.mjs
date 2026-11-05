import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { adoptTour, pauseTour, resumeTour, tourHoldsQuery, tourLocksDraftDrawer, tourSignal, useTour } from '../src/state/guidedTour.ts';

const stored = new Map();
globalThis.sessionStorage = { getItem: key => stored.get(key) ?? null };
globalThis.window = { location: new URL('https://example.test/configure/modeling/demo') };
globalThis.fetch = async () => ({ ok: true, status: 200 });
let login = 0;
function enter(step) {
  stored.set('dataagent_token', `tour-account-${++login}`);
  adoptTour({ step, groupId: 'demo', route: 'upload', paused: false });
}
function current() {
  let value = null;
  function Probe() { value = useTour(); return null; }
  renderToStaticMarkup(React.createElement(Probe));
  return value;
}

test('finishing modeling protects the review and confirmation controls without auto-advancing', () => {
  enter('modeling');
  assert.equal(tourLocksDraftDrawer(current(), 'demo'), false);
  tourSignal('model-idle', { groupId: 'demo' });
  assert.equal(current().step, 'review');
  assert.equal(tourLocksDraftDrawer(current(), 'demo'), true);
  tourSignal('review-next', { groupId: 'demo' });
  assert.equal(current().step, 'confirm');
  assert.equal(tourLocksDraftDrawer(current(), 'demo'), true);
  tourSignal('confirm-done', { groupId: 'another-kb' });
  assert.equal(current().step, 'confirm');
  tourSignal('confirm-done', { groupId: 'demo' });
  assert.equal(current().step, 'publish');
});

test('pause restores draft inspection and resume protects the same unfinished step', () => {
  enter('confirm');
  pauseTour();
  assert.equal(tourLocksDraftDrawer(current(), 'demo'), false);
  tourSignal('confirm-done', { groupId: 'demo' });
  assert.equal(current().step, 'confirm');
  resumeTour();
  assert.equal(current().step, 'confirm');
  assert.equal(tourLocksDraftDrawer(current(), 'demo'), true);
});

test('the lock follows the actual account and knowledge base instead of cached tour state', () => {
  enter('publish');
  assert.equal(tourLocksDraftDrawer(current(), 'demo'), true);
  assert.equal(tourLocksDraftDrawer(current(), 'another-kb'), false);
  stored.set('dataagent_token', 'different-account-without-a-tour');
  assert.equal(current(), null);
  assert.equal(tourLocksDraftDrawer(null, 'demo'), false);
});

test('the tour, not a manual 去问数, takes the user to the query page once querying is ready', () => {
  enter('publish');
  assert.equal(tourHoldsQuery(current(), 'demo'), true);
  assert.equal(tourHoldsQuery(current(), 'other'), false);
  tourSignal('published', { groupId: 'demo' });
  assert.equal(current().step, 'preparing');
  assert.equal(tourHoldsQuery(current(), 'demo'), true);
  tourSignal('query-ready', { groupId: 'demo' });
  assert.equal(current().step, 'ask');
  assert.equal(tourHoldsQuery(current(), 'demo'), false);
  enter('preparing');
  pauseTour();
  assert.equal(tourHoldsQuery(current(), 'demo'), false);
});
