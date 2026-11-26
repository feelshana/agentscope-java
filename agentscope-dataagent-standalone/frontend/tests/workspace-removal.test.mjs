import test from 'node:test';
import assert from 'node:assert/strict';
import { removeWorkspaceItems, updateWorkspaceSelection } from '../src/components/modeling/workspaceRemoval.ts';

test('a rejected removal keeps its error and does not undo or block the other items', async () => {
  const calls = [];
  const result = await removeWorkspaceItems(['first', 'stale', 'last'], async item => {
    calls.push(item);
    if (item === 'stale') throw new Error('引用已变化');
  });
  assert.deepEqual(calls, ['first', 'stale', 'last']);
  assert.deepEqual(result, {
    removed: ['first', 'last'],
    failures: [{ target: 'stale', message: '引用已变化' }],
  });
});

test('checking and unchecking separate rows preserves other selections', () => {
  const first = new Set(['first']);
  const both = updateWorkspaceSelection(first, 'second', true);
  assert.deepEqual([...first], ['first']);
  assert.deepEqual([...both], ['first', 'second']);
  assert.deepEqual([...updateWorkspaceSelection(both, 'first', false)], ['second']);
});
