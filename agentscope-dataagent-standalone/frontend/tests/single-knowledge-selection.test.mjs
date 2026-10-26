import test from 'node:test';
import assert from 'node:assert/strict';
import { singleKnowledgeSelection } from '../src/utils/singleKnowledgeSelection.ts';

test('zero and one explicit libraries preserve their scope', () => {
  assert.deepEqual(singleKnowledgeSelection([]), { groupIds: [], needsSelection: false });
  assert.deepEqual(singleKnowledgeSelection(['one', 'one']), { groupIds: ['one'], needsSelection: false });
});

test('legacy multi and all scopes require a new choice without picking the first', () => {
  assert.deepEqual(singleKnowledgeSelection(['one', 'two']), { groupIds: [], needsSelection: true });
  assert.deepEqual(singleKnowledgeSelection(['one'], true), { groupIds: [], needsSelection: true });
});
