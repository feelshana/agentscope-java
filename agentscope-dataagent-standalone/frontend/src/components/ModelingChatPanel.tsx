import { useCallback, useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import Icon from './Icon';
import Markdown from './Markdown';
import ModelingHitlCard from './ModelingHitlCard';
import ReadableCode from './ReadableCode';
import { confirmModeling, currentSession, stream } from '../api/chat';
import type { ChatEvent, HitlToolCall } from '../api/chat';
import { getModelingOverview } from '../api/semanticModeling';
import type { ModelingOverview } from '../api/semanticModeling';

/**
 * 对话建模抽屉（specs/013 M1）：内嵌建模助手聊天面板，SSE 渲染
 * token / tool_call / tool_result / done / error 帧。
 * specs/021：过程呈现对齐 Claude Code —— 一轮回复按事件到达顺序渲染为
 * 「叙述段 ↔ 任务行」交替流；每个工具一行状态 + 中文任务标签，
 * 详情默认收起、点击展开（input/result 全量，超长截断）；
 * HITL 等待时任务行显示「待确认」（hitl_request.call.id 与 toolCallId 同源配对）。
 * sessionKey 固定为 modeling-{groupId}，同一知识库的建模会话跨刷新延续。
 * 引导（对标 WrenAI onboarding）：空状态欢迎卡介绍三步流程并提供「一键开始」。
 */

const MODELING_AGENT_ID = 'modeling-agent';

/** 快捷引导：一条预设消息 = 一个建模动作，与 MODELING_SCRIPT 剧本对齐。 */


interface ToolFrame {
  id: string;
  name: string;
  callId?: string;
  input?: string;
  result?: string;
  state: 'running' | 'waiting' | 'done' | 'failed' | 'rejected';
}

/** 有序渲染段：叙述文本与工具任务行按到达顺序交替（specs/021 时序修正）。 */
type Segment = { kind: 'text'; text: string } | { kind: 'tool'; frame: ToolFrame };

interface PendingHitl {
  replyId: string;
  toolCalls: HitlToolCall[];
}

interface PanelMessage {
  id: string;
  role: 'user' | 'assistant';
  segments: Segment[];
  pending?: boolean;
  hitl?: PendingHitl;
}

let seq = 0;
const nextId = () => `mc${Date.now().toString(36)}-${seq++}`;

const DETAIL_LIMIT = 4000;

function clip(text: string): string {
  return text.length > DETAIL_LIMIT ? `${text.slice(0, DETAIL_LIMIT)}\n…（已截断）` : text;
}

function parseToolInput(inputJson?: string): Record<string, unknown> {
  if (!inputJson) return {};
  try {
    const parsed = JSON.parse(inputJson);
    return typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function baseName(path: string): string {
  const parts = path.split('/');
  return parts[parts.length - 1] || path;
}

/** 工具名 + 关键参数 → 中文任务标签（specs/021）。 */
function taskLabel(name: string, inputJson?: string): string {
  const input = parseToolInput(inputJson);
  const path = typeof input.path === 'string' ? (input.path as string) : '';
  switch (name) {
    case 'list_modeling_state':
      return '盘点建模现状';
    case 'list_files':
      return '列出工程文件';
    case 'read_file':
      return path ? `读取 ${baseName(path)}` : '读取工程文件';
    case 'write_file':
      if (typeof input.files_json === 'string') return '提交完整业务方案';
      return path ? `写入 ${path}` : '写入工程文件';
    case 'patch_file':
      return path ? `修改 ${path}` : '修改工程文件';
    case 'validate_mdl':
      return '校验工程文件';
    case 'suggest_relations':
      return '重算关系候选';
    case 'decide_relation':
      return '提交关系决策';
    case 'add_relation':
      return '新增关系';
    case 'list_terms':
      return '查看术语';
    case 'wren_context_show':
      return '查看语义上下文';
    case 'wren_context_instructions':
      return '获取建模规范';
    case 'wren_skills_list':
      return '列出官方剧本';
    case 'wren_skills_get':
      return '读取官方剧本';
    case 'wren_dry_plan':
      return '预览编译计划';
    case 'wren_dry_run':
      return '试跑验证';
    default:
      return name;
  }
}

/** 任务行（specs/021）：一行状态 + 中文任务标签；默认收起，点击展开完整 input/result。 */
function TaskRow({ frame }: { frame: ToolFrame }) {
  const [open, setOpen] = useState(false);
  const label = taskLabel(frame.name, frame.input);
  const input = parseToolInput(frame.input);
  const writeContent = frame.name === 'write_file' ? input.content : frame.name === 'patch_file' ? input.replacement : frame.name === 'create_view' ? input.statement : undefined;
  let readableResult = frame.result || '';
  try { const value: unknown = JSON.parse(readableResult); if (typeof value === 'string') readableResult = value; } catch { /* Keep plain tool output. */ }
  let prettyInput = '';
  if (frame.input) {
    try {
      prettyInput = JSON.stringify(JSON.parse(frame.input), null, 2);
    } catch {
      prettyInput = frame.input;
    }
  }
  const statusColor =
    frame.state === 'waiting'
      ? 'var(--da-warn)'
      : frame.state === 'failed'
        ? 'var(--da-danger)'
        : frame.state === 'done'
          ? 'var(--da-success)'
          : 'var(--da-text-muted)';
  const statusText =
    frame.state === 'running'
      ? '执行中'
      : frame.state === 'waiting'
        ? '待确认'
        : frame.state === 'failed'
          ? '失败'
          : frame.state === 'rejected'
            ? '已拒绝'
            : '完成';
  return (
    <div style={S.task}>
      <button
        style={S.taskRow}
        onClick={() => setOpen(v => !v)}
        title={open ? '收起详情' : '查看详情'}
      >
        <span style={{ ...S.taskStatus, color: statusColor }}>
          {frame.state === 'running' ? (
            <span className="da-spinner da-spinner-sm" />
          ) : (
            <Icon
              name={
                frame.state === 'waiting'
                  ? 'clock'
                  : frame.state === 'failed'
                    ? 'warn'
                    : frame.state === 'rejected'
                      ? 'close'
                      : 'check'
              }
              size="sm"
            />
          )}
          <span>{statusText}</span>
        </span>
        <span style={S.taskLabelText}>{label}</span>
      </button>
      {open && (
        <div style={S.taskDetail}>
          {prettyInput && (
            <>
              {typeof input.reason === 'string' && <p style={{ fontSize: 15, lineHeight: 1.7 }}>{input.reason}</p>}
              {typeof writeContent === 'string' ? <ReadableCode text={writeContent} language={frame.name === 'create_view' ? 'sql' : 'yaml'} title={frame.name === 'patch_file' ? '本次替换内容' : '写入内容'} /> : <ReadableCode text={prettyInput} language="json" title="工具输入" />}
            </>
          )}
          {frame.result && (
            <>
              <div style={S.taskDetailHead}>输出</div>
              <pre style={{ ...S.taskDetailPre, fontSize: 15, lineHeight: 1.7 }}>{clip(readableResult)}</pre>
            </>
          )}
        </div>
      )}
    </div>
  );
}

export default function ModelingChatPanel({
  groupId,
  onClose,
  variant = 'modal',
  draftPrompt,
  draftPromptId,
  submitDraft = false,
  onSessionReady,
  onAttachDocument,
}: {
  groupId: string;
  onClose: () => void;
  /** specs/027: centered large window by default; specs/030 adds the right-side dock.
   *  `dock` renders inline (no overlay) so the parent workbench layout controls its width. */
  variant?: 'modal' | 'drawer' | 'dock';
  draftPrompt?: string;
  draftPromptId?: number;
  submitDraft?: boolean;
  onSessionReady?: (hasPending: boolean, error?: string) => void;
  onAttachDocument?: () => void;
}) {
  const [messages, setMessages] = useState<PanelMessage[]>([]);
  const [input, setInput] = useState('');
  const [sessionReady, setSessionReady] = useState(false);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const decisionInFlight = useRef(false);
  const [overview, setOverview] = useState<ModelingOverview | null>(null);
  const threadRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const consumedDraft = useRef<string | null>(null);
  const consumedSubmission = useRef<string | null>(null);
  const sessionKeyRef = useRef<string>(`modeling-${groupId}`);
  const awaitingDecision = messages.some(message => message.hitl);

  useEffect(() => {
    const key = `${groupId}:${draftPromptId ?? draftPrompt}`;
    if (draftPrompt && !submitDraft && consumedDraft.current !== key) {
      consumedDraft.current = key;
      setInput(previous => (previous.trim() ? previous.trim() + '\n\n' : '') + draftPrompt);
      inputRef.current?.focus();
    }
  }, [draftPrompt, draftPromptId, submitDraft, groupId]);

  /** specs/036: the decide cards resolve table names from this overview — refetchable. */
  const loadOverview = useCallback(() => {
    return getModelingOverview(groupId)
      .then(value => setOverview(value))
      .catch(() => setOverview(null));
  }, [groupId]);

  useEffect(() => {
    let cancelled = false;
    sessionKeyRef.current = `modeling-${groupId}`;
    setMessages([]);
    setOverview(null);
    setSessionReady(false); setSessionError(null);
    void loadOverview();
    currentSession(MODELING_AGENT_ID, sessionKeyRef.current)
      .then(session => {
        if (cancelled) return;
        const hasPending = !!session.pendingReplyId && !!session.pendingToolCalls?.length;
        setSessionReady(true);
        onSessionReady?.(hasPending);
        if (!hasPending) return;
        setMessages([
          {
            id: nextId(),
            role: 'assistant',
            segments: [{ kind: 'text', text: '已恢复上次尚未处理的建模提案。' }],
            hitl: {
              replyId: session.pendingReplyId as string,
              toolCalls: session.pendingToolCalls as HitlToolCall[],
            },
          },
        ]);
      })
      .catch(error => { if (!cancelled) {
        const detail = `建模会话加载失败，请刷新重试。${error instanceof Error ? error.message : ''}`;
        setSessionError(detail); onSessionReady?.(false, detail);
      } });
    return () => {
      cancelled = true;
    };
  }, [groupId, onSessionReady]);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight });
  }, [messages]);

  // specs/036: a decide card arrives after the model just refreshed relation candidates — the
  // overview snapshot from mount time does not contain those new rows yet, so the card would
  // render 「未知数据表」instead of the two table names. Refetch once when a decision is pending.
  useEffect(() => {
    if (awaitingDecision) void loadOverview();
  }, [awaitingDecision, loadOverview]);

  /** specs/027: tell the modeling page to refetch so every tab reflects the change. */
  function notifyUpdated() {
    window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
  }

  const handleClose = useCallback(() => {
    notifyUpdated();
    onClose();
  }, [groupId, onClose]);

  useEffect(() => {
    if (variant !== 'modal') return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [variant, handleClose]);

  function applyEvent(messageId: string, evt: ChatEvent) {
    const patch = (fn: (message: PanelMessage) => PanelMessage) =>
      setMessages(previous => previous.map(message => (message.id === messageId ? fn(message) : message)));
    if (evt.type === 'token') {
      patch(message => {
        const segments = [...message.segments];
        const last = segments[segments.length - 1];
        if (last && last.kind === 'text') {
          segments[segments.length - 1] = { kind: 'text', text: last.text + (evt.data ?? '') };
        } else {
          segments.push({ kind: 'text', text: evt.data ?? '' });
        }
        return { ...message, segments };
      });
    } else if (evt.type === 'tool_call') {
      const frame: ToolFrame = {
        id: `${evt.toolCallId ?? nextId()}`,
        name: evt.toolName ?? 'tool',
        callId: evt.toolCallId,
        input: evt.toolInput,
        state: 'running',
      };
      patch(message => ({
        ...message,
        segments: [...message.segments, { kind: 'tool', frame }],
      }));
    } else if (evt.type === 'tool_result') {
      patch(message => {
        const segments = [...message.segments];
        for (let i = segments.length - 1; i >= 0; i--) {
          const seg = segments[i];
          if (
            seg.kind === 'tool' &&
            seg.frame.callId === evt.toolCallId &&
            !seg.frame.result &&
            seg.frame.state !== 'rejected'
          ) {
            const result = evt.toolResult ?? '';
            segments[i] = {
              kind: 'tool',
              frame: {
                ...seg.frame,
                result,
                state: result.startsWith('error') ? 'failed' : 'done',
              },
            };
            break;
          }
        }
        return { ...message, segments };
      });
    } else if (evt.type === 'hitl_request' && evt.replyId && evt.toolCalls?.length) {
      patch(message => {
        const ids = new Set((evt.toolCalls as HitlToolCall[]).map(call => call.id));
        const segments = message.segments.map(seg =>
          seg.kind === 'tool' && seg.frame.callId && ids.has(seg.frame.callId)
            ? { kind: 'tool' as const, frame: { ...seg.frame, state: 'waiting' as const } }
            : seg,
        );
        return {
          ...message,
          segments,
          pending: false,
          hitl: { replyId: evt.replyId as string, toolCalls: evt.toolCalls as HitlToolCall[] },
        };
      });
    } else if (evt.type === 'done') {
      if (evt.sessionKey) sessionKeyRef.current = evt.sessionKey;
      patch(message => ({ ...message, pending: false }));
      // specs/037: a finished turn may have written workspace files (write_file/create_view/
      // decide_relations); tell the modeling page to refetch so counts and content stay in sync
      // without a manual refresh.
      notifyUpdated();
    } else if (evt.type === 'error') {
      patch(message => {
        const segments = [...message.segments];
        const last = segments[segments.length - 1];
        const note = `**[错误]** ${evt.error ?? '未知错误'}`;
        if (last && last.kind === 'text') {
          segments[segments.length - 1] = {
            kind: 'text',
            text: `${last.text}${last.text ? '\n' : ''}${note}`,
          };
        } else {
          segments.push({ kind: 'text', text: note });
        }
        return { ...message, segments, pending: false };
      });
    }
  }

  async function restorePendingProposal(messageId: string) {
    try {
      const session = await currentSession(MODELING_AGENT_ID, sessionKeyRef.current);
      if (session.pendingReplyId && session.pendingToolCalls?.length) {
        applyEvent(messageId, { type: 'hitl_request', replyId: session.pendingReplyId, toolCalls: session.pendingToolCalls });
      }
    } catch (error) {
      applyEvent(messageId, { type: 'error', error: `建模确认状态同步失败，请刷新页面恢复提案。${error instanceof Error ? error.message : ''}` });
    }
  }

  /** Sends a user message and reconciles persisted native confirmation state. */
  async function sendText(raw: string) {
    const text = raw.trim();
    if (!text || busy || awaitingDecision) return;
    setInput('');
    setBusy(true);
    const userMsg: PanelMessage = { id: nextId(), role: 'user', segments: [{ kind: 'text', text }] };
    const replyMsg: PanelMessage = {
      id: nextId(),
      role: 'assistant',
      segments: [],
      pending: true,
    };
    setMessages(prev => [...prev, userMsg, replyMsg]);
    try {
      for await (const evt of stream(
        MODELING_AGENT_ID,
        { message: text, sessionKey: sessionKeyRef.current, groupIds: [groupId] },
      )) {
        applyEvent(replyMsg.id, evt);
      }
      await restorePendingProposal(replyMsg.id);
    } catch (e) {
      const msg = e instanceof Error ? e.message : '连接中断';
      setMessages(previous =>
        previous.map(message =>
          message.id === replyMsg.id
            ? {
                ...message,
                pending: false,
                segments: [
                  ...message.segments,
                  { kind: 'text' as const, text: `**[错误]** ${msg}` },
                ],
              }
            : message,
        ),
      );
    } finally {
      setBusy(false);
      inputRef.current?.focus();
    }
  }

  async function submitDecision(
    messageId: string,
    hitl: PendingHitl,
    call: HitlToolCall,
    confirmed: boolean,
    toolInput?: Record<string, unknown>,
    feedback?: string,
  ) {
    if (busy || decisionInFlight.current) return;
    decisionInFlight.current = true;
    setBusy(true);
    setMessages(previous =>
      previous.map(message => {
        if (message.id !== messageId) return message;
        // 确认/拒绝后解除任务行的「待确认」态：确认 → 回到执行中等结果；拒绝 → 标记已拒绝。
        const segments = message.segments.map(seg =>
          seg.kind === 'tool' && seg.frame.state === 'waiting'
            ? {
                kind: 'tool' as const,
                frame: { ...seg.frame, state: confirmed ? ('running' as const) : ('rejected' as const) },
              }
            : seg,
        );
        return { ...message, segments, hitl: undefined, pending: true };
      }),
    );
    try {
      for await (const evt of confirmModeling(MODELING_AGENT_ID, {
        sessionKey: sessionKeyRef.current,
        groupIds: [groupId],
        replyId: hitl.replyId,
        toolCallId: call.id,
        toolName: call.name,
        confirmed,
        toolInput,
        feedback,
      })) {
        applyEvent(messageId, evt);
      }
      await restorePendingProposal(messageId);
      getModelingOverview(groupId).then(setOverview).catch(() => undefined);
      notifyUpdated();
    } catch (error) {
      const detail = error instanceof Error ? error.message : '确认提交失败';
      setMessages(previous =>
        previous.map(message =>
          message.id === messageId
            ? {
                ...message,
                pending: false,
                hitl,
                segments: [
                  ...message.segments,
                  { kind: 'text' as const, text: `**[确认失败]** ${detail}` },
                ],
              }
            : message,
        ),
      );
    } finally {
      decisionInFlight.current = false;
      setBusy(false);
    }
  }

  function handleSend() {
    void sendText(input);
  }

  useEffect(() => {
    const key = `${groupId}:${draftPromptId ?? draftPrompt}`;
    if (submitDraft && draftPrompt && sessionReady && !busy && !awaitingDecision && consumedSubmission.current !== key) {
      consumedSubmission.current = key;
      void sendText(draftPrompt);
    }
  }, [submitDraft, draftPrompt, draftPromptId, sessionReady, busy, awaitingDecision, groupId]);

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  return (
    <div
      style={
        variant === 'dock'
          ? S.dockRoot
          : variant === 'drawer'
            ? S.overlay
            : S.overlayCenter
      }
      onClick={variant === 'drawer' ? onClose : variant === 'modal' ? handleClose : undefined}
    >
      <div
        style={
          variant === 'dock' ? S.dockShell : variant === 'drawer' ? S.panel : S.panelModal
        }
        onClick={e => e.stopPropagation()}
      >
        <div style={S.header}>
          <span style={S.title}>
            <Icon name="model" size="sm" /> 对话建模
          </span>
          <span style={S.headerHint}>写入草稿 → 验证与确认 → 发布</span>
          {variant !== 'dock' && <button style={S.close} onClick={handleClose} title="关闭">
            <Icon name="close" size="sm" />
          </button>}
        </div>

        <div style={S.thread} ref={threadRef}>
          {sessionError && <p role="alert">{sessionError}</p>}
          {messages.length === 0 && (
            <div style={S.welcome}>
              <div style={S.welcomeTitle}>
                <Icon name="model" size="sm" /> 我是建模助手
              </div>
              <div style={S.welcomeDesc}>围绕已提交的分析问题澄清口径、完善模型、关系、口径与明细视图。</div>
              <p style={S.welcomeDesc}>这里用于回答助手的问题和反馈结果。需要增加分析问题时，请使用右侧问题清单的“添加问题”。</p>
              <div style={S.welcomeDesc}>每个问题都会生成 SQL，经 Wren 验证并由你确认结果后再发布。</div>
            </div>
          )}
          {messages.map(m => (
            <div key={m.id} style={S.row}>
              <div style={m.role === 'user' ? S.userBubble : S.botBubble}>
                {m.role === 'assistant' ? (
                  <>
                    {m.segments.map((seg, i) =>
                      seg.kind === 'text' ? (
                        seg.text ? (
                          <Markdown key={i} fontSize={14}>
                            {seg.text}
                          </Markdown>
                        ) : null
                      ) : (
                        <TaskRow key={seg.frame.id} frame={seg.frame} />
                      ),
                    )}
                    {m.hitl?.toolCalls.map(call => (
                      <ModelingHitlCard
                        key={call.id}
                        call={call}
                        groupId={groupId}
                        overview={overview}
                        submitting={busy}
                        onDecision={(confirmed, toolInput, feedback) =>
                          void submitDecision(m.id, m.hitl as PendingHitl, call, confirmed, toolInput, feedback)
                        }
                      />
                    ))}
                    {m.pending && m.segments.length === 0 && !m.hitl && (
                      <span className="da-typing">
                        <span />
                        <span />
                        <span />
                      </span>
                    )}
                  </>
                ) : (
                  <div style={{ whiteSpace: 'pre-wrap' }}>
                    {m.segments.map(seg => (seg.kind === 'text' ? seg.text : '')).join('')}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>

        <div style={S.composer}>
          <div style={S.chips}>
            {onAttachDocument && <button style={S.chip} onClick={onAttachDocument} disabled={busy || awaitingDecision}>＋ 添加业务文档（可选）</button>}
          </div>
          <div style={S.composerRow}>
            <textarea
              ref={inputRef}
              style={S.textarea}
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={
                messages.some(message => message.hitl)
                  ? '请先处理上方待确认提案…'
                  : busy
                    ? '建模助手思考中…'
                    : '回答助手的口径问题，或反馈需要修正的内容…'
              }
              disabled={busy || awaitingDecision}
            />
            <button
              style={{
                ...S.send,
                ...(busy || awaitingDecision || !input.trim() ? S.sendDisabled : {}),
              }}
              aria-label="发送建模消息"
              onClick={handleSend}
              disabled={busy || awaitingDecision || !input.trim()}
            >
              <Icon name="send" size="sm" />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

const S: Record<string, CSSProperties> = {
  overlay: {
    position: 'fixed',
    inset: 0,
    background: 'rgba(15, 23, 42, 0.35)',
    zIndex: 200,
    display: 'flex',
    justifyContent: 'flex-end',
  },
  // specs/027: centered large window — the chat becomes the modeling page's main entry.
  overlayCenter: {
    position: 'fixed',
    inset: 0,
    background: 'rgba(15, 23, 42, 0.45)',
    zIndex: 200,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  // specs/030: inline right-side workbench dock — no overlay, the parent layout owns the width.
  dockRoot: {
    display: 'flex',
    flexDirection: 'column',
    width: '100%',
    height: '100%',
    minWidth: 0,
    overflow: 'hidden',
    background: 'var(--da-canvas-bg, #fafafa)',
    border: '1px solid var(--da-border)',
    borderRadius: 12,
  },
  dockShell: {
    display: 'flex',
    flexDirection: 'column',
    width: '100%',
    height: '100%',
    minWidth: 0,
  },
  panel: {
    width: 420,
    maxWidth: '92vw',
    height: '100%',
    background: 'var(--da-canvas-bg, #fafafa)',
    borderLeft: '1px solid var(--da-border)',
    boxShadow: 'var(--da-shadow-pop, -8px 0 24px rgba(0,0,0,0.12))',
    display: 'flex',
    flexDirection: 'column',
  },
  panelModal: {
    width: 'min(1100px, 86vw)',
    height: 'min(720px, 84vh)',
    background: 'var(--da-canvas-bg, #fafafa)',
    border: '1px solid var(--da-border)',
    borderRadius: 14,
    boxShadow: '0 24px 64px rgba(0,0,0,0.24)',
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden',
  },
  headerHint: {
    flex: 1,
    textAlign: 'right' as const,
    fontSize: 11.5,
    color: 'var(--da-text-muted)',
    paddingRight: 8,
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '12px 16px',
    borderBottom: '1px solid var(--da-border)',
    background: 'var(--da-surface, #fff)',
    flexShrink: 0,
  },
  title: { fontSize: 14, fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: 6 },
  close: {
    border: 'none',
    background: 'transparent',
    cursor: 'pointer',
    color: 'var(--da-text-muted)',
    padding: 4,
    display: 'inline-flex',
  },
  thread: { flex: 1, overflowY: 'auto', padding: 16, display: 'flex', flexDirection: 'column', gap: 12 },
  welcome: {
    display: 'flex',
    flexDirection: 'column',
    gap: 10,
    background: 'var(--da-surface, #fff)',
    border: '1px solid var(--da-border)',
    borderRadius: 12,
    padding: 16,
  },
  welcomeTitle: {
    fontSize: 14,
    fontWeight: 600,
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
  },
  welcomeDesc: { fontSize: 12.5, color: 'var(--da-text-muted)', lineHeight: 1.7 },
  steps: { margin: 0, paddingLeft: 18, fontSize: 12.5, color: 'var(--da-text)', lineHeight: 1.9 },
  quickCol: { display: 'flex', flexDirection: 'column', gap: 8 },
  quickPrimary: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    padding: '10px 12px',
    borderRadius: 10,
    border: 'none',
    background: 'var(--da-primary)',
    color: '#fff',
    cursor: 'pointer',
    fontSize: 13,
    fontWeight: 600,
    textAlign: 'left',
  },
  quickBtn: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    padding: '10px 12px',
    borderRadius: 10,
    border: '1px solid var(--da-border)',
    background: 'var(--da-surface, #fff)',
    color: 'var(--da-text)',
    cursor: 'pointer',
    fontSize: 13,
    textAlign: 'left',
  },
  quickHint: { fontSize: 11, fontWeight: 400, opacity: 0.75 },
  chips: { display: 'flex', gap: 6, flexWrap: 'wrap' },
  chip: {
    border: '1px solid var(--da-border)',
    background: 'var(--da-surface, #fff)',
    color: 'var(--da-text)',
    borderRadius: 999,
    padding: '4px 10px',
    fontSize: 12,
    cursor: 'pointer',
  },
  row: { display: 'flex', flexDirection: 'column' },
  userBubble: {
    alignSelf: 'flex-end',
    maxWidth: '85%',
    background: 'var(--da-primary)',
    color: '#fff',
    borderRadius: 12,
    borderBottomRightRadius: 4,
    padding: '8px 12px',
    fontSize: 13,
    whiteSpace: 'pre-wrap',
  },
  botBubble: {
    alignSelf: 'flex-start',
    maxWidth: '100%',
    width: '100%',
    boxSizing: 'border-box',
    minWidth: 0,
    background: 'var(--da-surface, #fff)',
    border: '1px solid var(--da-border)',
    borderRadius: 12,
    borderBottomLeftRadius: 4,
    padding: '8px 12px',
    fontSize: 13,
  },
  tools: { display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 8 },
  task: { display: 'flex', flexDirection: 'column', gap: 0, marginBottom: 4 },
  taskRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    border: 'none',
    background: 'transparent',
    padding: '3px 0',
    cursor: 'pointer',
    textAlign: 'left',
    fontSize: 12,
    color: 'var(--da-text)',
  },
  taskStatus: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    fontSize: 11,
    flexShrink: 0,
    minWidth: 52,
  },
  taskState: { fontSize: 11 },
  taskLabelText: {
    color: 'var(--da-text)',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  taskDetail: {
    margin: '2px 0 6px 4px',
    padding: '6px 8px',
    background: 'var(--da-surface-sunken, #f4f4f5)',
    borderRadius: 6,
  },
  taskDetailHead: {
    fontSize: 10.5,
    color: 'var(--da-text-muted)',
    marginBottom: 2,
    fontWeight: 600,
  },
  taskDetailPre: {
    margin: 0,
    padding: 0,
    fontSize: 11,
    lineHeight: 1.5,
    fontFamily: 'var(--da-font-mono, ui-monospace, monospace)',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
    color: 'var(--da-text)',
    maxHeight: 240,
    overflowY: 'auto',
  },
  composer: {
    display: 'flex',
    flexDirection: 'column',
    gap: 8,
    padding: 12,
    borderTop: '1px solid var(--da-border)',
    background: 'var(--da-surface, #fff)',
    flexShrink: 0,
  },
  composerRow: { display: 'flex', alignItems: 'flex-end', gap: 8 },
  textarea: {
    flex: 1,
    resize: 'none',
    border: '1px solid var(--da-border)',
    borderRadius: 10,
    padding: '8px 10px',
    fontSize: 13,
    minHeight: 60,
    maxHeight: 140,
    outline: 'none',
    fontFamily: 'inherit',
    color: 'var(--da-text)',
    background: 'var(--da-canvas-bg, #fafafa)',
  },
  send: {
    width: 34,
    height: 34,
    borderRadius: 999,
    border: 'none',
    background: 'var(--da-primary)',
    color: '#fff',
    cursor: 'pointer',
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    flexShrink: 0,
  },
  sendDisabled: {
    background: 'var(--da-surface-sunken, #eee)',
    color: 'var(--da-text-muted)',
    cursor: 'not-allowed',
  },
};
