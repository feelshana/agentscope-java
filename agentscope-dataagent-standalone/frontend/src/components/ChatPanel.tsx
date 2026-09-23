import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { stream } from '../api/chat';
import { TurnEntry, turns as fetchTurns } from '../api/sessions';
import ToolCallBlock, { ToolInspectPayload } from './ToolCallBlock';
import TaskTrace from './TaskTrace';
import ChartBlock from './ChartBlock';
import EChartsBlock, { ChartPayload } from './EChartsBlock';
import CitationPanel from './CitationPanel';
import EmptyIllustration from './EmptyIllustration';
import LandingExamples from './LandingExamples';
import Icon from './Icon';
import OntologyGraphView from './OntologyGraphView';
import type { OntologyGraphData } from '../api/ontology';
import Markdown from './Markdown';
import PythonArtifactsPanel from './PythonArtifactsPanel';
import { extractVegaSpec } from '../utils/charts';
import { listGroups, DatasetGroup } from '../api/datasets';
import { listOntologies, OntologyEntry } from '../api/ontologies';

type Role = 'user' | 'assistant' | 'system';

interface ToolEntry {
  id: string;
  name: string;
  callId?: string;
  parentCallId?: string;
  requestId?: string;
  runId?: string;
  callSeq?: number;
  resultSeq?: number;
  input?: string;
  result?: string;
}

/** One ordered step of the execution trace: a narration/thinking text or a tool call. */
type TraceNode =
  | { kind: 'text'; id: string; text: string }
  | { kind: 'tool'; id: string; tool: ToolEntry };

interface Message {
  id: string;
  role: Role;
  text: string;
  tools: ToolEntry[];
  trace: TraceNode[];
  pending?: boolean;
  /** True only when the turn was interrupted and produced no usable answer. */
  failed?: boolean;
  /** Timestamp (ms) when the assistant turn started streaming. */
  startedAtMs?: number;
  /** Elapsed time (ms) from start to done. */
  elapsedMs?: number;
}

interface SessionMsgs {
  messages: Message[];
  busy: boolean;
}

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, background: 'var(--da-app-bg)' },
  thread: { flex: 1, overflowY: 'auto', padding: '28px 36px', display: 'flex', flexDirection: 'column', gap: 18 },
  threadInner: {
    width: '100%', maxWidth: 960, margin: '0 auto',
    display: 'flex', flexDirection: 'column', gap: 18,
  },
  empty: { color: 'var(--da-text-muted)', fontSize: '0.95rem', textAlign: 'center', marginTop: 100 },
  bubble: {
    maxWidth: '78%', padding: '12px 16px', borderRadius: 10,
    fontSize: '0.95rem', lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
  },
  user: {
    alignSelf: 'flex-end',
    background: 'var(--da-primary)',
    color: '#fdfdff',
    boxShadow: '0 1px 2px rgba(16, 24, 40, 0.12)',
  },
  assistant: {
    alignSelf: 'flex-start', background: 'var(--da-surface)', color: 'var(--da-text)',
    border: '1px solid var(--da-border)',
    boxShadow: 'var(--da-shadow-card)',
  },
  system: {
    alignSelf: 'center', background: 'transparent', color: 'var(--da-text-muted)',
    fontSize: '0.85rem', fontStyle: 'italic',
  },
  composerWrap: {
    padding: '0 36px 20px',
    display: 'flex',
    justifyContent: 'center',
    flexShrink: 0,
  },
  textarea: {
    width: '100%',
    boxSizing: 'border-box',
    padding: '4px 2px',
    background: 'transparent',
    border: 'none',
    color: 'var(--da-text)',
    fontSize: 14,
    resize: 'none',
    minHeight: 84,
    maxHeight: 200,
    lineHeight: 1.6,
    outline: 'none',
    fontFamily: 'inherit',
  },
  composerBar: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
  },
  picker: {
    position: 'absolute',
    bottom: 'calc(100% + 6px)',
    left: 0,
    background: 'var(--da-surface)',
    border: '1px solid var(--da-border)',
    borderRadius: 'var(--da-radius-lg)',
    boxShadow: 'var(--da-shadow-pop)',
    padding: 6,
    minWidth: 220,
    maxHeight: 240,
    overflowY: 'auto',
    zIndex: 30,
    display: 'flex',
    flexDirection: 'column',
    gap: 2,
  },
  send: {
    width: 34,
    height: 34,
    borderRadius: 999,
    padding: 0,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    background: 'var(--da-primary)',
    color: '#ffffff',
    border: 'none',
    cursor: 'pointer',
    flexShrink: 0,
  },
  sendDisabled: { background: 'var(--da-surface-sunken)', color: 'var(--da-text-muted)', cursor: 'not-allowed' },
};

