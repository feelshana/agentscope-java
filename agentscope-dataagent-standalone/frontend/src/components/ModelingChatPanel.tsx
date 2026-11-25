import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import './ConversationPolish.css';
import './modeling/modeling-chat.css';
import type { CSSProperties, ReactNode } from 'react';
import Icon from './Icon';
import ModelingAssistantIcon from './modeling/ModelingAssistantIcon';
import Markdown from './Markdown';
import CopyAnswerButton from './CopyAnswerButton';
import { isSendKey, splitFailedAnswer } from '../utils/chatInteraction';
import ErrorNotice from './ErrorNotice';
import ModelingHitlCard from './ModelingHitlCard';
import ReadableCode from './ReadableCode';
import { confirmModeling, currentSession, stream } from '../api/chat';
import type { ChatEvent, HitlToolCall, TurnPurpose } from '../api/chat';
import { messagePage } from '../api/sessions';
import { confirmationReceipt, recentCoachTurns, restoreModelingHistory } from './modeling/modelingHistory';
import type { PanelMessage, PendingHitl, Segment, ToolFrame } from './modeling/modelingHistory';
import type { CoachRequest } from './modeling/queryCoachScope';
import type { CoachConversationTurn } from '../api/modelingQuestions';
import type { ModelingPrompt } from './modeling/modelingPrompts';
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

