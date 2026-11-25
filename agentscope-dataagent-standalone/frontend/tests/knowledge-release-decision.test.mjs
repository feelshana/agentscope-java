import assert from 'node:assert/strict';
import test from 'node:test';
import { releaseDecision } from '../src/components/modeling/releaseDecision.ts';

const workflow = { publishedVersion: 15, queryAvailable: true, engineeringStatus: 'PASSED', blockers: [] };
const file = (path, kind = 'MODIFIED') => ({ path, kind, category: 'EXAMPLE' });
const decide = (overrides = {}) => releaseDecision({
  workflow, files: [], assetTasks: [], incompleteViews: [], confirmations: 0, questionIds: [], removalIds: [], ...overrides,
});

test('release groups keep object counts and optional tasks separate', () => {
  const release = decide({
    files: [
      file('models/orders/metadata.yml'),
      file('models/orders/columns.yml'),
      file('knowledge/sql/held.md'),
    ],
    confirmations: 1,
    questionIds: ['held'],
  });
  assert.deepEqual(release.changes.map(change => change.path), ['models/orders/metadata.yml', 'models/orders/columns.yml']);
  assert.equal(release.objectCount, 1);
  assert.equal(release.taskCount, 2);
  assert.equal(release.blocker, null);
});

test('question removal stays counted once by its removed example file', () => {
  const release = decide({
    files: [file('knowledge/questions/archived.yml'), file('knowledge/sql/archived.md', 'REMOVED')],
    removalIds: ['archived'],
  });
  assert.deepEqual(release.removals, ['archived']);
  assert.deepEqual(release.changes.map(change => change.path), ['knowledge/sql/archived.md']);
});

test('missing engineering records permit checking while known failures and unfinished views block', () => {
  const unchecked = { ...workflow, engineeringStatus: 'NOT_CHECKED', blockers: [{ code: 'ENGINEERING_REQUIRED' }] };
  assert.equal(decide({ workflow: unchecked }).blocker, null);
  assert.equal(decide({ workflow: { ...workflow, engineeringStatus: 'FAILED' } }).blocker.kind, 'engineering');
  const target = { kind: 'view', name: 'monthly_sales', step: 'VERIFY_PENDING' };
  assert.deepEqual(decide({ assetTasks: [target] }).blocker, { kind: 'asset', target });
  assert.deepEqual(decide({ incompleteViews: ['draft_view'] }).blocker, { kind: 'incomplete', name: 'draft_view' });
  const failure = { severity: 'error', source: 'wren', message: '工程校验未通过' };
  assert.deepEqual(decide({ publicationFailure: failure }).blocker, { kind: 'engineering', message: failure.message });
  assert.deepEqual(decide({ publicationFailure: { ...failure, assetReview: target } }).blocker, { kind: 'asset', target });
});

test('an existing unavailable published version can require republishing even without file differences', () => {
  assert.equal(decide().republish, false);
  assert.equal(decide({ workflow: { ...workflow, queryAvailable: false } }).republish, true);
  assert.equal(decide({ workflow: { ...workflow, queryAvailable: false, publishedVersion: 0 } }).republish, false);
  assert.equal(decide({
    workflow: { ...workflow, queryAvailable: false }, files: [file('knowledge/sql/held.md')], questionIds: ['held'],
  }).republish, false);
});