let counter = 0;
const nextId = () => `m${Date.now().toString(36)}-${counter++}`;

const STORAGE_PREFIX = 'claw_chat_session:';
const storageKey = (agentId: string) => `${STORAGE_PREFIX}${agentId}`;

/** Tool names that should never be shown in the UI (framework plumbing, not user-facing). */
const HIDDEN_TOOL_PATTERNS = ['load_skill_through_path'];

function isHiddenTool(name: string): boolean {
  const lower = name.toLowerCase();
  return HIDDEN_TOOL_PATTERNS.some(p => lower.includes(p.toLowerCase()));
}

/** Extract the server-built chart payload (chartId or inline option) from a render_chart result. */
function chartPayloadFromTool(t: ToolEntry): ChartPayload | null {
  const raw = t.result ?? t.input;
  if (!raw) return null;
  try {
    let v: unknown = JSON.parse(raw);
    if (typeof v === 'string') v = JSON.parse(v);
    const obj = v as ChartPayload;
    if (obj && obj.chart === 'echarts' && (obj.option || obj.chartId)) return obj;
  } catch {
    /* not a chart payload */
  }
  return null;
}

/** Extract ontology graph payload from show_ontology_graph tool result. */
function ontologyGraphPayload(t: ToolEntry): OntologyGraphData | null {
  const raw = t.result ?? t.input;
  if (!raw) return null;
  try {
    let v: unknown = JSON.parse(raw);
    if (typeof v === 'string') v = JSON.parse(v);
    const obj = v as any;
    if (obj && obj.type === 'ontology_graph' && obj.nodes) return obj as OntologyGraphData;
  } catch {
    /* not an ontology payload */
  }
  return null;
}

/** Splits a turn's ordered trace into gray trace rows and answer-area visuals (charts). */
function splitToolRender(
  m: Message,
  onInspect?: (t: ToolInspectPayload) => void,
): { trace: React.ReactNode[]; answer: React.ReactNode[] } {
  const trace: React.ReactNode[] = [];
  const answer: React.ReactNode[] = [];
  for (const node of m.trace) {
    if (node.kind === 'text') {
      trace.push(
        <div key={node.id} className="da-trace-text">{node.text}</div>,
      );
      continue;
    }
    const t = node.tool;
    const isChartTool = t.name.toLowerCase().includes('render_chart');
    const isOntologyTool = t.name.toLowerCase().includes('show_ontology_graph');
    const chartPayload = isChartTool ? chartPayloadFromTool(t) : null;
    const ontologyPayload = isOntologyTool ? ontologyGraphPayload(t) : null;
    const spec = !chartPayload && !ontologyPayload ? extractVegaSpec(t.name, t.input) : null;
    const key = node.id + (t.result ? '-done' : '');
    trace.push(
      <div key={key}>
        <ToolCallBlock
          toolName={t.name}
          toolCallId={t.id}
          input={t.input}
          result={t.result}
          onInspect={onInspect}
        />
      </div>,
    );
    if (isChartTool && !chartPayload && !spec) {
      trace.push(
        <div
          key={`${key}-warn`}
          style={{
            background: '#fffbeb', border: '1px solid #fcd34d', borderRadius: 6,
            padding: '0.4rem 0.7rem', color: '#92400e', fontSize: '0.78rem',
          }}
        >
          图表解析失败 — 点击工具行查看原始参数
        </div>,
      );
    }
    if (chartPayload) answer.push(<EChartsBlock key={`${key}-chart`} payload={chartPayload} />);
    if (ontologyPayload) {
      answer.push(
        <OntologyGraphView
          key={`${key}-onto`}
          data={ontologyPayload}
          highlight={(ontologyPayload as any).highlight}
        />,
      );
    }
    if (!chartPayload && !ontologyPayload && spec) answer.push(<ChartBlock key={`${key}-spec`} spec={spec} />);
  }
  return { trace, answer };
}

