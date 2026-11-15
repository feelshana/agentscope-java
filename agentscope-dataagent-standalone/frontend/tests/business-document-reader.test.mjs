import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { documentHeadings, documentTextParts } from '../src/components/modeling/businessDocumentPresentation.ts';
import { BusinessDocumentArticle } from '../src/components/modeling/BusinessDocumentReader.tsx';

test('document headings keep source lines and ignore fenced code', () => {
  const source = '# 标题\r\n\r\n```md\r\n## 非目录\r\n```\r\n## **金额口径** ##\r\n~~~\r\n### 忽略\r\n~~~\r\n## C#';
  assert.deepEqual(documentHeadings(source), [
    { line: 1, level: 1, text: '标题' }, { line: 6, level: 2, text: '金额口径' }, { line: 10, level: 2, text: 'C#' },
  ]);
});
test('a longer opening fence is not ended by a shorter one', () => {
  assert.deepEqual(documentHeadings('````\n```\n## 忽略\n````\n## 显示'), [{ line: 5, level: 2, text: '显示' }]);
});
test('literal search preserves every source character, case and special characters', () => {
  for (const [source, query] of [['净销售额与销售额', '销售额'], ['SQL sql Sql', 'sql'], ['a[b] (b)', '[b]'], ['金额💰金额', '💰'], ['nothing', '?'], ['', 'x']]) {
    assert.equal(documentTextParts(source, query).map(part => part.text).join(''), source);
  }
  assert.equal(documentTextParts('SQL sql Sql', 'sql').filter(part => part.matched).length, 3);
  assert.deepEqual(documentTextParts('abc', ''), [{ text: 'abc', matched: false }]);
});
test('document Markdown uses real headings and tables without executing source HTML or loading images', () => {
  const content = '# 口径\n\n## 金额\n\n| 字段 | 说明 |\n| --- | --- |\n| amount | **销售额** |\n\n<script>alert(1)</script>\n\n![外部图](https://example.invalid/private.png)\n\n[危险链接](javascript:alert(1))';
  const html = renderToStaticMarkup(React.createElement(BusinessDocumentArticle, { content, query: '销售额', prefix: 'doc' }));
  assert.match(html, /id="doc-1"/);
  assert.match(html, /id="doc-3"/);
  assert.match(html, /<table>/);
  assert.match(html, /<mark>销售额<\/mark>/);
  assert.doesNotMatch(html, /<script|<img|href="javascript:/i);
});
