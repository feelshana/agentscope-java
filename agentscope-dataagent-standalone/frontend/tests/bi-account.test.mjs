import test from 'node:test';
import assert from 'node:assert/strict';
import { readBiAccount } from '../src/utils/biAccount.ts';
const storage = value => ({ getItem: key => value[key] ?? null });
test('BI entry reads login account, never the Chinese display name', () => {
  assert.equal(readBiAccount(storage({ username: ' employee ', userName: '中文名' })), 'employee');
  assert.equal(readBiAccount(storage({ account: 'fallback' })), 'fallback');
  assert.equal(readBiAccount(storage({ username: 'Employee', account: 'employee' })), 'Employee');
  assert.throws(() => readBiAccount(storage({ userName: '中文名' })), /未读取到/);
  assert.throws(() => readBiAccount(storage({ username: 'alice', account: 'bob' })), /不一致/);
  assert.throws(() => readBiAccount(storage({ username: ' ', account: '' })), /未读取到/);
});
