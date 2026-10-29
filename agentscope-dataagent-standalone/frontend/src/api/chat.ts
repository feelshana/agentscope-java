import { apiFetch, getToken } from './auth';

export interface ChatRequest {
  message: string;
  sessionKey?: string;
  groupIds?: string[];
  allGroups?: boolean;
  /** Modeling only: why the turn runs; labels draft changes and gates capabilities (spec092). */
  purpose?: TurnPurpose;
  /** Query only (spec097): the analysis method chosen for this turn; omitted for a plain query. */
  analysis?: AnalysisMethod;
  /** Query only: the parameters the user confirmed in the card; absent while proposing them. */
  analysisParams?: Record<string, unknown>;
}

export type AnalysisMethod = 'attribution' | 'anomaly' | 'forecast';

export const ANALYSIS_NAMES: Record<AnalysisMethod, string> = {
  attribution: '指标归因', anomaly: '异常检测', forecast: '趋势预测',
};

export interface AnalysisCapabilities {
  hasTimeField: boolean;
  methods: AnalysisMethod[];
  /** Why methods are unavailable; null when all can run. */
  reason: string | null;
}

/** spec098: the knowledge base's recommended question; null until generated or on failure. */
export async function recommendedQuestion(groupId: string, signal?: AbortSignal): Promise<string | null> {
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/recommended-question`, {
    headers: authHeaders(), signal,
  });
  if (!res.ok) return null;
  const body = (await res.json()) as { question?: string | null };
  return body.question?.trim() || null;
}

/** spec097: which analysis methods a knowledge base supports (greys out the others). */
export async function analysisCapabilities(groupId: string, signal?: AbortSignal): Promise<AnalysisCapabilities> {
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/analysis-capabilities`, {
    headers: authHeaders(), signal,
  });
  if (!res.ok) throw new Error(`读取分析能力失败：${res.status}`);
  return res.json();
}

/** 'question': user-question SQL (may query external tables); 'auto': automatic modeling
 *  (no business rules without a document); 'document': reconcile changed business documents. */
export type TurnPurpose = 'question' | 'auto' | 'document' | 'confirm';

export interface KnowledgeScope {
  groupIds: string[];
  allGroups: boolean;
  groupNames: Record<string, string>;
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
  answerId?: string;
  groupIds?: string[];
  allGroups?: boolean;
  knowledgeScope?: KnowledgeScope;
  state?: 'queued' | 'running';
  message?: string;
  toolCalls?: HitlToolCall[];
}

export interface ModelingConfirmRequest {
  confirmBatch?: boolean;
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
  allGroups?: boolean;
  groupIds?: string[] | null;
  sessionKey: string | null;
  exists: boolean;
  pendingReplyId?: string | null;
  pendingToolCalls?: HitlToolCall[];
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export interface AnswerFeedbackResult { vote: 'UP' | 'DOWN' | null; savedPairs: number; message: string; memoryStatus: 'NONE' | 'SAVED' | 'FAILED' }

export async function answerFeedback(agentId: string, session: string, answerId: string,
  vote?: 'UP' | 'DOWN'): Promise<AnswerFeedbackResult> {
  const url = `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(session)}/answers/${encodeURIComponent(answerId)}/feedback`;
  const res = await apiFetch(url, { method: vote ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: vote ? JSON.stringify({ vote }) : undefined });
  if (!res.ok) throw new Error('反馈未保存，请重试');
  return res.json();
}

export async function currentSession(
  agentId: string,
  sessionKey?: string,
  signal?: AbortSignal,
): Promise<CurrentSession> {
  const qs = sessionKey ? `?sessionKey=${encodeURIComponent(sessionKey)}` : '';
  const res = await apiFetch(`/api/agents/${encodeURIComponent(agentId)}/chat/session${qs}`, {
    headers: authHeaders(),
    signal,
  });
  if (!res.ok) throw new Error(`Failed to resolve current session: ${res.status}`);
  return res.json();
}

export async function* readEventStream(res: Response, fallback: string): AsyncGenerator<ChatEvent> {
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
  let terminal = false;
  function parse(frame: string): ChatEvent | null {
    const data = frame.split(/\r?\n/).filter(line => line.startsWith('data:'))
      .map(line => line.slice(5).replace(/^ /, '')).join('\n');
    if (!data) return null;
    try { return JSON.parse(data) as ChatEvent; }
    catch { throw new Error('回答数据格式错误，请重新加载会话'); }
  }
  try {
    while (true) {
      const { value, done } = await reader.read();
      buf += done ? dec.decode() : dec.decode(value, { stream: true });
      let boundary: RegExpExecArray | null;
      while ((boundary = /\r?\n\r?\n/.exec(buf)) !== null) {
        const frame = buf.slice(0, boundary.index);
        buf = buf.slice(boundary.index + boundary[0].length);
        const event = parse(frame);
        if (event) {
          terminal ||= event.type === 'done' || event.type === 'error';
          yield event;
        }
      }
      if (done) {
        if (buf.trim()) {
          const event = parse(buf);
          if (event) {
            terminal ||= event.type === 'done' || event.type === 'error';
            yield event;
          }
        }
        if (!terminal) throw new Error('连接已中断，回答可能尚未完成，请重新加载会话');
        break;
      }
    }
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

export async function* stream(
  agentId: string,
  req: ChatRequest,
  signal?: AbortSignal,
): AsyncGenerator<ChatEvent> {
  const res = await apiFetch(`/api/agents/${encodeURIComponent(agentId)}/chat/stream`, {
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
  const res = await apiFetch(`/api/agents/${encodeURIComponent(agentId)}/chat/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(req),
    signal,
  });
  yield* readEventStream(res, 'Modeling confirmation failed');
}
