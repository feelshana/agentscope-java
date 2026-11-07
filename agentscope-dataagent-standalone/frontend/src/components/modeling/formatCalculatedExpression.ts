import { formatDialect, trino } from 'sql-formatter';

// Keep literals, quoted identifiers, comments, and compound operators intact when comparing.
const tokenPattern = /--[^\r\n]*|\/\*[\s\S]*?\*\/|'(?:''|\\[\s\S]|[^'\\])*'|"(?:""|\\[\s\S]|[^"\\])*"|`(?:``|\\[\s\S]|[^`\\])*`|[\p{L}\p{N}_$]+|<=|>=|<>|!=|\|\||::|=>|[^\s]/gu;

export function formatCalculatedExpression(expression: string): string {
  const query = formatDialect(`SELECT\n${expression}`, { dialect: trino, tabWidth: 2 });
  const formatted = query.replace(/^SELECT\n/, '').replace(/^ {2}/gm, '').trim();
  const before = expression.match(tokenPattern) ?? [];
  const after = formatted.match(tokenPattern) ?? [];
  if (before.length !== after.length || before.some((token, index) => token !== after[index])) {
    throw new Error('Formatting changed expression tokens');
  }
  return formatted;
}
