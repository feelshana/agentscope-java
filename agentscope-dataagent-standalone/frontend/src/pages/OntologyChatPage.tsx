import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { currentSession, stream } from '../api/chat';
import { TurnEntry, turns as fetchTurns } from '../api/sessions';
import { listOntologies, OntologyEntry } from '../api/ontologies';
import ToolCallBlock from '../components/ToolCallBlock';
import TaskTrace from '../components/TaskTrace';
import EmptyIllustration from '../components/EmptyIllustration';
import Icon from '../components/Icon';
import Markdown from '../components/Markdown';

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
  failed?: boolean;
}

const ONTOLOGY_STORAGE_PREFIX = 'claw_ontology_session:';
const ontologyStorageKey = (agentId: string) => `${ONTOLOGY_STORAGE_PREFIX}${agentId}`;

let counter = 0;
const nextId = () => `m${Date.now().toString(36)}-${counter++}`;

function turnsToMessages(turns: TurnEntry[]): Message[] {
  const out: Message[] = [];
  let cur: Message | null = null;
  for (const t of turns) {
    const role = String(t.role).toUpperCase();
    if (role === 'USER') {
      out.push({ id: t.id, role: 'user', text: t.content ?? '', tools: [], trace: [] });
      cur = null;
    } else if (role === 'TOOL') {
      if (!cur) {
        cur = { id: `${t.id}-host`, role: 'assistant', text: '', tools: [], trace: [] };
        out.push(cur);
      }
      const name = t.toolName ?? 'tool';
      if (t.toolResult != null) {
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
        const entry: ToolEntry = { id: t.id, name, input: t.toolInput ?? undefined };
        cur.tools = [...cur.tools, entry];
        cur.trace = [...cur.trace, { kind: 'tool', id: t.id, tool: entry }];
      }
    } else if (role === 'ASSISTANT') {
      if (!cur) {
        cur = { id: t.id, role: 'assistant', text: '', tools: [], trace: [] };
        out.push(cur);
      }
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

export default function OntologyChatPage() {
  const [ontologies, setOntologies] = useState<OntologyEntry[]>([]);
  const [selectedOntology, setSelectedOntology] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [sessionKey, setSessionKey] = useState<string | null>(null);
  const threadRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);

  // Load ontology list on mount
  useEffect(() => {
    let cancelled = false;
    listOntologies()
      .then(list => { if (!cancelled) setOntologies(list); })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, []);

  const effectiveAgentId = selectedOntology ? `ontology-${selectedOntology}` : null;

  // When ontology selection changes, reload session
  useEffect(() => {
    if (!effectiveAgentId) {
      setMessages([]);
      setSessionKey(null);
      return;
    }
    let cancelled = false;
    setMessages([]);
    setInput('');
    setRestoring(true);

    const stored = (() => {
      try { return localStorage.getItem(ontologyStorageKey(effectiveAgentId)); } catch { return null; }
    })();

    async function run() {
      let key: string | null = null;
      try {
        const cur = await currentSession(effectiveAgentId!, stored ?? undefined);
        key = cur.sessionKey || stored || null;
      } catch {
        key = stored || null;
      }
      if (cancelled) return;
      setSessionKey(key);
      if (key) {
        try {
          const list = await fetchTurns(effectiveAgentId!, key);
          if (cancelled) return;
          setMessages(turnsToMessages(list));
        } catch {
          // empty session
        }
      }
      if (cancelled) return;
      setRestoring(false);
    }
    run();
    return () => { cancelled = true; };
  }, [effectiveAgentId]);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight });
  }, [messages]);

  const canSend = useMemo(
    () => !busy && !restoring && !!effectiveAgentId && input.trim().length > 0,
    [busy, restoring, effectiveAgentId, input],
  );

  const persistSession = useCallback((key: string | null) => {
    if (key && effectiveAgentId) {
      try { localStorage.setItem(ontologyStorageKey(effectiveAgentId), key); } catch { /* ignore */ }
    }
  }, [effectiveAgentId]);

  async function handleSend() {
    if (!canSend || !effectiveAgentId) return;
    const text = input.trim();
    setInput('');
    setBusy(true);
    const userMsg: Message = { id: nextId(), role: 'user', text, tools: [], trace: [] };
    const replyMsg: Message = { id: nextId(), role: 'assistant', text: '', tools: [], trace: [], pending: true };
    setMessages(prev => [...prev, userMsg, replyMsg]);

    try {
      for await (const evt of stream(effectiveAgentId, {
        message: text,
        sessionKey: sessionKey ?? undefined,
      })) {
        if (evt.type === 'token') {
          const chunk = evt.data ?? '';
          setMessages(prev => prev.map(m => m.id === replyMsg.id ? { ...m, text: m.text + chunk } : m));
        } else if (evt.type === 'tool_call') {
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
          setMessages(prev => prev.map(m => {
            if (m.id !== replyMsg.id) return m;
            const trace = m.text.trim()
              ? [...m.trace, { kind: 'text' as const, id: `${m.id}-n${m.trace.length}`, text: m.text }]
              : [...m.trace];
            return { ...m, text: '', trace: [...trace, { kind: 'tool' as const, id: entry.id, tool: entry }], tools: [...m.tools, entry] };
          }));
        } else if (evt.type === 'tool_result') {
          setMessages(prev => prev.map(m => {
            if (m.id !== replyMsg.id) return m;
            const tools = [...m.tools];
            let matchedId: string | null = null;
            if (evt.toolCallId) {
              for (let i = tools.length - 1; i >= 0; i--) {
                if (tools[i].callId === evt.toolCallId && tools[i].result === undefined) {
                  tools[i] = { ...tools[i], requestId: evt.requestId, runId: evt.runId, result: evt.toolResult, resultSeq: evt.seq };
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
          }));
        } else if (evt.type === 'done') {
          if (evt.sessionKey) {
            setSessionKey(evt.sessionKey);
            persistSession(evt.sessionKey);
          }
          setMessages(prev => prev.map(m => m.id === replyMsg.id ? { ...m, pending: false } : m));
        } else if (evt.type === 'error') {
          setMessages(prev => prev.map(m => m.id === replyMsg.id
            ? { ...m, pending: false, failed: true, text: m.text + (m.text ? '\n' : '') + `[错误] ${evt.error ?? '未知错误'}` }
            : m));
        }
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '连接中断';
      setMessages(prev => prev.map(m => m.id === replyMsg.id
        ? { ...m, pending: false, failed: true, text: m.text + (m.text ? '\n' : '') + `[错误] ${msg}` }
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
      {/* Top bar: ontology selector */}
      <div style={S.topBar}>
        <div style={S.topBarInner}>
          <span style={S.eyebrow}>本体查询</span>
          <select
            value={selectedOntology ?? ''}
            onChange={e => setSelectedOntology(e.target.value || null)}
            style={S.select}
            className="da-chip"
          >
            <option value="">— 选择本体 —</option>
            {ontologies.map(o => (
              <option key={o.id} value={o.id}>{o.name}</option>
            ))}
          </select>
          {ontologies.length === 0 && (
            <span style={S.hint}>暂无可用本体</span>
          )}
        </div>
      </div>

      {/* Thread */}
      <div style={S.thread} ref={threadRef}>
        <div style={S.threadInner}>
          {!effectiveAgentId && (
            <EmptyIllustration
              variant="doc"
              caption="请先从上方下拉框选择一个本体，然后开始对话"
            />
          )}
          {effectiveAgentId && restoring && messages.length === 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14, maxWidth: '70%' }}>
              <div className="da-skeleton da-skeleton-block" style={{ width: '45%' }} />
              <div className="da-skeleton da-skeleton-block" style={{ width: '80%', alignSelf: 'flex-end' }} />
            </div>
          )}
          {effectiveAgentId && !restoring && messages.length === 0 && (
            <EmptyIllustration
              variant="doc"
              caption="开始与本体的对话；使用 MCP 工具进行数据查询和分析"
            />
          )}
          {messages.map(m => (
            <div key={m.id} className={`da-bubble-row ${m.role === 'user' ? 'user' : 'agent'} da-enter`}>
              {m.role === 'user' ? (
                <div className="da-bubble user">{m.text}</div>
              ) : (
                <div className="da-agent-turn">
                  {(m.trace.length > 0 || m.pending) && (
                    <TaskTrace
                      running={!!m.pending}
                      active={m.trace.length > 0}
                      hasError={!!m.failed}
                      hadToolErrors={m.tools.some(t => t.result?.startsWith('error:'))}
                    >
                      {m.trace.map(node =>
                        node.kind === 'text' ? (
                          <div key={node.id} className="da-trace-text">{node.text}</div>
                        ) : (
                          <div key={node.id}>
                            <ToolCallBlock
                              toolName={node.tool.name}
                              toolCallId={node.tool.id}
                              input={node.tool.input}
                              result={node.tool.result}
                            />
                          </div>
                        ),
                      )}
                    </TaskTrace>
                  )}
                  <div className="da-answer">
                    {m.text
                      ? <Markdown>{m.text}</Markdown>
                      : m.pending
                        ? <div className="da-typing"><span /><span /><span /></div>
                        : null}
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Composer */}
      <div style={S.composerWrap}>
        <div className="da-composer" style={S.composerCard}>
          <textarea
            ref={inputRef}
            style={S.textarea}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={
              !effectiveAgentId
                ? '请先选择本体…'
                : restoring
                  ? '加载会话…'
                  : '输入数据查询或分析问题…'
            }
            disabled={!effectiveAgentId || restoring}
          />
          <div style={S.composerBar}>
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

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, background: 'var(--da-app-bg)' },
  topBar: {
    padding: '12px 24px',
    borderBottom: '1px solid var(--da-border)',
    background: 'rgba(255, 255, 255, 0.82)',
    backdropFilter: 'blur(8px)',
    WebkitBackdropFilter: 'blur(8px)',
    flexShrink: 0,
  },
  topBarInner: { display: 'flex', alignItems: 'center', gap: 12, maxWidth: 960, margin: '0 auto', width: '100%' },
  eyebrow: { fontSize: 13, fontWeight: 600, color: 'var(--da-text-muted)', letterSpacing: '0.04em', textTransform: 'uppercase' as const },
  select: {
    padding: '6px 12px',
    borderRadius: 8,
    border: '1px solid var(--da-border)',
    background: 'var(--da-surface)',
    color: 'var(--da-text)',
    fontSize: 14,
    cursor: 'pointer',
    minWidth: 180,
  },
  hint: { fontSize: 13, color: 'var(--da-text-muted)', fontStyle: 'italic' },
  thread: { flex: 1, overflowY: 'auto', padding: '28px 36px', display: 'flex', flexDirection: 'column', gap: 18 },
  threadInner: {
    width: '100%', maxWidth: 960, margin: '0 auto',
    display: 'flex', flexDirection: 'column', gap: 18,
  },
  composerWrap: {
    padding: '0 36px 20px',
    display: 'flex',
    justifyContent: 'center',
    flexShrink: 0,
  },
  composerCard: {
    width: '100%',
    maxWidth: 960,
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