function turnsToMessages(turns: TurnEntry[]): Message[] {
  const out: Message[] = [];
  let cur: Message | null = null;
  for (const t of turns) {
    const role = String(t.role).toUpperCase();
    if (role === 'USER') {
      out.push({ id: t.id, role: 'user', text: t.content ?? '', tools: [], trace: [] });
      cur = null;
    } else if (role === 'TOOL') {
      if (t.toolName && isHiddenTool(t.toolName)) continue;
      if (t.toolName === 'run_python' && t.toolResult) {
        const isEvicted = t.toolResult.includes('Tool output was too large');
        console.log(`[turnsToMessages] loaded run_python result: len=${t.toolResult.length}, evicted=${isEvicted}, preview=[${t.toolResult.substring(0, 300)}]`);
      }
      if (!cur) {
        cur = { id: `${t.id}-host`, role: 'assistant', text: '', tools: [], trace: [] };
        out.push(cur);
      }
      const name = t.toolName ?? 'tool';
      if (t.toolResult != null) {
        // A tool_result turn: attach to the most recent same-name call still awaiting
        // a result, so tool_use + tool_result pairs render as ONE row.
        let idx = -1;
        for (let i = cur.tools.length - 1; i >= 0; i--) {
          if (cur.tools[i].name === name && cur.tools[i].result === undefined) { idx = i; break; }
        }
        if (idx >= 0) {
          const targetId = cur.tools[idx].id;
          const updated: ToolEntry = {
            ...cur.tools[idx],
            result: t.toolResult,
            input: cur.tools[idx].input ?? (t.toolInput ?? undefined),
          };
          cur.tools = cur.tools.map((e, i) => (i === idx ? updated : e));
          cur.trace = cur.trace.map(n => (n.kind === 'tool' && n.id === targetId ? { ...n, tool: updated } : n));
        } else {
          const entry: ToolEntry = { id: t.id, name, input: t.toolInput ?? undefined, result: t.toolResult };
          cur.tools = [...cur.tools, entry];
          cur.trace = [...cur.trace, { kind: 'tool', id: t.id, tool: entry }];
        }
      } else {
        // A tool_use turn: create the row; its result arrives on a later turn.
        const entry: ToolEntry = { id: t.id, name, input: t.toolInput ?? undefined };
        cur.tools = [...cur.tools, entry];
        cur.trace = [...cur.trace, { kind: 'tool', id: t.id, tool: entry }];
      }
    } else if (role === 'ASSISTANT') {
      if (!cur) {
        cur = { id: t.id, role: 'assistant', text: '', tools: [], trace: [] };
        out.push(cur);
      }
      // A previous assistant segment that is now superseded was pre-tool narration,
      // so keep it in the trace; only the last segment stays as the formal answer.
      if (cur.text) {
        cur.trace = [...cur.trace, { kind: 'text', id: `${cur.id}-n${cur.trace.length}`, text: cur.text }];
      }
      cur.id = t.id;
      cur.text = t.content ?? '';
    }
  }
  for (const m of out) {
    if (m.role === 'assistant' && (m.text.includes('[error]') || m.text.includes('[错误]'))) m.failed = true;
  }
  return out;
}

export interface ChatPanelProps {
  agentId: string;
  /** Called after each successful message turn so the sessions sidebar can refresh. */
  onSessionUpdate?: () => void;
  /** Reports the conversation title (first user question; '' when new). */
  onTitle?: (title: string) => void;
  /** Opens a tool call's input/result in the side inspector panel. */
  onInspect?: (t: ToolInspectPayload) => void;
  /** ID of the tool currently open in the inspector; ChatPanel syncs its latest result. */
  inspectorId?: string | null;
  /** Called with the resolved tool payload whenever inspectorId's tool updates. */
  onToolResolved?: (t: ToolInspectPayload | null) => void;
}

