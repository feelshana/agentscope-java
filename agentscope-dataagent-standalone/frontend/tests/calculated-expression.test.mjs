import test from 'node:test';
import assert from 'node:assert/strict';
import { formatCalculatedExpression } from '../src/components/modeling/formatCalculatedExpression.ts';

test('formats a CASE expression without adding a query wrapper or changing its formula', () => {
  const original = 'CASE WHEN SUM(new_program_num) = 0 THEN NULL ELSE SUM(cold_sucess_program_num) / SUM(new_program_num) END';
  const formatted = [
    'CASE',
    '  WHEN SUM(new_program_num) = 0 THEN NULL',
    '  ELSE SUM(cold_sucess_program_num) / SUM(new_program_num)',
    'END',
  ].join('\n');
  assert.equal(formatCalculatedExpression(original), formatted);
  assert.equal(formatCalculatedExpression(formatted), formatted);
});

test('preserves quoted values, identifiers and SQL comments while formatting', () => {
  const original = `CASE WHEN "When  Value"='A  B' THEN 1 -- leave the next branch active
ELSE 0 END`;
  const formatted = formatCalculatedExpression(original);
  assert.match(formatted, /"When  Value" = 'A  B'/);
  assert.match(formatted, /-- leave the next branch active\s*\n\s*ELSE/);
});
