import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useSearchParams } from 'react-router-dom';
import { currentSession, stream } from '../api/chat';
import type { KnowledgeScope } from '../api/chat';
import { TurnEntry, messagePage } from '../api/sessions';
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
import AnswerFeedback from './AnswerFeedback';
import { extractVegaSpec } from '../utils/charts';
import { listGroupPage, listGroupCards, DatasetGroup, GroupPage } from '../api/datasets';
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
  knowledgeScope?: KnowledgeScope;
  id: string;
  answerId?: string;
  role: Role;
  text: string;
  tools: ToolEntry[];
  trace: TraceNode[];
  pending?: boolean;
  capacityMessage?: string;
  /** True only when the turn was interrupted and produced no usable answer. */
  failed?: boolean;
  interrupted?: boolean;
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
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, background: 'var(--da-canvas-bg)' },
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
    minHeight: 48,
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
      <div key={key} style={t.parentCallId ? { marginLeft: 16, paddingLeft: 8, borderLeft: '2px solid var(--da-border)' } : undefined}>
        <ToolCallBlock
          toolName={t.name}
          toolCallId={t.id}
          input={t.input}
          result={t.result}
          finished={!m.pending}
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
      out.push({ id: t.id, role: 'user', text: t.content ?? '', tools: [], trace: [], knowledgeScope: t.knowledgeScope });
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
      cur.answerId = t.id;
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
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [olderLoading, setOlderLoading] = useState(false);
  const [historyCursors, setHistoryCursors] = useState<Record<string, number | null>>({});
  const historyTurns = useRef(new Map<string, TurnEntry[]>());
  const olderRequest = useRef(false);
  const preserveScroll = useRef<{ height: number; top: number } | null>(null);
  const curKey = searchParams.get('session');
  const messages = curKey ? (sessionMsgs.get(curKey)?.messages ?? []) : [];
  const activeHistoryKey = useRef(curKey);
  activeHistoryKey.current = curKey;
  const busy = curKey ? (sessionMsgs.get(curKey)?.busy ?? false) : false;
  const isLanding = !restoring && !curKey && messages.length === 0;
  const [groups, setGroups] = useState<DatasetGroup[]>([]);
  const [groupPage, setGroupPage] = useState(0);
  const [groupTotal, setGroupTotal] = useState(0);
  const [groupSearch, setGroupSearch] = useState('');
  const [groupLoading, setGroupLoading] = useState(false);
  const [groupError, setGroupError] = useState<string | null>(null);
  // Page-local metadata only: leaving this page discards it and refreshes permissions next time.
  const groupPages = useRef(new Map<string, GroupPage>());
  const groupRequests = useRef(new Map<string, Promise<GroupPage>>());
  const groupControllers = useRef(new Set<AbortController>());
  const [groupNames, setGroupNames] = useState<Record<string, string>>({});
  const [selectedGroups, setSelectedGroups] = useState<string[]>(() => {
    const raw = searchParams.get('groups');
    return raw ? [...new Set(raw.split(',').filter(Boolean))] : [];
  });
  const [selectAllGroups, setSelectAllGroups] = useState(false);
  const selectionByConversation = useRef(new Map<string, string[]>());
  const allSelectionByConversation = useRef(new Map<string, boolean>());
  const [scopeRestoring, setScopeRestoring] = useState(false);
  const [groupPickerOpen, setGroupPickerOpen] = useState(false);
  const [ontologies, setOntologies] = useState<OntologyEntry[]>([]);
  const [selectedOntology, setSelectedOntology] = useState<string | null>(null);
  const [ontologyPickerOpen, setOntologyPickerOpen] = useState(false);
  /** Which picker is currently active (the other is frozen). Default: knowledge-base. */
  const [chatMode, setChatMode] = useState<'kb' | 'ontology'>('kb');
  const threadRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const groupPickerRef = useRef<HTMLDivElement | null>(null);
  const groupPopoverRef = useRef<HTMLDivElement | null>(null);
  const [groupPickerPosition, setGroupPickerPosition] = useState<React.CSSProperties>({});
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
      if (!groupPickerRef.current?.contains(event.target as Node) && !groupPopoverRef.current?.contains(event.target as Node)) {
        setGroupPickerOpen(false);
      }
    };
    document.addEventListener('pointerdown', closePicker);
    return () => document.removeEventListener('pointerdown', closePicker);
  }, [groupPickerOpen]);

  useLayoutEffect(() => {
    if (!groupPickerOpen) return;
    function positionPicker() {
      const rect = groupPickerRef.current?.getBoundingClientRect();
      if (!rect) return;
      const headerBottom = document.querySelector('main')?.getBoundingClientRect().top ?? 0;
      const topBoundary = Math.max(16, headerBottom + 84);
      const above = rect.top - topBoundary - 10;
      const below = window.innerHeight - rect.bottom - 26;
      const openBelow = below >= 380 || below > above;
      setGroupPickerPosition({
        position: 'fixed', left: Math.max(16, Math.min(rect.left, window.innerWidth - 360)),
        ...(openBelow ? { top: rect.bottom + 10, bottom: 'auto' } : { bottom: window.innerHeight - rect.top + 10, top: 'auto' }),
        maxHeight: Math.max(180, Math.min(480, openBelow ? below : above)),
      });
    }
    positionPicker();
    window.addEventListener('resize', positionPicker);
    window.addEventListener('scroll', positionPicker, true);
    return () => { window.removeEventListener('resize', positionPicker); window.removeEventListener('scroll', positionPicker, true); };
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
    if (chatMode === 'ontology') { setSelectedGroups([]); setSelectAllGroups(false); setGroupPickerOpen(false); }
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
    listOntologies()
      .then(list => { if (!cancelled) setOntologies(list); })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    const controllers = groupControllers.current;
    const pages = groupPages.current;
    const requests = groupRequests.current;
    return () => {
      controllers.forEach(controller => controller.abort());
      controllers.clear(); pages.clear(); requests.clear();
    };
  }, []);

  useEffect(() => {
    if (!groupPickerOpen) return;
    let active = true;
    const search = groupSearch.trim().toLowerCase();
    const key = JSON.stringify([search, groupPage]);
    const applyPage = (result: GroupPage) => {
      setGroups(result.items); setGroupTotal(result.total);
      setGroupNames(prev => ({ ...prev, ...Object.fromEntries(result.items.map(g => [g.id, g.name])) }));
      setGroupLoading(false); setGroupError(null);
    };
    // Only search locally if the unfiltered first page contains the entire accessible list.
    const all = groupPages.current.get(JSON.stringify(['', 0]));
    if (all && all.total <= all.items.length) {
      const matching = all.items.filter(g => !search || `${g.name}\n${g.description ?? ''}`.toLowerCase().includes(search));
      applyPage({ items: matching.slice(groupPage * 20, (groupPage + 1) * 20), total: matching.length, page: groupPage, size: 20 });
      return;
    }
    const cached = groupPages.current.get(key);
    if (cached) { applyPage(cached); return; }
    setGroups([]); setGroupTotal(0); setGroupLoading(true); setGroupError(null);
    const timer = window.setTimeout(() => {
      let request = groupRequests.current.get(key);
      if (!request) {
        const controller = new AbortController();
        groupControllers.current.add(controller);
        request = listGroupPage({ page: groupPage, size: 20, search }, controller.signal)
          .then(result => {
            if (!controller.signal.aborted) {
              groupPages.current.set(key, result);
              // Bound long-lived chat metadata while retaining the default page for fast reopening.
              if (groupPages.current.size > 32) {
                const oldest = [...groupPages.current.keys()].find(k => k !== JSON.stringify(['', 0]));
                if (oldest) groupPages.current.delete(oldest);
              }
            }
            return result;
          }).finally(() => {
            groupRequests.current.delete(key);
            groupControllers.current.delete(controller);
          });
        groupRequests.current.set(key, request);
      }
      request
        .then(result => {
          if (active) applyPage(result);
        }).catch(e => { if (active) setGroupError(e instanceof Error ? e.message : String(e)); })
        .finally(() => { if (active) setGroupLoading(false); });
    }, search ? 200 : 0);
    // Closing the menu keeps an in-flight request reusable; unmounting aborts it.
    return () => { active = false; window.clearTimeout(timer); };
  }, [groupPickerOpen, groupPage, groupSearch]);

  const selectedGroupKey = selectedGroups.join(',');
  useEffect(() => {
    const ids = selectedGroupKey.split(',').filter(Boolean);
    if (ids.length !== 1) return;
    const cached = new Map([...groupPages.current.values()].flatMap(page => page.items.map(g => [g.id, g.name] as const)));
    if (ids.every(id => cached.has(id))) {
      setGroupNames(prev => ({ ...prev, ...Object.fromEntries(ids.map(id => [id, cached.get(id)!])) }));
      return;
    }
    const controller = new AbortController();
    listGroupCards(ids, controller.signal).then(result => {
      if (!controller.signal.aborted) setGroupNames(prev => ({ ...prev, ...Object.fromEntries(result.map(g => [g.id, g.name])) }));
    }).catch(() => { /* A revoked selection remains pinned and the server refuses new reads. */ });
    return () => controller.abort();
  }, [selectedGroupKey]);

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
  const urlGroups = searchParams.get('groups');
  useEffect(() => {
    if (!urlSession) {
      setSelectedGroups(urlGroups ? [...new Set(urlGroups.split(',').filter(Boolean))] : []);
      setSelectAllGroups(false);
    }
  }, [urlSession, urlGroups]);
  useEffect(() => {
    if (!urlSession) { setScopeRestoring(false); return; }
    const controller = new AbortController();
    setScopeRestoring(true);
    currentSession(effectiveAgentId, urlSession, controller.signal).then(session => {
      if (controller.signal.aborted) return;
      const scope = session.groupIds ?? selectionByConversation.current.get(urlSession) ?? [];
      selectionByConversation.current.set(urlSession, scope);
      setSelectedGroups(scope);
      setSelectAllGroups(session.allGroups ?? allSelectionByConversation.current.get(urlSession) ?? false);
    }).catch(() => { /* History stays readable; the server checks scope again when sending. */ })
      .finally(() => { if (!controller.signal.aborted) setScopeRestoring(false); });
    return () => controller.abort();
  }, [effectiveAgentId, urlSession]);

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
        const page = await messagePage(effectiveAgentId, key);
        if (cancelled) return;
        const list = page.items;
        historyTurns.current.set(key, list);
        setHistoryCursors(prev => ({ ...prev, [key!]: page.nextBefore }));
        setHistoryError(null);
        setSessionMsgs(prev => {
          const next = new Map(prev);
          next.set(key!, { messages: turnsToMessages(list), busy: false });
          return next;
        });
      } catch {
        if (!cancelled) setHistoryError('历史消息加载失败，请重新打开此会话重试');
      }
      if (!cancelled) setRestoring(false);
    }
    run();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [effectiveAgentId, urlSession]);

  useEffect(() => {
    const node = threadRef.current;
    if (!node) return;
    if (preserveScroll.current) {
      node.scrollTop = preserveScroll.current.top + node.scrollHeight - preserveScroll.current.height;
      preserveScroll.current = null;
    } else node.scrollTo({ top: node.scrollHeight });
  }, [messages]);

  async function loadOlderMessages() {
    const key = curKey;
    const before = key ? historyCursors[key] : null;
    if (!key || before == null || olderRequest.current || busy || restoring) return;
    olderRequest.current = true; setOlderLoading(true); setHistoryError(null);
    try {
      const page = await messagePage(effectiveAgentId, key, before);
      if (activeHistoryKey.current !== key) return;
      const previous = historyTurns.current.get(key) ?? [];
      const seen = new Set(previous.map(t => t.id));
      const combined = [...page.items.filter(t => !seen.has(t.id)), ...previous];
      const oldIds = new Set(turnsToMessages(previous).map(m => m.id));
      historyTurns.current.set(key, combined);
      if (threadRef.current) preserveScroll.current = { height: threadRef.current.scrollHeight, top: threadRef.current.scrollTop };
      setSessionMsgs(prev => {
        const next = new Map(prev);
        const current = next.get(key);
        if (current) next.set(key, { ...current, messages: [...turnsToMessages(combined), ...current.messages.filter(m => !oldIds.has(m.id))] });
        return next;
      });
      setHistoryCursors(prev => ({ ...prev, [key]: page.nextBefore }));
    } catch { if (activeHistoryKey.current === key) setHistoryError('加载更早消息失败，请重试'); }
    finally { olderRequest.current = false; setOlderLoading(false); }
  }

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

  const kbSelectionLocked = effectiveAgentId === 'modeling-agent' && !!urlSession && messages.length > 0;
  const ordinaryChat = effectiveAgentId === 'data-agent' && !selectAllGroups && selectedGroups.length === 0;
  const canSend = useMemo(
    () => !busy && !restoring && !scopeRestoring && !!effectiveAgentId && input.trim().length > 0,
    [busy, restoring, scopeRestoring, effectiveAgentId, input],
  );

  async function handleSend() {
    if (!canSend) return;
    setGroupPickerOpen(false);
    const text = input.trim();
    setInput('');
    const nowMs = Date.now();
    const userMsg: Message = { id: nextId(), role: 'user', text, tools: [], trace: [], knowledgeScope: { groupIds: selectedGroups, allGroups: selectAllGroups, groupNames } };
    const replyMsg: Message = { id: nextId(), role: 'assistant', text: '', tools: [], trace: [], pending: true, startedAtMs: nowMs };

    // Use the URL conversationId as the Map key — stable across session switches.
    let convId = urlSession;
    if (!convId) {
      convId = (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : `conv-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
      selectionByConversation.current.set(convId, selectedGroups);
      allSelectionByConversation.current.set(convId, selectAllGroups);
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
        groupIds: selectAllGroups ? undefined : selectedGroups,
        allGroups: selectAllGroups,
      }, ac.signal)) {
        const isToolEvent = evt.type === 'tool_call' || evt.type === 'tool_result';
        if (isToolEvent) {
          const seq = evt.seq;
          if (typeof seq !== 'number' || !Number.isSafeInteger(seq) || seq <= lastToolEventSeq) continue;
          lastToolEventSeq = seq;
        }
        if (evt.type === 'capacity') {
          setSessionMsgs(prev => {
            const cur = prev.get(convId);
            if (!cur) return prev;
            const next = new Map(prev);
            next.set(convId, { ...cur, messages: cur.messages.map(m => m.id === replyMsg.id
              ? { ...m, capacityMessage: evt.state === 'queued' ? evt.message || '排队中，正在等待执行名额…' : undefined } : m) });
            return next;
          });
        } else if (evt.type === 'scope' && evt.knowledgeScope) {
          const scope = evt.knowledgeScope;
          selectionByConversation.current.set(convId, scope.groupIds);
          allSelectionByConversation.current.set(convId, scope.allGroups);
          setGroupNames(prev => ({ ...prev, ...scope.groupNames }));
          setSessionMsgs(prev => {
            const cur = prev.get(convId);
            if (!cur) return prev;
            const next = new Map(prev);
            next.set(convId, { ...cur, messages: cur.messages.map(m => m.id === userMsg.id ? { ...m, knowledgeScope: scope } : m) });
            return next;
          });
        } else if (evt.type === 'token') {
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
          if (evt.toolName && isHiddenTool(evt.toolName)) {
            continue;
          }
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
          if (evt.groupIds) {
            selectionByConversation.current.set(convId, evt.groupIds);
            allSelectionByConversation.current.set(convId, evt.allGroups ?? false);
            if (activeHistoryKey.current === convId) {
              setSelectedGroups(evt.groupIds); setSelectAllGroups(evt.allGroups ?? false);
            }
          }
          setSessionMsgs(prev => {
            const next = new Map(prev);
            const cur = next.get(convId);
            if (!cur) return prev;
            next.set(convId, {
              ...cur,
              messages: cur.messages.map(m => m.id === replyMsg.id
                ? { ...m, answerId: evt.answerId, pending: false, elapsedMs: m.startedAtMs ? Date.now() - m.startedAtMs : undefined }
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
      // A completed turn may already have been followed by another send.
      if (abortRef.current.get(convId) === ac) {
        // Every termination path releases the composer, including abort and truncated streams.
        setSessionMsgs(prev => {
          const cur = prev.get(convId);
          if (!cur) return prev;
          const next = new Map(prev);
          next.set(convId, { ...cur, busy: false, messages: cur.messages.map(m =>
            m.id === replyMsg.id && m.pending
              ? { ...m, pending: false, interrupted: ac.signal.aborted,
                  elapsedMs: m.startedAtMs ? Date.now() - m.startedAtMs : undefined }
              : m) });
          return next;
        });
        abortRef.current.delete(convId);
      }
      inputRef.current?.focus();
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  useEffect(() => {
    const textarea = inputRef.current;
    if (!textarea) return;
    const minimum = isLanding ? 76 : 48;
    textarea.style.height = '0px';
    const height = Math.min(200, Math.max(minimum, textarea.scrollHeight));
    textarea.style.height = `${height}px`;
    textarea.style.overflowY = textarea.scrollHeight > 200 ? 'auto' : 'hidden';
  }, [input, isLanding, curKey]);

  const composerContent = (
    <>
      <textarea
        ref={inputRef}
        style={{ ...S.textarea, ...(isLanding ? { minHeight: 76, fontSize: 15 } : {}) }}
        value={input}
        onChange={e => setInput(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={restoring ? '加载会话…' : ordinaryChat ? '输入问题；选择知识库后可查询数据…' : '输入查询、分析、预测数据问题…'}
        disabled={restoring}
      />
      <div style={S.composerBar}>
        <div ref={groupPickerRef} style={{ position: 'relative' }}>
          <button
            type="button"
            className={`da-kb-select${!kbFrozen && (selectedGroups.length || selectAllGroups || selectedOntology) ? ' is-selected' : ''}${groupPickerOpen ? ' is-open' : ''}`}
            onClick={() => kbFrozen ? setChatMode('kb') : setGroupPickerOpen(open => !open)}
            disabled={kbSelectionLocked || restoring || scopeRestoring || busy}
            aria-expanded={groupPickerOpen}
            aria-controls="chat-knowledge-picker"
            title={kbSelectionLocked ? '建模会话使用当前知识库' : busy ? '回答完成后可调整知识库' : kbFrozen ? '点击切换回知识库模式' : '可选一个、多个或全部知识库；未选时仅普通聊天'}
          >
            <span className="da-kb-select-icon"><Icon name="book" size="sm" /></span>
            <span className="da-kb-select-label">
            {selectedOntology
              ? '知识库'
              : selectAllGroups ? '全部知识库'
                : selectedGroups.length > 1 ? '知识库'
                  : selectedGroups.length ? groupNames[selectedGroups[0]] || '已选知识库'
                    : '选择知识库'}</span>
            {!kbFrozen && !selectAllGroups && selectedGroups.length > 1 && <span className="da-kb-select-count">{selectedGroups.length}</span>}
            {selectAllGroups && <span className="da-kb-select-count">全选</span>}
            <span className="da-kb-select-chevron"><Icon name="chevron" size="sm" /></span>
          </button>
          {groupPickerOpen && createPortal(
            <div ref={groupPopoverRef} id="chat-knowledge-picker" className="da-kb-picker" style={groupPickerPosition} role="dialog" aria-label="选择本轮知识库" onKeyDown={e => { if (e.key === 'Escape') { setGroupPickerOpen(false); groupPickerRef.current?.querySelector('button')?.focus(); } }}>
              <div className="da-kb-picker-heading"><span>查询范围</span><small>可多选</small></div>
              <div className="da-kb-picker-search"><Icon name="search" size="sm" /><input autoFocus aria-label="搜索可使用的知识库" placeholder="搜索知识库" value={groupSearch} onChange={e => { setGroupSearch(e.target.value); setGroupPage(0); }} /></div>
              {groupError && <div className="da-small" role="alert" style={{ padding: 8, color: 'var(--da-danger)' }}>{groupError}</div>}
              <label className={`da-kb-picker-all${selectAllGroups ? ' is-checked' : ''}`}><input type="checkbox" checked={selectAllGroups} onChange={e => { setSelectAllGroups(e.target.checked); setSelectedGroups([]); }} /><span>全部可用知识库<small>每次发送时更新可用范围</small></span></label>
              <div className="da-kb-picker-list">
              {!groupLoading && groups.map(g => (
                <label key={g.id} className={`da-kb-picker-item${selectAllGroups || selectedGroups.includes(g.id) ? ' is-checked' : ''}`}>
                  <input
                    type="checkbox"
                    checked={g.canQuery && (selectAllGroups || selectedGroups.includes(g.id))}
                    disabled={!g.canQuery || selectAllGroups}
                    onChange={e => {
                      if (selectAllGroups) return;
                      setSelectedGroups(prev => e.target.checked ? [...new Set([...prev, g.id])] : prev.filter(id => id !== g.id));
                    }}
                  />
                  <span className="da-kb-picker-item-icon"><Icon name="book" size="sm" /></span>
                  <span className="da-kb-picker-item-text">{g.name}<small>创建人：{g.ownerUsername}</small></span>
                </label>
              ))}
              {groupLoading && <div className="da-small" style={{ padding: 8 }}>加载中…</div>}
              {!groupLoading && groupTotal > 20 && <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, padding: 8 }}>
                <button className="da-btn da-btn-sm" disabled={groupPage === 0} onClick={() => setGroupPage(p => p - 1)}>上一页</button><span className="da-small">{groupPage + 1} / {Math.ceil(groupTotal / 20)}</span><button className="da-btn da-btn-sm" disabled={(groupPage + 1) * 20 >= groupTotal} onClick={() => setGroupPage(p => p + 1)}>下一页</button>
              </div>}
              {!groupLoading && groups.length === 0 && (
                <div className="da-small" style={{ padding: 6 }}>
                  暂无知识库
                </div>
              )}
              </div>
              <div className="da-kb-picker-footer">
                <button className="da-kb-picker-clear" onClick={() => { setSelectedGroups([]); setSelectAllGroups(false); }}>清空选择</button>
                <span>{selectAllGroups ? '全部可用' : selectedGroups.length ? `已选 ${selectedGroups.length} 个` : '普通聊天'}</span>
                <button className="da-btn da-btn-primary da-btn-sm" onClick={() => setGroupPickerOpen(false)}>完成</button>
              </div>
            </div>
          , document.body)}
        </div>
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
            <p>选择知识库，查询数据、分析变化或生成报告</p>
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
      <div style={S.thread} ref={threadRef} onScroll={e => {
        if (e.currentTarget.scrollTop < 80 && !historyError) void loadOlderMessages();
      }}>
        <div style={S.threadInner}>
        {historyError && <div role="alert">{historyError}</div>}
        {curKey && historyCursors[curKey] != null && <button className="da-btn da-btn-ghost da-btn-sm"
          disabled={olderLoading || busy} onClick={() => void loadOlderMessages()}>
          {olderLoading ? '加载中…' : '加载更早消息'}</button>}
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
                <div className="da-user-turn"><div className="da-bubble user">{m.text}</div>{m.knowledgeScope && <div className="da-turn-scope" title={m.knowledgeScope.groupIds.map(id => m.knowledgeScope!.groupNames[id] || id).join('、')}><Icon name={m.knowledgeScope.groupIds.length ? 'book' : 'chat'} size="sm" />{m.knowledgeScope.allGroups ? `全部可用 · ${m.knowledgeScope.groupIds.length} 个知识库` : m.knowledgeScope.groupIds.length > 1 ? `${m.knowledgeScope.groupIds.length} 个知识库` : m.knowledgeScope.groupIds.length ? m.knowledgeScope.groupNames[m.knowledgeScope.groupIds[0]] || '指定知识库' : '普通聊天'}</div>}</div>
              ) : (
                <div className="da-agent-turn">
                  <div className="da-agent-header">
                    <span className="da-agent-avatar">DA</span>
                    <span className="da-agent-name">红海DataAgent</span>
                  </div>
                  <TaskTrace
                    interrupted={m.interrupted}
                    running={!!m.pending}
                    active={trace.length > 0}
                    hasError={!!m.failed}
                    hadToolErrors={m.tools.some(t => t.result?.startsWith('error:') === true)}
                    elapsedMs={m.elapsedMs}
                  >
                    {trace}
                  </TaskTrace>
                  <div className="da-answer">
                    {m.text
                      ? <Markdown>{m.text}</Markdown>
                      : m.pending
                        ? m.capacityMessage
                          ? <div role="status" style={{ color: 'var(--da-text-muted)', fontSize: 14 }}>{m.capacityMessage}</div>
                          : <div className="da-typing"><span /><span /><span /></div>
                        : null}
                    {answer}
                    {agentId === 'data-agent' && curKey && !m.pending && !m.failed && m.text.trim() && (
                      <AnswerFeedback agentId={agentId} session={curKey} answerId={m.answerId} text={m.text} />
                    )}
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
