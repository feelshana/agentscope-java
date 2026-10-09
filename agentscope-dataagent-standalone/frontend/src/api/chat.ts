import { getToken } from './auth';

export interface ChatRequest {
  message: string;
  sessionKey?: string;
  groupIds?: string[];
}

export interface HitlToolCall {
  id: string;
  name: string;
  input: Record<string, unknown>;
}

export interface ChatEvent {
  type: 'token' | 'tool_call' | 'tool_result' | 'hitl_request' | 'done' | 'error' | string;
  data?: string;
  toolName?: string;
  toolCallId?: string;
  toolInput?: string;
  toolResult?: string;
  requestId?: string;
  runId?: string;
  seq?: number;
  parentToolCallId?: string;
  error?: string;
  sessionKey?: string;
  replyId?: string;
  toolCalls?: HitlToolCall[];
}

export interface ModelingConfirmRequest {
  sessionKey: string;
  groupIds: string[];
  replyId: string;
  toolCallId: string;
  toolName: string;
  confirmed: boolean;
  toolInput?: Record<string, unknown>;
  feedback?: string;
}

export interface CurrentSession {
  sessionKey: string | null;
  exists: boolean;
  pendingReplyId?: string | null;
  pendingToolCalls?: HitlToolCall[];
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function currentSession(
  agentId: string,
  sessionKey?: string,
): Promise<CurrentSession> {
  const qs = sessionKey ? `?sessionKey=${encodeURIComponent(sessionKey)}` : '';
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/session${qs}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(`Failed to resolve current session: ${res.status}`);
  return res.json();
}

async function* readEventStream(res: Response, fallback: string): AsyncGenerator<ChatEvent> {
  if (!res.ok || !res.body) {
    let detail = fallback;
    try {
      const body = (await res.json()) as { message?: string };
      detail = body.message || detail;
    } catch {
      // Keep the operation-specific fallback when the error response is not JSON.
    }
    throw new Error(`${detail}: ${res.status}`);
  }
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const evt = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const lines = evt.split('\n');
      let data = '';
      for (const ln of lines) if (ln.startsWith('data:')) data += ln.slice(5).trim();
      if (!data) continue;
      try {
        yield JSON.parse(data) as ChatEvent;
      } catch {
        yield { type: 'token', data } as ChatEvent;
      }
    }
  }
}

export async function* stream(
  agentId: string,
  req: ChatRequest,
  signal?: AbortSignal,
): AsyncGenerator<ChatEvent> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(req),
    signal,
  });
  yield* readEventStream(res, 'Chat stream failed');
}

export async function* confirmModeling(
  agentId: string,
  req: ModelingConfirmRequest,
  signal?: AbortSignal,
): AsyncGenerator<ChatEvent> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(req),
    signal,
  });
  yield* readEventStream(res, 'Modeling confirmation failed');
}
