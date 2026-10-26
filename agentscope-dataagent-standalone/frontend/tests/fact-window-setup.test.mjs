import test from 'node:test';
import assert from 'node:assert/strict';
import { factWindowSetup } from '../src/utils/factWindowSetup.ts';
const table = (id, tableRole, mode = null, timeColumn = 'date') => ({ datasetId: id, tableRole, mode, timeColumn, temporalColumns: ['date'] });
test('dated dimensions never open the time window dialog', () => {
  assert.deepEqual(factWindowSetup([table('customer', 'DIMENSION')], true), { facts: [], unknown: [], ready: true });
});
test('only new facts pause modeling, existing configuration is reused', () => {
  const fresh = table('new', 'FACT');
  const setup = factWindowSetup([table('old', 'FACT', 'LIMITED'), table('all', 'FACT', 'UNLIMITED'), fresh], true);
  assert.deepEqual(setup.facts, [fresh]); assert.equal(setup.ready, false);
});
test('unknown roles require confirmation without appearing as facts', () => {
  const unknown = table('unknown', 'UNKNOWN');
  const setup = factWindowSetup([unknown], true);
  assert.deepEqual(setup.facts, []); assert.deepEqual(setup.unknown, [unknown]); assert.equal(setup.ready, false);
});
test('saved facts unblock modeling and legacy multi-column configuration must be selected', () => {
  assert.equal(factWindowSetup([table('fact', 'FACT', 'LIMITED')], true).ready, true);
  assert.equal(factWindowSetup([table('fact', 'FACT', 'LIMITED', null)], true).ready, false);
});
