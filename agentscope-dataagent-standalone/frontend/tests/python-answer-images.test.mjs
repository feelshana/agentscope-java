import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import Markdown from '../src/components/Markdown.tsx';
import { pythonImageResolver } from '../src/components/PythonCodeBlock.tsx';
import { fileUrl } from '../src/api/artifacts.ts';

const imageName = '每日销售额趋势.png';
const imageUrl = '/api/artifacts/91a3378a-f591-4f39-81e9-86b4e4a92f50/content';
const otherUrl = '/api/artifacts/74bee28e-e206-40bf-8f5e-936a29e389b2/content';
const origin = 'https://dataagent.test';
const receipt = (path, name = 'run_python') => ({
  name,
  result: `### artifact:${imageName}\n- type: image\n- size: 144280\n- path: ${path}\n`,
});
globalThis.window = { location: new URL(origin) };

test('answer Markdown resolves a Python output filename to the protected preview', () => {
  const resolveImage = pythonImageResolver([receipt(imageUrl)]);
  assert.equal(resolveImage(`./outputs/${encodeURIComponent(imageName)}`), imageUrl);
  const markdown = `![每日销售额趋势](outputs/${imageName})`;
  const markup = renderToStaticMarkup(React.createElement(Markdown, { resolveImage }, markdown));
  assert.match(markup, /图片加载中/);
  assert.doesNotMatch(markup, /<img[^>]+src=/);
});

test('filename matching requires a Python result and does not guess between different images', () => {
  assert.equal(pythonImageResolver([receipt(imageUrl, 'run_sql')])(imageName), null);
  const resolveImage = pythonImageResolver([receipt(imageUrl), receipt(otherUrl)]);
  assert.equal(resolveImage(`outputs/${imageName}`), null);
  const unique = pythonImageResolver([receipt(imageUrl), receipt(imageUrl)]);
  assert.equal(unique(imageName), imageUrl);
  assert.equal(unique(`https://outside.test/outputs/${imageName}`), null);
  assert.equal(unique(`../outputs/${imageName}`), null);
});

test('protected image URLs accept the current deployment prefix and strip credentials', () => {
  const prefix = (process.env.DATAAGENT_TEST_BASE_PATH ?? '/').replace(/\/$/, '');
  assert.equal(fileUrl(`${prefix}${imageUrl}?token=example`), imageUrl);
  assert.equal(fileUrl(imageUrl), imageUrl);
  assert.equal(fileUrl(`//outside.test${imageUrl}`), null);
  assert.equal(fileUrl(`/different-prefix${imageUrl}`), null);
});