/** Project paths in the modeler's words; platform and skill internals stay generic. */
function pathLabel(path: string, verb: 'read' | 'write'): string {
  const model = path.match(/models\/([^/]+)\//)?.[1];
  if (/skills?\//i.test(path) || /SKILL\.md$/i.test(path)) return '学习建模方法';
  if (path.includes('raw/docs/')) return `阅读业务文档 ${baseName(path)}`;
  if (/gap_catalog|sampl|profile/i.test(path)) return '阅读采样资料';
  if (model) return `${verb === 'read' ? '查看' : '完善'}模型 ${model}`;
  if (path.includes('relationships')) return verb === 'read' ? '查看表关系' : '整理表关系';
  if (path.includes('knowledge/rules')) return verb === 'read' ? '查看业务规则' : '写入业务规则';
  if (path.includes('knowledge/caveats')) return verb === 'read' ? '查看待确认说明' : '记录待确认说明';
  if (path.includes('knowledge/')) return verb === 'read' ? '查看业务知识' : '写入业务知识';
  return verb === 'read' ? `读取 ${baseName(path)}` : `修改 ${baseName(path)}`;
}

/** 工具名 + 关键参数 → 中文任务标签（specs/021）。 */
function taskLabel(name: string, inputJson?: string): string {
  const input = parseToolInput(inputJson);
  const path = typeof input.path === 'string' ? (input.path as string) : '';
  switch (name) {
    case 'wren_cli': {
      const args = Array.isArray(input.arguments) ? input.arguments.filter((value): value is string => typeof value === 'string') : [];
      const operation = args.slice(0, 2).join(' ');
      const labels: Record<string, string> = {
        'context show': '查看模型现状', 'context validate': '校验模型',
        'context build': '编译检查', 'context instructions': '读取业务规则',
        'memory store': '保存问题与 SQL 示例', 'memory recall': '查找相似问题',
        'memory fetch': '检索语义上下文', 'memory index': '更新检索索引',
        'memory status': '查看检索状态', 'skills get': '学习建模方法',
      };
      return labels[operation] || (args[0] === 'query' ? '查询数据验证口径' : args[0] === 'dry-plan' ? '检查查询计划' : args[0] === 'dry-run' ? '试跑查询' : '检查模型');
    }
    case 'load_skill_through_path':
      return '学习建模方法';
    case 'request_user_confirmation':
      return '整理需要你确定的口径';
    case 'propose_modeling_change':
      return '提议建设查询模板';
    case 'glob_files':
    case 'grep_files':
      return '查找资料';
    case 'list_modeling_state':
      return '盘点建模现状';
    case 'list_files':
      return '列出工程文件';
    case 'read_file':
      return path ? pathLabel(path, 'read') : '读取资料';
    case 'write_file':
      if (typeof input.files_json === 'string') return '提交完整业务方案';
      return path ? pathLabel(path, 'write') : '写入草稿';
    case 'patch_file':
    case 'edit_file':
      return path ? pathLabel(path, 'write') : '修改草稿';
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
type Part = { kind: 'text'; text: string } | { kind: 'tools'; frames: ToolFrame[] };

/** Consecutive tool calls form one group, so a reply reads as text with folded work steps. */
function groupSegments(segments: Segment[]): Part[] {
  const parts: Part[] = [];
  for (const seg of segments) {
    const last = parts[parts.length - 1];
    if (seg.kind === 'text') parts.push({ kind: 'text', text: seg.text });
    else if (last?.kind === 'tools') last.frames.push(seg.frame);
    else parts.push({ kind: 'tools', frames: [seg.frame] });
  }
  return parts;
}

const TOOL_KIND: Record<string, string> = {
  read_file: '读取', list_files: '读取', glob_files: '读取', grep_files: '读取', load_skill_through_path: '读取',
  write_file: '修改', edit_file: '修改', delete_file: '修改', wren_cli: '检查', request_user_confirmation: '提问', propose_modeling_change: '提议',
};

/**
 * spec092 §7: finished work steps fold into one line like a coding agent; a group with a running
 * step or a pending decision stays open, and failures are counted in the folded line.
 */
function ToolGroup({ frames }: { frames: ToolFrame[] }) {
  const [open, setOpen] = useState(false);
  if (frames.length <= 2 || frames.some(f => f.state === 'running' || f.state === 'waiting')) {
    return <>{frames.map(f => <TaskRow key={f.id} frame={f} />)}</>;
  }
  const counts = new Map<string, number>();
  frames.forEach(f => { const k = TOOL_KIND[f.name] ?? '其他'; counts.set(k, (counts.get(k) ?? 0) + 1); });
  const failed = frames.filter(f => f.state === 'failed').length;
  const summary = [...counts].map(([k, n]) => `${k} ${n}`).join(' · ');
  return <div style={{ margin: '4px 0' }}>
    <button type="button" onClick={() => setOpen(v => !v)}
      style={{ background: 'none', border: 'none', padding: '2px 0', cursor: 'pointer', fontSize: 12, color: 'var(--da-text-muted)' }}>
      {open ? '▾' : '▸'} 执行了 {frames.length} 步：{summary}{failed ? ` · ${failed} 步失败` : ''}
    </button>
    {open && frames.map(f => <TaskRow key={f.id} frame={f} />)}
  </div>;
}

/**
 * spec092 §7: a reply is its result, not its process. Narration and tool steps fold into one
 * line with live counts (the latest step while running); the final text is shown in full. Steps
 * waiting for a decision stay visible.
 */
function ReplyBody({ segments, pending, startedAt }: { segments: Segment[]; pending: boolean; startedAt?: string }) {
  const [open, setOpen] = useState(false);
  const parts = groupSegments(segments);
  let last = -1;
  if (!pending)
    for (let i = parts.length - 1; i >= 0; i--) {
      const part = parts[i];
      if (part.kind === 'text' && part.text.trim()) { last = i; break; }
    }
  const process = parts.filter((_, i) => i !== last);
  const finalText = last >= 0 && parts[last].kind === 'text' ? (parts[last] as { kind: 'text'; text: string }).text : '';
  const failure = /\[(?:错误|error)\]/.test(finalText) ? splitFailedAnswer(finalText) : null;
  const frames = process.flatMap(p => p.kind === 'tools' ? p.frames : []);
  const waiting = frames.filter(f => f.state === 'waiting');
  const counts = new Map<string, number>();
  frames.forEach(f => { const k = TOOL_KIND[f.name] ?? '其他'; counts.set(k, (counts.get(k) ?? 0) + 1); });
  const failed = frames.filter(f => f.state === 'failed').length;
  const running = [...frames].reverse().find(f => f.state === 'running');
  const narration = [...process].reverse().find(p => p.kind === 'text' && p.text.trim());
  // The step in progress (or the last one) reads better than the model's own narration.
  const lastFrame = running ?? frames[frames.length - 1];
  const latest = lastFrame ? taskLabel(lastFrame.name, lastFrame.input)
    : narration && narration.kind === 'text' ? narration.text.trim().split(/\r?\n/)[0].slice(0, 60) : '';
  const stats = [...counts].map(([k, n]) => `${k} ${n}`).join(' · ');
  const hasProcess = process.some(p => p.kind === 'tools' || p.text.trim());
  // Milestones read from the tool calls, so a minute of work shows where it is.
  const did = (names: string[]) => frames.some(f => names.includes(f.name));
  const command = (word: string) => frames.some(f => f.name === 'wren_cli' && (f.input ?? '').includes(word));
  const stages = [
    { label: '读取资料', done: did(['read_file', 'load_skill_through_path', 'list_files', 'glob_files']) },
    { label: '确定口径', done: did(['request_user_confirmation']) },
    { label: '写入草稿', done: did(['write_file', 'edit_file']) },
    { label: '校验编译', done: command('build') },
  ];
  const reached = stages.reduce((n, stage, i) => stage.done ? i + 1 : n, 0);
  return <>
    {pending && <ProgressCard stages={stages} reached={reached} latest={latest || '正在理解你的数据'} steps={frames.length} failed={failed} startedAt={startedAt} />}
    {hasProcess && <div style={{ margin: '2px 0 6px' }}>
      <button type="button" aria-expanded={open} onClick={() => setOpen(v => !v)} className="mc-fold"
        style={{ display: 'flex', alignItems: 'center', gap: 6, width: '100%',
          cursor: 'pointer', fontSize: 12, color: 'var(--da-text-muted)', textAlign: 'left' }}>
        <span>{open ? '▾' : '▸'}</span>
        <span style={{ whiteSpace: 'nowrap' }}>{pending ? (open ? '收起过程明细' : '查看过程明细') : '建模过程'}</span>
        {!pending && frames.length > 0 && <span style={{ whiteSpace: 'nowrap' }}>· {frames.length} 步{stats ? `（${stats}）` : ''}</span>}
        {!pending && failed > 0 && <span style={{ color: 'var(--da-danger)', whiteSpace: 'nowrap' }}>· {failed} 步失败</span>}
      </button>
      {open && <div style={{ borderLeft: '2px solid var(--da-border)', paddingLeft: 10, marginTop: 4 }}>
        {process.map((part, i) => part.kind === 'text'
          ? (part.text.trim() ? <div key={i} style={{ fontSize: 12.5, color: 'var(--da-text-muted)' }}><Markdown fontSize={12.5}>{part.text}</Markdown></div> : null)
          : <ToolGroup key={part.frames[0].id} frames={part.frames} />)}
      </div>}
    </div>}
    {!open && waiting.map(f => <TaskRow key={f.id} frame={f} />)}
    {last >= 0 && parts[last].kind === 'text' && <div style={{ color: 'var(--da-text)' }}>
      {(!failure || failure.answer) && <Markdown fontSize={14}>{failure ? failure.answer : finalText}</Markdown>}
      {failure && <ErrorNotice error={failure.error} context="chat" />}
      {!pending && !failure && <CopyAnswerButton text={finalText} />}
    </div>}
  </>;
}

/** spec092 §7: a running turn shows where modeling is and what it is doing now. */
function ProgressCard({ stages, reached, latest, steps, failed, startedAt }: {
  stages: { label: string; done: boolean }[]; reached: number; latest: string; steps: number; failed: number; startedAt?: string;
}) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const t = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(t); }, []);
  const seconds = startedAt ? Math.max(0, Math.round((now - Date.parse(startedAt)) / 1000) - 2) : 0;
  const elapsed = seconds >= 60 ? `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒` : `${seconds} 秒`;
  const current = Math.max(reached - 1, 0);
  return <div aria-live="polite" style={{ margin: '0 0 10px', padding: '13px 14px 11px', borderRadius: 12, background: 'var(--da-surface-sunken)', border: '1px solid var(--da-border)' }}>
    <ol style={{ display: 'flex', listStyle: 'none', margin: 0, padding: 0 }}>
      {stages.map((stage, i) => {
        const state = i < current ? 'done' : i === current ? 'current' : 'todo';
        const color = state === 'done' ? 'var(--da-success)' : state === 'current' ? 'var(--da-primary)' : 'var(--da-text-muted)';
        return <li key={stage.label} style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <span style={{ width: 22, height: 22, borderRadius: 11, flex: 'none', display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
              fontSize: 12, fontWeight: 600, boxSizing: 'border-box',
              background: state === 'done' ? 'var(--da-success)' : state === 'current' ? 'var(--da-primary)' : 'var(--da-surface)',
              color: state === 'todo' ? 'var(--da-text-muted)' : '#fff', border: state === 'todo' ? '1.5px solid var(--da-border)' : 'none',
              boxShadow: state === 'current' ? '0 0 0 4px color-mix(in srgb, var(--da-primary) 18%, transparent)' : undefined }}>
              {state === 'done' ? '✓' : i + 1}</span>
            {i < stages.length - 1 && <span style={{ flex: 1, height: 2, margin: '0 8px', borderRadius: 1,
              background: i < current ? 'var(--da-success)' : 'var(--da-border)' }} />}
          </div>
          <span style={{ fontSize: 12.5, color, fontWeight: state === 'current' ? 600 : 400 }}>{stage.label}</span>
        </li>;
      })}
    </ol>
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 13, padding: '10px 12px', borderRadius: 10,
      background: 'var(--da-surface)', border: '1px solid var(--da-border)' }}>
      <span className="da-spinner da-spinner-sm" style={{ flex: 'none', borderColor: 'var(--da-border)', borderTopColor: 'var(--da-primary)' }} />
      <span style={{ flex: 1, minWidth: 0, fontSize: 14, fontWeight: 500, color: 'var(--da-text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{latest}</span>
      <span style={{ flex: 'none', fontSize: 12, color: 'var(--da-text-muted)' }}>
        已用 {elapsed}{steps ? ` · ${steps} 步` : ''}{failed ? <span style={{ color: 'var(--da-danger)' }}> · {failed} 步失败</span> : null}</span>
    </div>
  </div>;
}

function TaskRow({ frame }: { frame: ToolFrame }) {
  const [open, setOpen] = useState(false);
  const label = taskLabel(frame.name, frame.input);
  const input = parseToolInput(frame.input);
  const writeContent = frame.name === 'write_file' ? input.content : frame.name === 'patch_file' ? input.replacement : frame.name === 'edit_file' ? input.new_string : frame.name === 'create_view' ? input.statement : undefined;
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
            : frame.state === 'unrecorded' ? '无结果记录' : '完成';
  return (
    <div style={S.task}>
      <button
        className="mc-task"
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
                      : frame.state === 'unrecorded' ? 'info' : 'check'
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

/** What the modeling assistant is doing: a queued request waits for 'idle'. */
export type AssistantState = 'idle' | 'running' | 'awaiting';

export default function ModelingChatPanel({
  groupId,
  onClose,
  variant = 'modal',
  draftPrompt,
  draftPromptId,
  submitDraft = false,
  onSessionReady,
  onAttachDocument,
  onDraftSubmitted,
  onTurnDone,
  draftPurpose,
  draftQuestionId,
  draftLabel,
  onActivityChange,
  renderTurnResult,
  renderQuestionResult,
  resolveCoachRequest,
  resolveCoachAction,
  coachScope,
  onChooseCoachQuestion,
  intro,
  assetChat = false,
  quickPrompts,
  composerNotice,
  composerHint,
  composerFocus = 0,
  composerSuggestion,
  composerLocked = false,
  onCompose,
  footer,
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
  onDraftSubmitted?: () => void;
  /** spec090: fired when a modeling turn finishes; `awaitingConfirmation` is true while a
   *  confirmation card from that turn is still undecided. */
  onTurnDone?: (awaitingConfirmation: boolean) => void;
  /** Sent with the draft so the server can label and gate the turn (spec092). */
  draftPurpose?: TurnPurpose;
  draftQuestionId?: string;
  /** What the conversation shows for a platform-written draft; the full text goes to the assistant. */
  draftLabel?: string;
  /** Running turn or undecided confirmation card; drafts sent meanwhile queue behind it. */
  onActivityChange?: (state: AssistantState) => void;
  /** spec092 §7: "AI 建模结果" under a finished reply, given the turn's start time. */
  renderTurnResult?: (startedAt: string) => ReactNode;
  renderQuestionResult?: (id: string) => ReactNode;
  resolveCoachRequest?: (text: string) => CoachRequest | 'handled' | null | undefined;
  resolveCoachAction?: (id: string, text: string, recentTurns: CoachConversationTurn[]) => Promise<string | null>;
  coachScope?: ReactNode;
  onChooseCoachQuestion?: () => void;
  intro?: ReactNode;
  assetChat?: boolean;
  quickPrompts?: ModelingPrompt[];
  composerNotice?: ReactNode;
  composerHint?: string;
  composerFocus?: number;
  composerSuggestion?: string;
  composerLocked?: boolean;
  /** Handle a preparation command before sending anything to the agent. */
  onCompose?: (text: string) => boolean;
  /** spec092 §7: inline cards after the conversation, e.g. "需要你确定的部分". */
  footer?: ReactNode;
}) {
  const [messages, setMessages] = useState<PanelMessage[]>([]);
  const [input, setInput] = useState('');
  const [promptsOpen, setPromptsOpen] = useState(false);
  const [selectedPrompt, setSelectedPrompt] = useState<ModelingPrompt | null>(null);
  const promptTrigger = useRef<HTMLButtonElement>(null);
  const promptPanel = useRef<HTMLElement>(null);
  const promptPanelId = useId();
  const insertedPrompt = useRef<string | null>(null);
  const [sessionReady, setSessionReady] = useState(false);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [historyError, setHistoryError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [resolvingCoachAction, setResolvingCoachAction] = useState(false);
  const decisionInFlight = useRef(false);
  const [overview, setOverview] = useState<ModelingOverview | null>(null);
  const threadRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const suggestionId = useId();
  const composing = useRef(false);
  const consumedDraft = useRef<string | null>(null);
  const turnDone = useRef(onTurnDone);
  turnDone.current = onTurnDone;
  // Set when the current stream asked for confirmation; cleared once the user decides.
  const turnCompleted = useRef(false);
  const consumedSubmission = useRef<string | null>(null);
  const sessionKeyRef = useRef<string>(`modeling-${groupId}`);
  const awaitingDecision = messages.some(message => message.hitl);

  useEffect(() => {
    if (composerFocus > 0) inputRef.current?.focus();
  }, [composerFocus]);

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
    const controller = new AbortController();
    sessionKeyRef.current = `modeling-${groupId}`;
    setMessages([]);
    setOverview(null);
    setSessionReady(false); setSessionError(null); setHistoryError(false);
    void loadOverview();
    currentSession(MODELING_AGENT_ID, sessionKeyRef.current, controller.signal)
      .then(async session => {
        if (controller.signal.aborted) return;
        let entries: Awaited<ReturnType<typeof messagePage>>['items'] = [];
        if (session.exists && session.sessionKey) {
          try {
            const history = await messagePage(MODELING_AGENT_ID, session.sessionKey, undefined, controller.signal);
            entries = history.items;
            let before = history.nextBefore;
            // A long tool run may fill the latest page without its initiating user message.
            while (before != null && !entries.some(entry => entry.role === 'USER')) {
              const previous = await messagePage(MODELING_AGENT_ID, session.sessionKey, before, controller.signal);
              entries = [...previous.items, ...entries];
              before = previous.nextBefore;
            }
          } catch { if (!controller.signal.aborted) setHistoryError(true); }
        }
        if (controller.signal.aborted) return;
        const hitl = session.pendingReplyId && session.pendingToolCalls?.length
          ? { replyId: session.pendingReplyId, toolCalls: session.pendingToolCalls } : undefined;
        pinned.current = true;
        setMessages(restoreModelingHistory(entries, hitl));
        setSessionReady(true);
        onSessionReady?.(!!hitl);
      })
      .catch(error => { if (!controller.signal.aborted) {
        const detail = `建模会话加载失败，请刷新重试。${error instanceof Error ? error.message : ''}`;
        setSessionError(detail); onSessionReady?.(false, detail);
      } });
    return () => controller.abort();
  }, [groupId, onSessionReady]);

  // spec092 §7: follow the conversation while the user is at the bottom. Result cards and
  // confirmation cards load asynchronously after a turn, so DOM changes (not only new messages)
  // keep the view pinned; scrolling up to read stops the following until the user returns.
  const pinned = useRef(true);
  useEffect(() => {
    const thread = threadRef.current;
    if (!thread) return;
    const follow = () => { if (pinned.current && thread.clientHeight > 0) thread.scrollTo({ top: thread.scrollHeight }); };
    const onScroll = () => { pinned.current = thread.scrollHeight - thread.scrollTop - thread.clientHeight < 80; };
    const observer = new MutationObserver(follow);
    observer.observe(thread, { childList: true, subtree: true, characterData: true });
    const resize = new ResizeObserver(follow);
    resize.observe(thread);
    thread.addEventListener('scroll', onScroll, { passive: true });
    follow();
    return () => { observer.disconnect(); resize.disconnect(); thread.removeEventListener('scroll', onScroll); };
  }, []);
  useEffect(() => {
    if (pinned.current) threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight });
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
      // spec091/092: the review page shows each draft write as soon as it lands, not per turn.
      const written = evt.toolResult ?? '';
      if (!written.startsWith('error') && /Written to |Edited |已撤回工程文件|已提交给用户确认/.test(written)) notifyUpdated();
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
      turnCompleted.current = true;
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

  /** Restores persisted pending confirmations; returns true when any card still awaits a decision. */
  async function restorePendingProposal(messageId: string): Promise<boolean> {
    try {
      const session = await currentSession(MODELING_AGENT_ID, sessionKeyRef.current);
      if (session.pendingReplyId && session.pendingToolCalls?.length) {
        applyEvent(messageId, { type: 'hitl_request', replyId: session.pendingReplyId, toolCalls: session.pendingToolCalls });
        return true;
      }
      return false;
    } catch (error) {
      applyEvent(messageId, { type: 'error', error: `建模确认状态同步失败，请刷新页面恢复提案。${error instanceof Error ? error.message : ''}` });
      return true;
    }
  }

  /**
   * Reports a finished turn only after the stream ended and the server-side pending state was
   * reconciled: a batch decision may emit {@code done} before the remaining cards are restored.
   */
  async function finishTurn(messageId: string) {
    const pending = await restorePendingProposal(messageId);
    if (turnCompleted.current) turnDone.current?.(pending);
  }

  /** Sends a user message and reconciles persisted native confirmation state. */
  async function sendText(raw: string, purpose?: TurnPurpose, label?: string, questionId?: string, fromQueue = false) {
    const text = raw.trim();
    if (!text || busy || awaitingDecision) return;
    if (!fromQueue) setInput('');
    setPromptsOpen(false);
    setSelectedPrompt(null);
    insertedPrompt.current = null;
    setBusy(true);
    pinned.current = true;
    turnCompleted.current = false;
    const userMsg: PanelMessage = { id: nextId(), role: 'user', confirmation: purpose === 'confirm' ? confirmationReceipt(text) : undefined,
      segments: [{ kind: 'text', text: label?.trim() || text }] };
    const replyMsg: PanelMessage = {
      id: nextId(),
      role: 'assistant',
      segments: [],
      pending: true,
      questionId,
      // A little earlier than now, so writes are never missed through clock skew.
      startedAt: new Date(Date.now() - 2000).toISOString(),
    };
    setMessages(prev => [...prev, userMsg, replyMsg]);
    try {
      for await (const evt of stream(
        MODELING_AGENT_ID,
        { message: text, sessionKey: sessionKeyRef.current, groupIds: [groupId], purpose, modelingQuestionId: questionId },
      )) {
        applyEvent(replyMsg.id, evt);
        if (evt.type === 'error' && questionId) {
          if (!fromQueue) setInput(previous => previous || label || '');
          notifyUpdated();
        }
      }
      await finishTurn(replyMsg.id);
    } catch (e) {
      if (questionId) {
        if (!fromQueue) setInput(previous => previous || label || '');
        notifyUpdated();
      }
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
    confirmBatch = false,
  ) {
    if (busy || decisionInFlight.current) return;
    decisionInFlight.current = true;
    turnCompleted.current = false;
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
        confirmBatch,
      })) {
        applyEvent(messageId, evt);
      }
      await finishTurn(messageId);
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

  async function sendCoach(coach: CoachRequest) {
    const { questionId, label } = coach;
    if (coach.interpretAction && questionId && resolveCoachAction) {
      setResolvingCoachAction(true);
      setPromptsOpen(false);
      setSelectedPrompt(null);
      insertedPrompt.current = null;
      try {
        const reply = await resolveCoachAction(questionId, label, recentCoachTurns(messages, questionId));
        if (reply !== null) {
          pinned.current = true;
          setInput('');
          setMessages(previous => [...previous,
            { id: nextId(), role: 'user', segments: [{ kind: 'text', text: label }] },
            { id: nextId(), role: 'assistant', questionId, segments: [{ kind: 'text', text: reply }] },
          ]);
          return;
        }
      } finally {
        setResolvingCoachAction(false);
      }
    }
    await sendText(coach.text, coach.purpose, label, questionId);
  }

  function handleSend() {
    if (busy || resolvingCoachAction || awaitingDecision) return;
    if (input.trim() && onCompose?.(input.trim())) {
      setInput('');
      return;
    }
    if (composerLocked || !sessionReady) return;
    const coach = input.trim() ? resolveCoachRequest?.(input.trim()) : undefined;
    if (coach === 'handled') { setInput(''); return; }
    if (coach === null) return;
    if (coach) void sendCoach(coach);
    else void sendText(input);
  }

  useEffect(() => {
    const key = `${groupId}:${draftPromptId ?? draftPrompt}`;
    if (submitDraft && draftPrompt && !composerLocked && sessionReady && !busy && !resolvingCoachAction && !awaitingDecision && consumedSubmission.current !== key) {
      consumedSubmission.current = key;
      onDraftSubmitted?.();
      void sendText(draftPrompt, draftPurpose, draftLabel, draftQuestionId, true);
    }
  }, [submitDraft, draftPrompt, draftPromptId, draftPurpose, draftQuestionId, draftLabel, sessionReady, busy, resolvingCoachAction, awaitingDecision, groupId, composerLocked]);

  // Lets the page show that a request will queue behind the running turn or pending cards.
  const activity = useRef(onActivityChange);
  activity.current = onActivityChange;
  const state: AssistantState = awaitingDecision ? 'awaiting' : busy ? 'running' : 'idle';
  useEffect(() => { activity.current?.(state); }, [state]);

  const inputDisabled = busy || resolvingCoachAction || awaitingDecision || (!onCompose && (composerLocked || !sessionReady));
  const showPrompts = !!quickPrompts?.length && promptsOpen && !inputDisabled;
  useLayoutEffect(() => {
    if (showPrompts) promptPanel.current?.querySelector<HTMLButtonElement>('[data-quick-focus]')?.focus();
  }, [showPrompts, selectedPrompt]);

  function closePrompts(restoreFocus = false) {
    setPromptsOpen(false);
    setSelectedPrompt(null);
    if (restoreFocus) promptTrigger.current?.focus();
  }

  function fillPrompt(prompt: ModelingPrompt, append = false) {
    insertedPrompt.current = append ? null : prompt.text;
    setInput(previous => append && previous.trim() ? `${previous.trimEnd()}\n\n${prompt.text}` : prompt.text);
    closePrompts();
    inputRef.current?.focus();
  }

  function choosePrompt(prompt: ModelingPrompt) {
    if (input.trim() && input !== insertedPrompt.current && input !== prompt.text) {
      setSelectedPrompt(prompt);
    } else {
      fillPrompt(prompt);
    }
  }

  const suggestion = !inputDisabled && input === '' ? composerSuggestion : undefined;
  const placeholder = awaitingDecision ? '请先处理上方待确认提案…'
    : busy ? '建模助手思考中…'
    : composerHint ?? '描述要完善的字段、关系或业务口径…';
  const resizeInput = useCallback(() => {
    const textarea = inputRef.current;
    if (!textarea?.clientWidth) return;
    textarea.style.removeProperty('--mc-input-height');
    textarea.style.setProperty('--mc-input-height', `${Math.min(160, Math.max(48, textarea.scrollHeight))}px`);
  }, []);
  useLayoutEffect(resizeInput, [input, resizeInput]);

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (suggestion && e.key === 'Tab' && !e.shiftKey && !e.altKey && !e.ctrlKey && !e.metaKey &&
        !composing.current && !e.nativeEvent.isComposing && e.nativeEvent.keyCode !== 229) {
      e.preventDefault();
      setInput(suggestion);
      return;
    }
    if (isSendKey({ key: e.key, shiftKey: e.shiftKey, isComposing: e.nativeEvent.isComposing, keyCode: e.nativeEvent.keyCode }, composing.current)) {
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
        onKeyDown={event => {
          if (showPrompts && event.key === 'Escape' && !event.nativeEvent.isComposing) {
            event.stopPropagation();
            closePrompts(true);
          }
        }}
      >
        {variant !== 'dock' && <div style={S.header}>
          <span style={S.title}>
            <ModelingAssistantIcon /> 对话建模
          </span>
          <span style={S.headerHint}>完善草稿 → 助手检查 → 发布</span>
          <button style={S.close} onClick={handleClose} title="关闭">
            <Icon name="close" size="sm" />
          </button>
        </div>}

        <div className="mc-thread" style={variant === 'dock' ? S.dockThread : S.thread} ref={threadRef}>
          {sessionError && <p role="alert" style={{ margin: '0 0 12px', padding: '10px 12px', borderRadius: 10, background: 'color-mix(in srgb, var(--da-danger) 5%, var(--da-surface))', border: '1px solid color-mix(in srgb, var(--da-danger) 22%, transparent)', color: 'var(--da-danger)', fontSize: 12.5, lineHeight: 1.6 }}>{sessionError}</p>}
          {historyError && <p role="status" className="da-small">最近的建模记录加载失败。刷新后可重试。</p>}
          {intro}
          {!intro && messages.length === 0 && (
            <div className="mc-welcome" style={S.welcome}>
              <div style={S.welcomeTitle}>
                <ModelingAssistantIcon /> {assetChat ? '核对公共视图' : '建模助手'}
              </div>
              <div style={S.welcomeDesc}>{assetChat ? '从一个问题开始，核对视图的使用范围。' : '告诉助手要完善的字段、关系或业务口径。你可以在模型中检查改动，准备好后发布。'}</div>
              {(!quickPrompts || assetChat) && <div className="mc-welcome-examples">{(assetChat ? [
                ['能不能拆维度', '核对这张视图能按哪些维度拆分，哪些会改变统计粒度。'],
                ['口径对不对', '对照来源检查指标、过滤条件和关联口径。'],
                ['加一句使用限制', '帮我说明这张视图适用和不适用的场景。'],
              ] : [
                ['增加术语', '"金卡客户"指会员等级为金卡会员的客户'],
                ['增加规则', '统计有效订单时，默认排除已取消的订单'],
                ['补充字段说明', '给订单表的金额字段补充说明和单位'],
                ['新增关系', '订单表按客户编号关联客户表，每笔订单对应一个客户'],
              ]).map(([title, text]) => <button key={title} type="button" className="mc-ex" style={S.welcomeExample}
                title="填入输入框，可修改后发送" onClick={() => setInput(`${title}：${text}`)}>
                <strong style={{ color: 'var(--da-text)', fontSize: 13.5, fontWeight: 650, letterSpacing: '-.01em' }}>{title}</strong>
                <span style={{ fontSize: 12.5, lineHeight: 1.5 }}>{text}</span>
              </button>)}</div>}
            </div>
          )}
          {messages.map(m => (
            <div key={m.id} className={m.role === 'assistant' && variant === 'dock' ? 'mc-assistant-row' : undefined} style={m.role === 'user' ? S.row : S.botRow}>
              {m.role === 'assistant' && variant !== 'dock' && <ModelingAssistantIcon message />}
              <div className={m.role === 'user' ? 'mc-user-bubble' : 'mc-assistant-bubble'} style={m.role === 'user' ? S.userBubble : S.botBubble}>
                {m.role === 'assistant' ? (
                  <>
                    {variant === 'dock' && <ModelingAssistantIcon message />}
                    <ReplyBody segments={m.segments} pending={!!m.pending} startedAt={m.startedAt} />
                    {m.hitl && m.hitl.toolCalls.length > 1 && <div style={{ marginTop: 10, padding: '13px 14px', border: '1px solid color-mix(in srgb, var(--da-primary) 16%, var(--da-border))', borderRadius: 12, background: 'var(--da-surface)' }}>
                      <strong style={{ fontSize: 13.5, fontWeight: 650 }}>本次工程变更：{m.hitl.toolCalls.length} 项</strong>
                      <p style={{ margin: '6px 0 10px', fontSize: 12.5, lineHeight: 1.75, color: 'var(--da-text-3)' }}>下面列出本次全部变更，可以一次授权。相同文件的多条变更会按列表顺序依次应用，全部授权会全部执行，不会自动在两条内容中择一；写入后助手继续官方检查，发布仍需单独操作。</p>
                      <button className="da-btn da-btn-primary da-btn-sm" disabled={busy} onClick={() => void submitDecision(m.id, m.hitl as PendingHitl, m.hitl!.toolCalls[0], true, undefined, undefined, true)}>一次授权全部变更</button>
                    </div>}
                    {m.hitl?.toolCalls.map((call, index, calls) => {
                      const path = typeof call.input.path === 'string' ? call.input.path : '';
                      const samePath = path ? calls.filter(other => other.input.path === path).length : 0;
                      return <div key={call.id}>
                        {calls.length > 1 && <div style={{ margin: '10px 0 4px', color: 'var(--da-text-muted)', fontSize: 11.5, fontWeight: 600, letterSpacing: '.01em' }}>
                          变更 {index + 1}/{calls.length}{samePath > 1 ? ` · 同一文件还有 ${samePath - 1} 条待处理变更` : ''}
                        </div>}
                        <ModelingHitlCard
                          call={call}
                          groupId={groupId}
                          overview={overview}
                          submitting={busy}
                          onDecision={(confirmed, toolInput, feedback) =>
                            void submitDecision(m.id, m.hitl as PendingHitl, call, confirmed, toolInput, feedback)
                          }
                        />
                      </div>;
                    })}
                    {!m.pending && !m.hitl && (m.questionId ? renderQuestionResult?.(m.questionId)
                      : m === messages[messages.length - 1] && m.startedAt && renderTurnResult?.(m.startedAt))}
                    {m.pending && m.segments.length === 0 && !m.hitl && (
                      <span className="da-typing">
                        <span />
                        <span />
                        <span />
                      </span>
                    )}
                  </>
                ) : m.confirmation ? (
                  <details className="mc-confirmation-receipt">
                    <summary>已确认 {m.confirmation.length} 项口径</summary>
                    <dl>{m.confirmation.map((decision, index) => <div key={index}><dt>{decision.question}</dt><dd>{decision.answer}</dd></div>)}</dl>
                  </details>
                ) : (
                  <div style={{ whiteSpace: 'pre-wrap' }}>
                    {m.segments.map(seg => (seg.kind === 'text' ? seg.text : '')).join('')}
                  </div>
                )}
              </div>
            </div>
          ))}
          {footer}
          {showPrompts && <section className="mc-quick-panel" ref={promptPanel} id={promptPanelId} aria-labelledby={`${promptPanelId}-title`}>
            <header className="mc-quick-header">
              <div><h2 id={`${promptPanelId}-title`}>{selectedPrompt ? '使用这条提示' : '快捷提示'}</h2>
                <p>{selectedPrompt ? '输入框已有内容，可追加这条提示或替换。' : '选一个想完善的方向，填入后可以继续编辑。'}</p>
              </div>
              <button type="button" className="mc-quick-close" aria-label="关闭快捷提示" onClick={() => closePrompts(true)}><Icon name="close" size="sm" /></button>
            </header>
            {selectedPrompt ? <>
              <p className="mc-quick-preview">{selectedPrompt.text}</p>
              <div className="mc-quick-actions">
                <button type="button" className="da-btn da-btn-sm mc-quick-back" onClick={() => setSelectedPrompt(null)}>返回建议</button>
                <button type="button" className="da-btn da-btn-sm" onClick={() => fillPrompt(selectedPrompt)}>替换输入</button>
                <button type="button" className="da-btn da-btn-primary da-btn-sm" data-quick-focus onClick={() => fillPrompt(selectedPrompt, true)}>追加到末尾</button>
              </div>
            </> : <div className="mc-quick-choices">
              {quickPrompts?.map((prompt, index) => <button type="button" className="mc-quick-choice" key={prompt.title}
                data-quick-focus={index === 0 ? '' : undefined} onClick={() => {
                  if (onChooseCoachQuestion && prompt.title === '校对问数口径') {
                    closePrompts(false);
                    onChooseCoachQuestion();
                  } else choosePrompt(prompt);
                }}>
                <span className="mc-quick-choice-icon"><Icon name={prompt.icon} size="sm" /></span>
                <span><strong>{onChooseCoachQuestion && prompt.title === '校对问数口径' ? '选一道题优化' : prompt.title}</strong>
                  <small>{onChooseCoachQuestion && prompt.title === '校对问数口径' ? '指定一道业务题，核对或修正口径' : prompt.detail}</small></span>
                <span className="mc-quick-insert" aria-hidden="true"><Icon name="plus" size="sm" /></span>
              </button>)}
            </div>}
          </section>}
        </div>

        <div className="mc-composer" style={S.composer}>
          {composerNotice}
          {coachScope}
          {resolvingCoachAction && <div className="da-small" role="status">正在处理这条消息…</div>}
          <div className="da-composer-glow">
            <div className={`da-composer-shell da-composer${quickPrompts?.length ? ' mc-composer-with-prompts' : ''}`}>
              {!!quickPrompts?.length && <button type="button" ref={promptTrigger} className="mc-quick-trigger" aria-label="快捷提示"
                title="快捷提示" aria-expanded={showPrompts} aria-controls={showPrompts ? promptPanelId : undefined} disabled={inputDisabled}
                onMouseDown={event => { if (event.button === 0) event.preventDefault(); }}
                onClick={() => showPrompts ? closePrompts(true) : setPromptsOpen(true)}>
                <span className="mc-quick-mark" aria-hidden="true">✧</span><span className="mc-quick-label">快捷提示</span>
              </button>}
              <div className="mc-composer-field">
                <textarea
                  ref={inputRef}
                  className={suggestion ? 'mc-input mc-input-with-key-hint' : 'mc-input'}
                  aria-label="建模消息"
                  aria-describedby={suggestion ? suggestionId : undefined}
                  rows={2}
                  value={input}
                  onChange={e => setInput(e.target.value)}
                  onFocus={resizeInput}
                  onKeyDown={handleKeyDown}
                  onCompositionStart={() => { composing.current = true; }}
                  onCompositionEnd={() => { composing.current = false; }}
                  onBlur={() => { composing.current = false; }}
                  placeholder={placeholder}
                  disabled={inputDisabled}
                />
                {suggestion && <div className="mc-composer-inline-hint" aria-hidden="true">
                  {placeholder}<span id={suggestionId}>Tab 填入</span>
                </div>}
              </div>
              <div className="da-chat-composer-bar mc-composer-bar">
                {onAttachDocument && <button type="button" className="mc-chip" style={S.chip} onClick={onAttachDocument} disabled={busy || awaitingDecision}>＋ 添加业务文档（可选）</button>}
                {/* Keep the send target still when a mouse click would change composer focus. */}
                <button type="button" className="da-composer-submit" data-tooltip="发送" aria-label="发送建模消息"
                  onMouseDown={variant === 'dock' ? event => { if (event.button === 0) event.preventDefault(); } : undefined}
                  onClick={handleSend} disabled={inputDisabled || !input.trim()}>
                  <span className="da-composer-submit-face"><Icon name="arrowUp" /></span>
                </button>
              </div>
            </div>
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
    background: 'transparent',
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
    background: 'var(--da-canvas-bg)',
    borderLeft: '1px solid var(--da-border)',
    boxShadow: 'var(--da-shadow-pop, -8px 0 24px rgba(0,0,0,0.12))',
    display: 'flex',
    flexDirection: 'column',
  },
  panelModal: {
    width: 'min(1100px, 86vw)',
    height: 'min(720px, 84vh)',
    background: 'var(--da-canvas-bg)',
    border: '1px solid var(--da-border)',
    borderRadius: 12,
    boxShadow: '0 24px 64px rgba(0,0,0,0.24)',
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden',
  },
  headerHint: {
    flex: 1,
    textAlign: 'right' as const,
    fontSize: 12,
    color: 'var(--da-text-muted)',
    paddingRight: 8,
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '12px 16px',
    borderBottom: '1px solid var(--da-border)',
    background: 'var(--da-surface)',
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
  // Messages keep a comfortable reading width and stay centered on wide screens.
  thread: { flex: 1, overflowY: 'auto', padding: '22px max(20px, calc((100% - 780px) / 2)) 6px', display: 'flex', flexDirection: 'column', gap: 20, maskImage: 'linear-gradient(to bottom, #000 calc(100% - 14px), transparent 100%)', WebkitMaskImage: 'linear-gradient(to bottom, #000 calc(100% - 14px), transparent 100%)' },
  dockThread: { flex: 1, overflowY: 'auto', padding: '14px 12px 4px', display: 'flex', flexDirection: 'column', gap: 15, maskImage: 'linear-gradient(to bottom, #000 calc(100% - 10px), transparent 100%)', WebkitMaskImage: 'linear-gradient(to bottom, #000 calc(100% - 10px), transparent 100%)' },
  welcome: {
    display: 'flex', flexDirection: 'column', gap: 12,
    background: 'var(--da-surface)',
    border: '1px solid color-mix(in srgb, var(--da-primary) 8%, var(--da-border))',
    borderRadius: 16,
    padding: '20px 22px',
    boxShadow: '0 1px 2px rgba(16,24,42,.04), 0 20px 44px -38px rgba(16,24,42,.55)',
  },
  welcomeTitle: {
    fontSize: 15,
    fontWeight: 600,
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
  },
  welcomeDesc: { fontSize: 13, color: 'var(--da-text-3)', lineHeight: 1.7 },
  welcomeStep: { display: 'flex', gap: 10, alignItems: 'flex-start', fontSize: 13, color: 'var(--da-text-2)', lineHeight: 1.6 },
  welcomeExample: { display: 'flex', flexDirection: 'column', gap: 3, width: '100%', textAlign: 'left', padding: '10px 13px', border: '1px solid var(--da-border)', borderRadius: 11, background: 'var(--da-surface)', color: 'var(--da-text-2)', fontSize: 13, lineHeight: 1.55, cursor: 'pointer' },
  welcomeNum: { flex: '0 0 20px', height: 20, borderRadius: 8, background: 'var(--da-primary-subtle)', color: 'var(--da-primary)', fontSize: 12, fontWeight: 600, display: 'inline-flex', alignItems: 'center', justifyContent: 'center' },
  botRow: { display: 'flex', gap: 10, alignItems: 'flex-start' },
  steps: { margin: 0, paddingLeft: 18, fontSize: 12.5, color: 'var(--da-text)', lineHeight: 1.9 },
  quickCol: { display: 'flex', flexDirection: 'column', gap: 8 },
  quickPrimary: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    padding: '10px 12px',
    borderRadius: 8,
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
    borderRadius: 8,
    border: '1px solid var(--da-border)',
    background: 'var(--da-surface)',
    color: 'var(--da-text)',
    cursor: 'pointer',
    fontSize: 13,
    textAlign: 'left',
  },
  quickHint: { fontSize: 12, fontWeight: 400, opacity: 0.75 },
  chip: {
    border: '1px dashed color-mix(in srgb, var(--da-primary) 28%, var(--da-border))',
    background: 'transparent',
    color: 'var(--da-text-3)',
    borderRadius: 999,
    padding: '5px 11px',
    fontSize: 12,
    cursor: 'pointer',
  },
  row: { display: 'flex', flexDirection: 'column' },
  userBubble: {
    alignSelf: 'flex-end', maxWidth: '78%',
    background: 'color-mix(in srgb, var(--da-primary) 7%, var(--da-surface))',
    border: '1px solid color-mix(in srgb, var(--da-primary) 14%, transparent)',
    color: 'var(--da-text)',
    borderRadius: 16,
    borderBottomRightRadius: 6,
    padding: '9px 14px',
    fontSize: 13.5,
    lineHeight: 1.65,
    whiteSpace: 'pre-wrap',
    boxShadow: '0 1px 2px rgba(16,24,42,.03)',
  },
  botBubble: {
    alignSelf: 'flex-start', maxWidth: '100%', width: '100%', boxSizing: 'border-box', minWidth: 0,
    background: 'var(--da-surface)',
    border: '1px solid color-mix(in srgb, var(--da-primary) 8%, var(--da-border))',
    borderRadius: 14,
    padding: '14px 16px',
    fontSize: 13.5,
    lineHeight: 1.72,
    color: 'var(--da-text)',
    boxShadow: '0 1px 2px rgba(16,24,42,.04), 0 12px 30px -26px rgba(16,24,42,.5)',
  },
  tools: { display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 8 },
  task: { display: 'flex', flexDirection: 'column', gap: 0, marginBottom: 2 },
  taskRow: {
    display: 'flex', alignItems: 'center', gap: 8, border: 'none', background: 'transparent',
    padding: '4px 6px', margin: '0 -6px', borderRadius: 7, cursor: 'pointer', textAlign: 'left',
    fontSize: 12, color: 'var(--da-text)',
  },
  taskStatus: { display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11.5, fontWeight: 600, flexShrink: 0, minWidth: 52, opacity: .85 },
  taskState: { fontSize: 12 },
  taskLabelText: { color: 'var(--da-text-2)', fontSize: 12.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  taskDetail: {
    margin: '2px 0 6px 4px',
    padding: '6px 8px',
    background: 'var(--da-surface-sunken)',
    borderRadius: 8,
  },
  taskDetailHead: {
    fontSize: 12,
    color: 'var(--da-text-muted)',
    marginBottom: 2,
    fontWeight: 600,
  },
  taskDetailPre: {
    margin: 0,
    padding: 0,
    fontSize: 12,
    lineHeight: 1.5,
    fontFamily: 'var(--da-font-mono, ui-monospace, monospace)',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
    color: 'var(--da-text)',
    maxHeight: 240,
    overflowY: 'auto',
  },
  composer: { display: 'flex', flexDirection: 'column', gap: 8, padding: '8px max(20px, calc((100% - 780px) / 2)) 14px', background: 'var(--da-canvas-bg)', flexShrink: 0 },
};
