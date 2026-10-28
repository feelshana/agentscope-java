import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ToolCallBlock from '../src/components/ToolCallBlock.tsx';

test('Python nonzero exit is marked failed in the visible call trace', () => {
  const result = JSON.stringify('**描述：** 预期异常\n### exit_code: 1\n### stdout\n```\nValueError: expected\n```');
  const html = renderToStaticMarkup(React.createElement(ToolCallBlock, {
    finished: true, toolName: 'run_python', toolCallId: 'failed-call', result,
  }));
  assert.match(html, /da-toolcall-status failed/);
  assert.match(html, /失败/);
});

test('recovered Python success and missing result keep distinct visible states', () => {
  const render = result => renderToStaticMarkup(React.createElement(ToolCallBlock, {
    finished: true, toolName: 'run_python', toolCallId: 'recovered-call', result,
  }));
  assert.doesNotMatch(render(JSON.stringify('### exit_code: 0\n### stdout\n```\n6\n```')), /失败|结果未确认/);
  assert.match(render(undefined), /结果未确认/);
});
