import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { currentSession, stream } from '../api/chat';
import { TurnEntry, turns as fetchTurns } from '../api/sessions';
import ToolCallBlock from './ToolCallBlock';
import ChartBlock from './ChartBlock';
import EChartsBlock, { ChartPayload } from './EChartsBlock';
import CitationPanel, { DatasetRef } from './CitationPanel';
import EmptyIllustration from './EmptyIllustration';
import Icon from './Icon';
import PythonCodeBlock from './PythonCodeBlock';
import OntologyGraphView from './OntologyGraphView';
import type { OntologyGraphData } from '../api/ontology';
import Markdown from './Markdown';
import { extractVegaSpec } from '../utils/charts';
import { listDatasets, listGroups, DatasetGroup } from '../api/datasets';

type Role = 'user' | 'assistant' | 'system';

interface ToolEntry {
  id: string;
  name: string;
  input?: string;
  result?: string;
}

interface Message {
  id: string;
  role: Role;
  text: string;
  tools: ToolEntry[];
  pending?: boolean;
}

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, background: 'var(--da-app-bg)' },
  thread: { flex: 1, overflowY: 'auto', padding: '28px 36px', display: 'flex', flexDirection: 'column', gap: 18 },
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
    padding: '0 24px 20px',
    display: 'flex',
    justifyContent: 'center',
    flexShrink: 0,
  },
  composerCard: {
    width: '100%',
    maxWidth: 880,
    background: 'var(--da-surface)',
    border: '1px solid var(--da-border)',
    borderRadius: 'var(--da-radius-xl)',
    boxShadow: 'var(--da-shadow-pop)',
    padding: '12px 16px 10px',
    display: 'flex',
    flexDirection: 'column',
    gap: 8,
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
    borderTop: '1px solid var(--da-border)',
    paddingTop: 8,
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

/** Extracts the `code` field from a JSON-encoded run_python tool input string. */
function extractPythonCode(input?: string): string | null {
  if (!input) return null;
  try {
    const parsed = JSON.parse(input);
    return typeof parsed.code === 'string' ? parsed.code : null;
  } catch {
    return null;
  }
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

function turnsToMessages(turns: TurnEntry[]): Message[] {
  const out: Message[] = [];
  let cur: Message | null = null;
  for (const t of turns) {
    const role = String(t.role).toUpperCase();
    if (role === 'USER') {
      out.push({ id: t.id, role: 'user', text: t.content ?? '', tools: [] });
      cur = null;
    } else if (role === 'TOOL') {
      if (t.toolName && isHiddenTool(t.toolName)) continue;
      if (t.toolName === 'run_python' && t.toolResult) {
        const isEvicted = t.toolResult.includes('Tool output was too large');
        console.log(`[turnsToMessages] loaded run_python result: len=${t.toolResult.length}, evicted=${isEvicted}, preview=[${t.toolResult.substring(0, 300)}]`);
      }
      if (!cur) {
        cur = { id: `${t.id}-host`, role: 'assistant', text: '', tools: [] };
        out.push(cur);
      }
      cur.tools = [
        ...cur.tools,
        {
          id: t.id,
          name: t.toolName ?? 'tool',
          input: t.toolInput ?? undefined,
          result: t.toolResult ?? undefined,
        },
      ];
    } else if (role === 'ASSISTANT') {
      // One bubble per turn: intermediate reasoning texts are superseded by later
      // assistant texts, so only the final reply text survives — matching the live view.
      if (!cur) {
        cur = { id: t.id, role: 'assistant', text: '', tools: [] };
        out.push(cur);
      }
      cur.id = t.id;
      cur.text = t.content ?? '';
    }
  }
  return out;
}

export interface ChatPanelProps {
  agentId: string;
  /** Called after each successful message turn so the sessions sidebar can refresh. */
  onSessionUpdate?: () => void;
  /** Reports the conversation title (first user question; '' when new). */
  onTitle?: (title: string) => void;
}

export default function ChatPanel({ agentId, onSessionUpdate, onTitle }: ChatPanelProps) {
  const [searchParams, setSearchParams] = useSearchParams();
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(true);
  const [sessionKey, setSessionKey] = useState<string | null>(null);
  const [datasetMap, setDatasetMap] = useState<Record<string, DatasetRef>>({});
  const [groups, setGroups] = useState<DatasetGroup[]>([]);
  const [selectedGroups, setSelectedGroups] = useState<string[]>(() => {
    const raw = searchParams.get('groups');
    return raw ? raw.split(',').filter(Boolean) : [];
  });
  const [groupPickerOpen, setGroupPickerOpen] = useState(false);
  const threadRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    listDatasets()
      .then(list => {
        if (cancelled) return;
        const map: Record<string, DatasetRef> = {};
        for (const d of list) map[d.id] = { name: d.name, tableName: d.tableName };
        setDatasetMap(map);
      })
      .catch(() => undefined);
    listGroups()
      .then(g => {
        if (!cancelled) setGroups(g);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const persistSession = useCallback((key: string | null) => {
    if (key) {
      try { localStorage.setItem(storageKey(agentId), key); } catch { /* ignore quota */ }
    } else {
      try { localStorage.removeItem(storageKey(agentId)); } catch { /* ignore */ }
    }
  }, [agentId]);

  // On agent or URL session change: pick a session (URL > localStorage > backend default) and rehydrate.
  const urlSession = searchParams.get('session');
  useEffect(() => {
    let cancelled = false;
    setMessages([]);
    setInput('');
    setRestoring(true);

    const stored = (() => { try { return localStorage.getItem(storageKey(agentId)); } catch { return null; } })();

    async function run() {
      // URL-provided session always wins: it may be a freshly-minted UUID that the
      // backend has never seen yet, and we must not let `currentSession` overwrite it.
      let key: string | null = urlSession;
      if (!key) {
        try {
          const cur = await currentSession(agentId, stored ?? undefined);
          key = cur.sessionKey || stored || null;
        } catch {
          key = stored || null;
        }
      }
      if (cancelled) return;
      setSessionKey(key);
      if (key) {
        try {
          const list = await fetchTurns(agentId, key);
          if (cancelled) return;
          setMessages(turnsToMessages(list));
        } catch {
          // missing/empty session is fine — we just start empty
        }
      }
      if (cancelled) return;
      setRestoring(false);
      if (key && key !== urlSession) {
        const next = new URLSearchParams(searchParams);
        next.set('session', key);
        setSearchParams(next, { replace: true });
      }
    }
    run();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, urlSession]);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight });
  }, [messages]);

  useEffect(() => {
    const firstUser = messages.find(m => m.role === 'user');
    onTitle?.(firstUser ? firstUser.text.replace(/\s+/g, ' ').trim().slice(0, 40) : '');
  }, [messages, onTitle]);

  const canSend = useMemo(() => !busy && !restoring && input.trim().length > 0, [busy, restoring, input]);

  async function handleSend() {
    if (!canSend) return;
    const text = input.trim();
    setInput('');
    setBusy(true);
    const userMsg: Message = { id: nextId(), role: 'user', text, tools: [] };
    const replyMsg: Message = { id: nextId(), role: 'assistant', text: '', tools: [], pending: true };
    setMessages(prev => [...prev, userMsg, replyMsg]);

    try {
      for await (const evt of stream(agentId, {
        message: text,
        sessionKey: sessionKey ?? undefined,
        groupIds: selectedGroups.length ? selectedGroups : undefined,
      })) {
        if (evt.type === 'token') {
          const chunk = evt.data ?? '';
          setMessages(prev => prev.map(m => m.id === replyMsg.id ? { ...m, text: m.text + chunk } : m));
        } else if (evt.type === 'tool_call') {
          // Skip framework-internal tools that should not appear in the UI.
          if (evt.toolName && isHiddenTool(evt.toolName)) continue;
          const entry: ToolEntry = {
            id: `${evt.toolName ?? 'tool'}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
            name: evt.toolName ?? 'tool',
            input: evt.toolInput,
          };
          setMessages(prev => prev.map(m => m.id === replyMsg.id ? { ...m, tools: [...m.tools, entry] } : m));
        } else if (evt.type === 'tool_result') {
          // Skip results for hidden tools.
          if (evt.toolName && isHiddenTool(evt.toolName)) continue;
          const trLen = evt.toolResult ? evt.toolResult.length : 0;
          console.log(`[ChatPanel] tool_result: ${evt.toolName}, len=${trLen}, preview=[${evt.toolResult?.substring(0, 300)}]`);
          setMessages(prev => prev.map(m => {
            if (m.id !== replyMsg.id) return m;
            const tools = [...m.tools];
            for (let i = tools.length - 1; i >= 0; i--) {
              if (tools[i].name === evt.toolName && !tools[i].result) {
                tools[i] = { ...tools[i], result: evt.toolResult };
                return { ...m, tools };
              }
            }
            tools.push({
              id: `${evt.toolName ?? 'tool'}-${Date.now()}`,
              name: evt.toolName ?? 'tool',
              result: evt.toolResult,
            });
            return { ...m, tools };
          }));
        } else if (evt.type === 'done') {
          if (evt.sessionKey) {
            setSessionKey(evt.sessionKey);
            persistSession(evt.sessionKey);
            const next = new URLSearchParams(searchParams);
            if (next.get('session') !== evt.sessionKey) {
              next.set('session', evt.sessionKey);
              setSearchParams(next, { replace: true });
            }
          }
          setMessages(prev => prev.map(m => m.id === replyMsg.id ? { ...m, pending: false } : m));
        } else if (evt.type === 'error') {
          setMessages(prev => prev.map(m => m.id === replyMsg.id
            ? { ...m, pending: false, text: m.text + (m.text ? '\n' : '') + `[error] ${evt.error ?? 'unknown'}` }
            : m));
        }
      }
      onSessionUpdate?.();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'stream failed';
      setMessages(prev => prev.map(m => m.id === replyMsg.id
        ? { ...m, pending: false, text: m.text + (m.text ? '\n' : '') + `[error] ${msg}` }
        : m));
    } finally {
      setBusy(false);
      inputRef.current?.focus();
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  return (
    <div style={S.root}>
      <div style={S.thread} ref={threadRef}>
        {restoring && messages.length === 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14, maxWidth: '70%' }}>
            <div className="da-skeleton da-skeleton-block" style={{ width: '45%' }} />
            <div className="da-skeleton da-skeleton-block" style={{ width: '80%', alignSelf: 'flex-end' }} />
            <div className="da-skeleton da-skeleton-block" style={{ width: '65%' }} />
          </div>
        )}
        {!restoring && messages.length === 0 && (
          <EmptyIllustration
            variant="doc"
            caption="开始一段新对话；输入 /reset 可清空当前会话"
          />
        )}
        {messages.map(m => (
          <div
            key={m.id}
            className={`da-bubble-row ${m.role === 'user' ? 'user' : 'agent'} da-enter`}
          >
            <div className={`da-bubble ${m.role === 'user' ? 'user' : 'agent'}`}>
            {m.tools.length > 0 && (
              <div style={{ marginBottom: m.text ? 10 : 0 }}>
                {m.tools.filter(t => !isHiddenTool(t.name)).map(t => {
                  const isChartTool = t.name.toLowerCase().includes('render_chart');
                  const isPythonTool = t.name.toLowerCase() === 'run_python';
                  const isOntologyTool = t.name.toLowerCase().includes('show_ontology_graph');
                  const chartPayload = isChartTool ? chartPayloadFromTool(t) : null;
                  const ontologyPayload = isOntologyTool ? ontologyGraphPayload(t) : null;
                  const spec = !chartPayload && !ontologyPayload ? extractVegaSpec(t.name, t.input) : null;
                  // Remount when the result arrives so defaultOpen=false takes effect
                  // (React keeps the old component's state when the key is stable).
                  const key = t.id + (t.result ? '-done' : '');

                  // Python tool: collapsed raw tool block + expanded code/artifact view.
                  if (isPythonTool) {
                    const pyCode = extractPythonCode(t.input);
                    return (
                      <React.Fragment key={key}>
                        <ToolCallBlock
                          toolName={t.name}
                          toolCallId={t.id}
                          input={t.input}
                          result={t.result}
                          defaultOpen={false}
                        />
                        {pyCode && (
                          <PythonCodeBlock
                            code={pyCode}
                            result={t.result}
                            defaultOpen={true}
                          />
                        )}
                      </React.Fragment>
                    );
                  }

                  return (
                    <React.Fragment key={key}>
                      <ToolCallBlock
                        toolName={t.name}
                        toolCallId={t.id}
                        input={t.input}
                        result={t.result}
                        defaultOpen={!t.result}
                      />
                      {isChartTool && !chartPayload && !spec && (
                        <div style={{
                          background: '#fffbeb',
                          border: '1px solid #fcd34d',
                          borderRadius: 9,
                          margin: '0.5rem 0',
                          padding: '0.6rem 0.9rem',
                          color: '#92400e',
                          fontSize: '0.8rem',
                        }}>
                          chart payload could not be parsed — expand the tool block above to
                          inspect the raw arguments
                        </div>
                      )}
                      {chartPayload && <EChartsBlock payload={chartPayload} />}
                      {ontologyPayload && (
                        <OntologyGraphView
                          data={ontologyPayload}
                          highlight={(ontologyPayload as any).highlight}
                        />
                      )}
                      {!chartPayload && !ontologyPayload && spec && <ChartBlock spec={spec} />}
                    </React.Fragment>
                  );
                })}
              </div>
            )}
            {m.role === 'assistant' && m.tools.length > 0 && (
              <CitationPanel tools={m.tools} datasetMap={datasetMap} />
            )}
            <div className="da-msg-content" style={{ color: 'inherit' }}>
              {m.role === 'assistant'
                ? m.text
                  ? <Markdown>{m.text}</Markdown>
                  : m.pending
                    ? <div className="da-typing"><span /><span /><span /></div>
                    : null
                : <div style={{ whiteSpace: 'pre-wrap' }}>{m.text}</div>}
            </div>
            </div>
          </div>
        ))}
      </div>
      <div style={S.composerWrap}>
        <div className="da-composer" style={S.composerCard}>
          <textarea
            ref={inputRef}
            style={S.textarea}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={restoring ? '加载会话…' : '输入查询、分析、预测数据问题…'}
            disabled={restoring}
          />
          <div style={S.composerBar}>
            <div style={{ position: 'relative' }}>
              <button
                type="button"
                className={selectedGroups.length ? 'da-chip da-chip-active' : 'da-chip'}
                onClick={() => setGroupPickerOpen(o => !o)}
              >
                <Icon name="book" size="sm" />{' '}
                {selectedGroups.length
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
                      onChange={() => setSelectedGroups([])}
                    />
                    全部知识库（不限定）
                  </label>
                  {groups.map(g => (
                    <label key={g.id} className="da-navitem">
                      <input
                        type="checkbox"
                        checked={selectedGroups.includes(g.id)}
                        onChange={() =>
                          setSelectedGroups(prev =>
                            prev.includes(g.id) ? prev.filter(x => x !== g.id) : [...prev, g.id],
                          )
                        }
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
            <span style={{ flex: 1 }} />
            <span className="da-small" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
              <span className="da-kbd">Enter</span> 发送 · <span className="da-kbd">Shift</span>+
              <span className="da-kbd">Enter</span> 换行
            </span>
            <span className="da-small">{input.length} 字</span>
            <button
              style={{ ...S.send, ...(canSend ? {} : S.sendDisabled) }}
              onClick={handleSend}
              disabled={!canSend}
              title="发送"
            >
              <Icon name="send" size="sm" />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
