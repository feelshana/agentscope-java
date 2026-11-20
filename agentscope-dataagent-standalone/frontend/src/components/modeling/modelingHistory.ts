import type { HitlToolCall } from '../../api/chat';
import type { TurnEntry } from '../../api/sessions';
import type { ConfirmationDecision } from '../../api/draftReview';

const CONFIRMATION_PROMPT = '我确定了以下口径，请整理后写入草稿：指标定义、默认过滤、术语写入 knowledge/rules/，'
  + '字段含义写入对应模型的列描述，确定建立的关系写入 relationships.yml；不要再次向我确认这些内容，完成后用一两行说明更新了哪些口径。\n';

export function confirmationRequest(decisions: ConfirmationDecision[]): string {
  return CONFIRMATION_PROMPT + decisions.map((d, i) => `${i + 1}. 问题：${d.question}\n   我的确定：${d.answer}`).join('\n');
}

export function confirmationReceipt(text: string): ConfirmationDecision[] | undefined {
  if (!text.startsWith(CONFIRMATION_PROMPT)) return undefined;
  const rows = text.slice(CONFIRMATION_PROMPT.length).split(/(?:^|\n)\d+\. 问题：/).slice(1);
  const decisions = rows.map(row => {
    const separator = '\n   我的确定：';
    const split = row.indexOf(separator);
    return { question: row.slice(0, split).trim(), answer: split < 0 ? '' : row.slice(split + separator.length).trim() };
  });
  return decisions.length && decisions.every(d => d.question && d.answer) ? decisions : undefined;
}

export interface ToolFrame {
  id: string;
  name: string;
  callId?: string;
  input?: string;
  result?: string;
  state: 'running' | 'waiting' | 'done' | 'failed' | 'rejected' | 'unrecorded';
}

export type Segment = { kind: 'text'; text: string } | { kind: 'tool'; frame: ToolFrame };

export interface PendingHitl {
  replyId: string;
  toolCalls: HitlToolCall[];
}

export interface PanelMessage {
  id: string;
  role: 'user' | 'assistant';
  segments: Segment[];
  pending?: boolean;
  hitl?: PendingHitl;
  startedAt?: string;
  confirmation?: ConfirmationDecision[];
}

export function restoreModelingHistory(entries: TurnEntry[], hitl?: PendingHitl): PanelMessage[] {
  const messages: PanelMessage[] = [];
  let user: TurnEntry | undefined;
  let reply: PanelMessage | undefined;
  let calls: ToolFrame[] = [];

  for (const entry of entries) {
    if (entry.role === 'USER') {
      user = entry;
      reply = undefined;
      calls = [];
      const content = entry.content ?? '';
      if (content.trim()) messages.push({ id: entry.id, role: 'user', confirmation: confirmationReceipt(content), segments: [{ kind: 'text',
        text: content.startsWith('请加载官方 Wren 技能，按官方 Auto-pilot') ? '开始自动建模' : content }] });
      continue;
    }
    const narration = entry.role === 'ASSISTANT' ? entry.content : null;
    const tool = entry.role === 'TOOL' ? entry.toolName : null;
    if (!narration?.trim() && !tool) continue;
    if (!reply) {
      reply = { id: entry.id, role: 'assistant', segments: [] };
      messages.push(reply);
    }
    if (narration?.trim()) reply.segments.push({ kind: 'text', text: narration });
    if (!tool) continue;

    const result = entry.toolResult ?? undefined;
    if (result !== undefined) {
      const call = [...calls].reverse().find(frame => frame.state === 'unrecorded' &&
        (entry.toolCallId && frame.callId ? frame.callId === entry.toolCallId : frame.name === tool));
      if (call) {
        call.result = result;
        call.state = result.startsWith('error') ? 'failed' : 'done';
        continue;
      }
    }
    const frame: ToolFrame = {
      id: entry.id, name: tool, callId: entry.toolCallId ?? undefined, input: entry.toolInput ?? undefined, result,
      state: result === undefined ? 'unrecorded' : result.startsWith('error') ? 'failed' : 'done',
    };
    reply.segments.push({ kind: 'tool', frame });
    calls.push(frame);
  }

  if (hitl) {
    if (!reply) {
      reply = { id: `restored-${hitl.replyId}`, role: 'assistant', segments: [] };
      messages.push(reply);
    }
    reply.hitl = hitl;
    const waiting = new Set(hitl.toolCalls.map(call => call.id));
    for (const frame of calls) {
      if (frame.callId && waiting.has(frame.callId) && frame.state === 'unrecorded') frame.state = 'waiting';
    }
  }
  // The draft endpoint filters by a lower time bound only. It can restore the latest turn's
  // unpublished changes, but cannot reliably attribute newer writes to older replies.
  if (reply && user && user.timestampMs > 0) reply.startedAt = new Date(user.timestampMs).toISOString();
  return messages;
}
