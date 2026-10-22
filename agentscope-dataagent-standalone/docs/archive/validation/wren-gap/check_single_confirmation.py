"""Probe one native confirmation in a dedicated localhost fixture group."""
import json, uuid
from pathlib import Path
import live_validation as live
client = live.Client('bob')
group = client.request('POST', '/api/dataset-groups', {'name': 'confirm-probe-' + uuid.uuid4().hex[:8], 'description': '独立单次确认验证'})['id']
session = 'confirm-probe-' + uuid.uuid4().hex
message = '这是独立验证知识库。仅调用 write_file 在 knowledge/rules/confirmation_probe.md 保存正文「测试说明：金额单位为元」，reason 为「保存单次确认测试规则」。不要新增问题、表、视图或关系，写入后停止。'
events = client.stream('/api/agents/modeling-agent/chat/stream', {'message': message, 'sessionKey': session, 'groupIds': [group]}, 'single-confirm-before')
hitl = next(e for e in reversed(events) if e.get('type') == 'hitl_request')
call = hitl['toolCalls'][0]
assert call['name'] == 'write_file' and call['input']['path'] == 'knowledge/rules/confirmation_probe.md'
resumed = client.stream('/api/agents/modeling-agent/chat/confirm', {'sessionKey': session, 'groupIds': [group], 'replyId': hitl['replyId'], 'toolCallId': call['id'], 'toolName': call['name'], 'confirmed': True, 'toolInput': call['input']}, 'single-confirm-after')
result = {'groupId': group, 'callId': call['id'], 'before': events, 'after': resumed, 'sameCallAskedAgain': any(e.get('type') == 'hitl_request' and any(t['id'] == call['id'] for t in e.get('toolCalls', [])) for e in resumed)}
Path(__file__).with_name('single-confirm-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'groupId': group, 'sameCallAskedAgain': result['sameCallAskedAgain']}), flush=True)