export default function ChatPanel({
  agentId,
  onSessionUpdate,
  onTitle,
  onInspect,
  inspectorId,
  onToolResolved,
}: ChatPanelProps) {
  const [searchParams, setSearchParams] = useSearchParams();
  const [sessionMsgs, setSessionMsgs] = useState<Map<string, SessionMsgs>>(new Map());
  const [input, setInput] = useState('');
  const [restoring, setRestoring] = useState(true);
  const curKey = searchParams.get('session');
  const messages = curKey ? (sessionMsgs.get(curKey)?.messages ?? []) : [];
  const busy = curKey ? (sessionMsgs.get(curKey)?.busy ?? false) : false;
  const isLanding = !restoring && !curKey && messages.length === 0;
  const [groups, setGroups] = useState<DatasetGroup[]>([]);
  const [selectedGroups, setSelectedGroups] = useState<string[]>(() => {
    const raw = searchParams.get('groups');
    return raw ? raw.split(',').filter(Boolean) : [];
  });
  const [groupPickerOpen, setGroupPickerOpen] = useState(false);
  const [ontologies, setOntologies] = useState<OntologyEntry[]>([]);
  const [selectedOntology, setSelectedOntology] = useState<string | null>(null);
  const [ontologyPickerOpen, setOntologyPickerOpen] = useState(false);
  /** Which picker is currently active (the other is frozen). Default: knowledge-base. */
  const [chatMode, setChatMode] = useState<'kb' | 'ontology'>('kb');
  const threadRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const groupPickerRef = useRef<HTMLDivElement | null>(null);
  const ontologyPickerRef = useRef<HTMLDivElement | null>(null);
  const abortRef = useRef<Map<string, AbortController>>(new Map());
  const onTitleRef = useRef(onTitle);
  onTitleRef.current = onTitle;

  /** Mutually-exclusive effective agent: ontology mode overrides knowledge-base mode. */
  const effectiveAgentId = selectedOntology ? `ontology-${selectedOntology}` : agentId;
  /** Whether each button is frozen (non-interactive for its own picker, but clickable to switch mode). */
  const kbFrozen = chatMode === 'ontology';
  const ontologyFrozen = chatMode === 'kb';

  useEffect(() => {
    if (!groupPickerOpen) return;
    const closePicker = (event: PointerEvent) => {
      if (!groupPickerRef.current?.contains(event.target as Node)) {
        setGroupPickerOpen(false);
      }
    };
    document.addEventListener('pointerdown', closePicker);
    return () => document.removeEventListener('pointerdown', closePicker);
  }, [groupPickerOpen]);

  useEffect(() => {
    if (!ontologyPickerOpen) return;
    const closePicker = (event: PointerEvent) => {
      if (!ontologyPickerRef.current?.contains(event.target as Node)) {
        setOntologyPickerOpen(false);
      }
    };
    document.addEventListener('pointerdown', closePicker);
    return () => document.removeEventListener('pointerdown', closePicker);
  }, [ontologyPickerOpen]);

  // Mutual exclusivity: switching mode clears the other side's selection.
  useEffect(() => {
    if (chatMode === 'ontology') { setSelectedGroups([]); setGroupPickerOpen(false); }
  }, [chatMode]);
  useEffect(() => {
    if (chatMode === 'kb') { setSelectedOntology(null); setOntologyPickerOpen(false); }
  }, [chatMode]);

  useEffect(() => {
    if (!inspectorId || !onToolResolved) return;
    for (const m of messages) {
      for (const t of m.tools) {
        if (t.id === inspectorId) {
          onToolResolved({ id: t.id, name: t.name, input: t.input, result: t.result });
          return;
        }
      }
    }
  }, [messages, inspectorId, onToolResolved]);

  useEffect(() => {
    let cancelled = false;
    listGroups()
      .then(g => { if (!cancelled) setGroups(g); })
      .catch(() => undefined);
    listOntologies()
      .then(list => { if (!cancelled) setOntologies(list); })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, []);

  const persistSession = useCallback((key: string | null) => {
    if (key) {
      try { localStorage.setItem(storageKey(effectiveAgentId), key); } catch { /* ignore quota */ }
    } else {
      try { localStorage.removeItem(storageKey(effectiveAgentId)); } catch { /* ignore */ }
    }
  }, [effectiveAgentId]);

  // On agent or URL session change: switch display to the target session.
  // If we already have messages cached (e.g., from background streaming), show them immediately.
  // Otherwise fetch from backend. Streaming in other sessions continues undisturbed.
  const urlSession = searchParams.get('session');
  useEffect(() => {
    let cancelled = false;
    setInput('');

    // No URL session → landing page. Don't auto-restore from localStorage;
    // a session is only created when the user sends the first message.
    if (!urlSession) {
      setRestoring(false);
      return;
    }

    async function run() {
      const key = urlSession!;
      if (cancelled) return;

      if (key && sessionMsgs.has(key)) {
        setRestoring(false);
        return;
      }

      setRestoring(true);
      try {
        const list = await fetchTurns(effectiveAgentId, key);
        if (cancelled) return;
        setSessionMsgs(prev => {
          const next = new Map(prev);
          next.set(key!, { messages: turnsToMessages(list), busy: false });
          return next;
        });
      } catch {
        // missing/empty session is fine
      }
      if (!cancelled) setRestoring(false);
    }
    run();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [effectiveAgentId, urlSession]);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight });
  }, [messages]);

  useEffect(() => {
    if (isLanding) inputRef.current?.focus();
  }, [isLanding]);

  useEffect(() => {
    const firstUser = messages.find(m => m.role === 'user');
    onTitleRef.current?.(firstUser ? firstUser.text.replace(/\s+/g, ' ').trim().slice(0, 40) : '');
  }, [messages]);

  useEffect(() => {
    return () => {
      abortRef.current.forEach(ac => ac.abort());
    };
  }, []);

  const canSend = useMemo(
    () => !busy && !restoring && !!effectiveAgentId && input.trim().length > 0,
    [busy, restoring, effectiveAgentId, input],
  );

  async function handleSend() {
    if (!canSend) return;
    const text = input.trim();
    setInput('');
    const nowMs = Date.now();
    const userMsg: Message = { id: nextId(), role: 'user', text, tools: [], trace: [] };
    const replyMsg: Message = { id: nextId(), role: 'assistant', text: '', tools: [], trace: [], pending: true, startedAtMs: nowMs };

    // Use the URL conversationId as the Map key — stable across session switches.
    let convId = urlSession;
    if (!convId) {
      convId = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : `conv-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
      const next = new URLSearchParams(searchParams);
      next.set('session', convId);
      setSearchParams(next, { replace: true });
    }

    const ac = new AbortController();
    abortRef.current.set(convId, ac);
    setSessionMsgs(prev => {
      const next = new Map(prev);
      const cur = next.get(convId) ?? { messages: [], busy: false };
      next.set(convId, { messages: [...cur.messages, userMsg, replyMsg], busy: true });
      return next;
    });
    let lastToolEventSeq = -1;

    // Bump sidebar after a short delay so the new session entry is picked up while streaming.
    // The backend creates the session synchronously before streaming starts, so 500ms is safe.
    const bumpTimer = setTimeout(() => onSessionUpdate?.(), 500);

    try {
      for await (const evt of stream(effectiveAgentId, {
        message: text,
        sessionKey: convId,
        groupIds: selectedGroups.length ? selectedGroups : undefined,
      }, ac.signal)) {
        const isToolEvent = evt.type === 'tool_call' || evt.type === 'tool_result';
        if (isToolEvent) {
          const seq = evt.seq;
          if (typeof seq !== 'number' || !Number.isSafeInteger(seq) || seq <= lastToolEventSeq) continue;
          lastToolEventSeq = seq;
        }
        if (evt.type === 'token') {
          const chunk = evt.data ?? '';
          setSessionMsgs(prev => {
            const next = new Map(prev);
            const cur = next.get(convId);
            if (!cur) return prev;
            next.set(convId, { ...cur, messages: cur.messages.map(m => m.id === replyMsg.id ? { ...m, text: m.text + chunk } : m) });
            return next;
          });
        } else if (evt.type === 'tool_call') {
          // Skip framework-internal tools that should not appear in the UI.
          if (evt.toolName && isHiddenTool(evt.toolName)) continue;
          const entry: ToolEntry = {
            id: `${evt.toolName ?? 'tool'}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
            name: evt.toolName ?? 'tool',
            callId: evt.toolCallId,
            parentCallId: evt.parentToolCallId,
            requestId: evt.requestId,
            runId: evt.runId,
            callSeq: evt.seq,
            input: evt.toolInput,
          };
          // Text streamed before a tool call is pre-tool narration: move it into the
          // trace so the in-progress view shows the reasoning, not a half answer.
          setSessionMsgs(prev => {
            const next = new Map(prev);
            const cur = next.get(convId);
            if (!cur) return prev;
            next.set(convId, {
              ...cur,
              messages: cur.messages.map(m => {
                if (m.id !== replyMsg.id) return m;
                const trace = m.text.trim()
                  ? [...m.trace, { kind: 'text' as const, id: `${m.id}-n${m.trace.length}`, text: m.text }]
                  : [...m.trace];
                return { ...m, text: '', trace: [...trace, { kind: 'tool' as const, id: entry.id, tool: entry }], tools: [...m.tools, entry] };
              }),
            });
            return next;
          });
        } else if (evt.type === 'tool_result') {
          // Skip results for hidden tools.
          if (evt.toolName && isHiddenTool(evt.toolName)) continue;
          setSessionMsgs(prev => {
            const next = new Map(prev);
            const cur = next.get(convId);
            if (!cur) return prev;
            next.set(convId, {
              ...cur,
              messages: cur.messages.map(m => {
                if (m.id !== replyMsg.id) return m;
                const tools = [...m.tools];
                let matchedId: string | null = null;
                if (evt.toolCallId) {
                  for (let i = tools.length - 1; i >= 0; i--) {
                    if (tools[i].callId === evt.toolCallId && tools[i].result === undefined) {
                      tools[i] = {
                        ...tools[i],
                        requestId: evt.requestId,
                        runId: evt.runId,
                        result: evt.toolResult,
                        resultSeq: evt.seq,
                      };
                      matchedId = tools[i].id;
                      break;
                    }
                  }
                }
                if (matchedId) {
                  const matchedTool = tools.find(t => t.id === matchedId);
                  const trace = m.trace.map(n =>
                    n.kind === 'tool' && n.id === matchedId && matchedTool ? { ...n, tool: matchedTool } : n,
                  );
                  return { ...m, tools, trace };
                }
                const unmatched: ToolEntry = {
                  id: `${evt.toolName ?? 'tool'}-result-${evt.seq}`,
                  name: evt.toolName ?? 'tool',
                  callId: evt.toolCallId,
                  parentCallId: evt.parentToolCallId,
                  requestId: evt.requestId,
                  runId: evt.runId,
                  resultSeq: evt.seq,
                  result: evt.toolResult,
                };
                return {
                  ...m,
                  tools: [...tools, unmatched],
                  trace: [...m.trace, { kind: 'tool' as const, id: unmatched.id, tool: unmatched }],
                };
              }),
            });
            return next;
          });
        } else if (evt.type === 'done') {
          persistSession(convId);
          setSessionMsgs(prev => {
            const next = new Map(prev);
            const cur = next.get(convId);
            if (!cur) return prev;
            next.set(convId, {
              ...cur,
              messages: cur.messages.map(m => m.id === replyMsg.id
                ? { ...m, pending: false, elapsedMs: m.startedAtMs ? Date.now() - m.startedAtMs : undefined }
                : m),
              busy: false,
            });
            return next;
          });
        } else if (evt.type === 'error') {
          setSessionMsgs(prev => {
            const next = new Map(prev);
            const cur = next.get(convId);
            if (!cur) return prev;
            next.set(convId, {
              ...cur,
              messages: cur.messages.map(m => m.id === replyMsg.id
                ? { ...m, pending: false, failed: true, text: m.text + (m.text ? '\n' : '') + `[错误] ${evt.error ?? '未知错误'}`, elapsedMs: m.startedAtMs ? Date.now() - m.startedAtMs : undefined }
                : m),
              busy: false,
            });
            return next;
          });
        }
      }
      onSessionUpdate?.();
    } catch (e: unknown) {
      // Don't show error message when the stream was intentionally aborted (e.g., user clicked stop)
      const isAbort = e instanceof DOMException && e.name === 'AbortError';
      if (!isAbort) {
        const msg = e instanceof Error ? e.message : '连接中断';
        setSessionMsgs(prev => {
          const next = new Map(prev);
          const cur = next.get(convId);
          if (!cur) return prev;
          next.set(convId, {
            ...cur,
            messages: cur.messages.map(m => m.id === replyMsg.id
              ? { ...m, pending: false, failed: true, text: m.text + (m.text ? '\n' : '') + `[错误] ${msg}`, elapsedMs: m.startedAtMs ? Date.now() - m.startedAtMs : undefined }
              : m),
            busy: false,
          });
          return next;
        });
      }
    } finally {
      clearTimeout(bumpTimer);
      abortRef.current.delete(convId);
      inputRef.current?.focus();
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  const composerContent = (
    <>
      <textarea
        ref={inputRef}
        style={{ ...S.textarea, ...(isLanding ? { minHeight: 120, fontSize: 15 } : {}) }}
        value={input}
        onChange={e => setInput(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={restoring ? '加载会话…' : '输入查询、分析、预测数据问题…'}
        disabled={restoring}
      />
      <div style={S.composerBar}>
        <div ref={groupPickerRef} style={{ position: 'relative' }}>
          <button
            type="button"
            className={`da-chip${!kbFrozen && (selectedGroups.length || selectedOntology) ? ' da-chip-active' : ''}${kbFrozen ? ' da-chip-frozen' : ''}`}
            onClick={() => kbFrozen ? setChatMode('kb') : setGroupPickerOpen(open => !open)}
            title={kbFrozen ? '点击切换回知识库模式' : '选择知识库范围'}
          >
            <Icon name="book" size="sm" />{' '}
            {selectedOntology
              ? '知识库'
              : selectedGroups.length
                ? groups.filter(g => selectedGroups.includes(g.id)).map(g => g.name).join('、')
                : '知识库（全部）'}{' '}
            ▾
          </button>
          {groupPickerOpen && (
            <div style={S.picker}>
              <label className="da-navitem">
                <input
                  type="checkbox"
                  checked={selectedGroups.length === 0}
                  onChange={() => {
                    setSelectedGroups([]);
                    setGroupPickerOpen(false);
                  }}
                />
                全部知识库（不限定）
              </label>
              {groups.map(g => (
                <label key={g.id} className="da-navitem">
                  <input
                    type="checkbox"
                    checked={selectedGroups.includes(g.id)}
                    onChange={() => {
                      setSelectedGroups(prev =>
                        prev.includes(g.id) ? prev.filter(x => x !== g.id) : [...prev, g.id],
                      );
                      setGroupPickerOpen(false);
                    }}
                  />
                  {g.name}
                </label>
              ))}
              {groups.length === 0 && (
                <div className="da-small" style={{ padding: 6 }}>
                  暂无知识库
                </div>
              )}
            </div>
          )}
        </div>
        <div ref={ontologyPickerRef} style={{ position: 'relative' }}>
          <button
            type="button"
            className={`da-chip${!ontologyFrozen && selectedOntology ? ' da-chip-active' : ''}${ontologyFrozen ? ' da-chip-frozen' : ''}`}
            onClick={() => ontologyFrozen ? setChatMode('ontology') : setOntologyPickerOpen(open => !open)}
            title={ontologyFrozen ? '点击切换至本体模式' : (ontologies.length ? '选择本体进行数据查询' : '暂无可用本体')}
          >
            <Icon name="graph" size="sm" />{' '}
            {selectedOntology
              ? ontologies.find(o => o.id === selectedOntology)?.name ?? '本体'
              : ontologies.length ? '本体' : '本体（暂无服务）'}{' '}
            {ontologies.length ? '▾' : ''}
          </button>
          {ontologyPickerOpen && (
            <div style={S.picker}>
              {ontologies.map(o => (
                <button
                  key={o.id}
                  className="da-navitem"
                  onClick={() => {
                    setSelectedOntology(o.id);
                    setSelectedGroups([]);
                    setOntologyPickerOpen(false);
                  }}
                >
                  <Icon name="graph" size="sm" /> {o.name}
                </button>
              ))}
              {ontologies.length === 0 && (
                <div className="da-small" style={{ padding: 6 }}>
                  暂无可用本体
                </div>
              )}
            </div>
          )}
        </div>
        <button type="button" className="da-chip" disabled title="对话级模型切换暂未接入">
          大模型（自动）
        </button>
        <span style={{ flex: 1 }} />
        <button
          style={{
            ...S.send,
            ...(busy
              ? { background: 'var(--da-danger)', cursor: 'pointer' }
              : canSend ? {} : S.sendDisabled),
          }}
          onClick={busy && curKey ? () => abortRef.current.get(curKey)?.abort() : handleSend}
          disabled={!busy && !canSend}
          title={busy ? '停止回答' : '发送'}
        >
          {busy ? <Icon name="stop" size="sm" /> : <Icon name="send" size="sm" />}
        </button>
      </div>
    </>
  );

  if (isLanding) {
    return (
      <div style={S.root}>
        <div className="da-landing-wrap">
          <div className="da-landing-greeting">
            <h1 className="da-landing-title">红海DataAgent</h1>
            <div className="da-landing-tagline">你的大数据智囊团</div>
            <p>输入数据问题，我来帮你查询、分析和可视化</p>
          </div>
          <div className="da-composer-glow">
            <div className="da-composer-shell da-composer">
              {composerContent}
            </div>
          </div>
          <LandingExamples />
        </div>
      </div>
    );
  }

  return (
    <div style={S.root}>
      <div style={S.thread} ref={threadRef}>
        <div style={S.threadInner}>
        {restoring && messages.length === 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14, maxWidth: '70%' }}>
            <div className="da-skeleton da-skeleton-block" style={{ width: '45%' }} />
            <div className="da-skeleton da-skeleton-block" style={{ width: '80%', alignSelf: 'flex-end' }} />
            <div className="da-skeleton da-skeleton-block" style={{ width: '65%' }} />
          </div>
        )}
        {!restoring && messages.length === 0 && curKey && (
          <EmptyIllustration
            variant="doc"
            caption="开始一段新对话；输入 /reset 可清空当前会话"
          />
        )}
        {messages.map(m => {
          const { trace, answer } = splitToolRender(m, onInspect);
          return (
            <div
              key={m.id}
              className={`da-bubble-row ${m.role === 'user' ? 'user' : 'agent'} da-enter`}
            >
              {m.role === 'user' ? (
                <div className="da-bubble user">{m.text}</div>
              ) : (
                <div className="da-agent-turn">
                  <div className="da-agent-header">
                    <span className="da-agent-avatar">DA</span>
                    <span className="da-agent-name">红海DataAgent</span>
                  </div>
                  <TaskTrace
                    running={!!m.pending}
                    active={trace.length > 0}
                    hasError={!!m.failed}
                    hadToolErrors={m.tools.some(t => {
                      if (!t.result) return false;
                      if (t.name === 'query_structured_data') {
                        try { return ['FAILED', 'PARTIAL'].includes(JSON.parse(t.result)?.status); } catch { return false; }
                      }
                      return t.result.startsWith('error:');
                    })}
                    elapsedMs={m.elapsedMs}
                  >
                    {trace}
                  </TaskTrace>
                  <div className="da-answer">
                    {m.text
                      ? <Markdown>{m.text}</Markdown>
                      : m.pending
                        ? <div className="da-typing"><span /><span /><span /></div>
                        : null}
                    {answer}
                    {!m.pending && m.tools.some(t => t.name === 'run_python' && t.result) && (
                      <PythonArtifactsPanel tools={m.tools} onInspect={onInspect} />
                    )}
                    {m.tools.length > 0 && (
                      <CitationPanel tools={m.tools} />
                    )}
                  </div>
                </div>
              )}
            </div>
          );
        })}
        </div>
      </div>
      <div style={S.composerWrap} className="da-composerwrap">
        <div className="da-composer-glow">
          <div className="da-composer-shell da-composer">
            {composerContent}
          </div>
        </div>
      </div>
    </div>
  );
}
