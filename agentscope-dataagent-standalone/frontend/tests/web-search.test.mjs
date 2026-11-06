import assert from 'node:assert/strict';
import test from 'node:test';
import { citedWebSources, explicitlyRequestsWebSearch } from '../src/utils/webSearch.ts';

test('web sources require a matching result and citation in the final answer', () => {
  const tools = [{
    name: 'web_search',
    result: '1. Public notice\n   网址：https://gov.example/notice(1)\n2. Unused\n   网址：https://gov.example/unused',
  }, { name: 'web_search', result: '联网搜索暂时不可用' }];
  const answer = '[Public notice](https://gov.example/notice(1)) [Other](https://gov.example/other)';
  assert.deepEqual(citedWebSources(answer, tools), [{
    title: 'Public notice', url: 'https://gov.example/notice(1)',
  }]);
  assert.deepEqual(citedWebSources('https://gov.example/notice(1)-another', tools), []);
  assert.deepEqual(citedWebSources(answer, [{ ...tools[0], name: 'run_sql' }]), []);
});

test('only explicit search requests offer the opt-in retry', () => {
  assert.equal(explicitlyRequestsWebSearch('帮我联网查一下最新公开政策'), true);
  assert.equal(explicitlyRequestsWebSearch('查一下网上的政策'), true);
  assert.equal(explicitlyRequestsWebSearch('查一下上月的报表访问量'), false);
  assert.equal(explicitlyRequestsWebSearch('不要联网搜索'), false);
  assert.equal(explicitlyRequestsWebSearch('你搜下今天的新闻'), true);
});
